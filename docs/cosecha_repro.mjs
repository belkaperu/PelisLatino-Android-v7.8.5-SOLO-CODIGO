import { readFileSync } from 'fs';
import { resolve, dirname } from 'path';
import { fileURLToPath } from 'url';
// Cargar el drama_auto.js REAL con un navegador de mentirijillas. (ruta RELATIVA
// al repo: el test de tests_python y cualquier clon pueden correrlo igual.)
const src = readFileSync(resolve(dirname(fileURLToPath(import.meta.url)), '../app/src/main/assets/drama_auto.js'), 'utf8');
const logs = [];
const win = {
  addEventListener() {}, removeEventListener() {},
  location: { href: 'https://web.dramalive.cc/', reload() {} },
  navigator: { userAgent: 'node-repro' },
  localStorage: { getItem: () => null, setItem() {}, removeItem() {} },
  setInterval: () => 0, setTimeout: (f, ms) => setTimeout0(f, ms), // el sleep del JS necesita un timer DE VERDAD
  console,
  AndroidDrama: null,
};
win.fetch = undefined; // sin fetch original: cosechaRun usa el pedir inyectado
globalThis.window = win;
globalThis.document = { readyState: 'complete', addEventListener() {}, createElement: () => ({}) };
globalThis.location = win.location; globalThis.navigator = win.navigator;
globalThis.localStorage = win.localStorage;
const setTimeout0 = setTimeout;
globalThis.setInterval = () => 0;
globalThis.setTimeout = (f, ms) => setTimeout0(f, ms);
globalThis.clearInterval = () => {}; globalThis.clearTimeout = () => {};
try { eval(src); } catch (e) { console.log('EVAL_ERR:', e && (e.stack || e.message || String(e))); process.exit(1); }
const T = win.__dramaAutoTest;
if (!T || !T.cosechaRun) throw new Error('no exporta cosechaRun: ' + Object.keys(T || {}));
const A = T.A;
A.filas.length = 0;
A.filas.push(
  { n: 'Uno', u: 'dramalive://play?id_live=id1&u=1&a=all_streams_redirect' },
  { n: 'Dos', u: 'dramalive://play?id_live=id2&u=1&a=all_streams_redirect' },
  { n: 'Tres', u: 'dramalive://play?id_live=id3&u=0&a=x' },
  { n: 'YaCurado', u: 'https://fas6/real/antes.html' },
);
let calls = [];
const t0 = Date.now();
let res; try { res = await T.cosechaRun(A.filas, async (fila, p) => {
  calls.push([fila.n, p.id_live, p.u, p.a]);
  if (fila.n === 'Uno') return { result: 0, data: { url: 'https://fas6.fastspeedai.cfd/pages/uno/index.html' } };
  if (fila.n === 'Dos') return { result: 0, data: { url: 'http://file.twoapistack.work/stream/update.mp4' } };
  if (fila.n === 'Tres') return null; // respuesta vacia (rate limit)
  return null;
}); } catch (e) { console.log('RUN_ERR:', e && (e.stack || e.message)); process.exit(1); }
const ms = Date.now() - t0;
console.log('RES', JSON.stringify(res), 'ms=' + ms);
console.log('LLAMADAS', JSON.stringify(calls));
console.log('FILAS', JSON.stringify(A.filas.map(f => f.u)));
console.log('FIJOS', A.fijos, 'SUCIO', A.sucio > 0);
const ok = res.hechos === 3 && res.ok === 1 && res.suenos === 1 && res.blancos === 1
  && A.filas[0].u === 'https://fas6.fastspeedai.cfd/pages/uno/index.html'
  && A.filas[1].u.startsWith('dramalive://') // el sueno NO se escribe
  && A.filas[2].u.startsWith('dramalive://')
  && A.filas[3].u === 'https://fas6/real/antes.html' // la ya curada ni se pide
  && calls.length === 3 && A.fijos === 1 && ms >= 900 + 1400 + 6000 - 100; // cadencias reales respetadas
console.log(ok ? 'REPRO_OK' : 'REPRO_FAIL');
// paramsFila aparte
const p = T.paramsFila('dramalive://play?id_live=x%201&u=2&a=all%5Fstreams&t=t9');
console.log('PARAMS', JSON.stringify(p), p.id_live === 'x 1' && p.u === '2' && p.a === 'all_streams' ? 'PARAMS_OK' : 'PARAMS_FAIL');
// ---- v3.2.13: seed de glbrReq + ordenPrior + onFix ----
// (a) seed: arrancar el asset con __DRAMA_SESION sembrada debe popular A.plant.glbrReq
win.DramaAuto = undefined;
win.__DRAMA_SESION = { plant: {
  glt: { user_id: 'u-semilla', type: 'tv' },
  glbrReq: { token: 'T', rc: 1 }   // v3.2.25: veraz — el glbr real NO trae user_id (antes el fixture lo inventaba y tapaba el bug)
}, urlGlbr: 'https://re.example.test/redirect/glbr', wire: 'ctiv' };
eval(src);
const A2 = win.__dramaAutoTest.A;
console.log('SEED glbrReq?', JSON.stringify(A2.plant.glbrReq), 'urlGlbr?', A2.urlGlbr, 'wire?', A2.crackReq);
const seedOk = A2.plant.glbrReq && A2.plant.glbrReq.token === 'T'
  && A2.urlGlbr === 'https://re.example.test/redirect/glbr' && A2.crackReq === 'ctiv';
console.log(seedOk ? 'SEED_OK' : 'SEED_FAIL');
// (b) ordenPrior estable + onFix en cosechaRun
const T2 = win.__dramaAutoTest;
const filas = [
  { u: 'dramalive://play?id_live=a&u=1&a=x' },
  { u: 'dramalive://play?id_live=b&u=1&a=x' },
  { u: 'dramalive://play?id_live=c%20d&u=1&a=x' },
];
const ord = T2.ordenPrior(filas, 'c d');
const ordenOk = ord[0].u.indexOf('id_live=c%20d') >= 0 && ord[1].u.indexOf('id_live=a') >= 0 && ord[2].u.indexOf('id_live=b') >= 0;
console.log(ordenOk ? 'ORDEN_OK' : 'ORDEN_FAIL', ord.map(f => f.u.slice(19, 26)).join(','));
const fixes = [];
const res2 = await T2.cosechaRun(ord, async (fila, p) => ({ result: 0, data: { url: 'https://fas6/' + p.id_live.replace(/\s/g, '_') } }),
  (fila, p, du) => fixes.push([p.id_live, du]));
console.log('FIXES', JSON.stringify(fixes));
const fixOk = fixes.length === 3 && fixes[0][0] === 'c d'
  && fixes.every(([, du], i) => i === 0 ? du === 'https://fas6/c_d' : true);
console.log(res2.ok === 3 && fixOk ? 'ONFIX_OK' : 'ONFIX_FAIL');
// ---- v3.2.14: asegurarPrior — el canal jugado entra SIEMPRE a la cosecha ----
const T3 = win.__dramaAutoTest;
const pendA = [{ u: 'dramalive://play?id_live=aa&u=1', n: 'AA' }];
const todasA = pendA.slice();
T3.asegurarPrior(pendA, todasA, 'b b', 'Canal BB', 'http://l/b.png', 'G1');
const sintOk = pendA.length === 2 && pendA[0].n === 'Canal BB'
  && pendA[0].u === 'dramalive://play?id_live=b%20b&u=1&a=all_streams_redirect'
  && pendA[0].l === 'http://l/b.png' && pendA[0].g === 'G1' && todasA.length === 2;
// ya presente (id codificado en la fila): no se duplica
const pendB = [{ u: 'dramalive://play?id_live=c%20c&u=1', n: 'CC' }];
T3.asegurarPrior(pendB, pendB, 'c c', 'Otro');
const dupOk = pendB.length === 1 && pendB[0].n === 'CC';
// sin nombre: id como fallback (nunca n=null — desdePuente descartar la fila)
const pendC = [];
T3.asegurarPrior(pendC, pendC, 'd1', null, null, null);
const fbOk = pendC.length === 1 && pendC[0].n === 'd1';
console.log(sintOk && dupOk && fbOk ? 'ASEGURAR_OK' : 'ASEGURAR_FAIL', sintOk, dupOk, fbOk);
// el sintético pasa por paramsFila igual que una fila real
const pF = T3.paramsFila(pendA[0].u);
console.log(pF.id_live === 'b b' && pF.u === '1' && pF.a === 'all_streams_redirect' ? 'SINT_PARAMS_OK' : 'SINT_PARAMS_FAIL');
