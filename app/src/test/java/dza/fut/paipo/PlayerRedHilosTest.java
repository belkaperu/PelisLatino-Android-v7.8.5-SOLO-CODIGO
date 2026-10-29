package dza.fut.paipo;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * v7.3 — dos cosas que el logcat de un telefono real destapo:
 *
 *  1) «relay HLS: primer fetch fallo (android.os.NetworkOnMainThreadException)»:
 *     el relay bajaba la playlist en el hilo principal, asi que nunca se aplicaba
 *     el swap de segmentos k9x_. conRelaySiToca lo saca a un hilo aparte.
 *  2) «el pip siempre en landscap debe ser»: la ventanita flotante salia vertical
 *     con los canales verticales (el piso era el de Android, 0.418).
 */
public class PlayerRedHilosTest {

    /** El predicado que decide si una url es la pagina del player (y por tanto hay
     *  que bajarla por red para el relay). Puro: se prueba sin Android. */
    @Test public void soloLaPaginaDelPlayerVaPorElRelay() {
        assertTrue(PlayerActivity.esPaginaPlayerHls(
                "https://fas6.fastsuperai.cyou/sd/kfnedeqbwlpq/index.html"));
        assertFalse(PlayerActivity.esPaginaPlayerHls("https://x.example/a/master.m3u8"));
        assertFalse(PlayerActivity.esPaginaPlayerHls(null));
        assertFalse(PlayerActivity.esPaginaPlayerHls(""));
    }

    /** «el pip siempre en landscap debe ser». */
    @Test public void elPipSiempreSaleLandscape() {
        // 16:9 se queda como esta
        assertEquals(16f / 9f, PlayerActivity.relacionPip(1920, 1080), 0.001f);
        // un video vertical ya NO da una ventanita alta y estrecha
        assertEquals(1.0f, PlayerActivity.relacionPip(1080, 1920), 0.001f);
        assertEquals(1.0f, PlayerActivity.relacionPip(720, 1280), 0.001f);
        // cuadrado, tambien landscape
        assertEquals(1.0f, PlayerActivity.relacionPip(1000, 1000), 0.001f);
        // sin datos conocidos: 16:9, que es landscape
        assertEquals(16f / 9f, PlayerActivity.relacionPip(0, 0), 0.001f);
        // ultrapanoramico: se recorta al limite de Android, sigue siendo landscape
        assertEquals(2.39f, PlayerActivity.relacionPip(4000, 1000), 0.001f);
        // y nada puede bajar de 1.0
        assertTrue(PlayerActivity.relacionPip(9, 16) >= 1.0f);
    }
}
