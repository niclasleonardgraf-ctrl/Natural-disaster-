# Session guide: adding a disaster

Read this before starting a disaster session. Read only your disaster's section of `docs/SPEC.md` (§1, §2, §4 and §5 apply to every disaster).
The Tornado (`disaster/tornado/`, `client/tornado/`) is the reference implementation, so copy its patterns.

## Working rules
- Work as a single agent, sequentially. No workflows or subagents. Plan in ≤15 lines, then code.
- Build in small steps: compile after each step (`./gradlew compileJava -q`), fix the errors, move on.
- 26.x APIs differ from 1.21.x. Check the decompiled sources when unsure:
  `build/moddev/artifacts/minecraft-patched-<ver>-sources.jar` (Minecraft) and the
  `neoforge-<ver>-sources.jar` in `~/.gradle/caches/modules-2/files-2.1/net.neoforged/neoforge/`.
- Do **not** accept the Minecraft EULA (`run/eula.txt`) for the user. Test server logic with GameTests instead.
- If something can't be done cleanly, pick the simplest working alternative and note it in the README.

## Environment notes
- The Java 25 toolchain downloads automatically on the first build.
- If Maven Central answers **429 Too Many Requests**, create `~/.gradle/init.d/mirror.gradle` (outside the repo):
  ```groovy
  def mirror = 'https://maven-central.storage-download.googleapis.com/maven2/'
  beforeSettings { s -> s.pluginManagement.repositories { maven { url mirror } } }
  allprojects { buildscript.repositories { maven { url mirror } }; repositories { maven { url mirror } } }
  ```
- Headless visual check: copy a world to `run/saves/VisualTest` (e.g. `run/gametestserver/gametestworld` after one
  `./gradlew runGameTestServer`). Set `realisticdisasters.autostart` in the `clientVisualTest` run in `build.gradle`
  to `<type>,<intensity>,<PHASE>`, then run
  `xvfb-run -n 99 -s "-screen 0 1280x720x24" ./gradlew runClientVisualTest` in the background. Screenshot with
  `XAUTHORITY=$(ls -t /tmp/xvfb-run.*/Xauthority | head -1) DISPLAY=:99 import -window root -crop 854x480+213+120 shot.png`.
  Kill it with `pkill -f devlaunch; pkill -f "Xvfb :99"` and wait for both to exit before touching the save again.
  `-Drealisticdisasters.debug=true` logs the client's view of each disaster every 2 s.
- There is no audio device in cloud containers, so sound can't be heard-tested.

## Checklist: adding disaster `X`
1. `disaster/DisasterType`: add an enum entry (factory, enabled flag, weight, biomes).
2. `config/DisasterConfig`: add a `B.push("x")` section with at least `enabled`, `weight` and `biomes`, plus X's own knobs.
3. `disaster/x/XDisaster extends Disaster`: set the phase durations, implement `tickDisaster`, `effectRadius`, `describe`,
   and `syncData` (add public `D_*` index constants for the client). Route every block change through
   `DisasterManager.blockQueue(level)` and check `DisasterProtection.canModify(...)` when the change is applied.
   Scale by `intensity` and `strength()`. Make mobs flee (see the tornado's `alertMobs`).
4. `client/x/XVisual implements DisasterVisual`: in `contribute`, add sky/fog/cloud tint, fog, darkening, shake and
   FOV pulse. In `tick`, spawn particles (scale them by `ClientConfig.PARTICLE_DENSITY`, the vanilla particle setting,
   distance LOD and frustum culling) and play layered near/far sounds (`DisasterLoopSound`). Register it in `client/DisasterVisuals`.
5. Sounds: add a generator to `tools/gen_sounds.py`, register it in `registry/ModRegistries`, add it to `sounds.json`, list it in `CREDITS.md`.
6. Particles and textures: register in `ModRegistries`, generate the textures in `tools/gen_textures.py` (16x16 or 8x8,
   vanilla palette, hard pixels), add `particles/<name>.json`, register the provider in `RealisticDisastersClient`.
7. Add lang keys to `en_us.json` (name, warning and onset messages, death message, subtitles, config section).
8. GameTests in `gametest/DisasterGameTests`, each with **its own environment**: the effect happens, the
   `blockDestruction=false` toggle is respected, and the intensity-3 cost stays < 2 ms/tick. All tests must pass.
9. Docs: mark X as done in the README status table and add any deviations or known issues.

## Avoiding merge conflicts (several sessions work in parallel)
- Put new code in new files under `disaster/x/` and `client/x/`.
- Edits to the shared files above (DisasterType, DisasterConfig, ModRegistries, DisasterVisuals,
  RealisticDisastersClient, DisasterGameTests, lang, sounds.json, gen_* scripts, README, CREDITS) must be
  **append-only**: add your lines next to the existing ones and don't reformat or reorder them.
- Don't change the foundation classes (Disaster, DisasterManager, EffectsManager, ClientDisaster, payloads,
  ClientLevelMixin). If you must, keep the change minimal and backwards-compatible, and describe it in the PR.
- Before opening the PR: `git fetch origin main && git rebase origin/main`, resolve the (additive) conflicts,
  run `./gradlew build` and `./gradlew runGameTestServer` again.

## Definition of done
Compiles; all GameTests pass; a visual check was done (screenshots described in the PR); the README and CREDITS are updated;
the PR description lists what works, how to test it in-game, and the known issues.
