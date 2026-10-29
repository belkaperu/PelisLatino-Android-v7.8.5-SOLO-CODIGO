# COMO conseguir y usar tu sesion DramaLive (v3.2.9)

Objetivo: que la app reproduzca los canales de DramaLive SIN que nadie tenga
que abrir DevTools, copiar JSON ni entender el alambre. Lo manual queda como
ultimo fallback. Todo lo demas pasa dentro de la app.

## El camino normal (automatico)

1. **Captador de sesion** (boton en la pantalla de canales, o desde Ajustes):
   abre el sitio real `https://web.dramalive.cc` en un WebView con el gancho
   `assets/drama_auto.js` (verificar en la barra verde: «captador v3.2.9
   INSTALADO (cookies + plantilla glbr + plan B con ganchos)»).
2. Haz lo que haces siempre: entra, ve tu canal. El gancho escucha XHR+fetch
   (desde v3.2.6 tambien fetch, antes era oido de sordo) y captura:
   - la request `glt` (identidad: user_id, plan_type, exp, adm, fbp…) y la
     `glbt` (topic) como PLANTILLAS;
   - cada RESPONSE `glt`/`glbt` como sesion util;
   - desde v3.2.9, si el sitio emite su request `glbr` completa (la del canal
     que estas viendo), se aprende como plantilla `glbrReq` con sus campos de
     sesion (token, rc, adsShowed, adsComplete, runCount, timeZone…).
3. Cuando el sitio muestra el stream real, el gancho **entrega y persiste solo**
   (bridge `Android.puenteEntregado`): sesion + plantillas + rejilla del topic
   vista en vivo (backfill v3.2.7: si `glbt` solo trajo ids-puro, la entrega
   reescribe nombre/logo en `drama_extra.m3u`). A partir de ahi, play =
   `glbr` real y reproduce.

## Que pasa al pulsar play (v3.2.9)

- `DramaSesion.payloadGlbr()` arma el POST: si hay plantilla `glbrReq`
  aprendida del sitio, sale CON sus campos extra; si no, replica el glt.
- `DramaSesion.post()` manda ademas `Origin`/`Referer` de web.dramalive.cc y
  las COOKIES del WebView (jar compartido; las Set-Cookie que caigan se
  asientan de vuelta — la sesion engorda sola). Esto es lo que el sitio hace
  con `credentials:include` y lo que distingue un stream real del sueno en el
  logcat del mismo movil.
- Respuesta = `update.mp4` (clip de relleno)? Plan B: se abre el Captador (no
  el scrape de la portada — 44 s perdidos que ya no existen); ahi el canal se
  ve una vez en el propio sitio y el gancho lo cura para siempre.

## Auto-cura de la rejilla

Al leer `drama_extra.m3u`, `sanearSuenos()` quita las filas cuyo stream sea
`update.mp4` (escritas por v3.2.6 cuando el sueno se colaba en el backfill).
No hay que re-extraer nada.

## Semilla manual (ultimo fallback)

`ajustes → pegar sesion` acepta:
- `dramalive://` (linea de stream real copiada del sitio);
- JSON de respuesta `glt`/`glbt` (semilla basica, rc/ads NO se aprenden);
- request glt COMPLETA con user_id → se convierte en plantilla (la semilla
  buena: por eso el Captador la aprende mejor que nadie).

El contador de anuncios (rc/adsComplete) NO se finge: si la plantilla no lo
trae, el POST va «flaco» y el servidor puede dar el sueno — otra razon de
aprender del sitio en vivo.

## v3.2.10 — blindaje de la rejilla y adiós al CORS falso (logcat 15:44)

Tu log de las 15:44 tenía un `ERROR: Access to fetch … glbt … blocked by CORS
(Access-Control-Allow-Credentials '')`: **no era el sitio rompiéndose, era
nuestro autodiagnóstico** (`replay()` del JS) pidiendo `credentials:'include'`
que el servidor no admite. Quitado — ese ERROR ya no aparecerá y el
autodiagnóstico funcionará igual (los XHR del sitio nunca llevan credenciales).

Y el «se van todas las canales»: dos agujeros cerrados —

1. `fijarUrlResuelta()` guardaba en la rejilla lo que devolvía glbr… incluso el
   clip de relleno `update.mp4` (fue el envenenamiento original de v3.2.6).
   Ahora **jamás** cachea `update.mp4`/`twoapistack` en la lista.
2. `guardarDramaExtra()` aceptaba una entrega vacía y **borraba** la rejilla
   buena. Ahora una entrega sin canales no pisa nada (queda en el log:
   `rejilla NO actualizada: la entrega venia sin canales`); el botón «quitar
   canales» usa la vía explícita `limpiarDramaExtra()`.
3. El plan B abre el Captador **encima** del player (sin `finish()`): sales con
   atrás y sigues en tu canal. La barra debe decir «captador v3.2.10
   INSTALADO».

Nota: si tu rejilla actual aún viene vacía de v3.2.6 (filas sueno que
`sanearSuenos` purga al leer), la cura es una sola: abrir el Captador, jugar un
canal ahí y la entrega regenera la lista completa con `dramalive://` + id_live
— esto ya es automático desde v3.2.7.

## v3.2.11 — fin de la tormenta de reinyección y de la entrega que encogía la rejilla

**Lo que enseñó el logcat 16:07 (con la v3.2.10 ya instalada):** el sitio funcionaba,
el gancho aprendió la plantilla glbr de **20 campos** (`PLANTILLA glbr del sitio
aprendida`), el glbr del sitio devolvió el **fas6 real** (`glbr resuelto OK`) y la
entrega llegó (`ENTREGA OK 89 canales; sesion=true`). Pero tres bugs nuestros lo
estropeaban:

1. **Guarda anti-duplicada rota** (la mia, al bumpear `v: 330` y dejar el chequeo en
   `=== 329`): cada reinyección de `reaparar` creaba UNA INSTANCIA NUEVA del hook (en
   el log: 6 contadores `req` distintos a la vez). Cada instancia re-playeaba los 10
   topics → **98 POST a la API en 13 s** → el servidor empezó a contestar 32 bytes en
   blanco a todo (`sin leer (len 32)`), incluido el glbt del propio sitio → la web del
   captador se quedaba sin canales. Ahora la guarda es «cualquier instancia viva basta».
2. **Entrega destructiva**: `ENTREGA OK 89 canales` reemplazaba la rejilla GUARDADA de
   miles. Al volver a Canales «se van todas las canales». Ahora la entrega se FUSIONA
   (`DramaSesion.fusionar`): actualiza nombre/logo/url de los que llegan, conserva el
   resto, y una url real ya curada (https) nunca se degrada a dramalive://.
3. **El play saltaba solo al Captador** («el clic en play se va a drama»): quitado. El
   señuelo ahora es un aviso claro; el Captador queda a un botón.

**Si tu lista quedó en ~89 canales** (por la entrega de la v3.2.10): Canales →
«Extraer canales» un toque — la sesión ya está guardada, no pide nada — y la rejilla
vuelve completa; de ahí en cada entrega solo fusiona.

## v3.2.12 — la COSECHA: los streams reales se piden desde la propia página

**Medición que lo justifica (logcat usuario 16:33):** con la plantilla de 20 campos
aprendida Y las cookies del WebView en el POST Java, el glbr del player seguía
recibiendo `update.mp4` (3/3 canales), mientras dentro del Captador el mismo glbr
daba el `fas6` real (log 16:07: `glbr resuelto OK`). El servidor gradúa por contexto
de navegador, no solo por campos. Pelea tonta no: **v3.2.12 no reimite el glbr desde
Java al azar — cosecha**.

Mientras el Captador está abierto y el sitio ya emitió su glbr (aprendizaje
`PLANTILLA glbr del sitio aprendida`), el gancho recorre las filas `dramalive://`
del topic que el sitio cargó y, UNA POR UNA (cadencia 0.9 s en acierto, 1.4 s si el
server da relleno, backoff 6 s si llega respuesta vacía, tope 100, parada tras 8
suenos seguidos), replayea EL glbr DEL SITIO desde la página y escribe la url real
cruda en la fila — el mismo formato probado del backfill (el host solo da 403 sin
User-Agent; ExoPlayer manda uno). Termina con `ENTREGA (force)` → `fusionar` → la
rejilla del app queda con los https buenos. **Play desde la app sin salir.**

Verificación: barra «captador v3.2.12 INSTALADO (cosecha de streams reales desde la
pagina)»; en el volcado, `COSECHA (motivo): N filas pendientes` y
`COSECHA fin: ok/hechos reales · suenos · vacias`. El player loguea
`post glbr: base=glbrReq del sitio (20 campos) · url=…` para que se vea CON QUÉ
salió cada intento (si dice `base=ninguna`, la prefs está pelada: reentrega).

Repro node que ejercita `cosechaRun` REAL (el del asset, no un muñeco):
`node docs/cosecha_repro.mjs` → `REPRO_OK` (1 fila curada, el sueno NO se escribe,
la ya curada no se pide, cadencia 8310 ms).

## v3.2.13 — curación headless: el play sueña, la cosecha arranca sola

**Medición (logcat usuario 17:14):** `post glbr: base=glbrReq del sitio (20 campos) · url=https://re…`
— la sesión viaja BIEN y el server igual da `update.mp4`. Confirmado 2/2 (16:33 y 17:14):
el POST desde Java está muerto para canales soñados; solo la página cura. Pero en v3.2.12
la cosecha exigía que el usuario ABRIRA el Captador, y el log 17:14 no tenía ni una línea
`DramaAuto` — no se abrió, no se curó.

v3.2.13 quita el paso humano: cuando el glbr del player devuelve el sueño, `PlayerActivity`
lanza el `DramaAutoActivity` en **modo headless** (ventana 2x2 px, alpha 0 — invisible, se
cierra solo a los 45 s) con dos siembra nuevos: (1) la semilla `__DRAMA_SESION` ahora incluye
`glbrReq` (antes solo `glt`/`glbt`), así que el gancho puede cosechar SIN que nadie juegue
nada en la página; (2) `EXTRA_PRIOR` → `window.__DRAMA_PRIOR='<id_live>'` y `ordenPrior()`
pone ese canal al frente de la fila. Apenas el prioritario se cura, `onFix` dispara
`ENTREGA force` al instante (no espera al fin de la pasada) — la fila del canal jugado queda
con https real y el usuario solo re-toca play ~1 min después, con toast que lo dice. Si la
sesión no trae plantilla glbr (nunca hubo Captador), el aviso viejo manda al botón.

Verificación: `node docs/cosecha_repro.mjs` → `REPRO_OK` + `SEED_OK` (la semilla arma
`A.plant.glbrReq`) + `ORDEN_OK` (prior al frente, resto estable) + `ONFIX_OK` (callback por
fila curada, el prioritario primero). Suite 123 (DramaSesion 45/45: gate `tienePlantillaGlbr`,
`idDeCanal` decodifica, bootstrap siembra `glbrReq`). En el teléfono: tras el log
«glbr devolvio el sueno … curacion headless» deben salir «ACTIVIDAD v3.2.13 creada»,
«hooks v3.2.13 (AES propio + COSECHA prior)», «COSECHA prior …», «PRIOR curado: … — entrega
inmediata» y «ENTREGA (force)» sin que el usuario abra nada.

## v3.2.14 — la fila sintética: el canal jugado entra SIEMPRE a la cosecha

**Medición (logcat usuario 17:47, primer headless real en teléfono):** la máquina funcionó
— `version:"v3.2.13"` inyectado, `ready:"complete"`, `filas:3`, `glbrOk:1`, `fijos:1`,
`ENTREGA OK 3 canales` a los 13 s (antes del plomazo de 45 s), cierre limpio de la ventana
2x2 (SurfaceFlinger: layer `0 0 2 2`, alpha 0) y `erroresJs:0`. PERO `filas:3` delató el
hueco de diseño: la cosecha solo conocía las 3 filas del topic que la pagina cargó;
`ordenPrior` ordenaba... lo que hubiera. Si el canal jugado no estaba entre esas 3 (casi
siempre), no se curaba: ordenar no es incluir.

v3.2.14 lo cierra: `asegurarPrior(pend, A.filas, prior, N, L, G)` — si ninguna fila lleva
`id_live==prior`, SINTETIZA una con el nombre/logo/group REALES leidos de la rejilla
(`DramaSesion.filaDeRejilla`, puro), la mete en `A.filas` (para que ENTREGA la incluya) y
al frente de la cola. El glbr sintético viaja por el mismo POST probado de la cosecha; al
curarse, `onFix` dispara `ENTREGA force` y `fusionar` lo casa POR ID (`posId`) -> la fila
del canal jugado queda con https real sin perder su identidad. Ojo código: `desdePuente`
DESCARTA filas sin nombre — por eso la identidad viaja desde el player (extras
`EXTRA_PRIOR_NOMBRE/LOGO/GRUPO`), no un `n:null`.

Verificación: `node docs/cosecha_repro.mjs` -> `REPRO_OK · SEED_OK · ORDEN_OK · ONFIX_OK ·
ASEGURAR_OK · SINT_PARAMS_OK` (sintetiza con identidad, no duplica si ya está, fallback
n=id si la rejilla no tiene el canal, y los params del sintético parsean igual que una
fila real). Suite 124 (DramaSesion 46/46, nuevo `filaDeRejilla_conservaIdentidadDelCanalJugado`).

## v3.2.15 — auto-reintento del player y cura trazable por canal

**Medicion (logcat usuario 18:25-18:27):** el player logueo `glbr devolvio el sueno ...
curacion headless` y DESPUES silencio total de `DramaAutoAct` — cero `inyectado`, cero
`ENTREGA` — y al reintentar dos minutos despues el glbr sigio dando sueno. El log de v3.2.13/14
imprimia ese aviso ANTES del gate `curacionLanzada && tienePlantillaGlbr`, y las ramas que no
lanzaban (cooldown de por-vida, gate false) eran MUDAS: imposible saber desde el log que paso.
Ademas el boolean `curacionLanzada` bloqueaba reintentos legitimos del mismo canal.

v3.2.15 hace la cura trazable y se auto-reintenta:
- El player loguea SIEMPRE por el tag `BLABONGO_DEBUG` (el que el usuario mira):
  `cura headless: pid=… · plantilla=true/false · LANZANDO | EN VUELO (no relanzo) | NO se puede lanzar`,
  y tras lanzar `cura LANZADA para <pid> — captador invisible corriendo, plomazo 90 s`.
- El gate pasa a ser POR CANAL con cooldown de 100 s (`canalCurado` + `curacionTs`) en vez de
  un boolean por apertura: cada canal sonado lanza su propia cura, reintentos repetidos no.
- El player **se reintenta SOLO a los 70 s**: lee su propia fila en la rejilla
  (`filaDeRejilla` ahora trae tambien la url en el slot 3); si la ENTREGA ya la puso https,
  reproduce SIN que el usuario toque nada (`cura LLEGO a la rejilla — reproduciendo sin salir`)
  y la cachea con `fijarUrlResuelta` igual que el camino normal; si no llego, re-resuelve el
  glbr UNA vez en silencio (sin toasts, sin relanzar el captador por el cooldown).
- El plomazo headless pasa de 45 a 90 s y loguea `PLOMAZO 90s SIN ENTREGA` para que la
  derrota tambien sea visible; `inyectado` ahora muestra `prior=<pid>` (cableado verifiable).

Verificacion: suite 124 (DramaSesion 46/46, test de identidad extendido al slot url); repro
node sigue 6/6 (`REPRO_OK · SEED_OK · ORDEN_OK · ONFIX_OK · ASEGURAR_OK · SINT_PARAMS_OK`);
APK con las cuerdas nuevas dentro del DEX (grep `cura LANZADA|LLEGO a la rejilla|EN VUELO` =
6 hits en classes3.dex) y asset byte-identico.

## v3.2.16 — la cura vive en su propio task: cerrar el player ya no la mata

**Medicion (logcat usuario 19:06-19:07, v3.2.15):** la trazabilidad nueva hizo el diagnostico
posible: `cura headless: pid=live_tv_alkaas5 · plantilla=true · LANZANDO` y
`cura LANZADA … plomazo 90 s` — la cura ARRANCA bien — pero 17 s despues `⏹ Cerrando
reproductor` y nunca mas `ENTREGA`. Codigo en la mano (leido esta misma sesion):
`cerrarReproductor()` hace `finishAndRemoveTask()`, que destruye el TASK ENTERO, y el captador
headless estaba en el mismo task -> el cierre lo mataba antes de la cosecha. Ademas el cooldown
era de INSTANCIA: cada tap del usuario re-creaba el PlayerActivity y relanzaba un captador nuevo
(alkaas2 lanzado 2x en 27 s, y el panel_9df3 2x en 16 s). El «nada» del usuario era el cierre
matando la cura, no la cura fallando.

v3.2.16, cuatro piezas:
1. **Manifest**: `DramaAutoActivity` con `taskAffinity="dza.fut.paipo.cura"` +
   `excludeFromRecents="true"`; y el intent de cura lleva `FLAG_ACTIVITY_NEW_TASK` (solo el
   launch headless). Cerrar el player no toca la cosecha; la ventana 2x2 no ensucia Recents.
2. **Cooldown POR PROCESO**: `DramaSesion.curaFresca(map, pid, ahora, 100 s)` (puro, testeado)
   sobre un `ConcurrentHashMap` estatico del player — por canal, sobrevive re-creaciones del
   player, corta la re-lanzadera.
3. **Relevo + autopolling**: si un tap cae con cura EN VUELO, el player nuevo toma el relevo
   (`canalCurado=pid`) y se re-intenta a los 20 s; `reintentaTrasCura` avisa ademas al captador
   vivo (`instanciaViva().pedirRelevo(20 s)`) para que la poll de entrega siga aunque el player
   este cerrado. En la practica: tocas, ves el relleno, cierras tranquilo — cuando la ENTREGA
   caiga la fila queda https y el PROXIMO tap suena directo sin esperar nada.
4. **Tag unico**: el captador espeja sus hitos al tag `BLABONGO_DEBUG` que el usuario ya filtra
   (`captador(headless): ACTIVIDAD v3.2.16 creada | inyectado … · prior=… | ENTREGA OK n canales
   (fusion) | PLOMAZO SIN ENTREGA a los 90s`) — nada queda invisible segun el filtro.

Verificacion: suite 125 (DramaSesion 47/47, test `curaFresca_esPorCanalYConVencimiento`); repro
node 7/7 marcadores (`REPRO_OK · PARAMS_OK · SEED_OK · ORDEN_OK · ONFIX_OK · ASEGURAR_OK ·
SINT_PARAMS_OK`, exit 0); APK: asset byte-identico 40 082 B, 7 hits de las cuerdas nuevas en
classes3.dex, y `aapt2 dump xmltree` muestra `taskAffinity="dza.fut.paipo.cura"` +
`excludeFromRecents=true` DENTRO del manifiesto binario del APK.

## v3.2.17 — acceso por codigo FG: tiempos ampliados, 3 intentos y atajo normalizado

**Queja del usuario:** «aumenta el tiempo para poder acceder a drama live con el codigo
fgcode.org/232425 — no me deja entrar, el tiempo es muy corto». Medido desde el sandbox:
`https://fgcode.org/232425` es una pagina de error (meta-refresh que OkHttp no sigue; no
trae lista) y `fgcode.store/link/api.php/get?code=232425` responde **HTTP 500 en 0.47 s**
(el 500 de MySQL del store, ya documentado aqui: transitorio). Dentro de la app habia tres
cuellos de reloj: fuente 12/25 s y CERO reintentos (`FuenteCanales.cliente()`), cadena de
redirects del reproductor con **3+3 s por salto y 25 s de ventana total** (`StreamResolver`),
y WebView de embed con 45 s.

Cambios: (1) fuente: **30/60 s** + `cargarIntento` — 3 intentos con esperas de 5 s y 15 s,
tambien ante 5xx; (2) `AutoCanales.descargar1` (la lista entera): idem, 3x con backoff en
hilo de red; (3) `StreamResolver`: 12/15 s por salto, `TIEMPO_MAX_CADENA_MS` 25->**90 s**,
WebView 45->75 s; (4) `normalizarAtajoFg` (puro, testeado): pegar `https://fgcode.org/232425`
como URL o como codigo se reescribe a `https://fgcode.org/link/api.php/get?code=232425` —
solo si el host contiene "fg" y el path es puramente numerico; cualquier otra URL queda
intacta. En el codigo: los tres cuellos tenian comentarios que ASSUMIAN que el usuario
podia esperar; ahora la ventana es de un minuto y medio y el error final lo dice claro
(«3 intentos, ~1 min») para distinguir «tiempo» de «el proveedor esta caido».

Verificacion: suite 126 (nuevo `normalizaAtajoFgHaciaElEndpointApi`: fgcode normalizado por
ambas vias, host ajeno NO tocado, .m3u8 intacto); `tests_python/test_curacion_v3216.py` 9/9
con `test_T09_tiempos_fg_ampliados` (constantes leidas del fuente real); APK con el asset
byte-identico y 3 hits de las funciones nuevas en classes3.dex.

## Cifras verificadas (medidas, no promesas)

| Version | Medida |
|---|---|
| v3.2.5 | `extraer()` ok=true, 9115 canales en 7979 ms JVM (sesion envenenada rc=8/ads=7) |
| v3.2.6 | gancho ve fetch() (repro node: 2 llamadas -> 2 entradas); `puenteRecibido` valido |
| v3.2.7 | fusion grid: `name: "TyC Sports HD"  url: "dramalive://…"` (test JVM) |
| v3.2.8 | endpoints `re.highperformanceconnect.shop` + desenrollo `data.url` embebido (test JVM) |
| v3.2.12 | suite 120 (diagnosticoGlbr x2); repro node `cosechaRun` real: REPRO_OK, cadencias medidas 8310 ms; APK con asset 38 338 B byte-a-byte |
| v3.2.13 | suite 123 (DramaSesion 45/45); repro: REPRO_OK+SEED_OK+ORDEN_OK+ONFIX_OK; APK asset 39 151 B byte-a-byte, sha APK 3492aefc58900075 |
| v3.2.14 | suite 124 (DramaSesion 46/46); repro 6 checks OK incl. ASEGURAR_OK; APK asset 39 964 B byte-a-byte, sha APK 83179706a1bd5cde |
| v3.2.15 | suite 124 (46/46); repro 6/6; plomazo 90 s; auto-reintento 70 s; APK asset 40 078 B byte-a-byte + 6 hits del codigo nuevo en classes3.dex, sha APK 2f25e31a5002eefe |
| v3.2.16 | suite 125 (47/46→47/47); repro 7/7 exit 0; APK asset 40 082 B byte-a-byte, 7 hits DEX, aapt2: taskAffinity+excludeFromRecents presentes, sha APK 21ae14f2faa08434 |
| v3.2.18 | onReceivedError headless ya no finish: backoff 4x (4/8/15/25 s); PLAZO_HEADLESS/CADENA/WEBVIEW = 120 s; fuente read 75 s x3 intentos; harness 9/9 con T09 actualizado a 120_000 |
| v3.2.19 | fuente FG caída (500/timeout) + rejilla vacía => la app ABRE SOLA el captador visible (el camino que sí dejaba conectar y extraer); un disparo por visita; 2 min y backoff intactos |
| v3.2.20 | paquete renombrado dza.folbol.BLABONGO -> dza.fut.paipo (namespace+applicationId+dirs+manifest+tests); suite 126; APK con badging dza.fut.paipo. ATENCION: es otra app — datos frescos |
| v3.2.28 | PRUEBA-PYTHON DE UNA CANAL (pedido del usuario): cadena completa resuelta 100% en Python (glt 201/265 topics, glbt 201/81 canales, glbr 201 result:0) — wire y plantillas impecables; el 403 «CF error code 1010» se mata con UA Chrome (descubierto hoy); update.mp4 sale en las 10 variantes de contadores/agent/token = gate IP o SDK de ads, no nuestro bug. Fix: la cosecha hace la jugada del sitio — ve el update.mp4 como anuncio (muted, 22 s techo) dentro del WebView y RE-PIDE glbr con runCount+1/ads=1 (verAnuncio + A.adWatched). T15 en el repro (glbr=2, priorFijo=1), rojo contra el 3.2.27. Suite 133 |
| v3.2.29 | EL PLAY REAL PORTADO (prueba-python 12/10 22:40, verde de punta a punta): la cadena del navegador es glasbi{id+auth GLT+token+contadores} -> live.url '.LS.V2...' agent 'redirect' -> glbr -> JSON 'advanced' cuya .url es la pagina-player /pages/.../index.html que ES playlist HLS live con prefijos ofuscados (swap k9x_: con prefijo 404, sin el 200; MPEG-TS h264 1024x540+aac, 25 frames decodificados con PyAV). El pedir() de la cosecha glasea con glt y pide glasbi antes del glbr (si trae .LS. cambia url/agent); PlayerActivity monta RELAY para paginas /pages/: baja la playlist, aplica swap+absolutizado, escribe cacheDir/drama_relay.m3u8 y refresca cada 4 s (ExoPlayer la relee como live). T16 en el repro (verde con 29, ROJO con 28 — glasbi=0), PlayerActivityTest 3 tests nuevos, suite 135 (ApiProbeTest, sonda temporal que fallaba a proposito, eliminada). docs/proof_play_demo.py = prueba entregada; update.mp4 era SOLO la deco all_streams_redirect |
| v3.2.30 | LOGCAT REAL DEL USUARIO (12/10 17:51, «WAW ERES TOP»): play funcionando EN EL TELEFONO — glasbi suelta el marcador '.LS.V2' en su red, la cosecha cura la fila (1/1 reales, 0 suenos, curado=1), el player carga la pagina del advanced, segmentos .js 200 uno tras otro, decodificador HW c2.mtk.avc.decoder abierto y audio MediaTek activo durante 18 s de live. DETECCION: esos canales venian en /mirsp/ y /malasp2/ (NO /pages/) con playlist sin prefijos, asi que el relai no hizo falta; para los que si traen swap el gate ya no mira la ruta — toda pagina index.html pasa por el relai, que ademas verifica que la respuesta arranque con #EXTM3U antes de tocar nada (si no, url cruda como antes). PlayerActivityTest: assertions /mirsp/ + /malasp2/. Suite 135/135, BUILD_TAG v3.2.30 |
| v3.2.31 | «QUE SEA MAS RAPIDO Y EXTRAER LAS OPCIONES DE CADA CANAL» (pedido del usuario con el play ya funcionando): DramaSesion.resolverVivo resuelve el tap EN JAVA — glasbi{plantilla glbr+id} -> marcador .LS.V2 -> glbr(redirect) -> advanced — en ~2-4 s sin WebView ni cosecha (antes 25-35 s si el canal caia al sueno); del mismo glasbi salen las OPCIONES (url principal + backup ' -;- ' filtrando iframes, nombre via description_(X)_ como el dart): chip flotante «HD ▾» en el player con PopupMenu; elegir calidad fija el marcador y re-resuelve al toque (resolverMarcado, sin volver por el sueno). Todo lo nativo es FALLBACK-SAFE: si glasbi no suelta marcador, se cae al flujo viejo (glbr pelado -> sueno -> cura headless) sin perder nada. Tests puros nuevos: urlGlasbiDe, vivoDesdeGlasbi (muestra textual de la red medida), opcionesDeLive tira iframes, nuevasOpcionesCalidad |
| v3.2.32 | ESTILO DRAMA+ (queja del usuario: «las imagenes se ven chiquitas o blancos»). Causas medidas: (a) `app:tint` en el ImageView de item_canal teñía de gris-blanco TODOS los logos que pintaba Glide; (b) fitCenter + padding 6dp + transform FitCenter de Glide: ningun logo llenaba la losa; (c) el grid exigia minimo 3 columnas de 112dp — tiles de ~104dp, la mitad que DramaLive web (~170px); (d) sin tvg-logo quedaba un icono pálido sobre losa plana. Fix: centerCrop sin tinte, losa con gradiente (bg_tile_losa), AVATAR de iniciales con paleta por nombre (Logos.avatar/iniciales/indicePaleta — puros, testeables) como placeholder/error/fallback para que NINGUN tile se vea vacio, columnasPara() = minimo 150dp y piso 2 columnos (tile ~166dp en telefono), radio 14dp, gutter 5dp, nombre en negrita sobre la banda. TileStyleTest x3 nuevos |
| v3.2.33 | «LAS IMAGENES ESTAN PASANDO EL LIMITE, DEBEN VERSE BIEN»: el centerCrop de v3.2.32 recortaba por los costados los logos ANCHOS (banners deportivos) — lo que pasaba el límite de la losa desaparecía. Vuelta al marco DramaLive de verdad: fitCenter + padding 12dp (logo ENTERO, respirando), sin tinte, losa de gradiente como fondo; Glide sin transform (baja la imagen entera al tamaño del tile); avatar subido a 440 px para que la letra se vea nítida en losas de ~166dp. assert AVATAR_PX en TileStyleTest (143/143) |
| v3.2.34 | «NO HAS ICHO LA CONEXION AUTOMATICA A DRAMA Y EXTRACCION» — ahora lo es de punta a punta SIN captador ni semilla ni WebView: DramaSesion.gltInicial() (plantilla anonima empotrada, device_id/user_id frescos por instalacion, los campos que el edge valida medidos) + glbrReqDesdeGlt (token=user_id, contadores de ads en 1) + conectarYSembrar() (sonda el glt real en li…/glt y arma la sesion completa {url,urlGlbt,urlGlbr,plantillas}). CanalesEnVivoFragment.onResume: sesion vacia -> auto-conectar + DramaSesion.extraer() nativo (glt->topics->glbt->canales->m3u) en un hilo -> guardarDramaExtra (FUSION, no pisa lo del usuario) -> rejilla llena sola con toast «Conectado a Drama: N canales»; si el edge blaquea, plan B = captador headless (lo de siempre). PlayerActivity sin sesion: auto-conecta EN EL TAP antes de resolver — fresh install reproduce beIN al primer toque sin abrir el Captador. Tests puros x2 (gltInicial shape + semillaDerivada/glasbi); 145/145. MEDIDO PROBANDO: el glt de li. exige MICROSEGUNDOS (16 digitos) en user_id — el sitio genera uid = ts*1000 — y el device_id tiene que nacer UNA VEZ y persistirse (prefs drama_seed): regenerar uuid en cada retry garantiza el 200-en-blanco del antiflood. Ojo: 12/10 22:5x-23:5x el sandbox quedó blaqueado EN BLOQUE en li. (hasta la plantilla dorada del usuario contesta blank) — puerta anti-flood IP temporal; en la red del usuario el sitio trata al WebView con glt-anonimo igual que aqui, y el plan B (captador headless) sigue intacto |
| v3.2.35 | «IC_PELIS BORRALA, NO SE VE BIEN SE QUEDA TACHADO» + «ESCONDER BUSQUEDA/TODO/PELICULAS/SERIES/GENERO/PAIS, MOSTAR CON CLICK» + «AGREGA BUSQUEDA POR AÑOS COMO GENERO Y PAIS» — el carrete ic_pelis del placeholder de poster (alpha .28 centrado) y el ImageView fantasma del estado vacio (siempre encima de la rejilla) fuera: placeholder = degradado limpio. Buscador + chips Todo/Peliculas/Series + Genero/Pais/Año viven en `panelFiltrosPelis` que NACE GONE; el chip «Buscar y filtrar ▾» de la franja de resultados lo muestra/esconde y avisa «Filtros activos» si algo queda puesto. NUEVO FILTRO AÑO clon del de pais: `PelisApi.aniosDelCatalogo(root)` extrae los años del propio catalog.json (year/anio/release_date/first_air_date — el catalogo ya se descarga entero, filtro del lado cliente sin tocar el servidor), `lista(..., int anio)` filtra con `anioNumero` y `catalogoPagina` gana overload (el viejo delega con 0, nadie se rompe). Tests: FiltrosPeliTest x3; 148/148 |
| v3.2.36 | «EL CLICK EN AÑO SE CIERRA» + «NO QUIERO MOSTRAR EL TXT CATEGORIA/PELICULAS Y SERIES/ENCUENTRA TU PROXIMA HISTORIA, BORRA ESO» — causa medida del cierre: `opcionesAnio` se puebla SOLO cuando baja el catalog.json (network); con el panel abierto antes del fetch, el guard de `mostrarSelector` cortaba con Toast y el dialogo «se cerraba» sin abrirse. Fix doble: `PelisApi.aniosPorDefecto()` (años 1980..hoy, instantaneo, sin red) como reserva del selector — el dialogo SIEMPRE abre; y el estado abierto/cerrado del panel se persiste en prefs `pelis_ui/filtros_visibles` (antes girar o cambiar de pestaña reconstruia la vista y el panel caia a GONE — parecia que el click lo cerraba). FUERA los tres rotulos del header (txtCatalogoOverline/Titulo/Descripcion — ningun java los referenciaba, medido); el header ES el panel (bg_header_cine absorbido): cerrado = cero pixeles, abierto = buscador+chips. Tests: FiltrosPeliTest x5 (+reserva de años con descenso exacto, +guarda de cabecera borrada); 150/150 |
| v3.2.37 | «TAMPOCO» — el ano seguia cerrandose en el telefono con el dialogo intacto en codigo, y no habia forma honesta de reproducir la ROM desde el sandbox: en vez de adivinar, se QUITO el punto de fallo. Los anos ahora son una TIRA DE CHIPS dentro del panel (`tiraAniosPelis`, la pinta `pintarTiraAnios` con 16 recientes + «Todos los años ▾»): un toque aplica el filtro (otro, lo quita), sin dialogo que se cierre, sin red (reserva `aniosPorDefecto`), y `pintarSelectores` la repinta con la seleccion. El viejo dialogo sigue para «Todos» pero blindado con try/catch → logcat «FILTROS: dialogo anio fallo: …» + aviso que manda a la tira si la ROM lo mata. Medible: `adb logcat -s FILTROS` dira el camino que tomo cada toque. FiltrosPeliTest x6; 151/151 |
| v3.2.38 | «EL TOCAR GENERO SE CIERRA — ARREGLALO TODO SIN REPETIR TUS TONTERIAS» — confirmado: el AlertDialog de opciones SE CIERRA SOLO en la ROM del usuario PASO QUE SE TOQUE (primero Ano, ahora Genero: mismo MaterialAlertDialogBuilder). La fixes por via correcta: CERO ventanas. `scrollListaFiltros`+`listaFiltrosInline` dentro del panel: `alternarListaFiltros("Genero"/"Pais"/"Anio")` despliega las filas ahi mismo (toggle: segundo toque al chip pliega), `addFilaOpcion` usa el MISMO selector visual de los chips (bg_pelis_filter, bold+selected), un toque aplica y pliega. borrados `mostrarSelector`+`mostrarSelector{Genero,Pais,Anio}`+el import MaterialAlertDialogBuilder (0 usos restantes, medido). Y Genero/Pais dejan de depender de la red: onOk cachea las listas en prefs pelis_ui (gen_json/pais_json/anio_json via `lineasDeOpciones`/`opcionesDesdeLineas`, formato linea=tab), onCreateView las carga AL INSTANTE — abrieron la app sin internet, eligen igual; si nunca bajaron, la lista muestra «catalogo no ha bajado — toca para reintentar» (fila, no toast). La tira v3.2.37 de anios sigue para acceso rapido. FiltrosPeliTest x7 (inline sin ventanas + roundtrip de cache); 152/152 |
| v3.2.39 | «FALTA AGREGAR FAVORITO PARA CUANDO QUIERO VER» + «QUITA LOS NUMEROS DE CUANTAS PELICULAS HAY, NO SE VE PROFESIONAL» — FAVORITOS: clase pura `Favoritos` (clave=slug si hay, id si no; alternar; serializar por lineas), set vivo `PelisApi.FAVORITOS`, `PelisItem.TIPO_FAVORITOS` con `lista(..., favMode)` filtrando movies+series por la clave, corazon ♥/♡ en `item_pelis` (`btnFavPelis` abajo-izq, no abre el detalle — `setOnFavoritoClick`), chip «★ Favoritos» en la fila de tipos, prefs pelis_ui/favoritos, y el TIPO elegido persiste (`tipo_actual` — manana abres la app y sigues en Favoritos). NUMEROS FUERA: `txtConteoPelis` borrado del layout y del fragmento (cero «312 titulos», cero «Buscando...»), y `· cantidad` fuera de la tira de anos y de la lista inline (los conteos se siguen calculando en la API — solo ya no se muestran). FavoritosTest x3; 155/155 |
| v3.2.40 | «ELIMINA LA PUBLICIDAD DE START.IO POR AHORA» — Start.io (startapp inapp-sdk 5.3.0) estaba montado en CUATRO puntos del arbol canónico (no solo en la variante folbol del envenenado): Main.java init+setTestAdsEnabled antes de setContentView, el Banner fijo anadido a bannerContainer, la meta-data APPLICATION_ID 205773556 en el manifest y la implementation en build.gradle, con su contenedor FrameLayout en activity_main. FUERA los cuatro, "por ahora" documentado en los propios sitios (comentarios v3.2.40 con la ruta de vuelta); fragment_container pasa a anclar bottom a parent = catalogo a pantalla completa. `CeroAnunciosTest` x1 vigila las cinco piezas (si un merge se trae el SDK de vuelta, el build lo pilla). 156/156 |
| v3.2.41 | «NO SE ABRE, SE CIERRA AL ABRIR» — causa medida: v3.2.40 quitó el SDK pero dejó `item_ad_nativo.xml` (inflaba `com.startapp.sdk.ads.banner.Mrec`) y sus inyectores en agenda (cada 3) y canales (cada 30); aapt no valida clases de vistas en layouts, así que compila y revienta al inflar en el arranque. FUERA: layout, filas inyectadas, tipos ANUNCIO y ViewHolders en ambos adapters, span-lookup del grid; `CeroAnunciosTest` nueva guarda — ningún XML de res ni inflate de java puede volver a nombrar el SDK. 157/157 |
| v3.2.44 | **Conexión y extracción EN SEGUNDO PLANO de verdad: `AutoDrama.plan` (puro, 4 tests) decide — sin sesión: glt empotrado con el FG 232425 dentro (el usuario no agrega nada) + extraer + **fusionar** (ya no pisa las curadas) + `notificarEntrega`; con sesión vieja (>6h): refresco NATIVO con la misma identidad del aparato; el captador headless queda como plan B cuando el edge no cuaja. Ventana anti-flood intacta (un intento/día máximo)** | 173/173 |
| v3.2.46 | **Queja medida: «se devolvió el reloj» y «¿salta a conectar en primer plano?». Dos arreglos: `AutoDrama.plan` ya no confía en una resta cruda de hora de pared — la ventana vale solo dentro de [−10 min, +6 h] y cualquier anomalía (reloj atrasado/congelado/adelantado) se trata como "toca moverse" y el intento reescribe el ts: **auto-reparación**, la rejilla ya no se congela; y el auto **nunca más lanza el captador** (ni headless ni visible al caer la fuente FG) — falla → estado discreto + reintento silencioso al vencer la ventana. 2 tests nuevos de reloj** | 174/174 |
| v3.2.45 | **Fuera la hoja «Fuente» entera (dialogo, botón del header, helpers de panel/alcance y su layout): con la cadena automática el único paso manual que quedaba era configurar una fuente — ya no hace falta. Se fue SOLO el UI: `FuenteCanales` (motor/prefs), el FG 232425 embebido en `gltInicial`, el ⟳ recargar y todo `AutoDrama` intactos; imports muertos purgados de una pasada** | 173/173 |
| v3.2.43 | **La tira de canales, clonada de las capturas de Drama (17/10): `TiraCanales` arma ◎ Todos · ♥ Favoritos · ★ Los más vistos · 🇵🇪 país del aparato clavado · tópicos en SU orden (ya no «los más poblados») · 🔥 Partidos en vivo (marcador `live_tv_custom_handler` del feed). Corazón ♡/♥ en cada losa (`UsoCanales`, prefs `canales_ui`, clave = id_live o slug del título) y cada apertura desde la rejilla cuenta para ★. 7 tests nuevos** | 169/169 |
| v3.2.42 | «CADA CANAL EN SU LUGAR COMO HACE DRAMA» — la rejilla mostraba la lista por defecto primero y los canales de Drama pegados al final (dedupe por URL que nunca coincidía: duplicados + desorden). `FusionRejilla` (pura, 5 tests): Drama manda en su orden exacto, la base rellena huecos dedupando por nombre; los chips salen con nombre humano del tópico y bandera (🇵🇪 Perú…); `extraer()` usa el `topic_name` del glt como group y sube el corte de tópicos de 12 a 40 | 162/162 |
| v3.2.27 | HITOS PAGARON (log 12:26): semilla OK (glbrReq=20 campos) pero la cosecha moria «BLOQUEADA: auto en vuelo» — el disparo de 9 s caia dentro de auto() (~17 s) y A.coseHecho ya estaba consumido -> el tick no reintentaba JAMAS (mismo final en visible). Fix: bloqueo devuelve la bandera + auto() arranca la cosecha al terminar + «cura agotada» pasa por abrirCaptadorVisiblePara (re-inyectaba el hook sobre el visible vivo a las 12:29:07) + 9 prefijos «captador(headless):» mentirosos ahora dicen el modo real. T14 en el repro, rojo contra el viejo. Suite 133 |
| v3.2.26 | LOG 00:36 (v3.2.25): plomería OK, cosecha MUDA sin rastro -> 4 fixes: bootstrap acepta semilla solo-glbrReq (antes el glt sin user_id mataba la semilla entera), HITOS BLABONGO incondicionales en toda la cadena semilla->cosecha (nuevo puente AndroidDrama.hito), log «cosecha terminada» ANTES del guard y con prefijo de modo real, y anti-thrashing (no re-abrir el captador visible si ya hay uno vivo — NEW_TASK cortaba la cosecha ajena). T13 en glbr_seed_repro (rojo contra el viejo), DramaSesionTest 54/0, suite 133 |
| v3.2.25 | DIAGNOSTICO DEL USUARIO (log 23:56: cero lineas COSECHA en 5 min) VERIFICADO: DOS guardas `user_id` descartaban la plantilla glbrReq (que NUNCA trae ese campo) — una en el consumidor de la semilla y otra en el aprendizaje por trafico, que ademas se comia el disparo inmediato de la cosecha. Fix: glbrReq entra sin exigir user_id (identidad = token/cookies); demas plantillas siguen filtradas. El fixture SEED_OK viejo tapaba el bug (glbrReq ficticio CON user_id) -> puesto veraz + nuevo docs/glbr_seed_repro.mjs (SEED25/JUNK25/TRAFFIC25) probado en rojo contra el codigo viejo. Suite 131, python 12/12 |
| v3.2.24 | CONEXION AUTOMATICA A DRAMA (capturas: el sitio abre en su wizard «I have read and accept» + FG Code). Medido en main.dart.js: el setup vive en localStorage (flutter.terms_continue + flutter.source_config, SourceModel doble-json type fgCode). El captador siembra esas claves con el codigo que la app ya guarda y el sitio arranca SIN wizard; en el lazo inverso aprende el codigo del trafico/source_config (aprenderCodigo) si el usuario lo puso a mano. OJO: un } de mas en el seed romperia el evaluateJavascript ENTERO — nuevo test T10 ensambla el JS desde el Java y lo ejecuta con node contra un mock (SEED_SIM_OK). Suite 131, DramaSesionTest 52/0 |
| v3.2.23 | LOG USUARIO 23:24-23:31 (v3.2.22): «me quedo vivo» y «miro la fila en 15 s» funcionaron (2 inyecciones vs 45) peo el re-LANZAR reusaba la instancia y el plomazo viejo la mato a los 10 s. Fix: onNewIntent re-arra los 120 s; no relanzar si el captador vivo ya cura ese pid; la cosecha avisa su fin (cosechaTermino) y si no curo al prior quema el canal -> escape directo al captador VISIBLE. Suite 128 (1 rojo ambiental) |
| v3.2.22 | LOG USUARIO 13:01 (v3.2.21 en dza.fut.paipo): el fix funciono (ENTREGA despierta al player x2) peo el headless se cerraba con la cosecha a medias y el player re-resolvia el glbr contra el mismo sueno. Fix: captador vive hasta ver el prior curado (o plomazo 120 s); con cura en vuelo solo re-mira la fila cada 15 s; inyecciones throttleadas 4 s + reaparar tope 10. Suite 128 (1 rojo = ApiProbeTest ambiental) |
| v3.2.21 | LOG USUARIO 12:22 (v3.2.19): ENTREGA OK pero «aun no llega a la fila» — la cura mataba el id_live al pasar la fila a https. Fix: di/tvg-id sobrevive (idFila, fusion hereda id), ENTREGA despierta al player (notificarEntrega) y a los 3 ciclos abre el captador VISIBLE con el canal. Suite 128 (2 tests nuevos de fusion) |
| v3.2.17 | suite 126 (normalizaAtajoFg, 4 casos); harness python 9/9 (T09 tiempos FG); APK 8758ccdee7053d7f, asset 40 077 B byte-a-byte, 3 hits nuevas en classes3.dex |
| v3.2.11 | suite 118 (fusionar ×3: no borra, no degrada, viejo vacío); guard anti-apilamiento sin versión; node --check OK |
| v3.2.10 | suite 115 (2 nuevos: `contarBloques`, `sanearSuenos` inocuidad); cero `credentials` en el JS; APK destilado byte-a-byte |
| v3.2.9 | suite 113 tests (rojo unico historico = ApiProbe de red); DramaSesionTest 35/35; APK destilado byte-a-byte con el JS de assets |

## Protocolo anti-rollback del entorno

Este sandbox ha borrado el arbol 6 veces (rollbacks 24-28). Ancla de
reposicion: `/home/user/PelisLatino-estilo-cine.zip` (rebuild en CADA entrega;
verificar sha256 contra lo entregado antes de tocar nada). Tras reposicion:
`bash setup-only.sh` (JDK), `pip install pycryptodome` (probe), commit
«consolidacion», y RE-MEDIR `git rev-list --count` para el contador de docs
(se desincroniza: los commits de consolidacion suman).
