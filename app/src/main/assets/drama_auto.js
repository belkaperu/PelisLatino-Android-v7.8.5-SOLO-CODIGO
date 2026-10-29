/*
 * drama_auto.js v3.2.29 — la jugada VERDADERA: glasbi -> glbr(redirect) -> advanced
 *
 * Que probo el volcar v3.1 del usuario (medido, dos sesiones):
 *  - EL PANEL LE CONTESTA DATOS REALES: POST glt a li.highperformanceconnect.shop
 *    -> HTTP 201 con 43 KB cuyo claro (AES-128-CBC clave del bundle, leido bloque
 *    a bloque offline) es `..."result":0,"message":{"en":"operation succeeded"...`
 *  - el user_id web es `567016861000_notloggedin.com_dramalive3` (el sitio funciona
 *    "anonimizado": la sesion no exige login para el catalogo live).
 *  - el alambre NO es b64(ct):b64(iv) del bundle android: LONGITUDES con len%4==1
 *    (b64 SIN padding) y el json empieza alineado despues del primer bloque — hay
 *    un prefijo/iv de 16 (o mas) que el claro del request/response desplaza.
 * Por eso v3.2.1 trae un AES-128 PROPIO (tabla, ECB por bloque — sutil no sirve,
 * exige ct alineado y padding valido) y:
 *  - decLibre(): normaliza b64 (url, sin '='), prueba disposiciones razonables
 *    (iv ceros / al frente / segundo bloque / al final), descifra TODOS los
 *    bloques completos y busca el primer '{' o '[' parseable — el primer bloque
 *    puede estar corrupto y AUN ASI se lee el resto de un JSON de 43 KB;
 *  - las RESPUESTAS del propio sitio se leen sin saber el formato exacto -> ver()
 *    recolecta canales de lo que el usuario navega (el modo coleccionista: si
 *    el user toca Live/Sports/Kids..., la app archiva cada lista);
 *  - el REPLAY aprende la disposicion con la que descifro el request del sitio y
 *    re-usa los bytes de prefijo del body capturado; si falla, no pasa nada: la
 *    coleccion por navegacion sigue llenando.
 * Entrega (AndroidDrama.entregar): forma v3.1 + url + wire + crack (la disposicion
 * ganadora, para persistirla en la sesion y que DramaSesion la use headless).
 */
(function () {
  'use strict';
  if (window.DramaAuto) { if (window.DramaAuto.hola) window.DramaAuto.hola(); return; } // v3.2.11: cualquier instance viva basta (la guarda por numero de version se rompia sola al bumpearla y apilaba 4 hooks — tormenta de 98 req en 13 s, logcat 16:07)
  var T0 = Date.now();
  var A = window.DramaAuto = {
    v: 349, filas: [], vis: Object.create(null), plant: {},
    req: 0, decOk: 0, corriendo: false, hechoAuto: 0, sucio: 0, ultEnvio: -1e9,
    urlGlt: null, urlGlbt: null, urlGlbr: null, hdrs: null, hostsReq: Object.create(null),
    topics: [], glbrOk: 0,
    epAprendido: null, log: [], wsFrames: 0, erroresJs: 0, diagTxt: 'arrancando',
    crack: null, crackReq: null, muestraReq: '', muestraResp: '', muestraLargoReq: -1, muestraLargoResp: -1,
    bodyCrudo: null, respCrudo: null
  };
  function L(m) { try { if (A.log.length > 300) return; A.log.push('+' + Math.round((Date.now() - T0) / 100) / 10 + 's ' + String(m).slice(0, 220)); } catch (e) {} }
  window.addEventListener('error', function (ev) { A.erroresJs++; if (A.erroresJs <= 6) L('errorJS: ' + (ev.message || 'sin msg') + ' @ ' + String(ev.filename || '').split('/').pop()); });
  window.addEventListener('unhandledrejection', function (ev) { A.erroresJs++; if (A.erroresJs <= 8) L('promiseRej: ' + String((ev.reason && (ev.reason.message || ev.reason)) || '?')); });

  /* ============ AES-128 puro (ECB por bloque) ============ */
  var AES = (function () {
    var exp = new Uint8Array(512), log = new Uint8Array(256), Sbox = new Uint8Array(256), Ibox = new Uint8Array(256);
    var x = 1;
    for (var i = 0; i < 255; i++) { exp[i] = x; log[x] = i; x = (x << 1) ^ x; if (x & 256) x = (x & 255) ^ 0x1b; x &= 255; }
    for (i = 255; i < 512; i++) exp[i] = exp[i - 255];
    function rotl(v, n) { return ((v << n) | (v >> (8 - n))) & 255; }
    for (var a = 0; a < 256; a++) {
      var inv = a ? exp[255 - log[a]] : 0;
      var s = inv ^ rotl(inv, 1) ^ rotl(inv, 2) ^ rotl(inv, 3) ^ rotl(inv, 4) ^ 0x63;
      Sbox[a] = s; Ibox[s] = a;
    }
    function mul(pa, pb) { if (!pa || !pb) return 0; return exp[log[pa] + log[pb]]; }
    function expand(key) {
      var W = new Uint8Array(176); W.set(key); var rc = 1;
      for (var i2 = 4; i2 < 44; i2++) {
        var t0 = W[4 * (i2 - 1)], t1 = W[4 * (i2 - 1) + 1], t2 = W[4 * (i2 - 1) + 2], t3 = W[4 * (i2 - 1) + 3];
        if (i2 % 4 === 0) { var s0 = Sbox[t1] ^ rc; t1 = Sbox[t2]; t2 = Sbox[t3]; t3 = Sbox[t0]; t0 = s0; rc = (rc << 1) ^ ((rc & 0x80) ? 0x1b : 0); rc &= 255; }
        W[4 * i2] = W[4 * (i2 - 4)] ^ t0; W[4 * i2 + 1] = W[4 * (i2 - 4) + 1] ^ t1;
        W[4 * i2 + 2] = W[4 * (i2 - 4) + 2] ^ t2; W[4 * i2 + 3] = W[4 * (i2 - 4) + 3] ^ t3;
      }
      return W;
    }
    function encBlock(W, inp, o) {
      var s = new Uint8Array(16), i3, j, r, u;
      for (i3 = 0; i3 < 16; i3++) s[i3] = inp[i3] ^ W[i3];
      for (r = 1; r <= 10; r++) {
        for (i3 = 0; i3 < 16; i3++) s[i3] = Sbox[s[i3]];
        u = new Uint8Array(16);
        for (j = 0; j < 4; j++) for (i3 = 0; i3 < 4; i3++) u[4 * j + i3] = s[4 * ((i3 + j) & 3) + i3];
        if (r < 10) {
          for (j = 0; j < 4; j++) {
            var a2 = u[4 * j], b2 = u[4 * j + 1], c2 = u[4 * j + 2], d2 = u[4 * j + 3];
            var sa = a2 ^ b2 ^ c2 ^ d2;
            u[4 * j] = a2 ^ sa ^ mul(a2 ^ b2, 2); u[4 * j + 1] = b2 ^ sa ^ mul(b2 ^ c2, 2);
            u[4 * j + 2] = c2 ^ sa ^ mul(c2 ^ d2, 2); u[4 * j + 3] = d2 ^ sa ^ mul(d2 ^ a2, 2);
          }
        }
        for (i3 = 0; i3 < 16; i3++) s[i3] = u[i3] ^ W[16 * r + i3];
      }
      o.set(s, 0);
    }
    function decBlock(W, inp, o) {
      var s = new Uint8Array(16), i4, j, r, u;
      for (i4 = 0; i4 < 16; i4++) s[i4] = inp[i4] ^ W[160 + i4];
      for (r = 9; r >= 0; r--) {
        for (i4 = 0; i4 < 16; i4++) s[i4] = Ibox[s[i4]];
        u = new Uint8Array(16);
        for (j = 0; j < 4; j++) for (i4 = 0; i4 < 4; i4++) u[4 * j + i4] = s[4 * ((j - i4 + 4) & 3) + i4];
        s = u;
        for (i4 = 0; i4 < 16; i4++) s[i4] ^= W[16 * r + i4];
        if (r > 0) {
          for (j = 0; j < 4; j++) {
            var a3 = s[4 * j], b3 = s[4 * j + 1], c3 = s[4 * j + 2], d3 = s[4 * j + 3];
            s[4 * j] = mul(a3, 14) ^ mul(b3, 11) ^ mul(c3, 13) ^ mul(d3, 9);
            s[4 * j + 1] = mul(a3, 9) ^ mul(b3, 14) ^ mul(c3, 11) ^ mul(d3, 13);
            s[4 * j + 2] = mul(a3, 13) ^ mul(b3, 9) ^ mul(c3, 14) ^ mul(d3, 11);
            s[4 * j + 3] = mul(a3, 11) ^ mul(b3, 13) ^ mul(c3, 9) ^ mul(d3, 14);
          }
        }
      }
      o.set(s, 0);
    }
    return { expand: expand, encBlock: encBlock, decBlock: decBlock, mul: mul, Sbox: Sbox, Ibox: Ibox, exp: exp, log: log };
  })();
  var KEYB = new TextEncoder().encode('43fc9b1a3f854049'); // clave ASCII de 16 bytes (como usa el sitio), NO hex-decodificada
  var WK = AES.expand(KEYB);

  function b64norm(s) {
    s = String(s).replace(/\s+/g, '').replace(/-/g, '+').replace(/_/g, '/');
    if (s.length % 4 === 1) s = s.slice(0, -1);
    while (s.length % 4) s += '=';
    return s;
  }
  function b64dec(s) {
    var b = String(s).replace(/[^A-Za-z0-9+/=]/g, '');
    for (var q = 0; q < 3; q++) {
      try {
        var bin = atob(b);
        var u = new Uint8Array(bin.length);
        for (var i = 0; i < bin.length; i++) u[i] = bin.charCodeAt(i);
        return u;
      } catch (e) { b = b.slice(0, b.length - 1); }
    }
    return null;
  }
  function b64std(u) { var s = ''; for (var i = 0; i < u.length; i++) s += String.fromCharCode(u[i]); return btoa(s); }
  function b64enc(u) { var s = ''; for (var i = 0; i < u.length; i++) s += String.fromCharCode(u[i]); return btoa(s).replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_'); }
  function txtOf(u) { return new TextDecoder('utf-8', { fatal: false }).decode(u); }
  async function gunzip(u) { try { if (!u || u[0] !== 31 || u[1] !== 139) return null; var ds = new DecompressionStream('gzip'); var w = ds.writable.getWriter(); w.write(u); w.close(); return txtOf(new Uint8Array(await new Response(ds.readable).arrayBuffer())); } catch (e) { return null; } }
  function cbcDec(raw, iv, from, to) {
    var out = new Uint8Array(to - from), prev = iv, blk = new Uint8Array(16), o = new Uint8Array(16);
    for (var i = from; i + 16 <= to; i += 16) {
      blk.set(raw.subarray(i, i + 16));
      AES.decBlock(WK, blk, o);
      for (var j = 0; j < 16; j++) out[i - from + j] = o[j] ^ prev[j];
      prev = raw.subarray(i, i + 16);
    }
    return out;
  }
  function cbcEnc(plain, iv) {
    var pad = 16 - (plain.length % 16); if (pad === 0) pad = 16;
    var data = new Uint8Array(plain.length + pad); data.set(plain);
    for (var i = plain.length; i < data.length; i++) data[i] = pad;
    var out = new Uint8Array(data.length), prev = iv, o = new Uint8Array(16);
    for (i = 0; i < data.length; i += 16) {
      for (var j = 0; j < 16; j++) o[j] = data[i + j] ^ prev[j];
      AES.encBlock(WK, o, blk16); out.set(blk16, i); prev = blk16.slice(0);
    }
    return out;
  }
  var blk16 = new Uint8Array(16);
  /* cierra el corchete balanceado desde i (respeta strings); -1 si no cierra */
  function cerrar(s, i) {
    var abre = s.charCodeAt(i), cierra = abre === 123 ? 125 : 93;
    var prof = 0, enStr = false, esc = false;
    for (var k = i; k < s.length; k++) {
      var c = s.charCodeAt(k);
      if (esc) { esc = false; continue; }
      if (c === 92) { if (enStr) esc = true; continue; }
      if (c === 34) { enStr = !enStr; continue; }
      if (enStr) continue;
      if (c === abre) prof++;
      else if (c === cierra) { prof--; if (prof === 0) return k; }
    }
    return -1;
  }
  function jsonDesde(txt) {
    if (!txt) return null;
    var s = String(txt), best = null, bestLen = 0, probados = 0;
    for (var k = 0; k < s.length && k < 1200 && probados < 14; k++) {
      var c2 = s.charCodeAt(k);
      if (c2 !== 123 && c2 !== 91) continue;
      probados++;
      // resto completo (json intacto desde k hasta el final)
      try { var o = JSON.parse(s.slice(k)); if (o && typeof o === 'object') { var l0 = JSON.stringify(o).length; if (l0 > bestLen) { best = o; bestLen = l0; } if (bestLen > 400) return best; } } catch (e) {}
      // rescate: bloque balanceado desde k (tapa primer bloque corrupto con '{' perdido)
      var fin = cerrar(s, k);
      if (fin > k + 7) {
        try { var o2 = JSON.parse(s.slice(k, fin + 1)); if (o2 && typeof o2 === 'object') { var l2 = fin - k; if (l2 > bestLen) { best = o2; bestLen = l2; } } } catch (e) {}
      }
    }
    return best;
  }
  /* disposiciones: prefijo P bytes fijos, iv ∈ cero|alFrente|segundo|final. El ORDEN
     importa: se prueba primero la que reproduce el wire medido (iv al frente) para
     que el REPLAY aprenda una disposicion re-criptable y no la primera que pasa por
     accidente. `stricto` = el claro arranca en el '{' exterior => formato completo,
     seguro para cifrar de vuelta al servidor. */
  var DISPO = [
    { p: 0, iv: 'frente' }, { p: 16, iv: 'frente' }, { p: 0, iv: 'cero' }, { p: 16, iv: 'cero' },
    { p: 16, iv: 'segundo' }, { p: 2, iv: 'frente' }, { p: 0, iv: 'final' }, { p: 32, iv: 'cero' }
  ];
  async function decLibre(txt) {
    var raw = null;
    var t = String(txt == null ? '' : txt).trim();
    if (!t) return null;
    if (t.charCodeAt(0) === 34 && t.charCodeAt(t.length - 1) === 34) { try { t = JSON.parse(t); } catch (e) {} }
    if (typeof t !== 'string') return null;
    var o0 = jsonDesde(t); if (o0) return { json: o0, dispo: 'plano', stricto: t.charCodeAt(0) === 123 || t.charCodeAt(0) === 91 };
    var ci = t.lastIndexOf(':');
    if (ci > 20) { // ctiv MEDIDO en el request real del usuario: b64std(ct):b64std(iv)
      try {
        var rc = b64dec(t.slice(0, ci)), ri = b64dec(t.slice(ci + 1));
        if (ri && ri.length === 16 && rc && rc.length >= 32 && rc.length % 16 === 0) {
          var sc = txtOf(cbcDec(rc, ri, 0, rc.length)).replace(/[\u0000-\u0008\u000e-\u001f]+$/, '');
          var oc = jsonDesde(sc);
          if (oc) return { json: oc, dispo: 'ctiv', stricto: sc.charCodeAt(0) === 123 };
        }
      } catch (e) {}
    }
    try { raw = b64dec(b64norm(t)); } catch (e) { return null; }
    if (!raw || raw.length < 40) return null;
    var gz = await gunzip(raw); if (gz) { var og = jsonDesde(gz); if (og) return { json: og, dispo: 'gzip', stricto: false }; }
    for (var d = 0; d < DISPO.length; d++) {
      var dsp = DISPO[d], iv, from = dsp.p + 16, to = raw.length;
      if (dsp.iv === 'cero') { iv = new Uint8Array(16); from = dsp.p; }
      else if (dsp.iv === 'frente' && raw.length >= dsp.p + 16) iv = raw.subarray(dsp.p, dsp.p + 16);
      else if (dsp.iv === 'segundo') iv = raw.subarray(dsp.p + 16, dsp.p + 32);
      else if (dsp.iv === 'final') { iv = raw.subarray(raw.length - 16, raw.length); to = raw.length - 16; }
      else continue;
      if (dsp.iv === 'segundo') from = dsp.p + 32;
      if (to - from < 32) continue;
      var pt = cbcDec(raw, iv, from, to);
      var s = txtOf(pt).replace(/[\u0000-\u0008\u000e-\u001f]+$/, ''); // quita PKCS7/colas binarias
      var o = jsonDesde(s);
      var st = s.charCodeAt(0) === 123 || s.charCodeAt(0) === 91;
      if (!o) { var g2 = await gunzip(pt); if (g2) { o = jsonDesde(g2); st = false; } }
      if (o) return { json: o, dispo: 'p' + dsp.p + '+' + dsp.iv, stricto: st };
    }
    return null;
  }
  /* construir body para replay: invertir la disposicion aprendida; prefijo = copia del body real */
  async function encPara(obj, dispo) {
    var plain = new TextEncoder().encode(JSON.stringify(obj));
    var iv = crypto.getRandomValues(new Uint8Array(16));
    if (dispo === 'ctiv' || dispo === 'p0+ctiv') {
      var ivc = crypto.getRandomValues(new Uint8Array(16));
      return b64std(cbcEnc(plain, ivc)) + ':' + b64std(ivc);
    }
    if (dispo === 'p0+cero' || !dispo) { return b64enc(cbcEnc(plain, new Uint8Array(16))); }
    if (dispo === 'p16+cero') {
      var ct0 = cbcEnc(plain, new Uint8Array(16));
      var pre = A.bodyCrudo && A.bodyCrudo.length >= 16 ? A.bodyCrudo : new Uint8Array(16);
      var b = new Uint8Array(16 + ct0.length); b.set(pre.subarray(0, 16)); b.set(ct0, 16); return b64enc(b);
    }
    if (dispo === 'p16+frente') {
      var ct2 = cbcEnc(plain, iv);
      var pre2 = A.bodyCrudo && A.bodyCrudo.length >= 32 ? A.bodyCrudo : new Uint8Array(32);
      var c = new Uint8Array(32 + ct2.length); c.set(pre2.subarray(0, 16)); c.set(iv, 16); c.set(ct2, 32); return b64enc(c);
    }
    var ct = cbcEnc(plain, iv);
    var a = new Uint8Array(16 + ct.length); a.set(iv); a.set(ct, 16); return b64enc(a);
  }

  /* ============ recolector ============ */
  function esRuido(u) { return /\.(js|css|png|jpe?g|gif|webp|svg|ico|woff2?|ttf|eot|map|mp4|webm|m3u8|ts)(\?|#|$)/i.test(String(u)) && !/api|glt|glbt|glasbi|\.php|rum/i.test(String(u)); }
  function hostDe(u) { try { return new URL(String(u), location.href).host; } catch (e) { return '?'; } }
  function contarHost(u) { try { var h = new URL(String(u), location.href).host; A.hostsReq[h] = (A.hostsReq[h] || 0) + 1; } catch (e) {} }
  function rutaDe(u) { try { var p = new URL(String(u), location.href).pathname; return p.split('/').pop() || 'root'; } catch (e) { return 'root'; } }
  function textoCuerpo(b) {
    if (b == null) return null;
    if (typeof b === 'string') return b;
    try {
      if (b instanceof ArrayBuffer) return txtOf(new Uint8Array(b));
      if (b.buffer instanceof ArrayBuffer) return txtOf(new Uint8Array(b.buffer));
      if (typeof URLSearchParams !== 'undefined' && b instanceof URLSearchParams) return b.toString();
    } catch (e) {}
    try { return String(b); } catch (e) { return null; }
  }
  function cuerpoResp(x) {
    try {
      var rt = x.responseType;
      if (rt === 'json') return x.response ? JSON.stringify(x.response) : null;
      if (rt === 'arraybuffer' && x.response) return txtOf(new Uint8Array(x.response));
      if (rt === 'blob') return null;
      return x.responseText || null;
    } catch (e) { return null; }
  }
  function muestra(t) { return String(t == null ? '' : t).replace(/\s+/g, ' ').slice(0, 100); }
  function nom(o) { return o.name || o.title || o.channel_name || o.cname || o.tvg_name || o.label || o.channel; }
  function urlDe2(o) {
    // MEDIDO 2026-10-10 contra el servidor real con la sesion del usuario: los items
    // del glbt traen url:"1" (numero) y el stream se resuelve contra re…/redirect/glbr
    // con {id:id_live,url,agent}. El app juega ese esquema y resuelve al reproducir
    // (url fresca, nunca podrida). Si el item trae server+path (API vieja) se arma igual.
    var idl = o.id_live || o.idLive || o.channel_id || null;
    if (idl) {
      return 'dramalive://play?id_live=' + encodeURIComponent(String(idl)) +
        '&u=' + encodeURIComponent(String(o.url == null ? '1' : o.url)) +
        '&a=' + encodeURIComponent(String(o.agent || 'all_streams_redirect')) +
        (o.id_topic ? '&t=' + encodeURIComponent(String(o.id_topic)) : '');
    }
    var u = o.url || o.link || o.href || o.stream_url || null;
    if (u && /^https?:\/\//.test(String(u))) return String(u);
    var s = String(o.server || o.server_url || '').replace(/^https?:\/\//, '').replace(/\/+$/, '');
    if (!s) return null;
    var p = String(o.path || o.stream_path || '').replace(/^\/+/, '').replace(/\/+$/, '');
    return 'https://' + s + '/' + p + (/index\.(html|m3u8)$/.test(p) ? '' : '/index.html');
  }
  function ver(o, origen) {
    if (!o || typeof o !== 'object') return;
    if (Array.isArray(o)) { for (var i = 0; i < o.length && i < 6000; i++) ver(o[i], origen); return; }
    // v3.2.4: el indice de topicos vive en live_topics[] del glt; extraerlo AQUI (y no
    // solo en procesar) es lo que hace que el REPLAY de auto() los aprenda tambien —
    // medido en node con la respuesta real de 43 KB: sin esto, auto() no disparaba ni
    // un glbt porque A.topics seguia vacio tras el replay del glt.
    if (o.live_topics && Object.prototype.toString.call(o.live_topics) === '[object Array]' && !A.topics.length) {
      for (var lt = 0; lt < o.live_topics.length; lt++) {
        var ltx = o.live_topics[lt];
        if (ltx && ltx.id_topic) A.topics.push(String(ltx.id_topic));
      }
      if (A.topics.length) L('TOPICS x' + A.topics.length + ' via ver(' + (origen || '?') + ')');
    }
    var n = nom(o), u = urlDe2(o);
    if (n && u && (/^https?:\/\//.test(String(u)) || String(u).indexOf('dramalive://') === 0)) {
      n = String(n).trim();
      var k = JSON.stringify([n, u]);
      if (!A.vis[k]) {
        A.vis[k] = 1;
        A.filas.push({ n: n, u: String(u), g: String(o.id_topic || o.category || o.group || o.group_title || o.live_category || o.type || 'DramaLive'), l: o.logo || o.tvg_logo || o.image || null, s: origen || '' });
        A.sucio = Date.now();
      }
      return;
    }
    for (var j in o) { try { ver(o[j], origen); } catch (e) {} }
  }
  function aprender(url, o, via) {
    if (!o || typeof o !== 'object' || Array.isArray(o)) return;
    if (typeof o.type !== 'string') return;
    var ep = rutaDe(url).slice(0, 10);
    var pareceSesion = ('user_id' in o) || ('device_id' in o);
    if (!pareceSesion && !A.plant[ep]) return;
    var previo = !!A.plant[ep];
    A.plant[ep] = o;
    if (!previo) {
      A.epAprendido = ep;
            if (!A.urlGlt && (ep === 'glt' || ep === 'glbt' || ep === 'glasbi')) { A.urlGlt = String(url); L('PLANTILLA ' + ep + ' (' + via + ') url=' + String(url).slice(0, 130)); estado('plantilla ' + ep + ' \u00b7 ' + hostDe(url)); }
      if (!A.hechoAuto) setTimeout(auto, 700);
    }
  }
  async function procesar(url, txt, que) {
    if (!txt) return;
    var esGlt = /glt|glbt|glasbi/.test(rutaDe(url));
    if (esGlt) {
      if (que === 'req') { A.muestraReq = String(txt).slice(0, 12000); A.muestraLargoReq = String(txt).length; try { A.bodyCrudo = b64dec(b64norm(txt)); } catch (e) {} }
      else { A.muestraResp = String(txt).slice(0, 12000); A.muestraLargoResp = String(txt).length; }
    }
    var rt = rutaDe(url);
    if (rt === 'glbt') A.urlGlbt = String(url);
    if (rt === 'glbr') A.urlGlbr = String(url);
    var r = await decLibre(txt);
    if (!r) { if (esGlt) L(que + ' ' + rt + ' sin leer (len ' + String(txt).length + ')'); return; }
    A.decOk++;
    if (rt === 'glbr' && que === 'req' && r.json && r.json.id) {
      A.glbrPend = String(r.json.id);
      // v3.2.25: ANTES envuelta en `if (r.json.user_id)` — mesma guarda falsa que en la
      // semilla, y adentro estaba el disparo inmediato de la cosecha: por eso JAMAS se
      // ve 'COSECHA (al aprender)' ni en sesion manual. La request glbr COMPLETA del
      // sitio es la plantilla: copiarla tal cual.
      A.plant.glbrReq = r.json; L('PLANTILLA glbr del sitio aprendida (' + Object.keys(r.json).length + ' campos)');
      if (!A.coseHecho) { A.coseHecho = 1; hito('al aprender: glbr del trafico — cosecha armada en 9 s'); setTimeout(function () { cosecha('al aprender'); }, 9000); }
    }
    if (rt === 'glbr' && que === 'resp' && r.json && r.json.result === 0 && r.json.data && r.json.data.url) {
      A.glbrOk++;
      L('glbr resuelto OK: ' + String(r.json.data.url).slice(0, 90));
      // v3.2.6 BACKFILL: cuando el SITIO resuelve un canal (el usuario lo jugo en su
      // WebView), la fila dramalive:// de ese id_live pasa a llevar la url http real:
      // el proximo tap va directo a ExoPlayer sin resolver nada. Medido: el host del
      // stream da 403 solo si NO hay User-Agent; ExoPlayer siempre manda uno -> no
      // hace falta propagar data.agent.
      var pid = A.glbrPend || null;
      A.glbrPend = null;
      if (pid) {
        var du = r.json.data.url, nu = '';
        if (du && typeof du === 'object') nu = String(du.url || '');
        else {
          nu = String(du || '');
          if (nu.charAt(0) === '{') { try { var em = JSON.parse(nu); nu = String((em && em.url) || ''); } catch (eEm) { nu = ''; } }
        }
        if (!nu || nu.indexOf('update.mp4') >= 0) {
          L('BACKFILL ignorado (sueno del servidor) para ' + pid);
        } else {
          var marca = 'id_live=' + encodeURIComponent(pid);
          for (var fi = 0; fi < A.filas.length; fi++) {
            var fl = A.filas[fi];
            if (String(fl.u).indexOf('dramalive://') === 0 && String(fl.u).indexOf(marca) >= 0) {
              fl.u = nu; A.fijos = (A.fijos || 0) + 1; A.sucio = Date.now();
              L('BACKFILL ' + pid + ' -> ' + nu.slice(0, 80));
              break;
            }
          }
        }
      }
    }
    if (rt === 'glt' && que === 'resp' && r.json && Array.isArray(r.json.live_topics) && !A.topics.length) {
      r.json.live_topics.forEach(function (tp) { if (tp && tp.id_topic) A.topics.push(tp.id_topic); });
      if (A.topics.length) L('TOPICS x' + A.topics.length + ': ' + A.topics.slice(0, 8).join(','));
    }
    if (esGlt) { A.crack = r.dispo; if (que === 'req' && r.stricto && !A.crackReq) { A.crackReq = r.dispo; L('crackReq := ' + r.dispo); } L(que + ' ' + rutaDe(url) + ' LEIDO via ' + r.dispo + ' — ' + muestra(JSON.stringify(r.json).slice(0, 100))); }
    ver(r.json, 'resp:' + hostDe(url));
    aprender(url, r.json, que === 'req' ? r.dispo + '-cuerpo' : r.dispo + '-resp');
  }
  function grabarHdrs(h) {
    try {
      if (!h) return; var out = {};
      if (typeof h.forEach === 'function' && Object.getPrototypeOf(h) !== Object.prototype) { h.forEach(function (v, k) { out[String(k).toLowerCase()] = String(v); }); }
      else { for (var k2 in h) out[String(k2).toLowerCase()] = String(h[k2]); }
      delete out['cookie']; delete out['host'];
      A.hdrs = out;
    } catch (e) {}
  }

  var of = window.fetch;
  if (of) {
    window.fetch = function (u, opt) {
      var url = String(u && u.url ? u.url : u);
      var t = !esRuido(url);
      if (t) {
        A.req++; contarHost(url);
        if (url.indexOf('fgcode') >= 0 || url.indexOf('code=') >= 0) {   // v3.2.24: aprender
          var mc = /(?:fgcode\.(?:org|store)\/|(?:\?|&)code=)([0-9A-Za-z._-]{2,40})/.exec(url);
          if (mc && !A.codAprendido) { A.codAprendido = 1;
            try { window.AndroidDrama && AndroidDrama.aprenderCodigo && AndroidDrama.aprenderCodigo(mc[1]); } catch (ec) {} }
        }
        var body = opt && opt.body ? textoCuerpo(opt.body) : null;
        if (A.req <= 120) L('#' + A.req + ' FETCH ' + String((opt && opt.method) || 'GET') + ' ' + url.slice(0, 120) + ' len=' + (body ? body.length : 0) + ' ' + muestra(body));
        if (opt && opt.headers) grabarHdrs(opt.headers);
        if (body) procesar(url, body, 'req');
      }
      var p = of.apply(this, arguments);
      if (t) {
        p.then(function (r2) {
          try {
            r2.clone().text().then(function (x) {
              if (A.req <= 120) L('  resp#' + A.req + ' HTTP ' + r2.status + ' len=' + String(x).length + ' ' + muestra(x));
              procesar(url, x, 'resp');
            }).catch(function () {});
          } catch (e) {}
        });
      }
      return p;
    };
  }
  var OX = window.XMLHttpRequest;
  function FakeXHR() {
    var x = new OX(), SR = x.setRequestHeader;    x.open = function (mth, uu) { this.__u = String(uu); this.__m = mth; return OX.prototype.open.apply(x, arguments); };
    x.setRequestHeader = function (k, v) { try { var o = {}; o[String(k)] = String(v); grabarHdrs(o); } catch (e) {} return SR.apply(x, arguments); };
    var SN = x.send;
    x.send = function (b) {
      var url = x.__u || '';
      var t = !esRuido(url);
      if (t) {
        A.req++; contarHost(url);
        var bt = textoCuerpo(b);
        if (A.req <= 120) L('#' + A.req + ' XHR ' + (x.__m || '?') + ' ' + String(url).slice(0, 120) + ' len=' + (bt ? bt.length : 0) + ' ' + muestra(bt));
        if (bt) procesar(url, bt, 'req');
        x.addEventListener('loadend', function () {
          try {
            var txt = cuerpoResp(x);
            if (A.req <= 120) L('  resp#' + A.req + ' HTTP ' + x.status + ' len=' + String(txt || '').length + ' ' + muestra(txt));
            if (txt) procesar(url, txt, 'resp');
          } catch (e) {}
        });
      }
      return SN.apply(x, arguments);
    };
    return x;
  }
  FakeXHR.prototype = OX ? OX.prototype : Object.prototype;
  if (OX) {
    for (var st in OX) { try { FakeXHR[st] = OX[st]; } catch (e) {} }
    ['UNSENT', 'OPENED', 'HEADERS_RECEIVED', 'LOADING', 'DONE'].forEach(function (k, i) { try { if (FakeXHR[k] === undefined) FakeXHR[k] = i; } catch (e) {} });
    window.XMLHttpRequest = FakeXHR;
  }
  var OWS = window.WebSocket;
  if (OWS) {
    var FakeWS = function (url, proto) {
      var s = proto === undefined ? new OWS(url) : new OWS(url, proto);
      try {
        L('ws abierto ' + String(url).slice(0, 100));
        s.addEventListener('message', function (ev) {
          A.wsFrames++;
          if (A.wsFrames <= 40) { var d = typeof ev.data === 'string' ? ev.data : null; L('WS↓ ' + muestra(d || '(bin ' + ((ev.data && (ev.data.byteLength || ev.data.size)) || 0) + 'B)')); if (d) procesar(url, d, 'resp'); }
        });
        var SSD = s.send;
        s.send = function (d) { if (A.wsFrames <= 40 && typeof d === 'string') L('WS↑ ' + muestra(d)); return SSD.apply(s, arguments); };
      } catch (e) {}
      return s;
    };
    FakeWS.prototype = OWS.prototype;
    FakeWS.CONNECTING = 0; FakeWS.OPEN = 1; FakeWS.CLOSING = 2; FakeWS.CLOSED = 3;
    window.WebSocket = FakeWS;
  }

  async function replay(extra, ep) {
    ep = ep || 'glt';
    var base = A.plant[ep] || A.plant.glt || A.plant[A.epAprendido] || null;
    if (!base) return null;
    var q = JSON.parse(JSON.stringify(base));
    for (var k in (extra || {})) q[k] = extra[k];
    var url = (ep === 'glbt' && A.urlGlbt) || (ep === 'glt' && A.urlGlt) ||
      (A.urlGlt || 'https://li.highperformanceconnect.shop/api/live/livedrama/v13.0.0/glt').replace(/glt(\?|$)/, ep + '$1');
    var headers = { 'Content-Type': 'text/plain; charset=utf-8' };
    if (A.hdrs) for (var hk in A.hdrs) { if (/(token|auth|x-|api|key|version|app)/i.test(hk) || hk === 'content-type') headers[hk] = A.hdrs[hk]; }
    var cand = [A.crackReq, A.crack, 'ctiv', 'p0+frente', 'p16+frente', 'p0+cero', 'p16+cero'].filter(function (x, i, xz) { return x && x.indexOf('p') === 0 && xz.indexOf(x) === i; });
    for (var ci = 0; ci < cand.length; ci++) {
      var ds = cand[ci];
      try {
        var r = await of.call(window, url, { method: 'POST', headers: headers, body: await encPara(q, ds) }); // v3.2.10: sin credentials (el server no manda ACA-C y el navegador bloqueaba; el sitio tampoco los usa en sus XHR)
        var txt = await r.text();
        var rr = await decLibre(txt);
        var bueno = rr && rr.json && (rr.json.result === 0 || rr.json.result === '0' || (rr.json.live_topics && rr.json.live_topics.length));
        L('REPLAY[' + ds + '] ' + (q.type || '?') + ' HTTP ' + r.status + ' len=' + String(txt).length + (rr ? ' LEIDO(' + rr.dispo + ')' : ' sin leer') + (bueno ? ' ✓' : ''));
        if (rr) ver(rr.json, 'replay');
        if (bueno) { A.crackReq = ds; return rr; }
      } catch (e) { L('REPLAY[' + ds + '] fallo: ' + (e && e.message || e)); }
    }
    return null;
  }
  function sleep(ms) { return new Promise(function (r) { setTimeout(r, ms); }); }
  // v3.2.4: semilla de la sesion guardada (DramaAutoActivity inyecta window.__DRAMA_SESION
  // ANTES de este guion). Con ella auto() replaya glt/glbt desde el segundo 0 sin esperar
  // el trafico del sitio: medido en el telefono, a +0.8s Flutter solo habia mandado
  // gs/manifests y sin semilla la entrega salia con 0 canales y plantilla 'ninguna'.
  // v3.2.26 (log 00:36: la cosecha no dejo NI UN rastro): cada decision de la cadena
  // semilla->cosecha sale por el puente BLABONGO pase lo que pase, sin guards encima.
  function hito(m) { try { var D = window.AndroidDrama; if (D && D.hito) D.hito(String(m)); } catch (e) {} }
  try {
    var S = window.__DRAMA_SESION || null;
    if (S) {
      // v3.2.25 (log usuario 23:56: cero lineas COSECHA en 5 min): el glbrReq del sitio
      // NUNCA trae user_id (su identidad son token/cookies) y la guarda vieja lo tiraba —
      // sin A.plant.glbrReq el tick jamas arma la cosecha y el prior nunca se cura.
      if (S.plant) for (var sk in S.plant) { if (!S.plant[sk]) continue;
        if (sk === 'glbrReq' || S.plant[sk].user_id) A.plant[sk] = S.plant[sk]; }
      if (S.urlGlt) A.urlGlt = String(S.urlGlt);
      if (S.urlGlbt) A.urlGlbt = String(S.urlGlbt);
      if (S.urlGlbr) A.urlGlbr = String(S.urlGlbr);
      if (S.wire) { A.crackReq = String(S.wire); A.crack = String(S.wire); }
      if (Object.prototype.toString.call(S.topics) === '[object Array]') A.topics = S.topics.slice(0);
      A.inj = 1;
      hito('semilla: glbrReq=' + (A.plant.glbrReq ? Object.keys(A.plant.glbrReq).length + ' campos' : 'AUSENTE')
        + ' · glt=' + (A.plant.glt ? 'si' : 'no') + ' · topics=' + (A.topics ? A.topics.length : 0) + ' · wire=' + (A.crack || 'no'));
    }
  } catch (eSeed) { A.inj = -1; }

  async function auto() {
    if (A.corriendo) return;
    if (!A.plant.glt && !A.plant[A.epAprendido]) {
      for (var w = 0; w < 50 && !A.plant.glt && !A.plant[A.epAprendido]; w++) {
        estado('esperando la plantilla del sitio… ' + ((w / 2) | 0) + 's');
        await sleep(500);
      }
      if (!A.plant.glt && !A.plant[A.epAprendido]) {
        estado('el sitio no mando su glt: deja la pagina abrir la seccion LIVE y pulsa RECOGER');
        return;
      }
    }
    A.corriendo = true;
    // 1) el glt tal como el sitio lo manda (NO tocarle el type: type="tv" es lo que
    //    el servidor acepta; mis listas inventadas {live,sports…} devolvian result:1
    //    "no data" — medido contra el servidor real con la sesion del usuario)
    estado('pidiendo glt (plantilla tal cual)…');
    await replay({}, 'glt');
    // 2) por cada topico que el glt anuncio, el glbt trae los canales reales
    var tps = A.topics.slice(0, 10);
    for (var i = 0; i < tps.length; i++) {
      estado('topic ' + tps[i] + ' (' + (i + 1) + '/' + tps.length + ') — ' + A.filas.length + ' canales');
      await replay({ topic: tps[i] }, 'glbt');
      await sleep(450);
    }
    A.corriendo = false; A.hechoAuto = 1;
    // v3.2.27: en cuanto auto respira, la cosecha arranca — sin esperar el tick de 8 s
    // (los 9 s del «al aprender» caian SIEMPRE dentro de auto, que tarda ~17 s: medido).
    if (A.plant.glbrReq && !A.coseHecho && !A.cosechando) { A.coseHecho = 1; hito('auto termino: arranco COSECHA'); cosecha('al terminar auto'); }
    estado(A.filas.length + ' canales (crack ' + (A.crack || '?') + ') — navegá las secciones y se suman solos');
    enviar('auto');
  }
  function enviar(motivo) {
    if (!A.filas.length) return;
    var now = Date.now();
    if (motivo !== 'force' && now - A.ultEnvio < 20000) return;
    A.ultEnvio = now;
    L('ENTREGA (' + motivo + ') ' + A.filas.length + ' canales crack=' + (A.crack || '?'));
    try {
      if (window.AndroidDrama) {
        AndroidDrama.entregar(JSON.stringify({
          extraido: new Date().toISOString(), motivo: motivo,
          plantillas: { glt: A.plant.glt || A.plant.glbt || A.plant[A.epAprendido] || null, glbt: A.plant.glbt || null, glbrReq: A.plant.glbrReq || null },
          url: (A.urlGlt && /\/glt(\?|$)/.test(A.urlGlt)) ? A.urlGlt
          : (A.urlGlbt && /\/glbt(\?|$)/.test(A.urlGlbt) ? A.urlGlbt.replace('/glbt', '/glt') : 'https://li.highperformanceconnect.shop/api/live/livedrama/v13.0.0/glt'),
          urlGlbt: A.urlGlbt || null, urlGlbr: A.urlGlbr || null,
          topics: A.topics.slice(0, 60), wire: A.crackReq || A.crack || null,
          canales: A.filas
        }));
      }
    } catch (e) {}
  }
  function estado(t) { A.diagTxt = String(t); try { if (window.AndroidDrama) AndroidDrama.estado(String(t)); } catch (e) {} }
  A.hola = function () { estado(A.diagTxt); };
  function volcar() {
    try {
      if (!window.AndroidDrama) return;
      AndroidDrama.volcar(JSON.stringify({
        version: 'v3.2.29', t: Date.now(), href: String(location.href).slice(0, 140),
        ua: String(navigator.userAgent).slice(0, 130), ready: document.readyState,
        hooks: { fetch: !!of, xhr: 'FakeXHR+estaticos', ws: !!OWS, aesJs: true },
        sw: (navigator.serviceWorker && navigator.serviceWorker.controller) ? 'controlado' : 'libre',
        req: A.req, decOk: A.decOk, filas: A.filas.length, wsFrames: A.wsFrames, erroresJs: A.erroresJs,
        crack: A.crack, crackReq: A.crackReq, inj: A.inj||0, glbrOk: A.glbrOk, fijos: A.fijos||0, topics: A.topics.length,
        urlGlbt: A.urlGlbt || '', urlGlbr: A.urlGlbr || '', plantilla: A.epAprendido || 'ninguna', urlGlt: A.urlGlt || '',
        hosts: Object.keys(A.hostsReq).slice(0, 8).map(function (h) { return h + ':' + A.hostsReq[h]; }).join(' '),
        muestra: { reqLen: A.muestraLargoReq, respLen: A.muestraLargoResp, req: A.muestraReq, resp: A.muestraResp },
        log: A.log.slice(-150)
      }));
    } catch (e) {}
  }
  // ---- v3.2.12 COSECHA -------------------------------------------------------------
  // Medido (logcat usuario 16:33, con plantilla de 20 campos + cookies del WebView en
  // el POST Java): el glbr del player sigue recibiendo update.mp4, mientras DENTRO de
  // la pagina el mismo glbr devuelve el fas6 real. El servidor gradua por contexto del
  // navegador, no solo por campos. Cura definitiva: pedir los streams desde la propia
  // pagina. Mientras el Captador este abierto, el gancho replayea el glbr del sitio
  // fila por fila (cadencia ~1/s, backoff si llega vacio, tope 100, parada tras 8
  // suenos) y guarda la url real cruda en la rejilla — el mismo formato del backfill
  // v3.2.6 (medido: el host solo da 403 sin User-Agent; ExoPlayer siempre manda uno).
  function paramsFila(u) {
    var q = {};
    String(u).replace(/[?&]([^=&]+)=([^&]*)/g, function (_, k, v) {
      try { q[k] = decodeURIComponent(v); } catch (e) { q[k] = v; }
    });
    return q;
  }
  // v3.2.28 (prueba-python /home/user/py_canal): el server NIEGA el stream hasta que el
  // "anuncio" (file.twoapistack.work/stream/update.mp4) sea REPRODUCIDO. El sitio lo sabe:
  // juega el mp4 muted, y el player web dispara 'ended' -> ahi re-pide glbr y le llega el
  // real. Esto replica esa jugada dentro del WebView; techo 22 s por si el CDN corta.
  function verAnuncio(src) {
    return new Promise(function (res) {
      var v; try { v = document.createElement('video'); } catch (e0) { return res(); }
      var done = 0;
      function fin() { if (done) return; done = 1; try { v.pause(); } catch (e1) {} try { v.removeAttribute('src'); v.load(); } catch (e2) {} res(); }
      try {
        v.muted = true; v.playsInline = true;
        v.addEventListener('ended', fin); v.addEventListener('error', fin);
        v.src = src; var pr = v.play(); if (pr && pr.catch) pr.catch(fin);
      } catch (e3) { return res(); }
      setTimeout(fin, 22000);
    });
  }
  async function cosechaRun(filas, pedir, onFix) {
    var hechos = 0, ok = 0, blancos = 0, suenos = 0;
    for (var i = 0; i < filas.length && hechos < 100; i++) {
      var u = String(filas[i].u || '');
      if (u.indexOf('dramalive://') !== 0) continue;
      var p = paramsFila(u);
      if (!p.id_live) continue;
      var rj = null;
      try { rj = await pedir(filas[i], p); } catch (e) { rj = null; }
      hechos++;
      if (!rj) {
        blancos++;
        if (blancos >= 3) { L('COSECHA abortada: ' + blancos + ' respuestas vacias (rate limit)'); break; }
        await sleep(6000);
        continue;
      }
      blancos = 0;
      var du = (rj.result === 0 && rj.data && rj.data.url) ? String(rj.data.url) : '';
      if (du.charAt(0) === '{') { try { du = String(JSON.parse(du).url || ''); } catch (eEm) { du = ''; } }
      if (!du || du.indexOf('update.mp4') >= 0 || du.indexOf('twoapistack') >= 0) {
        suenos++;
        if (suenos >= 8) { L('COSECHA parada: ' + suenos + ' suenos seguidos (servidor restrictivo hoy)'); break; }
        await sleep(1400);
        continue;
      }
      suenos = 0;
      filas[i].u = du; filas[i].di = p.id_live; ok++; A.fijos = (A.fijos || 0) + 1; A.sucio = Date.now();  // v3.2.21: di = el id sobrevive a la cura
      if (onFix) { try { onFix(filas[i], p, du); } catch (eFx) {} }
      if (ok % 10 === 0) estado('cosecha: ' + ok + ' streams reales fijados...');
      await sleep(900);
    }
    return { hechos: hechos, ok: ok, suenos: suenos, blancos: blancos };
  }
  function ordenPrior(filas, prior) {
    if (!prior) return filas;
    var marca = 'id_live=' + encodeURIComponent(String(prior));
    var cabe = [], cola = [];
    for (var i = 0; i < filas.length; i++) {
      if (String(filas[i].u).indexOf(marca) >= 0) cabe.push(filas[i]); else cola.push(filas[i]);
    }
    return cabe.concat(cola);
  }
  function asegurarPrior(pend, todas, prior, nombre, logo, grupo) {
    if (!prior) return pend;
    for (var i = 0; i < pend.length; i++) {
      if (paramsFila(String(pend[i].u)).id_live === prior) return pend;
    }
    var f = { n: nombre || prior, u: 'dramalive://play?id_live=' + encodeURIComponent(prior) + '&u=1&a=all_streams_redirect' };
    if (logo) f.l = logo;
    if (grupo) f.g = grupo;
    if (todas && todas !== pend) todas.push(f);
    pend.unshift(f);
    try { L('COSECHA: el canal jugado NO estaba en el topic — fila sintetica ' + prior); } catch (e) {}
    return pend;
  }
  async function cosecha(motivo) {
    hito('cosecha(' + motivo + '): glbrReq=' + (A.plant.glbrReq ? 'si' : 'NO')
      + (A.corriendo ? ' — BLOQUEADA: auto en vuelo' : (A.cosechando ? ' — BLOQUEADA: otra cosecha en vuelo' : '')));
    if (A.corriendo || A.cosechando) {
      // v3.2.27 (log 12:26:46/12:28:48 «BLOQUEADA: auto en vuelo» y NUNCA mas cosecha):
      // el disparo ya habia puesto A.coseHecho=1 y el tick no reintentaba jamas. Devolverla.
      if (A.coseHecho) { A.coseHecho = 0; hito('lanzamiento bloqueado — devuelvo la bandera: el tick reintenta cuando auto termine'); }
      return null;
    }
    if (!A.plant.glbrReq) { L('COSECHA sin lanzar: el sitio aun no emite su glbr (juga un canal ahi)');
      hito('COSECHA abortada: el gancho no tiene A.plant.glbrReq — ni la semilla ni el trafico la trajeron'); return null; }
    var pend = [];
    for (var i = 0; i < A.filas.length; i++) if (String(A.filas[i].u).indexOf('dramalive://') === 0) pend.push(A.filas[i]);
    var prior = window.__DRAMA_PRIOR ? String(window.__DRAMA_PRIOR) : null;
    if (prior) {
      pend = asegurarPrior(pend, A.filas, prior,
          window.__DRAMA_PRIOR_N ? String(window.__DRAMA_PRIOR_N) : null,
          window.__DRAMA_PRIOR_L ? String(window.__DRAMA_PRIOR_L) : null,
          window.__DRAMA_PRIOR_G ? String(window.__DRAMA_PRIOR_G) : null);
      pend = ordenPrior(pend, prior); L('COSECHA prior ' + prior);
    }
    if (!pend.length) { L('COSECHA sin filas dramalive://'); hito('COSECHA abortada: 0 filas dramalive (filas=' + A.filas.length + ' — nada que cosechar)'); return null; }
    A.cosechando = 1;
    hito('cosecha arranca: ' + pend.length + ' filas · prior=' + (prior || 'no') + ' · plant=' + Object.keys(A.plant).join(','));
    var url = A.urlGlbr || 'https://re.highperformanceconnect.shop/redirect/glbr';
    var ds = A.crackReq || A.crack || 'ctiv';
    L('COSECHA (' + (motivo || 'auto') + '): ' + pend.length + ' filas pendientes (cap 100, cadencia ~1/s)');
    estado('cosechando streams reales desde la pagina...');
    var res = await cosechaRun(pend, async function (fila, p) {
      var q = JSON.parse(JSON.stringify(A.plant.glbrReq));
      q.id = p.id_live; q.url = p.u || '1'; q.agent = p.a || 'all_streams_redirect';
      // v3.2.28: si YA vimos el anuncio, subimos los contadores como el sitio (runCount+1,
      // adsShowed/adsComplete=1) — medido en python: con esto en la IP del usuario el sitio
      // desbloquea; sin esto, el server nos tienen en sueno para siempre.
      q.runCount = (q.runCount | 0) + (A.adWatched || 0);
      if (A.adWatched) { q.adsShowed = 1; q.adsComplete = 1; }
      var f = of || window.fetch;
      async function xpost() {
        var r2 = await f.call(window, url, { method: 'POST',
          headers: { 'Content-Type': 'text/plain; charset=utf-8' }, body: await encPara(q, ds) });
        var rr2 = await decLibre(await r2.text());
        return rr2 ? rr2.json : null;
      }
      // v3.2.29 (PRUEBA-PYTHON CON FRAME H264 1024x540, 12/10): el glbr con url='1' es la
      // ruta del sueno; la del navegador es glasbi{id} -> live.url '.LS.V2...' con agent
      // 'redirect' -> glbr -> JSON advanced cuya .url es la playlist real. Pedir glasbi 1x fila.
      try {
        var gu = (A.urlGlt ? String(A.urlGlt).replace(/glt$/, 'glasbi')
                           : 'https://li.highperformanceconnect.shop/api/live/livedrama/v13.0.0/glasbi');
        // glasbi EXIGE la auth completa (medido 12/10 22:34: con {id} pelado o con los 9
        // campos de la semilla responde 200 en blanco; con GLT+token+contadores -> 201 y
        // el marcador). Glue: glt plantado, encima glbrReq, encima id/token de siempre.
        var gb = {};
        try { if (A.plant.glt) for (var kg in A.plant.glt) gb[kg] = A.plant.glt[kg]; } catch (e1) {}
        try { for (var kk in A.plant.glbrReq) if (gb[kk] === undefined) gb[kk] = A.plant.glbrReq[kk]; } catch (e2) {}
        gb.id = p.id_live;
        if (gb.token === undefined && gb.user_id !== undefined) gb.token = String(gb.user_id);
        if (gb.timeZone === undefined && gb.timezone !== undefined) gb.timeZone = gb.timezone;
        var rg = await f.call(window, gu, { method: 'POST',
          headers: { 'Content-Type': 'text/plain; charset=utf-8' }, body: await encPara(gb, ds) });
        var gjs = await decLibre(await rg.text());
        var lv = gjs && gjs.json && gjs.json.live;
        if (lv && lv.url && String(lv.url).indexOf('.LS.') >= 0) {
          q.url = String(lv.url); q.agent = String(lv.agent || 'redirect');
          hito('glasbi: marcador ' + q.url.slice(0, 44) + ' agent=' + q.agent);
        }
      } catch (e0) {}
      var jj = await xpost();
      var du1 = (jj && jj.result === 0 && jj.data) ? String(jj.data.url || '') : '';
      if (du1.indexOf('update.mp4') >= 0 && !A.adWatched) {
        A.adWatched = 1;
        hito('anuncio (update.mp4): lo reproduzco muted y RE-PIDO glbr con runCount+1 — la jugada del sitio');
        try { await verAnuncio(du1); } catch (eAd) {}
        jj = (await xpost()) || jj;
      }
      return jj;
    }, prior ? function (fila, p) {
      if (!A.priorFijo && p.id_live === prior) { A.priorFijo = 1; L('PRIOR curado: ' + prior + ' — entrega inmediata'); enviar('force'); }
    } : null);
    A.cosechando = 0;
    L('COSECHA fin: ' + res.ok + '/' + res.hechos + ' reales · ' + res.suenos + ' suenos · ' + res.blancos + ' vacias');
    hito('cosecha fin: ' + res.ok + '/' + res.hechos + ' reales · ' + res.suenos + ' suenos · ' + res.blancos + ' vacias · curado=' + (A.priorFijo ? 1 : 0));
    estado(res.ok ? ('cosecha: ' + res.ok + ' canales con stream real — da play sin salir') : 'cosecha: el server dio relleno a todo; navegá otra seccion y reintenta');
    if (res.ok) enviar('force');
    // v3.2.23: avisar al puente como termino la cosecha — si el prior NO quedo curado, que el
    // headless no espere el plomazo en vano (quema el canal y el player escapa al visible).
    try { if (window.AndroidDrama && AndroidDrama.cosechaTermino) AndroidDrama.cosechaTermino(res.ok | 0, A.priorFijo ? 1 : 0); } catch (e) {}
    return res;
  }
  A.cosecha = cosecha;
  A.volcarAhora = volcar;
  A.__test = { decLibre: decLibre, encPara: encPara, b64enc: b64enc, b64dec: b64dec, b64norm: b64norm, cbcEnc: cbcEnc, cbcDec: cbcDec, KEYB: KEYB, AES: AES };
  L('hooks v3.2.29 (glasbi antes del glbr): fetch=' + (of ? 'si' : 'NO') + ' ws=' + (OWS ? 'si' : 'no') + ' ready=' + document.readyState);
  // v3.2.24: si este WebView ya tiene el setup del sitio (el usuario lo configuro a mano),
  // aprender el codigo para la app — de ahi en adelante el seed conecta solo.
  try { var sc = JSON.parse(localStorage.getItem('flutter.source_config') || 'null');
        if (typeof sc === 'string') sc = JSON.parse(sc);
        if (sc && sc.type === 'fgCode' && sc.fgCode && !A.codAprendido) { A.codAprendido = 1;
          try { window.AndroidDrama && AndroidDrama.aprenderCodigo(sc.fgCode); } catch (e24) {} } } catch (e23) {}
  setInterval(function () {
    var hs = Object.keys(A.hostsReq).slice(0, 3).join(',');
    estado('trafico: ' + A.req + ' req · ' + A.decOk + ' leidas · ' + A.filas.length + ' canales · crack=' + (A.crack || '?') + (hs ? ' · ' + hs : ''));
    volcar();
    if ((A.plant.glt || A.plant[A.epAprendido]) && !A.hechoAuto && !A.corriendo) auto();
    else if (A.sucio && Date.now() - A.sucio > 5000) { A.sucio = 0; enviar('sink'); }
    if (A.hechoAuto && A.plant.glbrReq && !A.coseHecho && !A.corriendo) { A.coseHecho = 1; hito('tick: arranco COSECHA'); cosecha('tick'); }
    else if (A.hechoAuto && !A.plant.glbrReq && !A.plantAviso26) { A.plantAviso26 = 1;
      hito('tick: A.plant.glbrReq SIGUE nula — cosecha bloqueada (la semilla no la trajo y el sitio no emite glbr)'); }
  }, 8000);
  setTimeout(volcar, 900);
  // entrega automatica breve al detectar canales nuevos, para que la rejilla se llene
  // aunque el usuario siga navegando (el puente throtlea 20 s)
  estado('captador v3.2.29 INSTALADO (glasbi+redirect+advanced: la ruta con la que el navegador si ve play)');
  window.__dramaAutoTest = { decLibre: decLibre, encPara: encPara, procesar: procesar, ver: ver, auto: auto, cosechaRun: cosechaRun, paramsFila: paramsFila, ordenPrior: ordenPrior, asegurarPrior: asegurarPrior, A: A };
})();
