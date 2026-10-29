#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""puente-agente.py — tunel entre el sandbox de Arena y TU agente local.

No es magia ni intrusion: es un servidor HTTP honesto en este sandbox.
Tu maquina hace POLLING hacia afuera (nada entra a tu red):

  GET  /orden      -> la orden encolada mas antigua {id,cmd} | 204 si no hay nada
  POST /resultado  -> {id, rc, stdout, stderr, host} de lo que tu cliente ejecuto
  GET  /estado     -> resumen para humanos      GET /hola -> ping (sin token)
  todo (menos /hola) exige el token de trabajo/token.txt

Del lado de Arena yo encolo y leo (mismo filesystem, no hace falta HTTP):

  python3 tools/puente-agente.py cola "pytest -q tests/"   # orden para tu agente
  python3 tools/puente-agente.py resultados [--n 5]        # que respondio
  python3 tools/puente-agente.py estado | pausa | reanuda | token

Interruptor de seguridad: `pausa` crea trabajo/PAUSA y /orden devuelve 204
siempre (tu cliente puede quedarse corriendo sin que nada se ejecute).
Mientras haya un resultado sin leer de la orden en vuelo, /orden la reenvia
(mismo id) por si tu cliente se reinicio a mitad.

Solo stdlib, Python 3.9+.
"""
import argparse
import json
import os
import re
import secrets
import sys
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TRABAJ_DEFECTO = "/home/user/puente"
LOCK = threading.Lock()


def dpath(t, *a):
    return os.path.join(t, *a)


def ensure(t):
    for d in ("cola", "volando", "hechos", "resultados"):
        os.makedirs(dpath(t, d), exist_ok=True)


def get_token(t):
    p = dpath(t, "token.txt")
    if not os.path.exists(p):
        with open(p, "w") as fh:
            fh.write(secrets.token_urlsafe(24))
        os.chmod(p, 0o600)
    with open(p) as fh:
        return fh.read().strip()


def ids_in(t, d, ext="cmd"):
    out = []
    p = dpath(t, d)
    if os.path.isdir(p):
        for f in os.listdir(p):
            m = re.fullmatch(r"(\d{6})\." + ext, f)
            if m:
                out.append(int(m.group(1)))
    return sorted(out)


def next_id(t):
    ids = ids_in(t, "cola") + ids_in(t, "volando") + ids_in(t, "hechos", "cmd")
    return (max(ids) + 1) if ids else 1


def log(t, msg):
    try:
        with open(dpath(t, "log.txt"), "a") as fh:
            fh.write(time.strftime("%Y-%m-%d %H:%M:%S ") + msg + "\n")
    except OSError:
        pass


def leer_json(p):
    try:
        with open(p) as fh:
            return json.load(fh)
    except (OSError, ValueError):
        return None


def escribir_json(p, obj):
    tmp = p + ".tmp"
    with open(tmp, "w") as fh:
        json.dump(obj, fh, ensure_ascii=False)
    os.replace(tmp, p)


def despacho(t):
    """Orden a entregar al cliente: la en vuelo (reenvio) o la mas antigua pendiente."""
    act = leer_json(dpath(t, "activo.json"))
    if act and not os.path.exists(dpath(t, "resultados", "%06d.json" % act["id"])):
        return {"id": act["id"], "cmd": act["cmd"], "reenvio": True}
    if os.path.exists(dpath(t, "PAUSA")):
        return None
    pendientes = ids_in(t, "cola")
    if not pendientes:
        return None
    i = pendientes[0]
    with open(dpath(t, "cola", "%06d.cmd" % i)) as fh:
        cmd = fh.read()
    os.rename(dpath(t, "cola", "%06d.cmd" % i), dpath(t, "volando", "%06d.cmd" % i))
    escribir_json(dpath(t, "activo.json"),
                  {"id": i, "cmd": cmd, "ts": time.strftime("%H:%M:%S")})
    log(t, "despacho %06d: %r" % (i, cmd[:160]))
    return {"id": i, "cmd": cmd}


def recibir_resultado(t, body):
    i = int(body["id"])
    r = {"id": i,
         "rc": int(body.get("rc", -1)),
         "stdout": str(body.get("stdout", ""))[:120000],
         "stderr": str(body.get("stderr", ""))[:60000],
         "host": str(body.get("host", ""))[:120],
         "sis": str(body.get("sis", ""))[:160],
         "ts": time.strftime("%H:%M:%S")}
    escribir_json(dpath(t, "resultados", "%06d.json" % i), r)
    v = dpath(t, "volando", "%06d.cmd" % i)
    if os.path.exists(v):
        os.rename(v, dpath(t, "hechos", "%06d.cmd" % i))
    act = leer_json(dpath(t, "activo.json"))
    if act and act.get("id") == i:
        try:
            os.remove(dpath(t, "activo.json"))
        except OSError:
            pass
    log(t, "resultado %06d rc=%d host=%s" % (i, r["rc"], r["host"]))
    return r


def estado_dict(t):
    act = leer_json(dpath(t, "activo.json"))
    res = ids_in(t, "resultados", "json")
    ult = []
    for i in res[-8:]:
        r = leer_json(dpath(t, "resultados", "%06d.json" % i))
        if r:
            ult.append({"id": i, "rc": r["rc"], "ts": r["ts"], "host": r["host"]})
    return {"pausa": os.path.exists(dpath(t, "PAUSA")),
            "pendientes": ids_in(t, "cola"),
            "en_vuelo": ids_in(t, "volando"),
            "activo": act,
            "resultados": ult}


class Manejador(BaseHTTPRequestHandler):
    trabajo = None
    token = None
    version = "puente-arena/1"

    def log_message(self, fmt, *a):  # ruido default fuera; usamos log.txt
        pass

    def _send(self, code, obj=None, raw=None):
        cuerpo = b"" if obj is None and raw is None else (
            raw if raw is not None else json.dumps(obj, ensure_ascii=False).encode("utf8"))
        self.send_response(code)
        if code != 204:
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(cuerpo)))
        self.end_headers()
        if code != 204 and cuerpo:
            self.wfile.write(cuerpo)

    def _tok_ok(self):
        q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
        dado = (q.get("token") or [self.headers.get("X-Puente-Token", "")])[0]
        return dado == Manejador.token

    def do_GET(self):
        p = urllib.parse.urlparse(self.path).path
        if p == "/hola":
            return self._send(200, {"puente": "arena", "ok": True,
                                   "pendientes": len(ids_in(Manejador.trabajo, "cola"))})
        if not self._tok_ok():
            log(Manejador.trabajo, "401 %s %s" % (self.command, p))
            return self._send(401, {"error": "token requerido"})
        if p == "/orden":
            with LOCK:
                o = despacho(Manejador.trabajo)
            return self._send(204) if o is None else self._send(200, o)
        if p == "/estado":
            with LOCK:
                return self._send(200, estado_dict(Manejador.trabajo))
        return self._send(404, {"error": "ruta desconocida"})

    def do_POST(self):
        p = urllib.parse.urlparse(self.path).path
        if p != "/resultado":
            return self._send(404, {"error": "ruta desconocida"})
        if not self._tok_ok():
            return self._send(401, {"error": "token requerido"})
        try:
            n = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            n = 0
        if n <= 0 or n > 4_000_000:
            return self._send(400, {"error": "body invalido"})
        try:
            body = json.loads(self.rfile.read(n))
            assert "id" in body
        except (ValueError, AssertionError):
            return self._send(400, {"error": "json invalido"})
        with LOCK:
            r = recibir_resultado(Manejador.trabajo, body)
        return self._send(200, {"ok": True, "id": r["id"]})


def cmd_servir(a):
    t = a.trabajo
    ensure(t)
    Manejador.trabajo = t
    Manejador.token = get_token(t)
    srv = ThreadingHTTPServer(("0.0.0.0", a.puerto), Manejador)
    log(t, "sirviendo en :%d" % a.puerto)
    print("puente listo en 0.0.0.0:%d  (trabajo=%s, cola=%d)"
          % (a.puerto, t, len(ids_in(t, "cola"))), flush=True)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print("puente detenido", flush=True)
    return 0


def cmd_cola(a):
    t = a.trabajo
    ensure(t)
    cmd = " ".join(a.comando)[:8000]
    i = next_id(t)
    with open(dpath(t, "cola", "%06d.cmd" % i), "w") as fh:
        fh.write(cmd)
    log(t, "encolado %06d: %r" % (i, cmd[:160]))
    print("ENCOLADO %06d: %s" % (i, cmd[:200]))
    return 0


def cmd_resultados(a):
    t = a.trabajo
    ids = ids_in(t, "resultados", "json")[-a.n:]
    if not ids:
        print("(sin resultados: tu cliente aun no conecta o no hay nada ejecutado)")
        return 1
    for i in ids:
        r = leer_json(dpath(t, "resultados", "%06d.json" % i))
        print("--- %06d  rc=%s  host=%s  %s" % (i, r["rc"], r["host"], r["ts"]))
        print(r["stdout"][:6000] or "(stdout vacio)")
        if r["stderr"].strip():
            print("STDERR: " + r["stderr"][:2000])
    return 0


def main(argv=None):
    ap = argparse.ArgumentParser(prog="puente-agente.py", description=__doc__.splitlines()[0])
    ap.add_argument("--trabajo", default=os.environ.get("PUENTE_DIR", TRABAJ_DEFECTO),
                    help="directorio de estado (def. %s)" % TRABAJ_DEFECTO)
    sub = ap.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("servir", help="levanta el HTTP del tunel")
    s.add_argument("--puerto", type=int, default=8777)
    s.set_defaults(fn=cmd_servir)
    s = sub.add_parser("cola", help="encola una orden para tu agente local")
    s.add_argument("comando", nargs="+")
    s.set_defaults(fn=cmd_cola)
    s = sub.add_parser("resultados", help="muestra lo que respondio tu cliente")
    s.add_argument("--n", type=int, default=5)
    s.set_defaults(fn=cmd_resultados)
    s = sub.add_parser("estado", help="resumen del puente (json)")
    s.set_defaults(fn=lambda a: (print(json.dumps(estado_dict(a.trabajo), ensure_ascii=False, indent=1)), 0)[1])
    s = sub.add_parser("pausa", help="no entregar ordenes nuevas (tu cliente sigue vivo)")
    s.set_defaults(fn=lambda a: (open(dpath(a.trabajo, "PAUSA"), "w").close(), print("PAUSA activa"))[1])
    s = sub.add_parser("reanuda", help="quitar la pausa")
    s.set_defaults(fn=lambda a: ((os.path.exists(dpath(a.trabajo, "PAUSA")) and os.remove(dpath(a.trabajo, "PAUSA"))), print("reanudado"))[1])
    s = sub.add_parser("token", help="imprime el token de trabajo")
    s.set_defaults(fn=lambda a: (ensure(a.trabajo), print(get_token(a.trabajo)), 0)[2])
    a = ap.parse_args(argv)
    return a.fn(a)


if __name__ == "__main__":
    sys.exit(main())
