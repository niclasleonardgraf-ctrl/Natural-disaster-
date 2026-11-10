package com.realisticdisasters.client.volcano;

import java.util.ArrayList;
import java.util.List;

import com.realisticdisasters.client.ClientDisaster;
import com.realisticdisasters.client.ClientDisasters;
import com.realisticdisasters.client.DisasterVisual;
import com.realisticdisasters.client.sound.DisasterLoopSound;
import com.realisticdisasters.config.ClientConfig;
import com.realisticdisasters.disaster.DisasterType;
import com.realisticdisasters.disaster.Phase;
import com.realisticdisasters.disaster.volcano.LavaBombEntity;
import com.realisticdisasters.disaster.volcano.VolcanoDisaster;
import com.realisticdisasters.registry.ModRegistries;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Volcano presentation. The big clouds (eruption column, umbrella cloud, fall-out, pyroclastic flows, steam and smoke
 * puffs) are volumetric voxel clouds rendered like Minecraft's own clouds ({@link VoxelCloud}); incandescent spatter
 * and fine ash fall are particles. Plus the ash-darkened sky, distance-scaled tremor and blast jolts, and layered sound
 * where blasts arrive after the flash with the speed of sound.
 */
public final class VolcanoVisual implements DisasterVisual {
    private static final int SKY = 0x6A5F55;     // ash-dimmed daylight: warm brown-grey
    private static final int FOG = 0x5E554C;
    private static final int CLOUDS = 0x463F39;
    private static final float SOUND_SPEED = 17f; // blocks per tick (~343 m/s)

    private static final RandomSource RANDOM = RandomSource.create();

    static final class State {
        VoxelCloud cloud;
        DisasterLoopSound rumble, distant;
        FrontSound pdcSound;
        int nextRumble = 30;
        int lastBlasts = -1;
        float blastShake;
        final List<int[]> pendingBlasts = new ArrayList<>(); // {ticks until heard}
    }

    // ------------------------------------------------------------------ shape helpers

    static Vec3 vent(ClientDisaster d) {
        Vec3 p = d.pos();
        return new Vec3(p.x, p.y + d.data(VolcanoDisaster.D_VENT_HEIGHT), p.z);
    }

    static float plumeHeight(ClientDisaster d) {
        return Math.max(30f, d.data(VolcanoDisaster.D_PLUME_HEIGHT));
    }

    static float ashRadius(ClientDisaster d) {
        return Math.max(40f, d.data(VolcanoDisaster.D_ASH_RADIUS));
    }

    static Vec3 wind(ClientDisaster d) {
        float wx = d.data(VolcanoDisaster.D_WIND_X), wz = d.data(VolcanoDisaster.D_WIND_Z);
        float len = Mth.sqrt(wx * wx + wz * wz);
        return len < 1.0e-3f ? new Vec3(1, 0, 0) : new Vec3(wx / len, 0, wz / len);
    }

    /** How much of the eruption column exists (grows through the buildup, collapses in the aftermath). */
    static float column(ClientDisaster d) {
        return switch (d.phase) {
            case WARNING -> 0f;
            case BUILDUP -> Mth.clamp((d.phaseProgress - 0.3f) / 0.7f, 0f, 1f);
            case PEAK -> 1f;
            case AFTERMATH -> 1f - Mth.clamp(d.phaseProgress * 1.2f, 0f, 1f);
        };
    }

    /** Ash fallout is centred downwind of the vent. */
    static Vec3 ashCenter(ClientDisaster d) {
        return d.pos().add(wind(d).scale(ashRadius(d) * 0.35));
    }

    private static boolean pdcActive(ClientDisaster d) {
        return d.data.length > VolcanoDisaster.D_PDC_HEAD && d.data[VolcanoDisaster.D_PDC_ON] > 0.5f;
    }

    /** The point of the pyroclastic flow's front (or body) nearest to the camera. */
    private static Vec3 nearestFront(ClientDisaster d, Vec3 cam) {
        double rf = d.data[VolcanoDisaster.D_PDC_RADIUS], a0 = d.data[VolcanoDisaster.D_PDC_ANGLE];
        double half = d.data[VolcanoDisaster.D_PDC_WIDTH];
        Vec3 o = d.pos();
        double a = Math.atan2(cam.z - o.z, cam.x - o.x);
        double diff = Mth.wrapDegrees((float) Math.toDegrees(a - a0));
        double lim = Math.toDegrees(half);
        double clamped = Math.toRadians(Mth.clamp(diff, -lim, lim)) + a0;
        double rCam = Math.hypot(cam.x - o.x, cam.z - o.z);
        double r = Math.min(rf, rCam);
        return new Vec3(o.x + Math.cos(clamped) * r, cam.y, o.z + Math.sin(clamped) * r);
    }

    // ------------------------------------------------------------------ atmosphere

    @Override
    public void contribute(ClientDisaster d, Vec3 cam, Atmosphere out) {
        float s = d.strength();
        float ashR = ashRadius(d);
        double ashDist = horizontal(ashCenter(d), cam);
        float under = 1f - smoothstep(ashR * 0.55f, ashR * 1.35f, (float) ashDist);
        // The ash cloud keeps hanging around a while after the eruption weakens.
        float ash = Math.max(s, column(d) * 0.8f) * under;
        // Ash only dims what light there is: scale the tint with daylight so nights stay dark.
        float day = daylight();
        out.sky(scaled(SKY, day), ash);
        out.clouds(scaled(CLOUDS, day), ash);
        out.fogColor(scaled(FOG, day), ash);
        out.darken(ash * (0.3f + d.intensity * 0.08f));
        // Ash fall cuts visibility, but a looming mountain stays visible through it.
        float fogEnd = 110f + (5 - d.intensity) * 25f + (float) ashDist * 0.3f;
        out.fog(fogEnd, ash * (d.phase == Phase.WARNING ? 0.25f : 0.6f));

        double dist = horizontal(d.pos(), cam);
        float i5 = d.intensity / 5f;
        float size = 50f + d.intensity * 12f + d.data(VolcanoDisaster.D_CONE_RADIUS);
        float near = 1f - smoothstep(6f, size, (float) dist);
        float far = 1f - smoothstep(size, size * 4f + 100f, (float) dist);
        float blast = d.visualState instanceof State st ? st.blastShake : 0f;
        float pdc = 0f;
        if (pdcActive(d)) {
            double df = horizontal(nearestFront(d, cam), cam);
            pdc = (1f - smoothstep(10f, 160f, (float) df)) * (0.4f + i5 * 0.6f);
            // Engulfed: the ash cloud swallows the light.
            if (df < 12) out.fog(6f, 1f - (float) df / 12f);
        }
        // Constant volcanic tremor (also during the warning) plus jolts from blasts and the roaring flow.
        out.shake(s * i5 * (near * 0.45f + far * 0.12f) + blast + pdc * 0.7f);
        out.fovPulse(Math.min(1f, blast * 1.2f + pdc) * Math.max(near, pdc));
    }

    /** 0..1 from the dimension's current sky light (vanilla day/night cycle and weather). */
    static float daylight() {
        var level = Minecraft.getInstance().level;
        if (level == null) return 1f;
        float sky = level.environmentAttributes().getDimensionValue(EnvironmentAttributes.SKY_LIGHT_LEVEL);
        return Mth.clamp((sky - 4f) / 11f, 0.08f, 1f);
    }

    private static int scaled(int rgb, float k) {
        int r = (int) (((rgb >> 16) & 255) * k), g = (int) (((rgb >> 8) & 255) * k), b = (int) ((rgb & 255) * k);
        return (r << 16) | (g << 8) | b;
    }

    // ------------------------------------------------------------------ particles + sound

    @Override
    public void tick(ClientDisaster d, ClientLevel level, Camera camera) {
        State st = d.visualState instanceof State s ? s : new State();
        d.visualState = st;
        if (st.cloud == null) st.cloud = new VoxelCloud(d.intensity, d.id * 31L + 7);
        Vec3 cam = camera.position();
        tickBlasts(d, st, level, cam);
        tickSounds(d, st, cam);
        spawnEffects(d, st, level, cam);
        st.cloud.tick(d, level, daylight());
    }

    static float density() {
        float density = ClientConfig.PARTICLE_DENSITY.get().floatValue();
        ParticleStatus status = Minecraft.getInstance().options.particles().get();
        return density * switch (status) {
            case ALL -> 1f;
            case DECREASED -> 0.5f;
            case MINIMAL -> 0.2f;
        };
    }

    private static SparkParticle spark(ClientLevel level, double x, double y, double z, double xd, double yd, double zd,
                                       float size, float heat, int life) {
        SparkParticle p = new SparkParticle(level, x, y, z, xd, yd, zd, size, heat, life, SparkParticle.sprites);
        Minecraft.getInstance().particleEngine.add(p);
        return p;
    }

    private void spawnEffects(ClientDisaster d, State st, ClientLevel level, Vec3 cam) {
        float density = density();
        Vec3 vent = vent(d);
        Vec3 wind = wind(d);
        float col = column(d);
        float s = d.strength();
        int i = d.intensity;
        double dist = horizontal(d.pos(), cam);
        float lod = dist < 128 ? 1f : dist < 256 ? 0.65f : dist < 420 ? 0.4f : 0.2f;
        VoxelCloud cloud = st.cloud;

        // Warning: white steam and gas from the vent and the opening fissures.
        if (d.phase == Phase.WARNING && RANDOM.nextFloat() < 0.15f + s) {
            cloud.puff(vent.x + RANDOM.nextGaussian() * 2, vent.y, vent.z + RANDOM.nextGaussian() * 2,
                    wind.x * 0.02, 0.15 + RANDOM.nextDouble() * 0.1, wind.z * 0.02,
                    1f + RANDOM.nextFloat(), 0.03f, 60 + RANDOM.nextInt(40), 0.85f, 0.85f, 0.86f, 0f);
        }

        if (SparkParticle.sprites != null && density > 0 && dist < 520 && col > 0.05f) {
            float erupt = s * (d.phase == Phase.PEAK ? 1f : 0.45f);
            // Incandescent lava fountain: a dense jet of spatter; far away fewer but larger clots stay readable.
            float sparkScale = (float) Math.max(1.0, dist / 70.0);
            double fountainH = (5 + i * 6) * erupt;
            if (fountainH > 1) {
                double v0 = Math.sqrt(2 * 0.04 * fountainH);
                int sparks = count((10 + i * 8) * erupt * density * lod / Math.min(sparkScale, 3f));
                for (int n = 0; n < sparks; n++) {
                    boolean core = RANDOM.nextFloat() < 0.45f;
                    double vy = v0 * (core ? 0.75 + RANDOM.nextDouble() * 0.3 : 0.45 + RANDOM.nextDouble() * 0.55);
                    double jet = core ? 0.05 : 0.13;
                    float size = (core ? 0.5f + RANDOM.nextFloat() * 0.6f : 0.12f + RANDOM.nextFloat() * 0.25f) * sparkScale;
                    spark(level, vent.x + RANDOM.nextGaussian() * 0.4, vent.y + 0.2, vent.z + RANDOM.nextGaussian() * 0.4,
                            RANDOM.nextGaussian() * v0 * jet, vy, RANDOM.nextGaussian() * v0 * jet,
                            size, 0.9f + RANDOM.nextFloat() * 0.1f, (int) (2 * vy / 0.04) + 10)
                            .coolingScale(core ? 0.45f : 0.7f);
                }
            }
        }

        if (dist < 260) spawnFumes(d, cloud, level, wind, density, lod);
        spawnAshFall(d, level, cam, wind, col, density);
    }

    /**
     * Gas off fresh lava and glowing crust, and big white steam clouds where lava meets water (littoral plumes).
     * Samples the ground around the vent and along the flows.
     */
    private static void spawnFumes(ClientDisaster d, VoxelCloud cloud, ClientLevel level, Vec3 wind, float density, float lod) {
        int samples = count((8 + d.intensity * 4) * Math.max(0.3f, density) * lod);
        double range = 14 + d.intensity * 10 + d.data(VolcanoDisaster.D_CONE_RADIUS) * 1.3;
        Vec3 base = d.pos();
        for (int n = 0; n < samples; n++) {
            int x = Mth.floor(base.x + (RANDOM.nextDouble() - 0.5) * 2 * range);
            int z = Mth.floor(base.z + (RANDOM.nextDouble() - 0.5) * 2 * range);
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
            BlockPos p = new BlockPos(x, y, z);
            boolean lava = level.getFluidState(p).is(FluidTags.LAVA);
            boolean crust = !lava && level.getBlockState(p).is(Blocks.MAGMA_BLOCK);
            if (!lava && !crust) continue;
            boolean water = false;
            if (lava) {
                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    if (level.getFluidState(p.relative(dir)).is(FluidTags.WATER)) {
                        water = true;
                        break;
                    }
                }
            }
            if (water) {
                // Lava entering water: a violent, billowing white steam plume.
                cloud.puff(x + 0.5, y + 1.2, z + 0.5, wind.x * 0.03, 0.35 + RANDOM.nextDouble() * 0.2, wind.z * 0.03,
                        2f + RANDOM.nextFloat() * 2f, 0.06f, 70 + RANDOM.nextInt(40), 0.93f, 0.93f, 0.95f, 0f);
            } else if (RANDOM.nextFloat() < (lava ? 0.5f : 0.2f)) {
                float shade = lava ? 0.78f : 0.7f;
                cloud.puff(x + RANDOM.nextDouble(), y + 1.1, z + RANDOM.nextDouble(), wind.x * 0.02, 0.08, wind.z * 0.02,
                        0.6f + RANDOM.nextFloat() * 0.8f, 0.025f, 40 + RANDOM.nextInt(30), shade, shade * 0.98f, shade * 0.96f,
                        lava ? 0.25f : 0.1f);
            }
            if (lava && SparkParticle.sprites != null && RANDOM.nextFloat() < 0.15f * density) {
                spark(level, x + RANDOM.nextDouble(), y + 1.0, z + RANDOM.nextDouble(),
                        RANDOM.nextGaussian() * 0.03, 0.12 + RANDOM.nextDouble() * 0.12, RANDOM.nextGaussian() * 0.03,
                        0.1f + RANDOM.nextFloat() * 0.12f, 0.8f, 15 + RANDOM.nextInt(15));
            }
        }
    }

    /** Fine ash drifting down around the viewer under the ash cloud (only where it can reach: open sky). */
    private static void spawnAshFall(ClientDisaster d, ClientLevel level, Vec3 cam, Vec3 wind, float col, float density) {
        if (density <= 0) return;
        float ashR = ashRadius(d);
        double ashDist = horizontal(ashCenter(d), cam);
        float under = (1f - smoothstep(ashR * 0.5f, ashR * 1.2f, (float) ashDist)) * Math.max(d.strength(), col * 0.8f);
        if (under <= 0.02f || d.phase == Phase.WARNING) return;
        int flakes = count(under * (12 + d.intensity * 8) * density);
        for (int n = 0; n < flakes; n++) {
            double x = cam.x + (RANDOM.nextDouble() - 0.5) * 40, z = cam.z + (RANDOM.nextDouble() - 0.5) * 40;
            double y = cam.y + 4 + RANDOM.nextDouble() * 14;
            if (y < level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(x), Mth.floor(z))) continue;
            level.addParticle(ModRegistries.ASH_FALL.get(), x, y, z,
                    wind.x * 0.03, -0.05 - RANDOM.nextDouble() * 0.04, wind.z * 0.03);
        }
    }

    /** Glowing spark arc and a smoke trail behind a flying lava bomb (called from the bomb's client tick). */
    public static void bombTrail(LavaBombEntity bomb) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        float density = density();
        double dist = mc.gameRenderer.getMainCamera().position().distanceTo(bomb.position());
        float lod = dist < 64 ? 1f : dist < 160 ? 0.6f : 0.3f;
        float scale = (float) Math.max(1.0, dist / 60.0);
        Vec3 p = bomb.position().add(0, 0.3, 0);
        Vec3 v = bomb.getDeltaMovement();
        if (SparkParticle.sprites != null && density > 0) {
            int sparks = count(3f * density * lod);
            for (int n = 0; n < sparks; n++) {
                double t = RANDOM.nextDouble();
                spark(mc.level, p.x - v.x * t, p.y - v.y * t, p.z - v.z * t,
                        v.x * 0.15 + RANDOM.nextGaussian() * 0.02, v.y * 0.15 + RANDOM.nextGaussian() * 0.02, v.z * 0.15 + RANDOM.nextGaussian() * 0.02,
                        (0.16f + RANDOM.nextFloat() * 0.16f) * scale, 0.75f + RANDOM.nextFloat() * 0.2f, 10 + RANDOM.nextInt(14));
            }
        }
        if (bomb.tickCount % 2 == 0) {
            VoxelCloud cloud = nearestCloud(p);
            if (cloud != null) {
                cloud.puff(p.x, p.y, p.z, 0, 0.02, 0, 0.45f, 0.03f, 18 + RANDOM.nextInt(10), 0.2f, 0.19f, 0.18f, 0.2f);
            }
        }
    }

    private static VoxelCloud nearestCloud(Vec3 p) {
        VoxelCloud best = null;
        double bd = Double.MAX_VALUE;
        for (ClientDisaster d : ClientDisasters.all()) {
            if (d.type != DisasterType.VOLCANO || !(d.visualState instanceof State st) || st.cloud == null) continue;
            double dd = d.pos().distanceToSqr(p);
            if (dd < bd) {
                bd = dd;
                best = st.cloud;
            }
        }
        return best;
    }

    /** A Vulcanian / Strombolian blast: dark ash jets punching out of the crater and a shower of glowing ejecta. */
    private static void blastEjecta(ClientDisaster d, State st, ClientLevel level, double dist) {
        Vec3 vent = vent(d);
        int i = d.intensity;
        float density = density();
        if (SparkParticle.sprites != null && density > 0 && dist < 600) {
            float scale = (float) Math.max(1.0, dist / 70.0);
            int sparks = count((50 + 30 * i) * density / Math.min(scale, 3f));
            for (int n = 0; n < sparks; n++) {
                double a = RANDOM.nextDouble() * Math.PI * 2;
                double tilt = RANDOM.nextDouble() * 0.9;
                double sp = 0.6 + RANDOM.nextDouble() * (0.6 + i * 0.15);
                spark(level, vent.x, vent.y + 0.5, vent.z, Math.cos(a) * Math.sin(tilt) * sp, Math.cos(tilt) * sp,
                        Math.sin(a) * Math.sin(tilt) * sp, (0.2f + RANDOM.nextFloat() * 0.35f) * scale,
                        0.9f + RANDOM.nextFloat() * 0.1f, 40 + RANDOM.nextInt(50));
            }
        }
        // Cock's-tail jets of dark, ash-laden gas.
        for (int n = 0; n < 12 + 5 * i; n++) {
            double a = RANDOM.nextDouble() * Math.PI * 2;
            double out = RANDOM.nextDouble() * 0.5;
            double up = 0.9 + RANDOM.nextDouble() * (0.6 + i * 0.15);
            float shade = 0.16f + RANDOM.nextFloat() * 0.08f;
            st.cloud.puff(vent.x, vent.y + 1, vent.z, Math.cos(a) * out, up, Math.sin(a) * out,
                    1.5f + RANDOM.nextFloat() * (1 + i * 0.5f), 0.12f + i * 0.02f, 50 + RANDOM.nextInt(40),
                    shade, shade * 0.95f, shade * 0.9f, 0.4f);
        }
    }

    private void tickBlasts(ClientDisaster d, State st, ClientLevel level, Vec3 cam) {
        st.blastShake *= 0.9f;
        int blasts = d.data.length > VolcanoDisaster.D_BLASTS ? Math.round(d.data[VolcanoDisaster.D_BLASTS]) : 0;
        double dist = cam.distanceTo(vent(d));
        if (st.lastBlasts < 0) {
            st.lastBlasts = blasts;
        } else if (blasts > st.lastBlasts) {
            st.lastBlasts = blasts;
            blastEjecta(d, st, level, dist);
            // Light is instant, sound and the pressure wave travel: delay by distance.
            st.pendingBlasts.add(new int[]{(int) Math.min(80, dist / SOUND_SPEED)});
        }
        var it = st.pendingBlasts.iterator();
        while (it.hasNext()) {
            int[] b = it.next();
            if (--b[0] > 0) continue;
            it.remove();
            float near = 1f - smoothstep(20f, 280f + d.intensity * 40f, (float) dist);
            st.blastShake = Math.max(st.blastShake, (0.25f + d.intensity * 0.15f) * near);
            float volume = 0.15f + 0.85f * (1f - smoothstep(30f, 700f, (float) dist));
            playAt(d, cam, ModRegistries.VOLCANO_BLAST.get(), volume, 0.75f + RANDOM.nextFloat() * 0.2f);
        }
    }

    private void tickSounds(ClientDisaster d, State st, Vec3 cam) {
        var sounds = Minecraft.getInstance().getSoundManager();
        if (st.rumble == null || st.rumble.isStopped()) {
            // Close layer: positional roaring rumble of the vent.
            st.rumble = new DisasterLoopSound(ModRegistries.VOLCANO_RUMBLE.get(), d, true,
                    x -> x.strength() * (1.5 + x.intensity * 0.4),
                    x -> 0.8 + 0.04 * (5 - x.intensity));
            sounds.play(st.rumble);
        }
        if (st.distant == null || st.distant.isStopped()) {
            // Distant layer: deep sub-rumble that is felt far away and already during the warning.
            st.distant = new DisasterLoopSound(ModRegistries.VOLCANO_DISTANT.get(), d, false,
                    x -> {
                        double dist = horizontal(x.pos(), Minecraft.getInstance().gameRenderer.getMainCamera().position());
                        float s = Math.max(x.strength(), x.phase == Phase.WARNING ? 0.35f * x.phaseProgress : 0f);
                        return s * (1 - smoothstep(30f, 700f, (float) dist)) * (0.5 + 0.1 * x.intensity);
                    },
                    x -> 1.0);
            sounds.play(st.distant);
        }
        if (pdcActive(d) && (st.pdcSound == null || st.pdcSound.isStopped())) {
            st.pdcSound = new FrontSound(d.id);
            sounds.play(st.pdcSound);
        }
        // Warning signs: deep rumbles while the magma rises.
        if (d.phase == Phase.WARNING || d.phase == Phase.BUILDUP) {
            if (--st.nextRumble <= 0) {
                st.nextRumble = 80 + RANDOM.nextInt(120);
                playAt(d, cam, ModRegistries.DISTANT_RUMBLE.get(), 0.6f + 0.4f * d.strength(), 0.5f + RANDOM.nextFloat() * 0.2f);
            }
        }
    }

    /** The roar of a pyroclastic flow, placed at the part of its front nearest to the listener. */
    private static final class FrontSound extends AbstractTickableSoundInstance {
        private final int disasterId;
        private float current;

        FrontSound(int disasterId) {
            super(ModRegistries.VOLCANO_RUMBLE.get(), SoundSource.WEATHER, RandomSource.create());
            this.disasterId = disasterId;
            this.looping = true;
            this.volume = 0.0001f;
            this.pitch = 0.6f;
            this.attenuation = SoundInstance.Attenuation.LINEAR;
        }

        @Override
        public void tick() {
            ClientDisaster d = ClientDisasters.get(disasterId);
            boolean on = d != null && pdcActive(d);
            current += ((on ? 3.5f : 0f) - current) * 0.08f;
            if (!on && current < 0.02f) {
                stop();
                return;
            }
            volume = Math.max(0.0001f, current);
            if (d != null && on) {
                Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().position();
                Vec3 f = nearestFront(d, cam);
                x = f.x;
                y = cam.y;
                z = f.z;
            }
        }

        void end() {
            stop();
        }

        @Override
        public boolean canStartSilent() {
            return true;
        }
    }

    /** Non-attenuated one-shot placed toward the volcano, so it has direction but stays audible far away. */
    private static void playAt(ClientDisaster d, Vec3 cam, SoundEvent sound, float volume, float pitch) {
        Vec3 dir = d.pos().subtract(cam);
        Vec3 at = cam.add(dir.normalize().scale(Math.min(dir.length(), 16)));
        Minecraft.getInstance().getSoundManager().play(new SimpleSoundInstance(sound.location(), SoundSource.WEATHER,
                Mth.clamp(volume, 0f, 1f), pitch, RANDOM, false, 0, SoundInstance.Attenuation.NONE, at.x, at.y, at.z, false));
    }

    @Override
    public void onRemoved(ClientDisaster d) {
        if (d.visualState instanceof State st) {
            if (st.rumble != null) st.rumble.end();
            if (st.distant != null) st.distant.end();
            if (st.pdcSound != null) st.pdcSound.end();
        }
    }

    // ------------------------------------------------------------------ math

    private static int count(float expected) {
        int n = (int) expected;
        if (RANDOM.nextFloat() < expected - n) n++;
        return n;
    }

    static double horizontal(Vec3 a, Vec3 b) {
        double dx = a.x - b.x, dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    static float smoothstep(float e0, float e1, float x) {
        float t = Mth.clamp((x - e0) / (e1 - e0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }
}
