# -*- coding: utf-8 -*-
# v3.2.45 — fuera la hoja «Fuente»: con la cadena automatica (v3.2.44) el usuario ya
# no agrega codigos ni urls; la fuente vive en Drama y en la cadena publica, y el
# dialogo era el ultimo paso manual que quedaba. Se va EL UI: el motor FuenteCanales,
# el FG embebido (232425 en gltInicial), el ⟳ y todo el auto siguen intactos.
import io, os, re, sys

def rd(p): return io.open(p, encoding="utf-8").read()
def wr(p, s): io.open(p, "w", encoding="utf-8").write(s)

FRAG = "app/src/main/java/dza/fut/paipo/CanalesEnVivoFragment.java"
LAY = "app/src/main/res/layout/fragment_canales_en_vivo.xml"
DLG = "app/src/main/res/layout/dialog_fuente_canales.xml"

# 1) fuera el boton de entrada del header del fragmento
f = rd(FRAG)
old_btn = """        ImageView btnFuente = view.findViewById(R.id.btnFuenteCanales);
        if (btnFuente != null) btnFuente.setOnClickListener(v -> mostrarHojaFuente());
"""
if old_btn not in f:
    print("SALTADO boton (ya fuera)")
else:
    assert f.count(old_btn) == 1, "ancla boton fuente"
    f = f.replace(old_btn + "\n", "").replace(old_btn, "")

# 2) fuera la seccion entera de la hoja (metodo + helpers exclusivos del dialogo)
if "─── fuente ───" in f:
    ini = f.index("    // ─────────────────────────── fuente ───────────────────────────")
    fin = f.index("    @NonNull\n    private static String normalizar")
    assert 0 < ini < fin
    f = f[:ini] + f[fin:]
else:
    print("SALTADO seccion fuente (ya fuera)")
assert "mostrarHojaFuente" not in f and "BottomSheetDialog" not in f.replace(
    "import com.google.android.material.bottomsheet.BottomSheetDialog;", "")

# 3) fuera imports que quedaron colgando (nombre simple sin uso = import muerto)
body = "\n".join(l for l in f.split("\n") if not l.startswith("import "))
nuevas = []
for l in f.split("\n"):
    m = re.match(r"import (?:static )?([\w.]+);", l)
    if m and m.group(1).rsplit(".", 1)[1] != "R":
        simple = m.group(1).rsplit(".", 1)[1]
        if not re.search(r"\b" + re.escape(simple) + r"\b", body):
            continue
    nuevas.append(l)
f = "\n".join(nuevas)
wr(FRAG, f)
print("OK fragmento (fuera boton, hoja, helpers e imports muertos)")

# 4) fuera el ImageView del layout y el layout del dialogo
g = rd(LAY)
old_v = """            <ImageView
                android:id="@+id/btnFuenteCanales"
                style="@style/Widget.Blabongo.IconAction"
                android:contentDescription="@string/canales_accion_fuente"
                android:src="@drawable/ic_playlist_add"
                app:tint="@color/pelis_accent" />
"""
if old_v not in g:
    print("SALTADO ImageView (ya fuera)")
else:
    assert g.count(old_v) == 1, "ancla ImageView fuente"
    wr(LAY, g.replace(old_v, ""))
if os.path.exists(DLG):
    os.remove(DLG)
print("OK layouts (header limpio; dialog_fuente_canales.xml borrado)")

# 5) TAG + docs
t = rd("app/src/main/java/dza/fut/paipo/DramaAutoActivity.java")
a = '"v3.2.44 — 173/173: conexión y extracción 100% en segundo plano — plan puro AutoDrama, refresco nativo con fusión, captador solo como plan B"'
b = '"v3.2.45 — 173/173: fuera la hoja Fuente (último paso manual): la conexión, la extracción y la fuente viven del auto; el motor FuenteCanales sigue siendo la fuente de la verdad"'
assert t.count(a) == 1
wr("app/src/main/java/dza/fut/paipo/DramaAutoActivity.java", t.replace(a, b))
d = rd("docs/COMO_SESION_DRAMA.md")
a3 = "| v3.2.44 | **Conexión y extracción EN SEGUNDO PLANO de verdad:"
assert a3 in d
lin = d.split('\n')
i = [k for k, L in enumerate(lin) if L.startswith("| v3.2.44 |")][0]
lin.insert(i + 1, "| v3.2.45 | **Fuera la hoja «Fuente» entera (dialogo, botón del header, helpers de panel/alcance y su layout): con la cadena automática el único paso manual que quedaba era configurar una fuente — ya no hace falta. Se fue SOLO el UI: `FuenteCanales` (motor/prefs), el FG 232425 embebido en `gltInicial`, el ⟳ recargar y todo `AutoDrama` intactos; imports muertos purgados de una pasada** | 173/173 |")
wr("docs/COMO_SESION_DRAMA.md", '\n'.join(lin))
print("LISTO v3.2.45")
