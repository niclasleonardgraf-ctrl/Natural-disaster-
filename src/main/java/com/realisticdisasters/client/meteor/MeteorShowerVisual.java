package com.realisticdisasters.client.meteor;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.realisticdisasters.client.ClientDisaster;
import com.realisticdisasters.client.DisasterVisual;
import com.realisticdisasters.client.EffectsManager;
import com.realisticdisasters.config.ClientConfig;
import com.realisticdisasters.disaster.Phase;
import com.realisticdisasters.disaster.meteor.MeteorShowerDisaster;
import com.realisticdisasters.registry.ModRegistries;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Meteor shower presentation, modelled on real meteors, bolides and impacts:
 * <ul>
 *   <li>Shooting stars: thin, fast streaks (0.3-0.8 s) radiating from one point of the sky. Most are white, bright ones
 *       may have a green or yellow-orange head; some leave a faint persistent train for a few seconds and the
 *       brightest end in a terminal flare. Much more visible at night.</li>
 *   <li>Fireballs (synced impacts): a white-hot head with a coloured halo, shedding glowing fragments and leaving a
 *       thick smoke trail that hangs in the sky and is lit orange near the head. Big ones light up the landscape at night.</li>
 *   <li>Impact: blinding flash, an expanding fireball that cools into dark smoke, an ejecta curtain of real block
 *       fragments, a ground-hugging dust ring racing out with the shockwave (coloured like the ground that was hit), and
 *       a rising dust plume. The boom arrives late with distance (~17 blocks/tick) and so does the shake.</li>
 *   <li>Afterwards: a thin smoke column from the hot crater and a local dust haze.</li>
 * </ul>
 */
public final class MeteorShowerVisual implements DisasterVisual {
    private static final double FLIGHT_SPEED = 4.2; // blocks per tick
    private static final double SOUND_SPEED = 17.0; // blocks per tick (343 m/s)
    private static final int SMOKE_TICKS = 2400;
    private static final int DUST_HAZE = 0x7A6E62;

    private static final RandomSource RANDOM = RandomSource.create();

    /** A meteor seen in a sync packet: falling until {@link #impactTick}. */
    private static final class Incoming {
        final Vec3 target;
        final Vec3 dir;     // unit vector from the target back up along the flight path
        final float size;
        final long impactTick;
        final float[] halo; // halo colour (composition / speed dependent)
        boolean soundStarted;

        Incoming(Vec3 target, Vec3 dir, float size, long impactTick, float[] halo) {
            this.target = target;
            this.dir = dir;
            this.size = size;
            this.impactTick = impactTick;
            this.halo = halo;
        }

        Vec3 at(double tick) {
            return target.add(dir.scale(Math.max(0, impactTick - tick) * FLIGHT_SPEED));
        }
    }

    private static final class Impact {
        final Vec3 pos;
        final float size;
        final long tick;
        final float[] dust;  // dust colour from the ground that was hit
        final float craterR;
        boolean soundPlayed;

        Impact(Vec3 pos, float size, long tick, float[] dust, float craterR) {
            this.pos = pos;
            this.size = size;
            this.tick = tick;
            this.dust = dust;
            this.craterR = craterR;
        }
    }

    private static final class Streak {
        Vec3 pos;
        final Vec3 vel;
        int life;
        final float size;
        final float[] head;
        final boolean train, flare;

        Streak(Vec3 pos, Vec3 vel, int life, float size, float[] head, boolean train, boolean flare) {
            this.pos = pos;
            this.vel = vel;
            this.life = life;
            this.size = size;
            this.head = head;
            this.train = train;
            this.flare = flare;
        }
    }

    private static final class State {
        int lastSeq;
        float[] lastData;
        final List<Incoming> incoming = new ArrayList<>();
        final List<Impact> impacts = new ArrayList<>();
        final List<Streak> streaks = new ArrayList<>();
        float streakBudget;
        int nextFarWhoosh = 120;
    }

    // Head colours seen in real meteors: mostly white / blue-white, green (bright, fast), yellow-orange (slow).
    private static final float[] WHITE = {0.92f, 0.95f, 1f};
    private static final float[] GREEN = {0.62f, 1f, 0.7f};
    private static final float[] YELLOW = {1f, 0.86f, 0.55f};

    // ------------------------------------------------------------------ atmosphere

    @Override
    public void contribute(ClientDisaster d, Vec3 cam, Atmosphere out) {
        // A real meteor shower does not tint the sky; only impacts change the air (dust) and shake the ground.
        if (!(d.visualState instanceof State st)) return;
        long now = EffectsManager.ticks();
        float shake = 0, fov = 0, dust = 0;
        float craterBase = craterRadius(d, 1f);
        for (Impact im : st.impacts) {
            double di = im.pos.distanceTo(cam);
            float craterR = craterBase * im.size;
            float shockR = 10f + craterR * 6f;
            // Ground shaking travels through rock (faster) and arrives first; the air blast follows at ~17 b/t.
            double groundArrival = di / (SOUND_SPEED * 3);
            double blastArrival = Math.max(0, di - craterR * 0.5) / MeteorShowerDisaster.WAVE_SPEED;
            double z = Math.max(0.5, di / craterR);           // scaled distance, as in the server's blast physics
            double gAge = now - im.tick - groundArrival;
            if (gAge >= 0 && gAge < 80) {
                shake += (float) (im.size * (0.5f + d.intensity * 0.12f) * Math.min(1.0, 2.0 / z) * Math.exp(-gAge / 18.0));
            }
            double bAge = now - im.tick - blastArrival;
            if (bAge >= 0 && bAge < 40 && di < shockR * 1.6) {
                float amp = (float) (im.size * (0.9f + d.intensity * 0.2f) * Math.min(1.2, 1.5 / z) * Math.exp(-bAge / 7.0));
                shake += amp;
                fov = Math.max(fov, amp);
            }
            double since = now - im.tick;
            if (since < 1200 && di < craterR * 8) {
                dust = Math.max(dust, Math.min(1f, im.size) * (float) (1 - since / 1200.0) * (1 - (float) (di / (craterR * 8))));
            }
        }
        out.shake(shake);
        out.fovPulse(fov);
        if (dust > 0.01f) {
            out.fogColor(DUST_HAZE, dust);
            out.fog(40f + (1f - dust) * 120f, dust * 0.75f);
        }
    }

    // ------------------------------------------------------------------ per tick

    @Override
    public void tick(ClientDisaster d, ClientLevel level, Camera camera) {
        State st = d.visualState instanceof State s ? s : new State();
        d.visualState = st;
        Vec3 cam = camera.position();
        long now = EffectsManager.ticks();
        float density = density();

        readMeteors(d, st, level, now);
        tickStreaks(d, st, level, camera, cam, density);
        tickIncoming(d, st, level, camera, cam, now, density);
        tickImpacts(st, level, cam, now, density);
    }

    /** Picks up new meteors from the sync ring buffer (raw data: these values must not be smoothed). */
    private void readMeteors(ClientDisaster d, State st, ClientLevel level, long now) {
        float[] data = d.data;
        // Only fresh packets carry fresh "ticks until impact" values.
        if (data == st.lastData) return;
        st.lastData = data;
        if (data.length < MeteorShowerDisaster.D_SLOTS + MeteorShowerDisaster.SLOT_COUNT * MeteorShowerDisaster.SLOT_SIZE) return;
        int maxSeq = st.lastSeq;
        Vec3 c = d.pos();
        for (int i = 0; i < MeteorShowerDisaster.SLOT_COUNT; i++) {
            int base = MeteorShowerDisaster.D_SLOTS + i * MeteorShowerDisaster.SLOT_SIZE;
            int seq = (int) data[base + MeteorShowerDisaster.S_SEQ];
            if (seq <= st.lastSeq) continue;
            maxSeq = Math.max(maxSeq, seq);
            int ticksLeft = Math.round(data[base + MeteorShowerDisaster.S_TICKS]) - 1;
            if (ticksLeft < -20) continue; // landed long before we heard of it
            Vec3 target = c.add(data[base + MeteorShowerDisaster.S_DX], data[base + MeteorShowerDisaster.S_DY],
                    data[base + MeteorShowerDisaster.S_DZ]);
            float size = data[base + MeteorShowerDisaster.S_SIZE];
            // Every meteor comes from the shower's radiant, at a 35-60 degree angle, with a little spread.
            RandomSource r = RandomSource.create(seq * 341873128712L + d.id);
            double az = Math.atan2(data[MeteorShowerDisaster.D_RADIANT_Z], data[MeteorShowerDisaster.D_RADIANT_X])
                    + (r.nextDouble() - 0.5) * 0.5;
            double el = Math.toRadians(35 + r.nextDouble() * 25);
            Vec3 dir = new Vec3(Math.cos(az) * Math.cos(el), Math.sin(el), Math.sin(az) * Math.cos(el));
            float roll = r.nextFloat();
            float[] halo = roll < 0.25f ? GREEN : roll < 0.65f ? YELLOW : new float[]{1f, 0.66f, 0.32f};
            if (ticksLeft <= 0) {
                st.impacts.add(new Impact(target, size, now, groundDust(level, target), craterRadius(d, size)));
            } else {
                st.incoming.add(new Incoming(target, dir, size, now + ticksLeft, halo));
            }
        }
        st.lastSeq = maxSeq;
    }

    // ------------------------------------------------------------------ sky streaks

    private void tickStreaks(ClientDisaster d, State st, ClientLevel level, Camera camera, Vec3 cam, float density) {
        float scatter = scatter(d);
        double dist = horizontalDistance(d.pos(), cam);
        float near = 1f - smoothstep(scatter + 150f, scatter + 450f, (float) dist);
        float p = d.phaseProgress;
        float base = switch (d.phase) {
            case WARNING -> 0.06f + 0.4f * p;
            case BUILDUP -> 0.5f;
            case PEAK -> 0.7f;
            case AFTERMATH -> 0.45f * (1f - p);
        };
        // Best at night: full rate after dark; in daylight only the brightest are visible.
        float night = Mth.clamp(level.getSkyDarken() / 9f, 0f, 1f);
        float rate = base * (0.2f + 0.8f * night) * (0.6f + d.intensity * 0.12f) * near * density
                * Math.min(1f, d.strength() * 5f + 0.2f);
        st.streakBudget += rate;
        while (st.streakBudget >= 1f) {
            st.streakBudget -= 1f;
            spawnStreak(d, st, cam, false);
        }

        Frustum frustum = camera.getCullFrustum();
        Iterator<Streak> it = st.streaks.iterator();
        while (it.hasNext()) {
            Streak s = it.next();
            Vec3 next = s.pos.add(s.vel);
            boolean visible = frustum == null || frustum.isVisible(new AABB(s.pos, next).inflate(2));
            if (visible) {
                double far = s.pos.distanceTo(cam);
                // Thin line: densely packed soft glows, sized to stay a few pixels wide at any distance.
                float width = s.size * (float) (0.12 + far * 0.0045) * (0.4f + 0.6f * night);
                double spacing = Math.max(0.2, width * 0.45);
                int steps = Math.max(1, (int) Math.ceil(s.vel.length() / spacing));
                for (int i = 0; i < steps; i++) {
                    Vec3 at = s.pos.add(s.vel.scale((i + RANDOM.nextDouble()) / steps));
                    glow(at, Vec3.ZERO, width, 3 + RANDOM.nextInt(5), s.head, new float[]{1f, 0.72f, 0.48f}, 0.85f, 1f);
                    if (s.train && (i & 1) == 0) {
                        // Persistent train: a faint, continuous, slowly spreading ionised glow (often greenish).
                        glow(at, Vec3.ZERO, width * 2.5f, 40 + RANDOM.nextInt(40), new float[]{0.72f, 0.95f, 0.82f},
                                new float[]{0.3f, 0.45f, 0.5f}, 0.13f, 2f);
                    }
                }
                head(s.pos, s.vel, width * 2.2f, s.head, 1f);
                if (s.flare && s.life == 1) { // terminal flare
                    glow(next, Vec3.ZERO, width * 6f, 4, s.head, new float[]{1f, 0.7f, 0.4f}, 1f, 1.4f);
                }
            }
            s.pos = next;
            if (--s.life <= 0) it.remove();
        }

        // Now and then a bright fireball streaks past with a faint far whoosh (warning sign).
        if (d.phase != Phase.AFTERMATH && near > 0.3f && --st.nextFarWhoosh <= 0) {
            st.nextFarWhoosh = 140 + RANDOM.nextInt(220);
            Streak bright = spawnStreak(d, st, cam, true);
            if (bright != null) {
                Vec3 at = cam.add(bright.pos.subtract(cam).normalize().scale(16));
                Minecraft.getInstance().getSoundManager().play(new SimpleSoundInstance(
                        ModRegistries.METEOR_WHOOSH_DISTANT.get().location(), SoundSource.WEATHER,
                        0.2f + 0.2f * d.strength(), 0.9f + RANDOM.nextFloat() * 0.3f, RANDOM, false, 0,
                        SoundInstance.Attenuation.NONE, at.x, at.y, at.z, false));
            }
        }
    }

    private Streak spawnStreak(ClientDisaster d, State st, Vec3 cam, boolean bright) {
        if (st.streaks.size() > 60) return null;
        float rx = d.data(MeteorShowerDisaster.D_RADIANT_X), rz = d.data(MeteorShowerDisaster.D_RADIANT_Z);
        double radiant = Math.atan2(rz, rx);
        // Streaks travel away from the radiant across the sky.
        double heading = radiant + Math.PI + (RANDOM.nextDouble() - 0.5) * 1.6;
        double speed = 3.5 + RANDOM.nextDouble() * 3.5;
        double drop = 0.12 + RANDOM.nextDouble() * 0.3;
        Vec3 vel = new Vec3(Math.cos(heading), -drop, Math.sin(heading)).normalize().scale(speed);
        int life = 6 + RANDOM.nextInt(10);
        // Start point: somewhere in the sky dome around the viewer, placed so the streak crosses it.
        double a = RANDOM.nextDouble() * Math.PI * 2;
        double r = 30 + RANDOM.nextDouble() * 90;
        double h = 50 + RANDOM.nextDouble() * 70;
        Vec3 mid = new Vec3(cam.x + Math.cos(a) * r, cam.y + h, cam.z + Math.sin(a) * r);
        Vec3 start = mid.subtract(vel.scale(life * 0.5));
        float magnitude = bright ? 1.8f + RANDOM.nextFloat() * 0.6f : 0.7f + RANDOM.nextFloat() * RANDOM.nextFloat() * 1.2f;
        float c = RANDOM.nextFloat();
        float[] head = magnitude > 1.4f && c < 0.45f ? GREEN : c < 0.75f ? WHITE : YELLOW;
        boolean train = magnitude > 1.3f && RANDOM.nextFloat() < 0.6f;
        boolean flare = magnitude > 1.5f && RANDOM.nextFloat() < 0.5f;
        Streak s = new Streak(start, vel, life, magnitude, head, train, flare);
        st.streaks.add(s);
        return s;
    }

    // ------------------------------------------------------------------ falling meteors

    private void tickIncoming(ClientDisaster d, State st, ClientLevel level, Camera camera, Vec3 cam, long now, float density) {
        Frustum frustum = camera.getCullFrustum();
        float night = Mth.clamp(level.getSkyDarken() / 9f, 0f, 1f);
        Iterator<Incoming> it = st.incoming.iterator();
        while (it.hasNext()) {
            Incoming m = it.next();
            if (now >= m.impactTick) {
                it.remove();
                Impact im = new Impact(m.target, m.size, now, groundDust(level, m.target), craterRadius(d, m.size));
                st.impacts.add(im);
                impactEffects(level, cam, im, density);
                continue;
            }
            if (!m.soundStarted) {
                m.soundStarted = true;
                startWhoosh(m);
            }
            Vec3 head = m.at(now);
            Vec3 next = m.at(now + 1);
            Vec3 vel = next.subtract(head);
            double far = head.distanceTo(cam);
            // A big fireball lights up the land at night, flickering as it fragments.
            if (night > 0.5f && m.size >= 0.9f && far < 220 && m.impactTick - now < 25 && RANDOM.nextFloat() < 0.35f) {
                level.setSkyFlashTime(1);
            }
            boolean visible = frustum == null || frustum.isVisible(new AABB(head, next).inflate(6 + m.size * 4));
            if (!visible) continue;
            float lod = far < 128 ? 1f : far < 256 ? 0.6f : 0.3f;
            // Readable size at any distance, but never a giant sprite up close.
            float core = (1.3f + m.size * 2f) * (float) Math.max(1.0, far / 90.0);
            // Spawn order matters: translucent particles draw in spawn order, so smoke goes first and the head last.
            // The smoke trail: thick, hangs in the sky for several seconds, behind the incandescent wake.
            int smoke = count((2 + m.size * 3) * density * lod);
            for (int i = 0; i < smoke; i++) {
                Vec3 at = head.subtract(vel.scale(1.8 + RANDOM.nextDouble() * 1.4)).add(jitter(core * 0.25));
                float g = 0.38f + RANDOM.nextFloat() * 0.14f;
                float sz = Math.min(4f, core * (0.6f + RANDOM.nextFloat() * 0.4f));
                spawn(ModRegistries.METEOR_SMOKE.get(), at, jitter(0.015), p -> p.size(sz).grow(3f).life(140 + RANDOM.nextInt(120))
                        .alpha(0.45f).drag(0.98f).colors(g * 1.05f, g, g * 0.95f, g, g, g));
            }
            // Fragments shedding off the head.
            int frags = count((1 + m.size * 2.5f) * density * lod);
            for (int i = 0; i < frags; i++) {
                Vec3 v = vel.scale(0.55 + RANDOM.nextDouble() * 0.3).add(jitter(0.25));
                spawn(ModRegistries.METEOR_EMBER.get(), head.add(jitter(core * 0.3)), v, p -> p.drag(0.9f).life(8 + RANDOM.nextInt(10)));
            }
            // Continuous incandescent wake: densely packed glows that cool from white to orange-red behind the head.
            int wake = Math.max(4, (int) Math.ceil(FLIGHT_SPEED / Math.max(0.2, core * 0.14)));
            for (int i = 0; i < wake; i++) {
                // Random points along this tick's path (not evenly spaced): evenly spaced batches that age
                // together read as a string of pearls, one per tick of travel.
                Vec3 at = head.subtract(vel.scale(RANDOM.nextDouble())).add(jitter(core * 0.06));
                glow(at, Vec3.ZERO, core * (0.75f + RANDOM.nextFloat() * 0.3f), 5 + RANDOM.nextInt(9), new float[]{1f, 0.93f, 0.75f},
                        new float[]{0.85f, 0.3f, 0.1f}, 0.75f, 0.6f);
            }
            // Large coloured halo, inner halo, then the blinding white head on top.
            head(head, vel, core * 4.5f, m.halo, 0.3f);
            head(head, vel, core * 2.2f, new float[]{1f, 0.97f, 0.9f}, 0.7f);
            spawn(ModRegistries.METEOR_FIREBALL.get(), head, vel, p -> p.size(core).life(1));
        }
    }

    private void startWhoosh(Incoming m) {
        var sounds = Minecraft.getInstance().getSoundManager();
        float pitch = 1.15f - m.size * 0.3f;
        // Close layer: positional, follows the fireball.
        sounds.play(new MeteorFlightSound(ModRegistries.METEOR_WHOOSH.get(), true,
                () -> m.at(EffectsManager.ticks()), () -> 0.7 + m.size * 0.8, m.impactTick, pitch));
        // Distant layer: heard across the whole shower, fading with distance.
        sounds.play(new MeteorFlightSound(ModRegistries.METEOR_WHOOSH_DISTANT.get(), false,
                () -> m.at(EffectsManager.ticks()),
                () -> {
                    double dist = m.at(EffectsManager.ticks()).distanceTo(camPos());
                    return (0.25 + 0.35 * m.size) * (1 - smoothstep(60f, 420f, (float) dist));
                }, m.impactTick, pitch));
    }

    // ------------------------------------------------------------------ impacts

    private void impactEffects(ClientLevel level, Vec3 cam, Impact im, float density) {
        double dist = im.pos.distanceTo(cam);
        float size = im.size;
        float cr = im.craterR;
        // Flash: vanilla sky flash (lights the whole landscape) plus a white-out that scales with the energy.
        if (dist < 600) level.setSkyFlashTime(3);
        MeteorFlashOverlay.flash(Math.min(0.95f, size * 0.9f) * (1f - smoothstep(cr * 2f, cr * 30f, (float) dist)));

        if (dist > 320) return;
        Vec3 p = im.pos.add(0, 0.3, 0);
        float lod = dist < 96 ? 1f : dist < 192 ? 0.6f : 0.35f;
        float n = density * lod;
        level.addParticle(ColorParticleOption.create(ParticleTypes.FLASH, 0xFFFFF4D8), true, true, p.x, p.y + 1, p.z, 0, 0, 0);

        // Fireball: incandescent gas billowing out to about the crater radius and up, cooling into dark smoke.
        int fire = count((18 + cr * 3) * Math.max(0.4f, n));
        for (int i = 0; i < fire; i++) {
            Vec3 v = hemisphere(0.2 + RANDOM.nextDouble() * cr * 0.09);
            float sz = 1.2f + cr * 0.35f + RANDOM.nextFloat() * 1.5f;
            spawn(ModRegistries.METEOR_SMOKE.get(), p.add(jitter(0.6)), v, q -> q.size(sz).grow(2.4f).life(70 + RANDOM.nextInt(60))
                    .alpha(0.75f).hot(0.18f).drag(0.86f).buoyancy(0.004f).colors(0.24f, 0.22f, 0.2f, 0.3f, 0.28f, 0.26f));
        }
        // Glowing fragments and molten spray.
        int embers = count((24 + cr * 5) * n);
        for (int i = 0; i < embers; i++) {
            Vec3 v = hemisphere(0.3 + RANDOM.nextDouble() * (0.4 + cr * 0.06));
            spawn(ModRegistries.METEOR_EMBER.get(), p.add(jitter(0.6)), v.add(0, 0.25, 0), q -> {});
        }
        // Ejecta curtain: real fragments of the ground, thrown out in a steep cone.
        BlockState ground = level.getBlockState(BlockPos.containing(im.pos).below());
        if (!ground.isAir()) {
            ParticleOptions block = new BlockParticleOption(ParticleTypes.BLOCK, ground);
            int clods = count((50 + cr * 10) * n);
            for (int i = 0; i < clods; i++) {
                double a = RANDOM.nextDouble() * Math.PI * 2;
                double out = 0.25 + RANDOM.nextDouble() * (0.3 + cr * 0.07);
                double up = out * (0.9 + RANDOM.nextDouble() * 0.6);
                level.addParticle(block, true, true, p.x, p.y, p.z, Math.cos(a) * out, up, Math.sin(a) * out);
            }
        }
        float[] dc = im.dust;
        // Dust ring: ground-hugging base surge racing outward with the shockwave.
        int ring = count((40 + cr * 6) * n);
        for (int i = 0; i < ring; i++) {
            double a = i / (double) Math.max(1, ring) * Math.PI * 2 + RANDOM.nextDouble() * 0.15;
            double v = (0.6 + RANDOM.nextDouble() * 0.5) * (0.5 + cr * 0.1);
            float sz = 1.2f + cr * 0.25f + RANDOM.nextFloat();
            float sh = 0.9f + RANDOM.nextFloat() * 0.2f;
            spawn(ModRegistries.METEOR_SMOKE.get(), p.add(Math.cos(a) * 1.5, -0.1, Math.sin(a) * 1.5), new Vec3(Math.cos(a) * v, 0.02, Math.sin(a) * v),
                    q -> q.size(sz).grow(4f).life(110 + RANDOM.nextInt(90)).alpha(0.6f).drag(0.9f)
                            .colors(dc[0] * sh, dc[1] * sh, dc[2] * sh, dc[0], dc[1], dc[2]));
        }
    }

    private void tickImpacts(State st, ClientLevel level, Vec3 cam, long now, float density) {
        var sounds = Minecraft.getInstance().getSoundManager();
        Iterator<Impact> it = st.impacts.iterator();
        while (it.hasNext()) {
            Impact im = it.next();
            long age = now - im.tick;
            if (age > SMOKE_TICKS) {
                it.remove();
                continue;
            }
            double dist = im.pos.distanceTo(cam);
            // Sound arrives late from far away: you see the flash first, then hear the boom.
            if (!im.soundPlayed && age >= dist / SOUND_SPEED) {
                im.soundPlayed = true;
                float pitch = 1.1f - im.size * 0.25f + (RANDOM.nextFloat() - 0.5f) * 0.1f;
                if (dist < 160) {
                    sounds.play(new SimpleSoundInstance(ModRegistries.METEOR_IMPACT.get().location(), SoundSource.WEATHER,
                            1.5f + im.craterR * 0.2f, pitch, RANDOM, false, 0, SoundInstance.Attenuation.LINEAR,
                            im.pos.x, im.pos.y + 1, im.pos.z, false));
                }
                float farVol = (0.6f + 0.6f * im.size) * (1f - smoothstep(80f, 900f, (float) dist)) * smoothstep(8f, 60f, (float) dist + 20f);
                if (farVol > 0.02f) {
                    Vec3 at = cam.add(im.pos.subtract(cam).normalize().scale(Math.min(dist, 14)));
                    sounds.play(new SimpleSoundInstance(ModRegistries.METEOR_IMPACT_DISTANT.get().location(), SoundSource.WEATHER,
                            farVol, pitch * 0.9f, RANDOM, false, 0, SoundInstance.Attenuation.NONE, at.x, at.y, at.z, false));
                }
            }
            if (dist > 256) continue;
            float lod = dist < 64 ? 1f : 0.5f;
            float[] dc = im.dust;
            // Dust plume: a column of ground dust and smoke rising over the first seconds.
            if (age < 50) {
                int plume = count((1.5f + im.craterR * 0.35f) * density * lod * (1f - age / 50f));
                for (int i = 0; i < plume; i++) {
                    Vec3 at = im.pos.add(jitter(0.5 + im.craterR * 0.3));
                    float sz = 1.4f + im.craterR * 0.22f;
                    float sh = 0.75f + RANDOM.nextFloat() * 0.2f;
                    spawn(ModRegistries.METEOR_SMOKE.get(), at, new Vec3(0, 0.12 + RANDOM.nextDouble() * (0.1 + im.craterR * 0.02), 0),
                            q -> q.size(sz).grow(3.5f).life(200 + RANDOM.nextInt(160)).alpha(0.45f).drag(0.965f).buoyancy(0.0015f)
                                    .colors(dc[0] * sh, dc[1] * sh, dc[2] * sh, dc[0] * 0.9f, dc[1] * 0.9f, dc[2] * 0.9f));
                }
            }
            // Smouldering crater: thin grey smoke and the odd glowing fleck, thinning out over two minutes.
            if (dist < 192 && age > 40) {
                float fade = 1f - age / (float) SMOKE_TICKS;
                int smoke = count((0.25f + im.size * 0.45f) * fade * density * lod);
                for (int i = 0; i < smoke; i++) {
                    Vec3 at = im.pos.add(jitter(0.5 + im.size * 0.8)).add(0, -0.6, 0);
                    float g = 0.3f + RANDOM.nextFloat() * 0.15f;
                    spawn(ModRegistries.METEOR_SMOKE.get(), at, new Vec3(0, 0.06, 0), q -> q.size(0.7f + im.size * 0.5f).grow(4f)
                            .life(120 + RANDOM.nextInt(80)).alpha(0.4f * fade + 0.1f).drag(0.98f).buoyancy(0.001f).colors(g, g, g, g + 0.15f, g + 0.15f, g + 0.15f));
                }
                if (age < 600 && RANDOM.nextFloat() < 0.12f * fade * density) {
                    Vec3 at = im.pos.add(jitter(0.5 + im.size * 0.8)).add(0, -0.6, 0);
                    spawn(ModRegistries.METEOR_EMBER.get(), at, new Vec3(0, 0.15 + RANDOM.nextDouble() * 0.15, 0), q -> {});
                }
            }
        }
        if (st.impacts.size() > 48) st.impacts.removeFirst();
    }

    @Override
    public void onRemoved(ClientDisaster d) {
        if (d.visualState instanceof State st) {
            st.incoming.clear();
            st.streaks.clear();
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void spawn(SimpleParticleType type, Vec3 at, Vec3 vel, java.util.function.Consumer<MeteorParticle> setup) {
        Particle particle = Minecraft.getInstance().particleEngine.createParticle(type, at.x, at.y, at.z, vel.x, vel.y, vel.z);
        if (particle instanceof MeteorParticle mp) setup.accept(mp);
    }

    /** A sprite that rides exactly on a moving head for one tick (no drag, no lingering = no "beads"). */
    private static void head(Vec3 at, Vec3 vel, float size, float[] color, float alpha) {
        spawn(ModRegistries.METEOR_STREAK.get(), at, vel, p -> p.size(size).life(1).drag(1f).alpha(alpha)
                .colors(color[0], color[1], color[2], color[0], color[1], color[2]));
    }

    private static void glow(Vec3 at, Vec3 vel, float size, int life, float[] from, float[] to, float alpha, float grow) {
        spawn(ModRegistries.METEOR_STREAK.get(), at, vel, p -> p.size(size).life(life).alpha(alpha).grow(grow)
                .colors(from[0], from[1], from[2], to[0], to[1], to[2]));
    }

    /** Dust colour = the map colour of the ground that was hit (grass gives brown-grey soil dust, sand gives tan). */
    private static float[] groundDust(ClientLevel level, Vec3 pos) {
        BlockPos bp = BlockPos.containing(pos).below();
        BlockState st = level.getBlockState(bp);
        // Grass and leaves are thrown up as soil, not green dust.
        if (st.isAir() || st.getMapColor(level, bp) == MapColor.GRASS || st.getMapColor(level, bp) == MapColor.PLANT) {
            return new float[]{0.55f, 0.47f, 0.38f};
        }
        int rgb = st.getMapColor(level, bp).col;
        float r = ((rgb >> 16) & 255) / 255f, g = ((rgb >> 8) & 255) / 255f, b = (rgb & 255) / 255f;
        // Desaturate and lighten a bit: airborne dust is paler than the ground it came from.
        float l = (r + g + b) / 3f;
        return new float[]{Mth.lerp(0.4f, r, l) * 0.85f + 0.08f, Mth.lerp(0.4f, g, l) * 0.85f + 0.08f, Mth.lerp(0.4f, b, l) * 0.85f + 0.08f};
    }

    /** Mod density setting times the vanilla particle setting. */
    private static float density() {
        float density = ClientConfig.PARTICLE_DENSITY.get().floatValue();
        ParticleStatus status = Minecraft.getInstance().options.particles().get();
        return density * switch (status) {
            case ALL -> 1f;
            case DECREASED -> 0.5f;
            case MINIMAL -> 0.2f;
        };
    }

    private static float scatter(ClientDisaster d) {
        return Math.max(20f, d.data(MeteorShowerDisaster.D_SCATTER));
    }

    /** Crater radius as the server computes it (its base is synced, so config scaling is respected). */
    private static float craterRadius(ClientDisaster d, float size) {
        float base = d.data(MeteorShowerDisaster.D_CRATER);
        return (base > 0 ? base : 3f + d.intensity * 1.4f) * size;
    }

    private static Vec3 camPos() {
        return Minecraft.getInstance().gameRenderer.getMainCamera().position();
    }

    private static Vec3 hemisphere(double speed) {
        double a = RANDOM.nextDouble() * Math.PI * 2;
        double up = RANDOM.nextDouble();
        double h = Math.sqrt(1 - up * up);
        return new Vec3(Math.cos(a) * h * speed, up * speed, Math.sin(a) * h * speed);
    }

    private static Vec3 jitter(double r) {
        return new Vec3((RANDOM.nextDouble() - 0.5) * 2 * r, (RANDOM.nextDouble() - 0.5) * 2 * r, (RANDOM.nextDouble() - 0.5) * 2 * r);
    }

    private static int count(float expected) {
        int n = (int) expected;
        if (RANDOM.nextFloat() < expected - n) n++;
        return n;
    }

    static double horizontalDistance(Vec3 a, Vec3 b) {
        double dx = a.x - b.x, dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    static float smoothstep(float e0, float e1, float x) {
        float t = Mth.clamp((x - e0) / (e1 - e0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }
}
