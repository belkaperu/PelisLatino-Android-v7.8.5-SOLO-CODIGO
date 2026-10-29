package dza.fut.paipo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * v3.2.52 — el panel del circulo de Drama. En web.dramalive.cc el boton redondo de la
 * izquierda de la tira abre la lista COMPLETA: primero los paises (con bandera, el del
 * aparato clavado) y despues las categorias (Deportes, Bein, Noticias...). Esta app tiene
 * ademas el recorte MAX_TABS=12 en la tira: el panel es la puerta a los topicos que no
 * caben ahi. Clase pura de JVM: la prueba la suite sin Android.
 *
 * Filas: {tipo, clave, rotulo, bandera, cantidad} —
 *   tipo "fijo": accion de la barra (Todos, Favoritos, Vistos, Vivos)
 *   tipo "header": separador de seccion (no clicable)
 *   tipo "pais" / "categoria": topicos de la fuente
 */
public final class PanelCategorias {

    private PanelCategorias() { }

    public static List<String[]> construir(Map<String, Integer> conteoTopicos, String cc,
                                           int nFavoritos, int nVistos, boolean hayVivos,
                                           int totalCanales) {
        return construir(conteoTopicos, cc, nFavoritos, nVistos, hayVivos, totalCanales,
                java.util.Collections.<String, String>emptyMap());
    }

    /** v3.2.56: `logos` es el manifiesto {id o nombre del topic -> img_url_topic}.
     *  Si la tabla local no conoce el pais, la bandera sale del ISO de la url y la
     *  fila cuenta como pais (entra en el grupo de paises, no como categoria). */
    public static List<String[]> construir(Map<String, Integer> conteoTopicos, String cc,
                                           int nFavoritos, int nVistos, boolean hayVivos,
                                           int totalCanales, Map<String, String> logos) {
        return construir(conteoTopicos, cc, nFavoritos, nVistos, hayVivos, totalCanales,
                logos, java.util.Collections.<String, String>emptyMap());
    }

    /** v3.2.59: `catalogo` es {id_topic -> nombre humano} de los 265 topics del glt.
     *  Los paises que el feed de la extraccion (tope 40) no trajo entran igual, con su
     *  bandera y contador vacio — tocarlos dispara la carga bajo demanda, como en la
     *  web de Drama. Dedupe por ROTULO canónico: "pe"/"Peru" es el mismo pais. */
    public static List<String[]> construir(Map<String, Integer> conteoTopicos, String cc,
                                           int nFavoritos, int nVistos, boolean hayVivos,
                                           int totalCanales, Map<String, String> logos,
                                           Map<String, String> catalogo) {
        List<String[]> out = new ArrayList<>();
        out.add(new String[]{"fijo", TiraCanales.CLAVE_TODO, "Todos", "\u25CE", String.valueOf(totalCanales)});
        if (nFavoritos > 0)
            out.add(new String[]{"fijo", TiraCanales.CLAVE_FAVORITOS, "Favoritos", "\u2665", String.valueOf(nFavoritos)});
        if (nVistos > 0)
            out.add(new String[]{"fijo", TiraCanales.CLAVE_VISTOS, "Los mas vistos", "\u2605", String.valueOf(nVistos)});
        if (hayVivos)
            out.add(new String[]{"fijo", TiraCanales.CLAVE_VIVOS, "Partidos en vivo", "\uD83D\uDD25", ""});

        List<String[]> paises = new ArrayList<>();
        List<String[]> paisesAjenos = new ArrayList<>();
        List<String[]> categorias = new ArrayList<>();
        String ccUp = cc == null ? "" : cc.trim().toUpperCase(Locale.ROOT);
        for (Map.Entry<String, Integer> e : conteoTopicos.entrySet()) {
            String clave = e.getKey();
            String bandera = FusionRejilla.banderaDeTopic(clave);
            String rotulo = FusionRejilla.etiquetaTopic(clave);
            if (bandera.isEmpty() && logos != null && !logos.isEmpty()) {
                String u = logos.get(clave);
                if (u == null) u = logos.get(rotulo);
                bandera = FusionRejilla.banderaDeUrlTopic(u);
            }
            if (!bandera.isEmpty() && rotulo.startsWith(bandera)) rotulo = rotulo.substring(bandera.length()).trim();
            String[] fila = new String[]{"pais", clave, rotulo, bandera, String.valueOf(e.getValue())};
            if (!bandera.isEmpty()) {
                if (FusionRejilla.topicEsPais(clave, ccUp)) paises.add(fila);        // el tuyo, primero
                else paisesAjenos.add(fila);
            } else {
                fila[0] = "categoria";
                categorias.add(fila);
            }
        }
        paises.addAll(paisesAjenos);
        // v3.2.59: catalogo del glt — paises y categorias que el feed no trajo
        java.util.Set<String> rotulos = new java.util.HashSet<>();
        for (String[] f : paises) rotulos.add(f[2].toLowerCase(Locale.ROOT));
        for (String[] f : categorias) rotulos.add(f[2].toLowerCase(Locale.ROOT));
        java.util.List<String[]> paisesCat = new ArrayList<>();
        java.util.List<String[]> catsCat = new ArrayList<>();
        if (catalogo != null && !catalogo.isEmpty()) {
            for (Map.Entry<String, String> e : catalogo.entrySet()) {
                String id = e.getKey();
                if (id == null || id.isEmpty() || conteoTopicos.containsKey(id)) continue;
                String nom = e.getValue();
                if (nom == null || nom.trim().isEmpty()) nom = id;
                String band = FusionRejilla.banderaDeTopic(nom);
                if (band.isEmpty() && logos != null)
                    band = FusionRejilla.banderaDeUrlTopic(logos.get(id));
                String rot = FusionRejilla.etiquetaTopic(nom);
                if (rot.isEmpty()) rot = nom;
                // como en las filas del feed: la bandera sale aparte, el rotulo va limpio
                if (!band.isEmpty() && rot.startsWith(band)) rot = rot.substring(band.length()).trim();
                if (!rotulos.add(rot.toLowerCase(Locale.ROOT))) continue;   // ya esta pintado
                String[] fila = new String[]{"pais", id, rot, band, ""};
                if (!band.isEmpty()) paisesCat.add(fila);
                else { fila[0] = "categoria"; catsCat.add(fila); }
            }
            java.util.Collections.sort(paisesCat, new java.util.Comparator<String[]>() {
                @Override public int compare(String[] a1, String[] b1) {
                    return a1[2].compareToIgnoreCase(b1[2]);
                }
            });
            java.util.Collections.sort(catsCat, new java.util.Comparator<String[]>() {
                @Override public int compare(String[] a1, String[] b1) {
                    return a1[2].compareToIgnoreCase(b1[2]);
                }
            });
        }
        out.addAll(paises);       // v3.2.53: sin secciones — una sola rejilla, como el
        out.addAll(paisesCat);    // panel del Drama real (captura del usuario)
        out.addAll(categorias);
        out.addAll(catsCat);
        return out;
    }

    /** v3.2.53: columnas de la rejilla del panel segun el ancho en dp (Drama usa 4 en
     *  telefono; en pantallas grandes se estira a 5-6 para que las celdas no crezcan). */
    public static int columnasPanel(float anchoDp) {
        if (anchoDp < 340f) return 3;
        if (anchoDp >= 720f) return 6;
        if (anchoDp >= 520f) return 5;
        return 4;
    }

    /** Glifo-emoji para las categorias conocidas (el panel de Drama pinta el logo de
     *  cada categoria; aqui usamos el emoji que la describe, sin assets). "" si no hay
     *  emoji digno — la celda cae entonces al glifo de rejilla del layout. */
    public static String emojiPara(String rotulo) {
        if (rotulo == null) return "";
        String t = rotulo.toLowerCase(java.util.Locale.ROOT);
        if (t.contains("deporte") || t.contains("sport") || t.contains("bein") || t.contains("futsal")) return "\u26BD";
        if (t.contains("noticia") || t.contains("news")) return "\uD83D\uDCF0";
        if (t.contains("pelicula") || t.contains("movie") || t.contains("cine")) return "\uD83C\uDF9E";
        if (t.contains("kid") || t.contains("infantil") || t.contains("kids")) return "\uD83E\uDDD2";
        if (t.contains("music") || t.contains("musica")) return "\uD83C\uDFB5";
        if (t.contains("document") || t.contains("doc ")) return "\uD83D\uDCFD";
        if (t.contains("religio") || t.contains("coran") || t.contains("islam")) return "\uD83D\uDD4C";
        if (t.contains("cook") || t.contains("cocina") || t.contains("food")) return "\uD83C\uDF73";
        if (t.contains("science") || t.contains("ciencia")) return "\uD83E\uDDEA";
        if (t.contains("anime")) return "\uD83C\uDF38";
        if (t.contains("twitch") || t.contains("gamer") || t.contains("juego")) return "\uD83C\uDFAE";
        if (t.contains("ent") || t.contains("entreten")) return "\uD83C\uDFAD";
        if (t.contains("infantil")) return "\uD83C\uDF81";
        if (t.contains("auto") || t.contains("motor")) return "\uD83C\uDFCE";
        if (t.contains("infantil2")) return "";
        return "";
    }

    /** Filtro del buscador del panel: se queda con las filas cuyo nombre contiene el
     *  texto (normalizado, sin acentos); "Todos" sobrevive siempre para poder salir. */
    public static java.util.List<String[]> filtrar(java.util.List<String[]> filas, String texto) {
        if (texto == null || texto.trim().isEmpty()) return filas;
        String q = java.text.Normalizer.normalize(texto.trim().toLowerCase(java.util.Locale.ROOT),
                java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+", "").replaceAll("[^a-z0-9]", "");
        java.util.List<String[]> out = new java.util.ArrayList<>();
        for (String[] f : filas) {
            if ("fijo".equals(f[0]) && f[1] != null && f[1].isEmpty()) { out.add(f); continue; }
            String nom = java.text.Normalizer.normalize(f[2].toLowerCase(java.util.Locale.ROOT),
                    java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+", "").replaceAll("[^a-z0-9]", "");
            if (nom.contains(q)) out.add(f);
        }
        return out;
    }
}

