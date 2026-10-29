package dza.fut.paipo;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * v3.2.43 — lo que el usuario guarda y lo que ve en la rejilla de canales. Alimenta los
 * chips ♥ Favoritos y ★ Los más vistos de la tira (como Drama). La persistencia son dos
 * strings en prefs `canales_ui`; la SERIACIÓN es pura y se prueba en la suite.
 *
 * La clave de un canal es su id_live cuando lo hay (estable entre entregas del feed) y
 * el slug del título si no — el mismo truco de los favoritos del cine (v3.2.39).
 */
public final class UsoCanales {

    public static final String PREFS = "canales_ui";
    static final String CLAVE_FAVS = "favoritos_canales";
    static final String CLAVE_VISTAS = "vistas_canales";

    private UsoCanales() { }

    public static String claveDe(Canal canal) {
        if (canal == null) return "";
        if (canal.options != null) {
            for (Canal.OpcionCanal o : canal.options) {
                if (o == null || o.url == null) continue;
                String id = DramaSesion.idDeCanal(o.url);
                if (id != null && !id.isEmpty()) return "i:" + id;
            }
        }
        String s = slug(canal.title);
        return s.isEmpty() ? "" : "s:" + s;
    }

    public static String slug(String titulo) {
        if (titulo == null) return "";
        return titulo.toLowerCase(Locale.ROOT)
                .replaceAll("\\(\\s*\\d{3,4}\\s*p\\s*\\)", " ")
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("-+", "-").replaceAll("^-|-$", "");
    }

    // ───────────────────────── favoritos ─────────────────────────

    public static LinkedHashSet<String> favoritos(Context c) {
        return Favoritos.deserializar(prefs(c).getString(CLAVE_FAVS, ""));
    }

    /** true = acaba de entrar; false = acaba de salir. */
    public static boolean alternarFavorito(Context c, Canal canal) {
        String k = claveDe(canal);
        if (k.isEmpty()) return false;
        LinkedHashSet<String> set = favoritos(c);
        boolean entro = Favoritos.alternar(set, k);
        prefs(c).edit().putString(CLAVE_FAVS, Favoritos.serializar(set)).apply();
        return entro;
    }

    // ───────────────────────── vistas ─────────────────────────

    public static Map<String, Integer> vistas(Context c) {
        return deserializarVistas(prefs(c).getString(CLAVE_VISTAS, ""));
    }

    /** Se llama al abrir un canal desde la rejilla (el contador del chip ★). */
    public static void registrarApertura(Context c, Canal canal) {
        String k = claveDe(canal);
        if (k.isEmpty()) return;
        Map<String, Integer> m = vistas(c);
        Integer antes = m.get(k);
        m.put(k, antes == null ? 1 : antes + 1);
        prefs(c).edit().putString(CLAVE_VISTAS, serializarVistas(m)).apply();
    }

    /** Las N claves más vistas (descendente por contador). */
    public static List<String> topVistas(Map<String, Integer> vistas, int n) {
        List<Map.Entry<String, Integer>> es = new ArrayList<>(vistas.entrySet());
        es.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < es.size() && i < n; i++) out.add(es.get(i).getKey());
        return out;
    }

    static String serializarVistas(Map<String, Integer> m) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(e.getKey()).append('\t').append(e.getValue());
        }
        return sb.toString();
    }

    static LinkedHashMap<String, Integer> deserializarVistas(String texto) {
        LinkedHashMap<String, Integer> m = new LinkedHashMap<>();
        if (texto == null || texto.isEmpty()) return m;
        for (String l : texto.split("\n")) {
            int t = l.indexOf('\t');
            if (t <= 0) continue;
            try { m.put(l.substring(0, t), Integer.parseInt(l.substring(t + 1).trim())); }
            catch (NumberFormatException ignore) { }
        }
        return m;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
