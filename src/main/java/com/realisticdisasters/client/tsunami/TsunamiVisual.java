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
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Tsunami presentation, modelled on footage of real events (2004 Indian Ocean, 2011 Tohoku):
 * <ul>
 *   <li>No storm: the weather stays as it is. Only spray haze thickens near the front.</li>
 *   <li>The sea draws back, leaving a foam line along the retreating edge and fish stranded on the seabed (server).</li>
 *   <li>The wave is a turbulent bore, not a towering curl: a churning white-water front with a foam toe racing
 *       ahead, dark water behind it, and spray exploding wherever it hits something.</li>
 *   <li>On land the water turns from turbid grey-green to dark brown and carries debris (also see {@link FloodTint}).
 *       It keeps rising after the front passes, a second surge rolls in, then the backwash drags everything seaward.</li>
 * </ul>
 */
public final class TsunamiVisual implements DisasterVisual {
    private static final int HAZE = 0xC4CDD0;        // spray haze near the front
    private static final int FOAM_SEA = 0xFFFFFF;    // aerated white water at sea
    private static final int FOAM_LAND = 0xE2D9C6;   // white water carrying sediment
    private static final int BODY_SEA = 0x2E4A47;    // dark, glossy grey-green water face
    private static final int BODY_LAND = 0x3F3426;   // dark, sediment- and debris-laden water on land
    private static final int SILT = 0x9C8F78;        // dirty foam streaks on the flood

    private static final RandomSource RANDOM = RandomSource.create();

    private static final class State {
        DisasterLoopSound roar, surf;
        int nextRumble = 30;
        int nextCrash = 10;
        float lastFront = Float.NaN, lastSurge = Float.NaN;
        float frontSpeed, surgeSpeed;
    }

    // ------------------------------------------------------------------ geometry (local frame, see TsunamiDisaster)

    record Frame(double px, double pz, double dx, double dz, float front, float halfWidth, float crest, float waterY,
                 float reach, float seaDist, float drawback, float wall, float surge, float surgeWall) {
        static Frame of(ClientDisaster d) {
            double dx = d.data(TsunamiDisaster.D_DIR_X), dz = d.data(TsunamiDisaster.D_DIR_Z);
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len < 1.0e-3) { dx = 1; dz = 0; } else { dx /= len; dz /= len; }
            return new Frame(d.pos().x, d.pos().z, dx, dz, d.data(TsunamiDisaster.D_FRONT),
                    Math.max(4f, d.data(TsunamiDisaster.D_HALF_WIDTH)), Math.max(1f, d.data(TsunamiDisaster.D_CREST)),
                    Math.round(d.data(TsunamiDisaster.D_WATER_Y)), Math.max(4f, d.data(TsunamiDisaster.D_REACH)),
                    Math.max(4f, d.data(TsunamiDisaster.D_SEA_DIST)), Mth.clamp(d.data(TsunamiDisaster.D_DRAWBACK), 0f, 1f),
                    Mth.clamp(d.data(TsunamiDisaster.D_WALL), 0f, 1f), d.data(TsunamiDisaster.D_SURGE),
                    Mth.clamp(d.data(TsunamiDisaster.D_SURGE_WALL), 0f, 1f));
        }

        double axial(double x, double z) { return -(x - px) * dx - (z - pz) * dz; }

        double lateral(double x, double z) { return -(x - px) * dz + (z - pz) * dx; }

        double x(double axial, double lateral) { return px - dx * axial - dz * lateral; }

        double z(double axial, double lateral) { return pz - dz * axial + dx * lateral; }

        double frontDistance(Vec3 p, double frontAxial) {
            double a = axial(p.x, p.z), l = lateral(p.x, p.z);
            double cl = Mth.clamp(l, -halfWidth, halfWidth);
            return Math.sqrt((a - frontAxial) * (a - frontAxial) + (l - cl) * (l - cl));
        }

        double areaDistance(Vec3 p) {
            double a = axial(p.x, p.z), l = lateral(p.x, p.z);
            double ea = Math.max(0, Math.max(-seaDist - a, a - reach));
            double el = Math.max(0, Math.abs(l) - halfWidth);
            return Math.sqrt(ea * ea + el * el);
        }

        Vec3 nearestFrontPoint(Vec3 p, double frontAxial, double height) {
            double l = Mth.clamp(lateral(p.x, p.z), -halfWidth, halfWidth);
            return new Vec3(x(frontAxial, l), waterY + 1 + height, z(frontAxial, l));
        }

        /** The front that matters most right now: the main bore, or the second surge once that is the active one. */
        float activeFront() { return surgeWall > wall ? surge : front; }

        float activeWall() { return Math.max(wall, surgeWall); }
    }

    // ------------------------------------------------------------------ atmosphere

    @Override
    public void contribute(ClientDisaster d, Vec3 cam, Atmosphere out) {
        Frame f = Frame.of(d);
        float s = d.strength();
        // Real tsunamis usually arrive in ordinary weather: no sky tint. Only the spray haze is felt near the front.
        float toFront = (float) f.frontDistance(cam, f.activeFront());
        float spray = f.activeWall() * (1f - smoothstep(6f, 70f, toFront));
        out.fogColor(HAZE, spray);
        out.fog(28f + toFront * 0.9f + (5 - d.intensity) * 8f, spray * 0.8f);

        float near = 1f - smoothstep(3f, 40f, toFront);
        float far = 1f - smoothstep(40f, 160f, toFront);
        out.shake(f.activeWall() * s * (0.25f + d.intensity * 0.12f) * (near * 0.9f + far * 0.12f));
        out.fovPulse(f.activeWall() * s * (1f - smoothstep(3f, 22f, toFront)) * 0.7f);
    }

    // ------------------------------------------------------------------ particles + sound

    @Override
    public void tick(ClientDisaster d, ClientLevel level, Camera camera) {
        State st = d.visualState instanceof State s ? s : new State();
        d.visualState = st;
        Frame f = Frame.of(d);
        if (!Float.isNaN(st.lastFront)) st.frontSpeed += ((f.front() - st.lastFront) - st.frontSpeed) * 0.3f;
        if (!Float.isNaN(st.lastSurge)) st.surgeSpeed += ((f.surge() - st.lastSurge) - st.surgeSpeed) * 0.3f;
        st.lastFront = f.front();
        st.lastSurge = f.surge();
        FloodTint.update(d.id, new FloodTint.Zone(f.px(), f.pz(), f.dx(), f.dz(), f.halfWidth(), f.reach(), (int) f.waterY()));
        Vec3 cam = camera.position();
        tickSounds(d, f, st, cam);
        spawnParticles(d, f, st, level, camera, cam);
    }

    private static float density() {
        float density = ClientConfig.PARTICLE_DENSITY.get().floatValue();
        ParticleStatus status = Minecraft.getInstance().options.particles().get();
        return density * switch (status) {
            case ALL -> 1f;
            case DECREASED -> 0.5f;
            case MINIMAL -> 0.2f;
        };
    }

    private void spawnParticles(ClientDisaster d, Frame f, State st, ClientLevel level, Camera camera, Vec3 cam) {
        float density = density();
        if (density <= 0) return;
        double camLat = Mth.clamp(f.lateral(cam.x, cam.z), -f.halfWidth(), f.halfWidth());

        if (f.wall() > 0.02f) {
            bore(d, f, level, camera, cam, camLat, density, f.front(), f.wall(), f.crest(), Mth.clamp(st.frontSpeed, 0, 1.2));
        }
        if (f.surgeWall() > 0.02f) {
            bore(d, f, level, camera, cam, camLat, density, f.surge(), f.surgeWall(), Math.max(1.5f, f.crest() * 0.6f),
                    Mth.clamp(st.surgeSpeed, 0, 1.2));
        }
        drawbackEdge(d, f, level, cam, camLat, density);
        floodSurface(d, f, level, cam, density);
    }

    /**
     * One travelling bore. Cross-section, from inland to seaward: a low foam toe racing ahead, the steep churning
     * face (white at the top, darker below), dark turbulent water behind it, and spray hanging above.
     */
    private void bore(ClientDisaster d, Frame f, ClientLevel level, Camera camera, Vec3 cam, double camLat, float density,
                      float front, float wall, float crest, double speed) {
        double toFront = f.frontDistance(cam, front);
        float lod = toFront < 64 ? 1f : toFront < 128 ? 0.6f : toFront < 256 ? 0.3f : 0.1f;
        double surf = f.waterY() + 1;
        AABB box = new AABB(f.x(front, -f.halfWidth()), surf - 2, f.z(front, -f.halfWidth()),
                f.x(front, f.halfWidth()), surf + crest * 1.6 + 4, f.z(front, f.halfWidth())).inflate(8, 0, 8);
        boolean visible = camera.getCullFrustum() == null || camera.getCullFrustum().isVisible(box);
        if (!visible) lod *= 0.05f;
        float budget = (110 + d.intensity * 40) * density * lod * wall;
        boolean land = front > 0;
        int foam = land ? FOAM_LAND : FOAM_SEA;
        int body = land ? BODY_LAND : BODY_SEA;
        double ix = -f.dx(), iz = -f.dz(); // inland unit vector
        // Far away the particles grow so the front still reads as a continuous white line on the horizon.
        float baseScale = 1f + (float) Math.max(0, toFront - 80) / 160f;
        WaveParticle.sizeScale = baseScale;

        // Steep face: churning white water, darker toward the base.
        int face = count(budget * 0.38f);
        for (int i = 0; i < face; i++) {
            double l = lateralSample(f, camLat);
            double hf = Math.pow(RANDOM.nextDouble(), 0.7);
            double a = front - RANDOM.nextDouble() * 1.5 + hf * 0.8;
            // dark water face, aerated white over the top third, and white churn breaking through it
            float white = RANDOM.nextFloat() < 0.35f ? 1f : smoothstep(0.15f, 0.5f, (float) hf);
            WaveParticle.tint = shade(lerpColor(body, foam, white));
            WaveParticle.sizeScale = (white < 0.5f ? 0.7f : 1f) * baseScale;
            spawn(level, ModRegistries.WAVE_WATER.get(), f.x(a, l), surf + hf * crest - 0.6, f.z(a, l),
                    ix * speed + jitter(0.05), 0.04 + RANDOM.nextDouble() * 0.06, iz * speed + jitter(0.05));
        }
        // Foam toe: low white water spilling ahead of the face.
        int toe = count(budget * 0.18f);
        for (int i = 0; i < toe; i++) {
            double l = lateralSample(f, camLat);
            double a = front + 0.5 + RANDOM.nextDouble() * 2.5;
            WaveParticle.tint = shade(foam);
            WaveParticle.sizeScale = baseScale;
            spawn(level, ModRegistries.WAVE_WATER.get(), f.x(a, l), surf - 0.3 + RANDOM.nextDouble() * 0.6, f.z(a, l),
                    ix * speed * 1.25, 0.01, iz * speed * 1.25);
        }
        // Dark turbulent water behind the face, streaked with white.
        int behind = count(budget * 0.22f);
        for (int i = 0; i < behind; i++) {
            double l = lateralSample(f, camLat);
            double back = RANDOM.nextDouble() * 7;
            double a = front - 1 - back;
            double h = crest * (0.75 - back / 14) * RANDOM.nextDouble();
            WaveParticle.tint = shade(RANDOM.nextFloat() < 0.65f ? foam : lerpColor(body, foam, 0.35f));
            spawn(level, ModRegistries.WAVE_WATER.get(), f.x(a, l), surf + Math.max(0, h) - 0.7, f.z(a, l),
                    ix * speed * 0.9, 0.02, iz * speed * 0.9);
        }
        // Spray: fine haze over the crest, and droplets flung forward off the top.
        int mist = count(budget * 0.05f);
        for (int i = 0; i < mist; i++) {
            double l = lateralSample(f, camLat);
            double a = front - RANDOM.nextDouble() * 2;
            spawn(level, ModRegistries.SEA_SPRAY.get(), f.x(a, l), surf + crest * (0.85 + RANDOM.nextDouble() * 0.3), f.z(a, l),
                    ix * speed * 0.8 + jitter(0.03), 0.03 + RANDOM.nextDouble() * 0.05, iz * speed * 0.8 + jitter(0.03));
        }
        WaveParticle.sizeScale = 1f;
        if (toFront < 90) {
            int drops = count(budget * 0.06f);
            for (int i = 0; i < drops; i++) {
                double l = lateralSample(f, camLat);
                double a = front + RANDOM.nextDouble();
                double fling = 0.1 + RANDOM.nextDouble() * 0.3;
                spawn(level, ModRegistries.WAVE_FOAM.get(), f.x(a, l), surf + crest * (0.8 + RANDOM.nextDouble() * 0.3), f.z(a, l),
                        ix * (speed + fling) + jitter(0.1), 0.15 + RANDOM.nextDouble() * 0.25, iz * (speed + fling) + jitter(0.1));
            }
            obstacleSpray(f, level, camLat, front, crest, speed, foam, density * wall);
        }
    }

    /** Where the bore slams into something solid (a wall, a house, a tree) it explodes upward in spray. */
    private void obstacleSpray(Frame f, ClientLevel level, double camLat, float front, float crest, double speed, int foam, float strength) {
        int probes = count(6 * strength);
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        for (int p = 0; p < probes; p++) {
            double l = Mth.clamp(camLat + (RANDOM.nextDouble() - 0.5) * 60, -f.halfWidth(), f.halfWidth());
            double a = front + 1.5;
            int bx = Mth.floor(f.x(a, l)), bz = Mth.floor(f.z(a, l));
            int y0 = (int) f.waterY() + 1;
            boolean hit = false;
            for (int y = y0; y <= y0 + Math.max(1, (int) (crest * 0.7)); y++) {
                BlockState bs = level.getBlockState(mp.set(bx, y, bz));
                if (!bs.isAir() && bs.getFluidState().isEmpty() && bs.blocksMotion()) { hit = true; break; }
            }
            if (!hit) continue;
            double ix = -f.dx(), iz = -f.dz();
            for (int k = 0; k < 10; k++) {
                spawn(level, ModRegistries.WAVE_FOAM.get(), bx + 0.5 - ix * 0.8, y0 + RANDOM.nextDouble() * crest * 0.6, bz + 0.5 - iz * 0.8,
                        jitter(0.25) - ix * 0.1, 0.35 + RANDOM.nextDouble() * 0.45, jitter(0.25) - iz * 0.1);
            }
            for (int k = 0; k < 3; k++) {
                WaveParticle.tint = foam;
                spawn(level, ModRegistries.WAVE_WATER.get(), bx + 0.5 - ix, y0 + crest * (0.4 + RANDOM.nextDouble() * 0.6), bz + 0.5 - iz,
                        jitter(0.08), 0.18 + RANDOM.nextDouble() * 0.2, jitter(0.08));
                spawn(level, ModRegistries.SEA_SPRAY.get(), bx + 0.5, y0 + crest + RANDOM.nextDouble() * 2, bz + 0.5,
                        ix * speed * 0.5 + jitter(0.04), 0.06, iz * speed * 0.5 + jitter(0.04));
            }
        }
    }

    /** The retreating sea: a frothy edge on the water, bubbles and wet glints on the exposed seabed. */
    private void drawbackEdge(ClientDisaster d, Frame f, ClientLevel level, Vec3 cam, double camLat, float density) {
        if (f.drawback() < 0.05f || f.frontDistance(cam, 0) > 300) return;
        float drawDist = 14 + 4 * d.intensity;
        double edge = -drawDist * f.drawback();
        double drop = (1 + d.intensity / 2) * f.drawback() * 0.8;
        int n = count((6 + d.intensity * 2) * density);
        for (int i = 0; i < n; i++) {
            double l = lateralSample(f, camLat);
            double a = edge - RANDOM.nextDouble() * 1.5;
            WaveParticle.tint = FOAM_SEA;
            WaveParticle.sizeScale = 0.35f;
            spawn(level, ModRegistries.WAVE_WATER.get(), f.x(a, l), f.waterY() + 0.85 - drop, f.z(a, l),
                    f.dx() * 0.06, 0.0, f.dz() * 0.06);
            WaveParticle.sizeScale = 1f;
            if (RANDOM.nextFloat() < 0.4f) {
                level.addParticle(ParticleTypes.BUBBLE_POP, f.x(a, l), f.waterY() + 1 - drop, f.z(a, l), 0, 0.02, 0);
            }
        }
        // water still draining off the exposed seabed
        int drips = count(4 * density * f.drawback());
        for (int i = 0; i < drips; i++) {
            double l = lateralSample(f, camLat);
            double a = edge * RANDOM.nextDouble();
            level.addParticle(ParticleTypes.SPLASH, f.x(a, l), f.waterY() + 1 - drop * RANDOM.nextDouble(), f.z(a, l), 0, 0, 0);
        }
    }

    /** Silt streaks and floating debris riding the current; near the camera only (cheap and where it can be seen). */
    private void floodSurface(ClientDisaster d, Frame f, ClientLevel level, Vec3 cam, float density) {
        boolean flooding = d.phase == Phase.PEAK || d.phase == Phase.AFTERMATH;
        if (!flooding || f.areaDistance(cam) > 40) return;
        boolean back = d.phase == Phase.AFTERMATH;
        // + = toward the sea. The backwash is fast and drags everything out.
        double flow = back ? 0.16 + 0.03 * d.intensity : (f.wall() > 0.1f ? -0.12 : -0.04);
        int n = count((14 + d.intensity * 5) * density * Math.max(0.3f, d.strength()));
        for (int i = 0; i < n; i++) {
            double x = cam.x + (RANDOM.nextDouble() - 0.5) * 56, z = cam.z + (RANDOM.nextDouble() - 0.5) * 56;
            double a = f.axial(x, z), l = f.lateral(x, z);
            if (a < -2 || a > Math.max(f.front(), 1) || Math.abs(l) > f.halfWidth()) continue;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(x), Mth.floor(z));
            if (y <= f.waterY() + 1 && a > 0) continue;
            if (level.getFluidState(BlockPos.containing(x, y - 1, z)).isEmpty()) continue;
            double vx = f.dx() * flow + jitter(0.02), vz = f.dz() * flow + jitter(0.02);
            if (RANDOM.nextFloat() < 0.25f) {
                spawn(level, ModRegistries.FLOOD_DEBRIS.get(), x, y + 0.02, z, vx, 0, vz);
            } else {
                WaveParticle.tint = back && a < 3 ? FOAM_LAND : SILT;
                WaveParticle.sizeScale = 0.35f;
                spawn(level, ModRegistries.WAVE_WATER.get(), x, y - 0.12, z, vx, 0, vz);
                WaveParticle.sizeScale = 1f;
            }
        }
    }

    private static double lateralSample(Frame f, double camLat) {
        if (RANDOM.nextBoolean()) return Mth.clamp(camLat + (RANDOM.nextDouble() - 0.5) * 50, -f.halfWidth(), f.halfWidth());
        return (RANDOM.nextDouble() * 2 - 1) * f.halfWidth();
    }

    private static double jitter(double amount) {
        return (RANDOM.nextDouble() - 0.5) * 2 * amount;
    }

    private static int count(float expected) {
        int n = (int) expected;
        if (RANDOM.nextFloat() < expected - n) n++;
        return n;
    }

    private static void spawn(ClientLevel level, SimpleParticleType type, double x, double y, double z, double vx, double vy, double vz) {
        level.addParticle(type, true, true, x, y, z, vx, vy, vz);
    }

    /** Slight random light/shadow variation so overlapping foam reads as depth instead of flat blobs. */
    private static int shade(int rgb) {
        float k = 0.82f + RANDOM.nextFloat() * 0.18f;
        return lerpColor(0, rgb, k);
    }

    private static int lerpColor(int a, int b, float t) {
        int r = (int) Mth.lerp(t, (a >> 16) & 255, (b >> 16) & 255);
        int g = (int) Mth.lerp(t, (a >> 8) & 255, (b >> 8) & 255);
        int bl = (int) Mth.lerp(t, a & 255, b & 255);
        return r << 16 | g << 8 | bl;
    }

    // ------------------------------------------------------------------ sound

    private void tickSounds(ClientDisaster d, Frame f, State st, Vec3 cam) {
        var sounds = Minecraft.getInstance().getSoundManager();
        if (st.roar == null || st.roar.isStopped()) {
            // Close layer: the roar of the bore and the grinding debris it carries, on the nearest point of the front.
            st.roar = new FrontSound(ModRegistries.TSUNAMI_ROAR.get(), d, true,
                    x -> x.strength() * Frame.of(x).activeWall() * (2.5 + x.intensity * 0.6),
                    x -> 0.8 + 0.04 * (5 - x.intensity));
            sounds.play(st.roar);
        }
        if (st.surf == null || st.surf.isStopped()) {
            // Distant layer: heavy surf / rushing water, fades with distance from the coast.
            st.surf = new FrontSound(ModRegistries.TSUNAMI_SURF.get(), d, false,
                    x -> {
                        Frame fx = Frame.of(x);
                        double dist = fx.areaDistance(Minecraft.getInstance().gameRenderer.getMainCamera().position());
                        float base = x.phase == Phase.WARNING ? 0.15f : x.phase == Phase.AFTERMATH ? 0.5f : 0.55f + 0.35f * fx.activeWall();
                        return x.strength() * base * (1 - smoothstep(20f, 420f, (float) dist)) * (0.6 + 0.1 * x.intensity);
                    },
                    x -> x.phase == Phase.WARNING ? 0.75 : 0.9);
            sounds.play(st.surf);
        }

        if (d.phase == Phase.WARNING && --st.nextRumble <= 0) {
            // A distant boom/rumble from the sea while the water draws back.
            st.nextRumble = 140 + RANDOM.nextInt(180);
            Vec3 src = new Vec3(f.x(-f.seaDist(), 0), f.waterY(), f.z(-f.seaDist(), 0));
            Vec3 dir = src.subtract(cam);
            Vec3 at = cam.add(dir.normalize().scale(Math.min(dir.length(), 24)));
            play(sounds, ModRegistries.DISTANT_RUMBLE.get(), 0.3f + 2.5f * d.strength(), 0.55f + RANDOM.nextFloat() * 0.2f,
                    SoundInstance.Attenuation.NONE, at);
        }
        float front = f.activeFront();
        if (f.activeWall() > 0.25f && f.frontDistance(cam, front) < 160 && --st.nextCrash <= 0) {
            st.nextCrash = 25 + RANDOM.nextInt(40);
            Vec3 at = f.nearestFrontPoint(cam, front, f.crest() * 0.5);
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
        FloodTint.remove(d.id);
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
            Frame f = Frame.of(d);
            Vec3 p = f.nearestFrontPoint(Minecraft.getInstance().gameRenderer.getMainCamera().position(), f.activeFront(), f.crest() * 0.5);
            x = p.x;
            y = p.y;
            z = p.z;
        }
    }

    static float smoothstep(float e0, float e1, float x) {
        float t = Mth.clamp((x - e0) / (e1 - e0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }
}
