#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Convierte el JSON capturado por tools/drama-extraer.js en una lista m3u.

Por que un convertidor aparte: la captura necesita TU sesion de DramaLive (el
API principal responde 32 bytes de relleno a cualquier payload anonimo y la via
androidApi.php exige credenciales que por acuerdo NO se usan). El navegador
exporta canales-drama.json con todo lo visto; aqui eso se convierte offline, y
sirve tambien para listas que ya tengas en JSON de otro origen.

Uso:
    python3 tools/drama-extraer.py m3u canales-drama.json [-o canales.m3u]
    python3 tools/drama-extraer.py verificar        # auto-test sin red
"""
import argparse, json, re, sys

NOMBRE = ('name', 'title', 'channel_name', 'cname', 'tvg_name', 'label', 'channel')
URL    = ('url', 'link', 'href', 'stream_url')
GRUPO  = ('category', 'group', 'group_title', 'live_category', 'type')
LOGO   = ('logo', 'tvg_logo', 'image', 'thumb')

def _primero(o, claves):
    for k in claves:
        v = o.get(k)
        if isinstance(v, str) and v.strip():
            return v.strip()
    return None

def canales_de(obj):
    """Recorre cualquier JSON y emite (nombre, url, grupo, logo) deduplicados."""
    vistos, salida = set(), []
    from collections import deque
    pila = deque([obj])          # FIFO: el orden de los Hermanos es el del JSON
    while pila:
        x = pila.popleft()
        if isinstance(x, list):
            pila.extend(x); continue
        if not isinstance(x, dict):
            continue
        nombre = _primero(x, NOMBRE)
        url = _primero(x, URL)
        if nombre and url:
            clave = (nombre, url)
            if clave not in vistos:
                vistos.add(clave)
                salida.append({'name': nombre, 'url': url,
                               'group': _primero(x, GRUPO) or 'DramaLive',
                               'logo': _primero(x, LOGO)})
        else:
            servidor = x.get('server') or x.get('server_url')
            ruta = x.get('path') or x.get('stream_path')
            if nombre and isinstance(servidor, str) and servidor:
                s = re.sub(r'^https?://', '', servidor.strip()).rstrip('/')
                p = str(ruta or '').strip('/').lstrip('/')
                if not re.search(r'index\.(html|m3u8)$', p):
                    p = (p + '/' if p else '') + 'index.html'
                clave = (nombre, s + p)
                if clave not in vistos:
                    vistos.add(clave)
                    salida.append({'name': nombre, 'url': 'https://' + s + '/' + p,
                                   'group': _primero(x, GRUPO) or 'DramaLive',
                                   'logo': _primero(x, LOGO)})
        pila.extend(x.values())
    return salida

def a_m3u(canales):
    lineas = ['#EXTM3U']
    for c in canales:
        t = c['name'].replace('"', "'")
        g = (c.get('group') or 'DramaLive').replace('"', "'")
        logo = (' tvg-logo="%s"' % c['logo']) if c.get('logo') else ''
        lineas.append('#EXTINF:-1 tvg-name="%s" group-title="%s"%s,%s' % (t, g, logo, t))
        lineas.append(c['url'])
    return '\n'.join(lineas) + '\n'
def cmd_m3u(a):
    datos = json.load(open(a.json, encoding='utf-8'))
    if isinstance(datos, dict) and 'canales' in datos:
        datos = datos['canales']
    cs = canales_de(datos)
    if not cs:
        print('el JSON no trae nada con forma de canal (nombre + url/server-path).', file=sys.stderr)
        return 3
    texto = a_m3u(cs)
    if a.salida:
        open(a.salida, 'w', encoding='utf-8').write(texto)
        print('%d canal(es) -> %s' % (len(cs), a.salida))
    else:
        sys.stdout.write(texto)
    return 0

MUESTRA = {
 'extraido': '2026-10-08T00:00:00Z',
 'canales': [
   {'name': 'Canal Uno', 'server': '190.93.224.42', 'path': 'live/c1', 'category': 'Peru'},
   {'name': 'Canal Uno', 'server': '190.93.224.42', 'path': 'live/c1', 'category': 'Peru'},
   {'name': 'Bein X', 'url': 'https://x.net/a/index.m3u8', 'group': 'Deportes', 'logo': 'https://l/i.png'},
   {'anidado': {'title': 'Hijo', 'link': 'https://y.net/z/index.html', 'type': 'Kids'}},
   {'name': 'Basura sin ruta'},
   {'name': 'Solo server', 'server': 'https://s.net/', 'path': ''}
 ]}

def cmd_verificar(a):
    cs = canales_de(MUESTRA)
    nombres = [c['name'] for c in cs]
    assert nombres == ['Canal Uno', 'Bein X', 'Solo server', 'Hijo'], nombres  # BFS: el anidado va despues
    assert cs[0]['url'] == 'https://190.93.224.42/live/c1/index.html', cs[0]['url']
    assert cs[1]['group'] == 'Deportes' and cs[1]['logo'].endswith('.png')
    assert cs[2]['url'] == 'https://s.net/index.html', cs[2]['url']
    assert cs[3]['url'] == 'https://y.net/z/index.html', cs[3]['url']
    m = a_m3u(cs)
    assert m.startswith('#EXTM3U') and m.count('#EXTINF') == 4, m.count('#EXTINF')
    assert 'group-title="Peru"' in m and 'tvg-logo="https://l/i.png"' in m
    print('convertidor json->m3u : ok (6 filas -> 4 canales, dedupe, server+path, anidado, logo, grupos)')
    return 0

def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = ap.add_subparsers(dest='cmd', required=True)
    m = sub.add_parser('m3u', help='JSON (o array) -> lista m3u')
    m.add_argument('json'); m.add_argument('-o', '--salida')
    m.set_defaults(fn=cmd_m3u)
    v = sub.add_parser('verificar', help='auto-test sin red'); v.set_defaults(fn=cmd_verificar)
    a = ap.parse_args(argv)
    return a.fn(a)

if __name__ == '__main__':
    sys.exit(main())
