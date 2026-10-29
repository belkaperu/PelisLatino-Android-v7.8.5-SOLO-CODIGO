#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""PRUEBA-PYTHON v3.2.29 — LA CADENA DEL PLAY REAL (la misma que va en la app).

Medida desde el datacenter 12/10 22:39: glt 201 -> glasbi 201 marcador .LS.V2 ->
glbr 201 agent 'advanced' -> pagina /pages/.../index.html que ES playlist #EXTM3U ->
segmentos SIN el prefijo k9x_ = MPEG-TS 200 -> PyAV: h264 852x480 + aac, 25 frames.
(update.mp4 de la ruta all_streams_redirect NO aparece por ningun lado: era la deco.)

Uso:   pip install pycryptodome av
       python3 proof_play_demo.py [id_live]        (default live_tv_beinsport1)

En red residencial deberia dar igual o mejor: glasbi glasea con el glt del MISMO
dispositivo minutos antes (por eso el paso 0). Si un paso sale en blanco, es puerta
anti-flood del edge — espera 2-3 minutos y corre de nuevo (medido: blaquea tras
muchos golpes seguidos y se levanta solo).
"""
import base64
import json
import os
import re
import subprocess
import sys
import time
import urllib.request

from Crypto.Cipher import AES

KEY = b'43fc9b1a3f854049'  # ASCII de 16, como la usa el sitio (NO hex-decodificada)
BASE = 'https://li.highperformanceconnect.shop/api/live/livedrama/v13.0.0/'
GLBR = 'https://re.highperformanceconnect.shop/redirect/glbr'
UA = ('Mozilla/5.0 (Linux; Android 15; SM-A156M) AppleWebKit/537.36 (KHTML, like Gecko) '
      'Chrome/154.0.0.0 Mobile Safari/537.36')
HERE = os.path.dirname(os.path.abspath(__file__))

# Plantilla glt ANONIMA capturada del sitio el 12/10 (funciono sin registro). Glasbi
# exige estos campos + token + contadores; con {id} pelado responde 200 en blanco.
GLT = {"user_id": "1791660633397000_notloggedin.com_dramalive3",
       "device_id": "ff005b6d-8252-4041-adf1-96a159e2880c",
       "device_api": "chrome/154.0.8037.57", "version_name": "web_241", "language": "en",
       "timezone": "America/Lima", "device_type": "phone", "os_platform": "web",
       "os": "android", "KEY_ACTIVATED_TYPE": "232425", "store": "web",
       "isStoreVersion": True, "isPremium": False, "isCoupon_active": False,
       "hideAds": False,
       "appCount": "{\"runCount\":8,\"adsLoaded\":2,\"adsShowed\":7,\"adsFailed\":1}",
       "type": "tv"}


def _pad(b):
    n = 16 - len(b) % 16
    return b + bytes([n]) * n


def enc(obj):
    iv = os.urandom(16)
    ct = AES.new(KEY, AES.MODE_CBC, iv).encrypt(_pad(json.dumps(obj, separators=(',', ':')).encode()))
    return base64.b64encode(ct).decode() + ':' + base64.b64encode(iv).decode()


def dec(raw):
    txt = raw.decode('utf-8', 'replace').strip() if isinstance(raw, bytes) else (raw or '').strip()
    if not txt:
        return None
    if txt[0] in '{[':
        return json.loads(txt)
    i = txt.rfind(':')
    iv = base64.b64decode(txt[i + 1:])
    pt = AES.new(KEY, AES.MODE_CBC, iv).decrypt(base64.b64decode(txt[:i]))
    return json.loads(pt[:-pt[-1]].decode('utf-8', 'replace'))


def post(url, body):
    req = urllib.request.Request(url, data=enc(body).encode(), headers={
        'User-Agent': UA, 'Content-Type': 'text/plain;charset=UTF-8',
        'Origin': 'https://web.dramalive.cc', 'Referer': 'https://web.dramalive.cc/'})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.status, dec(r.read())


def get_bin(url, hdr):
    """GET binario por curl: el CDN del player no pide nada, pero llevamos lo que pidio."""
    args = ['curl', '-sS', '-m', '75', '-o', '-']
    args += ['-A', (hdr or {}).get('User-Agent') or UA]
    if hdr and hdr.get('Referer'):
        args += ['-e', hdr['Referer']]
    args += [url]
    p = subprocess.run(args, capture_output=True)
    if p.returncode != 0:
        raise RuntimeError('curl rc=%d %s' % (p.returncode, p.stderr.decode()[:120]))
    return p.stdout


def main():
    cid = sys.argv[1] if len(sys.argv) > 1 else 'live_tv_beinsport1'
    auth = dict(GLT)
    auth.update(token=GLT['user_id'], rc=0, adsShowed=1, adsComplete=1, runCount=1,
                timeZone=GLT['timezone'])
    print('CANAL:', cid)

    st, j0 = post(BASE + 'glt', GLT)
    print('0) glt -> HTTP %s topics=%s  (glasea la sesion del device para glasbi)'
          % (st, len((j0 or {}).get('live_topics') or [])))

    marca, agent = '', 'redirect'
    for intento in range(1, 4):
        st1, j1 = post(BASE + 'glasbi', dict(auth, id=cid))
        lv = (j1 or {}).get('live') or {}
        marca = str(lv.get('url') or '')
        agent = str(lv.get('agent') or 'redirect')
        print('1) glasbi try%d -> HTTP %s result=%s live.url=%s agent=%s'
              % (intento, st1, (j1 or {}).get('result'), marca[:72], agent))
        if '.LS.' in marca:
            break
        time.sleep(20)
    if '.LS.' not in marca:
        print('   SIN marcador: el edge te esta blaqueando (anti-flood) o el id no es de tv live.'
              '\n   Espera unos minutos y reintenta; o proba otro id (live_tv_beinsport5, etc).')
        sys.exit(1)

    st2, j2 = post(GLBR, dict(auth, id=cid, url=marca, agent=agent))
    data = (j2 or {}).get('data') or {}
    raw = str(data.get('url') or '')
    av = json.loads(raw) if raw.startswith('{') else {'url': raw}
    purl, hdr, swap = av.get('url') or '', av.get('headers') or {}, av.get('swap') or {}
    print('2) glbr -> HTTP %s agent=%s mediatype=%s\n   pagina=%s\n   swap=%s'
          % (st2, data.get('agent'), av.get('mediatype'), purl[:92], json.dumps(swap)))
    if '/pages/' not in purl:
        print('   no es la pagina-player (recibi %r) — mandame esta salida tal cual' % raw[:80])
        sys.exit(1)

    pl = get_bin(purl, hdr).decode('utf-8', 'replace')
    if not pl.lstrip().startswith('#EXTM3U'):
        print('3) la pagina no trae playlist (largo=%d). Primeros 80: %r' % (len(pl), pl[:80]))
        sys.exit(1)
    segs = [l.strip() for l in pl.splitlines() if l.strip() and not l.startswith('#')]
    for pref in swap:
        segs = [s[len(pref):] if s.startswith(pref) else s for s in segs]
    base = purl[:purl.rfind('/') + 1]
    print('3) playlist live: %d segmentos (window=%ss)'
          % (len(segs), (re.search(r'TARGETDURATION:(\d+)', pl) or [0, '?'])[1]))

    ts = os.path.join(HERE, 'play_live.ts')
    trozos = []
    for s in (segs[-3:] or segs):
        u = s if s.startswith('http') else base + s
        d = get_bin(u, hdr)
        ok = d[:1] == b'\x47'
        print('   seg -> %d bytes %s' % (len(d), 'MPEG-TS OK' if ok else 'NO TS'))
        if not ok:
            print('   (el prefijo ya no es k9x_? swap recibido: %s) cortala y mandame esto' % json.dumps(swap))
            sys.exit(1)
        trozos.append(d)
    with open(ts, 'wb') as f:
        f.write(b''.join(trozos))
    print('4) %s = %d bytes — VLC lo reproduce YA (ahi esta beIN)' % (ts, os.path.getsize(ts)))

    try:
        import av
    except ImportError:
        print('5) sin PyAV (pip install av) — play_live.ts ES la prueba; VLC o ffprobe valen')
        return
    cont = av.open(ts)
    v = cont.streams.video[0]
    a = cont.streams.audio[0] if len(cont.streams.audio) else None
    n, frame = 0, None
    for f in cont.decode(video=0):
        n += 1
        frame = f
        if n >= 25:
            break
    png = os.path.join(HERE, 'play_frame.png')
    frame.to_image().save(png)
    print('5) PyAV: dur=%.1fs | %s %sx%s + %s | %d frames -> %s'
          % (float(cont.duration or 0) / av.time_base, v.codec_context.name,
             v.codec_context.width, v.codec_context.height,
             a.codec_context.name if a else '-', n, png))
    print('CONCLUSION: frame = PLAY REAL de %s. La app v3.2.29 porta EXACTAMENTE esta cadena' % cid)


if __name__ == '__main__':
    main()
