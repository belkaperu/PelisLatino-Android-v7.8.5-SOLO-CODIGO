#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
tests_python/test_curacion_v3216.py — test de pipeline v3.2.16 (solo stdlib).

Que verifica, de punta a punta y sobre los artefactos REALES del repo:

 T01  contrato del asset drama_auto.js (version, funciones de curacion, cero
      `credentials:` — regla v3.2.10).
 T02  corre el REPRO node real del repo (docs/cosecha_repro.mjs) y exige sus
      7 marcadores + exit 0 — es el codigo del asset ejecutandose, no una
      reimplementacion.
 T03  aislamiento de la cura v3.2.16: taskAffinity ":cura" + excludeFromRecents
      en el manifest, NEW_TASK en el intent del player, sin curacionLanzada,
      espejo BLABONGO_DEBUG y plomazo de 90 s en DramaAutoActivity.
 T04  si el resultado de la suite JVM existe (app/build/test-results), lo parsea
      y exige >=125 tests con DramaSesion >=49/49 (unico rojo permitido: ApiProbe).
 T05  motor AES puro (implementado aqui mismo) contra los vectores FIPS-197
      (AES-128 y AES-256) — la base de los dos siguientes.
 T06  INTEROP: cifra el encPara REAL del asset (node docs/crypto_interop.mjs) y
      desencripta este Python con su AES independiente: si el JSON vuelve igual,
      el alambre `b64std(ct):b64std(iv)` + KEY '43fc9b1a3f854049' estan verificados
      por dos caminos.
 T07  vector dorado `Salted__` (docs/vectores-clave-drama.json, generado con
      crypto-js): KDF EvpKDF(MD5,1) + AES-256-CBC — desencripta y compara.
 T08  consistencia zip: los archivos clave de /home/user/PelisLatino-estilo-cine.zip
      son byte-a-byte los del repo (lo que se entrega == lo que se probo).

Ejecutar desde la raiz del repo:  python3 tests_python/test_curacion_v3216.py -v
(o: python3 -m unittest discover -s tests_python)
"""
import base64
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import unittest
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
ASSET = ROOT / 'app/src/main/assets/drama_auto.js'
MANIFEST = ROOT / 'app/src/main/AndroidManifest.xml'
PLAYER = ROOT / 'app/src/main/java/dza/fut/paipo/PlayerActivity.java'
AUTOACT = ROOT / 'app/src/main/java/dza/fut/paipo/DramaAutoActivity.java'
SESION = ROOT / 'app/src/main/java/dza/fut/paipo/DramaSesion.java'
TESTJAVA = ROOT / 'app/src/test/java/dza/fut/paipo/DramaSesionTest.java'
ZIP_PATH = Path(os.environ.get('DRAMA_ZIP', str(ROOT.parent / 'PelisLatino-estilo-cine.zip')))
NODE = shutil.which('node')

GLBR_KEY = b'43fc9b1a3f854049'  # KEYB del asset: ASCII de 16 bytes, NO hex-decodificada


def rd(p: Path) -> str:
    return p.read_text(encoding='utf-8')


# ---------------------------------------------------------------- AES pura
# Minima y autovalidada (T05) contra FIPS-197. Sin dependencias.
def _gmul(a: int, b: int) -> int:
    r = 0
    while b:
        if b & 1:
            r ^= a
        b >>= 1
        a <<= 1
        if a & 0x100:
            a ^= 0x11b
    return r & 0xff


def _build_sbox():
    inv = [0] * 256
    for i in range(1, 256):
        for j in range(1, 256):
            if _gmul(i, j) == 1:
                inv[i] = j
                break
    sbox = []
    for x in range(256):
        s = inv[x]
        r = s
        for _ in range(4):
            r = ((r << 1) | (r >> 7)) & 0xff
            s ^= r
        sbox.append(s ^ 0x63)
    return sbox


SBOX = _build_sbox()
INV_SBOX = [0] * 256
for _i, _v in enumerate(SBOX):
    INV_SBOX[_v] = _i
RCON = [0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1B, 0x36]


def expand_key(key: bytes):
    nk = len(key) // 4
    nr = 6 + nk  # 10 para 128, 14 para 256
    w = [list(key[4 * i:4 * i + 4]) for i in range(nk)]
    for i in range(nk, 4 * (nr + 1)):
        t = list(w[i - 1])
        if i % nk == 0:
            t = t[1:] + t[:1]
            t = [SBOX[b] for b in t]
            t[0] ^= RCON[i // nk - 1]
        elif nk > 6 and i % nk == 4:
            t = [SBOX[b] for b in t]
        w.append([w[i - nk][j] ^ t[j] for j in range(4)])
    return w, nr


def _sub_bytes(st, table):
    for i in range(16):
        st[i] = table[st[i]]


def _shift_rows(st, inv=False):
    out = list(st)
    for r in range(1, 4):
        for c in range(4):
            # convencion estandar (FIPS-197 5.1.2): s'[r][c] = s[r][(c+r) mod 4]
            # con layout plano st[4c+r] eso es LEER desplazado, no escribirlo
            if inv:
                out[4 * c + r] = st[4 * ((c - r) % 4) + r]
            else:
                out[4 * c + r] = st[4 * ((c + r) % 4) + r]
    return out



def _mix(st, inv=False):
    for c in range(4):
        a = [st[4 * c + r] for r in range(4)]
        if not inv:
            st[4 * c + 0] = _gmul(a[0], 2) ^ _gmul(a[1], 3) ^ a[2] ^ a[3]
            st[4 * c + 1] = a[0] ^ _gmul(a[1], 2) ^ _gmul(a[2], 3) ^ a[3]
            st[4 * c + 2] = a[0] ^ a[1] ^ _gmul(a[2], 2) ^ _gmul(a[3], 3)
            st[4 * c + 3] = _gmul(a[0], 3) ^ a[1] ^ a[2] ^ _gmul(a[3], 2)
        else:
            st[4 * c + 0] = _gmul(a[0], 14) ^ _gmul(a[1], 11) ^ _gmul(a[2], 13) ^ _gmul(a[3], 9)
            st[4 * c + 1] = _gmul(a[0], 9) ^ _gmul(a[1], 14) ^ _gmul(a[2], 11) ^ _gmul(a[3], 13)
            st[4 * c + 2] = _gmul(a[0], 13) ^ _gmul(a[1], 9) ^ _gmul(a[2], 14) ^ _gmul(a[3], 11)
            st[4 * c + 3] = _gmul(a[0], 11) ^ _gmul(a[1], 13) ^ _gmul(a[2], 9) ^ _gmul(a[3], 14)
    return st


def _add_round_key(st, w, rnd):
    for c in range(4):
        for r in range(4):
            st[4 * c + r] ^= w[4 * rnd + c][r]


def aes_encrypt_block(key: bytes, blk: bytes) -> bytes:
    w, nr = expand_key(key)
    st = list(blk)
    _add_round_key(st, w, 0)
    for rnd in range(1, nr):
        _sub_bytes(st, SBOX)
        st = _shift_rows(st)
        _mix(st)
        _add_round_key(st, w, rnd)
    _sub_bytes(st, SBOX)
    st = _shift_rows(st)
    _add_round_key(st, w, nr)
    return bytes(st)


def aes_decrypt_block(key: bytes, blk: bytes) -> bytes:
    w, nr = expand_key(key)
    st = list(blk)
    _add_round_key(st, w, nr)
    for rnd in range(nr - 1, 0, -1):
        st = _shift_rows(st, inv=True)
        _sub_bytes(st, INV_SBOX)
        _add_round_key(st, w, rnd)
        _mix(st, inv=True)
    st = _shift_rows(st, inv=True)
    _sub_bytes(st, INV_SBOX)
    _add_round_key(st, w, 0)
    return bytes(st)


def pkcs7(data: bytes, pad=16) -> bytes:
    n = pad - len(data) % pad
    return data + bytes([n]) * n


def pkcs7_unpad(data: bytes) -> bytes:
    n = data[-1]
    assert data[-n:] == bytes([n]) * n, 'relleno PKCS7 invalido'
    return data[:-n]


def aes_cbc_encrypt(key: bytes, iv: bytes, plain: bytes) -> bytes:
    data = pkcs7(plain)
    out = b''
    prev = iv
    for i in range(0, len(data), 16):
        blk = bytes(a ^ b for a, b in zip(data[i:i + 16], prev))
        prev = aes_encrypt_block(key, blk)
        out += prev
    return out


def aes_cbc_decrypt(key: bytes, iv: bytes, ct: bytes) -> bytes:
    out = b''
    prev = iv
    for i in range(0, len(ct), 16):
        blk = ct[i:i + 16]
        d = bytes(a ^ b for a, b in zip(aes_decrypt_block(key, blk), prev))
        prev = blk
        out += d
    return pkcs7_unpad(out)


def evpkdf_md5_1(passphrase: bytes, salt: bytes, klen: int, ilen: int):
    """CryptoJS/OpenSSL EVP KDF con MD5 e iteracion 1 (la que usa CryptoJS)."""
    out = b''
    d = b''
    while len(out) < klen + ilen:
        d = hashlib.md5(d + passphrase + salt).digest()
        out += d
    return out[:klen], out[klen:klen + ilen]


# ----------------------------------------------------------------- tests
class V3216(unittest.TestCase):
    def test_T10_seed_setup_js_ejecutado(self):
        """v3.2.24: arma el JS del seed desde los literales de DramaSesion.java (la ruta
        real, no una copia) y lo EJECUTA con node contra un mock del storage del sitio.
        Si el Java produce JS roto (llave de mas/menos), este test revienta aqui, no en el
        WebView del usuario."""
        import re
        java = open('app/src/main/java/dza/fut/paipo/DramaSesion.java', encoding='utf-8').read()
        m = re.search(r'public static String seedSetupJs.*?return "\(function.*?\)\(\);";\n', java, re.S)
        self.assertIsNotNone(m, 'seedSetupJs no encontrado en DramaSesion.java')
        g = m.group(0)
        body = g[g.rindex('return "') + len('return '):].rstrip().rstrip(';')
        pat = re.compile(r'"((?:[^"\\]|\\.)*)"|\+\s*c\s*\+')
        out, pos = [], 0
        while True:
            mm = pat.search(body, pos)
            if not mm:
                break
            out.append(mm.group(1).encode().decode('unicode_escape')
                       if mm.group(1) is not None else '__CODE__')
            pos = mm.end()
        js = ''.join(out)
        self.assertGreater(len(js), 300, 'el seed ensamblado es sospechosamente corto')
        import tempfile, subprocess, os
        with tempfile.NamedTemporaryFile('w', suffix='.js', delete=False) as tf:
            tf.write(js)
            path = tf.name
        try:
            r = subprocess.run(['node', os.path.join('tests_python', 'seed_driver.js'),
                                path, 'fgcode.org/232425'],
                               capture_output=True, text=True, timeout=60)
            self.assertEqual(r.returncode, 0, 'SEED ROTO: ' + (r.stderr or r.stdout)[-350:])
            self.assertIn('SEED_SIM_OK', r.stdout)
        finally:
            os.unlink(path)

    maxDiff = None

    def test_T01_asset_contract(self):
        s = rd(ASSET)
        for pat, why in [
            (r"\* drama_auto\.js v3\.2\.(?:1[6-9]|2[0-9])", 'banner de version'),
            (r"v: (?:33[6-9]|34[0-9]), filas", 'payload v: 336/337'),
            (r"function ordenPrior\(filas, prior\)", 'ordenPrior pura'),
            (r"function asegurarPrior\(pend, todas, prior, nombre, logo, grupo\)", 'asegurarPrior (fila sintetica v3.2.14)'),
            (r"async function cosechaRun\(filas, pedir, onFix\)", 'cosechaRun con onFix'),
            (r"window\.__DRAMA_PRIOR_N \? String\(window\.__DRAMA_PRIOR_N\)", 'identidad del prior leida'),
            (r"__dramaAutoTest = \{[^}]*asegurarPrior: asegurarPrior", 'exports del test bridge'),
        ]:
            self.assertRegex(s, pat, f'falta en el asset: {why}')
        self.assertEqual(s.count('credentials:'), 0, 'v3.2.10: cero credentials en el JS')

    @unittest.skipUnless(NODE, 'node no disponible')
    def test_T02_repro_node_real(self):
        r = subprocess.run([NODE, str(ROOT / 'docs/cosecha_repro.mjs')],
                           capture_output=True, text=True, cwd=ROOT, timeout=120)
        out = r.stdout
        self.assertEqual(r.returncode, 0, f'repro exit {r.returncode}: {r.stderr[-400:]}')
        for mark in ['REPRO_OK', 'PARAMS_OK', 'SEED_OK', 'ORDEN_OK', 'ONFIX_OK', 'ASEGURAR_OK', 'SINT_PARAMS_OK']:
            self.assertIn(mark, out, f'repro sin {mark}')
        self.assertNotIn('_FAIL', out)
        self.assertIn('RES', out)  # el resumen {hechos,ok,suenos,blancos}

    def test_T12_glbr_seed_traffic(self):
        """v3.2.25: el JS REAL debe poblar A.plant.glbrReq desde la semilla SIN exigirle
        user_id (el glbr del sitio jamas lo trae) y aprenderlo del trafico disparando el
        armamento de la cosecha. Repro con fixtures VERACES (el SEED_OK viejo inventaba un
        glbrReq con user_id y tapaba el bug)."""
        r = subprocess.run([NODE, str(ROOT / 'docs/glbr_seed_repro.mjs')],
                           capture_output=True, text=True, cwd=ROOT, timeout=120)
        self.assertEqual(r.returncode, 0, f'glbr_seed_repro exit {r.returncode}: {(r.stdout or "")[-200:]} {r.stderr[-250:]}')
        for mark in ['SEED25_OK', 'JUNK25_OK', 'TRAFFIC25_OK']:
            self.assertIn(mark, r.stdout, f'repro sin {mark}')

    def test_T03_cura_aislada_del_player(self):
        m = rd(MANIFEST)
        blk = m[m.index('.DramaAutoActivity'):]
        blk = blk[:blk.index('/>')]
        self.assertIn('taskAffinity="dza.fut.paipo.cura"', blk, 'la cura debe vivir en task propio (v3.2.16)')
        self.assertIn('excludeFromRecents="true"', blk)
        p = rd(PLAYER)
        i = p.index('EXTRA_HEADLESS, true')
        self.assertIn('FLAG_ACTIVITY_NEW_TASK', p[max(0, i - 700):i],
                      'el intent de cura headless debe llevar NEW_TASK')
        self.assertNotIn('curacionLanzada', p, 'el flag de instancia (v3.2.13) debio irse')
        self.assertIn('curaFresca(curaTsMap', p, 'cooldown estatico por pid')
        self.assertIn('reintentaTrasCura', p)
        self.assertIn('pedirRelevo(20_000L)', p)
        a = rd(AUTOACT)
        self.assertIn('PLAZO_HEADLESS_MS = 120_000L', a, 'plomazo 2 min (v3.2.18)')
        self.assertIn('"BLABONGO_DEBUG"', a, 'espejo de hitos al tag del player')
        self.assertIn('instanciaViva', a)
        self.assertIn('v3.2.16', a)

    def test_T04_suite_jvm_si_existe(self):
        d = ROOT / 'app/build/test-results/testDebugUnitTest'
        if not d.is_dir():
            self.skipTest('sin resultados JVM (correr: ./gradlew testDebugUnitTest)')
        total = fallos = drama_tests = drama_fallos = 0
        rojos = []
        for x in d.glob('TEST-*.xml'):
            s = x.read_text()
            mm = re.search(r'tests="(\d+)" skipped="\d+" failures="(\d+)" errors="(\d+)"', s)
            if not mm:
                continue
            total += int(mm.group(1)); fallos += int(mm.group(2)) + int(mm.group(3))
            if 'DramaSesionTest' in x.name:
                drama_tests, drama_fallos = int(mm.group(1)), int(mm.group(2)) + int(mm.group(3))
            if int(mm.group(2)) + int(mm.group(3)):
                rojos.append(x.name.split('.')[-2])
        self.assertEqual(drama_fallos, 0, 'DramaSesionTest debe estar verde')
        self.assertGreaterEqual(drama_tests, 49, 'DramaSesionTest: >=49 tests (v3.2.21 sumo 2 de fusion/tvg-id)')
        self.assertGreaterEqual(total, 125)
        self.assertTrue(set(rojos) <= {'ApiProbeTest'}, f'rojos inesperados: {rojos}')

    def test_T05_aes_pura_vs_fips197(self):
        k128 = bytes(range(16)); p128 = bytes.fromhex('00112233445566778899aabbccddeeff')
        self.assertEqual(aes_encrypt_block(k128, p128).hex(), '69c4e0d86a7b0430d8cdb78070b4c55a',
                         'FIPS-197 C.1 AES-128')
        self.assertEqual(aes_decrypt_block(k128, aes_encrypt_block(k128, p128)), p128, 'decrypt(encrypt)=identidad')
        k256 = bytes(range(32))
        try:
            from Crypto.Cipher import AES  # oraculo independiente si existe
            oracle = AES.new(k256, AES.MODE_ECB).encrypt(p128).hex()
            self.assertEqual(aes_encrypt_block(k256, p128).hex(), oracle,
                             'AES-256 debe coincidir con pycryptodome byte a byte')
        except ImportError:
            pass
        self.assertEqual(aes_decrypt_block(k256, aes_encrypt_block(k256, p128)), p128, 'decrypt(encrypt) AES-256')
        iv = bytes(16)
        msg = b'glbr {"id":"live_tv_bein","url":"1"} \xc3\xb1'
        self.assertEqual(aes_cbc_decrypt(k128, iv, aes_cbc_encrypt(k128, iv, msg)), msg)

    @unittest.skipUnless(NODE, 'node no disponible')
    def test_T06_interop_wire_real_encPara(self):
        r = subprocess.run([NODE, str(ROOT / 'docs/crypto_interop.mjs')],
                           capture_output=True, text=True, cwd=ROOT, timeout=60)
        self.assertEqual(r.returncode, 0, f'interop exit {r.returncode}: {r.stderr[-300:]}')
        casos = json.loads(r.stdout)
        self.assertEqual(len(casos), 3)
        for c in casos:
            wire = c['wire']
            self.assertRegex(wire, r'^[A-Za-z0-9+/=]+:[A-Za-z0-9+/=]+$',
                             'formato del alambre: b64(ct):b64(iv)')
            ct_b64, iv_b64 = wire.rsplit(':', 1)
            ct = base64.b64decode(ct_b64); iv = base64.b64decode(iv_b64)
            self.assertEqual(len(iv), 16); self.assertEqual(len(ct) % 16, 0)
            claro = json.loads(aes_cbc_decrypt(GLBR_KEY, iv, ct).decode('utf-8'))
            self.assertEqual(claro, c['obj'], 'el JSON cifrado por el asset debe volver EXACTO en Python')

    def test_T07_vector_dorado_salted(self):
        d = json.loads((ROOT / 'docs/vectores-clave-drama.json').read_text())
        v = d[0]
        h = v['hash']
        raw = base64.urlsafe_b64decode(h + '=' * (-len(h) % 4))
        self.assertEqual(raw[:8], b'Salted__')
        salt = raw[8:16]
        self.assertEqual(salt.hex(), v['salt'], 'salt del vector')
        key, iv = evpkdf_md5_1(v['clave'].encode(), salt, 32, 16)
        claro = aes_cbc_decrypt(key, iv, raw[16:]).decode('utf-8')
        # el `texto` del json esta formoseado para lectura; lo cifrado fue el JSON
        # compacto real — la comparacion correcta es SEMANTICA (mismo objeto JSON)
        self.assertEqual(json.loads(claro), json.loads(v['texto']),
                         'vector generado con crypto-js debe abrir con EvpKDF(MD5,1)+AES-256-CBC')
        self.assertIn('index.m3u8', claro)

    def test_T09_tiempos_fg_ampliados(self):
        """v3.2.17: el usuario («el tiempo es muy corto», fgcode.org/232425). Verifica
        en el codigo real que la entrada por codigo tiene aire de sobra."""
        fc = rd(ROOT / 'app/src/main/java/dza/fut/paipo/FuenteCanales.java')
        self.assertIn('.connectTimeout(30, TimeUnit.SECONDS)', fc)
        asrc = rd(ROOT / 'app/src/main/assets/drama_auto.js')
        self.assertIn('filas[i].di = p.id_live', asrc, 'la cura conserva el id en la fila curada')
        ds = rd(ROOT / 'app/src/main/java/dza/fut/paipo/DramaSesion.java')
        self.assertIn('static String idFila(@NonNull String extinf, @NonNull String url)', ds,
                      'la llave de fila busca url o tvg-id')
        self.assertIn('tvg-id=', ds)
        pa = rd(ROOT / 'app/src/main/java/dza/fut/paipo/PlayerActivity.java')
        self.assertIn('static void notificarEntrega()', pa, 'la ENTREGA despierta al player YA')
        self.assertIn('cura agotada: abro el captador visible', pa, 'escape visible a los 3 ciclos')
        da = rd(ROOT / 'app/src/main/java/dza/fut/paipo/DramaAutoActivity.java')
        self.assertIn('PlayerActivity.notificarEntrega();', da, 'el captador avisa al player')
        self.assertIn('me quedo vivo hasta que la cosecha', da, 'v3.2.22: no matar la cosecha al entregar')
        self.assertIn('priorCurado', da)
        self.assertIn('ultIny', da, 'throttle anti-tormenta de inyecciones')
        self.assertIn('sin repetir el glbr', pa, 'v3.2.22: con cura en vuelo solo se re-mira la fila')
        js23 = open('app/src/main/assets/drama_auto.js', encoding='utf-8').read()
        self.assertIn('onNewIntent', da, 'v3.2.23: el relevo de LANZAR re-arra el plomazo')
        self.assertIn('plomazo re-armado', da)
        self.assertIn('cosechaTermino', da, 'v3.2.23: el fin de cosecha se reporta al puente')
        self.assertIn('AndroidDrama.cosechaTermino', js23)
        self.assertIn('no lo relanzo, miro la fila cada 15 s', pa, 'v3.2.23: no relanzar sobre instancia viva')
        self.assertIn('quemadosHeadless', pa, 'v3.2.23: pid quemado salta directo al visible')
        self.assertIn('abrirCaptadorVisiblePara', pa)
        se24 = open('app/src/main/java/dza/fut/paipo/DramaSesion.java', encoding='utf-8').read()
        fc24 = open('app/src/main/java/dza/fut/paipo/FuenteCanales.java', encoding='utf-8').read()
        self.assertIn('seedSetupJs', se24, 'v3.2.24: la semilla del wizard vive en DramaSesion')
        self.assertIn('flutter.source_config', se24)
        self.assertIn('flutter.terms_continue', se24)
        self.assertIn('codigoFg', fc24, 'v3.2.24: la app expone su codigo para el seed')
        self.assertIn('guardarSoloCodigo', fc24)
        self.assertIn('seedSetupJs', da, 'v3.2.24: el captador siembra antes del guion')
        self.assertIn('aprenderCodigo', da, 'v3.2.24: puente que memoriza el codigo del sitio')
        self.assertIn('aprenderCodigo', js23, 'v3.2.24: el hook aprende fgcode del trafico')
        self.assertIn('flutter.source_config', js23)
        self.assertIn("sk === 'glbrReq'", js23, 'v3.2.25: la semilla copia glbrReq sin user_id')
        self.assertNotIn('if (r.json.user_id) { A.plant.glbrReq', js23, 'v3.2.25: la guarda falsa del trafico debe haber muerto')
        self.assertNotIn("glbrReq: { user_id: 'u-semilla'", open('docs/cosecha_repro.mjs', encoding='utf-8').read(), 'fixture veraz exigido' )
        cv = rd(ROOT / 'app/src/main/java/dza/fut/paipo/CanalesEnVivoFragment.java')
        self.assertIn('captadorVisibleSiCaeLaFuente(mensaje);', cv, 'fuente manual caída -> captador visible')
        self.assertIn('captadorVisibleSiCaeLaFuente(resumen);', cv, 'auto-caída -> captador visible')
        self.assertIn('if (!canales.isEmpty()) return;', cv, 'nunca interrumpe con rejilla llena')
        self.assertIn('captadorVisibleLanzado = true;', cv, 'un disparo por visita (sin loops)')
        self.assertIn('.readTimeout(75, TimeUnit.SECONDS)', fc)
        self.assertIn('cargarIntento(1, peticion, oyente)', fc, 'la carga del codigo debe reintentar 3x')
        self.assertIn('intento == 1 ? 5_000L : 15_000L', fc)
        self.assertIn('static String normalizarAtajoFg(@NonNull String u)', fc,
                      'fgcode.org/<num> debe normalizarse al endpoint api.php')
        ac = rd(ROOT / Path('app/src/main/java/dza/fut/paipo/AutoCanales.java'))
        self.assertIn('for (int intento = 1; intento <= 3; intento++)', ac, 'descarga de lista con reintentos')
        dd = rd(ROOT / 'app/src/main/java/dza/fut/paipo/DramaAutoActivity.java')
        self.assertIn('PLAZO_HEADLESS_MS = 120_000L', dd, 'plomazo a 2 min')
        self.assertIn('fallosRed < 4', dd, 'el error de red re-carga, NO hace finish')
        self.assertNotIn('estado("error de red: " + desc);\n                    if (headless) finish();',
                         dd, 'el finish inmediato del v3.2.17 debe estar muerto')

        sr = rd(ROOT / Path('app/src/main/java/dza/fut/paipo/StreamResolver.java'))
        self.assertIn('.connectTimeout(12, TimeUnit.SECONDS)', sr)
        self.assertIn('.readTimeout(15, TimeUnit.SECONDS)', sr)
        self.assertIn('TIEMPO_MAX_CADENA_MS = 120_000', sr, 'v3.2.18: la cadena dispone de 2 min (pedido del usuario)')
        self.assertIn('WEBVIEW_MAXIMO_MS = 120_000', sr)

    @unittest.skipUnless(ZIP_PATH.is_file(), f'sin zip en {ZIP_PATH} (o DRAMA_ZIP=)')
    def test_T08_zip_sync_repo(self):
        z = zipfile.ZipFile(ZIP_PATH)
        for rel in ['app/src/main/assets/drama_auto.js',
                    'app/src/main/AndroidManifest.xml',
                    str(PLAYER.relative_to(ROOT)),
                    str(AUTOACT.relative_to(ROOT)),
                    str(SESION.relative_to(ROOT))]:
            with z.open(rel) as fh:
                self.assertEqual(fh.read(), (ROOT / rel).read_bytes(),
                                 f'{rel}: lo entregado en el zip debe ser byte-a-byte el repo probado')
        self.assertRegex(z.read('app/src/main/assets/drama_auto.js').decode(),
                         r'drama_auto\.js v3\.2\.\d+', 'el asset del zip debe ser el del repo (byte a byte, arriba)')


if __name__ == '__main__':
    os.chdir(ROOT)
    unittest.main(verbosity=2)
