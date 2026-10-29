package dza.fut.paipo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;

import org.junit.Test;

/**
 * v3.2.65 — contrato entre el repo de canales latinos del usuario
 * (youcefyakhoo/cesopppplop) y el parser histórico de la app
 * ({@link Canal.RespuestaCanales} vía Gson, el mismo camino que recorre
 * {@code FuenteCanales.parsearCuerpo} cuando AutoCanales descarga la fuente).
 * La muestra replica la forma EXACTA del canales.json publicado.
 */
public class LatinosTest {

    private static final String MUESTRA = "{"
            + "\"categorias\":[\"Deportes\",\"Entretenimiento\",\"Películas\","
            + "\"Infantiles\",\"Noticias\",\"Internacionales\"],"
            + "\"total\":2,"
            + "\"canales\":["
            + "{\"title\":\"TUDN\",\"image\":\"https://www.tvporinternet2.com/imge/tudn.webp\","
            + "\"category\":\"Deportes\",\"options\":["
            + "{\"label\":\"1\",\"url\":\"http://190.14.10.19:16000/play/a09v/index.m3u8\"},"
            + "{\"label\":\"ESPN HD\",\"url\":\"https://streamxhd.com/live1.php?stream=tudn\"}]},"
            + "{\"title\":\"Canal de Película\",\"image\":\"\",\"category\":\"Películas\","
            + "\"options\":[{\"label\":\"Ver\",\"url\":\"https://ejemplo.tv/cine/index.m3u8\"}]}"
            + "]}";

    @Test
    public void laUrlApuntaAlRepoPublicado() {
        assertEquals(
                "https://raw.githubusercontent.com/youcefyakhoo/cesopppplop/main/canales.json",
                AutoCanales.LATINOS_JSON);
    }

    @Test
    public void elJsonDelRepoParseaConLasClasesDeLaApp() {
        Canal.RespuestaCanales r = new Gson().fromJson(MUESTRA, Canal.RespuestaCanales.class);
        assertTrue(r != null && r.canales != null);
        assertEquals(2, r.canales.size());

        Canal tudn = r.canales.get(0);
        assertEquals("TUDN", tudn.title);
        assertEquals("Deportes", tudn.category);
        assertTrue(tudn.image.startsWith("https://"));
        assertEquals(2, tudn.options.size());
        // La primera opción es el stream directo (así lo ordena el constructor).
        assertTrue(tudn.options.get(0).url.endsWith(".m3u8"));
        assertEquals("ESPN HD", tudn.options.get(1).label);

        // La categoría con tilde sobrevive tal cual (será pestaña «Películas»).
        assertEquals("Películas", r.canales.get(1).category);
    }
}
