#!/bin/bash
# Instala VPMac en el juego: copia build/vpmac-agent.jar junto al puente y agrega -javaagent:vpmac-agent.jar al
# Info.plist, justo despues de pz-mac41-bridge.jar (el puente tiene que cargar primero). Respalda el Info.plist.
# Deshacer: uninstall.sh. Steam "Verificar archivos" tambien lo deja como venia (y quita el puente de xiveboy).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
source "$HERE/paths.sh"
PL="$APP/Info.plist"
PB=/usr/libexec/PlistBuddy

[ -f "$HERE/build/vpmac-agent.jar" ] || { echo "falta build/vpmac-agent.jar: corre build.sh"; exit 1; }
[ -f "$APP/Java/pz-mac41-bridge.jar" ] || { echo "falta el puente pz-mac41-bridge.jar (Mac Patch Installer en modo Steam)"; exit 1; }
if pgrep -f "Project Zomboid.app/Contents/MacOS" >/dev/null; then echo "cierra el juego primero"; exit 1; fi

mkdir -p "$HERE/backup"
[ -f "$HERE/backup/Info.plist.original" ] || cp "$PL" "$HERE/backup/Info.plist.original"
cp "$HERE/build/vpmac-agent.jar" "$APP/Java/vpmac-agent.jar"

opts=$($PB -c "Print :JVMOptions" "$PL")
if grep -q "vpmac-agent.jar" <<<"$opts"; then
  echo "el Info.plist ya tenia el agente; solo se actualizo el jar"
else
  n=$($PB -c "Print :JVMOptions" "$PL" | grep -c "^    ")
  at=-1
  for ((i = 0; i < n; i++)); do
    [ "$($PB -c "Print :JVMOptions:$i" "$PL")" = "-javaagent:pz-mac41-bridge.jar" ] && at=$i
  done
  [ "$at" -ge 0 ] || { echo "no encontre -javaagent:pz-mac41-bridge.jar en JVMOptions"; exit 1; }
  $PB -c "Add :JVMOptions:$((at + 1)) string -javaagent:vpmac-agent.jar" "$PL"
  echo "agregado -javaagent:vpmac-agent.jar despues del puente"
fi
$PB -c "Print :JVMOptions" "$PL"
