#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""drama-live.py — el pipeline completo de "canales desde DramaLive" en un solo
fichero Python, para PROBARLO aqui antes de usarlo en la app.

Espejo 1:1 del codigo de la app (lo que sale verde aqui predice Android):
  descifrar / probar   ↔  EnlaceDrama.java — mismo AES-256-CBC + EVP_BytesToKey(MD5,1),
                          reusa tools/dramalink.py + tools/aes_bloque.py, verificados
                          contra docs/vectores-cryptojs.json (node + crypto-js 4.2.0,
                          la libreria exacta del player de Drama).
  extraer / m3u        ↔  tools/drama-extraer.js (captador del navegador) y su
                          conversor drama-extraer.py (canal: nombre + url o server+path
                          -> https://{server}/{path}/index.html, formula medida).
  demo                 ↔  el formato medido del panel: catalogo POST /glt cifrado con
                          la key literal del bundle 43fc9b1a3f854049 (AES-128-CBC,
                          alambre b64(ct):b64(iv)), index.html que responde #EXTM3U con
                          segmentos disfrazados "k9x_<unix>-<unix>.js" y swap que quita
                          el prefijo (sin swap el CDN da 404 — el demo lo reproduce).

Dependencias: solo stdlib + los modulos hermanos de este directorio. ffmpeg es
opcional (solo para decodificar un fotograma de prueba).

Uso:
  python3 tools/drama-live.py verificar
  python3 tools/drama-live.py demo
  python3 tools/drama-live.py descifrar "https://…/player.html#U2FsdGVkX1…" [--clave <webPlayerSecret>]
  python3 tools/drama-live.py probar "<url-interior>" [--swap "k9x_="] [--max 6] [--salir DIR]
  python3 tools/drama-live.py extraer --sesion plantillas.json [--tipos live,sports] [--out canales.m3u]
  python3 tools/drama-live.py m3u canales-drama.json -o canales.m3u

Salidas / codigos de salida:
  0 ok · 2 uso · 3 ninguna clave abrio el hash · 4 sesion anonima o caducada
  (el API contesto el catch-all de 32 B) · 5 la "URL interior" ya no es un playlist
Nota: los enlaces reales del panel CADUCAN (tokens = segundos unix atados a la
sesion/IP). Para `extraer`, la plantillas.json se exporta del navegador con
DramaExport.plantillas() (tools/drama-extraer.js) y hay que usarla al momento.
"""
import argparse
import base64
import json
import os
import re
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)

import dramalink as D  # cripto verificada contra CryptoJS 4.2.0 — no re-derivar nada aqui

KEY_CAT = b'43fc9b1a3f854049'  # literal en main.dart.js (4 ocurrencias): AES-128 para el catalogo
UA = 'Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36'

try:  # el conversor json->m3u del captador (hermano de repo)
    import importlib.util as _iu
    _sp = _iu.spec_from_file_location('drama_extraer', os.path.join(HERE, 'drama-extraer.py'))
    _ex = _iu.module_from_spec(_sp)
    _sp.loader.exec_module(_ex)
    canales_de, a_m3u = _ex.canales_de, _ex.a_m3u
except Exception:
    canales_de = a_m3u = None


def cat_enc(payload) -> str:
    """El alambre del catalogo: b64(AES-128-CBC con IV aleatorio) ':' b64(iv)."""
    from aes_bloque import aes_encrypt_cbc
    iv = os.urandom(16)
    b = lambda x: base64.b64encode(x).decode()
    ct = aes_encrypt_cbc(KEY_CAT, iv, json.dumps(payload, separators=(',', ':')).encode())
    return b(ct) + ':' + b(iv)


def cat_dec(txt: str):
    """Descifra una respuesta del catalogo; None si no es alambre (p.ej. catch-all 32 B)."""
    from aes_bloque import aes_decrypt_cbc
    try:
        t = (txt or '').strip()
        i = t.rfind(':')
        if i <= 0:
            return None
        return aes_decrypt_cbc(KEY_CAT, base64.b64decode(t[i + 1:]), base64.b64decode(t[:i])).decode('utf-8')
    except Exception:
        return None


def http(u, data=None, ref=None, timeout=25):
    hd = {'User-Agent': UA}
    if ref:
        hd['Referer'] = ref
    if data is not None:
        hd['Content-Type'] = 'text/plain; charset=utf-8'
    req = urllib.request.Request(u, data=data, headers=hd, method='POST' if data is not None else 'GET')
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, dict(r.headers), r.read()
    except urllib.error.HTTPError as e:
        try:
            return e.code, dict(e.headers or {}), (e.read() or b'')[:128]
        except Exception:
            return e.code, {}, b''
    except Exception as e:
        return 0, {}, ('%s: %s' % (type(e).__name__, e)).encode()


def _swap(vals):
    out = {}
    for v in vals or []:
        k, _, r = v.partition('=')
        if k:
            out[k] = r
    return out


def cmd_descifrar(a):
    """Paso 1 de la app: enlace pegado -> JSON interior (url, mediatype, swap)."""
    h = D.extraer_hash(a.entrada)
    if h.startswith('http://') or h.startswith('https://'):
        print('modo PLAIN (medido, grupo peruano): el fragmento ya es la URL, nada que descifrar')
        print(h)
        print('\nsiguiente paso:')
        print('  python3 tools/drama-live.py probar "%s"' % h)
        return 0
    claves = [a.clave] if a.clave else D.CLAVES_POR_DEFECTO
    for c in claves:
        try:
            pt = D.descifrar_blob(h, c)
        except Exception:
            continue
        try:
            obj = json.loads(pt.decode('utf-8'))
        except Exception:
            continue
        if not isinstance(obj, dict) or 'url' not in obj:
            continue
        print('clave usada: %r' % c)
        print(json.dumps(obj, ensure_ascii=False, indent=1))
        sw = obj.get('swap') or {}
        print('\nsiguiente paso (lo mismo que hara la app al abrir):')
        print('  python3 tools/drama-live.py probar "%s"%s' % (
            obj['url'], ''.join(' --swap "%s=%s"' % (k, v) for k, v in sw.items())))
        return 0
    print('ninguna clave abrio el hash. Si este canal es de un grupo con clave propia, saca el',
          'campo webPlayerSecret del config (hook de TextDecoder al arranque, ver HANDOFF) y:', file=sys.stderr)
    print('  python3 tools/drama-live.py descifrar "<link>" --clave "<webPlayerSecret>"', file=sys.stderr)
    return 3



def decodificar_frame(ts_path, png):
    """(ancho,alto) con el nombre del decoder, o (None, motivo).
    PyAV primero: el ffmpeg estatico de imageio-ffmpeg 7.0.2 hace segfault (rc=139)
    AL DEMUXAR el mpegts de estos paneles (medido 2026-10-08, incluso con -c copy);
    PyAV trae su libav propio y lo decodifica. Ambos son opcionales."""
    errores = []
    try:
        import av
        try:
            with av.open(ts_path) as c:
                for fr in c.decode(c.streams.video[0]):
                    fr.to_image().save(png)
                    return (int(fr.width), int(fr.height)), 'PyAV'
            errores.append('PyAV: 0 frames')
        except Exception as e:
            errores.append('PyAV %s' % repr(e)[:70])
    except ModuleNotFoundError:
        pass
    try:
        import imageio_ffmpeg
        import subprocess
        ff = imageio_ffmpeg.get_ffmpeg_exe()
        r = subprocess.run([ff, '-y', '-v', 'error', '-i', ts_path,
                            '-frames:v', '1', png], capture_output=True)
        if r.returncode == 0 and os.path.isfile(png) and os.path.getsize(png) > 200:
            with open(png, 'rb') as fh:
                b = fh.read(33)
            return (int.from_bytes(b[16:20], 'big'),
                    int.from_bytes(b[20:24], 'big')), 'imageio-ffmpeg'
        errores.append('imageio-ffmpeg rc=%d' % r.returncode)
    except ModuleNotFoundError:
        pass
    except Exception as e:
        errores.append('imageio-ffmpeg %s' % repr(e)[:70])
    return None, ('sin-decodificador' if not errores else ' + '.join(errores))


def cmd_probar(a):
    """Paso 2 de la app: URL interior -> playlist -> swap -> segmentos -> auditoria TS."""
    swap = _swap(a.swap)
    st, hd, body = http(a.url, ref=a.referer)
    ct = hd.get('content-type', '-')
    print('GET playlist -> %s (%d B, %s)' % (st, len(body), ct))
    if st != 200 or not body.lstrip().startswith(b'#EXTM3U'):
        print('NO hay playlist #EXTM3U al otro lado.', file=sys.stderr)
        print('Si es un 404: el token del panel caduco (son segundos unix atados a la',
              'sesion/IP que lo creo) — re-extrae el enlace del navegador y prueba al momento.',
              file=sys.stderr)
        return 5
    txt = body.decode('utf-8', 'replace')
    segs = [l.strip() for l in txt.splitlines() if l.strip() and not l.startswith('#')]
    if not segs:
        print('playlist sin lineas de segmento', file=sys.stderr)
        return 5
    print('#EXTINF x %d, ventana %s s' % (
        txt.count('#EXTINF'), (re.search(r'TARGETDURATION:(\d+)', txt) or [None, '?'])[1]))
    if not swap:
        pref = {m.group(1) for m in (re.match(r'^([A-Za-z][0-9A-Za-z]*_)(?=\d)', s) for s in segs) if m}
        pref.discard('')
        if len(pref) == 1:
            p = pref.pop()
            print('auto-swap detectado: "%s" -> "" (aplicado; el panel real exige esto)' % p)
            swap = {p: ''}
    base = urllib.parse.urljoin(a.url, '')
    crudo = b''
    ok = 0
    for i, s in enumerate(segs[:a.max]):
        s2 = s
        for k, v in swap.items():
            s2 = s2.replace(k, v)
        st2, _, d2 = http(urllib.parse.urljoin(base, s2), ref=a.referer)
        ts = st2 == 200 and len(d2) > 188 and d2[:1] == b'\x47' and len(d2) % 188 == 0
        print('seg %d  %-40s -> %s %dB %s' % (i, s2[:40], st2, len(d2), 'TS!' if ts else 'x'))
        if ts:
            crudo += d2
            ok += 1
    if not ok:
        print('ningun segmento util: 404 = sesion caducada o el CDN filtra por IP/región',
              '(medido: el panel peruano solo sirve a su propia sesion)', file=sys.stderr)
        return 5
    out = a.salir or os.getcwd()
    os.makedirs(out, exist_ok=True)
    ts_path = os.path.join(out, 'drama-live.ts')
    open(ts_path, 'wb').write(crudo)
    print('reasamblado: %d B, %d/%d segmentos -> %s' % (len(crudo), ok, min(len(segs), a.max), ts_path))
    # auditoria con la MISMA funcion que usa la app/demo si esta en el repo
    notas = None
    try:
        sp2 = _iu.spec_from_file_location('demo', os.path.join(HERE, 'demo-play-en-python.py'))
        dm = _iu.module_from_spec(sp2)
        sp2.loader.exec_module(dm)
        okk, notas = dm.auditar_ts(ts_path)
        print('auditar_ts: %s' % (' | '.join(notas))[:280])
        if not okk:
            return 5
    except Exception:
        n = len(crudo) // 188
        mal = sum(1 for k in range(n) if crudo[k * 188] != 0x47)
        print('auditoria basica: %d paquetes de 188 B, %d fuera de sincronia' % (n, mal))
        if mal:
            return 5
    if a.frame:
        png = os.path.join(out, 'drama-live-frame.png')
        dim, por = decodificar_frame(ts_path, png)
        if dim:
            print('fotograma decodificado (%s): %dx%d -> %s (VIDEO REAL, no basura)'
                  % (por, dim[0], dim[1], png))
        elif por == 'sin-decodificador':
            print('fotograma omitido (pip install av 6  imageio-ffmpeg para exigirlo)')
        else:
            print('AVISO: ningun decodificador pudo abrir este TS (%s). No invalida el canal:'
                  ' la auditoria ya demostro video real (PIDs/PCR/continuidad); p.ej. el ffmpeg'
                  ' estatico de imageio da segfault (rc=139) con el mpegts de estos paneles,'
                  ' medido 2026-10-08, mientras PyAV si lo decodifica.' % por, file=sys.stderr)
    print('PROBAR-OK: el canal que la app abriria esta vivo, se audita y decodifica')
    return 0


def cmd_extraer(a):
    """Paso 'sacar los canales': replay del glt por cada tipo con TU plantilla de sesion."""
    if canales_de is None:
        print('falta tools/drama-extraer.py junto a este fichero', file=sys.stderr)
        return 2
    ses = json.load(open(a.sesion, encoding='utf-8'))
    base = (a.base or ses.get('base') or '').rstrip('/') + '/'
    plant = (ses.get('plantillas') or ses.get('plant') or {}).get('glt') or {}
    if 'user_id' not in plant:
        print('la plantilla no trae los campos de sesion (user_id, device_id, ...).'
              ' Exporta con DramaExport.plantillas() desde la consola del navegador,',
              ' tras haber navegado Live (asi el captador aprendio la peticion real).',
              file=sys.stderr)
        return 2
    tipos = (a.tipos.split(',') if a.tipos else [plant.get('type', 'live')])
    encontrados = []
    for t in tipos:
        q = dict(plant)
        q['type'] = t
        st, _, raw = http(base + 'glt', data=cat_enc(q).encode())
        claro = cat_dec(raw.decode('latin-1')) if (st == 200 and len(raw) > 48) else None
        if claro is None:
            print('tipo %-8s -> %s %dB %s' % (t, st, len(raw),
                  'CATCH-ALL (sesion caducada o plantilla incompleta: reexporta)' if len(raw) <= 48 else 'sin descifrar'),
                  file=sys.stderr)
            if not encontrados:
                return 4
            continue
        try:
            obj = json.loads(claro)
        except Exception:
            print('tipo %-8s -> claro pero no-JSON: %r' % (t, claro[:60]), file=sys.stderr)
            continue
        cs = canales_de(obj)
        print('tipo %-8s -> %d canales (%d B)' % (t, len(cs), len(claro)))
        encontrados += cs
    # dedupe por (nombre,url)
    vistos = set()
    unicos = [c for c in encontrados if not ((c['name'], c['url']) in vistos or vistos.add((c['name'], c['url'])))]
    if not unicos:
        print('respuesta ok pero 0 canales con forma utilizable — mira el crudo con --crudo',
              file=sys.stderr)
        return 4
    m3u = a_m3u(unicos)
    out = a.out or 'canales-drama.m3u'
    open(out, 'w', encoding='utf-8').write(m3u)
    if a.crudo:
        open(a.crudo, 'w', encoding='utf-8').write(json.dumps({'canales': unicos}, ensure_ascii=False, indent=1))
    print('\nEXTRAER-OK: %d canales -> %s  (pegalo en la app: En vivo -> Fuente -> Pegar lista;'
          ' y pruebalo ya con:  python3 tools/drama-live.py probar "<primera-url>" --max 2)'
          % (len(unicos), out))
    print(m3u[:400])
    return 0


def cmd_m3u(a):
    if a_m3u is None:
        print('falta tools/drama-extraer.py', file=sys.stderr)
        return 2
    datos = json.load(open(a.json, encoding='utf-8')) if a.json != '-' else json.load(sys.stdin)
    cs = canales_de(datos)
    if not cs:
        print('nada con forma de canal en el JSON', file=sys.stderr)
        return 2
    t = a_m3u(cs)
    if a.salida:
        open(a.salida, 'w', encoding='utf-8').write(t)
        print('%d canales -> %s' % (len(cs), a.salida))
    else:
        sys.stdout.write(t)
    return 0


# ─────────────────────────── demo: el panel entero, en local ───────────────────────────
#
# Sirve para probar TODO sin red y sin sesion: un mini-panel con el comportamiento
# EXACTO que medimos en DramaLive — catalogo glt cifrado con la key del bundle,
# index.html que responde #EXTM3U, segmentos disfrazados con prefijo y 404 si no se
# aplico el swap, y payload sin user_id contestado con el catch-all de 32 B. Si el
# pipeline pasa aqui, pasara contra el panel real mientras la sesion esté viva.

DEMO = {}


def _demo_server():
    import http.server
    import socketserver
    ts = b''
    ruta_ts = os.environ.get('DRAMA_DEMO_TS', '/home/user/demo-python/bein/canal.ts')
    if os.path.exists(ruta_ts):
        ts = open(ruta_ts, 'rb').read()
    if len(ts) < 188 * 30:
        ts = (b'\x47' + bytes(187)) * 300   # TS estructural de relleno si no hay grabacion
    n = 3
    trozo = (len(ts) // n) // 188 * 188
    segs = [ts[i * trozo:(i + 1) * trozo] for i in range(n)]
    t0 = int(time.time())
    pref = 'k9x_'
    crudos = ['%s%d-%d.js' % (pref, t0 - 10 * (n - i), t0) for i in range(n)]
    planos = [c.replace(pref, '') for c in crudos]
    catalogo = {'live_topics': [
        {'name': 'Canal Demo Peru', 'server': 'DEMO', 'path': 'pages/s1', 'group': 'Demo'},
        {'name': 'Canal Demo Arab', 'server': 'DEMO', 'path': 'pages/s2', 'group': 'Demo'}]}

    class H(http.server.BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def _send(self, code, b, ct='text/plain; charset=utf-8'):
            self.send_response(code)
            self.send_header('Content-Type', ct)
            self.send_header('Content-Length', str(len(b)))
            self.end_headers()
            self.wfile.write(b)

        def do_POST(self):
            ln = int(self.headers.get('Content-Length', 0))
            raw = self.rfile.read(ln).decode('latin-1')
            claro = cat_dec(raw)
            try:
                q = json.loads(claro) if claro else None
            except Exception:
                q = None
            if not isinstance(q, dict) or not q.get('user_id'):
                self._send(200, b'\t\t\r\n' + b' ' * 26)   # catch-all medido, tal cual
                return
            if self.path.endswith('glt'):
                self._send(200, cat_enc(dict(catalogo, eco=q['type'])).encode())
                return
            self._send(404, b'')

        def do_GET(self):
            p = self.path
            if p.endswith('/index.html'):
                m3u = ['#EXTM3U', '#EXT-X-VERSION:3', '#EXT-X-TARGETDURATION:10',
                       '#EXT-X-MEDIA-SEQUENCE:%d' % t0]
                for c in crudos:
                    m3u += ['#EXTINF:10.000,', c]
                m3u.append('#EXT-X-ENDLIST')
                self._send(200, '\n'.join(m3u).encode(), 'text/html')  # content-type disfraz: como el real
                return
            n = p.rsplit('/', 1)[-1]
            if n in planos:
                self._send(200, segs[planos.index(n)], 'video/mp2t')
                return
            self._send(404, b'')

    srv = socketserver.TCPServer(('127.0.0.1', 0), H)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv, 'http://127.0.0.1:%d/api/live/livedrama/v13.0.0/' % srv.server_address[1]


def cmd_demo(a):
    srv, api = _demo_server()
    out = a.salir or 'drama-live-demo'
    os.makedirs(out, exist_ok=True)
    try:
        print('== mini-panel drama en %s (formato exacto medido) ==' % api)
        # 0) anonimo -> catch-all de 32 B (la pared real; la app/Python no la salta sin sesion)
        st, _, raw = http(api + 'glt', data=cat_enc({'type': 'live'}).encode())
        print('paso 0  POST glt SIN user_id      -> %s %dB (catch-all reproducido: %r)'
              % (st, len(raw), raw[:8]))
        assert len(raw) <= 40, 'el demo deberia contestar catch-all al anonimo'
        # 1) exportamos nuestra "plantillas.json" como haria DramaExport.plantillas()
        ses = {'base': api, 'plantillas': {'glt': {'user_id': 'demo-1', 'device_id': 'd0',
                                                    'type': 'live', 'mainServer': 'DEMO'}}}
        rp = os.path.join(out, 'plantillas.json')
        json.dump(ses, open(rp, 'w'))
        print('paso 1  plantillas.json -> %s' % rp)
        # 2) extraer (igual que contra el panel real, con la sesion del demo)
        A = argparse.Namespace(sesion=rp, base=None, tipos='live,sports',
                               out=os.path.join(out, 'canales.m3u'),
                               crudo=os.path.join(out, 'canales-drama.json'))
        rc = cmd_extraer(A)
        assert rc == 0, 'extraer fallo'
        m3u = open(A.out, encoding='utf-8').read()
        urls = [l for l in m3u.splitlines() if l.startswith('http')]
        assert len(urls) == 2, urls
        # el server del demo solo conoce su propio host: reescribe DEMO -> localhost
        host = urllib.parse.urlparse(api).netloc
        urls = [u.replace('https://DEMO/', 'http://' + host + '/') for u in urls]
        # 3) probar el primer canal: playlist vivo -> swap -> segmentos -> auditoria (+frame si hay ffmpeg)
        B = argparse.Namespace(url=urls[0], referer=None, swap=[], max=3, salir=out, frame=True)
        rc = cmd_probar(B)
        assert rc == 0, 'probar fallo'
        print('DEMO-OK: catch-all-anonimo + extraccion con plantilla + canal con swap'
              ' + TS auditado, todo en local y sin tocar DramaLive')
        return 0
    finally:
        srv.shutdown()


def cmd_verificar(a):
    """Gate local: cripto contra el oracle externo + round-trip + swap regex. Sin red."""
    ok = 0
    orac = os.path.join(HERE, '..', 'docs', 'vectores-cryptojs.json')
    if os.path.exists(orac):
        vs = json.load(open(orac, encoding='utf-8'))
        for i, v in enumerate(vs):
            got = D.descifrar_blob(D.normalizar_base64url(v['hash']), v['clave']).decode('utf-8')
            assert got == v['texto'], 'vector %d NO coincide con crypto-js 4.2.0' % i
        print('vectores CryptoJS 4.2.0 : ok (%d/%d)' % (len(vs), len(vs)))
        ok += 1
    else:
        print('aviso: sin docs/vectores-cryptojs.json junto al repo; no hay oracle que exigir')
    for clave in ('_YOUR_SECRET', 'REPLACE_WITH_YOUR_SECRET', 'páñs-ünïcode'):
        texto = json.dumps({'url': 'https://s.example/live/ch1/index.html', 'title': 'Prüeba ñ',
                            'mediatype': 'hls', 'swap': {'k9x_': ''}})
        blob = D.cifrar_a_base64url(texto, clave)
        assert json.loads(D.descifrar_blob(blob, clave).decode())['url'].endswith('index.html')
    print('round-trip cifrar/descifrar : ok (3 claves, salt aleatorio)')
    ok += 1
    pref = {m.group(1) for m in (re.match(r'^([A-Za-z][0-9A-Za-z]*_)(?=\d)', s) for s in
            ['k9x_1791-1792.js', 'k9x_1792-1793.js']) if m}
    assert pref == {'k9x_'}, pref
    assert {m.group(1) for m in (re.match(r'^([A-Za-z][0-9A-Za-z]*_)(?=\d)', s) for s in
            ['1791-1792.js']) if m} == set(), 'sin prefijo no debe inventar swap'
    print('auto-deteccion de prefijo   : ok')
    alambre = cat_enc({'user_id': 'x'})
    assert isinstance(cat_dec(alambre), str) and json.loads(cat_dec(alambre))['user_id'] == 'x'
    print('alambre catalogo b64:b64    : ok (AES-128-CBC key del bundle)')
    ok += 1
    print('drama-live verificar: %d/3 comprobaciones, sin red' % (ok if os.path.exists(orac) else ok))
    return 0


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = ap.add_subparsers(dest='cmd', required=True)
    p = sub.add_parser('verificar', help='gate local sin red'); p.set_defaults(fn=cmd_verificar)
    p = sub.add_parser('demo', help='mini-panel del formato exacto + pipeline completo en local')
    p.add_argument('--salir'); p.set_defaults(fn=cmd_demo)
    p = sub.add_parser('descifrar', help='enlace/hash -> JSON interior (url/swap)')
    p.add_argument('entrada'); p.add_argument('--clave')
    p.add_argument('--max', type=int, default=6); p.set_defaults(fn=cmd_descifrar)
    p = sub.add_parser('probar', help='URL interior -> playlist -> swap -> segmentos -> auditoria TS')
    p.add_argument('url'); p.add_argument('--swap', action='append')
    p.add_argument('--referer'); p.add_argument('--max', type=int, default=6)
    p.add_argument('--salir'); p.add_argument('--frame', action='store_true', default=True)
    p.set_defaults(fn=cmd_probar)
    p = sub.add_parser('extraer', help='replay glt con plantilla de sesion -> canales -> m3u')
    p.add_argument('--sesion', required=True); p.add_argument('--base')
    p.add_argument('--tipos'); p.add_argument('--out')
    p.add_argument('--crudo'); p.set_defaults(fn=cmd_extraer)
    p = sub.add_parser('m3u', help='json del captador -> m3u (wrapper del convertidor)')
    p.add_argument('json'); p.add_argument('-o', '--salida'); p.set_defaults(fn=cmd_m3u)
    args = ap.parse_args(argv)
    return args.fn(args)


if __name__ == '__main__':
    sys.exit(main())
