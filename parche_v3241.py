# -*- coding: utf-8 -*-
# v3.2.41 — ARREGLA EL CIERRE AL ABRIR: las filas de publicidad que inflaban el Mrec
# ya quitado (aapt no valida clases de vistas en layouts: explotaba en runtime).
import io, os, sys

def rd(p): return io.open(p, encoding="utf-8").read()
def wr(p, s): io.open(p, "w", encoding="utf-8").write(s)
def fix(path, blocks):
    s = rd(path)
    for i, (old, new) in enumerate(blocks, 1):
        n = s.count(old)
        if n == 0 and (s.count(new) >= 1 if new else True):
            print("SALTADO %d en %s (ya aplicado)" % (i, path)); continue
        if n != 1:
            print("ANCLA %d en %s: %d coincidencias" % (i, path, n)); sys.exit(1)
        s = s.replace(old, new)
    wr(path, s)
    print("OK   %s" % path)

BASE = "app/src/main/java/dza/fut/paipo/"

# 1) AGENDA: fuera la fila "ANUNCIO_MREC"
fix(BASE + "AgendaDeportivaFragment.java", [(
"""        // 2. Construir la lista mixta (partido + anuncio MREC cada 3)
        itemsParaAdapter.clear();
        for (int i = 0; i < partidosHoy.size(); i++) {
            itemsParaAdapter.add(partidosHoy.get(i));
            if ((i + 1) % 3 == 0 && i != partidosHoy.size() - 1) {
                itemsParaAdapter.add("ANUNCIO_MREC");
            }
        }""",
"""        // 2. Construir la lista: partidos y nada más. v3.2.41 — aquí se metía cada tres
        // filas una de publicidad, cuyo layout inflaba una clase del SDK retirado. La
        // inflación no falla al compilar (aapt no valida clases de vistas): reventaba
        // al primer render, y ese era el cierre al abrir.
        itemsParaAdapter.clear();
        itemsParaAdapter.addAll(partidosHoy);""",
)])

# 2) CANALES: fuera inyección, fuera span lookup, fuera la constante
frag = BASE + "CanalesEnVivoFragment.java"
fix(frag, [
("""    private static final int CADA_CUANTOS_HAY_ANUNCIO = 30;\n""", ""),
("""            // El anuncio nativo ocupa la fila entera; los tiles, una columna.
            gestor.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
                @Override
                public int getSpanSize(int position) {
                    // Ojo con el límite: mientras el RecyclerView cambia de adaptador,
                    // el LayoutManager llega a preguntar por posiciones del recuento
                    // ANTERIOR. Sin este control, getItemViewType lanza
                    // IndexOutOfBoundsException y la pestaña cierra la app.
                    CanalAdapter actual = adapter;
                    if (actual != null && position >= 0 && position < actual.getItemCount()
                            && actual.getItemViewType(position) == 1) {
                        return columnas;
                    }
                    return 1;
                }
            });""",
"""            // v3.2.41: adiós al span-lookup de las filas publicitarias — los tiles
            // miden todos igual (y se fue con él una carrera del LayoutManager)."""),
("""        boolean hayFiltro = !busqueda.isEmpty() || !topicoActivo.isEmpty();
        List<Object> items = new ArrayList<>();
        for (int i = 0; i < visibles.size(); i++) {
            items.add(visibles.get(i));
            // Con filtro, meter un anuncio en medio corta la lectura de la lista.
            if (!hayFiltro && (i + 1) % CADA_CUANTOS_HAY_ANUNCIO == 0 && i != visibles.size() - 1) {
                items.add("ANUNCIO");
            }
        }""",
"""        boolean hayFiltro = !busqueda.isEmpty() || !topicoActivo.isEmpty();
        // v3.2.41: la rejilla lleva solo canales (mismo motivo que la agenda).
        List<Object> items = new ArrayList<>(visibles);"""),
])

# 3) PARTIDO-ADAPTER: un solo tipo de fila
fix(BASE + "PartidoAdapter.java", [
("""    private static final int VIEW_TYPE_PARTIDO = 0;
    private static final int VIEW_TYPE_ANUNCIO = 1;
""",
"""    private static final int VIEW_TYPE_PARTIDO = 0;
"""),
("""    @Override
    public int getItemViewType(int position) {
        return items.get(position) instanceof Partido ? VIEW_TYPE_PARTIDO : VIEW_TYPE_ANUNCIO;
    }
""",
"""    // v3.2.41: queda un único tipo de fila; sin filas publicitarias no hay que
    // discriminar, así que el getItemViewType se retira.""",
),
("""        if (viewType == VIEW_TYPE_ANUNCIO) {
            View view = LayoutInflater.from(context).inflate(R.layout.item_ad_nativo, parent, false);
            return new AnuncioViewHolder(view);
        } else {
            View view = LayoutInflater.from(context).inflate(R.layout.item_partido, parent, false);
            return new PartidoViewHolder(view);
        }""",
"""        View view = LayoutInflater.from(context).inflate(R.layout.item_partido, parent, false);
        return new PartidoViewHolder(view);"""),
("""        } else if (holder instanceof AnuncioViewHolder) {
            // El MREC se carga automáticamente, no hay que hacer nada más
        }
    }""",
"""        }
    }"""),
("""
    public static class AnuncioViewHolder extends RecyclerView.ViewHolder {
        // El layout item_ad_nativo ya contiene un MREC que se carga solo
        public AnuncioViewHolder(View itemView) {
            super(itemView);
        }
    }
""", ""),
])

# 4) CANAL-ADAPTER: idéntico
fix(BASE + "CanalAdapter.java", [
("""    private static final int VIEW_TYPE_CANAL = 0;
    private static final int VIEW_TYPE_ANUNCIO = 1;
""",
"""    private static final int VIEW_TYPE_CANAL = 0;
"""),
("""    private List<Object> items;   // Canal o "ANUNCIO\"""",
"""    private List<Object> items;   // solo Canal desde v3.2.41"""),
("""    @Override
    public int getItemViewType(int position) {
        return items.get(position) instanceof Canal ? VIEW_TYPE_CANAL : VIEW_TYPE_ANUNCIO;
    }
""",
"""    // v3.2.41: un solo tipo de fila (los tiles), como en PartidoAdapter.""",
),
("""        if (viewType == VIEW_TYPE_ANUNCIO) {
            //  ⚡ Aquí inflamos TU layout exacto (el mismo de partidos)
            View view = LayoutInflater.from(context).inflate(R.layout.item_ad_nativo, parent, false);
            return new AnuncioViewHolder(view);
        } else {
            View view = LayoutInflater.from(context).inflate(R.layout.item_canal, parent, false);
            return new CanalViewHolder(view);
        }""",
"""        View view = LayoutInflater.from(context).inflate(R.layout.item_canal, parent, false);
        return new CanalViewHolder(view);"""),
("""        } else if (holder instanceof AnuncioViewHolder) {
            // El MREC se carga automáticamente, no hay nada más que hacer
        }
    }""",
"""        }
    }"""),
("""
    public static class AnuncioViewHolder extends RecyclerView.ViewHolder {
        public AnuncioViewHolder(View itemView) {
            super(itemView);
            // El MREC se encarga solo
        }
    }
""", ""),
])

# 5) FUERA el layout que inflaba la clase muerta
ad = "app/src/main/res/layout/item_ad_nativo.xml"
if os.path.exists(ad):
    os.remove(ad)
    print("OK   borrado " + ad)

# 6) GUARDA: ningún XML de res menciona el SDK y ningún java infla filas publicitarias
test = "app/src/test/java/dza/fut/paipo/CeroAnunciosTest.java"
fix(test, [
("""            + "build.gradle no documenta la ruta de vuelta (POR AHORA)""",
"""            + "build.gradle no documenta la ruta de vuelta (POR AHORA)"""),
("""    }
}""",
"""    }

    @Test
    public void ningunRecursoVuelveaNombrarElSdk() throws Exception {
        // LA LECCIÓN DE ESTE CIERRE: un layout puede citar clases que ya no existen y
        // compilar limpio — el fallo aparece al inflar. Aquí no pasa: si alguien
        // resucita un Mrec/banner en un XML, o re-infla una fila publicitaria desde
        // java, la suite revienta ANTES de llegar al teléfono.
        java.io.File res = new java.io.File(System.getProperty("user.dir"), "src/main/res");
        java.nio.file.Path resRoot = res.toPath();
        final boolean[] huellaXml = {false};
        java.nio.file.Files.walk(resRoot).forEach(p -> {
            if (p.toString().endsWith(".xml")) {
                try {
                    String s = new String(java.nio.file.Files.readAllBytes(p), "UTF-8").toLowerCase();
                    if (s.contains("startapp")) huellaXml[0] = true;
                } catch (Exception ignored) { }
            }
        });
        assertFalse("algún XML de res vuelve a mencionar el SDK retirado", huellaXml[0]);

        java.nio.file.Path srcRoot = new java.io.File(System.getProperty("user.dir"), "src/main/java").toPath();
        final boolean[] huellaJava = {false};
        java.nio.file.Files.walk(srcRoot).forEach(p -> {
            if (p.toString().endsWith(".java")) {
                try {
                    String s = new String(java.nio.file.Files.readAllBytes(p), "UTF-8");
                    String b = s.toLowerCase();
                    if (b.contains("item_ad_nativo") || b.contains("anuncio_mrec") || b.contains("view_type_anuncio")) {
                        huellaJava[0] = true;
                    }
                } catch (Exception ignored) { }
            }
        });
        assertFalse("queda código que infla filas publicitarias", huellaJava[0]);
    }
}"""),
])

# 7) TAG + docs
fix(BASE + "DramaAutoActivity.java", [
('"v3.2.40 — 156/156: fuera Start.io (init, banner, meta-data y dependencia) — POR AHORA"',
 '"v3.2.41 — 157/157: arreglado el cierre al abrir — fuera las filas publicitarias y el layout que inflaba el SDK muerto; guarda en la suite"'),
])
fix("docs/COMO_SESION_DRAMA.md", [
("""| v3.2.40 | **Fuera la publicidad de Start.io («por ahora», ruta de vuelta documentada en `build.gradle`)** | 156/156 |""",
"""| v3.2.40 | **Fuera la publicidad de Start.io («por ahora», ruta de vuelta documentada en `build.gradle`)** | 156/156 |
| v3.2.41 | **Arreglado el cierre al abrir: quitar el SDK dejó filas que inflaban su Mrec (agenda y canales) — aapt no valida clases de vistas, reventaba en runtime. Fuera inyecciones, tipos ANUNCIO e `item_ad_nativo.xml`, y la suite ahora vigila todos los res** | 157/157 |"""),
])
print("LISTO v3.2.41")
