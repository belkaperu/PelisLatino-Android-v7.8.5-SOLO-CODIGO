#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""puente-ntfy.py — lado ARENA del tunel via relay publico ntfy.sh.

¿Por que un relay? El preview de Arena expone el HTTP del puente detras de un
token (e2b-traffic-access-token) que solo inyecta tu navegador, no tu agente.
ntfy.sh lo resuelve: ambas maquinas hacen SOLO salidas HTTP; el topico (una
cadena aleatoria) es el secreto compartido — quien no lo conoce ni lee ni
publica. Los mensajes caducan ~12 h en el relay (el estado real vive aqui en
/home/user/puente, que es compartido con puente-agente.py).

  python3 tools/puente-ntfy.py nuevo       # topico nuevo + comando listo para tu agente
  python3 tools/puente-ntfy.py publicar    # la orden pendiente -> topico-cmd (una por vez)
  python3 tools/puente-ntfy.py recoger     # resultados del topico-res -> trabajo/resultados/
  python3 tools/puente-ntfy.py estado      # resumen json (reusa el del puente HTTP)

Y del flujo de siempre: puente-agente.py cola "..." ; ... resultados.
"""
import argparse
import importlib.util
import json
import os
import secrets
import sys
import time
import urllib.request

AQUI = os.path.dirname(os.path.abspath(__file__))


def _cargar(nombre, modulo):
    esp = importlib.util.spec_from_file_location(modulo, os.path.join(AQUI, nombre))
    m = importlib.util.module_from_spec(esp)
    esp.loader.exec_module(m)
    return m


pa = _cargar("puente-agente.py", "puente_agente")  # reusa cola/activo/resultados/log

RELAY = os.environ.get("PUENTE_RELAY", "https://ntfy.sh")
LIMITE_INLINE = 3800  # el relay publico corta mensajes a 4096 B


def http(url, data=None, ctype=None, timeout=25):
    h = {"User-Agent": "puente-ntfy/1"}
    if ctype:
        h["Content-Type"] = ctype
    r = urllib.request.Request(url, data=data, headers=h)
    try:
        with urllib.request.urlopen(r, timeout=timeout) as f:
            return f.status, f.read()
    except urllib.error.HTTPError as e:  # noqa: PERF - import local pa simplificar
        return e.code, e.read()[:400]


def cfg(t, crear=False):
    p = pa.dpath(t, "ntfy.json")
    if not os.path.exists(p):
        if not crear:
            sys.exit("no hay topico aun: python3 tools/puente-ntfy.py nuevo")
        top = "pl-" + secrets.token_hex(6)
        pa.escribir_json(p, {"topico": top, "relay": RELAY,
                             "creado": time.strftime("%Y-%m-%d %H:%M:%S")})
    with open(p) as fh:
        return json.load(fh)


def cmd_nuevo(a):
    pa.ensure(a.trabajo)
    viejo = pa.dpath(a.trabajo, "ntfy.json")
    if os.path.exists(viejo) and a.rotar:
        os.rename(viejo, viejo + "." + time.strftime("%H%M%S"))
    c = cfg(a.trabajo, crear=True)
    print("topico del tunel : %s" % c["topico"])
    print("relay            : %s" % c["relay"])
    print("\nque tu agente (o tu) debe correr en TU maquina, en la carpeta del proyecto:")
    print("  python3 tools/puente-cliente.py --via ntfy --topico %s" % c["topico"])
    print("\nya esta encolado: %d orden(es) pendientes" % len(pa.ids_in(a.trabajo, "cola")))
    return 0


def cmd_publicar(a):
    pa.ensure(a.trabajo)
    c = cfg(a.trabajo)
    with pa.LOCK:
        o = pa.despacho(a.trabajo)
    if not o:
        print("nada pendiente (o PAUSA activa): encola con  puente-agente.py cola \"...\"")
        return 1
    if o.get("reenvio"):
        print("(reenvio la orden %06d que ya esta en vuelo)" % o["id"])
    cmd = o["cmd"]
    payload = {"id": o["id"], "cmd": cmd, "t": int(time.time())}
    if len(json.dumps(payload, ensure_ascii=False).encode("utf8")) > LIMITE_INLINE:
        cmd = cmd[:LIMITE_INLINE - 300] + "\n#[TRUNCADO por limite 4KB del relay: parte la orden]"
        payload["cmd"] = cmd
        pa.log(a.trabajo, "ntfy orden %06d truncada a %d" % (o["id"], len(cmd)))
    st, body = http("%s/%s-cmd" % (c["relay"], c["topico"]),
                    data=json.dumps(payload, ensure_ascii=False).encode("utf8"),
                    ctype="application/json")
    pa.log(a.trabajo, "ntfy publicar %06d -> HTTP %d" % (o["id"], st))
    print("publicada orden %06d al relay (HTTP %d) — el cliente la ejecuta en su proximo sondeo"
          % (o["id"], st))
    if not (200 <= st < 300):
        print("respuesta del relay: %s" % body.decode("utf8", "replace")[:200])
        return 1
    return 0


def cmd_recoger(a):
    pa.ensure(a.trabajo)
    c = cfg(a.trabajo)
    sp = pa.dpath(a.trabajo, "ntfy-since.txt")
    ult = 0
    if os.path.exists(sp):
        try:
            ult = int(open(sp).read().strip())
        except ValueError:
            ult = 0
    since = str(ult) if ult else "all"
    st, body = http("%s/%s-res/json?poll=1&since=%s" % (c["relay"], c["topico"], since))
    if st != 200:
        print("relay no contesto: HTTP %s %s" % (st, body[:200]))
        return 1
    n = 0
    for linea in body.decode("utf8", "replace").splitlines():
        linea = linea.strip()
        if not linea:
            continue
        try:
            m = json.loads(linea)
        except ValueError:
            continue
        if m.get("event") != "message":
            continue
        ult = max(ult, int(m.get("time", 0)) + 1)
        datos = None
        url_adj = (m.get("attachment") or {}).get("url")
        if url_adj:
            try:
                st2, blob = http(url_adj)
                datos = json.loads(blob)
            except Exception as e:
                print("adjunto legible? %s (%s)" % (st2 if "st2" in dir() else "?", e))
                continue
        else:
            try:
                datos = json.loads(m.get("message", ""))
            except ValueError:
                continue
        if not isinstance(datos, dict) or "id" not in datos:
            continue
        pa.recibir_resultado(a.trabajo, datos)
        print("recogido %06d: rc=%s host=%s" % (datos["id"], datos.get("rc"), datos.get("host", "")))
        n += 1
    if ult:
        with open(sp, "w") as fh:
            fh.write(str(ult))
    if not n:
        print("(aun no hay resultados en el relay — el cliente los publica tras ejecutar)")
        return 1
    return 0


def main(argv=None):
    ap = argparse.ArgumentParser(prog="puente-ntfy.py", description=__doc__.splitlines()[0])
    ap.add_argument("--trabajo", default=os.environ.get("PUENTE_DIR", pa.TRABAJ_DEFECTO))
    sub = ap.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("nuevo", help="topico nuevo (secreto compartido)")
    s.add_argument("--rotar", action="store_true", help="archivar el topico anterior")
    s.set_defaults(fn=cmd_nuevo)
    s = sub.add_parser("publicar", help="entregar la orden pendiente por el relay")
    s.set_defaults(fn=cmd_publicar)
    s = sub.add_parser("recoger", help="bajar resultados del relay")
    s.set_defaults(fn=cmd_recoger)
    s = sub.add_parser("estado", help="estado del puente (json)")
    s.set_defaults(fn=lambda a: (print(json.dumps(pa.estado_dict(a.trabajo), ensure_ascii=False, indent=1)), 0)[1])
    a = ap.parse_args(argv)
    return a.fn(a)


if __name__ == "__main__":
    sys.exit(main())
