# Realistic Natural Disasters

Cinematic, Minecraft-styled natural disasters. Mod ID `realisticdisasters`.
Minecraft **26.1.2**, NeoForge **26.1.2.126**, Java **25**, ModDevGradle.

Full design spec: [`docs/SPEC.md`](docs/SPEC.md).

## Status

| Disaster | State |
|---|---|
| Tornado | ✅ complete |
| Blizzard / Sandstorm | ✅ complete (shared wind-storm base) |
| Earthquake, Wildfire, Volcano, Meteor shower, Tsunami/flood | planned (next sessions, in this order) |

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

## Testing a blizzard or sandstorm in-game

1. `./gradlew runClient`, create a world with cheats on. A snowy biome suits the blizzard, a desert or badlands the sandstorm.
2. `/disaster start blizzard 3` or `/disaster start sandstorm 3`. The storm is a wide disc (radius 56–120 blocks)
   centred 48 blocks in front of you, so you're usually inside it.
3. Phases: **warning** (~20 s: the sky turns white or dusty orange, a distant moaning wind, animals look for a roof,
   villagers go inside), **buildup** (~20 s: fog thickens, sideways snow or sand, gusts), **peak** (80–160 s), **aftermath** (~20 s).
4. Walk into the wind: you're slowed and pushed back. With the wind you speed up. Under a roof the wind stops,
   the fog thins and the howl is muffled. In a blizzard you slowly freeze outside (leather armour protects). At
   intensity ≥ 3 a sandstorm hurts you if you're outside without a helmet.
5. Afterwards the land is buried: snow layers (and ice on still water) after a blizzard, sand or red-sand layers
   after a sandstorm, with deeper drifts and full-block sand piles against walls and in their lee.
6. `/disaster stop` lets it die down. The new blocks `Sand Layer` / `Red Sand Layer` are in the Natural Blocks tab.

### What the blizzard and sandstorm look like
Modelled on real footage and meteorological descriptions of blizzards, ground blizzards and haboobs, then compared
side by side with headless screenshots (`docs/screenshots/`) and tuned until they matched:
- **Blizzard:** a flat, light-grey white-out. Sky and fog merge, so there is no horizon and no contrast. Nearby
  objects ghost out within a few blocks, and visibility comes and goes with the gusts. Snow drives past almost
  horizontally as motion-blurred streaks. Each flake is rolled to its on-screen direction of travel and stretched by
  how fast it crosses the view, so snow coming straight at you reads as dots. Streamers of drifting snow race along
  the ground and plume off roof edges and crests. From outside, an approaching blizzard is a grey-white curtain that
  swallows the landscape.
- **Sandstorm (haboob):** from outside, a towering front up to ~100 blocks high stretches to the horizon on both
  sides. It is dark and dense at the base, with billowing "cauliflower" tops, and creeps forward while the dust boils
  upward. It is visible from the warning phase on. Inside, everything turns a dark orange-brown monochrome
  (rust-red over the badlands). Visibility drops to a few blocks and daylight dims toward dusk at high intensity.
  Sand saltates in a shin-high layer along the ground.
- **Physics** (`StormWind`, shared by server and client so what you see is what pushes you):
  - **Gusts:** they are fronts that sweep downwind across the land. You can watch a gust race toward you through
    the snow or dust before it hits.
  - **Height profile:** the wind is calmer at your feet than on a rooftop or ridge (logarithmic wind profile).
  - **Shelter:** there's no wind under a roof. A wall casts a wind shadow that's strongest right behind it.
  - **Entities:** they are moved by aerodynamic drag (area per mass, knockback resistance, crouching, water) against
    ground grip. Chickens get blown about, players struggle into the gusts (crouching helps), iron golems barely
    notice, dropped items tumble and hop downwind and pile up against walls, arrows drift in a crosswind, and boats
    drift on the water.
  - **Fire and crops:** open flames (fire, campfires, candles) are blown out. At intensity 3+ exposed crops are
    killed by frost or buried by sand, and saplings and flowers get buried.
  - **Powder snow:** deep blizzard drifts become powder snow you sink into.
- **Cinematic beats:**
  - As a haboob looms, the daylight dims and turns brown before it arrives.
  - A low outflow skirt of dust or snow rolls along the ground ahead of the front.
  - The moment the front hits you there's a roaring whoosh, a deep thump and a hard camera jolt.
  - Buffeting and the howl follow the local gusts and fade in the lee of walls.
- **Accumulation** spreads as a continuous blanket where players are, rather than as scattered tiles. On open ground
  it forms long ridges across the wind (sastrugi in snow, ripples and low dunes in sand); against walls it forms
  deep drifts and piles.

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

### Tornado physics
One wind model (`disaster/tornado/TornadoWind`) drives everything, on the server and on the client:
- **Wind field:** wind speed rises to a peak at the core radius and falls off outside it (a Rankine vortex). Air
  flows in along the ground and up through the core, and the storm's forward motion adds on top, so the right flank
  of the track has the strongest winds. Peak speed scales with intensity, mirroring the EF scale.
- **Objects:** everything is moved by aerodynamic drag on the wind/object speed difference, scaled by the object's
  surface area per unit of mass. Items and small animals fly; iron golems barely move; crouching lowers your profile;
  water shields you.
- **Shelter:** under a roof the wind is cut down according to how enclosed the spot is. Underground or in a closed
  room almost nothing gets through, until the roof is torn off.
- **Impacts:** hitting a wall at speed hurts (like flying into a wall with elytra). Paintings and item frames are torn
  off the walls.
- **Blocks:** whether a block fails depends on the local wind speed versus its material's wind resistance, scaled by
  how many faces are exposed. Roofs, walls and tree crowns fail first; buried blocks are safe. Plants are shredded,
  glass shatters, turf is scoured into a damage path, trunks snap (the part above flies, a stump stays), and wood and
  stone are torn out as debris at higher intensities.
- **Debris:** thrown blocks are projectiles with material-dependent drag. They smash through glass, leaves and
  plants, hit creatures in mid-air (damage from speed and mass), and land with the block's own break sound plus a
  heavy thud.

### Tornado sound design
Layers cross-fade with distance, like real recordings:
- a far "freight train" rumble (50–300 Hz with a slow rhythmic chug)
- a mid-range rushing roar
- an overwhelming core roar right next to the vortex
- a wind howl driven by the real wind speed at your position (its pitch rises with the wind)
- debris rattling around you, trees snapping and debris crashing where it actually happens
- thunder after in-cloud lightning, delayed by distance

Sheltering muffles the high, violent layers while the low rumble comes through. All sounds are original and
synthesized by `tools/gen_sounds.py`.

## Automated tests

`./gradlew runGameTestServer`. These run on a dedicated-server dist, so they also check that no client-only code loads on a server:
- `tornado_damages_terrain`: a pig gets lifted and the turf is scoured
- `tornado_respects_block_toggle`: with `blockDestruction=false` no blocks change
- `tornado_intensity3_tick_cost`: the server cost stays below 2 ms/tick (measured ~0.2–0.3 ms)
- `tornado_shelter_protects`: an animal in a sealed bunker stays put while an exposed one is lifted
- `tornado_breaks_glass_and_snaps_trees`: glass shatters and a trunk snaps, leaving a stump
- `blizzard_buries_and_pushes` / `sandstorm_buries_and_pushes`: an armour stand gets pushed downwind and snow / sand layers pile up
- `blizzard_respects_block_toggle` / `sandstorm_respects_block_toggle`: with `blockDestruction=false` no blocks change
- `blizzard_intensity3_tick_cost` / `sandstorm_intensity3_tick_cost`: below 2 ms/tick (measured 0.35–0.9 ms across runs)
- `sandstorm_builds_piles`: sand drifting against a wall settles into a full sand block, open ground only gets a thin layer
- `windstorm_lee_shelters`: over 15 s at intensity 5 an armour stand in the open is pushed ~6 blocks, one in a wall's lee 0, a dropped item ~7
- `windstorm_weathers_fires_and_crops`: a campfire and a candle are blown out, exposed wheat is lost

## Configuration

- `config/realisticdisasters-common.toml` (server): natural spawning, interval and chance (= frequency), intensity cap,
  max active disasters, sync range, block destruction on/off, block changes per tick, spawn-protection radius,
  claim-mod respect, extra protected areas (`"dimension,x,z,radius"`), and per-disaster enable / weight / biomes.
  The tornado section also has max debris, duration scale, and whether it changes the weather.
  The blizzard section has duration scale, snow rate and whether it starts precipitation; the sandstorm section has
  duration scale and sand rate.
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
  disaster/windstorm/        WindStormDisaster (wind vector, gusts, push, shelter-seeking, batched accumulation)
                             → BlizzardDisaster, SandstormDisaster
  block/SandLayerBlock       snow-layer-shaped sand / red sand that never melts
  network/                   DisasterSyncPayload (every 4 ticks), DisasterRemovePayload
  command/DisasterCommand, config/, registry/ModRegistries (sounds, particles, damage type key)
client (Dist.CLIENT only)
  ClientDisasters / ClientDisaster   interpolated mirror of server state, fade-out after removal
  EffectsManager             blends all disasters into sky/fog/cloud tint, fog distance, shake, FOV
  DisasterVisual(s)          per-type particles + sounds (tornado/TornadoVisual)
  particle/VortexParticle    funnel / debris / dust particles that orbit their disaster
  particle/WindParticle      flakes / grains / haze that follow their storm's synced wind vector and gusts
  windstorm/WindStormVisual  shared fog, particles, sounds → BlizzardVisual, SandstormVisual (palettes)
  sound/DisasterLoopSound    cross-fading positional (near) and non-attenuated (far) loops
mixin/ClientLevelMixin       adds environment-attribute layers for sky, fog, cloud colour and sky light
```

Assets are generated by `tools/gen_textures.py`, `tools/gen_sounds.py` (needs numpy + ffmpeg), `tools/gen_structures.py`
and `tools/gen_block_assets.py` (sand layer blockstates, models, loot tables).

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

- **Wind storms** are wide discs that creep along with the wind. The wind direction and gust factor are synced to the
  client (`WindStormDisaster.D_*`), so particles and pushes surge together. Only entities with open sky above them
  are pushed, chilled or sand-blasted, and the push only accelerates them up to the wind's own speed.
- **Sand layers** are a new block (vanilla has none). It reuses the snow-layer shapes with the vanilla sand textures.
  When a drift reaches 8 layers it becomes a real sand / red sand block and the pile keeps growing while the drift lasts.
  Badlands storms (or storms over red sand / terracotta) carry red sand.
- **Mobs take shelter**: passive mobs pick a nearby standable spot with a roof over it. If there's none within 14
  blocks they turn tail and drift downwind. Villagers use the bell signal, as with the tornado.
- **Blizzard freezing** uses vanilla's frost (`ticksFrozen`), so leather armour protects, and freeze damage and the death
  message are vanilla's. The blizzard starts precipitation, which is snow in cold biomes (global, like the tornado's weather).

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
- Wind-storm sounds haven't been heard-tested either (no audio device), for the same reason.
- The haboob wall is built from billboard particles, so up close (within ~50 blocks) individual billows can show
  their outlines, with slightly stepped edges where Minecraft's particle shader cuts off transparent pixels. From
  further away it reads as one solid wall. A true volumetric dust wall would need a custom renderer.
- The realism check compared headless screenshots against published descriptions of real blizzards and haboobs
  (WMO cloud atlas, AMS glossary, NWS). I couldn't watch video footage from the build container.
- Wind shelter samples up to 6 blocks upwind at feet and head height. It doesn't model air flowing around corners
  or over very tall buildings.
- Shelter-seeking is a short random search, not a real pathfinding query. In open terrain animals just drift downwind.
- In the test world (plains) vanilla rain still falls inside a blizzard or sandstorm, because the weather is global.
  In the storms' own biomes it falls as snow (blizzard) or not at all (desert/badlands).
- Screenshots of the headless visual check: `docs/screenshots/blizzard.png`, `blizzard_approach.png`, `sandstorm.png`, `haboob_approach.png`.
- The GameTest arena structure logs a harmless `oak_leaves[persistent=true]` parse error (existing, from `gen_structures.py`).
