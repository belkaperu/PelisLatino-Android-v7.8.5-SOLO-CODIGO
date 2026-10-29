package dza.fut.paipo;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.graphics.drawable.Icon;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Rational;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.net.http.SslError;
import android.webkit.SslErrorHandler;
import android.webkit.CookieManager;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.Toast;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

@UnstableApi
public class PlayerActivity extends AppCompatActivity {

    /** Logcat. Faltaba y el módulo no compilaba (Log.w/e la usaban en 3 sitios). */
    private static final String TAG = "PlayerActivity";

    /** Pares (de,a) del "swap" del enlace Drama descifrado; null si no hay enlace Drama. */
    private java.util.List<String[]> dramaSwapPares;
    private ExoPlayer player;
    private PlayerView playerView;
    private WebView webViewFallback;
    private String urlIframeInicial;
    private View fallbackCustomView;
    private WebChromeClient.CustomViewCallback fallbackCustomCallback;

    // Stream directo recibido del detalle (sin pasar por el resolver/WebView)
    private String streamDirecto;
    private String streamCookies;
    private String streamReferer;
    private String streamOrigin;
    /** Cabeceras extra que pidió el canal (o la fuente): se mezclan en resolverStreamDirecto. */
    private String streamCabeceras;

    private DefaultHttpDataSource.Factory dataSourceFactory;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private int curaCiclos;   // v3.2.21: ciclos «aun no llega» seguidos (escape a los 3)
    @androidx.annotation.Nullable private static java.lang.ref.WeakReference<PlayerActivity> vivoCura;
    private ExecutorService executorService;

    private TextView tvDebugLog;
    private ScrollView scrollDebugLog;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());

    private boolean streamReady = false;
    private android.view.View filaServidores;
    private android.widget.LinearLayout contenedorServidores;
    private final java.util.List<String[]> opcionesServidor = new java.util.ArrayList<>();
    private final java.util.Set<Integer> opcionesFallidas = new java.util.HashSet<>();
    private int opcionActual = -1;
    // v3.2.67: retrato+paisaje estilo dzasports (cabecera, tira de zapeo, girar).
    private boolean esHorizontal = true;
    private boolean controlesYaAtados;
    private android.view.View cabeceraPlayer;
    private android.widget.TextView tvTituloPlayer;
    private String tituloCanalActual;
    private android.view.View tiraCanalesPlayer;
    private android.widget.HorizontalScrollView scrollCanalesPlayer;
    private android.widget.LinearLayout contenedorCanalesPlayer;

    // ── v7.5: la tira de zapeo se construye por VENTANAS, no entera ──
    /**
     * Tarjetas por tanda. La tira de zapeo recibe {@link ZapeoCanales#canales}, que
     * es la lista VISIBLE de la rejilla: con el catálogo completo eso son decenas de
     * miles de canales. Antes se construía una tarjeta (LinearLayout + ImageView +
     * TextView) y se lanzaba un {@code Glide.with(this)} por CADA canal, en el hilo
     * principal, dentro de onCreate. El logcat de un teléfono real lo enseñó:
     * el proceso se quedaba mudo ~10 s justo después de montar el player y el
     * sistema declaraba ANR. Ahora se construye un ventana alrededor del canal
     * actual, en segundo plano, y se alarga sola si el usuario llega al borde.
     */
    static final int ZAPEO_TANDA = 40;
    /** Índice real (en ZapeoCanales.canales) de cada tarjeta ya construida. */
    private final java.util.List<Integer> tiraIndices = new java.util.ArrayList<>();
    private int tiraDesde = 0;
    private int tiraHasta = 0;
    private boolean tiraConstruyendo = false;
    private boolean tiraScrollAtado = false;
    private android.widget.ImageButton btnPantalla;
    private android.widget.TextView tvTiempoActual;
    private android.widget.TextView tvTiempoTotal;
    private boolean webViewOcultoYaProbado = false;   // el WebView oculto se prueba una sola vez por título
    private boolean isResolving = false;
    /** Momento en que arrancó la resolución en curso (para el "perro guardián"). */
    private long resolucionInicio = 0L;
    private int errorRefreshCount = 0;
    private static final int MAX_REFRESH_RETRIES = 5;
    private long lastRefreshTime = 0;
    private static final long MIN_REFRESH_INTERVAL_MS = 2000;

    // --- Controles personalizados ---
    private View controlsOverlay;
    private ImageButton btnPlayPause, btnRewind, btnForward, btnVolume, btnRefresh, btnPip, btnCerrar;
    // v3.2.31: chip de calidad + estado del play nativo (glasbi->glbr->advanced)
    private android.widget.TextView chipCalidad;
    private volatile String streamOpcionesJson;
    private volatile String calidadElegida;
    private volatile String marcadorForzado;
    private volatile String agenteForzado;
    private volatile String canalActual;
    private SeekBar seekBar;
    private boolean isSeeking = false;
    private final Handler seekHandler = new Handler(Looper.getMainLooper());
    private Runnable seekRunnable;

    // --- PIP ---
    private boolean isInPipMode = false;
    private boolean entrandoEnPip = false;   // true entre enterPictureInPictureMode() y onPictureInPictureModeChanged()
    private boolean cerrandoDesdePip = false;
    private boolean enPrimerPlano = true;   // la app se ve (onResume) o esta detras (onStop)
    // v3.2.15: la cura headless es POR CANAL y con cooldown de 100 s (el boolean de
    // v3.2.13 bloqueaba reintentos legitimos y sus ramas eran mudas en el log), y el
    // player SE AUTO-REINTENTA a los 70 s: si la ENTREGA ya curo la fila, reproduce
    // sin que nadie toque nada.
    private String canalCurado;              // pid cuya cura esta en vuelo (null: ninguna)
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> curaTsMap =
            new java.util.concurrent.ConcurrentHashMap<>(); // v3.2.16: cooldown POR PROCESO
    private boolean curaReintento;             // re-resolucion silenciosa tras la cura
    private String dramaLiveEnJuego;          // fuente dramalive:// actual del player
    private final Runnable curaReintentoRun = new Runnable() {
        @Override public void run() { reintentaTrasCura(); }
    };
    private int intentosTraerAlFrente = 0;
    private BroadcastReceiver pipReceiver;

    // --- Un solo reproductor vivo a la vez + foco de audio ---
    /** Instancia del reproductor abierta ahora mismo (null = ninguna). */
    private static PlayerActivity instanciaActiva = null;
    private AudioManager audioManager = null;
    private AudioFocusRequest peticionFoco = null;
    private final AudioManager.OnAudioFocusChangeListener focoListener = cambio -> {
        if (cambio == AudioManager.AUDIOFOCUS_LOSS
                || cambio == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            // Otra app (u otro reproductor) quiere el audio: nosotros callamos.
            if (player != null && !isInPipMode) {
                try { player.pause(); } catch (Exception ignored) { }
            }
        }
    };
    /** Vistas ocultadas al entrar en PiP -> se restauran al salir. */
    private final Map<View, Integer> visibilidadPrePip = new LinkedHashMap<>();

    private static final String ACTION_PIP_CONTROL = "dza.fut.paipo.PIP_CONTROL";
    private static final String EXTRA_PIP_ACTION = "pip_action";
    private static final int PIP_ACTION_PLAY_PAUSE = 1;
    private static final int PIP_ACTION_FULLSCREEN = 2;   // salir del PiP y volver a pantalla completa
    private static final String ACTION_SALIR_PIP = "dza.fut.paipo.SALIR_PIP";

    // Auto-ocultar controles
    private static final long CONTROLS_TIMEOUT = 4000; // 4 segundos
    private final Runnable hideControlsRunnable = () -> {
        if (!isInPipMode && controlsOverlay != null) {
            controlsOverlay.setVisibility(View.GONE);
            aplicarVisibilidadExtra(false);
            stopSeekUpdate();
        }
    };

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        esHorizontal = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        setContentView(esHorizontal ? R.layout.activity_player
                : R.layout.activity_player_portrait);

        // ATRÁS (gesto, botón y "predictive back" de Android moderno):
        // se registra el callback moderno para que nunca se nos escape.
        try {
            getOnBackPressedDispatcher().addCallback(this,
                    new androidx.activity.OnBackPressedCallback(true) {
                        @Override
                        public void handleOnBackPressed() {
                            cerrarReproductor();
                        }
                    });
        } catch (Throwable t) {
            Log.w(TAG, "No se pudo registrar el callback de atrás: " + t.getMessage());
        }

        // SOLO UN REPRODUCTOR: si quedaba otro abierto (por ejemplo un canal y
        // después una película), se cierra YA para que no se pisen los audios.
        if (instanciaActiva != null && instanciaActiva != this) {
            log("⏹ Cerrando el reproductor anterior para que no se crucen los audios");
            instanciaActiva.cerrarReproductor();
        }
        instanciaActiva = this;

        // v3.2.67: orientación libre (retrato y paisaje, estilo dzasports);
        // el botón de pantalla completa gira a mano cuando haga falta.
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (getSupportActionBar() != null) getSupportActionBar().hide();

        playerView = findViewById(R.id.playerView);
        // Se vuelve a FILL, que es lo que tenia la app original. (Lo habia
        // cambiado a FIT por creer que FILL deformaba el video y no es asi:
        // FILL rellena la pantalla RECORTANDO, manteniendo la proporcion;
        // FIT es el que deja franjas negras a los lados.)
        playerView.setResizeMode(esHorizontal
                ? AspectRatioFrameLayout.RESIZE_MODE_FILL
                : AspectRatioFrameLayout.RESIZE_MODE_FIT);
        playerView.setUseController(false);



        urlIframeInicial = getIntent().getStringExtra("url_iframe_inicial");
        streamDirecto = getIntent().getStringExtra("stream_url");
        streamCookies = getIntent().getStringExtra("stream_cookies");
        streamReferer = getIntent().getStringExtra("stream_referer");
        streamOrigin = getIntent().getStringExtra("stream_origin");
        streamCabeceras = getIntent().getStringExtra("stream_headers");
        // "Canal desde Drama": si lo que llegó es un enlace cifrado `player.html#<hash>`,
        // la URL real va adentro. Se intenta aqui ademas de en la importacion para que
        // valga tambien un intent directo (PiP, "abrir con…") sin re-importar la lista.
        String fuenteDrama = !vacioTexto(streamDirecto) ? streamDirecto : urlIframeInicial;
        // Canales del captador in-app (v3.2.3): url esquema dramalive://<parametros> — la
        // url real del stream NO viene en el catalogo (el item del sitio trae url:"1");
        // se resuelve contra el redirector glbr del sitio en el momento de reproducir,
        // con la sesion que dejo el captador. Fresca siempre, nunca podrida.
        final String dramaLive = fuenteDrama != null && fuenteDrama.startsWith("dramalive://") ? fuenteDrama : null;
        dramaLiveEnJuego = dramaLive;   // v3.2.15: el auto-reintento necesita la fuente actual
        if (dramaLive != null) {
            streamDirecto = null;
            urlIframeInicial = null;
        }
        EnlaceDrama.Contenido drama = dramaLive == null
                ? EnlaceDrama.abrir(fuenteDrama, FuenteCanales.dramaKey(this)) : null;
        if (drama != null) {
            streamDirecto = drama.url;
            dramaSwapPares = EnlaceDrama.paresSwap(drama.texto);
            String swapInfo = dramaSwapPares.isEmpty() ? "sin swap"
                    : dramaSwapPares.get(0)[0] + "'" + dramaSwapPares.get(0)[1] + "'";
            log("Enlace Drama descifrado (clave '" + drama.clave + "', " + swapInfo + ") -> " + drama.url);
        }
        log("URL Inicial: " + urlIframeInicial);
        if (streamDirecto != null && !streamDirecto.isEmpty()) {
            log("Stream directo recibido: " + streamDirecto);
        }

        executorService = Executors.newSingleThreadExecutor();

        configurarSSLInseguroUniversal();
        hideSystemUI();
        setupMedia3Player();
        setupCustomControls();
        atarOverlaysPlayer();
        leerOpcionesDeIntent(getIntent());
        tituloCanalActual = getIntent().getStringExtra("canal_titulo");
        pintarTituloPlayer();
        pintarChipsServidores();
        pintarTiraCanales();
        setupWebViewFallback();
        registerPipReceiver();
        aplicarParamsPip();          // Android 12+: deja listo el auto-entrar en PiP
        if (dramaLive != null) {
            resolverDramaLive(dramaLive);
        } else if (streamDirecto != null && !streamDirecto.isEmpty()) {
            resolverStreamDirecto(streamDirecto, streamCookies, streamReferer, streamOrigin);
        } else {
            resolverStreamEnSegundoPlano();
        }
    }

    /** dramalive:// → glbr (red, hilo secundario) → stream real con el User-Agent que exige. */
    /** v3.2.15: a los 70 s de lanzada la cura el player se reintenta SOLO: si la
     * ENTREGA del captador ya dejo la fila con https real (cosecha + fila sintetica),
     * reproduce sin que el usuario toque nada; si aun no llego, re-resuelve el glbr
     * UNA vez en silencio — el cooldown de 100 s de arriba evita bucles de relanzamiento. */
    /** v3.2.16: le pide al captador vivo (su propio task) un poll cada 20 s: si este
     * player se cierra antes de la ENTREGA, el captador sigue y la fila queda curada
     * para el proximo tap. Best-effort: sin captador vivo, no hace nada. */
    private void relevarCuraAlCaptador() {
        try {
            DramaAutoActivity pv = DramaAutoActivity.instanciaViva();
            if (pv != null) pv.pedirRelevo(20_000L);
        } catch (Throwable ignore) { }
    }

    /** v3.2.21: cuando el captador ENTREGA, el player que espera la cura se despierta YA —
     *  antes esperaba ciego su poll de 70 s y parecia que la cura «no llegaba». */
    static void notificarEntrega() {
        PlayerActivity p = vivoCura == null ? null : vivoCura.get();
        if (p != null && p.canalCurado != null && !p.isFinishing()) {
            android.util.Log.d("BLABONGO_DEBUG", "entrega del captador: despierto al player ya");
            p.mainHandler.removeCallbacks(p.curaReintentoRun);
            p.mainHandler.post(p.curaReintentoRun);
        }
    }

    /** v3.2.23: pids a los que el headless ya agoto sin curar (server terco con el sueno).
     *  Un segundo headless de 2 min es tiempo perdido: se escapa directo al captador VISIBLE. */
    private static final java.util.concurrent.ConcurrentHashMap<String, Boolean> quemadosHeadless =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** v3.2.23: el captador aviso que la cosecha termino/plomazo sin curar este pid — quemarlo y,
     *  si hay un player esperando por ese canal, escapar YA al captador visible (no esperar mas
     *  ciclos ni relevar al plomazo). */
    static void notificarCuraFracasada(final String pid) {
        if (pid == null || pid.isEmpty()) return;
        quemadosHeadless.put(pid, Boolean.TRUE);
        PlayerActivity p = vivoCura == null ? null : vivoCura.get();
        if (p != null && !p.isFinishing() && pid.equals(p.canalCurado)) {
            p.mainHandler.post(() -> {
                if (p.isFinishing()) return;
                // v3.2.47 (log del usuario 01:06:36): el escape VISIBLE ya no existe — la
                // cosecha a pantalla completa cerro con 0 curados y revolvi6 la pantalla.
                // La quemadura queda apuntada y a los 25 s el player re-mira la fila; si no
                // hay cosecha en vuelo, el flujo normal lanza OTRA ronda headless (invisible),
                // que es la que reproduce el anuncio muted y sube runCount.
                android.util.Log.d("BLABONGO_DEBUG",
                        "captador agoto la cosecha sin curar " + pid + " — ronda headless extra en 25 s (todo en segundo plano)");
                p.curaCiclos = 0;
                p.mainHandler.removeCallbacks(p.curaReintentoRun);
                p.mainHandler.postDelayed(p.curaReintentoRun, 25_000L);
            });
        }
    }

    private void reintentaTrasCura() {
        if (isFinishing() || canalCurado == null) return;
        String filaUrl = null;
        try {
            String[] fr = DramaSesion.filaDeRejilla(FuenteCanales.dramaExtraIdo(this), canalCurado);
            if (fr != null && fr.length > 3) filaUrl = fr[3];
        } catch (Exception ignore) { }
        if (filaUrl != null && (filaUrl.startsWith("http://") || filaUrl.startsWith("https://"))) {
            log("cura LLEGO a la rejilla — reproduciendo sin salir: " + filaUrl);
            canalCurado = null;
            curaCiclos = 0;   // v3.2.21
            relevarCuraAlCaptador();
            streamDirecto = filaUrl; streamCookies = null; streamCabeceras = null;
            streamReferer = "https://web.dramalive.cc/";
            streamOrigin = "https://web.dramalive.cc";
            try { FuenteCanales.fijarUrlResuelta(this,
                    dramaLiveEnJuego != null ? dramaLiveEnJuego : filaUrl, filaUrl); }
            catch (Exception ignore) { }
            resolverStreamDirecto(filaUrl, null, streamReferer, streamOrigin);
        } else if (canalCurado != null && DramaSesion.curaFresca(curaTsMap, canalCurado,
                System.currentTimeMillis(), 100_000L)) {
            // v3.2.22 (log 13:01:30.69): el despertar post-ENTREGA re-resolvia el glbr, el
            // server repetia el sueno y el player volvia a dormirse 20 s — POSTs gratis para
            // el site mientras la cosecha sigue viva en el captador. Con cura EN VUELO solo
            // re-miro la rejilla, barato, cada 15 s; la cura entra por fusion en cuanto exista.
            log("aun no llega y la cura sigue en vuelo — miro la fila de nuevo en 15 s (sin repetir el glbr)");
            relevarCuraAlCaptador();
            mainHandler.removeCallbacks(curaReintentoRun);
            mainHandler.postDelayed(curaReintentoRun, 15_000L);
        } else {
            log("cura aun no llega a la fila — re-resuelvo el glbr en silencio");
            relevarCuraAlCaptador();
            if (++curaCiclos >= 6) {
                // v3.2.47 (log del usuario 01:06:28): antes, al TERCER ciclo en seco, esto
                // abria el captador VISIBLE «donde el reproductor del sitio si levanta». Con
                // la cuenta inmadura tampoco ahi levanta: la cosecha cerro con 0 curados y
                // el usuario vio giros de pantalla y anuncios a full. Ahora: ~90 s de polling
                // discreto (cada ciclo relanza headless si no hay cosecha en vuelo), y ahi se
                // para con aviso honesto. La maduracion sigue sola en segundo plano.
                canalCurado = null;
                curaCiclos = 0;
                try {
                    android.widget.Toast.makeText(this,
                            "Este canal todavia no madura en la cuenta — se cura solo en segundo plano; probalo en unos minutos",
                            android.widget.Toast.LENGTH_LONG).show();
                } catch (Exception ignore) { }
            }
            curaReintento = true;
            try { if (dramaLiveEnJuego != null) resolverDramaLive(dramaLiveEnJuego); }
            finally { curaReintento = false; }
        }
    }

    private void resolverDramaLive(String canal) {
        canalActual = canal;
        log("Canal DramaLive: resolviendo glbr…");
        Toast.makeText(this, "Resolviendo glbr…", Toast.LENGTH_SHORT).show();
        executorService.execute(new Runnable() {
            @Override public void run() {
                String[] ra = null;
                String err = null;
                final long t0N = System.currentTimeMillis();
                try {
                    String ses = FuenteCanales.sesionDrama(PlayerActivity.this);
                    log("post glbr: " + DramaSesion.diagnosticoGlbr(ses)); // v3.2.12
                    if (ses == null || ses.trim().isEmpty()) {
                        // v3.2.34: sin sesion NO se manda al usuario al Captador: nos
                        // auto-conectamos aqui mismo (glt empotrado) y seguimos. Fresh
                        // install reproduce un canal en el primer toque.
                        log("sin sesion drama — auto-conectando (glt nativo)…");
                        ses = DramaSesion.conectarYSembrar(getApplicationContext());
                        if (ses != null) {
                            FuenteCanales.guardarSesionDrama(getApplicationContext(), ses);
                            log("auto-conexion OK — semilla propia en disco");
                        } else {
                            throw new IllegalStateException("no hay sesion y la auto-conexion fallo (sin red?)");
                        }
                    }
                    if (marcadorForzado != null) {
                        // cambio de calidad: el marcador de la opcion elegida va directo al glbr
                        ra = DramaSesion.resolverMarcado(canal, ses, marcadorForzado,
                                agenteForzado == null ? "redirect" : agenteForzado);
                        log("glbr con calidad " + calidadElegida + " -> " + (ra == null ? "null" : ra[0]));
                    } else {
                        DramaSesion.Vivo vv = DramaSesion.resolverVivo(canal, ses);
                        if (vv != null) {
                            ra = new String[]{ vv.url, vv.ua };
                            streamOpcionesJson = vv.opciones;
                            log("play nativo (glasbi) en " + (System.currentTimeMillis() - t0N)
                                    + " ms · " + vv.url + " · opciones=" + vv.opciones);
                        } else {
                            ra = DramaSesion.resolverDramaLive(canal, ses);
                        }
                    }
                    if (ra == null) throw new IllegalStateException("el glbr no devolvio un stream utilizable");
                } catch (Exception e) {
                    err = e.getClass().getSimpleName() + ": " + e.getMessage();
                }
                final String[] rr = ra;
                final String er = err;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (rr == null) {
                            // v3.2.6: el fallo del glbr NUNCA es silencioso: toast con el
                            // motivo exacto (el «no se resuelven» anterior moria en logcat)
                            // y plan B: el sitio en el WebView — ahi el usuario juega el
                            // canal una vez, los ganchos del captador ven el glbr propio del
                            // sitio y hacen el BACKFILL de la url real en la rejilla.
                            log("glbr fallo: " + er);
                            Toast.makeText(PlayerActivity.this,
                                    "No se resolvió el canal: " + er + " — abriendo el sitio; jugalo ahí una vez y reintenta",
                                    Toast.LENGTH_LONG).show();
                            streamDirecto = null;
                            if (urlIframeInicial == null || urlIframeInicial.isEmpty())
                                urlIframeInicial = "https://web.dramalive.cc/";
                            resolverStreamEnSegundoPlano();
                            return;
                        }
                        if (rr[1] != null && !rr[1].isEmpty()) streamCabeceras = "User-Agent: " + rr[1];
                        streamReferer = "https://web.dramalive.cc/";
                        streamOrigin = "https://web.dramalive.cc";
                        log("glbr resuelto -> " + rr[0]);
                        mostrarChipCalidad();
                        if (rr[0].contains("update.mp4")) {
                            // SUELO medido (logcat del usuario 2026-10-10 13:51 y sandbox):
                            // el glbr de esta plantilla (isPremium:false, user_id notloggedin)
                            // contesta el clip de relleno para canales restringidos. NO se
                            // reproduce el muneco: plan B al sitio, donde el backfill del
                            // captador aprende la url buena si el sitio si la tiene.
                            log("glbr devolvio el sueno update.mp4 — curacion headless (captador invisible, prior este canal)");
                            // v3.2.11: el salto automatico al Captador cada vez que el server
                            // daba el sueno se sentia como «el play se va a drama» (queja del
                            // usuario 16:07). Ahora: aviso claro; la cura (plantilla de 20
                            // campos + cookies) ya viajo con la ENTREGA, y el Captador queda
                            // a un boton de distancia para quien la quiera forzar.
                            // v3.2.13: ante el sueno, LAZO el Captador en modo headless (ventana
                            // de 2x2 invisible, se cierra solo a los 45 s). La semilla ahora incluye
                            // la plantilla glbrReq -> auto() llena filas y la COSECHA arranca sin
                            // interaccion; este canal va PRIORITARIO y su cura dispara ENTREGA
                            // force al instante. El usuario solo re-toca play un minuto despues.
                            String sesCur = FuenteCanales.sesionDrama(PlayerActivity.this);
                            final String pid = DramaSesion.idDeCanal(canal);
                            boolean hayPlantilla = DramaSesion.tienePlantillaGlbr(sesCur);
                            long curaAhora = System.currentTimeMillis();
                            // v3.2.16: cooldown POR CANAL a nivel proceso — el flag de instancia
                            // renacia con cada tap y permitio lanzar el captador 2x al mismo
                            // canal en 27 s (log 19:06, alkaas2).
                            boolean curaEnVuelo = DramaSesion.curaFresca(curaTsMap, pid, curaAhora, 100_000L);
                            log("cura headless: pid=" + pid + " · plantilla=" + hayPlantilla
                                    + (curaEnVuelo ? " · EN VUELO (no relanzo)"
                                       : (pid != null && hayPlantilla) ? " · LANZANDO" : " · NO se puede lanzar"));
                            DramaAutoActivity capViva = DramaAutoActivity.instanciaViva();
                            if (pid != null && capViva != null && pid.equals(capViva.priorViva())
                                    && !capViva.isFinishing()) {
                                // v3.2.23 (log 23:31:33.917): con el captador VIVO curando este
                                // mismo pid se relanzaba el LANZAR; NEW_TASK reciclaba la instancia
                                // y el plomazo viejo la remataba a los 10 s. No relanzar: solo
                                // re-mirar la fila, que la cosecha sigue corriendo adentro.
                                canalCurado = pid;
                                vivoCura = new java.lang.ref.WeakReference<>(PlayerActivity.this);
                                log("captador VIVO ya cura " + pid + " — no lo relanzo, miro la fila cada 15 s");
                                try { capViva.pedirRelevo(20_000L); } catch (Throwable ignore) { }
                                mainHandler.removeCallbacks(curaReintentoRun);
                                mainHandler.postDelayed(curaReintentoRun, 15_000L);
                                return;
                            }
                            // v3.2.47: fuera la escalada QUEMADO -> captador VISIBLE. Quem6 una
                            // ronda headless => el server sigue dando sueno (cuenta inmadura);
                            // abrir la pantalla completa no curo NADA (log 01:06: «cosecha fin:
                            // 0/1 reales · curado=0») y molesto con rotaciones. El flujo normal
                            // de abajo lanza otra ronda headless cuando no hay cosecha en vuelo,
                            // y el polling de 15 s re-mira la fila mientras tanto.
                            if (pid != null && hayPlantilla && !curaEnVuelo) {
                                canalCurado = pid; curaTsMap.put(pid, curaAhora);
                                vivoCura = new java.lang.ref.WeakReference<>(PlayerActivity.this);   // v3.2.21
                                try {
                                    Intent i = new Intent(PlayerActivity.this, DramaAutoActivity.class);
                                    // v3.2.16: LA CURA VIVE EN SU PROPIO TASK. El ⏹ del player hace
                                    // finishAndRemoveTask() y se llevaba por delante al captador
                                    // invisible del mismo task (log 19:06: LANZADA 19:06:33 -> cerrar
                                    // 19:06:55 -> jamas ENTREGA). Con NEW_TASK + taskAffinity ":cura"
                                    // (manifest) cerrar el player NO interrumpe la cosecha; la
                                    // ventana 2x2 ademas queda fuera de Recents.
                                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                    i.putExtra(DramaAutoActivity.EXTRA_HEADLESS, true);
                                    i.putExtra(DramaAutoActivity.EXTRA_PRIOR, pid);
                                    // v3.2.14: si el canal jugado no esta entre las filas del topic
                                    // que la pagina cargue (lo normal en headless), la cosecha debe
                                    // SINTETIZARLO — y para eso lleva su nombre/logo/group reales,
                                    // leidos de la rejilla guardada. Sin identidad, desdePuente la
                                    // descartaria y la cura no llegaría al grid.
                                    if (pid != null) {
                                        String[] fr = DramaSesion.filaDeRejilla(
                                                FuenteCanales.dramaExtraIdo(PlayerActivity.this), pid);
                                        if (fr != null) {
                                            i.putExtra(DramaAutoActivity.EXTRA_PRIOR_NOMBRE, fr[0]);
                                            if (!fr[1].isEmpty()) i.putExtra(DramaAutoActivity.EXTRA_PRIOR_LOGO, fr[1]);
                                            if (!fr[2].isEmpty()) i.putExtra(DramaAutoActivity.EXTRA_PRIOR_GRUPO, fr[2]);
                                        }
                                    }
                                    startActivity(i);
                                    log("cura LANZADA para " + pid + " — captador invisible corriendo, plomazo 120 s");
                                    mainHandler.removeCallbacks(curaReintentoRun);
                                    mainHandler.postDelayed(curaReintentoRun, 70_000L);
                                    if (!curaReintento) Toast.makeText(PlayerActivity.this,
                                            "El server dio relleno — lo curo en segundo plano y reintento SOLO en ~1 minuto. No toques nada.",
                                            Toast.LENGTH_LONG).show();
                                } catch (Exception eLaunch) {
                                    log("curacion headless no arranco: " + eLaunch);
                                    canalCurado = null;
                                    if (!curaReintento) Toast.makeText(PlayerActivity.this,
                                            "El server dio relleno a este canal. Jugalo una vez desde el Captador (boton de Canales) para curarlo.",
                                            Toast.LENGTH_LONG).show();
                                }
                            } else if (curaEnVuelo) {
                                // v3.2.16: hay una pasada en vuelo para ESTE canal (empezada hace
                                // <100 s, quizas desde otra apertura del player): se toma el relevo
                                // y el player se autopolling a los 20 s — si para entonces la
                                // ENTREGA curo la fila, al volver a tocar suena directo.
                                canalCurado = pid;
                                mainHandler.removeCallbacks(curaReintentoRun);
                                mainHandler.postDelayed(curaReintentoRun, 20_000L);
                                log("cura EN VUELO para " + pid + " — este player se re-intentara solo en 20 s");
                            } else if (!curaReintento) {
                                Toast.makeText(PlayerActivity.this,
                                        "Este canal esta en clip de relleno para tu sesion actual. Se cura jugando un canal en el Captador (boton de Canales) — la cosecha repara la rejilla sola.",
                                        Toast.LENGTH_LONG).show();
                            }
                            return;
                        }
                        if (canalCurado != null) {  // v3.2.15: este canal ya curo — cancela el auto-reintento
                            canalCurado = null;
                            mainHandler.removeCallbacks(curaReintentoRun);
                        }
                        try { FuenteCanales.fijarUrlResuelta(PlayerActivity.this, canal, rr[0]); }
                        catch (Exception ignore) { }
                        // plan B disponible si el stream real fallara despues (el log mostraba
                        // "No hay URL valida que resolver" porque la habiamos vaciado):
                        urlIframeInicial = "https://web.dramalive.cc/";
                        resolverStreamDirecto(rr[0], null, streamReferer, streamOrigin);
                    }
                });
            }
        });
    }

    // --------------- CONTROLES PERSONALIZADOS ---------------
    @SuppressLint("ClickableViewAccessibility")
    /** isEmpty sin depender de TextUtils (que en los tests de JVM esta stubbeado). */
    private static boolean vacioTexto(String s) {
        return s == null || s.trim().isEmpty();
    }

    private void setupCustomControls() {
        controlsOverlay = findViewById(R.id.centerControlsLayout);
        btnPlayPause = findViewById(R.id.btnPlayPause);
        btnRewind = findViewById(R.id.btnRewind);
        btnForward = findViewById(R.id.btnForward);
        btnVolume = findViewById(R.id.btnVolume);
        btnRefresh = findViewById(R.id.btnRefresh);
        btnPip = findViewById(R.id.btnPip);
        btnCerrar = findViewById(R.id.btnCerrar);
        btnPantalla = findViewById(R.id.btnFullscreen);
        tvTiempoActual = findViewById(R.id.tvTiempoActual);
        tvTiempoTotal = findViewById(R.id.tvTiempoTotal);
        actualizarIconoPantalla();
        chipCalidad = findViewById(R.id.chipCalidad);
        if (chipCalidad != null) {
            chipCalidad.setVisibility(View.GONE);
            chipCalidad.setOnClickListener(v -> mostrarMenuCalidad());
        }
        if (!controlesYaAtados && marcadorForzado == null) { calidadElegida = null; streamOpcionesJson = null; }   // v3.2.67: al girar no se olvida la calidad elegida
        seekBar = findViewById(R.id.seekBar);
        if (!hayControlesPropios()) return;   // v7.8.5: paisaje, controles estandar

        // Play/Pause
        if (btnPlayPause != null) {
            btnPlayPause.setOnClickListener(v -> {
                if (player != null) {
                    if (player.isPlaying()) player.pause();
                    else player.play();
                }
                updatePlayPauseButton();
                actualizarAccionesPip();   // refresca el boton play/pausa del PiP
                resetControlsTimeout();
            });
        }

        // Retroceder 10 segundos
        if (btnRewind != null) {
            btnRewind.setOnClickListener(v -> {
                if (player != null) {
                    long pos = player.getCurrentPosition() - 10000;
                    player.seekTo(Math.max(pos, 0));
                }
                resetControlsTimeout();
            });
        }

        // Adelantar 10 segundos
        if (btnForward != null) {
            btnForward.setOnClickListener(v -> {
                if (player != null) {
                    long pos = player.getCurrentPosition() + 10000;
                    long dur = player.getDuration();
                    if (dur > 0) pos = Math.min(pos, dur);
                    player.seekTo(pos);
                }
                resetControlsTimeout();
            });
        }

        // Volumen (toggle silencio)
        if (btnVolume != null) {
            btnVolume.setOnClickListener(v -> {
                if (player != null) {
                    float vol = player.getVolume();
                    if (vol > 0) {
                        player.setVolume(0f);
                        btnVolume.setImageResource(R.drawable.ic_volume_mute);
                    } else {
                        player.setVolume(1f);
                        btnVolume.setImageResource(R.drawable.ic_volume);
                    }
                }
                resetControlsTimeout();
            });
        }

        // Refrescar stream
        if (btnRefresh != null) {
            btnRefresh.setOnClickListener(v -> {
                if (streamDirecto != null && !streamDirecto.isEmpty()) {
                    resolverStreamDirecto(streamDirecto, streamCookies, streamReferer, streamOrigin);
                } else {
                    resolverStreamEnSegundoPlano();
                }
                resetControlsTimeout();
            });
        }

        // PiP
        if (btnPip != null) {
            btnPip.setOnClickListener(v -> {
                if (!soportaPip()) {
                    log("⚠️ Este dispositivo no soporta Picture-in-Picture");
                    Toast.makeText(this, "PiP no disponible en este dispositivo", Toast.LENGTH_SHORT).show();
                } else if (!hayVideoNativo()) {
                    log("⚠️ Todavía no hay video nativo: no se puede entrar en PiP");
                    Toast.makeText(this, "Espera a que cargue el video", Toast.LENGTH_SHORT).show();
                } else {
                    entrarEnPip();
                }
                resetControlsTimeout();
            });
        }

        // Girar a mano (v3.2.67): alterna la orientación pedida
        if (btnPantalla != null) {
            btnPantalla.setOnClickListener(v -> {
                setRequestedOrientation(esHorizontal
                        ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                        : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                resetControlsTimeout();
            });
        }

        // Cerrar: corta el audio y sale del reproductor
        if (btnCerrar != null) {
            btnCerrar.setOnClickListener(v -> {
                log("⏹ Botón cerrar pulsado");
                cerrarReproductor();
            });
        }

        // SeekBar
        if (seekBar != null) {
            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                    isSeeking = true;
                    stopSeekUpdate();
                    // Mantener controles visibles mientras arrastra
                    mainHandler.removeCallbacks(hideControlsRunnable);
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    if (player != null) {
                        long duration = player.getDuration();
                        if (duration > 0) {
                            long newPosition = (duration * seekBar.getProgress()) / 1000L;
                            player.seekTo(newPosition);
                        }
                    }
                    isSeeking = false;
                    startSeekUpdate();
                    resetControlsTimeout();
                }
            });
        }

        // Mostrar/ocultar controles al tocar la superficie del video
        playerView.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                if (controlsOverlay.getVisibility() == View.VISIBLE) {
                    hideControlsImmediately();
                } else {
                    showControls();
                }
                return true;
            }
            return false;
        });

        controlesYaAtados = true;
        // Mostrar controles al inicio
        showControls();
    }

    /**
     * v7.8.5 — en paisaje van los controles ESTANDAR de media3 (replica del
     * player de dzasports), asi que el layout no trae ninguno de estos botones
     * y todos los findViewById de arriba devuelven null. Sin este corte, el
     * primer setOnClickListener reventaria con NPE.
     */
    private boolean hayControlesPropios() {
        return controlsOverlay != null;
    }

    private void showControls() {
        if (controlsOverlay != null && !isInPipMode) {
            controlsOverlay.setVisibility(View.VISIBLE);
            aplicarVisibilidadExtra(true);
            updatePlayPauseButton();
            resetControlsTimeout();
            startSeekUpdate();
        }
    }

    private void hideControlsImmediately() {
        mainHandler.removeCallbacks(hideControlsRunnable);
        if (controlsOverlay != null) controlsOverlay.setVisibility(View.GONE);
        aplicarVisibilidadExtra(false);
        stopSeekUpdate();
    }

    private void resetControlsTimeout() {
        mainHandler.removeCallbacks(hideControlsRunnable);
        mainHandler.postDelayed(hideControlsRunnable, CONTROLS_TIMEOUT);
    }

    private void startSeekUpdate() {
        stopSeekUpdate();
        seekRunnable = new Runnable() {
            @Override
            public void run() {
                if (player != null && !isSeeking && seekBar != null
                        && controlsOverlay != null
                        && controlsOverlay.getVisibility() == View.VISIBLE) {
                    long duration = player.getDuration();
                    long position = player.getCurrentPosition();
                    if (tvTiempoActual != null) tvTiempoActual.setText(formatoTiempo(position));
                    if (duration > 0) {
                        int progress = (int) (position * 1000L / duration);
                        seekBar.setProgress(progress);
                        if (tvTiempoTotal != null) tvTiempoTotal.setText(formatoTiempo(duration));
                    } else if (tvTiempoTotal != null) {
                        tvTiempoTotal.setText("--:--");
                    }
                }
                seekHandler.postDelayed(this, 200);
            }
        };
        seekHandler.post(seekRunnable);
    }

    private void stopSeekUpdate() {
        if (seekRunnable != null) {
            seekHandler.removeCallbacks(seekRunnable);
        }
    }

    private void updatePlayPauseButton() {
        if (btnPlayPause != null && player != null) {
            btnPlayPause.setImageResource(player.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
        }
    }

    // --------------- PICTURE IN PICTURE ---------------

    /** El dispositivo soporta PiP (Android 8+ y con la feature del sistema). */
    private boolean soportaPip() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
    }

    /** Solo tiene sentido entrar en PiP si hay video nativo (no el WebView). */
    private boolean hayVideoNativo() {
        return player != null && streamReady
                && (webViewFallback == null || webViewFallback.getVisibility() != View.VISIBLE);
    }

    private boolean estaEnPipSistema() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode();
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (isInPipMode || entrandoEnPip) return;
        if (soportaPip() && hayVideoNativo() && player != null && player.isPlaying()) {
            entrarEnPip();
        }
    }

    @Override
    public void onBackPressed() {
        // ATRÁS SIEMPRE CIERRA el reproductor y corta el audio.
        // Antes, si estaba reproduciendo, volvía a entrar en PiP y no había
        // forma de salir. Para la ventanita flotante está el botón PiP o el
        // botón de inicio (que sí puede entrar en PiP).
        cerrarReproductor();
    }

    /**
     * Cierra el reproductor de verdad: para el audio, libera ExoPlayer, suelta
     * el foco de audio y termina la Activity.
     */
    protected void cerrarReproductor() {
        log("⏹ Cerrando reproductor");
        cerrandoDesdePip = true;
        liberarPlayer();
        if (instanciaActiva == this) instanciaActiva = null;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) finishAndRemoveTask();
            else finish();
        } catch (Exception e) {
            try { finish(); } catch (Exception ignored) { }
        }
    }

    /**
     * Corta el audio de raíz: para la reproducción, libera el reproductor y
     * suelta el foco de audio. Se llama al cerrar y al abrir otro reproductor,
     * para que nunca queden dos sonando a la vez.
     */
    protected void liberarPlayer() {
        try {
            if (player != null) {
                try { player.setPlayWhenReady(false); } catch (Exception ignored) { }
                try { player.stop(); } catch (Exception ignored) { }
                try { player.release(); } catch (Exception ignored) { }
                player = null;
            }
        } catch (Exception ignored) { }
        abandonarFocoAudio();
        // Los WebViews ocultos del resolver cargan el embed y su reproductor
        // arranca solo: si no se destruyen, el audio sigue sonando detrás.
        try { PelisStreamResolver.destruirWebViewsActivos(); } catch (Throwable ignored) { }
        try { StreamResolver.destruirWebViewsActivos(); } catch (Throwable ignored) { }
        if (webViewFallback != null) {
            try {
                webViewFallback.stopLoading();
                webViewFallback.loadUrl("about:blank");
            } catch (Exception ignored) { }
        }
    }

    /**
     * Si el reproductor se liberó al cerrar (player == null) lo vuelve a crear.
     * Imprescindible porque esta Activity es singleTask: al abrir otra película
     * Android puede reutilizar la instancia (onNewIntent) en lugar de crear una
     * nueva, y sin esto la segunda película no sonaba nunca.
     */
    private boolean asegurarPlayer() {
        if (player != null) return true;
        if (isFinishing()) return false;
        try {
            cerrandoDesdePip = false;
            setupMedia3Player();
            log("♻️ Reproductor recreado para el nuevo título");
        } catch (Throwable t) {
            Log.e(TAG, "No se pudo recrear el reproductor: " + t.getMessage(), t);
        }
        return player != null;
    }

    /** Si el executor se apagó al salir, se crea uno nuevo. */
    private void asegurarExecutor() {
        if (executorService == null || executorService.isShutdown() || executorService.isTerminated()) {
            executorService = Executors.newSingleThreadExecutor();
        }
    }

    /** Pide el foco de audio: si otra app está sonando, la calla. */
    @SuppressWarnings("deprecation")
    private void pedirFocoAudio() {
        try {
            if (audioManager == null) {
                audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            }
            if (audioManager == null) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (peticionFoco == null) {
                    android.media.AudioAttributes attrs =
                            new android.media.AudioAttributes.Builder()
                                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                                    .build();
                    peticionFoco = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                            .setAudioAttributes(attrs)
                            .setOnAudioFocusChangeListener(focoListener)
                            .build();
                }
                audioManager.requestAudioFocus(peticionFoco);
            } else {
                audioManager.requestAudioFocus(focoListener, AudioManager.STREAM_MUSIC,
                        AudioManager.AUDIOFOCUS_GAIN);
            }
        } catch (Exception ignored) { }
    }

    @SuppressWarnings("deprecation")
    private void abandonarFocoAudio() {
        if (audioManager == null) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (peticionFoco != null) audioManager.abandonAudioFocusRequest(peticionFoco);
            } else {
                audioManager.abandonAudioFocus(focoListener);
            }
        } catch (Exception ignored) { }
    }

    private void entrarEnPip() {
        if (!soportaPip() || isInPipMode) return;
        try {
            entrandoEnPip = true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                enterPictureInPictureMode(construirParamsPip());
            }
        } catch (Exception e) {
            entrandoEnPip = false;
            log("⚠️ No se pudo entrar en PiP: " + e.getMessage());
        }
    }

    @SuppressLint("NewApi")
    private PictureInPictureParams construirParamsPip() {
        PictureInPictureParams.Builder b = new PictureInPictureParams.Builder()
                .setAspectRatio(relacionAspectoVideo())
                .setActions(accionesPip());
        Rect r = rectOrigenPip();
        if (r != null) b.setSourceRectHint(r);      // animacion desde el video
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.setAutoEnterEnabled(hayVideoNativo());  // Android 12+: gesto de inicio -> PiP
            b.setSeamlessResizeEnabled(false);
        }
        return b.build();
    }

    /** Prepara el auto-entrar ANTES de salir de la app (Android 12+). */
    private void aplicarParamsPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !soportaPip()) return;
        try {
            setPictureInPictureParams(construirParamsPip());
        } catch (Exception ignored) { }
    }

    /** Refresca los botones del PiP sin volver a entrar en el modo. */
    private void actualizarAccionesPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        if (!isInPipMode && !entrandoEnPip) return;
        try {
            setPictureInPictureParams(new PictureInPictureParams.Builder()
                    .setAspectRatio(relacionAspectoVideo())
                    .setActions(accionesPip())
                    .build());
        } catch (Exception ignored) { }
    }

    /** Piso de la relacion del PiP: 1.0 = SIEMPRE landscape (nunca una ventanita
     *  flotante vertical). Pedido del usuario: «el pip siempre en landscap debe ser». */
    private static final float PIP_MIN_LANDSCAPE = 1.0f;
    /** Techo que impone Android para la ventanita. */
    private static final float PIP_MAX = 2.390000f;

    /**
     * Relacion de aspecto del PiP a partir del tamaño del video. Puro (sin Android)
     * para poder fijarlo con un test: «el pip siempre en landscap debe ser».
     *
     * <p>Se usa la real del video, pero JAMAS por debajo de 1.0: si el video es
     * vertical (o todavia no se conocio su tamaño) la ventanita sale landscape y el
     * video se ve letterboxeado dentro. Antes el piso era el de Android (0.418) y un
     * canal vertical daba un PiP alto y estrecho.
     */
    static float relacionPip(int ancho, int alto) {
        if (ancho <= 0 || alto <= 0) return 16f / 9f;   // sin datos: 16:9
        float r = (float) ancho / (float) alto;
        if (r < PIP_MIN_LANDSCAPE) r = PIP_MIN_LANDSCAPE;   // landscape obligatorio
        if (r > PIP_MAX) r = PIP_MAX;                       // limite de Android
        return r;
    }

    /** Relacion de aspecto del video real, con el piso landscape del PiP. */
    private Rational relacionAspectoVideo() {
        int w = 16, h = 9;
        if (player != null) {
            androidx.media3.common.VideoSize vs = player.getVideoSize();
            if (vs != null && vs.width > 0 && vs.height > 0) {
                w = vs.width;
                h = vs.height;
            }
        }
        float r = relacionPip(w, h);
        if (r <= 1.0f && w < h) {
            android.util.Log.d("BLABONGO_DEBUG", "PiP: video " + w + "x" + h
                    + " es vertical — ventanita forzada a landscape");
        }
        return new Rational(Math.round(r * 1000), 1000);
    }

    private Rect rectOrigenPip() {
        if (playerView == null) return null;
        int[] loc = new int[2];
        playerView.getLocationOnScreen(loc);
        return new Rect(loc[0], loc[1],
                loc[0] + playerView.getWidth(), loc[1] + playerView.getHeight());
    }

    /** Botones visibles dentro de la ventana flotante. */
    private java.util.List<RemoteAction> accionesPip() {
        java.util.List<RemoteAction> acciones = new java.util.ArrayList<>();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return acciones;
        boolean reproduciendo = player != null && player.isPlaying();
        acciones.add(new RemoteAction(
                Icon.createWithResource(this, reproduciendo ? R.drawable.ic_pause : R.drawable.ic_play),
                reproduciendo ? "Pausar" : "Reproducir",
                reproduciendo ? "Pausar" : "Reproducir",
                pendingIntentPip(PIP_ACTION_PLAY_PAUSE)));
        // Ojo: aqui ANTES habia un boton "Cerrar" (icono de X) que cerraba el
        // reproductor y borraba la tarea. La gente lo pulsaba para volver a
        // pantalla completa y se le cerraba todo. Ahora este boton SACA del
        // PiP y devuelve la app a pantalla completa; para cerrar del todo
        // esta el boton ATRAS una vez fuera del PiP.
        acciones.add(new RemoteAction(
                Icon.createWithResource(this, android.R.drawable.ic_menu_view),
                "Pantalla completa",
                "Sale del PiP y vuelve a pantalla completa",
                pendingIntentPip(PIP_ACTION_FULLSCREEN)));
        return acciones;
    }

    private PendingIntent pendingIntentPip(int accion) {
        Intent i = new Intent(ACTION_PIP_CONTROL).setPackage(getPackageName());
        i.putExtra(EXTRA_PIP_ACTION, accion);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(this, accion, i, flags);
    }

    private void registerPipReceiver() {
        pipReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (intent == null || !ACTION_PIP_CONTROL.equals(intent.getAction())) return;
                int accion = intent.getIntExtra(EXTRA_PIP_ACTION, 0);
                if (accion == PIP_ACTION_PLAY_PAUSE) {
                    alternarPlayPause();
                    actualizarAccionesPip();
                } else if (accion == PIP_ACTION_FULLSCREEN) {
                    salirDePip();
                }
            }
        };
        IntentFilter f = new IntentFilter(ACTION_PIP_CONTROL);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(pipReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(pipReceiver, f);
            }
        } catch (Exception e) {
            log("⚠️ No se pudo registrar el receptor de PiP: " + e.getMessage());
        }
    }

    private void alternarPlayPause() {
        if (player == null) return;
        if (player.isPlaying()) player.pause();
        else player.play();
        updatePlayPauseButton();
    }

    /**
     * Saca la app del PiP y la devuelve a pantalla completa (botón de la
     * ventanita flotante).
     *
     * No existe una llamada directa para salir del modo PiP: lo que lo saca es
     * traer la tarea al frente con REORDER_TO_FRONT. El intent lleva una marca
     * (ACTION_SALIR_PIP) para que onNewIntent sepa que no es un título nuevo y
     * no toque ni el intent original ni el stream que está sonando.
     */
    private void salirDePip() {
        log("⤢ Saliendo del PiP: vuelta a pantalla completa");
        Intent volver = new Intent(this, PlayerActivity.class);
        volver.setAction(ACTION_SALIR_PIP);
        volver.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        // CLAVE: esta Activity es launchMode="singleTask", y en ese modo
        // lanzar el intent desde la propia Activity NO trae la app al primer
        // plano (se queda detras del escritorio y parece que se ha cerrado
        // todo). Hay que lanzarlo desde el contexto de la APLICACION.
        try {
            android.content.Context ctx = getApplicationContext();
            if (ctx == null) ctx = this;
            ctx.startActivity(volver);
            return;
        } catch (Throwable t) {
            log("⚠️ Reintento para volver al primer plano: " + t.getMessage());
        }
        // Plan B (Android 14+): desde segundo plano el sistema puede bloquear
        // el arranque; con un PendingIntent con permiso explícito se permite.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                android.app.ActivityOptions op = android.app.ActivityOptions.makeBasic();
                op.setPendingIntentBackgroundActivityStartMode(
                        android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                PendingIntent pi = PendingIntent.getActivity(this, 77, volver,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE,
                        op.toBundle());
                pi.send();
                return;
            }
        } catch (Throwable t) {
            log("⚠️ Reintento 2 para volver al primer plano: " + t.getMessage());
        }
        // Plan C: por si todo lo anterior se bloquea.
        try {
            startActivity(volver);
        } catch (Throwable t) {
            log("⚠️ No se pudo salir del PiP: " + t.getMessage());
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode,
                                              @NonNull Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        isInPipMode = isInPictureInPictureMode;
        entrandoEnPip = false;
        if (isInPictureInPictureMode) {
            // En PiP SOLO se ve el vídeo: se oculta absolutamente todo lo demás
            // (controles, panel de depuración, WebView, anuncios...).
            ocultarTodoMenosElVideo();
            if (filaServidores != null) filaServidores.setVisibility(View.GONE);   // v3.2.66
            stopSeekUpdate();
            hideSystemUI();
            actualizarAccionesPip();

            // Comportamiento ESTÁNDAR de Android: al entrar en PiP la
            // aplicación SE ESCONDE y solo queda la ventanita flotante; al
            // salir del PiP la app vuelve a pantalla completa.
            //
            // Para esconderla NO vale moveTaskToBack(): en muchos móviles
            // (Samsung, Xiaomi, Android 12+) destruye la ventanita del PiP,
            // y entonces se veía la app a pantalla completa y el reproductor
            // flotante desaparecía, justo al revés de lo que tiene que pasar.
            //
            // Lo que hace el sistema es ir al escritorio: es lo mismo que
            // pulsar el botón de inicio, el PiP sigue vivo encima de todo y
            // la tarea de la app queda intacta para restaurarla después.
            mainHandler.postDelayed(this::esconderAppCuandoEstaEnPip, 500);
            // Vigía: si la ventanita se pierde sola, se apunta en el log de
            // depuración para poder saberlo.
            mainHandler.postDelayed(() -> {
                if (cerrandoDesdePip || isFinishing()) return;
                if (!estaEnPipSistema() && entrandoEnPip) {
                    log("⚠️ El sistema no llegó a abrir el PiP");
                }
            }, 2000);
            try {
                String info = "PiP · nativo=" + hayVideoNativo()
                        + (player != null ? ", playing=" + player.isPlaying() : ", player=null");
                if (player != null) {
                    androidx.media3.common.VideoSize vs = player.getVideoSize();
                    info += ", video=" + vs.width + "x" + vs.height;
                }
                log(info);
            } catch (Throwable ignored) { }
        } else if (!cerrandoDesdePip) {
            // Volvemos a pantalla completa
            restaurarVistasTrasPip();
            hideSystemUI();
            showControls();
            aplicarParamsPip();
            // Al salir del PiP el sistema devuelve la app a pantalla completa.
            // Si se cerró la ventanita desde el escritorio, la tarea puede
            // quedarse detrás: se trae al frente para que la app aparezca tal
            // como estaba. (Si ya está delante no hace nada.)
            // Si la ventanita se cierra estando la app detras del escritorio,
            // hay que traerla al frente; si el sistema ya lo ha hecho, no hace
            // nada mas.
            intentosTraerAlFrente = 0;
            mainHandler.postDelayed(this::asegurarAppEnPrimerPlano, 600);
        }
    }

    /**
     * Esconde la aplicación para que al entrar en PiP solo se vea la
     * ventanita flotante. Se llama un poco después de entrar en PiP, para dar
     * tiempo a que la ventanita esté montada.
     *
     * NO se usa moveTaskToBack(): en muchos móviles (Samsung, Xiaomi,
     * Android 12+) destruye la ventanita del PiP y entonces se quedaba la app
     * a pantalla completa y el reproductor flotante desaparecía, al revés de
     * lo que tiene que pasar. Ir al escritorio es lo mismo que pulsar el botón
     * de inicio: el PiP sigue vivo encima de todo y la tarea queda intacta.
     */
    private void esconderAppCuandoEstaEnPip() {
        if (!isInPipMode || isFinishing() || cerrandoDesdePip) return;
        try {
            Intent inicio = new Intent(Intent.ACTION_MAIN);
            inicio.addCategory(Intent.CATEGORY_HOME);
            inicio.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(inicio);
            log("PiP: app escondida, solo la ventanita flotante");
        } catch (Throwable t) {
            log("⚠️ No se pudo esconder la app: " + t.getMessage());
        }
    }

    /**
     * Devuelve la app a pantalla completa al salir del PiP. Si ya estaba
     * delante no hace nada (es lo que pasa al tocar la ventanita para
     * expandirla); si se cerró desde el escritorio, la trae al frente.
     */
    /**
     * Comprueba que la app ha vuelto realmente al primer plano y, si no, la
     * trae. Se reintenta un par de veces porque el sistema tarda en terminar
     * la animacion de salida del PiP.
     */
    private void asegurarAppEnPrimerPlano() {
        if (isInPipMode || isFinishing() || cerrandoDesdePip) return;
        if (enPrimerPlano || intentosTraerAlFrente >= 3) return;
        intentosTraerAlFrente++;
        salirDePip();
        mainHandler.postDelayed(this::asegurarAppEnPrimerPlano, 1200);
    }

    /**
     * Oculta la interfaz para que en el PiP SOLO se vea el vídeo
     * (ni controles, ni panel de depuración, ni WebView).
     *
     * Se recorre el camino desde el vídeo hasta la raíz ocultando los HERMANOS
     * de cada nivel. Nunca se oculta el vídeo ni ninguno de sus contenedores:
     * ocultarlos deja el PiP en NEGRO (se oye pero no se ve).
     */
    private void ocultarTodoMenosElVideo() {
        visibilidadPrePip.clear();
        // Si el vídeo lo está dando el WebView de respaldo, hay que dejar ESE
        // visible y ocultar el PlayerView; si no, el PiP sale negro (se oye el
        // audio del WebView pero no se ve nada).
        final boolean videoEnWebView = webViewFallback != null
                && webViewFallback.getVisibility() == View.VISIBLE;
        if (videoEnWebView) {
            visibilidadPrePip.put(playerView, playerView.getVisibility());
            playerView.setVisibility(View.GONE);
        }
        if (playerView != null) {
            View hijo = playerView;
            android.view.ViewParent padre = hijo.getParent();
            while (padre instanceof ViewGroup) {
                ViewGroup grupo = (ViewGroup) padre;
                for (int i = 0; i < grupo.getChildCount(); i++) {
                    View v = grupo.getChildAt(i);
                    if (v == hijo) continue;               // por aquí sube el vídeo
                    if (videoEnWebView && v == webViewFallback) continue;  // es el que se ve
                    visibilidadPrePip.put(v, v.getVisibility());
                    if (v.getVisibility() != View.GONE) v.setVisibility(View.GONE);
                }
                hijo = grupo;
                padre = grupo.getParent();
            }
        }
        if (playerView != null) {
            playerView.hideController();
            playerView.setUseController(false);
        }
        // El panel de depuración se recuerda para poder restaurarlo al salir.
        if (tvDebugLog != null) {
            visibilidadPrePip.put(tvDebugLog, tvDebugLog.getVisibility());
            tvDebugLog.setVisibility(View.GONE);
        }
        if (scrollDebugLog != null) {
            visibilidadPrePip.put(scrollDebugLog, scrollDebugLog.getVisibility());
            scrollDebugLog.setVisibility(View.GONE);
        }
    }

    /** Devuelve cada vista al estado que tenía antes de entrar en PiP. */
    private void restaurarVistasTrasPip() {
        for (Map.Entry<View, Integer> e : visibilidadPrePip.entrySet()) {
            if (e.getKey() != null) e.getKey().setVisibility(e.getValue() == null ? View.VISIBLE : e.getValue());
        }
        visibilidadPrePip.clear();
    }

    // --------------- CICLO DE VIDA ---------------

    /** Con launchMode="singleTask" sirve para abrir otra peli/canal sin duplicar Activity. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent == null) return;
        // Marca interna para salir del PiP: NO es un título nuevo, así que no
        // se toca el intent original (si lo reemplazáramos se perdería la URL
        // del stream que está sonando).
        if (ACTION_SALIR_PIP.equals(intent.getAction())) {
            cerrandoDesdePip = false;
            asegurarPlayer();
            return;
        }
        setIntent(intent);
        leerOpcionesDeIntent(intent);   // v3.2.66: chips del canal recien llegado
        String tituloNuevo = intent.getStringExtra("canal_titulo");
        if (tituloNuevo != null) { tituloCanalActual = tituloNuevo; pintarTituloPlayer(); }
        pintarChipsServidores();
        pintarTiraCanales();
        // Venimos de cerrar o de otra película: se limpia cualquier resolución
        // anterior y se garantiza que el reproductor existe.
        isResolving = false;
        resolucionInicio = 0L;
        webViewOcultoYaProbado = false;
        cerrandoDesdePip = false;
        asegurarPlayer();
        String nuevaUrl = intent.getStringExtra("stream_url");
        String nuevoIframe = intent.getStringExtra("url_iframe_inicial");
        if (nuevaUrl != null && !nuevaUrl.isEmpty()) {
            streamDirecto = nuevaUrl;
            streamCookies = intent.getStringExtra("stream_cookies");
            streamReferer = intent.getStringExtra("stream_referer");
            streamOrigin = intent.getStringExtra("stream_origin");
            String cab = intent.getStringExtra("stream_headers");
            if (cab != null) streamCabeceras = cab;
            urlIframeInicial = null;
            errorRefreshCount = 0;
            resolverStreamDirecto(streamDirecto, streamCookies, streamReferer, streamOrigin);
        } else if (nuevoIframe != null && !nuevoIframe.isEmpty()) {
            // Si vuelve a llegar la MISMA url (doble clic en el canal) mientras
            // ya se esta resolviendo o reproduciendo, no se reinicia todo: antes
            // eso hacia resolver dos veces y volver a preparar el player.
            if (nuevoIframe.equals(urlIframeInicial) && (streamReady || isResolving)) {
                log("⏳ Vuelve a llegar la misma URL; se ignora (ya estaba en marcha).");
                return;
            }
            urlIframeInicial = nuevoIframe;
            streamDirecto = null;
            errorRefreshCount = 0;
            resolverStreamEnSegundoPlano();
        }
    }

    /** v3.2.67: al girar NO se recrea la actividad (configChanges del
     *  manifiesto): se re-infla el layout de la nueva orientación y se re-ata
     *  todo al MISMO ExoPlayer — el vídeo no se corta. */
    @Override
    public void onConfigurationChanged(
            @NonNull android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        boolean ahoraHorizontal = newConfig.orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        if (ahoraHorizontal == esHorizontal || isInPipMode || entrandoEnPip) return;
        esHorizontal = ahoraHorizontal;
        atarInterfazPlayer();
        hideSystemUI();
    }

    private void atarInterfazPlayer() {
        setContentView(esHorizontal ? R.layout.activity_player
                : R.layout.activity_player_portrait);
        playerView = findViewById(R.id.playerView);
        playerView.setResizeMode(esHorizontal
                ? AspectRatioFrameLayout.RESIZE_MODE_FILL
                : AspectRatioFrameLayout.RESIZE_MODE_FIT);
        playerView.setUseController(false);
        if (player != null) playerView.setPlayer(player);
        setupCustomControls();
        setupWebViewFallback();
        atarOverlaysPlayer();
        pintarTituloPlayer();
        pintarChipsServidores();
        pintarTiraCanales();
        actualizarIconoPantalla();
        if (!streamReady && webViewOcultoYaProbado) {
            // El WebView oculto murió con la vista vieja a mitad de resolución:
            // se reintenta la misma opción con las vistas nuevas.
            webViewOcultoYaProbado = false;
            isResolving = false;
            resolverStreamEnSegundoPlano();
        }
    }

    @Override
    protected void onPause() {
        // Si estamos entrando/ya en PiP el video sigue sonando: NO pausar.
        if (!isInPipMode && !entrandoEnPip && !estaEnPipSistema() && player != null) {
            try { player.pause(); } catch (Exception ignored) { }
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        enPrimerPlano = true;
        intentosTraerAlFrente = 0;
        hideSystemUI();
        // Si la Activity se reutilizó tras cerrarla, el reproductor puede estar
        // liberado: se recrea para que el título actual pueda sonar.
        if (!isFinishing() && player == null
                && ((streamDirecto != null && !streamDirecto.isEmpty())
                    || (urlIframeInicial != null && !urlIframeInicial.isEmpty()))) {
            asegurarPlayer();
        }
        if (!isInPipMode) aplicarParamsPip();
    }

    @Override
    protected void onStop() {
        // ANTES: aqui se liberaba el player al ir a segundo plano, asi que al
        // volver la pantalla quedaba en negro. Solo liberamos si la Activity se
        // esta destruyendo de verdad (y nunca mientras estamos en PiP).
        if (!isInPipMode && !entrandoEnPip && !estaEnPipSistema()) {
            if (player != null) {
                try { player.pause(); } catch (Exception ignored) { }
                // Si la Activity se está cerrando de verdad, se libera YA para
                // que no quede ningún audio sonando.
                if (isFinishing()) liberarPlayer();
            }
        }
        enPrimerPlano = false;
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (pipReceiver != null) {
            try { unregisterReceiver(pipReceiver); } catch (Exception ignored) { }
            pipReceiver = null;
        }
        if (executorService != null) {
            executorService.shutdownNow();
            executorService = null;   // asegurarExecutor() lo crea de nuevo si hace falta
        }
        liberarPlayer();
        if (instanciaActiva == this) instanciaActiva = null;
        if (webViewFallback != null) {
            webViewFallback.stopLoading();
            webViewFallback.loadUrl("about:blank");
            webViewFallback.destroy();
            webViewFallback = null;
        }
        mainHandler.removeCallbacks(hideControlsRunnable);
        stopSeekUpdate();
        super.onDestroy();
    }

    // --------------- MÉTODOS ORIGINALES (SIN CAMBIOS) ---------------
    private void log(String msg) {
        String ts = timeFormat.format(new Date());
        String line = "[" + ts + "] " + msg + "\n";
        Log.d("BLABONGO_DEBUG", msg);
        runOnUiThread(() -> {
            if (tvDebugLog != null) {
                tvDebugLog.append(line);
                scrollDebugLog.post(() -> scrollDebugLog.fullScroll(ScrollView.FOCUS_DOWN));
            }
        });
    }

    private void setupMedia3Player() {
        dataSourceFactory = new DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(15000);

        HttpDataSource.Factory loggingDataSourceFactory = new HttpDataSource.Factory() {
            @Override
            public HttpDataSource createDataSource() {
                return new LoggingHttpDataSource(dataSourceFactory.createDataSource());
            }

            @Override
            public HttpDataSource.Factory setDefaultRequestProperties(Map<String, String> defaultRequestProperties) {
                dataSourceFactory.setDefaultRequestProperties(defaultRequestProperties);
                return this;
            }
        };

        // Swap de los paneles Drama (p.ej. k9x_ -> "") aplicado a CADA lectura de
        // playlist, incluidas las recargas en vivo de ExoPlayer; con pares explicitos
        // del JSON descifrado, o auto-deteccion cuando la URI es .../index.html.
        // v7.8 — EL RELAY APUNTA A UN file:// Y LA CADENA ERA SOLO HTTP.
        // relayHlsSiPagina() baja la playlist del panel, le aplica el swap, la
        // escribe en cache y devuelve file:///.../drama_relay.m3u8. Pero la
        // fabrica de arriba envuelve SIEMPRE un DefaultHttpDataSource, que hace
        // el cast a HttpURLConnection a mano: al abrir un file:// Java devuelve
        // un FileURLConnection y revienta con
        //   ClassCastException: FileURLConnection cannot be cast to HttpURLConnection
        // Medido en el logcat real: el canal resolvia bien (glasbi 811 ms,
        // playlist de 972 B con el swap aplicado) y moria solo en el transporte.
        // DefaultDataSource enruta por esquema: file/content/asset van al
        // datasource local; http/https siguen yendo al de siempre, con sus
        // cabeceras de usuario y sus logs.
        DataSource.Factory enrutado = new DefaultDataSource.Factory(this, loggingDataSourceFactory);

        DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(
                DramaSwapDataSource.nuevaFabrica(enrutado, dramaSwapPares));
        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(mediaSourceFactory)
                // v3.2.63: arrancar con 0.8 s de buffer (default 2.5 s) = el canal
                // se ve antes; tras un rebuffer pide 2 s para estabilizarse.
                .setLoadControl(new androidx.media3.exoplayer.DefaultLoadControl.Builder()
                        .setBufferDurationsMs(15000, 30000, 800, 2000)
                        .build())
                .build();
        playerView.setPlayer(player);

        // AUDIO EN ESPAÑOL: si el stream trae varias pistas de audio (vimeus
        // publica p.ej. "Español"/"es" + "한국어"/"ko"), se fuerza español.
        try {
            player.setTrackSelectionParameters(
                    player.getTrackSelectionParameters()
                            .buildUpon()
                            .setPreferredAudioLanguage("spa")
                            .setPreferredAudioRoleFlags(0)
                            .build());
            log("Pista de audio preferida: español (spa)");
        } catch (Throwable t) {
            Log.w(TAG, "No se pudo fijar el idioma de audio preferido: " + t.getMessage());
        }

        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                String msg = "❌ ERROR ExoPlayer: " + error.getMessage();
                if (error.getCause() != null) msg += " | " + error.getCause().getMessage();
                log(msg);

                if (!isNetworkAvailable()) {
                    log("🚫 Sin conexión. No se reintentará hasta que vuelva la red.");
                    return;
                }

                long now = System.currentTimeMillis();
                if (now - lastRefreshTime < MIN_REFRESH_INTERVAL_MS) {
                    log("⏱️ Refresco demasiado rápido, esperando...");
                    mainHandler.postDelayed(() -> {
                        if (!isResolving && errorRefreshCount < MAX_REFRESH_RETRIES) {
                            if (streamDirecto != null && !streamDirecto.isEmpty()) {
                                resolverStreamDirecto(streamDirecto, streamCookies, streamReferer, streamOrigin);
                            } else {
                                resolverStreamEnSegundoPlano();
                            }
                        }
                    }, MIN_REFRESH_INTERVAL_MS - (now - lastRefreshTime));
                    return;
                }

                streamReady = false;
                if (!isResolving && errorRefreshCount < MAX_REFRESH_RETRIES) {
                    errorRefreshCount++;
                    lastRefreshTime = System.currentTimeMillis();
                    log("⚠️ Refrescando stream (intento " + errorRefreshCount + "/" + MAX_REFRESH_RETRIES + ")...");
                    if (streamDirecto != null && !streamDirecto.isEmpty()) {
                        resolverStreamDirecto(streamDirecto, streamCookies, streamReferer, streamOrigin);
                    } else {
                        resolverStreamEnSegundoPlano();
                    }
                } else if (errorRefreshCount >= MAX_REFRESH_RETRIES) {
                    log("❌ Máximo de refrescos alcanzado. Abortando.");
                    // v3.2.66: esta opcion agoto sus reintentos — queda marcada
                    // con una ✕ y elige el usuario. Sin salto automatico a otro
                    // servidor (al reves que dzasports, a pedido del usuario).
                    if (opcionActual >= 0 && opcionesServidor.size() > 1) {
                        opcionesFallidas.add(opcionActual);
                        pintarChipsServidores();
                        showControls();
                        android.widget.Toast.makeText(PlayerActivity.this,
                                R.string.player_opcion_fallo,
                                android.widget.Toast.LENGTH_LONG).show();
                    }
                    mainHandler.postDelayed(() -> errorRefreshCount = 0, 30000);
                    if (webViewFallback != null && webViewFallback.getVisibility() != View.VISIBLE) {
                        log("🌐 Reproducción ExoPlayer fallida, abriendo reproductor web.");
                        isResolving = false;
                        usarWebViewOcultoParaResolver();
                    }
                }
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                switch (state) {
                    case Player.STATE_BUFFERING: log("Buffering..."); break;
                    case Player.STATE_READY:
                        log("✅ Ready");
                        errorRefreshCount = 0;
                        updatePlayPauseButton();
                        break;
                    case Player.STATE_ENDED:
                        log("Ended");
                        updatePlayPauseButton();
                        break;
                    case Player.STATE_IDLE: log("Idle"); break;
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                updatePlayPauseButton();
                actualizarAccionesPip();
            }

            @Override
            public void onVideoSizeChanged(androidx.media3.common.VideoSize videoSize) {
                // Si el video tiene otra relacion de aspecto (4:3, 21:9...) se
                // reajusta la ventana flotante.
                if (isInPipMode) actualizarAccionesPip();
            }
        });
    }

    private void setupWebViewFallback() {
        webViewFallback = findViewById(R.id.webPlayable);
        if (webViewFallback == null) return;

        WebSettings settings = webViewFallback.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setUserAgentString(StreamResolver.DESKTOP_USER_AGENT);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        CookieManager.getInstance().setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(webViewFallback, true);
        }

        webViewFallback.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.proceed();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                view.loadUrl(url);
                return true;
            }

            /**
             * Si por el WebView OCULTO pasa un playlist HLS, se reproduce con
             * ExoPlayer. El WebView solo sirve para que la página ejecute su
             * JavaScript; NUNCA se muestra al usuario.
             */
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String u = (request.getUrl() == null) ? null : request.getUrl().toString();
                if (u != null && u.contains(".m3u8") && !streamReady) {
                    String ref = (request.getRequestHeaders() == null) ? null
                            : request.getRequestHeaders().get("Referer");
                    final String m3u8 = u;
                    final String referer = (ref != null && !ref.isEmpty()) ? ref : urlIframeInicial;
                    mainHandler.post(() -> {
                        if (streamReady || isFinishing()) return;
                        log("🎯 Stream visto desde el WebView oculto");
                        try {
                            webViewFallback.stopLoading();
                            webViewFallback.setVisibility(View.GONE);
                        } catch (Throwable ignored) { }
                        resolverStreamDirecto(m3u8, "", referer, urlIframeInicial);
                    });
                }
                return super.shouldInterceptRequest(view, request);
            }
        });

        webViewFallback.setWebChromeClient(new WebChromeClient());
    }

    /**
     * Último recurso: el WebView se usa OCULTO, solo para que la página ejecute
     * su JavaScript (hay reproductores que arman el stream con JS y no queda
     * otra). En cuanto aparece un playlist HLS se reproduce con ExoPlayer.
     *
     * El WebView NUNCA se muestra: antes salía la página entera en pantalla y
     * eso es justo lo que no se quiere. Si de aquí tampoco sale nada, se avisa
     * al usuario en vez de dejarle viendo una web.
     */
    private void usarWebViewOcultoParaResolver() {
        runOnUiThread(() -> {
            if (webViewFallback == null) return;
            String url = urlIframeInicial;
            if (url == null || url.isEmpty() || !url.startsWith("http")) {
                log("❌ No hay URL válida que resolver.");
                avisarSinStream();
                return;
            }
            if (webViewOcultoYaProbado) {
                avisarSinStream();
                return;
            }
            webViewOcultoYaProbado = true;
            log("🕵️ Último recurso: WebView oculto para ejecutar el JS de la página");
            try {
                webViewFallback.setVisibility(View.GONE);   // NUNCA visible
                webViewFallback.stopLoading();
                webViewFallback.loadUrl(url);
            } catch (Throwable t) {
                avisarSinStream();
                return;
            }
            mainHandler.postDelayed(() -> {
                if (!streamReady && !isFinishing() && !cerrandoDesdePip) {
                    log("⚠️ Ni la cadena ni el WebView oculto encontraron el stream.");
                    avisarSinStream();
                }
            }, 20000);
        });
    }

    /** Avisa al usuario de que este enlace no da stream, en vez de mostrarle una web. */
    private void avisarSinStream() {
        runOnUiThread(() -> {
            try {
                Toast.makeText(this, "No se pudo cargar este enlace. Prueba con otra opción del canal.",
                        Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) { }
        });
    }
    /**
     * MEJORA 2026-09-28: elige el resolver según la URL.
     *
     * vsembed (y equivalentes con ds_lang=es o cloudorchestranova) no exponen el
     * m3u8 en el HTML: hay que pasar por vs_src -> cloudorchestranova ->
     * data.vidsrc.sh -> WASM -> token. Eso lo hace PelisStreamResolver.
     * StreamResolver solo usa WebView y con esos dominios se queda en timeout,
     * así que antes el reproductor caía al modo WebView aunque sí había stream.
     */
    private StreamResolver.StreamResult resolverInteligente(String url) throws Exception {
        // vimeus = el servidor LATINO (audio en español): resolver nativo, sin WebView.
        if (url != null && VimeusResolver.isVimeus(url)) {
            log("Usando VimeusResolver (Latino)...");
            try {
                PelisStreamResolver.StreamResult pr =
                        VimeusResolver.resolve(PlayerActivity.this, url);
                if (pr != null && pr.m3u8Url != null && !pr.m3u8Url.isEmpty()) {
                    log("✅ VimeusResolver devolvió master HLS en español");
                    return new StreamResolver.StreamResult(pr.m3u8Url, pr.cookies,
                            pr.referer, pr.origin, pr.headers);
                }
            } catch (Throwable t) {
                log("⚠️ VimeusResolver falló: " + t.getMessage());
            }
        }
        // embed69 = copias LAT/ESP con PoW+AES: resolver nativo, sin WebView.
        if (url != null && Embed69Resolver.isEmbed69(url)) {
            log("Usando Embed69Resolver (LAT/ESP)...");
            try {
                PelisStreamResolver.StreamResult pr =
                        Embed69Resolver.resolve(PlayerActivity.this, url);
                if (pr != null && pr.m3u8Url != null && !pr.m3u8Url.isEmpty()) {
                    log("✅ Embed69Resolver devolvió master HLS");
                    return new StreamResolver.StreamResult(pr.m3u8Url, pr.cookies,
                            pr.referer, pr.origin, pr.headers);
                }
            } catch (Throwable t) {
                log("⚠️ Embed69Resolver falló: " + t.getMessage());
            }
        }
        if (url != null && (url.contains("vsembed") || url.contains("ds_lang=es")
                || url.contains("cloudorchestranova"))) {
            log("Usando PelisStreamResolver (vsembed WASM+Token)...");
            try {
                PelisStreamResolver.StreamResult pr =
                        PelisStreamResolver.resolveSynchronously(PlayerActivity.this, url);
                if (pr != null && pr.m3u8Url != null && !pr.m3u8Url.isEmpty()) {
                    log("✅ PelisStreamResolver devolvió master HLS");
                    return new StreamResolver.StreamResult(pr.m3u8Url, pr.cookies,
                            pr.referer, pr.origin, pr.headers);
                }
            } catch (Throwable t) {
                log("⚠️ PelisStreamResolver falló: " + t.getMessage());
            }
            log("⚠️ Sin stream por el camino rápido, probando resolver genérico...");
        }
        log("Resolviendo stream con StreamResolver (WebView)...");
        return StreamResolver.resolveSynchronously(PlayerActivity.this, url);
    }

    // ---------- v3.2.67: overlays estilo dzasports (cabecera + zapeo) ----------

    /** Ata las vistas que viven fuera de la barra de controles en AMBOS layouts. */
    private void atarOverlaysPlayer() {
        filaServidores = findViewById(R.id.headerLayout);
        contenedorServidores = findViewById(R.id.serversContainer);
        cabeceraPlayer = findViewById(R.id.headerLayout);
        tvTituloPlayer = findViewById(R.id.tvCurrentChannel);
        tiraCanalesPlayer = findViewById(R.id.channelsLayout);
        scrollCanalesPlayer = findViewById(R.id.scrollCanalesPlayer);
        contenedorCanalesPlayer = findViewById(R.id.channelsContainer);
        View atras = findViewById(R.id.btnBack);
        if (atras != null) atras.setOnClickListener(v -> cerrarReproductor());
        // Retrato: la zona de vídeo es exactamente 16:9 del ancho de pantalla en retrato; en paisaje match_parent.
        View zona = findViewById(R.id.videoContainer);
        if (zona != null) {
            ViewGroup.LayoutParams lp = zona.getLayoutParams();
            if (esHorizontal) {
                lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            } else {
                lp.height = getResources().getDisplayMetrics().widthPixels * 9 / 16;
            }
            zona.setLayoutParams(lp);
        }
    }

    private void pintarTituloPlayer() {
        if (tvTituloPlayer != null)
            tvTituloPlayer.setText(tituloCanalActual == null ? "" : tituloCanalActual);
    }

    private void actualizarIconoPantalla() {
        if (btnPantalla != null) btnPantalla.setImageResource(esHorizontal
                ? R.drawable.ic_salir_pantalla : R.drawable.ic_pantalla_completa);
    }

    /** Gobierno único de cabecera/servidores/canales: en paisaje aparecen y
     *  desaparecen con los controles; en retrato son bloques sólidos siempre
     *  a la vista (si tienen contenido). En PiP, nada. */
    private void aplicarVisibilidadExtra(boolean controlesVisibles) {
        boolean mostrar = !isInPipMode && (!esHorizontal || controlesVisibles);
        if (cabeceraPlayer != null)
            cabeceraPlayer.setVisibility(mostrar ? View.VISIBLE : View.GONE);
        if (filaServidores != null)
            filaServidores.setVisibility(mostrar && opcionesServidor.size() > 1
                    ? View.VISIBLE : View.GONE);
        if (tiraCanalesPlayer != null)
            tiraCanalesPlayer.setVisibility(mostrar && ZapeoCanales.canales.size() > 1
                    ? View.VISIBLE : View.GONE);
        // v7.8.3: en paisaje el chip de calidad es lo unico que queda flotando
        // sobre el video (la fila de servidores se fue), asi que en PiP tambien
        // se oculta. En los demas casos manda mostrarChipCalidad(), que es quien
        // sabe si el canal trajo opciones.
        if (chipCalidad != null && isInPipMode) chipCalidad.setVisibility(View.GONE);
    }

    /** La tira inferior de canales: tocar una tarjeta zapea sin salir. */
    private void pintarTiraCanales() {
        if (contenedorCanalesPlayer == null || tiraCanalesPlayer == null) return;
        contenedorCanalesPlayer.removeAllViews();
        tiraIndices.clear();
        tiraConstruyendo = false;
        final java.util.List<Canal> canales = ZapeoCanales.canales;
        if (canales.size() < 2) {
            tiraCanalesPlayer.setVisibility(View.GONE);
            return;
        }
        int centro = ZapeoCanales.indiceActual;
        if (centro < 0 || centro >= canales.size()) centro = 0;
        // v7.5: se empieza por una ventana alrededor del canal que se está viendo.
        tiraDesde = zapeoVentana(centro, canales.size(), ZAPEO_TANDA);
        tiraHasta = tiraDesde;
        atarScrollTira();
        extenderTira(canales, ZAPEO_TANDA);
        aplicarVisibilidadExtra(controlsOverlay != null
                && controlsOverlay.getVisibility() == View.VISIBLE);
        centrarTiraEn(centro);
    }

    /**
     * v7.5: por qué canal empieza la ventana de la tira de zapeo.
     *
     * <p>Puro y estático para poder probarlo sin Android. El contrato que arregla el
     * ANR es el de siempre: <b>como máximo una tanda de tarjetas por vez</b>, nunca la
     * lista entera. Con catálogos pequeños la ventana cubre todo y no se nota.
     */
    static int zapeoVentana(int centro, int total, int tanda) {
        if (total <= tanda) return 0;
        int ini = centro - tanda / 2;
        if (ini < 0) ini = 0;
        int maxIni = total - tanda;
        return Math.min(ini, maxIni);
    }

    /** Deja la tarjeta del canal actual a la vista (si ya está construida). */
    private void centrarTiraEn(final int real) {
        if (scrollCanalesPlayer == null) return;
        scrollCanalesPlayer.post(() -> {
            if (contenedorCanalesPlayer == null) return;
            int pos = tiraIndices.indexOf(Integer.valueOf(real));
            if (pos < 0 || pos >= contenedorCanalesPlayer.getChildCount()) return;
            android.view.View hijo = contenedorCanalesPlayer.getChildAt(pos);
            scrollCanalesPlayer.smoothScrollTo(
                    Math.max(0, hijo.getLeft() - (int) (16 * getResources().getDisplayMetrics().density)), 0);
        });
    }

    /** Al llegar al borde derecho se alarga la tira con la tanda siguiente. */
    private void atarScrollTira() {
        if (tiraScrollAtado || scrollCanalesPlayer == null) return;
        tiraScrollAtado = true;
        final float d = getResources().getDisplayMetrics().density;
        scrollCanalesPlayer.setOnScrollChangeListener((v, sx, sy, osx, ooy) -> {
            if (contenedorCanalesPlayer == null || tiraConstruyendo) return;
            int falta = contenedorCanalesPlayer.getWidth() - (sx + v.getWidth());
            if (falta < (int) (320 * d)) extenderTira(ZapeoCanales.canales, ZAPEO_TANDA);
        });
    }

    /**
     * Construye {@code cuantas} tarjetas más (de {@link #tiraHasta} en adelante) FUERA
     * del hilo principal y las pega todas de una. Los logos se piden a Glide ya en el
     * hilo principal, con el fragmento/actividad vivos.
     */
    private void extenderTira(final java.util.List<Canal> lista, final int cuantas) {
        if (tiraConstruyendo) return;
        final int desde = tiraHasta;
        final int hasta = Math.min(lista.size(), desde + cuantas);
        if (hasta <= desde) return;
        tiraConstruyendo = true;
        final float d = getResources().getDisplayMetrics().density;
        dza.fut.paipo.core.AppExecutors.enSerie(() -> {
            final java.util.List<android.view.View> lote = new java.util.ArrayList<>(hasta - desde);
            final java.util.List<android.widget.ImageView> logos = new java.util.ArrayList<>(hasta - desde);
            final java.util.List<Canal> conLogo = new java.util.ArrayList<>(hasta - desde);
            try {
                for (int i = desde; i < hasta; i++) {
                    final Canal c = lista.get(i);
                    final int real = i;
                    android.widget.LinearLayout tarjeta = new android.widget.LinearLayout(PlayerActivity.this);
                    tarjeta.setOrientation(android.widget.LinearLayout.VERTICAL);
                    tarjeta.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
                    tarjeta.setBackgroundResource(R.drawable.bg_tarjeta_canal);
                    tarjeta.setSelected(real == ZapeoCanales.indiceActual);
                    tarjeta.setPadding((int) (6 * d), (int) (6 * d), (int) (6 * d), (int) (6 * d));
                    android.widget.LinearLayout.LayoutParams lpT =
                            new android.widget.LinearLayout.LayoutParams((int) (84 * d),
                                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
                    lpT.setMarginEnd((int) (8 * d));
                    tarjeta.setLayoutParams(lpT);
                    android.widget.ImageView img = new android.widget.ImageView(PlayerActivity.this);
                    img.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                            (int) (56 * d), (int) (40 * d)));
                    img.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                    if (c.image == null || c.image.isEmpty()) {
                        img.setImageDrawable(Logos.avatar(PlayerActivity.this, c.title));
                    } else {
                        logos.add(img);
                        conLogo.add(c);
                    }
                    android.widget.TextView nombre = new android.widget.TextView(PlayerActivity.this);
                    nombre.setText(c.title);
                    nombre.setTextColor(0xFFFFFFFF);
                    nombre.setTextSize(11);
                    nombre.setSingleLine(true);
                    nombre.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    nombre.setGravity(android.view.Gravity.CENTER);
                    tarjeta.addView(img);
                    tarjeta.addView(nombre);
                    // el índice real va en el tag: la tarjeta no sabe en qué tanda está
                    tarjeta.setTag(Integer.valueOf(real));
                    tarjeta.setOnClickListener(v -> zapearCanal((Integer) v.getTag()));
                    lote.add(tarjeta);
                }
            } catch (Throwable t) {
                android.util.Log.w("Player", "armando la tira de canales: " + t);
            }
            final int fin = hasta;
            runOnUiThread(() -> {
                tiraConstruyendo = false;
                if (contenedorCanalesPlayer == null || isDestroyed()) return;
                for (int k = 0; k < lote.size(); k++) {
                    contenedorCanalesPlayer.addView(lote.get(k));
                    tiraIndices.add(Integer.valueOf(desde + k));
                }
                tiraHasta = fin;
                // Los logos, ya en el hilo principal y con la actividad viva.
                for (int k = 0; k < logos.size(); k++) {
                    Canal c = conLogo.get(k);
                    com.bumptech.glide.Glide.with(PlayerActivity.this)
                            .load(c.image)
                            .placeholder(Logos.avatar(PlayerActivity.this, c.title))
                            .error(Logos.avatar(PlayerActivity.this, c.title))
                            .into(logos.get(k));
                }
            });
        });
    }

    /** Zapeo manual: pone el canal elegido con su primera opción; sus
     *  servidores quedan en los chips (también a mano, nunca automáticos). */
    private void zapearCanal(int idx) {
        java.util.List<Canal> canales = ZapeoCanales.canales;
        if (idx < 0 || idx >= canales.size()) return;
        if (idx == ZapeoCanales.indiceActual && (streamReady || isResolving)) {
            showControls();
            return;
        }
        Canal canal = canales.get(idx);
        if (canal.options == null || canal.options.isEmpty()) {
            android.widget.Toast.makeText(this, R.string.player_sin_opciones,
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        ZapeoCanales.indiceActual = idx;
        tituloCanalActual = canal.title;
        pintarTituloPlayer();
        opcionesServidor.clear();
        opcionesFallidas.clear();
        for (int i = 0; i < canal.options.size(); i++) {
            Canal.OpcionCanal o = canal.options.get(i);
            String et = o.label == null || o.label.trim().isEmpty()
                    ? getString(R.string.player_opcion_n, i + 1) : o.label.trim();
            String u = BottomSheetOpcionesFragment.normalizarUrlCanal(this, o.url);
            opcionesServidor.add(new String[]{et, u == null ? "" : u});
        }
        opcionActual = -1;
        UsoCanales.registrarApertura(this, canal);
        pintarTiraCanales();
        cargarOpcionServidor(0);
    }

    private static String formatoTiempo(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        long h = s / 3600, m = (s % 3600) / 60, ss = s % 60;
        return h > 0
                ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, ss)
                : String.format(java.util.Locale.ROOT, "%d:%02d", m, ss);
    }

    // v3.2.66 (estilo dzasports, pedido del usuario): fila de servidores dentro
    // del reproductor para cambiar de opcion SIN salir — y SOLO a mano. En
    // dzasports un error saltaba solo al siguiente servidor en silencio; aqui
    // NO: la opcion que agota sus reintentos queda marcada con una ✕ y el
    // usuario elige otra tocando su chip.

    private void leerOpcionesDeIntent(Intent intent) {
        opcionesServidor.clear();
        opcionesFallidas.clear();
        opcionActual = -1;
        if (intent == null) return;
        String[] etiquetas = intent.getStringArrayExtra("opciones_labels");
        String[] urls = intent.getStringArrayExtra("opciones_urls");
        if (etiquetas == null || urls == null) return;
        int n = Math.min(etiquetas.length, urls.length);
        for (int i = 0; i < n; i++) {
            String et = etiquetas[i] == null || etiquetas[i].trim().isEmpty()
                    ? getString(R.string.player_opcion_n, i + 1) : etiquetas[i].trim();
            opcionesServidor.add(new String[]{et, urls[i] == null ? "" : urls[i]});
        }
        int idx = intent.getIntExtra("opcion_indice", -1);
        opcionActual = (idx >= 0 && idx < opcionesServidor.size()) ? idx
                : (opcionesServidor.isEmpty() ? -1 : 0);
    }

    private void pintarChipsServidores() {
        if (contenedorServidores == null || filaServidores == null) return;
        contenedorServidores.removeAllViews();
        if (opcionesServidor.size() < 2) {
            filaServidores.setVisibility(View.GONE);
            return;
        }
        float d = getResources().getDisplayMetrics().density;
        for (int i = 0; i < opcionesServidor.size(); i++) {
            final int idx = i;
            boolean fallida = opcionesFallidas.contains(i)
                    && !(i == opcionActual && streamReady);
            android.widget.TextView chip = new android.widget.TextView(this);
            chip.setText(opcionesServidor.get(i)[0] + (fallida ? " \u2715" : ""));
            chip.setTextColor(0xFFFFFFFF);
            chip.setTextSize(12);
            chip.setBackgroundResource(R.drawable.bg_chip_servidor);
            chip.setSelected(i == opcionActual);
            chip.setAlpha(fallida ? 0.5f : 1f);
            chip.setPadding((int) (14 * d), (int) (7 * d), (int) (14 * d), (int) (7 * d));
            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd((int) (8 * d));
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> cargarOpcionServidor(idx));
            contenedorServidores.addView(chip);
        }
        aplicarVisibilidadExtra(controlsOverlay != null
                && controlsOverlay.getVisibility() == View.VISIBLE);
    }

    /** v3.2.66: cambio de servidor tocado por el usuario — mismo reinicio limpio
     *  e intercepciones que la entrada inicial, sin salir de la pantalla. */
    private void cargarOpcionServidor(int idx) {
        if (idx < 0 || idx >= opcionesServidor.size()) return;
        if (idx == opcionActual && (streamReady || isResolving)) {
            showControls();
            return;
        }
        String url = opcionesServidor.get(idx)[1];
        if (url == null || url.isEmpty()) {
            android.widget.Toast.makeText(this, R.string.player_sin_url,
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        opcionActual = idx;
        pintarChipsServidores();
        log("\uD83D\uDD01 Servidor elegido a mano: " + opcionesServidor.get(idx)[0]);
        isResolving = false;
        resolucionInicio = 0L;
        webViewOcultoYaProbado = false;
        cerrandoDesdePip = false;
        asegurarPlayer();
        errorRefreshCount = 0;
        lastRefreshTime = 0L;
        streamReady = false;
        streamCookies = null;
        streamReferer = null;
        streamOrigin = null;
        streamCabeceras = null;
        final String dramaLive2 = url.startsWith("dramalive://") ? url : null;
        dramaLiveEnJuego = dramaLive2;
        if (dramaLive2 != null) {
            streamDirecto = null;
            urlIframeInicial = null;
            resolverDramaLive(dramaLive2);
        } else {
            EnlaceDrama.Contenido drama2 = EnlaceDrama.abrir(url, FuenteCanales.dramaKey(this));
            if (drama2 != null) {
                streamDirecto = drama2.url;
                dramaSwapPares = EnlaceDrama.paresSwap(drama2.texto);
                urlIframeInicial = null;
                resolverStreamDirecto(streamDirecto, null, null, null);
            } else {
                streamDirecto = null;
                urlIframeInicial = url;
                resolverStreamEnSegundoPlano();
            }
        }
        showControls();
    }

    private void resolverStreamEnSegundoPlano() {
        // PERRO GUARDIÁN: si una resolución anterior se quedó colgada más de
        // 60 s (hilo muerto, executor apagado...), se libera el bloqueo; si no,
        // la Activity dejaba de resolver CUALQUIER stream para siempre y solo
        // funcionaba la primera película.
        if (isResolving && System.currentTimeMillis() - resolucionInicio > 60_000) {
            log("⚠️ La resolución anterior se quedó colgada; se libera el bloqueo");
            isResolving = false;
        }
        if (isResolving) {
            log("⏳ Ya se está resolviendo, ignoramos llamada duplicada.");
            return;
        }
        isResolving = true;
        resolucionInicio = System.currentTimeMillis();

        try {
            asegurarExecutor();
        } catch (Throwable t) {
            isResolving = false;
            log("❌ No se pudo preparar el hilo de resolución: " + t.getMessage());
            usarWebViewOcultoParaResolver();
            return;
        }

        executorService.execute(() -> {
            try {
                log("Resolviendo stream con el resolver adecuado...");
                StreamResolver.StreamResult result = resolverInteligente(urlIframeInicial);
                if (result == null || result.m3u8Url == null || result.m3u8Url.isEmpty()) {
                    log("❌ StreamResolver no devolvió stream. Cambiando a reproductor web.");
                    mainHandler.post(() -> {
                        isResolving = false;
                        usarWebViewOcultoParaResolver();
                    });
                    return;
                }
                String url = result.m3u8Url;
                String cookies = result.cookies;
                String referer = result.referer;
                String origin = result.origin;
                Map<String, String> capturedHeaders = result.headers;

                log("Stream: " + url);
                log("Cookies: " + (cookies.isEmpty() ? "(vacías)" : cookies));
                log("Referer (wrapper): " + referer);
                log("Origin (iframe real): " + origin);

                if (capturedHeaders != null && !capturedHeaders.isEmpty()) {
                    log("Headers capturados del WebView:");
                    for (Map.Entry<String, String> entry : capturedHeaders.entrySet()) {
                        log("   " + entry.getKey() + ": " + entry.getValue());
                    }
                } else {
                    log("(Sin headers capturados)");
                }

                mainHandler.post(() -> {
                    if (player == null) {
                        isResolving = false;
                        usarWebViewOcultoParaResolver();
                        return;
                    }

                    Map<String, String> finalHeaders = new HashMap<>();
                    if (capturedHeaders != null) {
                        finalHeaders.putAll(capturedHeaders);
                    }

                    finalHeaders.put("User-Agent", StreamResolver.DESKTOP_USER_AGENT);

                    boolean hasReferer = false;
                    for (String key : finalHeaders.keySet()) {
                        if (key.equalsIgnoreCase("Referer")) {
                            hasReferer = true;
                            break;
                        }
                    }
                    if (!hasReferer) {
                        finalHeaders.put("Referer", referer != null ? referer : "https://belkaperu.github.io/");
                    }
                    // Sin Accept-Language, CDNs como vimeos devuelven 403 (verificado).
                    boolean hasLang = false;
                    for (String key : finalHeaders.keySet()) {
                        if (key.equalsIgnoreCase("Accept-Language")) { hasLang = true; break; }
                    }
                    if (!hasLang) finalHeaders.put("Accept-Language", "es-ES,es;q=0.9");

                    boolean hasOrigin = false;
                    for (String key : finalHeaders.keySet()) {
                        if (key.equalsIgnoreCase("Origin")) {
                            hasOrigin = true;
                            break;
                        }
                    }
                    if (!hasOrigin && origin != null && !origin.isEmpty()) {
                        finalHeaders.put("Origin", origin);
                    } else if (!hasOrigin) {
                        try {
                            java.net.URL urlObj = new java.net.URL(referer);
                            finalHeaders.put("Origin", urlObj.getProtocol() + "://" + urlObj.getHost());
                        } catch (Exception ignored) {}
                    }

                    if (cookies != null && !cookies.isEmpty()) {
                        finalHeaders.put("Cookie", cookies);
                    }

                    log("Headers finales para ExoPlayer:");
                    for (Map.Entry<String, String> entry : finalHeaders.entrySet()) {
                        log("   " + entry.getKey() + ": " + entry.getValue());
                    }

                    player.stop();
                    player.clearMediaItems();

                    dataSourceFactory.setDefaultRequestProperties(finalHeaders);
                    conRelaySiToca(url, () -> {
                        MediaItem mediaItem = mediaItemPara(urlConRelay != null ? urlConRelay : url);
                        player.setMediaItem(mediaItem);
                        player.prepare();
                        pedirFocoAudio();
                        try { PelisStreamResolver.destruirWebViewsActivos(); } catch (Throwable ignored) { }
                        try { StreamResolver.destruirWebViewsActivos(); } catch (Throwable ignored) { }
                        player.setPlayWhenReady(true);
                        streamReady = true;
                        isResolving = false;
                        if (webViewFallback != null && webViewFallback.getVisibility() == View.VISIBLE) {
                            webViewFallback.stopLoading();
                            webViewFallback.setVisibility(View.GONE);
                        }
                        log("Reproducción iniciada con headers reales.");
                    });
                });
            } catch (Exception e) {
                log("❌ Error en callback: " + e.getMessage());
                mainHandler.post(() -> {
                    isResolving = false;
                    usarWebViewOcultoParaResolver();
                });
            }
        });
    }

    private void resolverStreamDirecto(String url, String cookies, String referer, String origin) {
        if (url == null || url.isEmpty()) {
            log("Stream directo vacio");
            return;
        }
        log("Reproduciendo stream m3u8 directo (sin WebView)");
        isResolving = false;
        mainHandler.post(() -> {
            if (!asegurarPlayer()) return;
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", StreamResolver.DESKTOP_USER_AGENT);
            if (referer != null && !referer.isEmpty()) headers.put("Referer", referer);
            if (origin != null && !origin.isEmpty()) headers.put("Origin", origin);
            if (cookies != null && !cookies.isEmpty()) headers.put("Cookie", cookies);
            // Sin Accept-Language, CDNs como vimeos devuelven 403 (verificado).
            if (!headers.containsKey("Accept-Language")) {
                headers.put("Accept-Language", "es-ES,es;q=0.9");
            }
            // Cabeceras del usuario: las de la fuente, y por encima las que pidió ESTE
            // canal. Van al final a propósito — si alguien fija su User-Agent, manda él;
            // el UA forzado de abajo es sólo un respaldo, no una imposición.
            Map<String, String> delUsuario = new java.util.LinkedHashMap<>(
                    Encabezados.fromTexto(FuenteCanales.cabecerasEnBruto(getApplicationContext())).para(url));
            if (!Encabezados.fromTexto(streamCabeceras).estaVacio()) {
                delUsuario.putAll(Encabezados.fromTexto(streamCabeceras).para(url));
            }
            if (!delUsuario.isEmpty()) {
                headers.putAll(delUsuario);
                log("Cabeceras del usuario aplicadas: " + delUsuario.keySet());
            }
            player.stop();
            player.clearMediaItems();
            dataSourceFactory.setDefaultRequestProperties(headers);
            conRelaySiToca(url, () -> {
                MediaItem mediaItem = mediaItemPara(urlConRelay != null ? urlConRelay : url);
                player.setMediaItem(mediaItem);
                player.prepare();
                pedirFocoAudio();
                try { PelisStreamResolver.destruirWebViewsActivos(); } catch (Throwable ignored) { }
                try { StreamResolver.destruirWebViewsActivos(); } catch (Throwable ignored) { }
                player.setPlayWhenReady(true);
                streamReady = true;
                isResolving = false;
                log("Stream directo cargado.");
            });
        });
    }

    /** v3.2.31: chip «HD ▾» con las opciones que extrajimos del glasbi. Vaciarlo si el
     *  canal no trajo nada (que no aparezca un boton que no hace nada). */
    private void mostrarChipCalidad() {
        java.util.List<String[]> ops = nuevasOpcionesCalidad(streamOpcionesJson);
        if (chipCalidad == null) return;
        if (ops.isEmpty()) { chipCalidad.setVisibility(android.view.View.GONE); return; }
        if (calidadElegida == null || calidadElegida.isEmpty()) calidadElegida = ops.get(0)[0];
        chipCalidad.setText(calidadElegida + " \u25BE");
        chipCalidad.setVisibility(android.view.View.VISIBLE);
    }

    /** Menu del chip: elegir opcion FIJA el marcador y re-resuelve el canal en el acto
     *  (mismo camino del play nativo, sin volver por el sueno). */
    private void mostrarMenuCalidad() {
        final java.util.List<String[]> ops = nuevasOpcionesCalidad(streamOpcionesJson);
        if (ops.isEmpty()) {
            Toast.makeText(this, "Este canal no trajo opciones de calidad", Toast.LENGTH_SHORT).show();
            return;
        }
        android.widget.PopupMenu pm = new android.widget.PopupMenu(this, chipCalidad);
        for (int i = 0; i < ops.size(); i++) {
            String marca = ops.get(i)[0];
            pm.getMenu().add(0, i, i, marca.equals(calidadElegida) ? marca + "  \u2713" : marca);
        }
        pm.setOnMenuItemClickListener(mi -> {
            String[] op = ops.get(mi.getItemId());
            calidadElegida = op[0];
            marcadorForzado = op[1];
            agenteForzado = "redirect";
            if (chipCalidad != null) chipCalidad.setText(op[0] + " \u25BE");
            log("calidad " + op[0] + " elegida — re-resolviendo con su marcador");
            Toast.makeText(this, "Cambiando a " + op[0] + "…", Toast.LENGTH_SHORT).show();
            if (canalActual != null) resolverDramaLive(canalActual);
            return true;
        });
        pm.show();
    }

    /** Puro y testeable: [{n,u}] del glasbi -> lista {nombre, marcador}; vacio ante basura. */
    static java.util.List<String[]> nuevasOpcionesCalidad(String json) {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        try {
            com.google.gson.JsonElement e = com.google.gson.JsonParser.parseString(json);
            if (e != null && e.isJsonArray()) {
                for (com.google.gson.JsonElement x : e.getAsJsonArray()) {
                    if (x.isJsonObject() && x.getAsJsonObject().has("n") && x.getAsJsonObject().has("u")
                            && x.getAsJsonObject().get("n").isJsonPrimitive()
                            && x.getAsJsonObject().get("u").isJsonPrimitive()) {
                        out.add(new String[]{ x.getAsJsonObject().get("n").getAsString(),
                                x.getAsJsonObject().get("u").getAsString() });
                    }
                }
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** v3.2.29 (play probado en PYTHON con frame decodificado): la ruta glasbi->advanced
     *  devuelve como "stream" la pagina del player (index.html), que ES una playlist live
     *  cuyo segmento viene con prefijo ofuscado (swap {"k9x_":""}: con prefijo el CDN da
     *  404, sin el 200). ExoPlayer no puede reescribir segmento a segmento, asi que la app
     *  hace de relai: baja la playlist, aplica el swap, absolutiza, la escribe en cache con
     *  extension .m3u8 y la refresca cada 4 s (sin ENDLIST ExoPlayer la recarga solo). */
    private static final java.util.regex.Pattern SEG_SWAPPEADO =
            java.util.regex.Pattern.compile("(?m)^([0-9A-Za-z]{2,8}_)(?=\\d)");

    private static final String RELAY_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36";

    static boolean esPaginaPlayerHls(String url) {
        // v3.2.30: los players del advanced rotan de host Y de ruta — medidos en el logcat
        // real del usuario: /pages/.. (beIN, con swap k9x_), /mirsp/.. (thmanyah) y
        // /malasp2/.. (beIN sport open) en la MISMA sesion. La firma estable es que el
        // "stream" es una pagina index.html; el guardadillo de que sea playlist real lo
        // pone el propio relay (si la primera linea no es #EXTM3U, devolvemos la url cruda).
        return url != null && url.endsWith("/index.html");
    }

    /** Pura y testeable: swap (quita el prefijo antes del primer digito) + absolutizado. */
    static String reescribirPlaylist(String txt, String baseUrl) {
        if (txt == null || txt.isEmpty() || baseUrl == null) return null;
        String limpio = SEG_SWAPPEADO.matcher(txt).replaceAll("");
        int cut = baseUrl.lastIndexOf('/');
        String base = cut > 0 ? baseUrl.substring(0, cut + 1) : baseUrl;
        StringBuilder sb = new StringBuilder();
        for (String raw : limpio.split("\n")) {
            String l = raw.trim();
            if (sb.length() > 0) sb.append('\n');
            if (l.isEmpty() || l.charAt(0) == '#') sb.append(l);
            else if (l.startsWith("http://") || l.startsWith("https://")) sb.append(l);
            else sb.append(base).append(l);
        }
        return sb.toString();
    }

    private volatile String relayBase;

    private java.io.File relayArchivo() { return new java.io.File(getCacheDir(), "drama_relay.m3u8"); }

    private static byte[] drain(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int r;
        while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
        return bo.toByteArray();
    }

    private byte[] bajarPlaylist(String url) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setConnectTimeout(6000); c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", RELAY_UA);
        try (java.io.InputStream in = c.getInputStream()) { return drain(in); }
    }

    /** v7.3: el relay baja la playlist por red, asi que se prepara en un hilo aparte y
     *  el resto (setMediaItem/prepare) sigue en el principal. Antes se llamaba dentro
     *  de un mainHandler.post y petaba con NetworkOnMainThreadException — que se veia
     *  en el logcat como «relay HLS: primer fetch fallo» y dejaba el relay sin
     *  aplicar (sin el swap de segmentos k9x_). */
    private void conRelaySiToca(@NonNull final String url, @NonNull final Runnable resto) {
        if (url == null || url.isEmpty() || !esPaginaPlayerHls(url)) {
            resto.run();   // no es pagina del player: se sigue tal cual
            return;
        }
        new Thread(() -> {
            final String lista = relayHlsSiPagina(url);
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                urlConRelay = lista;
                resto.run();
            });
        }, "drama-relay-prep").start();
    }

    /** Ultima url ya pasada por el relay (la usa conRelaySiToca). */
    private volatile String urlConRelay;

    /** Primer fetch de la playlist del player; si algo falla devolvemos la url
     *  original y ExoPlayer intenta lo suyo. Llamar desde un hilo en segundo plano. */
    private String relayHlsSiPagina(String url) {
        if (!esPaginaPlayerHls(url)) return url;
        try {
            String crudo = new String(bajarPlaylist(url), java.nio.charset.StandardCharsets.UTF_8);
            if (crudo.trim().split("\n", 2)[0].indexOf("#EXTM3U") < 0) {
                log("relay HLS: la pagina no es playlist (no arranca con #EXTM3U) — la dejo cruda");
                return url;
            }
            String nuevo = reescribirPlaylist(crudo, url);
            if (nuevo == null) return url;
            try (java.io.FileWriter w = new java.io.FileWriter(relayArchivo())) { w.write(nuevo); }
            android.util.Log.d("BLABONGO_DEBUG", "relay HLS: playlist en cache (" + nuevo.length()
                    + " B) con swap aplicado — play desde archivo .m3u8 local");
            iniciarBucleRelay(url);
            return android.net.Uri.fromFile(relayArchivo()).toString();
        } catch (Exception e) {
            log("relay HLS: primer fetch fallo (" + e + ") — dejo la url cruda");
            return url;
        }
    }

    private void iniciarBucleRelay(final String url) {
        relayBase = url;
        Thread t = new Thread(() -> {
            while (!isFinishing() && url.equals(relayBase)) {
                try { Thread.sleep(4000); } catch (InterruptedException e) { return; }
                if (!url.equals(relayBase) || isFinishing()) return;
                try {
                    String nuevo = reescribirPlaylist(new String(bajarPlaylist(url), java.nio.charset.StandardCharsets.UTF_8), url);
                    if (nuevo != null) { try (java.io.FileWriter w = new java.io.FileWriter(relayArchivo())) { w.write(nuevo); } }
                } catch (Exception ignored) { }
            }
        }, "drama-relay");
        t.setDaemon(true);
        t.start();
    }

    /**
     * ExoPlayer decide si algo es HLS mirando la RUTA de la URL (que acabe en
     * .m3u8). Los canales de deportes sirven el playlist como
     * playlist.php?id=..&sig=.., sin .m3u8, asi que ExoPlayer lo toma por un
     * fichero normal e intenta leer un contenedor de media dentro de un texto:
     * falla aunque el stream sea perfecto.
     *
     * Cuando la URL no trae .m3u8 se lo decimos nosotros con el MIME. Las
     * peliculas siempre resuelven a un master.m3u8, asi que esto no las toca.
     */
    private MediaItem mediaItemPara(String url) {
        MediaItem.Builder b = new MediaItem.Builder().setUri(Uri.parse(url));
        if (esContenedorDeMedia(url)) {
            log("\uD83C\uDFAC Contenedor progresivo (.mp4/.mkv/...): ExoPlayer lo autodetecta, NO lo metemos al parser HLS");
        } else if (url != null && !url.toLowerCase().contains(".m3u8")
                && !url.toLowerCase().endsWith(".mpd")) {
            // Si esta linea sale por el log, el arreglo SI esta en el build.
            // Si no sale, es que hay un APK viejo instalado.
            log("\uD83C\uDFAC Reproduciendo como HLS (la URL no trae .m3u8): " + url);
            b.setMimeType(MimeTypes.APPLICATION_M3U8);
        }
        return b.build();
    }

    /** Pura y testeable: extensiones de fichero-contenedor (no playlist). */
    static boolean esContenedorDeMedia(String url) {
        if (url == null) return false;
        String u = url.toLowerCase();
        int q = u.indexOf('?');
        String path = q >= 0 ? u.substring(0, q) : u;
        return path.endsWith(".mp4") || path.endsWith(".mkv") || path.endsWith(".webm")
                || path.endsWith(".mp3") || path.endsWith(".m4a") || path.endsWith(".aac")
                || path.endsWith(".mov") || path.endsWith(".avi") || path.endsWith(".flv");
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo netInfo = cm.getActiveNetworkInfo();
        return netInfo != null && netInfo.isConnected();
    }

    private void configurarSSLInseguroUniversal() {
        try {
            TrustManager[] trustAll = new TrustManager[]{
                    new X509TrustManager() {
                        public java.security.cert.X509Certificate[] getAcceptedIssuers() { return null; }
                        public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {}
                        public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {}
                    }
            };
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAll, new java.security.SecureRandom());
            javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
            javax.net.ssl.HttpsURLConnection.setDefaultHostnameVerifier((h, s) -> true);
        } catch (Exception ignored) {}
    }

    private void hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat ctrl = new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        ctrl.hide(WindowInsetsCompat.Type.systemBars());
        ctrl.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    // ===================== INTERCEPTOR DE RED (LOGS) =====================
    private class LoggingHttpDataSource implements HttpDataSource {
        private final HttpDataSource delegate;

        LoggingHttpDataSource(HttpDataSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public void addTransferListener(TransferListener transferListener) {
            delegate.addTransferListener(transferListener);
        }

        @Override
        public long open(DataSpec dataSpec) throws HttpDataSourceException {
            log("🌍 Petición HTTP a: " + dataSpec.uri);
            try {
                long bytes = delegate.open(dataSpec);
                log("✅ Conexión exitosa (HTTP " + delegate.getResponseCode() + ")");
                return bytes;
            } catch (IOException e) {
                log("❌ Error de red HTTP: " + e.getMessage());
                throw e;
            }
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws HttpDataSourceException {
            return delegate.read(buffer, offset, length);
        }

        @Override
        public Uri getUri() {
            return delegate.getUri();
        }

        @Override
        public void close() throws HttpDataSourceException {
            delegate.close();
        }

        @Override
        public Map<String, List<String>> getResponseHeaders() {
            return delegate.getResponseHeaders();
        }

        @Override
        public void setRequestProperty(String name, String value) {
            delegate.setRequestProperty(name, value);
        }

        @Override
        public void clearRequestProperty(String name) {
            delegate.clearRequestProperty(name);
        }

        @Override
        public void clearAllRequestProperties() {
            delegate.clearAllRequestProperties();
        }

        @Override
        public int getResponseCode() {
            return delegate.getResponseCode();
        }
    }
}