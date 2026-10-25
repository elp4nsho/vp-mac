#!/bin/bash
# Compila el laboratorio de shaders (usa la LWJGL y el GLFW que trae el juego).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
J="$HOME/Library/Application Support/Steam/steamapps/common/ProjectZomboid/Project Zomboid.app/Contents/Java"
rm -rf "$HERE/build"; mkdir -p "$HERE/build"
javac --release 17 -nowarn -d "$HERE/build" -cp "$J/projectzomboid.jar" "$HERE"/src/macgl/lab/*.java
echo "listo: $HERE/build"
