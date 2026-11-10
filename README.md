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
| Wildfire | ✅ complete |
| Earthquake, Volcano, Meteor shower, Tsunami/flood, Blizzard/sandstorm | planned (next sessions, in this order) |
| Volcano | ✅ complete |
| Earthquake, Wildfire, Meteor shower, Tsunami/flood, Blizzard/sandstorm | planned (next sessions, in this order) |
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

#### Physics
- **Load paths instead of "floating" rules.** A block with nothing under it only stays up if a chain of solid blocks
  reaches something standing on the ground within the material's span: wood 4, masonry 3, natural rock 4, iron-like 6,
  soil 1, glass 1, sand and gravel 0. Shaking shortens every span (up to -50% at intensity 5), so wide roofs and long
  overhangs fail first and minor quakes leave ordinary houses standing (tested). Every fall re-checks its neighbours,
  so collapses propagate.
- **Unreinforced masonry fails first.** From intensity 3, blocks are shaken out of thin masonry walls at any height
  (out-of-plane failure); then what's above needs a load path, so walls open up and roofs pancake. Timber framing is
  about five times more forgiving. Wall tops shed courses. Masonry cracks, and glass shatters.
- **Rigid toppling.** Chimneys, towers, pillars and undermined trees are found by the sampler, remembered, and get a
  topple roll every second (taller ones and stronger shaking make it more likely). They snap in the lower part of
  their free height and tip over as one rigid body: the tilt follows a falling rod's equation of motion
  (theta'' = 3g/2L * sin theta), with every block steered along its arc. Past ~65 degrees the pieces are released with
  their real tangential velocity, so the structure ends up lying along the ground (tested).
- **Landslides and rockfalls** on slopes steeper than ~55 degrees: the surface lets go and slides downhill, and the
  scar retreats upslope and widens.
- **Everything around reacts.** Doors, trapdoors and gates bang open and shut, and bells ring by themselves.
  Hanging lanterns, candles, flower pots and wall torches fall. Paintings and item frames come off the wall (frames
  drop their item first). Ponds slop over their banks as short-lived flowing water. Dropped items hop, and boats and
  minecarts are shoved. People can barely keep their footing (up to -55% walking speed while the ground moves).
  Grounded mobs are jostled, and debris hurts what it lands on.
- Sampling is dense around players (about 30 extra blocks per tick within 16 blocks), so what you can see reacts
  consistently.

#### Cinematic beats
- **P-wave boom:** a deep thump from below, heard later the further you are from the epicentre, with a small camera
  punch, a few seconds before the real shaking.
- **S-wave front:** a ring of dust and flying chips sweeps outward across the land. When it reaches you, the camera
  takes an impact (a jolt plus a short zoom punch).
- **The main rupture races** across the ground as the strong shaking arrives, then the cracks keep working open.
- **Collapse sound design:** every landing block thuds with its own material's sound, and where several come down
  together a heavy collapse crash plays (impact, breaking masonry, a tail of tumbling debris). A big collapse close to
  you jolts the camera.

Dev visual check for earthquakes: in `build.gradle`'s `clientVisualTest`, set
`realisticdisasters.autostart` to `earthquake,4,WARNING` and add `systemProperty 'realisticdisasters.quakeScene', 'true'`.
On a flat world (e.g. the GameTest world copied to `run/saves/VisualTest`) this builds a small test village around the
epicentre (houses, a wide hall, a stone tower, a chimney, a steep hill, trees, a pond, a road, doors, lanterns,
torches and a bell), clears the weather, puts you inside the quake zone, and moves you to an aerial viewpoint for the
aftermath (`EarthquakeDevScene`, dev only).

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

### 3D funnel mesh
The funnel is real 3D geometry, generated in code every frame (`client/tornado/FunnelRenderer`). Nested tube meshes
(about 44 rings × 64 segments each) follow the snaking, tilted funnel axis:
1. an opaque condensation core
2. a streaky outer shell
3. a faint outer veil
4. a dust sheath at the ground

Each layer scrolls its texture around the funnel (rotation from the wind model's angular speed) and upward (rising
air), with a slight helical slant for striations. The mesh has an irregular, living outline, strong side lighting
(bright lit flank, dark shadow flank), soft silhouettes, and reacts to lightning. Up close it fades into dark,
debris-laden murk, because inside a tornado you cannot see a funnel wall. Textures are seamless 256×512 / 256×256
procedural PNGs with smooth filtering (`.mcmeta` blur).

It is submitted through NeoForge's `SubmitCustomGeometryEvent` into vanilla's translucent entity pipeline, so fog,
lightmap and shader packs handle it like other world geometry. `meshFunnel = false` in the client config switches back
to the particle-only funnel (e.g. if a shader pack misbehaves).

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

## Testing a wildfire in-game

1. `./gradlew runClient`, create a world with cheats on. A forest, savanna or taiga shows it best.
2. `/disaster start wildfire 3`: it ignites 48 blocks in front of you. Stand downwind to get the full smoke pall.
3. Phases: **warning** (~20 s: a small fire smoulders, smoke rises, the sky starts turning orange, animals flee),
   **buildup** (~20 s: the wind picks it up), **peak** (80–160 s depending on intensity: a front races downwind,
   embers start spot fires ahead of it, smoke columns lean with the wind, ash falls), **aftermath** (~25 s: no new
   spread, the last columns burn out).
4. Afterwards the burn scar stays: black, ash-covered **scorched earth**, **charred logs** and rust-brown **scorched
   leaves** where the crowns were only scorched (stronger fires consume more foliage). Burnt ground and trunks keep
   smouldering for a minute or two (thin smoke, the odd ember). Over time scorched earth weathers back into dirt and
   grass regrows (`regrowth`). Water, sand, stone, bare dirt and cliffs act as firebreaks. Rain slows it a lot.
5. Walking into the flames sets you on fire (fire resistance protects).

### Wildfire physics
The fire is driven by the same factors as a real one:
- **Fuel from the blocks themselves.** Each column's fuel type, ignitability and fuel load come from its blocks' own
  flammability and fire-spread values (vanilla's and NeoForge's, so modded blocks work too). Grass flashes over in
  seconds, while trunks and wooden houses burn for minutes.
- **Fuel moisture.** Biome rainfall and temperature, rain, nearby water (green riparian vegetation) and the
  **time of day** (fires lie down at night) all count. Intensity acts as a drought or fire-danger index.
- **Rothermel-style spread.** The rate of spread grows with wind. Head fires race downwind, while backing fires creep
  upwind and often go out. Fire runs uphill fast and stops at cliffs.
- **Surface vs. crown fire.** Wind and intensity decide whether a forest fire climbs into the crowns. Crown fires
  spread faster, throw embers and consume the canopy. Surface fires leave scorched brown crowns.
- **Spotting.** Burning brands are lofted by the plume, carried by the wind and fall under gravity. Where they land on
  receptive fuel (dry grass, leaves, roofs, hay) they start spot fires, so a river or road isn't always enough.
- **Fire whirls.** Large, wind-driven fires spin up whirls: a rotating vortex with an updraft core and inflow at the
  ground. They follow the wind, ignite their path and throw embers high. Light objects (items, debris, small animals)
  follow the air through drag, so they get pulled in and carried up, while heavy ones are only shoved.
- **Water.** Water on burning ground puts it out with steam and a hiss. Rivers are firebreaks, buckets work, and the
  flame block is in `#minecraft:fire`, so splash water potions work too and mobs path around flames.
- **Heat.** TNT goes off (and any block that reacts to catching fire), campfires and candles light, snow and ice melt,
  and radiant heat hurts creatures standing next to a wall of flame. Dropped items burn.
- **Aftermath.** Burnt-through trunks sometimes snap and topple downwind. Animals flee, preferably to water, and
  villagers take shelter.

### What the wildfire looks like
Matched against real fire photos (crown fire in spruce forest, the Dome Fire and Beaver Complex plumes, prescribed
burns, a boreal forest after fire):
- **Flames:** the flame block carries a smoothly animated (interpolated), high-resolution flame texture on vanilla's
  fire geometry, so flames burn exactly where the fire is and light the terrain. Torching crowns add tall,
  animated flame-tongue particles one to two tree heights above the canopy; far away, fewer but larger flames keep
  the fire line readable.
- **Smoke:** dark grey-brown smoke boils off forest fires, paler grey-white smoke off grass. A convection column of
  soft cauliflower billows (sunlit tops, shadowed undersides) towers up to an inversion height, bent downwind by
  wind that strengthens with altitude, and spreads into a tan smoke layer that streams hundreds of blocks downwind.
  The base of the column is lit by the fire, which shows as an orange glow at night. Smoke right in front of the
  camera fades out instead of filling the screen.
- **Light:** the sky stays blue upwind; under the plume the light turns hazy yellow-brown, and near the flames (and
  at night) the fog and sky glow orange.
- **Embers and ash:** glowing sparks with motion streaks, lofted out of torching trees and carried downwind; pale ash
  flakes tumbling down under the smoke layer.
- **Torching:** when a tree's crown goes up it flares into a column of flame several times its height, with a burst of
  embers and a deep whoosh.
- **Fire whirls:** a narrow, spinning column of flame leaning with the wind and snaking with height, inside a rotating
  smoke column, with a jet-like roar. Standing close shakes the camera.
- Blocks (charred log, scorched earth, scorched leaves) stay 16x16 so they sit naturally among vanilla blocks.
## Testing a volcano in-game

1. `./gradlew runClient`, create a world with cheats on.
2. `/disaster start volcano <1-5>` and watch from a distance. The size scales with intensity: intensity 1 is a ~10-block
   cinder cone, intensity 5 grows a ~130-block mountain with a ~200-block base radius (as big as the largest vanilla
   mountains). A big volcano that would start right next to you moves its vent away, so you end up on its lower flank.
3. Phases: **warning** (~20 s: tremors, deep rumbles, steaming fissures tear open, animals flee, villagers hide),
   **buildup** (15–30 s: the mountain starts rising out of the ground, the crater fills with lava, the column starts),
   **peak** (60–120 s: the mountain keeps growing, lava bombs, blasts, lava rivers, the full eruption column with its
   umbrella cloud, ash fall, lightning, pyroclastic flows at intensity 3+, a full column collapse at intensity 5),
   **aftermath** (~30–60 s: activity fades, the column detaches and drifts away).
4. Try `/time set midnight` for the glowing night look, and stand downwind to see the ash fall and ash blanket.

Dev shortcut: set `realisticdisasters.autostart` in the `clientVisualTest` run to `volcano,5,PEAK`.

### What the volcano looks like
Modelled on real eruptions (Kīlauea fountains, Stromboli/Rinjani bursts, Taal, Sakurajima, Pinatubo, St. Helens) and
compared with reference photos. Everything is built to sit seamlessly in Minecraft:
- **The mountain** is real terrain: a stratovolcano that grows out of the ground during the eruption, keeping its shape
  (concave like Mount Fuji, steep near the summit, flattening at the foot) with a bowl-shaped summit crater, radial
  gullies and ridges, an irregular footprint, dark scoria on the upper cone, grey tephra and old lava channels lower down,
  and layered strata of lava rock and tephra inside.
- **Ash clouds are voxel clouds**, drawn the way Minecraft draws its own clouds: solid, shaded boxes (bright tops,
  darker sides and undersides) instead of flat smoke sprites. The column is a dark jet at the vent that widens with height
  and bends downwind; its billows rise and churn; it spreads into an umbrella cloud that drifts far downwind; cloud above
  shadows the cloud below; the lava fountain lights its base orange at night and lightning flashes light it up.
- **Pyroclastic flows** roll down the mountain as a ground-hugging voxel cloud with a tall billowing head.
- **Lava fountain and bombs**: incandescent spatter that cools in flight from yellow to orange, red and black; bombs
  trace glowing spark arcs and smoke trails. Steam and gas rise off fresh lava; lava meeting water throws up white steam.
- Under the ash cloud the daylight dims to a warm brown-grey (following the time of day), visibility drops and ash falls.

### Volcano physics
- **Growing mountain**: built solid, column by column under a time budget per tick. The ground rises under whatever
  stands on it: entities are lifted onto the new surface instead of being buried. Plants, trees, snow and water get buried;
  buildings stay embedded. The vent and its crater lava lake rise with the summit.
- **Lava**: the crater lava lake overflows into lava rivers that run down the flanks, braid and spread into lobes; they
  crust over first (glowing magma blocks) and then cool into basalt/blackstone, batched.
- **Lava bombs** fly ballistically, burn and hurt on impact, leave small craters and ignite only what can burn.
- **Blasts** throw out bursts of bombs and a pressure wave that knocks back everything near the crater.
- **Pyroclastic flows**: part of the column collapses into a hot ash avalanche racing down a sector of the mountain
  (a full 360° collapse at intensity 5). It accelerates on steep slopes and slows on flat ground (~50–150 km/h), kills
  and ignites what it catches (a roof halves the heat), shoves things ahead of its front, strips the leaves off trees and
  sets the charred trunks alight, scorches the ground, flash-melts snow and leaves an ash deposit.
- **Ash fall**: ash settles out of the drifting cloud as a new block, *Volcanic Ash* (layers 1–8 like snow, doesn't
  melt). It is thickest near the vent along the wind axis and thins with distance, like a real isopach map, and buries plants.
- **Snow and ice melt** around the mountain; the meltwater runs off downhill (lahars).
- **Precursors**: steaming fissures with glowing magma at the bottom tear open near the future vent during the warning.
- **Volcanic lightning** (visual only, never starts fires) strikes around the vent during strong eruptions.
- **Mobs flee**, villagers take shelter. All block changes respect `blockDestruction`, spawn protection, protected areas
  and claims.
## Testing a meteor shower in-game

1. `./gradlew runClient`, create a world with cheats on. `/time set midnight` shows the streaks best.
2. `/disaster start meteor_shower 4` (centred 48 blocks in front of you; craters land within ~58 blocks of that point).
   Stand back: craters are 4-10 blocks in radius (the big one at intensity ≥3 up to ~20), the blast throws and hurts
   you far beyond the rim, and windows shatter out to ~60 blocks.
3. Phases: **warning** (~28 s: thin shooting stars streak across the sky from one radiant point; bright ones have a
   green or yellow head, some leave a fading train or end in a flare, a few whoosh faintly; animals start drifting away), **buildup** (~15 s: the first small fireballs reach the ground),
   **peak** (36–80 s, longer at higher intensity: a barrage of fireballs; at intensity ≥4 one big meteor near the
   middle), **aftermath** (~20 s: the streaks die down, craters keep smoking for about two minutes).
4. Each fireball: white-hot head with a coloured halo, shedding glowing fragments, leaving a smoke trail that hangs in
   the sky. At night big ones light up the land. Impact: flash (sky flash + white-out when close), a fireball that
   cools into black smoke, real blocks thrown out on ballistic arcs, a dust ring racing out (coloured like the
   ground), then a rising dust plume. You feel the ground shake first, then the air blast (17 blocks/tick, the
   speed of sound) throws you back and the boom arrives with it. Inside the crater it's lethal; armour and Blast
   Protection help. Glass shatters, leaves are stripped and plants flattened around the impact.
   Walk into a crater afterwards: a bowl about a fifth as deep as it is wide, a raised rim, an ejecta blanket that
   thins out to about a diameter away, burnt turf, crushed rock (cobble/gravel/tuff, or coarse dirt in soil) lining
   the bowl, a dark melt patch on the floor and a small meteorite at the bottom with a chance of rare ores.
5. To see a solid ore core, set `oreChance = 1.0` in the `meteor_shower` config section.

## Automated tests

`./gradlew runGameTestServer`. These run on a dedicated-server dist, so they also check that no client-only code loads on a server:
- `tornado_damages_terrain`: a pig gets lifted and the turf is scoured
- `tornado_respects_block_toggle`: with `blockDestruction=false` no blocks change
- `tornado_intensity3_tick_cost`: the server cost stays below 2 ms/tick (measured ~0.2–0.3 ms)
- `tornado_shelter_protects`: an animal in a sealed bunker stays put while an exposed one is lifted
- `tornado_breaks_glass_and_snaps_trees`: glass shatters and a trunk snaps, leaving a stump
- `earthquake_fissures_and_crumbling`: fissures open the ground, the house and overhang crumble into falling-block debris, stone bricks crack
- `earthquake_respects_block_toggle`: with `blockDestruction=false` no blocks change and no debris spawns
- `earthquake_intensity3_tick_cost`: the server cost stays below 2 ms/tick (measured ~0.2–0.3 ms)
- `earthquake_mobs_flee`: a pig near the epicentre runs away during the warning
- `earthquake_tower_topples`: a pillar tipped over falls like a rigid body and lands spread out along the ground
- `earthquake_fixtures_react`: a door bangs open and a hanging lantern falls
- `earthquake_minor_spares_houses`: an intensity-1 quake leaves an ordinary house standing
- `wildfire_burns_and_chars`: the fire spreads, turf becomes scorched earth, tree trunks are charred, the canopy burns
  away or is scorched, and a pig flees or catches fire
- `wildfire_respects_block_toggle`: with `blockDestruction=false` the fire still runs (visuals, entity damage) but no block changes
- `wildfire_stopped_by_river`: a 3-wide river stops a wind-driven fire (with ember spotting off)
- `wildfire_put_out_by_water`: water poured on burning ground puts that column out
- `wildfire_heats_the_world`: the fire sets off TNT, lights a campfire under a tree and melts snow
- `wildfire_whirl_lofts_objects`: a fire whirl pulls in and lofts items
- `wildfire_intensity3_tick_cost`: on a 48x48 field (~1000 columns burnt in 15 s) the cost stays below 2 ms/tick
  (measured 0.3–0.9 ms)
- `volcano_erupts`: the mountain grows, a cow standing on it is lifted (not buried), bombs fly, a blast happens, lava flows
- `volcano_lava_cools`: lava crusts over into magma and then turns into basalt/blackstone
- `volcano_respects_block_toggle`: with `blockDestruction=false` it still erupts but no blocks change
- `volcano_intensity3_tick_cost`: the server cost stays below 2 ms/tick (measured ~0.8–1.5 ms with the mountain growing)
- `volcano_pyroclastic_flow`: a flow hits animals around the vent and leaves an ash deposit
- `volcano_mountain_scales`: size grows with intensity (mountain-sized at 5); a growing mountain has its summit over the vent

Volcano tests confine the volcano to an 18-block radius (`confineForTest`) because GameTest arenas are only 37 blocks apart.
- `meteor_impact_makes_crater`: a meteor carves a bowl with a lining and a raised rim, and the shockwave throws a pig
- `meteor_core_contains_ores`: with `oreChance = 1` the meteorite core is made of configured ores
- `meteor_respects_block_toggle`: with `blockDestruction=false` three impacts change no blocks
- `meteor_intensity3_tick_cost`: peak barrage plus two craters stays below 2 ms/tick (measured ~0.35 ms)
- `meteor_sync_payload_roundtrip`: the sync packet with a full meteor ring buffer decodes without leftover bytes
- `meteor_blast_shatters_glass_and_throws_ejecta`: glass and panes ~14 blocks out shatter; real blocks fly out

Tests start from a clean slate (leftover disasters stopped, block queue cleared), move the GameTest world spawn away
from the arena (vanilla spawn protection would otherwise protect part of it via the claim check), and only write
config values that differ.

## Configuration

- `config/realisticdisasters-common.toml` (server): natural spawning, interval and chance (= frequency), intensity cap,
  max active disasters, sync range, block destruction on/off, block changes per tick, spawn-protection radius,
  claim-mod respect, extra protected areas (`"dimension,x,z,radius"`), and per-disaster enable / weight / biomes.
  The tornado section also has max debris, duration scale, and whether it changes the weather.
  The earthquake section has max debris, duration scale, fissures on/off and fissure length scale. Its default biomes
  are `#minecraft:is_mountain`, `#minecraft:is_badlands`, `#minecraft:is_hill` plus stony shore, windswept gravelly
  hills / savanna, savanna plateau and desert.
  The wildfire section has the dry-biome bonus, max ignitions per tick, max area, spread speed, duration scale and
  whether rain slows it.
  The tornado section also has max debris, duration scale, and whether it changes the weather. The volcano section has
  max bombs, duration scale, mountain growth, lava flows, cooling time, volcanic lightning, pyroclastic flows and ash fall; its default biomes are `#minecraft:is_mountain`
  and the windswept hills.
  The meteor_shower section (default biomes: `#minecraft:is_overworld`, i.e. anywhere in the overworld) has duration
  scale, impact rate, crater scale, ore chance, the ore list (`"block_id"` or `"block_id*weight"`), rim fires on/off,
  max flying ejecta per impact, and blast damage (glass/leaves/plants) on/off.
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
  disaster/wildfire/WildfireDisaster   grid fire front, fuel model, charring; WildfireFlameBlock (self-extinguishing),
                             CharredLogBlock / ScorchedEarthBlock (smouldering state), scorched leaves
  disaster/volcano/VolcanoDisaster  eruption, bombs, blasts, lava, cooling, snow melt, fissures, lightning
  disaster/volcano/VolcanoEdifice   the growing mountain (time-budgeted builder, shape, strata, lifting entities)
  disaster/volcano/PyroclasticFlow  hot ash avalanche;  AshFall + AshLayerBlock: ash deposits
  disaster/volcano/LavaBombEntity   projectile, rendered as a full-bright magma block
  disaster/meteor/MeteorShowerDisaster   impacts scheduled ahead + synced in a ring buffer; blast physics; ballistic ejecta
  disaster/meteor/Crater, BlastDamage     crater carving per column; glass / leaves / plants around the impact
  network/                   DisasterSyncPayload (every 4 ticks), DisasterRemovePayload
  command/DisasterCommand, config/, registry/ModRegistries (sounds, particles, damage type key)
client (Dist.CLIENT only)
  ClientDisasters / ClientDisaster   interpolated mirror of server state, fade-out after removal
  EffectsManager             blends all disasters into sky/fog/cloud tint, fog distance, shake, FOV
  DisasterVisual(s)          per-type particles + sounds (tornado/TornadoVisual, earthquake/EarthquakeVisual)
  earthquake/EarthquakeCamera  earthquake ground motion: irregular strong motion + long-period rolling, angles and position
  particle/QuakeDustParticle  rock dust: low ground puffs (quake_dust) and billowing collapse clouds (collapse_dust)
mixin/CameraMixin            lets the earthquake offset the camera position
  DisasterVisual(s)          per-type particles + sounds (tornado/TornadoVisual, wildfire/WildfireVisual)
  DisasterVisual(s)          per-type particles + sounds (tornado/TornadoVisual, volcano/VolcanoVisual)
  volcano/VoxelCloud(+Renderer)  volumetric voxel clouds drawn like vanilla clouds (SubmitCustomGeometryEvent,
                             own pipelines + shader with vanilla fog); SparkParticle lava spatter; AshParticle ash flakes
  DisasterVisual(s)          per-type particles + sounds (tornado/TornadoVisual, meteor/MeteorShowerVisual)
  meteor/MeteorParticle      self-lit trail / fireball / ember particles; MeteorFlashOverlay (GUI layer)
  particle/VortexParticle    funnel / debris / dust particles that orbit their disaster
  particle/WildfireParticle  smoke, convection billows, smoke layer, smoulder wisps, flame tongues, embers, ash
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
- **Meteor shower look:** soft light and smoke (64x64 gaussian glow and billowy smoke sprites, tinted per particle)
  so streaks, fireballs, smoke trails and dust read like the real phenomenon; blocks, fragments and craters stay
  vanilla. Behaviour follows real observations: thin streaks of 0.3-0.8 s, green/white/yellow heads, short persistent
  trains, terminal flares, lingering bolide smoke trails, landscape illumination, flash first and boom later;
  craters follow simple-crater morphology (depth ≈ D/5, raised rim, ejecta ∝ r⁻³, breccia lining). The sky is not
  tinted: real showers don't change its colour.
- **Meteors are not entities.** Each impact is scheduled 2.5 s ahead and sent to clients in the regular sync packet
  (a 4-slot ring buffer in the type-specific data), so the client draws the fireball falling along a path that ends
  exactly at the impact point. Cheaper and more reliable than projectile entities, and nothing can intercept them.
- **Sky streaks are client-only** and random per player (they carry no gameplay); only real impacts are synced.
- **Impact physics.** Crater radius R ∝ meteor size (a proxy for the cube root of the impact energy). The blast
  front starts at the crater rim and travels at the speed of sound (17 blocks/tick); effects use the scaled distance
  z = r/R like real blast scaling: lethal for z < 1, damage ∝ (1.3/z)² to z ≈ 3-4, knockback ∝ 1/z out to z ≈ 6.
  Ejecta are real falling blocks launched at 45° ± 12° with the speed needed to land 1.2-3R away under Minecraft's
  gravity (0.04 b/t²) and drag (0.98/tick); a third of them go downrange of the meteor's travel direction. The
  client shakes the camera first with the ground wave and then with the air blast, timed to the same speeds. The screen flash honours the vanilla "Hide Lightning Flashes" option.
- **Crater blocks** go through the block-change queue one column per change (centre outward, so a crater opens in
  under a second at the default budget), with protection checked per column (claims and protected areas are
  column-shaped); block entities (chests etc.), bedrock and liquids are never touched. Glass shattering scans only
  chunk sections whose palette contains glass. Flying ejecta are capped (`maxEjecta`, and 3x that per shower). The crater floor is lined, not refilled, so water nearby can flow in.

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
- **Wildfire spread is simulated, not vanilla fire.** The fire front lives on a grid of block columns (burn timer
  per column, downwind-biased spread, ember spotting, fuel per column), capped per tick and applied through the block
  queue. The visible flames are a mod block (`wildfire_flame`) that burns entities like vanilla fire but never spreads
  and goes out on its own after a few seconds, even after a restart. Vanilla fire would spread uncontrollably and
  bypass the caps.
- **Wildfire sync:** the 32-float sync limit allows 12 burning columns per packet. The server sends a random 12 each
  time and the client remembers the last few seconds of them, so particles and sounds come from the whole front.
- **Natural spawning weight per biome:** `DisasterType` got an optional per-biome weight hook (default: unchanged), and
  `DisasterManager` asks it instead of the flat weight. Wildfires use it to favour hot, dry biomes (`dryBiomeBonus`).
- `tools/gen_structures.py` now writes block-state properties correctly (they used to end up in the block name).
  `tornado_arena.nbt` was not regenerated, so the tornado tests run on exactly the same arena as before.
- **Volcano lava** is placed as real lava source blocks along a downhill path chosen by the volcano, so vanilla
  lava spreads a few blocks from each of them and can start fires nearby. Lava placement keeps a 3-block margin from
  protected areas for that reason. When a source cools into rock, the flowing lava it fed drains away by itself.
- **Volcano ash clouds are voxels**, not sprites: soft smoke sprites looked pasted-in and showed sorting artifacts, so the
  big clouds are solid shaded boxes like vanilla clouds (sized 3–8 blocks per cell with the eruption). Small particles
  remain only for glowing spatter and fine ash fall.
- **Smoke render layer**: ash and gas use their own particle pipeline (vanilla's particle shader with translucent
  blending, but no depth writes, registered through `RegisterRenderPipelinesEvent`). Vanilla's translucent particles
  write depth unsorted, which punched sky-coloured holes into the dense ash column.
- **Blast sounds** are delayed by distance (about 17 blocks per tick) so the boom arrives after the flash.
- **Volcano cone** is decided once the vent area is loaded: it's built only where the ground within its radius varies by
  at most 3 blocks. On rough or mountain ground the eruption comes out of a magma vent in the existing terrain.

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
  slumping crack edges and collapsing blocks. A rigid topple is simulated only until the pieces separate; after
  that they fly as individual falling blocks and can't rotate.
- The structural model is a load-path heuristic (spans per material), not a finite-element simulation. It doesn't
  model weight, so a long, heavy masonry beam is judged like a short one.
- The new boom and collapse sounds were checked for level and length only, not listened to.
- Wildfire: wooden buildings in the path burn down partly (chance grows with intensity). Containers and other block
  entities are never touched, so a burnt house can leave chests standing. Use protected areas / claims for bases.
- Wildfire: fire behaviour is stochastic like the real thing. Under a strong wind the upwind side is left to a slow
  backing fire that often goes out, so not everything inside the burn area burns.
- Wildfire: ember spotting can jump rivers and roads; that's intended. Firebreaks need to be wide, or upwind.
- Wildfire: a fire started in the rain (also via command) usually fizzles out in the warning phase and goes straight
  to the aftermath. That's intentional (`rainSlows`), but it can look like nothing happened.
- Wildfire: flame blocks sit on top of a column and under the canopy, not on every log face; trunks get no flames of
  their own (the torching particles cover the crown).
- Wildfire: particles are sampled without texture filtering by Minecraft, so very large smoke puffs can show slightly
  stepped edges. 128 px textures and the near-camera fade keep this small.
- Wildfire: the flame block's animated texture is high resolution, unlike vanilla's 16x16 fire. That's deliberate
  (realism first) but it is the one place where the fire doesn't match vanilla's pixel style.
- Volcano: lava that is still molten when the server stops won't cool later, because disasters aren't saved.
  Fires started by lava or bombs spread by the vanilla fire rules (`doFireTick`). Lava bombs are rendered as a
  glowing magma block (vanilla item renderer), not a custom model.
- Volcano: shader packs (Iris) may not apply their lighting to the voxel clouds (custom pipeline with vanilla fog).
- Volcano: the intensity-5 mountain is ~2.5 million blocks; it grows over the eruption with a ~2.2 ms/tick budget (server
  cost at intensity 5 measured ~3.8 ms/tick in a dev client). Columns in unloaded chunks are skipped, so a part of a giant
  volcano far from any player can stay unfinished. If the eruption ends before the builder catches up, the mountain
  stays slightly smaller.
- Volcano: the mountain is not reverted afterwards (damage persists by design). Pyroclastic flows and ash fall reach far
  (hundreds of blocks at intensity 5).
- Meteor shower: streaks are chains of overlapping soft particles (vanilla has no line/ribbon particle); very close
  up you can see the individual glows. On "Minimal" particles they are sparse by design. Particle blending is alpha,
  not additive, so overlapping glows don't over-brighten like a camera would.
  Impacts outside the client's loaded chunks still happen on the server but only the distant boom is heard if in sync range.
  Craters in water/lava columns only remove the solid blocks; the liquid stays.
  The biggest impacts change a few thousand blocks; at the default 48 changes/tick that is spread over ~1-2 s, with a
  short server-cost bump while it happens (the intensity-3 average stays well under 1 ms/tick).
  Ejecta landing spots are taken from where the falling block stops; one that lands in a protected area or on a
  non-sturdy block simply vanishes (no item drop).
