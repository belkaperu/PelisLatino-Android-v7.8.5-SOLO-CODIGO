package dza.fut.paipo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * Pegar una lista o UNA sola URL de stream: la vía que queda cuando el portal del usuario
 * no expone su lista. Se prueba con texto real, sin red.
 */
public class ListaPegadaTest {

    @Test
    public void unM3uPegadoSeParseaIgualQueDescargado() {
        String m3u = "#EXTM3U\n"
                + "#EXTINF:-1 tvg-logo=\"http://x/i.png\" group-title=\"Dep\",ESPN HD (720p)\n"
                + "http://a/1.m3u8\n"
                + "#EXTINF:-1 group-title=\"Dep\",Golf\nhttp://a/2.m3u8\n";
        List<Canal> canales = ListaPegada.desdeTexto(m3u);
        assertNotNull(canales);
        assertEquals(2, canales.size());
        assertEquals("ESPN HD", canales.get(0).title);
        assertEquals("Dep", canales.get(0).category);
        assertEquals(2, ListaPegada.contar(m3u));
    }

    @Test
    public void unaSolaUrlDeStreamSeConvierteEnUnCanal() {
        List<Canal> canales = ListaPegada.desdeTexto(
                "https://cdn.tv/live/ESPN/index.m3u8?token=99&t=1");
        assertNotNull("no debería exigir el #EXTM3U para una URL suelta", canales);
        assertEquals(1, canales.size());
        Canal c = canales.get(0);
        assertEquals("ESPN", c.title);
        assertEquals("Pegado", c.category);
        assertEquals(1, c.options.size());
        assertEquals("Directo", c.options.get(0).label);
        assertEquals("https://cdn.tv/live/ESPN/index.m3u8?token=99&t=1", c.options.get(0).url);
    }

    @Test
    public void variasUrlsUnaPorLineaYbasuraIgnorada() {
        String texto = "\n# esto es un comentario\n"
                + "http://a/live/gol.ts\n"
                + "  https://b/live/bein/index.m3u8  \n"
                + "no-es-una-url\n";
        List<Canal> canales = ListaPegada.desdeTexto(texto);
        assertNotNull(canales);
        assertEquals(2, canales.size());
        assertEquals("Gol", canales.get(0).title);
        assertEquals("Bein", canales.get(1).title);
    }

    @Test
    public void elJsonDeLaAppTambienSirve() {
        String json = "{\"canales\":[{\"title\":\"X\",\"category\":\"Y\","
                + "\"options\":[{\"label\":\"s1\",\"url\":\"http://a/1.ts\"}]}]}";
        List<Canal> canales = ListaPegada.desdeTexto(json);
        assertNotNull(canales);
        assertEquals(1, canales.size());
        assertEquals("X", canales.get(0).title);
    }

    @Test
    public void loQueNoEsListaSeRechazaSinExplotar() {
        assertNull(ListaPegada.desdeTexto(null));
        assertNull(ListaPegada.desdeTexto(""));
        assertNull(ListaPegada.desdeTexto("hola qué tal"));
        assertNull(ListaPegada.desdeTexto("ftp://x/y"));
        assertEquals(0, ListaPegada.contar("basura"));
    }

    @Test
    public void nombresAbsurdosDeUrlNoSalen() {
        // Todos los segmentos genéricos: cae al host.
        assertEquals("Cdn.tv", ListaPegada.nombreDesdeUrl("https://cdn.tv/live/index.m3u8"));
        // Archivo con nombre de canal: se usa tal cual, sin extensión.
        assertEquals("22970", ListaPegada.nombreDesdeUrl("http://h/stream/22970.ts"));
        // Sin ruta: host.
        assertEquals("Mi-panel.net", ListaPegada.nombreDesdeUrl("http://mi-panel.net"));
        assertTrue(ListaPegada.nombreDesdeUrl("https://h/" + "x".repeat(120)).length() <= 48);
    }

    @Test
    public void desdeTexto_pasaElCanalDramaLiveHastaLaRejilla() {
        // El camino EXACTO de la fusion con la lista extraida (conLaListaDrama →
        // ListaPegada.desdeTexto → M3uParser.parsear): antes el parser filtraba el
        // esquema y la fusion recibia una lista vacia — «dice 9002 y no veo ninguno».
        String m3u = DramaSesion.aM3u(java.util.Collections.singletonList(canalDrama()));
        java.util.List<Canal> cs = ListaPegada.desdeTexto(m3u, null);
        assertEquals(1, cs.size());
        assertTrue(cs.get(0).options.get(0).url.startsWith("dramalive://play?id_live=live_tv_beinsport2"));
    }

    private static DramaSesion.CanalDrama canalDrama() {
        DramaSesion.CanalDrama c = new DramaSesion.CanalDrama();
        c.name = "Bein S. 2";
        c.url = "dramalive://play?id_live=live_tv_beinsport2&u=1&a=all_streams_redirect&t=hot_now";
        c.group = "hot_now";
        c.logo = null;
        return c;
    }
}
