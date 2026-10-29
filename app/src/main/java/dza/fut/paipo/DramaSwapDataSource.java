package dza.fut.paipo;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.TransferListener;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/**
 * DataSource que reescribe los PLAYLISTS que devuelve otro DataSource, aplicando
 * el swap de los paneles de Drama ({@link SwapHls}). Envuelve la fabrica de
 * ExoPlayer una sola vez: asi cada recarga del playlist en vivo pasa por aqui y
 * el reproductor JAMAS pide el segmento crudo `k9x_*.js` (404 medido), si no el
 * nombre real post-swap (200 medido, con frames decodificados).
 *
 * Reglas (deliberadamente chicas y explicables):
 *  - Solo se toca la respuesta si la URI parece playlist (acaba en index.html,
 *    .m3u8/.m3u/.php, o no tiene extension) y el cuerpo arranca con #EXTM3U. Los
 *    segmentos (.js/.ts) NO se inspeccionan: pasan de largo sin buffering.
 *  - swap EXPLICITO (los pares del JSON descifrado del enlace) manda siempre;
 *    sin pares, AUTO solo en URIs `.../index.html` (la firma del panel) y solo
 *    si TODAS las lineas-URI comparten el prefijo `algo_DIGITO`.
 *
 * Firmas contra media3 1.10.1 (medido con javap sobre el jar): DataSource.open
 * devuelve long y DataReader lee `int read(byte[], int, int)`.
 */
public final class DramaSwapDataSource implements DataSource {

    /** Playlistes de estos paneles: <1 KB; por encima de esto no se reescribe nada. */
    private static final int MAX_PLAYLIST = 256 * 1024;

    private final DataSource upstream;
    @NonNull private final List<String[]> swaps;

    /** Cuerpo pre-leido en modo passthrough (no-playlist o demasiado grande). */
    @Nullable private ByteArrayInputStream preStream;
    /** Playlist ya reescrito, servido desde memoria. */
    @Nullable private ByteArrayInputStream reescrito;

    private DramaSwapDataSource(@NonNull DataSource upstream, @NonNull List<String[]> swaps) {
        this.upstream = upstream;
        this.swaps = swaps;
    }

    /** Fabrica lista para meter en DefaultMediaSourceFactory. `pares` puede ser null/vacio. */
    @NonNull
    public static DataSource.Factory nuevaFabrica(@NonNull final DataSource.Factory base,
                                                   @Nullable final List<String[]> pares) {
        return new DataSource.Factory() {
            @NonNull
            @Override
            public DataSource createDataSource() {
                return new DramaSwapDataSource(base.createDataSource(),
                        pares == null ? Collections.<String[]>emptyList() : pares);
            }
        };
    }

    @Override
    public void addTransferListener(@NonNull TransferListener transferListener) {
        upstream.addTransferListener(transferListener);
    }

    @Override
    public long open(@NonNull DataSpec dataSpec) throws IOException {
        long upstreamLen = upstream.open(dataSpec);
        if (!puedeSerPlaylist(upstream.getUri())) return upstreamLen;

        // Olfatear: acumular hasta MAX_PLAYLIST bytes del upstream.
        ByteArrayOutputStream buf = new ByteArrayOutputStream(8192);
        byte[] tmp = new byte[16384];
        while (buf.size() < MAX_PLAYLIST) {
            int n = upstream.read(tmp, 0, tmp.length);
            if (n == C.RESULT_END_OF_INPUT || n <= 0) break;
            buf.write(tmp, 0, n);
        }
        boolean desbordo = buf.size() >= MAX_PLAYLIST;
        byte[] pre = buf.toByteArray();

        if (!desbordo && SwapHls.esPlaylist(pre)) {
            String texto = new String(pre, StandardCharsets.UTF_8);
            String nuevo = SwapHls.aplicar(texto, swaps, esIndexHtml(upstream.getUri()));
            reescrito = new ByteArrayInputStream(nuevo.getBytes(StandardCharsets.UTF_8));
            return reescrito.available();
        }
        // No es playlist (o es gigante): esos bytes YA se consumieron del upstream,
        // hay que servirlos primero y seguir por el upstream despues.
        preStream = new ByteArrayInputStream(pre);
        return upstreamLen;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (reescrito != null) {
            int n = reescrito.read(buffer, offset, length);
            return n < 0 ? C.RESULT_END_OF_INPUT : n;
        }
        if (preStream != null) {
            int n = preStream.read(buffer, offset, length);
            if (n > 0) return n;
            preStream = null;
        }
        return upstream.read(buffer, offset, length);
    }

    @Nullable
    @Override
    public Uri getUri() {
        return upstream.getUri();
    }

    @Override
    @NonNull
    public java.util.Map<String, List<String>> getResponseHeaders() {
        return upstream.getResponseHeaders();
    }

    @Override
    public void close() throws IOException {
        try {
            if (reescrito != null) reescrito.close();
            if (preStream != null) preStream.close();
        } finally {
            reescrito = null;
            preStream = null;
            upstream.close();
        }
    }

    /** Los segmentos (.js/.ts/.mp4…) no se olfatean nunca: cero overhead en el gordo. */
    private static boolean puedeSerPlaylist(@Nullable Uri uri) {
        if (uri == null) return false;
        // v7.8: la playlist que el relay dejo en cache YA viene reescrita y
        // absolutizada (reescribirPlaylist). Volver a olfatearla aqui solo puede
        // estropearla: el swap se aplica sobre nombres de segmento que ya los
        // perdieron, y la absolutizacion ya esta hecha.
        if (esLocal(uri)) return false;
        String p = uri.getLastPathSegment();
        if (p == null || p.isEmpty()) return true;
        String l = p.toLowerCase();
        return l.endsWith(".m3u8") || l.endsWith(".m3u") || l.endsWith(".html")
                || l.endsWith(".htm") || l.endsWith(".php") || !l.contains(".");
    }

    /** true si el URI es local: file://, content://, asset:// o sin esquema. */
    static boolean esLocal(@Nullable Uri uri) {
        return uri != null && esquemaLocal(uri.getScheme());
    }

    /**
     * Pura y testeable (sin android.net.Uri): true si el esquema es de fichero
     * local. Misma regla que Util.isLocalFileUri de media3, que es lo que usa
     * DefaultDataSource para decidir a que datasource manda.
     */
    static boolean esquemaLocal(@Nullable String esquema) {
        return esquema == null || esquema.equals("file")
                || esquema.equals("content") || esquema.equals("asset");
    }

    private static boolean esIndexHtml(@Nullable Uri uri) {
        if (uri == null) return false;
        String p = uri.getLastPathSegment();
        return p != null && p.equalsIgnoreCase("index.html");
    }
}
