package dza.fut.paipo;

import java.util.ArrayList;
import java.util.List;

/**
 * v3.2.67 — la lista visible de la rejilla, retenida en memoria para zapear
 * de canal dentro del reproductor sin salir de él (el equivalente a nuestro
 * ChannelManager de dzasports, pero sin serializar cientos de canales por el
 * Intent, que tiene límite de tamaño).
 *
 * La siembra CanalAdapter al tocar un canal y la lee PlayerActivity. Si el
 * proceso muere y el reproductor se restaura, la lista queda vacía y la tira
 * de zapeo simplemente no aparece: degradación elegante.
 */
public final class ZapeoCanales {

    private ZapeoCanales() {}

    public static final List<Canal> canales = new ArrayList<>();
    public static int indiceActual = -1;

    /** Filtra solo los Canal de los items visibles y marca el elegido. */
    public static void fijar(List<?> items, Canal elegido) {
        canales.clear();
        indiceActual = -1;
        if (items != null) {
            for (Object o : items) {
                if (o instanceof Canal) {
                    if (o == elegido) indiceActual = canales.size();
                    canales.add((Canal) o);
                }
            }
        }
        if (indiceActual < 0 && elegido != null) {
            indiceActual = canales.size();
            canales.add(elegido);
        }
    }
}
