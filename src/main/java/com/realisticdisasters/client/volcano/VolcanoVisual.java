package com.realisticdisasters.client.volcano;

import java.util.ArrayList;
import java.util.List;

import com.realisticdisasters.client.ClientDisaster;
import com.realisticdisasters.client.DisasterVisual;
import com.realisticdisasters.client.sound.DisasterLoopSound;
import com.realisticdisasters.config.ClientConfig;
import com.realisticdisasters.disaster.Phase;
import com.realisticdisasters.disaster.volcano.LavaBombEntity;
import com.realisticdisasters.disaster.volcano.VolcanoDisaster;
import com.realisticdisasters.registry.ModRegistries;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Volcano presentation, modelled on real eruption footage (Kīlauea fountains, Strombolian/Vulcanian bursts,
 * Sakurajima / Taal ash columns):
 * <ul>
 *   <li>an eruption column that is a narrow, dark, fast jet at the vent and widens with height as it entrains air,
 *   built from rising, swelling, churning billows with dark undersides and lighter sunlit crowns;</li>
 *   <li>an umbrella cloud spreading at neutral buoyancy and drifting far downwind, with grey fall-out curtains
 *   hanging beneath it;</li>
 *   <li>an incandescent lava fountain whose spatter cools from yellow to red to black in flight, lighting the base of
 *   the column orange;</li>
 *   <li>blasts that throw dark ash jets and a shower of glowing ejecta, with sound arriving after the flash;</li>
 *   <li>glowing spark arcs behind lava bombs, fumes rising off fresh lava flows, falling ash, a dim brown-grey sky.</li>
 * </ul>
 */
public final class VolcanoVisual implements DisasterVisual {
    private static final int SKY = 0x6A5F55;     // ash-dimmed daylight: warm brown-grey
    private static final int FOG = 0x5E554C;
    private static final int CLOUDS = 0x463F39;
    private static final float SOUND_SPEED = 17f; // blocks per tick (~343 m/s)

    private static final RandomSource RANDOM = RandomSource.create();

    private static final class State {
        DisasterLoopSound rumble, distant;
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
            case AFTERMATH -> 1f - Mth.clamp(d.phaseProgress * 1.3f, 0f, 1f);
        };
    }

    /** Ash fallout is centred downwind of the vent. */
    static Vec3 ashCenter(ClientDisaster d) {
        return d.pos().add(wind(d).scale(ashRadius(d) * 0.35));
    }

    /** Column geometry: radius at height h above the vent (jet + ~8° entrainment spread). */
    private static double columnRadius(int intensity, double h) {
        return 1.5 + intensity * 0.5 + 0.2 * h;
    }

    /** Horizontal offset of the column axis at height h: it bends over in the wind, more strongly aloft. */
    private static Vec3 lean(Vec3 wind, double h, double height) {
        return wind.scale(0.35 * h * h / height);
    }

    /** Ambient wind speed at height h (stronger aloft). */
    private static double windSpeed(double h, double height) {
        return 0.03 + 0.14 * Mth.clamp(h / height, 0, 1.2);
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
        float fogEnd = 40f + (5 - d.intensity) * 18f + (float) ashDist * 0.3f;
        out.fog(fogEnd, ash * (d.phase == Phase.WARNING ? 0.3f : 0.75f));

        double dist = horizontal(d.pos(), cam);
        float i5 = d.intensity / 5f;
        float near = 1f - smoothstep(6f, 50f + d.intensity * 12f, (float) dist);
        float far = 1f - smoothstep(50f, 260f, (float) dist);
        float blast = d.visualState instanceof State st ? st.blastShake : 0f;
        // Constant volcanic tremor (also during the warning) plus jolts from each blast.
        out.shake(s * i5 * (near * 0.45f + far * 0.1f) + blast);
        out.fovPulse(Math.min(1f, blast * 1.2f) * near);
    }

    /** 0..1 from the dimension's current sky light (vanilla day/night cycle and weather). */
    private static float daylight() {
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
        Vec3 cam = camera.position();
        tickBlasts(d, st, level, cam);
        tickSounds(d, st, cam);
        spawnParticles(d, level, camera, cam);
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

    private static boolean ready() {
        return AshParticle.cloudSprites != null && AshParticle.curtainSprites != null && SparkParticle.sprites != null;
    }

    private static AshParticle ash(ClientLevel level, AshParticle.Kind kind, double x, double y, double z,
                                   double xd, double yd, double zd) {
        var sprites = kind == AshParticle.Kind.CURTAIN ? AshParticle.curtainSprites : AshParticle.cloudSprites;
        AshParticle p = new AshParticle(level, x, y, z, xd, yd, zd, kind, sprites);
        Minecraft.getInstance().particleEngine.add(p);
        return p;
    }

    private static SparkParticle spark(ClientLevel level, double x, double y, double z, double xd, double yd, double zd,
                                       float size, float heat, int life) {
        SparkParticle p = new SparkParticle(level, x, y, z, xd, yd, zd, size, heat, life, SparkParticle.sprites);
        Minecraft.getInstance().particleEngine.add(p);
        return p;
    }

    private void spawnParticles(ClientDisaster d, ClientLevel level, Camera camera, Vec3 cam) {
        float density = density();
        if (density <= 0 || !ready()) return;

        Vec3 vent = vent(d);
        Vec3 wind = wind(d);
        Vec3 side = new Vec3(-wind.z, 0, wind.x);
        float col = column(d);
        float s = d.strength();
        int i = d.intensity;
        double height = plumeHeight(d) * (0.3f + 0.7f * col);
        double dist = horizontal(d.pos(), cam);
        float lod = dist < 128 ? 1f : dist < 256 ? 0.65f : dist < 420 ? 0.4f : 0.2f;
        double spread = height * 0.6;
        AABB box = new AABB(vent.x - spread, vent.y - 4, vent.z - spread, vent.x + spread, vent.y + height + 16, vent.z + spread)
                .expandTowards(wind.scale(height));
        boolean visible = camera.getCullFrustum() == null || camera.getCullFrustum().isVisible(box);
        if (!visible) lod *= 0.1f;
        // Sized for vanilla's shared particle queue (16384): roughly 5-7k live puffs at intensity 3-4.
        float budget = (5 + i * 3) * density * lod;
        // Far away, fewer but larger sparks keep the fountain readable.
        float sparkScale = (float) Math.max(1.0, dist / 70.0);

        // Warning: pale gas and steam from the vent.
        if (d.phase == Phase.WARNING || col < 0.05f) {
            if (dist < 260 && RANDOM.nextFloat() < (0.5f + 2f * s) * density * lod) {
                ash(level, AshParticle.Kind.STEAM, vent.x + RANDOM.nextGaussian() * 0.8, vent.y + 0.3,
                        vent.z + RANDOM.nextGaussian() * 0.8, 0, 0.06 + RANDOM.nextDouble() * 0.05, 0)
                        .wind(wind.x * 0.05, wind.z * 0.05).size(1.2f + RANDOM.nextFloat() * 1.5f, 3f);
            }
            if (col < 0.05f) {
                spawnFlowFumes(d, level, vent, wind, dist, density, lod);
                return;
            }
        }

        // ---- eruption column: rising, swelling billows along a bending axis
        double jetSpeed = 0.5 + i * 0.1;
        int stem = count(budget * 1.4f * col);
        for (int n = 0; n < stem; n++) {
            double h = height * 0.85 * Math.pow(RANDOM.nextDouble(), 0.9);
            double hf = h / height;
            double rad = columnRadius(i, h);
            Vec3 axis = vent.add(lean(wind, h, height));
            double a = RANDOM.nextDouble() * Math.PI * 2, rr = Math.sqrt(RANDOM.nextDouble()) * rad * 0.55;
            // dark, dense base; lighter sunlit crown higher up
            float shade = (float) (0.2 + 0.5 * Math.pow(hf, 0.8)) * (0.85f + RANDOM.nextFloat() * 0.3f);
            double up = jetSpeed * (1 - 0.75 * hf);
            double ws = windSpeed(h, height);
            ash(level, AshParticle.Kind.PLUME, axis.x + Math.cos(a) * rr, axis.y + 1 + h, axis.z + Math.sin(a) * rr,
                    Math.cos(a) * 0.02, up, Math.sin(a) * 0.02)
                    .updraft(up).wind(wind.x * ws, wind.z * ws)
                    .size((float) Math.min(24, rad * (0.9 + RANDOM.nextDouble() * 0.5)), 1.7f)
                    .tint(shade, shade * 0.92f, shade * 0.84f);
        }

        // ---- umbrella cloud at neutral buoyancy, drifting downwind
        int cap = count(budget * 0.4f * col);
        double top = height * 0.85;
        Vec3 capCenter = vent.add(lean(wind, top, height));
        for (int n = 0; n < cap; n++) {
            double a = RANDOM.nextDouble() * Math.PI * 2;
            double r = Math.sqrt(RANDOM.nextDouble()) * height * 0.22;
            double y = vent.y + top + (RANDOM.nextDouble() - 0.4) * height * 0.12;
            double out = 0.06 + RANDOM.nextDouble() * 0.1;
            double ws = windSpeed(top, height);
            float shade = 0.42f + RANDOM.nextFloat() * 0.16f;
            ash(level, AshParticle.Kind.UMBRELLA, capCenter.x + Math.cos(a) * r, y, capCenter.z + Math.sin(a) * r,
                    Math.cos(a) * out + wind.x * ws, 0, Math.sin(a) * out + wind.z * ws)
                    .updraft(0.004).wind(wind.x * ws, wind.z * ws)
                    .size((float) Math.min(22, height * 0.09 + RANDOM.nextFloat() * 6), 2.3f)
                    .tint(shade, shade * 0.93f, shade * 0.87f);
        }

        // ---- fall-out curtains hanging under the drifting cloud (seen from afar as grey streaks)
        if (d.phase != Phase.BUILDUP && i >= 2) {
            int curtains = count(budget * 0.05f * col);
            for (int n = 0; n < curtains; n++) {
                double along = height * (0.25 + RANDOM.nextDouble() * 0.9);
                double across = (RANDOM.nextDouble() - 0.5) * height * 0.35;
                Vec3 at = capCenter.add(wind.scale(along)).add(side.scale(across));
                double y = vent.y + RANDOM.nextDouble() * top * 0.75;
                float shade = 0.3f + RANDOM.nextFloat() * 0.1f;
                ash(level, AshParticle.Kind.CURTAIN, at.x, y, at.z, 0, -0.1, 0)
                        .wind(wind.x * 0.06, wind.z * 0.06)
                        .size((float) (height * 0.12 + RANDOM.nextFloat() * 8), 1.15f)
                        .tint(shade, shade * 0.94f, shade * 0.88f).opacity(0.18f + RANDOM.nextFloat() * 0.12f);
            }
        }

        if (dist < 520) {
            float erupt = s * (d.phase == Phase.PEAK ? 1f : 0.45f);
            // ---- base of the column lit orange by the fountain
            // Lava light on the column only stands out in the dark; by day it is confined to just above the fountain.
            float night = 1f - daylight();
            int glow = count(budget * (0.2f + 0.3f * night) * erupt);
            for (int n = 0; n < glow; n++) {
                double h = Math.pow(RANDOM.nextDouble(), 1.5) * Math.min(height * (0.08 + 0.14 * night), 10 + i * 6);
                double rad = columnRadius(i, h);
                double a = RANDOM.nextDouble() * Math.PI * 2, rr = Math.sqrt(RANDOM.nextDouble()) * rad * 0.5;
                double up = jetSpeed * 0.8;
                float k = 0.75f + RANDOM.nextFloat() * 0.25f;
                ash(level, AshParticle.Kind.GLOW, vent.x + Math.cos(a) * rr, vent.y + 0.5 + h, vent.z + Math.sin(a) * rr, 0, up, 0)
                        .updraft(up).wind(wind.x * 0.03, wind.z * 0.03)
                        .size((float) (rad * (0.7 + RANDOM.nextDouble() * 0.4)), 1.6f)
                        .tint(0.95f * k, 0.42f * k, 0.14f * k).opacity(0.8f);
            }

            // ---- incandescent lava fountain (Hawaiian / Strombolian jet of spatter)
            double fountainH = (5 + i * 5) * erupt;
            if (fountainH > 1) {
                double v0 = Math.sqrt(2 * 0.04 * fountainH);
                // A dense, continuous jet: big glowing clots in the core, fine spray around it.
                int sparks = count((10 + i * 8) * erupt * density * lod / Math.min(sparkScale, 3f));
                for (int n = 0; n < sparks; n++) {
                    boolean core = RANDOM.nextFloat() < 0.45f;
                    double vy = v0 * (core ? 0.75 + RANDOM.nextDouble() * 0.3 : 0.45 + RANDOM.nextDouble() * 0.55);
                    double jet = core ? 0.05 : 0.13;
                    double sx = RANDOM.nextGaussian() * v0 * jet, sz = RANDOM.nextGaussian() * v0 * jet;
                    float size = (core ? 0.5f + RANDOM.nextFloat() * 0.6f : 0.12f + RANDOM.nextFloat() * 0.25f) * sparkScale;
                    spark(level, vent.x + RANDOM.nextGaussian() * 0.4, vent.y + 0.2, vent.z + RANDOM.nextGaussian() * 0.4,
                            sx, vy, sz, size, 0.9f + RANDOM.nextFloat() * 0.1f, (int) (2 * vy / 0.04) + 10)
                            .coolingScale(core ? 0.45f : 0.7f);
                }
            }
        }

        spawnFlowFumes(d, level, vent, wind, dist, density, lod);
        spawnAshFall(d, level, cam, wind, col, density);
    }

    /** Gas and steam rising off fresh lava flows; an occasional glowing pop. Samples the ground around the vent. */
    private static void spawnFlowFumes(ClientDisaster d, ClientLevel level, Vec3 vent, Vec3 wind, double dist,
                                       float density, float lod) {
        if (dist > 200) return;
        int samples = count((6 + d.intensity * 3) * density * lod);
        double range = 12 + d.intensity * 8;
        for (int n = 0; n < samples; n++) {
            int x = Mth.floor(vent.x + (RANDOM.nextDouble() - 0.5) * 2 * range);
            int z = Mth.floor(vent.z + (RANDOM.nextDouble() - 0.5) * 2 * range);
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
            BlockPos p = new BlockPos(x, y, z);
            boolean lava = level.getFluidState(p).is(FluidTags.LAVA);
            boolean crust = !lava && level.getBlockState(p).is(Blocks.MAGMA_BLOCK);
            if (!lava && !crust) continue;
            if (RANDOM.nextFloat() < (lava ? 0.6f : 0.25f)) {
                float shade = 0.62f + RANDOM.nextFloat() * 0.2f;
                ash(level, AshParticle.Kind.STEAM, x + RANDOM.nextDouble(), y + 1.1, z + RANDOM.nextDouble(), 0, 0.04, 0)
                        .wind(wind.x * 0.04, wind.z * 0.04).tint(shade, shade * 0.97f, shade * 0.95f)
                        .opacity(lava ? 0.32f : 0.18f);
            }
            if (lava && RANDOM.nextFloat() < 0.15f) {
                spark(level, x + RANDOM.nextDouble(), y + 1.0, z + RANDOM.nextDouble(),
                        RANDOM.nextGaussian() * 0.03, 0.12 + RANDOM.nextDouble() * 0.12, RANDOM.nextGaussian() * 0.03,
                        0.1f + RANDOM.nextFloat() * 0.12f, 0.8f, 15 + RANDOM.nextInt(15));
            }
        }
    }

    /** Fine ash drifting down around the viewer under the ash cloud (only where it can reach: open sky). */
    private static void spawnAshFall(ClientDisaster d, ClientLevel level, Vec3 cam, Vec3 wind, float col, float density) {
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

    /** Glowing spark arc and thin smoke behind a flying lava bomb (called from the bomb's client tick). */
    public static void bombTrail(LavaBombEntity bomb) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !ready()) return;
        float density = density();
        if (density <= 0) return;
        double dist = mc.gameRenderer.getMainCamera().position().distanceTo(bomb.position());
        float lod = dist < 64 ? 1f : dist < 160 ? 0.6f : 0.3f;
        float scale = (float) Math.max(1.0, dist / 60.0);
        Vec3 p = bomb.position().add(0, 0.3, 0);
        Vec3 v = bomb.getDeltaMovement();
        int sparks = count(3f * density * lod);
        for (int n = 0; n < sparks; n++) {
            double t = RANDOM.nextDouble();
            spark(mc.level, p.x - v.x * t, p.y - v.y * t, p.z - v.z * t,
                    v.x * 0.15 + RANDOM.nextGaussian() * 0.02, v.y * 0.15 + RANDOM.nextGaussian() * 0.02, v.z * 0.15 + RANDOM.nextGaussian() * 0.02,
                    (0.16f + RANDOM.nextFloat() * 0.16f) * scale, 0.75f + RANDOM.nextFloat() * 0.2f, 10 + RANDOM.nextInt(14));
        }
        if (RANDOM.nextFloat() < 0.8f * density * lod) {
            ash(mc.level, AshParticle.Kind.TRAIL, p.x, p.y, p.z, 0, 0.02, 0);
        }
    }

    /** A Vulcanian / Strombolian blast: dark ash jets and a shower of glowing ejecta, instantly visible. */
    private static void blastEjecta(ClientDisaster d, ClientLevel level, double dist) {
        float density = density();
        if (density <= 0 || !ready() || dist > 600) return;
        Vec3 vent = vent(d);
        Vec3 wind = wind(d);
        int i = d.intensity;
        float scale = (float) Math.max(1.0, dist / 70.0);
        int sparks = count((50 + 30 * i) * density / Math.min(scale, 3f));
        for (int n = 0; n < sparks; n++) {
            double a = RANDOM.nextDouble() * Math.PI * 2;
            double tilt = RANDOM.nextDouble() * 0.9;        // up to ~50° from vertical
            double sp = 0.6 + RANDOM.nextDouble() * (0.6 + i * 0.15);
            spark(level, vent.x, vent.y + 0.5, vent.z, Math.cos(a) * Math.sin(tilt) * sp, Math.cos(tilt) * sp,
                    Math.sin(a) * Math.sin(tilt) * sp, (0.2f + RANDOM.nextFloat() * 0.35f) * scale,
                    0.9f + RANDOM.nextFloat() * 0.1f, 40 + RANDOM.nextInt(50));
        }
        // Cock's-tail jets of dark, ash-laden gas punching up and out of the crater.
        int jets = count((10 + 4 * i) * density);
        for (int n = 0; n < jets; n++) {
            double a = RANDOM.nextDouble() * Math.PI * 2;
            double out = RANDOM.nextDouble() * 0.35;
            double up = 0.9 + RANDOM.nextDouble() * (0.5 + i * 0.12);
            float shade = 0.14f + RANDOM.nextFloat() * 0.08f;
            ash(level, AshParticle.Kind.PLUME, vent.x, vent.y + 1, vent.z, Math.cos(a) * out, up, Math.sin(a) * out)
                    .updraft(0.3).wind(wind.x * 0.05, wind.z * 0.05)
                    .size(3f + RANDOM.nextFloat() * 3f, 2.4f).tint(shade, shade * 0.93f, shade * 0.88f);
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
            blastEjecta(d, level, dist);
            // Light is instant, sound and the pressure wave travel: delay by distance.
            st.pendingBlasts.add(new int[]{(int) Math.min(80, dist / SOUND_SPEED)});
        }
        var it = st.pendingBlasts.iterator();
        while (it.hasNext()) {
            int[] b = it.next();
            if (--b[0] > 0) continue;
            it.remove();
            float near = 1f - smoothstep(20f, 280f, (float) dist);
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
                        return s * (1 - smoothstep(30f, 600f, (float) dist)) * (0.5 + 0.1 * x.intensity);
                    },
                    x -> 1.0);
            sounds.play(st.distant);
        }
        // Warning signs: deep rumbles while the magma rises.
        if (d.phase == Phase.WARNING || d.phase == Phase.BUILDUP) {
            if (--st.nextRumble <= 0) {
                st.nextRumble = 80 + RANDOM.nextInt(120);
                playAt(d, cam, ModRegistries.DISTANT_RUMBLE.get(), 0.6f + 0.4f * d.strength(), 0.5f + RANDOM.nextFloat() * 0.2f);
            }
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
