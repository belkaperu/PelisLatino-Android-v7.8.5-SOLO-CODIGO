package dza.fut.paipo;

import java.nio.charset.StandardCharsets;

/**
 * Reducción de logos por proxy, copiada del mecanismo del cliente web de DramaLive.
 *
 * <p>En su bundle (v13.0.3.241, función {@code bmL}) cada logo de la rejilla se pide como
 * {@code https://images.weserv.nl/?url=<url escapada>&w=<ancho>&output=webp}, y solo se
 * salta los {@code data:}, los {@code assets/} y los que ya vienen proxeados. Con catálogos
 * de miles de canales eso es la diferencia entre pedir 11.000 PNG de 200 KB y pedir
 * recortes WebP de unos pocos KB: el tile se pinta aunque el origen del logo vaya lento.
 *
 * <p>Viene <b>apagado por defecto</b> en la app: al encenderlo, una tercera parte (weserv.nl)
 * ve qué lista estás abriendo. Se decide en la hoja "Fuente" ({@code cambioLogosReducidos}).
 */
public final class Logos {

    /** La misma base que usa él. */
    static final String BASE = "https://images.weserv.nl/?url=";

    /** Ancho del tile de canales a 2 columnes en un teléfono normal, con margen. */
    static final int ANCHO_TILE = 220;

    private Logos() {
    }

    // ───────────────────── v3.2.32 — letra de losa (estilo DramaLive) ─────────────────
    // Los canales sin logo (o con logo roto) ya no muestran un hueco gris: llevan sus
    // iniciales sobre un degradado, un matiz por nombre. Constantes separadas para que
    // la logica de eleccion sea PURA y testeable en JVM (el bitmap necesita Context).
    /** 440 px: la losa mide ~166dp (~500 px en pantallas xxhdpi); con fitCenter un avatar
     *  de 220 se estiraba 2.3x y se veía borroso — 440 queda a medio paso y nítido. */
    static final int AVATAR_PX = 440;
    static final int[][] PALETA = {
            {0xFFB71C1C, 0xFF5C0F0F}, {0xFF0D47A1, 0xFF062A61}, {0xFF00695C, 0xFF00322C},
            {0xFF4527A0, 0xFF1E0F52}, {0xFFEF6C00, 0xFF8C3D00}, {0xFF37474F, 0xFF151F24},
            {0xFF880E4F, 0xFF4A012B}, {0xFF1565C0, 0xFF053465}
    };

    static int indicePaleta(String nombre) {
        if (nombre == null || nombre.isEmpty()) return 0;
        int h = 7;
        for (int i = 0; i < nombre.length(); i++) h = 31 * h + nombre.charAt(i);
        return Math.abs(h) % PALETA.length;
    }

    /** Hasta dos iniciales de las primeras palabras; «?» cuando no hay con que. Pura. */
    static String iniciales(String nombre) {
        if (nombre == null) return "?";
        String[] trozos = nombre.trim().split("\\s+");
        java.util.ArrayList<String> ws = new java.util.ArrayList<>();
        for (String p : trozos) if (p != null && !p.isEmpty()) ws.add(p);
        if (ws.isEmpty()) return "?";
        if (ws.size() == 1) {
            String w = ws.get(0);
            return (w.length() >= 2 ? w.substring(0, 2) : w).toUpperCase(java.util.Locale.ROOT);
        }
        return ("" + ws.get(0).charAt(0) + ws.get(ws.size() > 1 ? 1 : 0).charAt(0))
                .toUpperCase(java.util.Locale.ROOT);
    }

    /** Losa de iniciales (gradiente por nombre + texto). Drawable fresco por llamada —
     *  Glide lo usa como placeholder/error y el ImageView lo recorta centro. */
    static android.graphics.drawable.Drawable avatar(android.content.Context ctx, String nombre) {
        int[] par = PALETA[indicePaleta(nombre == null ? "" : nombre)];
        android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(
                AVATAR_PX, AVATAR_PX, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas cd = new android.graphics.Canvas(bm);
        android.graphics.Paint bg = new android.graphics.Paint();
        bg.setShader(new android.graphics.LinearGradient(0, 0, AVATAR_PX, AVATAR_PX,
                par[0], par[1], android.graphics.Shader.TileMode.CLAMP));
        cd.drawRect(0, 0, AVATAR_PX, AVATAR_PX, bg);
        android.graphics.Paint tx = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        tx.setColor(0xEFFFFFFF);
        tx.setTextAlign(android.graphics.Paint.Align.CENTER);
        tx.setTypeface(android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.BOLD));
        tx.setTextSize(AVATAR_PX * 0.40f);
        cd.drawText(iniciales(nombre), AVATAR_PX / 2f,
                AVATAR_PX / 2f - (tx.ascent() + tx.descent()) / 2f, tx);
        return new android.graphics.drawable.BitmapDrawable(ctx.getResources(), bm);
    }

    /** true si la URL admite pasar por el reductor (http/https, no data:, no ya proxeadas). */
    static boolean aplicable(String url) {
        if (url == null) return false;
        String u = url.trim();
        if (u.isEmpty()) return false;
        if (u.startsWith("data:") || u.startsWith("assets/") || u.startsWith("file://")
                || u.startsWith("content://")) return false;
        if (u.contains("images.weserv.nl/")) return false;   // ya viene proxeada: no doblar
        if (!(u.startsWith("http://") || u.startsWith("https://"))) return false;
        int sep = u.indexOf("://");
        return sep + 3 < u.length() - 1;   // un esquema sin host ("https://") no sirve
    }

    /** Devuelve la URL reducida, o la original tal cual si no aplica reducirla. */
    static String reducido(String url, int ancho) {
        if (!aplicable(url)) return url == null ? "" : url.trim();
        return BASE + escapar(url.trim()) + "&w=" + Math.max(16, ancho) + "&output=webp";
    }

    /** v3.2.55: los iconos de topic de Drama (banderas incl.) van DIRECTOS desde su CDN.
     *  Medido (logo.twoapistack.work/img/topics/ic_flag_pe.png): el PNG es palette de
     *  4 bits, 399 B y perfecto; el proxy con &output=webp lo devuelve como 164 B de
     *  color solido — un rectangulo BLANCO, que es lo que veia el usuario. El original
     *  pesa menos que la convers rota: para estos iconos chicos, sin proxy. */
    static String iconoTopico(String url) {
        return url == null ? "" : url.trim();
    }

    static String reducido(String url) {
        return reducido(url, ANCHO_TILE);
    }

    /**
     * Porcentaje-escape al estilo de {@code encodeURIComponent}: se quedan sin escapar las
     * no reservadas ASCII. Hace falta escapar {@code :} y {@code /} porque el proxy toma el
     * resto de la línea como su propio path si se dejan crudos.
     */
    static String escapar(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            boolean libre = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~';
            if (libre) sb.append((char) c);
            else sb.append('%').append(Character.toUpperCase(Character.forDigit((c >> 4) & 0xF, 16)))
                    .append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
        }
        return sb.toString();
    }
}
