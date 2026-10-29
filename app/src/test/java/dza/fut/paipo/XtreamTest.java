package dza.fut.paipo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * El API de panel que usa DramaLive por dentro (player_api.php). Se prueba con el JSON de
 * ejemplo de Xtream Codes: sin red y sin credenciales de nadie.
 */
public class XtreamTest {

    private static String recurso(String nombre) throws Exception {
        try (java.io.InputStream in = XtreamTest.class.getResourceAsStream(nombre)) {
            assertNotNull("falta el fixture " + nombre, in);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    private static Xtream.Panel PANEL = new Xtream.Panel("http://panel.test:8080", "u", "p");

    @Test
    public void construyeLasUrlsDelPanel() {
        assertEquals("http://panel.test:8080/live/u/p/1234.m3u8", PANEL.urlDirecto("1234"));
        String api = PANEL.apiUrl("get_live_streams");
        assertTrue(api, api.contains("username=u") && api.contains("password=p"));
        assertTrue(api, api.endsWith("action=get_live_streams"));
        assertFalse(PANEL.completa() && PANEL.clave.isEmpty());
    }

    @Test
    public void traduceStreamsYQuedaVodFuera() throws Exception {
        List<Canal> canales = Xtream.desdeJson(PANEL, recurso("/xtream-cat.json"), recurso("/xtream-live.json"));
        assertEquals(3, canales.size());

        Canal espn = canales.get(0);
        assertEquals("ESPN HD", espn.title);                 // el (720p) pasa a etiqueta
        assertEquals("720p", espn.calidad);
        assertEquals("Deportes", espn.category);             // category_id -> category_name
        assertEquals("http://panel.test/icons/espn.png", espn.image);
        assertEquals(1, espn.options.size());
        assertEquals("720p", espn.options.get(0).label);
        assertEquals("http://panel.test:8080/live/u/p/1234.m3u8", espn.options.get(0).url);

        Canal gol = canales.get(1);
        assertNull("los iconos vacíos no son URL", gol.image);
        assertEquals("Xtream", gol.options.get(0).label);

        Canal series = canales.get(2);
        assertEquals("Series [FHD]", series.title);
        assertEquals("Entretenimiento", series.category);
        assertEquals("http://panel.test/i/88.png", series.image);
    }

    @Test
    public void unJsonRotoNoTumbaLaLista() throws Exception {
        assertTrue(Xtream.desdeJson(PANEL, "no es json", "[]").isEmpty());
        assertTrue(Xtream.desdeJson(PANEL, null, null).isEmpty());
        assertTrue(Xtream.desdeJson(PANEL, "[]", "[{\"name\":\"sin id\"}]").isEmpty());
    }

    @Test
    public void authAceptaEnabledQueEsLoQueDevuelveElApi() {
        assertNull(Xtream.revisarAuth("{\"user_info\":{\"status\":\"Enabled\"},\"server_info\":{\"url\":\"http://x\"}}"));
        assertNull(Xtream.revisarAuth("{\"user_info\":[],\"server_info\":{\"url\":\"http://x\"}}"));
        assertEquals("panel: Expired", Xtream.revisarAuth("{\"user_info\":{\"status\":\"Expired\"}}"));
        assertEquals("el panel rechazó las credenciales", Xtream.revisarAuth("{\"errors\":[\"Invalid Login\"]}"));
        assertEquals("el panel devolvió HTML (¿URL correcta?)", Xtream.revisarAuth("<html>404</html>"));
        assertEquals("el panel no respondió nada", Xtream.revisarAuth(""));
    }

    @Test
    public void aceptaLoQueElUsuarioPegaDelPanel() {
        // URL completa que da el panel: se sacan usuario y contraseña de la query.
        Xtream.Panel a = Xtream.normalizar(
                "http://panel.test:8080/player_api.php?username=pepe&password=secreto&action=get_live_streams", "", "");
        assertEquals("http://panel.test:8080", a.base);
        assertEquals("pepe", a.usuario);
        assertEquals("secreto", a.clave);
        assertTrue(a.completa());

        // user:pass@host, la forma m3u
        Xtream.Panel b = Xtream.normalizar("geo.panel.xyz:25461/", "ana", "1234");
        assertEquals("http://geo.panel.xyz:25461", b.base);
        assertEquals("ana", b.usuario);

        // la URL del stream, tal cual sale de un .m3u del panel
        Xtream.Panel c = Xtream.normalizar("user:pass@mi-panel.net/live/user/pass/1234.ts", "", "");
        assertEquals("http://mi-panel.net", c.base);
        assertEquals("user", c.usuario);

        Xtream.Panel d = Xtream.normalizar("https://mi-panel.net/get.php?username=x&password=y&type=m3u_plus", "", "");
        assertEquals("https://mi-panel.net", d.base);
        assertEquals("x", d.usuario);

        assertTrue(Xtream.normalizar("", "", "").base.isEmpty());
        assertFalse(Xtream.normalizar("http://h", "u", "").completa());
    }
}
