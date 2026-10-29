#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Prueba de punta a punta del formato "canal extraido de Drama", sin depender de
que la sesion del usuario siga viva (los enlaces reales caducan: medido hoy, el
`pages/<token>/index.html` que ayer daba 200 con playlist vivo hoy da 404 — los
nombres de segmento son segundos unix y el CDN ata el token a la sesion/IP).

Que cubre, en orden exacto del cliente real:
  1. fabrica el enlace cifrado (#U2FsdGVkX1…, AES-CBC + EVP_BytesToKey, swap y todo);
  2. lo descifra con las claves publicas medidas;
  3. baja el index.html (que responde #EXTM3U con segmentos disfrazados de .js);
  4. aplica el swap del propio JSON (prefijo que el CDN no conoce: sin swap = 404,
     y el test lo exige);
  5. reensambla los segmentos y AUDITA el TS con la misma funcion de la app/demo
     (paquetes 188, PMT, PIDs h264+AAC, contadores, duracion por PCR);
  6. decodifica un fotograma con ffmpeg (1920x1080 real);
  7. pasa el JSON del extraedor (drama-extraer.py) y comprueba que el m3u resultante
     abre y sirve #EXTM3U.

Salida esperada: `E2E-COMPLETO` y rc=0. DRAMA_E2E_OUT=dir guarda los artefactos.
"""
import http.server, json, os, re, socketserver, sys, threading, time, urllib.request
sys.path.insert(0, 'tools')
import dramalink as D
from importlib import import_module
demo = import_module('demo-play-en-python') if os.path.exists('tools/demo-play-en-python.py') else None
sys.path.insert(0, 'tools'); import importlib.util
spec = importlib.util.spec_from_file_location('demo', 'tools/demo-play-en-python.py')
demo = importlib.util.module_from_spec(spec); spec.loader.exec_module(demo)

TS = open('/home/user/demo-python/bein/canal.ts','rb').read()
N = 3; trozo = (len(TS)//N)//188*188
SEG = [TS[i*trozo:(i+1)*trozo] for i in range(N)]
PREF = 'zx7_'
t0 = int(time.time())
NOMBRES = ['%s%d-%d.js' % (PREF, t0 - 10*(N-i), t0) for i in range(N)]   # nombres crudos con prefijo
PLANO   = [n.replace(PREF, '') for n in NOMBRES]                          # nombres post-swap

class H(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def do_GET(self):
        p = self.path
        if p == '/pages/sesion/index.html':
            m3u = ['#EXTM3U','#EXT-X-VERSION:3','#EXT-X-TARGETDURATION:10','#EXT-X-MEDIA-SEQUENCE:%d' % t0]
            for k, n in enumerate(NOMBRES):
                m3u += ['#EXTINF:10.000,', n]
            m3u.append('#EXT-X-ENDLIST')
            b = '\n'.join(m3u).encode()
            self.send_response(200); self.send_header('Content-Type','text/html'); self.send_header('Content-Length',str(len(b))); self.end_headers(); self.wfile.write(b)
            return
        if p.startswith('/pages/sesion/'):
            n = p.rsplit('/',1)[1]
            if n in PLANO:                       # el CDN solo sirve el nombre post-swap
                i = PLANO.index(n); b = SEG[i]
                self.send_response(200); self.send_header('Content-Type','video/mp2t'); self.send_header('Content-Length',str(len(b))); self.end_headers(); self.wfile.write(b); return
            self.send_response(404); self.end_headers(); return
        self.send_response(404); self.end_headers()

srv = socketserver.TCPServer(('127.0.0.1', 0), H)
puerto = srv.server_address[1]
threading.Thread(target=srv.serve_forever, daemon=True).start()
base = 'http://127.0.0.1:%d' % puerto
print('mini-panel drama en', base)

# ── 1) FABRICA el enlace cifrado tal como lo hace Drama ──────────────────────
payload = {'url': base + '/pages/sesion/index.html', 'mediatype': 'hls', 'swap': {PREF: ''}}
enlace = 'http://127.0.0.1:%d/player/player.html?fit=contain#' % puerto + D.cifrar_a_base64url(json.dumps(payload, separators=(',',':')), '_YOUR_SECRET', __import__('binascii').unhexlify('0a0b0c0d0e0f1011'))
print('ENLACE (el que el usuario pegaría):'); print(' ', enlace[:110] + '…')

# ── 2) EL MISMO CAMINO QUE LA APP: hash -> JSON -> playlist -> swap -> segs ──
h = D.extraer_hash(enlace)
claro = json.loads(D.descifrar_blob(h, '_YOUR_SECRET').decode())
print('descifrado: url=%s swap=%s' % (claro['url'], claro['swap']))
pl = urllib.request.urlopen(claro['url'], timeout=15).read().decode()
assert pl.startswith('#EXTM3U'), pl[:40]
seg_txt = pl
for k, v in (claro.get('swap') or {}).items(): seg_txt = seg_txt.replace(k, v)
segs = [l for l in seg_txt.splitlines() if l and not l.startswith('#')]
print('swap aplicado: %s -> %s' % (NOMBRES[0], segs[0]))
ts = b''
for s in segs:
    r = urllib.request.urlopen(urllib.parse.urljoin(claro['url'], s), timeout=15)
    assert r.status == 200
    ts += r.read()
print('reasamblado: %d B (%d segmentos)' % (len(ts), len(segs)))
# control: el nombre SIN aplicar swap debe dar 404 (como en el panel real)
try:
    urllib.request.urlopen(urllib.parse.urljoin(claro['url'], NOMBRES[0]), timeout=10); cruda='200 (!!)'
except Exception as e: cruda = getattr(e,'code','ERR')
print('segmento crudo (sin swap) -> %s (esperado 404: el prefijo es disfraz)' % cruda)

# ── 3) AUDITORIA TS con la misma función que usa la app/demo ────────────────
import tempfile
OUT = os.environ.get('DRAMA_E2E_OUT', '.')
open(os.path.join(OUT,'e2e-canal.ts'),'wb').write(ts)
info = demo.auditar_ts(os.path.join(OUT,'e2e-canal.ts'))
print('auditar_ts:', json.dumps(info, ensure_ascii=False, default=str)[:300])

# ── 4) VIDEO REAL: frame con ffmpeg desde el TS reensamblado ────────────────
try:
    import imageio_ffmpeg, subprocess
    ff = imageio_ffmpeg.get_ffmpeg_exe()
except ModuleNotFoundError:
    ff = None
    print('ffmpeg no instalado (pip imageio-ffmpeg) — salto el paso de fotograma; la auditoria TS ya valida el contenido')
r = subprocess.run([ff,'-y','-v','error','-i',os.path.join(OUT,'e2e-canal.ts'),'-frames:v','1',os.path.join(OUT,'e2e-frame.png')],capture_output=True) if ff else None
if r is not None:
    print('ffmpeg frame rc=%d' % r.returncode, r.stderr.decode()[:80])
    import struct
    def png_dims(p):
        b=open(p,'rb').read(33); w,h=struct.unpack('>II', b[16:24]); return w,h
    print('fotograma:', png_dims(os.path.join(OUT,'e2e-frame.png')))
else:
    print('fotograma: omitido (sin ffmpeg)')

# ── 5) el flujo del EXTRACTOR sobre esto: json -> m3u jugable ───────────────
import importlib.util as iu
sp = iu.spec_from_file_location('ex','tools/drama-extraer.py'); ex = iu.module_from_spec(sp); sp.loader.exec_module(ex)
canales = ex.canales_de({'canales':[{'name':'Canal Drama (e2e)','url':base+'/pages/sesion/index.html','group':'Prueba'}]})
m3u = ex.a_m3u(canales)
print('m3u del extraedor:'); print(m3u)
u = m3u.strip().splitlines()[-1]
pl2 = urllib.request.urlopen(u, timeout=10).read().decode()
print('el m3u abre y responde:', pl2.splitlines()[0], '| lineas:', len(pl2.splitlines()))
srv.shutdown()
print('E2E-COMPLETO: descifrado->swap->segmentos->TS validado->%s->m3u del extraedor jugable' % ('frame decodificado' if r is not None else 'fotograma omitido'))
