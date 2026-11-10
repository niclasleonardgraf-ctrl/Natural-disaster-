package com.realisticdisasters.client.tornado;

import com.realisticdisasters.client.ClientDisaster;
import com.realisticdisasters.client.DisasterVisual;
import com.realisticdisasters.client.sound.DisasterLoopSound;
import com.realisticdisasters.config.ClientConfig;
import com.realisticdisasters.disaster.Phase;
import com.realisticdisasters.disaster.tornado.TornadoDisaster;
import com.realisticdisasters.registry.ModRegistries;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import com.realisticdisasters.client.EffectsManager;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Tornado presentation: green-black supercell sky, rain-wrapped fog, a rotating wall cloud that lowers into a
 * swirling funnel, debris and dust rings at the base, and a layered roar.
 */
public final class TornadoVisual implements DisasterVisual {
    private static final int SKY = 0x56654C;     // bruised storm green
    private static final int FOG = 0x7C876F;   // bright, sickly horizon band under the dark base
    private static final int CLOUDS = 0x33382F;

    private static final RandomSource RANDOM = RandomSource.create();

    private static final class State {
        DisasterLoopSound roar, distant;
        int nextRumble = 40;
        int nextGust = 80;
    }

    // ------------------------------------------------------------------ shape helpers (shared with particles)

    public static float coreRadius(ClientDisaster d) {
        return Math.max(2f, d.data(TornadoDisaster.D_CORE_RADIUS));
    }

    public static float height(ClientDisaster d) {
        return Math.max(20f, d.data(TornadoDisaster.D_HEIGHT));
    }

    public static float touchdown(ClientDisaster d) {
        return Mth.clamp(d.data(TornadoDisaster.D_TOUCHDOWN), 0f, 1f);
    }

    /**
     * Funnel radius at a height fraction: a narrow rope at the ground that widens upward and flares into the
     * wall cloud. Stronger tornadoes are wider ("wedge"); weakening ones thin out ("rope out").
     */
    public static double funnelRadius(ClientDisaster d, float h) {
        float core = coreRadius(d);
        float rope = 0.3f + 0.7f * d.strength();
        float width = 0.7f + d.intensity * 0.08f;
        double hc = Mth.clamp(h, 0f, 1f);
        double r = core * width * (0.42 * rope + 1.15 * Math.pow(hc, 2.3));
        if (hc > 0.8) r += core * (hc - 0.8) / 0.2 * 4.0;
        return r;
    }

    /**
     * Horizontal offset of the funnel axis at a height fraction: the top trails behind the direction of travel and
     * the column snakes slowly, so it never reads as a straight pillar.
     */
    public static Vec3 axisOffset(ClientDisaster d, float h) {
        float core = coreRadius(d);
        float hx = d.data(TornadoDisaster.D_HEADING_X), hz = d.data(TornadoDisaster.D_HEADING_Z);
        double hc = Mth.clamp(h, 0f, 1f);
        double t = (EffectsManager.ticks() + d.id * 977) * 0.035;
        double tilt = core * 1.8 * Math.pow(hc, 1.3) * (0.4 + 0.6 * d.strength());
        double belly = Math.sin(hc * Math.PI);
        double sway = core * 0.6 * Math.sin(t + hc * 3.0) * belly;
        double surge = core * 0.35 * Math.cos(t * 0.63 + hc * 4.7) * belly;
        return new Vec3(-hx * (tilt + surge) - hz * sway, 0, -hz * (tilt + surge) + hx * sway);
    }

    // ------------------------------------------------------------------ atmosphere

    @Override
    public void contribute(ClientDisaster d, Vec3 cam, Atmosphere out) {
        double dist = horizontalDistance(d, cam);
        float s = d.strength();
        // Felt from far away; full effect inside ~80 blocks.
        float reach = 220f + d.intensity * 40f;
        float near = 1f - smoothstep(60f, reach, (float) dist);
        float w = s * near;
        // During the warning the sky shifts first, before fog and shake.
        out.sky(SKY, w);
        out.clouds(CLOUDS, w);
        out.fogColor(FOG, w);
        out.darken(w * (0.25f + d.intensity * 0.06f));
        // Rain haze, not a wall: the funnel must stay visible from 100+ blocks.
        float fogEnd = 110f + (5 - d.intensity) * 20f + (float) dist * 0.6f;
        out.fog(fogEnd, w * (d.phase == Phase.WARNING ? 0.35f : 0.6f));

        float td = touchdown(d);
        float core = coreRadius(d);
        float shakeNear = 1f - smoothstep(core, core * 9f, (float) dist);
        float shakeFar = 1f - smoothstep(core * 9f, 160f, (float) dist);
        out.shake(s * td * (d.intensity / 5f) * (shakeNear * 0.9f + shakeFar * 0.12f));
        out.fovPulse(s * td * shakeNear);
    }

    // ------------------------------------------------------------------ particles + sound

    @Override
    public void tick(ClientDisaster d, ClientLevel level, Camera camera) {
        State st = d.visualState instanceof State s ? s : new State();
        d.visualState = st;
        Vec3 cam = camera.position();
        tickSounds(d, st, cam);
        spawnParticles(d, level, camera, cam);
    }

    private void spawnParticles(ClientDisaster d, ClientLevel level, Camera camera, Vec3 cam) {
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
        float height = height(d);
        float core = coreRadius(d);
        double dist = horizontalDistance(d, cam);
        // Distance LOD: far funnels need fewer, larger-looking particles.
        float lod = dist < 96 ? 1f : dist < 192 ? 0.6f : dist < 320 ? 0.35f : 0.15f;
        // Off-screen culling: the whole funnel's bounding box against the camera frustum.
        double wall = core * 3.5;
        AABB box = new AABB(c.x - wall, c.y - 2, c.z - wall, c.x + wall, c.y + height + 4, c.z + wall);
        boolean visible = camera.getCullFrustum() == null || camera.getCullFrustum().isVisible(box);
        if (!visible) lod *= 0.1f;

        float s = d.strength();
        float td = touchdown(d);
        float budget = (22 + d.intensity * 14) * density * lod;

        // Wall cloud / mesocyclone base: present from the warning phase on, slowly turning.
        int wallCount = count(budget * 0.2f * Math.max(0.35f, s));
        for (int i = 0; i < wallCount; i++) {
            float ring = 1.2f + RANDOM.nextFloat() * 2.6f;           // lowered wall cloud around the funnel top
            spawnAt(level, ModRegistries.WALL_CLOUD.get(), d, 0.9f, ring * core, ring, height);
        }
        // Storm deck: sparse, huge, slowly turning cloud base that covers the sky for ~150 blocks.
        int deckCount = count((1.6f + d.intensity * 0.3f) * Math.max(0.3f, s) * Math.max(0.5f, density));
        for (int i = 0; i < deckCount; i++) {
            float ring = 4f + (float) Math.sqrt(RANDOM.nextFloat()) * 14f;
            spawnAt(level, ModRegistries.WALL_CLOUD.get(), d, 0.97f, ring * core, ring, height);
        }

        // Condensation funnel lowering from the cloud base.
        float bottom = (1f - td) * 0.85f;
        if (td > 0.02f || d.phase == Phase.BUILDUP) {
            int funnelCount = count(budget * 1.4f * s);
            for (int i = 0; i < funnelCount; i++) {
                // bias toward the lower funnel, where it is thin and needs density to read as solid
                float u = RANDOM.nextFloat();
                float h = bottom + (u * u) * (0.9f - bottom);
                float jitter = 0.78f + RANDOM.nextFloat() * 0.3f;
                spawnAt(level, ModRegistries.FUNNEL_CLOUD.get(), d, h, funnelRadius(d, h) * jitter, jitter, height);
            }
        }

        if (td > 0.35f && dist < 300) {
            // Debris cloud at the base, flecks whipped around it, dust streamers feeding in.
            int dustCount = count(budget * 0.7f * td);
            for (int i = 0; i < dustCount; i++) {
                float jitter = RANDOM.nextFloat();
                spawnAt(level, ModRegistries.DUST_CLOUD.get(), d, RANDOM.nextFloat() * 0.15f, core * (0.5 + 1.3 * jitter), jitter, height);
            }
            int debrisCount = count(budget * 0.3f * td);
            for (int i = 0; i < debrisCount; i++) {
                float jitter = 0.8f + RANDOM.nextFloat() * 0.6f;
                float h = RANDOM.nextFloat() * 0.15f;
                spawnAt(level, ModRegistries.STORM_DEBRIS.get(), d, h, funnelRadius(d, h) * jitter * 1.25, jitter, height);
            }
            int inflowCount = count(budget * 0.15f * td);
            for (int i = 0; i < inflowCount; i++) {
                float jitter = 3.5f + RANDOM.nextFloat() * 3f;
                spawnAt(level, ModRegistries.DUST_INFLOW.get(), d, 0f, core * jitter, jitter, height);
            }
            // Real block chunks from the ground under the funnel (uses the actual terrain textures).
            if (dist < 128) {
                int chunks = count(budget * 0.12f * td);
                for (int i = 0; i < chunks; i++) {
                    double a = RANDOM.nextDouble() * Math.PI * 2;
                    double r = RANDOM.nextDouble() * core * 1.4;
                    int x = Mth.floor(c.x + Math.cos(a) * r), z = Mth.floor(c.z + Math.sin(a) * r);
                    int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
                    BlockState bs = level.getBlockState(new BlockPos(x, y, z));
                    if (bs.isAir()) continue;
                    double tx = -Math.sin(a), tz = Math.cos(a);
                    level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, bs), x + 0.5, y + 1.1, z + 0.5,
                            tx * 0.6, 0.4 + RANDOM.nextDouble() * 0.5, tz * 0.6);
                }
            }
        }
    }

    private static int count(float expected) {
        int n = (int) expected;
        if (RANDOM.nextFloat() < expected - n) n++;
        return n;
    }

    /** Spawns a vortex particle at height fraction {@code h}, radius {@code r} around the (tilted) axis. */
    private static void spawnAt(ClientLevel level, SimpleParticleType type, ClientDisaster d, float h, double r,
                                float jitter, float height) {
        Vec3 c = d.pos().add(axisOffset(d, h));
        double a = RANDOM.nextDouble() * Math.PI * 2;
        // The particle reads its disaster id / height / jitter from the "velocity" args (see VortexParticle).
        level.addParticle(type, true, true, c.x + Math.cos(a) * r, c.y + h * height, c.z + Math.sin(a) * r,
                d.id, h, jitter);
    }

    private void tickSounds(ClientDisaster d, State st, Vec3 cam) {
        Minecraft mc = Minecraft.getInstance();
        var sounds = mc.getSoundManager();
        if (st.roar == null || st.roar.isStopped()) {
            // Close layer: positional roar, audible within ~100 blocks.
            st.roar = new DisasterLoopSound(ModRegistries.TORNADO_ROAR.get(), d, true,
                    x -> x.strength() * (0.35 + touchdown(x) * 0.65) * (2.5 + x.intensity * 0.5),
                    x -> 0.85 + 0.05 * (5 - x.intensity));
            sounds.play(st.roar);
        }
        if (st.distant == null || st.distant.isStopped()) {
            // Distant layer: non-attenuated low rumble that fades with distance and under the roar.
            st.distant = new DisasterLoopSound(ModRegistries.TORNADO_DISTANT.get(), d, false,
                    x -> {
                        double dist = horizontalDistance(x, Minecraft.getInstance().gameRenderer.getMainCamera().position());
                        return x.strength() * 0.7 * (1 - smoothstep(40f, 380f, (float) dist)) * (0.5 + 0.1 * x.intensity);
                    },
                    x -> 1.0);
            sounds.play(st.distant);
        }

        double dist = horizontalDistance(d, cam);
        // Warning signs: distant thunder-like rumbles while the storm organises.
        if (d.phase == Phase.WARNING || d.phase == Phase.BUILDUP) {
            if (--st.nextRumble <= 0) {
                st.nextRumble = 100 + RANDOM.nextInt(160);
                Vec3 dir = d.pos().subtract(cam);
                Vec3 at = cam.add(dir.normalize().scale(Math.min(dir.length(), 24)));
                sounds.play(new SimpleSoundInstance(ModRegistries.DISTANT_RUMBLE.get().location(), SoundSource.WEATHER,
                        0.5f + 0.4f * d.strength(), 0.8f + RANDOM.nextFloat() * 0.3f, RANDOM, false, 0,
                        SoundInstance.Attenuation.NONE, at.x, at.y, at.z, false));
            }
        }
        // Gusts when the storm is close.
        if (dist < 150 && d.strength() > 0.15f && --st.nextGust <= 0) {
            st.nextGust = 50 + RANDOM.nextInt(90);
            double a = RANDOM.nextDouble() * Math.PI * 2;
            sounds.play(new SimpleSoundInstance(ModRegistries.WIND_GUST.get().location(), SoundSource.WEATHER,
                    0.3f + 0.5f * d.strength(), 0.8f + RANDOM.nextFloat() * 0.4f, RANDOM, false, 0,
                    SoundInstance.Attenuation.NONE, cam.x + Math.cos(a) * 6, cam.y, cam.z + Math.sin(a) * 6, false));
        }
    }

    @Override
    public void onRemoved(ClientDisaster d) {
        if (d.visualState instanceof State st) {
            if (st.roar != null) st.roar.end();
            if (st.distant != null) st.distant.end();
        }
    }

    // ------------------------------------------------------------------ math

    static double horizontalDistance(ClientDisaster d, Vec3 cam) {
        double dx = cam.x - d.pos().x, dz = cam.z - d.pos().z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    static float smoothstep(float e0, float e1, float x) {
        float t = Mth.clamp((x - e0) / (e1 - e0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }
}
