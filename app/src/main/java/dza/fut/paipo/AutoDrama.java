package dza.fut.paipo;

/**
 * v3.2.44 — el cerebro del "en segundo plano, sin pedir nada". Alguien tiene que
 * decidir si HOYA toca moverse, y esa decisión es aritmética pura: nada de Android,
 * todo de la suite. La regla de Drama medida en capturas y logs: identidad estable +
 * pocas visitas — el edge blaquea al que repide (anti-flood, v3.2.34).
 */
public final class AutoDrama {

    public static final int NADA = 0;
    /** Primera vez sin sesión: auto-conectar (glt empotrado con el FG 232425) + extraer. */
    public static final int CONEXION = 1;
    /** Sesión ya guardada y vieja: refresco NATIVO (extraer + fusionar), sin WebView. */
    public static final int REFRESCO = 2;

    static final long VENTANA_MS = 6L * 3600_000L;   // la de siempre (v3.2.34)

    private AutoDrama() { }

    /** Qué toca hacer ahora mismo al entrar en la pestaña de canales. */
    public static int plan(long ahora, long ultimoIntentoTs, boolean sesionVacia) {
        // v3.2.47 — CERO PRIMER PLANO TAMBIEN EN EL PLAYER (log del usuario 01:06): el ultimo
        // salto a "conectar a Drama" vivia en PlayerActivity, no en esta rejilla. Cuando el
        // glbr devolvia el sueno update.mp4 y la cosecha headless no curaba, el player escalaba
        // al captador VISIBLE a pantalla completa (tres caminos: rama QUEMADO, escape al agotar
        // la cosecha, tope de 3 ciclos del polling). Esa pantalla no curaba nada — «cosecha fin:
        // 0/1 reales · curado=0» — y revolvía display rotations y re-inyecciones. Los tres
        // caminos quedaron como reintento headless con tope (~90 s) y aviso honesto; la
        // maduracion (anuncio muted + runCount++ por uso) es 100% invisible para el usuario.
        // v3.2.46 — TOLERANCIA AL RELOJ (queja medida del usuario: «se devolvió el
        // reloj»). Una resta a la brava con la hora de pared congelaba la rejilla para
        // siempre si el aparato atrasaba (delta negativo, jamás "vieja"), y el camino a
        // medias dejaba que sin sesión se repidiera al edge en CADA resume (el antiflood
        // castiga exactly eso). "Fresca" solo vale dentro de [-10 min, +VENTANA]:
        // cualquier anomalía se trata como "toca moverse", el intento reescribe el ts con
        // la hora actual y el asunto se auto-repara — sin dialogs, sin primer plano.
        long delta = ahora - ultimoIntentoTs;
        boolean fresca = ultimoIntentoTs > 0L && delta >= -600_000L && delta <= VENTANA_MS;
        if (fresca) return NADA;
        return sesionVacia ? CONEXION : REFRESCO;
    }

    /**
     * Fusion de la entrega nueva con la rejilla guardada (v3.2.44: el auto ya NO pisa
     * crudo — las urls curadas del player se pierden si no; `fusionar` preserva
     * posiciones y no degrada http→dramalive).
     */
    public static String fusionarEntrega(String rejillaVieja, String m3uNuevo) {
        if (m3uNuevo == null || m3uNuevo.trim().isEmpty()) return rejillaVieja == null ? "" : rejillaVieja;
        if (rejillaVieja == null || rejillaVieja.trim().isEmpty()) return m3uNuevo;
        return DramaSesion.fusionar(rejillaVieja, m3uNuevo);
    }
}
