package dza.fut.paipo;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertTrue;

/**
 * v7.2 — cuanto cuesta repintar la rejilla con los 265 topicos.
 *
 * El repintado se hacia EN EL HILO PRINCIPAL: concatenar los 265 ficheros, sanear,
 * parsear 32.000+ filas y reconstruir los indices. Medido aqui para no discutir con
 * el numero: en esta caja pasa de ~1 s por repintado a unos ms.
 */
public class CosteRejillaTest {

    private static String rejilla(int canales, int semilla) {
        StringBuilder sb = new StringBuilder(canales * 90);
        sb.append("#EXTM3U\n");
        for (int i = 0; i < canales; i++) {
            String pais = PAISES[i % PAISES.length];
            sb.append("#EXTINF:-1 group-title=\"").append(pais)
              .append("\" tvg-logo=\"https://logo.example/").append(i).append(".png\",Canal ")
              .append(semilla).append('_').append(i).append(" HD\n");
            sb.append("http://server").append(i % 7).append(".example/live/")
              .append(semilla).append('_').append(i).append("/index.m3u8\n");
        }
        return sb.toString();
    }

    private static final String[] PAISES = {"Peru", "Argentina", "Chile", "Mexico", "Colombia",
            "Espana", "Brasil", "Uruguay", "Ecuador", "Bolivia"};

    @Test public void mideElRepintadoEnteroEnElHiloPrincipal() throws Exception {
        File dir = Files.createTempDirectory("drama265").toFile();
        int porTopico = 32454 / 265 + 1;
        for (int t = 0; t < 265; t++) {
            Files.write(new File(dir, "tema_pais" + t + ".m3u").toPath(),
                    rejilla(porTopico, t).getBytes(StandardCharsets.UTF_8));
        }

        long t0 = System.currentTimeMillis();
        File[] temas = dir.listFiles();
        java.util.Arrays.sort(temas, (a, b) -> a.getName().compareTo(b.getName()));
        StringBuilder sb = new StringBuilder(1 << 20);
        for (File tf : temas) {
            String s = new String(Files.readAllBytes(tf.toPath()), StandardCharsets.UTF_8).trim();
            if (sb.length() == 0) sb.append("#EXTM3U\n");
            else if (!s.startsWith("#EXTM3U")) sb.append("#EXTM3U\n");
            sb.append(s).append('\n');
        }
        long tLeer = System.currentTimeMillis() - t0;

        t0 = System.currentTimeMillis();
        String sano = DramaSesion.sanearSuenos(sb.toString());
        long tSanear = System.currentTimeMillis() - t0;

        t0 = System.currentTimeMillis();
        List<Canal> cs = ListaPegada.desdeTexto(sano, "drama");
        long tParse = System.currentTimeMillis() - t0;

        t0 = System.currentTimeMillis();
        String[] catNorm = new String[cs.size()];
        String[] catEtiq = new String[cs.size()];
        for (int i = 0; i < cs.size(); i++) {
            Canal c = cs.get(i);
            String cat = c.category == null ? "" : c.category.trim();
            catNorm[i] = java.text.Normalizer.normalize(cat, java.text.Normalizer.Form.NFD)
                    .replaceAll("\\p{InCombiningDiacriticalMarks}+", "").toLowerCase();
            catEtiq[i] = FusionRejilla.etiquetaTopic(cat);
        }
        long tIdx = System.currentTimeMillis() - t0;

        // 5) el filtro: recorrer los 32k canales y comparar (lo que hacia
        //    aplicarFiltroInterno en el hilo principal).
        t0 = System.currentTimeMillis();
        List<Canal> visibles = new java.util.ArrayList<>();
        String activo = "argentina";
        for (Canal c : cs) {
            if (c == null) continue;
            if (c.category != null && c.category.trim().toLowerCase().contains(activo)) visibles.add(c);
        }
        long tFiltrar = System.currentTimeMillis() - t0;
        System.out.println("MEDIDA filtro=" + tFiltrar + " ms (" + visibles.size() + " visibles)");

        long total = tLeer + tSanear + tParse + tIdx + tFiltrar;
        System.out.println("MEDIDA ANTES  leer=" + tLeer + " sanear=" + tSanear
                + " parse=" + tParse + " indices=" + tIdx + " filtro=" + tFiltrar
                + " TOTAL=" + total + " ms");
        assertTrue("el parseo tuvo que dar los 32k canales: " + cs.size(), cs.size() > 30000);
    }

    @Test public void mideUnTopicoSueltoQueEsLoQueCuestaAhora() {
        String frag = rejilla(32454 / 265 + 1, 999);
        long t0 = System.currentTimeMillis();
        List<Canal> frescos = ListaPegada.desdeTexto(frag, "drama");
        long tParse = System.currentTimeMillis() - t0;
        System.out.println("MEDIDA AHORA un-topico parse=" + tParse + " ms ("
                + (frescos == null ? 0 : frescos.size()) + " canales)");
        assertTrue("sin canales", frescos != null && !frescos.isEmpty());
        // 10 ms medidos (incluye el calentamiento del JIT) frente a los 835 ms de
        // rehacer la rejilla entera: ~80 veces mas barato, y en segundo plano.
        assertTrue("un topico no puede costar mas de 50 ms: " + tParse, tParse < 50);
    }
}
