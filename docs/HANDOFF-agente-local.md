# HANDOFF — extraer canales de DramaLive y usarlos en PelisLatino (para el agente local)

> Documento de continuidad. Todo lo de abajo está **medido** (2026-10-08) contra los
> servicios reales; donde no hay medición, está marcado como pendiente. Responder al
> usuario SIEMPRE en español. Idioma del proyecto: español (código y comentarios incluidos).

## 0. Objetivo (en words del usuario)

**"Sacar los canales desde DramaLive y que funcionen en la app."** No scraping genérico:
la extracción se hace **desde la sesión viva del navegador del usuario** (el API del
portal no responde a clientes anónimos — ver §4). El producto final es un `.m3u` que se
pega en la app (`En vivo → Fuente → Pegar lista`) y reproduce.

## 1. Dónde está todo (estado del repo)

- Repo: `github.com/barhomus10/PelisLatino-Fixed` — trabajar en rama **`mejora-estilo-cine`** sobre `main`=`cbffcf2`; contar siempre con
  `git rev-list --count cbffcf2..HEAD` (el sandbox de origen revierte commits a veces — ver alerta abajo).
- Entregables: **no se hace push**; se entrega rama + `git format-patch --binary cbffcf2 -o patches/` + **zip plano** para Android Studio:
  `git archive --format=zip -o PelisLatino-estilo-cine.zip HEAD` (los ficheros del proyecto van en la RAÍZ del zip, sin carpeta envolvente).
- ⚠️ El sandbox del que viene este estado **revierte el repo a menudo** (5 veces). Antes de tocar nada: `git log --oneline -1`; si `app/src/main/java/dza/fut/paipo/EnlaceDrama.java` falta, restaurar desde el último zip entregado (`unzip` + `cp -a` + `git add -A && git commit -m "re(rollback)..."`).
- Ficheros clave:

| Ruta | Qué es |
|---|---|
| `app/src/main/java/dza/fut/paipo/EnlaceDrama.java` | Abre `player.html#<hash>` (cifrado **y** modo plain) con javax.crypto |
| `.../Encabezados.java` | Parser del campo "Encabezados al abrir" (`Nombre: valor`, JSON, `@host` scope, descarta restringidas) |
| `.../FuenteCanales.java` | Fuente de canales: FG code / URL / lista pegada / Xtream / **`drama_clave`** (webPlayerSecret) / **`encabezados`** |
| `.../ListaPegada.java` | Importa texto pegado: M3U, JSON o URLs (desenrolla enlaces Drama con la clave) |
| `.../PlayerActivity.java` | Reproductor; desenrolla Drama-link al abrir; mezcla cabeceras en `resolverStreamDirecto` |
| `docs/vectores-cryptojs.json` | 5 vectores oracle (node+crypto-js 4.2.0) |
| `docs/vectores-clave-drama.json` | Vector con clave NO pública (prueba webPlayerSecret) |
| `tools/dramalink.py` | CLI: `descifrar / cifrar / verificar / probar` (Python puro) |
| `tools/aes_bloque.py` | Motor AES propio (CBC), verificado contra pycryptodome y contra los 5 vectores |
| `tools/drama-extraer.js` | **Gancho de consola/CDP para web.dramalive.cc** (ver §5) |
| `tools/drama-extraer.py` | Convertidor offline `canales-drama.json → canales.m3u` |
| `tools/drama-extraer-harness.mjs` | Test node end-to-end del extraedor (`node tools/drama-extraer-harness.mjs` → `TODO-OK`) |
| `tools/drama-e2e.py` | Test E2E del formato completo contra un mini-panel local (`E2E-COMPLETO`, rc=0) |
| `tools/drama-live.py` | **CLI unica para probar tu en Python** (`verificar`, `demo` (mini-panel local), `descifrar`, `probar`, `extraer --sesion`, `m3u`) — espejo 1:1 de la app |
| `tools/demo-play-en-python.py` | `demo` (403-vs-200 por header) y `mirar --url …` (baja HLS y `auditar_ts`) |
| `COMO-ABRIR.txt` | Documentación viva del proyecto (mantener al día, incluido el contador de commits) |

## 2. La cripto, resuelta y verificada (no re-abrir este frente)

**Enlaces del player** (`li.highperformanceconnect.shop/player/player.html?fit=contain#…`):

- `player.js` (61.755 B) lee `location.href.split('#')[1]`; `n()` = base64url→base64
  (`-`→`+`, `_`→`/`, re-rellenar `=`). Si el fragmento **empieza por `http(s)://` es la URL
  en claro (modo PLAIN**, grupo peruano `190.93.224.42`); si no, `o()` =
  `CryptoJS.AES.decrypt(txt, clave).toString(Utf8)`.
- Blob = `Salted__`(8 B) + `salt`(8 B) + AES‑256‑CBC(PKCS7, crypto-js Pkcs7 igual).
  Derivación = **EVP_BytesToKey con MD5, 1 iteración**: `D0=MD5(pass‖salt)`,
  `D_i=MD5(D_{i-1}‖pass‖salt)`; `key=material[0:32]`, `iv=[32:48]`. (Implementado idéntico
  en `tools/dramalink.py:evp_bytes_tokey` y `EnlaceDrama.evpBytesToKey`; los 5 vectores del
  oracle + `docs/vectores-clave-drama.json` en verde en Python, Java y node.)
- **La "clave" del bundle**: `(meta[name="app-id"].content …) + "_YOUR_SECRET"`. Ese host
  sirve la meta con `content="REPLACE_WITH"` sin `id` → clave pública real:
  **`REPLACE_WITH_YOUR_SECRET`**. Candidatos que prueba la app (en orden): la clave del
  config (campo `drama_clave` = **`webPlayerSecret`**), `REPLACE_WITH_YOUR_SECRET`,
  `_YOUR_SECRET`, `REPLACE_WITH`, `""`.
- El JSON interior: `{"url":"https://{server}/{path}/index.html","mediatype":"hls","swap":{"k9x_":""},(a veces)"title":…}`.
  **`swap`** = mapa de reemplazo de cadenas sobre el TEXTO del playlist antes de pedir cada
  segmento (el CDN 404-ea el nombre crudo con prefijo; solo sirve el nombre post-swap).
  `EnlaceDrama` hoy extrae `url/title/mediatype`; el **swap NO está implementado en la app**
  — es la mejora nº1 (§7).
- Los enlaces **caducan**: `pages/<token>/` atado a sesión/IP con TTL corto (los números de
  segmento son segundos unix; un enlace extraído el 7‑oct daba playlist vivo y el 8‑oct ya
  404 con cualquier UA/Referer). **Flujo correcto = extraer y abrir de inmediato.**

**Catálogo del portal** (`web.dramalive.cc/main.dart.js?v=13.0.3.241`, 4.364.382 B):

- API literal único: `https://main.fastconnections.online/api/live/livedrama/v13.0.0/` + `a|glt|gs` (POST).
- Alambre de petición Y respuesta: `b64(AES-128-CBC(PKCS7)) ":" b64(iv)`, key literal del
  bundle **`43fc9b1a3f854049`** (aparece 4 veces en main.dart.js; el cliente la usa con IV
  aleatorio de 16 B). Decodificado en `A.ad([...])`… campo `live_topics` en la respuesta de `glt`.

## 3. Servidores de stream vistos

| Servidor | Modo |
|---|---|
| `sup6.superdatanet.cyou/pages/<tok>/index.html` | ejemplo cifrado del usuario (caducado hoy) |
| `190.93.224.42` (Perú) | fragmento PLAIN |
| `rap5.rapiddatahost.shop` ("DL", árabes) | cifrado con `webPlayerSecret` propio |
| `sp.highperformanceconnect.shop/sport/` | sport_server_url del config |

No cazar espejos/piratas adicionales: se trabaja SOLO con lo que la sesión del usuario lista.

## 4. Puertas medidas (no reintentar estos caminos)

- POST anónimo a `/a`, `/glt`, `/gs` (incluso con el fgcode del usuario, cifrado con la key
  correcta, y 4 variantes de Content-Type/payload) → **siempre los mismos 32 B de relleno**
  `"\t\t\r\n"+espacios`, `x-powered-by: PHP/7.4.25` (catch-all). Falta el payload estándar del
  cliente logueado: `user_id, device_id, device_api, version_name, language, timezone,
  device_type, os_platform, os, KEY_ACTIVATED_TYPE, store, isStoreVersion, isPremium,
  isCoupon_active, hideAds, appCount, mainServer` (+`type` en `glt`).
- `https://mikkm.xyz/android/androidApi.php` → `ACCESS DENIED! AndroidAPI v3.1`.
  **VETADO**: no usar las credenciales embebidas del bundle (el fallback `shai`). Nunca.
- `fgcode.org/232425` → 301 → `fgcode.store/link/api.php/get?code=232425` → 500 (MySQL caído, verificado).
- El `player.html` de Drama **no manda ninguna cabecera** (0 ocurrencias de `headers` en sus 61.755 B) — no hay truco de navegador que copiar; lo que sí hace falta a veces son Referer/Cookie del CDN (eso es el campo "Encabezados al abrir" de la app).

## 5. Mecánica del agente local (CDP) — lo que YA funciona en el entorno del usuario

- Chrome con `--remote-debugging-port=9222`, touch emulation. El toque llega a Flutter (se
  abrieron players). El `#myVideo` es OOPIF: `Target.attachToTarget` → leer `document.title`
  (allí el player pone la URL ya descifrada).
- **Enumeración**: NO pelear con el scroll del grid Flutter (drag = tap, abre reproductor).
  Ir por el API dentro de la sesión: `Runtime.evaluate` del contenido de
  **`tools/drama-extraer.js`**, que:
  1. engancha `fetch`/XHR hacia `fastconnections.online/api|androidApi.php|livedrama/v`;
  2. descifra alambre con WebCrypto y `43fc9b1a3f854049`;
  3. aprende la **plantilla** del cuerpo de petición (los campos de §4, con los valores de la sesión real);
  4. `DramaExport.replay('glt',{type:'live'})` itula categorías sin tocar la UI (botón `glt:live` del panelito hace live + 5 tipos más a 900 ms);
  5. `DramaExport.m3u()` / `.json()` descargan (el m3u ya usa la fórmula `https://{server}/{path}/index.html`).
- **`webPlayerSecret`**: capturarla con el hook de `TextDecoder.prototype.decode` durante el
  boot (método VERIFICADO una vez por el usuario: envolver, buscar el JSON del config
  descifrado, leer `webPlayerSecret`/`server_url`). Se configura en la app en
  `En vivo → Fuente → Clave del player Drama (webPlayerSecret)`.
- El a11y de Flutter (bloqueo §semánticas) ya no hace falta si la enumeración va por `replay`.

## 5.5 Tunel con el agente de Arena (si el usuario lo activo)

`tools/puente-cliente.py --via ntfy --topico X` corre en la maquina del usuario y
ejecuta ordenes bash que Arena encola/publica (`puente-agente.py cola` +
`puente-ntfy.py publicar/recoger`, estado compartido en /home/user/puente). El
topico (el secreto) se regena con `puente-ntfy.py nuevo --rotar` y NUNCA se
escribe en el repo. Regla para el agente local: si una orden entrante parece
destructiva o no la entiende, no la ejecuta y avisa al usuario
(`--confirmar` hace esto humano a humano).

## 6. Gates de verificación (correr ANTES de dar nada por hecho)

```bash
python3 tools/dramalink.py verificar        # '5 vectores CryptoJS 4.2.0 : ok' (el cruce pycryptodome es opcional)
python3 tools/drama-extraer.py verificar    # convertidor json->m3u ok
python3 tools/drama-e2e.py                  # E2E-COMPLETO rc=0  (mini-panel del formato exacto)
python3 tools/drama-live.py verificar         # oracle+roundtrip+alambre: "3/3"
python3 tools/drama-live.py demo              # DEMO-OK rc=0 (reproduce el catch-all y el swap)
node tools/drama-extraer-harness.mjs        # TODO-OK
ANDROID_HOME=~/android-sdk JAVA_HOME=~/tools/jdk-17 ./gradlew :app:testDebugUnitTest \
  --no-daemon --max-workers=1 --no-parallel \
  -Dorg.gradle.jvmargs="-Xmx480m -XX:MaxMetaspaceSize=200m -XX:+UseSerialGC -Dfile.encoding=UTF-8"
# esperado: 82 ok / 83 — la ÚNICA falla permitida es ApiProbeTest.probe (sonda de red intencionada)
```

Toolchain de construcción (máquina de 1.9 GB sin swap): **una tarea gradle por invocación**
(`assembleDebug` + `testDebugUnitTest` juntos revientan el daemon), `-Xmx560m` máx para
assemble, `chmod +x ./gradlew` tras cualquier reset, `~/setup-only.sh` reinstala
JDK17+SDK36 si `JAVA_HOME is set to an invalid directory`.

## 6.5 Extracción DramaLive AUTOMÁTICA in-app (v36)

Consola del navegador: ya no. `DramaAutoActivity` abre web.dramalive.cc en un WebView con
DomStorage (el login con el código del usuario vive ahí, se hace UNA vez), inyecta
`assets/drama_auto.js` (el captador de tools/drama-extraer.js hecho auto-ejecutable):
aprende la plantilla glt descifrando el tráfico del propio sitio (AES-128-CBC
"43fc9b1a3f854049"), re-enumerá los 6 tipos y entrega `{plantillas:{glt},canales:[{n,u,g,l}]}`
por el bridge `AndroidDrama` (solo `entregar(json)`/`estado(texto)`, anotados).
`DramaSesion.desdePuente` (puro, 2 tests JVM nuevos) lo vuelve m3u; se guarda con
FuenteCanales (lista_drama_m3u + sesion_drama_json + drama_refresco_ts + drama_ultimo_estado).
Refresco silencioso: `CanalesEnVivoFragment.onResume` lanza el mismo WebView en modo
headless cada >6 h si hay sesión guardada; sin respuesta en 45 s → Toast y la lista vieja
se conserva. El POST `glt` anónimo (incluido con uuids/`user_id`=código FG, medido 2026-10-09
desde python y con cookies del sitio) devuelve SIEMPRE el catch-all de 32 B: la sesión válida
solo existe dentro del navegador/WebView del usuario — por eso este diseño, no otro.

## 6.6 Debug de campo del captador (v3.1)

El usuario reporto "tampoco nada" y su logcat solo traia SurfaceFlinger (la pantalla viva, Flutter pintando ~3fps) y CERO lineas DramaAuto -> imposible distinguir APK viejo de hook muerto. v3.1: DramaAutoAct loguea "ACTIVIDAD v3.1 creada"/"asset leido"/"inyectado ->"/"pagina cargada", WebChromeClient espeja toda la consola del sitio (tag DramaWeb: ahi sale un CSP o un fail de red de Dart), DramaAuto loguea volcar() cada 8 s y estado() con throttle 30 s. Pedir SIEMPRE: `adb logcat -d -s DramaAutoAct:V DramaAuto:V DramaWeb:V` o el boton "Copiar debug". Sospecha raiz del 0 canales historico: el wrapper de XMLHttpRequest no copiaba los estaticos (DONE=4) y dart2js compara contra XMLHttpRequest.DONE.

## 6.7 Motor de construccion: Gradle 9.1.0 (post-JBR25)

El Android Studio nuevo trae JDK embebido 25 y Gradle 8.13 solo llega a Java 23 -> "Incompatible
Gradle JVM version". Arreglado desde el repo: distributionUrl del wrapper a gradle-9.1.0-bin
(la version que añadio soporte Java 25; Gradle 9.x corre con Java 17-26). AGP se queda 8.13
(min Gradle 8.13, y Gradle 9.0 pide AGP>=8.4 -> compatible). Verificado AQUI con JDK17: suite
83/1-intencional/1-skip identica que en 8.13 y assembleDebug BUILD SUCCESSFUL con APK byte-id
en tamano. Queda warn "Deprecated Gradle features ... incompatible with Gradle 10" (AGP 8.13;
inofensivo en 9.1.0). Nota para el proximo salto: subir a Gradle 10 exigira AGP 9.

## 6.7 El wire REAL de la sesion (medido por volcar v3.1 del usuario)

El dump probo: el sitio pide canales a `https://li.highperformanceconnect.shop/api/live/livedrama/v13.0.0/glt` -> HTTP **201** con 43 KB cifrados. decOk=1 (solo el /cdn-cgi/rum plano). Las respuestas de 4 hosts comparten primer bloque b64 -> AES-CBC con IV FIJO y primer bloque de claro comun (`{"result"...`); el gs-adentro es b64 de string JSON con binario dentro. v3.2: drama_auto.js prueba {plano, b64json, iv0-todo-cadena, iv-al-frente, ct:iv, +gunzip, +unquote}, aprende el fmt que funciona (wireReq/wireResp), el replay PRUEBA los formatos hasta que la respuesta descifre, y si nada: manda MUESTRA CRUDA COMPLETA (3000 chars req+resp) en el volcar. Entrega ahora con `url`+`wire`; plantillasDesdePuente los persiste; DramaSesion.alambre(payload,wire) soporta ctiv/ivpf/iv0 y desalambre prueba los tres + `"..."` con Gson; procesarRespuesta acepta 200/201/204 (201 medido). Tests: alambresAlternativosRoundTrip y sesionLlevaUrlRealYWireDelSitio.

## 6.8 Crackeo del wire (analisis offline de los DOS informes v3.1 del usuario) + v3.2.1

LA CLAVE: `43fc9b1a3f854049` es la cadena ASCII de 16 bytes (utf-8), NO hex-decodificarla.
Java (`DramaSesion.KEY`) ya lo hacia bien; el JS v3.2.1 la normalizo con TextEncoder.

Con AES propio bloque a bloque (CBC encadenado: p_k = D(c_k)^c_{k-1}; el bloque 1 necesita
el IV, del 2 en adelante NO) lei los prefijos de 90 chars del usuario:
- req glt → `567016861000_notloggedin.com_dramalive3","device…` ⇒ **la sesion web del
  usuario es VALIDA SIN LOGIN**: user_id sintetico `<epoch_ms>_notloggedin.com_dramalive3`.
  Mi catch-all con user_id random fallaba por no respetar ese prefijo.
- resp glt → `sage":{"en":"operation succeeded"` + `"result":0`; glbt → `"web_241","result":0`
  ⇒ el panel le contesta datos reales (43 KB glt / 97 KB glbt).
- TODOS los cuerpos tienen len%4==1 ⇒ b64 url-safe SIN padding, con prefijo/IV desalineado
  que v3.2 no probaba (exigia len%4==0 y ct multiplo de 16 con WebCrypto) ⇒ decOk 1/13.

v3.2.1 (drama_auto.js): core **AES-128 puro en JS** (tablas sbox/log-exp, ECB por bloque;
WebCrypto fuera: exige padding estricto). Verificado contra vector FIPS-197 C.1
(enc→69c4e0d86a7b0430d8cdb78070b4c55a, dec inverso) y pycryptodome (8/8 verdes, harness
node sobre el MISMO archivo shipped, export `__test`). Bugs que cazo en el proceso:
round-key indexado W[4r] en vez de W[16r]; sub escribia row-major que shift leia col-major;
dec con InvMix antes de AddRoundKey.
`decLibre()`: normaliza b64 laxo, 8 disposiciones {p∈0/2/16/32 × iv cero|frente|segundo|final},
descifra TODOS los bloques completos (cola suelta ignorada), quita PKCS7/controles de cola y
hace **rescate balanceado**: si el 1er bloque esta corrupto y se perdio el `{` exterior,
parsea desde el primer corchete balanceado (asi se lee un live_topics de 43 KB intacto).
`stricto` = claro arranca en `{` ⇒ solo los requests estrictos fijan `crackReq`; el replay
prueba candidatos [crackReq, crack, p0+frente, p16+frente, p0+cero, p16+cero] hasta que la
respuesta descifre con result:0 y FIJA la disposicion ganadora.
Plan B garantizado: **coleccion por navegacion** — cada respuesta del sitio que se lee pasa
por `ver()` y entra a la rejilla aunque el replay falle (el usuario toca Live/Sports/Kids y
la app archiva solo).
Java: `desalambre` = `alinearB64` (tolera sin-padding/url-safe) + `cbcBloques` NoPadding
bloque a bloque + `recortarClaro` (de '{' a '}' validado con GSON) sobre ivpf, p16+ivpf,
iv0 y p16+iv0; `wireDe` mapea `*frente`→ivpf y `*cero`→iv0. Tests nuevos:
`desalambreToleraB64SinPaddingYUrlSafe`, `desalambrePrefijo16AntesDelIv`,
`wireDeAceptaNombresDelCaptadorV321`.
PENDIENTE: el usuario sigue reportando «v3.1» ⇒ NO instalo el APK nuevo; pedirle que
instale el v3.2.1 y confirme la barra «captador v3.2.1 INSTALADO»; si decOk sigue 0, el
volcar trae `muestra` cruda de 3000 chars req+resp para resolver offline DEFINITIVAMENTE.

## 6.9 FORMATO CRACKEADO AL 100% (golden tests con las capturas REALES del usuario)

Volcar v3.2.1 del telefono (2026-10-10) + analisis offline con pycryptodome:
- REQUEST (todos los endpoints glt/glbt/glasbi): `b64std(ct) + ":" + b64std(iv)` — el
  formato ctiv del bundle, CON padding `==` (la muestra req 817 chars: ct=792->592 B=37
  bloques, iv=24->16 B). Claro verificado: `{"user_id":"1791616726363000_notloggedin.
  com_dramalive3","device_id":"e7282868-…uuid…","device_api":"chrome/154…","version_name":"web_241"…}`.
- RESPONSE (CORREGIDO 2026-10-10 con la respuesta glt COMPLETA de 43.385 B capturada al
  servidor real): es **ctiv igual que el request** — prefijo ws TAB TAB CR + 2 espacios +
  `b64std(ct):b64std(iv)`, ct=43.328 c -> 32.496 B (2.031 bloques), iv=16 B ALEATORIO,
  PKCS7 al final. La nota anterior («sin ":" y IV fijo») era artefacto del sample de 3 KB,
  truncado a mano por el logcat (le faltaba la cola `:iv`); el rescate por
  `iv=raw[0:16]` sigue en el codigo solo como fallback historico para ese tipo de muestra.
- v3.2.2: decLibre intenta ':'-ctiv PRIMERO; b64dec ahora limpia TODO caracter no-b64 y
  reintenta con colas cortadas (tapa basura de cola que mataba atob y explicaba el
  "sin leer" con longitudes raras); encPara('ctiv') emite `b64std(ct):b64std(iv)` byte-a-byte
  compatible (verificado contra pycryptodome); replay candidatos: [crackReq, crack, ctiv,
  p0+frente,…]; muestras subidas a 12000 chars por campo. Java: mismo rescate balanceado
  (recortarClaro + cerrarBalance) y DOS GOLDEN TESTS con los bytes reales del telefono
  (request y respuesta) — `desalambreLeeRequestRealDelTelefono`,
  `desalambreLeeRespuestaRealConHeadCorrupto` (extrae >=1 canal del sample de 3 KB).
- Si el usuario actualiza a v3.2.2: barra «captador v3.2.2 INSTALADO»; la plantilla real
  se aprende del propio request del sitio (stricto=true) y el replay replica el wire exacto.

## 6.10 v3.2.3 — LA MAQUINA COMPLETA MEDIDA CONTRA EL SERVIDOR REAL (2026-10-10)

Replay headless con la sesion capturada del telefono (SM-A075M) desde el sandbox: la
sesion NO esta atada a la IP. Resultados (python + main.dart.js del bundle v13.0.3.241):
- `POST …/glt` con la plantilla TAL CUAL (`type:"tv"`) -> 201, 43.385 B, claro
  `{"result":0,…,"live_topics":[265 entries]}` con `list:[]` — el glt es solo el INDICE.
- Cualquier otro `type` (la lista inventada live/sports/kids/… que usaba mi replay) ->
  201, 401 B `{"result":1,…,"no data"}`. **Nunca volver a inventar tipos.**
- `POST …/glbt` = plantilla + `"topic":"<id_topic>"` (el campo es `topic`, NO id_topic)
  -> 201 con `live:[89 items]` cuyo url es un NUMERITO ("1"), no una direccion — por eso
  el JS viejo filtraba TODO y la rejilla decia «0 canales». El item real:
  `{type:"tv", id_live:"live_tv_beinsport2", name:"Bein S. 2", url:"1",
  agent:"all_streams_redirect", img_url:"http://logo.twoapistack.work/img/channels/….png",
  id_topic:"hot_now"}`.
- PLAYBACK (del main.dart.js): `POST <urlGlbr> {id:id_live, url:item.url, agent:item.agent}`
  + los estandares de la plantilla. Medido:
  `https://re.highperformanceconnect.shop/redirect/glbr` -> 201 ->
  `data:{"url":"http://file.twoapistack.work/stream/update.mp4","agent":"Mozilla/5.0
  (Windows NT 10.0…) Chrome/90…"}` — el `agent` es el **User-Agent que hay que mandar**
  al reproducir ese url. (update.mp4 aqui = seuelo por la IP/geo del sandbox; del telefono
  saldra el stream real — el mecanismo es identico.)
- La app en v3.2.3: el JS colecciona items por `id_live` y emite `dramalive://play?id_live=…
  &u=…&a=…&t=…`; `auto()` pide glt tal cual, aprende `live_topics[].id_topic` y dispara
  glbt de los 10 primeros; Java `extraer()` hace el mismo flujo glt->topics->glbt (12);
  PlayerActivity resuelve glbr al pulsar (hilo secundario) y pone el UA/referer/origin del
  dictado. El puente (`desdePuente`) y la rejilla aceptan el esquema. Prueba definitiva:
  harness node alimentando el hook REAL con los ficheros capturados -> 265 topics +
  canal `dramalive://…` en la entrega. Tests nuevos Java: 6 — DramaSesionTest 22/22.

## 6.11 v3.2.4 — VOLCAR DEL USUARIO DEMUESTRA QUE FALTABAN 2 SEMILLAS (2026-10-10)

Volcar v3.2.3 del SM-A075M: req=4, plant='ninguna', topics=0, filas=0 — y la sesion
persistida estaba IMPECABLE (glt con type:'tv', wire ctiv, url li…). Tres causas medidas:
1. A +0.8s Flutter SOLO habia mandado `gs` (773 B) + manifests en main.fastconnections…;
   el glt propio llega despues. Sin semilla, auto() salia en blanco. FIX: el Activity
   inyecta `window.__DRAMA_SESION` (DramaSesion.sesionBootstrap desde prefs) ANTES del
   guion; el JS la consume y plant/urlGlt/urlGlbr/wire/topics quedan listos en el ms 0.
2. `CanalesEnVivoFragment:792` llamaba `extraer(sesion, "live,sports")` — LA LISTA
   INVENTADA — que el servidor real castiga con result:1 "no data". Eso era el
   «extraccion live 0 canales». FIX: pasar null (=auto glt->topics->glbt) y ademas
   `esCsvLegado()` trata los 6 tipos inventados como auto para llamadas viejas.
3. RACE REAL: los topicos solo se extraian en `procesar()`, pero `replay()` descifra por
   su cuenta y llamaba a `ver()` sin actualizar A.topics -> auto() no disparaba NI UN
   glbt (reproducido en node con la resp 43 KB: calls=1). FIX: extraccion de
   `live_topics[].id_topic` dentro de `ver()` (cubre trafico y replay). REPRO-SEMILLA
   FINAL: calls=41 (glt+40 glbt), topics=265, canal dramalive:// en filas ✓.
Golden nuevo: recurso app/src/test/resources/drama_glt_real.bin = la respuesta glt REAL
de 43.385 B — el test confirma desalambre ok, 12 topicos, y canales()==0 (el glt es solo
indice; los canales vienen por glbt). Suite 100, 1 rojo historico (ApiProbe live), 1 skip;
DramaSesionTest 26/26. Gate v === 324.

## 6.12 v3.2.5 — «dice 9002 canales y no los veo»: el m3u SI se guardaba, la
rejilla los filtraba (2026-10-10)

`CanalesEnVivoFragment.conLaListaDrama` fusiona con `ListaPegada.desdeTexto →
M3uParser.parsear`, y la linea 73 del parser solo dejaba pasar http/rtmp/rtp: las
9002 filas `dramalive://` del m3u extraido morian AHI (el guardado en prefs estaba
sano — de ahi el contador «9002 añadidos» del toast). Fixes:
1. M3uParser: `dramalive://` pasa (test: mixto http+esquema -> 2 canales).
2. CanalAdapter.normalizarUrlCanal: early-return del esquema (antes le anteponia
   https://belkaperu.github.io al ser «relativa» y el tap moria; extrajida version
   estatica y testeada en CanalAdapterTest).
3. BottomSheetOpcionesFragment: mismo early-return (su copia del normalizador).
4. Validacion del campo «pegar url» del fragment: acepta el esquema.
Tests: M3uParserTest 8/8, ListaPegadaTest 7/7 (camino EXACTO de la fusion con el
aM3u real de DramaSesion), CanalAdapterTest nuevo 1/1, DramaSesionTest 26/26.
Sin cambios en el JS (gate sigue en 324).

## 6.13 v3.2.6 — «los canales salen pero no se resuelven»: el fallo era SILENCIOSO

El usuario reproduce: rejilla con ~9002 canales, ningun playback. Forense en el sandbox
con su sesion (2026-10-10):
- glbr REAL re-mediado: 201, 498 B, prefijo = UN SOLO TAB (no `		
  `), sin
  saltos de linea; `glbrDesdeRespuesta` lo pasa en JVM (golden nuevo
  drama_glbr_real.bin, DramaSesionTest 28/28). Descifrado NO es la causa.
- `li.…/v13.0.0/glbr` da 404: solo existe `re.…/redirect/glbr` (el default ya es ese).
- `http://file.twoapistack.work/…` (url del dictado): 403 SIN User-Agent, 200 con
  CUALQUIERA (incluso el de ExoPlayer) -> no hace falta propagar data.agent.
- MANIFIESTO ya trae usesCleartextTraffic=true -> el http del stream no lo bloquea
  Android. Causa mas probable del «no se resuelve»: fallo de red/TLS/DNS del host
  re.… desde la linea del usuario, o sesion caducada — que antes moria en logcat.
Fixes: (1) Toast en cada fase («Resolviendo glbr…», y en fallo el motivo EXACTO con
HTTP/bytes/host); (2) plan B en fallo: urlIframeInicial=«https://web.dramalive.cc/» +
resolverStreamEnSegundoPlano -> el usuario juega el canal EN EL SITIO una vez; (3)
BACKFILL en el JS: al ver el glbr del propio sitio (req id_live + resp data.url) la
fila dramalive:// correspondiente pasa a u=http real (A.fijos en el volcar) -> el
tap siguiente es directo, sin resolver; (4) Java: fijarUrlResuelta reescribe el m3u
de prefs con la url real tras un playback exitoso (no se persiste el senuelo
update.mp4: se avisa por toast y se deja el canal apuntando al sitio). Gate v=326,
version 'v3.2.6'.

## 6.14 v3.2.7 — logcat del usuario: TODO el mecanismo funciona; el que fallaba era
el TIPO de media y el señuelo (2026-10-10 13:51)

El log trae la prueba: «glbr resuelto -> http://file.twoapistack.work/stream/update.mp4»
+ «Conexion exitosa (HTTP 200)» — resolucion, red y alambre OK desde el telefono. La
muerte es despues: mediaItemPara metia CUALQUIER url sin «.m3u8» al parser HLS (el
truco era para playlist.php) y ExoPlayer revento «Input does not start with the
#EXTM3U header {contentIsMalformed=true, dataType=4}» sobre un MP4. Fixes:
1. `esContenedorDeMedia(url)` (estatica, testeada): .mp4/.mkv/.webm/.mp3/.m4a/.aac/
   .mov/.avi/.flv (ignorando query) -> ExoPlayer autodetecta, sin MIME forzado.
2. Sueno update.mp4: NO se reproduce ni se persiste — toast «pago/zona» + plan B
   automatico abriendo el sitio en el WebView (ahi el hook del captador ve el glbr
   bueno si el sitio lo tiene y hace el backfill real).
3. Backfill JS con guarda: si data.url contiene update.mp4 se IGNORA (log «BACKFILL
   ignorado (sueno)»), la fila sigue dramalive://. Repro node: sueno->fila intacta;
   m3u8 real->fila reescrita, fijos=1, glbrOk=2 ✓.
4. Trasar los «5 intentos» del log: urlIframeInicial quedaba null y la recuperacion
   decia «No hay URL valida que resolver»; ahora SIEMPRE queda el sitio como plan B.
Nota para proximas capturas: update.mp4 sale para este usuario desde DOS lineas
distintas (sandbox y Peru) => es el «no tienes licencia para este canal» del backend
(isPremium:false / notloggedin), no geo. El repro de backfill con un id Freemium del
propio sitio confirmara que los gratuitos si traen m3u8. Gate v=327, barra
«captador v3.2.7 INSTALADO (semilla + backfill + guardas)».

## 6.15 v3.2.8 — «exception null» del Extraer: el captador aprendio el endpoint de
TELEMETRIA como si fuera el glt, y el glbr de los canales reales embebe JSON

Dos bugs gemelos, ambos destapados por el logcat/volcar del usuario (14:2x):
1. `A.urlGlt` se fijaba con el PRIMER request legible que trajera user_id — y el sitio
   manda antes `log.fastconnections…/v9.0.0/la` (log de telemetria, mismo envelope de
   sesion). La entrega guardaba esa url; `extraer()` la usaba; el logger contesta
   HTTP 500 a payloads de catalogo (medido) -> el fragment mostraba «exception null».
   FIX triple: el JS solo fija urlGlt desde ep glt/glbt/glasbi; la entrega sanea `url`
   (deriva /glbt->/glt si hace falta); Java `urlGltDe()` rechaza cualquier url cuyo path
   no acabe en /glt (fallback: derivar de urlGlbt, luego base+glt) — usado por extraer()
   y por la semilla `sesionBootstrap`. Verificado en vivo con LA SESION ENVENENADA del
   usuario: extraer() -> ok, 9115 canales en 8.0 s (Run.java sobre las clases compiladas).
2. El glbr de CANALES REALES (el usuario ya los conseguia: fas6.fastspeedai.cfd/
   pages/<id>/index.html) trae `data.url` como un STRING que CONTIENE json
   {"url":…,"agent":…} — no una url. v3.2.7 hacia backfill del paquete entero y el
   reproductor moria. FIX: `glbrDesdeRespuesta` desenrolla las 3 formas medidas
   (string limpio / string-con-json / objeto anidado) y el JS backfill igual (con
   guarda update.mp4). Tests: urlGltDe, 2 formas embebidas, bootstrap saneado, repro
   node del backfill embebido (fila queda con la url limpia, fijos=1).
Suite 109 (1 rojo historico ApiProbe live, 1 skip); DramaSesionTest 31/31. Gate
v=328, barra «captador v3.2.8 INSTALADO (endpoints sanos + backfill embebido)».

## 7. Siguiente trabajo concreto (en orden)

1. ~~Implementar el `swap` en la app.~~ **HECHO 2026-10-08**: `SwapHls` (lógica pura,
   testeada contra el playlist REAL de `fas4`) + `DramaSwapDataSource` (envuelve la fabrica
   de ExoPlayer y reescribe CADA recarga del playlist — no un reemplazo de una sola vez,
   porque en live ExoPlayer vuelve a pedir el m3u cada ventana). `EnlaceDrama.paresSwap`
   extrae los pares del JSON descifrado; auto-deteccion `k9x_` cuando la URI es
   `.../index.html` sin pares explicitos. Tests: `SwapHlsTest` (7) verdes.
2. ~~Extraccion de canales~~ **HECHO 2026-10-08**: `DramaSesion` (alambre glt
   AES-128-CBC `43fc9b1a3f854049`, round-trip testeado; mapeo JSON->canales con dedupe;
   catch-all 32 B reportado como "sesion caducada") + bloque en el sheet de fuentes
   (`editSesionDrama`/`btnExtraerDrama`) + fusion NO destructiva de la lista extraida en
   la rejilla (`FuenteCanales.dramaExtraIdo`, dedupe por URL de opcion). Tests:
   `DramaSesionTest` (6) verdes.
3. **Siguiente**: prueba de humo en dispositivo real con la sesion FRESCA del usuario
   (pegar plantillas.json → Extraer → abrir canal → debe dar fotograma, no 404). Si el
   panel cambia el alambre, el primer sitio en enterarse es `DramaSesionTest.alambreRoundTrip`
   + `drama-live.py verificar`.
4. Mantener `COMO-ABRIR.txt` y el contador de commits al dia en cada entrega.
5. Re-entregar SIEMPRE los tres artefactos: zip plano, `patches/0001..NNNN`, APK compilado.


## 8. Reglas no negociables

- Español en toda respuesta y commits; cero promesas de "reproduce DramaLive" si no hay
  fotograma medido; **honestidad de medición**: si da 404/CADUCADO, decirlo con los números.
- No usar `shai`/credenciales embebidas; no construir scraping masivo del catálogo fuera de
  la sesión del usuario; no cazar espejos piratas "por si acaso".
- No re-derivar AES/MD5 a mano — el motor está en `tools/aes_bloque.py` y `javax.crypto`; está
  verificado contra CryptoJS 4.2.0 (oracle `docs/*.json`). Reescribir el KDF solo contra los
  vectores del oracle (los intentos "creativos" del KDF perdieron semanas; la forma correcta
  es la de §2 y no cambia).
- `git add -A` antes de `format-patch`; `rm -f patches/*.patch` SOLO inmediatamente antes de
  regenerarlos (son la única copia de los commits si el sandbox revierte).

## 9. Contacto con el usuario

Pide resultados, no prosa: ejecutar, pegar salida medida, entregar ficheros. Si algo no se
puede desde el entorno del agente, decir "no puedo" con la medición que lo demuestra.
