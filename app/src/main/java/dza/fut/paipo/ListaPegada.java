package dza.fut.paipo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Listas y URLs que el usuario PEGA en la app, en vez de descargarlas.
 *
 * <p>Sirve para los casos en que la lista vive detrás de un portal que no la expone
 * (el cliente web de DramaLive cifra sus peticiones con un AES del propio bundle, así que
 * desde fuera no se le puede pedir nada) pero el navegador del usuario YA la tiene en
 * pantalla: se copia el texto, se pega aquí y la app lo usa como fuente. Acepta tres formas:
 *
 * <ol>
 *   <li>un M3U/M3U8 completo (empieza por {@code #EXTM3U} o contiene {@code #EXTINF});</li>
 *   <li>el JSON de canales que usaba la app (objeto o array);</li>
 *   <li><b>una sola URL de stream</b> ({@code https://…/index.m3u8}, {@code …/10764.ts}),
 *       que se convierte en un canal con nombre deducido de la propia ruta.</li>
 * </ol>
 */
public final class ListaPegada {

    private ListaPegada() {}

    /** null si el texto no se parece a nada utilizable; si no, la lista ya parseada. */
    @Nullable
    public static List<Canal> desdeTexto(@Nullable String texto) {
        return desdeTexto(texto, null);
    }

    /** Con la `webPlayerSecret` de la fuente: los enlaces cifrados entran ya desenrollados. */
    @Nullable
    public static List<Canal> desdeTexto(@Nullable String texto, @Nullable String claveDrama) {
        if (texto == null) return null;
        String t = texto.replace("\r\n", "\n").trim();
        if (t.isEmpty()) return null;

        // 1) M3U: puede venir tal cual o con alguna línea basura antes del #EXTM3U.
        int extinf = t.indexOf("#EXTINF");
        if (t.startsWith("#EXTM3U") || (extinf >= 0 && extinf < 4000)) {
            int inicio = t.startsWith("#EXTM3U") ? 0 : extinf;
            List<Canal> canales = M3uParser.parsear(t.substring(inicio));
            return canales.isEmpty() ? null : canales;
        }

        // 2) JSON (el formato histórico de la app, o una respuesta {…} del portal).
        if (t.startsWith("{") || t.startsWith("[")) {
            List<Canal> canales = FuenteCanales.parsearCuerpo(t);
            return canales.isEmpty() ? null : canales;
        }

        // 3) Una o varias URLs sueltas, una por línea: cada una es un canal.
        List<Canal> salida = new ArrayList<>();
        for (String linea : t.split("\n")) {
            String url = linea.trim();
            if (url.isEmpty() || url.startsWith("#") || url.startsWith("//")) continue;
            if (!url.regionMatches(true, 0, "http", 0, 4)) continue;
            // "Un canal desde Drama": los enlaces que comparten esos portales son
            // `player.html#<blob Salted__>`. El blob es AES-256-CBC con clave publica
            // (ver EnlaceDrama); adentro esta la URL real y el titulo. Se desenrolla
            // aqui, al importar, para que el tile muestre el nombre y no el hash.
            EnlaceDrama.Contenido drama = EnlaceDrama.abrir(url, claveDrama);
            String tituloPegado = null;
            if (drama != null) { url = drama.url; tituloPegado = drama.titulo; }
            Canal canal = new Canal();
            canal.title = tituloPegado != null ? tituloPegado : nombreDesdeUrl(url);
            canal.category = "Pegado";
            canal.calidad = null;
            canal.image = null;
            Canal.OpcionCanal opcion = new Canal.OpcionCanal();
            opcion.label = "Directo";
            opcion.url = url;
            canal.options = new ArrayList<>();
            canal.options.add(opcion);
            salida.add(canal);
        }
        return salida.isEmpty() ? null : salida;
    }

    /**
     * `https://cdn.tv/live/ESPN/index.m3u8` -> "ESPN". Los nombres de archivo que usan los
     * reproductores (index/master/stream/play) no dicen nada, así que se sube un nivel.
     */
    @NonNull
    public static String nombreDesdeUrl(@NonNull String url) {
        String s = url.trim();
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        if (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        int trasEsclavos = s.indexOf("//");
        trasEsclavos = trasEsclavos < 0 ? 0 : trasEsclavos + 2;
        int barraRuta = s.indexOf('/', trasEsclavos);
        String host = barraRuta < 0 ? s.substring(trasEsclavos) : s.substring(trasEsclavos, barraRuta);
        String ruta = barraRuta < 0 ? "" : s.substring(barraRuta + 1);
        if (ruta.isEmpty()) return capitalizar(host);   // solo el host: es lo único que dice algo

        String[] seg = ruta.split("/");
        for (int i = seg.length - 1; i >= 0; i--) {
            String sinExt = quitarExtension(seg[i].trim());
            if (!sinExt.isEmpty() && !esGenerico(sinExt)) return capitalizar(sinExt);
        }
        return capitalizar(host);   // todo era index/master/live: mejor el host que un nombre tonto
    }

    /** `.m3u8`, `.ts`, `.mp4` fuera; un host (`cdn.tv`) NO se recorta. */
    @NonNull
    private static String quitarExtension(@NonNull String segmento) {
        int punto = segmento.lastIndexOf('.');
        if (punto <= 0 || punto == segmento.length() - 1) return segmento;
        String ext = segmento.substring(punto + 1).toLowerCase(Locale.ROOT);
        if (ext.length() > 5 || ext.indexOf('.') >= 0) return segmento;   // "mi-panel.net"
        switch (ext) {
            case "m3u8": case "m3u": case "ts": case "mp4": case "mkv": case "flv":
            case "m4s": case "mpd": case "m2ts": case "mp3": case "aac":
                return segmento.substring(0, punto);
            default:
                return segmento;
        }
    }

    @NonNull
    private static String capitalizar(@NonNull String crudo) {
        String v = crudo.replace('_', ' ').trim();
        if (v.isEmpty()) return "Canal";
        v = Character.toUpperCase(v.charAt(0)) + v.substring(1);
        return v.length() > 48 ? v.substring(0, 47) + "…" : v;
    }

    private static boolean esGenerico(@NonNull String nombre) {
        String n = nombre.toLowerCase(Locale.ROOT);
        switch (n) {
            case "index": case "master": case "playlist": case "stream": case "play":
            case "video": case "live": case "index.m3u8": case "chunklist": case "playlist.m3u8":
                return true;
            default:
                return n.contains("m3u8") || n.equals("v");
        }
    }

    /** Para la píldora de estado: cuántas cosas se van a poder ver. */
    public static int contar(@Nullable String texto) {
        List<Canal> c = desdeTexto(texto);
        return c == null ? 0 : c.size();
    }
}
