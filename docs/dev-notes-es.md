> Notas internas de desarrollo (en español), tal como se escribieron durante el trabajo. Las rutas `_vpmac/` y
> `../_macgl/` corresponden a la raíz de este repo y a `lab/`.

# VPMac: Project Viewpoint en Mac (OpenGL 4.1 sobre Metal)

> **Estado (2026-10-24):** la primera persona **funciona**, a unos 50 fps (sección 7), en el MacBook Air M5 (16 GB) del dueño, con Viewpoint
> **0.1.6.1** (Workshop 3809306528), ZombieBuddy 2.3.4 y PZ Build 42.21. Es de uso personal y **no se publica**: la
> licencia de Viewpoint prohíbe redistribuirlo modificado y VPMac lo modifica en memoria.
>
> Si eres un agente que retoma esto: lee este archivo completo y después `_almanac/06-troubleshooting.md`
> (secciones "Viewpoint 0.1.6.1 en la Mac M5"). El laboratorio de shaders tiene su propio README en
> `../_macgl/README.md`.

---

## 1. Qué es cada pieza

```
Project Zomboid.app/Contents/Info.plist  (JVMOptions, en este orden)
  -javaagent:pz-mac41-bridge.jar     puente de xiveboy: contexto OpenGL 4.1 Core sobre Metal para el JUEGO
  -javaagent:vpmac-agent.jar         ESTE proyecto: reescribe Viewpoint al cargarlo
  -javaagent:ZombieBuddy.jar=frontend=swing   cargador de mods Java (carga Viewpoint.jar del Workshop)
```

- **El problema de fondo:** macOS solo tiene OpenGL 4.1 y Viewpoint usa 4.2–4.5 (DSA, texture storage, texture
  views, vertex attrib binding, timestamps, program interface queries…). Una función que no existe es un puntero
  nulo, y el resultado es `EXC_BAD_ACCESS` en liblwjgl.
- **El parche de xiveboy** (Workshop 3812168749, abandonado el 2026-10-23, autoriza modificarlo):
  - `pz-mac41-bridge.jar`: parchea el juego para abrir un contexto 4.1 Core. Trae ASM completa, que VPMac reutiliza.
  - Un `Viewpoint.jar` 0.1.6 modificado con 44 "imitaciones" en `viewpoint/mac41/*` (Buffers41, Draws41, Pages41,
    Units41, Textures41, Shaders41…) y 458 llamadas redirigidas.
  - Steam lo pisó al actualizar a 0.1.6.1 (2026-10-16), y ahí se rompió todo.
- **VPMac** (este proyecto) hace lo mismo pero **en memoria y por reglas**, así que el jar del Workshop no se toca:
  - Toma las imitaciones `viewpoint/mac41` del payload de xiveboy, que no dependen de Viewpoint (solo de LWJGL y
    PZ), y las empaqueta en el agente.
  - `Transformer` redirige al cargar las llamadas GL 4.2+ de cada clase `viewpoint/*` a esas imitaciones, según la
    tabla `redirects.txt` sacada del jar de xiveboy, y repite sus ajustes manuales por clase y método.
  - Agrega lo que 0.1.6.1 trajo nuevo y xiveboy no cubría (ver sección 3).

> Los mensajes que VPMac escribe en el juego (consola, `vpmac.log`, informes) están en **inglés** porque los leen
> jugadores de todo el mundo. El código, sus comentarios y las herramientas de desarrollo siguen en español.

## 2. Archivos

| Archivo | Qué hace |
|---|---|
| `src/vpmac/Agent.java` | `premain`: carga reglas y registra el transformador |
| `src/vpmac/Config.java` | interruptores desde `~/Zomboid/vpmac.properties` (`fps`, `diag`, `dump`, `dumpDir`). Apagado no cuesta nada |
| `src/vpmac/Rules.java` + `redirects.txt` | tabla "función GL → clase imitación" |
| `src/vpmac/Transformer.java` | reescritura ASM de las clases de Viewpoint (reglas generales y por clase); avisa `Viewpoint changed: missing …` si un método esperado no está |
| `src/vpmac/Views41.java` | emula `glTextureView` (cascadas de sombra del sol de 0.1.6.1): cada vista es una textura 2D real que se copia (blit de profundidad) a su capa del arreglo justo antes de usar el arreglo |
| `src/vpmac/Glsl.java` | **reglas GLSL** aplicadas a todo shader justo antes de `glShaderSource` (ver sección 3c), más el volcado opcional |
| `src/vpmac/Timing.java`, `Probe.java` | consultas de tiempo y de interfaz de programa seguras sin GL 4.3 |
| `src/vpmac/Diag.java` | (diag) "primer uso/primer dibujo con <programa> [hashVS+hashFS]" y un vigilante del render |
| `src/vpmac/StateSnap.java` | (dump) foto JSON del estado GL en el primer dibujo de cada programa |
| `src/vpmac/Fps.java` | (fps) cada 10 s escribe FPS, tiempo medio de cuadro, mediana y el 1% peor |
| `src/vpmac/Bench.java` + `bench.sh` | benchmark automático de primera persona (sección 7) |
| `src/vpmac/Accel.java` | aceleradores: lotes de dibujo, mira cada N cuadros, páginas solo si hacen falta (conteo rápido + exacto compilando) |
| `src/vpmac/TLCache.java` | caché de ThreadLocal de las imitaciones para el hilo de render |
| `src/vpmac/FastUnits41.java` + `UnitsState`/`UnitsInput` | reemplazo de `Units41` de xiveboy: misma lógica con arreglos de enteros en vez de `HashMap` (se entrega renombrado al cargarse `viewpoint/mac41/Units41`) |
| `stubs/` | clases del juego mínimas solo para compilar (javac 21 no lee las clases Java 25 del juego); no van al jar |
| `src/vpmac/GpuPack.java` + `tools/GpuPackTest.java` | copia de páginas en GPU (apagada: más lenta en Apple) y su prueba de equivalencia |
| `build.sh` / `install.sh` / `uninstall.sh` | compilar, instalar (respalda el Info.plist en `backup/`) y desinstalar |
| `src/vpmac/Session.java` + `Report.java` | detección de sesiones que terminaron mal, informe para compartir (ventana con copiar) y detector de congelamiento |
| `src/vpmac/Compat.java` | informe de compatibilidad al arrancar (chip, RAM, macOS, límites de GPU) |
| `VPMac - Share report.command`, `PLAYERS.md`, `workshop-bug-thread.txt` | informe a pedido, guía para jugadores e hilo fijado de "Bug reports" |
| `src/vpmac/zb/PreMain.java` + `Main.java`, `package-zb.sh`, `zb-test-mode.sh`, `workshop-desc.bbcode` | Opción A: VPMac como mod de ZombieBuddy con preload (sección 5a) |
| `compat-test.sh` + `paths.sh` | prueba de compatibilidad completa en otra Mac; rutas de Steam detectadas |
| `tools/verify.sh` + `tools/Harness.java` | verificación offline de la reescritura (llamadas con destino, bytecode válido en Java 25) |
| `profile.sh` + `tools/hot.py` | perfilado JFR: `on`, `off` y `report` (métodos más calientes por hilo y por dueño VP/M41/VPMac/PZ) |

## 3. Qué se rompió y cómo se arregló (cronología con evidencia)

### 3a. Crash al cargar el mundo
- **Síntoma:** la carga se cae en STAGING justo después de `[Viewpoint] mesh arena: …`.
- **Evidencia:** `.ips` de JavaAppLauncher con `EXC_BAD_ACCESS → jni_FatalError → liblwjgl`, causado por
  `MeshArena.recordAttributes → GL43.glVertexAttribFormat`.
- **Causa:** se había perdido el jar parchado. Además 0.1.6.1 trae llamadas nuevas:
  - `glTextureView` y `glCreateTextures`/`glTextureStorage3D`/`glTextureParameteri` en ShadowPass.
  - `glGetProgramInterfacei`/`glGetProgramResourceiv` en StageSamplers.
- **Arreglo:** VPMac con las reglas de xiveboy más `Views41` y `Probe`. Verificado offline con un arnés: 103 clases
  reescritas, 0 errores de bytecode y todas las llamadas nuevas con destino. 96 de 102 clases quedan idénticas a
  las de xiveboy; las demás difieren por cambios propios de 0.1.6.1.

### 3b. ZombieBuddy pide aprobar el Viewpoint oficial
- Una ventana Swing que solo el dueño puede aprobar; el agente nunca la aprueba solo. Ya quedó recordada:
  `~/.zombie_buddy/mod_approvals.json` tiene 3 mods.

### 3c. Congelamiento y después cierre al apretar O (primera persona)
- **Lo que se veía:**
  - Primero el render quedaba esperando a `MTLCompilerService`, que giraba al 100% sin terminar nunca.
  - Después de un primer arreglo, el juego se cerraba solo.
- **La causa real** quedó clara al juntar `~/Library/Logs/DiagnosticReports`:
  - `MTLCompilerService-*.ips` muestra `EXC_ARM_MTE_TAGCHECK_FAIL` en `llvm::DominatorTreeBase<MachineBasicBlock>::eraseNode`.
    Es un use-after-free en el LLVM de la GPU AGX de Apple (macOS 26.5.1) que el MTE del M5 corta.
  - Si el driver reintenta, se ve como un cuelgue.
  - Si cae a su respaldo, `JavaAppLauncher-*.ips` muestra `gleFallbackBegin → gleDirtyBufferObjectCurrentBindPoints`
    (puntero nulo) y el juego muere.
  - Ojo: `MTLCompilerService-…-135903.0003.ips` se re-toca solo; su fecha no sirve como evidencia.
- **Cómo se encontró el shader** (todo con herramientas de este repo):
  1. `Diag` y su vigilante: el último programa activo era **gbuffer**.
  2. Volcado del GLSL final, y el laboratorio lo reprodujo fuera del juego.
  3. `lab.py stubs` (vaciar cada función): el problema está en la cadena de muestreo
     `sampleSprite→lanczos` + `sampleFloor→lanczosFloor`.
  4. `StateSnap` + `lab.py run --state`: reproducción con el estado exacto del juego (RGBA8+RGBA16F+RGBA8,
     D24S8, 9 atributos con instancing y texturas buffer RGBA32F/R32F).
  5. `lab.py stress`: el bug es **intermitente**. Con los dos Lanczos juntos: 6 caídas del compilador en 12
     compilaciones. Con uno solo: 0 en 12.
- **Arreglo** (reglas en `Glsl.java`):
  - `lanczos`: precalcula los 8 pesos del filtro 4×4 en vez de recalcular `sin()` 20 veces. El resultado es
    idéntico y Metal compila más fácil.
  - `lanczos-floor`: en shaders que también tienen Lanczos de sprites, saca el Lanczos del piso. El piso cae a
    bilineal si se eligió Lanczos; con la configuración del dueño (Nearest en todo) no cambia nada visible.
  - Resultado: 16 de 16 compilaciones bien y 0 caídas. En el juego, primera persona OK.

### 3d. Congelamiento en modo Modern (2026-10-25)
- **Síntoma:** al poner Viewpoint en Modern o High y entrar en primera persona, el juego se congela con el
  compilador de Metal al 100%.
- **Diagnóstico:** el informe de congelamiento automático (`freeze-*.txt`) mostró la pila
  `SurfacePass.light → Gl.screenTriangle`. Con `diag` + `dump`, el último programa dibujado fue `light`
  (7286 líneas).
- **Culpable:** apagar efectos de a uno o por grupos no sirvió. La causa era la **emulación de texturas
  empaquetadas de xiveboy** (`Pages41`): en Modern, `light` lee 19 texturas empaquetadas, y cada `texture(int,…)`
  metía unas 20 lecturas copiadas (todos los caminos de filtrado × 4 taps, cada uno con su bucle de mipmaps y su
  swizzle). Con la emulación vaciada compilaba en 4 ms.
- **Arreglo (regla `pages-loops`, `glsl/pages-fast.glsl`):** la misma emulación con bucles (1 o 2 niveles × 1 o 4
  taps), con una sola lectura escrita por llamada y el swizzle aplicado una vez al final.
  - Compila en **12 ms**, sin caídas en 12 de 12 compilaciones.
  - `tools/PagesEquivTest.java` da **0 píxeles distintos** contra la original (8 configuraciones × 3 modos;
    diferencia máxima 1,8e-7).
- **Resultado:** Modern funciona, a **30,8 fps** sin diagnóstico (Vanilla ~50).
- **Lo que queda en Modern:** `Pages41` re-copia con `glGetTexImage` unos 15–24 texturas por cuadro, unos 62 MB de
  render targets a resolución de pantalla, y no hay unidades libres para dejarlas nativas. Arreglarlo pide rediseñar
  cómo llegan esas texturas al shader, por ejemplo agrupar render targets del mismo tamaño en arreglos de texturas
  copiados en la GPU.

**Logs (2026-10-25):** VPMac escribe solo en eventos. La línea de estadísticas de Viewpoint (~4 KB, decenas de
contadores) pasó de cada 10 s a cada 60 s (`vpStatsSeconds`), y la última entra al informe completo. Los
`slow frame` de Viewpoint ya venían limitados por el propio Viewpoint.

## 4. Uso diario

- **Jugar:** abrir PZ normal desde Steam. Sin `~/Zomboid/vpmac.properties`, o con todo en `false`, no hay costo
  de diagnóstico.
- **Recompilar e instalar:** `./build.sh && ./install.sh`, con el juego cerrado.
- **Desinstalar:** `./uninstall.sh`. Para dejar todo como Steam: "Verificar archivos", que también borra el puente
  de xiveboy.
- **Probar en el juego sin tocar la partida:**
  `cd .. && python3 _cine/cine.py run vp_mac_diag --keep-open` carga un mundo de prueba con solo
  ZombieBuddy + Viewpoint. El dueño aprieta **O**; no robarle el foco con osascript.
  Al terminar: `python3 _cine/cine.py restore`.

## 5. Si Viewpoint se actualiza y se rompe otra vez (checklist)

1. Con `diag=true` y `dump=true` en `~/Zomboid/vpmac.properties`, abrir el juego y mirar `console.txt`:
   - `[VPMac] WARNING: Viewpoint changed: missing …`: un método que una regla esperaba ya no existe. Ajustar `Transformer`.
   - `WARNING: a Lanczos loop was left without the 'lanczos' rule`: el shader cambió. Ajustar `Glsl`.
   - Crash con `jni_FatalError` en liblwjgl: hay una función GL nueva sin imitación. Buscar en el jar nuevo las
     llamadas a `org/lwjgl/opengl/GL4[2-6]` que no estén en `redirects.txt`.
2. Cuelgue o cierre al dibujar: revisar los `.ips` de MTLCompilerService/JavaAppLauncher. Después correr
   `../_macgl/lab/lab.py sweep _vpmac/dump` y `stress` sobre el par sospechoso, con `--state`.
3. Después de cada cambio: `./build.sh && tools/verify.sh` (arnés offline, sin abrir el juego). Tiene que decir
   `sin destino: 0` y `ERRORES DE BYTECODE: 0`.
4. Anotar lo aprendido en el almanaque.

## 5a. Publicación en el Workshop (Opción A: mod de ZombieBuddy con preload)

**Cómo funciona:** VPMac se publica como un mod normal (`~/Zomboid/Workshop/VPMac/`, que arma
`./package-zb.sh`).
- ZombieBuddy lo carga **en su premain** (antes del juego) desde el segundo arranque, si el jugador lo aprobó como
  *preload* y la firma `.zbs` es válida.
- `vpmac.zb.PreMain` toma la `Instrumentation` de ZombieBuddy (`Loader.g_instrumentation`, por reflexión; no hay
  API pública) y arranca el puente de xiveboy y VPMac, igual que los `-javaagent`.
- Así nadie edita el `Info.plist`: el jugador se suscribe, aprueba y reinicia una vez.

**Lo que NO va en el jar (licencias):** el puente `pz-mac41-bridge.jar` deriva en parte de PZ_Optimization de xD3I,
que no tiene licencia de redistribución, y el propio xiveboy lo marcó como "solo copias privadas locales".
- Por eso `VPMac.jar` lleva **solo `vpmac/**`**.
- El puente y las imitaciones `viewpoint/mac41/*` se toman de la **copia local del jugador** del ítem de xiveboy
  (3812168749, requisito). Las imitaciones se extraen de su `Viewpoint.jar` a `~/Zomboid/vpmac/cache/`, nunca su
  jar entero, porque taparía al Viewpoint real.
- Si alguien todavía tiene los `-javaagent` del `Info.plist` (install.sh), `PreMain` no arranca nada dos veces.

**Firma:** clave Ed25519 en `~/.signing/vpmac-ed25519.der` (privada: no subir, respaldar). Se firma
`ZBS:<SteamID64>:<sha256 del jar>`. La clave pública va una vez en el resumen del perfil de Steam como
`JavaModZBS:<64 hex>`, y quien sube el ítem tiene que ser esa misma cuenta. Cada vez que cambie el jar:
`./build.sh && ./package-zb.sh`, que vuelve a firmar.

**Probar como jugador nuevo:** `./zb-test-mode.sh on` (deja solo ZombieBuddy en el `Info.plist`), después
`python3 ../_cine/cine.py run vp_mac_zb` (escena con el mod VPMac). Para volver: `./zb-test-mode.sh off`.

**Pendiente antes de publicar:** permiso escrito de los autores de Viewpoint (ellu y norkus; su licencia lo exige),
idealmente también de xD3I por el puente, y pedirle a zed_0xff una API oficial para la `Instrumentation`.
`workshop.txt` queda en `visibility=private`.

## 5b. Recibir informes de los jugadores (mantenedor)

**Cómo llega un informe:**
- **Crash, cierre forzado o congelamiento:** `Session` deja una marca `running` en `~/Zomboid/vpmac/session.properties`
  y un shutdown hook la cambia a `clean` al salir bien. Si al arrancar sigue `running`, `Report` arma el informe de
  esa sesión y muestra una ventana de macOS (osascript) con *Copy short* (≤1000 caracteres, cabe en un comentario
  de Steam), *Copy full* y *Close*.
  - Se basa en el `console.txt` anterior, copiado en `premain` antes de que el juego lo borre, más `vpmac.log` y
    los `.ips` de `~/Library/Logs/DiagnosticReports` de ese rato (JavaAppLauncher, java y MTLCompilerService;
    la hora se toma del **nombre** del archivo).
- **Congelamiento en vivo:** si en primera persona no hay cuadros por 15 s, `Session.watch` guarda en ese momento
  la pila del hilo de render y del hilo del juego (`freeze-<id>.txt`), y se suma al informe.
- **Bug sin crash:** el jugador hace doble clic en `VPMac - Share report.command`.
- Los textos del informe están en **inglés** (se pegan en el Workshop) y **anonimizados**: rutas con el usuario
  pasan a `~` y los Steam IDs se tapan. Nada se envía solo.

**Cómo leerlo:**
- **`What:`** el tipo: crash nativo, freeze, excepción Java o cierre inesperado.
- **`Known issue:`** si calza con una causa ya entendida (`Report.hint`). **Cada causa nueva que se descubra, se
  agrega ahí**, y así los próximos informes vienen clasificados solos.
- **`phase:`** menú, mundo (isométrico), primera persona o congelado.
- Versiones, Mac, `VPMac config:` (los interruptores activos) y la pila del crash.

**Para pedir más detalle a un jugador:** `debug=true` en `~/Zomboid/vpmac.properties` (activa diag y fps). Para
shaders: `dump=true`, y que envíe `~/Zomboid/Workshop/_vpmac/dump` (o el `dumpDir` que configure). Esos volcados
se prueban con `../_macgl/lab/lab.py compat`.

**Hilo fijado del Workshop:** el texto listo (BBCode de Steam) está en `workshop-bug-thread.txt`. La guía para
jugadores, en inglés, está en `PLAYERS.md`.

## 6. Pendientes e ideas

- [x] far shell ya no empaqueta: con el conteo exacto de samplers activos cabe en 16 unidades. (La copia en GPU
      por transform feedback, `gpuPack`, se probó y es **más lenta**.)
- [ ] `MousePick` todavía es ~8% del tiempo nativo del render aun cada 3 cuadros: probar `pickEvery=5`, o leer la
      profundidad de otra forma.
- [ ] Lo que queda en el hilo de render es sobre todo el costo del driver por dibujo (`glDrawArraysInstanced` +
      `rebase` de atributos por comando del multi-draw emulado). Bajarlo pide emular el base instance en el shader
      (`gl_InstanceID` + uniform), que es un cambio profundo.
- [ ] `macgl` como capa genérica: auditor de llamadas GL > 4.1 para cualquier jar, y reemplazar de a poco las
      imitaciones de xiveboy por código propio con pruebas.
- [ ] Lanczos del piso de vuelta: unificar los dos bucles Lanczos en uno solo (después del `if` piso/sprite) para
      no gatillar el bug de Apple sin perder calidad.
- [ ] El vigilante de `Diag` avisa en modo isométrico aunque no pase nada (ahí Viewpoint no dibuja).

## 7. Rendimiento (2026-10-24, Air M5, escena `vp_mac_diag`, primera persona quieto, 60 s)

**Herramientas:**
- `./bench.sh [clave=valor ...]`: benchmark **automático**. Entra solo a primera persona (`Bench`, vía
  `FP.cycleAsked`), mide 60 s, cierra el juego y guarda el resultado en `bench/*.txt`.
  - Las claves `vp.*` cambian ajustes de Viewpoint solo durante la corrida.
  - `REC=nombre ./bench.sh` graba video para comparar imagen. Esos FPS no valen: **el grabador de `_cine` le quita
    ~40% a la GPU**, y por eso las pruebas manuales grabando daban ~23 fps.
- `./profile.sh on|off|report`: JFR más `tools/hot.py` (CPU por hilo y por dueño, incluido el tiempo nativo dentro
  del driver).

| Configuración | FPS | 1% peor |
|---|---|---|
| Primera versión que funcionó (`diag` siempre activo, sin aceleradores) | 32,2 | 45,9 ms |
| Diagnóstico apagado (`Config`) | 33,6 | 44,9 ms |
| + `batchDraws`, `gpuTiming=false`, `pickEvery=3` | 41,4–42,0 | 34,6–36,7 ms |
| **+ `smartPages` (configuración por defecto actual)** | **50,0–50,8** | **31–33 ms** |
| + `gpuPack` (transform feedback) | 42–43 | 41–45 ms (peor: apagado) |

| + `smartPages` exacto (compila de prueba y cuenta samplers activos): **ya nada se empaqueta** | ~+1,5% |
| + `tlCache` (intercalado: 44,0 → 46,7 promedio) | ~+6% |
| + `skipMarks` (intercalado: 48,4→50,3 y 38,9→45,0) | +4 a +16% |
| + `fastUnits` (intercalado, sin límite de 60: 34,7→37,6 y 31,6→50,6) | +8% o más |
| **Todo apagado contra todo encendido, misma tanda, sin límite de 60** | **30,3 → 52,9 fps (+75%), 1% peor 66 → 41 ms** |

**En total, +58% hasta `smartPages`.** Comparación visual con optimizaciones apagadas y encendidas: `bench/comparacion-60.jpg`. Es la
misma imagen; la diferencia es solo un leve corrimiento de cámara entre corridas.

**Qué se encontró con el perfil y por qué funcionó cada cosa:**
1. **`Diag` en cada dibujo** (`HashSet<Integer>`) y en cada `glUseProgram` (synchronized): ahora es opcional y,
   apagado, ni se inserta en el bytecode.
2. **Multi-draw indirecto emulado (`Draws41.submit`)**: revisaba todo el estado de las imitaciones
   (`Ranges41.beforeDraw` → 5 `beforeDraw` con ThreadLocal y HashMap) en **cada comando**. Ahora se revisa una vez
   por lote (`Accel` + regla en `Transformer`), porque el estado no cambia dentro del lote.
3. **`FrameTimes.renderEnded` → `glQueryCounter`** por cuadro, que en el driver de Apple vacía la cola. Solo lo usa
   el overlay de rendimiento de Viewpoint, que se queda sin el tiempo de GPU.
4. **`MousePick.read` → `glReadPixels` de profundidad**: síncrono en Apple aunque use PBO y fence. Ahora corre
   cada 3 cuadros.
5. **`Pages41.pack` → `glGetTexImage`**: era el 23% del tiempo nativo del render. far shell, far shell band y light
   reempaquetaban sus texturas en cada cuadro, aunque far shell band y light caben en 16 unidades sin empaquetar.
   `smartPages` cuenta las unidades de forma conservadora y solo empaqueta si hace falta.
6. Después de todo esto, el hilo de render sigue siendo el límite (espera menos del 5%), pero repartido:
   ~25% dibujos reales en el driver, far shell, `MousePick`, `glBufferSubData` y Viewpoint mismo (`Meshes`,
   `WorldRenderer`).

**Cómo medir sin engañarse (aprendido a golpes):**
- El Air M5 no tiene ventilador: después de ~1 h de benchmarks seguidos el mismo código baja de 51 a 45 fps. A
  batería también rinde menos. **Comparar siempre intercalando A, B, A, B en la misma tanda**, enchufado.
- El juego tenía un **límite de 60 fps** (`menuFramerateIndex=17` en `~/Zomboid/viewpoint-framecap.properties`,
  igual a los 60 Hz de la pantalla del Air). `bench.sh` lo quita mientras mide y lo restaura al final.
- `SCENE=vp_mac_diag_small ./bench.sh`: misma escena con un cuarto de los píxeles, para saber cuánto pesa la GPU.
  Dio ~60 contra ~46: los píxeles son más o menos un cuarto del tiempo de cada cuadro y el resto es CPU del hilo
  de render.
- El grabador de `_cine` le quita ~40%: para medir siempre `--no-rec`, que es lo que usa `bench.sh`.

**Verificación de cada cambio:**
- `tools/verify.sh`: bytecode y destinos.
- `tools/GpuPackTest.java`: equivalencia exacta entre la copia en GPU y `glGetTexImage`.
- `../_macgl/lab/lab.py sweep` sobre los volcados: todos los shaders compilan y enlazan.
- Benchmark y comparación visual.
