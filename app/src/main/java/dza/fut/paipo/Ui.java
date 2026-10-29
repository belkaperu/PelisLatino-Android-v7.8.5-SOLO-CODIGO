package dza.fut.paipo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * Utilidades de presentacion compartidas (port del repo PelisLatino-Android,
 * commit 82105f5 "Paises con bandera en la tira de categorias").
 */
public final class Ui {

    private Ui() { }

    /**
     * Bandera del pais como par de indicadores regionales: {@code "PE"} -> la pareja de indicadores regionales U+1F1F5 U+1F1EA.
     *
     * <p>Se usa el emoji y no una imagen por tres razones: ya viene en la fuente
     * del sistema (no hay que empaquetar 250 SVG ni pedirlas por red), se ve igual
     * en telefono y en TV, y no necesita permisos ni red. Si el aparato no tuviera
     * fuente de emoji, el peor caso es que se vean las dos letras.
     *
     * <p>Devuelve "" si el codigo no son dos letras, para no pintar basura.
     */
    @NonNull
    public static String banderaEmoji(@Nullable String iso) {
        if (iso == null || iso.length() != 2) return "";
        String codigo = iso.toUpperCase(Locale.ROOT);
        char a = codigo.charAt(0);
        char b = codigo.charAt(1);
        if (a < 'A' || a > 'Z' || b < 'A' || b > 'Z') return "";
        return new String(Character.toChars(0x1F1E6 + (a - 'A')))
                + new String(Character.toChars(0x1F1E6 + (b - 'A')));
    }
}
