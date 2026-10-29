# -*- coding: utf-8 -*-
# v3.2.44 — conexion + extraccion en segundo plano de verdad: plan puro (AutoDrama),
# refresco NATIVO con fusion cuando hay sesion, captador solo como plan B.
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

fix(FRAG, [
# A) onResume: el plan lo decide AutoDrama (puro); el refresco con sesion es NATIVO
("""        // v3.2.34: LA CONEXION Y LA EXTRACCION SON AUTOMATICAS DE VERDAD. Con la cadena
        // resuelta en Java (glt/glbt/glasbi/glbr) ya no hace falta ni el WebView para el
        // primer arranque: sesion vacia -> auto-conectar (glt empotrado) + extraer() en un
        // hilo y la rejilla se llena sola. Si lo nativo no puede (edge blaqueado), cae al
        // captador headless de siempre. Con sesion vieja (>6 h) sigue el headless, que
        // trae las cookies reales del sitio.
        String ses = FuenteCanales.sesionDrama(contexto);
        boolean vieja = System.currentTimeMillis() - t > 6L * 3600_000L;
        if (ses.trim().isEmpty() || vieja) {
            long ahora = System.currentTimeMillis();
            FuenteCanales.guardarDramaTs(contexto, ahora);   // marca el intento: sin re-lazo hoy
            vistoTsDrama = ahora;
            if (ses.trim().isEmpty()) conectarYExtraerDrama(contexto);
            else startActivity(new android.content.Intent(contexto, DramaAutoActivity.class)
                    .putExtra(DramaAutoActivity.EXTRA_HEADLESS, true));
        }""",
"""        // v3.2.44: LA CONEXION Y LA EXTRACCION SON AUTOMATICAS DE VERDAD, EN SEGUNDO
        // PLANO y SIN WEBVIEW POR DEFECTO. AutoDrama.plan (puro, testeado) decide: sin
        // sesion -> glt empotrado (el FG 232425 viaja DENTRO de la plantilla; el usuario
        // no agrega nada) + extraer + fusionar; con sesion vieja (>6 h) -> refresco
        // NATIVO con la misma identidad, que es como el edge perdona (repide y blaquea).
        // El captador headless queda como plan B real, no como el camino de cada dia.
        String ses = FuenteCanales.sesionDrama(contexto);
        int plan = AutoDrama.plan(System.currentTimeMillis(), t, ses.trim().isEmpty());
        if (plan != AutoDrama.NADA) {
            long ahora = System.currentTimeMillis();
            FuenteCanales.guardarDramaTs(contexto, ahora);   // marca el intento: sin re-lazo hoy
            vistoTsDrama = ahora;
            if (plan == AutoDrama.CONEXION) conectarYExtraerDrama(contexto);
            else refrescarDramaNativo(contexto);
        }"""),
# B) metodo nuevo: refresco nativo en segundo plano
("""    private void cargarCanales() {
        cargarCanales(false);
    }""",
"""    /**
     * v3.2.44 — refresco EN SEGUNDO PLANO con la sesion guardada: extraer() nativo y
     * FUSION (nunca pisa crudo: las urls curadas del player sobreviven). Solo si el edge
     * no contesta cae al captador headless (cookies reales) como plan B.
     */
    private void refrescarDramaNativo(final Context ctx) {
        final androidx.fragment.app.FragmentActivity act = getActivity();
        if (act == null) return;
        final String ses = FuenteCanales.sesionDrama(ctx);
        if (ses.trim().isEmpty()) return;
        new Thread(() -> {
            DramaSesion.Resultado r;
            try {
                r = DramaSesion.extraer(ses, null);
            } catch (Exception e) {
                r = DramaSesion.Resultado.fallo("red: " + e);
            }
            final DramaSesion.Resultado rr = r;
            if (act.isFinishing()) return;
            act.runOnUiThread(() -> {
                if (!isAdded()) return;
                if (rr != null && rr.ok() && rr.canales > 0 && rr.m3u != null) {
                    FuenteCanales.guardarDramaExtra(ctx, AutoDrama.fusionarEntrega(
                            FuenteCanales.dramaExtraIdo(ctx), rr.m3u));
                    FuenteCanales.guardarDramaEstado(ctx,
                            "auto: rejilla refrescada (" + rr.canales + " canales)");
                    PlayerActivity.notificarEntrega();
                    cargarCanales(true);
                } else {
                    FuenteCanales.guardarDramaEstado(ctx, "auto: refresco nativo no cuajo ("
                            + (rr != null ? rr.error : "?") + ") — plan B captador");
                    startActivity(new android.content.Intent(ctx, DramaAutoActivity.class)
                            .putExtra(DramaAutoActivity.EXTRA_HEADLESS, true));
                }
            });
        }, "drama-auto-refresco").start();
    }

    private void cargarCanales() {
        cargarCanales(false);
    }"""),
# C) la primera conexion tambien fusiona (y avisa al player que espere una fila)
("""                if (semTxt != null && rTxt != null && rTxt.canales > 0 && rTxt.m3u != null) {
                    FuenteCanales.guardarDramaExtra(ctx, rTxt.m3u);
                    FuenteCanales.guardarDramaTs(ctx, System.currentTimeMillis());""",
"""                if (semTxt != null && rTxt != null && rTxt.canales > 0 && rTxt.m3u != null) {
                    // v3.2.44: fusionar, no pisar (y avisar al player: espera una fila curada).
                    FuenteCanales.guardarDramaExtra(ctx, AutoDrama.fusionarEntrega(
                            FuenteCanales.dramaExtraIdo(ctx), rTxt.m3u));
                    PlayerActivity.notificarEntrega();
                    FuenteCanales.guardarDramaTs(ctx, System.currentTimeMillis());"""),
])

fix("app/src/main/java/dza/fut/paipo/DramaAutoActivity.java", [
('"v3.2.43 — 169/169: tira como Drama (◎♥★ + país clavado + 🔥 en vivo), corazón en la losa y contador de aperturas"',
 '"v3.2.44 — 173/173: conexión y extracción 100% en segundo plano — plan puro AutoDrama, refresco nativo con fusión, captador solo como plan B"'),
])
fix("docs/COMO_SESION_DRAMA.md", [
("""| v3.2.43 | **La tira de canales, clonada""",
"""| v3.2.44 | **Conexión y extracción EN SEGUNDO PLANO de verdad: `AutoDrama.plan` (puro, 4 tests) decide — sin sesión: glt empotrado con el FG 232425 dentro (el usuario no agrega nada) + extraer + **fusionar** (ya no pisa las curadas) + `notificarEntrega`; con sesión vieja (>6h): refresco NATIVO con la misma identidad del aparato; el captador headless queda como plan B cuando el edge no cuaja. Ventana anti-flood intacta (un intento/día máximo)** | 173/173 |
| v3.2.43 | **La tira de canales, clonada"""),
])
print("LISTO v3.2.44")
