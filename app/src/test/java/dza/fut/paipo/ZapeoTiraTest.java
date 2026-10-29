package dza.fut.paipo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * v7.5 — la tira de zapeo del reproductor se construye por ventanas.
 *
 * El ANR real: PlayerActivity pintaba la tira de canales EN onCreate, en el hilo
 * principal, con una tarjeta y un Glide por cada canal de la rejilla visible. Con el
 * catálogo completo eso son decenas de miles de tarjetas: el proceso se quedaba mudo
 * y el sistema declaraba ANR. Estos tests fijan el ventaneo para que no se vuelva a
 * pintar la lista entera.
 */
public class ZapeoTiraTest {

    private static final int TANDA = PlayerActivity.ZAPEO_TANDA;

    @Test public void laVentanaSeCentraEnElCanalQueSeVe() {
        assertEquals(16000 - TANDA / 2, PlayerActivity.zapeoVentana(16000, 32000, TANDA));
    }

    @Test public void alPrincipioNuncaSePasaDeCero() {
        assertEquals(0, PlayerActivity.zapeoVentana(0, 32000, TANDA));
        assertEquals(0, PlayerActivity.zapeoVentana(3, 32000, TANDA));
        assertEquals(0, PlayerActivity.zapeoVentana(TANDA / 2 - 1, 32000, TANDA));
    }

    @Test public void alFinalLaVentanaSigueCompleta() {
        assertEquals(32000 - TANDA, PlayerActivity.zapeoVentana(31999, 32000, TANDA));
        assertEquals(32000 - TANDA, PlayerActivity.zapeoVentana(32000, 32000, TANDA));
    }

    @Test public void conPocosCanalesSeVenTodos() {
        assertEquals(0, PlayerActivity.zapeoVentana(0, 2, TANDA));
        assertEquals(0, PlayerActivity.zapeoVentana(11, 12, TANDA));
        assertEquals(0, PlayerActivity.zapeoVentana(39, 40, TANDA));
    }

    /** EL contrato: ninguna tanda se sale de la lista ni pasa de ZAPEO_TANDA. */
    @Test public void ningunaTandaSeSaleDeLaLista() {
        int[] centros = {0, 1, 20, 199, 5000, 16384, 31998, 31999};
        for (int total : new int[]{2, 40, 41, 265, 32000}) {
            for (int c : centros) {
                if (c >= total) continue;
                int ini = PlayerActivity.zapeoVentana(c, total, TANDA);
                assertTrue("inicio negativo en " + c + "/" + total, ini >= 0);
                int fin = Math.min(total, ini + TANDA);
                assertTrue("se pasa del final en " + c + "/" + total, fin <= total);
                assertTrue("tanda vacia en " + c + "/" + total, fin > ini);
            }
        }
    }

    /** El canal actual tiene que caer SIEMPRE dentro de la ventana. */
    @Test public void elCanalActualSiempreCaeEnLaVentana() {
        for (int total : new int[]{41, 100, 265, 32000}) {
            for (int c = 0; c < total; c += Math.max(1, total / 37)) {
                int ini = PlayerActivity.zapeoVentana(c, total, TANDA);
                assertTrue("el canal " + c + " queda fuera con total " + total,
                        c >= ini && c < Math.min(total, ini + TANDA));
            }
        }
    }
}
