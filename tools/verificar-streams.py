#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
verificar-streams.py — recupera canales de una lista M3U y comprueba si DE VERDAD
se pueden reproducir, sin móvil y sin adivinar: baja segmentos, los reemplaza con
ffmpeg y te deja un clip .mp4 y un fotograma .png que puedes abrir.

Esto es lo que se puede hacer con Python sobre lo LEGAL y público (la lista de
Free-TV/IPTV, que solo admite señales abiertas, y el FAST oficial beIN SPORTS XTRA
de AMAGI). No sondea espejos piratas ni portales con control de acceso ajeno.

  python3 tools/verificar-streams.py --fuente free --max 6 --segundos 10
  python3 tools/verificar-streams.py --lista ./mi-lista.m3u --canal "beIN SPORTS XTRA"
  python3 tools/verificar-streams.py --url https://bein-xtra-bein.amagi.tv/playlist.m3u8

Salida: una tabla por canal con estado (REPRODUCIBLE / DRM / SIN SEÑAL / MUERTO /
BLOQUEADO), resolución, fps, códecs, bitrate, y los archivos en --salir.

Dependencias: solo stdlib. Para el clip y el frame hace falta ffmpeg (variable
FFMPEG, o `ffmpeg` en el PATH, o `pip install imageio-ffmpeg`).
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

UA = "PelisLatino/1.0 (verificador de streams)"
FUENTES = {
    "free": "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u",
    "sports": "https://iptv-org.github.io/iptv/categories/sports.m3u",
    "latam": "https://iptv-org.github.io/iptv/regions/latam.m3u",
    "bein": None,   # solo el FAST oficial, abajo, por URL directa
}
BEIN_XTRA = "https://bein-xtra-bein.amagi.tv/playlist.m3u8"

RE_EXTINF = re.compile(r"^#EXTINF:\s*(-?\d+(?:\.\d+)?)\s*(.*),(.*)$")
RE_STREAM_INF = re.compile(r"^#EXT-X-STREAM-INF:(.*)$")
RE_MEDIA_SEQ = re.compile(r"^#EXT-X-MEDIA-SEQUENCE:(\d+)")
RE_TARGET = re.compile(r"^#EXT-X-TARGETDURATION:\s*(\d+)")
RE_KEY = re.compile(r"^#EXT-X-KEY:.*METHOD=([A-Z0-9-]+)", re.I)
RE_DRM = re.compile(r"#EXT-X-SESSION-KEY|#EXT-X-KEY:.*SAMPLE-AES|com\.apple\.fairplay", re.I)
RE_ATTR = re.compile(r'([\w-]+)=(?:"([^"]*)"|([^",]*))')
RE_CALIDAD = re.compile(r"\s*[\[(]\s*(\d{3,4}p|FHD|UHD|QHD|HD|SD|4K|8K)\s*[\])]\s*$", re.I)


def pedir(url, timeout=25, bin_=False):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "*/*"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            data = r.read()
            return r.status, dict(r.headers), (data if bin_ else data.decode("utf-8", "replace")), None
    except urllib.error.HTTPError as e:
        cuerpo = b""
        try:
            cuerpo = e.read()[:400]
        except Exception:
            pass
        return e.code, dict(e.headers or {}), (cuerpo if bin_ else cuerpo.decode("utf-8", "replace")), "http %s" % e.code
    except Exception as e:
        return 0, {}, "", "%s: %s" % (type(e).__name__, str(e)[:120])


def atributos(parte):
    at = {}
    for m in RE_ATTR.finditer(parte or ""):
        at[m.group(1).lower()] = (m.group(2) if m.group(2) is not None else m.group(3) or "").strip()
    return at


def parsear_lista(texto):
    lineas = (texto or "").replace("\r", "").split("\n")
    canales, resto = [], None
    for ln in (l.strip() for l in lineas):
        if not ln:
            continue
        m = RE_EXTINF.match(ln)
        if m:
            resto = m
            continue
        if ln.startswith("#"):
            continue
        at = atributos(resto.group(2)) if resto else {}
        nombre = (resto.group(3).strip() if resto else "") or at.get("tvg-name") or at.get("tvg-id") or ""
        calidad = ""
        mc = RE_CALIDAD.search(nombre)
        if mc:
            calidad = mc.group(1)
            nombre = nombre[: mc.start()].strip()
        grupos = [g.strip() for g in (at.get("group-title") or "").split(";") if g.strip() and g.strip() != "Undefined"]
        canales.append({
            "nombre": nombre or urllib.parse.urlsplit(ln).path.rsplit("/", 1)[-1] or ln,
            "url": ln,
            "logo": at.get("tvg-logo") or "",
            "grupo": grupos[0] if grupos else "General",
            "calidad": calidad,
            "tvgId": at.get("tvg-id") or "",
        })
        resto = None
    return canales


RE_SUSPECHOSO = re.compile(r"(\d{1,3}\.){3}\d{1,3}:(\d+)?|/play/a\d{2,4}/|\.(lol|top|xyz|icu|cam)(/|:)|jmp2\.uk|bkpcp|ayakkabiparti|herohdf|siuu4421", re.I)


def sospechoso(url):
    """true si la URL tiene pinta de panel robado / espejo no autorizado: no se sondea."""
    return bool(RE_SUSPECHOSO.search(url or ""))


def elegir_ffmpeg():
    env = os.environ.get("FFMPEG")
    if env and os.path.exists(env):
        return env
    cual = shutil.which("ffmpeg")
    if cual:
        return cual
    try:
        import imageio_ffmpeg

        return imageio_ffmpeg.get_ffmpeg_exe()
    except Exception:
        return None


def analizar_manifiesto(url, modo_variante="pequena"):
    """Devuelve (info, segments, error). Sigue master -> media si hace falta."""
    st, hdr, cuerpo, err = pedir(url)
    if err or "#EXTM3U" not in (cuerpo or "")[:200]:
        tipo = (hdr.get("content-type") or "").lower()
        return None, [], err or ("no es un manifiesto HLS (content-type=%s, %d B)" % (tipo or "?", len(cuerpo or "")))
    base = url.rsplit("/", 1)[0] + "/"
    lineas = [l.strip() for l in cuerpo.replace("\r", "").split("\n") if l.strip()]
    variantes = []
    i = 0
    while i < len(lineas):
        m = RE_STREAM_INF.match(lineas[i])
        if m and i + 1 < len(lineas) and not lineas[i + 1].startswith("#"):
            at = atributos(m.group(1))
            variantes.append({"bandwidth": int(at.get("bandwidth") or 0), "resolution": at.get("resolution") or "",
                              "codecs": at.get("codecs") or "", "url": urllib.parse.urljoin(base, lineas[i + 1])})
            i += 2
            continue
        i += 1
    info = {"status": st, "es_master": bool(variantes), "variantes": variantes, "uri": url,
            "drm": bool(RE_DRM.search(cuerpo)), "cifrado": None}
    km = RE_KEY.search(cuerpo or "")
    if km:
        info["cifrado"] = km.group(1)
    if variantes:
        orden = sorted(variantes, key=lambda v: v["bandwidth"])
        elegido = orden[0] if modo_variante == "pequena" else orden[-1]
        if modo_variante == "media" and len(orden) > 1:
            elegido = orden[len(orden) // 2]
        info["elegida"] = elegido
        st2, _, cuerpo2, err2 = pedir(elegido["url"])
        if err2 or "#EXTM3U" not in (cuerpo2 or "")[:200]:
            return info, [], err2 or "la variante tampoco devolvió un manifiesto"
        base = elegido["url"].rsplit("/", 1)[0] + "/"
        media = [l for l in cuerpo2.replace("\r", "").split("\n") if l.strip()]
        info["status2"] = st2
        if RE_DRM.search("\n".join(media)):
            info["drm"] = True
        if not info["cifrado"]:
            km = RE_KEY.search("\n".join(media))
            if km:
                info["cifrado"] = km.group(1)
    else:
        media = lineas
        base = base
    segs, dur = [], 0.0
    for i, l in enumerate(media):
        m = RE_EXTINF.match(l)
        if m and i + 1 < len(media):
            d = float(m.group(1))
            if d <= 0:
                d = 4.0
            segs.append({"uri": urllib.parse.urljoin(base, media[i + 1]), "dur": d})
            dur += d
    mt = RE_TARGET.search("\n".join(media))
    if mt:
        info["target_duration"] = int(mt.group(1))
    info["segmentos_totales"] = len(segs)
    info["ventana_seg"] = round(dur, 1)
    info["directo"] = "#EXT-X-ENDLIST" not in "\n".join(media)
    return info, segs, None


def probar(canal, segundos,outdir, ffmpeg, modo_variante="pequena"):
    r = {"canal": canal["nombre"], "grupo": canal["grupo"], "url": canal["url"], "estado": "?", "notas": []}
    info, segs, err = analizar_manifiesto(canal["url"], modo_variante)
    if err:
        r["estado"] = "MUERTO" if "http 4" in (err or "") else "SIN SEÑAL"
        r["notas"].append(err[:200])
        return r
    r["hls"] = {"es_master": info["es_master"], "variantes": len(info["variantes"]),
                "segmentos": info["segmentos_totales"], "ventana_seg": info["ventana_seg"],
                "target": info.get("target_duration"), "en_vivo": info["directo"]}
    if info.get("elegida"):
        r["hls"]["elegida"] = {"res": info["elegida"]["resolution"], "kbps": round(info["elegida"]["bandwidth"] / 1000)}
    if info["drm"]:
        r["estado"] = "DRM"
        r["notas"].append("manifiesto con SAMPLE-AES/FairPlay: hace falta licencia, no es reproducible sin ella")
        return r
    if info["cifrado"] and info["cifrado"] != "NONE":
        r["notas"].append("segmentos con AES-128 (%s): Media3 los descifra si la URI de la key es pública" % info["cifrado"])
    if not segs:
        r["estado"] = "SIN SEÑAL"
        r["notas"].append("el manifiesto no lista ningún segmento")
        return r
    elegidos, acum = [], 0.0
    for s in segs[-40:]:
        elegidos.append(s)
        acum += s["dur"]
        if acum >= segundos:
            break
    ts = os.path.join(outdir, re.sub(r"[^\w.-]+", "_", r["canal"])[:40] + ".ts")
    open(ts, "wb").close()          # desde cero: si no, lo de la vez pasada se queda pegado
    ok_bytes = 0
    con = 0
    for s in elegidos:
        st, _, data, e2 = pedir(s["uri"], timeout=30, bin_=True)
        if e2 or not data:
            r["notas"].append("segmento %s -> %s" % (os.path.basename(s["uri"])[:28], (e2 or "vacío")))
            continue
        con += 1
        ok_bytes += len(data)
        with open(ts, "ab") as f:
            f.write(data)
    if con == 0:
        r["estado"] = "SIN SEÑAL"
        r["notas"].append("ningún segmento bajó (host caído, geo-bloqueo o requiere cookies/referer)")
        return r
    r["clip_segundos"] = round(acum, 1)
    r["bytes"] = ok_bytes
    if ffmpeg:
        if os.path.exists(ts):
            mp4 = ts[:-3] + ".mp4"
            png = ts[:-3] + ".png"
            remux = subprocess.run([ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-fflags", "+genpts",
                                    "-i", ts, "-c", "copy", "-movflags", "+faststart", mp4],
                                   capture_output=True, text=True)
            aud = subprocess.run([ffmpeg, "-hide_banner", "-loglevel", "error", "-i", ts, "-f", "null", "-"],
                                 capture_output=True, text=True)
            if os.path.exists(mp4):
                frame = subprocess.run([ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-i", mp4,
                                        "-frames:v", "1", "-update", "1", png], capture_output=True, text=True)
                r["frame"] = png if os.path.exists(png) and os.path.getsize(png) > 1000 else None
                r["clip"] = mp4
                r["clip_bytes"] = os.path.getsize(mp4)
            info_txt = subprocess.run([ffmpeg, "-hide_banner", "-i", (r.get("clip") or ts)],
                                       capture_output=True, text=True).stderr or ""
            m = re.search(r"(\d{2,5})x(\d{2,5})", info_txt)
            if m:
                r["resolucion"] = "%sx%s" % (m.group(1), m.group(2))
            m = re.search(r"Stream #\d+:\d+.*?: Video: (\S+)", info_txt)
            if m:
                r["video"] = m.group(1)
            m = re.search(r"Audio: (\S+)", info_txt)
            if m:
                r["audio"] = m.group(1)
            m = re.search(r"(\d+(?:\.\d+)?) fps", info_txt)
            if m:
                r["fps"] = float(m.group(1))
            m = re.search(r"bitrate=\s*([\d.]+)\s*kbits/s", info_txt)
            if m:
                r["kbps"] = float(m.group(1))
            errores = (aud.stderr or "").strip().splitlines()
            if remux.returncode != 0 and remux.stderr.strip():
                r["notas"].append("remux: " + remux.stderr.strip()[:160])
            if errores:
                r["notas"].append("ffmpeg -f null: " + " | ".join(errores[:2])[:200])
                r["estado"] = "PARCIAL"
            else:
                r["estado"] = "REPRODUCIBLE"
        else:
            r["estado"] = "SIN SEÑAL"
            r["notas"].append("no se escribió el .ts")
    else:
        r["estado"] = "REPRODUCIBLE (sin ffmpeg: segmentos bajados, sin auditar por dentro)"
    return r


def main():
    ap = argparse.ArgumentParser(description="verifica canales M3U de verdad (HLS) y saca clip + frame")
    ap.add_argument("--lista", help="ruta a un .m3u local")
    ap.add_argument("--fuente", choices=sorted(FUENTES), help="lista pública conocida")
    ap.add_argument("--url", help="una sola URL de stream")
    ap.add_argument("--canal", default="", help="filtra por subcadena del nombre")
    ap.add_argument("--max", type=int, default=6, help="cuántos canales probar")
    ap.add_argument("--segundos", type=int, default=10, help="ventana de clip")
    ap.add_argument("--variante", choices=["pequena", "media", "grande"], default="pequena")
    ap.add_argument("--salir", default="/tmp/verificado", help="donde se escriben clip/frame/json")
    ap.add_argument("--permitir-sospechosos", action="store_true",
                    help="NO: solo para que veas el filtro; por defecto se descartan los espejos no autorizados")
    a = ap.parse_args()
    os.makedirs(a.salir, exist_ok=True)
    ffmpeg = elegir_ffmpeg()
    if not ffmpeg:
        print("aviso: sin ffmpeg no se audita el clip ni se saca el frame (pip install imageio-ffmpeg)")

    if a.url:
        canales = [{"nombre": os.path.basename(urllib.parse.urlsplit(a.url).path) or a.url, "url": a.url,
                    "grupo": "ad hoc", "calidad": "", "logo": "", "tvgId": ""}]
    else:
        origen = a.lista or FUENTES.get(a.fuente)
        if a.fuente == "bein":
            origen = None
            canales = [{"nombre": "beIN SPORTS XTRA (AMAGI, oficial)", "url": BEIN_XTRA, "grupo": "Sports",
                        "calidad": "", "logo": "", "tvgId": ""}]
        if origen:
            crudo = open(origen, encoding="utf-8", errors="replace").read() if not origen.startswith("http") else pedir(origen)[2]
            canales = parsear_lista(crudo)
    if a.canal:
        wanted = a.canal.lower()
        canales = [c for c in canales if wanted in (c["nombre"] + " " + c["grupo"]).lower()]
    if not a.permitir_sospechosos:
        descartados = [c for c in canales if sospechoso(c["url"])]
        canales = [c for c in canales if not sospechoso(c["url"])]
        if descartados:
            print("fuera %d entrada(s) de redifusión no autorizada (IP cruda, /play/aNNN/, dominios .lol/.top…): %s"
                  % (len(descartados), ", ".join(sorted({urllib.parse.urlsplit(c["url"]).netloc for c in descartados})[:5])))
    canales = canales[: a.max]
    print("probando %d canal(es), variante %s, %d s de clip%s\n" % (len(canales), a.variante, a.segundos,
                                                                     "" if ffmpeg else " (sin ffmpeg)"))
    resultados, t0 = [], time.time()
    for c in canales:
        r = probar(c, a.segundos, a.salir, ffmpeg, a.variante)
        resultados.append(r)
        extra = ""
        if "resolucion" in r:
            extra += " %s" % r["resolucion"]
        if r.get("fps"):
            extra += "@%g" % r["fps"]
        if r.get("kbps"):
            extra += " %gk" % r["kbps"]
        if r.get("video"):
            extra += " %s" % r["video"]
        print("%-42s %-14s %s seg  %6.1f MB%s" % (r["canal"][:42], r["estado"], r.get("clip_segundos", 0),
                                                   (r.get("bytes") or 0) / 1e6, extra))
        for n in r.get("notas", [])[:3]:
            print("      · " + n[:150])
    with open(os.path.join(a.salir, "resultado.json"), "w", encoding="utf-8") as f:
        json.dump({"cuando": time.strftime("%Y-%m-%d %H:%M:%S"), "segundos": round(time.time() - t0, 1),
                   "ffmpeg": bool(ffmpeg), "canales": resultados}, f, ensure_ascii=False, indent=2)
    ok = sum(1 for r in resultados if r["estado"].startswith("REPRODUCIBLE"))
    print("\n%d/%d reproducibles, %.1f s, detalle en %s/resultado.json" % (ok, len(resultados), time.time() - t0, a.salir))


if __name__ == "__main__":
    sys.exit(main())
