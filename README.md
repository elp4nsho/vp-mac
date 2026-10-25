<p align="center"><img src="docs/img/poster.png" width="180" alt="VPMac"></p>

# VPMac — Project Viewpoint on Apple Silicon Macs

VPMac makes [Project Viewpoint](https://steamcommunity.com/sharedfiles/filedetails/?id=3809306528) (first-person 3D
for Project Zomboid B42) run on Apple Silicon Macs. macOS only has OpenGL 4.1, and Viewpoint needs OpenGL 4.2–4.5;
VPMac rewrites Viewpoint **in memory** while the game runs, so the Workshop files are never changed.

- **Steam Workshop:** [VPMac](https://steamcommunity.com/sharedfiles/filedetails/?id=3827015893)
- **Bug reports:** [Discussions > Bug reports](https://steamcommunity.com/workshop/filedetails/discussion/3827015893/574930637987900322/)
- **Español:** [README.es.md](README.es.md)

> **Built 100% with AI.** VPMac — the code, the tools and these docs — was built with Claude (Anthropic), directed
> and tested by [elp4nsho](https://github.com/elp4nsho) (Wenopal). Support works the same way. This repository exists
> so anyone can see exactly what the mod does.

## For players

Requirements (subscribe to all three):
[ZombieBuddy](https://steamcommunity.com/sharedfiles/filedetails/?id=3619862853) (installed per its guide),
[Project Viewpoint](https://steamcommunity.com/sharedfiles/filedetails/?id=3809306528) and
[Project Viewpoint - Patch for Mac](https://steamcommunity.com/sharedfiles/filedetails/?id=3812168749) by xiveboy.
**Do not run the "Patch for Mac" installer** — VPMac only reads its files.

1. Subscribe to VPMac and enable it (its requirements are enabled with it).
2. Start the game and approve VPMac in ZombieBuddy's window.
3. Restart the game once.

If the game crashes or freezes, the next start shows a window with a ready-made report: copy it and paste it in the
[bug thread](https://steamcommunity.com/workshop/filedetails/discussion/3827015893/574930637987900322/). Details:
[PLAYERS.md](PLAYERS.md).

## What it does

| Problem on Mac | What VPMac does |
|---|---|
| Viewpoint calls OpenGL 4.2+ functions → crash when loading a world | Redirects them to OpenGL 4.1 emulations (xiveboy's shims + its own) as each Viewpoint class loads |
| New in Viewpoint 0.1.6.1: texture views for sun shadow cascades | `Views41` emulates `glTextureView` with real textures + GPU blits |
| Apple's Metal shader compiler crashes or never finishes on some shaders (first person, Modern mode) | GLSL rules rewrite the guilty code with the same result ([story](docs/development-story.md)) |
| Low FPS from emulation overhead | Accelerators: batched draws, no sync GPU readbacks, smarter texture packing, faster texture units… **+75% FPS** ([numbers](docs/performance.md)) |
| Hard to support players on many Macs | Crash/freeze reports ready to copy, compatibility report, debug switches ([maintaining](docs/maintaining.md)) |

How it fits together: [docs/how-it-works.md](docs/how-it-works.md).

## Repository

| Path | What |
|---|---|
| `src/vpmac/` | the agent: rewriting rules (`Transformer`), GLSL rules (`Glsl`), accelerators, reports, compatibility |
| `src/vpmac/zb/` | ZombieBuddy *preload* entry point (how the Workshop version starts before the game) |
| `glsl/` | GLSL replacements used by the rules |
| `lab/` | **shader lab**: compiles game shaders against the Mac's OpenGL 4.1/Metal outside the game ([guide](docs/lab.md)) |
| `tools/` | offline checks (bytecode harness, equivalence tests), profiler report, screenshot helper |
| `*.sh` | build, install for development, benchmark, profile, compatibility test, Workshop packaging + signing |
| `docs/` | how it works, development story, lab guide, performance, maintaining; Spanish dev notes |

Build (macOS, JDK 17+ and the game installed): `./build.sh && tools/verify.sh`. Package for the Workshop:
`./package-zb.sh` (signs the jar for ZombieBuddy). See [docs/maintaining.md](docs/maintaining.md).

## What is not in this repository

- **Viewpoint's code or shaders** — its license forbids redistribution. VPMac only patches it in memory; shader
  dumps stay on the developer's machine (`dump/` is git-ignored).
- **xiveboy's bridge and shims** — used from the player's own copy of his Workshop item. The bridge partly derives
  from [xD3I's PZ_Optimization](https://github.com/xD3I/PZ_Optimization), which has no redistribution license.

## Credits and status

- **Project Viewpoint** — ellu and norkus. VPMac is unofficial and **not endorsed** by them; if they ask, it will be
  changed or removed.
- **Mac bridge and shims** — xiveboy, who allowed anyone to modify and publish his patch. VPMac is an **alternative,
  not a competitor**: it builds on his work, and we all just want to play.
- **ZombieBuddy** — Andrey "Zed" Zaikin (MIT).
- Tested on a MacBook Air M5 (macOS 26.5.1). Other Apple Silicon Macs should work; run `./compat-test.sh` and share
  the result.

License: VPMac's own code is MIT ([LICENSE](LICENSE)). Third-party projects keep their own terms.
