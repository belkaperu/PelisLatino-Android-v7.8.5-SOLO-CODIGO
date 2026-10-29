# HANDOFF — estado del proyecto (para el proximo turno, cualquier IA)

Repo: /home/user/pelislatino, rama mejora-estilo-cine. App: PelisLatino
(dza.fut.paipo). Todo en ESPANOL. El usuario prueba en su telefono
(Peru); sus capturas de logcat son la fuente de verdad.

## Reglas del usuario (innegociables)

- Evidencia medida (comandos + numeros) en cada respuesta; jamas «funciona»
  sin prueba sobre el codigo real.
- Entregar SIEMPRE tras cada fix: zip plano Android Studio
  (/home/user/PelisLatino-estilo-cine.zip, raiz sin carpeta envolvente),
  APK en /home/user/app-debug.apk y patches git format-patch en
  /home/user/patches/ (0001..N = pila de commits).
- «Captura y se arregla en un paso»: diagnostico + fix + build + reentrega en
  el mismo turno.
- Todo automatico dentro de la app; lo manual (DevTools/copiar-pegar) es
  ultimo fallback. No culpar al usuario (lema: PELIGRO: no culpar al usuario).
- Tema: MaterialComponents oscuro cine (NO Material3/dynamic color).
- Canales de pago/geo: la app debe DECEPCION visible (toast), nunca prometer.

## Estado funcional (v3.2.9)

- Catalogo propio + API de canales (admin token, saliente-only), FG code,
  beIN, exportador navegador, tunnel nfy.pucelazo.net:8443, tema, verificador
  de enlaces, reductor de logos — TODO entregado y estable (patches 0001..0010).
- DramaLive: glt/glbt/glbr contra re/li.highperformanceconnect.shop, AES-CBC
  propio (KEY 43fc9b1a3f854049, alambre b64std(ct):b64std(iv)), desenrollo
  data.url embebido (fas6 real o update.mp4 sueno -> Plan B Captador),
  semilla dramalive://, Captador WebView+bridge con hooks XHR+fetch,
  plantilla glbrReq aprendida, cookies/Origin/Referer en POST, sanearSuenos
  en lectura de rejilla, backfill del topic.
- Diagnostico cerrado del «clip de relleno»: request flaca vs sueno del
  servidor (logcat 15:14 vs 14:06 mismo movil). La prueba definitiva del
  unlock es en el telefono del usuario (IP de datacenter ve sueno siempre —
  verificado con pycryptodome: 4/4 combinaciones sesion x canal = update.mp4).

## Como compilar / verificar (sandbox)

export JAVA_HOME=/home/user/tools/jdk-17 ANDROID_HOME=/home/user/android-sdk GRADLE_OPTS=-Xmx128m
./gradlew --no-daemon --max-workers=1 --no-parallel testDebugUnitTest -Dorg.gradle.jvmargs=\'-Xmx560m -XX:MaxMetaspaceSize=220m\'
node --check app/src/main/assets/drama_auto.js
APK: task assembleDebug SIEMPRE en comando aparte (testDebugUnitTest aborta
antes si el rojo historico ApiProbeTest corre en la misma tanda).
Ruta clases JVM para probes: app/build/intermediates/javac/debug/
compileDebugJavaWithJavac/classes ; gson 2.10.1 en cache de gradle.
Probe de extraer(): recrear /tmp/probe/Run.java (no persiste /tmp).
pip install -q pycryptodome (borrado en cada rollback; probe python de
sesion en /home/user/case).

## Suite actual (medida)

113 tests, 1 rojo historico (ApiProbeTest — sandbox sin red, tolerado), 1 skip.
DramaSesionTest 35/35 (incluye: senales adultas NO pasan, sin sesion NO se
finge stream, desenrollo fas6, sanearSuenos, payloadGlbr prefiere plantilla,
puente persiste glbrReq, galletas no rompen fuera de Android).

## Protocolo anti-rollback

0. ANTES de nada: git log -1 y sha256sum del zip ancla; si el assert de un
   parche falla, sospechar rollback #N primero.
1. Reposicion: git reset --hard <HEAD-consolidacion> y/o descomprimir el zip
   ancla a un dir y rsync app/+build+docs al repo (el zip NO trae .git ni
   tools/). bash setup-only.sh repara tools/jdk-17.
2. Commit «consolidacion (rollback N)» + RE-MEDIR: git rev-list --count
   cbffcf2..HEAD para el contador que citan los docs.
3. Rebuild del zip SIEMPRE que se entrega algo (el ancla envejece con cada
   version: hoy 45817b… es v3.2.8; al cerrar v3.2.9 hay que reemplazarla).
4. Los docs del repo se perdieron en el GC del rollback 28 (COMO/HANDOFF
   regenerados en v3.2.9) — mantenerlos versionados de ahora en adelante.

## Pendientes / ideas

- v3.2.10 (commit actual): replay() JS sin credentials:'include' (el ERROR CORS
  del logcat 15:44 era NUESTRO autodiagnostico, no el sitio); fijarUrlResuelta
  nunca cachea update.mp4/twoapistack; guardarDramaExtra rechaza entregas
  vacias (antes borraban la rejilla -> «se van todas las canales» 15:44);
  limpiarDramaExtra es la unica via de borrado; plan B abre Captador sin finish().
  Suite 115 (rojo historico ApiProbe), APK d626848f… (24 189 602 B), zip ancla
  REEMPLAZADO con v3.2.10, 21 patches.
- v3.2.11 (commit actual): LA REPARACION DEFINITIVA DEL BUCLE. En v3.2.10 la guarda
  JS `v === 329` choco con `v: 330` (bumpeo mio): cada reaparar apilaba instancias
  (logcat 16:07: 6 contadores req distintos, 98 POST API/13 s, len=32 en blanco hasta
  para el sitio). Guarda ahora = `if (window.DramaAuto) return` sin numero. ENTREGA:
  `DramaSesion.fusionar(viejo, nuevo)` — la entrega (topic visto, p.ej. 89) fusiona en
  la rejilla en vez de reemplazarla (era la causa de «se van todas las canales»); url
  https curada jamas se degrada. Plan B: sin salto automatico al Captador (aviso y
  stay). Version JS unificada v3.2.11 (antes el state JSON deia v3.2.9 — cosmético).
  Suite 118 (3 nuevos), APK 3fab0add6df96252 (BUILD_TAG ya dice v3.2.11: el log
  «ACTIVIDAD v3.2.11 creada» + «asset leido 34059 B» son la firma de instalacion),
  ancla REEMPLAZADA fb7558149a7c7705, 22 patches. Rollback #31 absorvido con el
  mismo protocolo (git log -1 + sha del zip antes de tocar).
  Prueba de fuego en telefono: log «captador v3.2.11 INSTALADO», UN solo contador de
  req en el dump, y ENTREGA que no encoje la lista.

- v3.2.12 (commit actual): COSECHA en drama_auto.js — con glbrReq aprendido + filas
  dramalive://, el gancho replayea el glbr DEL SITIO desde la pagina (cap 100, 0.9/1.4/6 s,
  parada 8 suenos), escribe la url real en la fila y envia ENTREGA force -> fusionar.
  Base: glbr Java pelado sigue recibiendo update.mp4 3/3 en linea del usuario (16:33) pese
  a plantilla+cookies: NO volver a intentar "POST mejor"; la pagina es el contexto bueno.
  Anadido DramaSesion.diagnosticoGlbr (log "post glbr: base=…"), PlayerActivity lo loguea.
  Repro: node docs/cosecha_repro.mjs -> REPRO_OK. Suite 120 (DramaSesion 42/42).
  OJO rollback #31/#32 en turno; ancla = zip de este commit.

- v3.2.29 (commit actual): EL PORT DEL METODO QUE SI — glasbi->glbr(redirect)->advanced->relai HLS con swap. Evidencia
  sandbox 12/10 22:40: glt 201/265, glasbi 201 marcador .LS.V2, glbr 201 advanced, playlist 10 seg, 3.0 MB TS,
  PyAV h264 1024x540 + aac 25 frames -> /home/user/play_frame.png. El host del player ROTA por llamada
  (sup6.superfastnet.cyou -> spe6.speedfastnet.cfd); el CDN del player no exige cabeceras pero el app manda
  las del JSON advanced. El edge li./re. blaquea tras martillazo (200 en blanco = anti-flood, se levanta solo).
- v3.2.28 (sin commit): «TRATA DE RESOLVER UNA SOLA CANAL CON PYTHON» — Y LA PRUEBA-PYTHON (/home/user/py_canal/)
  pago la verdad: la cadena COMPLETA resuelta en Python puro contra la API real: glt 201 (live_topics 265), glbt 201
  (81 canales, item0=live_tv_beinsport1 con url:'1'/agent:'all_streams_redirect'), glbr 201 decodificado result:0 —
  ALAMBRE Y PLANTILLAS PERFECTOS. Descubrimiento: el edge bloquea por UA (python sin UA → 403 CF «error code: 1010»;
  UA Chrome-movil → 201). El sueno update.mp4 sali6 en LAS 10 VARIANTES medidas (token vacio/user_id, agent redirect/UA
  viejo, rc 0/1, adsShowed/adsComplete 0/1, runCount 0/1/8, canal sport y no-sport, re-pedidos inmediatos) — el gate
  es IP/datacenter, no bug nuestro (glbr_ok.json capturado del MISMO sitio también traía update.mp4). La jugada que
  el sitio SI hace y nosotros NUNCA: jugar el update.mp4 como ANUNCIO (muted) y RE-PEDIR glbr con runCount+1 y
  adsShowed/adsComplete=1. v3.2.28 = esa maquina de estados en el pedir() de la cosecha (verAnuncio(): video element
  en el propio WebView, ended/error o techo 22 s, luego segundo POST; A.adWatched marca la sesividad y sube los
  contadores de TODAS las filas restantes). Repro T15 (ANUNCIO28_OK glbr=2 priorFijo=1, wire AES real de prueba),
  PROBADO EN ROJO contra el 3.2.27 (glbr=1, bucle clasico). Suite 133 (1 rojo ambiental), APK f44a6d7a7fc0 (asset
  8ba6b63badc0 byte-a-byte). Rollbacks #54 y #55 en el turno: repuestos desde zip 8c58a03e -> commits 9ed3e27/8994446.
  Si en el telefono (IP residencial, donde el navegador del usuario SI reproduce) el server sigue dando sueno tras
  el anuncio visto, la puerta la tiene el SDK de ads (anythink/topon) y ninguna app no-paga la abre: ahi la unica
  via es el play dentro del captador visible — que ahora al menos NO se suicida a mitad de cosecha.

- v3.2.27 (commit previo): LOS HITOS PAGARON (log 12:26-12:29 v3.2.26, canal live_tv_panel_dd37..): la semilla
  llego perfecta («semilla: glbrReq=20 campos · glt=si · topics=60 · wire=ctiv» y «semilla(Java): OK 2560 B») y AUN
  ASI la cosecha no corrio: «hito(headless): cosecha(al aprender): glbrReq=si — BLOQUEADA: auto en vuelo» — el
  disparo de 9 s caia SIEMPRE adentro de auto() (que tarda ~17 s: medido 12:26:34->51 y 12:28:34->51) y ya habia
  consumido A.coseHecho=1, asi que el tick NUNCA reintento; paso lo mismo en el captador visible (12:28:48). Y a
  las 12:29:07 el bloque «cura agotada» de PlayerActivity se escribio un Intent a mano (sin el guard anti-reapertura)
  y re-inyecto el hook sobre el visible que estaba en pleno ciclo, reiniciandolo. Fix: (1) JS: un lanzamiento
  bloqueado devuelve A.coseHecho=0 (hito «lanzamiento bloqueado — devuelvo la bandera»); (2) JS: auto() al terminar
  arranca la cosecha EL INSTANTE (gate glbrReq && !coseHecho && !cosechando, hito «auto termino: arranco COSECHA»),
  sin esperar el tick de 8 s; (3) Java: «cura agotada» pasa por abrirCaptadorVisiblePara (guarda si hay visible
  vivo); (4) los 9 «captador(headless):» de la Activity (inyectado/ENTREGA/plomazo/queda-vivo/error-red/poll/
  cierre/relevo) salen con modo real — el log mentia en las lineas del visible. El server sigue terco: las 4
  resoluciones POST del player (12:26:34, 12:28:23, 12:28:51, 12:29:24) dieron update.mp4 PARA EL PANEL TAMBIEN —
  con la cosecha ya encendida, la ultima esperanza es el contexto-pagina; si ella tambien ve sueno, es server-side
  para ESTE dispositivo/hora (ojo: el unlock del telefono confirmaba fa6, no el panel). Repro sumo T14
  (DESBLOQUEO27: coseHecho vuelve a 0 tras bloqueo) y se PROBO EN ROJO contra el 3.2.26 (coseHecho=1). Rollback
  #53 (tercero en 2 turnos): repuesto desde zip ab073edd -> commit 76a438d. Suite 133 (1 rojo ambiental), APK
  e15ccd26e2a2 (asset d1dbcdd500fd byte-a-byte, v3.2.27 en classes3.dex).

- v3.2.26 (commit previo): LOG USUARIO 00:36-00:39 (v3.2.25): la plomeria anti-loop funciono (poll 15 s x6,
  «captador VIVO ya cura» x3, plomazo exacto, escape VISIBLE) pero la COSECHA no dejo NINGUN rastro y el server
  siguio dando update.mp4 a beinsport5 en las 5 resoluciones (plantilla 20 campos = alambre perfecto, server terco).
  Diagnóstico: el log de «cosecha terminada» estaba DETRAS del guard `if (!headless...) return;` (en visible nunca
  sale) y el log del hook L() es DramaWeb (invisible en el logcat pegado) -> la cosecha pudo morir en la guarda de
  sesionBootstrap: si el glt guardado no trae user_id, bootstrap devolvía NULL y la semilla entera moría (con ella
  glbrReq y la cosecha). Fix: (1) sesionBootstrap acepta semilla solo-con-glbrReq (la cosecha no necesita user_id;
  auto() ya se autocensura sin glt util) + 2 tests nuevos (DramaSesionTest 54/0); (2) HITOS incondicionales por el
  puente nuevo AndroidDrama.hito -> BLABONGO en cada decision: «semilla: glbrReq=N campos/AUSENTE», «al aprender»,
  «tick: arranco COSECHA», «cosecha(motivo): glbrReq=si/NO», «COSECHA abortada», «cosecha arranca: N filas · prior»,
  «cosecha fin: ok/hechos · suenos · vacias · curado» — y «semilla(Java): OK nB / AUSENTE» desde inyectar(); (3) el
  log de cosechaTermino sale ANTES del guard y con prefijo de modo real (captador(headless|visible)) — era el
  prefijo mentiroso del log; (4) ANTI-THRASHING: abrirCaptadorVisiblePara no re-abre si hay captador VISIBLE vivo
  (log 00:38 lo abrio 2 veces mas a 33 s de distancia con NEW_TASK cortando la cosecha ajena). El helper hito() usa
  `window.AndroidDrama` (el identificador pelado falla en node y se tragaba todo el try). El repro glbr_seed_repro
  sumo T13 (HITO_SEED26/COSECHA26/ABORT26 con fetch roto a proposito -> assert veraz «0 suenos · 1 vacias») y se
  PROBADO EN REVERSO contra el JS viejo: rc=1 (rojo). Rollback #51 otra vez a mitad de turno: repuesto desde zip
  5673020fc2d023a2 -> commit ba91f3e. Suite 133 (1 rojo ambiental ApiProbeTest), APK 9be6e44e3841 (asset
  1eb666a71be5 byte-a-byte, v3.2.26 y hayCaptadorVisibleVivo en classes3.dex).

- v3.2.25 (commit previo): EL USUARIO TRAE EL DIAGNOSTICO Y TENIA RAZON (log 23:56: ENTREGA OK 87 canales pero
  CERO lineas COSECHA en 5 min -> plomazo -> bucle de suenos). Verificado en codigo: en drama_auto.js, el
  consumidor de la semilla `if (S.plant[sk] && S.plant[sk].user_id)` y el aprendizaje por trafico
  `if (r.json.user_id) { A.plant.glbrReq = ...` exigian user_id a LA PLANTILLA GLBR, que nunca lo trae (sus
  campos: id,url,agent,token,rc,adsShowed,runCount,timeZone…) -> A.plant.glbrReq quedaba null -> el tick
  `if (A.hechoAuto && A.plant.glbrReq && !A.coseHecho)` nunca armaba la cosecha, Y la segunda guarda tambien
  se comia el disparo inmediato `cosecha('al aprender')` (por eso ni la sesion manual la arrancaba). Java
  estaba limpio: sesionBootstrap SÍ sembraba plant.glbrReq (v3.2.13) y glbrBase/tienePlantillaGlbr no piden
  user_id — el player veía la plantilla («base=glbrReq del sitio (20 campos)») mientras el hook la tiraba.
  La trampa del test: el fixture SEED_OK de docs/cosecha_repro.mjs inventaba un glbrReq CON user_id ->
  verde sobre bug vivo; LECCION: los fixtures se copian de payload real capturado, nunca «a mano». Fix:
  sk==='glbrReq' copia sin guarda (demas plantillas mantienen user_id; verifico que la basura sigue
  filtrada); fixture veraz; nuevo docs/glbr_seed_repro.mjs (SEED25_OK/JUNK25_OK/TRAFFIC25_OK) + test T12 —
  y PROBA REVERSO corrida: con el JS viejo el repro da rc=1 (rojo), con el nuevo rc=0. Suite 131
  (DramaSesionTest 52/0, unico rojo ApiProbeTest ambiental), python 12/12, APK 1ecb14eb20a715fc (asset
  e56c0e2783b8 byte-a-byte, v3.2.25 en DEX). Rollback #50 a mitad de turno: restaurado desde zip
  0e75db83 -> commit 108757a. Con la cosecha armada de verdad, la pregunta abierta sigue siendo si el
  server suelta el stream real de alkaas2/beinsport5 CUANDO LO PIDE LA PAGINA (contexto navegador) —
  medible ahora: buscar «COSECHA (al aprender|tick|al semilla)» y «PRIOR curado» en el proximo log.

- v3.2.24 (commit previo): «hacelo automatico la conexion a drama» + capturas del wizard de web.dramalive.cc
  (checkbox «I have read and accept usage conditions and privacy policy» + acordeon FG Code con
  fgcode.org/232425). En vez de tocar el canvas de Flutter, MEDICION en main.dart.js (copia local, 4.36 MB):
  shared_preferences_web guarda el setup como «flutter.terms_continue» (bool) y «flutter.source_config»
  (SourceModel bD(): {type:'fgCode',fgCode,m3uUrl,xtream*4,finalUrlLink} jsonEncodeado DOS veces);
  location.hash del sitio es solo el router (a1B), NO lleva sesion — descartado el enlace-hash. Fix:
  DramaSesion.seedSetupJs(codigo) (logica pura) siembra ambas claves en la inyeccion de DramaAutoActivity
  (antes del guion), con guardas: no pisa una fuente m3u/xtream ya configurada en ESE WebView, y el unico
  location.reload() lleva guarda sessionStorage blab_sr. FuenteCanales.codigoFg/guardarSoloCodigo; el hook
  aprende el codigo del trafico fgcode o del source_config del sitio y lo guarda (AndroidDrama.aprenderCodigo):
  configuracion manual una vez -> automatico para siempre. Leccion GORDA: el seed se arma en RUNTIME (concat
  Java) — un } de mas en un literal habria tirado SyntaxError en evaluateJavascript y MUERTO el hook
  completo; eso no lo ve ni node --check del asset ni los asserts de substring -> test python T10 +
  tests_python/seed_driver.js ensamblan el JS desde el .java y lo ejecutan con node contra un mock del
  storage (siembra, idempotencia, no-pisar). Suite 131 (DramaSesionTest 52/0: +3 seed), python 10/10,
  APK 95119ac0aba34bfa (asset 43 049 B byte-a-byte). Rollback #49 otra vez a mitad de turno: restaurado
  desde zip f782051b (d83a923/bd72d44); el «commit por paso» salvo el trabajo dos turnos seguidos.

- v3.2.23 (commit previo): LOG DEL USUARIO (23:24-23:31, v3.2.22): los dos fixes de v3.2.22 se ven trabajando
  («entrega sin cura del prior — me quedo vivo», «miro la fila de nuevo en 15 s (sin repetir el glbr)» x6,
  inyecciones 2/episodio vs 45). Quedaba un defecto: a 23:31:33, con el plomazo corriendo desde 23:29:44, la
  re-resolucion VOLVIO a LANZAR; sin launchMode, NEW_TASK con la affinity :cura RECICLO la tarea sin crear
  instancia (no hay segunda «ACTIVIDAD creada» en el log), el intent nuevo llego a la instancia vieja (no habia
  onNewIntent) y su plomada la remato 10 s despues (23:31:44 «plomazo con entrega hecha y prior sin curar»).
  Ademas la cosecha termino sin curar a live_tv_alkaas2 (sueno terco con la plantilla de 20 campos, medido otra
  vez en linea real) y el captador espero el plomazo mirando 90 s. Fix: (1) DramaAutoActivity.onNewIntent
  actualiza prior y re-ARMA plomada 120 s; (2) PlayerActivity NO relanza si instanciaViva().priorViva()==pid —
  solo relevo 15 s; (3) JS al fin de cosecha llama AndroidDrama.cosechaTermino(curadas, A.priorFijo?1:0): sin
  cura del prior => PlayerActivity.notificarCuraFracasada marca quemadosHeadless[pid] y si hay player esperando
  ese canal abre el captador VISIBLE YA (abrirCaptadorVisiblePara, extraido del escape v3.2.21); con curadas>0,
  gracia de 9 s (cierreTrasCosecha) para que salga el sink y no se pierdan filas curadas; (4) pid quemado: un
  play futuro salta directo al VISIBLE sin gastar otra ronda headless. Suite 128 (DramaSesionTest 49/0, unico
  rojo ApiProbeTest ambiental), python 9/9 tras rezip, APK e24afc1a6b506d64, asset 42 093 B byte-a-byte.
  Lecciones del turno: el rollback #48 borraba tools/jdk-17/bin (setup-only.sh lo repara en 16 s) y mi
  double-replace sin assert habia dejado el HANDOFF sin entrada v3.2.22 — toda edicion de docs se verifica con
  grep DESPUES, no solo con el print('ok').

- v3.2.22 (commit previo): LOG DEL USUARIO (13:01, ya en dza.fut.paipo v3.2.21): notificarEntrega funciona
  («entrega del captador: despierto al player ya» x2). Dos huecos: el finish() INMEDIATO del headless tras
  «ENTREGA OK 84 canales» mataba la COSECHA a medias (el prior quedaba por curar y nadie lo mandaba), y el
  despertar tras entrega PARCIAL re-resolvia glbr -> mismo sueno -> 20 s dormido. Fix: campo priorCurado (el
  headless solo cierra si la fila del prior trae url http, o al plomazo — plomada ahora SI cierra «con entrega
  hecha y prior sin curar»); reintentaTrasCura con cura fresca (<100 s) NO repite glbr: re-mira la fila cada
  15 s; inyecciones throttle 4 s + cadena reaparar unica tope 10. Suite 128, DramaSesionTest 49/0, unico rojo
  ApiProbeTest (ambiental). OJO: esta entrada debio escribirse en el turno v3.2.22 y un double-replace sin
  assert se la salto — reintegrada en el turno v3.2.23.

- v3.2.21 (commit previo-previo): LOG DEL USUARIO (12:21-12:23, app vieja dza.folbol.BLABONGO v3.2.19): el captador headless
  SI cura y entrega («ENTREGA OK 3 canales (fusion)» a los 17 s), pero el player repite «aun no llega a la fila».
  CAIZ (mia, encontrada gracias al log): al fijar la url real, la fila perda el id_live (unica llave de
  fusionar()/filaDeRejilla(), que lo lean de la url dramalive://) -> la fila curada queda INVISIBLE por id y el
  player jamas la ve. Fix en 4 puntos: (1) JS cosechaRun marca filas[i].di=p.id_live al curar; (2) desdePuente
  lo pasa a CanalDrama.dramaId y aM3u escribe tvg-id="X" en el EXTINF; (3) idFila(extinf,url) es la nueva llave
  unificada — fusionar y filaDeRejilla la usan, y fusion hereda el id viejo si la fila nueva no lo trae; (4)
  DramaAutoActivity.entregar llama PlayerActivity.notificarEntrega() (WeakReference vivoCura) para despertar al
  player EN EL ACTO (antes: poll ciego de 70 s). Ademas: a los 3 ciclos «aun no llega» el player abre el
  captador VISIBLE con EXTRA_PRIOR (escape del sueno eterno; el site si reproduce ahi), y el log dice plomazo
  120 s (era 90 hardcoded). Suite 128 (DramaSesionTest 49: +curacionConTvgIdLlegaALaFila +fusionHeredaElId),
  python T09 +7 asserts. Rollbacks #44/#45 absorbidos (ojo: tras reset --hard al base, PURGAR dza/folbol antes
  de sobreponer el zip, o el commit sale contaminado — paso olvidado una vez y lo cacé por errores de R).

- v3.2.20: RENOMBRE DE PAQUETE pedido por el usuario: dza.folbol.BLABONGO -> dza.fut.paipo.
  namespace + applicationId (build.gradle), git mv de los 3 source sets (main/test/androidTest), manifest
  (NotificationReceiver y taskAffinity dza.fut.paipo.cura), output-metadata, docs y tests_python (rutas).
  git grep 'dza\.folbol|dza/folbol' => 0 residuos. BUILD_TAG/asset subidos a v3.2.20 (v:340) para identificar
  el APK en logcat. app_name sigue «BLABONGO» (no lo pidieron). CONSECUENCIA MEDIDA: otra applicationId = OTRA
  app — al instalarla arranca con prefs vacias (codigo FG y rejilla se vuelven a crear; la app vieja queda
  intacta hasta que la desinstalen). Suite 126 + harness python 9/9.

- v3.2.19: Â«tampoco â regresa a la version donde se podia conectar y extraerÂ». La raiz NO era el
  tiempo: la entrada por codigo depende del server fgcode (500 medido); cuando cae, v3.2.18 solo mostraba la
  banda roja. El camino que SI dejaba conectar/extraer era el captador VISIBLE (log 16:07: ahi el sitio contesta
  real y la entrega fusiona, sin depender de fgcode). Fix: captadorVisibleSiCaeLaFuente en onError (manual y
  auto): mensaje tipo server-caido (500/502/503/timeout/base de datos/no se pudo conectar/intentos) + rejilla
  vacia => abre el DramaAutoActivity VISIBLE solo (un disparo por visita). 2 min y backoff de v3.2.18 intactos.

- v3.2.18: «se regresa RAPIDO sin dejar conectar» — no era el plomazo: era onReceivedError del WebView
  haciendo finish() INMEDIATO en headless al primer error de red (v3.2.17 y antes). Fix: error red = re-carga
  SITIO con backoff {4,8,15,25} s (fallosRed<4) y el UNICO que cierra headless es la plomada; plazos a 2 min
  como pidio el usuario: PLAZO_HEADLESS_MS 120_000, TIEMPO_MAX_CADENA_MS 120_000, WEBVIEW_MAXIMO_MS 120_000,
  fuente read 60->75 s x3 intentos (>4 min). Harness 9/9 (T03/T09 actualizados a 120_000; T03 ahora exige
  ver 120_000L). Rollbacks #39/#40 absorbidos: restaurar SIEMPRE desde el zip ancla, NUNCA desde /tmp.

- v3.2.17: QUEJA «tiempo muy corto» fgcode.org/232425. Medidas sandbox: org/<num> = pagina de
  error (meta-refresh, OkHttp no la sigue, no trae lista); store api.php = HTTP 500/0.47s (MySQL caido, 5xx
  transitorios conocidos). Cuellos: fuente 12/25s SIN reintentos; StreamResolver 3+3s/salto y cadena 25s;
  WebView 45s. Fix: cliente 30/60s + cargarIntento 3x(5s/15s, tb 5xx); AutoCanales.descargar1 3x backoff;
  cadena 12/15s + TIEMPO_MAX_CADENA_MS 90_000 + WEBVIEW_MAXIMO_MS 75_000; normalizarAtajoFg (puro): host
  con "fg" + path = solo-numero -> https://host/link/api.php/get?code=N, aplicado en resolver() por url Y
  por codigo. Suite 126 (+normalizaAtajoFg x4 casos), python 9/9 (T09), APK 8758ccde… asset byte-identico.
  Rollback #38 absorbido (restaurar SIEMPRE al workspace, nunca a /tmp — se evapora entre llamadas). Si el
  500 persiste en la red del usuario, es el proveedor caido: el error ahora lo dice con los 3 intentos.

- v3.2.16 + HARNESS PYTHON: agregado tests_python/test_curacion_v3216.py (stdlib puro, 8/8 OK):
  contrato del asset, REPRO node real (7 marcadores), aislamiento de tarea (affinity/NEW_TASK/plomazo
  90s), parseo de resultados de la suite JVM, AES pura validada contra FIPS C.1 y pycryptodome,
  INTEROP desencripta el encPara real del asset en Python, golden Salted__ (comparacion SEMANTICA:
  texto bonito vs JSON compacto cifrado), sincronia zip-repo byte a byte. docs/crypto_interop.mjs
  exporta 3 {obj,wire}; docs/cosecha_repro.mjs ahora usa ruta RELATIVA (portable a cualquier clon).
  En el camino se corrigio 1 bug de MI test (SR al reves en la variante plana y un assert dec(x)==x
  en lugar de dec(enc(x))==x) - el codigo de la app NO toco: v3.2.16 intacta. Suite 125 (124+ApiProbe).
  Rollback #37 a mitad de turno: restaurado desde ancla 07f7eb0…; OJO cp desde /tmp es volatil, usar
  dir del workspace para restaurar.

- v3.2.16 (commit actual): CAUSA RAIZ del «tampoco» 19:06 (MEDIDA EN CODIGO, no deducida):
  cerrarReproductor() hace finishAndRemoveTask() -> mataba al captador headless del MISMO task a
  los ~17 s (log: LANZADA y luego silencio, cero ENTREGA). Y el cooldown de instancia renacia
  con cada tap (alkaas2 lanzado 2x/27 s). Fix 4 piezas: (1) cura en task propio: manifest
  taskAffinity ":cura" + excludeFromRecents, intent NEW_TASK; (2) cooldown estatico por pid
  DramaSesion.curaFresca (puro, testeado); (3) tap con cura en vuelo -> toma relevo + poll 20 s
  (player Y captador via instanciaViva().pedirRelevo); (4) hitos del captador espejados al tag
  BLABONGO_DEBUG. Suite 125 (47/47), repro 7/7, aapt2 confirma affinity en el APK. Rollback #36
  a mitad de turno - arbol repuesto desde ancla dbfe140…; re-medir rev-list (21). PROXIMA
  PRUEBA ESPERADA: LANZADA -> ⏹ cerrar -> «ENTREGA OK» a los ~20-40 s SIN player abierto ->
  tap al mismo canal suena directo. Si ENTREGA no cae con el captador vivo 90 s, el PLOMAZO
  SIN ENTREGA (ahora visible) dice que la cosecha en si fallo -> ahi mira el volcado COSECHA.

- v3.2.15 (commit actual): log usuario 18:25: player aviso «curacion headless» PERO DramaAutoAct
  no emito ni una linea y el reintento a los 2 min siguio soñado. Causa estructural: ese log salia
  ANTES del gate (curacionLanzada boolean + tienePlantillaGlbr) y las ramas que no lanzaban eran
  mudas. Fix: gate POR CANAL con cooldown 100 s (canalCurado/curacionTs reemplazan el boolean),
  log de cada rama por BLABONGO_DEBUG («cura headless: pid=… · LANZANDO|EN VUELO|NO se puede»,
  «cura LANZADA para …»), plomazo 45→90 s con log PLOMAZO SIN ENTREGA, inyectado muestra
  prior=<pid>. Y lo que quita al usuario del medio: AUTO-REINTENTO a los 70 s — filaDeRejilla
  (slot 3 = url de la fila ahora) decide: https -> reproduce sin tocar nada + fijarUrlResuelta;
  dramaalive -> re-resuelve el glbr una vez en silencio. Suite 124 (46/46), repro 6/6, 6 hits
  de las cuerdas nuevas en classes3.dex. Rollback #34 y #35 a mitad de turnos - arboles repuestos
  desde anclas (60c8a22…, c297eba…); re-medir rev-list tras cada consolidacion (ahora 21).
  OJ O: si el 18:25 era APK v3.2.13, la ausencia de cura es EL HOYO QUE CERRA v3.2.14 (fila
  sintetica) - preguntar version por la barra/banner del captador en proximo log.

- v3.2.14 (commit actual): PRIMER HEADLESS MEDIDO EN TELEFONO (log 17:47): todo el loop funciono
  (inyeccion 41700 B, hooks v3.2.13, fijos:1, ENTREGA OK 3 canales a los 13 s, cierre limpio,
  erroresJs:0) PERO filas:3 probo el hueco: prior fuera de las filas del topic no se cura nunca
  (ordenar no es incluir). Fix: asegurarPrior() sintetiza la fila del canal jugado (con nombre/
  logo/group leidos de la rejilla via DramaSesion.filaDeRejilla - desdePuente descarta filas sin
  n, por eso la identidad viaja en EXTRA_PRIOR_NOMBRE/LOGO/GRUPO). Cosecha arranca aunque
  filas=0. Guard jsSeguro() contra comillas en nombres. Suite 124 (46/46), repro 6/6 OK.
  Nota entorno: rollback #34 a mitad de turno - arbol repuesto desde ancla 60c8a22…, rev-list
  re-medido. NO reintentar el POST glbr desde Java (muerto 2/2); el contexto pagina es el unico.

- v3.2.13 (commit actual): curacion HEADLESS automatica. PlayerActivity, ante sueño con
  plantilla glbr (gate puro DramaSesion.tienePlantillaGlbr), lanza DramaAutoActivity
  EXTRA_HEADLESS+EXTRA_PRIOR (invisibles 2x2, 45 s). sesionBootstrap ahora SIEMBRA glbrReq
  en la semilla JS (antes solo glt/glbt) -> cosecha posible sin interactuar. JS: ordenPrior
  puro + __DRAMA_PRIOR + onFix en cosechaRun -> ENTREGA force apenas se cura el prioritario.
  Toast «Reintenta el play dentro de ~1 minuto» (un solo lanzamiento por apertura, flag
  curacionLanzada). NO reimintar el POST glbr desde Java (muerto 2/2 en linea real 16:33 y
  17:14). Suite 123 (DramaSesion 45/45); repro docs/cosecha_repro.mjs suma SEED_OK/ORDEN_OK/
  ONFIX_OK. Unica rojo: ApiProbeTest (historico de red del sandbox).
  Prueba de fuego en telefono: tocar un canal soñado -> sin abrir Captador, el logcat debe
  mostrar COSECHA + PRIOR curado + ENTREGA (force); reintento de play reproduce https real.

- Verificar en telefono: barra «v3.2.10 INSTALADO»; pedir logcat con filtro
  DramaAuto|DramaSesion para ver si la ENTREGA del captador corre (el log del
  usuario 15:44 no tenia ninguna linea nuestra de DramaAuto).

Verificar en telefono: barra «v3.2.9 INSTALADO», log «plantilla glbr del
  sitio aprendida» y «glbr resuelto -> https://fas6…».
- Si el usuario ve «sesion aprendida (rc=…)» en el log del player pero sigue
  el sueno, siguiente hipotesis: headers de app nativa (User-Agent/accept del
  APK de DramaLive) — replicar UA Android del sitio movil.
- Limpieza: los clips de relleno quedan en la lista si el backfill no los
  reviso; considerar purgar al GUARDAR, no solo al leer.
