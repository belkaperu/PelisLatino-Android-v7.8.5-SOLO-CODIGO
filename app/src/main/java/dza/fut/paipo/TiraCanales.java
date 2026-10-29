package dza.fut.paipo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * v3.2.43 — la tira de canales como en Drama (capturas del usuario, 2026-10-17):
 *   ◎ Todos · ♥ Favoritos · ★ Los más vistos · 🇵 [país del aparato] ·
 *   …los tópicos en SU orden de llegada… · 🔥 Partidos en vivo
 *
 * Drama clava el país del usuario antes que los demás (el de su zona horaria — en las
 * capturas, "Peru" entre ★ y los topics) y NUNCA reordena los grupos por población.
 * Si el aparato es de un país que no está en la lista, el chip no se inventa.
 * Clase pura: la tira se prueba en la suite sin Android.
 */
public final class TiraCanales {

    public static final String CLAVE_TODO = "";
    public static final String CLAVE_FAVORITOS = "\u0000fav";
    public static final String CLAVE_VISTOS = "\u0000top";
    public static final String CLAVE_VIVOS = "\u0000vivos";

    private TiraCanales() { }

    /**
     * [{clave, rótulo, cantidad}] en el orden de la tira. La `conteo` debe preservar el
     * orden de inserción (el fragmento usa LinkedHashMap: el orden de llegada de los
     * tópicos es el dato, no un adorno).
     */
    public static List<String[]> construir(Map<String, Integer> conteoTopicos,
                                           String paisDispositivoCc, int nFavoritos,
                                           int nConHistorial, boolean hayPartidosEnVivo) {
        List<String[]> out = new ArrayList<>();
        out.add(new String[]{CLAVE_TODO, "Todos", ""});
        if (nFavoritos > 0) out.add(new String[]{CLAVE_FAVORITOS, "\u2665 Favoritos", String.valueOf(nFavoritos)});
        if (nConHistorial > 0) out.add(new String[]{CLAVE_VISTOS, "\u2605 Los m\u00e1s vistos", String.valueOf(nConHistorial)});
        String cc = paisDispositivoCc == null ? "" : paisDispositivoCc.trim().toUpperCase(Locale.ROOT);
        List<String[]> paisPropio = new ArrayList<>();
        List<String[]> demas = new ArrayList<>();
        for (Map.Entry<String, Integer> e : conteoTopicos.entrySet()) {
            String[] fila = new String[]{e.getKey(), FusionRejilla.etiquetaTopic(e.getKey()), String.valueOf(e.getValue())};
            if (!cc.isEmpty() && FusionRejilla.topicEsPais(e.getKey(), cc)) paisPropio.add(fila);
            else demas.add(fila);
        }
        out.addAll(paisPropio);   // clava al usuario primero, como Drama
        out.addAll(demas);
        if (hayPartidosEnVivo) out.add(new String[]{CLAVE_VIVOS, "\uD83D\uDD25 Partidos en vivo", ""});
        return out;
    }

    /**
     * El marcador con el que el feed de Drama señala los partidos en directo (el chip
     * "Live Matches" de las capturas): las filas cuyo stream pasa por el handler custom.
     */
    public static boolean esPartidoEnVivo(String url) {
        return url != null && url.contains("live_tv_custom_handler");
    }

    /** El país del aparato según el locale (PE, AR, …) — en blanco si el SO no lo dice. */
    public static String paisDelAparato() {
        return Locale.getDefault().getCountry();
    }
}
