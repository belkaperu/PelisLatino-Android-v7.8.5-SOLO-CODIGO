package dza.fut.paipo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** v3.2.52: el modelo del panel del circulo es puro y se prueba sin Android. */
public class PanelCategoriasTest {

    private static Map<String, Integer> conteo() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("Deportes", 5);
        m.put("live_tv_peru", 12);
        m.put("974", 9);
        return m;
    }

    @Test
    public void elPanelOrdenaFijosPaisesYLuegoCategorias() {
        List<String[]> filas = PanelCategorias.construir(conteo(), "PE", 0, 0, false, 26);
        assertEquals("fijo", filas.get(0)[0]);
        assertEquals("Todos", filas.get(0)[2]);
        assertEquals("26", filas.get(0)[4]);
        assertEquals("pais", filas.get(1)[0]);
        assertEquals("live_tv_peru", filas.get(1)[1]);
        assertEquals("\uD83C\uDDF5\uD83C\uDDEA", filas.get(1)[3]);
        assertEquals("12", filas.get(1)[4]);
        assertEquals("974", filas.get(2)[1]);
        assertEquals("Qatar", filas.get(2)[2]);
        assertEquals("\uD83C\uDDF6\uD83C\uDDE6", filas.get(2)[3]);
        assertEquals("categoria", filas.get(3)[0]);
        assertEquals("Deportes", filas.get(3)[2]);
    }

    /** v3.2.53: la rejilla del panel — columnas segun ancho, emojis de categorias y
     *  el buscador del panel filtra celdas (Todos sobrevive para poder salir). */
    @Test
    public void laRejillaDelPanelFiltraYEligeColumnas() {
        assertEquals(3, PanelCategorias.columnasPanel(320f));
        assertEquals(4, PanelCategorias.columnasPanel(360f));
        assertEquals(4, PanelCategorias.columnasPanel(411f));
        assertEquals(5, PanelCategorias.columnasPanel(560f));
        assertEquals(6, PanelCategorias.columnasPanel(1080f));
        assertEquals("\u26BD", PanelCategorias.emojiPara("Deportes"));
        assertEquals("\u26BD", PanelCategorias.emojiPara("Bein Sports"));
        assertEquals("\uD83D\uDCF0", PanelCategorias.emojiPara("News"));
        assertEquals("\uD83C\uDF9E", PanelCategorias.emojiPara("Peliculas"));
        assertEquals("", PanelCategorias.emojiPara("NETFLIX")); // sin emoji digno: glifo
        List<String[]> filas = PanelCategorias.construir(conteo(), "PE", 0, 0, false, 26);
        List<String[]> q = PanelCategorias.filtrar(filas, "qa");
        assertEquals(2, q.size());               // Todos (siempre) + Qatar
        assertEquals("Qatar", q.get(1)[2]);
        assertEquals(filas.size(), PanelCategorias.filtrar(filas, "  ").size());
        assertEquals(1, PanelCategorias.filtrar(filas, "zzzz").size()); // solo Todos
    }

    @Test
    public void losFijosSoloAparecenSiHayMaterial() {
        List<String[]> vacio = PanelCategorias.construir(new LinkedHashMap<>(), "PE", 0, 0, false, 0);
        assertEquals(1, vacio.size());                      // solo "Todos"
        assertTrue(vacio.get(0)[2].equals("Todos"));
        List<String[]> conFav = PanelCategorias.construir(new LinkedHashMap<>(), "PE", 3, 2, true, 0);
        assertEquals("Favoritos", conFav.get(1)[2]);
        assertEquals("\u2665", conFav.get(1)[3]);
        assertEquals("Partidos en vivo", conFav.get(3)[2]);
        assertFalse(conFav.get(3)[3].isEmpty());            // llama 🔥
    }

    /** v3.2.56: un pais que la tabla local no conoce entra como PAIS (no categoria)
     *  cuando el manifiesto de Drama trae su url con ISO — y sale con bandera. */
    @Test
    public void unPaisDesconocidoEntraComoPaisSiElManifiestoTieneSuUrl() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("live_tv_armenia", 3);
        Map<String, String> logos = new LinkedHashMap<>();
        logos.put("live_tv_armenia", "http://logo.twoapistack.work/img/topics/ic_flag_am.png");
        java.util.List<String[]> filas =
                PanelCategorias.construir(m, "PE", 0, 0, false, 3, logos);
        String[] fila = null;
        for (String[] f : filas) if ("live_tv_armenia".equals(f[1])) fila = f;
        org.junit.Assert.assertNotNull("armenia debe salir en el panel", fila);
        org.junit.Assert.assertEquals("pais", fila[0]);
        org.junit.Assert.assertEquals("\uD83C\uDDE6\uD83C\uDDF2", fila[3]);
    }

    /** v3.2.59: el catalogo del glt llena el panel con los paises que la extraccion
     *  de tope-40 no trajo — con bandera y sin duplicar a los que ya estan. */
    @Test
    public void elCatalogoSumaLosPaisesQueElFeedNoTrajo() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("Peru", 12);                       // el feed nombra por name_topic
        Map<String, String> logos = new LinkedHashMap<>();
        logos.put("pe", "http://x/ic_flag_pe.png");
        logos.put("cl", "http://x/ic_flag_cl.png");
        Map<String, String> catalogo = new LinkedHashMap<>();
        catalogo.put("pe", "Peru");               // duplicado: mismo rotulo, no entra
        catalogo.put("cl", "Chile");              // falta: entra con bandera
        catalogo.put("hot_now", "Most watched");  // sin bandera: va a categorias
        java.util.List<String[]> filas =
                PanelCategorias.construir(m, "PE", 0, 0, false, 12, logos, catalogo);
        int perus = 0, chiles = 0, hots = 0;
        for (String[] f : filas) {
            if ("Perú".equals(f[2])) perus++;
            if ("Chile".equals(f[2])) { chiles++; org.junit.Assert.assertEquals("pais", f[0]);
                org.junit.Assert.assertEquals("\uD83C\uDDE8\uD83C\uDDF1", f[3]); }
            if ("hot_now".equals(f[1])) { hots++; org.junit.Assert.assertEquals("categoria", f[0]); }
        }
        org.junit.Assert.assertEquals("Peru no debe duplicarse", 1, perus);
        org.junit.Assert.assertEquals("Chile debe entrar del catalogo", 1, chiles);
        org.junit.Assert.assertEquals("la categoria del glt entra al final", 1, hots);
    }
}
