#!/bin/bash
# Prueba de compatibilidad de VPMac en ESTA Mac (cualquier Apple Silicon), sin tocar el teclado. ~30-40 min.
#   1. informe del sistema (chip, RAM, macOS)            4. benchmark de primera persona (FPS)
#   2. verificacion offline del bytecode (si hay javac)  5. estres de TODOS los shaders en el compilador de Metal
#   3. abre el juego (escena _cine), entra solo a 1a persona, vuelca shaders + fotos de estado, mide y cierra
# Resultado: _vpmac/compat/<chip>_<fecha>.txt (mandalo de vuelta). Requiere: juego + puente de xiveboy + VPMac
# instalados (install.sh), ZombieBuddy con Viewpoint aprobado, y _cine (CINE_WORKSPACE).
# No toca tu partida: la escena usa un mundo temporal y restaura mods/opciones al final.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
WS="${CINE_WORKSPACE:-$HOME/Zomboid/Workshop}"   # carpeta que contiene _cine/ (lo usa bench.sh)
source "$HERE/paths.sh"
CHIP="$(/usr/sbin/sysctl -n machdep.cpu.brand_string)"
STAMP="$(date +%Y-%m-%d_%H-%M)"
OUT="$HERE/compat/$(echo "$CHIP" | tr ' ' '-')_$STAMP.txt"
DUMP="$HERE/compat/dump-$STAMP"
mkdir -p "$HERE/compat"
pgrep -f "Project Zomboid.app/Contents/MacOS" >/dev/null && { echo "cierra el juego primero"; exit 1; }
[ -f "$J/vpmac-agent.jar" ] || { echo "VPMac no esta instalado (corre install.sh)"; exit 1; }
T0=$(date +%s)
log() { echo "$*" | tee -a "$OUT"; }

log "== VPMac compat-test $STAMP"
log "chip: $CHIP ($(/usr/sbin/sysctl -n hw.model)), RAM $(( $(/usr/sbin/sysctl -n hw.memsize) >> 30 )) GB, macOS $(sw_vers -productVersion)"
log "steam: $STEAMAPPS"
log "viewpoint.jar sha256: $(shasum -a 256 "$VPJAR" | cut -c1-16)  agente sha256: $(shasum -a 256 "$J/vpmac-agent.jar" | cut -c1-16)"

log ""
log "== 1. verificacion offline"
if command -v javac >/dev/null && [ -f "$HERE/build/classes/vpmac/Agent.class" ]; then
  "$HERE/tools/verify.sh" 2>&1 | grep -E "reescritas|sin destino|ERRORES" | tee -a "$OUT"
else
  log "(se salta: sin javac o sin build/; no es necesario para jugar)"
fi

log ""
log "== 2. juego: primera persona automatica + volcado de shaders (diag) + FPS"
# bench.sh arma vpmac.properties (bench=60 + lo que se le pase) y lo restaura al final
"$HERE/bench.sh" diag=true dump=true "dumpDir=$DUMP" 2>&1 | tail -1 | tee -a "$OUT"
grep -E "\[VPMac\] (compatibility|WARNING|ERROR)|replaced by|GLSL rule|pages:|=== VPMac" "$HOME/Zomboid/console.txt" \
  | sed 's/.*\[VPMac\]/  [VPMac]/' | cut -c1-400 | tee -a "$OUT"
[ -f "$HOME/Zomboid/vpmac-compat.txt" ] && { log ""; log "-- vpmac-compat.txt"; cat "$HOME/Zomboid/vpmac-compat.txt" >> "$OUT"; }

log ""
log "== 3. benchmark limpio (sin diagnostico)"
"$HERE/bench.sh" 2>&1 | tail -1 | sed 's/.*BENCH result: //' | tee -a "$OUT"

log ""
log "== 4. estres de shaders en el compilador de Metal de esta GPU"
if [ -d "$DUMP" ] && ls "$DUMP"/*.glsl >/dev/null 2>&1; then
  [ -f "$HERE/lab/build/macgl/lab/ShaderLab.class" ] || "$HERE/lab/build.sh" >/dev/null
  python3 -u "$HERE/lab/lab.py" compat "$DUMP" --n 6 --parallel 3 2>&1 | tee -a "$OUT"
else
  log "no hubo volcado de shaders (¿el juego no llego a primera persona?)"
fi

log ""
log "== 5. reportes de crash nuevos durante la prueba"
find "$HOME/Library/Logs/DiagnosticReports" -newermt "@$T0" \( -name "MTLCompilerService*" -o -name "JavaAppLauncher*" -o -name "java-*" \) \
  -exec basename {} \; | tee -a "$OUT"
log ""
log "listo en $(( ($(date +%s) - T0) / 60 )) min: $OUT"
