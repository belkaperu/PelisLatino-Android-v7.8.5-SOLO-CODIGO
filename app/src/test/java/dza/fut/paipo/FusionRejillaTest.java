package dza.fut.paipo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** v3.2.42 — «cada canal en su lugar como hace Drama»: el orden de Drama manda. */
public class FusionRejillaTest {

    private static Canal canal(String titulo, String grupo) {
        Canal c = new Canal();
        c.title = titulo;
        c.category = grupo;
        Canal.OpcionCanal o = new Canal.OpcionCanal();
        o.url = "http://x/" + titulo;
        c.options = new ArrayList<>(Arrays.asList(o));
        return c;
    }

    private static List<String> titulos(List<Canal> l) {
        List<String> ts = new ArrayList<>();
        for (Canal c : l) ts.add(c.title);
        return ts;
    }

    /** v3.2.51 (captura del usuario «sale numero, no hay flags»): 974/961/966 son
     *  codigos telefonicos de pais; tenian que resolverse a nombre + bandera. */
    /** v3.2.52: el grupo "unsorted" del feed se muestra como lo llama Drama. */
    @Test
    public void losInOrdenadosTienenNombre() {
        assertEquals("Sin clasificar", FusionRejilla.etiquetaTopic("Unsorted"));
        assertEquals("Sin clasificar", FusionRejilla.etiquetaTopic("unsorted"));
    }

    /** v3.2.55: los topics-pais del glt llegan con nombre en ingles; la tabla los
     *  conoce ahi tambien y salen con bandera + nombre en castellano sin necesidad
     *  del CDN (fallback local cuando la imagen no llega). */
    @Test
    public void losNombresArabigosTienenAliasConBandera() {
        assertEquals("\uD83C\uDDF8\uD83C\uDDFE", FusionRejilla.banderaDeTopic("Syria"));
        // la bandera son 2 regional indicators = 4 unidades UTF-16: acabar en el nombre, sin contar prefijos
        assertTrue(FusionRejilla.etiquetaTopic("Syria").endsWith("Siria"));
        assertEquals("\uD83C\uDDF1\uD83C\uDDE7", FusionRejilla.banderaDeTopic("Lebanon"));
        assertEquals("\uD83C\uDDF6\uD83C\uDDE6", FusionRejilla.banderaDeTopic("Qatar"));
        assertEquals("\uD83C\uDDE6\uD83C\uDDEA", FusionRejilla.banderaDeTopic("United Arab Emirates"));
        assertTrue(FusionRejilla.esPais("saudi arabia"));
    }

    /** v3.2.56: la bandera se deriva del ISO embebido en la url de Drama. */
    @Test
    public void losIsoAlimentanLaBanderaEmbebida() {
        assertEquals("pe", FusionRejilla.isoDeUrlTopic(
                "http://logo.twoapistack.work/img/topics/ic_flag_pe.png"));
        assertEquals("", FusionRejilla.isoDeUrlTopic("http://x/hot_now.png"));
        assertEquals("", FusionRejilla.isoDeUrlTopic(null));
        assertEquals("pe", FusionRejilla.isoDeBanderaEmoji("\uD83C\uDDF5\uD83C\uDDEA"));
        assertEquals("", FusionRejilla.isoDeBanderaEmoji("\u25CE"));
        assertEquals("", FusionRejilla.isoDeBanderaEmoji(null));
    }

    @Test
    public void laBanderaSeDerivaDelIsoDeLaUrl() {
        assertEquals("\uD83C\uDDF5\uD83C\uDDEA",
                FusionRejilla.banderaDeUrlTopic("http://logo.twoapistack.work/img/topics/ic_flag_pe.png"));
        assertEquals("\uD83C\uDDEB\uD83C\uDDF7",
                FusionRejilla.banderaDeUrlTopic("http://x/ic_flag_fr.PNG"));
        assertEquals("", FusionRejilla.banderaDeUrlTopic("http://x/hot_now.png"));
        assertEquals("", FusionRejilla.banderaDeUrlTopic(null));
    }

    /** v3.2.61: el id del glt y el nombre del feed deben resolver al MISMO rotulo,
     *  si no, el filtro del panel dejaria el pais recien cargado sin canales. */
    @Test
    public void elIdTelefonicoYElNombreInglesSonElMismoTopic() {
        assertEquals(FusionRejilla.etiquetaTopic("Syria"),
                FusionRejilla.etiquetaTopic("963"));
        assertEquals(FusionRejilla.etiquetaTopic("Egypt"),
                FusionRejilla.etiquetaTopic("20"));
    }

    @Test
    public void losCodigosDeMarcadorSonPaisConBandera() {
        assertEquals("\uD83C\uDDF6\uD83C\uDDE6", FusionRejilla.banderaDeTopic("974"));   // Qatar
        assertEquals("\uD83C\uDDF1\uD83C\uDDE7", FusionRejilla.banderaDeTopic("live_tv_961")); // Libano
        assertTrue(FusionRejilla.esPais("966"));
        assertFalse(FusionRejilla.esPais("98765"));           // no es marcador -> no inventar
        assertEquals("1234", FusionRejilla.etiquetaTopic("1234")); // numerico suelto sigue num
        assertEquals("\uD83C\uDDF6\uD83C\uDDE6 Qatar", FusionRejilla.etiquetaTopic("974"));
        assertTrue(FusionRejilla.topicEsPais("51", "PE"));    // el pais del aparato se clava
        assertTrue(FusionRejilla.topicEsPais("live_tv_974", "QA"));
        assertFalse(FusionRejilla.topicEsPais("974", "PE"));  // Qatar no es Peru
    }

    @Test
    public void elDramaMandaYEscribeEnSuOrden() {
        List<Canal> drama = Arrays.asList(canal("GOLPERU", "live_tv_peru"), canal("ESPN", "live_tv_deportes"));
        List<Canal> base = Arrays.asList(canal("Canal C", "general"), canal("Canal D", "general"));
        List<Canal> out = FusionRejilla.conDramaPrimero(drama, base);
        // Con el pegado-al-final de antes esto salía [C, D, GOLPERU, ESPN].
        assertEquals(Arrays.asList("GOLPERU", "ESPN", "Canal C", "Canal D"), titulos(out));
    }

    @Test
    public void nuncaPisaUnCanalDeDramaNiDejaDuplicadosPorCalidad() {
        List<Canal> drama = Arrays.asList(canal("ESPN (720p)", "live_tv_deportes"));
        List<Canal> base = Arrays.asList(canal("espn", "deportes"), canal("Nuevoplay", "x"));
        List<Canal> out = FusionRejilla.conDramaPrimero(drama, base);
        assertEquals(2, out.size());
        assertEquals("ESPN (720p)", out.get(0).title);   // la fila de Drama gana
        assertEquals("Nuevoplay", out.get(1).title);
    }

    @Test
    public void paisesConBanderaYHumanizacion() {
        assertEquals("\uD83C\uDDF5\uD83C\uDDEA Per\u00fa", FusionRejilla.etiquetaTopic("live_tv_peru"));
        assertEquals("\uD83C\uDDE6\uD83C\uDDF7 Argentina", FusionRejilla.etiquetaTopic("Live_TV_Argentina"));
        assertEquals("\uD83C\uDDFA\uD83C\uDDF8 USA", FusionRejilla.etiquetaTopic("USA Sports"));
        assertEquals("Deportes", FusionRejilla.etiquetaTopic("live_tv_deportes"));
        assertTrue(FusionRejilla.esPais("Live_TV_Chile"));
        assertFalse(FusionRejilla.esPais("live_tv_deportes"));
        // "usa" suelto no muerde dentro de otras palabras:
        assertFalse(FusionRejilla.esPais("Casas y platos"));
    }

    @Test
    public void dramaVacioDejaLaBaseIntacta() {
        List<Canal> base = Arrays.asList(canal("A", "g"), canal("B", "g"));
        assertEquals(2, FusionRejilla.conDramaPrimero(null, base).size());
        assertEquals(2, FusionRejilla.conDramaPrimero(new ArrayList<Canal>(), base).size());
    }

    @Test
    public void elGltAportaLosNombresHumanosDeLosTopicos() {
        String glt = "{\"result\":0,\"live_topics\":["
                + "{\"id_topic\":\"live_tv_peru\",\"topic_name\":\"Perú\"},"
                + "{\"id_topic\":\"live_tv_usa\",\"name\":\"USA\"}]}";
        java.util.Map<String, String> m = DramaSesion.nombresTopicosDe(glt);
        assertEquals("Perú", m.get("live_tv_peru"));
        assertEquals("USA", m.get("live_tv_usa"));
        assertTrue(DramaSesion.nombresTopicosDe("{\"live_topics\":[]}").isEmpty());
        assertTrue(DramaSesion.nombresTopicosDe("no json").isEmpty());
    }
    @org.junit.Test
    public void grupoSinMarcaSeMuestraEnVivo() {
        org.junit.Assert.assertEquals("En vivo", FusionRejilla.etiquetaTopic("DramaLive"));
    }
    @org.junit.Test
    public void topicosBloqueadosNoSeMuestran() {
        org.junit.Assert.assertTrue(FusionRejilla.topicBloqueado("Live Twitch"));
        org.junit.Assert.assertTrue(FusionRejilla.topicBloqueado("Israel"));
        org.junit.Assert.assertTrue(FusionRejilla.topicBloqueado("972"));
        org.junit.Assert.assertFalse(FusionRejilla.topicBloqueado("Peru"));
        org.junit.Assert.assertFalse(FusionRejilla.topicBloqueado("Live_TV_Peru"));
        org.junit.Assert.assertFalse(FusionRejilla.topicBloqueado(null));
    }
}
