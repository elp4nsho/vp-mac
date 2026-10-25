#!/bin/bash
# Perfilado con Java Flight Recorder (JFR) del juego.
#   profile.sh on      agrega -XX:StartFlightRecording al Info.plist (graba desde que abre el juego)
#   profile.sh off     lo saca
#   profile.sh report [archivo.jfr]   metodos mas calientes (por hilo) del ultimo registro
# Los registros quedan en _vpmac/prof/. El juego tiene que estar cerrado para on/off.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
source "$HERE/paths.sh"
PL="$APP/Info.plist"; PB=/usr/libexec/PlistBuddy
JFR="$APP/PlugIns/jre-aarch64/Contents/Home/bin/jfr"
mkdir -p "$HERE/prof"
opt_index() {
  local n i; n=$($PB -c "Print :JVMOptions" "$PL" | grep -c "^    ")
  for ((i = 0; i < n; i++)); do
    [[ "$($PB -c "Print :JVMOptions:$i" "$PL")" == -XX:StartFlightRecording* ]] && { echo $i; return; }
  done
  echo -1
}
case "${1:-}" in
  on)
    pgrep -f "Project Zomboid.app/Contents/MacOS" >/dev/null && { echo "cierra el juego primero"; exit 1; }
    [ "$(opt_index)" -ge 0 ] && { echo "ya estaba activo"; exit 0; }
    n=$($PB -c "Print :JVMOptions" "$PL" | grep -c "^    ")
    $PB -c "Add :JVMOptions:$n string -XX:StartFlightRecording=settings=profile,filename=$HERE/prof/,dumponexit=true" "$PL"
    echo "JFR activo: el proximo arranque graba en $HERE/prof/" ;;
  off)
    pgrep -f "Project Zomboid.app/Contents/MacOS" >/dev/null && { echo "cierra el juego primero"; exit 1; }
    i=$(opt_index); [ "$i" -ge 0 ] && $PB -c "Delete :JVMOptions:$i" "$PL"; echo "JFR apagado" ;;
  report)
    f="${2:-$(/bin/ls -t "$HERE"/prof/*.jfr 2>/dev/null | head -1)}"
    [ -f "$f" ] || { echo "no hay registros .jfr"; exit 1; }
    echo "registro: $f"
    "$JFR" print --json --events jdk.ExecutionSample "$f" | python3 "$HERE/tools/hot.py" ;;
  *) echo "uso: profile.sh on|off|report [archivo.jfr]"; exit 2 ;;
esac
