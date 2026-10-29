package dza.fut.paipo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser mínimo de listas M3U / M3U8 extendidas.
 *
 * Se usa cuando la fuente configurada por el usuario es una lista de reproducción
 * en vez del JSON propio de la app. Un M3U es, en la práctica:
 *
 *   #EXTM3U
 *   #EXTINF:-1 tvg-logo="https://…/logo.png" group-title="Fútbol",Directv Sports
 *   https://…/directv.m3u8
 *
 * Dos decisiones que imitan a DramaLive:
 *   1) el {@code group-title} alimenta los tópicos (las pestañas de ícono + rótulo);
 *   2) si el mismo nombre aparece varias veces con URLs distintas, NO se duplica el
 *      tile: se convierte en varias {@code options} del mismo canal, que es lo que
 *      allí se ve como "Servers 1 / 2 / 3".
 */
public final class M3uParser {

    private static final Pattern P_LOGO = Pattern.compile("tvg-logo=\"([^\"]*)\"");
    private static final Pattern P_GROUP = Pattern.compile("group-title=\"([^\"]*)\"");
    private static final Pattern P_TVG_NAME = Pattern.compile("tvg-name=\"([^\"]*)\"");
    private static final Pattern P_TVG_ID = Pattern.compile("tvg-id=\"([^\"]*)\"");
    /**
     * Basura de nombre: hay entradas de índices públicos cuyo ",<nombre>" viene ocupado por
     * un User-Agent o por una URL (se ve en iptv-org: "like Gecko) Chrome/130.0.0.0
     * Safari/537.36"). Pintar eso en el tile es peor que no pintarlo.
     */
    private static final Pattern P_BASURA = Pattern.compile(
            "mozilla/|like gecko|chrome/|safari/|applewebkit|windows nt|linux x86|https?://|\\.m3u8|\\.php\\?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern P_NAME = Pattern.compile(",([^,]*)$");
    /** iptv-org y compañía escriben la calidad al final del nombre: "36 TV Huacho (720p)". */
    private static final Pattern P_CALIDAD =
            Pattern.compile("\\s*\\((\\d{3,4}p|HD|FHD|SD|4K|8K)\\)\\s*$", Pattern.CASE_INSENSITIVE);

    private M3uParser() {}

    /** Dice si un cuerpo de respuesta parece M3U (antes de intentar parsearlo). */
    public static boolean pareceM3u(String cuerpo) {
        if (cuerpo == null) return false;
        String head = cuerpo.substring(0, Math.min(cuerpo.length(), 64)).trim();
        return head.startsWith("#EXTM3U") || head.startsWith("#EXTINF");
    }

    public static List<Canal> parsear(String texto) {
        List<Canal> salida = new ArrayList<>();
        if (texto == null || texto.trim().isEmpty()) return salida;

        Map<String, Canal> porNombre = new LinkedHashMap<>();
        Canal pendiente = null;

        for (String lineaOriginal : texto.split("\\r?\\n")) {
            String linea = lineaOriginal.trim();
            if (linea.isEmpty()) continue;

            if (linea.startsWith("#EXTINF")) {
                pendiente = canalDesdeExtinf(linea);
                continue;
            }
            if (linea.startsWith("#")) continue;   // extvlive, extgrp, comentarios…

            String url = linea;
            // v3.2.5: los canales EXTRAIDOS del captador viven en el m3u con esquema
            // dramalive:// (el item del sitio trae url:"1"; la url real se resuelve al
            // reproducir via glbr). Filtrarla aqui era el «dice 9002 y no veo ninguno».
            if (!url.startsWith("http") && !url.startsWith("rtmp") && !url.startsWith("rtp")
                    && !url.startsWith("dramalive://")) continue;

            Canal canal = pendiente;
            if (canal == null) {
                // Lista sin #EXTINF: se usa la última ruta del enlace como nombre.
                // Ojo: options se inicializa aquí, si no el add() de abajo revienta
                // (es el caso de las M3U peladas sin cabecera #EXTINF).
                canal = new Canal();
                canal.title = nombreDesdeUrl(url);
                canal.category = "";
                canal.options = new ArrayList<>();
            }
            pendiente = null;

            Canal.OpcionCanal opcion = new Canal.OpcionCanal();
            opcion.url = url;
            // Si el #EXTINF traía "(1080p)", esa es la etiqueta del servidor.
            opcion.label = canal.calidad == null ? "" : canal.calidad;

            Canal previo = porNombre.get(canal.title.toLowerCase(Locale.ROOT));
            if (previo != null) {
                previo.options.add(opcion);
            } else {
                canal.options.add(opcion);
                porNombre.put(canal.title.toLowerCase(Locale.ROOT), canal);
                salida.add(canal);
            }
        }

        etiquetarOpciones(salida);
        return salida;
    }

    /** true si el texto sirve como nombre de canal (no está vacío y no es UA/URL). */
    static boolean esNombreUsable(String candidato) {
        if (candidato == null) return false;
        String v = candidato.trim();
        return !v.isEmpty() && !P_BASURA.matcher(v).find();
    }

    private static Canal canalDesdeExtinf(String linea) {
        Canal canal = new Canal();
        canal.options = new ArrayList<>();

        String nombre = captura(P_NAME, linea);
        if (!esNombreUsable(nombre)) {
            // El rótulo del tile se rescata de tvg-name y, si tampoco sirve, de tvg-id.
            String alterno = captura(P_TVG_NAME, linea);
            if (!esNombreUsable(alterno)) alterno = captura(P_TVG_ID, linea);
            nombre = esNombreUsable(alterno) ? alterno : "";
        }
        nombre = nombre == null ? "" : nombre.trim();

        // La calidad NO va en el rótulo del tile (echa a perder el nombre y repite el
        // canal como si fueran dos): se guarda como etiqueta del servidor.
        java.util.regex.Matcher q = P_CALIDAD.matcher(nombre);
        if (q.find()) {
            canal.calidad = q.group(1);
            nombre = nombre.substring(0, q.start()).trim();
        }
        if (nombre.isEmpty()) nombre = "Canal";
        canal.title = nombre;

        String logo = captura(P_LOGO, linea);
        canal.image = logo == null ? "" : logo.trim();

        canal.category = primerGrupo(captura(P_GROUP, linea));
        return canal;
    }

    /**
     * iptv-org admite varios grupos por línea ({@code group-title="Outdoor;Sports"}) y
     * rellena con "Undefined" lo que no está clasificado. Para las pestañas solo
     * interesa el primer grupo real: con los compuestos saldrían decenas de tabs
     * inútiles ("Kids;Sports", "News;Sports"…).
     */
    static String primerGrupo(String grupo) {
        if (grupo == null) return "";
        for (String parte : grupo.split(";")) {
            String p = parte.trim();
            if (p.isEmpty() || p.equalsIgnoreCase("Undefined") || p.equalsIgnoreCase("Other")) continue;
            return p;
        }
        return "";
    }

    private static String captura(Pattern patron, String texto) {
        Matcher m = patron.matcher(texto);
        return m.find() ? m.group(1) : null;
    }

    private static String nombreDesdeUrl(String url) {
        String sinQuery = url.split("\\?")[0];
        int barra = sinQuery.lastIndexOf('/');
        String nombre = barra >= 0 ? sinQuery.substring(barra + 1) : sinQuery;
        int punto = nombre.lastIndexOf('.');
        if (punto > 0) nombre = nombre.substring(0, punto);
        return nombre.isEmpty() ? "Canal" : nombre.replace('-', ' ').replace('_', ' ');
    }

    /**
     * Etiquetas legibles para los servidores: "1080p", "SD" o "Servidor 2" cuando la
     * lista no dice nada. Se numeran para que dos calidades iguales no se llamen igual.
     */
    private static void etiquetarOpciones(List<Canal> canales) {
        for (Canal canal : canales) {
            if (canal.options == null || canal.options.isEmpty()) continue;
            for (int i = 0; i < canal.options.size(); i++) {
                Canal.OpcionCanal opcion = canal.options.get(i);
                String base = opcion.label == null ? "" : opcion.label.trim();
                if (base.isEmpty()) base = canal.options.size() > 1 ? "Servidor " + (i + 1) : "Ver";
                if (canal.options.size() > 1) {
                    for (int j = 0; j < i; j++) {
                        if (base.equals(canal.options.get(j).label)) {
                            base = base + " " + (i + 1);
                            break;
                        }
                    }
                }
                opcion.label = base;
            }
        }
    }
}
