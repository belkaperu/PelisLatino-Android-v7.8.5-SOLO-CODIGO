package dza.fut.paipo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Aplica el "swap" de los paneles de Drama al playlist HLS ya descifrado.
 *
 * Medido 2026-10-08 contra el panel real (`fas4.faststorageapi.shop`): el JSON
 * del enlace trae `"swap":{"k9x_":""}` y el playlist lista segmentos llamados
 * `k9x_1791436683-1791437198.js`. Con ese nombre LITERAL los segmentos no son
 * los que el reproductor debe pedir: el player.js del host les quita el prefijo
 * (crudo 404 vs post-swap 200, medido tambien en el demo local `drama-live.py`).
 * Como ExoPlayer recarga el playlist en cada ventana de live, la reescritura
 * tiene que vivir en el DataSource ({@link DramaSwapDataSource}), no "un solo
 * reemplazo" tras descargarlo: aqui esta la logica pura, sin Android, para que
 * la suite de unidad la pruebe contra el fixture medido.
 */
public final class SwapHls {

    private SwapHls() {}

    /** Prefijo de segmento de estos paneles: letras/digitos terminando en '_' y seguido de numero. */
    static final Pattern PREFIJO = Pattern.compile("^([A-Za-z][0-9A-Za-z]*_)(?=\\d)");

    /**
     * true si el cuerpo (ya en claro) parece un playlist m3u8: arranca con
     * `#EXTM3U` tolerando BOM y basura por delante (los paneles a veces ponen
     * lineas en blanco antes, y el index.html ese viene como text/html).
     */
    public static boolean esPlaylist(@Nullable byte[] cuerpo) {
        if (cuerpo == null) return false;
        int i = 0;
        if (cuerpo.length >= 3 && (cuerpo[0] & 0xFF) == 0xEF
                && (cuerpo[1] & 0xFF) == 0xBB && (cuerpo[2] & 0xFF) == 0xBF) i = 3;
        while (i < cuerpo.length && (cuerpo[i] == ' ' || cuerpo[i] == '\n'
                || cuerpo[i] == '\r' || cuerpo[i] == '\t')) i++;
        if (cuerpo.length - i < 7) return false;
        return cuerpo[i] == '#' && cuerpo[i + 1] == 'E' && cuerpo[i + 2] == 'X'
                && cuerpo[i + 3] == 'T' && cuerpo[i + 4] == 'M' && cuerpo[i + 5] == '3'
                && cuerpo[i + 6] == 'U';
    }

    /**
     * Reescribe el playlist: para cada linea-URI, el primer par `(de, a)` cuyo
     * `de` sea prefijo reemplaza esa parte. Con `swaps` vacio y `auto`, detecta
     * el prefijo comun tipo `k9x_` (misma regla que `drama-live.py probar`).
     * Las lineas de comentario (`#EXT*`) y vacias viajan intactas.
     */
    @NonNull
    public static String aplicar(@NonNull String playlist, @Nullable List<String[]> swaps, boolean auto) {
        List<String> lineas = new ArrayList<>();
        for (String l : splitLines(playlist)) lineas.add(l);

        List<String[]> pares = new ArrayList<>();
        if (swaps != null) for (String[] p : swaps) if (p != null && p.length == 2 && !p[0].isEmpty()) pares.add(p);
        if (pares.isEmpty() && auto) {
            String pref = prefijoComun(lineas);
            if (pref != null) pares.add(new String[]{pref, ""});
        }
        if (pares.isEmpty()) return playlist;

        for (int i = 0; i < lineas.size(); i++) {
            String l = lineas.get(i);
            if (l.isEmpty() || l.charAt(0) == '#') continue;
            for (String[] par : pares) {
                if (l.startsWith(par[0])) {
                    lineas.set(i, par[1] + l.substring(par[0].length()));
                    break;
                }
            }
        }
        String unido = String.join("\n", lineas);
        // preserva el salto final del original (los parsers lo toleran, pero la
        // reescritura debe ser byte-identica salvo los nombres de segmento)
        return playlist.endsWith("\n") ? unido + "\n" : unido;
    }

    /** El prefijo tipo `k9x_` si TODAS las lineas-URI lo comparten; null si no hay unanimidad. */
    @Nullable
    static String prefijoComun(@NonNull List<String> lineas) {
        String comun = null;
        boolean hay = false;
        for (String l : lineas) {
            if (l.isEmpty() || l.charAt(0) == '#') continue;
            Matcher m = PREFIJO.matcher(l);
            if (!m.find()) return null;
            String p = m.group(1);
            if (!hay) { comun = p; hay = true; }
            else if (!comun.equals(p)) return null;
        }
        return hay ? comun : null;
    }

    /** split conservando CR al final de cada linea (algunos paneles sirven CRLF). */
    @NonNull
    static List<String> splitLines(@NonNull String s) {
        List<String> out = new ArrayList<>();
        int ini = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                int fin = i;
                if (fin > ini && s.charAt(fin - 1) == '\r') fin--;
                out.add(s.substring(ini, fin));
                ini = i + 1;
            }
        }
        if (ini < s.length()) out.add(s.substring(ini));
        return out;
    }
}
