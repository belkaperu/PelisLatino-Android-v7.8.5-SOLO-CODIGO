import base64, json, os, time, urllib.request, urllib.error
from Crypto.Cipher import AES
KEY = b'43fc9b1a3f854049'
UA = 'Mozilla/5.0 (Linux; Android 15; SM-A156M) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Mobile Safari/537.36'
SESI = json.load(open('/home/user/case/sesion_usuario.json'))
GLT = SESI['plantillas']['glt']
LA = 'https://log.fastconnections.online/api/live/livedrama/v9.0.0/la'
GLBR = 'https://re.highperformanceconnect.shop/redirect/glbr'
GLBT = 'https://li.highperformanceconnect.shop/api/live/livedrama/v13.0.0/glbt'
def enc(obj):
    pt = json.dumps(obj, separators=(',', ':'), ensure_ascii=False).encode()
    n = 16 - len(pt) % 16; pt += bytes([n]) * n
    iv = os.urandom(16)
    return base64.b64encode(AES.new(KEY, AES.MODE_CBC, iv).encrypt(pt)).decode() + ':' + base64.b64encode(iv).decode()
def dec(txt):
    try:
        c, i = txt.strip().rsplit(':', 1)
        pt = AES.new(KEY, AES.MODE_CBC, base64.b64decode(i)).decrypt(base64.b64decode(c))
        return json.loads(pt[:-pt[-1]].decode('utf-8','replace'))
    except Exception: return None
def post(url, body, label):
    r = urllib.request.Request(url, data=enc(body).encode(), headers={'User-Agent': UA, 'Content-Type': 'text/plain;charset=UTF-8'})
    try:
        with urllib.request.urlopen(r, timeout=20) as resp: code, txt = resp.status, resp.read().decode('utf-8','replace')
    except urllib.error.HTTPError as e: code, txt = e.code, e.read().decode('utf-8','replace')
    j = dec(txt)
    print(f'[{label}] http={code} dec={"SI" if j else "NO"}' + ('' if j else ' raw=' + txt[:60].replace(chr(10),' ')))
    return j
items = post(GLBT, {**GLT, 'topic': 'hot_now'}, 'glbt')['live']
ch = next(it for it in items if it.get('id_live') == 'live_tv_beinsport1')
def gbody(run, sh, cm):
    b = dict(GLT)
    b.update({'id': ch['id_live'], 'url': '1', 'agent': 'all_streams_redirect', 'token': GLT['user_id'],
              'rc': 0, 'adsShowed': sh, 'adsComplete': cm, 'runCount': run, 'timeZone': GLT['timezone'],
              'appCount': json.dumps({'runCount': run, 'adsLoaded': 2, 'adsShowed': sh, 'adsFailed': 0})})
    b.pop('topic', None)
    return b
def glbr(label, run, sh, cm):
    j = post(GLBR, gbody(run, sh, cm), label)
    u = ((j or {}).get('data') or {}).get('url') or ''
    print(f'   => {u[:80]!r}', '*** REAL ***' if (u.startswith('http') and 'update.mp4' not in u and 'twoapistack' not in u) else 'SUEÑO')
    return u
print('--- estado 1: glbr (el server responde sueno-ad) ---')
u = glbr('glbr#1 (run0)', 0, 0, 0)
print('--- estado 2: la con appCount adsShowed=1 (el sitio registra el ad visto) ---')
post(LA, {**GLT, 'appCount': json.dumps({'runCount': 1, 'adsLoaded': 1, 'adsShowed': 1, 'adsFailed': 0}), 'type': 'tv'}, 'la#1')
print('--- estado 3: glbr de nuevo (runCount=1, adsShowed=1) ---')
u = glbr('glbr#2 (run1,ads1)', 1, 1, 1)
print('--- estado 4: repetir GLABRO idéntico (por si es 2-step puro) ---')
time.sleep(3)
u = glbr('glbr#3 (run1,ads1)', 1, 1, 1)
