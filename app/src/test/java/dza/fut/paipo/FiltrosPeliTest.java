package dza.fut.paipo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import org.junit.Test;

/**
 * v3.2.35 — el filtro por años sale del propio catálogo (los campos que parseItem
 * ya lee) y el encabezado de búsqueda/filtros nace escondido; el carrete que se
 * veia «tachado» sobre cada póster y sobre la rejilla queda erradicado.
 *
 * <p>Nota metodológica: los asserts de archivo miran PATRONES DE ATRIBUTO reales
 * (android:drawable="@drawable/…"), nunca la palabra suelta — un comentario que
 * mencione el icono no debe hacer fallar la guarda (nos mordió en v3.2.33).
 */
public class FiltrosPeliTest {

    @Test
    public void losAniosSalenDelCatalogoOrdenadosYContados() {
        JsonObject root = JsonParser.parseString("{\"movies\":["
                + "{\"title\":\"a\",\"release_date\":\"2024-05-01\"},"
                + "{\"title\":\"b\",\"year\":\"2023\"},"
                + "{\"title\":\"c\",\"anio\":\"2024\"},"
                + "{\"title\":\"d\"}],"
                + "\"series\":[{\"title\":\"e\",\"first_air_date\":\"2019-01-01\"}]}")
                .getAsJsonObject();
        List<PelisApi.OpcionFiltro> anos = PelisApi.aniosDelCatalogo(root);
        assertEquals(3, anos.size());
        assertEquals(2024, anos.get(0).id);      // descendente, como la web
        assertEquals(2, anos.get(0).cantidad);   // dos títulos de 2024
        assertEquals(2023, anos.get(1).id);
        assertEquals(2019, anos.get(2).id);
        assertEquals("2024", anos.get(0).nombre);
        assertTrue(PelisApi.aniosDelCatalogo(new JsonObject()).isEmpty()); // sin catálogo, sin años
    }

    @Test
    public void elFantasmaDelCarreteFueErradicado() throws Exception {
        String ph = leer("drawable/bg_pelis_poster_placeholder.xml");
        assertFalse("el placeholder del póster no puede llevar drawable centrado (se veía tachado)",
                ph.contains("android:drawable="));
        String layout = leer("layout/fragment_pelis.xml");
        assertFalse("el estado vacío no puede pintar el logo encima de la rejilla",
                layout.contains("@drawable/ic_pelis\""));
    }

    @Test
    public void elPanelDeBusquedaYFiltrosNaceEscondidoConSuTira() throws Exception {
        String layout = leer("layout/fragment_pelis.xml");
        assertTrue("el panel plegable debe existir", layout.contains("@+id/panelFiltrosPelis"));
        assertTrue("el chip de año como los de género y país",
                layout.contains("@+id/btnFiltroAnioPelis"));
        assertTrue("el botón que muestra/esconde", layout.contains("@+id/btnToggleFiltrosPelis"));
        String strings = leer("values/strings.xml");
        assertTrue(strings.contains("pelis_filtro_anio"));
        assertTrue(strings.contains("pelis_filtros_toggle"));
    }

    @Test
    public void elSelectorDeAnioNuncaQuedaSinOpciones() {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        int actual = cal.get(java.util.Calendar.YEAR);
        List<PelisApi.OpcionFiltro> anos = PelisApi.aniosPorDefecto();
        assertTrue("tiene que haber años de sobra", anos.size() >= 40);
        assertEquals(actual, anos.get(0).id);            // este año primero
        assertEquals(String.valueOf(actual), anos.get(0).nombre);
        for (int i = 1; i < anos.size(); i++) {
            assertEquals("descendiente sin huecos", anos.get(i - 1).id - 1, anos.get(i).id);
        }
    }

    @Test
    public void laCabeceraDeTextoFueBorrada() throws Exception {
        String layout = leer("layout/fragment_pelis.xml");
        for (String id : new String[]{"txtCatalogoOverline", "txtCatalogoTitulo",
                "txtCatalogoDescripcion"}) {
            assertFalse("el rotulo «" + id + "» fuera por orden del usuario",
                    layout.contains("@+id/" + id));
        }
        assertTrue("el panel absorbe el fondo de cine del encabezado",
                layout.contains("@drawable/bg_header_cine"));
        assertTrue("y sigue siendo el unico panel plegable",
                layout.contains("@+id/panelFiltrosPelis"));
    }

    @Test
    public void laTiraDeAniosNoNecesitaDialogo() throws Exception {
        String layout = leer("layout/fragment_pelis.xml");
        assertTrue("la tira de anos vive dentro del panel plegable",
                layout.contains("@+id/tiraAniosPelis"));
        // desde res/ subimos a src/main y bajamos al java: la misma raiz que uso arriba
        File mainSrc = buscarRes().getParentFile();
        String fra = new String(java.nio.file.Files.readAllBytes(
                new File(mainSrc, "java/dza/fut/paipo/PelisFragment.java").toPath()),
                StandardCharsets.UTF_8);
        assertTrue("el click de la tira aplica el ano sin abrir dialogo",
                fra.contains("pintarTiraAnios"));
        assertTrue("los selectores usan la lista inline del panel — cero ventanas",
                fra.contains("alternarListaFiltros"));
        assertFalse("ya no queda NI UN AlertDialog de filtros en el fragmento",
                fra.contains("MaterialAlertDialogBuilder"));
    }

    @Test
    public void lasListasDeOpcionesSePintanInlineYViajanSinRed() throws Exception {
        String layout = leer("layout/fragment_pelis.xml");
        assertTrue("el scroll de la lista vive en el layout", layout.contains("@+id/scrollListaFiltros"));
        assertTrue("dentro, la fila de filas", layout.contains("@+id/listaFiltrosInline"));
        assertTrue("su fondo", new File(buscarRes(), "drawable/bg_pelis_sheet.xml").isFile());
        java.util.List<PelisApi.OpcionFiltro> ops = new java.util.ArrayList<>();
        ops.add(new PelisApi.OpcionFiltro(28, "Acci\u00f3n", "accion", 47));
        ops.add(new PelisApi.OpcionFiltro(10757, "Ciencia ficci\u00f3n", "cf", 0));
        java.util.List<PelisApi.OpcionFiltro> back =
                PelisApi.opcionesDesdeLineas(PelisApi.lineasDeOpciones(ops));
        assertEquals(2, back.size());
        assertEquals(28, back.get(0).id);
        assertEquals("Acci\u00f3n", back.get(0).nombre);
        assertEquals(47, back.get(0).cantidad);
        assertEquals(0, back.get(1).cantidad);
        assertTrue(PelisApi.opcionesDesdeLineas("").isEmpty());
    }

    private static String leer(String rel) throws Exception {
        File f = new File(buscarRes(), rel);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static File buscarRes() {
        for (String c : new String[]{"src/main/res", "app/src/main/res", "../app/src/main/res"}) {
            if (new File(c).isDirectory()) return new File(c);
        }
        throw new AssertionError("no encuentro res/ desde " + new File(".").getAbsolutePath());
    }
}
