package com.realisticdisasters.client.windstorm;

import java.util.function.Supplier;

import com.realisticdisasters.client.ClientDisaster;
import com.realisticdisasters.client.DisasterVisual;
import com.realisticdisasters.client.sound.DisasterLoopSound;
import com.realisticdisasters.config.ClientConfig;
import com.realisticdisasters.disaster.Phase;
import com.realisticdisasters.disaster.windstorm.WindStormDisaster;
import com.realisticdisasters.registry.ModRegistries;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Shared presentation for wind storms: a thick, tinted fog that closes in as you enter the storm, sideways
 * particles that follow the synced wind vector (fine flakes/grains near the camera, big haze puffs further out,
 * a haze "wall" seen from outside), ground drift, buffeting, and a howling close loop that cross-fades with a
 * distant one. Subclasses supply the palette, particle types and sounds.
 */
public abstract class WindStormVisual implements DisasterVisual {
    protected static final RandomSource RANDOM = RandomSource.create();

    /** Colours and assets for one storm look. */
    protected record Palette(int sky, int fog, int clouds, float darkness, float fogNear, float fogFar,
                             Supplier<SimpleParticleType> fine, Supplier<SimpleParticleType> haze,
                             Supplier<SoundEvent> howl, Supplier<SoundEvent> distant) {}

    private static final class State {
        DisasterLoopSound howl, distant;
        float howlVolume, distantVolume, howlPitch = 1f;
        boolean sheltered;
        int nextGust = 60;
    }

    protected abstract Palette palette(ClientDisaster d);

    // ------------------------------------------------------------------ geometry helpers

    static double horizontalDistance(ClientDisaster d, Vec3 cam) {
        double dx = cam.x - d.pos().x, dz = cam.z - d.pos().z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    static float smoothstep(float e0, float e1, float x) {
        float t = Mth.clamp((x - e0) / (e1 - e0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    static float radius(ClientDisaster d) {
        return Math.max(16f, d.radius);
    }

    /** 0..1: how deep inside the storm the camera is (matches the server's soft edge). */
    static float inside(ClientDisaster d, double dist) {
        float r = radius(d);
        return 1f - smoothstep(r * 0.75f, r + 16f, (float) dist);
    }

    static float gust(ClientDisaster d) {
        return Mth.clamp(d.data(WindStormDisaster.D_GUST), 0.3f, 1.6f);
    }

    static boolean sheltered(ClientLevel level, Vec3 cam) {
        if (level == null) return false;
        int x = Mth.floor(cam.x), z = Mth.floor(cam.z);
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) > cam.y + 0.5;
    }

    // ------------------------------------------------------------------ atmosphere

    @Override
    public void contribute(ClientDisaster d, Vec3 cam, Atmosphere out) {
        Palette p = palette(d);
        double dist = horizontalDistance(d, cam);
        float s = d.strength();
        float in = inside(d, dist);
        // The sky shifts from far away (warning sign), the fog only rolls in near / inside the storm.
        float reach = 1f - smoothstep(radius(d), radius(d) + 260f, (float) dist);
        float skyW = s * Math.max(in, reach * 0.7f);
        out.sky(p.sky, skyW);
        out.clouds(p.clouds, skyW);
        out.fogColor(p.fog, s * Math.max(in, reach * 0.5f));
        out.darken(s * Math.max(in, reach * 0.3f) * p.darkness);

        boolean sheltered = sheltered(Minecraft.getInstance().level, cam);
        // Fog end: thick inside (fogNear .. a few blocks at intensity 5), a wall on the horizon from outside.
        float thick = p.fogNear - (p.fogNear - p.fogFar) * (d.intensity - 1) / 4f;
        float fogEnd = Mth.lerp(in, 140f + (float) Math.max(0, dist - radius(d)) * 0.5f, thick);
        if (sheltered) fogEnd *= 2.2f;
        float amount = s * Math.max(in, reach * 0.45f) * (sheltered ? 0.6f : 0.97f);
        if (d.phase == Phase.WARNING) amount *= 0.6f;
        out.fog(fogEnd, amount);

        if (!sheltered) {
            float g = gust(d);
            float buffet = s * in * (0.03f + 0.02f * d.intensity) * Mth.clamp((g - 0.7f) * 1.6f, 0.2f, 1f);
            out.shake(buffet);
            out.fovPulse(s * in * 0.25f * Mth.clamp(g - 0.8f, 0f, 1f));
        }
    }

    // ------------------------------------------------------------------ particles + sound

    @Override
    public void tick(ClientDisaster d, ClientLevel level, Camera camera) {
        State st = d.visualState instanceof State s ? s : new State();
        d.visualState = st;
        Vec3 cam = camera.position();
        st.sheltered = sheltered(level, cam);
        Palette p = palette(d);
        tickSounds(d, st, p, cam);
        spawnParticles(d, level, camera, cam, p);
    }

    private static float density() {
        Minecraft mc = Minecraft.getInstance();
        float density = ClientConfig.PARTICLE_DENSITY.get().floatValue();
        ParticleStatus status = mc.options.particles().get();
        return density * switch (status) {
            case ALL -> 1f;
            case DECREASED -> 0.5f;
            case MINIMAL -> 0.2f;
        };
    }

    private void spawnParticles(ClientDisaster d, ClientLevel level, Camera camera, Vec3 cam, Palette p) {
        float density = density();
        if (density <= 0) return;
        float s = d.strength();
        if (s < 0.03f) return;
        double dist = horizontalDistance(d, cam);
        float in = inside(d, dist);
        Frustum frustum = camera.getCullFrustum();
        double wx = d.data(WindStormDisaster.D_WIND_X), wz = d.data(WindStormDisaster.D_WIND_Z);
        double wl = Math.sqrt(wx * wx + wz * wz);
        if (wl < 1.0e-3) {
            wx = 1;
            wz = 0;
        } else {
            wx /= wl;
            wz /= wl;
        }
        float variant = d.data(WindStormDisaster.D_VARIANT);
        float speed = Math.max(0.1f, d.data(WindStormDisaster.D_WIND_SPEED));
        int id = d.id;

        if (in > 0.01f) {
            // Fine flakes / grains around the camera. Spawned upwind so they stream across the view.
            float budget = (16 + d.intensity * 10) * density * s * in;
            int n = count(budget);
            for (int i = 0; i < n; i++) {
                double a = RANDOM.nextDouble() * Math.PI * 2;
                // Close to the camera: further out the thick fog swallows them anyway.
                double r = 1.5 + Math.sqrt(RANDOM.nextDouble()) * 13;
                double x = cam.x + Math.cos(a) * r - wx * 7;
                double z = cam.z + Math.sin(a) * r - wz * 7;
                boolean ground = RANDOM.nextFloat() < 0.3f;
                int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(x), Mth.floor(z));
                double y = ground ? surface + 0.1 + RANDOM.nextDouble() * 0.6 : cam.y - 3 + RANDOM.nextDouble() * 8;
                if (y < surface) continue;             // under a roof or inside terrain: no snow indoors
                if (y > cam.y + 30) continue;
                if (!visible(frustum, x, y, z, wx * speed * 40, wz * speed * 40)) continue;
                level.addParticle(p.fine.get(), true, false, x, y, z, id, ground ? 0.8 : 1.0, variant);
            }
            // Haze puffs: build up the white-out / brown-out at mid distance.
            float hazeBudget = (1.2f + d.intensity * 0.7f) * density * s * in;
            int h = count(hazeBudget);
            for (int i = 0; i < h; i++) {
                double a = RANDOM.nextDouble() * Math.PI * 2;
                double r = 6 + RANDOM.nextDouble() * 30;
                double x = cam.x + Math.cos(a) * r - wx * 16;
                double z = cam.z + Math.sin(a) * r - wz * 16;
                int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(x), Mth.floor(z));
                double y = Math.max(surface + 0.5, cam.y - 3) + RANDOM.nextDouble() * 9;
                if (!visible(frustum, x, y, z, wx * speed * 50, wz * speed * 50)) continue;
                level.addParticle(p.haze.get(), true, false, x, y, z, id, 1.0, variant);
            }
        }
        if (in < 0.9f && dist < radius(d) + 220) {
            // Seen from outside: a rolling wall of haze along the storm's near edge. Distance LOD: fewer, bigger puffs.
            float lod = dist < radius(d) + 80 ? 1f : 0.45f;
            int n = count((1.5f + d.intensity * 0.6f) * density * s * lod * (1f - in));
            Vec3 c = d.pos();
            double ex = cam.x - c.x, ez = cam.z - c.z;
            double el = Math.max(1e-3, Math.sqrt(ex * ex + ez * ez));
            ex /= el;
            ez /= el;
            for (int i = 0; i < n; i++) {
                double along = (RANDOM.nextDouble() - 0.5) * 120;
                double r = radius(d) * (0.85 + RANDOM.nextDouble() * 0.2);
                double x = c.x + ex * r - ez * along;
                double z = c.z + ez * r + ex * along;
                int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(x), Mth.floor(z));
                double y = surface + RANDOM.nextDouble() * (10 + d.intensity * 5);
                if (!visible(frustum, x, y, z, 0, 0)) continue;
                level.addParticle(p.haze.get(), true, true, x, y, z, id, 2.2 + RANDOM.nextDouble() * (1 - lod) * 3, variant);
            }
        }
    }

    /** Frustum culling for a particle that will travel by (dx, dz) during its life. */
    private static boolean visible(Frustum frustum, double x, double y, double z, double dx, double dz) {
        if (frustum == null) return true;
        AABB box = new AABB(x, y - 1, z, x + dx, y + 1, z + dz).inflate(2);
        return frustum.isVisible(box);
    }

    private static int count(float expected) {
        int n = (int) expected;
        if (RANDOM.nextFloat() < expected - n) n++;
        return n;
    }

    private void tickSounds(ClientDisaster d, State st, Palette p, Vec3 cam) {
        var sounds = Minecraft.getInstance().getSoundManager();
        double dist = horizontalDistance(d, cam);
        float s = d.strength();
        float in = inside(d, dist);
        float g = gust(d);
        // Close layer: the storm surrounds you, so it isn't positional. Muffled indoors, swells with gusts.
        st.howlVolume = s * in * (st.sheltered ? 0.4f : 1f) * (0.55f + 0.1f * d.intensity) * (0.7f + 0.3f * g);
        st.howlPitch = 0.85f + 0.15f * g;
        // Distant layer: low moan of the approaching / receding storm.
        float far = 1f - smoothstep(radius(d), radius(d) + 420f, (float) dist);
        st.distantVolume = s * Math.max(0.15f * in, (1f - in) * far) * (0.5f + 0.08f * d.intensity);

        if (st.howl == null || st.howl.isStopped()) {
            st.howl = new DisasterLoopSound(p.howl.get(), d, false,
                    x -> x.visualState instanceof State xs ? xs.howlVolume : 0,
                    x -> x.visualState instanceof State xs ? xs.howlPitch : 1);
            sounds.play(st.howl);
        }
        if (st.distant == null || st.distant.isStopped()) {
            st.distant = new DisasterLoopSound(p.distant.get(), d, false,
                    x -> x.visualState instanceof State xs ? xs.distantVolume : 0, x -> 1.0);
            sounds.play(st.distant);
        }
        // Individual gusts whistle past while you're inside and exposed.
        if (in > 0.3f && !st.sheltered && s > 0.2f && --st.nextGust <= 0) {
            st.nextGust = 40 + RANDOM.nextInt(80);
            double a = RANDOM.nextDouble() * Math.PI * 2;
            sounds.play(new SimpleSoundInstance(ModRegistries.WIND_GUST.get().location(), SoundSource.WEATHER,
                    (0.25f + 0.4f * s) * g, 0.9f + RANDOM.nextFloat() * 0.4f, RANDOM, false, 0,
                    SoundInstance.Attenuation.NONE, cam.x + Math.cos(a) * 6, cam.y, cam.z + Math.sin(a) * 6, false));
        }
    }

    @Override
    public void onRemoved(ClientDisaster d) {
        if (d.visualState instanceof State st) {
            if (st.howl != null) st.howl.end();
            if (st.distant != null) st.distant.end();
        }
    }
}
