package dza.fut.paipo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * El único beIN legal y público que aparece en los índices abiertos es beIN SPORTS XTRA (FAST
 * de AMAGI, con anuncios, operado por el propio beIN). Este test fija que la app lo pinta bien
 * y que la basura de nombres que trae el índice arriba no acaba en un tile.
 */
public class BeinCanalesTest {

    private static String fixture() throws Exception {
        try (java.io.InputStream in = BeinCanalesTest.class.getResourceAsStream("/bein-mini.m3u")) {
            assertNotNull("falta el fixture /bein-mini.m3u", in);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    @Test
    public void elBeinLegalQuedaConNombreLimpioYEnSuTab() throws Exception {
        List<Canal> canales = M3uParser.parsear(fixture());
        assertEquals(3, canales.size());

        Canal xtra = canales.get(0);
        assertEquals("beIN SPORTS XTRA", xtra.title);           // sin el (1080p) pegado
        assertEquals("1080p", xtra.calidad);
        assertEquals("Sports", xtra.category);                  // → pestaña Sports
        assertEquals("https://bein-xtra-bein.amagi.tv/playlist.m3u8", xtra.options.get(0).url);
        assertTrue("el logo viene de tvg-logo", xtra.image.contains("amagi"));
    }

    @Test
    public void laBasuraDeNombreNoSePintaEnElTile() throws Exception {
        List<Canal> canales = M3uParser.parsear(fixture());
        Canal chorizo = canales.get(1);
        assertFalse("el User-Agent del índice no es un nombre de canal: " + chorizo.title,
                chorizo.title.toLowerCase().contains("gecko"));
        assertFalse(chorizo.title.toLowerCase().contains("chrome"));
        assertEquals("box1", chorizo.title);                     // rescatado de tvg-id
    }

    @Test
    public void elEspejoEnEspanolTambienSeLimpia() throws Exception {
        List<Canal> canales = M3uParser.parsear(fixture());
        Canal es = canales.get(2);
        assertEquals("beIN SPORTS XTRA en Espanol", es.title);
        assertEquals("1080p", es.calidad);
    }

    @Test
    public void nombresUsablesYSucios() {
        assertTrue(M3uParser.esNombreUsable("beIN SPORTS XTRA"));
        assertTrue(M3uParser.esNombreUsable("36 TV Huacho (720p)"));
        assertFalse(M3uParser.esNombreUsable(""));
        assertFalse(M3uParser.esNombreUsable(null));
        assertFalse(M3uParser.esNombreUsable("like Gecko) Chrome/130.0.0.0 Safari/537.36"));
        assertFalse(M3uParser.esNombreUsable("Mozilla/5.0 (Linux; Android 13)"));
        assertFalse(M3uParser.esNombreUsable("https://nord.ayakkabiparti.lol/box1/index.m3u8"));
    }
}
