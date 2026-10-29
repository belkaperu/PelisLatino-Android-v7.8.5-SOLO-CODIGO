package dza.fut.paipo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * El parser tiene que aguantar el formato real de iptv-org, que es lo que alimenta el
 * modo automático: calidades escritas dentro del nombre, grupos compuestos con ';' y
 * el mismo canal repetido una vez por cada resolución.
 */
public class M3uParserTest {

    private static final String M3U =
            "#EXTM3U x-tvg-url=\"https://example.com/epg.xml\"\n"
            + "#EXTINF:-1 tvg-id=\"36TVHuacho.pe@SD\" tvg-logo=\"https://ex.it/36.png\" group-title=\"General\",36 TV Huacho (720p)\n"
            + "https://cdn.example.pe/36tv/playlist.m3u8\n"
            + "#EXTINF:-1 tvg-id=\"36TVHuacho.pe@HD\" tvg-logo=\"https://ex.it/36.png\" group-title=\"General\",36 TV Huacho (1080p)\n"
            + "https://cdn.example.pe/36tv-hd/playlist.m3u8\n"
            + "#EXTINF:-1 tvg-logo=\"https://ex.it/g.png\" group-title=\"Kids;Sports\",Golden\n"
            + "https://cdn.example.pe/golden/index.m3u8\n"
            + "#EXTINF:-1 group-title=\"Undefined\",Sin Clasificar\n"
            + "https://cdn.example.pe/sin/index.m3u8\n"
            + "#EXTVLCOPT:network-caching=1000\n"
            + "#EXTINF:-1,no-url-after-me\n"
            + "esto-no-es-una-url\n";

    @Test
    public void detectaListasYGuardaSoloEntradasConUrl() {
        assertTrue(M3uParser.pareceM3u(M3U));
        assertFalse(M3uParser.pareceM3u("{\"canales\":[]}"));

        List<Canal> canales = M3uParser.parsear(M3U);
        // 3 tiles: las dos resoluciones de 36 TV Huacho se fusionan, y la entrada sin
        // enlace se descarta en vez de inventarse un canal vacío.
        assertEquals(3, canales.size());
    }

    @Test
    public void laCalidadPasaASerEtiquetaDelServidorYNoParteElNombre() {
        List<Canal> canales = M3uParser.parsear(M3U);
        Canal primero = canales.get(0);

        assertEquals("36 TV Huacho", primero.title);
        // Las dos resoluciones del mismo canal son dos servidores de un solo tile,
        // no dos tiles idénticos (así lo muestra DramaLive con "Servers").
        assertEquals(2, primero.options.size());
        assertEquals("720p", primero.options.get(0).label);
        assertEquals("1080p", primero.options.get(1).label);
        assertTrue(primero.options.get(0).url.endsWith("/36tv/playlist.m3u8"));
    }

    @Test
    public void tomaElPrimerGrupoRealYdescartaRelleno() {
        List<Canal> canales = M3uParser.parsear(M3U);
        assertEquals("General", canales.get(0).category);
        assertEquals("Kids", canales.get(1).category);
        assertEquals("", canales.get(2).category);

        // iptv-org pone el grupo PRINCIPAL primero (Outdoor;Sports => canal de "Outdoor"
        // que además aparece en el índice de deportes): el tab usa el primero.
        assertEquals("Outdoor", M3uParser.primerGrupo("Outdoor;Sports"));
        assertEquals("", M3uParser.primerGrupo("Undefined"));
        assertEquals("", M3uParser.primerGrupo(null));
    }

    @Test
    public void usaElNombreDelArchivoCuandoNoHayExtinf() {
        List<Canal> canales = M3uParser.parsear(
                "#EXTM3U\nhttps://cdn.example.pe/otro-canal.m3u8\n");
        assertEquals(1, canales.size());
        assertEquals("otro canal", canales.get(0).title);
    }

    /**
     * Mismo parser, pero contra 40 entradas REALES de la lista de país de iptv-org
     * (recorte commiteado en {@code src/test/resources/pe-mini.m3u}, sin red en el test).
     * Es la forma de asegurar que el modo automático no pinta una rejilla con nombres
     * tipo "Señal XYZ (720p)" ni pestañas "Kids;Sports".
     */
    @Test
    public void parseaLaListaRealDePaisSinBasuraEnNombresYNiGruposCompuestos() throws Exception {
        String m3u;
        try (java.io.InputStream in = getClass().getResourceAsStream("/pe-mini.m3u")) {
            assertNotNull("falta el fixture /pe-mini.m3u", in);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            m3u = out.toString("UTF-8");
        }

        java.util.List<Canal> canales = M3uParser.parsear(m3u);
        assertTrue("la lista real debería dar decenas de canales, no " + canales.size(),
                canales.size() >= 35);

        for (Canal canal : canales) {
            assertFalse("el título no debería conservar la calidad: " + canal.title,
                    canal.title.matches(".*\\(\\s*\\d{3,4}p\\s*\\)\\s*$"));
            assertTrue("sin enlace no hay canal: " + canal.title,
                    canal.options != null && !canal.options.isEmpty());
            assertTrue("URL rara: " + canal.options.get(0).url,
                    canal.options.get(0).url.startsWith("http"));
            assertFalse("grupo compuesto sin partir: " + canal.category,
                    canal.category.contains(";"));
        }

        boolean hayCalidadComoServidor = false;
        for (Canal canal : canales) {
            if (canal.options.get(0).label.matches("\\d{3,4}p")) hayCalidadComoServidor = true;
        }
        assertTrue("la calidad debería aterrizar como etiqueta del servidor", hayCalidadComoServidor);
    }

    // ── pestañas con catálogos gigantes ─────────────────────────────────────

    @Test
    public void lasPestanasSonLosGruposMasPobladosNoLosPrimeros() {
        java.util.Map<String, Integer> conteo = new java.util.LinkedHashMap<>();
        conteo.put("Legislative", 3);
        conteo.put("General", 900);
        conteo.put("Religious", 7);
        conteo.put("Music", 120);
        conteo.put("News", 340);

        java.util.List<String> nombres = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, Integer> e : AutoCanales.gruposPrincipales(conteo, 3)) {
            nombres.add(e.getKey());
        }
        assertEquals(java.util.Arrays.asList("General", "News", "Music"), nombres);
    }

    @Test
    public void siHayPocosGruposSeMuestranTodos() {
        java.util.Map<String, Integer> conteo = new java.util.LinkedHashMap<>();
        conteo.put("General", 90);
        conteo.put("Music", 20);
        assertEquals(2, AutoCanales.gruposPrincipales(conteo, 11).size());
    }


    /** v3.2.5: la rejilla debe CONSOLIDAR los canales dramalive:// del extraido (el
     * filtro vieja los botaba: el m3u guardado tenia 9002 y la rejilla 0). */
    @Test
    public void parsear_conservaCanalesConEsquemaDramaLive() {
        String m3u = "#EXTM3U\n"
                + "#EXTINF:-1 tvg-name=\"Bein S. 2\" group-title=\"hot_now\",Bein S. 2\n"
                + "dramalive://play?id_live=live_tv_beinsport2&u=1&a=all_streams_redirect&t=hot_now\n"
                + "#EXTINF:-1 tvg-name=\"Directo\" group-title=\"g\",Directo\n"
                + "https://example.com/a/index.m3u8\n";
        java.util.List<Canal> cs = M3uParser.parsear(m3u);
        assertEquals(2, cs.size());
        assertEquals("dramalive://play?id_live=live_tv_beinsport2&u=1&a=all_streams_redirect&t=hot_now",
                cs.get(0).options.get(0).url);
        assertEquals("https://example.com/a/index.m3u8", cs.get(1).options.get(0).url);
    }
}
