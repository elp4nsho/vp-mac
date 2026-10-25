#!/bin/bash
# Double-click in Finder: builds a VPMac report of your LAST game session and copies it to the clipboard,
# so you can paste it in the VPMac Workshop page > Discussions > Bug reports. Nothing is sent anywhere.
# Use it for bugs that are not crashes (crashes get a report window automatically on the next start).
Z="$HOME/Zomboid"
OUT="$Z/vpmac/reports/manual-$(date +%Y-%m-%d_%H-%M).txt"
mkdir -p "$Z/vpmac/reports"
clean() { sed -e "s#$HOME#~#g" -e 's#/Users/[^/ ]*#~#g' -e 's/7656119[0-9]\{10\}/7656119**********/g'; }
{
  echo "=== VPMac manual report ($(date '+%Y-%m-%d %H:%M')) ==="
  echo "Mac: $(sysctl -n machdep.cpu.brand_string) ($(sysctl -n hw.model)), $(( $(sysctl -n hw.memsize) >> 30 )) GB, macOS $(sw_vers -productVersion)"
  grep -h "=== VPMac\|config:" "$Z/vpmac/vpmac.log" 2>/dev/null | sed 's/^[0-9:]* //'
  grep -h "getVersion() = \|game build:" "$Z/console.txt" 2>/dev/null | sed 's/.*> //' | head -2
  echo; echo "--- what happened (please describe it in your post, in your own words)"
  echo; echo "--- VPMac warnings"
  grep -h "AVISO\|WARNING" "$Z/vpmac/vpmac.log" 2>/dev/null | tail -8 | sed 's/^[0-9:]* //'
  echo; echo "--- compatibility"
  cat "$Z/vpmac-compat.txt" 2>/dev/null
  echo; echo "--- last console lines"
  grep -v "has no uniform uTextures\[\|missing tile\|texturepack: loading\|Cargando pack" "$Z/console.txt" 2>/dev/null | tail -30 | cut -c1-300
} | clean > "$OUT"
pbcopy < "$OUT"
osascript -e 'display dialog "VPMac report copied to the clipboard.\n\nPaste it in: VPMac Workshop page > Discussions > Bug reports, and describe what you saw.\n\nSaved in ~/Zomboid/vpmac/reports/" with title "VPMac" buttons {"OK"} default button "OK"' >/dev/null
