# Realistic Natural Disasters

Cinematic, Minecraft-styled natural disasters. Mod ID `realisticdisasters`.
Minecraft **26.1.2**, NeoForge **26.1.2.126**, Java **25**, ModDevGradle.

Full design spec: [`docs/SPEC.md`](docs/SPEC.md).

## Status

| Disaster | State |
|---|---|
| Tornado | ✅ complete |
| Earthquake | ✅ complete |
| Wildfire, Volcano, Meteor shower, Tsunami/flood, Blizzard/sandstorm | planned (next sessions, in this order) |

## Commands (op level 2)

```
/disaster start <type> [intensity 1-5] [x y z]   # default: intensity 3, 48 blocks in front of you
/disaster stop [all|<id>] [now]                  # default fades out through the aftermath; "now" removes instantly
/disaster list                                   # active disasters + server cost in ms/tick
```

## Testing a tornado in-game

1. `./gradlew runClient`, create a world with cheats on (plains or another open biome looks best).
2. `/disaster start tornado 3` and face the direction you were looking.
3. Phases: **warning** (~25 s: sky turns green-black, rain, distant rumbles, animals flee, villagers hide),
   **buildup** (~15 s: the funnel lowers from the wall cloud), **peak** (60–120 s, longer at higher intensity),
   **aftermath** (~16 s: the funnel ropes out and the sky clears).
4. Stand near the funnel to feel the pull, lift, camera shake and FOV pulse. Afterwards you'll find a track of scoured dirt and scattered debris.
5. `/disaster stop` lets it dissipate.

## Testing an earthquake in-game

1. `./gradlew runClient`, create a world with cheats on. Build or stand near a few structures (a house with a roof,
   glass windows, stone-brick walls) to see crumbling; mountains or badlands give rockfalls from overhangs.
2. `/disaster start earthquake 4 ~ ~ ~` puts the epicentre under you (or omit the position to have it 48 blocks ahead).
3. Phases: **warning** (~21 s: small foreshocks and low rumbles, animals flee, villagers hide),
   **buildup** (~10 s: the main shock arrives like in real seismograms: a sharp P-wave jolt, then a few seconds later
   the much stronger S-wave shaking, with slow rolling surface waves building behind it), **peak** (33–65 s, longer at
   higher intensity: violent irregular shaking, zig-zag ruptures tear across the ground step by step, roofs and
   overhangs cave in, wall tops shed blocks, stone bricks crack, glass shatters, collapse dust billows up),
   **aftermath** (~25 s: the rolling outlasts the jolts, decaying aftershocks, the dust slowly settles).
4. Near the epicentre the camera jerks irregularly; further out you mostly feel a slow, sea-sick rolling. Indoors,
   dust trickles from the ceiling; outside, trees shed leaves and ponds slop. Afterwards you'll find open cracks with
   a stepped fault scarp beside them (intensity 3+), rubble piles, cracked masonry and, in low ground, cracks filled
   with groundwater.
5. Intensity scales radius (30–70 blocks), fissure count (1–4), width (1–3) and depth (4–12), what can fall
   (up to hardness 1.6 at intensity 1 / 4.0 at intensity 5), how much an unsupported block needs to stay up, and damage.

### What the earthquake looks like
Modelled on real quake footage, survey photos and seismology, while keeping Minecraft's own blocks and renderer:
- **The sky doesn't change.** Earthquakes don't change the weather. A thin grey-beige haze only builds up from the dust
  that collapses actually throw up, and it settles again.
- **Ground motion is the main effect.** The camera itself moves (a small positional jolt and heave, via a client
  mixin), not just tilts, because real footage is dominated by the camera being thrown back and forth. Strong motion is
  irregular noise (no regular rhythm) and dies off quickly with distance. The long-period rolling (period ~3 s,
  elliptical like a Rayleigh wave) carries much further and outlasts the strong shaking.
- **Dust comes from what breaks.** Where debris lands, a pale grey-beige cloud bursts out, billows up, rolls outward
  low and wide, and hangs for ~7–14 s. Falling debris trails dust. Opening cracks spurt soil, and loose dry ground
  (sand, gravel, bare dirt, terracotta) smokes, while grass and rock barely do.
- **Ground ruptures look like real surface ruptures.** They're narrow, mostly straight runs with sudden kinks
  (zig-zag), tapered at both ends, with short en-echelon cracks splaying off at an angle. Rim blocks slump into the
  crack, the down-thrown side drops 1–2 blocks (a fault scarp), and in low-lying ground the bottom floods with
  groundwater.
- **Trees topple with their trunks:** when a trunk falls into a crack, its now-unattached canopy comes down too.

Dev visual check for earthquakes: in `build.gradle`'s `clientVisualTest`, set
`realisticdisasters.autostart` to `earthquake,4,WARNING` and add `systemProperty 'realisticdisasters.quakeScene', 'true'`.
On a flat world (e.g. the GameTest world copied to `run/saves/VisualTest`) this builds a small test village around the
epicentre (houses, tower, trees, pond, road), clears the weather, puts you inside the quake zone, and moves you to an
aerial viewpoint for the aftermath (`EarthquakeDevScene`, dev only).

Shortcut for development: `./gradlew runClientVisualTest` loads the save `run/saves/VisualTest`, moves you onto a
small pillar, plants a test forest and auto-starts an intensity-4 tornado at peak about 100 blocks away
(`-Drealisticdisasters.autostart=type,intensity,PHASE`, dev only).

### What the tornado looks like
Modelled on real supercell tornadoes. The cloud base is drawn with flat, downward-facing cloud sheets, so it reads as
one continuous dark ceiling receding in perspective, with slowly rotating concentric striations. Vanilla's blocky
clouds fade out under it. It lowers into a wall cloud, from which a dark, tilted funnel hangs. The funnel is built
from panels lying on its surface, carrying a streaked texture wound into a helix, with soft edges. It snakes down
into a churning grey-brown debris cloud, while dust streamers spiral in along the ground and rain curtains hang on
the trailing side. Flung blocks circle high in the air. The grading is dark and dramatic: near-black sky and darkened
land against a bright, backlit horizon band. In-cloud lightning flickers through the base and lights the storm from
within, and thunder follows after the sound's real travel time. Weak tornadoes are thin ropes; strong ones are wide wedges.
As a tornado dies, it thins out and lifts back into the cloud. All textures are soft, noise-based PNGs generated by
`tools/gen_textures.py`.

Implementation notes: oriented quads come from overriding `VortexParticle#extract`. `QuadParticleGroupMixin` culls
large particles by their real size (vanilla culls by centre point). All quad particles share vanilla's
16384-particle queue, so the spawn budget keeps the storm around 11k particles.

## Automated tests

`./gradlew runGameTestServer`. These run on a dedicated-server dist, so they also check that no client-only code loads on a server:
- `tornado_damages_terrain`: a pig gets lifted and the turf is scoured
- `tornado_respects_block_toggle`: with `blockDestruction=false` no blocks change
- `tornado_intensity3_tick_cost`: the server cost stays below 2 ms/tick (measured ~0.23 ms)
- `earthquake_fissures_and_crumbling`: fissures open the ground, the house and overhang crumble into falling-block debris, stone bricks crack
- `earthquake_respects_block_toggle`: with `blockDestruction=false` no blocks change and no debris spawns
- `earthquake_intensity3_tick_cost`: the server cost stays below 2 ms/tick (measured ~0.2–0.3 ms)
- `earthquake_mobs_flee`: a pig near the epicentre runs away during the warning

## Configuration

- `config/realisticdisasters-common.toml` (server): natural spawning, interval and chance (= frequency), intensity cap,
  max active disasters, sync range, block destruction on/off, block changes per tick, spawn-protection radius,
  claim-mod respect, extra protected areas (`"dimension,x,z,radius"`), and per-disaster enable / weight / biomes.
  The tornado section also has max debris, duration scale, and whether it changes the weather.
  The earthquake section has max debris, duration scale, fissures on/off and fissure length scale. Its default biomes
  are `#minecraft:is_mountain`, `#minecraft:is_badlands`, `#minecraft:is_hill` plus stony shore, windswept gravelly
  hills / savanna, savanna plateau and desert.
- `config/realisticdisasters-client.toml`: camera shake on/off and strength, FOV pulse, particle density, fog, sky tint.
  Also editable in-game: Mods → Realistic Natural Disasters → Config.

## Architecture

```
common (server-safe)
  disaster/Disaster          phases WARNING→BUILDUP→PEAK→AFTERMATH, smooth 0..1 strength envelope
  disaster/DisasterManager   per-dimension ticking, batched BlockChangeQueue, sync, natural spawning
  disaster/DisasterProtection  config + spawn radius + protected areas + claim mods
  disaster/DisasterType      registry of kinds (factory, enable flag, weight, biomes)
  disaster/tornado/TornadoDisaster
  disaster/earthquake/EarthquakeDisaster   tremor curve, fissures, support-based crumbling
  network/                   DisasterSyncPayload (every 4 ticks), DisasterRemovePayload
  command/DisasterCommand, config/, registry/ModRegistries (sounds, particles, damage type key)
client (Dist.CLIENT only)
  ClientDisasters / ClientDisaster   interpolated mirror of server state, fade-out after removal
  EffectsManager             blends all disasters into sky/fog/cloud tint, fog distance, shake, FOV
  DisasterVisual(s)          per-type particles + sounds (tornado/TornadoVisual, earthquake/EarthquakeVisual)
  earthquake/EarthquakeCamera  earthquake ground motion: irregular strong motion + long-period rolling, angles and position
  particle/QuakeDustParticle  rock dust: low ground puffs (quake_dust) and billowing collapse clouds (collapse_dust)
mixin/CameraMixin            lets the earthquake offset the camera position
  particle/VortexParticle    funnel / debris / dust particles that orbit their disaster
  sound/DisasterLoopSound    cross-fading positional (near) and non-attenuated (far) loops
mixin/ClientLevelMixin       adds environment-attribute layers for sky, fog, cloud colour and sky light
```

Assets are generated by `tools/gen_textures.py`, `tools/gen_sounds.py` (needs numpy + ffmpeg) and `tools/gen_structures.py`.

## Design notes and deviations

- **Sky tint through a mixin.** 26.x computes the sky colour through the environment-attribute system, and NeoForge
  has no event for adding layers to it. A small client mixin adds time-based layers to `ClientLevel`'s attribute
  system. Because the values go through vanilla's own pipeline, shader packs (Iris) receive the tinted sky, fog and
  cloud colours. Some shader packs draw their own fog and ignore vanilla fog distance; the tint and particles still apply.
- **Claim protection** uses NeoForge's cancellable `BreakBlockEvent` fired by a fake player. Claim mods that listen to
  it (most do) will protect their areas. If one doesn't, use `protectedAreas`.
- **Disasters are not saved** across server restarts; the damage they caused is.
- **Villagers "take shelter"** through the same brain memory a ringing bell sets.
- **Tornado debris** is real `FallingBlockEntity`s, capped per tornado. Lifted soil crumbles on impact. Wood, planks,
  leaves etc. land where they're thrown. When the cap is reached, blocks are destroyed without drops.
- **Weather:** in the overworld a tornado starts rain (thunder at intensity ≥3) unless it's already thundering.
  Weather is global, so this affects the whole overworld.

- **Earthquake fissures** grow from near the epicentre in steps; each step queues one change per column across the
  crack (a column change carves up to 12 blocks), so the batching is per column, not per block. Fissures follow the
  natural ground under buildings, don't drain water (they stop at liquids) and don't cut through terrain more than
  24 blocks above or below the epicentre.
- **Earthquake crumbling** is a simple support model rather than real structural physics: a block with nothing solid
  below falls unless it has at least *intensity* solid horizontal neighbours (so at intensity 5 nothing hangs in the
  air), and every fall re-checks its neighbours, so collapses cascade over the following ticks. From intensity 3, wall
  tops can also be shaken off sideways. Leaves, block entities, liquids and non-collidable blocks are never moved;
  falling debris lands as rubble (it's capped per quake; blocks over the cap wait their turn).
- **Earthquake damage:** besides falling debris (vanilla falling-block damage), entities near the epicentre take small
  "shaken" damage at intensity ≥3 during strong shaking, and every grounded entity in the zone gets jostled.
- **Earthquake camera:** `client/earthquake/EarthquakeCamera` drives both angles (`ViewportEvent.ComputeCameraAngles`)
  and a small camera *position* offset through a new client mixin (`mixin/CameraMixin`, injected at the end of
  `Camera#alignWithEntity`, before the culling frustum is built). Both obey `cameraShake` / `cameraShakeStrength`.
  Measured in the dev scene, at 0.7 radius from an intensity-4 epicentre the peak motion is ~10 cm and ~2°; the
  foreshocks are ≤1 cm. The quake adds only a little to the shared (sine-based) disaster shake.

## Known issues

- Sound has not been heard-tested: the build container has no audio device. The sound files and their playback code
  are in place and load without errors.
- The vortex is built from soft translucent particles. From inside the funnel, individual puffs can still be seen.
- **Realistic rain is global:** the mod overrides `minecraft:textures/environment/rain.png` with thin, tapered,
  semi-transparent grey-white streaks, so rain looks like this everywhere, not only during disasters. A resource
  pack can override it back.
- Cloud sheets fade out before the render-distance fog, which would otherwise paint them bright. With very low render
  distances (< 8 chunks), the storm base therefore covers less of the sky.
- The weather change is global (vanilla limitation).
- Earthquake sounds were also not heard-tested (same reason).
- The earthquake was compared against documented descriptions of real footage and seismology. Real reference photos
  couldn't be downloaded in the build container (the image sites refused automated requests), so there was no
  side-by-side pixel comparison.
- Camera motion can only be judged from logged amplitudes and frame-to-frame differences in headless screenshots;
  it hasn't been watched live.
- Earthquake dust is built from soft translucent puffs; very close up, individual puffs can still be made out.
- Blocks can't bend or tilt, so swaying trees, buckled roads and tilted buildings are approximated by falling leaves,
  slumping crack edges and collapsing blocks.
