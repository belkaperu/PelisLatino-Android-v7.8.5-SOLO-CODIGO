package dza.fut.paipo.core;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * v7.4 — LOS EJECUTORES DE LA APP, EN UN SITIO.
 *
 * <p><b>Por qué existe.</b> Los hilos estaban dispersos: {@code rejilla-io} en
 * FuenteCanales, {@code rejilla-prep} en el fragmento, {@code auto-canales} en
 * AutoCanales, más los {@code new Thread} del relay y del worker de Drama. Cada
 * uno con su propia configuración y su propio riesgo.
 *
 * <p><b>El crash que viene a cerrar.</b> En un build de la app se vio:
 * <pre>
 * java.lang.IllegalArgumentException: Core threads must have nonzero keep alive times
 *     at java.util.concurrent.ThreadPoolExecutor.allowCoreThreadTimeOut(ThreadPoolExecutor.java:1629)
 *     at dza.fut.paipo.core.AppExecutors.serial(AppExecutors.java:80)
 *     at dza.fut.paipo.AutoCanales.cargarAsync(AutoCanales.java:262)
 * </pre>
 * {@code allowCoreThreadTimeOut(true)} exige que el {@code keepAliveTime} sea
 * MAYOR QUE CERO. Con un ejecutor de un solo hilo (core = max = 1) el keepAlive
 * por defecto es 0, así que llamarla <b>revienta con IllegalArgumentException en
 * cuanto se pide el ejecutor</b> — y si eso pasa en el hilo principal, como
 * pasaba en {@code cargarAsync}, la app se cae sin más.
 *
 * <p>Aquí no se llama a {@code allowCoreThreadTimeOut} en ningún sitio: los hilos
 * de un solo hilo usan {@link Executors#newSingleThreadExecutor(ThreadFactory)},
 * y los pools acotados llevan keepAlive de 30 s, que ya los deja morir solos.
 * {@link AppExecutorsTest} lo comprueba para que no se vuelva a colar.
 */
public final class AppExecutors {

    private AppExecutors() {}

    /** Hilos de red como poco (y como mucho, los núcleos de la máquina). */
    private static final int HILOS_RED = Math.max(2, Math.min(4,
            Runtime.getRuntime().availableProcessors()));

    /** keepAlive de los pools acotados: > 0 siempre (ver la nota de arriba). */
    private static final long KEEP_ALIVE_S = 30L;

    private static final ThreadFactory FABRICA = new ThreadFactory() {
        private int n = 0;
        @Override public synchronized Thread newThread(@NonNull Runnable r) {
            // daemon: que ningún hilo de estos mantenga el proceso vivo solo.
            Thread t = new Thread(r, "app-" + (++n));
            t.setDaemon(true);
            return t;
        }
    };

    private static ThreadFactory conNombre(@NonNull final String nombre) {
        return r -> {
            Thread t = new Thread(r, nombre);
            t.setDaemon(true);
            return t;
        };
    }

    // ─────────────────────────── los tres pools ───────────────────────────

    /** E/S de disco: leer/escribir la rejilla y los ficheros por tópico. Un hilo. */
    private static final ExecutorService IO = nuevo("rejilla-io", 1);

    /** Preparar la rejilla: parsear, indexar, filtrar. Un hilo (el orden importa). */
    private static final ExecutorService SERIAL = nuevo("rejilla-prep", 1);

    /** Red: resolver fuentes y canales. Acotado, para no ahogar la conexión. */
    private static final ExecutorService RED = new ThreadPoolExecutor(
            0, HILOS_RED, KEEP_ALIVE_S, TimeUnit.SECONDS,
            new SynchronousQueue<>(), conNombre("red"));

    /**
     * Un ejecutor de UN solo hilo, con nombre y keepAlive correcto.
     *
     * <p>Nótese lo que NO hay: ninguna llamada a {@code allowCoreThreadTimeOut}.
     * Esa es la trampa que rompía la app.
     */
    @NonNull
    private static ExecutorService nuevo(@NonNull String nombre, int hilos) {
        return new ThreadPoolExecutor(hilos, hilos,
                KEEP_ALIVE_S, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), conNombre(nombre));
    }

    /** E/S de disco (rejilla, ficheros por tópico). */
    @NonNull
    public static ExecutorService io() { return IO; }

    /** Trabajo en serie: preparar la rejilla. El nombre viene del crash original. */
    @NonNull
    public static ExecutorService serial() { return SERIAL; }

    /** Red: resolver fuentes/canales, en paralelo y acotado. */
    @NonNull
    public static ExecutorService red() { return RED; }

    /**
     * Ejecuta sin dejar que un fallo del ejecutor se lleve la app por delante.
     * Si el pool está muerto o rechaza la tarea, se corre en el hilo actual.
     */
    public static void seguro(@NonNull ExecutorService pool, @NonNull Runnable tarea) {
        try {
            pool.execute(tarea);
        } catch (Throwable t) {
            try { tarea.run(); } catch (Throwable ignored) { }
        }
    }

    /** Atajo: {@link #seguro(ExecutorService, Runnable)} sobre el pool de E/S. */
    public static void enIo(@NonNull Runnable tarea) { seguro(IO, tarea); }

    /** Atajo: {@link #seguro(ExecutorService, Runnable)} sobre el pool en serie. */
    public static void enSerie(@NonNull Runnable tarea) { seguro(SERIAL, tarea); }

    /** Atajo: {@link #seguro(ExecutorService, Runnable)} sobre el pool de red. */
    public static void enRed(@NonNull Runnable tarea) { seguro(RED, tarea); }

    /** Para los tests: fábrica de hilos con nombre, reutilizable. */
    @NonNull
    public static ThreadFactory fabrica(@Nullable String nombre) {
        return nombre == null ? FABRICA : conNombre(nombre);
    }
}
