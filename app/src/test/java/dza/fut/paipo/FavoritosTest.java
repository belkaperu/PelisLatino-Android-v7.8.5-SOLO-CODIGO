package dza.fut.paipo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;

import org.junit.Test;

/** v3.2.39 — favoritos puros + la erradicacion de los numeros de conteo. */
public class FavoritosTest {

    @Test
    public void laClavePrefiereElSlug() {
        assertEquals("s:rapidos-10", Favoritos.clave("rapidos-10", 7));
        assertEquals("i:7", Favoritos.clave("", 7));
        assertEquals("i:7", Favoritos.clave(null, 7));
        PelisItem it = new PelisItem();
        it.slug = "coco";
        it.id = 12;
        assertEquals("s:coco", it.claveFavorito());
    }

    @Test
    public void alternarIdempotenteYCacheViajaCompleta() {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        assertTrue(Favoritos.alternar(set, "s:a"));
        assertTrue(Favoritos.alternar(set, "s:b"));
        assertFalse(Favoritos.alternar(set, "s:a"));
        assertEquals(Arrays.asList("s:b"), new ArrayList<>(set));
        String texto = Favoritos.serializar(set);
        assertEquals(set, Favoritos.deserializar(texto));
        assertTrue(Favoritos.deserializar("").isEmpty());
        assertTrue(Favoritos.deserializar("s:b\n\n").contains("s:b"));
    }

    @Test
    public void losNumerosDeConteoFueronErradicados() throws Exception {
        String layout = leer("layout/fragment_pelis.xml");
        assertFalse("la franja de resultados ya no pinta conteo",
                layout.contains("txtConteoPelis"));
        assertTrue("favoritos vive en la fila de tipos", layout.contains("btnFavoritosPelis"));
        assertTrue("el corazon esta en la card", leer("layout/item_pelis.xml").contains("btnFavPelis"));
        assertTrue(leer("values/strings.xml").contains("pelis_filtro_favoritos"));
        String fra = leerJava("PelisFragment.java");
        assertFalse("el fragmento no toca ya el TextView del conteo", fra.contains("txtConteo"));
        assertFalse("ni chips ni listas muestran «· cantidad»", fra.contains("op.cantidad > 0"));
        assertTrue("el set vivo de la API es la fuente", fra.contains("PelisApi.FAVORITOS"));
    }

    private static String leer(String rel) throws Exception {
        return new String(Files.readAllBytes(new File(buscarRes(), rel).toPath()),
                StandardCharsets.UTF_8);
    }

    private static String leerJava(String archivo) throws Exception {
        File main = buscarRes().getParentFile();
        return new String(Files.readAllBytes(
                new File(main, "java/dza/fut/paipo/" + archivo).toPath()), StandardCharsets.UTF_8);
    }

    private static File buscarRes() {
        for (String c : new String[]{"src/main/res", "app/src/main/res", "../app/src/main/res"}) {
            if (new File(c).isDirectory()) return new File(c);
        }
        throw new AssertionError("no encuentro res/ desde " + new File(".").getAbsolutePath());
    }
}
