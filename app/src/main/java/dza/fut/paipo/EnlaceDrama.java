package dza.fut.paipo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Abre los enlaces cifrados que usan los players "de Drama":
 *
 *   https://<host>/player/player.html?fit=contain#U2FsdGVkX1…
 *
 * Lo que hay tras el `#` es un blob `Salted__` de CryptoJS (tambien lo escribe
 * OpenSSL con `enc -aes-256-cbc -md md5`): AES-256-CBC cuya clave se deriva con
 * EVP_BytesToKey(MD5, 1 ronda) sobre `(clave, salt)`, donde el `salt` viaja en
 * claro dentro del propio blob. El texto libre es JSON:
 *
 *   {"url":"https://cdn…/pages/<id>/index.html","mediatype":"hls","swap":{…}}
 *
 * Medido en el `player.js` de ese host (61.755 B, crypto-js 4.2.0): la "clave"
 * se arma con `(meta[name="app-id"].id || "") + "_YOUR_SECRET"`, y el HTML que
 * sirve ese servidor trae la meta con `content="REPLACE_WITH"` y SIN `id`, asi
 * que la clave publica queda en la literal `REPLACE_WITH_YOUR_SECRET`. No hay
 * nada secreto que romper: solo hay que saber leer el bundle, y eso ya esta leido.
 *
 * La clase es 100% JVM (javax.crypto + java.util.Base64) para poder probarla en
 * la suite de unidad contra los vectores generados con node/crypto-js 4.2.0
 * (`docs/vectores-cryptojs.json`), que son la unica autoridad que vale aqui:
 * un KDF erroneo tambien "round-tripsea" consigo mismo, pero no desencripta.
 */
public final class EnlaceDrama {

    /** Los primeros 12 caracteres de cualquier blob `Salted__` en base64. */
    public static final String MAGIC_B64 = "U2FsdGVkX1";
    private static final String MAGIC = "Salted__";

    /**
     * Claves candidates, en orden: la publica de este host (meta `REPLACE_WITH`
     * concatenada con el literal del bundle), el literal a secas (si el panel se
     * olvid6 de poner la meta), la meta sola y la cadena vacia (algun fork la usa).
     */
    public static final String[] CLAVES_PUBLICAS = {
            "REPLACE_WITH_YOUR_SECRET", "_YOUR_SECRET", "REPLACE_WITH", ""
    };

    /** Resultado de abrir un enlace. */
    public static final class Contenido {
        /** La URL interna: playlist m3u8 (a veces servido como index.html) o embed. */
        @NonNull public final String url;
        /** El "title" del JSON si lo trae; null si no. */
        @Nullable public final String titulo;
        /** "mediatype" tal cual ("hls", "mp4", …); null si no venia. */
        @Nullable public final String mediatype;
        /** La clave que funciono, para el log. */
        @NonNull public final String clave;
        /** El JSON descifrado completo, por si el caller quiere mas campos. */
        @NonNull public final String texto;

        Contenido(@NonNull String url, @Nullable String titulo, @Nullable String mediatype,
                  @NonNull String clave, @NonNull String texto) {
            this.url = url;
            this.titulo = titulo;
            this.mediatype = mediatype;
            this.clave = clave;
            this.texto = texto;
        }
    }

    private EnlaceDrama() {}

    /**
     * true si la cadena es un enlace de Drama: trae un blob `Salted__` (cifrado), o
     * su fragmento `#...` es una URL a pelo (modo PLAIN, medido en el grupo peruano:
     * el propio player.js dice "raw http(s):// accepted as-is").
     */
    public static boolean esEnlace(@Nullable String cadena) {
        if (cadena == null) return false;
        if (cadena.contains(MAGIC_B64)) return true;
        String f = fragmento(cadena.trim());
        return f != null && (f.startsWith("http://") || f.startsWith("https://"));
    }

    /** Todo lo que va despues del ultimo '#'; null si el enlace no trae fragmento. */
    @Nullable
    static String fragmento(@NonNull String cadena) {
        int i = cadena.lastIndexOf('#');
        return i < 0 ? null : cadena.substring(i + 1).trim();
    }

    /**
     * Convierte lo que el usuario pega en la URL real que hay que abrir.
     * Devuelve null si NO es un enlace de Drama (y el caller sigue con su URL tal cual);
     * nunca lanza: si es un enlace pero no se puede abrir, tambien devuelve null.
     */
    @Nullable
    public static String desenrollar(@Nullable String cadena) {
        return desenrollar(cadena, null);
    }

    /** Como {@link #desenrollar(String)} pero probando ANTES la clave del config. */
    @Nullable
    public static String desenrollar(@Nullable String cadena, @Nullable String claveDrama) {
        Contenido c = abrir(cadena, claveDrama);
        return c == null ? null : c.url;
    }

    /** Descifra y parsea; null si no es un enlace de Drama o si ninguna clave publica sirve. */
    @Nullable
    public static Contenido abrir(@Nullable String cadena) {
        return abrir(cadena, null);
    }

    /**
     * Abre el enlace. `claveDrama` es el `webPlayerSecret` que el usuario guarda en la
     * fuente (ver FuenteCanales): se prueba PRIMERO porque es la que abre los grupos que
     * no usan el placeholder publico; si no hay o falla, siguen las cuatro candidates.
     */
    @Nullable
    public static Contenido abrir(@Nullable String cadena, @Nullable String claveDrama) {
        if (cadena == null) return null;
        String s = cadena.trim();
        String f = fragmento(s);
        if (f != null && (f.startsWith("http://") || f.startsWith("https://"))) {
            // Modo PLAIN: el fragmento YA es la URL; el player de Drama hace exactamente
            // esto (usa el fragmento tal cual, sin pasar por CryptoJS).
            return new Contenido(f, null, null, "plain", f);
        }
        if (!s.contains(MAGIC_B64)) return null;
        String blob = extraerBlob(s);
        java.util.List<String> claves = new java.util.ArrayList<>();
        if (claveDrama != null && !claveDrama.trim().isEmpty()) claves.add(claveDrama.trim());
        for (String k : CLAVES_PUBLICAS) if (!claves.contains(k)) claves.add(k);
        for (String clave : claves) {
            String texto = descifrar(blob, clave);
            if (texto == null) continue;
            String url = campoJson(texto, "url");
            if (url == null || url.isEmpty()) continue;   // padding valido por casualidad
            return new Contenido(url, campoJson(texto, "title"), campoJson(texto, "mediatype"),
                    clave, texto);
        }
        return null;
    }

    /**
     * Los pares del objeto `"swap":{...}` del JSON descifrado, en orden. Vacio si
     * el enlace no trae swap. Cada par (de, a) significa: a todo segmento que
     * empiece con `de`, cambiale ese prefijo por `a` ANTES de pedirlo — medido en
     * el panel `fas4` (2026-10-08): el playlist lista `k9x_*.js`, el reproductor
     * debe pedir `*.js` (404 crudo / 200 post-swap, frame 852x480 decodificado).
     * Lo consume {@link SwapHls} a traves de {@link DramaSwapDataSource}.
     */
    @NonNull
    public static java.util.List<String[]> paresSwap(@Nullable String textoJson) {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        if (textoJson == null) return out;
        int i = textoJson.indexOf("\"swap\"");
        if (i < 0) return out;
        int llave = textoJson.indexOf('{', i);
        if (llave < 0) return out;
        int pos = llave + 1;
        while (true) {
            int q = textoJson.indexOf('"', pos);
            int cierre = textoJson.indexOf('}', pos);
            if (q < 0 || (cierre >= 0 && q > cierre)) break;
            int finClave = cerrarCadena(textoJson, q);
            int dosPts = textoJson.indexOf(':', q);
            if (finClave < 0 || dosPts < 0 || (cierre >= 0 && dosPts > cierre)) break;
            String clave = desescapar(textoJson.substring(q + 1, finClave));
            int v = textoJson.indexOf('"', dosPts + 1);
            if (v < 0 || (cierre >= 0 && v > cierre)) break;
            int finValor = cerrarCadena(textoJson, v);
            if (finValor < 0) break;
            out.add(new String[]{clave, desescapar(textoJson.substring(v + 1, finValor))});
            pos = finValor + 1;
            int coma = textoJson.indexOf(',', pos);
            cierre = textoJson.indexOf('}', pos);
            if (coma < 0 || (cierre >= 0 && coma > cierre)) break;
            pos = coma + 1;
        }
        return out;
    }

    /** Indice del `"` que cierra la cadena abierta en `ini` (comillas escapadas con \\ incluidas). */
    private static int cerrarCadena(@NonNull String s, int ini) {
        for (int k = ini + 1; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '\\') k++;
            else if (c == '"') return k;
        }
        return -1;
    }

    /** El hash va despues del ultimo `#`; pero aceptamos el blob pegado a pelo. */
    @NonNull
    static String extraerBlob(@NonNull String cadena) {
        int alm = cadena.indexOf('#');
        String fragmento = alm >= 0 ? cadena.substring(alm + 1) : cadena;
        // algun panel lo pasa como #?h=... o con restos de query detras
        int corte = Math.min(Math.max(fragmento.indexOf('?'), 0), fragmento.length());
        int corte2 = Math.max(fragmento.indexOf('&'), 0);
        if (corte2 >= 0 && (corte == 0 || corte2 < corte)) corte = corte2;
        fragmento = (corte > 0 ? fragmento.substring(0, corte) : fragmento).trim();
        int inicio = fragmento.indexOf(MAGIC_B64);
        return inicio > 0 ? fragmento.substring(inicio) : fragmento;
    }

    /**
     * Descifrado completo: base64url -> `Salted__|salt(8)|AES-CBC`, con
     * EVP_BytesToKey(MD5) como derivador. Devuelve el UTF-8 libre o null si la
     * clave no es esta / el blob esta roto.
     */
    @Nullable
    public static String descifrar(@NonNull String blobB64Url, @NonNull String passphrase) {
        try {
            byte[] todo = Base64.getDecoder().decode(normalizarBase64(blobB64Url));
            if (todo.length < 32) return null;
            if (!new String(todo, 0, 8, StandardCharsets.US_ASCII).equals(MAGIC)) return null;
            byte[] salt = new byte[8];
            System.arraycopy(todo, 8, salt, 0, 8);
            byte[] cifrado = new byte[todo.length - 16];
            System.arraycopy(todo, 16, cifrado, 0, cifrado.length);
            if (cifrado.length == 0 || cifrado.length % 16 != 0) return null;

            byte[] material = evpBytesToKey(passphrase.getBytes(StandardCharsets.UTF_8), salt, 48);
            byte[] clave = new byte[32];
            byte[] iv = new byte[16];
            System.arraycopy(material, 0, clave, 0, 32);
            System.arraycopy(material, 32, iv, 0, 16);

            Cipher aes = Cipher.getInstance("AES/CBC/PKCS5Padding");
            aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(clave, "AES"), new IvParameterSpec(iv));
            byte[] claro = aes.doFinal(cifrado);
            // `toString(UTF8)` de CryptoJS no pasa con bytes sueltos: si la clave esta mal,
            // esto revienta o sale basura; el caller exige JSON de todas formas.
            return new String(claro, StandardCharsets.UTF_8);
        } catch (Exception esperado) {
            return null;   // BadPadding = clave incorrecta, el caso normal
        }
    }

    /** `K=MD5(p||s); D_i=MD5(D_{i-1}||p||s)` concatenado y recortado (OpenSSL/CryptoJS). */
    @NonNull
    static byte[] evpBytesToKey(@NonNull byte[] pass, @NonNull byte[] salt, int n) throws Exception {
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] d = new byte[0];
        while (out.size() < n) {
            md5.reset();
            md5.update(d);
            md5.update(pass);
            md5.update(salt);
            d = md5.digest();
            out.write(d, 0, d.length);
        }
        byte[] r = out.toByteArray();
        if (r.length > n) {
            byte[] recortado = new byte[n];
            System.arraycopy(r, 0, recortado, 0, n);
            return recortado;
        }
        return r;
    }

    /** base64url -> base64 normal, re-rellena el padding (CryptoJS escribe sin `=`). */
    @NonNull
    static String normalizarBase64(@NonNull String b64url) {
        String s = b64url.trim().replace('-', '+').replace('_', '/');
        int sobrante = s.length() % 4;
        if (sobrante == 2) s += "==";
        else if (sobrante == 3) s += "=";
        else if (sobrante == 1) s = s.substring(0, s.length() - 1);   // corrupto: recorta
        return s;
    }

    private static final Pattern CAMPO = Pattern.compile("\"(url|title|mediatype)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    @Nullable
    static String campoJson(@NonNull String json, @NonNull String campo) {
        Matcher m = CAMPO.matcher(json);
        while (m.find()) {
            if (m.group(1).equals(campo)) return desescapar(m.group(2));
        }
        return null;
    }

    @NonNull
    private static String desescapar(@NonNull String v) {
        if (v.indexOf('\\') < 0) return v;
        StringBuilder sb = new StringBuilder(v.length());
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c != '\\' || i + 1 >= v.length()) { sb.append(c); continue; }
            char s = v.charAt(++i);
            switch (s) {
                case 'n': sb.append('\n'); break;
                case 't': sb.append('\t'); break;
                case 'r': sb.append('\r'); break;
                case 'u':
                    if (i + 4 < v.length()) {
                        sb.append((char) Integer.parseInt(v.substring(i + 1, i + 5), 16));
                        i += 4;
                    }
                    break;
                default: sb.append(s);   // \" \\ \/
            }
        }
        return sb.toString();
    }
}
