#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""puente-cliente.py — la parte que corre en TU maquina (o lanza tu agente).

Pregunta al puente de Arena si hay una orden, la ejecuta con bash EN ESTE
directorio, y devuelve rc+stdout+stderr. Tu maquina solo hace salidas HTTP;
aqui no se abre ningun puerto.

Dos transportes, misma dinamica:

  --via ntfy --topico pl-XXXXXXXX          (RECOMENDADO: relay publico ntfy.sh,
                                            solo requiere que tu red salga a HTTPS)
  --via http --url https://8777-XXXX.e2b.app --token <TOKEN>
        (el preview de Arena exige un token extra de trafico que solo lleva tu
         navegador: usa esto solo si ese proxy te responde, p.ej. dentro de la
         propia app de Arena)

  python3 tools/puente-cliente.py --via ntfy --topico pl-abc123 --dir ~/pelislatino

Opciones: --cada 4 (sondeo)  --tmo 180 (max por orden)  --confirmar (y/N cada orden)
Ya-ejecutados: los ids vistos se anotan en ~/.puente-vistos-<topico>.txt para
que un reinicio del cliente NO vuelva a ejecutar ordenes viejas.
Solo stdlib; requiere bash (Git-Bash vale en Windows). Ctrl-C sale limpio.
"""
import argparse
import json
import os
import platform
import secrets
import subprocess
import sys
import time
import urllib.error
import urllib.request

RELAY_DEFECTO = "https://ntfy.sh"


def req(url, data=None, headers=None, timeout=30):
    """(status, bytes). status=0 => fallo de red."""
    h = {"User-Agent": "puente-cliente/1"}
    if headers:
        h.update(headers)
    r = urllib.request.Request(url, data=data, headers=h)
    try:
        with urllib.request.urlopen(r, timeout=timeout) as f:
            return f.status, f.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()[:400]
    except Exception as e:
        return 0, ("%s: %s" % (type(e).__name__, e)).encode()


def trunc(s, n):
    return s if len(s) <= n else s[:n] + "\n...[truncado a %d de %d chars]" % (n, len(s))


def ejecutar(a, i, cmd):
    print("[puente] ORDEN %06d: %s" % (i, cmd), flush=True)
    if a.confirmar:
        try:
            ans = input("[puente] ejecutar? [y/N] ").strip().lower()
        except EOFError:
            ans = "n"
        if ans not in ("y", "s", "si", "sí"):
            return 126, "", "RECHAZADA por el usuario"
    t0 = time.time()
    try:
        p = subprocess.run(["bash", "-lc", cmd], cwd=a.dir,
                           capture_output=True, timeout=a.tmo)
        rc, out = p.returncode, p.stdout.decode("utf8", "replace")
        err = p.stderr.decode("utf8", "replace")
    except subprocess.TimeoutExpired as e:
        rc = 124
        out = (e.stdout or b"").decode("utf8", "replace")
        err = "TIMEOUT: la orden tardo mas de %ds y la mate" % a.tmo
    except Exception as e:
        rc, out, err = 125, "", "fallo al lanzar bash: %s: %s" % (type(e).__name__, e)
    print("[puente] orden %06d termino: rc=%d en %.1fs" % (i, rc, time.time() - t0), flush=True)
    return rc, trunc(out, 60000), trunc(err, 15000)


def resultado_dict(i, rc, out, err):
    return {"id": i, "rc": rc, "stdout": out, "stderr": err,
            "host": platform.node(), "sis": platform.platform(),
            "cuando": time.strftime("%H:%M:%S")}


# ------------------------------------------------------------------ ntfy -----

def ntfy_multipart(nombre, datos):
    b = "----p" + secrets.token_hex(8)
    cab = ("--%s\r\nContent-Disposition: form-data; name=\"file\"; filename=\"%s\"\r\n"
           "Content-Type: application/json\r\n\r\n" % (b, nombre)).encode()
    pie = ("\r\n--%s\r\nContent-Disposition: form-data; name=\"message\"\r\n\r\n"
           "resultado %s\r\n--%s--\r\n" % (b, nombre, b)).encode()
    return cab + datos + pie, "multipart/form-data; boundary=" + b


def loop_ntfy(a):
    base = "%s/%s" % (a.relay.rstrip("/"), a.topico)
    vf = os.path.expanduser("~/.puente-vistos-%s.txt" % a.topico.replace("/", "_"))
    vistos = set()
    if os.path.exists(vf):
        vistos = set(x.strip() for x in open(vf) if x.strip())
    print("[puente] tunel ntfy: %s-cmd (vistos guardados en %s)" % (base, vf), flush=True)
    problemas = 0
    while True:
        st, body = req(base + "-cmd/json?poll=1&since=all")
        if st != 200:
            problemas += 1
            if problemas in (1, 5) or problemas % 30 == 0:
                print("[puente] poll -> %s %s (intento %d; sigo reintentando)"
                      % (st, body[:120].decode("utf8", "replace"), problemas), flush=True)
            time.sleep(max(a.cada, 8))
            continue
        problemas = 0
        nuevas = []
        for linea in body.decode("utf8", "replace").splitlines():
            linea = linea.strip()
            if not linea:
                continue
            try:
                m = json.loads(linea)
                d = json.loads(m.get("message", ""))
                assert isinstance(d, dict) and "id" in d and "cmd" in d
            except (ValueError, AssertionError):
                continue
            if str(d["id"]) in vistos:
                continue
            nuevas.append((int(d["id"]), d["cmd"]))
        if not nuevas:
            time.sleep(a.cada)
            continue
        for i, cmd in sorted(nuevas):
            rc, out, err = ejecutar(a, i, cmd)
            payload = json.dumps(resultado_dict(i, rc, out, err), ensure_ascii=False).encode("utf8")
            if len(payload) <= 3800:
                st2, _ = req(base + "-res", data=payload, headers={"Content-Type": "application/json"})
            else:
                cuerpo, ctype = ntfy_multipart("res-%06d.json" % i, payload)
                st2, _ = req(base + "-res", data=cuerpo, headers={"Content-Type": ctype})
                if st2 not in range(200, 300):  # sin adjuntos permitidos: manda inline recortado
                    short = resultado_dict(i, rc, trunc(out, 2200), trunc(err, 900) + "\n[adjunto rechazado]")
                    st2, _ = req(base + "-res", data=json.dumps(short).encode("utf8"),
                                 headers={"Content-Type": "application/json"})
            vistos.add(str(i))
            with open(vf, "a") as fh:
                fh.write("%s\n" % i)
            print("[puente] resultado %06d publicado en el relay (HTTP %d)" % (i, st2), flush=True)


# ------------------------------------------------------------------- http ----

def loop_http(a):
    url = a.url.rstrip("/")
    tok = {"X-Puente-Token": a.token}

    def get(path):
        st, b = req(url + path, headers=tok)
        try:
            return st, json.loads(b) if b else None
        except ValueError:
            return st, b.decode("utf8", "replace") if b else None

    def post(path, obj):
        return req(url + path, data=json.dumps(obj, ensure_ascii=False).encode("utf8"),
                   headers=dict(tok, **{"Content-Type": "application/json"}))

    st, ping = get("/hola")
    print("[puente] %s/hola -> %s %s (dir=%s)" % (url, st, ping or "", a.dir), flush=True)
    while True:
        st, b = get("/orden")
        if st == 401:
            print("[puente] 401: token rechazado, salgo", flush=True)
            return 2
        if st == 204:
            time.sleep(a.cada)
            continue
        if st != 200 or not isinstance(b, dict) or "cmd" not in b:
            print("[puente] /orden -> %s %s; calma 10s" % (st, b), flush=True)
            time.sleep(max(a.cada, 10))
            continue
        rc, out, err = ejecutar(a, b["id"], b["cmd"])
        st2, _ = post("/resultado", resultado_dict(b["id"], rc, out, err))
        print("[puente] resultado %06d -> POST /resultado HTTP %s" % (b["id"], st2), flush=True)


def main():
    ap = argparse.ArgumentParser(description="cliente del tunel Arena<->tu agente local")
    ap.add_argument("--via", choices=("ntfy", "http"), default="ntfy",
                    help="ntfy=relay publico (recomendado); http=directo al sandbox si tu red lo deja")
    ap.add_argument("--topico", default=os.environ.get("PUENTE_TOPICO", ""),
                    help="topico ntfy que te dio Arena (es el secreto: no lo pegues publicos)")
    ap.add_argument("--relay", default=os.environ.get("PUENTE_RELAY", RELAY_DEFECTO))
    ap.add_argument("--url", default=os.environ.get("PUENTE_URL", ""), help="via http: base del puente")
    ap.add_argument("--token", default=os.environ.get("PUENTE_TOKEN", ""), help="via http: token de trabajo")
    ap.add_argument("--cada", type=float, default=4.0, help="segundos entre sondeos")
    ap.add_argument("--tmo", type=int, default=180, help="segundos max por orden")
    ap.add_argument("--dir", default=os.getcwd(), help="cwd donde se ejecutan las ordenes")
    ap.add_argument("--confirmar", action="store_true", help="preguntar y/N antes de cada orden")
    a = ap.parse_args()
    if a.via == "ntfy":
        if not a.topico:
            ap.error("falta --topico (te lo da Arena con `puente-ntfy.py nuevo`)")
        return loop_ntfy(a) or 0
    if not a.url or not a.token:
        ap.error("via http: faltan --url y/o --token (o env PUENTE_URL/PUENTE_TOKEN)")
    return loop_http(a) or 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("\n[puente] desconectado", flush=True)
        sys.exit(130)
