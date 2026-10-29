# -*- coding: utf-8 -*-
# v3.2.43 — tira de canales como Drama (capturas 17/10): ◎♥★ + pais clavado + 🔥,
# corazon en la losa y contador de aperturas.
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

P = "app/src/main/java/dza/fut/paipo/"
FRAG = P + "CanalesEnVivoFragment.java"
ADAP = P + "CanalAdapter.java"
FUS = P + "FusionRejilla.java"

# 1) FusionRejilla: país del aparato (código ISO → tópico)
fix(FUS, [
("""    /** true si el tópico es un país (para darle al chip el globo, no la rejilla). */""",
"""    /** Código ISO del aparato → palabra que delata al país en un tópico (PE → "peru"). */
    private static final String[][] CC_PAIS = {
        {"PE", "peru"}, {"AR", "argentina"}, {"CL", "chile"}, {"BO", "bolivia"},
        {"PY", "paraguay"}, {"UY", "uruguay"}, {"EC", "ecuador"}, {"CO", "colombia"},
        {"VE", "venezuela"}, {"MX", "mexico"}, {"US", "usa"}, {"ES", "espana"},
        {"BR", "brasil"},
    };

    /** true si el tópico pertenece al país que sugiere el código ISO del aparato. */
    public static boolean topicEsPais(String topicoCrudo, String codigoPais) {
        if (topicoCrudo == null || codigoPais == null) return false;
        String cc = codigoPais.trim().toUpperCase(Locale.ROOT);
        for (String[] m : CC_PAIS) {
            if (!m[0].equals(cc)) continue;
            String t = " " + crudeza(topicoCrudo) + " ";
            return m[1].length() <= 3 ? t.contains(" " + m[1] + " ") : t.contains(m[1]);
        }
        return false;
    }

    /** true si el tópico es un país (para darle al chip el globo, no la rejilla). */"""),
])

# 2) Layout del tile: corazon al canto (como el de las losas del cine)
fix("app/src/main/res/layout/item_canal.xml", [
("""        <TextView
            android:id="@+id/txtNombreCanal"
            style="@style/Widget.Blabongo.TileName\"""",
"""        <!-- v3.2.43: corazon de favorito en la losa (tira de Drama con ♥ Favoritos). -->
        <TextView
            android:id="@+id/btnFavCanal"
            android:layout_width="30dp"
            android:layout_height="30dp"
            android:layout_margin="@dimen/space_1_5"
            android:background="@drawable/bg_pelis_year"
            android:clickable="true"
            android:focusable="true"
            android:gravity="center"
            android:text="\u2661"
            android:textColor="@color/pelis_text_secondary"
            android:textSize="15sp"
            android:contentDescription="@string/canales_fav_dentro"
            app:layout_constraintEnd_toEndOf="parent"
            app:layout_constraintTop_toTopOf="parent" />

        <TextView
            android:id="@+id/txtNombreCanal"
            style="@style/Widget.Blabongo.TileName\""""),
])

# 3) Strings
fix("app/src/main/res/values/strings.xml", [
("""    <string name="canales_tab_todo">""",
"""    <string name="canales_fav_dentro">Guardar canal en favoritos</string>
    <string name="canales_fav_si">Guardado en favoritos</string>
    <string name="canales_fav_no">Quitado de favoritos</string>
    <string name="canales_tab_todo">"""),
])

# 4) Adaptador: estado del corazon + escucha + contador
fix(ADAP, [
("""    public CanalAdapter(List<Object> items, Context context) {""",
"""    /**
     * v3.2.43 — el corazon de la losa (favoritos de la tira) y el aviso al fragmento.
     * El set vive en el fragmento (UsoCanales); aqui solo se pinta y se notifica.
     */
    private java.util.Set<String> favoritos = java.util.Collections.emptySet();
    private OnFavorito onFavorito;

    public interface OnFavorito { void alTocar(Canal canal); }

    public void setFavoritos(java.util.Set<String> favoritos) {
        this.favoritos = favoritos == null ? java.util.Collections.<String>emptySet() : favoritos;
        notifyDataSetChanged();
    }

    public void setOnFavorito(OnFavorito l) { onFavorito = l; }

    public CanalAdapter(List<Object> items, Context context) {"""),
("""        ImageView imgCanal;
        TextView txtNombre;""",
"""        ImageView imgCanal;
        TextView txtNombre;
        TextView btnFav;   // v3.2.43"""),
("""            imgCanal = itemView.findViewById(R.id.imgCanal);
            txtNombre = itemView.findViewById(R.id.txtNombreCanal);""",
"""            imgCanal = itemView.findViewById(R.id.imgCanal);
            txtNombre = itemView.findViewById(R.id.txtNombreCanal);
            btnFav = itemView.findViewById(R.id.btnFavCanal);"""),
("""            cHolder.itemView.setOnClickListener(v -> {
                marcarComoActivo(holder.getBindingAdapterPosition());
                mostrarOpcionesCanal(canal);
            });""",
"""            // v3.2.43: el corazon alterna sin abrir la hoja, y abrir desde la rejilla
            // ya cuenta para el chip ★ Los más vistos (como el historial de Drama).
            final Canal paraFav = canal;
            if (cHolder.btnFav != null) {
                cHolder.btnFav.setText(favoritos.contains(UsoCanales.claveDe(canal))
                        ? "\u2665" : "\u2661");
                cHolder.btnFav.setOnClickListener(v -> {
                    if (onFavorito != null) onFavorito.alTocar(paraFav);
                });
            }
            cHolder.itemView.setOnClickListener(v -> {
                UsoCanales.registrarApertura(context, canal);
                marcarComoActivo(holder.getBindingAdapterPosition());
                mostrarOpcionesCanal(canal);
            });"""),
])

# 5) Fragmento: tira nueva, scan de "en vivo", filtros especiales, alambre del corazon
fix(FRAG, [
("""    private String topicoActivo = "";""",
"""    private String topicoActivo = "";
    private boolean hayPartidosEnVivo;   // v3.2.43: el chip 🔥, si el feed lo justifica"""),
("""    private void contarTopicos() {
        conteoTopicos.clear();
        for (Canal canal : canales) {
            if (canal == null || TextUtils.isEmpty(canal.category)) continue;
            String topico = canal.category.trim();
            if (topico.isEmpty()) continue;
            Integer actual = conteoTopicos.get(topico);
            conteoTopicos.put(topico, actual == null ? 1 : actual + 1);
        }
    }""",
"""    private void contarTopicos() {
        conteoTopicos.clear();
        hayPartidosEnVivo = false;
        for (Canal canal : canales) {
            if (canal == null) continue;
            if (!hayPartidosEnVivo && canal.options != null && !canal.options.isEmpty()
                    && canal.options.get(0) != null
                    && TiraCanales.esPartidoEnVivo(canal.options.get(0).url)) {
                hayPartidosEnVivo = true;   // el "Live Matches" de las capturas
            }
            if (TextUtils.isEmpty(canal.category)) continue;
            String topico = canal.category.trim();
            if (topico.isEmpty()) continue;
            Integer actual = conteoTopicos.get(topico);
            conteoTopicos.put(topico, actual == null ? 1 : actual + 1);
        }
    }"""),
("""        // Con un solo tópico la tira sobra: se oculta en vez de dejar una fila vacía.
        if (conteoTopicos.size() < 2) {
            bloqueTabs.setVisibility(View.GONE);
            return;
        }
        bloqueTabs.setVisibility(View.VISIBLE);
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        anadirTab(inflater, "", getString(R.string.canales_tab_todo), canales.size(),
                R.drawable.ic_topic_live);
        // Con miles de canales hay decenas de grupos: se muestran los MÁS POBLADOS,
        // no los primeros de la lista (si no, "Legislative" le gana a "General").
        for (Map.Entry<String, Integer> entrada
                : AutoCanales.gruposPrincipales(conteoTopicos, MAX_TABS - 1)) {
            String topico = entrada.getKey();
            // v3.2.42: la etiqueta se humaniza y los países salen con bandera; la CLAVE
            // sigue siendo el topico crudo (el filtro compara contra Canal.category).
            anadirTab(inflater, topico, FusionRejilla.etiquetaTopic(topico),
                    entrada.getValue(), iconoPara(topico));
        }""",
"""        // v3.2.43: la tira la arma TiraCanales (◎ Todos, ♥ Favoritos, ★ Los más vistos,
        // país del aparato clavado, tópicos en SU orden, 🔥 Partidos en vivo). Con un solo
        // tópico y nada guardado la tira sobra: se oculta en vez de dejar una fila vacía.
        if (conteoTopicos.size() < 2 && !hayPartidosEnVivo
                && UsoCanales.favoritos(requireContext()).isEmpty()
                && UsoCanales.vistas(requireContext()).isEmpty()) {
            bloqueTabs.setVisibility(View.GONE);
            return;
        }
        bloqueTabs.setVisibility(View.VISIBLE);
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        java.util.List<String[]> tiras = TiraCanales.construir(conteoTopicos,
                TiraCanales.paisDelAparato(),
                UsoCanales.favoritos(requireContext()).size(),
                UsoCanales.vistas(requireContext()).size(),
                hayPartidosEnVivo);
        int tope = Math.min(tiras.size(), MAX_TABS);
        for (int i = 0; i < tope; i++) {
            String[] tira = tiras.get(i);
            String rotulo = TiraCanales.CLAVE_TODO.equals(tira[0])
                    ? getString(R.string.canales_tab_todo) : tira[1];
            int icono = TiraCanales.CLAVE_FAVORITOS.equals(tira[0])
                    || TiraCanales.CLAVE_VISTOS.equals(tira[0])
                    || TiraCanales.CLAVE_VIVOS.equals(tira[0])
                    ? R.drawable.ic_topic_star
                    : TiraCanales.CLAVE_TODO.equals(tira[0])
                    ? R.drawable.ic_topic_live : iconoPara(tira[0]);
            int cantidad = tira[2].isEmpty() ? canales.size() : Integer.parseInt(tira[2]);
            anadirTab(inflater, tira[0], rotulo, cantidad, icono);
        }"""),
("""    private void aplicarFiltroInterno() {
        List<Canal> visibles = new ArrayList<>();
        String topicoNormal = normalizar(topicoActivo);
        for (Canal canal : canales) {
            if (canal == null) continue;
            if (!topicoActivo.isEmpty() && !topicoNormal.equals(normalizar(canal.category))) continue;
            if (!busqueda.isEmpty() && !normalizar(canal.title).contains(busqueda)) continue;
            visibles.add(canal);
        }""",
"""    private void aplicarFiltroInterno() {
        List<Canal> visibles = new ArrayList<>();
        String topicoNormal = normalizar(topicoActivo);
        // v3.2.43: las claves especiales de la tira filtran por USO (♥ favoritos, ★ más
        // vistos, 🔥 en vivo), no por tópico.
        final java.util.Set<String> favs = TiraCanales.CLAVE_FAVORITOS.equals(topicoActivo)
                ? UsoCanales.favoritos(requireContext()) : null;
        java.util.List<String> top = TiraCanales.CLAVE_VISTOS.equals(topicoActivo)
                ? UsoCanales.topVistas(UsoCanales.vistas(requireContext()), 20) : null;
        final java.util.Set<String> topSet = top == null ? null : new java.util.HashSet<>(top);
        for (Canal canal : canales) {
            if (canal == null) continue;
            if (!topicoActivo.isEmpty()) {
                if (favs != null) {
                    if (!favs.contains(UsoCanales.claveDe(canal))) continue;
                } else if (topSet != null) {
                    if (!topSet.contains(UsoCanales.claveDe(canal))) continue;
                } else if (TiraCanales.CLAVE_VIVOS.equals(topicoActivo)) {
                    Canal.OpcionCanal o0 = canal.options == null || canal.options.isEmpty()
                            ? null : canal.options.get(0);
                    if (o0 == null || !TiraCanales.esPartidoEnVivo(o0.url)) continue;
                } else if (!topicoNormal.equals(normalizar(canal.category))) continue;
            }
            if (!busqueda.isEmpty() && !normalizar(canal.title).contains(busqueda)) continue;
            visibles.add(canal);
        }"""),
("""        if (adapter == null) {
            adapter = new CanalAdapter(items, requireContext());
            if (recyclerView != null) recyclerView.setAdapter(adapter);""",
"""        if (adapter == null) {
            adapter = new CanalAdapter(items, requireContext());
            // v3.2.43: el corazon de la losa. Alternar no abre la hoja de opciones.
            adapter.setFavoritos(UsoCanales.favoritos(requireContext()));
            adapter.setOnFavorito(canal -> {
                if (adapter == null || getContext() == null) return;
                boolean dentro = UsoCanales.alternarFavorito(requireContext(), canal);
                adapter.setFavoritos(UsoCanales.favoritos(requireContext()));
                construirTabs();
                if (TiraCanales.CLAVE_FAVORITOS.equals(topicoActivo)) aplicarFiltro();
                Toast.makeText(requireContext(),
                        dentro ? R.string.canales_fav_si : R.string.canales_fav_no,
                        Toast.LENGTH_SHORT).show();
            });
            if (recyclerView != null) recyclerView.setAdapter(adapter);"""),
])

# 6) TAG + docs
fix(P + "DramaAutoActivity.java", [
('"v3.2.42 — 162/162: rejilla de Drama en su lugar (delante, en su orden) + chips con países y banderas; extracción sin corte de 12 tópicos"',
 '"v3.2.43 — 169/169: tira como Drama (◎♥★ + país clavado + 🔥 en vivo), corazón en la losa y contador de aperturas"'),
])
fix("docs/COMO_SESION_DRAMA.md", [
("""| v3.2.42 | «CADA CANAL EN SU LUGAR COMO HACE DRAMA»""",
"""| v3.2.43 | **La tira de canales, clonada de las capturas de Drama (17/10): `TiraCanales` arma ◎ Todos · ♥ Favoritos · ★ Los más vistos · 🇵🇪 país del aparato clavado · tópicos en SU orden (ya no «los más poblados») · 🔥 Partidos en vivo (marcador `live_tv_custom_handler` del feed). Corazón ♡/♥ en cada losa (`UsoCanales`, prefs `canales_ui`, clave = id_live o slug del título) y cada apertura desde la rejilla cuenta para ★. 7 tests nuevos** | 169/169 |
| v3.2.42 | «CADA CANAL EN SU LUGAR COMO HACE DRAMA»"""),
])
print("LISTO v3.2.43")
