package dza.fut.paipo;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * v7.8 — EL CATÁLOGO BAJADO DE GITHUB.
 *
 * <p><b>Qué sustituye.</b> La app abría conectando a DramaLive y extrayendo los 265
 * tópicos del índice para tener la rejilla: medido, <b>21 segundos</b>, 265 peticiones
 * en paralelo, más datos y batería — y era el camino que se comía el ANR de la tira
 * de pestañas. Con el catálogo servido desde GitHub es <b>un solo GET</b>.
 *
 * <p><b>De dónde sale.</b> {@code canales-compacto.json}, el fichero que ya está
 * publicado en el repo. Es el mismo esquema que {@code belkaperu/belkafut/data2.json}:
 * {@code categories[]} + {@code channels[]} con {@code servers[]}, y
 * {@code servers[].url} es un {@code dramalive://} que {@link PlayerActivity} ya sabe
 * resolver. El JSON no cambia el reproductor.
 *
 * <p><b>Cómo encaja.</b> Tres capas, de más rápida a más fresca:
 * <ol>
 *   <li><b>assets</b> — el catálogo empaquetado en el APK (v7.7). Aparece al
 *       instante y funciona sin red. Sigue siendo la semilla.</li>
 *   <li><b>disco</b> — la última copia bajada de GitHub. Sobrevive al reinicio.</li>
 *   <li><b>red</b> — un GET al repo. Refresca en segundo plano y pisa la 2.</li>
 * </ol>
 * Si la red falla no se pierde nada: se queda lo que haya y la extracción en vivo
 * de siempre entra como último recurso (ver {@link DramaFondo}).
 *
 * <p><b>Por qué se cachea el JSON y no el m3u.</b> El JSON es lo que se ha bajado y
 * se puede validar tal cual; el m3u se deriva con {@link CatalogoJson#aM3uUnico},
 * que es puro y está en la suite. Así un fichero corrupto se detecta en el mismo
 * momento de bajarlo y no envenena la caché.
 */
public final class CatalogoRemoto {

    private CatalogoRemoto() {}

    private static final String TAG = "CatalogoRemoto";

    /** El catálogo publicado. Rama fija a propósito: un corte estable del repo. */
    public static final String URL =
            "https://raw.githubusercontent.com/youcefyakhoo/cesopppplop/refs/heads/main/canales-compacto.json";

    /** Copia en {@code filesDir} del último JSON que bajó bien. */
    private static final String ARCHIVO = "catalogo_remoto.json";

    /** El m3u derivado, una sola vez por proceso (son ~4 MB de texto). */
    private static volatile String cacheM3u;

    private static volatile OkHttpClient cliente;

    private static OkHttpClient cliente() {
        OkHttpClient c = cliente;
        if (c == null) {
            synchronized (CatalogoRemoto.class) {
                if (cliente == null) {
                    cliente = new OkHttpClient.Builder()
                            .connectTimeout(10, TimeUnit.SECONDS)
                            .readTimeout(30, TimeUnit.SECONDS)
                            .callTimeout(45, TimeUnit.SECONDS)
                            .retryOnConnectionFailure(true)
                            .build();
                }
                c = cliente;
            }
        }
        return c;
    }

    /** ¿Hay una copia de GitHub guardada en disco? */
    public static boolean enDisco(@NonNull Context ctx) {
        return jsonDisco(ctx) != null;
    }

    /**
     * El m3u de la copia guardada en disco, o "" si no hay.
     * Llamar desde un hilo en segundo plano.
     */
    @NonNull
    public static String aM3u(@NonNull Context ctx) {
        String c = cacheM3u;
        if (c != null) return c;
        synchronized (CatalogoRemoto.class) {
            if (cacheM3u != null) return cacheM3u;
            String json = jsonDisco(ctx);
            String m3u = json == null ? "" : CatalogoJson.aM3uUnico(json);
            cacheM3u = m3u;
            return m3u;
        }
    }

    /** Tira la copia en memoria (tras publicar o tras un fallo). */
    public static void invalidar() {
        synchronized (CatalogoRemoto.class) { cacheM3u = null; }
    }

    /**
     * Baja el catálogo y, si sirve, lo guarda en disco.
     *
     * @return el m3u, o {@code null} si no se pudo (sin red, HTTP malo, o el texto
     *         no daba ni un canal). En ese caso NO se toca lo que hubiera en disco.
     */
    @Nullable
    public static String refrescar(@NonNull Context ctx) {
        String json = descargar();
        if (json == null) return null;
        String m3u = CatalogoJson.aM3uUnico(json);
        if (m3u.isEmpty()) {
            Log.w(TAG, "el JSON de GitHub no dio ni un canal (" + json.length() + " bytes)");
            return null;
        }
        if (!guardarDisco(ctx, json)) return null;
        synchronized (CatalogoRemoto.class) { cacheM3u = m3u; }
        Log.i(TAG, "catálogo de GitHub: " + FuenteCanales.contarBloques(m3u) + " canales");
        return m3u;
    }

    /**
     * Un GET al repo. Bloqueante: llamar desde un hilo en segundo plano.
     *
     * @return el texto, o {@code null} si no se pudo
     */
    @Nullable
    public static String descargar() {
        Request req = new Request.Builder()
                .url(URL)
                .header("Accept", "application/json")
                .build();
        try (Response r = cliente().newCall(req).execute()) {
            if (!r.isSuccessful()) {
                Log.w(TAG, "HTTP " + r.code() + " al bajar el catálogo");
                return null;
            }
            ResponseBody b = r.body();
            if (b == null) return null;
            String s = b.string();
            if (s == null) return null;
            s = s.trim();
            // Un portal cautivo o un proxy pueden contestar 200 con HTML: si no
            // empieza por '{' no es JSON y no se guarda.
            if (s.isEmpty() || s.charAt(0) != '{') {
                Log.w(TAG, "la respuesta no es JSON (" + s.length() + " bytes)");
                return null;
            }
            return s;
        } catch (IOException e) {
            Log.w(TAG, "bajando el catálogo: " + e);
            return null;
        } catch (Exception e) {
            Log.w(TAG, "bajando el catálogo (inesperado): " + e);
            return null;
        }
    }

    // ─────────────────────────── disco ───────────────────────────

    @Nullable
    private static String jsonDisco(@NonNull Context ctx) {
        try {
            File f = new File(ctx.getFilesDir(), ARCHIVO);
            if (!f.isFile() || f.length() == 0) return null;
            byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
            if (b.length == 0) return null;
            return new String(b, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean guardarDisco(@NonNull Context ctx, @NonNull String json) {
        try {
            File f = new File(ctx.getFilesDir(), ARCHIVO);
            File tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(json.getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(f)) {
                try (FileOutputStream fos = new FileOutputStream(f)) {
                    fos.write(json.getBytes(StandardCharsets.UTF_8));
                }
                tmp.delete();
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "guardando el catálogo: " + e);
            return false;
        }
    }
}
