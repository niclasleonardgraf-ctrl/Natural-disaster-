# Roadmap: from "nice mod" to "mod of the year"

This file is the hand-off between sessions. **When the user says "continue" (or anything like it), read this file
top to bottom, then resume at the first unchecked item in [Progress](#progress).** Update the checkboxes and the
[Session log](#session-log) as you go, and commit and push this file with every milestone. Containers are wiped
between sessions, so anything not pushed is lost.

## What the user asked for (their words, condensed)

- The mod is "still trash, not realistic": make every disaster look, sound and behave like the real thing. Aim for
  "best mod of the year" quality, and keep going until nothing more can be added.
- **Iterate visually.** Run the game after every adjustment. Look from different angles, distances, times of day and
  weather, compare with real-life footage, improve, repeat.
- **Keep the user updated with in-game screenshots** (send them with `SendUserFile`).
- **After every big milestone, give the user the mod jar** to test (`./gradlew build`, then send
  `build/libs/*.jar` with `SendUserFile`).
- Use **parallel worktree sessions / workflows** so the work goes faster. The user explicitly asked for this, which
  overrides the "single agent, no workflows" rule in `docs/SESSION_GUIDE.md`.
- Keep going until the user says stop.

### First concrete complaints, to fix first
1. **Tornado storm clouds** (the cloud base and wall cloud above the tornado) look bad. Redo them from scratch so
   they look like a real supercell.
2. **The tornado funnel doesn't connect to the ground.** It looks cut off in mid-air.
3. **Volcano eruption clouds are far too boxy.** They are deliberately drawn as voxel boxes today.
4. **Effects must fade with distance.** Moving away from any disaster should reduce its sky tint, fog, darkness,
   shake, FOV pulse, sound and particles smoothly and believably.

## Branches and state (as of 2026-11-10)

- **Work branch:** `claude/nifty-euler-zm2upd`. It contains `origin/integration/all-disasters` (all seven disasters
  plus the Disaster Controller, which is what the user plays) merged with the GameTest flake fix
  (`tornado_breaks_glass_and_snaps_trees` is now deterministic via `TornadoDisaster#anchor()`). If a future session
  is assigned a different branch, start it from this branch:
  `git fetch origin claude/nifty-euler-zm2upd && git checkout -B <new-branch> origin/claude/nifty-euler-zm2upd`.
- `origin/builds` holds the jars the user downloaded (`realisticdisasters-1.0.0-all-disasters.jar`). Ask the user
  before pushing to it. Delivering the jar with `SendUserFile` is enough.
- `main` only has the tornado. The per-disaster `claude/*` branches are already merged into `integration/all-disasters`.
- **Known separate flake:** `tornado_respects_block_toggle` failed 2 of 8 times on the old code. Planks (likely
  debris from an earlier test's tornado) landed in its area. It is probably fixed upstream by the integration branch's
  `forceFinish()` discarding debris; re-check it during the next GameTest runs.

## Environment facts (verified in this container)

- `./gradlew runGameTestServer` runs the whole suite (~45 tests on the integration code) in roughly 20–60 s. Grep the log for `failed at`.
- **The client runs headless:** `xvfb-run -n 99 -s "-screen 0 1280x720x24" ./gradlew runClient`. Mesa llvmpipe
  gives GL 4.5 core and reaches the title screen in about 1–2 min on a cold start. The window is 854×480 unless you
  pass `--width/--height` (the `clientVisualTest` run passes 1280×720).
  - `Failed to open OpenAL device` is not fatal: the game runs without sound. **Sound can't be heard-tested here.**
    Judge sound by reading code and generator parameters, and by checking `tools/gen_sounds.py` output with
    spectrograms (ffmpeg is installed).
  - On a fresh `run/`, the first launch shows the accessibility onboarding screen. Write
    `onboardAccessibility:false` into `run/options.txt` (also `pauseOnLostFocus:false`, `renderDistance:16`,
    `fov:70`, `guiScale:2`) before launching.
  - `--quickPlaySingleplayer <name>` only opens an **existing** world (`QuickPlay#joinSingleplayerWorld`), so the
    harness must create the world itself (see Phase 0).
  - Screenshot of the X display: `XAUTHORITY=$(ls -t /tmp/xvfb-run.*/Xauthority | head -1) DISPLAY=:99 import -window root shot.png`.
    The in-game `Screenshot.grab` is better: it has no window chrome and is exactly timed.
  - Kill: `pkill -f devlaunch; pkill -f "Xvfb :99"`.
- The machine has 4 CPUs and 15 GB RAM. One llvmpipe client uses about 2–3 GB and every core it can get, so
  **run at most 2 clients at once** (give each agent its own Xvfb display number: :99, :100, ...). Parallel agents
  should spend most of their time on code and only run short scripted client sessions.
- Decompiled Minecraft sources: `build/moddev/artifacts/minecraft-patched-26.1.2.126-sources.jar`. Unzip it into the
  scratchpad to grep it.

## Phase 0: screenshot harness (do this first, alone, before fanning out)

Every later step depends on taking repeatable screenshots, so build it once, well:

1. **`client/dev/ShotDirector`** (dev only: do nothing if `FMLEnvironment.isProduction()` or the
   `realisticdisasters.shots` property is missing). It reads a shot-list file named by `-Drealisticdisasters.shots=<path>`.
   - **World:** on the title screen, if `run/saves/<world>` is missing, create it with
     `minecraft.createWorldOpenFlows().createFreshLevel(name, new LevelSettings(name, GameType.CREATIVE,
     new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT),
     new WorldOptions(seed, false, false), registries -> WorldPresets.createNormalWorldDimensions(registries)
     /* optionally replaceOverworldGenerator with a FixedBiomeSource(plains), as in PresetEditor#fixedBiomeConfigurator */, parent)`.
     Otherwise use `createWorldOpenFlows().openWorld(name, ...)`. Use a fixed seed so every agent sees the same terrain.
   - **Shot-list commands**, one per line, run in order on the client tick:
     - `cmd <command>` sends a server command (`/time set 6000`, `/weather clear`, `/disaster start volcano 4 ~ ~ ~200`, ...).
     - `wait <ticks>`.
     - `spectator` switches to spectator mode and hides the GUI (`options.hideGui = true`).
     - `cam <dist> <heightAboveGround> <azimuthDeg> [lookHeight]` puts the camera relative to the first
       `ClientDisasters.active()` disaster and looks at its position plus `lookHeight` (teleport via `/tp @s x y z yaw pitch`).
     - `camabs <x> <y> <z> <yaw> <pitch>`.
     - `shot <name>` waits 2 frames, then `Screenshot.grab(gameDir, name + ".png", mainRenderTarget, 1, cb)` writes
       `run/screenshots/<name>.png`.
     - `quit` calls `minecraft.stop()`.
   - Log `[shots] done <name>` per shot so scripts can follow progress.
2. **`/disaster phase <id|all> <WARNING|BUILDUP|PEAK|AFTERMATH>`** (admin) on top of `Disaster#skipTo`, so a shot list can
   jump straight to peak.
3. **`tools/shoot.sh <shotlist> [display]`**: writes `run/options.txt`, runs
   `xvfb-run -n <display> ./gradlew runClient -Drealisticdisasters.shots=...` (or a dedicated `runClientShots` run
   in `build.gradle` with `--width 1280 --height 720`), waits for exit (timeout of about 10 min), and prints the PNG
   paths. Also `tools/contact_sheet.sh` (ImageMagick `montage`) to tile shots into one image for quick review.
4. **Standard shot lists** in `tools/shots/`: for each disaster, `*_peak.txt` (far 600 / mid 250 / near 80 / close
   25 / inside, ground-level and elevated, 3 azimuths, noon and dusk, clear vs. rain), and `*_distance.txt` (same
   view direction at 50/150/300/600/1000 blocks, for the falloff work).
5. Check it end to end: one tornado shot list produces about 10 sharp 1280×720 PNGs. Commit, push, and send the
   user the first contact sheet as the "before" picture.

## Phase 1: parallel fixes (Workflow, `isolation: 'worktree'`, one agent per item)

Each agent works in its own worktree, uses the harness, and runs **at least 3 visual iterations** (screenshot →
compare with real footage → change → screenshot). It commits on its own branch, returns a summary with before/after
screenshot paths, and must not touch the other items' files. Then merge them all (Phase 2). Shared rule: no
`Math.random`-style visual popping, smooth fades everywhere, and cost stays under budget (FPS on llvmpipe must not
drop much versus baseline; the server stays < 2 ms/tick at intensity 3).

### A. Tornado storm clouds: full redo
- Code today: `client/tornado/TornadoVisual` spawns cloud-base sheets (`cloud_sheet`, `cloud_band`, `wall_cloud`
  particles), `EffectsManager` and `ClientLevelMixin` tint the sky, and textures come from `tools/gen_textures.py`.
- Real reference (supercell): a flat, dark, **rain-free base** many km wide that recedes in perspective; a
  **rotating wall cloud** lowered under the updraft, with the funnel hanging from it; **striated, corkscrew updraft**
  edges (the "mothership" look) with a backlit, brighter shelf; an **inflow band / beaver tail**; a **clear slot / RFD
  cut** wrapping around; **rain and hail curtains** in the forward flank; bright yellow-green-grey light under the
  base against a lit horizon. Clouds have soft, wispy edges and no visible quads, puffs or hard sheet borders.
- Approach: replace the particle sheets with **mesh-rendered cloud geometry** (like `FunnelRenderer`, through
  `SubmitCustomGeometryEvent`): a large concave base disc with domain-warped noise for alpha and brightness, a
  layered wall-cloud skirt, a striated rotating updraft tower ring, and soft-edged rain curtains. Shade from sun
  direction and time of day, react to lightning, and fade into the fog correctly at every render distance.
- Done when screenshots from 1000/400/150/50 blocks at noon and dusk read as a real supercell, and nothing looks like
  a flat sheet, a box or a particle.

### B. Funnel must touch the ground
- Code: `client/tornado/FunnelRenderer` (`bottom = (1 - td) * 0.78`, the bottom-ring alpha ramp
  `if (i < 4 && td < 0.95f)`, the near-camera fade, the debris sheath only when `td >= 0.35`), `TornadoVisual.touchdown`,
  and the client position smoothing in `ClientDisaster` (y glides toward the server's `pos.y`, which itself only
  moves 10 %/tick toward `MOTION_BLOCKING_NO_LEAVES`).
- Hypotheses to check with screenshots: (1) the funnel's y origin sits above the real ground on uneven terrain or
  over trees, because the server ground follow is slow and uses leaves-free height; (2) the bottom rings fade out
  (alpha ramp / soft silhouette) so the base reads as transparent; (3) the debris cloud is too faint to hide the
  join; (4) fog or the near-camera murk fade eats the base from mid distance.
- Fix: sample the actual client ground height under each ring's axis offset (client heightmap), extend the mesh a
  little *into* the ground, keep the condensation funnel continuous down to the debris cloud at peak (real tornadoes
  often show a gap between condensation and ground filled by a **debris whirl**, so render that convincingly), and
  make the debris whirl dense, dark and churning at the ground.
- Done when the funnel visibly meets the ground at peak from all distances and angles, including slopes and forest.

### C. Volcano eruption clouds: no more boxes
- Code: `client/volcano/VoxelCloud` (density field on a 3–8 block grid) and `VoxelCloudRenderer` (solid boxes,
  `core/voxel_cloud` shader), plus `SmokeLayer` and `AshParticle`.
- Real reference: Plinian/Vulcanian columns (Pinatubo 1991, Calbuco 2015, Sakurajima): a **cauliflower** convective
  column with sharp, lit, rounded billows; a dark ash-laden core; an **umbrella cloud** spreading at the tropopause
  and drifting downwind; fall-out streaks underneath; lightning in the plume; pyroclastic flows hugging the slopes
  as rolling, billowing grey-brown clouds.
- Approach: keep the density field and its physics, but replace the box renderer. Draw camera-facing soft billow
  impostors (one per surface cell, with sizes and overlap that hide the grid). Use a generated spherical-billow
  texture with normals for lit tops and shadowed undersides, and per-billow noise rotation. Alternatively,
  ray-march the density field in a shader if llvmpipe performance allows. Blend the edges into fog and haze.
- Done when no straight edges or cubes are visible from any distance, and the plume reads as cauliflower billows.

### D. Distance falloff for every effect, for all disasters
- Audit `client/EffectsManager` (sky/fog/cloud tint, fog distance, darkening, shake, FOV), `ClientLevelMixin`, and
  every `client/*/...Visual` (`contribute`, particles, sounds via `DisasterLoopSound`) for tornado, earthquake,
  wildfire, volcano, meteor shower, tsunami and blizzard/sandstorm/windstorm.
- Define one shared, physically motivated falloff helper (for example `EffectsManager.falloff(dist, inner, outer)` with
  a smooth curve). Sky darkening follows the storm's real visual extent (a supercell darkens the sky ~20–40 km
  away, so scale that to render distance); shake follows ground motion decay; sound follows inverse-distance plus air
  absorption (high frequencies drop first); fog only inside the precipitation or ash area.
- Add a GameTest-style or unit-style check that the contributions decrease monotonically with distance, plus the
  `*_distance.txt` shot series.
- Done when walking or flying away from any disaster fades every effect smoothly to nothing, with no pops and no
  global darkening far away.

## Phase 2: merge and first milestone delivery
1. Merge the A–D branches into the work branch and resolve conflicts (the most likely is `EffectsManager`, between A and D).
2. Run `./gradlew build` and `./gradlew runGameTestServer`. Everything must pass, twice in a row.
3. Take the full screenshot set again and build contact sheets. Send the user the before/after comparisons and the jar
   (`SendUserFile`). Commit and push. Update [Progress](#progress) and the [Session log](#session-log).

## Phase 3+: the realism loop (repeat until the user says stop)
For each round, run a Workflow:
1. **Review:** one agent per disaster takes the standard shot list and lists concrete gaps against real footage. Use
   WebSearch for references and descriptions of real events (look, sound, physics, scale, timing, aftermath). Each
   gap is a finding with a screenshot and a reference.
2. **Adversarial verify:** a second agent tries to refute each finding ("is this really unrealistic / worth it?").
3. **Fix:** one worktree agent per surviving finding cluster, with a visual iteration loop.
4. **Merge, test, deliver:** screenshots plus jar to the user at each milestone.

Backlog, so each disaster reaches the same level:
- **Tornado:** multi-vortex at EF4–5, rope-out stage, satellite tornadoes, RFD gust front, hail, power flashes,
  realistic debris trajectories, damage path variety (EF-scale contextual damage), and sound (freight train + roar
  layering, real attenuation).
- **Volcano:** lava fountaining and flow realism (crust, glow, levees), ballistic bombs, ash fall accumulation, lahars,
  volcanic lightning, sulfur haze, night glow, and an eruption sound with a shockwave delay.
- **Earthquake:** P/S-wave timing, ground roll, building damage by material, liquefaction, aftershocks,
  dust clouds, and sound (rumble preceding shaking).
- **Wildfire:** spotting embers, crown fire, pyrocumulus, smoke column and night glow, ash, the burn scar, and regrowth.
- **Meteor shower:** entry fireballs, sonic booms delayed by distance, airbursts, crater morphology, and ejecta.
- **Tsunami:** drawback before the wave, a bore with a turbulent front, debris-laden flow, run-up, and backwash.
- **Blizzard / sandstorm / windstorm:** whiteout and haboob wall fronts, drifting, and visibility gradients.
- **Cross-cutting:** performance profiling (Spark-style timing, particle budgets), config and controller UX, shader-pack
  compatibility (Iris), multiplayer sync, localisation, README and screenshots for a public release.

## Progress
- [x] GameTest `tornado_breaks_glass_and_snaps_trees` made deterministic (anchor + wind-peak placement + 800 ticks)
- [x] Work branch merged with `integration/all-disasters`
- [x] Verified: headless client renders (llvmpipe GL 4.5), and sound is unavailable in the container
- [ ] Phase 0: screenshot harness (`ShotDirector`, `/disaster phase`, `tools/shoot.sh`, standard shot lists, "before" sheet)
- [ ] Phase 1A: tornado storm clouds redone
- [ ] Phase 1B: funnel touches the ground
- [ ] Phase 1C: volcano clouds not boxy
- [ ] Phase 1D: distance falloff for all disasters
- [ ] Phase 2: merged, all tests green, jar + screenshots delivered (milestone 1)
- [ ] Phase 3: realism rounds (append one line per round below)

## Session log
- 2026-11-10: fixed the tornado glass/trunk GameTest flake; merged `integration/all-disasters`; confirmed that the client runs
  headless; received the user's realism goals and first four complaints; wrote this roadmap. The user paused the work
  here. Next step: Phase 0.
