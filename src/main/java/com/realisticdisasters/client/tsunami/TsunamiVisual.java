package com.realisticdisasters.client.tsunami;

import com.realisticdisasters.client.ClientDisaster;
import com.realisticdisasters.client.ClientDisasters;
import com.realisticdisasters.client.DisasterVisual;
import com.realisticdisasters.client.sound.DisasterLoopSound;
import com.realisticdisasters.config.ClientConfig;
import com.realisticdisasters.disaster.Phase;
import com.realisticdisasters.disaster.tsunami.TsunamiDisaster;
import com.realisticdisasters.registry.ModRegistries;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.particles.ParticleTypes;
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
 * Tsunami presentation: a grey sea-mist sky, the exposed seabed's foam line during the drawback, a breaking wave wall
 * (blue-green body, white crest, spray clouds) that travels with the server's wave front, foam on the flood and a
 * seaward backwash. Sounds: a positional roar that follows the nearest point of the wave front, a distant surf layer,
 * one-shot crashes as the wave breaks, and low rumbles during the warning.
 */
public final class TsunamiVisual implements DisasterVisual {
    private static final int SKY = 0x7A8C94;     // overcast sea grey
    private static final int FOG = 0x6C8590;     // salt mist
    private static final int CLOUDS = 0x5B676C;

    private static final RandomSource RANDOM = RandomSource.create();

    private static final class State {
        DisasterLoopSound roar, surf;
        int nextRumble = 30;
        int nextCrash = 10;
        float lastFront = Float.NaN;
        float frontSpeed;
    }

    // ------------------------------------------------------------------ geometry (local frame, see TsunamiDisaster)

    record Frame(double px, double pz, double dx, double dz, float front, float halfWidth, float crest, float waterY,
                 float reach, float seaDist, float drawback, float wall) {
        static Frame of(ClientDisaster d) {
            double dx = d.data(TsunamiDisaster.D_DIR_X), dz = d.data(TsunamiDisaster.D_DIR_Z);
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len < 1.0e-3) { dx = 1; dz = 0; } else { dx /= len; dz /= len; }
            return new Frame(d.pos().x, d.pos().z, dx, dz, d.data(TsunamiDisaster.D_FRONT),
                    Math.max(4f, d.data(TsunamiDisaster.D_HALF_WIDTH)), Math.max(1f, d.data(TsunamiDisaster.D_CREST)),
                    d.data(TsunamiDisaster.D_WATER_Y), Math.max(4f, d.data(TsunamiDisaster.D_REACH)),
                    Math.max(4f, d.data(TsunamiDisaster.D_SEA_DIST)), Mth.clamp(d.data(TsunamiDisaster.D_DRAWBACK), 0f, 1f),
                    Mth.clamp(d.data(TsunamiDisaster.D_WALL), 0f, 1f));
        }

        double axial(double x, double z) { return -(x - px) * dx - (z - pz) * dz; }

        double lateral(double x, double z) { return -(x - px) * dz + (z - pz) * dx; }

        double x(double axial, double lateral) { return px - dx * axial - dz * lateral; }

        double z(double axial, double lateral) { return pz - dz * axial + dx * lateral; }

        /** Horizontal distance from a point to the wave front line. */
        double frontDistance(Vec3 p) {
            double a = axial(p.x, p.z), l = lateral(p.x, p.z);
            double cl = Mth.clamp(l, -halfWidth, halfWidth);
            return Math.sqrt((a - front) * (a - front) + (l - cl) * (l - cl));
        }

        /** Horizontal distance from a point to the whole affected rectangle (0 inside). */
        double areaDistance(Vec3 p) {
            double a = axial(p.x, p.z), l = lateral(p.x, p.z);
            double ea = Math.max(0, Math.max(-seaDist - a, a - reach));
            double el = Math.max(0, Math.abs(l) - halfWidth);
            return Math.sqrt(ea * ea + el * el);
        }

        /** Point on the front nearest to {@code p}, at half the crest height. */
        Vec3 nearestFrontPoint(Vec3 p) {
            double l = Mth.clamp(lateral(p.x, p.z), -halfWidth, halfWidth);
            return new Vec3(x(front, l), waterY + 1 + crest * 0.5, z(front, l));
        }
    }

    // ------------------------------------------------------------------ atmosphere

    @Override
    public void contribute(ClientDisaster d, Vec3 cam, Atmosphere out) {
        Frame f = Frame.of(d);
        float s = d.strength();
        float area = (float) f.areaDistance(cam);
        float near = 1f - smoothstep(30f, 240f + d.intensity * 30f, area);
        float w = s * near;
        out.sky(SKY, w * 0.8f);
        out.clouds(CLOUDS, w);
        out.fogColor(FOG, w);
        out.darken(w * (0.10f + d.intensity * 0.03f));

        float toFront = (float) f.frontDistance(cam);
        float spray = f.wall() * (1f - smoothstep(10f, 90f, toFront));
        float fogEnd = 48f + (5 - d.intensity) * 14f + toFront * 0.6f;
        out.fog(fogEnd, w * (0.25f + 0.6f * spray));

        float shakeNear = 1f - smoothstep(3f, 45f, toFront);
        float shakeFar = 1f - smoothstep(45f, 180f, toFront);
        float rumble = d.phase == Phase.WARNING ? 0.04f * s : 0f;
        out.shake(f.wall() * s * (0.3f + d.intensity * 0.14f) * (shakeNear * 0.9f + shakeFar * 0.15f) + rumble);
        out.fovPulse(f.wall() * s * (1f - smoothstep(3f, 25f, toFront)));
    }

    // ------------------------------------------------------------------ particles + sound

    @Override
    public void tick(ClientDisaster d, ClientLevel level, Camera camera) {
        State st = d.visualState instanceof State s ? s : new State();
        d.visualState = st;
        Frame f = Frame.of(d);
        if (!Float.isNaN(st.lastFront)) st.frontSpeed += ((f.front() - st.lastFront) - st.frontSpeed) * 0.3f;
        st.lastFront = f.front();
        Vec3 cam = camera.position();
        tickSounds(d, f, st, cam);
        spawnParticles(d, f, st, level, camera, cam);
    }

    private void spawnParticles(ClientDisaster d, Frame f, State st, ClientLevel level, Camera camera, Vec3 cam) {
        Minecraft mc = Minecraft.getInstance();
        float density = ClientConfig.PARTICLE_DENSITY.get().floatValue();
        ParticleStatus status = mc.options.particles().get();
        density *= switch (status) {
            case ALL -> 1f;
            case DECREASED -> 0.5f;
            case MINIMAL -> 0.2f;
        };
        if (density <= 0) return;

        double toFront = f.frontDistance(cam);
        float lod = toFront < 64 ? 1f : toFront < 128 ? 0.6f : toFront < 256 ? 0.3f : 0.1f;
        double surf = f.waterY() + 1;

        // ---- the wave wall
        if (f.wall() > 0.02f) {
            AABB box = new AABB(f.x(f.front(), -f.halfWidth()), surf - 2, f.z(f.front(), -f.halfWidth()),
                    f.x(f.front(), f.halfWidth()), surf + f.crest() + 6, f.z(f.front(), f.halfWidth())).inflate(6, 0, 6);
            boolean visible = camera.getCullFrustum() == null || camera.getCullFrustum().isVisible(box);
            float wlod = visible ? lod : lod * 0.05f;
            float budget = (40 + d.intensity * 18) * density * wlod * f.wall();
            // Concentrate detail on the part of the wall nearest to the camera.
            double camLat = Mth.clamp(f.lateral(cam.x, cam.z), -f.halfWidth(), f.halfWidth());
            double speed = Mth.clamp(st.frontSpeed, 0, 1.2);
            double ix = -f.dx(), iz = -f.dz(); // inland unit vector

            int body = count(budget * 0.45f);
            for (int i = 0; i < body; i++) {
                double l = lateralSample(f, camLat);
                double h = Math.pow(RANDOM.nextDouble(), 0.8) * f.crest() * 0.9;
                double a = f.front() - RANDOM.nextDouble() * (1.5 + h * 0.35); // the face leans forward at the top
                a += h / f.crest() * 1.2;
                spawn(level, ModRegistries.WAVE_WATER.get(), f.x(a, l), surf + h - 0.5, f.z(a, l),
                        ix * speed, 0.02, iz * speed);
            }
            int crest = count(budget * 0.35f);
            for (int i = 0; i < crest; i++) {
                double l = lateralSample(f, camLat);
                double h = f.crest() * (0.85 + RANDOM.nextDouble() * 0.25);
                double a = f.front() + 0.8 + RANDOM.nextDouble() * 1.2;
                double fling = 0.15 + RANDOM.nextDouble() * 0.35;
                spawn(level, ModRegistries.WAVE_FOAM.get(), f.x(a, l), surf + h, f.z(a, l),
                        ix * (speed + fling) + (RANDOM.nextDouble() - 0.5) * 0.15, 0.12 + RANDOM.nextDouble() * 0.3,
                        iz * (speed + fling) + (RANDOM.nextDouble() - 0.5) * 0.15);
            }
            int mist = count(budget * 0.15f);
            for (int i = 0; i < mist; i++) {
                double l = lateralSample(f, camLat);
                double a = f.front() - RANDOM.nextDouble() * 3;
                spawn(level, ModRegistries.SEA_SPRAY.get(), f.x(a, l), surf + f.crest() * (0.8 + RANDOM.nextDouble() * 0.5), f.z(a, l),
                        ix * speed * 0.9, 0.03 + RANDOM.nextDouble() * 0.04, iz * speed * 0.9);
            }
            // Churning at the foot of the wave (vanilla splash, uses the water texture).
            if (toFront < 96) {
                int splash = count(budget * 0.3f);
                for (int i = 0; i < splash; i++) {
                    double l = lateralSample(f, camLat);
                    double a = f.front() + RANDOM.nextDouble() * 2;
                    level.addParticle(ParticleTypes.SPLASH, f.x(a, l), surf + RANDOM.nextDouble(), f.z(a, l),
                            ix * 0.3, 0.3, iz * 0.3);
                }
            }
        }

        // ---- the drawback: a foam line where the retreating sea meets the exposed seabed
        if (f.drawback() > 0.05f && toFront < 400) {
            float drawDist = 8 + 2 * d.intensity;
            double edge = -drawDist * f.drawback();
            int foam = count((6 + d.intensity * 3) * density * lod);
            double camLat = Mth.clamp(f.lateral(cam.x, cam.z), -f.halfWidth(), f.halfWidth());
            for (int i = 0; i < foam; i++) {
                double l = lateralSample(f, camLat);
                double a = edge - RANDOM.nextDouble() * 1.5;
                spawn(level, ModRegistries.WAVE_FOAM.get(), f.x(a, l), f.waterY() + 0.95 - drawbackDrop(d, f), f.z(a, l),
                        f.dx() * 0.08, 0.05, f.dz() * 0.08);
                if (RANDOM.nextFloat() < 0.3f) {
                    level.addParticle(ParticleTypes.BUBBLE_POP, f.x(a, l), f.waterY() + 1 - drawbackDrop(d, f), f.z(a, l), 0, 0.02, 0);
                }
            }
        }

        // ---- foam on the flood and the backwash, only around the camera (cheap, and only where it can be seen)
        boolean flooding = d.phase == Phase.PEAK || d.phase == Phase.AFTERMATH;
        if (flooding && f.areaDistance(cam) < 32) {
            float flow = d.phase == Phase.AFTERMATH ? 0.12f : -0.05f; // + = toward the sea
            int n = count((10 + d.intensity * 4) * density * d.strength());
            for (int i = 0; i < n; i++) {
                double x = cam.x + (RANDOM.nextDouble() - 0.5) * 48, z = cam.z + (RANDOM.nextDouble() - 0.5) * 48;
                double a = f.axial(x, z), l = f.lateral(x, z);
                if (a < 0 || a > Math.max(f.front(), 1) || Math.abs(l) > f.halfWidth()) continue;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(x), Mth.floor(z));
                if (y <= f.waterY() + 1 || level.getFluidState(net.minecraft.core.BlockPos.containing(x, y - 1, z)).isEmpty()) continue;
                spawn(level, ModRegistries.WAVE_FOAM.get(), x, y + 0.05, z, f.dx() * flow, 0.04, f.dz() * flow);
            }
        }
    }

    /** How far the sea surface dropped during the drawback (visual estimate, matches the server's layer count). */
    private static double drawbackDrop(ClientDisaster d, Frame f) {
        return (1 + d.intensity / 2) * f.drawback() * 0.8;
    }

    /** Half the samples near the camera's stretch of coast, the rest along the whole front. */
    private static double lateralSample(Frame f, double camLat) {
        if (RANDOM.nextBoolean()) return Mth.clamp(camLat + (RANDOM.nextDouble() - 0.5) * 40, -f.halfWidth(), f.halfWidth());
        return (RANDOM.nextDouble() * 2 - 1) * f.halfWidth();
    }

    private static int count(float expected) {
        int n = (int) expected;
        if (RANDOM.nextFloat() < expected - n) n++;
        return n;
    }

    private static void spawn(ClientLevel level, SimpleParticleType type, double x, double y, double z, double vx, double vy, double vz) {
        level.addParticle(type, true, true, x, y, z, vx, vy, vz);
    }

    private void tickSounds(ClientDisaster d, Frame f, State st, Vec3 cam) {
        var sounds = Minecraft.getInstance().getSoundManager();
        if (st.roar == null || st.roar.isStopped()) {
            // Close layer: the breaking wave, positioned on the nearest point of the front.
            st.roar = new FrontSound(ModRegistries.TSUNAMI_ROAR.get(), d, true,
                    x -> x.strength() * Frame.of(x).wall() * (2.5 + x.intensity * 0.6),
                    x -> 0.8 + 0.04 * (5 - x.intensity));
            sounds.play(st.roar);
        }
        if (st.surf == null || st.surf.isStopped()) {
            // Distant layer: heavy surf, non-attenuated, fades with distance from the coast.
            st.surf = new FrontSound(ModRegistries.TSUNAMI_SURF.get(), d, false,
                    x -> {
                        Frame fx = Frame.of(x);
                        double dist = fx.areaDistance(Minecraft.getInstance().gameRenderer.getMainCamera().position());
                        float base = x.phase == Phase.WARNING ? 0.25f : 0.55f + 0.35f * fx.wall();
                        return x.strength() * base * (1 - smoothstep(20f, 420f, (float) dist)) * (0.6 + 0.1 * x.intensity);
                    },
                    x -> x.phase == Phase.WARNING ? 0.75 : 0.9);
            sounds.play(st.surf);
        }

        if (d.phase == Phase.WARNING && --st.nextRumble <= 0) {
            st.nextRumble = 120 + RANDOM.nextInt(160);
            Vec3 src = new Vec3(f.x(-f.seaDist(), 0), f.waterY(), f.z(-f.seaDist(), 0));
            Vec3 dir = src.subtract(cam);
            Vec3 at = cam.add(dir.normalize().scale(Math.min(dir.length(), 24)));
            play(sounds, ModRegistries.DISTANT_RUMBLE.get(), 0.4f + 0.6f * d.strength() * 5f, 0.6f + RANDOM.nextFloat() * 0.2f,
                    SoundInstance.Attenuation.NONE, at);
        }
        double toFront = f.frontDistance(cam);
        if (f.wall() > 0.3f && toFront < 160 && --st.nextCrash <= 0) {
            st.nextCrash = 30 + RANDOM.nextInt(45);
            Vec3 at = f.nearestFrontPoint(cam);
            play(sounds, ModRegistries.TSUNAMI_CRASH.get(), 1.5f + d.intensity * 0.4f, 0.75f + RANDOM.nextFloat() * 0.3f,
                    SoundInstance.Attenuation.LINEAR, at);
        }
    }

    private static void play(net.minecraft.client.sounds.SoundManager sounds, SoundEvent event, float volume, float pitch,
                             SoundInstance.Attenuation att, Vec3 at) {
        sounds.play(new SimpleSoundInstance(event.location(), SoundSource.WEATHER, volume, pitch, RANDOM, false, 0,
                att, at.x, at.y, at.z, false));
    }

    @Override
    public void onRemoved(ClientDisaster d) {
        if (d.visualState instanceof State st) {
            if (st.roar != null) st.roar.end();
            if (st.surf != null) st.surf.end();
        }
    }

    /** A disaster loop that sits on the point of the wave front nearest to the listener instead of the landfall point. */
    private static final class FrontSound extends DisasterLoopSound {
        private final int disasterId;

        FrontSound(SoundEvent event, ClientDisaster d, boolean positional,
                   java.util.function.ToDoubleFunction<ClientDisaster> volumeFn,
                   java.util.function.ToDoubleFunction<ClientDisaster> pitchFn) {
            super(event, d, positional, volumeFn, pitchFn);
            this.disasterId = d.id;
        }

        @Override
        public void tick() {
            super.tick();
            if (isStopped()) return;
            ClientDisaster d = ClientDisasters.get(disasterId);
            if (d == null) return;
            Vec3 p = Frame.of(d).nearestFrontPoint(Minecraft.getInstance().gameRenderer.getMainCamera().position());
            x = p.x;
            y = p.y;
            z = p.z;
        }
    }

    // ------------------------------------------------------------------ math

    static float smoothstep(float e0, float e1, float x) {
        float t = Mth.clamp((x - e0) / (e1 - e0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }
}
