package dza.fut.paipo;

import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/**
 * v3.2.58: el «siempre blanco» tenia una causa que ni la red ni los emojis: el tinte
 * del tema (pelis_text_secondary = #DCEDF1, y el del style del tab) APLANA cualquier
 * imagen real que entre por setImageResource/Glide en imgCelda/imgTabCanal. La bandera
 * llegaba perfecta del CDN de Drama y el tinte la pintaba de gris-casi-blanco. Este
 * test es la guardia estatica (misma tecnica que RecursosLayoutTest): toda imagen
 * real tiene que pasar por pintarImagenReal (o por cargarIconoDeDrama, que hace lo
 * propio en onResourceReady), nunca por setImageResource pelado.
 */
public class TinteBlancoTest {

    private static String fuente() {
        String r = "dza/fut/paipo/CanalesEnVivoFragment.java";
        try (InputStream in = TinteBlancoTest.class.getClassLoader().getResourceAsStream(r)) {
            if (in != null) return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException ignore) { }
        java.io.File f = new java.io.File(
                "src/main/java/dza/fut/paipo/CanalesEnVivoFragment.java");
        if (!f.isFile()) f = new java.io.File(
                "app/src/main/java/dza/fut/paipo/CanalesEnVivoFragment.java");
        try {
            return new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new AssertionError("no encuentro la fuente del fragmento: " + e);
        }
    }

    @Test
    public void todaImagenRealPasaPorElHelperSinTinte() {
        String c = fuente();
        assertTrue("falta pintarImagenReal", c.contains("private static void pintarImagenReal("));
        assertTrue("el helper debe limpiar el tinte",
                c.contains("img.setImageTintList(null);"));
        // ninguna bandera local se pinta con setImageResource pelado (sin helper):
        int i = 0, crudos = 0;
        while ((i = c.indexOf("setImageResource(banderaLocal)", i + 1)) >= 0) {
            int finLinea = c.indexOf('\n', i);
            int iniLinea = c.lastIndexOf('\n', i) + 1;
            String linea = c.substring(iniLinea, finLinea);
            if (!linea.trim().startsWith("//")) crudos++;
        }
        assertTrue("banderaLocal debe pintarse via pintarImagenReal, no pelada", crudos == 0);
        // v3.2.62 (pedido del usuario: «elimina los tags»): el hint del panel ya
        // NO lleva tag de version ni marca ajena — texto limpio y estable.
        assertTrue("el hint del panel debe ser el texto limpio, sin tag de version",
                c.contains("setHint(\"Buscar país o categoría\")") && !c.contains("BUILD_TAG"));
    }
}
