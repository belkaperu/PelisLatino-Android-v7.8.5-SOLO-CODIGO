package dza.fut.paipo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/** v7.7 — el cargador del catálogo empaquetado (puro, sin Android). */
public class CatalogoJsonTest {

    static final String INDICE = "{"
            + "\"categories\":["
            + "{\"display_name\":\"Peru\",\"icon\":\"\\uD83C\\uDDF5\\uD83C\\uDDF7\",\"name\":\"pe\",\"count\":2,\"file\":\"cat/pe.json\"},"
            + "{\"display_name\":\"Most watched\",\"name\":\"hot_now\",\"count\":1,\"file\":\"cat/hot_now.json\"},"
            + "{\"display_name\":\"Vacio\",\"name\":\"zw\",\"count\":0,\"file\":\"cat/zw.json\"}"
            + "]}";

    static final String PE = "{\"category\":\"Peru\",\"channels\":["
            + "{\"category\":\"Peru\",\"imageUrl\":\"http://logo/x.png\",\"name\":\"Latina\","
            + " \"normalized_name\":\"latina\",\"servers\":[{\"name\":\"Drama\","
            + " \"url\":\"dramalive://play?id_live=live_tv_latina&u=1&a=all_streams_redirect\"}]},"
            + "{\"category\":\"Peru\",\"name\":\"Am\\u00e9rica, el \\\"bueno\\\"\","
            + " \"servers\":[{\"name\":\"Drama\","
            + " \"url\":\"dramalive://play?id_live=live_tv_america&u=1&a=all_streams_redirect\"}]}"
            + "]}";

    static final String HOT = "{\"category\":\"Most watched\",\"channels\":["
            + "{\"name\":\"Bein S. 2\",\"imageUrl\":\"http://logo/b.png\","
            + " \"servers\":[{\"url\":\"dramalive://play?id_live=live_tv_beinsport2&u=1&a=all_streams_redirect\"}]}"
            + "]}";

    private Map<String, String> ficheros() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("cat/pe.json", PE);
        m.put("cat/hot_now.json", HOT);
        return m;   // cat/zw.json a proposito ausente (categoria vacia)
    }

    @Test public void elM3uLlevaCabeceraYLosCanales() {
        String m3u = CatalogoJson.aM3u(INDICE, ficheros());
        assertTrue(m3u.startsWith("#EXTM3U\n"));
        assertEquals("2 de Peru + 1 de hot_now", 3, contar(m3u, "#EXTINF:-1"));
        assertEquals(3, contar(m3u, "dramalive://play?"));
    }

    @Test public void cadaFilaLlevaNombreGrupoLogoYUrl() {
        String m3u = CatalogoJson.aM3u(INDICE, ficheros());
        assertTrue(m3u.contains("#EXTINF:-1 tvg-name=\"Latina\" tvg-id=\"live_tv_latina\""
                + " group-title=\"Peru\" tvg-logo=\"http://logo/x.png\",Latina"));
        assertTrue(m3u.contains("dramalive://play?id_live=live_tv_latina&u=1&a=all_streams_redirect"));
        // sin logo no se inventa el atributo
        assertTrue(m3u.contains("tvg-name=\"América, el 'bueno'\" tvg-id=\"live_tv_america\""
                + " group-title=\"Peru\",América, el 'bueno'"));
        // el grupo sale del display_name de la categoria, no del id
        assertTrue(m3u.contains("group-title=\"Most watched\""));
    }

    @Test public void lasComillasSeEscapanParaNoRompirElExtinf() {
        String m3u = CatalogoJson.aM3u(INDICE, ficheros());
        for (String linea : m3u.split("\n")) {
            if (linea.startsWith("#EXTINF:")) {
                // justo 2 comillas de atributo por linea cerrada + las del valor final
                assertTrue("comillas sin escapar en: " + linea, !linea.contains("\"bueno\""));
            }
        }
    }

    @Test public void elIdLiveSeSacaDeLaUrl() {
        assertEquals("live_tv_latina",
                CatalogoJson.idLiveDe("dramalive://play?id_live=live_tv_latina&u=1&a=x"));
        assertEquals("live_tv_latina",
                CatalogoJson.idLiveDe("dramalive://play?id_live=live_tv_latina"));
        assertNull(CatalogoJson.idLiveDe("https://otra/x.m3u8"));
        assertNull(CatalogoJson.idLiveDe(null));
    }

    @Test public void sinCanalesDevuelteVacioNoUnM3uRoto() {
        assertEquals("", CatalogoJson.aM3u("", ficheros()));
        assertEquals("", CatalogoJson.aM3u(INDICE, new LinkedHashMap<>()));
        assertEquals("", CatalogoJson.aM3u("no es json", ficheros()));
        // indice sin categories
        assertEquals("", CatalogoJson.aM3u("{\"categories\":[]}", ficheros()));
    }

    @Test public void losCanalesSinUrlSeSaltan() {
        String roto = "{\"channels\":[{\"name\":\"Sin url\"},{\"name\":\"Sin servers\",\"servers\":[]},"
                + "{\"name\":\"Url vacia\",\"servers\":[{\"url\":\"\"}]},"
                + "{\"servers\":[{\"url\":\"dramalive://play?id_live=x\"}]}]}";
        Map<String, String> m = new LinkedHashMap<>();
        m.put("cat/pe.json", roto);
        assertEquals("", CatalogoJson.aM3u(INDICE, m));
    }

    @Test public void elOrdenSigueElDelIndice() {
        String m3u = CatalogoJson.aM3u(INDICE, ficheros());
        int pe = m3u.indexOf("group-title=\"Peru\"");
        int hot = m3u.indexOf("group-title=\"Most watched\"");
        assertTrue("Peru (primero en el indice) debe ir antes", pe >= 0 && hot >= 0 && pe < hot);
    }

    // ─────────────────── v7.8: el JSON de un solo fichero (GitHub) ───────────────────

    /** El mismo catalogo que INDICE+PE+HOT, pero junto: categories[] y channels[]. */
    static final String UNICO = "{\"categories\":["
            + "{\"display_name\":\"Peru\",\"name\":\"pe\",\"count\":2},"
            + "{\"display_name\":\"Most watched\",\"name\":\"hot_now\",\"count\":1}"
            + "],\"channels\":["
            + "{\"category\":\"Peru\",\"imageUrl\":\"http://logo/x.png\",\"name\":\"Latina\","
            + "\"normalized_name\":\"latina\",\"servers\":[{\"name\":\"Drama\","
            + "\"url\":\"dramalive://play?id_live=live_tv_latina&u=1&a=all_streams_redirect\"}]},"
            + "{\"category\":\"Peru\",\"name\":\"Am\\u00e9rica, el \\\"bueno\\\"\","
            + "\"servers\":[{\"name\":\"Drama\","
            + "\"url\":\"dramalive://play?id_live=live_tv_america&u=1&a=all_streams_redirect\"}]},"
            + "{\"category\":\"Most watched\",\"imageUrl\":\"http://logo/b.png\",\"name\":\"Bein S. 2\","
            + "\"servers\":[{\"url\":\"dramalive://play?id_live=live_tv_beinsport2&u=1&a=all_streams_redirect\"}]}"
            + "]}";

    /**
     * EL contrato: el mismo catalogo, partido por categoria (assets del APK) o
     * junto (el JSON de GitHub), tiene que dar EXACTAMENTE el mismo m3u. Si no,
     * el filtro, la rejilla y la tira de topicos tratarian a los canales de un
     * sitio y a los del otro de forma distinta.
     */
    @Test public void elFicheroUnicoDaElMismoM3uQueElPartido() {
        assertEquals(CatalogoJson.aM3u(INDICE, ficheros()), CatalogoJson.aM3uUnico(UNICO));
    }

    @Test public void elUnicoLlevaCabeceraYAgrupaPorCategoria() {
        String m3u = CatalogoJson.aM3uUnico(UNICO);
        assertTrue(m3u.startsWith("#EXTM3U\n"));
        assertEquals(3, contar(m3u, "#EXTINF:-1"));
        assertEquals(3, contar(m3u, "dramalive://play?"));
        assertTrue(m3u.contains("group-title=\"Peru\""));
        assertTrue(m3u.contains("group-title=\"Most watched\""));
    }

    @Test public void elUnicoSigueElOrdenDelIndiceAunqueVenganMezclados() {
        // los canales salen intercalados: tiene que mandar categories[], no el
        // orden en que esten escritos. El indice dice Zeta primero, luego Alfa.
        String mezclado = "{\"categories\":["
                + "{\"display_name\":\"Zeta\",\"name\":\"z\"},{\"display_name\":\"Alfa\",\"name\":\"a\"}"
                + "],\"channels\":["
                + "{\"category\":\"Alfa\",\"name\":\"uno\",\"servers\":[{\"url\":\"dramalive://play?id_live=u1\"}]},"
                + "{\"category\":\"Zeta\",\"name\":\"dos\",\"servers\":[{\"url\":\"dramalive://play?id_live=u2\"}]},"
                + "{\"category\":\"Alfa\",\"name\":\"tres\",\"servers\":[{\"url\":\"dramalive://play?id_live=u3\"}]}"
                + "]}";
        String m3u = CatalogoJson.aM3uUnico(mezclado);
        int a1 = m3u.indexOf("tvg-name=\"uno\"");
        int z = m3u.indexOf("tvg-name=\"dos\"");
        int a3 = m3u.indexOf("tvg-name=\"tres\"");
        assertTrue("Zeta entero antes que Alfa, como marca el indice",
                a1 >= 0 && z >= 0 && a3 >= 0 && z < a1 && z < a3);
        // y dentro de cada grupo se respeta el orden de los canales
        assertTrue(a1 < a3);
    }

    @Test public void elUnicoNoPierdeUnCanalDeGrupoDesconocido() {
        // el canal llega con una categoria que no esta en el indice: al final,
        // pero dentro, no tirado
        String raro = "{\"categories\":[{\"display_name\":\"Peru\",\"name\":\"pe\"}],"
                + "\"channels\":["
                + "{\"category\":\"Peru\",\"name\":\"Latina\",\"servers\":[{\"url\":\"dramalive://play?id_live=a\"}]},"
                + "{\"category\":\"Fantasia\",\"name\":\"Raro\",\"servers\":[{\"url\":\"dramalive://play?id_live=b\"}]}]}";
        String m3u = CatalogoJson.aM3uUnico(raro);
        assertEquals(2, contar(m3u, "#EXTINF:-1"));
        assertTrue(m3u.contains("group-title=\"Fantasia\""));
    }

    @Test public void elUnicoSinaCategoriaVaAEnVivo() {
        String sin = "{\"categories\":[],\"channels\":["
                + "{\"name\":\"Suelto\",\"servers\":[{\"url\":\"dramalive://play?id_live=a\"}]}]}";
        assertTrue(CatalogoJson.aM3uUnico(sin).contains("group-title=\"En vivo\""));
    }

    @Test public void elUnicoDescartaLoQueNoDaJuego() {
        assertEquals("", CatalogoJson.aM3uUnico(""));
        assertEquals("", CatalogoJson.aM3uUnico("   "));
        assertEquals("", CatalogoJson.aM3uUnico("no es json"));
        assertEquals("", CatalogoJson.aM3uUnico("{\"categories\":[]}"));
        assertEquals("", CatalogoJson.aM3uUnico("{\"channels\":[]}"));
        // lo que contesta un proxy cuando algo va mal: HTML con 200
        assertEquals("", CatalogoJson.aM3uUnico("<html><body>404</body></html>"));
        String roto = "{\"categories\":[{\"display_name\":\"Peru\",\"name\":\"pe\"}],\"channels\":["
                + "{\"category\":\"Peru\",\"name\":\"Sin url\"},"
                + "{\"category\":\"Peru\",\"name\":\"Sin servers\",\"servers\":[]},"
                + "{\"category\":\"Peru\",\"name\":\"Url vacia\",\"servers\":[{\"url\":\"\"}]},"
                + "{\"category\":\"Peru\",\"servers\":[{\"url\":\"dramalive://play?id_live=x\"}]}]}";
        assertEquals("", CatalogoJson.aM3uUnico(roto));
    }

    /** El m3u del fichero unico tambien lo tiene que comer el parser de la app. */
    @Test public void elM3uUnicoSeParseaCanalesDeLaApp() {
        java.util.List<Canal> cs = ListaPegada.desdeTexto(CatalogoJson.aM3uUnico(UNICO));
        assertTrue(cs != null && !cs.isEmpty());
        assertEquals(3, cs.size());
        boolean vioPeru = false, vioHot = false;
        for (Canal c : cs) {
            assertTrue("categoria vacia en " + c.title, c.category != null && !c.category.isEmpty());
            assertTrue("url sin resolver en " + c.title,
                    c.options != null && !c.options.isEmpty()
                            && c.options.get(0).url.startsWith("dramalive://play?id_live="));
            if ("Latina".equals(c.title)) { vioPeru = true; assertEquals("Peru", c.category); }
            if ("Bein S. 2".equals(c.title)) { vioHot = true; assertEquals("Most watched", c.category); }
        }
        assertTrue(vioPeru && vioHot);
    }

    @Test public void elUnicoNoArrastraMarcaDelFormatoAjeno() {
        String m3u = CatalogoJson.aM3uUnico(UNICO);
        assertFalse(m3u.contains("normalized_name"));
        assertFalse(m3u.contains("display_name"));
        assertFalse(m3u.contains("servers"));
    }

    private static int contar(String hay, String aguja) {
        int n = 0, i = 0;
        while ((i = hay.indexOf(aguja, i)) >= 0) { n++; i += aguja.length(); }
        return n;
    }

    @Test public void noArrastraMarcaDelFormatoAjeno() {
        String m3u = CatalogoJson.aM3u(INDICE, ficheros());
        assertFalse(m3u.contains("normalized_name"));
        assertFalse(m3u.contains("display_name"));
        assertFalse(m3u.contains("servers"));
    }

    /**
     * EL contrato que importa: el m3u que sale del JSON empaquetado lo tiene que
     * comer el parser de la app y dar canales con su grupo y su url dramalive://
     * (que es la que resuelve el reproductor).
     */
    @Test public void elM3uSeParseaCanalesDeLaApp() {
        String m3u = CatalogoJson.aM3u(INDICE, ficheros());
        java.util.List<Canal> cs = ListaPegada.desdeTexto(m3u);
        assertTrue(cs != null && !cs.isEmpty());
        assertEquals(3, cs.size());
        for (Canal c : cs) {
            assertTrue("categoria mal puesta en " + c.title,
                    c.category != null && !c.category.isEmpty());
            assertTrue("url sin resolver en " + c.title,
                    c.options != null && !c.options.isEmpty()
                            && c.options.get(0).url.startsWith("dramalive://play?id_live="));
        }
        boolean vioPeru = false, vioHot = false;
        for (Canal c : cs) {
            if ("Latina".equals(c.title)) {
                vioPeru = true;
                assertEquals("Peru", c.category);
            }
            if ("Bein S. 2".equals(c.title)) vioHot = true;
        }
        assertTrue(vioPeru && vioHot);
    }
}
