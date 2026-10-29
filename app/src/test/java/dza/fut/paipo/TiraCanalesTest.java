package dza.fut.paipo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.Test;

/** v3.2.43 — la tira de la rejilla, clon de Drama (capturas 2026-10-17). */
public class TiraCanalesTest {

    private static LinkedHashMap<String, Integer> conteo(String... kv) {
        LinkedHashMap<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], Integer.parseInt(kv[i + 1]));
        return m;
    }

    private static List<String> claves(List<String[]> tiras) {
        List<String> out = new ArrayList<>();
        for (String[] t : tiras) out.add(t[0]);
        return out;
    }

    @Test
    public void laTiraSigueElOrdenDeDramaYClavaElPais() {
        List<String[]> t = TiraCanales.construir(
                conteo("live_tv_deportes", "9", "live_tv_peru", "3", "live_tv_argentina", "2"),
                "PE", 2, 5, true);
        assertEquals(Arrays.asList("", TiraCanales.CLAVE_FAVORITOS, TiraCanales.CLAVE_VISTOS,
                        "live_tv_peru", "live_tv_deportes", "live_tv_argentina",
                        TiraCanales.CLAVE_VIVOS), claves(t));
        assertEquals("\uD83C\uDDF5\uD83C\uDDEA Per\u00fa", t.get(3)[1]);
        // el país va clavado pese a llegar TERCERO en los tópicos y ser menos poblado
    }

    @Test
    public void sinHistorialYNadaGuardadoNoHayChipsEspeciales() {
        List<String[]> t = TiraCanales.construir(
                conteo("live_tv_argentina", "2", "live_tv_deportes", "9"), "PE", 0, 0, false);
        assertEquals(Arrays.asList("", "live_tv_argentina", "live_tv_deportes"), claves(t));
        // orden de llegada, NO de población: argentina(2) antes que deportes(9)
    }

    @Test
    public void paisDelAparatoDesconocidoNoInventaChip() {
        List<String[]> t = TiraCanales.construir(
                conteo("live_tv_chile", "4", "live_tv_peru", "3"), "XX", 0, 0, false);
        assertEquals(Arrays.asList("", "live_tv_chile", "live_tv_peru"), claves(t));
        List<String[]> v = TiraCanales.construir(
                conteo("live_tv_peru", "3"), "", 0, 0, false);
        assertEquals(Arrays.asList("", "live_tv_peru"), claves(v));
    }

    @Test
    public void elPaisPropioNoSeDuplica() {
        List<String[]> t = TiraCanales.construir(
                conteo("live_tv_peru", "3"), "PE", 1, 1, false);
        int n = 0;
        for (String[] fila : t) if ("live_tv_peru".equals(fila[0])) n++;
        assertEquals(1, n);
    }

    @Test
    public void vivoDetectaElMarcadorDelFeed() {
        assertTrue(TiraCanales.esPartidoEnVivo(
                "http://.LS.V2live_tv_custom_handler_12_description_HD_/s"));
        assertTrue(TiraCanales.esPartidoEnVivo("dramalive://play?id_live=x&u=http%3A%2F%2Fx%2Flive_tv_custom_handler_9_z"));
        assertFalse(TiraCanales.esPartidoEnVivo("https://cdn.example/x.m3u8"));
        assertFalse(TiraCanales.esPartidoEnVivo(null));
    }

    @Test
    public void lasVistasViajanSerializadasYEltopMandaPorCantidad() {
        LinkedHashMap<String, Integer> v = new LinkedHashMap<>();
        v.put("s:a", 1);
        v.put("s:b", 4);
        v.put("s:c", 2);
        String s = UsoCanales.serializarVistas(v);
        assertEquals(v, UsoCanales.deserializarVistas(s));
        assertEquals(Arrays.asList("s:b", "s:c"), UsoCanales.topVistas(v, 2));
        assertTrue(UsoCanales.deserializarVistas("").isEmpty());
        assertTrue(UsoCanales.deserializarVistas("basura\ns:x\tnum").isEmpty());
    }

    @Test
    public void laClaveEstabilizaElCanal() {
        Canal c = new Canal();
        c.title = "ESPN (720p)";
        assertEquals("s:ESPN".replace("ESPN", "espn"), UsoCanales.claveDe(c)); // sin id: slug del título
        Canal.OpcionCanal o = new Canal.OpcionCanal();
        o.url = "dramalive://play?id_live=abc123&u=1";
        c.options = new ArrayList<>(Arrays.asList(o));
        assertEquals("i:abc123", UsoCanales.claveDe(c));   // con id_live manda el id
        assertTrue(UsoCanales.claveDe(null).isEmpty());
    }
}
