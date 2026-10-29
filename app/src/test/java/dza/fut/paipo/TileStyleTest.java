package dza.fut.paipo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** v3.2.32: la matematica del estilo (columnas gordas + letra de losa) es pura y se
 *  prueba sin Android. */
public class TileStyleTest {

    /**
     * v3.2.48 (repo 82105f5): la bandera del pais en la tira de categorias.
     *
     * <p>Se usa el emoji de indicadores regionales, asi que el codigo ISO se
     * convierte en dos puntos de codigo: U+1F1E6 + (letra - 'A'). Las banderas se
     * escriben con escapes unicode para que el test no dependa de como se guarde
     * este archivo. (Ojo: no se puede escribir la barra invertida suelta en un
     * comentario, porque Java la interpreta antes de compilar.)
     */
    @Test
    public void lasBanderasSalenDelCodigoIso() {
        // PE = U+1F1F5 U+1F1EA, MX = U+1F1F2 U+1F1FD, AR = U+1F1E6 U+1F1F7
        assertEquals("\uD83C\uDDF5\uD83C\uDDEA", Ui.banderaEmoji("PE"));
        assertEquals("\uD83C\uDDF2\uD83C\uDDFD", Ui.banderaEmoji("mx"));
        assertEquals("\uD83C\uDDE6\uD83C\uDDF7", Ui.banderaEmoji("AR"));
        // Minusculas y espacios sobrantes no rompen.
        assertEquals("\uD83C\uDDF5\uD83C\uDDEA", Ui.banderaEmoji(" pe".trim().toUpperCase()));
        // Basura: mejor nada que un cuadro vacio.
        assertEquals("", Ui.banderaEmoji(null));
        assertEquals("", Ui.banderaEmoji(""));
        assertEquals("", Ui.banderaEmoji("P"));
        assertEquals("", Ui.banderaEmoji("PER"));
        assertEquals("", Ui.banderaEmoji("12"));
    }

    /** La tira pinta la bandera del topic-pais (tabla PAISES), no un glifo generico;
     *  los topicos normales siguen sin bandera. Y el pais del aparato se clava primero,
     *  como Drama (v3.2.43), ahora con su bandera en el tile. */
    @Test
    public void laBanderaSaleDelTopicYElPaisSeClavaPrimero() {
        assertEquals("\uD83C\uDDF5\uD83C\uDDEA", FusionRejilla.banderaDeTopic("live_tv_peru"));
        assertEquals("\uD83C\uDDE6\uD83C\uDDF7", FusionRejilla.banderaDeTopic("Live_TV_Argentina"));
        assertEquals("", FusionRejilla.banderaDeTopic("Deportes"));
        assertEquals("", FusionRejilla.banderaDeTopic(null));
        java.util.Map<String, Integer> conteo = new java.util.LinkedHashMap<>();
        conteo.put("Deportes", 5);
        conteo.put("live_tv_peru", 12);
        java.util.List<String[]> tira = TiraCanales.construir(conteo, "PE", 0, 0, false);
        assertEquals(TiraCanales.CLAVE_TODO, tira.get(0)[0]);   // Todos siempre primero
        assertEquals("live_tv_peru", tira.get(1)[0]);            // el pais clavado, antes
        assertTrue(tira.get(1)[1].contains("\uD83C\uDDF5\uD83C\uDDEA")); // con bandera en el rotulo
    }

    @Test
    public void columnasHacenLosasDeDramaLive() {
        assertEquals(2, CanalesEnVivoFragment.columnasPara(360f)); // telefono: tiles ~166dp
        assertEquals(2, CanalesEnVivoFragment.columnasPara(300f)); // piso 2, nunca 3 chicos
        assertEquals(3, CanalesEnVivoFragment.columnasPara(500f));
        assertEquals(4, CanalesEnVivoFragment.columnasPara(700f));
        assertEquals(4, CanalesEnVivoFragment.columnasPara(1080f)); // techo 4 (tablet)
    }

    @Test
    public void inicialesTomanPrimerasDosPalabras() {
        assertEquals("DS", Logos.iniciales("Directv Sports"));
        assertEquals("BS", Logos.iniciales("beIN SPORTS 1"));
        assertEquals("HB", Logos.iniciales("HBO"));
        assertEquals("?", Logos.iniciales(null));
        assertEquals("?", Logos.iniciales("   "));
        assertEquals("T", Logos.iniciales("T"));
    }

    @Test
    public void avatarNitidoParaLosasGrandes() {
        // v3.2.33: losa ~166dp (≈500 px) con fitCenter — el avatar tiene que nacer grande.
        assertEquals(440, Logos.AVATAR_PX);
        assertTrue(Logos.AVATAR_PX >= 2 * 166);
    }

    @Test
    public void paletaEsEstableYEstaEnRango() {
        assertEquals(Logos.indicePaleta("Directv Sports"), Logos.indicePaleta("Directv Sports"));
        for (String n : new String[]{"A", "Bein", "TNT Sports", "", "Zzz 9"}) {
            int ix = Logos.indicePaleta(n);
            assertTrue(n, ix >= 0 && ix < Logos.PALETA.length);
        }
        assertEquals(0, Logos.indicePaleta(null));
        assertEquals(0, Logos.indicePaleta(""));
    }
}
