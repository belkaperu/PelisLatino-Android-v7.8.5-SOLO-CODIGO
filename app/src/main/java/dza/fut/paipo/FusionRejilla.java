package dza.fut.paipo;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * v3.2.42 — Los canales recuperados desde Drama van EN SU LUGAR, como en el sitio:
 * delante y en el orden exacto de su lista (el m3u de la extracción conserva el orden
 * de live_topics[] y, dentro de cada tópico, el del servidor). La lista de la fuente
 * por defecto solo rellena huecos y nunca pisa un canal de Drama. Antes pasaba al revés
 * ("pegados al final", con dedupe por URL — y una URL dramalive:// jamás iguala a una
 * http de data1.json, así que los repetidos salían duplicados).
 *
 * Los rótulos de los chips también salen de aquí: "live_tv_peru" → "🇵🇪 Perú".
 * Clase pura de JVM: la prueba la suite sin tocar Android.
 */
public final class FusionRejilla {

    private FusionRejilla() { }

    /** Clave de dedupe: minúsculas, sin etiqueta de calidad y sin decoración. */
    static String claveNombre(String nombre) {
        if (nombre == null) return "";
        return nombre.toLowerCase(Locale.ROOT)
                .replaceAll("\\(\\s*\\d{3,4}\\s*p\\s*\\)", " ")
                .replaceAll("[^a-z0-9 ]+", " ")
                .trim().replaceAll("\\s+", " ");
    }

    /** Drama primero (en su orden); base después, solo con lo que Drama no trajo. */
    public static List<Canal> conDramaPrimero(List<Canal> drama, List<Canal> base) {
        Set<String> vistos = new HashSet<>();
        List<Canal> out = new ArrayList<>();
        if (drama != null) {
            for (Canal c : drama) {
                if (c == null || c.title == null) continue;
                if (vistos.add(claveNombre(c.title))) out.add(c);
            }
        }
        if (base != null) {
            for (Canal c : base) {
                if (c == null || c.title == null) continue;
                if (vistos.add(claveNombre(c.title))) out.add(c);
            }
        }
        return out;
    }

    // ───────────────────────── países y rótulos ─────────────────────────

    /** {palabra que delata al país, bandera, nombre canónico}. */
    private static final String[][] PAISES = {
        {"peru", "\uD83C\uDDF5\uD83C\uDDEA", "Perú"},
        {"argentina", "\uD83C\uDDE6\uD83C\uDDF7", "Argentina"},
        {"chile", "\uD83C\uDDE8\uD83C\uDDF1", "Chile"},
        {"bolivia", "\uD83C\uDDE7\uD83C\uDDF4", "Bolivia"},
        {"paraguay", "\uD83C\uDDF5\uD83C\uDDFE", "Paraguay"},
        {"uruguay", "\uD83C\uDDFA\uD83C\uDDFE", "Uruguay"},
        {"ecuador", "\uD83C\uDDEA\uD83C\uDDE8", "Ecuador"},
        {"colombia", "\uD83C\uDDE8\uD83C\uDDF4", "Colombia"},
        {"venezuela", "\uD83C\uDDFB\uD83C\uDDEA", "Venezuela"},
        {"mexico", "\uD83C\uDDF2\uD83C\uDDFD", "México"},
        {"usa", "\uD83C\uDDFA\uD83C\uDDF8", "USA"},
        {"estados unidos", "\uD83C\uDDFA\uD83C\uDDF8", "USA"},
        {"espana", "\uD83C\uDDEA\uD83C\uDDF8", "España"},
        {"brasil", "\uD83C\uDDE7\uD83C\uDDF7", "Brasil"},
        {"siria", "\uD83C\uDDF8\uD83C\uDDFE", "Siria"},
        {"syria", "\uD83C\uDDF8\uD83C\uDDFE", "Siria"},
        {"libano", "\uD83C\uDDF1\uD83C\uDDE7", "Líbano"},
        {"lebanon", "\uD83C\uDDF1\uD83C\uDDE7", "Líbano"},
        {"qatar", "\uD83C\uDDF6\uD83C\uDDE6", "Qatar"},
        {"arabia", "\uD83C\uDDF8\uD83C\uDDE6", "Arabia"},
        {"saudi arabia", "\uD83C\uDDF8\uD83C\uDDE6", "Arabia"},
        {"saudi", "\uD83C\uDDF8\uD83C\uDDE6", "Arabia"},
        {"egipto", "\uD83C\uDDEA\uD83C\uDDEC", "Egipto"},
        {"egypt", "\uD83C\uDDEA\uD83C\uDDEC", "Egipto"},
        {"jordania", "\uD83C\uDDEF\uD83C\uDDF4", "Jordania"},
        {"jordan", "\uD83C\uDDEF\uD83C\uDDF4", "Jordania"},
        {"irak", "\uD83C\uDDEE\uD83C\uDDF6", "Irak"},
        {"iraq", "\uD83C\uDDEE\uD83C\uDDF6", "Irak"},
        {"kuwait", "\uD83C\uDDF0\uD83C\uDDFC", "Kuwait"},
        {"oman", "\uD83C\uDDF4\uD83C\uDDF2", "Omán"},
        {"yemen", "\uD83C\uDDFE\uD83C\uDDEA", "Yemen"},
        {"barein", "\uD83C\uDDE7\uD83C\uDDED", "Baréin"},
        {"bahrain", "\uD83C\uDDE7\uD83C\uDDED", "Baréin"},
        {"emiratos", "\uD83C\uDDE6\uD83C\uDDEA", "Emiratos"},
        {"uae", "\uD83C\uDDE6\uD83C\uDDEA", "Emiratos"},
        {"united arab emirates", "\uD83C\uDDE6\uD83C\uDDEA", "Emiratos"},
        {"palestina", "\uD83C\uDDF5\uD83C\uDDF8", "Palestina"},
        {"palestine", "\uD83C\uDDF5\uD83C\uDDF8", "Palestina"},
        {"israel", "\uD83C\uDDEE\uD83C\uDDF1", "Israel"},
        {"iran", "\uD83C\uDDEE\uD83C\uDDF7", "Irán"},
        {"turquia", "\uD83C\uDDF9\uD83C\uDDF7", "Turquía"},
        {"turkey", "\uD83C\uDDF9\uD83C\uDDF7", "Turquía"},
        {"marruecos", "\uD83C\uDDF2\uD83C\uDDE6", "Marruecos"},
        {"morocco", "\uD83C\uDDF2\uD83C\uDDE6", "Marruecos"},
        {"argelia", "\uD83C\uDDE9\uD83C\uDDFF", "Argelia"},
        {"algeria", "\uD83C\uDDE9\uD83C\uDDFF", "Argelia"},
        {"tunez", "\uD83C\uDDF9\uD83C\uDDF3", "Túnez"},
        {"tunisia", "\uD83C\uDDF9\uD83C\uDDF3", "Túnez"},
        {"libia", "\uD83C\uDDF1\uD83C\uDDFE", "Libia"},
        {"libya", "\uD83C\uDDF1\uD83C\uDDFE", "Libia"},
        {"sudan", "\uD83C\uDDF8\uD83C\uDDE9", "Sudán"},
        {"canada", "\uD83C\uDDE8\uD83C\uDDE6", "Canadá"},
        {"yamaica", "\uD83C\uDDEF\uD83C\uDDF2", "Jamaica"},
        {"jamaica", "\uD83C\uDDEF\uD83C\uDDF2", "Jamaica"},
        {"internacional", "\uD83C\uDF0E", "Internacional"},
    };

    /** Código ISO del aparato → palabra que delata al país en un tópico (PE → "peru"). */
    private static final String[][] CC_PAIS = {
        {"PE", "peru"}, {"AR", "argentina"}, {"CL", "chile"}, {"BO", "bolivia"},
        {"PY", "paraguay"}, {"UY", "uruguay"}, {"EC", "ecuador"}, {"CO", "colombia"},
        {"VE", "venezuela"}, {"MX", "mexico"}, {"US", "usa"}, {"ES", "espana"},
        {"BR", "brasil"},
    };

    /** v3.2.51 (captura del usuario: «sale numero, no hay flags»): varias fuentes —
     *  iptv-org y los topics numericos de Drama — nombran a los paises por su CODIGO
     *  TELEFONICO ("974", "961", "966"). Sin esta tabla el tab salia crudo y sin bandera.
     *  {marcador, ISO, nombre en español}. La bandera sale del ISO con Ui.banderaEmoji
     *  (port del repo 82105f5), asi que la tabla no duplica emojis. */
    private static final String[][] MARCADORES = {
        {"51", "PE", "Perú"},          {"54", "AR", "Argentina"},
        {"55", "BR", "Brasil"},         {"56", "CL", "Chile"},
        {"57", "CO", "Colombia"},       {"58", "VE", "Venezuela"},
        {"52", "MX", "México"},         {"591", "BO", "Bolivia"},
        {"593", "EC", "Ecuador"},       {"595", "PY", "Paraguay"},
        {"598", "UY", "Uruguay"},       {"1", "US", "USA"},
        {"34", "ES", "España"},
        {"212", "MA", "Marruecos"},     {"213", "DZ", "Argelia"},
        {"216", "TN", "Túnez"},         {"20", "EG", "Egipto"},
        {"218", "LY", "Libia"},         {"249", "SD", "Sudán"},
        {"966", "SA", "Arabia"},        {"974", "QA", "Qatar"},
        {"971", "AE", "Emiratos"},      {"965", "KW", "Kuwait"},
        {"973", "BH", "Baréin"},        {"968", "OM", "Omán"},
        {"964", "IQ", "Irak"},          {"961", "LB", "Líbano"},
        {"962", "JO", "Jordania"},      {"963", "SY", "Siria"},
        {"967", "YE", "Yemen"},         {"970", "PS", "Palestina"},
        {"972", "IL", "Israel"},        {"98", "IR", "Irán"},
        {"90", "TR", "Turquía"},
    };

    /** El codigo telefonico de un topic numerico ("974", "live_tv_974", "grupo 974");
     *  null si el topic no es un numero suelto de 1 a 4 digitos. */
    private static String marcaDe(String raw) {
        if (raw == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^(?:(?:live )?tv |grupo |pais )?(\\d{1,4})$").matcher(crudeza(raw));
        return m.matches() ? m.group(1) : null;
    }

    /** true si el tópico pertenece al país que sugiere el código ISO del aparato. */
    public static boolean topicEsPais(String topicoCrudo, String codigoPais) {
        if (topicoCrudo == null || codigoPais == null) return false;
        String cc = codigoPais.trim().toUpperCase(Locale.ROOT);
        for (String[] m : CC_PAIS) {
            if (!m[0].equals(cc)) continue;
            String t = " " + crudeza(topicoCrudo) + " ";
            if (m[1].length() <= 3 ? t.contains(" " + m[1] + " ") : t.contains(m[1])) return true;
            break;   // v3.2.51: la palabra no calzo — todavia puede calzar el marcador
        }
        // v3.2.51: y tambien cuando el topic es el codigo telefonico del pais del aparato.
        String marca = marcaDe(topicoCrudo);
        if (marca != null) {
            for (String[] p : MARCADORES) {
                if (p[0].equals(marca) && p[1].equals(cc)) return true;
            }
        }
        return false;
    }

    /** true si el tópico es un país (para darle al chip el globo, no la rejilla). */
    public static boolean esPais(String topicoCrudo) {
        return pais(topicoCrudo) != null;
    }

            /** v3.2.57 («tampoco siempre blanco»): el ISO de la bandera, en minusculas, tal
     *  como nombra al recurso embebido flag_XX.png — la imagen REAL de Drama, bajada
     *  de su propio CDN y metida en el APK (226/237 paises del glt medidos). Con esto
     *  el pais ya no depende NI de la red NI de que la fuente de emojis del telefono
     *  sepa dibujar regional indicators (en el aparato del usuario salian en blanco). */
    @androidx.annotation.NonNull
    public static String isoDeUrlTopic(@androidx.annotation.Nullable String url) {
        if (url == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("flag[_\\-.]?([a-z]{2})\\.(?:png|jpe?g|webp)",
                        java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(url);
        return m.find() ? m.group(1).toLowerCase(java.util.Locale.ROOT) : "";
    }

    /** Inverso de Ui.banderaEmoji: "🇵🇪" -> "pe"; cualquier otra cosa, "". */
    @androidx.annotation.NonNull
    public static String isoDeBanderaEmoji(@androidx.annotation.Nullable String emoji) {
        if (emoji == null || emoji.length() != 4) return "";
        if (emoji.charAt(0) != '\uD83C' || emoji.charAt(2) != '\uD83C') return "";
        char a = emoji.charAt(1), b = emoji.charAt(3);
        if (a < 0xDDE6 || a > 0xDDFF || b < 0xDDE6 || b > 0xDDFF) return "";
        return new String(new char[]{(char) ('a' + (a - 0xDDE6)), (char) ('a' + (b - 0xDDE6))});
    }

/** v3.2.56 («tampoco»): el ISO del pais ya viaja dentro de la url que Drama
     *  manda en img_url_topic (ic_flag_pe.png). Derivar la bandera de ahi da la
     *  bandera correcta para los 237 paises del glt SIN depender de la tabla
     *  local ni de que la descarga de la imagen llegue a tiempo. */
    @androidx.annotation.NonNull
    public static String banderaDeUrlTopic(@androidx.annotation.Nullable String url) {
        if (url == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("flag[_\\-.]?([a-z]{2})\\.(?:png|jpe?g|webp)",
                        java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(url);
        return m.find() ? Ui.banderaEmoji(m.group(1).toUpperCase(java.util.Locale.ROOT)) : "";
    }

/** v3.2.48 (repo 82105f5): la bandera del pais del topic, o "" si no es un pais.
     *  "live_tv_peru" -> la bandera emoji. La tira de categorias la pinta como icono del
     *  tile, como hace Drama con sus canales por bandera. */
    public static String banderaDeTopic(String topicoCrudo) {
        String[] p = pais(topicoCrudo);
        return p == null ? "" : p[1];
    }

    /** "live_tv_peru" → "🇵🇪 Perú"; "Deportes" → "Deportes". Puro y sin sorpresas. */
    public static String etiquetaTopic(String topicoCrudo) {
        String[] p = pais(topicoCrudo);
        if (p != null) return p[1] + " " + p[2];
        if (topicoCrudo == null) return "";
        String t = crudeza(topicoCrudo)
                .replaceFirst("^live tv ", "").replaceFirst("^live ", "")
                .replaceFirst("^tv ", "").trim();
        // v3.2.52 (captura del usuario): el feed trae los canales sin grupo bajo
        // "unsorted"; Drama los muestra como "Sin clasificar", no con la palabra cruda.
        if (t.equals("unsorted")) return "Sin clasificar";
        if (t.equals("dramalive")) return "En vivo";   // v3.2.62: rejillas viejas, sin marca ajena
        if (t.isEmpty()) return topicoCrudo.trim();
        return Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    /** v3.2.64 (pedido del usuario): topicos que no se muestran nunca. */
    public static boolean topicBloqueado(String crudo) {
        if (crudo == null) return false;
        String t = crudeza(crudo);
        if (t.contains("twitch") || t.contains("israel") || t.equals("972") || t.equals("il"))
            return true;
        return etiquetaTopic(crudo).contains("Israel");
    }

    private static String[] pais(String raw) {
        if (raw == null) return null;
        // v3.2.51: los paises que llegan como codigo telefonico se resuelven aca; la
        // bandera se arma desde el ISO (Ui.banderaEmoji), el nombre sale de la tabla.
        String marca = marcaDe(raw);
        if (marca != null) {
            for (String[] p : MARCADORES) {
                if (p[0].equals(marca)) return new String[]{marca, Ui.banderaEmoji(p[1]), p[2]};
            }
        }
        String t = " " + crudeza(raw) + " ";
        for (String[] p : PAISES) {
            String k = p[0];
            // Las palabras cortas ("usa") solo valen como palabra suelta; a las demás
            // les basta estar dentro del rótulo ("Live_TV_Argentina", "Perú — 34 canales").
            if (k.length() <= 3 ? t.contains(" " + k + " ") : t.contains(k)) return p;
        }
        return null;
    }

    /** minúsculas, sin acentos, guiones bajos ni decoración: la lengua que entiende PAISES. */
    private static String crudeza(String raw) {
        String t = Normalizer.normalize(raw.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", " ");
        return t.replace('_', ' ').replaceAll("[^a-z0-9 ]+", " ")
                .replaceAll("\\s+", " ").trim();
    }
}
