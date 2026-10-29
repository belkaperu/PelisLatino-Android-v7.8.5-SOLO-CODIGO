#!/usr/bin/env node
/*
  Convierte docs/exportar-canales-navegador.js en un MARCADOR.

      node tools/… no:  node docs/hacer-bookmarklet.mjs
                        (desde la raíz del proyecto)

  Escribe docs/exportar-canales-bookmarklet.txt con una sola línea `javascript:…`.
  Uso: abre ese archivo, copia la línea entera, crea un marcador en Chrome/Edge con esa
  URL, y con la lista de DramaLive ya cargada en la pestaña le das al marcador: te descarga
  `dramalive.m3u`. Nada de F12 y nada de copiar y pegar.

  El compactado solo quita comentarios que ocupan la línea entera y los bloques de estrella
  alineados a la izquierda, y pega las líneas con saltos de línea (no con espacios): así
  sobreviven tanto los `//` finales de código como las dobles barras de las URLs dentro de
  los strings, que es lo que este archivo manipula. `new Function` sobre el resultado es
  parte del generador: si el compactado desequilibrara una llave, esto no compila.
*/
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const AQUI = path.dirname(fileURLToPath(import.meta.url));
export const FUENTE = path.join(AQUI, 'exportar-canales-navegador.js');
export const DESTINO = path.join(AQUI, 'exportar-canales-bookmarklet.txt');

export function compactar(codigo) {
    const lineas = String(codigo).split(/\r?\n/);
    const salida = [];
    let enBloque = false;
    for (const linea of lineas) {
        const t = linea.trim();
        if (enBloque) { if (t.endsWith('*/')) enBloque = false; continue; }
        if (t.startsWith('/*')) { if (!t.endsWith('*/')) enBloque = true; continue; }
        if (!t || t.startsWith('//')) continue;
        salida.push(t);
    }
    return salida.join('\n');   // con \n, no con espacio: si unieras con espacios, un `//` al final de una línea
                                          // de código se comería TODO lo que quedara después al cabecerla en una sola línea.
}

export function hacerBookmarklet(codigo) {
    const c = compactar(codigo);
    if (!c.includes('#EXTM3U')) throw new Error('el compactado perdió la cabecera #EXTM3U');
    if (!c.includes('Ventana')) throw new Error('el compactado perdió window.Ventana');
    new Function(c);                     // si no compila aquí, no va a compilar en la página
    return 'javascript:' + encodeURIComponent(c);
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
    const codigo = fs.readFileSync(FUENTE, 'utf8');
    const bm = hacerBookmarklet(codigo);
    fs.writeFileSync(DESTINO, bm + '\n', 'utf8');
    console.log('bookmarklet: ' + bm.length + ' caracteres (código compactado ' + codigo.length + ' → ' + compactar(codigo).length + ')');
    console.log('escrito en ' + path.relative(path.join(AQUI, '../..'), DESTINO));
}
