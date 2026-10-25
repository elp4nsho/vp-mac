#!/bin/bash
# Regenera ~/Zomboid/Workshop/VPMac/workshop.txt desde workshop-desc.bbcode (sin tocar el jar ni la firma).
# Agrega al final el bloque de servidor como en los demas mods: Workshop ID (el id que el juego guarda en
# workshop.txt tras la primera subida) y Mod ID. Tras la PRIMERA subida: corre ./desc.sh y vuelve a subir.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
WT="$HOME/Zomboid/Workshop/VPMac/workshop.txt"
ID=$(grep -s '^id=' "$WT" | head -1 | cut -d= -f2 || true)
{
  echo "version=1"
  [ -n "$ID" ] && echo "id=$ID"
  echo "title=VPMac - Project Viewpoint on Apple Silicon Macs [B42]"
  BUG_URL=$(cat "$HERE/bug-thread-url.txt" 2>/dev/null | tr -d '[:space:]' || true)
  {
    if [ -n "$BUG_URL" ]; then
      # "Bug reports" pasa a ser enlace directo al hilo (en los 3 idiomas)
      sed -E "s#\[b\](Discussions|Discusiones|Discussões) > Bug reports\[/b\]#[url=$BUG_URL][b]\1 > Bug reports[/b][/url]#g" "$HERE/workshop-desc.bbcode"
    else
      cat "$HERE/workshop-desc.bbcode"
    fi
    echo
    echo "[hr][/hr]"
    echo
    echo "[h2]🖥️ Server · Servidor[/h2]"
    [ -n "$ID" ] && echo "Workshop ID: $ID"
    echo "Mod ID: VPMac"
    echo "Multiplayer: not tested yet · Multijugador: aún sin probar · Multijogador: ainda não testado"
  } | sed 's/^/description=/'
  echo "tags=Build 42;Misc"
  echo "visibility=private"
} > "$WT"
python3 -c "import sys;s=open(sys.argv[1],encoding='utf-8').read();d=sum(len(l)-12+1 for l in s.splitlines() if l.startswith('description='));print('workshop.txt listo (%d caracteres de descripcion, limite 8000)%s' % (d, '' if '$ID' else '; falta el Workshop ID: se completa tras la primera subida'))" "$WT"
