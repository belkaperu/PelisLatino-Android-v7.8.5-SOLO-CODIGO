// v3.2.44 — prueba viva de la auto-conexion: glt con la plantilla EMPOTRADA tal cual la
// construye DramaSesion.gltInicial() (user_id = epoch us 16 digitos, device_id uuid
// fresh, KEY_ACTIVATED_TYPE 232425). UNA request nomas: el edge blaquea al que repide.
import crypto from 'node:crypto';
const KEY = Buffer.from('43fc9b1a3f854049', 'utf8');
const wire = (obj) => {
  const pt = Buffer.from(JSON.stringify(obj));
  const pad = 16 - (pt.length % 16);
  const iv = crypto.randomBytes(16);
  const c = crypto.createCipheriv('aes-128-cbc', KEY, iv);
  const ct = Buffer.concat([c.update(Buffer.concat([pt, Buffer.alloc(pad, pad)])), c.final()]);
  return ct.toString('base64') + ':' + iv.toString('base64');
};
const unwire = (txt) => {
  const parts = txt.trim().split(':');
  if (parts.length < 2) return null;
  const iv = Buffer.from(parts.pop(), 'base64');
  const d = crypto.createDecipheriv('aes-128-cbc', KEY, iv);
  const out = Buffer.concat([d.update(Buffer.from(parts.join(':'), 'base64')), d.final()]);
  return JSON.parse(out.subarray(0, out.length - out[out.length - 1]).toString('utf8'));
};
const glt = {
  user_id: (Date.now() * 1000) + '_notloggedin.com_dramalive3',
  device_id: crypto.randomUUID(),
  device_api: 'chrome/154.0.8037.57',
  version_name: 'web_241',
  language: 'en',
  timezone: 'America/Lima',
  device_type: 'phone',
  os_platform: 'web',
  os: 'android',
  KEY_ACTIVATED_TYPE: '232425',
  store: 'web', isStoreVersion: true, isPremium: false, isCoupon_active: false, hideAds: false,
  appCount: JSON.stringify({ runCount: 0, adsLoaded: 0, adsShowed: 0, adsFailed: 0 }),
  mainServer: 'https://main.fastconnections.online/api/live/livedrama/v13.0.0/',
  type: 'tv',
};
const UA = 'Mozilla/5.0 (Linux; Android 15; SM-A156M) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Mobile Safari/537.36';
const r = await fetch('https://li.highperformanceconnect.shop/api/live/livedrama/v13.0.0/glt', {
  method: 'POST',
  headers: { 'User-Agent': UA, 'Content-Type': 'text/plain;charset=UTF-8', 'Origin': 'https://web.dramalive.cc', 'Referer': 'https://web.dramalive.cc/' },
  body: wire(glt),
});
const txt = await r.text();
let claro = null;
try { claro = unwire(txt); } catch { }
if (!claro) { console.log('AUTO-GLT: HTTP', r.status, '| ilegible/blank (edge):', JSON.stringify(txt.slice(0, 60))); process.exit(3); }
const topics = (claro.live_topics || []).map(t => t.id_topic || t);
console.log('AUTO-GLT_OK status=' + r.status, '| result=' + claro.result, '| topics=' + topics.length, '=>', topics.slice(0, 8).join(','));
