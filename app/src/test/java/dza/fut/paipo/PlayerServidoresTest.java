package dza.fut.paipo;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;

public class PlayerServidoresTest {

    private static String fuente(String archivo) {
        String r = "dza/fut/paipo/" + archivo;
        try (InputStream in = PlayerServidoresTest.class.getClassLoader()
                .getResourceAsStream(r)) {
            if (in != null) return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ignore) { }
        File f = new File("src/main/java/dza/fut/paipo/" + archivo);
        if (!f.isFile()) f = new File("app/src/main/java/dza/fut/paipo/" + archivo);
        try {
            return new String(Files.readAllBytes(f.toPath()),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("no encuentro la fuente de " + archivo + ": " + e);
        }
    }

    @Test
    public void hayChipsDeServidoresYLaHojaPasaTodasLasOpciones() {
        String p = fuente("PlayerActivity.java");
        assertTrue("falta cargarOpcionServidor", p.contains("void cargarOpcionServidor("));
        assertTrue("falta pintarChipsServidores", p.contains("void pintarChipsServidores()"));
        assertTrue("falta leerOpcionesDeIntent", p.contains("void leerOpcionesDeIntent("));
        assertTrue("falta el marcado de opciones fallidas", p.contains("opcionesFallidas"));

        String hoja = fuente("BottomSheetOpcionesFragment.java");
        assertTrue("la hoja debe pasar las etiquetas de todas las opciones",
                hoja.contains("opciones_labels"));
        assertTrue("la hoja debe pasar el indice elegido",
                hoja.contains("opcion_indice"));
    }

    @Test
    public void ningunErrorSaltaSoloDeOpcion() {
        String p = fuente("PlayerActivity.java");
        int i = p.indexOf("public void onPlayerError(PlaybackException");
        assertTrue("debe existir onPlayerError", i >= 0);
        int fin = p.indexOf("@Override", i + 10);
        String cuerpo = p.substring(i, fin > i ? fin : Math.min(p.length(), i + 6000));
        assertFalse("onPlayerError NO debe cambiar de servidor por su cuenta",
                cuerpo.contains("cargarOpcionServidor("));
        assertFalse("onPlayerError NO debe zapear de canal por su cuenta",
                cuerpo.contains("zapearCanal("));
        assertFalse("no debe existir el salto silencioso",
                p.contains("switchToNextServerSilently"));
        assertTrue("al agotar reintentos se marca la opcion fallida",
                cuerpo.contains("opcionesFallidas.add(opcionActual)"));
    }

    private static String fuenteRes(String ruta) {
        File f = new File("src/main/res/" + ruta);
        if (!f.isFile()) f = new File("app/src/main/res/" + ruta);
        try {
            return new String(Files.readAllBytes(f.toPath()),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("no encuentro el recurso " + ruta + ": " + e);
        }
    }

    @Test
    public void playerEstiloExoplayerConCentroServidorYCanales() {
        String land = fuenteRes("layout/activity_player.xml");
        String port = fuenteRes("layout/activity_player_portrait.xml");

        for (String id : new String[]{
                "@+id/centerControlsLayout", "@+id/headerLayout", "@+id/serversContainer",
                "@+id/channelsLayout", "@+id/channelsContainer", "@+id/videoContainer",
                "@+id/playerView", "@+id/btnBack", "@+id/btnRefresh"}) {
            assertTrue("layout land debe contener " + id, land.contains(id));
            assertTrue("layout port debe contener " + id, port.contains(id));
        }

        String p = fuente("PlayerActivity.java");
        assertTrue("rotar re-infla sin recrear la actividad",
                p.contains("onConfigurationChanged"));
        assertTrue("existe el zapeo manual de canales",
                p.contains("void zapearCanal(") && p.contains("void pintarTiraCanales()"));
    }
}
