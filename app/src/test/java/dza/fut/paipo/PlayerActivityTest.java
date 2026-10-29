package dza.fut.paipo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** v3.2.29: la logica pura del relai HLS (swap de segmentos + absolutizado) se prueba
 *  sin contexto Android — es la que convierte el advanced de glasbi en play real. */
public class PlayerActivityTest {

    @Test
    public void nuevasOpcionesCalidadLeenElJsonDelGlasbi() {
        java.util.List<String[]> ops = PlayerActivity.nuevasOpcionesCalidad(
                "[{\"n\":\"HD\",\"u\":\"http://.LS.A\"},{\"n\":\"SD\",\"u\":\"http://.LS.B\"}]");
        assertEquals(2, ops.size());
        assertEquals("HD", ops.get(0)[0]);
        assertEquals("http://.LS.B", ops.get(1)[1]);
        assertTrue(PlayerActivity.nuevasOpcionesCalidad(null).isEmpty());
        assertTrue(PlayerActivity.nuevasOpcionesCalidad("{no-es-array}").isEmpty());
        assertTrue(PlayerActivity.nuevasOpcionesCalidad("[{\"n\":\"HD\"}]").isEmpty());
    }

    @Test
    public void esPaginaPlayerDetectaSoloLaPaginaDelSitio() {
        assertTrue(PlayerActivity.esPaginaPlayerHls("https://sup6.superfastnet.cyou/pages/fnwzpoqozpru/index.html"));
        // v3.2.30: el logcat real mostro /mirsp/ y /malasp2/ con la MISMA papeleta — el gate
        // ya no mira la ruta, solo que sea la pagina index.html del player (y el relay exige
        // que la respuesta empiece con #EXTM3U antes de tocar nada).
        assertTrue(PlayerActivity.esPaginaPlayerHls("https://dat3.datahostingapiplatform.monster/mirsp/fibkkpqmtvsp/index.html"));
        assertTrue(PlayerActivity.esPaginaPlayerHls("https://api4.apidatahostingsolutions.shop/malasp2/idtagclozbrc/index.html"));
        assertFalse(PlayerActivity.esPaginaPlayerHls("https://cdn.example/live/master.m3u8"));
        assertFalse(PlayerActivity.esPaginaPlayerHls("http://file.twoapistack.work/stream/update.mp4"));
        assertFalse(PlayerActivity.esPaginaPlayerHls(null));
    }

    @Test
    public void reescribirPlaylistAplicaSwapYAbsolutiza() {
        String pl = "#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:4.760000,\nk9x_1791793804-1791802092.js\n"
                + "#EXTINF:3.440000,\nk9x_1791793804-1791802093.js\nhttps://otro.cdn/x.ts\n";
        String out = PlayerActivity.reescribirPlaylist(pl, "https://sup6.superfastnet.cyou/pages/fnwzpoqozpru/index.html");
        assertTrue(out.contains("https://sup6.superfastnet.cyou/pages/fnwzpoqozpru/1791793804-1791802092.js"));
        assertTrue(out.contains("https://sup6.superfastnet.cyou/pages/fnwzpoqozpru/1791793804-1791802093.js"));
        assertTrue(out.contains("https://otro.cdn/x.ts"));
        assertFalse(out.contains("k9x_"));
        assertTrue(out.startsWith("#EXTM3U"));
    }

    @Test
    public void reescribirPlaylistInocuaConBasura() {
        assertNull(PlayerActivity.reescribirPlaylist(null, "https://x/index.html"));
        assertNull(PlayerActivity.reescribirPlaylist("", "https://x/index.html"));
        String soloTags = PlayerActivity.reescribirPlaylist("#EXTM3U\n#EXT-X-ENDLIST\n", "https://x/y/index.html");
        assertEquals("#EXTM3U\n#EXT-X-ENDLIST", soloTags.trim());
    }
}
