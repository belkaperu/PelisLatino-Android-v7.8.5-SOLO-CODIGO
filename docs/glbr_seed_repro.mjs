import { readFileSync } from 'fs';
import { createCipheriv, createDecipheriv, randomBytes } from 'crypto';
import { resolve, dirname } from 'path';
import { fileURLToPath } from 'url';
// v3.2.25: prueba con el JS REAL que la semilla popula A.plant.glbrReq SIN exigirle
// user_id (log usuario 23:56: la cosecha nunca arrancaba por esa guarda), que la request
// glbr del sitio aprende la plantilla Y dispara el armamento de la cosecha, y que las
// plantillas basura SIN user_id siguen filtradas (no abrir la puerta de mas).
// v3.2.26 (T13): la cadena semilla->cosecha habla sola — los hitos BLABONGO salen por el
// puente AndroidDrama.hito SIEMPRE: al consumir la semilla, al arrancar/abortar/terminar
// la cosecha y cuando el glbrReq sigue nulo a lo bruto (el caso mudo del log 00:36).
const src = readFileSync(resolve(dirname(fileURLToPath(import.meta.url)), '../app/src/main/assets/drama_auto.js'), 'utf8');
const hitos = [];
const win = {
  addEventListener() {}, removeEventListener() {},
  location: { href: 'https://web.dramalive.cc/', reload() {} },
  navigator: { userAgent: 'node-repro' },
  localStorage: { getItem: () => null, setItem() {}, removeItem() {} },
  setInterval: () => 0, setTimeout: (f, ms) => setTimeout(f, ms), console,
  AndroidDrama: {
    hito: (m) => { hitos.push(String(m)); },
    estado() {}, volcar() {}, entregar() {}, cosechaTermino() {}, aprenderCodigo() {},
  },
};
win.fetch = undefined;
// T13: fetch de red ROTO a proposito — cada fila de la cosecha termina en sueno, y asi
// el test es determinista sin tocar la red (lo que se prueba es el HITO, no el stream).
globalThis.fetch = async () => ({ ok: true, status: 201, text: async () => 'zz!not-wire', json: async () => ({}) });
globalThis.window = win;
globalThis.document = { readyState: 'complete', addEventListener() {}, createElement: () => ({}) };
globalThis.location = win.location; globalThis.navigator = win.navigator;
globalThis.localStorage = win.localStorage;
// (a) SEMILLA honesta: glbrReq de 20 campos SIN user_id + basura sin user_id
win.__DRAMA_SESION = {
  plant: {
    glt: { user_id: 'u-sem', type: 'tv' },
    glbrReq: { id: 'live_tv_alkaas2', url: '1', agent: 'all_streams_redirect', token: 'T9',
               rc: 0, adsShowed: 0, adsComplete: 0, runCount: 3, timeZone: 'America/Lima' },
    churumbo: { nada: true },
  },
  urlGlt: 'https://li.highperformanceconnect.shop/api/live/livedrama/v13.0.0/glt',
  urlGlbr: 'https://re.highperformanceconnect.shop/redirect/glbr',
  wire: 'ctiv',
};
try { eval(src); } catch (e) { console.log('EVAL_ERR:', e && (e.stack || e.message)); process.exit(1); }
const T = win.__dramaAutoTest, A = T.A;
const seedOk = A.plant.glbrReq && A.plant.glbrReq.token === 'T9' && A.plant.glbrReq.id === 'live_tv_alkaas2'
  && A.plant.glt && A.plant.glt.user_id === 'u-sem';
console.log(seedOk ? 'SEED25_OK' : 'SEED25_FAIL', JSON.stringify(A.plant.glbrReq || null));
const junkOk = !A.plant.churumbo;   // la guarda legitima sigue viva para las DEMAS plantillas
console.log(junkOk ? 'JUNK25_OK' : 'JUNK25_FAIL');
// (b) TRAFICO: una request glbr real (sin user_id) debe aprender + armar la cosecha (A.coseHecho=1)
const q = { id: 'live_tv_beinsport5', url: '1', agent: 'all_streams_redirect', token: 'TK', rc: 0 };
(async function () {
  try {
    const w = await T.encPara(q, 'x');
    await T.procesar('https://re.highperformanceconnect.shop/redirect/glbr', w, 'req');
    const trafOk = A.plant.glbrReq && A.plant.glbrReq.id === 'live_tv_beinsport5'
      && A.glbrPend === 'live_tv_beinsport5' && A.coseHecho === 1;
    console.log(trafOk ? 'TRAFFIC25_OK' : 'TRAFFIC25_FAIL', 'plant=' + JSON.stringify(A.plant.glbrReq && A.plant.glbrReq.id), 'coseHecho=' + A.coseHecho);
    // (c) T13 HITOS: el consumo de semilla ya debe haber hablado (eval ocurrio arriba).
    const hitoSeed = hitos.some((m) => m.indexOf('semilla: glbrReq=9 campos') === 0 && m.indexOf('glt=si') > 0);
    console.log(hitoSeed ? 'HITO_SEED26_OK' : 'HITO_SEED26_FAIL', JSON.stringify(hitos.slice(0, 3)));
    // (d) T13: cosecha REAL (1 fila sintetica por el prior; el fetch roto => sueno) —
    // tiene que dejar arranque y fin en los hitos, sin guards que los tapen.
    win.__DRAMA_PRIOR = 'live_tv_beinsport5'; win.__DRAMA_PRIOR_N = 'Bein Sports 5';
    let err13 = null;
    try { await T.A.cosecha('test'); } catch (e) { err13 = e; }
    const arr = hitos.some((m) => m.indexOf('cosecha(test): glbrReq=si') === 0);
    const arranc = hitos.some((m) => m.indexOf('cosecha arranca: ') === 0 && m.indexOf('prior=live_tv_beinsport5') > 0);
    const finc = !err13 && hitos.some((m) => m.indexOf('cosecha fin: 0/1 reales · 0 suenos · 1 vacias') === 0 && m.indexOf('curado=0') > 0);
    console.log(arr && arranc && finc ? 'COSECHA26_OK' : 'COSECHA26_FAIL', err13 ? String(err13 && err13.message) : JSON.stringify(hitos.filter((m) => /cosecha|COSECHA/.test(m))));
    // (e) T13 el caso MUDO del log 00:36: sin glbrReq la cosecha aborta, y ahora ESO SE VE.
    A.plant.glbrReq = null;
    try { await T.A.cosecha('neg'); } catch (e) {}
    const abort = hitos.some((m) => m.indexOf('COSECHA abortada: el gancho no tiene A.plant.glbrReq') === 0);
    console.log(abort ? 'ABORT26_OK' : 'ABORT26_FAIL');
    // (f) T14 v3.2.27: un lanzamiento BLOQUEADO (auto en vuelo — el asesino del log 12:26:46)
    // NO debe consumir A.coseHecho: el tick tiene que poder reintentar.
    A.corriendo = true; A.coseHecho = 1;
    try { await T.A.cosecha('bloq'); } catch (e) {}
    const desbloqueo = A.coseHecho === 0 && hitos.some((m) => m.indexOf('lanzamiento bloqueado') === 0);
    console.log(desbloqueo ? 'DESBLOQUEO27_OK' : 'DESBLOQUEO27_FAIL', 'coseHecho=' + A.coseHecho);
    // (g) T15 v3.2.28: la maquina de estados del SITIO — primer glbr da el sueno
    // (update.mp4 = anuncio), el captador lo reproduce muted y RE-PIDE con runCount+1;
    // ahi llega el stream REAL y la fila del prior queda curada en la misma cosecha.
    const KEY = Buffer.from('43fc9b1a3f854049');
    const wire = (obj) => {
      let pt = Buffer.from(JSON.stringify(obj));
      const n = 16 - pt.length % 16; pt = Buffer.concat([pt, Buffer.alloc(n, n)]);
      const iv = randomBytes(16); const c = createCipheriv('aes-128-cbc', KEY, iv);
      return Buffer.concat([c.update(pt), c.final()]).toString('base64') + ':' + iv.toString('base64');
    };
    A.corriendo = false; A.adWatched = 0; A.priorFijo = 0;
    A.plant.glbrReq = { id: 'x', url: '1', agent: 'all_streams_redirect', runCount: 0, adsShowed: 0, adsComplete: 0 };
    let nGlbr = 0;
    win.fetch = globalThis.fetch = async (u) => {
      if (String(u).indexOf('glasbi') >= 0) return { ok: true, status: 201, text: async () => wire({ result: 1 }) };
      nGlbr++;
      const body = nGlbr === 1 ? { result: 0, data: { url: 'http://file.twoapistack.work/stream/update.mp4' } }
                               : { result: 0, data: { url: 'https://stream.example/live.m3u8' } };
      return { ok: true, status: 201, text: async () => wire(body) };
    };
    let err15 = null;
    try { await T.A.cosecha('t15'); } catch (e) { err15 = e; }
    const adOk = nGlbr >= 2 && A.adWatched === 1 && A.priorFijo === 1
      && hitos.some((m) => m.indexOf('anuncio (update.mp4)') === 0);
    console.log(adOk ? 'ANUNCIO28_OK' : 'ANUNCIO28_FAIL', 'glbr=' + nGlbr, 'adWatched=' + A.adWatched, 'priorFijo=' + A.priorFijo, err15 ? String(err15.message) : '');
    // (h) T16 v3.2.29 — LA JUGADA DEL NAVEGADOR, medida en python con frame h264 1024x540:
    // glasbi{id} -> live.url '.LS.V2...' agent 'redirect' -> glbr -> JSON advanced cuya .url
    // es la pagina-player (playlist con swap). El mock DESENCRIPTA el cuerpo: solo suelta el
    // advanced si el glbr lleva el marcador; si no, sueno eterno. Con el JS viejo esto es ROJO.
    let glasbiOk = false;
    {
      const unwire = (txt) => {
        try {
          const str = String(txt); const i = str.lastIndexOf(':');
          const d = createDecipheriv('aes-128-cbc', KEY, Buffer.from(str.slice(i + 1), 'base64'));
          let pt = Buffer.concat([d.update(Buffer.from(str.slice(0, i), 'base64')), d.final()]);
          const pad = pt[pt.length - 1]; if (pad >= 1 && pad <= 16) pt = pt.subarray(0, pt.length - pad);
          return JSON.parse(pt.toString('utf8'));
        } catch (e) { return null; }
      };
      const AVANZADO = { url: 'https://sup6.superfastnet.cyou/pages/fnwzpoqozpru/index.html',
        agent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) Chrome/149.0.0.0',
        mediatype: 'hls', swap: { 'k9x_': '' } };
      const MARCA = 'http://.LS.V2live_tv_custom_handler_live_tv_beinsport5_description_HD_/s';
      let f16 = (A.filas || []).find((f) => f && f.di === 'live_tv_beinsport5');
      if (!f16) { f16 = { n: 'Bein Sports 5', u: '' }; A.filas.push(f16); }
      f16.u = 'dramalive://play?id_live=' + encodeURIComponent('live_tv_beinsport5');
      A.corriendo = false; A.adWatched = 0; A.priorFijo = 0;
      let nGlasbi = 0, nGlbr16 = 0, marcadorVisto = '';
      win.fetch = globalThis.fetch = async (u, opt) => {
        const url16 = String(u);
        const q16 = unwire(opt && opt.body);
        if (url16.indexOf('glasbi') >= 0) {
          nGlasbi++;
          if (q16 && q16.id !== undefined) return { ok: true, status: 201, text: async () => wire({ result: 0, live: { url: MARCA, agent: 'redirect' } }) };
          return { ok: true, status: 201, text: async () => wire({ result: 1 }) };
        }
        nGlbr16++;
        marcadorVisto = q16 && q16.url ? String(q16.url) : '';
        if (marcadorVisto.indexOf('.LS.') >= 0 && q16.agent === 'redirect') {
          return { ok: true, status: 201, text: async () => wire({ result: 0, data: { agent: 'advanced', url: JSON.stringify(AVANZADO) } }) };
        }
        return { ok: true, status: 201, text: async () => wire({ result: 0, data: { url: 'http://file.twoapistack.work/stream/update.mp4' } }) };
      };
      let err16 = null;
      try { await T.A.cosecha('t16'); } catch (e) { err16 = e; }
      glasbiOk = nGlasbi >= 1 && marcadorVisto.indexOf('.LS.') >= 0
        && String(f16.u || '').indexOf('/pages/fnwzpoqozpru/index.html') >= 0
        && A.priorFijo === 1
        && hitos.some((m) => m.indexOf('glasbi: marcador') === 0);
      console.log(glasbiOk ? 'GLASBI29_OK' : 'GLASBI29_FAIL', 'glasbi=' + nGlasbi, 'glbr=' + nGlbr16,
        'marcador=' + marcadorVisto.slice(0, 34), 'u=' + String(f16.u || '').slice(0, 62), 'priorFijo=' + A.priorFijo, err16 ? String(err16.message) : '');
    }
    const todo = seedOk && junkOk && trafOk && hitoSeed && arr && arranc && finc && abort && desbloqueo && adOk && glasbiOk;
    console.log(todo ? 'REPRO29 TODO VERDE' : 'REPRO29 ROJO');
    process.exit(todo ? 0 : 1);
  } catch (e) { console.log('TRAFFIC_ERR:', e && (e.stack || e.message)); process.exit(1); }
})();
