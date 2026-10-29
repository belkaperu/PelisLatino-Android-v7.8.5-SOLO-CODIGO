package dza.fut.paipo;

import java.io.Serializable;
import java.util.List;

public class Canal implements Serializable {
    // Se recomienda agregar serialVersionUID para evitar advertencias
    private static final long serialVersionUID = 1L;

    public String title;
    public String image;
    public String category;
    /**
     * Calidad declarada en el propio nombre de la lista (``36 TV Huacho (720p)``).
     * La sacamos del título para que el tile no se llame "X (720p)" y la usamos como
     * etiqueta del servidor. No viene en el JSON de la app: se queda nula ahí.
     */
    public String calidad;
    public List<OpcionCanal> options;

    public static class OpcionCanal implements Serializable {
        private static final long serialVersionUID = 1L;
        public String label;
        public String url;
    }

    public static class RespuestaCanales {
        public List<Canal> canales;
    }
}