# VPMac — Project Viewpoint on Apple Silicon Macs

VPMac makes Project Viewpoint's first-person 3D view work on Apple Silicon Macs (OpenGL 4.1 over Metal). It patches
Viewpoint in memory when the game starts; the Viewpoint files from the Workshop are not changed.

**Requirements:** ZombieBuddy, Project Viewpoint, and xiveboy's "Project Viewpoint - Patch for Mac". **Do NOT run the
"Patch for Mac" installer**: just subscribe (VPMac only reads its files). Enable VPMac (its requirements are enabled
with it), start the game, approve VPMac in ZombieBuddy's window, and restart once.

VPMac was built 100% with AI (Claude), and support works the same way. It is an alternative to xiveboy's patch, not
a competitor.

**Early stage:** it has been tested on an M5 MacBook Air (macOS 26.5.1). Other Macs should work, but your reports
are how we find out. Thank you!

## If the game crashes or freezes

The next time you start the game, a window will say **"VPMac noticed that your last session ended badly"**.

1. Click **Copy short report** (it fits a Steam comment) or **Copy full report** (for a discussion post).
2. Paste it in **VPMac Workshop page > Discussions > Bug reports**.
3. Add a sentence or two: what were you doing (first person? driving? opening a menu?), and can you make it happen
   again?

The report is also saved in `~/Zomboid/vpmac/reports/`. If the window does not appear, open that folder and copy
the newest file.

## If something looks wrong but did not crash

Double-click **`VPMac - Share report.command`**. It copies a report of your last session to the clipboard. Paste it
in the same discussion and describe what you saw. A screenshot helps a lot.

## If we ask you for "debug mode"

Create (or edit) the text file `~/Zomboid/vpmac.properties` and put this line in it:

```
debug=true
```

Play until the problem happens, close the game, and share a report as above. Then delete the line (debug mode is a
little slower).

## Privacy

Nothing is sent anywhere automatically. Reports stay on your Mac until you paste them. Before saving, VPMac replaces
your user name in file paths with `~` and hides Steam IDs. Reports contain: versions (VPMac, Viewpoint, the game,
ZombieBuddy), your Mac model, chip, RAM and macOS version, VPMac's settings and messages, and the last lines of the
game's log.

## Files

| File | What |
|---|---|
| `~/Zomboid/vpmac/reports/` | crash and manual reports |
| `~/Zomboid/vpmac/vpmac.log` | VPMac's log for this session (`vpmac.prev.log`: the one before) |
| `~/Zomboid/vpmac-compat.txt` | what VPMac detected about your Mac |
| `~/Zomboid/vpmac.properties` | optional settings (`debug=true`, `reportPopup=false` to turn off the crash window) |
