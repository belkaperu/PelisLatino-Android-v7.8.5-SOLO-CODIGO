package dza.fut.paipo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unicidad de proposito: estos vectores son los que generé con node + crypto-js
 * 4.2.0 EXACTO que usan esos players (`docs/vectores-cryptojs.json`). Pasarlos
 * aqui prueba dos cosas a la vez: que el KDF (EVP_BytesToKey MD5, 1 ronda) esta
 * bien y que el AES-CBC de javax.crypto coincide bloque a bloque con el de
 * CryptoJS. Un KDF mal tambien round-tripsea consigo mismo; contra estos
 * vectores, no.
 */
public class EnlaceDramaTest {

    @Test
    public void vectorBeinDemo() {
        String claro = EnlaceDrama.descifrar(
                "U2FsdGVkX1_0KTbgsGLRFY9dsXLUEDN51cylQEbImiuBErTOsOywzkkTETfXjb_TkmuWgRtylaIy4PpEpaG7IjUI8trv2lzbbdFQfEJYX7NSuZ5PV-2wtFI7rNDFa7LniO6i9JBSt2udqynI6xHZbw",
                "_YOUR_SECRET");
        assertEquals("{\"url\":\"https://bein-xtra-bein.amagi.tv/playlist.m3u8\",\"title\":\"beIN XTRA (demo)\"}", claro);
    }

    @Test
    public void vectorClaveVacia() {
        assertEquals("hola", EnlaceDrama.descifrar("U2FsdGVkX18mY-L8EA6VmifRUBqE_vC6otyBkzCROYE", ""));
        assertNull(EnlaceDrama.descifrar("U2FsdGVkX18mY-L8EA6VmifRUBqE_vC6otyBkzCROYE", "no-es"));
    }

    @Test
    public void vectorConCabecerasInternas() {
        String claro = EnlaceDrama.descifrar(
                "U2FsdGVkX196BSP-qDGNFtzG6z1B6dx3zZowLLW7G9Kh-maoMNqRqnLnTy7pAVhSKbd_Gs3scY9t64Z52KQH-lReepWChcWCwchi9hviZ87n4Q-7yaki7kqA5CZi0233pqQxzaqBIsTAhnXzXGFVI6lmhbSRUxvFcoac3wWxiSwaWnPHhAf_rTa3iel7lTk0",
                "REPLACE_WITH_YOUR_SECRET");
        assertEquals("{\"url\":\"https://x/playlist.m3u8\",\"headers\":{\"Referer\":\"https://mi-proveedor.net/\",\"User-Agent\":\"Reproductor/1.0\"},\"ttl\":120}", claro);
    }

    @Test
    public void todosLosVectoresDelFicheroOracle() throws Exception {
        // docs/vectores-cryptojs.json es la autoridad externa (generada con node +
        // crypto-js 4.2.0). Si el KDF o el modo cambian, esto es lo primero que salta.
        java.io.File f = null;
        for (String cand : new String[]{"../docs/vectores-cryptojs.json", "docs/vectores-cryptojs.json",
                "../../docs/vectores-cryptojs.json"}) {
            java.io.File g = new java.io.File(cand);
            if (g.isFile()) { f = g; break; }
        }
        org.junit.Assume.assumeTrue("sin oracle en el path de test", f != null);
        String js = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        com.google.gson.JsonArray vec = com.google.gson.JsonParser.parseString(js).getAsJsonArray();
        assertEquals(5, vec.size());
        for (int i = 0; i < vec.size(); i++) {
            com.google.gson.JsonObject v = vec.get(i).getAsJsonObject();
            String claro = EnlaceDrama.descifrar(v.get("hash").getAsString(), v.get("clave").getAsString());
            assertEquals("vector " + i, v.get("texto").getAsString(), claro);
        }
    }

    @Test
    public void elEnlaceRealDelUsuarioSeAbreConLaClavePublica() {
        String enlace = "https://li.highperformanceconnect.shop/player/player.html?fit=contain"
                + "#U2FsdGVkX1_MXyIabLau-_weimn5FfVzKO4MlTQ-CaZO9UDx7JjLBIHUcty_lhWzHSiO9lCnG7JPhtckdcj4_gL-VtN7sNyCGSVLDJqX0lbeKQVarYKMsSQrGh6GXSjujNBK6wQ64HBMORAvjz9M08hMeymRNVxxWMg01EW5hQ8";
        assertTrue(EnlaceDrama.esEnlace(enlace));
        EnlaceDrama.Contenido c = EnlaceDrama.abrir(enlace);
        assertNotNull("debe abrirse con alguna de las claves publicas", c);
        assertEquals("https://sup6.superdatanet.cyou/pages/gddhlwvduhqa/index.html", c.url);
        assertEquals("hls", c.mediatype);
        assertEquals("REPLACE_WITH_YOUR_SECRET", c.clave);
        assertEquals("https://sup6.superdatanet.cyou/pages/gddhlwvduhqa/index.html",
                EnlaceDrama.desenrollar(enlace));
    }

    @Test
    public void noEsEnlaceNoTocaNada() {
        assertFalse(EnlaceDrama.esEnlace("https://bein-xtra-bein.amagi.tv/playlist.m3u8"));
        assertFalse(EnlaceDrama.esEnlace(null));
        assertNull(EnlaceDrama.desenrollar("https://x.net/a.m3u8"));
        assertNull(EnlaceDrama.abrir("#EXTM3U\n#EXTINF:10,\nhttps://x/a.ts"));
    }

    @Test
    public void blobSoloOEquivocadoTambienSeExtrae() {
        // el hash pegado a pelo (sin el `#` de delante)
        assertTrue(EnlaceDrama.esEnlace("U2FsdGVkX18mY-L8EA6VmifRUBqE_vC6otyBkzCROYE"));
        // fragmento con query detras (algun panel pega `#/watch` o `?h=`)
        String h = "U2FsdGVkX18mY-L8EA6VmifRUBqE_vC6otyBkzCROYE";
        assertEquals("hola", EnlaceDrama.descifrar(EnlaceDrama.extraerBlob("https://x/player.html#?a=1" + h), ""));
    }

    @Test
    public void campoJsonEscapes() {
        assertEquals("a\"b", EnlaceDrama.campoJson("{\"url\":\"a\\\"b\"}", "url"));
        assertEquals("C:\\x", EnlaceDrama.campoJson("{\"title\":\"C:\\\\x\"}", "title"));
        assertEquals("lí", EnlaceDrama.campoJson("{\"url\":\"l\\u00ed\"}", "url"));
        assertNull(EnlaceDrama.campoJson("{\"otro\":1}", "url"));
    }

    @Test
    public void kdfCoincideConOpenSslVectorOficial() throws Exception {
        // D0 = MD5(pass||salt) — la raiz del encadenado, comprobada aparte contra hashlib.
        byte[] material = EnlaceDrama.evpBytesToKey("_YOUR_SECRET".getBytes("UTF-8"),
                hex("f42936e0b062d115"), 48);
        assertEquals("e942f1bc5bf7cab92060b1005f6b7437"
                        + "5aff0bc383a798032b300d219c13f42f"
                        + "01c4fbe53bf2096a3985e78acac00937", hex(material));
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return out;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    @Test
    public void fragmentoPlainEsLaUrlDirectamente() {
        // modo medido del grupo peruano: `player.html#https://190.93.224.42/...` — el
        // propio player.js usa el fragmento tal cual cuando empieza por http; aqui igual.
        String enlace = "https://pe.example.com/player/player.html?fit=contain"
                + "#https://190.93.224.42/live/canal-07/index.html";
        assertTrue(EnlaceDrama.esEnlace(enlace));
        EnlaceDrama.Contenido c = EnlaceDrama.abrir(enlace);
        assertNotNull(c);
        assertEquals("https://190.93.224.42/live/canal-07/index.html", c.url);
        assertEquals("plain", c.clave);
        // una URL suelta SIN fragmento sigue sin ser un enlace (no se toca nada)
        assertFalse(EnlaceDrama.esEnlace("https://x.net/a.m3u8"));
        assertNull(EnlaceDrama.desenrollar("https://x.net/a.m3u8"));
    }

    @Test
    public void webPlayerSecretPropioAbreLoQueLaPublicaNoAlcanza() {
        // docs/vectores-clave-drama.json: cifrado con node+crypto-js 4.2.0 y clave NO
        // publica, simulando el `webPlayerSecret` del config. Sin clave extra, NULL.
        String hash = "U2FsdGVkX19eYHGCk6S1xts-HApEdeIX8zFIS7rzojMaViKdl0X3DtF3GGsujJ6Z"
                + "dVdZlKagSQQWUYh6-lqs1FQQv6q1Dh3L217QEOYO59yFEgsAQKaWN52CDz0SGXsVfvcO"
                + "0MIgh2MJT5FONeCdiEy_ZRv3BggkvxTSuoMdNBPchgmVRXglQIBoxKO6oiMbtCLkqWX9"
                + "V0gVrhLV3np-SA";
        assertNull(EnlaceDrama.abrir(hash));
        EnlaceDrama.Contenido c = EnlaceDrama.abrir(hash, "SECRETO-PRUEBA-9F2C");
        assertNotNull("la clave del config debe abrir lo que la publica no", c);
        assertEquals("https://sp.highperformanceconnect.shop/sport/canal-01/index.m3u8", c.url);
        assertEquals("Grupo Arabe (vector prueba)", c.titulo);
        assertEquals("SECRETO-PRUEBA-9F2C", c.clave);
        // y la clave extra no estropea los enlaces publicos (se prueba primero, cae despues)
        assertNotNull(EnlaceDrama.abrir("https://h/player.html#U2FsdGVkX1_MXyIabLau-_weimn5FfVzKO4MlTQ"
                + "-CaZO9UDx7JjLBIHUcty_lhWzHSiO9lCnG7JPhtckdcj4_gL-VtN7sNyCGSVLDJqX0lbeKQVarYKMsSQrGh6"
                + "GXSjujNBK6wQ64HBMORAvjz9M08hMeymRNVxxWMg01EW5hQ8", "no-existe-esta"));
    }

    @Test
    public void listaPegadaUsaLaClaveAlImportar() {
        String hash = "U2FsdGVkX19eYHGCk6S1xts-HApEdeIX8zFIS7rzojMaViKdl0X3DtF3GGsujJ6Z"
                + "dVdZlKagSQQWUYh6-lqs1FQQv6q1Dh3L217QEOYO59yFEgsAQKaWN52CDz0SGXsVfvcO"
                + "0MIgh2MJT5FONeCdiEy_ZRv3BggkvxTSuoMdNBPchgmVRXglQIBoxKO6oiMbtCLkqWX9"
                + "V0gVrhLV3np-SA";
        String enlace = "https://h/player/player.html?fit=contain#" + hash;
        java.util.List<Canal> sin = ListaPegada.desdeTexto(enlace);
        assertEquals("sin clave entra igual, con la URL cruda (se desenrolla al abrir)",
                enlace, sin.get(0).options.get(0).url);
        java.util.List<Canal> con = ListaPegada.desdeTexto(enlace, "SECRETO-PRUEBA-9F2C");
        assertEquals("https://sp.highperformanceconnect.shop/sport/canal-01/index.m3u8",
                con.get(0).options.get(0).url);
        assertEquals("Grupo Arabe (vector prueba)", con.get(0).title);
    }
}
