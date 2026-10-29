package dza.fut.paipo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Map;

/**
 * Las cabeceras son el único modo de abrir un canal cuyo CDN exige `Referer` o `Cookie`
 * de sesión. Lo que se prueba aquí es que lo que el usuario escribe (o pega del
 * inspector del navegador) se convierte en lo que de verdad se manda: nada de
 * cabeceras que rompen el cliente, nada de cabeceras que se mandan a otro host.
 */
public class EncabezadosTest {

    @Test
    public void vacioNoAportaNada() {
        assertTrue(Encabezados.NINGUNO.estaVacio());
        assertTrue(Encabezados.fromTexto(null).estaVacio());
        assertTrue(Encabezados.fromTexto("   \n\n  ").estaVacio());
        assertEquals(0, Encabezados.fromTexto("").para("https://x.net/a.m3u8").size());
    }

    @Test
    public void nombreDosPuntosValor() {
        Encabezados h = Encabezados.fromTexto("Referer: https://mi-proveedor.net/\nCookie: sess=abc123; t=1");
        Map<String, String> m = h.para("https://cdn.mi-proveedor.net/a.m3u8");
        assertEquals(2, m.size());
        assertEquals("https://mi-proveedor.net/", m.get("Referer"));
        assertEquals("sess=abc123; t=1", m.get("Cookie"));   // los ';' y '=' del valor no se parten
    }

    @Test
    public void signoIgualTambienVale() {
        Encabezados h = Encabezados.fromTexto("X-Token = abc.def");
        assertEquals("abc.def", h.para("https://x.net/").get("X-Token"));
    }

    @Test
    public void aceptaJsonPegadoDelInspector() {
        Encabezados h = Encabezados.fromTexto(
                "{\"Referer\":\"https://proveedor.net/player/\",\"User-Agent\":\"Repro/1.0\"}");
        Map<String, String> m = h.para("https://x.net/");
        assertEquals("https://proveedor.net/player/", m.get("Referer"));
        assertEquals("Repro/1.0", m.get("User-Agent"));
        assertEquals(2, m.size());
    }

    @Test
    public void comentariosYBasuraSeIgnoran() {
        Encabezados h = Encabezados.fromTexto(
                "# esto es una nota\n// otra\nsin dos puntos\nReferer: https://a.net/\n");
        assertEquals(1, h.cuantas());
        assertEquals("https://a.net/", h.para("https://b.net/").get("Referer"));
    }

    @Test
    public void cabecerasQueRompenElClienteSeDescartanYSeAvisan() {
        Encabezados h = Encabezados.fromTexto(
                "Host: evil.net\nContent-Length: 5\nAccept-Encoding: gzip\nReferer: https://a.net/");
        Map<String, String> m = h.para("https://b.net/");
        assertEquals(1, m.size());
        assertEquals("https://a.net/", m.get("Referer"));
        assertTrue(h.descartadas().contains("Host"));
        assertTrue(h.descartadas().contains("Content-Length"));
        assertTrue(h.descartadas().contains("Accept-Encoding"));
    }

    @Test
    public void alcancePorHostIncluyeSubdominiosYPadreNo() {
        Encabezados h = Encabezados.fromTexto(
                "@cdn.proveedor.net User-Agent: Repro/1.0\n@otro.net Referer: https://x/");
        assertEquals("Repro/1.0", h.para("https://cdn.proveedor.net/a.ts").get("User-Agent"));
        assertEquals("Repro/1.0", h.para("https://deep.cdn.proveedor.net/a.ts").get("User-Agent"));
        assertFalse(h.para("https://proveedor.net/a.ts").containsKey("User-Agent"));
        assertFalse(h.para("https://cdn.proveedor.net.evil.net/").containsKey("User-Agent"));
        // a otro.net sólo le llega su propia cabecera, la del proveedor no se cuela
        assertEquals(1, h.para("https://otro.net/").size());
        assertEquals("https://x/", h.para("https://otro.net/").get("Referer"));
    }

    @Test
    public void ultimaDefinicionGanaYElResumenListaLosNombres() {
        Encabezados h = Encabezados.fromTexto(
                "referer: https://una.net\nReferer: https://dos.net\n@cdn.net User-Agent: A/1");
        assertEquals("https://dos.net", h.para("https://x.net/").get("Referer"));
        assertEquals(2, h.cuantas());
        assertEquals("Referer, @cdn.net User-Agent", h.resumen());
        assertTrue(Encabezados.pareceCabeceras("Referer: https://a.net"));
        assertTrue(Encabezados.pareceCabeceras("{\"Cookie\":\"a=1\"}"));
        assertFalse(Encabezados.pareceCabeceras("https://solo-url.net/a.m3u8"));
    }
}
