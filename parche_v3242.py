# -*- coding: utf-8 -*-
# v3.2.42 — canales de Drama en su lugar (delante, en su orden) + chips con países.
import io, sys

def rd(p): return io.open(p, encoding="utf-8").read()
def wr(p, s): io.open(p, "w", encoding="utf-8").write(s)
def fix(path, blocks):
    s = rd(path)
    for i, (old, new) in enumerate(blocks, 1):
        n = s.count(old)
        if n >= 1 and new and s.count(new) >= 1:
            print("SALTADO %d en %s (ya aplicado, marca presente)" % (i, path)); continue
        if n == 0 and (s.count(new) >= 1 if new else True):
            print("SALTADO %d en %s (ya aplicado)" % (i, path)); continue
        if n != 1:
            print("ANCLA %d en %s: %d coincidencias" % (i, path, n)); sys.exit(1)
        s = s.replace(old, new)
    wr(path, s)
    print("OK   %s" % path)

FRAG = "app/src/main/java/dza/fut/paipo/CanalesEnVivoFragment.java"
SES = "app/src/main/java/dza/fut/paipo/DramaSesion.java"

# 1) Fragmento: la fusión ahora es Drama-primero, etiqueta con bandera en los chips
fix(FRAG, [
("""    /** Canales extraidos de DramaLive (si los hay), pegados al final sin duplicar URLs. */
    @NonNull
    private List<Canal> conLaListaDrama(@NonNull List<Canal> base) {
        Context contexto = getContext();
        if (contexto == null) return base;
        String m3u = FuenteCanales.dramaExtraIdo(contexto);
        if (m3u.isEmpty()) return base;
        try {
            List<Canal> extras = ListaPegada.desdeTexto(m3u, FuenteCanales.dramaKey(contexto));
            if (extras == null || extras.isEmpty()) return base;
            java.util.HashSet<String> urls = new java.util.HashSet<>();
            for (Canal c : base) if (c != null && c.options != null)
                for (Canal.OpcionCanal o : c.options) if (o != null && o.url != null) urls.add(o.url);
            List<Canal> merged = new java.util.ArrayList<>(base);
            for (Canal c : extras) {
                if (c == null || c.options == null || c.options.isEmpty()) continue;
                Canal.OpcionCanal primera = c.options.get(0);
                if (primera != null && primera.url != null && urls.add(primera.url)) merged.add(c);
            }
            return merged;
        } catch (Throwable t) {
            Log.w(TAG, "fusion lista drama", t);
            return base;
        }
    }""",
"""    /**
     * v3.2.42: los canales de Drama van DELANTE y en su propio orden (como en el sitio);
     * la lista de la fuente solo rellena huecos. Antes se pegaban al final con dedupe por
     * URL, que nunca coincidía — de ahí el «revuelto» y los duplicados. Ver {@link
     * FusionRejilla}, que es pura y la prueba la suite.
     */
    @NonNull
    private List<Canal> conLaListaDrama(@NonNull List<Canal> base) {
        Context contexto = getContext();
        if (contexto == null) return base;
        String m3u = FuenteCanales.dramaExtraIdo(contexto);
        if (m3u.isEmpty()) return base;
        try {
            List<Canal> drama = ListaPegada.desdeTexto(m3u, FuenteCanales.dramaKey(contexto));
            return FusionRejilla.conDramaPrimero(drama, base);
        } catch (Throwable t) {
            Log.w(TAG, "fusion lista drama", t);
            return base;
        }
    }"""),
("""        for (Map.Entry<String, Integer> entrada
                : AutoCanales.gruposPrincipales(conteoTopicos, MAX_TABS - 1)) {
            String topico = entrada.getKey();
            anadirTab(inflater, topico, topico, entrada.getValue(), iconoPara(topico));""",
"""        for (Map.Entry<String, Integer> entrada
                : AutoCanales.gruposPrincipales(conteoTopicos, MAX_TABS - 1)) {
            String topico = entrada.getKey();
            // v3.2.42: la etiqueta se humaniza y los países salen con bandera; la CLAVE
            // sigue siendo el topico crudo (el filtro compara contra Canal.category).
            anadirTab(inflater, topico, FusionRejilla.etiquetaTopic(topico),
                    entrada.getValue(), iconoPara(topico));"""),
("""        if (t.contains("fut") || t.contains("deporte") || t.contains("sport")) {
            return R.drawable.ic_topic_star;
        }
        return R.drawable.ic_topic_grid;""",
"""        if (t.contains("fut") || t.contains("deporte") || t.contains("sport")) {
            return R.drawable.ic_topic_star;
        }
        if (FusionRejilla.esPais(topico)) return R.drawable.ic_topic_live;
        return R.drawable.ic_topic_grid;"""),
])

# 2) Extracción: sin corte de 12 topicos y group = nombre humano del topic
fix(SES, [
("""                    List<String> tps = topicosDe(claroG, 12);
                    if (tps.isEmpty()) todos.addAll(canales(claroG)); // API vieja: canales directos en el glt
                    String urlGlbt = urlGlbtDe(plantillasJson);
                    for (String tp : tps) {
                        try {
                            Respuesta rb = post(urlGlbt, alambre(payloadGlbt(glt, tp), wire));
                            String claroB = rb.cuerpo == null ? null : new String(desalambreBytes(rb.cuerpo), StandardCharsets.UTF_8);
                            if (claroB != null) todos.addAll(canales(claroB));
                            else errores.add(tp + ": glbt ilegible (" + rb.status + ")");""",
"""                    // v3.2.42: sin corte de 12 — cada pais/topic que se quedaba fuera era
                    // una casilla vacia en la rejilla; y el group-title lleva ahora el
                    // nombre humano del topic (no el id crudo) para el chip con bandera.
                    List<String> tps = topicosDe(claroG, 40);
                    Map<String, String> nombresT = nombresTopicosDe(claroG);
                    if (tps.isEmpty()) todos.addAll(canales(claroG)); // API vieja: canales directos en el glt
                    String urlGlbt = urlGlbtDe(plantillasJson);
                    for (String tp : tps) {
                        try {
                            Respuesta rb = post(urlGlbt, alambre(payloadGlbt(glt, tp), wire));
                            String claroB = rb.cuerpo == null ? null : new String(desalambreBytes(rb.cuerpo), StandardCharsets.UTF_8);
                            if (claroB != null) {
                                String nom = nombresT.get(tp);
                                for (CanalDrama c : canales(claroB)) {
                                    if (nom != null && !nom.trim().isEmpty()) c.group = nom.trim();
                                    todos.add(c);
                                }
                            }
                            else errores.add(tp + ": glbt ilegible (" + rb.status + ")");"""),
("""                if (out.size() >= maximo) break;
            }
        } catch (Exception ignore) { }
        return out;
    }""",
"""                if (out.size() >= maximo) break;
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
                String nom = primero(o, new String[]{"topic_name", "name", "title", "label"});
                if (id != null && !id.isEmpty() && nom != null && !nom.trim().isEmpty()
                        && !out.containsKey(id)) out.put(id, nom.trim());
            }
        } catch (Exception ignore) { }
        return out;
    }"""),
])

# 3) TAG + docs
fix("app/src/main/java/dza/fut/paipo/DramaAutoActivity.java", [
('"v3.2.41 — 157/157: arreglado el cierre al abrir — fuera filas publicitarias y el layout que inflaba el SDK muerto (guarda en la suite)"',
 '"v3.2.42 — 162/162: rejilla de Drama en su lugar (delante, en su orden) + chips con países y banderas; extracción sin corte de 12 tópicos"'),
])
fix("docs/COMO_SESION_DRAMA.md", [
("""| v3.2.41 | «NO SE ABRE, SE CIERRA AL ABRIR» — causa medida: v3.2.40 quitó el SDK pero dejó `item_ad_nativo.xml` (inflaba `com.startapp.sdk.ads.banner.Mrec`) y sus inyectores en agenda (cada 3) y canales (cada 30); aapt no valida clases de vistas en layouts, así que compila y revienta al inflar en el arranque. FUERA: layout, filas inyectadas, tipos ANUNCIO y ViewHolders en ambos adapters, span-lookup del grid; `CeroAnunciosTest` nueva guarda — ningún XML de res ni inflate de java puede volver a nombrar el SDK. 157/157 |""",
"""| v3.2.41 | «NO SE ABRE, SE CIERRA AL ABRIR» — causa medida: v3.2.40 quitó el SDK pero dejó `item_ad_nativo.xml` (inflaba `com.startapp.sdk.ads.banner.Mrec`) y sus inyectores en agenda (cada 3) y canales (cada 30); aapt no valida clases de vistas en layouts, así que compila y revienta al inflar en el arranque. FUERA: layout, filas inyectadas, tipos ANUNCIO y ViewHolders en ambos adapters, span-lookup del grid; `CeroAnunciosTest` nueva guarda — ningún XML de res ni inflate de java puede volver a nombrar el SDK. 157/157 |
| v3.2.42 | «CADA CANAL EN SU LUGAR COMO HACE DRAMA» — la rejilla mostraba la lista por defecto primero y los canales de Drama pegados al final (dedupe por URL que nunca coincidía: duplicados + desorden). `FusionRejilla` (pura, 5 tests): Drama manda en su orden exacto, la base rellena huecos dedupando por nombre; los chips salen con nombre humano del tópico y bandera (🇵🇪 Perú…); `extraer()` usa el `topic_name` del glt como group y sube el corte de tópicos de 12 a 40 | 162/162 |"""),
])
print("LISTO v3.2.42")
