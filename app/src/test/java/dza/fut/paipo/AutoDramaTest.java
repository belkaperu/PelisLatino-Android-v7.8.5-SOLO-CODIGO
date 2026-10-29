package dza.fut.paipo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** v3.2.44 — la conexión y la extracción automáticas deciden en JVM, no en el teléfono. */
public class AutoDramaTest {

    private static final long H = 3600_000L;

    @Test
    public void primeraVezTocaConexionYelRestoRespetaLaVentana() {
        assertEquals(AutoDrama.CONEXION, AutoDrama.plan(1000L, 0L, true));
        // v3.2.46: sin sesion PERO con el intento ya marcado (fallo de red/edge), no se
        // repide al edge en cada resume: la ventana manda tambien aca.
        assertEquals(AutoDrama.NADA, AutoDrama.plan(2000L, 1500L, true));
    }

    @Test
    public void relojAtrasadoNoCongelaLaRejilla() {
        long marcado = 9_000L * H;              // el ts se escribio con el reloj "de antes"
        long ahora = 8_990L * H;                // y el usuario devolvio el reloj DIEZ HORAS
        assertEquals(AutoDrama.REFRESCO, AutoDrama.plan(ahora, marcado, false));
        assertEquals(AutoDrama.CONEXION, AutoDrama.plan(ahora, marcado, true));
        // un jiterito de NTP (atraso < 10 min) NO despierta ciclos extra
        assertEquals(AutoDrama.NADA, AutoDrama.plan(marcado - 5 * 60_000L, marcado, false));
        // salto del reloj hacia adelante: delta gigante = toca moverse y el intento
        // reescribe el ts con la hora nueva (se auto-repara sin pedir nada)
        assertEquals(AutoDrama.REFRESCO, AutoDrama.plan(marcado + 4000L * H, marcado, false));
    }

    @Test
    public void sesionFrescaNoMueveNada() {
        long ahora = 5000L * H;
        assertEquals(AutoDrama.NADA, AutoDrama.plan(ahora, ahora - 5 * H, false));
    }

    @Test
    public void sesionViejaPideRefrescoNativo() {
        long ahora = 5000L * H;
        assertEquals(AutoDrama.REFRESCO, AutoDrama.plan(ahora, ahora - 7 * H, false));
        assertEquals(AutoDrama.REFRESCO, AutoDrama.plan(ahora, 0L, false));
    }

    @Test
    public void laEntregaNuevaFusionaSinPisarCurados() {
        String vieja = "#EXTM3U\n#EXTINF:-1 tvg-name=\"X\" tvg-id=\"x1\" group-title=\"g\",X\nhttps://cdn.real/x.m3u8\n";
        String nueva = "#EXTM3U\n#EXTINF:-1 tvg-name=\"X\" tvg-id=\"x1\" group-title=\"g\",X\ndramalive://play?id_live=x1&u=1\n#EXTINF:-1 tvg-name=\"Y\" tvg-id=\"y9\" group-title=\"g\",Y\ndramalive://play?id_live=y9&u=1\n";
        String f = AutoDrama.fusionarEntrega(vieja, nueva);
        assertTrue("la url curada (http) no se degrada a dramalive://", f.contains("https://cdn.real/x.m3u8"));
        assertTrue("el canal nuevo entra", f.contains("id_live=y9"));
        assertEquals("fusionarEntrega vacia devuelve la vieja intacta", vieja,
                AutoDrama.fusionarEntrega(vieja, "  "));
    }
}
