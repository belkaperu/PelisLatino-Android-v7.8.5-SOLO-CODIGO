// v3.2.24: ejecuta el JS-semilla que arma DramaSesion.seedSetupJs contra un mock del
// localStorage del sitio (shared_preferences web = doble-json, prefijo «flutter.») y
// comprueba el COMPORTAMIENTO: siembra terms+source, idempotencia, no recarga dos veces.
const fs = require('fs'), vm = require('vm'), assert = require('assert');
const code = process.argv[3] || 'fgcode.org/232425';
const seed = fs.readFileSync(process.argv[2], 'utf8').split('__CODE__').join(code);
const store = {}, ss = {}; const reloads = [];
const win = { localStorage: { getItem: k => (k in store ? store[k] : null),
                              setItem: (k, v) => { store[k] = String(v); } },
              sessionStorage: { getItem: k => (k in ss ? ss[k] : null),
                                setItem: (k, v) => { ss[k] = String(v); } },
              location: { reload: () => reloads.push(1) } };
const ctx = { window: win, localStorage: win.localStorage,
              sessionStorage: win.sessionStorage, location: win.location,
              JSON, setTimeout: (f) => reloads._t = f };
vm.runInNewContext(seed, ctx);
assert.strictEqual(store['flutter.terms_continue'], 'true', 'terms_continue no sembrado');
const outer = JSON.parse(store['flutter.source_config']);
assert.strictEqual(typeof outer, 'string', 'source_config debe estar doble-JSON');
const inner = JSON.parse(outer);
assert.strictEqual(inner.type, 'fgCode');
assert.strictEqual(inner.fgCode, code);
assert.strictEqual(win.__BLAB_SEED_FG, code);
reloads.length = 0;
vm.runInNewContext(seed, ctx);
assert.strictEqual(reloads.length, 0, '2a pasada no debe recargar');
// usuario configuro otro tipo en este WebView: NO pisarselo
const store2 = { 'flutter.source_config': JSON.stringify(JSON.stringify(
    { type: 'm3u', fgCode: '', m3uUrl: 'https://x/y.m3u' })) };
const ctx2 = { window: { localStorage: { getItem: k => (k in store2 ? store2[k] : null),
        setItem: (k, v) => { store2[k] = String(v); } }, sessionStorage: { getItem: () => null, setItem: () => {} },
        location: { reload: () => {} } }, JSON, setTimeout: f => f() };
ctx2.location = ctx2.window.location; ctx2.localStorage = ctx2.window.localStorage; ctx2.sessionStorage = ctx2.window.sessionStorage;
vm.runInNewContext(seed, ctx2);
assert.strictEqual(JSON.parse(JSON.parse(store2['flutter.source_config'])).type, 'm3u',
    'el seed no debe pisar una fuente m3u/xtream existente');
console.log('SEED_SIM_OK');
