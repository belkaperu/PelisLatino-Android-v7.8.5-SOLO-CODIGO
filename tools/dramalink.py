#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
dramalink.py — cifra y descifra los enlaces `#U2FsdGVkX1...` de los players tipo
"Drama Live Player" (p. ej. https://li.highperformanceconnect.shop/player/player.html).

Medido sobre su `player.js` (61.755 B, 2026-10-07): el fragmento NO es un token de
acceso, es la URL del stream cifrada con CryptoJS para que no se pueda copiar/pegar:

    clave    = (meta[name="app-id"].id || "") + "_YOUR_SECRET"
    blob     = base64url( "Salted__" || salt(8) || AES-256-CBC/PKCS7 )
    material = OpenSSL/CryptoJS EVP_BytesToKey(MD5) -> key(32 B) + iv(16 B)
    texto    = AES.decrypt(blob, clave).toString(Utf8)  -> JSON {url, title, drm, ttl, ...}

El HTML que sirve el host público trae `<meta name="app-id" content="REPLACE_WITH">`;
el código del player lee el atributo `id` del elemento (nunca `content`), que está
vacío, así que la clave "pública" queda en la literal `_YOUR_SECRET`: un placeholder
que el host debe sustituir al desplegar. Si no lo sustituye, ni el propio player abre
SU enlace — y nadie más tampoco. El player llama a ese fallo:
"Decryption failed — wrong key or corrupted hash."

Dependencias: sólo la librería estándar. El motor AES está en `tools/aes_bloque.py`
(núcleo de pyaes, MIT) y está verificado contra pycryptodome y contra CryptoJS 4.2.0:

    python3 tools/dramalink.py verificar
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import re
import sys
import urllib.parse
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from aes_bloque import aes_decrypt_cbc, aes_encrypt_cbc   # noqa: E402


# ======================================================================
# base64url "al estilo CryptoJS" + EVP_BytesToKey
# ======================================================================

def normalizar_base64url(texto: str) -> str:
    """`_` -> `/`, `-` -> `+` y relleno; idéntico a la función `n()` del player."""
    t = (texto or '').strip().strip('"\'`<>').strip()
    if t.startswith('#'):
        t = t[1:]
    t = re.sub(r'\s+', '', t).replace('-', '+').replace('_', '/')
    while len(t) % 4:
        t += '='
    return t


def extraer_hash(entrada: str) -> str:
    """Acepta el hash pelado, el fragmento con `#` o la URL completa del player."""
    e = (entrada or '').strip()
    m = re.search(r'(U2FsdGVkX1[0-9A-Za-z_\-+/=]*)', e)
    if m:
        return m.group(1)
    if e.lower().startswith(('http://', 'https://')):
        return urllib.parse.urldefrag(e)[1]
    return e.lstrip('#')



def evp_bytes_tokey(passphrase: str, salt: bytes, n: int = 48) -> bytes:
    """Derivación de clave/IV que usan CryptoJS y OpenSSL (EVP_BytesToKey, MD5):

        K   = MD5(pass || salt)
        D_i = MD5(D_{i-1} || pass || salt)
        material = D_0 || D_1 || ...   recortado a n   -> key = [0:32], iv = [32:48]

    Medido contra crypto-js 4.2.0 con el salt del propio blob (`Salted__`+8 B):
    para pass="_YOUR_SECRET" y salt f42936e0b062d115 da
    key = e942f1bc5bf7cab92060b1005f6b74375aff0bc383a798032b300d219c13f42f e
    iv  = 01c4fbe53bf2096a3985e78acac00937, que es lo que produce el `keySize:8,
    ivSize:4` del `EvpKDF` interno de `CryptoJS.AES`. Ver subcomando `verificar`.
    """
    p = passphrase.encode('utf-8')
    out = b''
    d = b''
    while len(out) < n:
        d = hashlib.md5(d + p + salt).digest()
        out += d
    return out[:n]


def parsear_salted(blob: bytes):
    if len(blob) < 16 or blob[:8] != b'Salted__':
        raise ValueError('no empieza con "Salted__": no es un blob de CryptoJS')
    ct = blob[16:]
    if not ct or len(ct) % 16:
        raise ValueError('cifrado de %d bytes: no es múltiplo de bloque' % len(ct))
    return blob[8:16], ct


def descifrar_blob(blob_b64: str, passphrase: str) -> bytes:
    blob = base64.b64decode(normalizar_base64url(blob_b64))
    salt, ct = parsear_salted(blob)
    k = evp_bytes_tokey(passphrase, salt, 48)
    return aes_decrypt_cbc(k[:32], k[32:48], ct)


def cifrar_a_base64url(texto: str, passphrase: str, salt: bytes | None = None) -> str:
    salt = salt or os.urandom(8)
    k = evp_bytes_tokey(passphrase, salt, 48)
    ct = aes_encrypt_cbc(k[:32], k[32:48], texto.encode('utf-8'))
    return base64.b64encode(b'Salted__' + salt + ct).decode('ascii') \
        .replace('+', '-').replace('/', '_').rstrip('=')


# ======================================================================
# subcomandos
# ======================================================================

CLAVES_POR_DEFECTO = ['_YOUR_SECRET', 'REPLACE_WITH_YOUR_SECRET', 'REPLACE_WITH', '',
                      'dramalive', 'DramaLive', '9DFB77C0', '6399312afcQeZ']
IMPRIMIBLE = re.compile(rb'^[\x09\x0a\x0d\x20-\x7e\x80-\xff]+$')


def _leer(entrada: str) -> str:
    if entrada == '-':
        return sys.stdin.read()
    if os.path.exists(entrada):
        return open(entrada, encoding='utf8').read()
    return entrada


def cmd_descifrar(args) -> int:
    h = extraer_hash(_leer(args.entrada))
    if h.startswith('http://') or h.startswith('https://'):
        # modo PLAIN medido (grupo peruano 190.93.224.42): el fragmento ya es la URL,
        # el propio player la usa tal cual sin pasar por CryptoJS. Nada que descifrar.
        print('fragmento PLAIN -> la URL ya esta a la vista (sin cifrado):\n  %s' % h)
        print('\npégala en la app: En vivo -> Fuente -> Pegar lista o URL de un canal.')
        return 0
    if not h:
        print('no encuentro el hash en la entrada')
        return 2
    try:
        raw = base64.b64decode(normalizar_base64url(h))
    except Exception as ex:  # noqa: BLE001
        print('base64 inválido: %s' % ex)
        return 2
    print('hash      : %d caracteres -> %d bytes' % (len(h), len(raw)))
    try:
        salt, ct = parsear_salted(raw)
    except ValueError as ex:
        print('PROBLEMA  : %s' % ex)
        print('            -> el pegado está incompleto: copia el fragmento entero.')
        return 2
    print('magic     : Salted__ (CryptoJS/OpenSSL)')
    print('salt      : %s' % salt.hex())
    print('cifrado   : %d B = %d bloque(s) AES' % (len(ct), len(ct) // 16))
    print('nota      : la clave se arma como (meta[name="app-id"].id || "") + "_YOUR_SECRET";')
    print('            este host sirve el meta con content="REPLACE_WITH" y nunca pone un id,')
    print('            así que la clave pública queda en la literal "_YOUR_SECRET".')
    claves = args.clave or CLAVES_POR_DEFECTO
    for c in claves:
        try:
            pt = descifrar_blob(h, c)
        except Exception:  # noqa: BLE001
            continue
        if pt and len(pt) >= 8 and IMPRIMIBLE.match(pt):
            print('\nCLAVE     : %r -> DESCIFRADO' % c)
            return _mostrar(pt)
    print('\nRESULTADO : ninguna de las %d clave(s) probadas da texto válido.' % len(claves))
    print('            La clave real no está en el fichero público: el host la inyecta en')
    print('            el `id` del <meta name="app-id"> al servir la página. Sin ese valor')
    print('            el enlace no se abre — ni aquí ni desde su propio reproductor.')
    print('            (en el player eso es exactamente el error "Decryption failed —')
    print('             wrong key or corrupted hash".)')
    return 3


def _mostrar(pt: bytes) -> int:
    txt = pt.decode('utf-8', 'replace')
    print('\n-------- texto --------')
    print(txt[:4000])
    try:
        obj = json.loads(txt)
    except Exception:  # noqa: BLE001
        return 0
    if isinstance(obj, dict):
        print('\n-------- campos --------')
        for llave in ('url', 'title', 'name', 'drm', 'referer', 'referrer', 'headers',
                      'ttl', 'audio_codec', 'hlsjs'):
            if llave in obj:
                print('  %-10s %s' % (llave + ':', json.dumps(obj[llave], ensure_ascii=False)[:300]))
        if isinstance(obj.get('headers'), dict):
            print('\n  el enlace trae cabeceras propias: pégalas en la app en')
            print('  "En vivo -> Fuente -> Encabezados al abrir" (una por línea).')
        if obj.get('url'):
            print('\n  para la app basta esa URL: "En vivo -> Fuente -> Pegar lista o URL de un canal".')
    return 0


def cmd_cifrar(args) -> int:
    payload = {'url': args.url}
    if args.titulo:
        payload['title'] = args.titulo
    texto = json.dumps(payload, ensure_ascii=False, separators=(',', ':'))
    clave = args.clave_unica or '_YOUR_SECRET'
    b = cifrar_a_base64url(texto, clave, bytes.fromhex(args.salt) if args.salt else None)
    control = descifrar_blob(b, clave).decode('utf-8')
    print('JSON        : %s' % texto)
    print('clave       : %r' % clave)
    print('hash (%d B)  :\n%s' % (len(b), b))
    print('\nenlace para el player de Drama (sólo abre si ESE host usa tu clave):')
    print('  https://li.highperformanceconnect.shop/player/player.html?fit=contain#%s' % b)
    raiz = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    print('\nenlace para el player local de este repo (file://, sin tocar la red):')
    print('  file://%s#%s' % (os.path.join(raiz, 'player-local', 'index.html'), b))
    print('\ncontrol redescifrado: %s' % control)
    return 0 if control == texto else 1


def cmd_verificar(args) -> int:
    mal = []
    aqui = os.path.dirname(os.path.abspath(__file__))
    try:
        from Crypto.Cipher import AES
        for L in (16, 24, 32):
            for _ in range(8):
                key = os.urandom(L)
                pt = os.urandom(16)
                if bytes(_AES(pt, key)) != AES.new(key, AES.MODE_ECB).encrypt(pt):
                    mal.append('AES-%d vs pycryptodome' % (L * 8))
                    break
        print('  motor AES vs pycryptodome          : %s' % ('ok (AES-128/192/256, 24 bloques)'
                                                             if not mal else 'MAL'))
    except Exception as ex:  # noqa: BLE001
        print('  motor AES vs pycryptodome          : (sin pycryptodome: %s)' % type(ex).__name__)
    vec = args.vectores or os.path.join(os.path.dirname(aqui), 'docs', 'vectores-cryptojs.json')
    datos = json.load(open(vec, encoding='utf8'))
    n0 = len(mal)
    for i, d in enumerate(datos):
        if descifrar_blob(cifrar_a_base64url(d['texto'], d['clave'], bytes.fromhex(d['salt'])),
                          d['clave']).decode() != d['texto']:
            mal.append('roundtrip %d' % (i + 1))
            continue
        pt = descifrar_blob(d['hash'], d['clave']).decode('utf-8', 'replace')
        if pt != d['texto']:
            mal.append('cryptojs %d (%r != %r)' % (i + 1, pt[:30], d['texto'][:30]))
    print('  %d vectores CryptoJS 4.2.0        : %s  (texto %d..%d B, claves %d..%d chars)'
          % (len(datos), 'ok' if len(mal) == n0 else 'MAL',
             min(len(d['texto']) for d in datos), max(len(d['texto']) for d in datos),
             min(len(d['clave']) for d in datos), max(len(d['clave']) for d in datos)))
    if mal:
        print('MALOS: ' + '; '.join(mal))
        return 1
    return 0


def _AES(pt: bytes, key: bytes):
    from aes_bloque import _AESBloque
    return _AESBloque(key).encrypt(list(pt))


def cmd_probar(args) -> int:
    h = extraer_hash(_leer(args.entrada))
    url = txt = None
    for c in (args.clave or CLAVES_POR_DEFECTO):
        try:
            t = descifrar_blob(h, c).decode('utf-8', 'replace')
        except Exception:  # noqa: BLE001
            continue
        if t.lstrip().startswith('{'):
            txt = t
            try:
                url = json.loads(t).get('url')
            except Exception:  # noqa: BLE001
                url = None
            break
    if not url:
        print('no se pudo descifrar (%s) -> nada que probar' % (txt or 'sin texto'))
        return 2
    cab = {'User-Agent': 'Mozilla/5.0'}
    if args.referer:
        cab['Referer'] = args.referer
    req = urllib.request.Request(url, headers=cab)  # noqa: S310
    with urllib.request.urlopen(req, timeout=25) as r:  # noqa: S310
        cuerpo = r.read(60000).decode('utf-8', 'replace')
        print('HTTP %d %s (%d B)' % (r.status, r.headers.get('content-type'), len(cuerpo)))
    if '#EXTM3U' not in cuerpo:
        print('no es un playlist HLS')
        return 3
    var = re.findall(r'RESOLUTION=(\d+)x(\d+).*?BANDWIDTH=(\d+)', cuerpo)
    if var:
        mejor = max(var, key=lambda t: int(t[2]))
        print('master con %d variantes, mejor %sx%s @ %d kbps'
              % (len(var), mejor[0], mejor[1], int(mejor[2]) // 1000))
    if args.ffmpeg:
        import subprocess
        destino = args.salir or '/tmp/dramalink-proba.ts'
        rr = subprocess.run([args.ffmpeg, '-hide_banner', '-loglevel', 'error', '-t', str(args.segundos),
                             '-user_agent', cab['User-Agent'], '-i', url, '-c', 'copy', destino],
                            capture_output=True, text=True, timeout=300)
        print('ffmpeg rc=%d %s' % (rr.returncode, (rr.stderr or '').strip()[:300]))
        if rr.returncode == 0:
            print('grabado %s (%d B)' % (destino, os.path.getsize(destino)))
    else:
        print('playlist válido -> pégalo en la app (En vivo -> Fuente -> Pegar lista o URL de un canal)')
    return 0


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog='dramalink.py',
                                 description='enlaces cifrados de players tipo Drama Live')
    sub = ap.add_subparsers(dest='cmd', required=True)
    d = sub.add_parser('descifrar', help='abrir el hash del enlace y mostrar la URL')
    d.add_argument('entrada', help='hash, URL completa o "-" por stdin')
    d.add_argument('--clave', action='append', help='passphrase candidata (repetible)')
    d.set_defaults(fn=cmd_descifrar)
    c = sub.add_parser('cifrar', help='fabricar tu propio enlace cifrado')
    c.add_argument('--url', required=True)
    c.add_argument('--titulo')
    c.add_argument('--clave-unica', default='')
    c.add_argument('--salt', help='8 bytes en hex, para salida reproducible')
    c.set_defaults(fn=cmd_cifrar)
    v = sub.add_parser('verificar', help='comprobar el motor contra CryptoJS 4.2.0 y pycryptodome')
    v.add_argument('--vectores')
    v.set_defaults(fn=cmd_verificar)
    p = sub.add_parser('probar', help='descifrar y pedir la URL (¿reproduce de verdad?)')
    p.add_argument('entrada')
    p.add_argument('--clave', action='append')
    p.add_argument('--referer')
    p.add_argument('--ffmpeg')
    p.add_argument('--segundos', type=int, default=12)
    p.add_argument('--salir')
    p.set_defaults(fn=cmd_probar)
    args = ap.parse_args(argv)
    return args.fn(args)


if __name__ == '__main__':
    sys.exit(main())
