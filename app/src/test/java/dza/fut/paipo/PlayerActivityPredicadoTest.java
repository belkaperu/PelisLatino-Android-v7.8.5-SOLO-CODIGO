package dza.fut.paipo;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** v3.2.7: la eleccion HLS/progresivo del reproductor, sin Android. */
public class PlayerActivityPredicadoTest {

    @Test
    public void contenedorDeMedia_detectaElSuenoYLosMp4Reales() {
        assertTrue(PlayerActivity.esContenedorDeMedia("http://file.twoapistack.work/stream/update.mp4"));
        assertTrue(PlayerActivity.esContenedorDeMedia("https://cdn/x/pelicula.MP4"));
        assertTrue(PlayerActivity.esContenedorDeMedia("https://cdn/x/peli.mp4?token=abc"));
        assertFalse(PlayerActivity.esContenedorDeMedia("https://x/playlist.php?id=9&sig=ab.mp4x"));
        assertFalse(PlayerActivity.esContenedorDeMedia("https://x/a/index.m3u8"));
        assertFalse(PlayerActivity.esContenedorDeMedia("https://x/playlist.php?id=9"));
        assertFalse(PlayerActivity.esContenedorDeMedia(null));
    }
}
