package com.realisticdisasters.client.windstorm;

import java.util.function.Supplier;

import com.realisticdisasters.client.ClientDisaster;
import com.realisticdisasters.client.DisasterVisual;
import com.realisticdisasters.client.sound.DisasterLoopSound;
import com.realisticdisasters.config.ClientConfig;
import com.realisticdisasters.disaster.Phase;
import com.realisticdisasters.disaster.windstorm.StormWind;
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
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import com.realisticdisasters.client.particle.WindParticle;
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
    /**
     * @param fog       fog colour at intensity 1; {@code fogDense} at intensity 5 (and in the strongest gusts)
     * @param darkness  sky-light reduction at intensity 3 (scaled with intensity)
     * @param wallBase  height in blocks of the storm front seen from outside, plus {@code wallPerLevel} per intensity
     */
    protected record Palette(int sky, int fog, int fogDense, int clouds, float darkness, float fogNear, float fogFar,
                             float wallBase, float wallPerLevel,
                             Supplier<SimpleParticleType> fine, Supplier<SimpleParticleType> haze,
                             Supplier<SoundEvent> howl, Supplier<SoundEvent> distant) {}

    private static final class State {
        DisasterLoopSound howl, distant;
        float howlVolume, distantVolume, howlPitch = 1f;
        boolean sheltered;
        int nextGust = 60;
        /** 0..1 wind exposure at the camera (lee of walls, roofs). */
        float exposure = 1f;
        float prevIn = -1f;
        /** Ticks left of the "front hits you" jolt. */
        int impact;
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

    /** Local gust factor at a point: the storm's surge plus the gust fronts sweeping downwind (see StormWind). */
    static float gust(ClientDisaster d, Vec3 at) {
        ClientLevel level = Minecraft.getInstance().level;
        double wx = d.data(WindStormDisaster.D_WIND_X), wz = d.data(WindStormDisaster.D_WIND_Z);
        double wl = Math.max(1.0e-3, Math.sqrt(wx * wx + wz * wz));
        long time = level == null ? 0 : level.getGameTime();
        return StormWind.gust(Mth.clamp(d.data(WindStormDisaster.D_GUST), 0.3f, 1.6f), at.x, at.z, wx / wl, wz / wl, time, d.id);
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
        float g = gust(d, cam);
        // Denser air in gusts and at higher intensity: the colour deepens as visibility drops.
        float dense = Mth.clamp((d.intensity - 1) / 4f * 0.8f + (g - 1f) * 0.6f, 0f, 1f);
        int fogColor = ARGB.srgbLerp(dense, 0xFF000000 | p.fog, 0xFF000000 | p.fogDense) & 0xFFFFFF;
        // The sky shifts from far away (warning sign). Inside, sky and fog become one colour: no horizon, flat light.
        float reach = 1f - smoothstep(radius(d), radius(d) + 260f, (float) dist);
        // As the front looms (visible from the warning phase on), the light dims and takes the storm's colour before
        // it arrives: strongest when the wall is close and towering over you.
        float front = d.phase == Phase.WARNING ? 0.25f + 0.75f * d.phaseProgress : s;
        float looming = front * (1f - smoothstep(radius(d) * 0.9f, radius(d) + 160f, (float) dist)) * (1f - in);
        out.sky(fogColor, s * in);
        out.sky(p.sky, Math.max(s * reach * 0.7f, looming * 0.85f) * (1f - in));
        out.clouds(p.clouds, Math.max(s * Math.max(in, reach * 0.7f), looming));
        out.fogColor(fogColor, Math.max(s * Math.max(in, reach * 0.5f), looming * 0.6f));
        float dark = p.darkness * (0.5f + 0.25f * (d.intensity - 1)) * (0.85f + 0.3f * Mth.clamp(g - 0.7f, 0f, 1f));
        out.darken(Math.max(s * Math.max(in, reach * 0.3f), looming * 0.6f) * dark);

        boolean sheltered = sheltered(Minecraft.getInstance().level, cam);
        // Fog end: thick inside (fogNear at intensity 1 .. fogFar at 5), pulsing with the gusts the way visibility
        // comes and goes in real footage; a wall on the horizon from outside.
        float thick = p.fogNear - (p.fogNear - p.fogFar) * (d.intensity - 1) / 4f;
        thick *= Mth.clamp(1.35f - 0.45f * g, 0.6f, 1.3f);
        float fogEnd = Mth.lerp(in, 140f + (float) Math.max(0, dist - radius(d)) * 0.5f, thick);
        if (sheltered) fogEnd *= 2.2f;
        float amount = s * Math.max(in, reach * 0.45f) * (sheltered ? 0.6f : 0.97f);
        if (d.phase == Phase.WARNING) amount *= 0.6f;
        out.fog(fogEnd, amount);

        State st = d.visualState instanceof State x ? x : null;
        float expo = st == null ? 1f : st.exposure;
        if (!sheltered) {
            // Buffeting: you feel the gust fronts, less so in the lee of a wall.
            float buffet = s * in * expo * (0.03f + 0.025f * d.intensity) * Mth.clamp((g - 0.7f) * 1.6f, 0.2f, 1.2f);
            out.shake(buffet);
            out.fovPulse(s * in * expo * 0.3f * Mth.clamp(g - 0.8f, 0f, 1f));
        }
        if (st != null && st.impact > 0) {
            // The front hits: a hard jolt that settles over a second and a half.
            float k = st.impact / 30f;
            out.shake(k * k * (0.35f + 0.08f * d.intensity));
            out.fovPulse(k);
        }
    }

    // ------------------------------------------------------------------ particles + sound

    @Override
    public void tick(ClientDisaster d, ClientLevel level, Camera camera) {
        State st = d.visualState instanceof State s ? s : new State();
        d.visualState = st;
        Vec3 cam = camera.position();
        st.sheltered = sheltered(level, cam);
        double wx = d.data(WindStormDisaster.D_WIND_X), wz = d.data(WindStormDisaster.D_WIND_Z);
        double wl = Math.max(1.0e-3, Math.sqrt(wx * wx + wz * wz));
        st.exposure = StormWind.exposure(level, cam.x, cam.y - 1.5, cam.z, wx / wl, wz / wl);
        if (st.impact > 0) st.impact--;
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
        int red = d.data(WindStormDisaster.D_VARIANT) > 0.5f ? WindParticle.RED : 0;
        float speed = Math.max(0.1f, d.data(WindStormDisaster.D_WIND_SPEED));
        float g = gust(d, cam);
        int id = d.id;

        if (in > 0.01f && s > 0.03f) {
            // Airborne flakes / grains close to the camera (further out the fog swallows them anyway).
            // Spawned upwind so they stream across the view; more in the gusts.
            float budget = (14 + d.intensity * 9) * density * s * in * (0.6f + 0.4f * g);
            int n = count(budget);
            for (int i = 0; i < n; i++) {
                double a = RANDOM.nextDouble() * Math.PI * 2;
                double r = 2.5 + Math.sqrt(RANDOM.nextDouble()) * 10;
                double x = cam.x + Math.cos(a) * r - wx * 7;
                double z = cam.z + Math.sin(a) * r - wz * 7;
                int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(x), Mth.floor(z));
                double y = cam.y - 3 + RANDOM.nextDouble() * 8;
                if (y < surface) continue;             // under a roof or inside terrain: no snow indoors
                if (!visible(frustum, x, y, z, wx * speed * 40, wz * speed * 40)) continue;
                level.addParticle(p.fine.get(), true, false, x, y, z, id, 1.0, red);
            }

            // Ground drift: ribbons of snow / sand streaming along the surface ("snow snakes", saltation).
            // Each ribbon is a short line of particles along the wind, so they read as flowing streamers.
            float ribbons = (2.5f + d.intensity * 1.5f) * density * s * in * g;
            int rn = count(ribbons);
            for (int i = 0; i < rn; i++) {
                double a = RANDOM.nextDouble() * Math.PI * 2;
                double r = 2 + Math.sqrt(RANDOM.nextDouble()) * 16;
                double x0 = cam.x + Math.cos(a) * r - wx * 8, z0 = cam.z + Math.sin(a) * r - wz * 8;
                int len = 3 + RANDOM.nextInt(5);
                for (int k = 0; k < len; k++) {
                    double x = x0 + wx * k * 0.7 + (RANDOM.nextDouble() - 0.5) * 0.3;
                    double z = z0 + wz * k * 0.7 + (RANDOM.nextDouble() - 0.5) * 0.3;
                    int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(x), Mth.floor(z));
                    if (surface > cam.y + 6 || surface < cam.y - 10) continue;
                    double y = surface + 0.05 + RANDOM.nextDouble() * 0.3;
                    if (!visible(frustum, x, y, z, wx * speed * 30, wz * speed * 30)) continue;
                    level.addParticle(p.fine.get(), true, false, x, y, z, id, 0.85, red | WindParticle.DRIFT);
                }
            }

            // Plumes: snow / sand streaming off roof edges, walls and crests (a column higher than the one upwind).
            int probes = count(6 * density * s * in);
            for (int i = 0; i < probes; i++) {
                double a = RANDOM.nextDouble() * Math.PI * 2;
                double r = 3 + RANDOM.nextDouble() * 20;
                int x = Mth.floor(cam.x + Math.cos(a) * r), z = Mth.floor(cam.z + Math.sin(a) * r);
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                int upwind = level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(x - wx * 1.5), Mth.floor(z - wz * 1.5));
                if (top <= upwind || !visible(frustum, x + 0.5, top, z + 0.5, wx * 6, wz * 6)) continue;
                for (int k = 0; k < 4; k++) {
                    level.addParticle(p.fine.get(), true, false, x + RANDOM.nextDouble(), top + 0.1 + RANDOM.nextDouble() * 0.4,
                            z + RANDOM.nextDouble(), id, 0.9, red | WindParticle.DRIFT);
                }
            }

            // Haze: builds the white-out / brown-out; gusts drive denser sheets through.
            float hazeBudget = (1.0f + d.intensity * 0.6f) * density * s * in * (0.4f + 0.6f * g * g);
            int h = count(hazeBudget);
            for (int i = 0; i < h; i++) {
                double a = RANDOM.nextDouble() * Math.PI * 2;
                double r = 6 + RANDOM.nextDouble() * 30;
                double x = cam.x + Math.cos(a) * r - wx * 16;
                double z = cam.z + Math.sin(a) * r - wz * 16;
                int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(x), Mth.floor(z));
                // Densest near the ground, thinning upward.
                double y = Math.max(surface + 0.5, cam.y - 3) + Math.pow(RANDOM.nextDouble(), 1.6) * 10;
                if (!visible(frustum, x, y, z, wx * speed * 50, wz * speed * 50)) continue;
                level.addParticle(p.haze.get(), true, false, x, y, z, id, 1.0, red);
            }
        }

        // Seen from outside: the storm front, a towering wall (a haboob for the sandstorm, a grey-white snow curtain
        // for the blizzard). Visible from the warning phase on, as it approaches. Distance LOD: fewer, bigger puffs.
        float front = d.phase == Phase.WARNING ? 0.25f + 0.75f * d.phaseProgress : s;
        if (in < 0.9f && front > 0.03f && dist < radius(d) + 300) {
            // A real front is a straight wall running off to the horizon on both sides, not a ring around you:
            // lay it along the tangent of the storm disc at the point nearest the viewer.
            float lod = dist < radius(d) + 90 ? 1f : 0.5f;
            float wallH = p.wallBase + p.wallPerLevel * d.intensity;
            int n = count((5f + d.intensity * 2.5f) * density * front * lod * (1f - in));
            Vec3 c = d.pos();
            double ex = cam.x - c.x, ez = cam.z - c.z;
            double el = Math.max(1e-3, Math.sqrt(ex * ex + ez * ez));
            ex /= el;
            ez /= el;
            double edge = radius(d) * 0.95;
            double halfLength = radius(d) + 80;
            for (int i = 0; i < n; i++) {
                double along = (RANDOM.nextDouble() * 2 - 1) * halfLength;
                double depth = (RANDOM.nextDouble() - 0.5) * 12;   // some thickness to the front
                double x = c.x + ex * (edge + depth) - ez * along;
                double z = c.z + ez * (edge + depth) + ex * along;
                int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(x), Mth.floor(z));
                float frac = RANDOM.nextFloat();
                // Billowing top: the front's height undulates along its length.
                double top = wallH * (0.8 + 0.2 * Math.sin(along * 0.045 + d.id) + 0.08 * Math.sin(along * 0.17 + d.id * 3));
                double y = surface + frac * top;
                if (!visible(frustum, x, y, z, 0, 0)) continue;
                // Dense, smaller puffs at the base; bigger billows ("cauliflower" tops) higher up.
                double size = (3 + 5 * frac) * (lod < 1 ? 1.6 : 1.0) * (0.8 + wallH / 200.0);
                level.addParticle(p.haze.get(), true, true, x, y, z, id, size, (red | WindParticle.WALL) + Math.min(0.99f, frac));
            }
            // Outflow: the gust front runs ahead of the wall as a low, fast skirt of dust / snow rolling over the ground.
            int skirt = count((2f + d.intensity) * density * front * lod * (1f - in));
            for (int i = 0; i < skirt; i++) {
                double along = (RANDOM.nextDouble() * 2 - 1) * halfLength;
                double ahead = 4 + RANDOM.nextDouble() * 26;
                double x = c.x + ex * (edge + ahead) - ez * along;
                double z = c.z + ez * (edge + ahead) + ex * along;
                int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(x), Mth.floor(z));
                double y = surface + RANDOM.nextDouble() * 2.5;
                if (!visible(frustum, x, y, z, 0, 0)) continue;
                level.addParticle(p.haze.get(), true, true, x, y, z, id, 0.8 + RANDOM.nextDouble() * 0.8,
                        (red | WindParticle.WALL | WindParticle.SKIRT) + 0.05f);
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
        float g = gust(d, cam);
        // Close layer: the storm surrounds you, so it isn't positional. Muffled indoors, swells with gusts.
        st.howlVolume = s * in * (st.sheltered ? 0.4f : 0.55f + 0.45f * st.exposure) * (0.55f + 0.1f * d.intensity) * (0.7f + 0.3f * g);
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
        // The front arrives: a roaring whoosh and a deep thump, like the moment a haboob or squall line hits.
        if (st.prevIn >= 0 && st.prevIn < 0.5f && in >= 0.5f && s > 0.35f) {
            st.impact = 30;
            sounds.play(new SimpleSoundInstance(ModRegistries.WIND_GUST.get().location(), SoundSource.WEATHER,
                    1.2f, 0.55f, RANDOM, false, 0, SoundInstance.Attenuation.NONE, cam.x, cam.y, cam.z, false));
            sounds.play(new SimpleSoundInstance(ModRegistries.DISTANT_RUMBLE.get().location(), SoundSource.WEATHER,
                    0.9f, 0.6f, RANDOM, false, 0, SoundInstance.Attenuation.NONE, cam.x, cam.y, cam.z, false));
        }
        st.prevIn = in;
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
