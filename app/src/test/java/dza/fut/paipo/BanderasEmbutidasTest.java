package dza.fut.paipo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import org.junit.Test;

/**
 * v3.2.57 («tampoco siempre blanco»): las banderas de los paises son PNG REALES del CDN
 * de Drama embebidos en el APK (res/drawable-nodpi/flag_xx.png). Este test es la
 * garantia de que el paquete nunca sale con el recurso roto o incompleto: si falta la
 * carpeta, un png no es png, o baja del umbral de paises del glt (237, medidos; hoy
 * 226 porque el propio Drama no tiene 11 territorios minimos), la suite falla.
 */
public class BanderasEmbutidasTest {

    private static File dir() {
        for (String c : new String[]{"src/main/res/drawable-nodpi",
                "app/src/main/res/drawable-nodpi"}) {
            File f = new File(c);
            if (f.isDirectory()) return f;
        }
        throw new AssertionError("no encuentro drawable-nodpi desde "
                + new File(".").getAbsolutePath());
    }

    @Test
    public void cadaBanderaEmbebidaEsUnPngSano() {
        File d = dir();
        File[] fl = d.listFiles((f, n) -> n.startsWith("flag_") && n.endsWith(".png"));
        assertNotNull("falta el directorio de banderas", fl);
        assertTrue("menos de 220 banderas embebidas: " + fl.length, fl.length >= 220);
        for (File f : fl) {
            byte[] b = new byte[(int) Math.min(f.length(), 8)];
            try (java.io.InputStream in = new java.io.FileInputStream(f)) {
                int leido = in.read(b);
                assertEquals("lectura corta en " + f.getName(), 8, leido);
            } catch (java.io.IOException e) {
                throw new AssertionError(e);
            }
            assertTrue("no es PNG: " + f.getName(), b[0] == (byte) 0x89 && b[1] == 'P'
                    && b[2] == 'N' && b[3] == 'G');
            assertTrue("nombre fuera del esquema: " + f.getName(),
                    f.getName().matches("flag_[a-z]{2}\\.png"));
        }
    }

    /** Los paises que la tabla local conoce tienen que tener imagen embebida. */
    @Test
    public void losPaisesDeLaTablaTienenBanderaEmbebida() {
        File d = dir();
        for (String pais : new String[]{"Peru", "Argentina", "Syria", "Qatar",
                "United Arab Emirates", "Egypt", "Lebanon"}) {
            String iso = FusionRejilla.isoDeBanderaEmoji(FusionRejilla.banderaDeTopic(pais));
            assertTrue("la tabla no conoce " + pais, iso.length() == 2);
            assertTrue("falta flag_" + iso + ".png para " + pais,
                    new File(d, "flag_" + iso + ".png").isFile());
        }
    }
}
