package dza.fut.paipo;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * v7.7 — las TRES formas del {@code live.url} del glasbi, medidas sobre 150 canales
 * del catálogo real:
 * <pre>
 *  (a) marcador  "http://.LS.V2.../s"   agent "redirect"      6,7 %
 *  (b) directa   "http://host/index.m3u8"                    35,3 %
 *  (c) advanced  "{\"url\":\"...\",\"agent\":\"Mozilla\",...}"  56,7 %
 * </pre>
 *
 * <p>El bug: {@code vivoDesdeGlasbi} exigía {@code .LS.} y devolvía null en (b) y (c),
 * el 93 % de los canales. El llamador caía al glbr pelado, que contesta el clip de
 * relleno {@code update.mp4}, y de ahí al captador headless. Estos tests fijan que las
 * tres formas se acepten.
 */
public class GlasbiFormasTest {

    private static String envuelve(String live) {
        return "{\"result\":0,\"message\":{\"en\":\"operation succeeded\"},\"live\":" + live + "}";
    }

    // ───────────────────────── (a) marcador .LS. ─────────────────────────
    @Test public void marcadorLsSeAcepta() {
        String claro = envuelve("{\"type\":\"tv\",\"id_live\":\"live_tv_beinsport2\","
                + "\"name\":\"Bein S. 2\","
                + "\"url\":\"http://.LS.V2live_tv_custom_handler_live_tv_beinsport2_description_SD_/s\","
                + "\"agent\":\"redirect\"}");
        String[] r = DramaSesion.vivoDesdeGlasbi(claro);
        assertTrue(r != null);
        assertTrue("el marcador se devuelve tal cual para la segunda vuelta",
                r[0].contains(".LS."));
        assertArrayEquals("el agent del marcador es redirect",
                new String[]{"http://.LS.V2live_tv_custom_handler_live_tv_beinsport2_description_SD_/s", "redirect"},
                new String[]{r[0], r[1]});
    }

    // ───────────────────────── (b) url directa ─────────────────────────
    @Test public void urlDirectaSeAcepta() {
        String claro = envuelve("{\"type\":\"tv\",\"id_live\":\"live_tv_new_auto_96c5\","
                + "\"name\":\"LNK\",\"url\":\"http://stream.mcquack.net/30/index.m3u8\","
                + "\"agent\":\"Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/149\"}");
        String[] r = DramaSesion.vivoDesdeGlasbi(claro);
        assertTrue(r != null);
        assertArrayEquals(new String[]{"http://stream.mcquack.net/30/index.m3u8",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/149"},
                new String[]{r[0], r[1]});
    }

    /** El caso que se perdía: 35 % de los canales medidos. */
    @Test public void urlDirectaSinPuntoLSYaNoSeDescarta() {
        String claro = envuelve("{\"name\":\"BabyFirst Spanish\","
                + "\"url\":\"https://st2.mediabay.tv/KG_KTRK-Ala-too/playlist.m3u8\","
                + "\"agent\":\"Mozilla\"}");
        String[] r = DramaSesion.vivoDesdeGlasbi(claro);
        assertTrue("antes devolvía null y el canal se iba al sueno update.mp4", r != null);
        assertTrue(r[0].startsWith("https://st2.mediabay.tv/"));
    }

    // ───────────────────────── (c) advanced json ─────────────────────────
    @Test public void advancedJsonSeDesempaqueta() {
        String claro = envuelve("{\"name\":\"TV Aldeia\",\"agent\":\"advanced\","
                + "\"url\":\"{\\\"url\\\":\\\"https://stmv1.samcast.com.br/giovane7843/playlist.m3u8\\\","
                + "\\\"agent\\\":\\\"Mozilla\\\",\\\"acceptSSL\\\":\\\"1\\\","
                + "\\\"headers\\\":{\\\"User-Agent\\\":\\\"Mozilla\\\"}}\"}");
        String[] r = DramaSesion.vivoDesdeGlasbi(claro);
        assertTrue("el advanced se tragaba entero y no empezaba por http", r != null);
        assertArrayEquals(new String[]{"https://stmv1.samcast.com.br/giovane7843/playlist.m3u8", "Mozilla"},
                new String[]{r[0], r[1]});
    }

    /** El advanced sin agent dentro se queda con el de fuera ("advanced"). */
    @Test public void advancedSinAgentUsaElDeFuera() {
        String claro = envuelve("{\"name\":\"X\",\"agent\":\"advanced\","
                + "\"url\":\"{\\\"url\\\":\\\"https://a.example/x.m3u8\\\"}\"}");
        String[] r = DramaSesion.vivoDesdeGlasbi(claro);
        assertTrue(r != null);
        assertArrayEquals(new String[]{"https://a.example/x.m3u8", "advanced"},
                new String[]{r[0], r[1]});
    }

    // ───────────────────────── opciones ─────────────────────────
    @Test public void lasOpcionesSalenDelBackup() {
        String claro = envuelve("{\"name\":\"Canal\",\"agent\":\"redirect\","
                + "\"url\":\"http://.LS.V2x_description_HD_/s\","
                + "\"backup\":\"http://.LS.V2x_description_SD_/s -;- {iframe:basura} -;- \"}");
        String[] r = DramaSesion.vivoDesdeGlasbi(claro);
        assertTrue(r != null);
        assertTrue("las dos variantes utiles, sin el iframe",
                r[2].contains("HD") && r[2].contains("SD") && !r[2].contains("basura"));
    }

    // ───────────────────────── lo que sigue siendo null ─────────────────────────
    @Test public void respuestasMalasSiguenSiendoNull() {
        assertNull(DramaSesion.vivoDesdeGlasbi(null));
        assertNull(DramaSesion.vivoDesdeGlasbi(""));
        assertNull(DramaSesion.vivoDesdeGlasbi("no es json"));
        assertNull(DramaSesion.vivoDesdeGlasbi("{\"result\":1}"));
        assertNull(DramaSesion.vivoDesdeGlasbi("{\"result\":0}"));            // sin live
        assertNull(DramaSesion.vivoDesdeGlasbi(envuelve("{\"name\":\"X\"}"))); // sin url
        assertNull(DramaSesion.vivoDesdeGlasbi(envuelve("{\"name\":\"X\",\"url\":\"\"}")));
        assertNull(DramaSesion.vivoDesdeGlasbi(envuelve("{\"name\":\"X\",\"url\":\"javascript:algo\"}")));
    }

    /** El urlYAgenteDe compartido con el camino del glbr. */
    @Test public void urlYAgenteDesempaquetaLasTresFormas() {
        assertArrayEquals(new String[]{"http://a.example/x.m3u8", "UA"},
                DramaSesion.urlYAgenteDe("http://a.example/x.m3u8", "UA"));
        assertArrayEquals(new String[]{"http://a.example/x.m3u8", "Mozilla"},
                DramaSesion.urlYAgenteDe("{\"url\":\"http://a.example/x.m3u8\",\"agent\":\"Mozilla\"}", "redirect"));
        assertArrayEquals(new String[]{"http://a.example/x.m3u8", "redirect"},
                DramaSesion.urlYAgenteDe("{\"url\":\"http://a.example/x.m3u8\"}", "redirect"));
        assertNull(DramaSesion.urlYAgenteDe(null, "UA"));
        assertNull(DramaSesion.urlYAgenteDe("", "UA"));
        assertNull(DramaSesion.urlYAgenteDe("ftp://a.example/x", "UA"));
        assertNull(DramaSesion.urlYAgenteDe("{json roto", "UA"));
    }
}
