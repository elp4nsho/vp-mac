> Notas internas del laboratorio (en español). Guía actualizada en inglés: [lab.md](lab.md).

# macgl: herramientas para OpenGL 4.1 / Metal en Mac

Nació arreglando Project Viewpoint (ver `../_vpmac/README.md`), pero no depende de Viewpoint. Sirve para cualquier
programa LWJGL u OpenGL que se cuelgue, crashee o no compile en el Mac.

## lab/: laboratorio de shaders

Compila y dibuja un par vertex/fragment en un contexto **OpenGL 4.1 Core oculto**, con la misma LWJGL y el mismo
GLFW del juego. Lo hace en un proceso aparte con tiempo límite, porque un cuelgue de Metal no se puede cortar
desde adentro. El primer dibujo es el momento en que el driver de Apple arma el pipeline de Metal y compila de
verdad.

```
lab/build.sh                                   compilar (una vez)
lab/lab.py run     <vs> <fs> [--state foto.json]   un intento: ok / FAIL / HANG / CRASH
lab/lab.py defines <vs> <fs>                   prende y apaga cada #define PIPELINE_* 0/1
lab/lab.py bisect  <vs> <fs>                   achica main() a la sentencia culpable (ojo: código muerto)
lab/lab.py stubs   <vs> <fs> [--only f,g]      vacía cada función: cuál quita el problema
lab/lab.py sweep   <carpeta>                   empareja y prueba todos los shaders volcados
lab/lab.py stress  <vs> <fs> [--n 12 --parallel 4 --state foto.json]
                                               cuenta caídas del compilador (bugs intermitentes)
```

- **CRASH** = apareció un `~/Library/Logs/DiagnosticReports/MTLCompilerService-*.ips` nuevo durante la prueba.
- `--state` reproduce una foto del juego (`state_<vs>+<fs>.json` de VPMac `StateSnap`): formatos de render
  targets, depth/stencil, blending, atributos de vértice con divisor, y para cada sampler una textura con el
  formato del juego.
- Sin foto: cada sampler recibe una textura del tipo justo. Sin textura, el driver usa una "textura cero" y se
  salta la variante pesada; se puede forzar con `MACGL_TEXTURES=0`.

**Lecciones que costaron:**
- El bug del compilador de Apple (MTE, macOS 26 + M5) es **intermitente**. Una sola compilación que sale bien no
  prueba nada; usar `stress`.
- `bisect` engaña: al cortar `main()`, el compilador borra todo lo que deja de usarse. Preferir `stubs`.
- La foto de estado hay que tomarla en el dibujo GL **real**. Con imitaciones como las de xiveboy, las texturas se
  enlazan adentro de la imitación.
- Compilar los dos Lanczos 4×4 en un mismo shader tardaba más de 5 s (cada uno solo, unos 90 ms) y era lo que
  gatillaba el bug.
