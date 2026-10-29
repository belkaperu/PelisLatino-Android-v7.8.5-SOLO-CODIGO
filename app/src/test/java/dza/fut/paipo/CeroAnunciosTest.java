package dza.fut.paipo;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;

/**
 * v3.2.40 — «elimina la publicidad de start.io por ahora». Guarda de que el SDK
 * esta efectivamente fuera del build: ni init, ni banner, ni meta-data, ni
 * dependencia, ni contenedor en la actividad principal. Si alguien lo reincorpora
 * sin querer (o un merge se lo trae de vuelta), este test lo pilla antes del APK.
 */
public class CeroAnunciosTest {

    @Test
    public void startIoQuedoFueraDeTodoElCamino() throws Exception {
        File main = new File(buscarModulo(), "src/main/java/dza/fut/paipo/Main.java");
        String m = leer(main);
        assertFalse("Main no puede inicializar el SDK", m.contains("StartAppSDK.init("));
        assertFalse("Main no puede importar el SDK", m.contains("import com.startapp"));
        assertFalse("Main no puede inflar el banner", m.contains("new Banner("));
        assertTrue("la decision de quitarla queda documentada para volver", m.contains("POR AHORA"));

        String gradle = leer(new File(buscarModulo(), "build.gradle"));
        assertFalse("la dependencia del SDK no puede estar declarada",
                gradle.contains("implementation 'com.startapp"));

        String mani = leer(new File(buscarModulo(), "src/main/AndroidManifest.xml"));
        assertFalse("el APPLICATION_ID de Start.io no puede estar en el manifest",
                mani.contains("com.startapp.sdk"));

        String act = leer(new File(buscarModulo(), "src/main/res/layout/activity_main.xml"));
        assertFalse("ya no hay contenedor de banner en la actividad",
                act.contains("bannerContainer"));
        assertTrue("el fragment_container ancla abajo contra el padre (pantalla completa)",
                act.contains("app:layout_constraintBottom_toBottomOf=\"parent\""));
    }

    private static String leer(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static File buscarModulo() {
        for (String c : new String[]{".", "app", "../app"}) {
            File base = new File(c);
            if (new File(base, "src/main/java/dza/fut/paipo/Main.java").isFile()) return base;
        }
        throw new AssertionError("no encuentro el modulo app desde " + new File(".").getAbsolutePath());
    }

    @Test
    public void ningunRecursoVuelveaNombrarElSdk() throws Exception {
        // LA LECCIÓN DEL CIERRE AL ABRIR (v3.2.40): un layout puede citar clases que ya
        // no existen y compilar limpio — el fallo aparece al inflar, en el teléfono.
        // Aquí no vuelve a pasar: si alguien resucita un Mrec o una vista del SDK en un
        // XML, o re-infla una fila publicitaria desde java, la suite revienta antes.
        java.nio.file.Path resRoot = new java.io.File(System.getProperty("user.dir"), "src/main/res").toPath();
        final boolean[] huellaXml = {false};
        java.util.List<java.nio.file.Path> xmls = new java.util.ArrayList<>();
        try (java.util.stream.Stream<java.nio.file.Path> w = java.nio.file.Files.walk(resRoot)) {
            w.filter(x -> x.toString().endsWith(".xml")).forEach(xmls::add);
        }
        for (java.nio.file.Path x : xmls) {
            String s = new String(java.nio.file.Files.readAllBytes(x), "UTF-8").toLowerCase();
            if (s.contains("startapp")) huellaXml[0] = true;
        }
        assertFalse("algún XML de res vuelve a mencionar el SDK retirado", huellaXml[0]);

        java.nio.file.Path srcRoot = new java.io.File(System.getProperty("user.dir"), "src/main/java").toPath();
        final boolean[] huellaJava = {false};
        java.util.List<java.nio.file.Path> javs = new java.util.ArrayList<>();
        try (java.util.stream.Stream<java.nio.file.Path> w = java.nio.file.Files.walk(srcRoot)) {
            w.filter(x -> x.toString().endsWith(".java")).forEach(javs::add);
        }
        for (java.nio.file.Path j : javs) {
            String b = new String(java.nio.file.Files.readAllBytes(j), "UTF-8").toLowerCase();
            if (b.contains("item_ad_nativo") || b.contains("anuncio_mrec") || b.contains("view_type_anuncio")) {
                huellaJava[0] = true;
            }
        }
        assertFalse("queda código que infla filas publicitarias", huellaJava[0]);
    }
}

