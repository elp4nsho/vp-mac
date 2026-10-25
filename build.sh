#!/bin/bash
# Compila build/vpmac-agent.jar: el agente + las imitaciones viewpoint/mac41 sacadas del parche de xiveboy que ya
# esta en tu Workshop (no se descarga ni se redistribuye nada). Uso personal.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
source "$HERE/paths.sh"   # STEAMAPPS, APP, J, VPJAR, PAYLOAD (detecta la biblioteca de Steam)
B="$HERE/build"

rm -rf "$B"; mkdir -p "$B/classes" "$B/shims"
# 1. imitaciones de xiveboy (solo el paquete viewpoint/mac41; nada de Viewpoint en si)
(cd "$B/shims" && unzip -q "$PAYLOAD/Viewpoint.jar" 'viewpoint/mac41/*')
cp -R "$B/shims/viewpoint" "$B/classes/"
# 2. agente (ASM viene del puente pz-mac41-bridge.jar, que se carga antes en el juego)
# stubs/: clases del juego que javac 21 no puede leer (son Java 25); se compilan aparte y NO van al jar
mkdir -p "$B/stubs"
javac --release 17 -nowarn -d "$B/stubs" $(find "$HERE/stubs" -name '*.java')
javac --release 17 -nowarn -d "$B/classes" \
  -cp "$B/stubs:$J/projectzomboid.jar:$J/pz-mac41-bridge.jar:$B/shims:$VPJAR" \
  "$HERE"/src/vpmac/*.java "$HERE"/src/vpmac/zb/*.java
cp "$HERE/src/vpmac/redirects.txt" "$B/classes/vpmac/"
cp "$HERE"/glsl/*.glsl "$B/classes/vpmac/"   # reemplazos GLSL que usa Glsl.java (recursos del jar)
# 3. jar con Premain-Class
printf 'Manifest-Version: 1.0\nPremain-Class: vpmac.Agent\nCan-Redefine-Classes: false\nCan-Retransform-Classes: false\n' > "$B/MANIFEST.MF"
(cd "$B/classes" && jar cfm "$B/vpmac-agent.jar" "$B/MANIFEST.MF" .)
echo "listo: $B/vpmac-agent.jar ($(unzip -l "$B/vpmac-agent.jar" | tail -1 | awk '{print $2}') archivos)"
