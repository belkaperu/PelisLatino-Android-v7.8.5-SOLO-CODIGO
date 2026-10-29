package dza.fut.paipo;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Request;
import okhttp3.Response;

/**
 * Panel Xtream Codes (`player_api.php`): el API que usa de verdad el cliente web de
 * DramaLive. Lo que hace `web.dramalive.cc` es construir
 * `https://&lt;proxy&gt;/androidApi.php?url=&lt;host&gt;&mode=live&username=&lt;u&gt;&password=&lt;p&gt;`
 * — un proxy PHP solo porque el navegador tiene CORS y no puede llamar al panel directamente.
 * Una app nativa no necesita ese intermediario: llama a `player_api.php` a pelo.
 *
 * <p>Nada de esto funciona sin una cuenta del panel: probado contra el proxy de la web con el
 * usuario de defecto que trae el propio bundle (`shai`, sin contraseña) devuelve
 * `ACCESS DENIED! AndroidAPI v3.1`. O sea: aquí se pegan HOST + USUARIO + CONTRASEÑA que te
 * haya dado tu proveedor y la pestaña se llena con el catálogo completo del panel, con sus
 * logos y sus categorías reales.
 */
public final class Xtream {

    private static final String TAG = "Xtream";
    private static final String PREFS = "fuente_canales";
    static final String CLAVE_URL = "xtream_url";
    static final String CLAVE_USUARIO = "xtream_usuario";
    static final String CLAVE_CLAVE = "xtream_clave";
    static final String CLAVE_ACTIVO = "xtream_activo";

    /** `(720p)`, `(1080p)`, `[FHD]` al final del nombre de algunos canales. */
    private static final Pattern CALIDAD = Pattern.compile("\\(?(\\d{3,4})p\\)?\\s*$",
            Pattern.CASE_INSENSITIVE);

    /** `user:pass@host:puerto`, la forma en la que se suele pegar un panel. */
    private static final Pattern CREDENCIALES_EN_URL =
            Pattern.compile("^(?:https?://)?([^:/@\\s]+):([^@/\\s]+)@(.+)$");

    private Xtream() {}

    // ─────────────────────────── el panel ───────────────────────────

    public static final class Panel {
        @NonNull public final String base;
        @NonNull public final String usuario;
        @NonNull public final String clave;

        Panel(@NonNull String base, @NonNull String usuario, @NonNull String clave) {
            this.base = base;
            this.usuario = usuario;
            this.clave = clave;
        }

        public boolean completa() {
            return !base.isEmpty() && !usuario.isEmpty() && !clave.isEmpty();
        }

        /** Para la píldora de estado: nunca mostramos la contraseña. */
        @NonNull
        public String etiqueta() {
            String host = base.replaceFirst("^https?://", "");
            return host;
        }

        @NonNull
        String apiUrl(@NonNull String accion) {
            return base + "/player_api.php?username=" + usuario + "&password=" + clave
                    + "&action=" + accion;
        }

        /** Reproducción HLS: es lo que entiende Media3. `.ts` existe, pero no compensa. */
        @NonNull
        public String urlDirecto(@NonNull String id) {
            return base + "/live/" + usuario + "/" + clave + "/" + id + ".m3u8";
        }
    }

    /**
     * Acepta lo que un usuario tiene a mano para pegar: `http://host:8080`,
     * `user:pass@host:8080`, o la URL completa de `player_api.php` / `.m3u` que da el panel.
     * Los campos de texto vacíos se rellenan desde la propia URL pegada.
     */
    @NonNull
    public static Panel normalizar(@Nullable String entrada, @Nullable String usuario,
                                   @Nullable String clave) {
        String texto = entrada == null ? "" : entrada.trim();
        String u = usuario == null ? "" : usuario.trim();
        String p = clave == null ? "" : clave.trim();
        if (texto.isEmpty()) return new Panel("", u, p);

        // `?username=X&password=Y` es lo que da el propio panel al copiar la lista.
        String query = "";
        int interrogacion = texto.indexOf('?');
        if (interrogacion >= 0) query = texto.substring(interrogacion + 1);
        if (!query.isEmpty()) {
            String delQuery = parametro(query, "username");
            if (u.isEmpty() && !delQuery.isEmpty()) u = delQuery;
            delQuery = parametro(query, "password");
            if (p.isEmpty() && !delQuery.isEmpty()) p = delQuery;
        }

        Matcher credenciales = CREDENCIALES_EN_URL.matcher(texto);
        if (credenciales.matches()) {
            if (u.isEmpty()) u = credenciales.group(1);
            if (p.isEmpty()) p = credenciales.group(2);
            texto = credenciales.group(3);
        }
        if (!texto.toLowerCase(Locale.ROOT).startsWith("http")) {
            texto = "http://" + texto;
        }
        // Fuera la parte útil: /player_api.php?..., /get.php?..., /live/..., /xmltv.php, /canales.m3u
        int corte = texto.indexOf("player_api.php");
        if (corte < 0) corte = texto.indexOf("get.php");
        if (corte < 0) corte = texto.indexOf("/live/");
        if (corte < 0) corte = texto.indexOf("/movie/");
        if (corte < 0) corte = texto.indexOf("/xmltv");
        if (corte >= 0) texto = texto.substring(0, corte);
        int puntoYQuery = texto.indexOf('?');
        if (puntoYQuery >= 0) texto = texto.substring(0, puntoYQuery);
        while (texto.endsWith("/")) {
            texto = texto.substring(0, texto.length() - 1);
        }
        return new Panel(texto, u, p);
    }

    // ─────────────────────────── preferencias ───────────────────────────

    @NonNull
    public static Panel desdePreferencias(@NonNull Context contexto) {
        SharedPreferences prefs = contexto.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new Panel(prefs.getString(CLAVE_URL, ""), prefs.getString(CLAVE_USUARIO, ""),
                prefs.getString(CLAVE_CLAVE, ""));
    }

    public static boolean estaActivo(@NonNull Context contexto) {
        SharedPreferences prefs = contexto.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.getBoolean(CLAVE_ACTIVO, false);
    }

    public static void guardar(@NonNull Context contexto, @NonNull String entrada,
                                @NonNull String usuario, @NonNull String clave, boolean activo) {
        Panel normalizado = normalizar(entrada, usuario, clave);
        contexto.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(CLAVE_URL, normalizado.base)
                .putString(CLAVE_USUARIO, normalizado.usuario)
                .putString(CLAVE_CLAVE, normalizado.clave)
                .putBoolean(CLAVE_ACTIVO, activo && normalizado.completa())
                .apply();
    }

    /** Panel utilizable, o null si no está configurado/activado. */
    @Nullable
    public static Panel siActivo(@NonNull Context contexto) {
        Panel panel = desdePreferencias(contexto);
        return estaActivo(contexto) && panel.completa() ? panel : null;
    }

    // ─────────────────────────── red ───────────────────────────

    @NonNull
    public static List<Canal> cargar(@NonNull Panel panel) throws IOException {
        if (!panel.completa()) {
            throw new IOException("falta host, usuario o contraseña");
        }
        // `auth` ya nos dice si las credenciales valen: evita leer 2 MB de una lista ajena.
        String auth = pedir(panel.apiUrl("auth"));
        String problema = revisarAuth(auth);
        if (problema != null) throw new IOException(problema);
        String categorias;
        String canales;
        try {
            categorias = pedir(panel.apiUrl("get_live_categories"));
        } catch (IOException e) {
            categorias = "[]";   // sin categorías seguimos: se etiquetan "Xtream"
        }
        canales = pedir(panel.apiUrl("get_live_streams"));
        return desdeJson(panel, categorias, canales);
    }

    @NonNull
    private static String pedir(@NonNull String url) throws IOException {
        Request peticion = new Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android) Blabongo").build();
        try (Response response = FuenteCanales.cliente().newCall(peticion).execute()) {
            String cuerpo = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code());
            }
            return cuerpo;
        }
    }

    /**
     * Lo que enseña el botón "Guardar y cargar": una pasada real al panel para poder decir
     * cuántos canales trajo, o el motivo por el que no. Se hace fuera del hilo de UI porque
     * un panel puede tardar segundos (o colgar el TCP).
     */
    public static void probarAsync(@NonNull Context contexto,
                                   @NonNull java.util.function.BiConsumer<Integer, Integer> listo,
                                   @NonNull java.util.function.Consumer<String> falla) {
        final Panel panel = desdePreferencias(contexto);
        new Thread(() -> {
            try {
                final List<Canal> canales = cargar(panel);
                java.util.Set<String> categorias = new java.util.HashSet<>();
                for (Canal c : canales) {
                    if (!vacio(c.category)) categorias.add(c.category);
                }
                final int total = canales.size();
                final int grupos = Math.max(categorias.size(), 1);
                FuenteCanales.enMain(() -> listo.accept(total, grupos));
            } catch (Exception e) {
                final String mensaje = vacio(e.getMessage()) ? e.toString() : e.getMessage();
                FuenteCanales.enMain(() -> falla.accept(mensaje));
            }
        }, "xtream-probar").start();
    }

    // ─────────────────────────── parseo (puro, testeable sin red) ───────────────────────────

    /**
     * null si el panel acepta las credenciales; si no, el mensaje que se le enseña al usuario.
     * Un login bueno responde {@code user_info.status = "Enabled"} (no "1": eso es de otras
     * versiones del API) y {@code server_info.url}.
     */
    @Nullable
    public static String revisarAuth(@Nullable String json) {
        if (vacio(json)) return "el panel no respondió nada";
        if (json.trim().startsWith("<")) return "el panel devolvió HTML (¿URL correcta?)";
        JsonObject o;
        try {
            JsonElement e = JsonParser.parseString(json);
            if (e == null || !e.isJsonObject()) return "respuesta ilegible del panel";
            o = e.getAsJsonObject();
        } catch (Exception ex) {
            return "respuesta ilegible del panel";
        }
        String estado = texto(o, "user_info", "status");
        String servidor = texto(o, "server_info", "url");
        if ("Enabled".equalsIgnoreCase(estado) || "Active".equalsIgnoreCase(estado)
                || "1".equals(estado) || "OK".equalsIgnoreCase(estado)) {
            return null;
        }
        if (!vacio(servidor)) return null;   // paneles que no traen user_info
        String errores = texto(o, "errors");
        if (!vacio(errores)) return "panel: " + errores;
        return vacio(estado) ? "el panel rechazó las credenciales" : "panel: " + estado;
    }

    /**
     * Mezcla `get_live_categories` + `get_live_streams`. Se parsea a mano y no con POJOs
     * porque los paneles Xtream no se ponen de acuerdo en los nombres de campo.
     */
    @NonNull
    public static List<Canal> desdeJson(@Nullable Panel panel, @Nullable String categoriasJson,
                                       @Nullable String canalesJson) {
        List<Canal> salida = new ArrayList<>();
        Map<String, String> nombres = new HashMap<>();
        JsonArray categorias = arreglo(categoriasJson, "categorías");
        for (JsonElement e : categorias) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            String id = texto(o, "category_id");
            String nombre = texto(o, "category_name");
            if (!id.isEmpty() && !nombre.isEmpty()) nombres.put(id, nombre);
        }

        JsonArray canales = arreglo(canalesJson, "canales");
        for (JsonElement e : canales) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            String id = texto(o, "stream_id", "num");
            String nombre = texto(o, "name", "stream_display_name");
            if (id.isEmpty() || nombre.isEmpty()) continue;
            // `series`/`movie` no van aquí: esta lista es de directo.
            String tipo = texto(o, "stream_type");
            if ("movie".equalsIgnoreCase(tipo) || "series".equalsIgnoreCase(tipo)) continue;

            Canal canal = new Canal();
            canal.title = CALIDAD.matcher(nombre).replaceAll("").trim();
            Matcher m = CALIDAD.matcher(nombre);
            canal.calidad = m.find() ? m.group(1) + "p" : null;
            String categoriaId = texto(o, "category_id");
            String categoria = nombres.get(categoriaId);
            canal.category = vacio(categoria)
                    ? (categoriaId.isEmpty() ? "Xtream" : "Categoría " + categoriaId) : categoria;
            String icono = texto(o, "stream_icon", "custom_stream_icon", "icon");
            canal.image = vacio(icono) ? null : icono;
            // `id` ya viene absoluto en los paneles que devuelven una URL directa en vez de
            // un stream_id numérico.
            String directo = id.startsWith("http") ? id
                    : (panel != null ? panel.urlDirecto(id) : id);
            Canal.OpcionCanal opcion = new Canal.OpcionCanal();
            opcion.label = vacio(canal.calidad) ? "Xtream" : canal.calidad;
            opcion.url = directo;
            canal.options = new ArrayList<>();
            canal.options.add(opcion);
            salida.add(canal);
        }
        return salida;
    }

    /**
     * Vacío, sin depender de android.text.TextUtils: en los tests de JVM el framework está
     * stubbeado y isEmpty() devuelve false para todo (con returnDefaultValues), así que un
     * check así solo se comprueba en el dispositivo.
     */
    private static boolean vacio(@Nullable String s) {
        return s == null || s.trim().isEmpty();
    }

    // ─────────────────────────── utilidades gson ───────────────────────────

    @NonNull
    private static JsonArray arreglo(@Nullable String json, @NonNull String queEs) {
        if (vacio(json)) return new JsonArray();
        try {
            JsonElement e = JsonParser.parseString(json);
            return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
        } catch (Exception ex) {
            Log.w(TAG, "no es JSON de " + queEs + ": " + ex.getMessage());
            return new JsonArray();
        }
    }

    @NonNull
    private static JsonObject objeto(@NonNull String json, @NonNull String queEs) throws IOException {
        try {
            JsonElement e = JsonParser.parseString(json);
            if (e != null && e.isJsonObject()) return e.getAsJsonObject();
        } catch (Exception ex) {
            Log.w(TAG, queEs + " no es JSON: " + ex.getMessage());
        }
        throw new IOException(queEs + " no es JSON (¿es el host correcto?)");
    }

    /** Valor de `clave=...` dentro de una query string, ya sin escapar. */
    @NonNull
    private static String parametro(@NonNull String query, @NonNull String clave) {
        for (String parte : query.split("&")) {
            int eq = parte.indexOf('=');
            if (eq > 0 && clave.equalsIgnoreCase(parte.substring(0, eq))) {
                try {
                    return java.net.URLDecoder.decode(parte.substring(eq + 1), "UTF-8");
                } catch (Exception e) {
                    return parte.substring(eq + 1);
                }
            }
        }
        return "";
    }

    /**
     * Lee un campo del JSON del panel. Primero intenta la ruta anidada
     * {@code user_info -> status} (así responden todos) y si no, cualquiera de las claves
     * suelta: algunos paneles devuelven {@code status} a pelo, y el icono viene como
     * {@code stream_icon} o {@code custom_stream_icon}.
     */
    @NonNull
    private static String texto(@NonNull JsonObject o, @NonNull String... claves) {
        JsonElement nodo = o;
        for (String clave : claves) {
            if (nodo == null || !nodo.isJsonObject()) {
                nodo = null;
                break;
            }
            nodo = nodo.getAsJsonObject().get(clave);
        }
        String valor = comoTexto(nodo);
        if (!valor.isEmpty()) return valor;
        for (String clave : claves) {
            valor = comoTexto(o.get(clave));
            if (!valor.isEmpty()) return valor;
        }
        return "";
    }

    @NonNull
    private static String comoTexto(@Nullable JsonElement e) {
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return "";
        try {
            String v = e.getAsString().trim();
            return v.isEmpty() || "null".equalsIgnoreCase(v) ? "" : v;
        } catch (Exception ignorado) {
            return "";
        }
    }
}
