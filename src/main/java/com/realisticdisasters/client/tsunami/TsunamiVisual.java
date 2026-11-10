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
 *   <li>The wave is a turbulent bore, not a towering curl. It is drawn as water by {@link WaveRenderer} (one body
 *       with the sea and the flood); particles only add droplets off the crest and splashes where it hits something.</li>
 *   <li>On land the water turns from turbid grey-green to dark brown and carries debris (also see {@link FloodTint}).
 *       It keeps rising after the front passes, a second surge rolls in, then the backwash drags everything seaward.</li>
 * </ul>
 */
public final class TsunamiVisual implements DisasterVisual {
    private static final int HAZE = 0xC4CDD0;        // spray haze near the front
    private static final int GRADE_SKY = 0x8FA6B2;   // cooler, deeper sky: a restrained cinematic grade
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
        // Unsmoothed fronts, extrapolated between server packets, so the drawn wave stays on the real water's edge.
        float rawFront = Float.NaN, rawSpeed, predFront, prevPred;
        int rawAge;
        float frontSpeed, surgeSpeed;
        boolean landfall;
        int impact;      // ticks left of the landfall eruption
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
        // Real tsunamis usually arrive in ordinary weather, so no storm. Only a restrained grade as the wave closes in:
        // a cooler, deeper sky and slightly darker land, and spray haze near the front.
        float toFront = (float) f.frontDistance(cam, f.activeFront());
        float drama = s * (1f - smoothstep(40f, 260f, (float) f.areaDistance(cam)))
                * Math.max(f.activeWall(), d.phase == Phase.WARNING ? 0.3f : 0f);
        out.sky(GRADE_SKY, drama * 0.4f);
        out.darken(drama * (0.05f + 0.02f * d.intensity));
        float spray = f.activeWall() * (1f - smoothstep(6f, 70f, toFront));
        out.fogColor(HAZE, spray);
        out.fog(28f + toFront * 0.9f + (5 - d.intensity) * 8f, spray * 0.8f);

        float near = 1f - smoothstep(3f, 40f, toFront);
        float far = 1f - smoothstep(40f, 160f, toFront);
        out.shake(f.activeWall() * s * (0.25f + d.intensity * 0.12f) * (near * 0.9f + far * 0.12f));
        out.fovPulse(f.activeWall() * s * (1f - smoothstep(3f, 22f, toFront)) * 0.7f);
        // Landfall: the whole coast erupts; a jolt felt far inland.
        if (d.visualState instanceof State st && st.impact > 0) {
            float k = st.impact / 30f;
            float dist = (float) f.frontDistance(cam, 0);
            out.shake(k * (0.4f + 0.15f * d.intensity) * (1f - smoothstep(10f, 220f, dist)));
            out.fovPulse(k * (1f - smoothstep(5f, 60f, dist)));
        }
        // The bore reaching you: a hard hit.
        if (toFront < 4f && f.activeWall() > 0.2f) {
            out.shake(0.6f + 0.1f * d.intensity);
            out.fovPulse(1f);
        }
    }

    // ------------------------------------------------------------------ particles + sound

    @Override
    public void tick(ClientDisaster d, ClientLevel level, Camera camera) {
        State st = d.visualState instanceof State s ? s : new State();
        d.visualState = st;
        Frame f = Frame.of(d);
        if (!Float.isNaN(st.lastFront)) st.frontSpeed += ((f.front() - st.lastFront) - st.frontSpeed) * 0.3f;
        if (!Float.isNaN(st.lastSurge)) st.surgeSpeed += ((f.surge() - st.lastSurge) - st.surgeSpeed) * 0.3f;
        // The wave itself: a water surface joined to the real water behind and ahead (see WaveRenderer).
        java.util.List<WaveRenderer.Front> fronts = new java.util.ArrayList<>(2);
        float raw = d.data.length > TsunamiDisaster.D_FRONT ? d.data[TsunamiDisaster.D_FRONT] : f.front();
        if (Float.isNaN(st.rawFront) || Math.abs(raw - st.rawFront) > 8f) {
            st.rawFront = raw;
            st.predFront = st.prevPred = raw;
            st.rawAge = 0;
        } else if (raw != st.rawFront) {
            st.rawSpeed = Math.max(0f, (raw - st.rawFront) / Math.max(1, st.rawAge));
            st.rawFront = raw;
            st.rawAge = 0;
        }
        st.rawAge++;
        st.prevPred = st.predFront;
        float target = st.rawFront + st.rawSpeed * st.rawAge;
        st.predFront += (target - st.predFront) * 0.6f;
        if (f.wall() > 0.02f) {
            fronts.add(sampleFront(level, f, st.prevPred, st.predFront, f.crest(), f.wall()));
        }
        if (f.surgeWall() > 0.02f) {
            fronts.add(sampleFront(level, f, Float.isNaN(st.lastSurge) ? f.surge() : st.lastSurge, f.surge(),
                    Math.max(1.5f, f.crest() * 0.6f), f.surgeWall()));
        }
        WaveRenderer.set(d.id, fronts);
        st.lastFront = f.front();
        st.lastSurge = f.surge();
        FloodTint.update(d.id, new FloodTint.Zone(f.px(), f.pz(), f.dx(), f.dz(), f.halfWidth(), f.reach(), (int) f.waterY()));
        Vec3 cam = camera.position();
        if (!st.landfall && f.wall() > 0.4f && f.front() > -2.5f && (d.phase == Phase.BUILDUP || d.phase == Phase.PEAK)) {
            st.landfall = true;
            st.impact = 30;
            landfallSound(d, f, cam);
        }
        tickSounds(d, f, st, cam);
        spawnParticles(d, f, st, level, camera, cam);
        if (st.impact > 0) {
            landfallEruption(d, f, level, camera, cam, st.impact);
            st.impact--;
        }
        underwater(d, level, camera, cam);
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

        // The wave body and its white water are drawn as a water surface by WaveRenderer; particles only add the
        // spray that real breaking water throws off: droplets flung from the crest and bursts against obstacles.
        WaveParticle.sizeScale = 1f;
        if (toFront < 90) {
            int drops = count(budget * 0.08f);
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

    /**
     * Landfall: the bore slams into the coast and white water erupts along the whole shoreline, far higher than the
     * wave itself, then collapses forward over the land.
     */
    private void landfallEruption(ClientDisaster d, Frame f, ClientLevel level, Camera camera, Vec3 cam, int ticksLeft) {
        float density = density();
        double dist = f.frontDistance(cam, 0);
        float lod = dist < 96 ? 1f : dist < 200 ? 0.5f : 0.2f;
        float k = ticksLeft / 30f;
        double surf = f.waterY() + 1;
        double camLat = Mth.clamp(f.lateral(cam.x, cam.z), -f.halfWidth(), f.halfWidth());
        double ix = -f.dx(), iz = -f.dz();
        float height = 0.12f + 0.05f * d.intensity; // initial upward speed of the plume
        int n = count((70 + 30 * d.intensity) * density * lod * k);
        for (int i = 0; i < n; i++) {
            double l = lateralSample(f, camLat);
            double a = RANDOM.nextDouble() * 2 - 1;
            double up = height * (0.5 + RANDOM.nextDouble()) * (0.6 + 0.4 * k);
            level.addParticle(ParticleTypes.SPLASH, f.x(a, l), surf + RANDOM.nextDouble(), f.z(a, l), ix * 0.3, up * 3, iz * 0.3);
            if (dist < 160) {
                WaveParticle.sizeScale = 1f;
                spawn(level, ModRegistries.WAVE_FOAM.get(), f.x(a, l), surf + 1 + RANDOM.nextDouble() * 4, f.z(a, l),
                        ix * 0.25 + jitter(0.15), up * 5, iz * 0.25 + jitter(0.15));
            }
        }
        WaveParticle.sizeScale = 1f;
    }

    /** Submerged in the flood: silt and debris fragments drifting past in the murk, bubbles rising. */
    private void underwater(ClientDisaster d, ClientLevel level, Camera camera, Vec3 cam) {
        if (camera.getFluidInCamera() != net.minecraft.world.level.material.FogType.WATER) return;
        float inland = FloodTint.inland(cam.x, cam.y, cam.z);
        if (inland < 0) return;
        Frame f = Frame.of(d);
        Vec3 flow = new Vec3(-f.dx(), 0, -f.dz()).scale(d.phase == Phase.AFTERMATH ? -0.12 : 0.06 + f.activeWall() * 0.2);
        int n = count(10 * density());
        for (int i = 0; i < n; i++) {
            double x = cam.x + jitter(5), y = cam.y + jitter(3), z = cam.z + jitter(5);
            WaveParticle.tint = FloodTint.mudColor(inland);
            WaveParticle.sizeScale = 0.12f;
            spawn(level, ModRegistries.WAVE_WATER.get(), x, y, z, flow.x + jitter(0.02), jitter(0.01), flow.z + jitter(0.02));
            if (RANDOM.nextFloat() < 0.3f) level.addParticle(ParticleTypes.BUBBLE, x, y, z, flow.x, 0.05, flow.z);
            if (RANDOM.nextFloat() < 0.15f) spawn(level, ModRegistries.FLOOD_DEBRIS.get(), x, y, z, flow.x, 0, flow.z);
        }
        WaveParticle.sizeScale = 1f;
    }

    /**
     * Samples one wave front for {@link WaveRenderer}: per metre of coastline, the crest height (tapering into the water
     * at both ends), the height of the water or ground behind and ahead of the wave, the water colour and the light.
     */
    private static WaveRenderer.Front sampleFront(ClientLevel level, Frame f, float prevAxial, float axial, float crest, float wall) {
        WaveRenderer.Front w = new WaveRenderer.Front();
        w.px = f.px();
        w.pz = f.pz();
        w.dx = f.dx();
        w.dz = f.dz();
        w.prevAxial = prevAxial;
        w.axial = axial;
        w.baseY = f.waterY() + WaveRenderer.WATER_TOP;
        float hw = f.halfWidth();
        int lines = (int) Math.ceil(hw * 2) + 1;
        w.lines = lines;
        w.lat0 = -hw;
        w.height = new float[lines];
        w.back = new float[lines];
        w.ahead = new float[lines];
        w.tint = new int[lines];
        w.light = new int[lines];
        w.foam = wall;
        float h = crest * wall;
        float backLen = 5f + h * 1.5f;
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        for (int i = 0; i < lines; i++) {
            float l = Math.min(hw, -hw + i);
            float taper = 1f - (float) Math.pow(Math.min(1f, Math.abs(l) / hw), 4);
            w.height[i] = h * taper;
            w.back[i] = surfaceHeight(level, f.x(axial - backLen, l), f.z(axial - backLen, l), w.baseY, mp);
            w.ahead[i] = Math.min(w.height[i] + 1f, surfaceHeight(level, f.x(axial + 0.7, l), f.z(axial + 0.7, l), w.baseY, mp));
            int bx = Mth.floor(f.x(axial, l)), bz = Mth.floor(f.z(axial, l));
            BlockPos sea = new BlockPos(bx, (int) f.waterY(), bz);
            // Sea-coloured at sea; it takes on the flood's sediment colour only as it runs up onto the land.
            int water = net.minecraft.client.renderer.BiomeColors.getAverageWaterColor(level, sea) & 0xFFFFFF;
            int mud = FloodTint.apply(sea.above(), water) & 0xFFFFFF;
            w.tint[i] = lerpColor(water, mud, Mth.clamp(axial / 8f, 0f, 1f));
            w.light[i] = net.minecraft.client.renderer.LevelRenderer.getLightCoords(level,
                    mp.set(bx, Mth.floor(w.baseY + w.height[i] * 0.5f), bz));
        }
        return w;
    }

    /**
     * Height of the water surface at a column above the sea surface (0 where there is no water: dry ground at the
     * waterline). Only water counts - never a roof or a tree above it.
     */
    private static float surfaceHeight(ClientLevel level, double x, double z, float baseY, BlockPos.MutableBlockPos mp) {
        int bx = Mth.floor(x), bz = Mth.floor(z);
        int y0 = Mth.floor(baseY);
        for (int y = y0 + 10; y >= y0; y--) {
            if (!level.getFluidState(mp.set(bx, y, bz)).isEmpty()) {
                return Mth.clamp(y + WaveRenderer.WATER_TOP - baseY, 0f, 10f);
            }
        }
        return 0f;
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
            for (int k = 0; k < 16; k++) {
                spawn(level, ModRegistries.WAVE_FOAM.get(), bx + 0.5 - ix * 0.8, y0 + RANDOM.nextDouble() * crest * 0.6, bz + 0.5 - iz * 0.8,
                        jitter(0.25) - ix * 0.1, 0.35 + RANDOM.nextDouble() * 0.45, jitter(0.25) - iz * 0.1);
            }
            for (int k = 0; k < 6; k++) {
                level.addParticle(ParticleTypes.SPLASH, bx + 0.5 - ix, y0 + RANDOM.nextDouble() * crest * 0.5, bz + 0.5 - iz,
                        jitter(0.2), 0.4 + RANDOM.nextDouble() * 0.3, jitter(0.2));
            }
        }
    }

    /** The retreating sea: a frothy edge on the water, bubbles and wet glints on the exposed seabed. */
    private void drawbackEdge(ClientDisaster d, Frame f, ClientLevel level, Vec3 cam, double camLat, float density) {
        // Only while the sea is pulling back; once the wave is coming the returning water swallows the edge.
        if (d.phase != Phase.WARNING || f.drawback() < 0.05f || f.frontDistance(cam, 0) > 300) return;
        float drawDist = 14 + 4 * d.intensity;
        double edge = -drawDist * f.drawback();
        double drop = (1 + d.intensity / 2) * f.drawback() * 0.8;
        int n = count((6 + d.intensity * 2) * density);
        for (int i = 0; i < n; i++) {
            double l = lateralSample(f, camLat);
            double a = edge - RANDOM.nextDouble() * 1.5;
            level.addParticle(ParticleTypes.SPLASH, f.x(a, l), f.waterY() + 0.9 - drop, f.z(a, l), f.dx() * 0.06, 0.05, f.dz() * 0.06);
            if (RANDOM.nextFloat() < 0.6f) {
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
            if (RANDOM.nextFloat() < 0.3f) spawn(level, ModRegistries.FLOOD_DEBRIS.get(), x, y + 0.02, z, vx, 0, vz);
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

    /** The boom of landfall: a deep crash heard far inland, with a low rumble under it. */
    private static void landfallSound(ClientDisaster d, Frame f, Vec3 cam) {
        var sounds = Minecraft.getInstance().getSoundManager();
        Vec3 at = f.nearestFrontPoint(cam, 0, 2);
        Vec3 dir = at.subtract(cam);
        Vec3 near = cam.add(dir.normalize().scale(Math.min(dir.length(), 12)));
        play(sounds, ModRegistries.TSUNAMI_CRASH.get(), 2.5f + d.intensity * 0.5f, 0.55f, SoundInstance.Attenuation.NONE, near);
        play(sounds, ModRegistries.DISTANT_RUMBLE.get(), 2.5f, 0.5f, SoundInstance.Attenuation.NONE, near);
    }

    private static void play(net.minecraft.client.sounds.SoundManager sounds, SoundEvent event, float volume, float pitch,
                             SoundInstance.Attenuation att, Vec3 at) {
        sounds.play(new SimpleSoundInstance(event.location(), SoundSource.WEATHER, volume, pitch, RANDOM, false, 0,
                att, at.x, at.y, at.z, false));
    }

    @Override
    public void onRemoved(ClientDisaster d) {
        FloodTint.remove(d.id);
        WaveRenderer.remove(d.id);
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
