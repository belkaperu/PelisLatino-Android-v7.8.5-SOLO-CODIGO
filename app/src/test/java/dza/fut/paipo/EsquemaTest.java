package dza.fut.paipo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Los hosts de códigos FG se reparten entre http y https (fgcode.org atiende http y redirige
 * a fgcode.store, que es https), así que la app cambia de esquema sola cuando el fallo es de
 * conexión. Lógica pura: se comprueba en la JVM, sin red.
 */
public class EsquemaTest {

    @Test
    public void cambiaDeEsquemaEnAmbasDirecciones() {
        assertEquals("http://fgcode.org/link/api.php/get?code=232425",
                FuenteCanales.otroEsquema("https://fgcode.org/link/api.php/get?code=232425"));
        assertEquals("https://a.tv/l.m3u", FuenteCanales.otroEsquema("http://a.tv/l.m3u"));
        assertNull("sin esquema no hay nada que cambiar", FuenteCanales.otroEsquema("a.tv/l.m3u"));
        assertNull(FuenteCanales.otroEsquema(null));
    }

    @Test
    public void soloSeReintentaSiElFalloEsDeConexion() {
        assertTrue(FuenteCanales.esFalloDeConexion(new java.net.ConnectException("Failed to connect")));
        assertTrue(FuenteCanales.esFalloDeConexion(
                new java.io.IOException("x", new javax.net.ssl.SSLHandshakeException("no cipher"))));
        assertTrue(FuenteCanales.esFalloDeConexion(new java.net.SocketTimeoutException("timeout")));
        assertFalse("un 500 del servidor no se arregla cambiando de esquema",
                FuenteCanales.esFalloDeConexion(new java.io.IOException("HTTP 500")));
        assertFalse("si el host no existe, reintentar es perder el tiempo",
                FuenteCanales.esFalloDeConexion(new java.net.UnknownHostException("fgcode.org")));
        assertFalse(FuenteCanales.esFalloDeConexion(null));
    }
}
