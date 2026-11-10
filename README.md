# Realistic Natural Disasters

Cinematic, Minecraft-styled natural disasters. Mod ID `realisticdisasters`.
Minecraft **26.1.2**, NeoForge **26.1.2.126**, Java **25**, ModDevGradle.

Full design spec: [`docs/SPEC.md`](docs/SPEC.md).

## Status

| Disaster | State |
|---|---|
| Tornado | ✅ complete |
| Meteor shower | ✅ complete |
| Earthquake, Wildfire, Volcano, Tsunami/flood, Blizzard/sandstorm | planned (next sessions, in this order) |

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

Shortcut for development: `./gradlew runClientVisualTest` loads the save `run/saves/VisualTest` and auto-starts an
intensity-4 tornado in buildup in front of you (`-Drealisticdisasters.autostart=type,intensity,PHASE`, dev only).

## Testing a meteor shower in-game

1. `./gradlew runClient`, create a world with cheats on. `/time set midnight` shows the streaks best.
2. `/disaster start meteor_shower 4` (centred 48 blocks in front of you; craters land within ~58 blocks of that point).
3. Phases: **warning** (~28 s: shooting stars streak across the sky from one radiant point, a few bright ones whoosh
   faintly, animals start drifting away), **buildup** (~15 s: the first small fireballs reach the ground),
   **peak** (36–80 s, longer at higher intensity: a barrage of fireballs; at intensity ≥4 one big meteor near the
   middle), **aftermath** (~20 s: the streaks die down, craters keep smoking for about two minutes).
4. Each impact: a flash (sky flash + white-out when close), then the shockwave throws you back and shakes the camera;
   the boom arrives later the further away you are (sound travels ~17 blocks/tick). Walk into a crater afterwards:
   scorched blackstone/basalt/magma lining, ejecta on the rim, sometimes fires, and a meteorite core at the bottom
   with a chance of rare ores.
5. To see a solid ore core, set `oreChance = 1.0` in the `meteor_shower` config section.

## Automated tests

`./gradlew runGameTestServer`. These run on a dedicated-server dist, so they also check that no client-only code loads on a server:
- `tornado_damages_terrain`: a pig gets lifted and the turf is scoured
- `tornado_respects_block_toggle`: with `blockDestruction=false` no blocks change
- `tornado_intensity3_tick_cost`: the server cost stays below 2 ms/tick (measured ~0.23 ms)
- `meteor_impact_makes_crater`: a meteor carves a crater with a scorched lining and the shockwave throws a pig
- `meteor_core_contains_ores`: with `oreChance = 1` the meteorite core is made of configured ores
- `meteor_respects_block_toggle`: with `blockDestruction=false` three impacts change no blocks
- `meteor_intensity3_tick_cost`: peak barrage plus two craters stays below 2 ms/tick (measured ~0.45 ms)

## Configuration

- `config/realisticdisasters-common.toml` (server): natural spawning, interval and chance (= frequency), intensity cap,
  max active disasters, sync range, block destruction on/off, block changes per tick, spawn-protection radius,
  claim-mod respect, extra protected areas (`"dimension,x,z,radius"`), and per-disaster enable / weight / biomes.
  The tornado section also has max debris, duration scale, and whether it changes the weather.
  The meteor_shower section (default biomes: `#minecraft:is_overworld`, i.e. anywhere in the overworld) has duration
  scale, impact rate, crater scale, ore chance, the ore list (`"block_id"` or `"block_id*weight"`) and rim fires on/off.
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
  disaster/meteor/MeteorShowerDisaster, Crater   impacts scheduled ahead + synced in a ring buffer; shockwave; crater carving
  network/                   DisasterSyncPayload (every 4 ticks), DisasterRemovePayload
  command/DisasterCommand, config/, registry/ModRegistries (sounds, particles, damage type key)
client (Dist.CLIENT only)
  ClientDisasters / ClientDisaster   interpolated mirror of server state, fade-out after removal
  EffectsManager             blends all disasters into sky/fog/cloud tint, fog distance, shake, FOV
  DisasterVisual(s)          per-type particles + sounds (tornado/TornadoVisual, meteor/MeteorShowerVisual)
  meteor/MeteorParticle      self-lit trail / fireball / ember particles; MeteorFlashOverlay (GUI layer)
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
- **Meteors are not entities.** Each impact is scheduled 2.5 s ahead and sent to clients in the regular sync packet
  (a 5-slot ring buffer in the type-specific data), so the client draws the fireball falling along a path that ends
  exactly at the impact point. Cheaper and more reliable than projectile entities, and nothing can intercept them.
- **Sky streaks are client-only** and random per player (they carry no gameplay); only real impacts are synced.
- **Shockwave** expands at 2.5 blocks/tick on the server (entities are pushed when the front reaches them) and the
  client delays its camera shake by the same amount. The screen flash honours the vanilla "Hide Lightning Flashes" option.
- **Crater blocks** go through the block-change queue one block per change, each re-checking protection; block
  entities (chests etc.), bedrock and liquids are never touched. The crater floor is lined, not refilled, so water nearby can flow in.

## Known issues

- Sound has not been heard-tested: the build container has no audio device. The sound files and their playback code
  are in place and load without errors.
- Particles are opaque pixel-art puffs, so very close up the funnel reads as large flat quads. This is intentional
  for the vanilla look, but it may get a translucent outer layer later.
- The weather change is global (vanilla limitation).
- Meteor shower: sky streaks are drawn as particle trails, so on "Minimal" particles they are sparse by design.
  Impacts outside the client's loaded chunks still happen on the server but only the distant boom is heard if in sync range.
  Craters in water/lava columns only remove the solid blocks; the liquid stays.
