# Rutas compartidas por los scripts de VPMac (se carga con: source paths.sh).
# Busca la biblioteca de Steam que tiene Project Zomboid (app 108600) en libraryfolders.vdf, porque puede estar
# en otro disco. Se puede forzar con STEAMAPPS=/ruta/a/steamapps.
if [ -z "${STEAMAPPS:-}" ]; then
  _default="$HOME/Library/Application Support/Steam/steamapps"
  STEAMAPPS="$(python3 - "$_default" <<'PY'
import os, re, sys
default = sys.argv[1]
vdf = os.path.join(default, 'libraryfolders.vdf')
paths = [os.path.dirname(default)]
try:
    paths += re.findall(r'"path"\s+"([^"]+)"', open(vdf).read())
except OSError:
    pass
for p in paths:
    apps = os.path.join(p, 'steamapps')
    if os.path.exists(os.path.join(apps, 'appmanifest_108600.acf')) or os.path.isdir(os.path.join(apps, 'common', 'ProjectZomboid')):
        print(apps); break
else:
    print(default)
PY
)"
fi
APP="$STEAMAPPS/common/ProjectZomboid/Project Zomboid.app/Contents"
J="$APP/Java"
GAME_JAVA="$APP/PlugIns/jre-aarch64/Contents/Home/bin/java"
WORKSHOP="$STEAMAPPS/workshop/content/108600"
VPJAR="$WORKSHOP/3809306528/mods/Viewpoint/42/media/java/client/Viewpoint.jar"
PAYLOAD="$WORKSHOP/3812168749/mods/ViewpointMac41Patch/common/tools/payload"
export STEAMAPPS
