#!/bin/bash
# Probar la Opcion A como un jugador nuevo: deja en el Info.plist solo ZombieBuddy (saca el puente y VPMac como
# -javaagent); VPMac entra por el mod de ZombieBuddy (preload). Juego cerrado.
#   zb-test-mode.sh on    respalda JVMOptions y saca los -javaagent del puente y de VPMac
#   zb-test-mode.sh off   vuelve a dejarlos como estaban
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
source "$HERE/paths.sh"
PL="$APP/Info.plist"; PB=/usr/libexec/PlistBuddy; BAK="$HERE/backup/Info.plist.before-zb-test"
PAT="Project Zomboid.app/Contents/Mac""OS"
pgrep -f "$PAT" >/dev/null && { echo "cierra el juego primero"; exit 1; }
case "${1:-}" in
  on)
    mkdir -p "$HERE/backup"; cp "$PL" "$BAK"
    n=$($PB -c "Print :JVMOptions" "$PL" | grep -c "^    ")
    for ((i = n - 1; i >= 0; i--)); do
      v="$($PB -c "Print :JVMOptions:$i" "$PL")"
      if [ "$v" = "-javaagent:pz-mac41-bridge.jar" ] || [ "$v" = "-javaagent:vpmac-agent.jar" ]; then $PB -c "Delete :JVMOptions:$i" "$PL"; fi
    done
    echo "modo prueba ZombieBuddy: solo ZombieBuddy como -javaagent"; $PB -c "Print :JVMOptions" "$PL" | grep javaagent ;;
  off)
    [ -f "$BAK" ] || { echo "no hay respaldo $BAK"; exit 1; }
    cp "$BAK" "$PL"; echo "Info.plist restaurado"; $PB -c "Print :JVMOptions" "$PL" | grep javaagent ;;
  *) echo "uso: zb-test-mode.sh on|off"; exit 2 ;;
esac
