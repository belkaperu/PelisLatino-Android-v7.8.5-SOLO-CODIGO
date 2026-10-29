package dza.fut.paipo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Extraccion de canales de DramaLive DESDE LA APP, con la sesion que el usuario
 * exporta de su navegador (`DramaExport.plantillas()` → este JSON).
 *
 * Espejo 1:1 del alambre medido (y verificado contra el panel real el
 * 2026-10-08, rama tools/drama-live.py `extraer`):
 *   POST {base}glt   body = b64(AES-128-CBC(json compacto)) + ':' + b64(iv16)
 *   clave literal del bundle main.dart.js: "43fc9b1a3f854049" (es publica: esta
 *   en el JS que descarga cualquier visitante; no hay nada que esconder aqui)
 *   respuesta = el mismo alambre; el claro es {live_topics:[...]}
 *
 * Sin la plantilla de sesion (user_id, device_id, ...) el panel responde su
 * catch-all de 32 bytes ("\t\t\r\n" + espacios) — NO es un falloourlo: es la
 * SEÑAL medida de sesion anonima/caducada, y este parser la reporta como tal.
 *
 * Las funciones puras (errorPlantilla/alambre/desalambre/canales/aM3u) no tocan
 * red ni Android y las cubre DramaSesionTest; solo extraer() hace HTTP.
 */
public final class DramaSesion {

    private DramaSesion() {}

    public static final String BASE_DEFECTO = "https://main.fastconnections.online/api/live/livedrama/v13.0.0/";
    public static final String UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36";
    /** Clave AES-128 del catalogo, literal en main.dart.js (4 ocurrencias). */
    static final byte[] KEY = "43fc9b1a3f854049".getBytes(StandardCharsets.US_ASCII);

    // Listas de campos, en orden de preferencia — identicas a tools/drama-extraer.py
    static final String[] NOMBRE = {"name", "title", "channel_name", "cname", "tvg_name", "label", "channel"};
    static final String[] URLK   = {"url", "link", "href", "stream_url"};
    static final String[] GRUPO  = {"id_topic", "category", "group", "group_title", "live_category", "type"};
    static final String[] LOGO   = {"img_url", "logo", "tvg_logo", "image", "thumb"};

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /** Un canal extraido (nombre, url jugable, grupo, logo). */
    public static final class CanalDrama {
        @NonNull public String name = "";
        @NonNull public String url = "";
        @NonNull public String group = "En vivo";
        @Nullable public String logo;
        /** v3.2.21: id_live de la fila. Sobrevive en tvg-id del EXTINF cuando la url ya es https curado. */
        @Nullable public String dramaId;
    }

    /** Resultado de una extraccion: o m3u con N canales, o un error explicativo. */
    public static final class Resultado {
        public final int canales;
        @Nullable public final String m3u;
        @Nullable public final String error;

        private Resultado(int canales, @Nullable String m3u, @Nullable String error) {
            this.canales = canales;
            this.m3u = m3u;
            this.error = error;
        }

        public boolean ok() { return error == null; }

        @NonNull public static Resultado exito(int n, @NonNull String m3u) { return new Resultado(n, m3u, null); }
        @NonNull public static Resultado fallo(@NonNull String q) { return new Resultado(0, null, q); }
    }

    // ---------------------------------------------------------------- plantilla

    /**
     * null si el JSON pegado trae una plantilla utilizable (con user_id);
     * si no, una frase que dice exactamente que falta (para el estado del sheet).
     */
    @Nullable
    public static String errorPlantilla(@Nullable String json) {
        JsonObject raiz = objeto(json);
        if (raiz == null) return "no es JSON";
        JsonObject cont = objetoHijo(raiz, "plantillas");
        if (cont == null) cont = objetoHijo(raiz, "plant");
        if (cont == null) cont = raiz;
        JsonObject glt = objetoHijo(cont, "glt");
        if (glt == null) return "falta plantillas.glt — exporta con DramaExport.plantillas() tras navegar Live";
        if (!hayCampo(glt, "user_id"))
            return "la plantilla no trae user_id (sesion anonima = catch-all del panel); reexporta logueado";
        return null;
    }

    /**
     * Que le digas AL USUARIO cuando lo que pego NO parece una sesion. El panel
     * de DramaLive tiene tres entradas que la gente confunde (codigo FG del
     * portal, enlace player.html de UN canal, lista M3U); cada una va en otro
     * sitio y esta app lo explica en vez de quedarse muda. null = parece
     * sesion, deja decidir a {@link #errorPlantilla}.
     */
    @Nullable
    public static String diagnosticoEntrada(@Nullable String texto) {
        if (texto == null) return null;
        String t = texto.trim();
        if (t.isEmpty()) return null;
        if (t.matches("(?s)\\d{3,12}")) return "FG";
        String l = t.toLowerCase();
        if (!t.contains("\n") && (l.startsWith("http://") || l.startsWith("https://"))) return "ENLACE";
        if (l.startsWith("#extm3u") || t.contains("#EXTINF") || t.contains("#EXT-X-")) return "M3U";
        if (l.startsWith("[") || (l.startsWith("{") && !t.contains("plantillas")
                && !t.contains("glt") && !t.contains("user_id") && !t.contains("session")))
            return "JSONOTRO";
        return null;
    }

    /** base de la API (plantillas.base o el default medido). */
    @NonNull
    public static String baseDe(@Nullable String json) {
        JsonObject raiz = objeto(json);
        String b = raiz == null ? null : textoDe(raiz, "base");
        if (b == null || b.isEmpty()) b = BASE_DEFECTO;
        if (!b.endsWith("/")) b = b + "/";
        return b;
    }

    /**
     * URL EXACTA del glt que aprendio el captador WebView (medido: la sesion real
     * habla con li.highperformanceconnect.shop, no con main.fastconnections). Si la
     * sesion la trae, extraer() POSTea ahi; si no, baseDe()+"glt" (compatibilidad).
     */
    @Nullable
    public static String urlDe(@Nullable String json) {
        JsonObject raiz = objeto(json);
        String u = raiz == null ? null : textoDe(raiz, "url");
        return (u == null || !u.startsWith("http")) ? null : u;
    }

    /** alambre que la sesion aprendio ("ctiv"/"ivpf"/"iv0"); null = default del bundle. */
    @NonNull
    public static String wireDe(@Nullable String json) {
        JsonObject raiz = objeto(json);
        String w = raiz == null ? null : textoDe(raiz, "wire");
        if (w == null) return "ctiv";
        if (w.endsWith("ivpf") || w.contains("ivpf")) return "ivpf";
        if (w.endsWith("iv0") || w.contains("iv0")) return "iv0";
        // nombres que emite el captador v3.2.1 ("p0+frente", "p16+frente", "p0+cero"...)
        if (w.contains("frente") || w.contains("front")) return "ivpf";
        if (w.contains("cero") || w.contains("zero")) return "iv0";
        if (w.contains("ctiv") || w.contains("plano")) return "ctiv";
        return "ctiv";
    }

    @NonNull
    public static List<String> tiposDe(@Nullable String json, @Nullable String csv) {
        List<String> out = new ArrayList<>();
        String fuente = csv;
        if (fuente == null || fuente.trim().isEmpty()) {
            JsonObject raiz = objeto(json);
            if (raiz != null) fuente = textoDe(raiz, "tipos");
        }
        if (fuente != null && !fuente.trim().isEmpty()) {
            for (String t : fuente.split(",")) if (!t.trim().isEmpty()) out.add(t.trim());
        }
        if (out.isEmpty()) {
            JsonObject glt = gltDe(json);
            String t = glt == null ? null : textoDe(glt, "type");
            out.add(t != null && !t.isEmpty() ? t : "live");
        }
        return out;
    }

    @Nullable
    static JsonObject gltDe(@Nullable String json) {
        JsonObject raiz = objeto(json);
        if (raiz == null) return null;
        JsonObject cont = objetoHijo(raiz, "plantillas");
        if (cont == null) cont = objetoHijo(raiz, "plant");
        if (cont == null) cont = raiz;
        return objetoHijo(cont, "glt");
    }

    /** Payload del glt para un tipo: copia de la plantilla con `type` fijado. */
    @NonNull
    public static JsonObject payloadPara(@NonNull JsonObject glt, @NonNull String tipo) {
        JsonObject p = glt.deepCopy();
        p.addProperty("type", tipo);
        return p;
    }

    // ----------------------------------------------------------------- alambre

    /** b64(ct) + ':' + b64(iv) con AES-128-CBC/PKCS5 e IV aleatorio (identico al bundle). */
    @NonNull
    public static String alambre(@NonNull JsonObject payload) throws Exception {
        return alambre(payload, "ctiv");
    }

    /**
     * Mismo payload, tres alambres medidos/hipoteticos del panel:
     *  "ctiv" = b64(ct)+':'+b64(iv)   (formato que medi del bundle Android)
     *  "ivpf" = b64(iv‖ct), iv random  (iv al frente del blob)
     *  "iv0"  = b64(ct), iv = 16 ceros (firmeza medida en campo 2026-10-09: las
     *           respuestas de 4 hosts distintos arrancan con el MISMO bloque ->
     *           CBC con IV fijo; el captador WebView aprende cual usa tu sesion)
     */
    @NonNull
    public static String alambre(@NonNull JsonObject payload, @NonNull String wire) throws Exception {
        byte[] iv = new byte[16];
        if (!"iv0".equals(wire)) new SecureRandom().nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY, "AES"), new IvParameterSpec(iv));
        byte[] ct = c.doFinal(GSON.toJson(payload).getBytes(StandardCharsets.UTF_8));
        Base64.Encoder b64 = Base64.getEncoder();
        if ("ivpf".equals(wire)) {
            byte[] both = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, both, 0, iv.length);
            System.arraycopy(ct, 0, both, iv.length, ct.length);
            return b64.encodeToString(both);
        }
        if ("iv0".equals(wire)) return b64.encodeToString(ct);
        return b64.encodeToString(ct) + ":" + b64.encodeToString(iv);
    }

    /**
     * Descifra una respuesta probando los tres formatos (y aceptando el envoltorio
     * `"<b64>"` de string JSON que midio el captador en las respuestas web).
     * null si no es ninguno (p. ej. el catch-all de 32 B).
     */
    @Nullable
    public static String desalambre(@Nullable String cuerpo) {
        String t = cuerpo == null ? "" : cuerpo.trim();
        if (t.isEmpty()) return null;
        if (t.length() > 1 && t.charAt(0) == '"' && t.charAt(t.length() - 1) == '"') {
            try { t = GSON.fromJson(t, String.class).trim(); } catch (Exception ignored) { }
        }
        Base64.Decoder d = Base64.getDecoder();
        int i = t.lastIndexOf(':');
        if (i > 16) { // ctiv
            try {
                byte[] ct = d.decode(alinearB64(t.substring(0, i)));
                byte[] iv = d.decode(alinearB64(t.substring(i + 1)));
                if (iv.length == 16 && ct.length > 0 && ct.length % 16 == 0) {
                    Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
                    c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(KEY, "AES"), new IvParameterSpec(iv));
                    String s = new String(c.doFinal(ct), StandardCharsets.UTF_8);
                    if (empiezaJson(s)) return s;
                }
            } catch (Exception ignored) { }
        }
        // v3.2.1: el wire MEDIDO de la sesion web es b64 SIN padding (len%4==1) y
        // con posible prefijo de 16 antes del iv; se prueban 4 disposiciones
        // descifrando bloque a bloque (NoPadding) y aceptando solo texto que
        // arranca en '{' tras recortar basura de borde.
        byte[] raw;
        try { raw = d.decode(alinearB64(t.replaceAll("\\s+", ""))); } catch (Exception e) { return null; }
        if (raw.length < 32) return null;
        String s;
        s = cbcBloques(raw, 0, 16, raw.length);                       // ivpf (iv al frente)
        if (s != null) return s;
        if (raw.length >= 64) { s = cbcBloques(raw, 16, 32, raw.length); if (s != null) return s; } // p16+ivpf
        s = cbcBloques(raw, -1, 0, raw.length);                        // iv0 (iv = ceros)
        if (s != null) return s;
        if (raw.length >= 48) { s = cbcBloques(raw, -1, 16, raw.length); if (s != null) return s; }  // p16+iv0
        return null;
    }

    /** Normaliza b64 tolerante: url-safe, sin padding (medido len%4==1), quita ruido. */
    static String alinearB64(String s) {
        String b = s.replaceAll("[^A-Za-z0-9+/_=-]", "").replace('-', '+').replace('_', '/');
        if (b.length() % 4 == 1) b = b.substring(0, b.length() - 1);
        while (b.length() % 4 != 0) b += "=";
        return b;
    }

    /**
     * CBC a mano, bloque completo a bloque desde `desde` (iv en raw[ivOff..ivOff+16];
     * ivOff&lt;0 = ceros). No exige ct multiplo de 16 ni padding valido: ignora la
     * cola suelta, recorta basura y solo devuelve texto que arranca con '{'.
     */
    @Nullable
    static String cbcBloques(byte[] raw, int ivOff, int desde, int hasta) {
        try {
            Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
            byte[] iv = new byte[16];
            if (ivOff >= 0) System.arraycopy(raw, ivOff, iv, 0, 16);
            SecretKeySpec k = new SecretKeySpec(KEY, "AES");
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] prev = iv;
            int n = 0;
            for (int p = desde; p + 16 <= hasta; p += 16) {
                c.init(Cipher.DECRYPT_MODE, k, new IvParameterSpec(prev));
                byte[] dec = c.doFinal(raw, p, 16);
                out.write(dec, 0, 16);
                prev = java.util.Arrays.copyOfRange(raw, p, p + 16);
                n++;
            }
            if (n < 1) return null;
            return recortarClaro(out.toByteArray());
        } catch (Exception e) { return null; }
    }

    /**
     * Deja solo el JSON con rescate balanceado (espejo del jsonDesde del JS): escanea
     * los primeros 1200 chars buscando '{' o '[', cierra el balance (respetando
     * strings) y devuelve el candidato MAYOR que parsee. Tapa el caso medido del
     * wire real: IV fijo no enviado => el primer bloque (con el '{' exterior) llega
     * ilegible, pero los arrays internos de 30-43 KB vienen intactos desde el bloque 1.
     */
    @Nullable
    static String recortarClaro(byte[] claro) {
        String s = new String(claro, StandardCharsets.UTF_8);
        String mejor = null;
        int mejorLen = 0;
        int probados = 0;
        for (int k = 0; k < s.length() && k < 1200 && probados < 14; k++) {
            char c = s.charAt(k);
            if (c != '{' && c != '[') continue;
            probados++;
            String resto = s.substring(k);
            try {
                Object o = JsonParser.parseString(resto);
                if (o != null && resto.length() > mejorLen) { mejor = resto; mejorLen = resto.length(); }
            } catch (Exception ignored) { }
            int fin = cerrarBalance(s, k);
            if (fin > k + 7) {
                String sub = s.substring(k, fin + 1);
                try {
                    Object o = JsonParser.parseString(sub);
                    if (o != null && sub.length() > mejorLen) { mejor = sub; mejorLen = sub.length(); }
                } catch (Exception ignored) { }
            }
        }
        return mejor;
    }

    /** Índice del cierre balanceado de s[i] ('{'→'}', '['→']'), -1 si no cierra. */
    static int cerrarBalance(String s, int i) {
        char abre = s.charAt(i), cierra = abre == '{' ? '}' : ']';
        int prof = 0;
        boolean enStr = false, esc = false;
        for (int k = i; k < s.length(); k++) {
            char c = s.charAt(k);
            if (esc) { esc = false; continue; }
            if (c == '\\') { if (enStr) esc = true; continue; }
            if (c == '"') { enStr = !enStr; continue; }
            if (enStr) continue;
            if (c == abre) prof++;
            else if (c == cierra) { prof--; if (prof == 0) return k; }
        }
        return -1;
    }

    private static boolean empiezaJson(@Nullable String s) {
        if (s == null) return false;
        s = s.trim();
        return !s.isEmpty() && (s.charAt(0) == '{' || s.charAt(0) == '[');
    }

    /** La senal del panel sin sesion: 48 bytes o menos de solo whitespace (medido: 32). */
    public static boolean esCatchAll(@Nullable byte[] cuerpo) {
        if (cuerpo == null || cuerpo.length == 0 || cuerpo.length > 48) return false;
        for (byte b : cuerpo) {
            if (b != ' ' && b != '\t' && b != '\r' && b != '\n') return false;
        }
        return true;
    }

    /** Cuerpo HTTP -> lista de canales o error con la explicacion medida. */
    @NonNull
    public static Resultado procesarRespuesta(int status, @Nullable byte[] cuerpo) {
        // medido 2026-10-09 con la sesion real (volcar v3.1): el panel web contesta
        // HTTP 201 Created a los POST de datos — no solo 200.
        if (status != 200 && status != 201 && status != 204) return Resultado.fallo("HTTP " + status);
        if (status == 204) return Resultado.fallo("HTTP 204 vacio");
        if (esCatchAll(cuerpo))
            return Resultado.fallo("catch-all del panel: sesion caducada, plantilla incompleta o red distinta (el telefono y el navegador deben salir por la misma IP: usa el mismo WiFi). Re-exporta y prueba al momento");
        String claro = desalambre(cuerpo == null ? null : new String(cuerpo, StandardCharsets.ISO_8859_1));
        if (claro == null) return Resultado.fallo("respuesta cifrada no reconocida");
        List<CanalDrama> cs = canales(claro);
        if (cs.isEmpty()) return Resultado.fallo("respuesta ok pero 0 canales con forma utilizable");
        return Resultado.exito(cs.size(), aM3u(cs));
    }

    // ----------------------------------------------------------------- canales

    /**
     * Recorre el JSON en cualquier forma (BFS, como tools/drama-extraer.py) y emite
     * (nombre,url) deduplicados: directa por `url`, o compuesta `https://{server}/{path}/index.html`.
     */
    @NonNull
    public static List<CanalDrama> canales(@NonNull String claroJson) {
        JsonElement raiz;
        try {
            raiz = JsonParser.parseString(claroJson);
        } catch (Exception e) {
            return new ArrayList<>();
        }
        List<CanalDrama> salida = new ArrayList<>();
        Set<String> vistos = new HashSet<>();
        Deque<JsonElement> pila = new ArrayDeque<>();
        pila.add(raiz);
        while (!pila.isEmpty()) {
            JsonElement x = pila.poll();
            if (x == null || !x.isJsonObject()) {
                if (x != null && x.isJsonArray()) {
                    for (JsonElement e : x.getAsJsonArray()) pila.add(e);
                }
                continue;
            }
            JsonObject o = x.getAsJsonObject();
            String nombre = primero(o, NOMBRE);
            String idLive = textoDe(o, "id_live");
            if (nombre != null && idLive != null) {
                // MEDIDO 2026-10-10 contra el servidor real (replay con la sesion del
                // usuario): los items del glbt traen url:"1" (numerica) y el stream se
                // resuelve contra re…/redirect/glbr con {id,url,agent}. Guardamos el
                // esquema dramalive:// y el reproductor resuelve al pulsar (url fresca).
                String cu = textoDe(o, "url"); if (cu == null) cu = "1";
                String ag = textoDe(o, "agent"); if (ag == null || ag.trim().isEmpty()) ag = "all_streams_redirect";
                String tp = textoDe(o, "id_topic");
                String u2 = "dramalive://play?id_live=" + pct(idLive) + "&u=" + pct(cu)
                        + "&a=" + pct(ag) + (tp == null ? "" : "&t=" + pct(tp));
                if (vistos.add(nombre + "\u0000" + u2)) {
                    CanalDrama c = new CanalDrama();
                    c.name = nombre; c.url = u2;
                    c.group = tp != null ? tp : primero(o, GRUPO);
                    if (c.group == null || c.group.trim().isEmpty()) c.group = "En vivo";
                    c.logo = primero(o, LOGO);
                    salida.add(c);
                }
                continue;
            }
            String url = primero(o, URLK);
            if (nombre != null && url != null) {
                if (vistos.add(nombre + "\u0000" + url)) {
                    CanalDrama c = new CanalDrama();
                    c.name = nombre; c.url = url;
                    String g = primero(o, GRUPO); if (g != null) c.group = g;
                    c.logo = primero(o, LOGO);
                    salida.add(c);
                }
            } else {
                String servidor = primero(o, new String[]{"server", "server_url"});
                String ruta = textoDe(o, "path");
                if (ruta == null) ruta = textoDe(o, "stream_path");
                if (nombre != null && servidor != null) {
                    String s = servidor.trim().replaceFirst("^https?://", "");
                    while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
                    String p = (ruta == null ? "" : ruta.trim()).replaceAll("^/+", "").replaceAll("/+$", "");
                    if (!p.matches("(?s).*index\\.(html|m3u8)$")) p = (p.isEmpty() ? "" : p + "/") + "index.html";
                    String completa = "https://" + s + "/" + p;
                    if (vistos.add(nombre + "\u0000" + completa)) {
                        CanalDrama c = new CanalDrama();
                        c.name = nombre; c.url = completa;
                        String g = primero(o, GRUPO); if (g != null) c.group = g;
                        c.logo = primero(o, LOGO);
                        salida.add(c);
                    }
                }
            }
            for (java.util.Map.Entry<String, JsonElement> e : o.entrySet()) pila.add(e.getValue());
        }
        return salida;
    }

    /** M3U pegable tal cual genera tools/drama-extraer.py a_m3u. */
    @NonNull
    /** true si el csv SOLO contiene los tipos inventados del listado viejo (live,sports,
     * kids,news,movies,series): el servidor real responde result:1 "no data" a todos —
     * tratarlos como auto (glt tal cual -> topicos -> glbt) es siempre mejor. MEDIDO
     * 2026-10-10 con la sesion real del usuario contra el servidor del sitio. */
    public static boolean esCsvLegado(@Nullable String csv) {
        if (csv == null) return true;
        String t = csv.toLowerCase().replace(" ", "");
        if (t.isEmpty()) return true;
        for (String k : t.split(",")) {
            if (!k.equals("live") && !k.equals("sports") && !k.equals("kids")
                    && !k.equals("news") && !k.equals("movies") && !k.equals("series")) return false;
        }
        return true;
    }

    /** v3.2.24: CONEXION AUTOMATICA a drama. main.dart.js (medido) guarda el setup del
     *  wizard en localStorage via shared_preferences: «flutter.terms_continue» (bool del
     *  accept) y «flutter.source_config» (SourceModel doble-json: type fgCode + el codigo).
     *  Sembrando esas dos claves antes del arranque, web.dramalive.cc salta el wizard e
     *  inicia con la fuente puesta. Si el usuario configuro otro tipo (m3u/xtream) en ESTE
     *  WebView, no se pisa. Devuelve "" si no hay codigo que sembrar. */
    @NonNull
    public static String seedSetupJs(@Nullable String codigoFg) {
        if (codigoFg == null) return "";
        String c = codigoFg.trim().replaceAll("[^A-Za-z0-9._/:@-]", "");
        if (c.isEmpty()) return "";
        return "(function(){try{var L=window.localStorage;if(!L)return;"
                + "var want=JSON.stringify(JSON.stringify({type:'fgCode',fgCode:'" + c
                + "',m3uUrl:'',xtreamUsername:'',xtreamPassword:'',xtreamUrl:'',xtreamAgent:'',"
                + "finalUrlLink:''}));"
                + "var cur=L.getItem('flutter.source_config');"
                + "var d=null;try{d=JSON.parse(cur);if(typeof d==='string')d=JSON.parse(d);}catch(e){}"
                + "var t2=L.getItem('flutter.terms_continue');if(t2!=='true')L.setItem('flutter.terms_continue','true');"
                + "if(d&&d.type==='fgCode'&&d.fgCode==='" + c + "'){window.__BLAB_SEED_FG='" + c + "';return;}"
                + "if(d&&d.type&&d.type!=='fgCode')return;"
                + "L.setItem('flutter.source_config',want);window.__BLAB_SEED_FG='" + c + "';"
                + "try{if(!sessionStorage.getItem('blab_sr')){sessionStorage.setItem('blab_sr','1');"
                + "setTimeout(function(){location.reload();},80);}}catch(e4){}}catch(ex){}})();";
    }

    /** Semilla para el WebView: reemplaza el codigo de inicializacion del JS con los
     * datos de la sesion guardada, para que auto() funcione desde el segundo 0 sin
     * esperar (ni necesitar) el trafico glt del sitio. null si no hay sesion util. */
    @Nullable
    public static String sesionBootstrap(@Nullable String sesionJson) {
        if (sesionJson == null || sesionJson.trim().isEmpty()) return null;
        try {
            JsonElement r = JsonParser.parseString(sesionJson);
            if (!r.isJsonObject()) return null;
            JsonObject ses = r.getAsJsonObject();
            JsonObject glt = gltDe(sesionJson);
            // v3.2.26 (log 00:36: cosecha muda): un glt pelado mataba la semilla ENTERA y con
            // ella la cosecha. Ahora la semilla tambien vale solo con glbrReq (la cosecha no
            // necesita user_id); auto() ya se autocensura si no hay glt util.
            JsonObject plant = new JsonObject();
            JsonObject pl = ses.has("plantillas") && ses.getAsJsonObject("plantillas").isJsonObject()
                    ? ses.getAsJsonObject("plantillas") : null;
            if (glt != null && glt.get("user_id") != null) {
                plant.add("glt", glt);
            } else if (pl == null || !pl.has("glbrReq")
                    || !pl.getAsJsonObject("glbrReq").isJsonObject()) {
                return null;
            }
            if (pl != null && pl.has("glbt") && pl.getAsJsonObject("glbt") != null)
                plant.add("glbt", pl.getAsJsonObject("glbt"));
            // v3.2.13: sembrar TAMBIEN la plantilla glbr del sitio: el gancho arranca con
            // A.plant.glbrReq cargada y la COSECHA se arma sin que nadie juegue nada en la
            // pagina (modo headless lanzado desde el player ante un sueno).
            if (pl != null && pl.has("glbrReq") && pl.getAsJsonObject("glbrReq") != null)
                plant.add("glbrReq", pl.getAsJsonObject("glbrReq"));
            JsonObject o = new JsonObject();
            o.add("plant", plant);
            String w = wireDe(sesionJson);
            if (w != null) o.addProperty("wire", w);
            o.addProperty("urlGlt", urlGltDe(sesionJson));
            String ub = texto(ses, "urlGlbt"); if (ub != null) o.addProperty("urlGlbt", ub);
            String ur = texto(ses, "urlGlbr"); if (ur != null) o.addProperty("urlGlbr", ur);
            if (ses.has("topics") && ses.get("topics").isJsonArray())
                o.add("topics", ses.get("topics"));
            return "window.__DRAMA_SESION=" + GSON.toJson(o) + ";";
        } catch (Exception e) { return null; }
    }

    private static boolean topicoVacioYCanalVacio(@NonNull String claro) {
        return topicosDe(claro, 1).isEmpty() && canales(claro).isEmpty();
    }

    /** result/message legibles de una respuesta glt ya descifrada (para el estado del usuario). */
    @NonNull
    public static String resumenGlt(@NonNull String claroJson) {
        try {
            JsonElement r = JsonParser.parseString(claroJson);
            if (!r.isJsonObject()) return "claro no-objeto";
            JsonObject o = r.getAsJsonObject();
            int res = optInt(o, "result", -1);
            String msg = "";
            if (o.has("message") && o.get("message").isJsonObject())
                msg = String.valueOf(optStr(o.getAsJsonObject("message"), "en"));
            return "result:" + res + (msg.isEmpty() || "null".equals(msg) ? "" : " " + msg);
        } catch (Exception e) { return "aclarar fallo " + e.getClass().getSimpleName(); }
    }

    private static String optStr(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    /** Cookies del WebView para esa url (el app no tiene jar propio; el WebView del
     * captador navega web.dramalive.cc y colecciona las suyas). null fuera de Android. */
    @Nullable
    static String galletasPara(@NonNull String url) {
        try {
            String c = android.webkit.CookieManager.getInstance().getCookie(url);
            return c == null || c.isEmpty() ? null : c;
        } catch (Throwable t) { return null; }
    }

    /** Devuelve al WebView las Set-Cookie que nos manden (p.ej. la tarta anti-sueno). */
    static void asientaGalletas(@NonNull String url, @NonNull HttpURLConnection con) {
        try {
            java.util.List<String> scs = con.getHeaderFields().get("Set-Cookie");
            if (scs == null) return;
            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            for (String sc : scs) if (sc != null) cm.setCookie(url, sc);
            cm.flush();
        } catch (Throwable ignored) { }
    }

    static String safeStr(JsonElement e) {
        try { return e.getAsString(); } catch (Exception ex) { return null; }
    }

    static String pct(String v) {
        try { return java.net.URLEncoder.encode(v, "UTF-8"); } catch (Exception e) { return v; }
    }

    static String unpct(String v) {
        try { return java.net.URLDecoder.decode(v, "UTF-8"); } catch (Exception e) { return v; }
    }

    /** @return {id_live, url, agent, topic?} o null si no es esquema dramalive://. */
    @Nullable
    public static String[] partesDramaLive(@Nullable String url) {
        if (url == null || !url.startsWith("dramalive://play?")) return null;
        String q = url.substring(url.indexOf('?') + 1);
        String id = null, u = null, ag = null, tp = null;
        for (String kv : q.split("&")) {
            int i = kv.indexOf('='); if (i < 0) continue;
            String k = kv.substring(0, i), v = unpct(kv.substring(i + 1));
            if (k.equals("id_live")) id = v; else if (k.equals("u")) u = v;
            else if (k.equals("a")) ag = v; else if (k.equals("t")) tp = v;
        }
        if (id == null || id.isEmpty()) return null;
        return new String[]{id, u == null ? "1" : u, ag == null || ag.isEmpty() ? "all_streams_redirect" : ag, tp == null ? "" : tp};
    }

    /** URL del glt SANEADA: la entrega pudo aprender el endpoint de telemetria «la»
     * (HTTP 500 para payloads de catalogo — medido); se rechaza cualquier url cuyo path
     * no acabe en /glt, se deriva de urlGlbt, y si no, la base del bundle. */
    @NonNull
    public static String urlGltDe(@Nullable String sesionJson) {
        String u = urlDe(sesionJson);
        if (u != null && u.matches("(?s).*/glt(\\?.*)?$")) return u;
        String ub = urlGlbtDe(sesionJson);
        if (ub != null && ub.matches("(?s).*/glbt(\\?.*)?$")) return ub.replace("/glbt", "/glt");
        return baseDe(sesionJson) + "glt";
    }

    /** Base del glbr: la request COMPLETA que el sitio mando (plantillas.glbrReq,
     * aprendida en el captador v3.2.9+); si no existe, la plantilla glt. Asi viajan
     * los campos extra del sitio (token de ads, flags…) que abren el stream real. */
    @NonNull
    public static JsonObject payloadGlbr(@Nullable String sesionJson, @NonNull JsonObject glt,
                                         @NonNull String id, @NonNull String url, @NonNull String agent) {
        JsonObject base = glt;
        try {
            JsonElement r = JsonParser.parseString(sesionJson == null ? "" : sesionJson);
            if (r.isJsonObject() && r.getAsJsonObject().has("plantillas")) {
                JsonElement pl = r.getAsJsonObject().get("plantillas");
                if (pl.isJsonObject() && pl.getAsJsonObject().has("glbrReq")
                        && pl.getAsJsonObject().get("glbrReq").isJsonObject())
                    base = pl.getAsJsonObject().get("glbrReq").getAsJsonObject();
            }
        } catch (Exception ignore) { }
        JsonObject q = base.deepCopy();
        q.addProperty("id", id); q.addProperty("url", url); q.addProperty("agent", agent);
        return q;
    }

    /** URL del redirector glbr: la aprendida por el captador, si no la medida fija. */
    @NonNull
    public static String urlGlbrDe(@Nullable String sesionJson) {
        try {
            JsonElement r = JsonParser.parseString(sesionJson == null ? "" : sesionJson);
            if (r.isJsonObject()) {
                String u = r.getAsJsonObject().has("urlGlbr") ? texto(r.getAsJsonObject(), "urlGlbr") : null;
                if (u != null && u.startsWith("http")) return u;
            }
        } catch (Exception ignore) { }
        return "https://re.highperformanceconnect.shop/redirect/glbr";
    }

    @NonNull
    public static String urlGlbtDe(@Nullable String sesionJson) {
        try {
            JsonElement r = JsonParser.parseString(sesionJson == null ? "" : sesionJson);
            if (r.isJsonObject() && r.getAsJsonObject().has("urlGlbt")) {
                String u = texto(r.getAsJsonObject(), "urlGlbt");
                if (u != null && u.startsWith("http")) return u;
            }
        } catch (Exception ignore) { }
        String g = urlDe(sesionJson);
        if (g == null) g = baseDe(sesionJson) + "glt";
        return g.replaceFirst("glt(\\?|$)", "glbt$1");
    }

    /** glbt = plantilla glt + topic (formulas y campos MEDIDOS del main.dart.js y del replay real). */
    @NonNull
    public static JsonObject payloadGlbt(@NonNull JsonObject glt, @NonNull String topic) {
        JsonObject q = glt.deepCopy();
        q.addProperty("topic", topic);
        return q;
    }

    /** ids de live_topics[] de una respuesta glt leible (listas vacias: llegan por glbt). */
    @NonNull
    /**
     * Tópicos del índice {@code live_topics[]} del glt.
     *
     * @param maximo tope; {@code <= 0} = TODOS. Medido contra el servidor real: el
     *        índice trae <b>265</b> entradas (27 categorías + 238 países). Pedir
     *        menos dejaba fuera países enteros y categorías como bein_sport.
     */
    public static List<String> topicosDe(@NonNull String claroGltJson, int maximo) {
        List<String> out = new ArrayList<>();
        try {
            JsonElement r = JsonParser.parseString(claroGltJson);
            if (!r.isJsonObject()) return out;
            JsonElement lt = r.getAsJsonObject().get("live_topics");
            if (lt == null || !lt.isJsonArray()) return out;
            for (JsonElement e : lt.getAsJsonArray()) {
                if (!e.isJsonObject()) continue;
                String id = texto(e.getAsJsonObject(), "id_topic");
                if (id != null && !id.isEmpty() && !out.contains(id)) out.add(id);
                if (maximo > 0 && out.size() >= maximo) break;
            }
        } catch (Exception ignore) { }
        return out;
    }

    /**
     * v3.2.42: {id_topic → rótulo humano} del índice live_topics[] del glt. Campos leídos
     * en orden (topic_name, name, title, label). Si el sitio no trae nombres, el mapa queda
     * vacío y manda el id como siempre — FusionRejilla humaniza el rótulo en pantalla.
     */
    @NonNull
    public static Map<String, String> nombresTopicosDe(@NonNull String claroGltJson) {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            JsonElement r = JsonParser.parseString(claroGltJson);
            if (!r.isJsonObject()) return out;
            JsonElement lt = r.getAsJsonObject().get("live_topics");
            if (lt == null || !lt.isJsonArray()) return out;
            for (JsonElement e : lt.getAsJsonArray()) {
                if (!e.isJsonObject()) continue;
                JsonObject o = e.getAsJsonObject();
                String id = texto(o, "id_topic");
                String nom = primero(o, new String[]{"topic_name", "name_topic", "name", "title", "label"});
                if (id != null && !id.isEmpty() && nom != null && !nom.trim().isEmpty()
                        && !out.containsKey(id)) out.put(id, nom.trim());
            }
        } catch (Exception ignore) { }
        return out;
    }

    /**
     * v3.2.54: {id_topic -> img_url_topic} Y {name_topic -> img_url_topic} del indice
     * live_topics[] del glt — MEDIDO en la respuesta real (case/resp_glt_live.json):
     *   {"id_topic":"pe","name_topic":"Peru","img_url_topic":
     *    "http://logo.twoapistack.work/img/topics/ic_flag_pe.png"}
     * Es la bandera que pinta Drama en su tira y en su panel: la usamos como imagen de red
     * y el emoji de la tabla local queda solo como fallback (fuente sin glt, sin red).
     */
    @NonNull
    public static java.util.Map<String, String> logosTopicosDe(@NonNull String claroGltJson) {
        java.util.Map<String, String> out = new LinkedHashMap<>();
        try {
            JsonElement r = JsonParser.parseString(claroGltJson);
            if (!r.isJsonObject()) return out;
            JsonElement lt = r.getAsJsonObject().get("live_topics");
            if (lt == null || !lt.isJsonArray()) return out;
            for (JsonElement e : lt.getAsJsonArray()) {
                if (!e.isJsonObject()) continue;
                JsonObject o = e.getAsJsonObject();
                String id = texto(o, "id_topic");
                String nom = primero(o, new String[]{"topic_name", "name_topic", "name", "title", "label"});
                String img = texto(o, "img_url_topic");
                if (img == null || !img.startsWith("http")) continue;
                if (id != null && !id.isEmpty() && !out.containsKey(id)) out.put(id, img);
                if (nom != null && !nom.trim().isEmpty() && !out.containsKey(nom.trim()))
                    out.put(nom.trim(), img);
            }
        } catch (Exception ignore) { }
        return out;
    }

    /** "id\u0001url" por linea — la forma en que el mapa viaja al prefs y a la UI. */
    @NonNull
    public static String serializarLogos(@NonNull java.util.Map<String, String> logos) {
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : logos.entrySet()) {
            if (e.getKey().indexOf('\n') < 0 && e.getKey().indexOf('\u0001') < 0)
                sb.append(e.getKey()).append('\u0001').append(e.getValue()).append('\n');
        }
        return sb.toString();
    }

    @NonNull
    public static java.util.Map<String, String> logosDesdeLineas(@Nullable String lineas) {
        java.util.Map<String, String> out = new LinkedHashMap<>();
        if (lineas == null || lineas.isEmpty()) return out;
        for (String l : lineas.split("\n")) {
            int i = l.indexOf('\u0001');
            if (i > 0 && !out.containsKey(l.substring(0, i))) out.put(l.substring(0, i), l.substring(i + 1));
        }
        return out;
    }

    /** Puente extraccion->UI: lo ultimo que dijo el glt (la captura headless corre en el
     *  mismo proceso; al reiniciar la app manda el prefs drama_seed/topic_logos). */
    public static volatile String ultimosLogosTopicos = null;
    /** v3.2.59: {id_topic -> nombre humano} de TODOS los topics del glt —
     *  el catalogo completo que la extraccion de tope-40 nunca piso. */
    public static volatile String ultimosNombresTopicos = null;

    /** @return {url, userAgent} o null si el cuerpo no es un glbr utilizable. */
    @Nullable
    public static String[] glbrDesdeRespuesta(int status, @Nullable byte[] cuerpo) {
        if (status < 200 || status >= 300 || cuerpo == null) return null;
        String claro = desalambre(new String(cuerpo, StandardCharsets.ISO_8859_1));
        if (claro == null) return null;
        try {
            JsonElement r = JsonParser.parseString(claro);
            if (!r.isJsonObject()) return null;
            if (optInt(r.getAsJsonObject(), "result", -1) != 0) return null;
            JsonElement d = r.getAsJsonObject().get("data");
            if (d == null || !d.isJsonObject()) return null;
            // FORMAS MEDIDAS del data.url del glbr (logcat usuario 2026-10-10 14:2x +
            // sandbox): (a) url limpia (sueno update.mp4), (b) string que CONTIENE json
            // {"url":…,"agent":…} (canales reales: fas6…/index.html), (c) objeto anidado.
            // v3.2.7 se tragaba (b) entera como url -> «exception null» al reproducir.
            String u = null; String ag = texto(d.getAsJsonObject(), "agent");
            JsonElement du = d.getAsJsonObject().get("url");
            if (du != null && du.isJsonObject()) {
                u = texto(du.getAsJsonObject(), "url");
                String a2 = texto(du.getAsJsonObject(), "agent"); if (a2 != null) ag = a2;
            } else {
                u = du == null || du.isJsonNull() ? null : safeStr(du);
            }
            if (u == null || u.trim().isEmpty()) return null;
            // v7.7: el desempaquetado del advanced vive en urlYAgenteDe(), compartido
            // con el camino del glasbi (donde estaba el bug del 93 %).
            String[] par = urlYAgenteDe(u, ag);
            if (par == null) return null;
            return new String[]{par[0], par[1]};
        } catch (Exception e) { return null; }
    }

    private static int optInt(JsonObject o, String k, int porDefecto) {
        JsonElement e = o.get(k);
        if (e == null || e.isJsonNull()) return porDefecto;
        try { return e.getAsInt(); } catch (Exception ex) { return porDefecto; }
    }

    /** Resuelve un canal dramalive:// contra el glbr del sitio (llamada de red; en hilo secundario). */
    @Nullable
    public static String[] resolverDramaLive(@NonNull String canalUrl, @NonNull String sesionJson) throws Exception {
        String[] pr = partesDramaLive(canalUrl);
        if (pr == null) return null;
        JsonObject glt = gltDe(sesionJson);
        if (glt == null) throw new IllegalStateException("el sitio no dejo una plantilla glt (abre el captador)");
        JsonObject q = payloadGlbr(sesionJson, glt, pr[0], pr[1], pr[2]);
        Respuesta rp = post(urlGlbrDe(sesionJson), alambre(q, wireDe(sesionJson)));
        String[] ra = glbrDesdeRespuesta(rp.status, rp.cuerpo);
        if (ra == null)
            throw new IllegalStateException("HTTP " + rp.status + " "
                    + (rp.cuerpo == null ? 0 : rp.cuerpo.length) + " B desde "
                    + urlGlbrDe(sesionJson) + " (respuesta sin stream utilizable)");
        return ra;
    }

    /** v3.2.31 — PLAY NATIVO RAPIDO + OPCIONES. Con la cadena ya documentada (glasbi
     *  {plantilla glbr con id} -> live.url '.LS.V2...' + agent 'redirect' -> glbr ->
     *  advanced {url:index.html, swap}), el tap de play se resuelve AQUI mismo en dos
     *  POSTs (2-4 s), sin esperar al WebView ni a la cosecha (~30 s). Del MISMO glasbi
     *  salen las OPCIONES del canal: la url principal + cada variante del 'backup'
     *  separada por ' -;- ' (se tiran los iframes '{...}'). Cualquier paso que huela mal
     *  devuelve null y el player cae al flujo de siempre (glbr pelado -> sueno -> cura). */
    public static final class Vivo {
        @NonNull public final String url;
        @NonNull public final String ua;
        /** JsonArray serializado [{n:'HD',u:'http://.LS...'}, ...] — puede venir vacio. */
        @NonNull public final String opciones;
        Vivo(@NonNull String u, @NonNull String a, @NonNull String o) { url = u; ua = a; opciones = o; }
    }

    /** Base del glasbi = base del glt con el endpoint cambiado (medido: glasbi vive en
     *  live_url, NO en redirect_url). Sin sesion, la constante del bundle. Puro. */
    @NonNull
    public static String urlGlasbiDe(@Nullable String sesionJson) {
        String g = urlGltDe(sesionJson);
        if (g != null && g.endsWith("glt")) return g.substring(0, g.length() - 3) + "glasbi";
        return "https://li.highperformanceconnect.shop/api/live/livedrama/v13.0.0/glasbi";
    }

    /** Opciones del bloque 'live' del glasbi (principal + backup), filtrando basura e
     *  iframes y deduplicando. El nombre sale de 'description_(X)_' como hace el dart.
     *  Puro y testeable. */
    @NonNull
    public static com.google.gson.JsonArray opcionesDeLive(@Nullable JsonObject live) {
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        if (live == null) return arr;
        java.util.List<String> cand = new java.util.ArrayList<>();
        String pri = texto(live, "url");
        if (pri != null) cand.add(pri.trim());
        String bk = texto(live, "backup");
        if (bk != null) for (String p : bk.split(" -;- ")) if (p != null) cand.add(p.trim());
        java.util.LinkedHashSet<String> vistos = new java.util.LinkedHashSet<>();
        for (String m : cand) {
            if (m.isEmpty() || m.charAt(0) == '{' || m.indexOf(".LS.") < 0) continue;
            if (!vistos.add(m)) continue;
            String nom = "OTRA";
            java.util.regex.Matcher mm = java.util.regex.Pattern
                    .compile("description_(.+?)_").matcher(m);
            if (mm.find()) nom = mm.group(1);
            com.google.gson.JsonObject o = new com.google.gson.JsonObject();
            o.addProperty("n", nom);
            o.addProperty("u", m);
            arr.add(o);
        }
        return arr;
    }

    /**
     * v7.7 — de un {@code url} crudo (del glbr o del {@code live} del glasbi) saca
     * {@code {url, agent}}. Puro.
     *
     * <p>Se tragaba solo la url limpia; el servidor manda tambien la forma
     * {@code advanced}: un <b>string que contiene JSON</b>
     * {@code {"url":…,"agent":…,"acceptSSL":"1","headers":{…}}}. Sin desempaquetarla
     * la url no empieza por http y el canal no se reproduce (v3.2.7 se la tragaba
     * entera y moria con «exception null»).
     */
    @Nullable
    static String[] urlYAgenteDe(@Nullable String crudo, @Nullable String agent) {
        if (crudo == null) return null;
        String u = crudo.trim();
        if (u.isEmpty()) return null;
        String ag = agent;
        if (u.startsWith("{")) {
            try {
                JsonElement em = JsonParser.parseString(u);
                if (em.isJsonObject()) {
                    String dentro = texto(em.getAsJsonObject(), "url");
                    String agDentro = texto(em.getAsJsonObject(), "agent");
                    if (dentro != null) u = dentro.trim();
                    if (agDentro != null) ag = agDentro;
                }
            } catch (Exception ignore) { }
        }
        if (!u.startsWith("http")) return null;
        return new String[]{ u, ag == null ? "" : ag.trim() };
    }

    /** Respuesta glasbi (ya desencriptada) -> {url, agent, opcionesJSON}; null si no
     *  hay nada jugable. Puro y testeable.
     *
     *  <p><b>v7.7 — el bug que cerraba 9 de cada 10 canales.</b> {@code live.url} llega
     *  en TRES formas, medidas sobre 150 canales del catálogo real:
     *  <pre>
     *   (a) marcador  "http://.LS.V2…/s"                  agent "redirect"   6,7 %
     *   (b) directa   "http://stream.mcquack.net/30/index.m3u8"             35,3 %
     *   (c) advanced  "{\"url\":\"…m3u8\",\"agent\":\"Mozilla\",…}"                 56,7 %
     *  </pre>
     *  Antes se exigia {@code .LS.} y se devolvia null en (b) y (c) — o sea, en el
     *  93 % de los canales. El llamador caia entonces al glbr pelado, que contesta el
     *  clip de relleno {@code update.mp4}, y de ahi al captador headless: lento y a
     *  veces sin cura. Comprobado que (b) y (c) son reproducibles: de 21 url
     *  descartadas, 14 devolvieron {@code #EXTM3U} valido.
     *
     *  <p>Ahora las tres valen. En (a) se devuelve el marcador para que
     *  {@link #resolverVivo} dé la segunda vuelta por el glbr; en (b) y (c) se
     *  devuelve la url ya jugable. */
    @Nullable
    public static String[] vivoDesdeGlasbi(@Nullable String claro) {
        if (claro == null || claro.trim().isEmpty()) return null;
        try {
            JsonElement r = JsonParser.parseString(claro);
            if (!r.isJsonObject()) return null;
            JsonObject jo = r.getAsJsonObject();
            if (optInt(jo, "result", -1) != 0) return null;
            JsonElement lv = jo.get("live");
            if (lv == null || !lv.isJsonObject()) return null;
            JsonObject live = lv.getAsJsonObject();
            String marca = texto(live, "url");
            if (marca == null || marca.trim().isEmpty()) return null;
            String ag = texto(live, "agent");
            if (ag == null || ag.trim().isEmpty()) ag = "redirect";
            String[] par = urlYAgenteDe(marca, ag);
            if (par == null) return null;
            return new String[]{ par[0], par[1], opcionesDeLive(live).toString() };
        } catch (Exception e) { return null; }
    }

    @Nullable
    public static Vivo resolverVivo(@NonNull String canalUrl, @NonNull String sesionJson) {
        try {
            String[] pr = partesDramaLive(canalUrl);
            if (pr == null) return null;
            JsonObject glt = gltDe(sesionJson);
            if (glt == null) return null;
            String wire = wireDe(sesionJson);
            // glasbi se traga la plantilla glbr tal cual (con url='1'/agent deco no le
            // molesta — medido en el telefono del usuario 17:51 y en el sandbox 22:40: 201
            // con el marcador). El id de la fila manda.
            JsonObject gb = payloadGlbr(sesionJson, glt, pr[0], pr[1], pr[2]);
            Respuesta rg = post(urlGlasbiDe(sesionJson), alambre(gb, wire));
            String claroG = rg.cuerpo == null ? null
                    : desalambre(new String(rg.cuerpo, StandardCharsets.ISO_8859_1));
            String[] vg = vivoDesdeGlasbi(claroG);
            if (vg == null) return null;
            JsonObject q2 = payloadGlbr(sesionJson, glt, pr[0], vg[0], vg[1]);
            Respuesta r2 = post(urlGlbrDe(sesionJson), alambre(q2, wire));
            String[] ra = glbrDesdeRespuesta(r2.status, r2.cuerpo);
            if (ra == null) return null;
            return new Vivo(ra[0], ra[1] == null ? "" : ra[1], vg[2]);
        } catch (Exception e) { return null; }
    }

    /** Re-resuelve el MISMO canal con el marcador de una opcion elegida (cambio de
     *  calidad desde el chip). No vuelve por glasbi: el marcador ya esta en la mano. */
    @NonNull
    public static String[] resolverMarcado(@NonNull String canalUrl, @NonNull String sesionJson,
                                           @NonNull String marcador, @NonNull String agent) throws Exception {
        String[] pr = partesDramaLive(canalUrl);
        if (pr == null) throw new IllegalStateException("fila dramalive:// ilegible");
        JsonObject glt = gltDe(sesionJson);
        if (glt == null) throw new IllegalStateException("no hay plantilla glt en la sesion");
        JsonObject q = payloadGlbr(sesionJson, glt, pr[0], marcador, agent);
        Respuesta r = post(urlGlbrDe(sesionJson), alambre(q, wireDe(sesionJson)));
        String[] ra = glbrDesdeRespuesta(r.status, r.cuerpo);
        if (ra == null)
            throw new IllegalStateException("HTTP " + r.status + " sin stream para el marcador pedido");
        return ra;
    }

    /** v3.2.16: cooldown de cura headless POR CANAL y a nivel proceso (el flag de
     * instancia renacia con cada tap y permitia relanzar el captador en bucle). Puro. */
    public static boolean curaFresca(@NonNull java.util.Map<String, Long> tsMap,
                                     @Nullable String pid, long ahoraMs, long cooldownMs) {
        if (pid == null) return false;
        Long t = tsMap.get(pid);
        return t != null && ahoraMs - t < cooldownMs;
    }

    /** v3.2.14: busca en la rejilla (m3u guardado) la fila cuyo dramalive:// lleva
     * id_live==id y devuelve {nombre de exhibicion, logo, group, url-de-la-fila}
     * (vacio donde no traiga);
     * null si el id no esta. Sirve para que la fila sintetica de la cosecha conserve
     * la identidad del canal al fusionar. Puro, testeable. */
    @Nullable
    public static String[] filaDeRejilla(@Nullable String m3u, @NonNull String id) {
        for (String[] b : bloques(m3u)) {
            if (id.equals(idFila(b[0], b[1]))) {   // v3.2.21: url o tvg-id
                String ext = b[0];
                int c = ext.lastIndexOf(',');
                String nom = c < 0 ? "" : ext.substring(c + 1).trim();
                String logo = "", group = "";
                java.util.regex.Matcher m =
                        java.util.regex.Pattern.compile("tvg-logo=\"([^\"]*)\"").matcher(ext);
                if (m.find()) logo = m.group(1);
                m = java.util.regex.Pattern.compile("group-title=\"([^\"]*)\"").matcher(ext);
                if (m.find()) group = m.group(1);
                return new String[]{nom.isEmpty() ? id : nom, logo, group, b[1]};
            }
        }
        return null;
    }

    /** v3.2.13: true si la sesion guardada trae la plantilla glbr del sitio CON user_id:
     * sin ella la curacion headless no puede armar la cosecha. Puro, testeable. */
    public static boolean tienePlantillaGlbr(@Nullable String sesionJson) {
        try {
            if (sesionJson == null || sesionJson.trim().isEmpty()) return false;
            JsonObject ses = JsonParser.parseString(sesionJson).getAsJsonObject();
            if (!ses.has("plantillas") || !ses.getAsJsonObject("plantillas").isJsonObject()) return false;
            JsonObject plant = ses.getAsJsonObject("plantillas");
            return plant.has("glbrReq") && plant.getAsJsonObject("glbrReq").isJsonObject()
                    && plant.getAsJsonObject("glbrReq").has("user_id");
        } catch (Exception e) {
            return false;
        }
    }

    /** v3.2.13: id_live crudo (decodificado) de una fila dramalive://play?id_live=…; null si no. */
    @Nullable
    public static String idDeCanal(@Nullable String canalUrl) {
        if (canalUrl == null) return null;
        int i = canalUrl.indexOf("id_live=");
        if (i < 0) return null;
        int j = canalUrl.indexOf('&', i);
        String id = canalUrl.substring(i + 8, j < 0 ? canalUrl.length() : j);
        if (id.isEmpty()) return null;
        try { return java.net.URLDecoder.decode(id, "UTF-8"); } catch (Exception e) { return id; }
    }

    /** v3.2.12: describe (para el log del player) CON QUÉ arma salió el POST glbr:
     * plantilla aprendida del sitio (N campos) o el glt pelado. Puro, testeable. */
    @NonNull
    public static String diagnosticoGlbr(@Nullable String sesionJson) {
        try {
            JsonObject ses = JsonParser.parseString(sesionJson == null || sesionJson.trim().isEmpty()
                    ? "{}" : sesionJson).getAsJsonObject();
            JsonObject plant = ses.has("plantillas") && ses.getAsJsonObject("plantillas").isJsonObject()
                    ? ses.getAsJsonObject("plantillas") : new JsonObject();
            String base = "ninguna";
            if (plant.has("glbrReq") && plant.getAsJsonObject("glbrReq").isJsonObject())
                base = "glbrReq del sitio (" + plant.getAsJsonObject("glbrReq").size() + " campos)";
            else if (plant.has("glt") && plant.getAsJsonObject("glt").isJsonObject())
                base = "glt (" + plant.getAsJsonObject("glt").size() + " campos)";
            String u = ses.has("urlGlbr") && !ses.get("urlGlbr").isJsonNull()
                    ? ses.get("urlGlbr").getAsString() : "https://re.highperformanceconnect.shop/redirect/glbr";
            return "base=" + base + " · url=" + u;
        } catch (Exception e) {
            return "sesion ilegible: " + e.getClass().getSimpleName();
        }
    }

    /** Quita del m3u los bloques cuya url es el sueno update.mp4 (v3.2.6 los backfilleo
     * sin guarda; envenenaban la rejilla con "siempre clip de relleno"). Puro, testeado. */
    @NonNull
    public static String sanearSuenos(@NonNull String m3u) {
        if (!m3u.contains("update.mp4")) return m3u;
        String[] ls = m3u.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ls.length; i++) {
            String l = ls[i];
            if (l.startsWith("#EXTINF") && i + 1 < ls.length && ls[i + 1].contains("update.mp4")) { i++; continue; }
            if (l.contains("update.mp4")) continue;
            sb.append(l).append('\n');
        }
        String out = sb.toString();
        return out.endsWith("\n") ? out : out + "\n";
    }

    /**
     * v3.2.11: la entrega del captador trae SOLO el topic que el sitio cargó (p. ej.
     * 89 canales) pero guardarDramaExtra reemplazaba la rejilla ENTERA: «se van todas
     * las canales» (logcat 16:07: «ENTREGA OK 89 canales» con 9115 guardados). Fusionar:
     * los canales que llegan actualizan nombre/logo/url en su posicion; una url real ya
     * curada (https) no se degrada a dramalive://; lo no mencionado se conserva.
     */
    @NonNull
    public static String fusionar(@Nullable String viejo, @NonNull String nuevo) {
        List<String[]> vs = bloques(viejo), ns = bloques(nuevo);
        if (vs.isEmpty()) return nuevo;
        if (ns.isEmpty()) return viejo == null ? "" : viejo;
        Map<String, Integer> posId = new LinkedHashMap<>(), posNom = new LinkedHashMap<>();
        for (int i = 0; i < vs.size(); i++) {
            String id = idFila(vs.get(i)[0], vs.get(i)[1]);   // v3.2.21
            if (id != null) posId.putIfAbsent(id, i);
            String nom = nombreDe(vs.get(i)[0]);
            if (nom != null) posNom.putIfAbsent(nom, i);
        }
        for (String[] b : ns) {
            String id = idFila(b[0], b[1]);   // v3.2.21
            Integer p = id == null ? null : posId.get(id);
            if (p == null) {
                String nom = nombreDe(b[0]);
                if (nom != null) p = posNom.get(nom);
            }
            if (p == null) { vs.add(b); continue; }
            String[] antigua = vs.get(p);
            boolean realVieja = antigua[1].startsWith("http"), realNueva = b[1].startsWith("http");
            if (realNueva || !realVieja) {   // nunca degradar una url real ya curada
                // v3.2.21: si la fila nueva llego SIN id (sitio viejo), hereda el id de la
                // vieja en tvg-id — asi la cura continua siendo visible para filaDeRejilla.
                String idVieja = idFila(antigua[0], antigua[1]);
                if (idVieja != null && idFila(b[0], b[1]) == null) {
                    int cc = b[0].lastIndexOf(',');
                    if (cc > 0) b[0] = b[0].substring(0, cc) + " tvg-id=\"" + idVieja + "\"" + b[0].substring(cc);
                }
                vs.set(p, b);
            }
        }
        StringBuilder sb = new StringBuilder("#EXTM3U\n");
        for (String[] b : vs) sb.append(b[0]).append('\n').append(b[1]).append('\n');
        return sb.toString();
    }

    /** id_live dentro de una url dramalive://; null si no la lleva. */
    @Nullable
    private static String idLive(@NonNull String url) {
        int i = url.indexOf("id_live=");
        if (i < 0) return null;
        int j = url.indexOf('&', i);
        String id = url.substring(i + 8, j < 0 ? url.length() : j).trim();
        return id.isEmpty() ? null : id;
    }

    /**
     * v3.2.21: la llave de una fila. Antes vivia solo en la url (dramalive://?id_live=X); al
     * curarse a https esa llave MORIA y fusionar()/filaDeRejilla() perdian el canal (log usuario
     * 12:22: «ENTREGA OK» y «aun no llega a la fila» para siempre). Ahora el id tambien puede
     * venir en tvg-id del EXTINF, que la cura conserva.
     */
    @Nullable
    static String idFila(@NonNull String extinf, @NonNull String url) {
        String id = idLive(url);
        if (id != null) return id;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("tvg-id=\"([^\"]+)\"").matcher(extinf);
        return m.find() ? m.group(1) : null;
    }

    /** Nombre de exhibicion: lo que sigue a la ultima coma de la linea #EXTINF (minusculas). */
    @Nullable
    private static String nombreDe(@NonNull String extinf) {
        int c = extinf.lastIndexOf(',');
        if (c < 0) return null;
        String n = extinf.substring(c + 1).trim().toLowerCase();
        return n.isEmpty() ? null : n;
    }

    @NonNull
    private static List<String[]> bloques(@Nullable String m3u) {
        List<String[]> out = new ArrayList<>();
        if (m3u == null) return out;
        String[] ls = m3u.split("\n");
        for (int i = 0; i < ls.length; i++) {
            if (!ls[i].startsWith("#EXTINF")) continue;
            for (int j = i + 1; j < ls.length; j++) {
                if (ls[j].isEmpty() || ls[j].startsWith("#")) continue;
                out.add(new String[]{ls[i], ls[j]});
                i = j;
                break;
            }
        }
        return out;
    }

    public static String aM3u(@NonNull List<CanalDrama> canales) {
        StringBuilder sb = new StringBuilder("#EXTM3U\n");
        for (CanalDrama c : canales) {
            String t = c.name.replace("\"", "'");
            String g = (c.group == null || c.group.isEmpty() ? "En vivo" : c.group).replace("\"", "'");
            String logo = c.logo == null ? "" : " tvg-logo=\"" + c.logo + "\"";
            String tid = c.dramaId == null || c.dramaId.isEmpty() ? ""
                    : " tvg-id=\"" + c.dramaId.replace("\"", "'") + "\"";   // v3.2.21
            sb.append("#EXTINF:-1 tvg-name=\"").append(t).append(tid).append("\" group-title=\"").append(g)
              .append('"').append(logo).append(',').append(t).append('\n')
              .append(c.url).append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------- puente WebView

    /**
     * Traduce la entrega del captador in-app (assets/drama_auto.js corriendo en
     * el WebView de {@link DramaAutoActivity}) a m3u. Forma medida del puente:
     *   {"extraido":"...","motivo":"auto","plantillas":{"glt":{...}},
     *    "canales":[{"n":"Nombre","u":"https://...","g":"grupo","l":"logo"}]}
     * Devuelve {@link Resultado#exito} con N canales o {@link Resultado#fallo}
     * con la razon para el usuario. Puro: sin red ni Android (testeable en JVM).
     */
    @NonNull
    public static Resultado desdePuente(@Nullable String jsonPuente) {
        if (jsonPuente == null || jsonPuente.trim().isEmpty())
            return Resultado.fallo("entrega vacia");
        JsonElement raiz;
        try {
            raiz = JsonParser.parseString(jsonPuente);
        } catch (Exception e) {
            return Resultado.fallo("entrega no es JSON: " + e.getClass().getSimpleName());
        }
        if (!raiz.isJsonObject()) return Resultado.fallo("entrega no es un objeto");
        JsonElement cs = raiz.getAsJsonObject().get("canales");
        if (cs == null || !cs.isJsonArray() || cs.getAsJsonArray().size() == 0)
            return Resultado.fallo("el captador aun no trajo canales");
        List<CanalDrama> todos = new ArrayList<>();
        for (JsonElement el : cs.getAsJsonArray()) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            String n = texto(o, "n"), u = texto(o, "u");
            if (n == null || u == null) continue;
            if (!u.startsWith("http://") && !u.startsWith("https://") && !u.startsWith("dramalive://")) continue;
            CanalDrama c = new CanalDrama();
            c.name = n.trim();
            c.url = u.trim();
            String g = texto(o, "g");
            c.group = g == null || g.trim().isEmpty() ? "En vivo" : g.trim();
            c.logo = texto(o, "l");
            String di = texto(o, "di");          // v3.2.21: el captador marca las filas curadas
            if (di != null && !di.trim().isEmpty()) c.dramaId = di.trim();
            todos.add(c);
        }
        if (todos.isEmpty()) return Resultado.fallo("canales sin nombre+url utilizable");
        List<CanalDrama> unicos = new ArrayList<>();
        Set<String> vistos = new HashSet<>();
        for (CanalDrama c : todos) if (vistos.add(c.name + "\u0000" + c.url)) unicos.add(c);
        return Resultado.exito(unicos.size(), aM3u(unicos));
    }

    /**
     * Reempaqueta la plantilla glt aprendida por el captador en la forma que
     * consume {@link #extraer} ({"plantillas":{"glt":...}}), para que el
     * refresco silencioso funcione SIN WebView mientras la sesion aguante.
     * null si la entrega no traia un glt con user_id (no guardable).
     */
    @Nullable
    public static String plantillasDesdePuente(@Nullable String jsonPuente) {
        try {
            JsonElement raiz = JsonParser.parseString(jsonPuente);
            if (!raiz.isJsonObject()) return null;
            JsonObject p = raiz.getAsJsonObject().getAsJsonObject("plantillas");
            if (p == null) return null;
            JsonObject glt = p.getAsJsonObject("glt");
            if (glt == null || !glt.has("user_id")) return null;
            JsonObject out = new JsonObject();
            out.addProperty("base", BASE_DEFECTO);
            if (raiz.getAsJsonObject().has("url") && !raiz.getAsJsonObject().get("url").isJsonNull())
                out.add("url", raiz.getAsJsonObject().get("url"));
            if (raiz.getAsJsonObject().has("wire") && !raiz.getAsJsonObject().get("wire").isJsonNull())
                out.add("wire", raiz.getAsJsonObject().get("wire"));
            JsonObject plant = new JsonObject();
            plant.add("glt", glt);
            JsonObject p0 = raiz.getAsJsonObject();
            if (p0.has("urlGlbt") && !p0.get("urlGlbt").isJsonNull()) out.add("urlGlbt", p0.get("urlGlbt"));
            if (p0.has("urlGlbr") && !p0.get("urlGlbr").isJsonNull()) out.add("urlGlbr", p0.get("urlGlbr"));
            if (p0.has("topics") && !p0.get("topics").isJsonNull()) out.add("topics", p0.get("topics"));
            if (p.has("glbt") && p.getAsJsonObject("glbt") != null) plant.add("glbt", p.getAsJsonObject("glbt"));
            if (p.has("glbrReq") && p.getAsJsonObject("glbrReq") != null) plant.add("glbrReq", p.getAsJsonObject("glbrReq"));
            out.add("plantillas", plant);
            out.addProperty("auto", System.currentTimeMillis() / 1000L);
            return GSON.toJson(out);
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    private static String texto(@NonNull JsonObject o, @NonNull String k) {
        JsonElement e = o.get(k);
        if (e == null || e.isJsonNull()) return null;
        try { return e.getAsString(); } catch (Exception ex) { return null; }
    }

    // ------------------------------------------------------------------- red

    /**
     * Un glt por cada tipo con la plantilla pegada y dedupe global. Sincrono: el
     * caller (el sheet de fuentes) lo llama en su hilo. `tiposCsv` null → los de la plantilla.
     */
    /** ───────────────── v3.2.34 — CONEXION AUTOMATICA A DRAMA (sin captador) ─────
     *  Plantilla glt anonima EMPOTRADA: los campos que el edge valida de verdad (medidos:
     *  con user_id «_notloggedin.com_dramalive3», version_name web_241, store web y
     *  KEY_ACTIVATED_TYPE el glt contesta 201 con los topics; con basura, 200 en blanco).
     *  device_id/user_id nacen FRESHOS por instalacion para no heredar floods ajenos. */
    @NonNull
    public static JsonObject gltInicial() {
        JsonObject g = new JsonObject();
        g.addProperty("user_id", (System.currentTimeMillis() * 1000L)
                + "_notloggedin.com_dramalive3"); // 16 digitos en MICROSEGUNDOS: el formato del sitio
        g.addProperty("device_id", java.util.UUID.randomUUID().toString());
        g.addProperty("device_api", "chrome/154.0.8037.57");
        g.addProperty("version_name", "web_241");
        g.addProperty("language", "en");
        String tz = "America/Lima";
        try { tz = java.util.TimeZone.getDefault().getID(); } catch (Exception ignore) { }
        g.addProperty("timezone", tz);
        g.addProperty("device_type", "phone");
        g.addProperty("os_platform", "web");
        g.addProperty("os", "android");
        g.addProperty("KEY_ACTIVATED_TYPE", "232425");
        g.addProperty("store", "web");
        g.addProperty("isStoreVersion", true);
        g.addProperty("isPremium", false);
        g.addProperty("isCoupon_active", false);
        g.addProperty("hideAds", false);
        g.addProperty("appCount", "{\"runCount\":1,\"adsLoaded\":1,\"adsShowed\":1,\"adsFailed\":1}");
        g.addProperty("mainServer", BASE_DEFECTO);
        g.addProperty("type", "tv");
        return g;
    }

    /** glbrReq derivado del glt con la auth que glasbi/glbr exigen (token=user_id y los
     *  contadores de ads en 1 — la jugada medida en la red del usuario 12/10). Puro. */
    @NonNull
    public static JsonObject glbrReqDesdeGlt(@NonNull JsonObject glt) {
        JsonObject q = glt.deepCopy();
        String uid = texto(glt, "user_id");
        q.addProperty("token", uid == null ? "" : uid);
        q.addProperty("rc", 0);
        q.addProperty("adsShowed", 1);
        q.addProperty("adsComplete", 1);
        q.addProperty("runCount", 1);
        String tz = texto(glt, "timezone");
        q.addProperty("timeZone", tz == null ? "" : tz);
        q.addProperty("id", "live_tv_beinsport1");
        q.addProperty("url", "1");
        q.addProperty("agent", "all_streams_redirect");
        return q;
    }

    /** Auto-conexion: sondea el glt real con la plantilla empotrada y, si el sitio nos
     *  reconoce, arma la sesion completa (glt + glbrReq + urls) para el disco. null =
     *  no hay red o el edge blaquea: el llamador hace el plan B de siempre. */
    @Nullable
    public static String conectarYSembrar(@Nullable android.content.Context ctx) {
        try {
            // EL DISPOSITIVO NACE UNA VEZ y se persiste (como hace el sitio): re-generar
            // uuid en cada retry es la receta para que el antiflood del edge nunca nos
            // reconozca. Con la misma identidad, el glt 201 llega cuando la puerta baja.
            // ctx null (o sin prefs legibles) => se siembra al vuelo, sin persistir.
            android.content.SharedPreferences sp = null;
            try { sp = ctx.getSharedPreferences("drama_seed", 0); } catch (Exception sinPrefs) { }
            JsonObject glt = null;
            if (sp != null) {
                String mine = sp.getString("glt", null);
                if (mine != null) {
                    try { glt = JsonParser.parseString(mine).getAsJsonObject(); } catch (Exception ignore) { }
                }
            }
            if (glt == null) {
                glt = gltInicial();
                if (sp != null) sp.edit().putString("glt", glt.toString()).apply();
            }
            Respuesta rg = post(BASE_LIVE + "glt", alambre(glt, "ctiv"));
            String claro = rg.cuerpo == null ? null
                    : new String(desalambreBytes(rg.cuerpo), StandardCharsets.UTF_8);
            if (rg.status < 200 || rg.status >= 300 || claro == null || claro.trim().isEmpty()) return null;
            JsonObject plant = new JsonObject();
            plant.add("glt", glt);
            plant.add("glbrReq", glbrReqDesdeGlt(glt));
            JsonObject ses = new JsonObject();
            ses.addProperty("wire", "ctiv");
            ses.addProperty("url", BASE_LIVE + "glt");
            ses.addProperty("urlGlbt", BASE_LIVE + "glbt");
            ses.addProperty("urlGlbr", BASE_REDIRECT + "glbr");
            ses.add("plantillas", plant);
            return ses.toString();
        } catch (Exception e) { return null; }
    }

    private static final String BASE_LIVE = "https://li.highperformanceconnect.shop/api/live/livedrama/v13.0.0/";
    private static final String BASE_REDIRECT = "https://re.highperformanceconnect.shop/redirect/";

    /** public: el glbrReq derivado del glt empotrado (testeable sin Android). */
    @NonNull
    public static JsonObject semillaDerivada() {
        return glbrReqDesdeGlt(gltInicial());
    }

    @NonNull
    /**
     * v3.2.59 («my bien pero falta un montón de paises»): la conexión automática trae
     * los canales de los primeros 40 topics (tope histórico, medido en topicosDe) — el
     * panel de Drama en cambio muestra los 265 del catálogo y CARGA cada uno al tocarlo.
     * Este método replica ese comportamiento: pide el glbt de UN topic con la sesión
     * guardada y devuelve sus canales listos para fusionar. Sync: llamar en un hilo.
     * Null = sesión inservible, topic inexistente o el edge no contestó.
     */
    @Nullable
    public static java.util.List<CanalDrama> canalesDeTopic(@Nullable String sesionJson,
                                                             @NonNull String topicId) {
        try {
            if (sesionJson == null || sesionJson.trim().isEmpty()) return null;
            JsonObject glt = gltDe(sesionJson);
            if (glt == null) return null;
            String wire = "ctiv";
            try {
                JsonElement w = JsonParser.parseString(sesionJson).getAsJsonObject().get("wire");
                if (w != null && w.isJsonPrimitive() && !w.getAsString().isEmpty()) wire = w.getAsString();
            } catch (Exception ignoreW) { }
            Respuesta rb = post(urlGlbtDe(sesionJson), alambre(payloadGlbt(glt, topicId), wire));
            if (rb == null || rb.cuerpo == null) return null;
            String claro = new String(desalambreBytes(rb.cuerpo), StandardCharsets.UTF_8);
            java.util.List<CanalDrama> cs = canales(claro);
            if (cs.isEmpty()) return cs;
            String nom = nombresTopicosDe(glt.toString()).get(topicId);
            if (nom != null && !nom.trim().isEmpty())
                for (CanalDrama cd : cs) cd.group = nom.trim();
            return cs;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * v3.2.60 («faltan paises, en drama hay mas»): el panel no debe esperar a que hoy
     * caiga una extraccion para mostrar los 265 topics: con la SEMILLA de sesion
     * guardada pide el `glt` — un request chico, el mismo que la web del sitio hace al
     * entrar — y devuelve {topic_nombres, topic_logos} serializados listos para prefs.
     * Sync: llamar en un hilo. Null = sin sesion util, sin red o respuesta extraña.
     */
    @Nullable
    public static String[] catalogoDeSesion(@Nullable String sesionJson) {
        try {
            if (sesionJson == null || sesionJson.trim().isEmpty()) return null;
            JsonObject ses = JsonParser.parseString(sesionJson).getAsJsonObject();
            JsonObject glt = gltDe(sesionJson);
            if (glt == null) return null;
            String url = null;
            JsonElement u = ses.get("url");
            if (u != null && u.isJsonPrimitive()) url = u.getAsString();
            if (url == null || !url.startsWith("http")) return null;
            String wire = "ctiv";
            JsonElement w = ses.get("wire");
            if (w != null && w.isJsonPrimitive() && !w.getAsString().isEmpty())
                wire = w.getAsString();
            Respuesta r = post(url, alambre(glt, wire));
            if (r == null || r.cuerpo == null) return null;
            String claro = new String(desalambreBytes(r.cuerpo), StandardCharsets.UTF_8);
            if (claro == null || !claro.contains("live_topics")) return null;
            return new String[]{serializarLogos(nombresTopicosDe(claro)),
                                serializarLogos(logosTopicosDe(claro))};
        } catch (Exception e) {
            return null;
        }
    }

    public static Resultado extraer(@NonNull String plantillasJson, @Nullable String tiposCsv) {
        return extraer(plantillasJson, tiposCsv, 0, null);
    }

    /**
     * Extracción con tope de tópicos y avance por tópico.
     *
     * @param maxTopics {@code <= 0} = TODOS los del índice (265 medidos).
     * @param avance    si no es null, se le avisa al terminar cada tópico (desde los
     *                  hilos del pool: quien lo use debe aguantar concurrencia).
     */
    public static Resultado extraer(@NonNull String plantillasJson, @Nullable String tiposCsv,
                                    int maxTopics, @Nullable Avance avance) {
        String err = errorPlantilla(plantillasJson);
        if (err != null) return Resultado.fallo(err);
        JsonObject glt = gltDe(plantillasJson);
        if (glt == null) return Resultado.fallo("sin glt");
        String url = urlGltDe(plantillasJson);
        final String wire = wireDe(plantillasJson);
        List<CanalDrama> todos = new ArrayList<>();
        // Con los tópicos en paralelo, varios hilos añaden errores a la vez.
        List<String> errores = java.util.Collections.synchronizedList(new ArrayList<>());
        boolean auto = tiposCsv == null || tiposCsv.trim().isEmpty() || esCsvLegado(tiposCsv);
        if (auto) {
            // MEDIDO: el servidor solo acepta el type que trae la propia plantilla
            // (type:"tv"); las listas inventadas {live,sports…} devuelven result:1
            // "no data" (401 B). El glt contesta el INDICE de categorias con listas
            // vacias; los canales llegan por glbt {topic} — el flujo del sitio.
            try {
                Respuesta rg = post(url, alambre(glt, wire));
                String claroG = rg.cuerpo == null ? null : new String(desalambreBytes(rg.cuerpo), StandardCharsets.UTF_8);
                if (claroG == null) {
                    errores.add("glt: respuesta ilegible (" + rg.status + ", " + (rg.cuerpo == null ? 0 : rg.cuerpo.length) + " B)");
                } else if (topicoVacioYCanalVacio(claroG)) {
                    errores.add("glt dijo " + resumenGlt(claroG) + " (el servidor no reconoce la sesion o el type)");
                } else {
                    // v3.2.42: sin corte de 12 — cada pais/topic que se quedaba fuera era
                    // una casilla vacia en la rejilla; y el group-title lleva ahora el
                    // nombre humano del topic (no el id crudo) para el chip con bandera.
                    // TODOS los tópicos del índice (medido: 265 = 27 categorías
                    // + 238 países). Con el tope de 40 se perdían 225.
                    List<String> tps = topicosDe(claroG, maxTopics);
                    Map<String, String> nombresT = nombresTopicosDe(claroG);
                    ultimosLogosTopicos = serializarLogos(logosTopicosDe(claroG)); // v3.2.54
                    ultimosNombresTopicos = serializarLogos(nombresT);   // v3.2.59
                    if (tps.isEmpty()) todos.addAll(canales(claroG)); // API vieja: canales directos en el glt
                    String urlGlbt = urlGlbtDe(plantillasJson);
                    todos.addAll(pedirTopicos(glt, urlGlbt, wire, tps, nombresT, errores, avance));
                }
            } catch (Exception e) {
                errores.add("glt: " + e.getClass().getSimpleName() + " " + e.getMessage());
            }
        }
        if (!auto || (todos.isEmpty() && errores.size() > 0 && !errores.get(0).contains("ilegible"))) {
            for (String tipo : auto ? java.util.Collections.<String>emptyList() : tiposDe(plantillasJson, tiposCsv)) {
                try {
                    Respuesta respuesta = post(url, alambre(payloadPara(glt, tipo), wire));
                    Resultado r = procesarRespuesta(respuesta.status, respuesta.cuerpo);
                    if (r.ok()) {
                        todos.addAll(canales(new String(desalambreBytes(respuesta.cuerpo), StandardCharsets.UTF_8)));
                    } else {
                        errores.add(tipo + ": " + r.error);
                    }
                } catch (Exception e) {
                    errores.add(tipo + ": " + e.getClass().getSimpleName() + " " + e.getMessage());
                }
            }
        }
        if (todos.isEmpty())
            return Resultado.fallo(errores.isEmpty() ? "0 canales" : String.join(" | ", errores));
        // dedupe global por (nombre,url) conservando el orden del primer glt
        List<CanalDrama> unicos = new ArrayList<>();
        Set<String> vistos = new HashSet<>();
        for (CanalDrama c : todos) if (vistos.add(c.name + "\u0000" + c.url)) unicos.add(c);
        return Resultado.exito(unicos.size(), aM3u(unicos));
    }

    /** Hilos concurrentes para pedir tópicos (el pool se crea y se cierra por extracción). */
    private static final int HILOS_TOPICOS = 6;

    /**
     * Progreso de la extracción por tópicos. Se llama desde los hilos del pool.
     */
    public interface Avance {
        void enTopico(@NonNull String topico, @NonNull List<CanalDrama> nuevos, int total);
    }

    /**
     * Pide los canales de una lista de tópicos EN PARALELO y los devuelve en el
     * mismo orden en que se pidieron.
     *
     * <p>Medido: el índice trae 265 tópicos. Pedirlos de uno en uno tardaba
     * minutos; con {@value #HILOS_TOPICOS} hilos la extracción completa baja a
     * ~12 s. Cada tópico es una petición independiente al mismo endpoint, así que
     * no hay orden que respetar en la red.
     *
     * <p>El {@code group} de cada canal se pone con el nombre humano del tópico,
     * igual que hacía el bucle de uno en uno.
     */
    @NonNull
    private static List<CanalDrama> pedirTopicos(@NonNull JsonObject glt, @NonNull String urlGlbt,
                                                 @NonNull String wire, @NonNull List<String> tps,
                                                 @NonNull Map<String, String> nombresT,
                                                 @NonNull List<String> errores,
                                                 @Nullable Avance avance) {
        final int n = tps.size();
        @SuppressWarnings("unchecked")
        final List<CanalDrama>[] porTopico = new List[n];
        if (n <= 1) {
            if (n == 1) {
                porTopico[0] = pedirUnTopico(glt, urlGlbt, wire, tps.get(0), nombresT, errores);
                if (avance != null) {
                    try { avance.enTopico(tps.get(0), porTopico[0], porTopico[0].size()); }
                    catch (Exception ignore) { }
                }
            }
            List<CanalDrama> out = new ArrayList<>();
            for (int i = 0; i < n; i++) if (porTopico[i] != null) out.addAll(porTopico[i]);
            return out;
        }
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors
                .newFixedThreadPool(Math.min(HILOS_TOPICOS, n));
        try {
            java.util.List<java.util.concurrent.Future<?>> futuros = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                final String tp = tps.get(i);
                final int idx = i;
                futuros.add(pool.submit(() -> {
                    List<CanalDrama> del = pedirUnTopico(glt, urlGlbt, wire, tp, nombresT, errores);
                    porTopico[idx] = del;
                    if (avance != null) {
                        try { avance.enTopico(tp, del, del.size()); } catch (Exception ignore) { }
                    }
                }));
            }
            for (java.util.concurrent.Future<?> f : futuros) {
                try {
                    f.get();
                } catch (Exception e) {
                    errores.add("tópico: " + e.getClass().getSimpleName());
                }
            }
        } finally {
            pool.shutdown();
        }
        List<CanalDrama> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (porTopico[i] != null && !porTopico[i].isEmpty()) out.addAll(porTopico[i]);
        }
        return out;
    }

    /** Un POST glbt de un tópico, con el {@code group} puesto al nombre humano. */
    @NonNull
    private static List<CanalDrama> pedirUnTopico(@NonNull JsonObject glt, @NonNull String urlGlbt,
                                                  @NonNull String wire, @NonNull String tp,
                                                  @NonNull Map<String, String> nombresT,
                                                  @NonNull List<String> errores) {
        try {
            Respuesta rb = post(urlGlbt, alambre(payloadGlbt(glt, tp), wire));
            String claroB = rb.cuerpo == null ? null
                    : new String(desalambreBytes(rb.cuerpo), StandardCharsets.UTF_8);
            if (claroB == null) {
                errores.add(tp + ": glbt ilegible (" + rb.status + ")");
                return new ArrayList<>();
            }
            String nom = nombresT.get(tp);
            List<CanalDrama> cs = canales(claroB);
            if (nom != null && !nom.trim().isEmpty()) {
                for (CanalDrama c : cs) c.group = nom.trim();
            }
            return cs;
        } catch (Exception e) {
            errores.add(tp + ": " + e.getClass().getSimpleName() + " " + e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Pide los canales de UN solo tópico (glbt). Para cuando el usuario toca una
     * categoría o un país de la tira que todavía no está cargado.
     */
    @NonNull
    public static Resultado extraerTopico(@NonNull String plantillasJson, @NonNull String topico) {
        String err = errorPlantilla(plantillasJson);
        if (err != null) return Resultado.fallo(err);
        JsonObject glt = gltDe(plantillasJson);
        if (glt == null) return Resultado.fallo("sin glt");
        String urlGlbt = urlGlbtDe(plantillasJson);
        final String wire = wireDe(plantillasJson);
        try {
            Respuesta rb = post(urlGlbt, alambre(payloadGlbt(glt, topico), wire));
            String claroB = rb.cuerpo == null ? null
                    : new String(desalambreBytes(rb.cuerpo), StandardCharsets.UTF_8);
            if (claroB == null) return Resultado.fallo(topico + ": glbt ilegible (" + rb.status + ")");
            List<CanalDrama> cs = canales(claroB);
            if (cs.isEmpty()) return Resultado.fallo(topico + ": sin canales");
            return Resultado.exito(cs.size(), aM3u(cs));
        } catch (Exception e) {
            return Resultado.fallo(topico + ": " + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    /** descifra el alambre desde bytes crudos (latin-1 como lo lee python). */
    @Nullable
    static byte[] desalambreBytes(@Nullable byte[] cuerpo) {
        String claro = cuerpo == null ? null : desalambre(new String(cuerpo, StandardCharsets.ISO_8859_1));
        return claro == null ? null : claro.getBytes(StandardCharsets.UTF_8);
    }

    private static final class Respuesta {
        final int status;
        final byte[] cuerpo;
        Respuesta(int status, byte[] cuerpo) { this.status = status; this.cuerpo = cuerpo; }
    }

    @NonNull
    private static Respuesta post(@NonNull String url, @NonNull String body) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        try {
            con.setRequestMethod("POST");
            con.setConnectTimeout(8000);   // v3.2.62: fallar rapido, no colgar
            con.setReadTimeout(15000);   // v3.2.62
            con.setDoOutput(true);
            con.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
            con.setRequestProperty("User-Agent", UA);
            // v3.2.9: el fetch del SITIO manda Origin/Referer y las COOKIES del dominio
            // (credentials:include); nuestro post naked recibia el sueno update.mp4
            // incluso desde la linea del usuario, mientras el glbr del propio sitio
            // conseguia el stream fas6 real (logcat 14:06 vs 15:14, mismo telefono).
            con.setRequestProperty("Origin", "https://web.dramalive.cc");
            con.setRequestProperty("Referer", "https://web.dramalive.cc/");
            String galletas = galletasPara(url);
            if (galletas != null && !galletas.isEmpty()) con.setRequestProperty("Cookie", galletas);
            byte[] datos = body.getBytes(StandardCharsets.UTF_8);
            con.setFixedLengthStreamingMode(datos.length);
            try (OutputStream os = con.getOutputStream()) { os.write(datos); }
            int st = con.getResponseCode();
            asientaGalletas(url, con);
            InputStream is = st >= 400 ? con.getErrorStream() : con.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream(16384);
            if (is != null) {
                byte[] tmp = new byte[16384];
                int n;
                int total = 0;
                while ((n = is.read(tmp)) > 0) {
                    buf.write(tmp, 0, n);
                    total += n;
                    if (total > 8 * 1024 * 1024) throw new java.io.IOException("respuesta >8MB (no parece el alambre del catalogo)");
                }
                is.close();
            }
            return new Respuesta(st, buf.toByteArray());
        } finally {
            // v3.2.63 (velocidad): SIN disconnect() — el stream ya se leyo entero
            // y se cerro, asi la conexion (DNS+TCP+TLS ya pagados) vuelve al pool
            // y el siguiente post al mismo host la reutiliza. Antes cada peticion
            // (abrir un canal hace 2) pagaba el handshake completo.
        }
    }

    // ---------------------------------------------------------------- helpers

    @Nullable
    private static JsonObject objeto(@Nullable String json) {
        if (json == null) return null;
        try {
            JsonElement e = JsonParser.parseString(json.trim());
            return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (Exception ex) {
            return null;
        }
    }

    @Nullable
    private static JsonObject objetoHijo(@NonNull JsonObject o, @NonNull String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static boolean hayCampo(@NonNull JsonObject o, @NonNull String k) {
        JsonElement e = o.get(k);
        return e != null && !e.isJsonNull() && !e.getAsString().trim().isEmpty();
    }

    @Nullable
    private static String textoDe(@NonNull JsonObject o, @NonNull String k) {
        JsonElement e = o.get(k);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return null;
        String s = e.getAsString();
        return s.trim().isEmpty() ? null : s.trim();
    }

    @Nullable
    private static String primero(@NonNull JsonObject o, @NonNull String[] claves) {
        for (String k : claves) {
            String v = textoDe(o, k);
            if (v != null) return v;
        }
        return null;
    }
}
