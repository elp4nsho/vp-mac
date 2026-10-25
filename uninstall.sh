#!/bin/bash
# Quita VPMac: saca -javaagent:vpmac-agent.jar del Info.plist y borra el jar. El puente de xiveboy queda como estaba.
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd)/paths.sh"
PL="$APP/Info.plist"
PB=/usr/libexec/PlistBuddy
if pgrep -f "Project Zomboid.app/Contents/MacOS" >/dev/null; then echo "cierra el juego primero"; exit 1; fi
n=$($PB -c "Print :JVMOptions" "$PL" | grep -c "^    ")
for ((i = n - 1; i >= 0; i--)); do
  [ "$($PB -c "Print :JVMOptions:$i" "$PL")" = "-javaagent:vpmac-agent.jar" ] && $PB -c "Delete :JVMOptions:$i" "$PL"
done
rm -f "$APP/Java/vpmac-agent.jar"
$PB -c "Print :JVMOptions" "$PL"
echo "VPMac quitado"
