package dza.fut.paipo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cabeceras HTTP que el usuario pide mandar al abrir un canal o al leer la fuente.
 *
 * Formato (una por línea, en el campo "Encabezados al abrir" de la fuente):
 *
 *   Referer: https://mi-proveedor.net/
 *   Cookie = sess=abc123; t=1
 *   {"Referer":"https://mi-proveedor.net/","User-Agent":"Reproductor/1.0"}
 *   @cdn.proveedor.net User-Agent: Reproductor/1.0        <- sólo a ese host o sus subdominios
 *   # los que empiezan por # o // se ignoran
 *
 * Por qué hace falta: muchos CDNs de portales no dejan pasar la URL sola — responden
 * 403 si no ven un `Referer` de su propio dominio, o piden la `Cookie` de la sesión.
 * Es la diferencia entre "en el navegador del proveedor va" y "en un reproductor
 * limpio no". Las copias del propio player de Drama, por cierto, no sirven de nada:
 * ese fichero no manda ninguna cabecera (medido: `headers` aparece 0 veces en su
 * player.js), sólo abre la URL ya resuelta.
 *
 * Se descartan las cabeceras que un cliente no debe fijar (Host, Content-Length,
 * Transfer-Encoding, Connection, Upgrade, Accept-Encoding): OkHttp y ExoPlayer las
 * calculan y aceptarlas del usuario produce errores imposibles de diagnosticar. Lo que
 * se descartó se puede enseñar con {@link #descartadas()}.
 */
public final class Encabezados {

    /** Cabeceras que el stack HTTP pone él mismo; si el usuario las fija, rompe. */
    private static final Pattern RESTRINGIDAS = Pattern.compile(
            "^(accept-encoding|content-length|transfer-encoding|connection|upgrade|host|"
                    + "keep-alive|te|trailer|proxy-connection)$");

    private static final Pattern NOMBRE = Pattern.compile("^[A-Za-z0-9!#$%&'*+.^_`|~-]+$");

    /** `@host.com` al principio de la línea: la cabecera sólo se manda a ese host. */
    private static final Pattern P_SCOPE = Pattern.compile("^@([\\w.\\-]+)\\s+(.*)$");

    /** `Nombre: valor` o `Nombre = valor`. */
    private static final Pattern P_LINEA = Pattern.compile("^\\s*([^:=]{1,60}?)\\s*[:=]\\s*(.*)$");

    @NonNull
    private final Map<String, String> globales;
    @NonNull
    private final Map<String, Map<String, String>> porHost;
    @NonNull
    private final List<String> descartadas;
    @NonNull
    private final String bruto;

    private Encabezados(@NonNull Map<String, String> globales,
                        @NonNull Map<String, Map<String, String>> porHost,
                        @NonNull List<String> descartadas, @NonNull String bruto) {
        this.globales = globales;
        this.porHost = porHost;
        this.descartadas = descartadas;
        this.bruto = bruto;
    }

    public static final Encabezados NINGUNO =
            new Encabezados(Collections.<String, String>emptyMap(),
                    Collections.<String, Map<String, String>>emptyMap(),
                    Collections.<String>emptyList(), "");

    /** Analiza el texto del usuario. Nunca lanza: lo que no se entiende, se ignora. */
    @NonNull
    public static Encabezados fromTexto(@Nullable String texto) {
        if (texto == null || texto.trim().isEmpty()) return NINGUNO;
        Map<String, String> globales = new LinkedHashMap<>();
        Map<String, Map<String, String>> porHost = new LinkedHashMap<>();
        List<String> descartadas = new ArrayList<>();

        for (String cruda : texto.split("\\r?\\n")) {
            String linea = cruda.trim();
            if (linea.isEmpty() || linea.startsWith("#") || linea.startsWith("//")) continue;

            String alcance = null;
            Matcher sc = P_SCOPE.matcher(linea);
            if (sc.matches()) {
                alcance = sc.group(1).toLowerCase(Locale.US);
                linea = sc.group(2).trim();
            }

            for (String par : descomponer(linea)) {
                Matcher m = P_LINEA.matcher(par);
                if (!m.matches()) continue;
                String nombre = m.group(1).trim();
                String valor = m.group(2).trim();
                // comas de relleno entre cabeceras pegadas del JSON ("Referer: x", "Cookie: y")
                if (nombre.indexOf('"') >= 0) nombre = nombre.replace("\"", "");
                if (nombre.startsWith("\"") || valor.startsWith("\"")) {
                    nombre = sinComillas(nombre);
                    valor = sinComillas(valor);
                }
                if (nombre.isEmpty() || valor.isEmpty() || !NOMBRE.matcher(nombre).matches()) continue;
                String canon = canonico(nombre);
                if (canon == null) {
                    descartadas.add(nombre);
                    continue;
                }
                if (alcance == null) {
                    globales.put(canon, valor);                    // la última definición gana
                } else {
                    Map<String, String> mapa = porHost.get(alcance);
                    if (mapa == null) {
                        mapa = new LinkedHashMap<>();
                        porHost.put(alcance, mapa);
                    }
                    mapa.put(canon, valor);
                }
            }
        }
        return new Encabezados(globales, porHost, descartadas, texto.trim());
    }

    /**
     * Acepta también un objeto JSON pegado tal cual desde las herramientas de red:
     * `{"Referer":"...","Cookie":"..."}`. Se trata como pares clave:valor separados por comas.
     */
    @NonNull
    private static List<String> descomponer(@NonNull String linea) {
        List<String> salida = new ArrayList<>();
        String t = linea.trim();
        if (t.startsWith("{") && t.endsWith("}")) {
            t = t.substring(1, t.length() - 1).trim();
            // se parte en comas que estan FUERA de comillas
            StringBuilder actual = new StringBuilder();
            boolean dentro = false;
            for (int i = 0; i < t.length(); i++) {
                char c = t.charAt(i);
                if (c == '"') dentro = !dentro;
                if (c == ',' && !dentro) {
                    salida.add(actual.toString());
                    actual.setLength(0);
                } else {
                    actual.append(c);
                }
            }
            salida.add(actual.toString());
            return salida;
        }
        salida.add(linea);
        return salida;
    }

    @NonNull
    private static String sinComillas(@NonNull String s) {
        String t = s.trim();
        while (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            t = t.substring(1, t.length() - 1);
        }
        return t.replace("\\\"", "\"").replace("\\\\", "\\").trim();
    }

    /** `user-agent` -> `User-Agent`; devuelve null si es una cabecera restringida. */
    @Nullable
    private static String canonico(@NonNull String nombre) {
        String minus = nombre.toLowerCase(Locale.US);
        if (RESTRINGIDAS.matcher(minus).matches()) return null;
        String[] partes = minus.split("-");
        StringBuilder sb = new StringBuilder();
        for (String p : partes) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append('-');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }

    public boolean estaVacio() {
        return globales.isEmpty() && porHost.isEmpty();
    }

    /** Cabeceras aplicables a `url`: las globales más las del host (o sus dominios padre). */
    @NonNull
    public Map<String, String> para(@Nullable String url) {
        Map<String, String> salida = new LinkedHashMap<>(globales);
        if (url != null && !porHost.isEmpty()) {
            String host = hostDe(url);
            if (host != null) {
                for (Map.Entry<String, Map<String, String>> e : porHost.entrySet()) {
                    if (host.equals(e.getKey()) || host.endsWith("." + e.getKey())) {
                        salida.putAll(e.getValue());
                    }
                }
            }
        }
        return salida;
    }

    @Nullable
    private static String hostDe(@NonNull String url) {
        try {
            String h = java.net.URI.create(url).getHost();
            return h == null ? null : h.toLowerCase(Locale.US);
        } catch (Exception ignorado) {
            return null;
        }
    }

    /** Cabeceras que se pidieron pero se tiraron por restringidas, para decírselo al usuario. */
    @NonNull
    public List<String> descartadas() {
        return Collections.unmodifiableList(descartadas);
    }

    /** Nombres que se van a mandar, para el resumen del diálogo. */
    @NonNull
    public String resumen() {
        List<String> nombres = new ArrayList<>();
        for (String k : globales.keySet()) nombres.add(k);
        for (Map.Entry<String, Map<String, String>> e : porHost.entrySet()) {
            for (String k : e.getValue().keySet()) nombres.add("@" + e.getKey() + " " + k);
        }
        return TextJoiner.unir(nombres, ", ");
    }

    @NonNull
    public String bruto() {
        return bruto;
    }

    public int cuantas() {
        int n = globales.size();
        for (Map<String, String> m : porHost.values()) n += m.size();
        return n;
    }

    /** Utilitario de unión (evita depender de StringUtils en el test). */
    private static final class TextJoiner {
        @NonNull
        static String unir(@NonNull List<String> partes, @NonNull String sep) {
            StringBuilder sb = new StringBuilder();
            for (String p : partes) {
                if (sb.length() > 0) sb.append(sep);
                sb.append(p);
            }
            return sb.toString();
        }
    }

    /**
     * true si el texto trae algo que parezca cabeceras. Sirve para no cambiar de
     * campo por error: si pegas aquí un playlist o una URL, esto dice "no" y se
     * puede avisar. Una URL suelta (`https://x.net/a.m3u8`) NO parece cabecera
     * aunque traiga dos puntos, y `#EXTM3U` tampoco.
     */
    public static boolean pareceCabeceras(@Nullable String texto) {
        if (texto == null) return false;
        String t = texto.trim();
        if (t.isEmpty()) return false;
        if (t.startsWith("{")) return true;
        String primera = t.split("\\r?\\n", 2)[0].trim();
        if (primera.startsWith("#EXT") || primera.startsWith("http://") || primera.startsWith("https://")) {
            return false;
        }
        return P_LINEA.matcher(primera).matches();
    }
}
