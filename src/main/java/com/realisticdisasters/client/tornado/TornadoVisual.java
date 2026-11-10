package com.realisticdisasters.client.tornado;

import com.realisticdisasters.client.ClientDisaster;
import com.realisticdisasters.client.DisasterVisual;
import com.realisticdisasters.client.sound.DisasterLoopSound;
import com.realisticdisasters.config.ClientConfig;
import com.realisticdisasters.disaster.Phase;
import com.realisticdisasters.disaster.tornado.TornadoDisaster;
import com.realisticdisasters.disaster.tornado.TornadoWind;
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
    private static final int SKY = 0x2A302F;     // near-black storm overhead
    private static final int FOG = 0xA7AD9E;     // bright, backlit horizon under the storm
    private static final int CLOUDS = 0x2C3031;

    private static final RandomSource RANDOM = RandomSource.create();

    private static final class State {
        DisasterLoopSound roar, distant, core, howl;
        float shelter;
        float localWind;
        int nextRattle = 20;
        int nextRumble = 40;
        int nextGust = 80;
        int nextFlash = 30;
        int thunderIn = -1;
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

    /** Height of the cloud base above the tornado's ground point at {@code ring} core radii from the axis. */
    public static double cloudBaseHeight(ClientDisaster d, float ring) {
        float t = Mth.clamp((ring - 1.5f) / 8f, 0f, 1f);
        return height(d) * (0.86 + 0.12 * (t * t * (3 - 2 * t)));
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
        out.darken(w * (0.42f + d.intensity * 0.06f));
        // Rain haze, not a wall: the funnel must stay visible from 100+ blocks.
        float fogEnd = 220f + (5 - d.intensity) * 20f + (float) dist;
        out.fog(fogEnd, w * (d.phase == Phase.WARNING ? 0.25f : 0.4f));

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
        // Every quad particle in the game shares one 16384-entry queue (oldest evicted first), so the per-tick
        // budget is sized to stay around 11k live particles at worst; otherwise the long-lived cloud base would
        // be evicted by the funnel.
        float budget = (22 + d.intensity * 14) * density * lod;

        // Cloud base, built from flat, downward-facing sheets so it reads as one continuous ceiling receding in
        // perspective. It is lowest (the wall cloud) around the funnel and rises outward in a gentle step.
        float baseRate = Math.max(0.35f, s) * Math.max(0.5f, density);
        int wallSheets = count(0.35f * baseRate * (1 + d.intensity * 0.15f));
        for (int i = 0; i < wallSheets; i++) {
            float ring = 0.3f + RANDOM.nextFloat() * 3.2f;
            spawnAt(level, ModRegistries.CLOUD_SHEET.get(), d, 0.9f, ring * core, ring, height);
        }
        // Wide enough to reach the horizon from most viewpoints; the outer rings fade (no hard disc edge).
        int deckSheets = count(2.2f * baseRate * (1 + d.intensity * 0.12f));
        for (int i = 0; i < deckSheets; i++) {
            float ring = 3.5f + (float) Math.sqrt(RANDOM.nextFloat()) * 24f;
            spawnAt(level, ModRegistries.CLOUD_SHEET.get(), d, 0.95f, ring * core, ring, height);
        }
        // Rotating concentric striations in the base (the "mothership" look of a strong supercell).
        int bands = count(0.6f * baseRate);
        for (int i = 0; i < bands; i++) {
            float ring = 1.8f + RANDOM.nextFloat() * 10f;
            spawnAt(level, ModRegistries.CLOUD_BAND.get(), d, 0.92f, ring * core, ring, height);
        }
        // Rain curtains hanging from the cloud base on the rear flank (behind the direction of travel).
        if (dist < 400) {
            int rainCount = count(budget * 0.05f * Math.max(0.3f, s));
            float hx = d.data(TornadoDisaster.D_HEADING_X), hz = d.data(TornadoDisaster.D_HEADING_Z);
            double back = Math.atan2(-hz, -hx);
            for (int i = 0; i < rainCount; i++) {
                double a = back + (RANDOM.nextDouble() - 0.5) * 2.4;
                float ring = 4f + RANDOM.nextFloat() * 7f;
                float h = RANDOM.nextFloat() * 0.85f;
                Vec3 base = d.pos();
                level.addParticle(ModRegistries.RAIN_SHAFT.get(), true, true,
                        base.x + Math.cos(a) * ring * core, base.y + h * height, base.z + Math.sin(a) * ring * core,
                        d.id, h, ring);
            }
        }

        // Condensation funnel lowering from the cloud base.
        float bottom = (1f - td) * 0.78f;
        if (td > 0.02f || d.phase == Phase.BUILDUP) {
            int funnelCount = count(budget * 1.5f * s);
            for (int i = 0; i < funnelCount; i++) {
                // bias toward the lower funnel, where it is thin and needs density to read as solid
                float u = RANDOM.nextFloat();
                // stop below the flare: the soft wall cloud takes over there (crisp funnel puffs look like bubbles)
                float h = bottom + (u * u) * (0.8f - bottom);
                // mostly a tight outer shell (crisp edge) plus some fill
                if (RANDOM.nextFloat() < 0.5f) {
                    // streaked surface panels: the funnel's skin with helical striations
                    spawnAt(level, ModRegistries.FUNNEL_SURFACE.get(), d, h, funnelRadius(d, h) * 0.96, 1f, height);
                } else {
                    // soft puffs at the silhouette and some fill
                    float jitter = RANDOM.nextFloat() < 0.75f ? 0.95f + RANDOM.nextFloat() * 0.08f : 0.4f + RANDOM.nextFloat() * 0.5f;
                    spawnAt(level, ModRegistries.FUNNEL_CLOUD.get(), d, h, funnelRadius(d, h) * jitter, jitter, height);
                }
            }
        }

        if (td > 0.35f && dist < 300) {
            // Debris cloud at the base, flecks whipped around it, dust streamers feeding in.
            int dustCount = count(budget * 0.3f * td);
            for (int i = 0; i < dustCount; i++) {
                float jitter = RANDOM.nextFloat();
                spawnAt(level, ModRegistries.DUST_CLOUD.get(), d, RANDOM.nextFloat() * 0.15f, core * (0.5 + 1.3 * jitter), jitter, height);
            }
            int debrisCount = count(budget * 0.2f * td);
            for (int i = 0; i < debrisCount; i++) {
                float jitter = 0.8f + RANDOM.nextFloat() * 0.6f;
                float h = RANDOM.nextFloat() * 0.15f;
                spawnAt(level, ModRegistries.STORM_DEBRIS.get(), d, h, funnelRadius(d, h) * jitter * 1.25, jitter, height);
            }
            int inflowCount = count(budget * 0.1f * td);
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

    /** The tornado's wind field, rebuilt on the client from synced data (same model the server uses). */
    public static TornadoWind wind(ClientDisaster d) {
        return new TornadoWind(d.pos(), coreRadius(d), height(d), d.intensity,
                d.data(TornadoDisaster.D_HEADING_X), d.data(TornadoDisaster.D_HEADING_Z), d.data(TornadoDisaster.D_SPEED),
                d.strength() * touchdown(d));
    }

    /**
     * Layered sound, mixed like real recordings: a far "freight train" rumble, a mid-range roar, an overwhelming
     * core roar when it is on top of you, a wind howl driven by the actual wind speed at your position, and debris
     * rattling around you. Everything above the rumble is muffled when you are sheltered.
     */
    private void tickSounds(ClientDisaster d, State st, Vec3 cam) {
        Minecraft mc = Minecraft.getInstance();
        var sounds = mc.getSoundManager();
        // Shelter: under a roof / underground the high, violent layers are muffled; the low rumble comes through.
        boolean open = mc.level != null && mc.level.canSeeSky(BlockPos.containing(cam));
        st.shelter += ((open ? 0f : 1f) - st.shelter) * 0.1f;
        TornadoWind wind = wind(d);
        double localWind = wind.at(cam.x, cam.y, cam.z).length();
        st.localWind += (float) ((localWind - st.localWind) * 0.2);
        double vmax = TornadoWind.maxWind(d.intensity);

        if (st.distant == null || st.distant.isStopped()) {
            st.distant = new DisasterLoopSound(ModRegistries.TORNADO_DISTANT.get(), d, false,
                    x -> {
                        double dist = horizontalDistance(x, camPos());
                        double far = 1 - smoothstep(250f, 750f, (float) dist);
                        return x.strength() * (0.5 + 0.1 * x.intensity) * far * (1 - 0.15 * st.shelter);
                    },
                    x -> 0.9 + 0.1 * (1 - smoothstep(60f, 400f, (float) horizontalDistance(x, camPos()))));
            sounds.play(st.distant);
        }
        if (st.roar == null || st.roar.isStopped()) {
            st.roar = new DisasterLoopSound(ModRegistries.TORNADO_ROAR.get(), d, true,
                    x -> x.strength() * (0.3 + touchdown(x) * 0.7) * (1.2 + x.intensity * 0.2) * (1 - 0.55 * st.shelter),
                    x -> 0.85 + 0.04 * (5 - x.intensity));
            sounds.play(st.roar);
        }
        if (st.core == null || st.core.isStopped()) {
            st.core = new DisasterLoopSound(ModRegistries.TORNADO_CORE.get(), d, false,
                    x -> {
                        double dist = horizontalDistance(x, camPos());
                        float rc = coreRadius(x);
                        double near = 1 - smoothstep(rc * 1.2f, rc * 5.5f, (float) dist);
                        return x.strength() * touchdown(x) * near * (0.7 + 0.08 * x.intensity) * (1 - 0.7 * st.shelter);
                    },
                    x -> 0.9 + 0.1 * Math.min(1, st.localWind / vmax));
            sounds.play(st.core);
        }
        if (st.howl == null || st.howl.isStopped()) {
            st.howl = new DisasterLoopSound(ModRegistries.WIND_HOWL.get(), d, false,
                    x -> Math.min(1.0, st.localWind / (vmax * 0.55)) * 0.85 * (1 - 0.75 * st.shelter),
                    x -> 0.7 + 0.55 * Math.min(1.0, st.localWind / vmax));
            sounds.play(st.howl);
        }

        // Debris pelting the surroundings when you are in the wind field.
        double ratio = st.localWind / vmax;
        if (ratio > 0.25 && --st.nextRattle <= 0) {
            st.nextRattle = 6 + RANDOM.nextInt(Math.max(4, (int) (30 * (1 - ratio))));
            double a = RANDOM.nextDouble() * Math.PI * 2;
            double r = 3 + RANDOM.nextDouble() * 8;
            sounds.play(new SimpleSoundInstance(ModRegistries.DEBRIS_RATTLE.get().location(), SoundSource.WEATHER,
                    (float) Math.min(1.0, ratio) * (1 - 0.6f * st.shelter), 0.85f + RANDOM.nextFloat() * 0.3f, RANDOM, false, 0,
                    SoundInstance.Attenuation.LINEAR, cam.x + Math.cos(a) * r, cam.y + RANDOM.nextDouble() * 3, cam.z + Math.sin(a) * r, false));
        }

        double dist = horizontalDistance(d, cam);
        // In-cloud lightning: the base flickers, thunder follows after the sound's travel time.
        if ((d.phase == Phase.BUILDUP || d.phase == Phase.PEAK) && d.strength() > 0.3f && --st.nextFlash <= 0) {
            st.nextFlash = 50 + RANDOM.nextInt(Math.max(20, 200 - d.intensity * 25));
            float near = 1f - smoothstep(60f, 500f, (float) dist);
            EffectsManager.triggerFlash((0.45f + 0.55f * RANDOM.nextFloat()) * near * d.strength());
            st.thunderIn = 2 + (int) (dist / 17.0);   // ~343 m/s
        }
        if (st.thunderIn > 0 && --st.thunderIn == 0) {
            float loud = 1f - smoothstep(20f, 400f, (float) dist);
            Vec3 dir = d.pos().subtract(cam);
            Vec3 at = cam.add(dir.normalize().scale(Math.min(dir.length(), 16)));
            sounds.play(new SimpleSoundInstance(ModRegistries.DISTANT_RUMBLE.get().location(), SoundSource.WEATHER,
                    0.6f + loud * 0.9f, 0.75f + loud * 0.35f + RANDOM.nextFloat() * 0.1f, RANDOM, false, 0,
                    SoundInstance.Attenuation.NONE, at.x, at.y, at.z, false));
        }
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

    private static Vec3 camPos() {
        return Minecraft.getInstance().gameRenderer.getMainCamera().position();
    }

    @Override
    public void onRemoved(ClientDisaster d) {
        if (d.visualState instanceof State st) {
            if (st.roar != null) st.roar.end();
            if (st.distant != null) st.distant.end();
            if (st.core != null) st.core.end();
            if (st.howl != null) st.howl.end();
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
