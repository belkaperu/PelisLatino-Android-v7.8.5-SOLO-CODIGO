package dza.fut.paipo;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Auto-extraccion de DramaLive SIN consola ni copiar-pegar: la app abre el
 * propio sitio (web.dramalive.cc) en un WebView con sesion persistente
 * (DomStorage: el login con el codigo del usuario vive ahi), y le inyecta
 * assets/drama_auto.js — el captador verificado, auto-ejecutable, que aprende
 * la plantilla glt del trafico del propio sitio, re-enumeracion de los 6 tipos
 * y entrega canales+plantilla por el bridge {@code AndroidDrama}.
 *
 * v3.1 (debug de campo): cada pieza habla a logcat con tag propio para que un
 * `adb logcat -d -s DramaAuto:V DramaWeb:V DramaAutoAct:V` conteste solo las
 * tres preguntas que antes requerian fe:
 *   1) "ACTIVIDAD v3.1 creada"  → el APK instalado es el nuevo (no el v1/v2);
 *   2) "inyectado ->"            → el guion entro en la pagina;
 *   3) volcado cada 8 s          → el captador ve (o no) el trafico: el JSON
 *      trae req/decOk/filas/hosts/erroresJS + las primeras 90 peticiones con
 *      el principio de cuerpo y respuesta de cada una.
 * Ademas WebChromeClient espeja TODO console.log/warn/error del sitio (ahi
 * vive un CSP roto o un fail de red de Flutter, si lo hay). El boton «Copiar
 * debug» deja el ultimo volcado en el portapapeles y el fichero
 * Android/data/<pkg>/files/drama-debug.log tiene el ultimo informe completo.
 *
 * Seguridad del bridge: entregar()/estado()/volcar() solo escriben prefs/
 * ficheros de la app; el origen cargado es el sitio que el usuario ya usa.
 */
public final class DramaAutoActivity extends Activity {

    public static final String EXTRA_HEADLESS = "drama_headless";
    /** v3.2.13: id_live a curar PRIORITARIO (lo manda el player cuando el server dió sueno). */
    public static final String EXTRA_PRIOR = "drama_prior";
    /** v3.2.14: identidad de la fila sintetica (nombre/logo/group reales del canal jugado). */
    public static final String EXTRA_PRIOR_NOMBRE = "drama_prior_n";
    public static final String EXTRA_PRIOR_LOGO = "drama_prior_l";
    public static final String EXTRA_PRIOR_GRUPO = "drama_prior_g";
    public static final String EXTRA_CANALES = "drama_canales";
    private static final String SITIO = "https://web.dramalive.cc/";
    private static final long PLAZO_HEADLESS_MS = 120_000L; // v3.2.18: el usuario pidio 2 min
    // (90 s de v3.2.17 seguan cortos en su red). El plomazo es el UNICO que cierra headless.

    @Nullable private WebView vista;
    @Nullable private TextView estadoTxt;
    @Nullable private String guion;
    @Nullable private volatile String ultimoEstado;
    @Nullable private volatile String ultimoVolcado;
    private long ultLogEstado;
    private boolean entregado;
    private boolean headless;
    private int fallosRed;   // v3.2.18: errores de red tolerados antes de rendirse
    private volatile boolean priorCurado;   // v3.2.22: la fila del canal jugado ya tiene url real
    private long ultIny;                    // v3.2.22: throttle de inyecciones
    private int reparos;                      // v3.2.22: tope de la cadena reaparar
    @Nullable private String prior;
    @Nullable private String priorN, priorL, priorG;
    private final Handler manejador = new Handler(Looper.getMainLooper());
    // v3.2.16: el player releva el polling en el captador vivo: el player puede cerrarse
    // antes de la ENTREGA y la cura sigue sola en su task.
    @Nullable private static volatile DramaAutoActivity viva;

    @Nullable
    public static DramaAutoActivity instanciaViva() { return viva; }

    /** v3.2.26 (log 00:38: el escape re-abria el captador visible cada 30 s): si YA hay un
     *  captador VISIBLE vivo cosechando, re-abrirlo con NEW_TASK solo corta la cosecha. */
    static boolean hayCaptadorVisibleVivo() {
        DramaAutoActivity v = viva;
        return v != null && !v.headless && !v.isFinishing();
    }

    /** v3.2.16: re-programa el poll de revision +N ms (hasta el plomazo). */
    public void pedirRelevo(long ms) { manejador.postDelayed(revisionEntrega, ms); }

    private final Runnable revisionEntrega = new Runnable() {
        @Override public void run() {
            if (entregado || isFinishing()) return;
            android.util.Log.d("BLABONGO_DEBUG", "captador(" + (headless ? "headless" : "visible") + "): poll de relevo — sigue la cosecha");
            manejador.postDelayed(this, 20_000L);
        }
    };
    private final Runnable reaparar = new Runnable() {
        @Override public void run() {
            if (entregado || isFinishing()) return;
            if (++reparos > 10) return;   // v3.2.22: el hook ya esta plantado; no hace falta seguir pinchando
            inyectar();
            manejador.postDelayed(this, 2500L);
        }
    };
    private final Runnable cierreTrasCosecha = new Runnable() {
        @Override public void run() {   // v3.2.23: la cosecha termino sin el prior; tras dejar
            if (isFinishing()) return;  // salir el sink (filas curadas), cerrar sin esperar plomazo
            if (priorCurado || prior == null || prior.isEmpty()) return;
            android.util.Log.d("BLABONGO_DEBUG", "captador(" + (headless ? "headless" : "visible") + "): cierre tras cosecha sin cura del prior (gracia de 9 s vencida)");
            finish();
        }
    };
    private final Runnable plomada = new Runnable() {
        @Override public void run() {
            if (!headless || isFinishing()) return;
            if (entregado && priorCurado) return;
            if (entregado) {   // v3.2.22: hubo entrega pero la cosecha no alcanzo a curar el prior
                android.util.Log.d("BLABONGO_DEBUG", "captador(" + (headless ? "headless" : "visible") + "): plomazo con entrega hecha y prior sin curar — cierro");
                PlayerActivity.notificarCuraFracasada(prior);   // v3.2.23: quemar + escape visible
                finish();
                return;
            }
            String aviso = getString(R.string.drama_auto_headless_sin);
            FuenteCanales.guardarDramaEstado(DramaAutoActivity.this, aviso);
            android.widget.Toast.makeText(DramaAutoActivity.this, aviso, android.widget.Toast.LENGTH_LONG).show();
            android.util.Log.i("BLABONGO", "PLOMAZO " + (PLAZO_HEADLESS_MS / 1000)
                    + "s SIN ENTREGA: la cosecha no curo (sitio mudo, seed sin filas o red)");
            android.util.Log.d("BLABONGO_DEBUG", "captador(" + (headless ? "headless" : "visible") + "): PLOMAZO SIN ENTREGA a los "
                    + (PLAZO_HEADLESS_MS / 1000) + "s");
            PlayerActivity.notificarCuraFracasada(prior);       // v3.2.23
            finish();
        }
    };

    /** v3.2.23 (log 23:31:33→23:31:44): el re-LANZAR reusaba esta instancia (NEW_TASK
     *  recicla la tarea :cura) pero el plomazo viejo la mato a los 10 s del relevo. Con
     *  intent nuevo: actualizo el prior y re-ARMO los 120 s desde ahora. */
    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.hasExtra(EXTRA_HEADLESS)) headless = intent.getBooleanExtra(EXTRA_HEADLESS, false);
        String np = intent.getStringExtra(EXTRA_PRIOR);
        if (np != null && !np.isEmpty()) {
            prior = np;
            priorN = intent.getStringExtra(EXTRA_PRIOR_NOMBRE);
            priorL = intent.getStringExtra(EXTRA_PRIOR_LOGO);
            priorG = intent.getStringExtra(EXTRA_PRIOR_GRUPO);
        }
        if (headless && !priorCurado && !isFinishing()) {
            manejador.removeCallbacks(plomada);
            manejador.postDelayed(plomada, PLAZO_HEADLESS_MS);
            android.util.Log.d("BLABONGO_DEBUG", "captador(" + (headless ? "headless" : "visible") + "): relevo de cura — plomazo re-armado 120 s para " + prior);
        }
    }

    /** v3.2.23: el player pregunta, antes de relanzar, si el captador vivo ya cura este pid. */
    @Nullable String priorViva() { return prior; }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        headless = getIntent() != null && getIntent().getBooleanExtra(EXTRA_HEADLESS, false);
        if (getIntent() != null) {
            prior = getIntent().getStringExtra(EXTRA_PRIOR);
            priorN = getIntent().getStringExtra(EXTRA_PRIOR_NOMBRE);
            priorL = getIntent().getStringExtra(EXTRA_PRIOR_LOGO);
            priorG = getIntent().getStringExtra(EXTRA_PRIOR_GRUPO);
        }
        android.util.Log.i("BLABONGO", "actividad creada headless=" + headless);
        android.util.Log.d("BLABONGO_DEBUG", "captador(" + (headless ? "headless" : "visible") + "): actividad creada");
        viva = this;

        vista = new WebView(this);
        WebSettings ws = vista.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        // v3.2.63: el captador solo necesita scripts y XHR — las imagenes del
        // sitio (cientos de logos) ya no se descargan: conexion mucho mas ligera.
        ws.setBlockNetworkImage(true);
        ws.setLoadsImagesAutomatically(false);
        String ua = ws.getUserAgentString();
        ws.setUserAgentString(ua.replace("; wv", "").replaceAll("Version/[^ ]+ ", ""));
        vista.addJavascriptInterface(new Puente(), "AndroidDrama");
        vista.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(@NonNull ConsoleMessage m) {
                android.util.Log.i("BLABONGO", m.messageLevel() + " "
                        + String.valueOf(m.sourceId()).replaceAll(".*/", "") + ":" + m.lineNumber()
                        + " " + m.message());
                return false;
            }
        });

        WebViewClient cliente = new WebViewClient() {
            @Override public void onPageStarted(@NonNull WebView v, @NonNull String url, @Nullable android.graphics.Bitmap f) {
                inyectar();
            }
            @Override public void onPageFinished(@NonNull WebView v, @Nullable String url) {
                android.util.Log.i("BLABONGO", "pagina cargada: " + url);
                inyectar();
                if (!entregado && reparos == 0) manejador.postDelayed(reaparar, 2500L);   // v3.2.22: UNA cadena
            }
            @Override public void onReceivedError(@NonNull WebView v, int cod, @NonNull String desc, @NonNull String failingUrl) {
                if (URLUtil.isNetworkUrl(failingUrl)) {
                    android.util.Log.w("BLABONGO", "error red " + cod + " " + failingUrl + " " + desc);
                    estado("error de red: " + desc);
                    // v3.2.18: ANTES esto hacia finish() inmediato en headless — el «se
                    // regresa rapido sin dejar conectar» del usuario. Un timeout/DNS
                    // transitorio tumbaba el captador en el segundo 2 cuando el plomazo
                    // le daban 90. Ahora: re-cargar el sitio con backoff (4/8/15/25 s,
                    // ~52 s extra en total) y rendirse SOLO cuando el plomazo (120 s)
                    // dice basta. En modo visible nunca se cerraba; ahora tampoco.
                    if (headless && !entregado && !isFinishing() && fallosRed < 4) {
                        fallosRed++;
                        final long espera = new long[]{4_000L, 8_000L, 15_000L, 25_000L}[fallosRed - 1];
                        android.util.Log.d("BLABONGO_DEBUG", "captador(" + (headless ? "headless" : "visible") + "): error red " + cod
                                + " — re-carga " + fallosRed + "/4 en " + (espera / 1000) + "s (ya no me salgo)");
                        manejador.postDelayed(() -> {
                            if (!entregado && !isFinishing() && vista != null) vista.loadUrl(SITIO);
                        }, espera);
                    }
                }
            }
        };
        vista.setWebViewClient(cliente);

        if (headless) {
            getWindow().setLayout(2, 2);
            getWindow().setGravity(Gravity.TOP | Gravity.START);
            getWindow().getDecorView().setAlpha(0f);
        } else {
            LinearLayout caja = new LinearLayout(this);
            caja.setOrientation(LinearLayout.VERTICAL);
            caja.setBackgroundColor(0xFF0D0F14);
            LinearLayout barra = new LinearLayout(this);
            barra.setOrientation(LinearLayout.HORIZONTAL);
            barra.setBackgroundColor(0x2200E0A0);
            estadoTxt = new TextView(this);
            estadoTxt.setTextColor(0xFFE8EAF0);
            estadoTxt.setPadding(24, 16, 8, 16);
            estadoTxt.setTextSize(12f);
            estadoTxt.setMaxLines(3);
            estadoTxt.setText(R.string.drama_auto_esperando);
            barra.addView(estadoTxt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            android.widget.Button copiar = new android.widget.Button(this);
            copiar.setText(R.string.drama_auto_copiar);
            copiar.setTextSize(11f);
            copiar.setAllCaps(false);
            copiar.setOnClickListener(v -> {
                String s = ultimoVolcado;
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (s == null) {
                    s = "{\"informe\":\"aun no llego volcado del captador (v3.1) — estado_barra: "
                            + String.valueOf(ultimoEstado).replace("\"", "'") + "\"}";
                }
                if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("drama-debug", s));
                android.widget.Toast.makeText(this,
                        "Informe copiado — pegalo en el chat (tambien en " + archivoVolcado().getName() + ")",
                        android.widget.Toast.LENGTH_LONG).show();
            });
            barra.addView(copiar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            caja.addView(barra, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            caja.addView(vista, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            setContentView(caja);
        }

        manejador.postDelayed(plomada, PLAZO_HEADLESS_MS);
        android.util.Log.i("BLABONGO", "loadUrl " + SITIO);
        vista.loadUrl(SITIO);
    }

    /** v3.2.14: string -> prefijo de asignacion JS segura (comillas/escapes fuera). */
    @NonNull
    private static String jsSeguro(@NonNull String var, @Nullable String v) {
        if (v == null) return "";
        String clean = v.replaceAll("['\"\\\\\r\n]", " ").trim();
        return clean.isEmpty() ? "" : var + "='" + clean + "';\n";
    }

    private void inyectar() {
        WebView v = vista;
        if (v == null) return;
        // v3.2.22 (log 13:01: 45 inyecciones de 43 KB en 16 s): el sitio dispara
        // onPageStarted/Finished por cada navegacion de la SPA; el hook ya se guarda a si
        // mismo de duplicarse, pero el evaluateJavascript cuesta. Cortamos a 1/4s minimo.
        long ahoraIny = System.currentTimeMillis();
        if (ultIny > 0 && ahoraIny - ultIny < 4000L) return;
        ultIny = ahoraIny;
        try {
            String g = guion;
            if (g == null) {
                g = leerAsset();
                if (g == null) {
                    android.util.Log.e("BLABONGO", "falta assets/drama_auto.js en el APK");
                    estado("falta assets/drama_auto.js — build roto");
                    return;
                }
                guion = g;
                android.util.Log.i("BLABONGO", "asset leido " + g.length() + " B; inyectando");
            }
            // v3.2.4: sembrar la sesion guardada ANTES del guion para que auto() pueda
            // replayar desde el segundo 0 sin depender del trafico glt del sitio.
            String sesD = FuenteCanales.sesionDrama(this);
            String boot = DramaSesion.sesionBootstrap(sesD);
            // v3.2.26: la semilla ya no es un misterio — BLABONGO dice si venia y que traia.
            android.util.Log.d("BLABONGO_DEBUG", "semilla(Java): " + (boot == null
                    ? "AUSENTE — bootstrap no armo nada (sesion en disco: " + (sesD == null ? "null" : sesD.length() + " B") + ")"
                    : "OK " + boot.length() + " B · glbrReq=" + (sesD != null && sesD.contains("glbrReq") ? "en disco" : "NO en disco")
                      + " · glt=" + (sesD != null && sesD.contains("user_id") ? "con user_id" : "sin user_id")));
            // v3.2.13: id prioritario sanitizado (id_live solo trae [A-Za-z0-9._~%-])
            String p = (prior == null || prior.isEmpty()) ? "" :
                    "window.__DRAMA_PRIOR='" + prior.replaceAll("[^A-Za-z0-9._~%-]", "") + "';\n"
                            + jsSeguro("window.__DRAMA_PRIOR_N", priorN)
                            + jsSeguro("window.__DRAMA_PRIOR_L", priorL)
                            + jsSeguro("window.__DRAMA_PRIOR_G", priorG);
            // v3.2.24: sembrar el wizard del sitio ANTES que nada (ver DramaSesion.seedSetupJs).
            String seed = DramaSesion.seedSetupJs(FuenteCanales.codigoFg(this));
            String js = (seed.isEmpty() ? "" : seed + "\n")
                    + (boot == null ? "" : boot + "\n") + p + g;
            v.evaluateJavascript(js + "\n;void 0;", r ->
                    android.util.Log.i("BLABONGO", "inyectado " + js.length() + " B · prior=" + (prior == null || prior.isEmpty() ? "-" : prior) + " -> " + (r == null ? "null" : r)));
            android.util.Log.d("BLABONGO_DEBUG", "captador(" + (headless ? "headless" : "visible") + "): inyectado " + js.length()
                    + " B · prior=" + (prior == null || prior.isEmpty() ? "-" : prior));
        } catch (Exception e) {
            android.util.Log.e("BLABONGO", "inyeccion fallo: " + e);
        }
    }

    @Nullable
    private String leerAsset() {
        try (InputStream in = getAssets().open("drama_auto.js");
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private void estado(@NonNull String t) {
        ultimoEstado = t;
        long ahora = System.currentTimeMillis();
        if (ahora - ultLogEstado > 30_000L) {
            ultLogEstado = ahora;
            android.util.Log.i("BLABONGO", "estado: " + t);
        }
        runOnUiThread(() -> {
            TextView e = estadoTxt;
            if (e != null) e.setText(t);
        });
    }

    /** Sin entrega, el ultimo diagnostico del captador queda en prefs para el
     *  sheet: es lo que distingue "sitio mudo" de "no lo desciframos". */
    @Override
    protected void onPause() {
        super.onPause();
        if (!entregado && !isFinishing()) {
            FuenteCanales.guardarDramaEstado(this,
                    "WebView abierto, sin entrega — " + (ultimoEstado == null ? "el captador no vio trafico" : ultimoEstado));
        }
    }

    @NonNull
    private java.io.File archivoVolcado() {
        java.io.File dir = getExternalFilesDir(null);
        return new java.io.File(dir == null ? getFilesDir() : dir, "drama-debug.log");
    }

    private void escribirVolcado(@NonNull String json) {
        try (java.io.Writer w = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(archivoVolcado()), StandardCharsets.UTF_8)) {
            w.write("=== drama-debug volcado " + new java.util.Date() + " ===\n");
            try {
                w.write(new org.json.JSONObject(json).toString(1));
            } catch (Exception e) {
                w.write(json);
            }
            w.write("\n");
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        manejador.removeCallbacks(cierreTrasCosecha);   // v3.2.23
        if (viva == this) viva = null;
        manejador.removeCallbacks(reaparar);
        manejador.removeCallbacks(plomada);
        WebView v = vista;
        if (v != null) {
            v.setWebViewClient(new WebViewClient());
            v.loadUrl("about:blank");
            v.destroy();
            vista = null;
        }
        super.onDestroy();
    }

    /** El bridge que ve drama_auto.js como `AndroidDrama`. Tres metodos, todos anotados. */
    private final class Puente {

        @JavascriptInterface
        public void estado(@NonNull String texto) {
            DramaAutoActivity.this.estado(texto);
        }

        /** Informe de debug: logcat (filtro DramaAuto), memoria y fichero. */
        @JavascriptInterface
        public void volcar(@NonNull String json) {
            ultimoVolcado = json;
            android.util.Log.i("BLABONGO", json);
            new Thread(() -> escribirVolcado(json)).start();
        }

        /** v3.2.26: hito incondicional de la cadena semilla->cosecha — llega a logcat pase lo
         *  que pase (el JS lo llama en cada decision, sin guards encima). */
        @JavascriptInterface
        public void hito(final String m) {
            android.util.Log.d("BLABONGO_DEBUG", "hito(" + (headless ? "headless" : "visible") + "): " + m);
        }

        /** v3.2.23: fin de la COSECHA. Si no curo al prior, no esperar el plomazo: quemar el
         *  canal (el player escapa al captador VISIBLE) y cerrar — con 9 s de gracia para que
         *  salga el sink con las demas filas curadas, si las hay. */
        @JavascriptInterface
        public void cosechaTermino(final int curadas, final int priorOk) {
            runOnUiThread(() -> {
                // v3.2.26 (log 00:36: cero "cosecha terminada"): el log estaba DETRAS del guard
                // headless — en modo visible nunca salia. Ahora siempre sale, con el modo real.
                android.util.Log.d("BLABONGO_DEBUG", "captador(" + (headless ? "headless" : "visible") + "): cosecha terminada — "
                        + curadas + " curadas, prior " + (priorOk == 1 ? "QUEDO CURADO" : "SIGUE EN SUENO"));
                if (!headless || isFinishing()) return;
                if (priorOk == 1 || prior == null || prior.isEmpty()) return; // la ENTREGA force ya cierra
                PlayerActivity.notificarCuraFracasada(prior);
                if (curadas > 0) {
                    manejador.removeCallbacks(cierreTrasCosecha);
                    manejador.postDelayed(cierreTrasCosecha, 9_000L);
                } else {
                    finish();
                }
            });
        }

        /** v3.2.24: el sitio traía un código FG (trafico o source_config de este WebView) —
         *  memorizarlo como codigo de la app para que la proxima la conexion sea sola. */
        @JavascriptInterface
        public void aprenderCodigo(@NonNull String codigo) {
            final String c = codigo.trim();
            if (c.isEmpty()) return;
            runOnUiThread(() -> {
                try {
                    if (!c.equals(FuenteCanales.codigoFg(DramaAutoActivity.this))) {
                        FuenteCanales.guardarSoloCodigo(DramaAutoActivity.this, c);
                        android.util.Log.d("BLABONGO_DEBUG",
                                "captador: codigo FG aprendido (" + c + ") — la proxima me conecto solo");
                    }
                } catch (Exception ignore) { }
            });
        }

        /** Entrega del captador: canales + plantilla glt aprendida. Guarda y cierra. */
        @JavascriptInterface
        public void entregar(@NonNull String json) {
            runOnUiThread(() -> {
                if (isFinishing()) return;
                DramaSesion.Resultado r = DramaSesion.desdePuente(json);
                if (!r.ok()) {
                    android.util.Log.w("BLABONGO", "entrega descartada: " + r.error);
                    FuenteCanales.guardarDramaEstado(DramaAutoActivity.this, "entrega descartada: " + r.error);
                    estado("entrega descartada: " + r.error);
                    return;
                }
                // v3.2.11: FUSION, no reemplazo — la entrega trae solo el topic visto y
                // antes borraba la rejilla completa («se van todas las canales», 16:07).
                FuenteCanales.guardarDramaExtra(DramaAutoActivity.this,
                        DramaSesion.fusionar(FuenteCanales.dramaExtraIdo(DramaAutoActivity.this), r.m3u));
                String ses = DramaSesion.plantillasDesdePuente(json);
                if (ses != null) FuenteCanales.guardarSesionDrama(DramaAutoActivity.this, ses);
                FuenteCanales.guardarDramaTs(DramaAutoActivity.this, System.currentTimeMillis());
                String msg = getString(R.string.drama_auto_ok, r.canales);
                FuenteCanales.guardarDramaEstado(DramaAutoActivity.this, msg);
                entregado = true;
                android.util.Log.i("BLABONGO", "ENTREGA OK " + r.canales + " canales; sesion=" + (ses != null));
                android.util.Log.d("BLABONGO_DEBUG", "captador(" + (headless ? "headless" : "visible") + "): ENTREGA OK " + r.canales + " canales (fusion)");
                PlayerActivity.notificarEntrega();   // v3.2.21: el player espera esto
                // v3.2.22 (log usuario 13:01:31): «ENTREGA OK 84 canales» mataba al captador con
                // la cosecha EN VUELO — la fila del canal jugado se curaba segundos despues y ya
                // no habia WebView que la mandara. Ahora el headless solo se cierra si el prior
                // quedo curado; si no, sigue vivo hasta que una entrega lo traiga o el plomazo.
                if (prior != null && !prior.isEmpty() && !priorCurado) {
                    String[] frP = DramaSesion.filaDeRejilla(
                            FuenteCanales.dramaExtraIdo(DramaAutoActivity.this), prior);
                    if (frP != null && frP.length > 3 && frP[3].startsWith("http")) priorCurado = true;
                }
                Intent dato = new Intent();
                dato.putExtra(EXTRA_CANALES, r.canales);
                setResult(RESULT_OK, dato);
                estado(msg);
                if (headless) {
                    if (priorCurado || prior == null || prior.isEmpty()) {
                        finish();
                    } else {
                        android.util.Log.d("BLABONGO_DEBUG", "captador(" + (headless ? "headless" : "visible") + "): entrega sin cura del prior — me quedo vivo hasta que la cosecha la traiga (o el plomazo)");
                    }
                } else {
                    manejador.postDelayed(DramaAutoActivity.this::finish, 1400L);
                }
            });
        }
    }
}
