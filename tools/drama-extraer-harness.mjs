import { webcrypto as crypto } from 'node:crypto';
import fs from 'node:fs';
const KEY = '43fc9b1a3f854049';
const kb = () => { const o = new Uint8Array(16); for (let i = 0; i < 16; i++) o[i] = parseInt(KEY.substr(i * 2, 2), 16); return o };
const k = await crypto.subtle.importKey('raw', kb(), { name: 'AES-CBC' }, false, ['encrypt', 'decrypt']);
const enc = async (obj) => { const iv = crypto.getRandomValues(new Uint8Array(16));
  const ct = await crypto.subtle.encrypt({ name: 'AES-CBC', iv }, k, new TextEncoder().encode(JSON.stringify(obj)));
  const b64 = (u) => Buffer.from(u).toString('base64');
  return b64(new Uint8Array(ct)) + ':' + b64(iv); };
// la respuesta tal y como la manda su API: lista de canales con server+path (formula medida)
const RESPUESTA = await enc({ live_topics: [ { name: 'beIN XTRA AR', server: 'rap5.rapiddatahost.shop', path: 'sport/a11', group: 'Arabes' },
  { name: 'Peru 1', server: '190.93.224.42', path: 'live/p1', group: 'Peru' } ] });
// peticion del cliente (para que el gancho aprenda la plantilla)
const PETICION = await enc({ user_id: 'u77', device_id: 'd1', type: 'live' });
globalThis.window = globalThis;
let peticiones = [];
globalThis.fetch = async (u, o) => { peticiones.push({ u: String(u), body: o && o.body }); return { status: 200, text: async () => RESPUESTA, clone: () => ({ text: async () => RESPUESTA }) }; };
const codigo = fs.readFileSync('tools/drama-extraer.js', 'utf8');
new Function(codigo)();                       // instala el gancho, sin DOM
await fetch('https://main.fastconnections.online/api/live/livedrama/v13.0.0/glt', { method: 'POST', body: PETICION });
await new Promise(r => setTimeout(r, 350));    // dejar correr los .then
const DE = globalThis.DramaExport;
const filas = DE.tabla();
console.error = () => {};
const ok = filas.length === 2 && filas[0].u === 'https://rap5.rapiddatahost.shop/sport/a11/index.html' && filas[1].u === 'https://190.93.224.42/live/p1/index.html';
console.log('harness: filas=%d %s', filas.length, ok ? 'OK' : 'MAL ' + JSON.stringify(filas));
// replay: con plantilla aprendida, la app exportada debe re-POSTear glt cifrando {"type":"sports"}
await DE.replay('glt', { type: 'sports' });
const ultima = peticiones[peticiones.length - 1];
const ct = ultima.body.split(':')[0].length;
console.log('replay: url=%s body-cifrado=%dB tipo=%s', ultima.u.split('/').pop(), ct, ct > 20 ? 'ok' : 'MAL');
// m3u sin DOM: el blob/download fallaria; probamos la construccion via json() con URL.createObjectURL stub
globalThis.document = { body: { appendChild(){} }, createElement: () => ({ style: {}, set innerHTML(v){}, click(){}, remove(){} }), getElementById: () => null };
globalThis.URL.createObjectURL = () => 'blob:x'; globalThis.Blob = class { constructor(t){ this.t = t } };
globalThis.Anchor = undefined;
const antes = filas.length;
DE.__txt = null;
const txt = DE.m3u();
console.log('m3u: lineas=%d canales=%d', txt.trim().split('\n').length, antes);
console.log(txt);
console.log(ok && ct > 20 && txt.includes('#EXTINF') ? 'TODO-OK' : 'FALLO');
process.exit(0);
