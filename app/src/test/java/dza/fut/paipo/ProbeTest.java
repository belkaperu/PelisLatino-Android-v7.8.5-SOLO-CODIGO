package dza.fut.paipo;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/** Diagnostico: inspecciona la forma de data/catalog.json. Se puede borrar. */
public class ProbeTest {

    private String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", PelisApi.UA);
        c.setRequestProperty("Accept", "*/*");
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setInstanceFollowRedirects(true);
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        StringBuilder sb = new StringBuilder();
        if (in != null) {
            // limite duro: una sola linea minificada de varios MB hacia OOM al fork
            // (224m). Se leen los primeros 6 MB y se corta; alcanza para "que forma
            // tiene el catalogo", que es todo el proposito de este test diagnostico.
            try (java.io.Reader r = new java.io.BufferedReader(new java.io.InputStreamReader(in, "UTF-8"))) {
                char[] buf = new char[8192];
                int n;
                while (sb.length() < 6_000_000 && (n = r.read(buf)) > 0) sb.append(buf, 0, n);
            }
            try { c.disconnect(); } catch (Exception ignore) {}
        }
        System.out.println("### PROBE " + url + " -> HTTP " + code + " len=" + sb.length());
        return sb.toString();
    }

    @Test
    public void catalogShape() throws Exception {
        String base = "https://pelislatinohd.pages.dev";
        String json = get(base + "/data/catalog.json");

        // test DIAGNOSTICO (no gate de nada): remoto grande o HTML de error -> se autocompone
        if (json.length() > 4_194_304 || json.isEmpty() || json.charAt(0) != '{') {
            org.junit.Assume.assumeTrue("respuesta remota no-parseable (len=" + json.length() + ")", false);
        }

        JsonObject root =
                JsonParser.parseString(json).getAsJsonObject();
        System.out.println("### TOP KEYS " + root.keySet());

        for (String k : root.keySet()) {
            JsonElement e = root.get(k);
            if (e.isJsonArray()) {
                System.out.println("### ARRAY " + k + " size=" + e.getAsJsonArray().size());
                if (e.getAsJsonArray().size() > 0) {
                    System.out.println("### ITEM[" + k + "] " + e.getAsJsonArray().get(0).toString());
                }
            } else if (e.isJsonObject()) {
                System.out.println("### OBJ " + k + " keys=" + e.getAsJsonObject().keySet());
                String s = e.getAsJsonObject().toString();
                System.out.println("### OBJVAL " + k + " " + (s.length() > 600 ? s.substring(0, 600) : s));
            } else {
                System.out.println("### SCALAR " + k + " = " + e);
            }
        }

        // Muestra series con episodios si existen
        if (root.has("series") && root.get("series").isJsonArray()
                && root.getAsJsonArray("series").size() > 0) {
            String s = root.getAsJsonArray("series").get(0).toString();
            System.out.println("### SERIES0 " + (s.length() > 1500 ? s.substring(0, 1500) : s));
        }
    }
}
