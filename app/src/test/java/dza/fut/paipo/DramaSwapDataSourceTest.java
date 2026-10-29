package dza.fut.paipo;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * v7.8 — el guardián que faltaba en el DataSource.
 *
 * <p>El relai HLS ({@code PlayerActivity.relayHlsSiPagina}) baja la playlist del
 * panel, le aplica el swap, la absolutiza, la escribe en cache y apunta a
 * {@code file:///.../drama_relay.m3u8}. Ese fichero tiene extensión {@code .m3u8},
 * que es justo la firma con la que {@link DramaSwapDataSource} decide si un cuerpo
 * es playlist y hay que reescribirlo. Sin el guardián, la playlist ya reescrita
 * pasaba otra vez por el swap.
 *
 * <p>Y el transporte: la cadena de datos del reproductor era solo HTTP, así que
 * abrir ese {@code file://} reventaba con
 * {@code ClassCastException: FileURLConnection cannot be cast to HttpURLConnection}
 * (medido en el logcat real del usuario). De eso se encarga
 * {@code DefaultDataSource}, que enruta por esquema.
 */
public class DramaSwapDataSourceTest {

    @Test
    public void losEsquemasLocalesSeReconocen() {
        assertTrue(DramaSwapDataSource.esquemaLocal("file"));
        assertTrue(DramaSwapDataSource.esquemaLocal("content"));
        assertTrue(DramaSwapDataSource.esquemaLocal("asset"));
        assertTrue(DramaSwapDataSource.esquemaLocal(null));   // sin esquema
    }

    @Test
    public void losEsquemasDeRedNoSonLocales() {
        assertFalse(DramaSwapDataSource.esquemaLocal("http"));
        assertFalse(DramaSwapDataSource.esquemaLocal("https"));
        assertFalse(DramaSwapDataSource.esquemaLocal("rtmp"));
        assertFalse(DramaSwapDataSource.esquemaLocal("udp"));
        assertFalse(DramaSwapDataSource.esquemaLocal("dramalive"));
    }

    /**
     * El nombre del fichero que deja el relai: si el guardián no estuviera, la
     * extension .m3u8 lo marcaria como playlist reescribible.
     */
    @Test
    public void elNombreDelRelayParecePlaylistPeroEsLocal() {
        String nombre = "drama_relay.m3u8";
        assertTrue(nombre.toLowerCase().endsWith(".m3u8"));
        // y el esquema con el que se abre es file://
        assertTrue(DramaSwapDataSource.esquemaLocal("file"));
    }
}
