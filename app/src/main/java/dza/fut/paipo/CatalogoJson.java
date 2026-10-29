package dza.fut.paipo;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v7.7 — EL CATÁLOGO EMPAQUETADO EN EL APK.
 *
 * <p><b>Qué sustituye.</b> La app abría pidiendo los 265 tópicos de Drama por red para
 * tener la rejilla: medido, <b>21 segundos</b> y 265 peticiones, más el gasto de datos y
 * batería. Con el catálogo dentro del APK la rejilla aparece <b>al instante y sin una
 * sola petición</b>; la extracción en vivo sigue en segundo plano y, cuando acaba,
 * refresca el disco (datos más frescos).
 *
 * <p><b>Formato.</b> El de {@code belkaperu/belkafut/data2.json}, con nuestra fuente:
 * <pre>
 * {"categories":[{"display_name":"Peru","icon":"🇵🇪","name":"pe","count":171,
 *                "file":"cat/pe.json"}],
 *  "channels":[{"category":"Peru","imageUrl":"…","name":"…",
 *               "normalized_name":"…",
 *               "servers":[{"name":"Drama",
 *                           "url":"dramalive://play?id_live=…&u=1&a=all_streams_redirect"}]}]}
 * </pre>
 * {@code servers[].url} es un {@code dramalive://}, que es lo que
 * {@link PlayerActivity} ya sabe resolver: el JSON no cambia el reproductor.
 *
 * <p><b>Por qué un m3u y no una lista de {@link Canal}.</b> Todo lo que viene detrás
 * (fusión, filtro, rejilla, tira de tópicos) ya come m3u. Si el cargador devuelve m3u
 * con el mismo {@code #EXTINF} de siempre, no hay que tocar nada más.
 */
public final class CatalogoJson {

    private CatalogoJson() {}

    private static final String DIR = "catalogo/";
    private static final String INDICE = DIR + "indice.json";

    private static final Object CERROJO = new Object();
    /** El m3u se calcula una sola vez por proceso (son ~2 MB de texto). */
    private static volatile String cache;

    /** ¿Viene el catálogo dentro del APK? */
    public static boolean disponible(@NonNull Context ctx) {
        try {
            String[] raiz = ctx.getAssets().list("catalogo");
            return raiz != null && raiz.length > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** @return el m3u del catálogo empaquetado, o "" si no hay nada utilizable. */
    @NonNull
    public static String aM3u(@NonNull Context ctx) {
        String c = cache;
        if (c != null) return c;
        synchronized (CERROJO) {
            if (cache != null) return cache;
            String indice = leerAsset(ctx, INDICE);
            Map<String, String> ficheros = new LinkedHashMap<>();
            if (indice != null) {
                try {
                    JsonObject j = JsonParser.parseString(indice).getAsJsonObject();
                    JsonArray cats = j.getAsJsonArray("categories");
                    if (cats != null) {
                        for (JsonElement e : cats) {
                            String f = texto(e.getAsJsonObject(), "file");
                            if (f == null || f.isEmpty()) continue;
                            String t = leerAsset(ctx, DIR + f);
                            if (t != null) ficheros.put(f, t);
                        }
                    }
                } catch (Exception ignore) { }
            }
            String out = indice == null ? "" : aM3u(indice, ficheros);
            cache = out == null ? "" : out;
            return cache;
        }
    }

    /** Tira la copia en memoria (por si se recalienta el catálogo). */
    public static void invalidar() {
        synchronized (CERROJO) { cache = null; }
    }

    // ─────────────────────────── puro (testeable) ───────────────────────────

    /**
     * Convierte el índice + los ficheros por categoría en el m3u que la app ya come.
     * Puro: sin Android ni red.
     *
     * @param indiceJson texto de {@code indice.json}
     * @param ficheros   {@code ruta del fichero -> texto} (las de {@code categories[].file})
     * @return m3u, o "" si no salió ni un canal
     */
    @NonNull
    public static String aM3u(@NonNull String indiceJson,
                              @NonNull Map<String, String> ficheros) {
        StringBuilder sb = new StringBuilder("#EXTM3U\n");   // la cabecera manda
        int n = 0;
        if (!indiceJson.isEmpty()) {
            try {
                JsonObject j = JsonParser.parseString(indiceJson).getAsJsonObject();
                JsonArray cats = j.getAsJsonArray("categories");
                if (cats != null) {
                    for (JsonElement ce : cats) {
                        JsonObject co = ce.getAsJsonObject();
                        String grupo = texto(co, "display_name");
                        if (grupo == null || grupo.isEmpty()) grupo = texto(co, "name");
                        String fich = texto(co, "file");
                        if (fich == null || fich.isEmpty()) continue;
                        String cuerpo = ficheros.get(fich);
                        if (cuerpo == null || cuerpo.isEmpty()) continue;
                        n += emitir(sb, cuerpo, grupo);
                    }
                }
            } catch (Exception ignore) { }
        }
        return n == 0 ? "" : sb.toString();
    }

    /**
     * v7.8 — el catálogo en UN fichero (el que se sirve desde GitHub:
     * {@code canales-compacto.json}) convertido al m3u que la app ya come.
     *
     * <p>Es el mismo esquema que el empaquetado, pero con {@code categories[]} y
     * {@code channels[]} juntos en un solo JSON, y {@code channels[].category}
     * llevando el {@code display_name} de su grupo en vez de un {@code file}.
     * Los canales salen agrupados y en el orden del índice, para que la rejilla
     * se lea igual que la de assets.
     *
     * <p>Puro: sin Android ni red.
     *
     * @return m3u, o "" si no salió ni un canal (o el texto no es JSON válido)
     */
    @NonNull
    public static String aM3uUnico(@NonNull String json) {
        if (json.trim().isEmpty()) return "";
        StringBuilder sb = new StringBuilder("#EXTM3U\n");
        int n = 0;
        try {
            JsonObject j = JsonParser.parseString(json).getAsJsonObject();
            // El orden lo manda categories[]: si un canal trae un grupo que no
            // está en el índice, se añade al final en vez de perderse.
            Map<String, List<JsonObject>> porGrupo = new LinkedHashMap<>();
            JsonArray cats = j.getAsJsonArray("categories");
            if (cats != null) {
                for (JsonElement e : cats) {
                    if (!e.isJsonObject()) continue;
                    String dn = texto(e.getAsJsonObject(), "display_name");
                    if (dn != null && !dn.isEmpty()) porGrupo.put(dn, new ArrayList<>());
                }
            }
            JsonArray chs = j.getAsJsonArray("channels");
            if (chs == null) return "";
            for (JsonElement e : chs) {
                if (!e.isJsonObject()) continue;
                JsonObject c = e.getAsJsonObject();
                String g = texto(c, "category");
                if (g == null || g.isEmpty()) g = "En vivo";
                List<JsonObject> l = porGrupo.get(g);
                if (l == null) porGrupo.put(g, l = new ArrayList<>());
                l.add(c);
            }
            for (Map.Entry<String, List<JsonObject>> en : porGrupo.entrySet()) {
                for (JsonObject c : en.getValue()) {
                    if (fila(sb, c, en.getKey())) n++;
                }
            }
        } catch (Exception ignore) { }
        return n == 0 ? "" : sb.toString();
    }

    /** Añade los canales de UN fichero de categoría. @return cuántos salieron. */
    private static int emitir(@NonNull StringBuilder sb, @NonNull String json,
                              @Nullable String grupo) {
        int n = 0;
        try {
            JsonObject j = JsonParser.parseString(json).getAsJsonObject();
            JsonArray chs = j.getAsJsonArray("channels");
            if (chs == null) return 0;
            for (JsonElement e : chs) {
                if (!e.isJsonObject()) continue;
                if (fila(sb, e.getAsJsonObject(), grupo)) n++;
            }
        } catch (Exception ignore) { }
        return n;
    }

    /**
     * Escribe la fila m3u de UN canal. Compartida por el empaquetado y el de
     * GitHub: los dos tienen que salir con el mismo {@code #EXTINF} o el filtro,
     * la rejilla y la tira de tópicos tratarían a los canales de forma distinta.
     *
     * @return true si el canal tenía nombre y URL jugables
     */
    private static boolean fila(@NonNull StringBuilder sb, @NonNull JsonObject c,
                                @Nullable String grupo) {
        String nom = texto(c, "name");
        String url = null;
        JsonArray srv = c.getAsJsonArray("servers");
        if (srv != null && srv.size() > 0 && srv.get(0).isJsonObject())
            url = texto(srv.get(0).getAsJsonObject(), "url");
        if (nom == null || nom.isEmpty() || url == null || url.isEmpty()) return false;

        String logo = texto(c, "imageUrl");
        String id = idLiveDe(url);
        String g = grupo == null || grupo.isEmpty() ? "En vivo" : grupo;
        sb.append("#EXTINF:-1 tvg-name=\"").append(nom.replace('"', '\'')).append('"');
        if (id != null) sb.append(" tvg-id=\"").append(id.replace('"', '\'')).append('"');
        sb.append(" group-title=\"").append(g.replace('"', '\'')).append('"');
        if (logo != null && !logo.isEmpty())
            sb.append(" tvg-logo=\"").append(logo.replace('"', '\'')).append('"');
        sb.append(',').append(nom.replace('"', '\'')).append('\n');
        sb.append(url).append('\n');
        return true;
    }

    /** El {@code id_live} de un {@code dramalive://play?...}; null si no lo es. */
    @Nullable
    static String idLiveDe(@Nullable String url) {
        if (url == null || !url.startsWith("dramalive://play?")) return null;
        int i = url.indexOf("id_live=");
        if (i < 0) return null;
        int f = url.indexOf('&', i);
        return f < 0 ? url.substring(i + 8) : url.substring(i + 8, f);
    }

    // ─────────────────────────── utilidades ───────────────────────────

    @Nullable
    private static String texto(@NonNull JsonObject o, @NonNull String k) {
        JsonElement e = o.get(k);
        if (e == null || e.isJsonNull()) return null;
        try {
            String s = e.getAsString();
            return s == null ? null : s.trim();
        } catch (Exception ex) {
            return null;
        }
    }

    @Nullable
    private static String leerAsset(@NonNull Context ctx, @NonNull String ruta) {
        try (InputStream in = ctx.getAssets().open(ruta)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 15);
            byte[] buf = new byte[1 << 13];
            int k;
            while ((k = in.read(buf)) > 0) out.write(buf, 0, k);
            return new String(out.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }
}
