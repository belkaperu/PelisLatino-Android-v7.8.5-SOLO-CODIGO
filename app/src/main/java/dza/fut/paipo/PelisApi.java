package dza.fut.paipo;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URL;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Cliente HTTP + parser de PelisLatinoHD.
 *
 * IMPORTANTE: pelislatinohd.pages.dev NO expone una API WordPress
 * (no existe /wp-json/pelis/v1/...). Es un SPA estatico que carga TODO su
 * catalogo desde un unico fichero:
 *
 *     https://pelislatinohd.pages.dev/data/catalog.json
 *
 * con la forma:
 *   {
 *     "meta":    { "base": "...", "total": {...} },
 *     "movies":  [ {id, type, title, slug, poster, backdrop, year, rating,
 *                   synopsis, embeddedId, ...}, ... ],
 *     "series":  [ {..., seasons, episodes:[{season, episode, title, date}]} ]
 *   }
 *
 * Por eso antes fallaba con HTTP 404: se pedia /wp-json/pelis/v1/search.
 *
 * Este cliente descarga catalog.json una vez, lo cachea en memoria y sirve
 * catalogo (con paginacion y busqueda), detalle y episodios a partir de el.
 */
public final class PelisApi {

    private static final String TAG = "PelisApi";

    /** Dominios: el primero es principal; el resto son espejos. */
    public static final String[] HOSTS = {
            "https://pelislatinohd.pages.dev",
            "https://pelislatinohd.com",
            "https://ev.pelislatinohd.com",
            "https://www.pelislatinohd.com"
    };

    public static final String SITIO = HOSTS[0];

    /** Fichero estatico que contiene todo el catalogo del SPA. */
    public static final String RUTA_CATALOGO = "/data/catalog.json";

    /** Prefijo de imagenes TMDB (por si llega una ruta relativa). */
    public static final String IMG = "https://image.tmdb.org/t/p/w500";

    /** Número de tarjetas por página, como en el grid de la web de referencia. */
    public static final int POR_PAGINA = 28;

    public static final String UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36";

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build();

    /**
     * Handler del hilo principal. Puede ser null si no hay Looper principal
     * (p. ej. en un test JVM sin Robolectric); en ese caso las respuestas se
     * entregan en el mismo hilo para no dejar colgado al llamante.
     */
    private static final Handler MAIN;

    static {
        Handler h = null;
        try {
            Looper looper = Looper.getMainLooper();
            if (looper != null) h = new Handler(looper);
        } catch (Throwable ignored) { }
        MAIN = h;
    }

    /** Cache en memoria del catalogo completo (se descarga una sola vez). */
    private static volatile JsonObject CATALOGO = null;

    private PelisApi() { }

    /* --------------------------------------------------------------- *
     * Callback + entrega en el hilo principal                          *
     * --------------------------------------------------------------- */

    public interface Callback<T> {
        void onOk(T data);
        void onError(String mensaje);
    }

    /** Una página de resultados con el total exacto para la cabecera de búsqueda. */
    public static final class PaginaCatalogo {
        public final List<PelisItem> items;
        public final int total;

        private PaginaCatalogo(List<PelisItem> items, int total) {
            this.items = items;
            this.total = total;
        }
    }

    /** Género o país disponible como filtro en el catálogo. */
    public static final class OpcionFiltro {
        public final int id;
        public final String nombre;
        public final String slug;
        public final int cantidad;

        OpcionFiltro(int id, String nombre, String slug, int cantidad) { // package-private: cache roundtrip (test)
            this.id = id;
            this.nombre = nombre;
            this.slug = slug;
            this.cantidad = cantidad;
        }
    }

    /** Listas de géneros, países y años publicadas por el catálogo web. */
    public static final class FiltrosCatalogo {
        public final List<OpcionFiltro> generos;
        public final List<OpcionFiltro> paises;
        /** v3.2.35: años presentes en el catálogo — el filtro se aplica aquí, del
         *  lado del cliente, porque el catálogo ya se descarga entero (catalog.json). */
        public final List<OpcionFiltro> anos;

        private FiltrosCatalogo(List<OpcionFiltro> generos, List<OpcionFiltro> paises,
                                List<OpcionFiltro> anos) {
            this.generos = generos;
            this.paises = paises;
            this.anos = anos;
        }
    }

    private static <T> void enMain(Callback<T> cb, T data) {
        if (MAIN != null) MAIN.post(() -> cb.onOk(data));
        else cb.onOk(data);
    }

    private static <T> void error(Callback<T> cb, String mensaje) {
        if (MAIN != null) MAIN.post(() -> cb.onError(mensaje));
        else cb.onError(mensaje);
    }

    /* --------------------------------------------------------------- *
     * API publica                                                     *
     * --------------------------------------------------------------- */

    /** API compatible con llamadas que solo necesitan la lista de la página. */
    public static void catalogo(String tipo, int pagina, String busqueda,
                                Callback<List<PelisItem>> cb) {
        new Thread(() -> {
            try {
                enMain(cb, lista(tipo, pagina, busqueda, 0, 0).items);
            } catch (Exception e) {
                Log.e(TAG, "catalogo", e);
                error(cb, describir(e));
            }
        }, "PelisApi-catalogo").start();
    }

    /** Página más el total de coincidencias para mostrar resultados exactos. */
    public static void catalogoPagina(String tipo, int pagina, String busqueda,
                                      Callback<PaginaCatalogo> cb) {
        catalogoPagina(tipo, pagina, busqueda, 0, 0, cb);
    }

    /** Página filtrada por género y país; cero indica que no se aplica ese filtro. */
    public static void catalogoPagina(String tipo, int pagina, String busqueda,
                                      int generoId, int paisId,
                                      Callback<PaginaCatalogo> cb) {
        catalogoPagina(tipo, pagina, busqueda, generoId, paisId, 0, cb);
    }

    /** v3.2.35: igual y además por año; cero deja el año sin aplicar. */
    public static void catalogoPagina(String tipo, int pagina, String busqueda,
                                      int generoId, int paisId, int anio,
                                      Callback<PaginaCatalogo> cb) {
        new Thread(() -> {
            try {
                enMain(cb, lista(tipo, pagina, busqueda, generoId, paisId, anio));
            } catch (Exception e) {
                Log.e(TAG, "catalogo", e);
                error(cb, describir(e));
            }
        }, "PelisApi-catalogo-pagina").start();
    }

    /** Descarga desde el mismo JSON las listas de géneros y países para los selectores. */
    public static void filtrosCatalogo(Callback<FiltrosCatalogo> cb) {
        new Thread(() -> {
            try {
                JsonObject root = catalogoJson();
                FiltrosCatalogo filtros = new FiltrosCatalogo(
                        opcionesFiltro(array(root, "genresList")),
                        opcionesFiltro(array(root, "countriesList")),
                        aniosDelCatalogo(root));
                enMain(cb, filtros);
            } catch (Exception e) {
                Log.e(TAG, "filtrosCatalogo", e);
                error(cb, describir(e));
            }
        }, "PelisApi-filtros").start();
    }

    public static void detalle(PelisItem base, Callback<PelisItem> cb) {
        new Thread(() -> {
            try {
                enMain(cb, detalle(base));
            } catch (Exception e) {
                Log.e(TAG, "detalle", e);
                error(cb, describir(e));
            }
        }, "PelisApi-detalle").start();
    }

    public static void episodios(PelisItem serie, Callback<List<PelisItem.Episodio>> cb) {
        new Thread(() -> {
            try {
                enMain(cb, episodios(serie));
            } catch (Exception e) {
                Log.e(TAG, "episodios", e);
                error(cb, describir(e));
            }
        }, "PelisApi-episodios").start();
    }

    /* --------------------------------------------------------------- *
     * HTTP                                                            *
     * --------------------------------------------------------------- */

    /** GET con cabeceras de navegador. Devuelve el cuerpo como texto. */
    public static String get(String url, String referer) throws IOException {
        Request.Builder rb = new Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Accept", "application/json, text/html;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "es-ES,es;q=0.9,en;q=0.8");
        if (!TextUtils.isEmpty(referer)) {
            rb.header("Referer", referer);
            try {
                String host = new URL(referer).getProtocol() + "://" +
                        new URL(referer).getHost();
                rb.header("Origin", host);
            } catch (Exception ignored) { }
        }
        try (Response resp = CLIENT.newCall(rb.build()).execute()) {
            ResponseBody body = resp.body();
            String txt = body != null ? body.string() : "";
            if (!resp.isSuccessful()) {
                throw new IOException("HTTP " + resp.code() + " en " + url);
            }
            return txt;
        }
    }

    /** GET contra el dominio principal. */
    public static String getDeSitio(String ruta) throws IOException {
        return get(SITIO + ruta, SITIO + "/");
    }

    /* --------------------------------------------------------------- *
     * Logica de negocio (sobre data/catalog.json)                     *
     * --------------------------------------------------------------- */

    /** Descarga (o reutiliza) el catalogo completo. */
    private static JsonObject catalogoJson() throws IOException {
        JsonObject cache = CATALOGO;
        if (cache != null) return cache;
        synchronized (PelisApi.class) {
            if (CATALOGO != null) return CATALOGO;
            String json = getDeSitio(RUTA_CATALOGO);
            JsonElement el = JsonParser.parseString(json);
            if (!el.isJsonObject()) {
                throw new IOException("catalog.json no es un objeto JSON");
            }
            CATALOGO = el.getAsJsonObject();
            return CATALOGO;
        }
    }

    private static List<OpcionFiltro> opcionesFiltro(JsonArray valores) {
        List<OpcionFiltro> opciones = new ArrayList<>();
        for (JsonElement elemento : valores) {
            if (!elemento.isJsonObject()) continue;
            JsonObject opcion = elemento.getAsJsonObject();
            int id = (int) numero(opcion, "id");
            String nombre = primero(opcion, "name", "nombre", "title");
            if (id <= 0 || TextUtils.isEmpty(nombre)) continue;
            opciones.add(new OpcionFiltro(id, nombre, primero(opcion, "slug"),
                    (int) numero(opcion, "count", "total")));
        }
        Collections.sort(opciones, (a, b) -> a.nombre.compareToIgnoreCase(b.nombre));
        return opciones;
    }

    /** v3.2.39: los favoritos del dispositivo. El fragmento carga aqui las prefs al
     *  armar la vista y lo mantiene al dia; lista() lo consulta con tipo==favoritos. */
    public static final java.util.Set<String> FAVORITOS =
            java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<String>());

    /** v3.2.38: serializacion minima de las opciones de filtro para la caché
     *  offline del fragmento: una opcion por linea, columnas separadas por tab. */
    public static String lineasDeOpciones(List<OpcionFiltro> opciones) {
        StringBuilder sb = new StringBuilder();
        if (opciones != null) {
            for (OpcionFiltro o : opciones) {
                if (o == null || o.nombre == null) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append(o.id).append('\t').append(o.nombre).append('\t').append(o.cantidad);
            }
        }
        return sb.toString();
    }

    public static List<OpcionFiltro> opcionesDesdeLineas(String texto) {
        List<OpcionFiltro> out = new ArrayList<>();
        if (texto == null || texto.isEmpty()) return out;
        for (String linea : texto.split("\n")) {
            String[] p = linea.split("\t");
            if (p.length < 2) continue;
            try {
                out.add(new OpcionFiltro(Integer.parseInt(p[0]), p[1], p[1],
                        p.length > 2 ? Integer.parseInt(p[2]) : 0));
            } catch (Exception ignore) { }
        }
        return out;
    }

    /** v3.2.36: años de reserva. El selector de año no puede depender de que ya
     *  haya bajado el catalog.json — si el usuario abre el panel antes del fetch,
     *  no había opciones y el diálogo «se cerraba» sin abrirse. Estos salen del
     *  instante, sin red. */
    public static List<OpcionFiltro> aniosPorDefecto() {
        int actual = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
        List<OpcionFiltro> anos = new ArrayList<>();
        for (int y = actual; y >= 1980; y--) {
            anos.add(new OpcionFiltro(y, String.valueOf(y), String.valueOf(y), 0));
        }
        return anos;
    }

    /** v3.2.35: años del catálogo para el selector — los mismos campos que parseItem. */
    public static List<OpcionFiltro> aniosDelCatalogo(JsonObject root) {
        java.util.TreeMap<Integer, Integer> cuenta = new java.util.TreeMap<>(
                java.util.Collections.reverseOrder());
        for (String coleccion : new String[]{"movies", "series"}) {
            for (JsonElement el : array(root, coleccion)) {
                if (!el.isJsonObject()) continue;
                int y = anioNumero(el.getAsJsonObject());
                if (y > 0) cuenta.merge(y, 1, Integer::sum);
            }
        }
        List<OpcionFiltro> anos = new ArrayList<>();
        for (java.util.Map.Entry<Integer, Integer> e : cuenta.entrySet()) {
            anos.add(new OpcionFiltro(e.getKey(), String.valueOf(e.getKey()),
                    String.valueOf(e.getKey()), e.getValue()));
        }
        return anos;
    }

    /** Año de 4 dígitos del item sin importar dónde lo publique la web; 0 si no tiene. */
    private static int anioNumero(JsonObject o) {
        String s = anio(primero(o, "year", "anio", "release_date", "first_air_date"));
        try {
            int y = Integer.parseInt(s);
            return y > 1800 && y < 2200 ? y : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static PaginaCatalogo lista(String tipo, int pagina, String busqueda,
                                        int generoId, int paisId) throws IOException {
        return lista(tipo, pagina, busqueda, generoId, paisId, 0);
    }

    private static PaginaCatalogo lista(String tipo, int pagina, String busqueda,
                                        int generoId, int paisId, int anio) throws IOException {
        JsonObject root = catalogoJson();
        String q = normalizarBusqueda(busqueda);
        final boolean favs = PelisItem.TIPO_FAVORITOS.equalsIgnoreCase(tipo);
        boolean todas = PelisItem.TIPO_TODOS.equalsIgnoreCase(tipo) || favs;
        boolean serie = PelisItem.TIPO_SERIE.equalsIgnoreCase(tipo);

        List<PelisItem> filtrado = new ArrayList<>();
        if (todas) {
            agregarCoincidencias(array(root, "movies"), PelisItem.TIPO_PELICULA,
                    root, q, generoId, paisId, anio, favs, filtrado);
            agregarCoincidencias(array(root, "series"), PelisItem.TIPO_SERIE,
                    root, q, generoId, paisId, anio, favs, filtrado);
        } else {
            JsonArray origen = array(root, serie ? "series" : "movies");
            agregarCoincidencias(origen, tipo, root, q, generoId, paisId, anio, false, filtrado);
        }

        // En búsquedas simples conserva el orden web; las páginas por género/país
        // se ordenan por fecha de publicación como en la web de referencia.
        if (generoId > 0 || paisId > 0 || anio > 0 || favs) {
            Collections.sort(filtrado, (a, b) ->
                    b.fechaCatalogo.compareTo(a.fechaCatalogo));
        }
        int total = filtrado.size();
        int page = Math.max(1, pagina);
        int desde = (page - 1) * POR_PAGINA;
        if (desde >= total) return new PaginaCatalogo(new ArrayList<>(), total);
        int hasta = Math.min(desde + POR_PAGINA, total);
        return new PaginaCatalogo(new ArrayList<>(filtrado.subList(desde, hasta)), total);
    }

    private static void agregarCoincidencias(JsonArray origen, String tipoPorDefecto,
                                              JsonObject root, String consulta,
                                              int generoId, int paisId, int anio,
                                              boolean favMode,
                                              List<PelisItem> destino) {
        for (JsonElement el : origen) {
            if (!el.isJsonObject()) continue;
            JsonObject objeto = el.getAsJsonObject();
            if (!TextUtils.isEmpty(consulta) && !coincideBusqueda(objeto, consulta)) continue;
            if (!contieneId(objeto, "genres", generoId)
                    || !contieneId(objeto, "countries", paisId)) continue;
            if (anio > 0 && anioNumero(objeto) != anio) continue; // v3.2.35
            PelisItem it = parseItem(objeto, tipoPorDefecto, root, false);
            if (it == null) continue;
            if (favMode && !FAVORITOS.contains(it.claveFavorito())) continue; // v3.2.39
            destino.add(it);
        }
    }

    private static boolean contieneId(JsonObject item, String clave, int id) {
        if (id <= 0) return true;
        for (JsonElement elemento : array(item, clave)) {
            try {
                if (elemento.getAsInt() == id) return true;
            } catch (Exception ignored) { }
        }
        return false;
    }

    private static boolean coincideBusqueda(JsonObject item, String consulta) {
        String titulo = primero(item, "title", "titulo", "name");
        String original = primero(item, "originalTitle", "original_title", "originalName", "original_name");
        String slug = primero(item, "slug", "permalink", "url");
        return normalizarBusqueda(titulo).contains(consulta)
                || normalizarBusqueda(original).contains(consulta)
                || normalizarBusqueda(slug).contains(consulta);
    }

    /** Comparación sin mayúsculas ni tildes: "accion" también encuentra "Acción". */
    private static String normalizarBusqueda(String valor) {
        if (valor == null || valor.trim().isEmpty()) return "";
        String normalizado = Normalizer.normalize(valor.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return normalizado.toLowerCase(Locale.ROOT);
    }

    private static PelisItem detalle(PelisItem base) throws IOException {
        JsonObject root = catalogoJson();
        boolean serie = base != null && base.esSerie();
        JsonArray origen = array(root, serie ? "series" : "movies");

        if (base != null) {
            for (JsonElement el : origen) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                if (coincide(o, base)) {
                    PelisItem it = parseItem(o, base.tipo, root, true);
                    if (it != null) return it;
                }
            }
        }
        // Si no se localiza, devolvemos lo que ya teniamos.
        return base != null ? base : new PelisItem();
    }

    private static List<PelisItem.Episodio> episodios(PelisItem serie) throws IOException {
        JsonObject root = catalogoJson();
        JsonArray origen = array(root, "series");

        JsonObject encontrado = null;
        for (JsonElement el : origen) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            if (coincide(o, serie)) { encontrado = o; break; }
        }
        if (encontrado == null) return new ArrayList<>();

        List<PelisItem.Episodio> out = parseEpisodios(encontrado);
        Collections.sort(out, (a, b) -> {
            int t = numero(a.temporada) - numero(b.temporada);
            return t != 0 ? t : numero(a.episodio) - numero(b.episodio);
        });
        return out;
    }

    /* --------------------------------------------------------------- *
     * Parseo                                                          *
     * --------------------------------------------------------------- */

    /** El objeto del catalogo corresponde al item pedido? */
    private static boolean coincide(JsonObject o, PelisItem base) {
        if (base == null) return false;
        String embedded = primero(o, "embeddedId", "imdb_id", "imdb", "tmdb_id");
        String slug = primero(o, "slug");
        if (!TextUtils.isEmpty(base.playId) && base.playId.equals(embedded)) return true;
        if (!TextUtils.isEmpty(base.slug) && base.slug.equals(slug)) return true;
        int oid = (int) numero(o, "id");
        return base.id > 0 && base.id == oid;
    }

    private static PelisItem parseItem(JsonObject o, String tipoPorDefecto,
                                       JsonObject catalogo, boolean incluirEpisodios) {
        if (o == null) return null;
        PelisItem it = new PelisItem();

        String tipo = primero(o, "type", "post_type", "tipo");
        it.tipo = TextUtils.isEmpty(tipo) ? normalizarTipo(tipoPorDefecto) : normalizarTipo(tipo);

        it.titulo = primero(o, "title", "titulo", "name", "originalTitle", "original_title");
        if (TextUtils.isEmpty(it.titulo)) return null;

        it.tituloOriginal = primero(o, "originalTitle", "original_title", "originalName", "original_name");
        it.id = (int) numero(o, "id");
        it.slug = primero(o, "slug", "permalink", "url");
        it.poster = imagen(primero(o, "poster", "poster_path", "image", "thumbnail"));
        it.backdrop = imagen(primero(o, "backdrop", "backdrop_path", "cover"));
        it.sinopsis = sinopsisDe(o);
        it.anio = anio(primero(o, "year", "anio", "release_date", "first_air_date"));
        it.fechaCatalogo = primero(o, "date", "created_at", "fecha");
        it.rating = numero(o, "rating", "tmdbRating", "vote_average", "imdb_rating");
        it.playId = primero(o, "embeddedId", "imdb_id", "imdb", "playId", "tmdb_id", "tmdb");
        it.temporadas = (int) numero(o, "seasons", "temporadas", "total_seasons");
        it.generos = parseGeneros(o, catalogo);
        if (incluirEpisodios) it.episodios = parseEpisodios(o);
        return it;
    }

    private static List<String> parseGeneros(JsonObject item, JsonObject catalogo) {
        List<String> nombres = new ArrayList<>();
        JsonArray ids = array(item, "genres", "genre_ids", "generos");
        JsonObject mapa = catalogo != null && catalogo.has("genres")
                && catalogo.get("genres").isJsonObject()
                ? catalogo.getAsJsonObject("genres") : new JsonObject();

        for (JsonElement elemento : ids) {
            if (elemento == null || elemento.isJsonNull()) continue;
            String valor;
            try {
                valor = elemento.getAsString();
            } catch (Exception ignored) {
                continue;
            }
            String nombre = "";
            if (mapa.has(valor) && !mapa.get(valor).isJsonNull()) {
                try { nombre = mapa.get(valor).getAsString(); } catch (Exception ignored) { }
            } else if (!valor.matches("\\d+")) {
                // Algunos espejos ya envían el nombre del género en lugar de su ID.
                nombre = valor;
            }
            if (!TextUtils.isEmpty(nombre) && !nombres.contains(nombre)) nombres.add(nombre);
        }
        return nombres;
    }

    private static List<PelisItem.Episodio> parseEpisodios(JsonObject o) {
        List<PelisItem.Episodio> out = new ArrayList<>();
        JsonArray arr = array(o, "episodes", "episodios");
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            out.add(parseEpisodio(el.getAsJsonObject()));
        }
        return out;
    }

    private static PelisItem.Episodio parseEpisodio(JsonObject o) {
        PelisItem.Episodio e = new PelisItem.Episodio();
        e.temporada = metaStr(o, "season", "temporada", "season_number");
        e.episodio = metaStr(o, "episode", "episodio", "episode_number");
        e.titulo = primero(o, "title", "titulo", "name");
        e.fecha = primero(o, "date", "air_date", "fecha", "release_date");
        if (e.temporada.isEmpty()) e.temporada = "1";
        if (e.episodio.isEmpty()) e.episodio = "1";
        return e;
    }

    private static String normalizarTipo(String t) {
        if (t == null) return PelisItem.TIPO_PELICULA;
        String s = t.toLowerCase(Locale.US);
        if (s.contains("serie") || s.contains("series") || s.contains("tv") || s.contains("show")) {
            return PelisItem.TIPO_SERIE;
        }
        return PelisItem.TIPO_PELICULA;
    }

    private static String sinopsisDe(JsonObject o) {
        String s = primero(o, "synopsis", "overview", "sinopsis", "description", "plot", "content");
        if (s.length() > 900) s = s.substring(0, 900) + "...";
        return s;
    }

    private static String imagen(String p) {
        if (TextUtils.isEmpty(p)) return "";
        if (p.startsWith("http://") || p.startsWith("https://")) return p;
        if (p.startsWith("/")) return IMG + p;
        return IMG + "/" + p;
    }

    private static String anio(String s) {
        if (TextUtils.isEmpty(s)) return "";
        String[] partes = s.split("[-/ ]");
        for (String p : partes) {
            if (p.length() == 4 && p.matches("\\d{4}")) return p;
        }
        return "";
    }

    private static String metaStr(JsonObject o, String... claves) {
        String v = primero(o, claves);
        return v == null ? "" : v.trim();
    }

    private static int numero(String s) {
        if (s == null) return 0;
        try { return Integer.parseInt(s.replaceAll("[^0-9]", "")); }
        catch (Exception e) { return 0; }
    }

    private static double numero(JsonObject o, String... claves) {
        for (String k : claves) {
            if (o.has(k) && !o.get(k).isJsonNull()) {
                try { return o.get(k).getAsDouble(); } catch (Exception ignored) { }
            }
        }
        return 0.0;
    }

    private static String primero(JsonObject o, String... claves) {
        if (o == null) return "";
        for (String k : claves) {
            if (o.has(k) && !o.get(k).isJsonNull()) {
                try {
                    String v = o.get(k).getAsString();
                    if (!TextUtils.isEmpty(v)) return v;
                } catch (Exception ignored) { }
            }
        }
        return "";
    }

    private static JsonArray array(JsonObject root, String... claves) {
        if (root == null) return new JsonArray();
        for (String k : claves) {
            if (root.has(k) && root.get(k).isJsonArray()) {
                return root.getAsJsonArray(k);
            }
        }
        return new JsonArray();
    }

    private static String describir(Exception e) {
        String m = e.getMessage();
        return TextUtils.isEmpty(m) ? e.getClass().getSimpleName() : m;
    }
}
