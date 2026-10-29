#!/usr/bin/env node
/*
  Prueba de tools/api-canales/server.mjs.
  Arranca el servidor de verdad (puerto efímero), le hace peticiones HTTP reales
  y comprueba los resultados. No necesita red: las fuentes son ficheros locales.

    node tools/api-canales/probar-api.mjs
*/

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { crearServidor, configDesdeEntorno, parsearM3u, unificar } from './server.mjs';

const AQUI = path.dirname(fileURLToPath(import.meta.url));
const FIXTURE = path.join(AQUI, 'fixtures', 'muestra.m3u');
const ROTO = path.join(AQUI, 'fixtures', 'no-existe.m3u');
const hosts = JSON.parse(fs.readFileSync(path.join(AQUI, 'fixtures', 'hosts.json'), 'utf8'));

let casos = 0;
const fallos = [];
function ok(cond, que, detalle) {
  casos++;
  if (!cond) fallos.push(que + (detalle === undefined ? '' : '  [' + JSON.stringify(detalle) + ']'));
}
function igual(a, b, que) { ok(a === b, que, { obtenido: a, esperado: b }); }

const cfg = { fuentes: [FIXTURE, ROTO], puerto: 0, hosts, origenes: ['https://mi-panel.local'], ttl: 0 };
const srv = crearServidor(cfg);
await new Promise((r) => srv.listen(0, '127.0.0.1', r));
const BASE = 'http://127.0.0.1:' + srv.address().port;
const get = async (ruta, opciones) => {
  const r = await fetch(BASE + ruta, opciones);
  const texto = await r.text();
  const esJson = (r.headers.get('content-type') || '').includes('json');
  return { status: r.status, headers: r.headers, texto, json: esJson && texto ? JSON.parse(texto) : null };
};

try {
  // ---------------------------------------------------- arranque y salud
  const salud = await get('/healthz');
  igual(salud.status, 200, 'healthz responde 200');
  igual(salud.json.ok, true, 'healthz dice ok');
  igual(salud.json.canales, 5, 'cinco canales únicos desde el fixture');
  igual(salud.json.fuentes, 2, 'healthz cuenta las dos fuentes');

  const raiz = await get('/');
  ok(/no llama a DramaLive/i.test(raiz.json.nota), 'la raíz declara que no llama a terceros', raiz.json.nota);

  // ---------------------------------------------------- fuente rota: no rompe
  const fuentes = await get('/v1/fuentes');
  const rota = fuentes.json.fuentes.find((f) => f.fuente === ROTO);
  ok(rota && rota.ok === false && /no such file|ENOENT|no existe/i.test(rota.error || ''), 'la fuente ilegible se reporta sin tumbar la API', rota);
  igual(fuentes.json.canales, 5, 'la fuente rota no resta canales');

  // ---------------------------------------------------- canales
  const todos = await get('/v1/channels');
  igual(todos.json.total, 5, 'total en /v1/channels');
  const bein = todos.json.canales.find((c) => /bein/i.test(c.nombre));
  ok(!!bein, 'beIN SPORTS XTRA aparece en la lista');
  igual(bein.nombre, 'beIN SPORTS XTRA', 'la calidad no forma parte del nombre');
  igual(bein.calidad, '1080p', 'la calidad queda como etiqueta');
  igual(bein.grupo, 'Sports', 'grupo Sports');
  igual(bein.servidores.length, 1, 'un servidor para beIN');
  igual(bein.servidores[0].url, 'https://bein-xtra-bein.amagi.tv/playlist.m3u8', 'URL de AMAGI intacta');
  ok(/i\.imgur\.com/.test(bein.logo), 'logo conservado');
  igual(bein.tvgId, 'beINSportsXtra.us', 'tvg-id conservado');

  const basura = todos.json.canales.find((c) => c.grupo === 'Movies');
  igual(basura.nombre, 'box1', 'el User-Agent basureado se rescata con tvg-id');
  ok(!/Gecko|Chrome/i.test(basura.nombre), 'ningún tile se llama como un User-Agent', basura.nombre);
  ok(/ayakkabiparti/.test(basura.servidores[0].url), 'la URL rarísima se conserva igual');

  const uno = todos.json.canales.find((c) => c.nombre === 'Canal Uno');
  igual(uno.grupo, 'General', 'group-title="Undefined" se va a General');

  const gol = todos.json.canales.find((c) => /gol perfume/i.test(c.nombre));
  igual(gol.servidores.length, 2, 'el mismo nombre en SD y HD se une en un canal con dos servidores');
  igual(gol.calidad, 'SD / HD', 'las calidades se acumulan como etiquetas');
  ok(gol.servidores[0].etiqueta === 'SD' && gol.servidores[1].etiqueta === 'HD', 'cada servidor con su etiqueta', gol.servidores.map((s) => s.etiqueta));

  const cartoon = todos.json.canales.find((c) => c.nombre === 'Cartoon');
  ok(cartoon.grupos.join('|') === 'Kids|Family', 'group-title con punto y coma reparte en dos grupos', cartoon.grupos);

  // ---------------------------------------------------- filtros
  const sports = await get('/v1/channels?grupo=Sports');
  igual(sports.json.total, 2, '?grupo=Sports deja dos canales');
  const buscar = await get('/v1/search?q=be%20in');
  igual(buscar.json.total, 1, '?q=be in encuentra solo beIN (espacios tolerados)');
  igual(buscar.json.canales[0] && buscar.json.canales[0].nombre, 'beIN SPORTS XTRA', 'el buscador responde con el canal limpio');
  igual((await get('/v1/search?q=GOLPERFUME')).json.total, 1, '?q=GOLPERFUME sin espacios también encuentra');
  igual((await get('/v1/search?q=amagi')).json.total, 0, 'no se busca dentro de la URL, solo en nombres y grupos');
  igual((await get('/v1/channels?grupo=kids')).json.total, 1, '?grupo= vale para el grupo secundario');
  const porGrupo = await get('/v1/grupos');
  const sportsEnGrupos = porGrupo.json.grupos.find((g) => g.nombre === 'Sports');
  igual(sportsEnGrupos && sportsEnGrupos.total, 2, '/v1/grupos cuenta Sports');
  ok(porGrupo.json.grupos.some((g) => g.nombre === 'Kids'), '/v1/grupos incluye los grupos secundarios');
  const paginado = await get('/v1/channels?limit=2&offset=1');
  igual(paginado.json.canales.length, 2, 'limit/offset cortan la lista');
  igual(paginado.json.enTotal, 5, 'enTotal sigue diciendo el total real');

  // ---------------------------------------------------- M3U regenerado
  const m3u = await get('/v1/channels.m3u');
  igual(m3u.status, 200, 'el M3U se pide bien');
  ok(m3u.texto.startsWith('#EXTM3U'), 'empieza con #EXTM3U');
  const lineasExtinf = m3u.texto.split('\n').filter((l) => l.startsWith('#EXTINF')).length;
  igual(lineasExtinf, 6, 'seis #EXTINF (cinco canales, uno con dos servidores)');
  ok(/group-title="Sports"/.test(m3u.texto), 'conserva el group-title');
  const vuelta = unificar(parsearM3u(m3u.texto));
  igual(vuelta.length, 5, 'el M3U generado vuelve a parsearse al mismo número de canales');
  const bein2 = vuelta.find((c) => /bein/i.test(c.nombre));
  igual(bein2.nombre, 'beIN SPORTS XTRA', 'el nombre sobrevive el viaje de ida y vuelta');
  igual(bein2.calidad, '1080p', 'y la calidad también');
  const m3uFiltrado = await get('/v1/channels.m3u?grupo=Sports');
  igual(m3uFiltrado.texto.split('\n').filter((l) => l.startsWith('#EXTINF')).length, 3, 'el M3U respeta ?grupo=');

  // ---------------------------------------------------- play + headers
  const play = await get('/v1/play?canal=' + bein.id);
  igual(play.json.url, 'https://bein-xtra-bein.amagi.tv/playlist.m3u8', '/v1/play devuelve la URL del canal');
  igual(Object.keys(play.json.headers).length, 0, 'sin configuración, no se inventa ningún header');
  const playPropio = await get('/v1/play?canal=' + cartoon.id + '&servidor=0');
  igual(playPropio.json.headers.Referer, 'http://mi-proveedor.net:8000/', '/v1/play adjunta el Referer que tú configuraste');
  igual(playPropio.json.headers['User-Agent'], 'ReproductorPropio/1.0', 'y el User-Agent propio');
  const playServidor2 = await get('/v1/play?canal=' + gol.id + '&servidor=1');
  igual(playServidor2.json.servidor, 1, 'se puede elegir el servidor');
  ok(/otro-host/.test(playServidor2.json.url), 'el servidor 1 es el del otro host', playServidor2.json.url);
  const playBuscado = await get('/v1/play?canal=beIN SPORTS XTRA');
  igual(playBuscado.json.canal, 'beIN SPORTS XTRA', 'también se puede pedir por nombre');
  const play404 = await get('/v1/play?canal=no-existe');
  igual(play404.status, 404, '/v1/play de un canal inexistente da 404');

  // ---------------------------------------------------- CORS: solo tus orígenes
  const conOrigen = await get('/v1/channels?limit=1', { headers: { Origin: 'https://mi-panel.local' } });
  igual(conOrigen.headers.get('access-control-allow-origin'), 'https://mi-panel.local', 'CORS: el origen permitido recibe Access-Control-Allow-Origin');
  const otro = await get('/v1/channels', { headers: { Origin: 'https://alguien-mas.example' } });
  ok(!otro.headers.get('access-control-allow-origin'), 'CORS: un origen no listado no recibe cabecera', otro.headers.get('access-control-allow-origin'));
  const pre = await fetch(BASE + '/v1/channels', { method: 'OPTIONS', headers: { Origin: 'https://mi-panel.local' } });
  igual(pre.status, 204, 'CORS: el preflight contesta 204');
  igual(pre.headers.get('access-control-allow-origin'), 'https://mi-panel.local', 'CORS: el preflight devuelve el origen');
  const malMetodo = await get('/v1/channels', { method: 'POST', body: 'x' });
  igual(malMetodo.status, 405, 'solo se acepta GET');
  const rutaRara = await get('/v2/no-existe');
  igual(rutaRara.status, 404, 'ruta desconocida da 404 con el índice de rutas');
  ok(Array.isArray(rutaRara.json.rutas), 'el 404 lista las rutas disponibles');

  // ---------------------------------------------------- parseo suelto
  const crlf = parsearM3u('#EXTM3U\r\n#EXTINF:-1 tvg-id="",Nombre CRLF\r\nhttp://example.net/live/x/4001.m3u8\r\n');
  igual(crlf.length, 1, 'CRLF y coma suelta se parsean');
  igual(crlf[0].nombre, 'Nombre CRLF', 'el nombre de la línea CRLF');
  const solaUrl = parsearM3u('http://example.net/live/usuario/clave/4001.m3u8\n');
  igual(solaUrl[0].nombre, '4001', 'una URL suelta recibe nombre desde la ruta');
  const indexUrl = parsearM3u('http://201.190.41.246:9060/play/index.m3u8\n');
  igual(indexUrl[0].nombre, '201.190.41.246:9060', 'si el tramo final es "index", se usa el host');
  const basuraDura = parsearM3u('#EXTINF:-1 tvg-id="" tvg-name="" tvg-logo="" group-title="x",Mozilla/5.0 (Windows NT 10.0)\r\nhttps://a.example/b.m3u8\n');
  igual(basuraDura[0].nombre, 'B', 'un nombre que es un User-Agent se cambia por el del archivo (capitalizado)');
  const sinComa = parsearM3u('#EXTINF:-1 Solo Nombre Sin Comas\nhttp://a.example/b\n');
  igual(sinComa[0].nombre, 'Solo Nombre Sin Comas', 'EXTINF sin coma se interpreta igual');
  const comaEnNombre = parsearM3u('#EXTINF:-1 tvg-name="",Noticias, en vivo\nhttp://a.example/n\n');
  igual(comaEnNombre[0].nombre, 'Noticias, en vivo', 'las comas del nombre no se comen los atributos');

  // ---------------------------------------------------- config
  const porArgv = configDesdeEntorno(['node', 'server.mjs', '--canales-fuentes=a.m3u,b.m3u', '--canales-puerto=9100', '--canales-ttl=0'], {});
  igual(porArgv.fuentes.length, 2, 'configDesdeEntorno: --canales-fuentes divide por coma');
  igual(porArgv.puerto, 9100, 'configDesdeEntorno: --canales-puerto manda');
  igual(porArgv.ttl, 0, 'configDesdeEntorno: --canales-ttl=0 (sin caché)');
  const porEnv = configDesdeEntorno(['node', 'server.mjs'], { CANALES_FUENTES: 'x.m3u', CANALES_ORIGINOS: 'https://a, https://b' });
  igual(porEnv.fuentes[0], 'x.m3u', 'configDesdeEntorno: lee CANALES_FUENTES');
  igual(porEnv.origenes.length, 2, 'configDesdeEntorno: lee CANALES_ORIGINOS');
  igual(porEnv.puerto, 8787, 'configDesdeEntorno: puerto por defecto');
  igual(Object.keys(porEnv.hosts).length, 0, 'configDesdeEntorno: sin CANALES_HOSTS no hay headers configurados');
  const conArchivoHosts = configDesdeEntorno(['node', 'server.mjs', '--canales-hosts=' + path.join(AQUI, 'fixtures', 'hosts.json')], {});
  ok(!!conArchivoHosts.hosts['mi-proveedor.net'], 'configDesdeEntorno: lee el JSON de hosts', conArchivoHosts.hosts);
  const porGuión = configDesdeEntorno(['node', 'server.mjs', '--canales-origenes=https://a'], {});
  igual(porGuión.origenes[0], 'https://a', 'configDesdeEntorno: --canales-origenes vale');

  const sinFuentes = await new Promise((resuelve) => {
    const hijo = crearServidor({ fuentes: [], puerto: 0, hosts: {}, origenes: [], ttl: 0 });
    hijo.listen(0, '127.0.0.1', async () => {
      const r = await fetch('http://127.0.0.1:' + hijo.address().port + '/v1/channels');
      const j = await r.json();
      hijo.close();
      resuelve({ status: r.status, j });
    });
  });
  igual(sinFuentes.status, 200, 'sin fuentes la API no revienta');
  igual(sinFuentes.j.total, 0, 'sin fuentes devuelve lista vacía');
} catch (e) {
  fallos.push('excepción en la prueba: ' + ((e && e.message) || e));
} finally {
  srv.close();
}

if (fallos.length) {
  console.error('FALLAN ' + fallos.length + ' de ' + casos + ':');
  for (const f of fallos) console.error('  - ' + f);
  process.exit(1);
}
console.log('OK — ' + casos + ' casos');
