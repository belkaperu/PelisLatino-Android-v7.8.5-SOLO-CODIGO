package dza.fut.paipo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** v3.2.5: el normalizador del tap no debe corromper el esquema propio del app. */
public class CanalAdapterTest {

    @Test
    public void normalizar_dejaIntactoElEsquemaDramaLive() {
        String u = "dramalive://play?id_live=x&u=1&a=all_streams_redirect&t=hot_now";
        assertEquals(u, CanalAdapter.normalizarUrlCanal(u, null));
        // y sigue normalizando lo de siempre
        assertEquals("https://example.com/intro.m3u8",
                CanalAdapter.normalizarUrlCanal("/p/x.html?r=https://example.com/intro.m3u8", null));
        assertNull(CanalAdapter.normalizarUrlCanal(null, null));
    }
}
