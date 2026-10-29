# API de canales (tuya, sobre tus fuentes)

Un servidor HTTP en un solo archivo, sin dependencias, que hace una cosa: **lee las
listas que tú le des** (archivos `.m3u` locales o URLs), las unifica con las mismas
reglas que usa la app de Android y las devuelve como **JSON limpio** y como **M3U
regenerado**. Así puedes reutilizar tus canales fuera de la app (una web, un
reproductor de escritorio, un Home Assistant, lo que sea) sin depender de nadie.

```
node tools/api-canales/server.mjs
```

## Qué NO es

No es un proxy de DramaLive ni de ningún otro servicio, y no reimplementa su cifrado.
Se compridió el 2026-10-06 y no hace falta discutirlo:

| Prueba (desde el sandbox) | Resultado |
|---|---|
| `POST li.highperformanceconnect.shop/api/live/livedrama/v13.0.0/a` sin cifrar | `200`, cuerpo de **32 B de espacios** |
| `POST …/v13.0.0/glt` sin cifrar | `200`, **32 B de espacios** |
| `POST main.fastconnections.online/api/live/livedrama/v13.0.0/gs` sin cifrar | `200`, **32 B de espacios** |
| Las mismas tres peticiones **con** `User-Agent`, `Referer: https://web.dramalive.cc/` y `Origin` de la app | **idénticas**: `200`, 32 B de espacios |

O sea: el obstáculo no es CORS ni los headers. Un worker que reenvíe con
`Referer`/`User-Agent` de la app recibe exactamente lo mismo que desde el
navegador: vacío. La respuesta sólo llega cifrada/descifrada dentro de su cliente,
y eso no se va a reproducir aquí. Lo que sí funciona es usar **tus** listas, y
para eso existe esta API.

## Requisitos y arranque

Node 18+. Nada que instalar.

```bash
# una lista tuya (por ejemplo la que exportaste con docs/exportar-canales-navegador.js)
CANALES_FUENTES=./mi-lista.m3u node tools/api-canales/server.mjs

# varias, locales y remotas, separadas por coma
CANALES_FUENTES=./mi-lista.m3u,https://iptv-org.github.io/iptv/categories/sports.m3u \
CANALES_PUERTO=8787 node tools/api-canales/server.mjs

# también por argumentos
node tools/api-canales/server.mjs --canales-fuentes=./mi-lista.m3u --canales-puerto=8787
```

| Variable | Para qué sirve | Por defecto |
|---|---|---|
| `CANALES_FUENTES` | `.m3u` locales o URLs, separados por coma | obligatorio |
| `CANALES_PUERTO` | puerto de escucha (en `0.0.0.0`) | `8787` |
| `CANALES_ORIGINOS` | allowlist de orígenes para CORS, separada por coma | vacío = sin `Access-Control-Allow-Origin` |
| `CANALES_HOSTS` | JSON `{"host": {"Referer": "…", "Origin": "…", "User-Agent": "…"}}` para los streams que tú controlas | vacío |
| `CANALES_TTL` | segundos de caché de las fuentes; `?fresh=1` la salta | `300` |

Sin `CANALES_ORIGINOS` la API no responde a ningún otro origen: no es un proxy
abierto para que lo use medio internet.

## Endpoints

```bash
curl 'http://127.0.0.1:8787/healthz'
curl 'http://127.0.0.1:8787/v1/fuentes'                      # cuántos bytes salió de cada fuente
curl 'http://127.0.0.1:8787/v1/channels?grupo=Sports&q=bein&limit=50&offset=0&fresh=1'
curl 'http://127.0.0.1:8787/v1/search?q=gol%20perfume'       # tolera espacios: "be in" encuentra "beIN"
curl 'http://127.0.0.1:8787/v1/grupos'                       # pestañas con su conteo
curl 'http://127.0.0.1:8787/v1/channels.m3u?grupo=Sports'    # M3U canónico, para la app o para mpv/VLC
curl 'http://127.0.0.1:8787/v1/play?canal=c33&servidor=1'    # {url, headers} de un servidor concreto
```

`/v1/channels` devuelve exactamente esto (medido con el fixture del repo):

```json
{
  "total": 5,
  "enTotal": 5,
  "canales": [
    {
      "id": "c1",
      "nombre": "beIN SPORTS XTRA",
      "grupo": "Sports",
      "grupos": ["Sports"],
      "logo": "https://i.imgur.com/8Zh46FO.png",
      "calidad": "1080p",
      "tvgId": "beINSportsXtra.us",
      "servidores": [
        { "etiqueta": "1080p", "url": "https://bein-xtra-bein.amagi.tv/playlist.m3u8", "calidad": "1080p" }
      ]
    }
  ]
}
```

Reglas de unificación (mismas que en la app): `(1080p)` / `[FHD]` / `(SD)` del nombre
pasan a `calidad` y `etiqueta`; `group-title="Kids;Family"` genera dos grupos; el
grupo `Undefined` se convierte en `General`; dos líneas con el mismo nombre se
convierten en **un** canal con **dos servidores**; y un nombre que en realidad es un
User-Agent o una URL se reemplaza por `tvg-name` → `tvg-id` → el archivo de la ruta.

## Cómo se usa

**Desde la app de Android** (el caso más útil): `En vivo → Fuente → URL de lista` y
pones `http://<IP-de-tu-máquina>:8787/v1/channels.m3u`. La app ya sabe leer una URL
de lista, así que la API se convierte en tu "panel" privado: puedes editar tu `.m3u`
en un solo sitio y lo ven el teléfono, el TV y la web.

**Desde un reproductor**: `mpv "$(curl -s http://127.0.0.1:8787/v1/play?canal=c1 | jq -r .url)"`.

**Con encabezados obligatorios**: si tu proveedor exige `Referer`, se los dices en
`CANALES_HOSTS` y `/v1/play` los devuelve junto con la URL; el reproductor los aplica
(mpv `--http-header-fields`, VLC `http-user-agent`) o, en la app, Media3 con
`DefaultHttpDataSource.Builder().setDefaultRequestProperties(map)`. La API **no**
reenvía el video: entrega URL + headers y el cliente va directo al origen.

## Prueba

```bash
node tools/api-canales/probar-api.mjs     # OK — 75 casos
```

Arranca el servidor de verdad en un puerto efímero, le hace peticiones HTTP reales y
verifica salud, conteos, filtros, grupos, el M3U regenerado (con viaje de ida y
vuelta: se vuelve a parsear y da el mismo número de canales), la unificación de
servidores, los headers por host, que una fuente ilegible no tumbe la API, que el
CORS sólo respete tus orígenes, que un origen ajeno no reciba cabeceras, `405` fuera
de `GET`, `404` en rutas desconocidas, y los casos raros de parseo (CRLF, comas
dentro del nombre, `#EXTINF` sin coma, URL suelta sin `#EXTINF`).

## Límite, en una frase

La API solo muestra lo que tú le pongas dentro; no hace que una señal de pago robada se
vuelva legal, y no va a ir a buscar espejos piratas por ti. Si lo que metes en
`CANALES_FUENTES` es tu lista de proveedor o una señal pública como la de AMAGI,
todo lo que devuelva es tuyo y reutilizable.
