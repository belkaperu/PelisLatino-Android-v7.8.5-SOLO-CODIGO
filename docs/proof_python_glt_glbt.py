#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v2 — LA cadena COMPLETA en Python con el descubrimiento de la hora: el edge exige
UA Chrome-movil (sin el: 403 CF-1010). glt -> topics -> glbt -> glbr con 3 variantes de
contadores de ads sobre UNA canal. Todo medido, pocas requests."""
import base64, json, os, sys, time, urllib.request, urllib.error
from Crypto.Cipher import AES

KEY = b'43fc9b1a3f854049'
UA = 'Mozilla/5.0 (Linux; Android 15; SM-A156M) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Mobile Safari/537.36'
SESI = json.load(open('/home/user/case/sesion_usuario.json'))
GLT_URL = SESI['url']
GLT = SESI['plantillas']['glt']
GLBT_URL = 'https://li.highperformanceconnect.shop/api/live/livedrama/v13.0.0/glbt'
GLBR_URL = 'https://re.highperformanceconnect.shop/redirect/glbr'

def enc(obj):
    pt = json.dumps(obj, separators=(',', ':'), ensure_ascii=False).encode()
    n = 16 - len(pt) % 16; pt += bytes([n]) * n
    iv = os.urandom(16)
    return base64.b64encode(AES.new(KEY, AES.MODE_CBC, iv).encrypt(pt)).decode() + ':' + base64.b64encode(iv).decode()

def dec(txt):
    try:
        c, i = txt.strip().rsplit(':', 1)
        pt = AES.new(KEY, AES.MODE_CBC, base64.b64decode(i)).decrypt(base64.b64decode(c))
        pt = pt[:-pt[-1]]
        return json.loads(pt.decode('utf-8', 'replace'))
    except Exception:
        return None

def post(url, body, label):
    r = urllib.request.Request(url, data=enc(body).encode(), headers={
        'User-Agent': UA, 'Content-Type': 'text/plain;charset=UTF-8',
        'Accept': '*/*', 'Origin': 'https://web.dramalive.cc', 'Referer': 'https://web.dramalive.cc/'})
    try:
        with urllib.request.urlopen(r, timeout=20) as resp:
            code, txt = resp.status, resp.read().decode('utf-8', 'replace')
    except urllib.error.HTTPError as e:
        code, txt = e.code, e.read().decode('utf-8', 'replace')
    j = dec(txt)
    print(f'[{label}] http={code} dec={"SI" if j else "NO"}')
    return j

print('== 1) glt ==')
glt = post(GLT_URL, dict(GLT), 'glt')
if not glt: sys.exit('glt muerto, paro')
topics = glt.get('live_topics') or []
print('result=', glt.get('result'), '| live_topics:', topics[:12], '(total', len(topics), ')')

print('\n== 2) glbt del primer topic ==')
canales = []
for tp in (topics or ['live_tv'])[:3]:
    body = dict(GLT); body['topic'] = tp
    r = post(GLBT_URL, body, f'glbt:{tp}')
    if not r: continue
    data = r.get('live') or r.get('data') or []
    if isinstance(data, dict):
        for k, v in data.items():
            if isinstance(v, list): data = v; break
    if isinstance(data, list):
        for it in data:
            if isinstance(it, dict): canales.append((tp, it))
    print(f'  {tp}: acumulado {len(canales)} items', '| keys item0:', sorted(canales[0][1].keys())[:12] if canales else '-')
    if len(canales) >= 60: break
if not canales: sys.exit('sin canales, paro')
json.dump([dict(topic=t, **{k: v for k, v in it.items() if not isinstance(v, (dict, list))}) for t, it in canales[:80]],
          open('/home/user/py_canal/canales.json', 'w'), ensure_ascii=False, indent=1)

print('\n== 3) glbr UNA canal, 3 variantes ==')
def buscar(frases):
    for t, it in canales:
        s = json.dumps(it).lower()
        if any(f in s for f in frases): return t, it
    return canales[0]
tp, ch = buscar(['beinsport', 'bein sport', ' bein'])
print('canal:', json.dumps({k: ch.get(k) for k in list(ch)[:8]}, ensure_ascii=False)[:260])

def glbr_body(sh, cm, rc, run):
    b = dict(GLT)
    b.update({'id': ch.get('id_live') or ch.get('id'), 'url': ch.get('url') or '1',
              'agent': ch.get('agent') or 'all_streams_redirect',
              'token': GLT.get('user_id'), 'rc': rc, 'adsShowed': sh, 'adsComplete': cm,
              'runCount': run, 'timeZone': GLT.get('timezone'),
              'adsShowed': sh, 'adsComplete': cm})
    b['appCount'] = json.dumps({'runCount': run, 'adsLoaded': max(sh, 1), 'adsShowed': sh, 'adsFailed': 0})
    b.pop('topic', None)
    return b

for nombre, (sh, cm, rc, run) in [('A sin-ads (como hoy la app)', (0, 0, 0, 0)),
                                   ('B ads-vistas', (1, 1, 1, 1)),
                                   ('C usuario-maduro', (7, 7, 2, 8))]:
    r = post(GLBR_URL, glbr_body(sh, cm, rc, run), f'glbr {nombre}')
    if r:
        u = ((r.get('data') or {}).get('url') or '')
        print(f'   {nombre}: result={r.get("result")} url={u[:100]!r}',
              '=>', 'SUEÑO' if 'update.mp4' in u else ('REAL' if u.startswith('http') else 'vacio'))
    time.sleep(1.5)
