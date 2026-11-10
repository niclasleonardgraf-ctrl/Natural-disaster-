# Realistic Natural Disasters — Specification

Mod ID: `realisticdisasters` · Minecraft 26.1.2 · NeoForge 26.1.2.x · Java 25 · ModDevGradle MDK.

Sections are self-contained so a session can read only the part it needs.

---

## 1. Style

The mod should feel cinematic and immersive while still looking clearly like Minecraft, like a Mojang-made disaster update.

- Textures are pixel art at 16x16 or 8x8 in a vanilla palette, with no smooth gradients and no high-res or realistic images.
- Generate textures with a small script that writes PNGs (`tools/gen_textures.py`).
- Use original or CC0 sounds and list them in `CREDITS.md`.
- Don't copy other mods' code or assets.
- Effects must work in vanilla rendering and with shader packs (Iris, if available).
- Keep client-only code out of common and server classes. The mod must work on dedicated servers.

---

## 2. Every disaster (shared behaviour)

- **Phases:** warning signs (animals flee, the sky shifts, distant rumbles), buildup, peak, and aftermath. Effects blend in and out smoothly.
- **Atmosphere and camera:** custom fog and sky tint, distance-based camera shake, and an optional FOV pulse. Camera effects can be turned off.
- **Sound and particles:** layered positional audio with distant and close variants, and layered particles with varied size, speed, and lifetime.
- **Mobs and villagers** flee or take shelter.
- **Damage persists** after the disaster ends.
- **Intensity levels 1–5** scale size, damage, and effects.
- **Natural spawning** by biome, with configurable frequency.

---

## 3. Disasters (build in this order)

### 3.1 Tornado
A swirling particle funnel that lifts and flings blocks and entities as falling-block debris, turns the sky dark and green, and leaves a damage path.

### 3.2 Earthquake
Escalating shake, fissures that open in the ground, crumbling structures, dust, and rumble.

### 3.3 Wildfire
Wind-driven spread, smoke columns, orange fog, ash, and charred terrain afterward.

### 3.4 Volcano
Lava bombs, ash clouds that dim the sun, and lava flows that cool into basalt or blackstone.

### 3.5 Meteor shower
Sky streaks, then craters with scorched blocks and rare ores, a flash, and a shockwave.

### 3.6 Tsunami / flood
A wave wall that floods low ground and then recedes.

### 3.7 Blizzard and sandstorm
Heavy directional particles, thick fog, wind pushback, and accumulation.

---

## 4. Config

- Enable or disable each disaster.
- Frequency and intensity cap.
- Block destruction on/off.
- Spawn and claimed-area protection.
- Camera shake strength.
- Particle density.

---

## 5. Performance

- Batch block changes across ticks.
- Scale particles to the client setting and to distance.
- Cull off-screen effects.
- Target no noticeable TPS drop at intensity 3.

---

## 6. Session plan

1. Project setup from the MDK, runClient verified.
2. Shared foundation: disaster base class with phases, server-side manager, sync payloads, client effects manager (fog, sky tint, camera shake, sounds), `/disaster start|stop|list`, config.
3. Tornado (complete).
4. Later sessions: one disaster per session in the order of section 3.
