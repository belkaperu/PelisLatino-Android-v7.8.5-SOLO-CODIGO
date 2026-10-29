# -*- coding: utf-8 -*-
# v3.2.46 — «cuando se devolvio el reloj… y salta a conectar en primer plano»:
# ventana tolerante al reloj de pared (auto-repara) y CERO lanzamientos de captador
# desde el auto (falla -> reintento silencioso al vencer la ventana).
import io, sys

def rd(p): return io.open(p, encoding="utf-8").read()
def wr(p, s): io.open(p, "w", encoding="utf-8").write(s)
def fix(path, blocks):
    s = rd(path)
    for i, (old, new) in enumerate(blocks, 1):
        n = s.count(old)
        if n >= 1 and new and s.count(new) >= 1:
            print("SALTADO %d en %s" % (i, path)); continue
        if n == 0 and (s.count(new) >= 1 if new else True):
            print("SALTADO %d en %s (ya aplicado)" % (i, path)); continue
        if n != 1:
            print("ANCLA %d en %s: %d coincidencias" % (i, path, n)); sys.exit(1)
        s = s.replace(old, new)
    wr(path, s)
    print("OK   %s" % path)

AUT = "app/src/main/java/dza/fut/paipo/AutoDrama.java"
FRAG = "app/src/main/java/dza/fut/paipo/CanalesEnVivoFragment.java"
TST = "app/src/test/java/dza/fut/paipo/AutoDramaTest.java"

fix(AUT, [
("""    /** Qué toca hacer ahora mismo al entrar en la pestaña de canales. */
    public static int plan(long ahora, long ultimoIntentoTs, boolean sesionVacia) {
        if (sesionVacia) return CONEXION;
        boolean vieja = ultimoIntentoTs <= 0L || ahora - ultimoIntentoTs > VENTANA_MS;
        return vieja ? REFRESCO : NADA;
    }""",
"""    /** Qué toca hacer ahora mismo al entrar en la pestaña de canales. */
    public static int plan(long ahora, long ultimoIntentoTs, boolean sesionVacia) {
        // v3.2.46 — TOLERANCIA AL RELOJ (queja medida del usuario: «se devolvió el
        // reloj»). Una resta a la brava con la hora de pared congelaba la rejilla para
        // siempre si el aparato atrasaba (delta negativo, jamás "vieja"), y el camino a
        // medias dejaba que sin sesión se repidiera al edge en CADA resume (el antiflood
        // castiga exactly eso). "Fresca" solo vale dentro de [-10 min, +VENTANA]:
        // cualquier anomalía se trata como "toca moverse", el intento reescribe el ts con
        // la hora actual y el asunto se auto-repara — sin dialogs, sin primer plano.
        long delta = ahora - ultimoIntentoTs;
        boolean fresca = ultimoIntentoTs > 0L && delta >= -600_000L && delta <= VENTANA_MS;
        if (fresca) return NADA;
        return sesionVacia ? CONEXION : REFRESCO;
    }"""),
])

fix(FRAG, [
("""                } else if (semTxt != null) {
                    FuenteCanales.guardarDramaEstado(ctx, "auto-conexion: plantillas OK, extraccion floja ("
                            + (rTxt != null ? rTxt.error : "?") + ") — pruebo el captador");
                    startActivity(new android.content.Intent(ctx, DramaAutoActivity.class)
                            .putExtra(DramaAutoActivity.EXTRA_HEADLESS, true));
                } else {
                    FuenteCanales.guardarDramaEstado(ctx, "auto-conexion fallo (red/edge) — plan B captador");
                    startActivity(new android.content.Intent(ctx, DramaAutoActivity.class)
                            .putExtra(DramaAutoActivity.EXTRA_HEADLESS, true));
                }""",
"""                } else {
                    // v3.2.46: NADA de saltar al captador en primer plano (queja del
                    // usuario). El intento ya quedó marcado en el ts; vencida la ventana,
                    // AutoDrama.plan re-intenta solo, en segundo plano, con la misma
                    // identidad (que es lo que el edge perdona).
                    FuenteCanales.guardarDramaEstado(ctx, "auto-conexion no cuajo ("
                            + (semTxt == null ? "red/edge" : "extracción floja: "
                                    + (rTxt != null ? rTxt.error : "?")) + ") — reintento en la próxima ventana");
                }"""),
("""     * FUSION (nunca pisa crudo: las urls curadas del player sobreviven). Solo si el edge
     * no contesta cae al captador headless (cookies reales) como plan B.
     */""",
"""     * FUSION (nunca pisa crudo: las urls curadas del player sobreviven). Si el edge no
     * contesta, nada salta a primer plano (v3.2.46): el reintento llega solo al vencer
     * la ventana de AutoDrama.
     */"""),
("""                } else {
                    FuenteCanales.guardarDramaEstado(ctx, "auto: refresco nativo no cuajo ("
                            + (rr != null ? rr.error : "?") + ") — plan B captador");
                    startActivity(new android.content.Intent(ctx, DramaAutoActivity.class)
                            .putExtra(DramaAutoActivity.EXTRA_HEADLESS, true));
                }""",
"""                } else {
                    FuenteCanales.guardarDramaEstado(ctx, "auto: refresco no cuajo ("
                            + (rr != null ? rr.error : "?") + ") — reintento en la próxima ventana");
                }"""),
("""        if (!serverCaido) return;
        captadorVisibleLanzado = true;
        android.util.Log.d("BLABONGO_DEBUG", "fuente FG caída: abro el captador visible automáticamente");
        Context c = getContext();
        if (c != null) {
            startActivity(new android.content.Intent(c, DramaAutoActivity.class));
        }
    }""",
"""        if (!serverCaido) return;
        captadorVisibleLanzado = true;
        // v3.2.46: antes abríamos el captador VISIBLE cuando la fuente moría. Con la
        // cadena automática eso cortaba al usuario por nada: el aviso queda en el estado
        // y el reintento silencioso lo trae AutoDrama al vencer la ventana.
        android.util.Log.w("DramaAuto", "fuente caída (sin salto a primer plano): " + mensaje);
        Context c = getContext();
        if (c != null) FuenteCanales.guardarDramaEstado(c, "fuente caída: " + mensaje);
    }"""),
])

fix(TST, [
("""    @Test
    public void sinSesionSiempreTocaConexion() {
        assertEquals(AutoDrama.CONEXION, AutoDrama.plan(1000L, 0L, true));
        assertEquals(AutoDrama.CONEXION, AutoDrama.plan(1000L, 999L, true)); // el ts no frena la primera
    }""",
"""    @Test
    public void primeraVezTocaConexionYelRestoRespetaLaVentana() {
        assertEquals(AutoDrama.CONEXION, AutoDrama.plan(1000L, 0L, true));
        // v3.2.46: sin sesion PERO con el intento ya marcado (fallo de red/edge), no se
        // repide al edge en cada resume: la ventana manda tambien aca.
        assertEquals(AutoDrama.NADA, AutoDrama.plan(2000L, 1500L, true));
    }

    @Test
    public void relojAtrasadoNoCongelaLaRejilla() {
        long marcado = 9_000L * H;              // el ts se escribio con el reloj "de antes"
        long ahora = 8_990L * H;                // y el usuario devolvio el reloj DIEZ HORAS
        assertEquals(AutoDrama.REFRESCO, AutoDrama.plan(ahora, marcado, false));
        assertEquals(AutoDrama.CONEXION, AutoDrama.plan(ahora, marcado, true));
        // un jiterito de NTP (atraso < 10 min) NO despierta ciclos extra
        assertEquals(AutoDrama.NADA, AutoDrama.plan(marcado - 5 * 60_000L, marcado, false));
        // salto del reloj hacia adelante: delta gigante = toca moverse y el intento
        // reescribe el ts con la hora nueva (se auto-repara sin pedir nada)
        assertEquals(AutoDrama.REFRESCO, AutoDrama.plan(marcado + 4000L * H, marcado, false));
    }"""),
])

fix("app/src/main/java/dza/fut/paipo/DramaAutoActivity.java", [
('"v3.2.45 — 173/173: fuera la hoja Fuente (último paso manual): la conexión, la extracción y la fuente viven del auto; el motor FuenteCanales sigue siendo la fuente de la verdad"',
 '"v3.2.46 — 175/175: ventana tolerante al reloj (se auto-repara si lo devuelven) y cero saltos a primer plano: si el auto no cuaja, reintento silencioso a la próxima ventana"'),
])
fix("docs/COMO_SESION_DRAMA.md", [
("""| v3.2.45 | **Fuera la hoja «Fuente» entera""",
"""| v3.2.46 | **Queja medida: «se devolvió el reloj» y «¿salta a conectar en primer plano?». Dos arreglos: `AutoDrama.plan` ya no confía en una resta cruda de hora de pared — la ventana vale solo dentro de [−10 min, +6 h] y cualquier anomalía (reloj atrasado/congelado/adelantado) se trata como "toca moverse" y el intento reescribe el ts: **auto-reparación**, la rejilla ya no se congela; y el auto **nunca más lanza el captador** (ni headless ni visible al caer la fuente FG) — falla → estado discreto + reintento silencioso al vencer la ventana. 2 tests nuevos de reloj** | 175/175 |
| v3.2.45 | **Fuera la hoja «Fuente» entera"""),
])
print("LISTO v3.2.46")
