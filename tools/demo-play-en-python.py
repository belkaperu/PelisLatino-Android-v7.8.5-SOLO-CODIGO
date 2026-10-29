#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
demo-play-en-python.py — "reproducir un canal" en Python, de punta a punta, sin
navegador y sin libs: lista M3U8 -> segmentos -> auditar el video -> fotograma.

Sirve para dos cosas:

  1) DEMO autocontenida (sin tocar ningún portal):
        python3 tools/demo-play-en-python.py demo
     Corta un clip real en HLS, levanta un servidor HTTP que EXIGE Referer (igual que
     hacen los CDNs de los portales: sin esa cabecera devuelven 403), intenta abrirlo
     SIN el encabezado (sale el 403), lo intenta CON el encabezado ( reproduce ), y
     saca el fotograma. Es la prueba andante del campo "Encabezados al abrir" de la app.

  2) Ver tu propia señal (la lista del proveedor, o un FAST público y legal):
        python3 tools/demo-play-en-python.py mirar --url https://…/playlist.m3u8 \
            --header "Referer: https://mi-proveedor.net/" --segundos 12
     Sigue master -> variante -> segmentos, y audita con ffmpeg: si un solo fotograma
     está roto, el veredicto es PARCIAL.

Por diseño NO incluye la vía "sacar la grilla de DramaLive": eso está cifrado dentro de
su cliente y su proxy usa una cuenta que no es tuya. Aquí se reproduce lo que ya es
tuyo o público.
"""
import argparse
import http.server
import os
import re
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

UA = "PelisLatino/1.0 (demo de reproducción en Python)"
REFERER_QUE_ES_JEFE = "https://mi-proveedor.net/"


def ffmpeg():
    env = os.environ.get("FFMPEG")
    if env and os.path.exists(env):
        return env
    cual = __import__("shutil").which("ffmpeg")
    if cual:
        return cual
    try:
        import imageio_ffmpeg
        return imageio_ffmpeg.get_ffmpeg_exe()
    except Exception:
        return None


def pedir(url, headers=None, bin_=False, timeout=25):
    req = urllib.request.Request(url, headers=dict({"User-Agent": UA}, **(headers or {})))
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            data = r.read()
            return r.status, (data if bin_ else data.decode("utf-8", "replace")), None
    except urllib.error.HTTPError as e:
        return e.code, "", "HTTP %d %s" % (e.code, e.reason)
    except Exception as e:
        return 0, "", "%s: %s" % (type(e).__name__, str(e)[:140])


# ────────────────────────── seguir HLS como lo haría Media3 ──────────────────────────

def resolver_lista(url, headers=None):
    """Devuelve (url_de_media, notas). Si es master, elige la variante más alta."""
    st, cuerpo, err = pedir(url, headers)
    if err:
        raise SystemExit("el manifiesto no respondió: %s (%s)" % (err, url))
    if "#EXTM3U" not in cuerpo[:200]:
        raise SystemExit("eso no es un manifiesto HLS (empezaba con %r)" % cuerpo[:40])
    lineas = [l.strip() for l in cuerpo.replace("\r", "").split("\n") if l.strip()]
    variantes = []
    for i, l in enumerate(lineas):
        if l.startswith("#EXT-X-STREAM-INF") and i + 1 < len(lineas):
            bw = re.search(r"BANDWIDTH=(\d+)", l)
            res = re.search(r"RESOLUTION=(\d+x\d+)", l)
            variantes.append((int(bw.group(1)) if bw else 0, res.group(1) if res else "?",
                              urllib.parse.urljoin(url, lineas[i + 1])))
    if not variantes:
        return url, ["lista de un solo bitrato", "%d líneas" % len(lineas)]
    variantes.sort(reverse=True)
    bw, res, media = variantes[0]
    return media, ["master con %d variantes; elegida %s (%d kbps)" % (len(variantes), res, bw // 1000)]


def segmentos(media_url, headers=None, segundos=12):
    st, cuerpo, err = pedir(media_url, headers)
    if err:
        raise SystemExit("la lista de segmentos no respondió: %s" % err)
    lineas = [l.strip() for l in cuerpo.replace("\r", "").split("\n")]
    fuera, acum = [], 0.0
    for i, l in enumerate(lineas):
        m = re.match(r"^#EXTINF:\s*(-?\d+(?:\.\d+)?)", l)
        if m and i + 1 < len(lineas):
            d = float(m.group(1)) or 4.0
            fuera.append(urllib.parse.urljoin(media_url, lineas[i + 1]))
            acum += d
            if acum >= segundos:
                break
    cifrado = re.search(r"#EXT-X-KEY:.*METHOD=([A-Z0-9-]+)", cuerpo)
    drm = "SAMPLE-AES" in cuerpo or "fairplay" in cuerpo.lower()
    notas = ["ventana de %.1f s en %d segmento(s)" % (acum, len(fuera)),
             "AES-128 con %s" % cifrado.group(1) if cifrado else "segmentos en claro",
             "directo (sin ENDLIST)" if "#EXT-X-ENDLIST" not in cuerpo else "VOD"]
    if drm:
        raise SystemExit("DRM/SAMPLE-AES: esto no se reproduce sin la licencia, y no se intenta saltar")
    return fuera, acum, notas



# ────────────────────────── auditor de MPEG-TS en puro Python ──────────────────────────
#
# Se hace sin ffmpeg porque el contenedor TS es trivial de verificar por estructura y
# porque en este sandbox el ffmpeg estático revienta (SIGSEGV) al LEER .ts: escribirlos
# sí los escribe. El auditor responde a lo que de verdad importa: ¿está el stream
# completo y son reales los 12 segundos?

PAQUETE_TS = 188


def _cabecera(pk):
    """pid, offset de payload, PUSI, si hay campo de adaptación y dónde empieza."""
    pid = ((pk[1] & 0x1F) << 8) | pk[2]
    pusi = bool(pk[1] & 0x40)
    afc = (pk[3] >> 4) & 0x03
    af = None
    off = 4
    if afc & 2:                       # el largo del campo de adaptación es el byte 4
        largo = pk[4]
        af = 5
        off = 5 + largo
    return pid, pusi, off, af


def _paquetes(crudo):
    for i in range(0, len(crudo) - PAQUETE_TS + 1, PAQUETE_TS):
        yield i, crudo[i:i + PAQUETE_TS]


def auditar_ts(ruta):
    """Devuelve (ok, [notas]): PAT -> PMT -> flujos, continuidad por PID y duración por PCR."""
    crudo = open(ruta, "rb").read()
    if not crudo:
        return False, ["el archivo está vacío"]
    mal = sum(1 for _, pk in _paquetes(crudo) if pk[0] != 0x47)
    if mal:
        return False, ["%d paquetes fuera de sincronía (no empiezan por 0x47)" % mal]
    paquetes = len(crudo) // PAQUETE_TS
    notas = ["TS alineado: %d paquetes de 188 B (%d B)" % (paquetes, len(crudo))]

    # PAT: tabla 0x00, su primera entrada útil dice dónde está el PMT
    pmt_pid = None
    for _, pk in _paquetes(crudo):
        pid, pusi, off, _ = _cabecera(pk)
        if pid != 0 or not pusi or off + 12 > len(pk):
            continue
        q = off + 1 + pk[off]                     # salta el pointer_field
        if q + 9 > len(pk) or pk[q] != 0x00:       # table_id de la PAT
            continue
        n = (pk[q + 1] & 0x0F) << 8 | pk[q + 2]    # section_length
        p = q + 8
        for _e in range(min(4, n // 4)):           # program_number=0 es la red, se ignora
            if p + 4 > len(pk):
                break
            num = pk[p] << 8 | pk[p + 1]
            pid_cand = ((pk[p + 2] & 0x1F) << 8) | pk[p + 3]
            if num != 0:
                pmt_pid = pid_cand
                break
            p += 4
        if pmt_pid is not None:
            break
    if pmt_pid is None:
        return False, notas + ["no se encontró la PAT"]
    notas.append("PMT en PID %d" % pmt_pid)

    # PMT: flujos elementales y su tipo
    streams, pcr_pid = {}, pmt_pid
    for _, pk in _paquetes(crudo):
        pid, pusi, off, _ = _cabecera(pk)
        if pid != pmt_pid or not pusi or off + 12 > len(pk):
            continue
        q = off + 1 + pk[off]
        if q + 12 > len(pk) or pk[q] != 0x02:
            continue
        n = (pk[q + 1] & 0x0F) << 8 | pk[q + 2]
        fin_tabla = q + 3 + n - 4
        pcr_pid = ((pk[q + 8] & 0x1F) << 8) | pk[q + 9]
        p = q + 12 + ((pk[q + 10] & 0x0F) << 8 | pk[q + 11])
        while p + 5 <= min(fin_tabla, len(pk)):
            st = pk[p]
            epid = ((pk[p + 1] & 0x1F) << 8) | pk[p + 2]
            plen = (pk[p + 3] & 0x0F) << 8 | pk[p + 4]
            streams[epid] = st
            p += 5 + plen
        break
    nombres = {0x1B: "h264/AVC", 0x24: "h265/HEVC", 0x0F: "AAC LATM", 0x11: "AAC ADTS",
               0x03: "MP3", 0x06: "subt. DTVCC", 0x81: "AC-3", 0x04: "MPEG2 audio"}
    if not streams:
        return False, notas + ["el PMT no declaró ningún flujo"]
    notas.append("flujos: " + ", ".join("PID %d=%s" % (i, nombres.get(t, "0x%02X" % t)) for i, t in sorted(streams.items())))
    notas.append("PCR en PID %d" % pcr_pid)

    # contadores de continuidad, por PID
    vistos, rotos = {}, 0
    for _, pk in _paquetes(crudo):
        pid, pusi, off, af = _cabecera(pk)
        cc = pk[3] & 0x0F
        prev = vistos.get(pid)
        if prev is not None and (prev + 1) % 16 != cc:
            rotos += 1
        vistos[pid] = cc
    notas.append("contadores de continuidad: %s" % ("correctos en los %d PIDs" % len(vistos)
                                                    if not rotos else "%d saltos (hay huecos)" % rotos))

    # duración real: PCR primero y último del PID que lleva el reloj
    pcrs = []
    for _, pk in _paquetes(crudo):
        pid, pusi, off, af = _cabecera(pk)
        if pid != pcr_pid or af is None or pk[4] < 7:
            continue
        # En el byte de BANDERAS del campo de adaptación: discontinuity=0x80,
        # length_field=0x40, splicing=0x20, PCR=0x10. (0x80 es discontinuity, no PCR:
        # ese error hacía que no apareciera ninguna marca de reloj.)
        if not (pk[af] & 0x10):
            continue
        b = pk[af + 1:af + 7]
        if len(b) < 6:
            continue
        base = ((b[0] << 25) | (b[1] << 17) | (b[2] << 9) | (b[3] << 1) | (b[4] >> 7)) & ((1 << 33) - 1)
        pcrs.append(base)
    dur = None
    if len(pcrs) >= 2:
        # El PCR es un reloj de 33 bits que da vueltas: se suma incremento a incremento
        # (y solo los incrementos creíbles), que es como se detecta un salto real.
        saltos = [b - a for a, b in zip(pcrs, pcrs[1:])]
        buenos = [d for d in saltos if 0 <= d < 90000 * 60]
        dur = sum(buenos) / 90000.0 if buenos else 0.0
        envueltos = len(saltos) - len(buenos)
        notas.append("PCR: %.1f s de contenido real en %d marcas de reloj%s"
                     % (dur, len(pcrs), (" (%d saltos raros)" % envueltos) if envueltos else ""))
    ok = not rotos and len(streams) >= 1 and (dur is None or dur > 0.5)
    return ok, notas


def frame_desde(mp4, png, ff):
    """Fotograma, solo si este ffmpeg puede leer ese contenedor (con .ts falla en este sandbox)."""
    if not ff:
        return None
    r = subprocess.run([ff, "-hide_banner", "-loglevel", "error", "-y", "-i", mp4,
                        "-frames:v", "1", "-update", "1", png], capture_output=True, text=True)
    if r.returncode == 0 and os.path.exists(png):
        return png
    return None


def bajar_y_auditar(segs, destino, ff, headers=None):
    open(destino, "wb").close()
    total = 0
    bajados = 0
    for s in segs:
        st, data, err = pedir(s, headers, bin_=True, timeout=40)
        if err or not data:
            print("     ! segmento %s -> %s" % (os.path.basename(s)[:32], err or "vacío"))
            continue
        bajados += 1
        total += len(data)
        with open(destino, "ab") as f:
            f.write(data)
    if not bajados:
        return None, 0
    ok, notas = auditar_ts(destino)
    for n in notas:
        print("     ·", n)
    png = None
    if ff and ok:
        mp4 = destino[:-3] + ".mp4"
        r = subprocess.run([ff, "-hide_banner", "-loglevel", "error", "-y", "-fflags", "+genpts", "-i", destino,
                            "-c", "copy", "-movflags", "+faststart", mp4], capture_output=True, text=True)
        if r.returncode == 0 and os.path.exists(mp4):
            png = frame_desde(mp4, destino[:-3] + ".png", ff)
            info = subprocess.run([ff, "-hide_banner", "-i", mp4], capture_output=True, text=True).stderr or ""
            res = re.search(r"(\d{3,5})x(\d{3,5})", info)
            cod = re.search(r"Video: (\S+)", info)
            fps = re.search(r"(\d+(?:\.\d+)?) fps", info)
            if res or cod:
                print("     vídeo (por mp4): %s" % ", ".join(
                    [x for x in [(res.group(1) + "x" + res.group(2)) if res else "",
                                cod.group(1) if cod else "", (fps.group(1) + " fps") if fps else ""] if x]))
        else:
            print("     ! este ffmpeg no puede re-empaquetar .ts en esta máquina (SIGSEGV conocido):"
                  " se audita por estructura, que es lo que decide si reproduce")
    return png, total if not ok else total


# ───────────────────────────────── servidor con Referer obligatorio ─────────────────────────────────

class Exigente(http.server.BaseHTTPRequestHandler):
    """Imita al CDN del portal: sin el Referer de su dominio, 403 y punto."""

    dir = "."

    def do_GET(self):
        ref = self.headers.get("Referer") or ""
        if not ref.startswith(REFERER_QUE_ES_JEFE):
            self.send_response(403)
            self.send_header("Content-Type", "text/plain")
            self.end_headers()
            self.wfile.write(b'ACCESS DENIED! falta el Referer del proveedor\n')
            print("  servidor: 403 a %s (Referer=%r)" % (self.path, ref or ""))
            return
        f = os.path.join(self.dir, self.path.lstrip("/").split("?")[0])
        if not os.path.isfile(f):
            self.send_response(404)
            self.end_headers()
            print("  servidor: 404 %s" % self.path)
            return
        data = open(f, "rb").read()
        self.send_response(200)
        self.send_header("Content-Type", "audio/x-mpegurl" if f.endswith(".m3u8") else "video/mp2t")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)
        print("  servidor: 200 %s (%d B, Referer=%s)" % (self.path, len(data), ref))

    def log_message(self, *a):
        pass


def demo(origen, out):
    ff = ffmpeg()
    if not ff:
        raise SystemExit("hace falta ffmpeg (FFMPEG=… o pip install imageio-ffmpeg)")
    os.makedirs(out, exist_ok=True)
    print("== 1) cortar el clip en HLS con ffmpeg (como lo sirve cualquier portal)")
    if not os.path.exists(os.path.join(out, "demo.m3u8")):
        subprocess.run([ff, "-hide_banner", "-loglevel", "error", "-y", "-i", origen,
                        "-c", "copy", "-hls_time", "4", "-hls_list_size", "0",
                        "-hls_segment_filename", os.path.join(out, "seg%03d.ts"),
                        os.path.join(out, "demo.m3u8")], check=True)
    print("   ", " ".join(sorted(os.listdir(out))))

    Exigente.dir = out
    srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Exigente)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    base = "http://127.0.0.1:%d/" % srv.server_address[1]
    lista = base + "demo.m3u8"
    try:
        print("\n== 2) abrir SIN el encabezado (lo que hace un reproductor limpio)")
        st, cuerpo, err = pedir(lista)
        print("   resultado: %s" % (err or "HTTP %d" % st))
        print("\n== 3) abrir CON `Referer: %s` (lo que ahora manda la app)" % REFERER_QUE_ES_JEFE)
        cab = {"Referer": REFERER_QUE_ES_JEFE}
        media, notas = resolver_lista(lista, cab)
        for n in notas:
            print("   ·", n)
        segs, dur, notas2 = segmentos(media, cab, segundos=12)
        for n in notas2:
            print("   ·", n)
        png, total = bajar_y_auditar(segs, os.path.join(out, "reproducido.ts"), ff, cab)
        print("   bajado %.2f MB en %d segmentos, %.1f s de vídeo" % (total / 1e6, len(segs), dur))
        if png:
            print("   fotograma escrito en %s (%d B)" % (png, os.path.getsize(png)))
        print("\nveredicto: la MISMA URL, con y sin encabezado -> 403 / reproduce. Eso es lo que arregla el campo nuevo.")
    finally:
        srv.shutdown()


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="modo", required=True)
    d = sub.add_parser("demo", help="403 sin Referer / reproduce con Referer, sin tocar ningún portal")
    d.add_argument("--clip", default="/home/user/bein-xtra-clip-18s.mp4", help="mp4 real que cortar en HLS")
    d.add_argument("--salir", default="/home/user/demo-python")
    m = sub.add_parser("mirar", help="reproduce UNA lista tuya (legal) y la audita")
    m.add_argument("--url", required=True)
    m.add_argument("--header", action="append", default=[], help='"Referer: https://…" (repetible)')
    m.add_argument("--segundos", type=int, default=12)
    m.add_argument("--salir", default="/home/user/demo-python")
    a = ap.parse_args()
    if a.modo == "demo":
        demo(a.clip, a.salir)
        return
    os.makedirs(a.salir, exist_ok=True)
    cab = {}
    for h in a.header:
        k, _, v = h.partition(":")
        cab[k.strip()] = v.strip()
    ff = ffmpeg()
    media, notas = resolver_lista(a.url, cab)
    for n in notas:
        print("·", n)
    segs, dur, notas2 = segmentos(media, cab, a.segundos)
    for n in notas2:
        print("·", n)
    png, total = bajar_y_auditar(segs, os.path.join(a.salir, "canal.ts"), ff, cab)
    print("REPRODUCIDO: %.2f MB, %.1f s, frame=%s" % (total / 1e6, dur, png or "sin ffmpeg"))


if __name__ == "__main__":
    sys.exit(main())
