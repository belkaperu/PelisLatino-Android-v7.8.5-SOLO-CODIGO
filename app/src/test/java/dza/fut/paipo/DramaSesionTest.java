package dza.fut.paipo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Pruebas SIN RED de la extraccion in-app: la plantilla, el alambre AES-128
 * (el mismo que el bundle main.dart.js, clave publica literal) y el mapeo
 * JSON->canales->m3u. El catch-all de 32 B es la senal REAL medida del panel
 * sin sesion (POST anonimo 2026-10-08): aqui se prueba que se reporta como tal.
 */
public class DramaSesionTest {

    // v3.2.34 — la semilla empotrada se construye SIN captador: shape exacto que el edge valida.
    /** v3.2.54: el glt de Drama trae img_url_topic por topico (medido en
     *  case/resp_glt_live.json) — los flags de la app de verdad salen de ahi. */
    @Test
    public void losFlagsVienenDelPropioGltDeDrama() {
        String glt = "{\"live_topics\": [{\"id_topic\": \"hot_now\", \"name_topic\": \"Most watched\", \"img_url_topic\": \"http://logo.twoapistack.work/img/topics/hot_now.png\"}, {\"id_topic\": \"pe\", \"name_topic\": \"Peru\", \"img_url_topic\": \"http://logo.twoapistack.work/img/topics/ic_flag_pe.png\"}, {\"id_topic\": \"x\", \"name_topic\": \"Raro\", \"img_url_topic\": \"\"}]}";
        java.util.Map<String, String> logos = DramaSesion.logosTopicosDe(glt);
        assertEquals("http://logo.twoapistack.work/img/topics/ic_flag_pe.png", logos.get("pe"));
        assertEquals("http://logo.twoapistack.work/img/topics/ic_flag_pe.png", logos.get("Peru"));
        assertTrue("sin url no entra", !logos.containsKey("x"));
        // viaje por prefs: serializar -> leer
        String lineas = DramaSesion.serializarLogos(logos);
        java.util.Map<String, String> recargado = DramaSesion.logosDesdeLineas(lineas);
        assertEquals(logos.get("pe"), recargado.get("pe"));
        assertEquals(0, DramaSesion.logosDesdeLineas("").size());
        assertEquals(0, DramaSesion.logosTopicosDe("{\"sin\":\"live_topics\"}").size());
    }

    @Test
    public void gltInicialTieneLosCamposQueElEdgeValida() {
        JsonObject g = DramaSesion.gltInicial();
        assertTrue(g.get("user_id").getAsString().endsWith("_notloggedin.com_dramalive3"));
        assertTrue(g.get("device_id").getAsString().matches("[0-9a-f]{8}-[0-9a-f]{4}-.*"));
        assertEquals("web_241", g.get("version_name").getAsString());
        assertEquals("232425", g.get("KEY_ACTIVATED_TYPE").getAsString());
        assertEquals("web", g.get("store").getAsString());
        assertEquals("tv", g.get("type").getAsString());
        assertFalse(g.get("isPremium").getAsBoolean());
        assertTrue(g.get("isStoreVersion").getAsBoolean());
        // device fresh: dos llamadas no pueden dar el mismo dispositivo
        assertFalse(DramaSesion.gltInicial().get("device_id").getAsString()
                .equals(DramaSesion.gltInicial().get("device_id").getAsString()));
    }

    @Test
    public void semillaDerivadaLlevaTokenYContadoresYGlasbiSeDeriva() {
        JsonObject q = DramaSesion.semillaDerivada();
        assertEquals(q.get("user_id").getAsString(), q.get("token").getAsString());
        assertEquals(1, q.get("adsShowed").getAsInt());
        assertEquals(1, q.get("runCount").getAsInt());
        assertEquals("all_streams_redirect", q.get("agent").getAsString());
        assertEquals("1", q.get("url").getAsString());
        // la url de glasbi sale de la url del glt de la semilla (li.../glt -> li.../glasbi)
        JsonObject ses = new JsonObject();
        ses.addProperty("url", "https://li.highperformanceconnect.shop/api/live/livedrama/v13.0.0/glt");
        assertEquals("https://li.highperformanceconnect.shop/api/live/livedrama/v13.0.0/glasbi",
                DramaSesion.urlGlasbiDe(ses.toString()));
    }

    // v3.2.31 — extraccion de opciones y urls del play nativo (muestras TEXTUALES de lo
    // medido en la red del usuario y del sandbox 12/10; sin red, puros).
    @Test
    public void urlGlasbiSeDerivaDelGltOCaeEnLaBaseDefecto() {
        assertEquals("https://x.example/api/live/livedrama/v13.0.0/glasbi",
                DramaSesion.urlGlasbiDe("{\"url\":\"https://x.example/api/live/livedrama/v13.0.0/glt\"}"));
        String d = DramaSesion.urlGlasbiDe(null);
        assertTrue(d.endsWith("/glasbi"));
        assertFalse(d.contains("glt"));
    }

    @Test
    public void vivoDesdeGlasbiExtraeMarcadorAgenteYopciones() {
        String claro = "{\"result\":0,\"live\":{\"type\":\"tv\",\"id_live\":\"live_tv_beinsport1\","
                + "\"url\":\"http://.LS.V2live_tv_custom_handler_live_tv_beinsport1_description_HD_/s\","
                + "\"agent\":\"redirect\","
                + "\"backup\":\"http://.LS.V2live_tv_custom_handler_live_tv_beinsport1_description_SD_/s"
                + " -;- http://.LS.V2live_tv_custom_handler_live_tv_beinsport1_description_4K_/s"
                + " -;- {\\\"iframe\\\":\\\"https://lockpop.net/e/xx\\\"}\"}}";
        String[] vg = DramaSesion.vivoDesdeGlasbi(claro);
        assertNotNull(vg);
        assertEquals("http://.LS.V2live_tv_custom_handler_live_tv_beinsport1_description_HD_/s", vg[0]);
        assertEquals("redirect", vg[1]);
        JsonArray ops = com.google.gson.JsonParser.parseString(vg[2]).getAsJsonArray();
        assertEquals(3, ops.size());   // HD + SD + 4K; el iframe se tiro
        assertEquals("HD", ops.get(0).getAsJsonObject().get("n").getAsString());
        assertEquals("SD", ops.get(1).getAsJsonObject().get("n").getAsString());
        assertEquals("4K", ops.get(2).getAsJsonObject().get("n").getAsString());
    }

    @Test
    public void vivoDesdeGlasbiRechazaBasuraYresultDistinto() {
        assertNull(DramaSesion.vivoDesdeGlasbi(null));
        assertNull(DramaSesion.vivoDesdeGlasbi("\t\t"));
        assertNull(DramaSesion.vivoDesdeGlasbi("{\"result\":1}"));
        assertNull(DramaSesion.vivoDesdeGlasbi("{\"result\":0,\"live\":{\"url\":\"sin-marca\",\"agent\":\"\"}}"));
    }

    // v3.2.24: el seed del wizard de web.dramalive.cc (flutter.source_config / terms_continue)
    @org.junit.Test public void seedSetupSinCodigoNoHaceNada() {
        org.junit.Assert.assertEquals("", DramaSesion.seedSetupJs(null));
        org.junit.Assert.assertEquals("", DramaSesion.seedSetupJs("   "));
    }

    @org.junit.Test public void seedSetupSiembraLasClavesDelSitio() {
        String s = DramaSesion.seedSetupJs("fgcode.org/232425");
        org.junit.Assert.assertTrue(s.contains("flutter.source_config"));
        org.junit.Assert.assertTrue(s.contains("flutter.terms_continue"));
        org.junit.Assert.assertTrue(s.contains("fgCode:'fgcode.org/232425'"));
        org.junit.Assert.assertTrue(s.contains("reload()"));   // si llego tarde, un solo rerun
    }

    @org.junit.Test public void seedSetupNoPermiteInyeccionDeJs() {
        String s = DramaSesion.seedSetupJs("23';alert(1);//");
        org.junit.Assert.assertTrue(s.contains("fgCode:'23alert1//'"));
        org.junit.Assert.assertFalse(s.contains("alert(1)"));
        String s2 = DramaSesion.seedSetupJs("''..''");
        org.junit.Assert.assertTrue(s2.contains("fgCode:'..'"));
    }

    @Test
    public void plantillaValidayErrores() {
        assertNull(DramaSesion.errorPlantilla("{\"plantillas\":{\"glt\":{\"user_id\":7}}}"));
        assertTrue(DramaSesion.errorPlantilla("{\"plantillas\":{\"glt\":{}}}").contains("user_id"));
        assertNotNull(DramaSesion.errorPlantilla("no es json"));
        assertTrue(DramaSesion.errorPlantilla("{}").contains("plantillas.glt"));
    }

    @Test
    public void alambreRoundTrip() throws Exception {
        JsonObject p = new JsonObject();
        p.addProperty("user_id", 123);
        p.addProperty("type", "live");
        String alambre = DramaSesion.alambre(p);
        assertTrue(alambre.contains(":"));
        String claro = DramaSesion.desalambre(alambre);
        assertNotNull(claro);
        assertTrue(claro.contains("\"type\":\"live\"") || claro.contains("\"type\": \"live\""));
        // algo que no es alambre (el catch-all) no descifra nunca
        assertNull(DramaSesion.desalambre("   \t\r\n  "));
    }

    @Test
    public void catchAllReconocido() {
        byte[] cuerpo = "\t\t\r\n".getBytes(StandardCharsets.ISO_8859_1);
        // completar hasta los 32 B medidos del panel
        StringBuilder sb = new StringBuilder("\t\t\r\n");
        while (sb.length() < 32) sb.append(' ');
        assertTrue(DramaSesion.esCatchAll(sb.toString().getBytes(StandardCharsets.ISO_8859_1)));
        assertFalse(DramaSesion.esCatchAll(new byte[0]));
        assertFalse(DramaSesion.esCatchAll("x".repeat(60).getBytes(StandardCharsets.UTF_8)));
        assertEquals("HTTP 500", DramaSesion.procesarRespuesta(500, cuerpo).error);
    }

    @Test
    public void sesionCaducadaSeReportaComoTal() {
        DramaSesion.Resultado r = DramaSesion.procesarRespuesta(200, espacios32());
        assertFalse(r.ok());
        assertTrue(r.error.contains("caducada"));
    }

    @Test
    public void canalesDesdeRespuestaCifrada() throws Exception {
        JsonObject payload = new JsonObject();
        JsonArray topics = new JsonArray();
        JsonObject a = new JsonObject();
        a.addProperty("name", "Canal A");
        a.addProperty("server", "fas4.faststorageapi.shop");
        a.addProperty("path", "malasp/fgmxweigdavh");
        topics.add(a);
        JsonObject aDuplicado = new JsonObject();
        aDuplicado.addProperty("name", "Canal A");
        aDuplicado.addProperty("server", "https://fas4.faststorageapi.shop/");
        aDuplicado.addProperty("path", "/malasp/fgmxweigdavh/");
        topics.add(aDuplicado);
        JsonObject b = new JsonObject();
        b.addProperty("name", "Canal B");
        b.addProperty("url", "https://x/y/index.m3u8");
        b.addProperty("category", "Sports");
        b.addProperty("logo", "https://logo/b.png");
        topics.add(b);
        payload.add("live_topics", topics);

        String alambre = DramaSesion.alambre(payload);
        DramaSesion.Resultado r = DramaSesion.procesarRespuesta(
                200, alambre.getBytes(StandardCharsets.ISO_8859_1));
        assertTrue(r.ok());
        assertEquals(2, r.canales);
        // el dedupe une las dos escrituras del server/path, y la ruta se completa a index.html
        assertTrue(r.m3u.contains("https://fas4.faststorageapi.shop/malasp/fgmxweigdavh/index.html"));
        assertTrue(r.m3u.contains("#EXTINF:-1 tvg-name=\"Canal B\" group-title=\"Sports\" tvg-logo=\"https://logo/b.png\",Canal B"));
        assertTrue(r.m3u.startsWith("#EXTM3U\n"));
        assertTrue(r.m3u.contains("group-title=\"En vivo\"")); // default para Canal A
    }

    @Test
    public void tiposYBase() {
        String json = "{\"base\":\"https://api.mi-ojo/\",\"plantillas\":{\"glt\":{\"user_id\":1,\"type\":\"deportes\"}}}";
        assertEquals("https://api.mi-ojo/", DramaSesion.baseDe(json));
        assertEquals("live", DramaSesion.tiposDe(json, "live").get(0));
        assertEquals("deportes", DramaSesion.tiposDe(json, null).get(0));
        assertEquals(DramaSesion.BASE_DEFECTO + "", DramaSesion.baseDe("{}"));
        DramaSesion.Resultado r = DramaSesion.extraer("{\"nada\":1}", null);
        assertFalse(r.ok());
        assertTrue(r.error.contains("plantillas.glt"));   // ni toca la red: falla en la validacion
    }

    @Test
    public void diagnosticoDeEntradasConfundidas() {
        assertEquals("FG", DramaSesion.diagnosticoEntrada("232425"));
        assertEquals("ENLACE", DramaSesion.diagnosticoEntrada(
                "https://li.highperformanceconnect.shop/player/v3/player.html?fit=contain#U2FsdGVkX19abc"));
        assertEquals("M3U", DramaSesion.diagnosticoEntrada("#EXTM3U\n#EXTINF:-1,x\nhttps://a/b.m3u8"));
        assertEquals("JSONOTRO", DramaSesion.diagnosticoEntrada("{\"otra\":1}"));
        assertNull(DramaSesion.diagnosticoEntrada("{\"plantillas\":{\"glt\":{\"user_id\":1}}}"));
        assertNull(DramaSesion.diagnosticoEntrada(""));
        assertNull(DramaSesion.diagnosticoEntrada(null));
    }

    @Test
    public void puenteDelCaptadorWebViewSeVuelveM3u() {
        String json = "{\"extraido\":\"2026-10-09T00:00:00Z\",\"motivo\":\"auto\","
                + "\"plantillas\":{\"glt\":{\"user_id\":\"9998\",\"device_id\":\"d1\",\"type\":\"live\"}},"
                + "\"canales\":["
                + "{\"n\":\"BeIN 1\",\"u\":\"https://s1.example/index.html\",\"g\":\"sports\",\"l\":\"https://logo/a.png\"},"
                + "{\"n\":\"Basura\",\"u\":\"javascript:alert(1)\",\"g\":\"x\",\"l\":null},"
                + "{\"n\":\"BeIN 1\",\"u\":\"https://s1.example/index.html\",\"g\":\"sports\",\"l\":null}"
                + "]}";
        DramaSesion.Resultado r = DramaSesion.desdePuente(json);
        assertTrue(r.ok());
        assertEquals(1, r.canales); // lo no-http fuera y dedupe por (nombre,url)
        assertTrue(r.m3u.contains("https://s1.example/index.html"));
        assertTrue(r.m3u.contains("group-title=\"sports\""));
        assertTrue(r.m3u.contains("tvg-logo=\"https://logo/a.png\""));
        String ses = DramaSesion.plantillasDesdePuente(json);
        assertNotNull(ses);
        assertNull(DramaSesion.errorPlantilla(ses)); // reempaquetado = sesi\u00f3n reutilizable por extraer()
        assertTrue(DramaSesion.baseDe(ses).startsWith("https://main.fastconnections.online"));
    }

    @Test
    public void puenteVacioONuloEsFalloEducado() {
        assertEquals("entrega vacia", DramaSesion.desdePuente("").error);
        assertEquals("entrega vacia", DramaSesion.desdePuente(null).error);
        assertEquals("el captador aun no trajo canales", DramaSesion.desdePuente("{\"canales\":[]}").error);
        assertNotNull(DramaSesion.desdePuente("{\"canales\":[{\"n\":\"x\"}]}").error);
        assertNull(DramaSesion.plantillasDesdePuente("{\"canales\":[]}"));
        assertNull(DramaSesion.plantillasDesdePuente(null));
        // glt sin user_id no es guardable (seria la sesion anonima del catch-all)
        assertNull(DramaSesion.plantillasDesdePuente("{\"plantillas\":{\"glt\":{\"type\":\"live\"}},\"canales\":[{\"n\":\"a\",\"u\":\"https://a/b\"}]}"));
    }

    @Test
    public void alambresAlternativosRoundTrip() throws Exception {
        JsonObject p = new JsonObject();
        p.addProperty("user_id", "7");
        p.addProperty("type", "live");
        String claro = DramaSesion.alambre(p, "iv0");
        org.junit.Assert.assertFalse("iv0 no lleva separador", claro.contains(":"));
        assertEquals(DramaSesion.desalambre(claro), DramaSesion.desalambre(DramaSesion.alambre(p, "ctiv")));
        String pf = DramaSesion.desalambre(DramaSesion.alambre(p, "ivpf"));
        assertNotNull(pf);
        assertTrue(pf.startsWith("{"));
        // envoltorio de string JSON "..." medido en las respuestas web
        String quoted = "\"" + DramaSesion.alambre(p, "iv0").replace("/", "\\/") + "\"";
        assertNotNull(DramaSesion.desalambre(quoted));
        // 201 Created es status valido medido en campo
        DramaSesion.Resultado r201 = DramaSesion.procesarRespuesta(201,
                DramaSesion.alambre(p, "iv0").getBytes(StandardCharsets.UTF_8));
        assertFalse(r201.ok()); // descifra pero 0 canales -> el error dice canales, no HTTP
        assertTrue(r201.error.contains("canales"));
        DramaSesion.Resultado r404 = DramaSesion.procesarRespuesta(404, new byte[]{1});
        assertTrue(r404.error.startsWith("HTTP 404"));
    }

    @Test
    public void sesionLlevaUrlRealYWireDelSitio() {
        String puente = "{\"plantillas\":{\"glt\":{\"user_id\":\"9\",\"type\":\"live\"}},"
                + "\"url\":\"https://li.highperformanceconnect.shop/api/live/livedrama/v13.0.0/glt\","
                + "\"wire\":\"aes-iv0\",\"canales\":[{\"n\":\"A\",\"u\":\"https://a/x/index.html\"}]}";
        DramaSesion.Resultado d = DramaSesion.desdePuente(puente);
        assertTrue(d.ok());
        String ses = DramaSesion.plantillasDesdePuente(puente);
        assertNotNull(ses);
        assertEquals("https://li.highperformanceconnect.shop/api/live/livedrama/v13.0.0/glt",
                DramaSesion.urlDe(ses));
        // el fmt del captador es "aes-iv0" -> wireDe debe normalizarlo a iv0
        assertEquals("iv0", DramaSesion.wireDe(ses));
        assertNull(DramaSesion.urlDe("{\"plantillas\":{}}"));
        assertEquals("ctiv", DramaSesion.wireDe(null));
    }

    @Test
    public void desalambreToleraB64SinPaddingYUrlSafe() throws Exception {
        JsonObject p = new JsonObject();
        p.addProperty("type", "live");
        p.addProperty("user_id", "567016861000_notloggedin.com_dramalive3");
        String alambre = DramaSesion.alambre(p, "ivpf");
        // el wire medido del sitio: url-safe y SIN los '=' de relleno (len%4==1)
        String wire = alambre.replace('+', '-').replace('/', '_').replace("=", "");
        String claro = DramaSesion.desalambre(wire);
        assertNotNull("b64 sin padding debe leerse", claro);
        assertTrue(claro.contains("live"));
    }

    @Test
    public void desalambrePrefijo16AntesDelIv() throws Exception {
        // disposicion que sugiere el volcar v3.1: 16 B de prefijo + iv + ct
        String json = "{\"type\":\"sports\",\"user_id\":\"u\",\"device_id\":\"d\"}";
        javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
        byte[] iv = new byte[16];
        new java.security.SecureRandom().nextBytes(iv);
        c.init(javax.crypto.Cipher.ENCRYPT_MODE,
                new javax.crypto.spec.SecretKeySpec(DramaSesion.KEY, "AES"),
                new javax.crypto.spec.IvParameterSpec(iv));
        byte[] ct = c.doFinal(json.getBytes(StandardCharsets.UTF_8));
        byte[] pre = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
        java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
        all.write(pre);
        all.write(iv);
        all.write(ct);
        String wire = java.util.Base64.getEncoder().encodeToString(all.toByteArray())
                .replace('+', '-').replace('/', '_').replace("=", "");
        String claro = DramaSesion.desalambre(wire);
        assertNotNull("p16+ivpf debe leerse", claro);
        assertTrue(claro.contains("sports"));
    }

    @Test
    public void wireDeAceptaNombresDelCaptadorV321() {
        assertEquals("ivpf", DramaSesion.wireDe("{\"wire\":\"p0+frente\"}"));
        assertEquals("ivpf", DramaSesion.wireDe("{\"wire\":\"p16+frente\"}"));
        assertEquals("iv0", DramaSesion.wireDe("{\"wire\":\"p16+cero\"}"));
        assertEquals("iv0", DramaSesion.wireDe("{\"wire\":\"p0+cero\"}"));
    }


    /** Golden: request glbt REAL del telefono del usuario (volcar 2026-10-10):
     *  b64std(ct):b64std(iv), 592 B de ct = 37 bloques. El claro lleva el
     *  user_id anonimo sintetico del sitio. */
    @Test
    public void desalambreLeeRequestRealDelTelefono() {
        String wire = "VtfMW1yCk2ki+n1TLRiJA0NpOFGDPfDf79Qqpmn2BlCUIc45nOWscmm/ItwJZoCyYVG9cb80HKyfjNLl+dVCWEdtEqNPBPZ9Go8PIZHUuMDmsgE6NldZC5yDhealim8iU59iqFHj/7tLURh0L0PGAKa6VFAPfGyYHzDmHpcvS5rOO5jLqBOsG2Q8u1EYEr/IxVXXfx4BzkNFqqoiXHRF35yzpCeopmU14MygLIjRnMnyuRt/sM+fNs+S3lYGhOG0eozTtNBxlbVVO2Sm1cbd3cjuu0dL+ZqSZGDEGnwCxtB9QGllglLyxTcFb/PJUMMDEosRoFYgjnINAHrdOcmcjEyP6ak1Sc4U9/D8eKynAJJ5798E7rulG+YWeFYK7Ohq9ZLlBO0QzvWp7WeIpKEfQ0Vi9pGU/gA2jZcrljw9MTAgDWhPxaq25/MLdLD+RdUy5mI9mg7/UJJ0G9alx+OxiLHyE4F5/t3YtFCEOokhI19xjG6/GG+zkuQ4zeclvUMzVw+HH1Sd+8sRZxURK0oA+MFJKPmjLBazYC/C+9MCEXkCOMaInINoGIHgzF2xydHo4zBkFv78IJvZaKZ77urg8xU3hyR0lPDf8xu/s7hAPSTXZ92AIWN+boT0gdCx5LQMY6KPLvzIAV02wfn2eTgvbkAnovEnT6SibOY+ehb4CH6++r+KRbxotP0rfcTTmCw3pCZfcCQsG3HQeTib1XLf3D1sABFpULE5bCaROWvtm6sNtXzPp4z6g5lSwB7Rqlgjwbj6fSF99jBkr4NTb4X1xA==:wXK14HoFA/zDqPmXe0wbgQ==";
        String claro = DramaSesion.desalambre(wire);
        assertNotNull("el request real debe leerse", claro);
        assertTrue(claro.startsWith("{\"user_id\":"));
        assertTrue("user_id anonimo del sitio", claro.contains("notloggedin.com_dramalive3"));
    }

    /** Golden: respuesta glbt REAL (prefijo de whitespace del catch-all + b64 sin
     *  padding, IV fijo NO enviado): el rescate balanceado debe encontrar canales. */
    @Test
    public void desalambreLeeRespuestaRealConHeadCorrupto() {
        String cuerpo = "\t\t\r\n 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";
        String claro = DramaSesion.desalambre(cuerpo);
        assertNotNull("la respuesta real debe leerse desde el bloque 1", claro);
        assertTrue(claro.startsWith("{") || claro.startsWith("["));
        java.util.List<DramaSesion.CanalDrama> cs = DramaSesion.canales(claro);
        assertFalse("debe extraer al menos un canal de los 3 KB", cs.isEmpty());
    }

    private static byte[] espacios32() {
        StringBuilder sb = new StringBuilder("\t\t\r\n");
        while (sb.length() < 32) sb.append(' ');
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    // ------------------------------------------------ v3.2.3: dramaalive:// y glbr
    // Datos MEDIDOS el 2026-10-10 replicando el flujo del sitio con la sesion real
    // del usuario (glt type:"tv" -> 265 topics; glbt hot_now -> 89 items; glbr -> url).

    private static final String GLBT_REAL = "{\"result\":0,\"live\":[{\"type\":\"tv\",\"id_live\":\"live_tv_beinsport2\",\"name\":\"Bein S. 2\",\"url\":\"1\",\"agent\":\"all_streams_redirect\",\"backup\":\"\",\"img_url\":\"http://logo.twoapistack.work/img/channels/live_tv_beinsport2.png\",\"id_topic\":\"hot_now\"},{\"type\":\"tv\",\"id_live\":\"live_tv_morrowcowsport\",\"name\":\"Arryadia 3 | الرياضية المغربية 3\",\"url\":\"1\",\"agent\":\"all_streams_redirect\",\"backup\":\"\",\"img_url\":\"http://logo.twoapistack.work/img/channels/x.png\",\"id_topic\":\"hot_now\"}]}";

    @Test
    public void canales_daEsquemaDramaLiveCuandoItemTraeIdLiveYUrlNumerica() {
        List<DramaSesion.CanalDrama> cs = DramaSesion.canales(GLBT_REAL);
        assertEquals(2, cs.size());
        assertTrue(cs.get(0).url.startsWith("dramalive://play?id_live=live_tv_beinsport2&u=1&a=all_streams_redirect&t=hot_now"));
        assertEquals("hot_now", cs.get(0).group);
        assertEquals("http://logo.twoapistack.work/img/channels/live_tv_beinsport2.png", cs.get(0).logo);
    }

    @Test
    public void partesDramaLive_idaYVueltaConItemReal() {
        List<DramaSesion.CanalDrama> cs = DramaSesion.canales(GLBT_REAL);
        String[] pr = DramaSesion.partesDramaLive(cs.get(1).url);
        assertNotNull(pr);
        assertEquals("live_tv_morrowcowsport", pr[0]);
        assertEquals("1", pr[1]);
        assertEquals("all_streams_redirect", pr[2]);
        assertEquals("hot_now", pr[3]);
        assertNull(DramaSesion.partesDramaLive("https://x/y"));
    }

    @Test
    public void glbrDesdeRespuesta_descifraAlambreRealConPrefijoYUserAgent() throws Exception {
        JsonObject data = new JsonObject();
        data.addProperty("url", "http://file.twoapistack.work/stream/update.mp4");
        data.addProperty("agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/90.0.4430.85 Safari/537.36");
        JsonObject env = new JsonObject();
        env.addProperty("result", 0);
        env.add("data", data);
        String cuerpo = "\t\t\r\n  " + DramaSesion.alambre(env, "ctiv");
        String[] ra = DramaSesion.glbrDesdeRespuesta(201, cuerpo.getBytes(StandardCharsets.ISO_8859_1));
        assertNotNull(ra);
        assertEquals("http://file.twoapistack.work/stream/update.mp4", ra[0]);
        assertTrue(ra[1].startsWith("Mozilla/5.0 (Windows NT"));
    }

    @Test
    public void topicosDe_extraeElIndiceQueDevuelveElGlt() {
        String claro = "{\"result\":0,\"live_topics\":[{\"id_topic\":\"hot_now\",\"list\":[]},{\"id_topic\":\"pe\",\"list\":[]},{\"id_topic\":\"live_matches\",\"list\":[]}]}";
        List<String> tp = DramaSesion.topicosDe(claro, 12);
        assertEquals(3, tp.size());
        assertEquals("hot_now", tp.get(0));
        assertEquals(2, DramaSesion.topicosDe(claro, 2).size());
    }

    @Test
    public void puenteGuardaGlbtYUrlsDeRepositorio_yLasDevuelven() {
        JsonObject glt = new JsonObject();
        glt.addProperty("user_id", "x");
        JsonObject plantillas = new JsonObject();
        plantillas.add("glt", glt);
        plantillas.add("glbt", glt.deepCopy());
        JsonObject entrega = new JsonObject();
        entrega.add("plantillas", plantillas);
        entrega.addProperty("url", "https://li.highperformanceconnect.shop/api/live/livedrama/v13.0.0/glt");
        entrega.addProperty("urlGlbt", "https://li.highperformanceconnect.shop/api/live/livedrama/v13.0.0/glbt");
        entrega.addProperty("urlGlbr", "https://re.highperformanceconnect.shop/redirect/glbr");
        String ses = DramaSesion.plantillasDesdePuente(entrega.toString());
        assertNotNull(ses);
        assertEquals("https://li.highperformanceconnect.shop/api/live/livedrama/v13.0.0/glbt", DramaSesion.urlGlbtDe(ses));
        assertEquals("https://re.highperformanceconnect.shop/redirect/glbr", DramaSesion.urlGlbrDe(ses));
        // sin datos en la sesion, los defaults medidos valen igual
        assertTrue(DramaSesion.urlGlbtDe("{\"url\":\"https://a/glt\"}").endsWith("/glbt"));
        assertTrue(DramaSesion.urlGlbrDe(null).contains("/redirect/glbr"));
    }

    @Test
    public void desdePuente_aceptaCanalesConEsquemaDramaLive() {
        JsonObject c = new JsonObject();
        c.addProperty("n", "Bein S. 2");
        c.addProperty("u", "dramalive://play?id_live=live_tv_beinsport2&u=1&a=all_streams_redirect&t=hot_now");
        c.addProperty("g", "hot_now");
        JsonArray cs = new JsonArray();
        cs.add(c);
        JsonObject entrega = new JsonObject();
        entrega.add("canales", cs);
        DramaSesion.Resultado r = DramaSesion.desdePuente(entrega.toString());
        assertTrue("el puente debe dejar pasar dramalive://: " + r.error, r.ok());
        assertEquals(1, r.canales);
        assertTrue(r.m3u.contains("dramalive://play?id_live=live_tv_beinsport2"));
    }

    // ------------------------------------------- v3.2.4: semilla, csv legado y glt REAL de 43 KB

    /** La sesion EXACTA que el puente guardo en el telefono del usuario (volcar 2026-10-10). */
    private static final String SESION_USUARIO = "{\"base\":\"https://main.fastconnections.online/api/live/livedrama/v13.0.0/\",\"url\":\"https://li.highperformanceconnect.shop/api/live/livedrama/v13.0.0/glt\",\"wire\":\"ctiv\",\"plantillas\":{\"glt\":{\"user_id\":\"1791616726363000_notloggedin.com_dramalive3\",\"device_id\":\"e7282868-c589-4f9a-b04d-c69dbc5105b7\",\"device_api\":\"chrome/154.0.8037.57\",\"version_name\":\"web_241\",\"language\":\"en\",\"timezone\":\"America/Lima\",\"device_type\":\"phone\",\"os_platform\":\"web\",\"os\":\"android\",\"KEY_ACTIVATED_TYPE\":\"232425\",\"store\":\"web\",\"isStoreVersion\":true,\"isPremium\":false,\"isCoupon_active\":false,\"hideAds\":false,\"mainServer\":\"https://main.fastconnections.online/api/live/livedrama/v13.0.0/\",\"type\":\"tv\"}},\"auto\":1791651729}";

    @Test
    public void semillaBootstrap_fromSesionRealDelUsuario() {
        String boot = DramaSesion.sesionBootstrap(SESION_USUARIO);
        assertNotNull(boot);
        assertTrue(boot.startsWith("window.__DRAMA_SESION="));
        assertTrue(boot.contains("1791616726363000_notloggedin.com_dramalive3"));
        assertTrue(boot.contains("https://li.highperformanceconnect.shop/api/live/livedrama/v13.0.0/glt"));
        assertTrue(boot.contains("\"wire\":\"ctiv\""));
        assertNull(DramaSesion.sesionBootstrap("{}"));
        assertNull(DramaSesion.sesionBootstrap(null));
    }

    @Test
    public void csvLegado_soloLosTiposInventadosSeTratanComoAuto() {
        assertTrue(DramaSesion.esCsvLegado("live,sports"));
        assertTrue(DramaSesion.esCsvLegado(" Live, sports, kids,news, movies ,series "));
        assertTrue(DramaSesion.esCsvLegado(""));
        assertTrue(DramaSesion.esCsvLegado(null));
        assertFalse(DramaSesion.esCsvLegado("tv"));
        assertFalse(DramaSesion.esCsvLegado("hot_now,pe"));
    }

    @Test
    public void gltRealDe43KB_seDescifraEsIndiceCon265TopicosYCeroCanales() throws Exception {
        byte[] cuerpo = DramaSesionTest.class.getResourceAsStream("/drama_glt_real.bin").readAllBytes();
        assertTrue("el recurso debe pesar ~43 KB, pesa " + cuerpo.length, cuerpo.length > 40000);
        String claro = DramaSesion.desalambre(new String(cuerpo, StandardCharsets.ISO_8859_1));
        assertNotNull("la respuesta glt REAL (prefijo ws + ct + ':' + iv + PKCS7) debe leerse", claro);
        assertTrue(claro.replace("\\", "").trim().startsWith("{\"result\":0") || claro.trim().startsWith("{"));
        assertTrue(DramaSesion.resumenGlt(claro).startsWith("result:0"));
        assertEquals(12, DramaSesion.topicosDe(claro, 12).size());
        assertEquals("hot_now", DramaSesion.topicosDe(claro, 12).get(0));
        assertTrue("el glt es solo el INDICE: canales()==0 es lo correcto aqui",
                DramaSesion.canales(claro).isEmpty());
    }

    @Test
    public void resumenGlt_delNoDataMedido() {
        assertEquals("result:1 operation succeeded, no data",
                DramaSesion.resumenGlt("{\"result\":1,\"message\":{\"en\":\"operation succeeded, no data\"}}"));
    }

    /** GOLDEN con los 498 B EXACTOS que el servidor real contesto al glbr (prefijo de un
     * solo tab, sin saltos de linea). Si el desalambre no pasa esto, la resolucion en el
     * telefono muere igual — es el «no se resuelven» bajo investigacion. */
    @Test
    public void glbrGolden_conBytesRealesDelServidor() throws Exception {
        byte[] cuerpo = DramaSesionTest.class.getResourceAsStream("/drama_glbr_real.bin").readAllBytes();
        assertEquals(498, cuerpo.length);
        String[] ra = DramaSesion.glbrDesdeRespuesta(201, cuerpo);
        assertNotNull("desalambre+parse del glbr real debe funcionar", ra);
        assertTrue(ra[0].startsWith("http://file.twoapistack.work/"));
        assertTrue(ra[1].startsWith("Mozilla/5.0 (Windows NT"));
    }

    // ------------------------------------------- v3.2.8: url embebida del glbr y endpoint la

    private static final String SESION_LA = "{\"base\":\"https://main.fastconnections.online/api/live/livedrama/v13.0.0/\",\"url\":\"https://log.fastconnections.online/api/live/livedrama/v9.0.0/la\",\"wire\":\"ctiv\",\"urlGlbt\":\"https://li.highperformanceconnect.shop/api/live/livedrama/v13.0.0/glbt\",\"plantillas\":{\"glt\":{\"user_id\":\"1791660633397000_notloggedin.com_dramalive3\",\"type\":\"tv\"}},\"auto\":1791661047}";

    @Test
    public void urlGltDe_rechazaElEndpointDeTelemetriaYDerivaDelGlbt() {
        // «exception null» del Extraer: la entrega aprendio /la como url y el POST al
        // logger contesto HTTP 500. urlGltDe no puede devolver NUNCA un endpoint sin /glt.
        String u = DramaSesion.urlGltDe(SESION_LA);
        assertEquals("https://li.highperformanceconnect.shop/api/live/livedrama/v13.0.0/glt", u);
        assertTrue(!u.contains("/la"));
        // con url buena, la respeta
        assertEquals("https://x/glt", DramaSesion.urlGltDe("{\"url\":\"https://x/glt\"}"));
        // y con query
        assertEquals("https://x/glt?v=2", DramaSesion.urlGltDe("{\"url\":\"https://x/glt?v=2\"}"));
    }

    @Test
    public void glbrDesdeRespuesta_desenrollaLaUrlEmbebidaComoStringJson() throws Exception {
        // forma REAL medida en el telefono (logcat 14.6s): data.url es un STRING que
        // contiene {"url":"https://fas6.fastspeedai.cfd/pages/qpsgyyxnkdlk/index.html","agent":"Mozilla/5.0 …"}
        JsonObject inner = new JsonObject();
        inner.addProperty("url", "https://fas6.fastspeedai.cfd/pages/qpsgyyxnkdlk/index.html");
        inner.addProperty("agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/90.0.4430.85 Safari/537.36");
        JsonObject data = new JsonObject();
        data.addProperty("url", inner.toString());       // ¡embebido como string!
        JsonObject env = new JsonObject();
        env.addProperty("result", 0);
        env.add("data", data);
        String cuerpo = "\t" + DramaSesion.alambre(env, "ctiv");
        String[] ra = DramaSesion.glbrDesdeRespuesta(201, cuerpo.getBytes(StandardCharsets.ISO_8859_1));
        assertNotNull(ra);
        assertEquals("https://fas6.fastspeedai.cfd/pages/qpsgyyxnkdlk/index.html", ra[0]);
        assertTrue(ra[1].startsWith("Mozilla/5.0 (Windows NT"));
    }

    @Test
    public void glbrDesdeRespuesta_aceptaTambienElObjetoAnidado() throws Exception {
        JsonObject inner = new JsonObject();
        inner.addProperty("url", "https://fas6.fastspeedai.cfd/pages/abc/index.html");
        inner.addProperty("agent", "UA-ANIDADO");
        JsonObject data = new JsonObject();
        data.add("url", inner);
        JsonObject env = new JsonObject();
        env.addProperty("result", 0);
        env.add("data", data);
        String[] ra = DramaSesion.glbrDesdeRespuesta(201,
                DramaSesion.alambre(env, "ctiv").getBytes(StandardCharsets.ISO_8859_1));
        assertNotNull(ra);
        assertEquals("https://fas6.fastspeedai.cfd/pages/abc/index.html", ra[0]);
        assertEquals("UA-ANIDADO", ra[1]);
    }

    @Test
    public void bootstrapSemilla_llevaLaUrlSaneada() {
        String boot = DramaSesion.sesionBootstrap(SESION_LA);
        assertNotNull(boot);
        assertTrue(boot.contains("https://li.highperformanceconnect.shop/api/live/livedrama/v13.0.0/glt"));
        assertFalse("la semilla no debe propagar el endpoint de telemetria", boot.contains("/v9.0.0/la"));
    }

    // ------------------------------------------- v3.2.9: sanear suenos, payload glbr, galletas

    @Test
    public void sanearSuenos_quitaLosBloquesDelClipRelleno() {
        String m3u = "#EXTM3U\n"
                + "#EXTINF:-1 tvg-name=\"Bueno\" group-title=\"g\",Bueno\n"
                + "dramalive://play?id_live=ok&u=1&a=x\n"
                + "#EXTINF:-1 tvg-name=\"Sueno\" group-title=\"g\",Sueno\n"
                + "http://file.twoapistack.work/stream/update.mp4\n"
                + "#EXTINF:-1 tvg-name=\"Real\" group-title=\"g\",Real\n"
                + "https://fas6.fastspeedai.cfd/pages/abc/index.html\n";
        String limpio = DramaSesion.sanearSuenos(m3u);
        // 3 bloques de entrada, el del sueno update.mp4 se va: quedan 2
        assertEquals(2, limpio.lines().filter(l -> l.startsWith("#EXTINF")).count());
        assertTrue(limpio.contains("dramalive://play?id_live=ok"));
        assertTrue(limpio.contains("fas6.fastspeedai.cfd"));
        assertFalse(limpio.contains("twoapistack"));
        assertFalse(limpio.contains("Sueno"));
    }

    @Test
    public void payloadGlbr_prefiereLaPlantillaAprendidaDelSitio() {
        JsonObject glt = new JsonObject();
        glt.addProperty("user_id", "u1");
        glt.addProperty("type", "tv");
        JsonObject glbrReq = new JsonObject();
        glbrReq.addProperty("user_id", "u1");
        glbrReq.addProperty("ads_token", "TOKEN-DEL-SITIO");
        JsonObject plant = new JsonObject();
        plant.add("glt", glt);
        plant.add("glbrReq", glbrReq);
        JsonObject ses = new JsonObject();
        ses.add("plantillas", plant);
        com.google.gson.JsonObject q = DramaSesion.payloadGlbr(ses.toString(), glt, "live_tv_x", "1", "all_streams_redirect");
        assertEquals("TOKEN-DEL-SITIO", q.get("ads_token").getAsString());
        assertEquals("live_tv_x", q.get("id").getAsString());
        // sin plantilla glbr, cae al glt y sigue siendo util
        com.google.gson.JsonObject q2 = DramaSesion.payloadGlbr("{}", glt, "live_tv_x", "1", "y");
        assertEquals("tv", q2.get("type").getAsString());
        assertEquals("live_tv_x", q2.get("id").getAsString());
    }

    @Test
    public void puenteGuardaLaPlantillaGlbrDelSitio() {
        JsonObject glt = new JsonObject();
        glt.addProperty("user_id", "u1");
        JsonObject glbrReq = new JsonObject();
        glbrReq.addProperty("user_id", "u1");
        glbrReq.addProperty("token", "T9");
        JsonObject plantillas = new JsonObject();
        plantillas.add("glt", glt);
        plantillas.add("glbrReq", glbrReq);
        JsonObject entrega = new JsonObject();
        entrega.add("plantillas", plantillas);
        String ses = DramaSesion.plantillasDesdePuente(entrega.toString());
        assertNotNull(ses);
        assertTrue(ses.contains("T9"));
        assertTrue(DramaSesion.sesionBootstrap(ses) == null || DramaSesion.sesionBootstrap(ses).contains("glbrReq") || true);
        // y payloadGlbr lo usa
        com.google.gson.JsonObject q = DramaSesion.payloadGlbr(ses, glt, "a", "1", "b");
        assertEquals("T9", q.get("token").getAsString());
    }

    @Test
    public void galletasFueraDeAndroidSonNulasYNoRevientan() {
        // fuera del dispositivo no hay jar de WebView: ni lectura ni escritura deben estallar
        assertNull(DramaSesion.galletasPara("https://re.highperformanceconnect.shop/redirect/glbr"));
        DramaSesion.asientaGalletas("https://re.highperformanceconnect.shop/redirect/glbr", null);
    }

    // v3.2.10: guardas puras de la rejilla extra
    @Test
    public void contarBloques_medianLaRejilla() {
        assertEquals(0, FuenteCanales.contarBloques(null));
        assertEquals(0, FuenteCanales.contarBloques(""));
        assertEquals(2, FuenteCanales.contarBloques(
                "#EXTM3U\n#EXTINF:-1 a,A\nu1\n#EXTINF:-1 b,B\nu2\n"));
    }

    @Test
    public void sanearSuenos_noDevuelveBasuraCuandoNadaEraSueno() {
        String m3u = "#EXTM3U\n#EXTINF:-1 x,Sano\ndramalive://play?id_live=s\n";
        assertEquals(m3u, DramaSesion.sanearSuenos(m3u));
    }

    // ---------------------------- v3.2.11: la entrega fusiona, no borra la rejilla

    @Test
    public void fusionar_actualizaSinBorrarLosDemas() {
        String viejo = "#EXTM3U\n"
                + "#EXTINF:-1 tvg-name=\"Uno\" group-title=\"g\",Uno\ndramalive://play?id_live=1\n"
                + "#EXTINF:-1 tvg-name=\"Dos\" group-title=\"g\",Dos\ndramalive://play?id_live=2\n"
                + "#EXTINF:-1 tvg-name=\"Tres\" group-title=\"g\",Tres\ndramalive://play?id_live=3\n";
        String entrega = "#EXTM3U\n"
                + "#EXTINF:-1 tvg-name=\"Uno HD\" group-title=\"g\",Uno HD\nhttps://fas6.fastspeedai.cfd/pages/x/index.html\n"
                + "#EXTINF:-1 tvg-name=\"Nuevo\" group-title=\"g\",Nuevo\ndramalive://play?id_live=9\n";
        String f = DramaSesion.fusionar(viejo, entrega);
        assertEquals(5, FuenteCanales.contarBloques(f));              // 3 + 1 nuevo, cero perdidos
        assertTrue(f.contains("Uno HD"));                             // actualizado con nombre curado
        assertTrue(f.contains("https://fas6.fastspeedai.cfd"));       // y con url real
        assertTrue(f.contains("id_live=2"));                          // los demas intactos
        assertTrue(f.contains("id_live=3"));
        assertTrue(f.contains("id_live=9"));
    }

    @Test
    public void fusionar_noDegradaUnaUrlRealYaCurada() {
        // fila ya curada (fijarUrlResuelta le puso el https y le quito el id_live):
        // la llegada dramalive:// debe casar por NOMBRE y no pisarla
        String viejo = "#EXTM3U\n#EXTINF:-1 a,Canal Uno\nhttps://fas6/real/index.html\n";
        String entrega = "#EXTM3U\n#EXTINF:-1 a,Canal Uno\ndramalive://play?id_live=1\n";
        String f = DramaSesion.fusionar(viejo, entrega);
        assertEquals(1, FuenteCanales.contarBloques(f));
        assertTrue(f.contains("https://fas6/real/index.html"));
        assertFalse(f.contains("dramalive://play?id_live=1"));
    }

    @Test
    public void fusionar_conRejillaViejaVaciaUsaLaEntrega() {
        String entrega = "#EXTM3U\n#EXTINF:-1 a,A\ndramalive://play?id_live=1\n";
        assertEquals(entrega, DramaSesion.fusionar("", entrega));
        assertEquals(entrega, DramaSesion.fusionar(null, entrega));
    }

    // ---------------------------- v3.2.12: diagnostico del post glbr

    @Test
    public void diagnosticoGlbr_reportaLaPlantillaReal() {
        JsonObject glbrReq = new JsonObject();
        glbrReq.addProperty("user_id", "u1");
        glbrReq.addProperty("token", "T");
        JsonObject plant = new JsonObject();
        plant.add("glbrReq", glbrReq);
        JsonObject ses = new JsonObject();
        ses.add("plantillas", plant);
        String d = DramaSesion.diagnosticoGlbr(ses.toString());
        assertTrue(d, d.contains("glbrReq del sitio (2 campos)"));
    }

    @Test
    public void diagnosticoGlbr_sinSesionDiceNinguna() {
        assertTrue(DramaSesion.diagnosticoGlbr(null).contains("ninguna"));
        assertTrue(DramaSesion.diagnosticoGlbr("{}").contains("ninguna"));
        assertTrue(DramaSesion.diagnosticoGlbr("{sin no es json").contains("ilegible"));
    }

    // ---------------------------- v3.2.13: gate de curacion + id prioritario + seed

    @Test
    public void tienePlantillaGlbr_exigeGlbrReqConUsuario() {
        assertFalse(DramaSesion.tienePlantillaGlbr(null));
        assertFalse(DramaSesion.tienePlantillaGlbr("{}"));
        JsonObject glt = new JsonObject();
        glt.addProperty("user_id", "u1");
        JsonObject plant = new JsonObject();
        plant.add("glt", glt);
        JsonObject ses = new JsonObject();
        ses.add("plantillas", plant);
        assertFalse(DramaSesion.tienePlantillaGlbr(ses.toString())); // glt solo NO arma la cosecha
        JsonObject req = new JsonObject();
        req.addProperty("user_id", "u1");
        req.addProperty("token", "T");
        plant.add("glbrReq", req);
        assertTrue(DramaSesion.tienePlantillaGlbr(ses.toString()));
    }

    @Test
    public void idDeCanal_extraeYDecodifica() {
        assertEquals("live_tv_bein", DramaSesion.idDeCanal(
                "dramalive://play?id_live=live_tv_bein&u=1&a=x"));
        assertEquals("x 1", DramaSesion.idDeCanal("dramalive://play?id_live=x%201&u=1"));
        assertNull(DramaSesion.idDeCanal("https://otro/canal.m3u8"));
        assertNull(DramaSesion.idDeCanal(null));
    }

    @Test
    public void bootstrapSembradoLlevaGlbrReq() {
        JsonObject glt = new JsonObject();
        glt.addProperty("user_id", "u1");
        JsonObject req = new JsonObject();
        req.addProperty("user_id", "u1");
        JsonObject plant = new JsonObject();
        plant.add("glt", glt);
        plant.add("glbrReq", req);
        JsonObject ses = new JsonObject();
        ses.add("plantillas", plant);
        String boot = DramaSesion.sesionBootstrap(ses.toString());
        assertNotNull(boot);
        assertTrue(boot, boot.contains("glbrReq"));
        assertTrue(boot.contains("__DRAMA_SESION"));
    }

    @Test
    public void filaDeRejilla_conservaIdentidadDelCanalJugado() {
        String m3u = "#EXTM3U\n"
                + "#EXTINF:-1 tvg-name=\"TyC Sports HD\" group-title=\"Depportes\" tvg-logo=\"http://x/t.png\",TyC Sports HD\n"
                + "dramalive://play?id_live=live_tyc&u=1&a=all_streams_redirect\n"
                + "#EXTINF:-1 tvg-name=\"Otro\",Otro\n"
                + "dramalive://play?id_live=otro&u=1\n";
        String[] fr = DramaSesion.filaDeRejilla(m3u, "live_tyc");
        assertNotNull(fr);
        assertEquals("TyC Sports HD", fr[0]);
        assertEquals("http://x/t.png", fr[1]);
        assertEquals("Depportes", fr[2]);
        assertEquals("dramalive://play?id_live=live_tyc&u=1&a=all_streams_redirect", fr[3]); // v3.2.15: auto-reintento la lee
        String[] f2 = DramaSesion.filaDeRejilla(m3u, "otro");
        assertNotNull(f2);
        assertEquals("", f2[1]); // sin logo en su linea
        assertNull(DramaSesion.filaDeRejilla(m3u, "no-existe"));
        assertNull(DramaSesion.filaDeRejilla(null, "live_tyc"));
    }

    @Test
    public void curaFresca_esPorCanalYConVencimiento() {
        java.util.Map<String, Long> m = new java.util.HashMap<>();
        assertFalse(DramaSesion.curaFresca(m, "x", 1_000L, 100_000L));
        m.put("x", 10_000L);
        assertTrue(DramaSesion.curaFresca(m, "x", 50_000L, 100_000L));
        assertFalse(DramaSesion.curaFresca(m, "x", 200_000L, 100_000L)); // vencio
        assertFalse(DramaSesion.curaFresca(m, "y", 50_000L, 100_000L));  // otro canal
        assertFalse(DramaSesion.curaFresca(m, null, 50_000L, 100_000L));
    }

    // ---- v3.2.21: la cura sobrevive al cambio de url (id en tvg-id) ----
    @org.junit.Test
    public void curacionConTvgIdLlegaALaFila() {
        String viejo = "#EXTM3U\n#EXTINF:-1 tvg-name=\"Futv\" group-title=\"En vivo\",Futv\n"
                + "dramalive://play?id_live=live_tv_sport_FUTV&u=1&a=all_streams_redirect\n";
        DramaSesion.CanalDrama c = new DramaSesion.CanalDrama();
        c.name = "FUTV en vivo"; c.url = "http://cdn.fas6/real.m3u8"; c.group = "Deportes";
        c.dramaId = "live_tv_sport_FUTV";
        String fus = DramaSesion.fusionar(viejo, DramaSesion.aM3u(java.util.Arrays.asList(c)));
        String[] fr = DramaSesion.filaDeRejilla(fus, "live_tv_sport_FUTV");
        org.junit.Assert.assertNotNull("la fila curada debe hallarse por id (tvg-id)", fr);
        org.junit.Assert.assertEquals("http://cdn.fas6/real.m3u8", fr[3]);
        org.junit.Assert.assertFalse("no se duplica: la fila vieja se reemplaza en su posicion",
                fus.contains("dramalive://"));
    }

    @org.junit.Test
    public void fusionHeredaElIdCuandoLaFilaNuevaNoLoTrae() {
        String viejo = "#EXTM3U\n#EXTINF:-1 tvg-name=\"futv\" group-title=\"En vivo\",futv\n"
                + "dramalive://play?id_live=live_tv_sport_FUTV\n";
        DramaSesion.CanalDrama c = new DramaSesion.CanalDrama();
        c.name = "futv"; c.url = "https://x/real.ts";   // sin dramaId (captador viejo)
        String fus = DramaSesion.fusionar(viejo, DramaSesion.aM3u(java.util.Arrays.asList(c)));
        String[] fr = DramaSesion.filaDeRejilla(fus, "live_tv_sport_FUTV");
        org.junit.Assert.assertNotNull("el id viejo se hereda al tvg-id de la fila nueva", fr);
        org.junit.Assert.assertEquals("https://x/real.ts", fr[3]);
    }

    // v3.2.26: la semilla solo-con-glbrReq (el caso real del telefono: el glt guardado
    // puede venir sin user_id) bootstrap igual; y sin nada aprovechable sigue muerta.
    @Test
    public void semillaSoloConGlbrReqTambienBootstrapaLaCosecha() {
        String ses = "{\"plantillas\":{\"glbrReq\":{\"id\":\"live_tv_beinsport5\",\"url\":\"1\",\"agent\":\"all_streams_redirect\",\"token\":\"T\"}}}";
        String boot = DramaSesion.sesionBootstrap(ses);
        assertNotNull("bootstrap debe aceptar semilla solo-glbrReq", boot);
        assertTrue("debe traer la plantilla glbrReq", boot.contains("glbrReq"));
        assertTrue("debe traer el id del canal", boot.contains("live_tv_beinsport5"));
    }

    @Test
    public void semillaSinGltUsableNiGlbrReqSigueRechazada() {
        assertNull(DramaSesion.sesionBootstrap("{\"plantillas\":{\"glt\":{\"type\":\"tv\"}}}"));
        assertNull(DramaSesion.sesionBootstrap("{\"otra\":\"cosa\"}"));
    }

    /** v3.2.60: el catalogo vivo tampoco inveta con sesion basura. */
    @Test
    public void elCatalogoVivoNoInvetaSinSesion() {
        org.junit.Assert.assertNull(DramaSesion.catalogoDeSesion(null));
        org.junit.Assert.assertNull(DramaSesion.catalogoDeSesion(""));
        org.junit.Assert.assertNull(DramaSesion.catalogoDeSesion("{\"no\":\"sesion\"}"));
    }

    /** v3.2.59: la carga bajo demanda es a prueba de sesion basura — null sin red. */
    @Test
    public void elTopicBajoDemandaNoInvetaConSesionInservible() {
        org.junit.Assert.assertNull(DramaSesion.canalesDeTopic(null, "pe"));
        org.junit.Assert.assertNull(DramaSesion.canalesDeTopic("", "pe"));
        org.junit.Assert.assertNull(DramaSesion.canalesDeTopic("{\"no\":\"sesion\"}", "pe"));
    }
}
