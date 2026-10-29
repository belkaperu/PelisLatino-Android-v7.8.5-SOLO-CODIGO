package dza.fut.paipo;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.Request;
import okhttp3.Response;

/**
 * Carga de canales SIN configuración del usuario.
 *
 * La idea es la contraria a "pegar una URL": se recorre una cadena de fuentes
 * públicas hasta que una responda, se recuerda cuál fue la que funcionó para que el
 * próximo arranque empiece por ahí, y se guarda el cuerpo en el caché de la app para
 * que la rejilla se pinte aunque no haya red (o el proveedor esté caído).
 *
 * Qué hay en la cadena, y por qué:
 *   1. La lista con la que la app ya venía (deportes; es la que reproduce en el player).
 *   2. El índice comunitario iptv-org recortado por categoría (deportes).
 *   3. El mismo índice recortado por PAÍS, tomado del idioma del dispositivo
 *      ({@code Locale.getCountry()} → {@code countries/pe.m3u}). iptv-org solo indexa
 *      señales publicadas por el propio canal y borra lo que le reportan.
 *   4. Free-TV/IPTV, que explícitamente es televisión abierta.
 *   5. (opcional, apagada por defecto) la lista global: 4+ MB y miles de entradas.
 *
 * Ninguna de esas fuentes garantiza reproducción: muestreando 25 entradas al azar
 * desde este entorno, el manifiesto contestaba 200 en 20/25 (Perú) y 17/25
 * (deportes); el resto eran 403/404 (geobloqueo o señal muerta). Por eso el tile se
 * pinta igual y el fallo real se ve al abrirlo, que es donde el WebView puede reintentar.
 */
public final class AutoCanales {

    public static final String CLAVE_ACTIVO = "auto_activado";
    public static final String CLAVE_ALCANCE = "auto_alcance";

    /** Qué tan grande es el catálogo que se baja. Ver {@link #candidatos}. */
    public static final String ALCANCE_CORTO = "corto";
    public static final String ALCANCE_LATAM = "latam";
    public static final String ALCANCE_GLOBAL = "global";
    private static final String CLAVE_GANADOR = "auto_ganador_url";
    private static final String CLAVE_GANADOR_ETIQUETA = "auto_ganador_etiqueta";

    private static final String PREFS = "fuente_canales";
    private static final String IPTV_ORG = "https://iptv-org.github.io/iptv/";
    /** Medidas el día de la entrega: latam 507 KB / 2.422 canales; index 2,5 MB / 11.183. */
    private static final String LATAM = IPTV_ORG + "regions/latam.m3u";
    private static final String ESPANOL = IPTV_ORG + "languages/spa.m3u";
    /**
     * category en vez de index a secas: mismos 11.170 canales (2,7 MB) pero 620 de ellos
     * llegan con varios servidores, frente a los 10 que trae index.m3u.
     */
    private static final String GLOBAL = IPTV_ORG + "index.category.m3u";
    private static final String FREE_TV = "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8";
    /** v3.2.65: catálogo propio de canales latinos (repo del usuario, 479 canales
     *  con todos sus servidores en el JSON histórico de la app). Package-visible
     *  para que el test de contrato lo verifique. */
    static final String LATINOS_JSON =
            "https://raw.githubusercontent.com/youcefyakhoo/cesopppplop/main/canales.json";

    private static final long TTL_CACHE = TimeUnit.HOURS.toMillis(12);
    private static final long MAX_CACHE = 16L * 1024 * 1024;
    public static final int TOPE_CORTO = 600;
    public static final int TOPE_LATAM = 2500;
    public static final int TOPE_GLOBAL = 12000;
    private static final int MAX_FALLOS_EN_RESUMEN = 4;
    private static final String TAG = "AutoCanales";

    public static class Fuente {
        public final String etiqueta;
        public final String url;
        /** Pesada = solo se intenta si el usuario la activó explícitamente. */
        public final boolean optativa;
        /** Panel Xtream si esta fuente es un `player_api.php`; null si es lista normal. */
        @Nullable
        public final Xtream.Panel panel;
        /** Texto pegado por el usuario (M3U, JSON o URLs de stream): no se descarga nada. */
        @Nullable
        public final String pegada;

        Fuente(String etiqueta, String url, boolean optativa) {
            this(etiqueta, url, optativa, null, null);
        }

        Fuente(String etiqueta, String url, boolean optativa, @Nullable Xtream.Panel panel) {
            this(etiqueta, url, optativa, panel, null);
        }

        Fuente(String etiqueta, @Nullable String url, boolean optativa,
               @Nullable Xtream.Panel panel, @Nullable String pegada) {
            this.etiqueta = etiqueta;
            this.url = url == null ? "" : url;
            this.optativa = optativa;
            this.panel = panel;
            this.pegada = pegada;
        }
    }

    public interface Oyente {
        /** `desdeCache` sirve para no anunciar "actualizado" cuando es datos viejos. */
        void onCanales(@NonNull List<Canal> canales, @NonNull String etiqueta, boolean desdeCache);

        void onError(@NonNull String resumen);
    }

    // v7.4: el pool de red lo lleva AppExecutors.red() (ver core/AppExecutors).

    private AutoCanales() {}

    // ─────────────────────────── preferencias ───────────────────────────

    public static boolean estaActivado(@NonNull Context contexto) {
        SharedPreferences prefs = contexto.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        // Sin preferencia guardada el automático va encendido: es lo que se espera de la app.
        return prefs.getBoolean(CLAVE_ACTIVO, true);
    }

    public static void setActivado(@NonNull Context contexto, boolean activado) {
        contexto.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(CLAVE_ACTIVO, activado).apply();
    }

    /**
     * Tamaño del catálogo. En un dispositivo en español el defecto es LATAM: 2.422
     * canales por 507 KB es "un montón" sin bajar 2,5 MB por datos móviles.
     */
    @NonNull
    public static String alcance(@NonNull Context contexto) {
        String guardado = contexto.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(CLAVE_ALCANCE, "");
        if (ALCANCE_CORTO.equals(guardado) || ALCANCE_LATAM.equals(guardado) || ALCANCE_GLOBAL.equals(guardado)) {
            return guardado;
        }
        return esEspanol() ? ALCANCE_LATAM : ALCANCE_CORTO;
    }

    public static void setAlcance(@NonNull Context contexto, @NonNull String alcance) {
        contexto.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(CLAVE_ALCANCE, alcance).apply();
    }

    private static boolean esEspanol() {
        return "es".equalsIgnoreCase(Locale.getDefault().getLanguage());
    }

    @NonNull
    public static String etiquetaAlcance(@NonNull String alcance) {
        if (ALCANCE_GLOBAL.equals(alcance)) return "Global";
        if (ALCANCE_LATAM.equals(alcance)) return "LATAM";
        return "Corto";
    }

    public static int tope(@NonNull String alcance) {
        if (ALCANCE_GLOBAL.equals(alcance)) return TOPE_GLOBAL;
        if (ALCANCE_LATAM.equals(alcance)) return TOPE_LATAM;
        return TOPE_CORTO;
    }

    /** Etiqueta de la fuente que ganó la última vez, para mostrarla en pantalla. */
    @NonNull
    public static String ultimoGanador(@NonNull Context contexto) {
        return contexto.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(CLAVE_GANADOR_ETIQUETA, "");
    }

    @Nullable
    public static String paisDispositivo() {
        String cc = Locale.getDefault().getCountry().toLowerCase(Locale.ROOT);
        return TextUtils.isEmpty(cc) || cc.length() != 2 ? null : cc;
    }

    @NonNull
    public static List<Fuente> candidatos(@NonNull Context contexto) {
        String cc = paisDispositivo();
        List<Fuente> locales = new ArrayList<>();
        locales.add(new Fuente("Deportes · iptv-org", IPTV_ORG + "categories/sports.m3u", false));
        if (cc != null) {
            locales.add(new Fuente("País · iptv-org " + cc.toUpperCase(Locale.ROOT),
                    IPTV_ORG + "countries/" + cc + ".m3u", false));
        }
        locales.add(new Fuente("Deportes (lista de la app)", FuenteCanales.URL_POR_DEFECTO, false));
        locales.add(new Fuente("TDT · Free-TV", FREE_TV, false));

        List<Fuente> lista = new ArrayList<>();
        // v3.2.65: «Latinos» va PRIMERO entre las fuentes públicas en cualquier
        // alcance — es el catálogo de la casa. Si el repo no responde, la cadena
        // sigue con iptv-org/Free-TV como siempre; la fuente manual y el panel
        // del usuario se insertan después al frente y siguen mandando.
        lista.add(new Fuente("Latinos", LATINOS_JSON, false));
        switch (alcance(contexto)) {
            case ALCANCE_GLOBAL:
                lista.add(new Fuente("Global · iptv-org", GLOBAL, true));
                lista.add(new Fuente("LATAM · iptv-org", LATAM, false));
                lista.add(new Fuente("Español · iptv-org", ESPANOL, false));
                lista.addAll(locales);
                break;
            case ALCANCE_LATAM:
                lista.add(new Fuente("LATAM · iptv-org", LATAM, false));
                lista.add(new Fuente("Español · iptv-org", ESPANOL, false));
                lista.addAll(locales);
                break;
            case ALCANCE_CORTO:
            default:
                lista.addAll(locales);
                break;
        }

        // El ganador anterior pasa al principio: así el segundo arranque no vuelve a
        // probar toda la cadena.
        // v3.2.65: al estrenarse «Latinos» se olvida UNA sola vez el ganador
        // público anterior, para que la cadena pruebe primero el catálogo nuevo.
        SharedPreferences prefC = contexto.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefC.getBoolean("latinos_v1", false)) {
            prefC.edit().remove(CLAVE_GANADOR).putBoolean("latinos_v1", true).apply();
        }
        String ganador = prefC.getString(CLAVE_GANADOR, "");
        if (!TextUtils.isEmpty(ganador)) {
            for (int i = 0; i < lista.size(); i++) {
                if (lista.get(i).url.equals(ganador)) {
                    lista.add(0, lista.remove(i));
                    break;
                }
            }
        }
        // Un panel configurado manda sobre todo lo público: es lo que el usuario pidió, y si
        // falla se sigue con iptv-org/Free-TV para no dejar la pestaña vacía.
        Xtream.Panel panel = Xtream.siActivo(contexto);
        if (panel != null) {
            lista.add(0, new Fuente("Panel Xtream · " + panel.etiqueta(), "xtream:" + panel.base,
                    false, panel));
        }
        // La fuente que el usuario escribió (código FG o URL) manda sobre todo lo demás, pero
        // DENTRO de la cadena: así su lista se cachea y, si el servidor de códigos está caído
        // (fgcode.store responde 500 con su MySQL rechazando conexiones), la pestaña no se
        // queda vacía: sigue con LATAM/Global.
        FuenteCanales manual = FuenteCanales.desdePreferencias(contexto);
        if (!manual.pegada.isEmpty()) {
            lista.add(0, new Fuente("Fuente: " + manual.comoTexto(), null, false, null, manual.pegada));
        }
        if (manual.esPersonalizada() && manual.pegada.isEmpty()) {
            String resuelta = manual.urlResuelta();
            if (!TextUtils.isEmpty(resuelta)) {
                lista.add(0, new Fuente("Fuente: " + manual.comoTexto(), resuelta, false));
            }
        }
        return lista;
    }

    // ─────────────────────────── recorrido ───────────────────────────

    public static void cargarAsync(@NonNull final Context contexto, boolean forzarRed,
                                   @NonNull final Oyente oyente) {
        final List<Fuente> pendientes = candidatos(contexto);
        final Context app = contexto.getApplicationContext();
        ejecutor().execute(() -> recorrer(app, pendientes, forzarRed, oyente));
    }

    private static void recorrer(@NonNull Context app, @NonNull List<Fuente> fuentes,
                                 boolean forzarRed, @NonNull Oyente oyente) {
        List<String> fallos = new ArrayList<>();
        for (Fuente fuente : fuentes) {
            if (fuente.pegada != null) {
                List<Canal> pegados = ListaPegada.desdeTexto(fuente.pegada);
                if (pegados != null && !pegados.isEmpty()) {
                    recordarGanador(app, fuente);
                    limitar(pegados, tope(alcance(app)));
                    final List<Canal> finales = pegados;
                    FuenteCanales.enMain(() -> oyente.onCanales(finales, fuente.etiqueta, false));
                    return;
                }
                fallos.add(fuente.etiqueta + ": el texto pegado no es M3U, JSON ni una URL de stream");
                continue;
            }
            if (fuente.panel != null) {
                try {
                    List<Canal> delPanel = Xtream.cargar(fuente.panel);
                    if (!delPanel.isEmpty()) {
                        recordarGanador(app, fuente);
                        limitar(delPanel, Math.max(tope(alcance(app)), TOPE_GLOBAL));
                        final List<Canal> finales = delPanel;
                        FuenteCanales.enMain(() -> oyente.onCanales(finales, fuente.etiqueta, false));
                        return;
                    }
                    fallos.add(fuente.etiqueta + ": 0 canales");
                } catch (Exception e) {
                    fallos.add(fuente.etiqueta + ": " + mensajeDe(e));
                    Log.w(TAG, "panel xtream -> " + mensajeDe(e));
                }
                continue;
            }
            File cache = archivoCache(app, fuente.url);

            if (cache.exists() && !forzarRed && System.currentTimeMillis() - cache.lastModified() < TTL_CACHE) {
                List<Canal> delCache = leerCache(cache);
                if (!delCache.isEmpty()) {
                    limitar(delCache, tope(alcance(app)));
                    FuenteCanales.enMain(() -> oyente.onCanales(delCache, fuente.etiqueta + " · caché", true));
                    return;
                }
            }

            String cuerpo = null;
            try {
                cuerpo = descargar(app, fuente.url);
            } catch (Exception e) {
                fallos.add(fuente.etiqueta + ": " + mensajeDe(e));
                Log.w(TAG, fuente.url + " -> " + mensajeDe(e));
            }

            if (cuerpo != null) {
                try {
                    List<Canal> canales = FuenteCanales.parsearCuerpo(cuerpo);
                    if (!canales.isEmpty()) {
                        escribirCache(cache, cuerpo);
                        recordarGanador(app, fuente);
                        limitar(canales, tope(alcance(app)));
                        final List<Canal> finales = canales;
                        FuenteCanales.enMain(() -> oyente.onCanales(finales, fuente.etiqueta, false));
                        return;
                    }
                    fallos.add(fuente.etiqueta + ": sin canales");
                } catch (Exception e) {
                    fallos.add(fuente.etiqueta + ": " + mensajeDe(e));
                }
            }

            // Ni la red ni un cuerpo útil: si hay caché viejo, mejor eso que la pantalla vacía.
            List<Canal> rancio = leerCache(cache);
            if (!rancio.isEmpty()) {
                limitar(rancio, tope(alcance(app)));
                final List<Canal> rescatados = rancio;
                FuenteCanales.enMain(() -> oyente.onCanales(rescatados, fuente.etiqueta + " · caché", true));
                return;
            }
        }

        final String resumen = resumir(fallos);
        FuenteCanales.enMain(() -> oyente.onError(resumen));
    }

    @NonNull
    private static String descargar(@NonNull Context app, @NonNull String url) throws IOException {
        try {
            return descargar1(app, url);
        } catch (IOException e) {
            if (FuenteCanales.esFalloDeConexion(e)) {
                String alterno = FuenteCanales.otroEsquema(url);
                if (alterno != null) {
                    try {
                        return descargar1(app, alterno);
                    } catch (IOException e2) {
                        throw new IOException(mensajeDe(e) + " (ni por el otro esquema: "
                                + mensajeDe(e2) + ")");
                    }
                }
            }
            throw e;
        }
    }

    @NonNull
    private static String descargar1(@NonNull Context app, @NonNull String url) throws IOException {
        Request peticion = FuenteCanales.conCabecerasDelUsuario(new Request.Builder().url(url)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android) Blabongo"),
                // las listas públicas no van a necesitarlas, pero un .m3u del proveedor
                // tras un CDN con control de acceso sí: misma fuente de cabeceras que el reproductor.
                FuenteCanales.desdePreferencias(app).encabezados().para(url));
        // v3.2.17: 3 intentos (5 s / 15 s de espera) — corremos en hilo de red, dormir aqui es
        // legitimo; el fallo del primer timeout ya no tumba la descarga de la lista.
        IOException ultimo = null;
        for (int intento = 1; intento <= 3; intento++) {
            try (Response response = FuenteCanales.cliente().newCall(peticion).execute()) {
                String cuerpo = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    IOException e5 = new IOException("HTTP " + response.code());
                    if (response.code() >= 500 && intento < 3) { ultimo = e5; }
                    else throw e5;
                } else {
                    return cuerpo;
                }
            } catch (IOException e) {
                if (ultimo == null || e.getMessage() == null || !e.getMessage().startsWith("HTTP ")) ultimo = e;
                if (intento >= 3) throw ultimo != null ? ultimo : e;
            }
            try { Thread.sleep(intento == 1 ? 5_000L : 15_000L); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw new IOException("cancelado"); }
        }
        throw ultimo != null ? ultimo : new IOException("descarga fallo");
    }

    /** Recorta la lista al tope del catálogo elegido. */
    private static void limitar(@NonNull List<Canal> canales, int tope) {
        while (canales.size() > tope) {
            canales.remove(canales.size() - 1);
        }
    }

    /**
     * Los N grupos con más canales, para las pestañas. Con 2.400 canales hay decenas de
     * `group-title`; si se toman los primeros por orden de aparición salen "Legislative"
     * o "Religious" y se pierden General/Music/News, que son los gordos.
     * Los empates conservan el orden de la lista (más estable que ordenar por nombre).
     */
    @NonNull
    public static List<Map.Entry<String, Integer>> gruposPrincipales(
            @NonNull Map<String, Integer> conteo, int max) {
        List<Map.Entry<String, Integer>> todo = new ArrayList<>(conteo.entrySet());
        todo.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        return todo.size() > max ? new ArrayList<>(todo.subList(0, max)) : todo;
    }

    // ─────────────────────────── caché en disco ───────────────────────────

    @NonNull
    private static File carpetaCache(@NonNull Context app) {
        File carpeta = new File(app.getCacheDir(), "fuentes");
        if (!carpeta.exists()) carpeta.mkdirs();
        return carpeta;
    }

    @NonNull
    private static File archivoCache(@NonNull Context app, @NonNull String url) {
        // El hash del nombre no es criptográfico: solo hay que evitar colisiones triviales.
        String nombre = String.format(Locale.ROOT, "%08x.txt", url.hashCode());
        return new File(carpetaCache(app), nombre);
    }

    @NonNull
    private static List<Canal> leerCache(@NonNull File archivo) {
        try {
            if (!archivo.exists() || archivo.length() > MAX_CACHE) return new ArrayList<>();
            String cuerpo = new String(Files.readAllBytes(archivo.toPath()), StandardCharsets.UTF_8);
            return FuenteCanales.parsearCuerpo(cuerpo);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "caché ilegible: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    private static void escribirCache(@NonNull File archivo, @NonNull String cuerpo) {
        if (cuerpo.length() > MAX_CACHE) return;
        try {
            Files.write(archivo.toPath(), cuerpo.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.w(TAG, "no se pudo guardar el caché: " + e.getMessage());
        }
    }

    public static void vaciarCache(@NonNull Context app) {
        File carpeta = carpetaCache(app);
        File[] archivos = carpeta.listFiles();
        if (archivos != null) {
            for (File a : archivos) {
                //noinspection ResultOfMethodCallIgnored
                a.delete();
            }
        }
    }

    @NonNull
    public static String estadoCache(@NonNull Context app) {
        File[] archivos = carpetaCache(app).listFiles();
        if (archivos == null || archivos.length == 0) return "";
        long vieja = Long.MAX_VALUE;
        for (File a : archivos) vieja = Math.min(vieja, a.lastModified());
        long hace = System.currentTimeMillis() - vieja;
        if (hace < TimeUnit.MINUTES.toMillis(1)) return "hace un momento";
        if (hace < TimeUnit.HOURS.toMillis(1)) return "hace " + TimeUnit.MILLISECONDS.toMinutes(hace) + " min";
        if (hace < TimeUnit.DAYS.toMillis(1)) {
            return "hace " + TimeUnit.MILLISECONDS.toMinutes(hace) / 60 + " h";
        }
        return "hace " + TimeUnit.MILLISECONDS.toDays(hace) + " d";
    }

    private static void recordarGanador(@NonNull Context app, @NonNull Fuente fuente) {
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(CLAVE_GANADOR, fuente.url)
                .putString(CLAVE_GANADOR_ETIQUETA, fuente.etiqueta)
                .apply();
    }

    @NonNull
    private static String mensajeDe(@NonNull Throwable e) {
        String m = e.getMessage();
        return TextUtils.isEmpty(m) ? e.getClass().getSimpleName() : m;
    }

    @NonNull
    private static String resumir(@NonNull List<String> fallos) {
        if (fallos.isEmpty()) return "Ninguna fuente respondió.";
        StringBuilder sb = new StringBuilder();
        int n = Math.min(fallos.size(), MAX_FALLOS_EN_RESUMEN);
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(" · ");
            sb.append(fallos.get(i));
        }
        if (fallos.size() > n) sb.append(" · +").append(fallos.size() - n);
        return sb.toString();
    }

    /**
     * Pool de red. Antes era un ejecutor de UN hilo pedido a un sitio que podia
     * romperse; ahora es el pool acotado y bien configurado de AppExecutors.
     */
    @NonNull
    private static ExecutorService ejecutor() {
        return dza.fut.paipo.core.AppExecutors.red();
    }
}
