package dza.fut.paipo;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 🌐 Conexión automática de DramaLive EN SEGUNDO PLANO.
 *
 * <p><b>Qué hace.</b> En cuanto se abre la app se conecta a DramaLive y extrae los
 * canales de los 265 tópicos del índice, sin que el usuario pulse nada y sin que
 * tenga que entrar en la pestaña de canales. Cada tópico se guarda en cuanto
 * llega, así que la rejilla se va llenando sola mientras se usa el resto de la
 * app; cuando el usuario abre los canales ya están.
 *
 * <p><b>v7.8.</b> Antes de todo eso se intenta el catálogo servido desde GitHub
 * ({@link CatalogoRemoto}): un GET en lugar de 265 peticiones y 21 s. Solo si el
 * repo no responde se cae a la extracción en vivo de siempre, que es el último
 * recurso.
 *
 * <p><b>Por qué existe.</b> Antes la extracción se lanzaba al entrar en la pestaña
 * ({@code CanalesEnVivoFragment.onResume}) y era secuencial, así que había que
 * esperar minutos delante de una rejilla vacía. Ahora:
 * <ul>
 *   <li>la lanza {@code Main} al crear la actividad (arranque de la app);</li>
 *   <li>los tópicos se piden <b>en paralelo</b> (ver {@link DramaSesion#pedirTopicos});</li>
 *   <li>un único worker para toda la app: si la pestaña de canales se abre mientras
 *       corre, se engancha al mismo en vez de lanzar otro.</li>
 * </ul>
 *
 * <p>La decisión de «¿toca moverse?» sigue siendo {@link AutoDrama#plan}, que es
 * aritmética pura y está en la suite: aquí solo se ejecuta.
 */
final class DramaFondo {

    private DramaFondo() {
    }

    private static final String TAG = "DramaFondo";

    /** Entre un intento fallido y el siguiente, para no machacar la red. */
    private static final long ESPERA_REINTENTO_MS = 60L * 1000L;
    /** Intentos seguidos cuando la app arranca sin sesión de Drama. */
    private static final int INTENTOS = 3;

    private static final AtomicBoolean EN_CURSO = new AtomicBoolean(false);
    private static volatile long ULTIMO_FALLO = 0L;
    private static volatile WeakReference<Oyente> OYENTE;
    private static final java.util.concurrent.atomic.AtomicReference<Context> APP =
            new java.util.concurrent.atomic.AtomicReference<>(null);

    /** Avisos del worker en segundo plano. */
    interface Oyente {
        /** Llegó un tópico: los canales que trae, ya en texto m3u, para sumarlos. */
        void dramaFondoProgreso(@Nullable String m3uDelTopico, int totalAcumulado);

        /** La extracción nativa terminó bien. */
        void dramaFondoExito(int canales);

        /** Lo nativo no pudo: hace falta el captador headless. */
        void dramaFondoFallo(@NonNull String motivo);
    }

    static void fijarOyente(@Nullable Oyente o) {
        OYENTE = new WeakReference<>(o);
    }

    static void fijarApp(@Nullable Context c) {
        APP.set(c == null ? null : c.getApplicationContext());
    }

    /** true si hay una conexión corriendo ahora mismo. */
    static boolean enCurso() {
        return EN_CURSO.get();
    }

    /**
     * Arranca la conexión si {@link AutoDrama#plan} dice que toca. Se puede llamar
     * desde cualquier sitio y todas las veces que se quiera: si ya hay una en
     * marcha, o si el plan dice NADA, no hace nada.
     */
    static void arrancar(@NonNull Context ctx) {
        if (EN_CURSO.get()) return;
        long ahora = System.currentTimeMillis();
        if (ULTIMO_FALLO != 0 && ahora - ULTIMO_FALLO < ESPERA_REINTENTO_MS) return;

        final Context app = ctx.getApplicationContext();
        final String ses = FuenteCanales.sesionDrama(app);
        final long ts = FuenteCanales.dramaTs(app);
        int plan = AutoDrama.plan(ahora, ts, ses == null || ses.trim().isEmpty());
        if (plan == AutoDrama.NADA) return;

        if (!EN_CURSO.compareAndSet(false, true)) return;
        final boolean sinSesion = plan == AutoDrama.CONEXION;
        // El plan ya marcó el intento en cuanto a ts: se refleja aquí para que un
        // segundo arrancar() no vuelva a colarse mientras este corre.
        FuenteCanales.guardarDramaTs(app, ahora);

        new Thread(() -> {
            try {
                // v7.8: PRIMERO el catalogo de GitHub. Un solo GET de 4,3 MB
                // sustituye a conectar + extraer los 265 topicos (21 s y 265
                // peticiones medidas). Si sirve, aqui acaba la cosa.
                String remoto = CatalogoRemoto.refrescar(app);
                if (remoto != null) {
                    int n = FuenteCanales.contarBloques(remoto);
                    FuenteCanales.publicarCatalogoRemoto(app, remoto);
                    FuenteCanales.guardarDramaTs(app, System.currentTimeMillis());
                    FuenteCanales.guardarDramaEstado(app,
                            "catalogo de GitHub: " + n + " canales");
                    ULTIMO_FALLO = 0L;
                    final int nF = n;
                    avisar(o -> o.dramaFondoExito(nF));
                    return;
                }
                // Sin red o con el repo caido: la extraccion en vivo de siempre,
                // que es el ultimo recurso y ya sabemos que funciona.
                Log.w(TAG, "sin catalogo de GitHub, se extrae de DramaLive");
                int intentos = sinSesion ? INTENTOS : 1;
                for (int i = 0; i < intentos; i++) {
                    String sem = null;
                    DramaSesion.Resultado r = null;
                    try {
                        if (i == 0 && !sinSesion) {
                            r = DramaSesion.extraer(ses, null, 0, AVANCE);
                        } else {
                            sem = DramaSesion.conectarYSembrar(app);
                            if (sem != null) r = DramaSesion.extraer(sem, null, 0, AVANCE);
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "conexión en segundo plano: " + t);
                    }

                    final String semF = sem;
                    final DramaSesion.Resultado rF = r;
                    final boolean ok = semF != null && rF != null && rF.canales > 0 && rF.m3u != null;

                    if (semF != null) FuenteCanales.guardarSesionDrama(app, semF);
                    if (ok) {
                        // Se fusiona con lo guardado: las urls ya curadas por el
                        // player no se pueden perder.
                        FuenteCanales.guardarDramaExtra(app, AutoDrama.fusionarEntrega(
                                FuenteCanales.dramaExtraIdo(app), rF.m3u));
                        FuenteCanales.guardarDramaTs(app, System.currentTimeMillis());
                        FuenteCanales.guardarDramaEstado(app,
                                "automático en segundo plano: " + rF.canales + " canales");
                        ULTIMO_FALLO = 0L;
                        avisar(o -> o.dramaFondoExito(rF.canales));
                        return;
                    }
                    String motivo = rF != null && rF.error != null ? rF.error : "sin respuesta";
                    Log.w(TAG, "intento " + (i + 1) + "/" + intentos + " falló: " + motivo);
                    if (i + 1 < intentos) {
                        try {
                            Thread.sleep(ESPERA_REINTENTO_MS / 3);
                        } catch (InterruptedException e) {
                            return;
                        }
                    } else {
                        FuenteCanales.guardarDramaEstado(app, "automático: " + motivo);
                        ULTIMO_FALLO = System.currentTimeMillis();
                        final String m = motivo;
                        avisar(o -> o.dramaFondoFallo(m));
                    }
                }
            } finally {
                EN_CURSO.set(false);
            }
        }, "drama-fondo").start();
    }

    /** Fuerza un intento ya (reconexión manual): olvida el último fallo. */
    static void forzar(@NonNull Context ctx) {
        ULTIMO_FALLO = 0L;
        FuenteCanales.guardarDramaTs(ctx.getApplicationContext(), 0L);
        arrancar(ctx);
    }

    /** El captador headless ya entregó (o fracasó): se puede reintentar. */
    static void captadorTerminado(@NonNull Context ctx) {
        ULTIMO_FALLO = 0L;
        FuenteCanales.guardarDramaTs(ctx.getApplicationContext(), System.currentTimeMillis());
    }

    /**
     * Avance de la extracción: avisa con el m3u del tópico recién llegado. Se
     * llama desde los hilos del pool.
     */
    private static final DramaSesion.Avance AVANCE = new DramaSesion.Avance() {
        @Override
        public void enTopico(@NonNull String topico,
                             @NonNull List<DramaSesion.CanalDrama> nuevos,
                             int total) {
            if (nuevos.isEmpty()) return;
            final String frag = DramaSesion.aM3u(nuevos);
            avisar(o -> o.dramaFondoProgreso(frag, total));
        }
    };

    private static void avisar(@NonNull Accion a) {
        Oyente o = OYENTE == null ? null : OYENTE.get();
        if (o != null) {
            try {
                a.correr(o);
            } catch (Throwable ignored) { }
        }
    }

    private interface Accion {
        void correr(@NonNull Oyente o);
    }

    /** Abre el captador headless (plan B). */
    static void abrirCaptadorHeadless(@NonNull Context ctx) {
        try {
            Intent i = new Intent(ctx, DramaAutoActivity.class);
            i.putExtra(DramaAutoActivity.EXTRA_HEADLESS, true);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable t) {
            Log.w(TAG, "captador headless: " + t);
        }
    }
}
