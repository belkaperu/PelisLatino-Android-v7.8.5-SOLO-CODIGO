package dza.fut.paipo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.InputStream;

/**
 * El campo "código FG" depende de un servidor ajeno que ahora mismo tiene su MySQL caído.
 * Estos tests cubren las dos cosas que sí dependen de nosotros: cómo se arma la URL a partir
 * de lo que el usuario pega, y cómo se le explica al usuario QUÉ respondió ese servidor.
 */
public class FuentePruebaTest {

    // ── armado de la URL ──────────────────────────────────────────────────────

    @Test
    public void laUrlDirectaMandaSobreElCodigo() {
        assertEquals("https://mi-lista.tv/x.m3u",
                FuenteCanales.resolver("https://mi-lista.tv/x.m3u", "232425", null));
    }

    @Test
    public void elCodigoSeMontaSobreElEndpointDeDefecto() {
        assertEquals("https://fgcode.store/link/api.php/get?code=232425",
                FuenteCanales.resolver("", "232425", ""));
    }

    @Test
    public void elEndpointSeRespetaSegunComoTermine() {
        // Termina en "=" -> el código va pegado, no se vuelve a poner "code=".
        assertEquals("https://otro.host/link/api.php/get?code=AB",
                FuenteCanales.resolver(null, "AB", "https://otro.host/link/api.php/get?code="));
        // Termina en ".php" -> se añade la query.
        assertEquals("https://otro.host/get.php?code=AB",
                FuenteCanales.resolver(null, "AB", "https://otro.host/get.php"));
        // Espacios de sobra al pegar, fuera.
        assertEquals("https://otro.host/get?code=AB",
                FuenteCanales.resolver(null, "  AB  ", "  https://otro.host/get?code="));
    }

    @Test
    public void sePuedePegarLaUrlCompletaEnElCampoCodigo() {
        assertEquals("http://panel.xyz/link/api.php/get?code=9988",
                FuenteCanales.resolver(null, "http://panel.xyz/link/api.php/get?code=9988", null));
    }

    @Test
    public void codigoArrobaHostUsaEseHost() {
        assertEquals("https://fgcode.store/link/api.php/get?code=232425",
                FuenteCanales.resolver("", "232425@fgcode.store", ""));
        assertEquals("https://panel.tv:8443/link/api.php/get?code=7",
                FuenteCanales.resolver("", "7@https://panel.tv:8443/", ""));
    }

    @Test
    public void sinCodigoYSinUrlSeUsaLaListaPorDefecto() {
        assertEquals(FuenteCanales.URL_POR_DEFECTO, FuenteCanales.resolver("", "", ""));
        assertEquals(FuenteCanales.URL_POR_DEFECTO, FuenteCanales.resolver(null, null, null));
    }

    // ── diagnóstico de la respuesta ─────────────────────────────────────────

    @Test
    public void reconoceQueElServidorDeCodigosTieneSuBaseDeDatosCaida() throws Exception {
        String cuerpo;
        try (InputStream in = getClass().getResourceAsStream("/fgcode-500.html")) {
            assertNotNull("falta el fixture /fgcode-500.html", in);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            cuerpo = out.toString("UTF-8");
        }
        String detalle = FuenteCanales.clasificarRespuesta(500, cuerpo);
        assertTrue(detalle, detalle.contains("base de datos caída"));
        assertTrue(detalle, detalle.contains("mysqli"));
        assertTrue(detalle, detalle.contains("no es un problema de la app"));
    }

    @Test
    public void distingueListaBuenaHtmlRotoY404() {
        assertEquals("M3U leída bien",
                FuenteCanales.clasificarRespuesta(200, "#EXTM3U\n#EXTINF:-1,x\nhttp://a/1.ts"));
        assertEquals("JSON de canales",
                FuenteCanales.clasificarRespuesta(200, "{\"canales\":[{\"title\":\"x\"}]}"));
        assertTrue(FuenteCanales.clasificarRespuesta(404, "<html><body>Not Found</body></html>")
                .contains("404"));
        assertEquals("respuesta vacía", FuenteCanales.clasificarRespuesta(0, ""));
        assertEquals("HTTP 500 sin cuerpo", FuenteCanales.clasificarRespuesta(500, "   "));
        // Un 404 dentro de una URL de la lista NO es una respuesta 404.
        assertEquals("M3U leída bien", FuenteCanales.clasificarRespuesta(200,
                "#EXTM3U\n#EXTINF:-1 x,canal\nhttp://h/pelicula-404.ts"));
        assertTrue(FuenteCanales.clasificarRespuesta(200, "no es nada").startsWith("no parece una lista"));
    }

    // ---- v3.2.17: el atajo «fgcode.org/<numero>» apunta a la API, no a la pagina bonita ----
    @Test
    public void normalizaAtajoFgHaciaElEndpointApi() {
        org.junit.Assert.assertEquals("https://fgcode.org/link/api.php/get?code=232425",
                dza.fut.paipo.FuenteCanales.resolver(null, "https://fgcode.org/232425", null));
        org.junit.Assert.assertEquals("https://fgcode.org/link/api.php/get?code=232425",
                dza.fut.paipo.FuenteCanales.resolver("https://fgcode.org/232425", null, null));
        // host ajeno con path numerico: NO se toca
        org.junit.Assert.assertEquals("https://otro.example/123456",
                dza.fut.paipo.FuenteCanales.resolver("https://otro.example/123456", null, null));
        // URL de lista viva detras del mismo host: intacta
        org.junit.Assert.assertEquals("https://fgcode.store/link/lista.m3u8",
                dza.fut.paipo.FuenteCanales.resolver("https://fgcode.store/link/lista.m3u8", null, null));
    }
}
