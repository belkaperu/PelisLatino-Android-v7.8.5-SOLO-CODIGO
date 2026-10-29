package dza.fut.paipo;

import java.util.Collection;
import java.util.LinkedHashSet;

/**
 * v3.2.39 — la clase de los «guardar para ver luego». Pura a proposito: la clave,
 * el alterneo y la serializacion se prueban aqui sin Android; las prefs las mueve
 * el fragmento (clave "favoritos" en pelis_ui) y PelisApi.FAVORITOS guarda el set
 * vivo que filtra el catalogo.
 */
public final class Favoritos {
    private Favoritos() { }

    /** La clave del item: el slug si lo hay (estable entre descargas), el id si no. */
    public static String clave(String slug, int id) {
        return slug != null && !slug.isEmpty() ? "s:" + slug : "i:" + id;
    }

    /** true = la clave acabo de entrar; false = acabo de salir. */
    public static boolean alternar(java.util.Set<String> set, String clave) {
        if (set.contains(clave)) {
            set.remove(clave);
            return false;
        }
        set.add(clave);
        return true;
    }

    public static String serializar(Collection<String> claves) {
        StringBuilder sb = new StringBuilder();
        for (String c : claves) {
            if (c == null || c.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(c);
        }
        return sb.toString();
    }

    public static LinkedHashSet<String> deserializar(String texto) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (texto == null || texto.isEmpty()) return out;
        for (String linea : texto.split("\n")) {
            if (!linea.trim().isEmpty()) out.add(linea.trim());
        }
        return out;
    }
}
