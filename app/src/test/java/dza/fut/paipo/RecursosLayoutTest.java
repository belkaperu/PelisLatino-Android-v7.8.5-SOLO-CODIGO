package dza.fut.paipo;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * Guarda contra el {@code InflateException} que no ve ni el build ni lint.
 *
 * <p>Hay atributos de framework cuyo formato declarado es {@code reference|color}, así que
 * AAPT2 acepta un {@code @dimen} sin protestar y el {@code Resources#getDrawable} solo
 * revienta en el inflado, en la cara del usuario. Nos pasó con
 * {@code android:divider="@dimen/space_1"} en la tira de pestañas de canales (error en
 * runtime "Binary XML file line #124 ... Error inflating class &lt;unknown&gt;").
 *
 * <p>Este test recorre los layouts del módulo y falla si algún atributo que pide Drawable
 * referencia un dimen o un integer. Es barato (~200 archivos, un par de ms) y corre sin
 * dispositivo, que es justo lo que falta para atrapar esta clase de bug.
 */
public class RecursosLayoutTest {

    /** Atributos que en la plataforma se resuelven como Drawable. */
    private static final List<String> PIDEN_DRAWABLE = Arrays.asList(
            "divider", "src", "background", "foreground", "listSelector", "button",
            "thumb", "track", "tickMark", "progressDrawable", "scrollbarThumbHorizontal",
            "scrollbarThumbVertical", "scrollbarTrackHorizontal", "scrollbarTrackVertical",
            "drawableTop", "drawableBottom", "drawableLeft", "drawableRight",
            "drawableStart", "drawableEnd", "drawableOverride");

    private static final Pattern MAL = Pattern.compile(
            "(?:android|app):(" + String.join("|", PIDEN_DRAWABLE) + ")\\s*=\\s*\"@(dimen|integer)/[^\"]*\"");

    @Test
    public void ningunAtributoDeDrawableApuntaAUnDimen() throws Exception {
        File dir = buscarLayouts();
        List<String> ofensores = new ArrayList<>();
        for (File f : dir.listFiles((d, n) -> n.endsWith(".xml"))) {
            List<String> lineas = sinComentarios(f);
            for (int i = 0; i < lineas.size(); i++) {
                Matcher m = MAL.matcher(lineas.get(i));
                if (m.find()) {
                    ofensores.add(f.getName() + ":" + (i + 1) + " -> " + m.group().trim());
                }
            }
        }
        assertTrue("atributos que piden Drawable apuntando a @dimen/@integer (revientan al "
                + "inflar, no en el build): " + ofensores, ofensores.isEmpty());
    }

    /** Los comentarios no cuentan: solo los atributos de verdad. */
    private static List<String> sinComentarios(File f) throws Exception {
        String crudo = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        String limpio = Pattern.compile("<!--.*?-->", Pattern.DOTALL).matcher(crudo).replaceAll(" ");
        return Arrays.asList(limpio.split("\n", -1));
    }

    /** La tira de pestañas se separa con márgenes del chip, no con dividers del contenedor. */
    @Test
    public void laTiraDePestanasNoUsaDividers() throws Exception {
        File f = new File(buscarLayouts(), "fragment_canales_en_vivo.xml");
        StringBuilder sb = new StringBuilder();
        for (String linea : sinComentarios(f)) sb.append(linea).append('\n');
        String s = sb.toString();
        assertFalse("el contenedor de tabs no puede llevar android:divider (pide Drawable)",
                s.contains("android:divider"));
    }

    private static File buscarLayouts() {
        for (String candidato : new String[]{"src/main/res/layout", "app/src/main/res/layout",
                "../app/src/main/res/layout"}) {
            File dir = new File(candidato);
            if (dir.isDirectory()) return dir;
        }
        throw new AssertionError("no encuentro res/layout desde " + new File(".").getAbsolutePath()
                + " (¿se movió la estructura del módulo?)");
    }
}
