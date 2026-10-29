// docs/crypto_interop.mjs — cifrado REAL del asset para el test Python
// (tests_python/test_curacion_v3216.py). Carga drama_auto.js con el mock de
// navegador y emite por stdout un JSON: [{obj, wire}, …] donde wire = encPara(obj,'ctiv')
// — el alambre del sitio `b64std(ct):b64std(iv)`, AES-CBC PKCS7, KEY ASCII
// '43fc9b1a3f854049'. Python lo desencripta con SU propia AES (independiente):
// si ambos coinciden, el formato y la clave del asset estan verificados por dos vias.
import { readFileSync } from 'fs';
import { resolve, dirname } from 'path';
import { fileURLToPath } from 'url';

const here = dirname(fileURLToPath(import.meta.url));
const src = readFileSync(resolve(here, '../app/src/main/assets/drama_auto.js'), 'utf8');

const setTimeout0 = setTimeout;
const win = {
  addEventListener() {}, removeEventListener() {},
  location: { href: 'https://web.dramalive.cc/', reload() {} },
  navigator: { userAgent: 'node-interop' },
  localStorage: { getItem: () => null, setItem() {}, removeItem() {} },
  setInterval: () => 0, setTimeout: (f, ms) => setTimeout0(f, ms), // timer DE VERDAD (lección del repro)
  console,
  AndroidDrama: null,
};
win.fetch = undefined;
globalThis.window = win;
globalThis.document = { readyState: 'complete', addEventListener() {}, createElement: () => ({}) };
globalThis.location = win.location; globalThis.navigator = win.navigator; globalThis.localStorage = win.localStorage;
globalThis.setInterval = () => 0;
globalThis.setTimeout = (f, ms) => setTimeout0(f, ms);
globalThis.clearInterval = () => {}; globalThis.clearTimeout = () => {};

try { eval(src); } catch (e) { console.error('EVAL_ERR:', e && (e.stack || e.message)); process.exit(1); }

const T = win.__dramaAutoTest;
if (!T || !T.encPara) { console.error('EL_ASSET_NO_EXPORTA_encPara'); process.exit(1); }

const objs = [
  { id: 'live_tv_bein', url: '1', agent: 'all_streams_redirect', user_id: 'py-interop', token: 'T-ÿé' },
  { id: 'x y', url: '0' },
  { result: 0, data: { url: 'http://file.twoapistack.work/stream/update.mp4' } },
];
const out = [];
for (const o of objs) out.push({ obj: o, wire: await T.encPara(o, 'ctiv') });
process.stdout.write(JSON.stringify(out));
