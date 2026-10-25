#!/bin/bash
# Verificacion offline (sin abrir el juego): reescribe TODAS las clases del Viewpoint.jar del Workshop con el
# agente recien compilado, revisa que cada llamada nueva apunte a un metodo static existente y carga cada clase
# reescrita con el Java 25 del juego (verificador de bytecode). Correr despues de build.sh.
set -euo pipefail
V="$(cd "$(dirname "$0")/.." && pwd)"
source "$V/paths.sh"
JAVA="$GAME_JAVA"
VP="$VPJAR"
CP="$V/build/classes:$APP/Java/pz-mac41-bridge.jar:$APP/Java/projectzomboid.jar"
T="$(mktemp -d)"
javac --release 17 -nowarn -d "$T/classes" -cp "$CP" "$V/tools/Harness.java"
"$JAVA" -cp "$T/classes:$CP:$VP" vpmac.Harness "$VP" "$T/out" "$V/build/classes" "$APP/Java/pz-mac41-bridge.jar" "$APP/Java/projectzomboid.jar" \
  | grep -v "^\[VPMac\]"
# 2) las imitaciones de xiveboy que VPMac tambien transforma (lotes de dibujo, avisos de diagnostico)
echo "--- imitaciones viewpoint/mac41"
(cd "$V/build/classes" && jar cf "$T/m41.jar" viewpoint)
"$JAVA" -cp "$T/classes:$CP:$VP" vpmac.Harness "$T/m41.jar" "$T/out2" "$V/build/classes" "$APP/Java/pz-mac41-bridge.jar" "$APP/Java/projectzomboid.jar" "$VP" \
  | grep -v "^\[VPMac\]"
rm -rf "$T"
