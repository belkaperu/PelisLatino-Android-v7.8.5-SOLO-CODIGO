/*
 * exportar-canales-navegador.js — saca la lista de canales que TU NAVEGADOR ya tiene,
 * sin reimplementar el cifrado del portal.
 *
 * Contexto (medido en el bundle de web.dramalive.cc v13.0.3.241): el campo "FG Code" no llama
 * a fgcode.org. El código se manda como campo KEY_ACTIVATED_TYPE dentro de un payload POST a
 * https://main.fastconnections.online/api/live/livedrama/v13.0.0/{gs,glt,glbt,glasbi} y el
 * cuerpo viaja AES-CBC (clave fija del bundle, formato `base64(cifrado):base64(iv)`), así que
 * en la pestaña Network la respuesta es ilegible. Este script no descifra nada: lee lo que la
 * app YA guardo descifrado (SharedPreferences de Flutter web vive en localStorage/IndexedDB) y
 * lo devuelve en M3U, que es el formato que entiende PelisLatino.
 *
 * USO
 *  1. Abre https://web.dramalive.cc/ en Chrome/Edge, mete tu FG Code y deja que cargue la lista
 *     (y abre un canal cualquiera, para que el reproductor también tenha su URL).
 *  2. F12 -> Console. Pega TODO este archivo y pulsa Enter.
 *  3. Imprime `Ventana.descargar()` y te baja `dramalive.m3u` solo. Si tu navegador no deja
 *     descargar, `copy(Ventana.m3u)` y pégalo donde quieras. En la app: En vivo -> Fuente ->
 *     "Pegar lista o URL de un canal" -> Usar lo pegado.
 *  3b. `Ventana.json` / `Ventana.descargarJson()` dan el MISMO catálogo con el formato de
 *     `tools/api-canales` (/v1/channels), para meterlo en tu API o en tu web sin reescribir nada.
 *
 * SIN CONSOLA: `docs/hacer-bookmarklet.mjs` convierte este archivo en un marcador
 * (`docs/exportar-canales-bookmarklet.txt`). Arrástralo a la barra de marcadores, abre
 * DramaLive con la lista cargada y haz clic: descarga el .m3u. Un clic, cero F12.
 *  4. Si NO encuentra la lista (porque la guardan cifrada), usa el Plan B de abajo.
 */
(function () {
    var CANAL_KEYS = ['name', 'stream_display_name', 'title', 'channel_name'];
    var ID_KEYS = ['stream_id', 'id', 'num', 'episode_id'];
    var URL_KEYS = ['url', 'stream_url', 'direct_url', 'container_url', 'm3u8'];
    var LOGO_KEYS = ['stream_icon', 'custom_stream_icon', 'icon', 'image', 'tvg-logo', 'logo'];
    var GRUPO_KEYS = ['category_name', 'category', 'group-title', 'group', 'topic'];

    function txt(o, keys) {
        for (var i = 0; i < keys.length; i++) {
            var v = o[keys[i]];
            if (typeof v === 'string' && v.trim()) return v.trim();
            if (typeof v === 'number' && isFinite(v)) return String(v);
        }
        return '';
    }

    /** Busca arrays de "cosas con nombre" en un objeto anidado (cualquier profundidad). */
    function buscarListas(nodo, ruta, salida, profundidad) {
        profundidad = profundidad || 0;
        if (!nodo || profundidad > 7) return;
        if (Object.prototype.toString.call(nodo) === '[object Array]') {
            var útiles = 0;
            for (var i = 0; i < nodo.length; i++) {
                var e = nodo[i];
                if (e && typeof e === 'object' && txt(e, CANAL_KEYS) && (txt(e, URL_KEYS) || txt(e, ID_KEYS))) útiles++;
            }
            if (útiles >= Math.max(2, nodo.length * 0.5)) {
                salida.push({ ruta: ruta, items: nodo.filter(function (x) { return x && typeof x === 'object'; }) });
                return;
            }
        }
        if (typeof nodo !== 'object') return;
        for (var k in nodo) {
            if (!Object.prototype.hasOwnProperty.call(nodo, k)) continue;
            try { buscarListas(nodo[k], ruta + '.' + k, salida, profundidad + 1); } catch (e) { /* Getter raro: sigue. */ }
        }
    }

    function aM3u(items) {
        var lineas = ['#EXTM3U'];
        var sinUrl = 0;
        for (var i = 0; i < items.length; i++) {
            var o = items[i];
            var nombre = txt(o, CANAL_KEYS);
            if (!nombre) continue;
            var url = txt(o, URL_KEYS);
            var id = txt(o, ID_KEYS);
            if (!url) { sinUrl++; continue; }          // sin URL el canal no reproduce: fuera
            var logo = txt(o, LOGO_KEYS);
            var grupo = txt(o, GRUPO_KEYS) || 'Dramalive';
            lineas.push('#EXTINF:-1 tvg-id="' + id.replace(/"/g, '') + '" tvg-logo="' + logo.replace(/"/g, '') +
                '" group-title="' + grupo.replace(/"/g, '') + '",' + nombre);
            lineas.push(url);
        }
        return { m3u: lineas.slice(1).length ? lineas.join('\n') : '', canales: (lineas.length - 1) / 2, sinUrl: sinUrl };
    }

    function intentarParsear(s) {
        if (typeof s !== 'string' || s.length < 8) return null;
        var t = s.trim();
        if (t.charAt(0) !== '{' && t.charAt(0) !== '[') return null;
        try { return JSON.parse(t); } catch (e) { return null; }
    }

    var encontrados = [];
    var revisados = 0;

    function escanearAlmacen(nombre, almacen) {
        if (!almacen) return;
        for (var i = 0; i < almacen.length; i++) {
            var clave = almacen.key(i);
            revisados++;
            var bruto = almacen.getItem(clave);
            // Flutter web guarda SharedPreferences como JSON envuelto: {"key":"<clave>","value":...}
            var obj = intentarParsear(bruto);
            if (obj && typeof obj === 'object' && !Array.isArray(obj) && 'value' in obj) {
                var interior = intentarParsear(obj.value);
                if (interior) obj = interior;
            }
            if (!obj) continue;
            var antes = encontrados.length;
            buscarListas(obj, nombre + ':' + clave, encontrados, 0);
            if (encontrados.length === antes && typeof obj === 'string') {
                buscarListas({ v: obj }, nombre + ':' + clave, encontrados, 0);
            }
        }
    }

    try { escanearAlmacen('localStorage', window.localStorage); } catch (e) { console.warn('localStorage bloqueado:', e); }
    try { escanearAlmacen('sessionStorage', window.sessionStorage); } catch (e) { /* nada */ }

    // IndexedDB: la base de shared_preferences de flutter también acaba aquí en algunos builds.
    var promesas = [];
    if (window.indexedDB && indexedDB.databases) {
        promesas.push(indexedDB.databases().then(function (bases) {
            return Promise.all(bases.map(function (b) {
                return new Promise(function (resolve) {
                    var req;
                    try { req = indexedDB.open(b.name); } catch (e) { return resolve(); }
                    req.onupgradeneeded = req.onerror = function () { resolve(); };
                    req.onsuccess = function () {
                        var db = req.result;
                        var tiendas = Array.prototype.slice.call(db.objectStoreNames);
                        var cola = tiendas.map(function (t) {
                            return new Promise(function (res2) {
                                try {
                                    var tx = db.transaction(t, 'readonly');
                                    var all = tx.objectStore(t).getAll();
                                    all.onsuccess = all.onerror = function () {
                                        var lista = all.result || [];
                                        for (var i = 0; i < lista.length; i++) {
                                            var fila = lista[i];
                                            var obj = fila && typeof fila === 'object' && 'value' in fila ? fila.value : fila;
                                            if (typeof obj === 'string') obj = intentarParsear(obj) || obj;
                                            buscarListas(obj, 'idb:' + b.name + '/' + t + '#' + i, encontrados, 0);
                                        }
                                        res2();
                                    };
                                } catch (e) { res2(); }
                            });
                        });
                        Promise.all(cola).then(function () { try { db.close(); } catch (e) { } resolve(); },
                                               function () { resolve(); });
                    };
                });
            }));
        }).catch(function () { }));
    }

    /** Separa la calidad del nombre, igual que hace M3uParser en la app. */
    function partirCalidad(nombre) {
        var m = /[[({]\s*(\d{3,4}p|FHD|UHD|QHD|HD|SD|4K|8K)\s*[)\]}]\s*$/i.exec(nombre);
        return m ? { nombre: nombre.slice(0, m.index).trim(), calidad: m[1] } : { nombre: nombre, calidad: '' };
    }

    /** El mismo shape que sirve /v1/channels de tools/api-canales, para que el archivo entre directo. */
    function aCanales(items) {
        var out = [];
        for (var i = 0; i < items.length; i++) {
            var o = items[i];
            var nombre = txt(o, CANAL_KEYS);
            var url = txt(o, URL_KEYS);
            if (!nombre || !url) continue;
            var partido = partirCalidad(nombre);
            var grupo = txt(o, GRUPO_KEYS) || 'Dramalive';
            out.push({
                id: 'c' + (out.length + 1),
                nombre: partido.nombre,
                grupo: grupo,
                grupos: [grupo],
                logo: txt(o, LOGO_KEYS),
                calidad: partido.calidad,
                tvgId: txt(o, ID_KEYS),
                servidores: [{ etiqueta: partido.calidad || 'Servidor 1', url: url }]
            });
        }
        return out;
    }

    /** Descarga el archivo desde la pestaña, sin copiar y pegar. */
    function guardar(nombre, texto) {
        try {
            var b = new Blob([texto], { type: 'text/plain;charset=utf-8' });
            var a = document.createElement('a');
            a.href = URL.createObjectURL(b);
            a.download = nombre;
            document.body.appendChild(a);
            a.click();
            setTimeout(function () { URL.revokeObjectURL(a.href); if (a.remove) a.remove(); }, 500);
            console.log('[exportar] descargado ' + nombre + ' (' + texto.length + ' caracteres).');
            return true;
        } catch (e) {
            console.warn('[exportar] no se pudo descargar; usa copy(Ventana.m3u) en su lugar.', e);
            return false;
        }
    }

    function informe() {
        var mejor = null;
        for (var i = 0; i < encontrados.length; i++) {
            var r = aM3u(encontrados[i].items);
            if (!mejor || r.canales > mejor.canales) mejor = { ruta: encontrados[i].ruta, items: encontrados[i].items, r: r };
        }
        console.log('[exportar] revisé ' + revisados + ' claves de almacenamiento y encontré ' +
            encontrados.length + ' lista(s) con forma de canales.');
        if (!mejor || !mejor.r.canales) {
            console.warn('[exportar] La lista NO está en claro en el almacenamiento: la guardan ' +
                'cifrada (AES del bundle). Usa el PLAN B del comentario de arriba: copia la URL del ' +
                'reproductor desde Network (suele ser …/androidApi.php?url=…&mode=live&username=…&password=…, ' +
                'o un .m3u8/.ts) y pégala en la app como "URL de un canal".');
            if (encontrados.length) console.log('[exportar] rutas vistas:', encontrados.map(function (x) { return x.ruta; }));
            return null;
        }
        console.log('[exportar] mejor lista en ' + mejor.ruta + ': ' + mejor.r.canales + ' canales' +
            (mejor.r.sinUrl ? ' (+' + mejor.r.sinUrl + ' sin URL, descartados)' : ''));
        var lista = aCanales(mejor.items);
        window.Ventana = {
            m3u: mejor.r.m3u,
            canales: mejor.r.canales,
            lista: lista,
            json: JSON.stringify(lista, null, 2),
            descargar: function () { return guardar('dramalive.m3u', mejor.r.m3u + '\n'); },
            descargarJson: function () { return guardar('dramalive.json', window.Ventana.json + '\n'); }
        };
        console.log('%cVentana.descargar()  te baja dramalive.m3u  (o copy(Ventana.m3u) y pega en la app: ' +
            'En vivo -> Fuente -> pegar).', 'background:#42D1FF;color:#0D0F14;padding:2px 6px');
        try { guardar('dramalive.m3u', mejor.r.m3u + '\n'); } catch (e) { /* si el navegador lo bloquea, queda copy() */ }
        console.log(mejor.r.m3u.split('\n').slice(0, 7).join('\n') + '\n…');
        return window.Ventana;
    }

    Promise.all(promesas).then(informe, informe);
})();
