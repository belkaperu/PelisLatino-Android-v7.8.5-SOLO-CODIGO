package dza.fut.paipo.core;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * v7.4 — el ejecutor NO puede volver a romper la app.
 *
 * El crash real que motivó esta clase:
 * <pre>
 * java.lang.IllegalArgumentException: Core threads must have nonzero keep alive times
 *     at java.util.concurrent.ThreadPoolExecutor.allowCoreThreadTimeOut(ThreadPoolExecutor.java:1629)
 *     at dza.fut.paipo.core.AppExecutors.serial(AppExecutors.java:80)
 * </pre>
 * Es decir: {@code allowCoreThreadTimeOut(true)} con {@code keepAliveTime == 0}
 * lanza IllegalArgumentException. Si se pide el ejecutor en el hilo principal,
 * la app se cae. Aquí se fija que eso no pueda pasar.
 */
public class AppExecutorsTest {

    /** La regla de Android, expuesta para que el test la recuerde. */
    private static void comprobar(String nombre, ThreadPoolExecutor p) {
        boolean timeOut = p.allowsCoreThreadTimeOut();
        long keepAlive = p.getKeepAliveTime(TimeUnit.MILLISECONDS);
        if (timeOut) {
            assertTrue(nombre + ": allowCoreThreadTimeOut(true) exige keepAlive > 0 (tenía "
                    + keepAlive + ")", keepAlive > 0);
        }
    }

    @Test public void ningunPoolDejaKeepAliveEnCeroConTimeOut() {
        comprobar("io", (ThreadPoolExecutor) AppExecutors.io());
        comprobar("serial", (ThreadPoolExecutor) AppExecutors.serial());
        comprobar("red", (ThreadPoolExecutor) AppExecutors.red());
    }

    @Test public void pedirElEjecutorNoRevienta() {
        // Justo lo que hacía el build que se caía: pedirlo y ejecutar algo.
        AppExecutors.serial();
        AppExecutors.io();
        AppExecutors.red();
    }

    @Test public void lasTareasCorrenDeVerdad() throws Exception {
        CountDownLatch io = new CountDownLatch(1), serie = new CountDownLatch(1), red = new CountDownLatch(1);
        AppExecutors.enIo(io::countDown);
        AppExecutors.enSerie(serie::countDown);
        AppExecutors.enRed(red::countDown);
        assertTrue("io no corrió", io.await(5, TimeUnit.SECONDS));
        assertTrue("serie no corrió", serie.await(5, TimeUnit.SECONDS));
        assertTrue("red no corrió", red.await(5, TimeUnit.SECONDS));
    }

    @Test public void elNombreDelHiloEsElQueSeEspera() throws Exception {
        CountDownLatch listo = new CountDownLatch(1);
        final String[] visto = new String[1];
        AppExecutors.serial().execute(() -> {
            visto[0] = Thread.currentThread().getName();
            listo.countDown();
        });
        assertTrue(listo.await(5, TimeUnit.SECONDS));
        assertEquals("rejilla-prep", visto[0]);
    }

    @Test public void siElPoolRechazaLaTareaSeCorreIgual() {
        // Un pool ya apagado: seguro() no puede dejar la tarea sin hacer.
        ExecutorService muerto = Executors.newSingleThreadExecutor();
        muerto.shutdown();
        CountDownLatch listo = new CountDownLatch(1);
        AppExecutors.seguro(muerto, listo::countDown);
        assertTrue("la tarea se perdió", listo.getCount() == 0);
    }
}
