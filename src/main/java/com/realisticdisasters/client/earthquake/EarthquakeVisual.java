package com.realisticdisasters.client.earthquake;

import com.realisticdisasters.client.ClientDisaster;
import com.realisticdisasters.client.DisasterVisual;
import com.realisticdisasters.client.sound.DisasterLoopSound;
import com.realisticdisasters.config.ClientConfig;
import com.realisticdisasters.disaster.Phase;
import com.realisticdisasters.disaster.earthquake.EarthquakeDisaster;
import com.realisticdisasters.registry.ModRegistries;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Earthquake presentation, modelled on real footage: the sky stays as it is; the ground motion (irregular strong
 * motion near the epicentre, slow rolling further out, see {@link EarthquakeCamera}) is the main effect. Dust comes
 * from where things actually break: collapsing structures and rockfalls throw up billowing clouds that roll outward
 * and hang in the air, opening fissures spurt soil, loose dry ground smokes, trees shed leaves, ponds slosh and
 * dust trickles from ceilings. Sound is layered: a deep far rumble, a grinding close rumble and cracking rock.
 */
public final class EarthquakeVisual implements DisasterVisual {
    // Collapse dust: desaturated grey-beige (concrete, stone and soil dust), only where dust was thrown up.
    private static final int DUST_FOG = 0x9A9284;
    private static final int DUST_SKY = 0xA59C8C;

    private record Falling(Vec3 pos, BlockState state) {}

    private static final RandomSource RANDOM = RandomSource.create();

    private static final class State {
        DisasterLoopSound close, distant;
        int nextRumble = 20;
        int nextCrack = 10;
        int nextCreak = 30;
        final java.util.Map<Integer, Falling> falling = new java.util.HashMap<>();
        // cinematic beats
        Phase lastPhase;
        int boomIn = -1;
        boolean sArrived, waveHitViewer;
        float wave = -1f;
        long ticks;
        int impactSounds;
        final java.util.Map<Long, long[]> landingCells = new java.util.HashMap<>(); // cell -> {count, firstTick, lastCrash}
    }

    public static float radius(ClientDisaster d) {
        float r = d.data(EarthquakeDisaster.D_RADIUS);
        return r > 1 ? r : 20f + d.intensity * 10f;
    }

    public static float tremor(ClientDisaster d) {
        return Mth.clamp(d.data(EarthquakeDisaster.D_TREMOR), 0f, 1f);
    }

    public static float roll(ClientDisaster d) {
        return Mth.clamp(d.data(EarthquakeDisaster.D_ROLL), 0f, 1f);
    }

    public static float dust(ClientDisaster d) {
        return Mth.clamp(d.data(EarthquakeDisaster.D_DUST), 0f, 1f);
    }

    // ------------------------------------------------------------------ atmosphere

    @Override
    public void contribute(ClientDisaster d, Vec3 cam, Atmosphere out) {
        double dist = horizontalDistance(d, cam);
        float radius = radius(d);

        // Earthquakes don't change the weather or the sky. Haze only comes from dust that collapses and rockfalls
        // actually threw up (synced from the server), concentrated where it happened and settling over time.
        // The visible dust is mostly the billowing clouds at collapse sites (particles); this is only the thin
        // haze that hangs over the whole area afterwards.
        float haze = dust(d) * (1f - smoothstep(radius * 0.7f, radius * 3f, (float) dist));
        out.fogColor(DUST_FOG, haze * 0.7f);
        out.sky(DUST_SKY, haze * 0.15f);
        out.darken(haze * 0.06f);
        out.fog(80f + (1f - dust(d)) * 160f + (float) dist * 0.3f, haze * 0.5f);

        // Strong (high-frequency) motion dies off quickly with distance; long-period rolling carries much further.
        float near = 1f - smoothstep(radius * 0.1f, radius * 1.2f, (float) dist);
        float far = 1f - smoothstep(radius * 0.8f, radius * 5f + 120f, (float) dist);
        float jolt = tremor(d) * (0.35f + 0.13f * d.intensity) * (near + far * 0.12f);
        float roll = roll(d) * (0.3f + 0.14f * d.intensity) * (near * 0.6f + far * 0.7f);
        double dx = cam.x - d.pos().x, dz = cam.z - d.pos().z;
        double len = Math.max(1.0e-3, Math.sqrt(dx * dx + dz * dz));
        EarthquakeCamera.add(jolt, roll, dx / len, dz / len);
        // A little of the shared sway too, so mixed disasters blend; the quake's own motion does the real work.
        out.shake(tremor(d) * near * 0.1f * d.intensity / 5f);
    }

    // ------------------------------------------------------------------ particles + sound

    @Override
    public void tick(ClientDisaster d, ClientLevel level, Camera camera) {
        State st = d.visualState instanceof State s ? s : new State();
        d.visualState = st;
        Vec3 cam = camera.position();
        st.ticks++;
        st.impactSounds = 0;
        tickSounds(d, st, cam);
        tickBeats(d, st, level, camera, cam);
        spawnParticles(d, st, level, camera, cam);
        st.lastPhase = d.phase;
    }

    /**
     * The big moments: the P-wave boom (heard first, later the further away you are) and the S-wave front, a ring of
     * dust sweeping outward over the land that hits the camera as it passes you.
     */
    private void tickBeats(ClientDisaster d, State st, ClientLevel level, Camera camera, Vec3 cam) {
        Minecraft mc = Minecraft.getInstance();
        double dist = horizontalDistance(d, cam);
        float radius = radius(d);
        if (st.lastPhase == Phase.WARNING && d.phase == Phase.BUILDUP) st.boomIn = (int) (dist / 30);
        if (st.boomIn >= 0 && st.boomIn-- == 0) {
            float near = 1f - smoothstep(radius, radius * 6f + 200f, (float) dist);
            if (near > 0.02f) {
                Vec3 dir = d.pos().subtract(cam);
                Vec3 at = cam.add(dir.lengthSqr() < 1 ? Vec3.ZERO : dir.normalize().scale(Math.min(dir.length(), 16)));
                mc.getSoundManager().play(new SimpleSoundInstance(ModRegistries.QUAKE_BOOM.get().location(), SoundSource.WEATHER,
                        (0.6f + 0.1f * d.intensity) * near, 0.9f + RANDOM.nextFloat() * 0.15f, RANDOM, false, 0,
                        SoundInstance.Attenuation.NONE, at.x, at.y - 4, at.z, false));
                EarthquakeCamera.impact(0.35f * near);
            }
        }
        if (!st.sArrived && d.phase == Phase.BUILDUP && tremor(d) > 0.4f) {
            st.sArrived = true;
            st.wave = 0f;
        }
        if (st.wave < 0f) return;
        st.wave += 1.6f; // ~32 blocks/s: a visible front rather than the real (much faster) wave speed
        float limit = (float) Math.min(radius * 3f, dist + 160);
        if (st.wave > limit) {
            st.wave = -1f;
            return;
        }
        if (!st.waveHitViewer && Math.abs(dist - st.wave) < 1.7f) {
            st.waveHitViewer = true;
            float hit = 1f - smoothstep(radius * 0.3f, radius * 2.5f, (float) dist);
            EarthquakeCamera.impact(0.25f + 0.6f * hit);
        }
        float density = ClientConfig.PARTICLE_DENSITY.get().floatValue() * switch (mc.options.particles().get()) {
            case ALL -> 1f;
            case DECREASED -> 0.5f;
            case MINIMAL -> 0.2f;
        };
        if (density <= 0) return;
        Frustum frustum = camera.getCullFrustum();
        Vec3 c = d.pos();
        int points = count(90 * density * (0.6f + 0.1f * d.intensity));
        double toViewer = Math.atan2(cam.z - c.z, cam.x - c.x);
        for (int i = 0; i < points; i++) {
            // Most of the budget goes to the part of the front the viewer can see (the arc facing them).
            double a = RANDOM.nextFloat() < 0.75f ? toViewer + (RANDOM.nextDouble() - 0.5) * 1.6 : RANDOM.nextDouble() * Math.PI * 2;
            double x = c.x + Math.cos(a) * st.wave, z = c.z + Math.sin(a) * st.wave;
            if (sq(x - cam.x) + sq(z - cam.z) > 120 * 120) continue;
            BlockState ground = groundAt(level, x, z);
            if (ground == null || !looseGround(ground) && RANDOM.nextFloat() > 0.6f) continue;
            int ix = Mth.floor(x), iz = Mth.floor(z);
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, ix, iz);
            if (frustum != null && !frustum.isVisible(new AABB(x - 2, y - 1, z - 2, x + 2, y + 3, z + 2))) continue;
            // The front kicks dust up and outward as it rolls past.
            level.addParticle(ModRegistries.QUAKE_DUST.get(), true, true, x, y + 0.2, z,
                    Math.cos(a) * 0.09, 0.05 + RANDOM.nextDouble() * 0.08, Math.sin(a) * 0.09);
            if (RANDOM.nextFloat() < 0.3f) spawnChip(level, x, z, 0.3);
        }
    }

    private void spawnParticles(ClientDisaster d, State st, ClientLevel level, Camera camera, Vec3 cam) {
        Minecraft mc = Minecraft.getInstance();
        float density = ClientConfig.PARTICLE_DENSITY.get().floatValue();
        ParticleStatus status = mc.options.particles().get();
        density *= switch (status) {
            case ALL -> 1f;
            case DECREASED -> 0.5f;
            case MINIMAL -> 0.2f;
        };
        if (density <= 0) return;

        Vec3 c = d.pos();
        float radius = radius(d);
        double dist = horizontalDistance(d, cam);
        if (dist > radius + 220) return;
        Frustum frustum = camera.getCullFrustum();

        // Collapse dust works even when the shaking has stopped (aftershock collapses, settling rubble).
        tickCollapseDust(d, st, level, frustum, cam, radius, density);

        float tremor = tremor(d);
        if (tremor < 0.05f) return;
        // Distance LOD.
        float lod = dist < radius ? 1f : dist < radius + 64 ? 0.6f : dist < radius + 140 ? 0.3f : 0.12f;
        AABB box = new AABB(c.x - radius, c.y - 16, c.z - radius, c.x + radius, c.y + 24, c.z + radius);
        boolean zoneVisible = frustum == null || frustum.isVisible(box);
        float budget = (6 + d.intensity * 5) * density * lod * (zoneVisible ? 1f : 0.1f);
        boolean inside = dist < radius;

        // Loose, dry ground (sand, gravel, bare dirt) shakes out low dust; grass and rock barely do.
        double sx = inside ? cam.x : c.x, sz = inside ? cam.z : c.z;
        double spread = inside ? Math.min(radius, 36) : radius;
        int dustTries = count(budget * 0.5f * tremor * tremor);
        for (int i = 0; i < dustTries; i++) {
            double a = RANDOM.nextDouble() * Math.PI * 2;
            double r = Math.sqrt(RANDOM.nextDouble()) * spread;
            double x = sx + Math.cos(a) * r, z = sz + Math.sin(a) * r;
            if (sq(x - c.x) + sq(z - c.z) > radius * radius || sq(x - cam.x) + sq(z - cam.z) < 36) continue;
            BlockState ground = groundAt(level, x, z);
            if (ground == null || !looseGround(ground) && RANDOM.nextFloat() > 0.1f) continue;
            spawnDust(level, frustum, x, z, 0.01 + RANDOM.nextDouble() * 0.03, 0.08);
        }

        // Fissures: soil and rock spurting from the tearing ground at the opening tips.
        float cracking = d.data(EarthquakeDisaster.D_CRACKING);
        int tips = Math.min(EarthquakeDisaster.MAX_SYNCED_TIPS, Math.round(d.data(EarthquakeDisaster.D_TIP_COUNT)));
        if (cracking > 0.05f) {
            for (int t = 0; t < tips; t++) {
                double tx = c.x + d.data(EarthquakeDisaster.D_TIPS + t * 2);
                double tz = c.z + d.data(EarthquakeDisaster.D_TIPS + t * 2 + 1);
                int plume = count(budget * 0.3f * cracking);
                for (int i = 0; i < plume; i++) {
                    double x = tx + (RANDOM.nextDouble() - 0.5) * 3, z = tz + (RANDOM.nextDouble() - 0.5) * 3;
                    spawnDust(level, frustum, x, z, 0.04 + RANDOM.nextDouble() * 0.08, 0.1);
                }
                if (sq(tx - cam.x) + sq(tz - cam.z) < 90 * 90) {
                    int chips = count(budget * 0.5f * cracking);
                    for (int i = 0; i < chips; i++) {
                        spawnChip(level, tx + (RANDOM.nextDouble() - 0.5) * 3, tz + (RANDOM.nextDouble() - 0.5) * 3, 0.35);
                    }
                }
            }
        }

        // Around the viewer: gravel and sand grains jump; trees shed leaves; water sloshes.
        if (inside || dist < radius * 1.5) {
            float local = tremor * (inside ? 1f : 0.4f);
            if (local > 0.35f) {
                int chips = count(budget * 0.3f * (local - 0.3f));
                for (int i = 0; i < chips; i++) {
                    double a = RANDOM.nextDouble() * Math.PI * 2, r = 2 + RANDOM.nextDouble() * 12;
                    double x = cam.x + Math.cos(a) * r, z = cam.z + Math.sin(a) * r;
                    BlockState ground = groundAt(level, x, z);
                    if (ground != null && looseGround(ground)) spawnChip(level, x, z, 0.15);
                }
            }
            shakeSurroundings(level, cam, local, density);
        }

        // Indoors / under overhangs: dust trickles from the ceiling.
        if (dist < radius * 1.3 && tremor > 0.2f) {
            int trickles = count(3f * density * tremor);
            for (int i = 0; i < trickles; i++) {
                BlockPos base = BlockPos.containing(cam.x + (RANDOM.nextDouble() - 0.5) * 10, cam.y + 1, cam.z + (RANDOM.nextDouble() - 0.5) * 10);
                for (int up = 0; up < 6; up++) {
                    BlockPos bp = base.above(up);
                    BlockState bs = level.getBlockState(bp);
                    if (bs.isAir()) continue;
                    if (bs.isSolidRender()) {
                        level.addParticle(new BlockParticleOption(ParticleTypes.FALLING_DUST, bs),
                                bp.getX() + RANDOM.nextDouble(), bp.getY() - 0.05, bp.getZ() + RANDOM.nextDouble(), 0, 0, 0);
                    }
                    break;
                }
            }
        }
    }

    /**
     * Collapsing structures and rockfalls are what really fill the air with dust: falling debris trails dust, and
     * where a block lands (or shatters) a cloud bursts out low and wide and rolls outward.
     */
    private void tickCollapseDust(ClientDisaster d, State st, ClientLevel level, Frustum frustum, Vec3 cam, float radius, float density) {
        Vec3 c = d.pos();
        double reach = Math.min(radius * 1.4, 140);
        AABB area = new AABB(c.x - reach, c.y - 32, c.z - reach, c.x + reach, c.y + 64, c.z + reach);
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (FallingBlockEntity fb : level.getEntitiesOfClass(FallingBlockEntity.class, area)) {
            seen.add(fb.getId());
            st.falling.put(fb.getId(), new Falling(fb.position(), fb.getBlockState()));
            if (cam.distanceToSqr(fb.position()) < 96 * 96 && RANDOM.nextFloat() < 0.35f * density) {
                level.addParticle(ModRegistries.QUAKE_DUST.get(), true, true, fb.getX(), fb.getY() + 0.5, fb.getZ(),
                        (RANDOM.nextDouble() - 0.5) * 0.04, 0.01, (RANDOM.nextDouble() - 0.5) * 0.04);
            }
        }
        var it = st.falling.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (seen.contains(e.getKey())) continue;
            it.remove();
            Falling f = e.getValue();
            landed(st, f, cam);
            if (cam.distanceToSqr(f.pos) > 128 * 128) continue;
            if (frustum != null && !frustum.isVisible(new AABB(f.pos.x - 4, f.pos.y - 1, f.pos.z - 4, f.pos.x + 4, f.pos.y + 4, f.pos.z + 4))) continue;
            // Masonry and soil make far more dust than wood. A collapse cloud billows up and rolls outward low and
            // wide, then hangs in the air for several seconds (see QuakeDustParticle's collapse mode).
            float dusty = f.state.getSoundType() == net.minecraft.world.level.block.SoundType.WOOD ? 0.5f : 1f;
            int puffs = count((4 + RANDOM.nextInt(4)) * density * dusty);
            for (int i = 0; i < puffs; i++) {
                double a = RANDOM.nextDouble() * Math.PI * 2, sp = 0.05 + RANDOM.nextDouble() * 0.12;
                level.addParticle(ModRegistries.COLLAPSE_DUST.get(), true, true, f.pos.x, f.pos.y + 0.3, f.pos.z,
                        Math.cos(a) * sp, 0.02 + RANDOM.nextDouble() * 0.07, Math.sin(a) * sp);
            }
            int bits = count(6 * density);
            for (int i = 0; i < bits; i++) {
                level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, f.state), f.pos.x + (RANDOM.nextDouble() - 0.5),
                        f.pos.y + 0.3, f.pos.z + (RANDOM.nextDouble() - 0.5),
                        (RANDOM.nextDouble() - 0.5) * 0.3, 0.1 + RANDOM.nextDouble() * 0.25, (RANDOM.nextDouble() - 0.5) * 0.3);
            }
        }
    }

    /**
     * Sound of debris landing: each block thuds with its own material's sound, and where several land close together
     * a heavy collapse crash plays. A big collapse near the viewer also jolts the camera.
     */
    private static void landed(State st, Falling f, Vec3 cam) {
        Minecraft mc = Minecraft.getInstance();
        double d2 = cam.distanceToSqr(f.pos);
        if (d2 < 40 * 40 && st.impactSounds < 6 && mc.level != null) {
            st.impactSounds++;
            var type = f.state.getSoundType();
            mc.level.playLocalSound(f.pos.x, f.pos.y, f.pos.z, type.getBreakSound(), SoundSource.BLOCKS,
                    0.6f + RANDOM.nextFloat() * 0.3f, type.getPitch() * (0.6f + RANDOM.nextFloat() * 0.2f), false);
        }
        long key = BlockPos.asLong(Mth.floor(f.pos.x) >> 3, Mth.floor(f.pos.y) >> 3, Mth.floor(f.pos.z) >> 3);
        long[] cell = st.landingCells.computeIfAbsent(key, k -> new long[]{0, st.ticks, -1000});
        if (st.ticks - cell[1] > 40) {
            cell[0] = 0;
            cell[1] = st.ticks;
        }
        cell[0]++;
        if (cell[0] >= 4 && st.ticks - cell[2] > 50 && d2 < 160 * 160) {
            cell[2] = st.ticks;
            mc.getSoundManager().play(new SimpleSoundInstance(ModRegistries.COLLAPSE_CRASH.get().location(), SoundSource.BLOCKS,
                    Math.min(4f, 1.6f + cell[0] * 0.15f), 0.8f + RANDOM.nextFloat() * 0.2f, RANDOM, false, 0,
                    SoundInstance.Attenuation.LINEAR, f.pos.x, f.pos.y, f.pos.z, false));
            if (d2 < 24 * 24) EarthquakeCamera.impact(0.3f);
        }
        if (st.landingCells.size() > 256) st.landingCells.entrySet().removeIf(e -> st.ticks - e.getValue()[1] > 200);
    }

    /** Leaves shaken out of trees and water slopping about in ponds near the viewer. */
    private static void shakeSurroundings(ClientLevel level, Vec3 cam, float local, float density) {
        if (local < 0.15f) return;
        int samples = count(40 * local * density);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int i = 0; i < samples; i++) {
            m.set(cam.x + (RANDOM.nextDouble() - 0.5) * 32, cam.y + (RANDOM.nextDouble() - 0.4) * 16, cam.z + (RANDOM.nextDouble() - 0.5) * 32);
            BlockState bs = level.getBlockState(m);
            if (bs.is(BlockTags.LEAVES)) {
                // Vanilla's own (per-leaf-type) falling-leaf particles, just much more often while the tree shakes.
                for (int k = 0; k < 4; k++) bs.getBlock().animateTick(bs, level, m, RANDOM);
            } else if (bs.getFluidState().isSource() && bs.getFluidState().is(net.minecraft.tags.FluidTags.WATER)
                    && level.getBlockState(m.above()).isAir() && RANDOM.nextFloat() < local) {
                for (int k = 0; k < 3; k++) {
                    level.addParticle(ParticleTypes.SPLASH, m.getX() + RANDOM.nextDouble(), m.getY() + 0.95, m.getZ() + RANDOM.nextDouble(),
                            (RANDOM.nextDouble() - 0.5) * 0.2, 0.1 + RANDOM.nextDouble() * 0.15 * local, (RANDOM.nextDouble() - 0.5) * 0.2);
                }
            }
        }
    }

    private static BlockState groundAt(ClientLevel level, double x, double z) {
        int ix = Mth.floor(x), iz = Mth.floor(z);
        if (!level.hasChunk(ix >> 4, iz >> 4)) return null;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, ix, iz) - 1;
        return level.getBlockState(new BlockPos(ix, y, iz));
    }

    private static boolean looseGround(BlockState bs) {
        return bs.is(BlockTags.SAND) || bs.is(net.minecraft.world.level.block.Blocks.GRAVEL)
                || bs.is(net.minecraft.world.level.block.Blocks.COARSE_DIRT) || bs.is(net.minecraft.world.level.block.Blocks.DIRT)
                || bs.is(net.minecraft.world.level.block.Blocks.DIRT_PATH) || bs.is(BlockTags.TERRACOTTA)
                || bs.is(net.minecraft.world.level.block.Blocks.SUSPICIOUS_GRAVEL) || bs.is(net.minecraft.world.level.block.Blocks.SUSPICIOUS_SAND);
    }

    private static void spawnDust(ClientLevel level, Frustum frustum, double x, double z, double up, double side) {
        int ix = Mth.floor(x), iz = Mth.floor(z);
        if (!level.hasChunk(ix >> 4, iz >> 4)) return;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, ix, iz);
        if (frustum != null && !frustum.isVisible(new AABB(x - 2, y - 1, z - 2, x + 2, y + 3, z + 2))) return;
        level.addParticle(ModRegistries.QUAKE_DUST.get(), true, true, x, y + 0.2, z,
                (RANDOM.nextDouble() - 0.5) * side, up, (RANDOM.nextDouble() - 0.5) * side);
    }

    /** A real block-texture chip from the ground at (x, z), thrown upward. */
    private static void spawnChip(ClientLevel level, double x, double z, double up) {
        int ix = Mth.floor(x), iz = Mth.floor(z);
        if (!level.hasChunk(ix >> 4, iz >> 4)) return;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, ix, iz) - 1;
        BlockState bs = level.getBlockState(new BlockPos(ix, y, iz));
        if (bs.isAir()) return;
        level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, bs), x, y + 1.05, z,
                (RANDOM.nextDouble() - 0.5) * 0.2, up * (0.6 + RANDOM.nextDouble() * 0.8), (RANDOM.nextDouble() - 0.5) * 0.2);
    }

    private static int count(float expected) {
        int n = (int) expected;
        if (RANDOM.nextFloat() < expected - n) n++;
        return n;
    }

    private void tickSounds(ClientDisaster d, State st, Vec3 cam) {
        Minecraft mc = Minecraft.getInstance();
        var sounds = mc.getSoundManager();
        if (st.close == null || st.close.isStopped()) {
            // Close layer: grinding, rattling rumble that comes from the ground all around you inside the zone.
            st.close = new DisasterLoopSound(ModRegistries.QUAKE_RUMBLE.get(), d, false,
                    x -> {
                        double dist = horizontalDistance(x, camPos());
                        float r = radius(x);
                        return tremor(x) * (1 - smoothstep(r * 0.8f, r * 2f, (float) dist)) * (0.6 + 0.12 * x.intensity);
                    },
                    x -> 0.8 + 0.25 * tremor(x));
            sounds.play(st.close);
        }
        if (st.distant == null || st.distant.isStopped()) {
            // Distant layer: deep low rumble that carries far and sits under the close layer.
            st.distant = new DisasterLoopSound(ModRegistries.QUAKE_DISTANT.get(), d, false,
                    x -> {
                        double dist = horizontalDistance(x, camPos());
                        float r = radius(x);
                        float t = Math.max(tremor(x), x.strength() * 0.15f);
                        return t * (0.45 + 0.1 * x.intensity) * (1 - smoothstep(r * 2f, r * 6f + 160f, (float) dist));
                    },
                    x -> 0.9 + 0.1 * tremor(x));
            sounds.play(st.distant);
        }

        double dist = horizontalDistance(d, cam);
        float radius = radius(d);
        float tremor = tremor(d);
        // Warning signs: low rumbles rolling in from the epicentre's direction.
        if (d.phase == Phase.WARNING && --st.nextRumble <= 0) {
            st.nextRumble = 120 + RANDOM.nextInt(160);
            Vec3 dir = d.pos().subtract(cam);
            Vec3 at = cam.add(dir.lengthSqr() < 1 ? Vec3.ZERO : dir.normalize().scale(Math.min(dir.length(), 24)));
            sounds.play(new SimpleSoundInstance(ModRegistries.DISTANT_RUMBLE.get().location(), SoundSource.WEATHER,
                    0.4f + 0.3f * d.strength() / 0.2f, 0.55f + RANDOM.nextFloat() * 0.2f, RANDOM, false, 0,
                    SoundInstance.Attenuation.NONE, at.x, at.y, at.z, false));
        }
        // Cracking rock where fissures tear open.
        float cracking = d.data(EarthquakeDisaster.D_CRACKING);
        int tips = Math.min(EarthquakeDisaster.MAX_SYNCED_TIPS, Math.round(d.data(EarthquakeDisaster.D_TIP_COUNT)));
        if (cracking > 0.3f && tips > 0 && dist < radius * 3 && --st.nextCrack <= 0) {
            st.nextCrack = 8 + RANDOM.nextInt(20);
            int t = RANDOM.nextInt(tips);
            double tx = d.pos().x + d.data(EarthquakeDisaster.D_TIPS + t * 2);
            double tz = d.pos().z + d.data(EarthquakeDisaster.D_TIPS + t * 2 + 1);
            sounds.play(new SimpleSoundInstance(ModRegistries.QUAKE_CRACK.get().location(), SoundSource.WEATHER,
                    1.5f + 0.3f * d.intensity, 0.7f + RANDOM.nextFloat() * 0.4f, RANDOM, false, 0,
                    SoundInstance.Attenuation.LINEAR, tx, d.pos().y, tz, false));
        }
        // Creaks and pops close by while the ground moves under you.
        if (dist < radius && tremor > 0.35f && --st.nextCreak <= 0) {
            st.nextCreak = 25 + RANDOM.nextInt(60);
            double a = RANDOM.nextDouble() * Math.PI * 2;
            sounds.play(new SimpleSoundInstance(ModRegistries.QUAKE_CRACK.get().location(), SoundSource.WEATHER,
                    0.25f + 0.35f * tremor, 1.1f + RANDOM.nextFloat() * 0.4f, RANDOM, false, 0,
                    SoundInstance.Attenuation.NONE, cam.x + Math.cos(a) * 5, cam.y - 1, cam.z + Math.sin(a) * 5, false));
        }
    }

    @Override
    public void onRemoved(ClientDisaster d) {
        if (d.visualState instanceof State st) {
            if (st.close != null) st.close.end();
            if (st.distant != null) st.distant.end();
        }
    }

    // ------------------------------------------------------------------ math

    private static Vec3 camPos() {
        return Minecraft.getInstance().gameRenderer.getMainCamera().position();
    }

    private static double sq(double v) {
        return v * v;
    }

    static double horizontalDistance(ClientDisaster d, Vec3 cam) {
        double dx = cam.x - d.pos().x, dz = cam.z - d.pos().z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    static float smoothstep(float e0, float e1, float x) {
        float t = Mth.clamp((x - e0) / (e1 - e0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }
}
