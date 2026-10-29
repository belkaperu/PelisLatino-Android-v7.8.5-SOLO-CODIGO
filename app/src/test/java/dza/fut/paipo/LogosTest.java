package dza.fut.paipo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * El reductor de logos tiene que producir EXACTAMENTE la forma que usa el portal
 * (una sola vez, con la URL de origen escapada) y no tocar lo que no es una URL.
 */
public class LogosTest {

    @Test
    public void construyeLaMismaFormaQueElClienteWeb() {
        assertEquals("https://images.weserv.nl/?url=https%3A%2F%2Fcdn.example.com%2Fa%2Fb.png&w=220&output=webp",
                Logos.reducido("https://cdn.example.com/a/b.png"));
        assertEquals("https://images.weserv.nl/?url=http%3A%2F%2Fx.net%2Fi.png&w=120&output=webp",
                Logos.reducido("http://x.net/i.png", 120));
    }

    @Test
    public void nodobloLoQueYaVieneProxeado() {
        String una = Logos.reducido("https://cdn.example.com/a/b.png");
        assertEquals("aplicarlo dos veces no debe anidar el proxy", una, Logos.reducido(una));
    }

    @Test
    public void dejaEnPazLoQueNoEsUnaUrlDeRed() {
        assertEquals("", Logos.reducido(null));
        assertEquals("", Logos.reducido("   "));
        String data = "data:image/png;base64,iVBORw0KGgo=";
        assertEquals(data, Logos.reducido(data));
        assertEquals("assets/logo.png", Logos.reducido("assets/logo.png"));
        assertEquals("file:///sdcard/x.png", Logos.reducido("file:///sdcard/x.png"));
        assertEquals("content://media/1", Logos.reducido("content://media/1"));
        assertEquals("ftp://no/x.png", Logos.reducido("ftp://no/x.png"));
        assertFalse(Logos.aplicable(null));
        assertFalse(Logos.aplicable("https://  "));
        assertTrue(Logos.aplicable(" https://ok.net/a.png "));
    }

    @Test
    public void escapaLoRaroYRespetaElAnchoMinimo() {
        String conEspacios = Logos.reducido("https://x.com/Ñ a.png");
        assertTrue("debe escapar el espacio y la eñe: " + conEspacios,
                conEspacios.contains("%20") && conEspacios.contains("%C3%91"));
        assertTrue(Logos.reducido("https://x.com/a.png", 1).endsWith("&w=16&output=webp"));
        assertEquals("https%3A%2F%2Fx", Logos.escapar("https://x"));
    }

    /** v3.2.55: los iconos-chicos de topico van DIRECTOS al CDN: el proxy con
     *  &output=webp convertia los PNG palette de las banderas en rectangulos
     *  blancos (164 B de relleno medidos contra el origen, que son 399 B sanos). */
    @Test
    public void losIconosDeTopicoVanDirectosAlCdn() {
        String u = "http://logo.twoapistack.work/img/topics/ic_flag_pe.png";
        assertEquals(u, Logos.iconoTopico(u));
        assertEquals("", Logos.iconoTopico(null));
        assertEquals(u, Logos.iconoTopico("  " + u + "  "));
    }
}
