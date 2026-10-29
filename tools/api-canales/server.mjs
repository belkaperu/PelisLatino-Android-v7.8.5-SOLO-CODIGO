#!/usr/bin/env node
/*
  API propia de canales, sobre TUS fuentes.

  Lee listas M3U (archivos locales o URLs que tú configures), las unifica con las
  mismas reglas que usa la app de Android, y las expone como JSON limpio y como
  un M3U canónico. No llama ni reimplementa la API de nadie: las únicas fuentes
  son las que tú pones en CANALES_FUENTES.

  Endpoints
    GET /healthz                       estado + cuántas fuentes se leyeron
    GET /v1/fuentes                   por fuente: bytes y si respondió
    GET /v1/channels?grupo=&q=&limit=&offset=&fresh=1
    GET /v1/search?q=                 atajo de lo mismo
    GET /v1/grupos                    lista de grupos con conteo
    GET /v1/channels.m3u?grupo=&q=    M3U regenerado (la app lo acepta como "URL de lista")
    GET /v1/play?canal=<id>&servidor=0
                                      { url, headers } para reproductores que aceptan
                                      encabezados por host (Media3: setDefaultRequestProperties)

  Config (variables de entorno o --clave=valor)
    CANALES_FUENTES   rutas .m3u o URLs, separadas por coma
    CANALES_PUERTO    por defecto 8787
    CANALES_HOSTS     ruta a JSON {"host": {"Referer": "...", "Origin": "...", "User-Agent": "..."}}
    CANALES_ORIGINOS  allowlist de orígenes para CORS, separada por coma (vacío = sin ACAO)
    CANALES_TTL       segundos de caché (0 = recargar en cada petición)

  Sin dependencias. Node 18+.
*/

import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const UA = 'PelisLatino-API/1.0 (+lista de canales propia)';
const P_EXTINF = /^#EXTINF:\s*(-?\d+(?:\.\d+)?)\s*(.*)$/;
const P_ATTR = /([\w-]+)="([^"]*)"/g;
const P_CALIDAD = /\s*[[({]\s*(\d{3,4}p|fhd|uhd|qhd|hd|sd|4k|8k)\s*[)\]}]\s*$/i;
const P_JUNK = /(mozilla|like gecko|chrome\/|safari\/|applewebkit|windows nt|linux x86|https?:\/\/|\.m3u8|\.php)/i;
const P_VACIO = /^(undefined|null|nan|none|unknown|\[\s*\]|\{\s*\})$/i;

// ---------------------------------------------------------------- M3U

function txtUsable(s) {
  const t = String(s == null ? '' : s).trim();
  if (!t || P_VACIO.test(t) || P_JUNK.test(t)) return '';
  return t;
}

function quitarCalidad(nombre) {
  const m = P_CALIDAD.exec(nombre);
  if (!m) return { nombre, calidad: '' };
  return { nombre: nombre.slice(0, m.index).trim(), calidad: m[1] };
}

/** Separa `a="b" group-title="x",Nombre, con coma` en [attrs, nombre]. */
function separarAttrsNombre(resto) {
  let dentro = false;
  for (let i = 0; i < resto.length; i++) {
    const c = resto[i];
    if (c === '"') dentro = !dentro;
    else if (c === ',' && !dentro) return [resto.slice(0, i), resto.slice(i + 1)];
  }
  return ['', resto];
}

function attrsDe(parte) {
  const at = {};
  for (const m of String(parte || '').matchAll(P_ATTR)) at[m[1].toLowerCase()] = m[2].trim();
  return at;
}

function hostDe(u) {
  try { return new URL(u).host.toLowerCase(); } catch { return ''; }
}

function nombreDesdeUrl(u) {
  let p = null;
  try { p = new URL(u); } catch { return 'Canal'; }
  const seg = p.pathname.split('/').filter(Boolean).pop() || '';
  const sinExt = seg.replace(/\.(m3u8|mpd|ts|index)$/i, '');
  const base = sinExt && !/^(live|stream|index|playlist|play)$/i.test(sinExt) ? sinExt : p.host;
  return decodeURIComponent(base)
    .replace(/[-_]+/g, ' ')
    .replace(/\b\p{L}\p{L}*/gu, (w) => w[0].toUpperCase() + w.slice(1))
    .trim();
}

export function canalDesdeExtinf(resto, url) {
  const u = String(url || '').trim();
  if (!u || u.startsWith('#')) return null;
  const [parteAttrs, nombreCrudo] = separarAttrsNombre(String(resto == null ? '' : resto));
  const at = attrsDe(parteAttrs);
  const preferidos = [quitarCalidad(nombreCrudo.trim()).nombre, at['tvg-name'], at['tvg-id']];
  let nombre = '';
  for (const c of preferidos) {
    const t = txtUsable(c);
    if (t) { nombre = t; break; }
  }
  let calidad = quitarCalidad(nombreCrudo.trim()).calidad || quitarCalidad(at['tvg-name'] || '').calidad;
  if (!nombre) nombre = nombreDesdeUrl(u);
  else { const q = quitarCalidad(nombre); nombre = q.nombre; if (!calidad) calidad = q.calidad; }
  const grupos = String(at['group-title'] || '')
    .split(';')
    .map((s) => txtUsable(s))
    .filter(Boolean);
  // El logo SÍ es una URL: no se le aplica el filtro de basura, solo se limpia.
  const logoCrudo = String(at['tvg-logo'] || at['logo'] || at['icon'] || at['custom_stream_icon'] || '').replace(/["\r\n]+/g, '').trim();
  const logo = P_VACIO.test(logoCrudo) ? '' : logoCrudo;
  return {
    nombre,
    url: u,
    logo,
    grupos,
    grupo: grupos[0] || 'General',
    calidad: calidad || '',
    tvgId: at['tvg-id'] || '',
  };
}

export function parsearM3u(texto) {
  const bruto = String(texto || '').replace(/\r/g, '');
  if (!bruto.trim()) return [];
  const lineas = bruto.split('\n');
  const fuera = [];
  let resto = null;
  for (const crudo of lineas) {
    const linea = crudo.trim();
    if (!linea) continue;
    const m = P_EXTINF.exec(linea);
    if (m) { resto = m[2]; continue; }
    if (linea.startsWith('#')) continue;
    fuera.push(canalDesdeExtinf(resto == null ? '' : resto, linea));
    resto = null;
  }
  return fuera.filter(Boolean);
}

function compactar(n) {
  return normalizarNombre(n).replace(/ /g, '');
}

function normalizarNombre(n) {
  return String(n || '')
    .toLowerCase()
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .replace(/\b\d{3,4}p\b|\b(?:sd|hd|fhd|uhd|4k|8k)\b/g, ' ')
    .replace(/[^a-z0-9]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

/** Une entradas repetidas por nombre en un solo canal con varios servidores. */
export function unificar(canales) {
  const porNombre = new Map();
  for (const c of canales) {
    const clave = normalizarNombre(c.nombre);
    if (!clave) continue;
    let s = porNombre.get(clave);
    if (!s) {
      s = { id: '', nombre: c.nombre, grupos: c.grupos.slice(), grupo: c.grupo, logo: c.logo, tvgId: c.tvgId, calidad: '', servidores: [] };
      porNombre.set(clave, s);
    }
    if (!s.logo && c.logo) s.logo = c.logo;
    if (s.grupo === 'General' && c.grupo !== 'General') s.grupo = c.grupo;
    for (const g of c.grupos) if (!s.grupos.includes(g)) s.grupos.push(g);
    if (s.servidores.some((x) => x.url === c.url)) continue;
    s.servidores.push({ etiqueta: c.calidad || 'Servidor ' + (s.servidores.length + 1), url: c.url, calidad: c.calidad });
    if (c.calidad && !s.calidad.includes(c.calidad)) s.calidad = s.calidad ? s.calidad + ' / ' + c.calidad : c.calidad;
  }
  const salida = [...porNombre.values()];
  salida.forEach((c, i) => { c.id = 'c' + (i + 1); });
  return salida;
}

function aM3u(canales) {
  const pila = (s) => String(s == null ? '' : s).replace(/["\r\n]+/g, ' ').trim();
  const lineas = ['#EXTM3U'];
  for (const c of canales) {
    const principal = c.calidad ? c.calidad.split(' / ')[0] : '';
    for (const srv of c.servidores) {
      const sufijo = srv.calidad && srv.calidad !== principal ? ' (' + srv.calidad + ')' : principal ? ' (' + principal + ')' : '';
      const attrs = [
        'tvg-id="' + pila(c.tvgId) + '"',
        'tvg-name="' + pila(c.nombre) + '"',
        'tvg-logo="' + pila(c.logo) + '"',
        'group-title="' + pila(c.grupos.join(';') || c.grupo) + '"',
      ].join(' ');
      lineas.push('#EXTINF:-1 ' + attrs + ',' + pila(c.nombre) + sufijo);
      lineas.push(srv.url);
    }
  }
  return lineas.join('\n') + '\n';
}

// ---------------------------------------------------------------- datos

export async function cargarLista(cfg) {
  const textos = [];
  const fuentes = [];
  for (const f of cfg.fuentes) {
    let texto = '';
    try {
      if (/^https?:/i.test(f)) {
        const r = await fetch(f, { headers: { 'User-Agent': UA }, redirect: 'follow' });
        texto = r.ok ? await r.text() : '';
        fuentes.push({ fuente: f, ok: !!r.ok, status: r.status, bytes: texto.length });
      } else {
        texto = fs.readFileSync(path.resolve(f), 'utf8');
        fuentes.push({ fuente: f, ok: true, status: 200, bytes: Buffer.byteLength(texto) });
      }
    } catch (e) {
      fuentes.push({ fuente: f, ok: false, status: 0, bytes: 0, error: String((e && e.message) || e) });
    }
    if (texto) textos.push(texto);
  }
  const canales = unificar(parsearM3u(textos.join('\n')));
  return { canales, fuentes };
}

function headersPara(cfg, url) {
  // hostname sin puerto: un panel en :8000 debe casar con la clave "mi-proveedor.net"
  const host = hostDe(url).replace(/:\d+$/, '');
  const tabla = cfg.hosts || {};
  let mejor = '';
  for (const h of Object.keys(tabla)) {
    if (host === h || (host && host.endsWith('.' + h))) {
      if (h.length > mejor.length) mejor = h;
    }
  }
  return mejor ? { ...tabla[mejor] } : {};
}

function filtrar(canales, q) {
  let salida = canales;
  const grupo = q.get('grupo');
  if (grupo) {
    const g = compactar(grupo);
    salida = salida.filter((c) => c.grupos.some((x) => compactar(x) === g) || compactar(c.grupo) === g);
  }
  const txt = compactar(q.get('q') || q.get('search') || '');
  if (txt) {
    salida = salida.filter(
      (c) => compactar(c.nombre).includes(txt) || c.grupos.some((g) => compactar(g).includes(txt)) || compactar(c.tvgId).includes(txt)
    );
  }
  const offset = Math.max(0, parseInt(q.get('offset') || '0', 10) || 0);
  const limit = parseInt(q.get('limit') || '0', 10) || 0;
  if (offset || limit) salida = limit ? salida.slice(offset, offset + limit) : salida.slice(offset);
  return salida;
}

// ---------------------------------------------------------------- HTTP

function responder(res, code, body, tipo, cfg, req) {
  const h = { 'Content-Type': tipo, 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' };
  const origen = req && req.headers ? req.headers.origin : '';
  if (origen && cfg.origenes.includes(origen)) {
    h['Access-Control-Allow-Origin'] = origen;
    h['Vary'] = 'Origin';
  }
  if (Buffer.isBuffer(body)) res.writeHead(code, h).end(body);
  else res.writeHead(code, h).end(body);
}

const json = (res, code, obj, cfg, req) => responder(res, code, JSON.stringify(obj, null, 2) + '\n', 'application/json; charset=utf-8', cfg, req);

export function crearServidor(cfg) {
  const estado = { cache: null, cuando: 0 };
  const ttlMs = Math.max(0, cfg.ttl) * 1000;

  async function datos(fresh) {
    if (!fresh && estado.cache && Date.now() - estado.cuando < ttlMs) return estado.cache;
    const nuevo = await cargarLista(cfg);
    estado.cache = nuevo;
    estado.cuando = Date.now();
    return nuevo;
  }

  return http.createServer(async (req, res) => {
    let u;
    try {
      u = new URL(req.url, 'http://localhost');
    } catch {
      return json(res, 400, { error: 'URL inválida' }, cfg, req);
    }
    const ruta = u.pathname.replace(/\/+$/, '') || '/';
    if (req.method === 'OPTIONS') {
      res.writeHead(204, { Allow: 'GET, OPTIONS', 'Access-Control-Allow-Methods': 'GET, OPTIONS', 'Access-Control-Allow-Headers': 'Content-Type', 'Access-Control-Max-Age': '600', 'Access-Control-Allow-Origin': cfg.origenes.includes(req.headers.origin) ? req.headers.origin : '' });
      return res.end();
    }
    if (req.method !== 'GET' && req.method !== 'HEAD') return json(res, 405, { error: 'solo GET' }, cfg, req);
    try {
      if (ruta === '/healthz' || ruta === '/health') {
        const d = await datos(u.searchParams.has('fresh'));
        return json(res, 200, { ok: true, fuentes: d.fuentes.length, canales: d.canales.length, servicios: d.fuentes }, cfg, req);
      }
      if (ruta === '/v1/fuentes') {
        const d = await datos(u.searchParams.has('fresh'));
        return json(res, 200, { fuentes: d.fuentes, canales: d.canales.length }, cfg, req);
      }
      if (ruta === '/v1/channels.m3u' || ruta === '/v1/lista.m3u') {
        const d = await datos(u.searchParams.has('fresh'));
        const lista = filtrar(d.canales, u.searchParams);
        return responder(res, 200, aM3u(lista), 'audio/x-mpegurl; charset=utf-8', cfg, req);
      }
      if (ruta === '/v1/grupos') {
        const d = await datos(u.searchParams.has('fresh'));
        const conteo = new Map();
        for (const c of d.canales) for (const g of (c.grupos.length ? c.grupos : [c.grupo])) conteo.set(g, (conteo.get(g) || 0) + 1);
        const grupos = [...conteo.entries()].map(([nombre, total]) => ({ nombre, total })).sort((a, b) => b.total - a.total || a.nombre.localeCompare(b.nombre));
        return json(res, 200, { total: grupos.length, grupos }, cfg, req);
      }
      if (ruta === '/v1/play') {
        const d = await datos(false);
        const id = u.searchParams.get('canal') || u.searchParams.get('id') || '';
        const canal = d.canales.find((c) => c.id === id || c.tvgId === id || normalizarNombre(c.nombre) === normalizarNombre(id));
        if (!canal) return json(res, 404, { error: 'no está en tu lista', canal: id }, cfg, req);
        const n = Math.max(0, Math.min(canal.servidores.length - 1, parseInt(u.searchParams.get('servidor') || '0', 10) || 0));
        const srv = canal.servidores[n];
        return json(res, 200, {
          id: canal.id,
          canal: canal.nombre,
          grupo: canal.grupo,
          servidor: n,
          etiqueta: srv.etiqueta,
          url: srv.url,
          headers: headersPara(cfg, srv.url),
        }, cfg, req);
      }
      if (ruta === '/v1/channels' || ruta === '/v1/search') {
        const d = await datos(u.searchParams.has('fresh'));
        const lista = filtrar(d.canales, u.searchParams);
        const modo = u.searchParams.get('modo');
        if (modo === 'm3u') return responder(res, 200, aM3u(lista), 'audio/x-mpegurl; charset=utf-8', cfg, req);
        return json(res, 200, {
          total: lista.length,
          enTotal: d.canales.length,
          canales: lista.map((c) => ({
            id: c.id,
            nombre: c.nombre,
            grupo: c.grupo,
            grupos: c.grupos,
            logo: c.logo,
            calidad: c.calidad,
            tvgId: c.tvgId,
            servidores: c.servidores,
          })),
        }, cfg, req);
      }
      if (ruta === '/' ) {
        return json(res, 200, {
          ok: true,
          nota: 'API de canales sobre tus propias fuentes. No llama a DramaLive ni a fgcode.',
          endpoints: ['/healthz', '/v1/fuentes', '/v1/channels', '/v1/search?q=', '/v1/grupos', '/v1/channels.m3u', '/v1/play?canal=&servidor='],
        }, cfg, req);
      }
      return json(res, 404, { error: 'ruta desconocida', rutas: ['/healthz', '/v1/channels', '/v1/channels.m3u', '/v1/grupos', '/v1/fuentes', '/v1/play'] }, cfg, req);
    } catch (e) {
      return json(res, 500, { error: String((e && e.stack) || e) }, cfg, req);
    }
  });
}

// ---------------------------------------------------------------- arranque

export function configDesdeEntorno(argv, entorno = process.env) {
  const vals = {};
  for (const a of argv.slice(2)) {
    const m = /^--([\w-]+)=(.*)$/.exec(a);
    if (m) vals[m[1].toLowerCase().replace(/-/g, '_')] = m[2];
  }
  const tomar = (clave, ...alias) => {
    if (vals[clave] !== undefined && vals[clave] !== '') return vals[clave];
    const porClave = entorno[clave.toUpperCase()];
    if (porClave) return porClave;
    for (const a of alias) if (entorno[a]) return entorno[a];
    return '';
  };
  const coma = (s) => String(s).split(',').map((x) => x.trim()).filter(Boolean);
  const cfg = {
    fuentes: coma(tomar('canales_fuentes')),
    puerto: parseInt(tomar('canales_puerto') || '8787', 10),
    hosts: {},
    origenes: coma(tomar('canales_origenes', 'CANALES_ORIGINOS', 'CANALES_ORIGEN')),
    ttl: parseInt(tomar('canales_ttl') || '300', 10),
  };
  const archivoHosts = tomar('canales_hosts');
  if (archivoHosts) {
    try { cfg.hosts = JSON.parse(fs.readFileSync(path.resolve(archivoHosts), 'utf8')); }
    catch (e) { console.error('no se pudo leer CANALES_HOSTS:', e.message); }
  }
  return cfg;
}

function esEntradaPropia() {
  const arg1 = process.argv[1];
  return !!arg1 && import.meta.url === pathToFileURL(path.resolve(arg1)).href;
}

if (esEntradaPropia()) {
  const cfg = configDesdeEntorno(process.argv);
  if (!cfg.fuentes.length) {
    console.error('Falta CANALES_FUENTES. Ejemplo:\n  CANALES_FUENTES=./mi-lista.m3u node server.mjs\n  CANALES_FUENTES=https://…/lista.m3u node server.mjs');
    process.exit(2);
  }
  const srv = crearServidor(cfg);
  srv.listen(cfg.puerto, '0.0.0.0', () => {
    console.log('API de canales en http://127.0.0.1:' + cfg.puerto);
    console.log('  fuentes:', cfg.fuentes.join(', '));
    console.log('  canales: (se leen en la primera petición; ?fresh=1 los recarga)');
  });
}
