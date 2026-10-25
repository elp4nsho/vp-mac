#!/bin/bash
# Arma el item de Workshop de VPMac (Opcion A: mod de ZombieBuddy con preload) en ~/Zomboid/Workshop/VPMac/,
# listo para subir con la herramienta de Workshop del juego, y firma el jar para ZombieBuddy.
#   ./package-zb.sh            (despues de ./build.sh)
# VPMac.jar lleva SOLO codigo de VPMac: el puente y las imitaciones de xiveboy se usan desde la copia local del
# jugador de su item (ver src/vpmac/zb/PreMain.java y CREDITS.txt).
# Firma: clave Ed25519 en ~/.signing/vpmac-ed25519.der (se crea la primera vez; NUNCA se sube ni se comparte).
# La clave publica va una vez en tu perfil de Steam (resumen) como JavaModZBS:<64 hex>.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
source "$HERE/paths.sh"
OPENSSL=/opt/homebrew/bin/openssl
STEAMID="${ZBS_STEAMID:-76561198028959829}"   # Wenopal (sube el item: tiene que ser la misma cuenta)
KEY="$HOME/.signing/vpmac-ed25519.der"
ITEM="$HOME/Zomboid/Workshop/VPMac"
MOD="$ITEM/Contents/mods/VPMac/42"
VERSION=$(grep -o 'VERSION = "[^"]*"' "$HERE/src/vpmac/Agent.java" | cut -d'"' -f2)
[ -d "$HERE/build/classes/vpmac" ] || { echo "falta build: corre ./build.sh"; exit 1; }
[ -x "$OPENSSL" ] || { echo "falta OpenSSL 3 de Homebrew (brew install openssl)"; exit 1; }

# 1. VPMac.jar: vpmac/** (incluye vpmac/zb y redirects.txt); nada de viewpoint/** ni del puente
T="$(mktemp -d)"
mkdir -p "$T/classes"
cp -R "$HERE/build/classes/vpmac" "$T/classes/"
printf 'Manifest-Version: 1.0\nZB-Preload: true\nImplementation-Title: VPMac\nImplementation-Version: %s\n' "$VERSION" > "$T/MANIFEST.MF"
mkdir -p "$MOD/media/java/client"
JAR="$MOD/media/java/client/VPMac.jar"
(cd "$T/classes" && jar cfm "$JAR" "$T/MANIFEST.MF" vpmac)
rm -rf "$T"

# 2. firma ZBS: Ed25519 sobre "ZBS:<SteamID64>:<sha256 del jar en minusculas>"
if [ ! -f "$KEY" ]; then
  mkdir -p "$HOME/.signing" && chmod 700 "$HOME/.signing"
  "$OPENSSL" genpkey -algorithm ed25519 -outform DER -out "$KEY"
  chmod 600 "$KEY"
  echo "clave nueva: $KEY (respaldala en un lugar seguro; si la pierdes, hay que publicar otra clave publica)"
fi
PUB=$("$OPENSSL" pkey -in "$KEY" -inform DER -pubout -outform DER | tail -c 32 | xxd -p -c 64)
SHA=$(shasum -a 256 "$JAR" | cut -d' ' -f1)
printf 'ZBS:%s:%s' "$STEAMID" "$SHA" > "$HERE/build/zbs-msg.bin"
"$OPENSSL" pkeyutl -sign -inkey "$KEY" -keyform DER -rawin -in "$HERE/build/zbs-msg.bin" -out "$HERE/build/zbs-sig.bin"
SIG=$(xxd -p -c 128 "$HERE/build/zbs-sig.bin")
[ ${#SIG} -eq 128 ] || { echo "firma con largo inesperado (${#SIG})"; exit 1; }
printf 'ZBS\nSteamID64:%s\nSignature:%s\n' "$STEAMID" "$SIG" > "$JAR.zbs"
# auto-verificacion con la clave publica
"$OPENSSL" pkey -in "$KEY" -inform DER -pubout -out "$HERE/build/zbs-pub.pem"
"$OPENSSL" pkeyutl -verify -pubin -inkey "$HERE/build/zbs-pub.pem" -rawin -in "$HERE/build/zbs-msg.bin" \
  -sigfile "$HERE/build/zbs-sig.bin" >/dev/null && echo "firma verificada"

# 3. mod.info, creditos, documentacion
cat > "$MOD/mod.info" <<EOF
name=VPMac - Project Viewpoint on Apple Silicon Macs
id=VPMac
description=Project Viewpoint on Apple Silicon Macs. Needs ZombieBuddy and Viewpoint, plus xiveboy's Patch for Mac (do NOT run its installer). Approve VPMac in ZombieBuddy and restart once. Built 100% with AI.
modversion=$VERSION
versionMin=42.21.0
require=\\ZombieBuddy,\\Viewpoint,\\ViewpointMac41Patch
javaJarFile=media/java/client/VPMac.jar
javaPkgName=vpmac.zb
javaPreload=true
ZBVersionMin=2.3.4
poster=poster.png
EOF
cp "$HERE/PLAYERS.md" "$MOD/README.txt"
cat > "$MOD/CREDITS.txt" <<'EOF'
VPMac — Project Viewpoint on Apple Silicon Macs (unofficial compatibility layer)

VPMac.jar contains only VPMac's own code. It rewrites Viewpoint in memory while the game runs; it does not ship
or modify any Viewpoint file.

Project Viewpoint — by ellu and norkus, and contributors.
  https://steamcommunity.com/sharedfiles/filedetails/?id=3809306528
  Viewpoint's license requires prior written authorization for modification and redistribution.
  VPMac is not endorsed or authorized by the Viewpoint authors. It is an unofficial community compatibility
  layer while official Mac support does not exist. If the Viewpoint authors ask, VPMac will be changed or removed.

Mac OpenGL 4.1 bridge and viewpoint/mac41 compatibility shims — by xiveboy.
  "Project Viewpoint - Patch for Mac": https://steamcommunity.com/sharedfiles/filedetails/?id=3812168749
  Not included in VPMac.jar: VPMac loads them from your own local copy of xiveboy's Workshop item.
  The bridge's CoreGl/CoreGlsl derive from Project Zomboid Optimization by xD3I
  (https://github.com/xD3I/PZ_Optimization), which has no redistribution license.
  VPMac's FastUnits41 is a rewrite of xiveboy's Units41 (xiveboy allowed modifying and publishing his patch).

Poster: a variant of xiveboy's patch icon (recolored, "VPMac" label), used with his permission to modify and publish.

ZombieBuddy — by Andrey "Zed" Zaikin and contributors (MIT). https://github.com/zed-0xff/ZombieBuddy
  VPMac reads ZombieBuddy's Instrumentation to start early (preload).

ASM (OW2) is used at runtime from the bridge; it is not bundled in VPMac.jar.
Project Zomboid — The Indie Stone.
EOF

# 4. item del Workshop: workshop.txt (se conserva el id si ya se subio), imagenes
# imagen: variante del icono de xiveboy (verde + "VPMac"; el autorizo modificar y publicar su parche), art/
cp "$HERE/art/vpmac-poster.png" "$ITEM/preview.png"
cp "$HERE/art/vpmac-poster.png" "$MOD/poster.png"
"$HERE/desc.sh"   # workshop.txt (descripcion + Workshop ID / Mod ID al final)

echo
echo "item listo: $ITEM"
echo "VPMac $VERSION | jar sha256 $SHA"
echo
echo "UNA VEZ: agrega esta linea al resumen de tu perfil de Steam (la lee ZombieBuddy para verificar la firma):"
echo "  JavaModZBS:$PUB"
