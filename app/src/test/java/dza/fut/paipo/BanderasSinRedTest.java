package dza.fut.paipo;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * v7.5 — las banderas de país NO se piden por red.
 *
 * Un teléfono real mandó un logcat con 404 a mansalva contra
 * http://logo.twoapistack.work/img/topics/ic_flag_XX.png para territorios que no
 * existen en el servidor (bv, tf, gp, hm, pn, bl, pm, sj, gs, um, wf...), cada uno
 * con su traza completa de Glide. La app tiene 226 banderas embebidas, así que la
 * red no pinta nada aquí: o está embebida o se queda el emoji.
 */
public class BanderasSinRedTest {

    private static final String BASE = "http://logo.twoapistack.work/img/topics/";

    @Test public void banderasQueSiEstanEnElServidor() {
        assertTrue(CanalesEnVivoFragment.esUrlDeBandera(BASE + "ic_flag_pe.png"));
        assertTrue(CanalesEnVivoFragment.esUrlDeBandera(BASE + "ic_flag_ar.png"));
        assertTrue(CanalesEnVivoFragment.esUrlDeBandera(BASE + "IC_FLAG_PE.PNG"));
    }

    @Test public void banderasDeTerritoriosInexistentesTambienSonBanderas() {
        // justo las que provocaban la tormenta de 404
        for (String iso : new String[]{"bv", "tf", "gp", "hm", "pn", "bl", "pm", "sj", "gs", "um", "wf"}) {
            assertTrue(iso, CanalesEnVivoFragment.esUrlDeBandera(BASE + "ic_flag_" + iso + ".png"));
        }
    }

    @Test public void iconosDeCategoriaSiSeBajan() {
        assertFalse(CanalesEnVivoFragment.esUrlDeBandera(BASE + "ic_futbol.png"));
        assertFalse(CanalesEnVivoFragment.esUrlDeBandera(BASE + "ic_deportes.png"));
        assertFalse(CanalesEnVivoFragment.esUrlDeBandera(BASE + "ic_noticias.png"));
    }

    @Test public void sinUrlNoHayNadaQueDecidir() {
        assertFalse(CanalesEnVivoFragment.esUrlDeBandera(null));
        assertFalse(CanalesEnVivoFragment.esUrlDeBandera(""));
    }
}
