#!/bin/bash
# Benchmark automatico de primera persona, sin tocar el teclado:
#   bench.sh [clave=valor ...]
# Claves de VPMac (vpmac.properties: batchDraws, gpuTiming, pickEvery, bench, benchDelay...) y de Viewpoint con
# prefijo "vp." (p. ej. vp.pack.default.motionBlur=false): esas se cambian SOLO durante la corrida en
# ~/Zomboid/viewpoint-live.properties y se restauran al final. Abre la escena _cine vp_mac_diag, entra solo a
# primera persona, mide y cierra el juego. Resultado: ultima linea "[VPMac] BENCH result" y _vpmac/bench/*.txt
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
# _cine (herramienta aparte de escenas de prueba: mundo temporal + entrar al juego sin tocar el teclado)
WS="${CINE_WORKSPACE:-$HOME/Zomboid/Workshop}"   # carpeta que contiene _cine/
[ -f "$WS/_cine/cine.py" ] || { echo "falta _cine en $WS (define CINE_WORKSPACE)"; exit 1; }
PROPS="$HOME/Zomboid/vpmac.properties"
VPL="$HOME/Zomboid/viewpoint-live.properties"
pgrep -f "Project Zomboid.app/Contents/MacOS" >/dev/null && { echo "cierra el juego primero"; exit 1; }
cp "$PROPS" "$PROPS.bak" 2>/dev/null || true
cp "$VPL" "$VPL.benchbak"
# sin limite de FPS durante la medicion (el juego suele estar en 60 = la pantalla del Air): se restaura al final
CAP="$HOME/Zomboid/viewpoint-framecap.properties"
[ -f "$CAP" ] && cp "$CAP" "$CAP.benchbak" && sed -i '' 's/^menuFramerateIndex=.*/menuFramerateIndex=2/' "$CAP"
restore() {
  [ -f "$CAP.benchbak" ] && mv "$CAP.benchbak" "$CAP"
  if [ -f "$PROPS.bak" ]; then mv "$PROPS.bak" "$PROPS"; else rm -f "$PROPS"; fi
  mv "$VPL.benchbak" "$VPL"
  (cd "$WS" && python3 _cine/cine.py restore >/dev/null 2>&1)
}
trap restore EXIT
{ echo "bench=60"; echo "benchDelay=15"; } > "$PROPS"
for kv in "$@"; do
  k="${kv%%=*}"; v="${kv#*=}"
  if [[ "$k" == vp.* ]]; then
    k="${k#vp.}"
    if grep -q "^$k=" "$VPL"; then sed -i '' "s|^$k=.*|$k=$v|" "$VPL"; else echo "$k=$v" >> "$VPL"; fi
  else
    grep -v "^$k=" "$PROPS" > "$PROPS.tmp"; echo "$k=$v" >> "$PROPS.tmp"; mv "$PROPS.tmp" "$PROPS"
  fi
done
echo "corrida: $*"
# REC=nombre graba video (para comparar imagen; el grabador usa GPU, asi que esos FPS no valen)
if [ -n "${REC:-}" ]; then
  (cd "$WS" && python3 _cine/cine.py run "${SCENE:-vp_mac_diag}" > "$HERE/bench/last-cine.log" 2>&1)
  cp "$WS/_cine/out/${SCENE:-vp_mac_diag}/take.mov" "$HERE/bench/$REC.mov" && echo "video: $HERE/bench/$REC.mov"
else
  (cd "$WS" && python3 _cine/cine.py run "${SCENE:-vp_mac_diag}" --no-rec > "$HERE/bench/last-cine.log" 2>&1)
fi
grep "\[VPMac\] BENCH" "$HOME/Zomboid/console.txt" | tail -2 | sed 's/.*\[VPMac\] //'
