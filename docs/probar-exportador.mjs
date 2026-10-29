/*
 * Comprobación del exportador (docs/exportar-canales-navegador.js) sin navegador: se ejecuta el
 * script con un `window` y un `localStorage` de mentira, con datos con la forma en la que Flutter
 * web guarda SharedPreferences, y se mira lo que deja en `window.Ventana`.
 *
 *   node docs/probar-exportador.mjs
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import assert from 'node:assert/strict';
import { compactar, hacerBookmarklet } from './hacer-bookmarklet.mjs';

const AQUI = dirname(fileURLToPath(import.meta.url));

const src = readFileSync(join(dirname(fileURLToPath(import.meta.url)), 'exportar-canales-navegador.js'), 'utf8');

function almacen(mapa) {
    const claves = Object.keys(mapa);
    return {
        get length() { return claves.length; },
        key: (i) => claves[i],
        getItem: (k) => (k in mapa ? mapa[k] : null),
    };
}

function ejecutar(mapa) {
    const avisos = [];
    const consola = {
        log: (...a) => avisos.push(['log', a.map(String).join(' ')]),
        warn: (...a) => avisos.push(['warn', a.map(String).join(' ')]),
    };
    const ventana = { localStorage: almacen(mapa), console: consola };
    const runner = new Function('window', 'console', 'alert', src);
    runner(ventana, consola, () => { });
    return new Promise((r) => setImmediate(() => r({ ventana, avisos })));
}

// ── 1) lista en claro dentro de una clave flutter.* ──────────────────────────
const canales = {
    result: 1,
    live: [
        { name: 'ESPN (720p)', stream_id: 12, url: 'https://cdn/live/12.m3u8', stream_icon: 'https://cdn/i/12.png', category_name: 'Deportes' },
        { name: 'Golf TV', stream_id: 88, url: 'https://cdn/live/88.m3u8', custom_stream_icon: 'https://cdn/i/88.png', category_name: 'Deportes' },
        { name: 'Sin URL todavia', stream_id: 91, category_name: 'General' },   // se descarta, pero se cuenta
    ],
};
const mapa = {
    'flutter.theme_mode': JSON.stringify({ key: 'flutter.theme_mode', value: 'dark' }),
    'flutter.live_data': JSON.stringify({ key: 'flutter.live_data', value: JSON.stringify(canales) }),
    'flutter.live_favourites_raw_jsons': JSON.stringify({ key: 'flutter.live_favourites_raw_jsons', value: '[]' }),
};

const { ventana: v1, avisos: a1 } = await ejecutar(mapa);
assert.ok(v1.Ventana, 'debería haber encontrado la lista y dejarla en window.Ventana');
assert.equal(v1.Ventana.canales, 2, 'canales con URL: ESPN y Golf');
const m3u = v1.Ventana.m3u;
assert.ok(m3u.startsWith('#EXTM3U'), 'empieza por la cabecera M3U');
assert.ok(m3u.includes('group-title="Deportes",ESPN (720p)'), 'nombre y grupo: ' + m3u.split('\n')[1]);
assert.ok(m3u.includes('tvg-id="12"'), 'tvg-id del stream_id');
assert.ok(m3u.includes('tvg-logo="https://cdn/i/12.png"'), 'logo desde stream_icon');
assert.ok(m3u.includes('https://cdn/live/88.m3u8'), 'URL del segundo canal (logo desde custom_stream_icon)');
assert.ok(!m3u.includes('Sin URL'), 'un canal sin URL no sirve, se fuera de la lista');
assert.ok(a1.some(([n, t]) => n === 'log' && t.includes('2 canales')), 'informa del conteo: ' + JSON.stringify(a1.map(x => x[1]).slice(0, 2)));
assert.ok(a1.some(([, t]) => t.includes('copy(Ventana.m3u)')), 'dice qué copiar');

// ── 2) array JSON suelto (otra forma en la que lo pueden guardar) ────────────
const { ventana: v2 } = await ejecutar({
    'flutter.cache': JSON.stringify([
        { name: 'Canal A', stream_id: '1', url: 'http://a/1.ts' },
        { name: 'Canal B', stream_id: '2', url: 'http://b/2.ts' },
    ]),
});
assert.equal(v2.Ventana.canales, 2, 'también entiende un array plano');
assert.ok(v2.Ventana.m3u.includes('group-title="Dramalive",Canal A'), 'grupo por defecto si no viene');

// ── 3) todo cifrado: no inventa nada y manda al plan B ──────────────────────
const { ventana: v3, avisos: a3 } = await ejecutar({
    'flutter.live_data': JSON.stringify({ key: 'flutter.live_data', value: 'Zm9vYmFyMTIzNDU2Nzg5MDEyMzQ1Njc4Og==:YWJjZGVm' }),
    'flutter.KEY_ACTIVATED_TYPE': JSON.stringify({ key: 'flutter.KEY_ACTIVATED_TYPE', value: '232425' }),
});
assert.equal(v3.Ventana, undefined, 'con los valores cifrados no debe producir lista');
assert.ok(a3.some(([n, t]) => n === 'warn' && t.includes('PLAN B')), 'avisa del plan B (copiar la URL del reproductor)');

// ── 4) no revienta con basura ni con almacenamiento vacío ───────────────────
for (const basura of [{}, { 'flutter.x': 'no-es-json' }, { 'flutter.y': '{"a":{"b":{"c":{"d":{"e":{}}}}}}}' },
{ 'flutter.z': JSON.stringify({ key: 'flutter.z', value: '{"circular":1}' }) }]) {
    const { ventana: vb } = await ejecutar(basura);
    assert.equal(vb.Ventana, undefined, 'basura no debe producir lista');
}

// ── caso 5: el bookmarklet que genera docs/hacer-bookmarklet.mjs ──
const codigoExportador = readFileSync(join(AQUI, 'exportar-canales-navegador.js'), 'utf8');
const compactado = compactar(codigoExportador);
assert.ok(!compactado.includes('medido en el bundle'), 'se van los comentarios de encabezado');
const muestra = "var u = 'https://x/y';  // comentario final\n/* bloque */\nvar v = 2;\n";
const cMuestra = compactar(muestra);
assert.ok(cMuestra.includes("'https://x/y'"), 'las dobles barras de las URLs dentro de strings no se tocan');
assert.ok(cMuestra.includes('var v = 2;'), 'lo que sigue a un comentario final de línea no se pierde');
assert.ok(!cMuestra.includes('bloque'), 'los bloques de comentario alineados a la izquierda sí se quitan');
assert.ok(compactado.includes("sin URL el canal no reproduce"), 'los comentarios al final de línea se quedan, pero no se tragan el resto del archivo');
assert.ok(compactado.includes('function aCanales'), 'el compactado no puede perder funciones por culpa de un //');
const bm = hacerBookmarklet(codigoExportador);
assert.ok(bm.startsWith('javascript:'), 'empieza con javascript:');
assert.ok(bm.includes('%23EXTM3U'), 'lleva la cabecera M3U escapada');
assert.ok(bm.length < 40000, 'cabe en un marcador: ' + bm.length + ' caracteres');
new Function(decodeURIComponent(bm.slice('javascript:'.length)));   // compila como script de página
assert.ok(v1.Ventana && typeof v1.Ventana.descargar === 'function', 'el exportador ofrece descargar()');
assert.ok(v1.Ventana.lista[0] && v1.Ventana.lista[0].servidores[0].url, 'y la lista con el formato de /v1/channels');
assert.ok(v1.Ventana.lista[0].nombre === 'ESPN', 'sin la calidad dentro del nombre: ' + v1.Ventana.lista[0].nombre);
assert.ok(v1.Ventana.lista[0].calidad === '720p', 'la calidad viaja en su campo');

console.log('OK — 5 casos del exportador verificados (lista en claro, array plano, cifrado, basura, bookmarklet).');
