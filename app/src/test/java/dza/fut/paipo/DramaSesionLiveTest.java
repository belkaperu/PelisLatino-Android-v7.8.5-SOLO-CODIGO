package dza.fut.paipo;

import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * v7.1 — MEDIDA REAL (necesita red): los 265 topicos del indice, en paralelo.
 * Sin red el test se SALTA (assumeTrue), nunca rompe el build: la evidencia de que
 * la extraccion并行 no pierde topicos es este numero, no una afirmacion.
 */
public class DramaSesionLiveTest {

    @Test public void extraeTodosLosTopicosEnParalelo() {
        // plantillas iniciales (el seed lo fabrica gltInicial(); sin prefs que lean).
        String sem = DramaSesion.conectarYSembrar(null);
        assumeTrue("sin red o el edge cerro la puerta: se salta la medida", sem != null && sem.contains("glt"));

        final AtomicInteger conCanales = new AtomicInteger();
        final AtomicInteger vistos = new AtomicInteger();
        long t0 = System.currentTimeMillis();
        DramaSesion.Resultado r = DramaSesion.extraer(sem, null, 0, new DramaSesion.Avance() {
            @Override public void enTopico(String t, List<DramaSesion.CanalDrama> cs, int total) {
                vistos.addAndGet(1);
                if (!cs.isEmpty()) conCanales.incrementAndGet();
            }
        });
        long ms = System.currentTimeMillis() - t0;

        System.out.println("MEDIDA topicos=" + vistos.get() + " conCanales=" + conCanales.get()
                + " canales=" + r.canales + " ms=" + ms);
        assumeTrue("el edge no solto los topicos: " + r.error, r.ok());
        assertTrue("pocos canales: " + r.canales, r.canales > 30000);
        assertTrue("faltan topicos: " + vistos.get(), vistos.get() == 265);
        // El edge de vez en cuando devuelve un topico vacio (medido: 264 de 265 con
        // 32.923 canales). Lo que este test vigila es que la extraccion EN PARALELO no
        // pierda topicos, no que el servidor este completo: se permiten unos pocos
        // vacios, pero no una mayoria.
        assertTrue("vacios de mas: " + conCanales.get() + " de " + vistos.get(),
                conCanales.get() >= vistos.get() - 5);
    }
}
