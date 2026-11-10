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
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Meteor shower presentation. Warning: streaks across the sky radiating from one point (many more at night).
 * Then each synced meteor is drawn as a fireball with a burning trail and a whoosh that follows it; on impact a flash
 * (screen + sky), a fireball burst, embers, a dust ring, and a delayed impact sound and shockwave shake (sound
 * travels at ~17 blocks per tick, the shock front at {@link MeteorShowerDisaster#WAVE_SPEED}). Craters smoke afterwards.
 */
public final class MeteorShowerVisual implements DisasterVisual {
    private static final int SKY = 0x232A4A;       // deep indigo night sky
    private static final int CLOUDS = 0x5A4450;    // clouds lit from below by fireballs
    private static final int DUST = 0x6E5E50;      // impact dust / smoke haze
    private static final double FLIGHT_SPEED = 4.2; // blocks per tick
    private static final double SOUND_SPEED = 17.0; // blocks per tick (343 m/s)
    private static final int SMOKE_TICKS = 2400;

    private static final RandomSource RANDOM = RandomSource.create();

    /** A meteor seen in a sync packet: falling until {@link #impactTick}. */
    private static final class Incoming {
        final Vec3 target;
        final Vec3 dir;     // unit vector from the target back up along the flight path
        final float size;
        final long impactTick;
        boolean soundStarted;

        Incoming(Vec3 target, Vec3 dir, float size, long impactTick) {
            this.target = target;
            this.dir = dir;
            this.size = size;
            this.impactTick = impactTick;
        }

        Vec3 at(double tick) {
            return target.add(dir.scale(Math.max(0, impactTick - tick) * FLIGHT_SPEED));
        }
    }

    private static final class Impact {
        final Vec3 pos;
        final float size;
        final long tick;
        boolean soundPlayed;

        Impact(Vec3 pos, float size, long tick) {
            this.pos = pos;
            this.size = size;
            this.tick = tick;
        }
    }

    private static final class Streak {
        Vec3 pos;
        final Vec3 vel;
        int life;
        final float size;

        Streak(Vec3 pos, Vec3 vel, int life, float size) {
            this.pos = pos;
            this.vel = vel;
            this.life = life;
            this.size = size;
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

    // ------------------------------------------------------------------ atmosphere

    @Override
    public void contribute(ClientDisaster d, Vec3 cam, Atmosphere out) {
        float s = d.strength();
        double dist = horizontalDistance(d.pos(), cam);
        float scatter = scatter(d);
        // The shower fills the whole sky: felt from far away.
        float near = 1f - smoothstep(scatter + 150f, scatter + 450f, (float) dist);
        float w = s * near;
        out.sky(SKY, w * 0.55f);
        out.clouds(CLOUDS, w * 0.6f);
        out.darken(w * (0.12f + d.intensity * 0.03f));   // dimmer daylight makes the streaks stand out

        if (!(d.visualState instanceof State st)) return;
        long now = EffectsManager.ticks();
        float shake = 0, fov = 0, dust = 0;
        float craterBase = craterRadius(d, 1f);
        for (Impact im : st.impacts) {
            double di = im.pos.distanceTo(cam);
            float craterR = craterBase * im.size;
            float shockR = 8f + craterR * 4f;
            // Shock front reaches the camera after di / WAVE_SPEED ticks; beyond it, only the ground rumble.
            double arrival = di < shockR * 1.5 ? di / MeteorShowerDisaster.WAVE_SPEED : di / SOUND_SPEED;
            double age = now - im.tick - arrival;
            if (age >= 0 && age < 50) {
                float falloff = (float) (1.0 / (1.0 + Math.pow(di / (craterR * 3.5), 2)));
                float amp = im.size * (0.6f + d.intensity * 0.18f) * falloff * (float) Math.exp(-age / 9.0);
                shake += amp;
                if (di < shockR) fov = Math.max(fov, amp);
            }
            double since = now - im.tick;
            if (since < 1200 && di < craterR * 14) {
                dust = Math.max(dust, im.size * (float) (1 - since / 1200.0) * (1 - (float) (di / (craterR * 14))));
            }
        }
        out.shake(shake);
        out.fovPulse(fov);
        if (dust > 0.01f) {
            out.fogColor(DUST, dust);
            out.fog(48f + (1f - dust) * 120f, dust * 0.7f);
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

        readMeteors(d, st, now);
        tickStreaks(d, st, level, camera, cam, density);
        tickIncoming(d, st, level, camera, cam, now, density);
        tickImpacts(d, st, level, cam, now, density);
    }

    /** Picks up new meteors from the sync ring buffer (raw data: these values must not be smoothed). */
    private void readMeteors(ClientDisaster d, State st, long now) {
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
            if (ticksLeft <= 0) {
                st.impacts.add(new Impact(target, size, now));
            } else {
                st.incoming.add(new Incoming(target, dir, size, now + ticksLeft));
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
            case WARNING -> 0.08f + 0.45f * p;
            case BUILDUP -> 0.55f;
            case PEAK -> 0.75f;
            case AFTERMATH -> 0.5f * (1f - p);
        };
        // Best at night: full rate after dark, a third of it in daylight.
        float night = Mth.clamp(level.getSkyDarken() / 9f, 0f, 1f);
        float rate = base * (0.3f + 0.7f * night) * (0.6f + d.intensity * 0.12f) * near * density
                * Math.min(1f, d.strength() * 5f + 0.2f);
        st.streakBudget += rate;
        while (st.streakBudget >= 1f) {
            st.streakBudget -= 1f;
            spawnStreak(d, st, cam);
        }

        Frustum frustum = camera.getCullFrustum();
        Iterator<Streak> it = st.streaks.iterator();
        while (it.hasNext()) {
            Streak s = it.next();
            Vec3 next = s.pos.add(s.vel);
            boolean visible = frustum == null || frustum.isVisible(new AABB(s.pos, next).inflate(2));
            if (visible) {
                double far = s.pos.distanceTo(cam);
                float size = s.size * (float) (0.25 + far * 0.0075);
                int steps = Math.max(1, (int) Math.ceil(s.vel.length() / 1.3));
                for (int i = 0; i < steps; i++) {
                    Vec3 at = s.pos.add(s.vel.scale(i / (double) steps));
                    spawn(ModRegistries.METEOR_STREAK.get(), at, Vec3.ZERO, size);
                }
                spawn(ModRegistries.METEOR_FIREBALL.get(), s.pos, s.vel, size * 1.3f);
            }
            s.pos = next;
            if (--s.life <= 0) it.remove();
        }

        // Now and then a bright fireball streaks past with a faint far whoosh (warning sign).
        if (d.phase != Phase.AFTERMATH && near > 0.3f && --st.nextFarWhoosh <= 0) {
            st.nextFarWhoosh = 140 + RANDOM.nextInt(220);
            Streak bright = spawnStreak(d, st, cam);
            if (bright != null) {
                Vec3 at = cam.add(bright.pos.subtract(cam).normalize().scale(16));
                Minecraft.getInstance().getSoundManager().play(new SimpleSoundInstance(
                        ModRegistries.METEOR_WHOOSH_DISTANT.get().location(), SoundSource.WEATHER,
                        0.25f + 0.25f * d.strength(), 0.9f + RANDOM.nextFloat() * 0.3f, RANDOM, false, 0,
                        SoundInstance.Attenuation.NONE, at.x, at.y, at.z, false));
            }
        }
    }

    private Streak spawnStreak(ClientDisaster d, State st, Vec3 cam) {
        if (st.streaks.size() > 60) return null;
        float rx = d.data(MeteorShowerDisaster.D_RADIANT_X), rz = d.data(MeteorShowerDisaster.D_RADIANT_Z);
        double radiant = Math.atan2(rz, rx);
        // Streaks travel away from the radiant across the sky.
        double heading = radiant + Math.PI + (RANDOM.nextDouble() - 0.5) * 1.6;
        double speed = 3.0 + RANDOM.nextDouble() * 3.0;
        double drop = 0.12 + RANDOM.nextDouble() * 0.3;
        Vec3 vel = new Vec3(Math.cos(heading), -drop, Math.sin(heading)).normalize().scale(speed);
        int life = 6 + RANDOM.nextInt(10);
        // Start point: somewhere in the sky dome around the viewer, placed so the streak crosses it.
        double a = RANDOM.nextDouble() * Math.PI * 2;
        double r = 30 + RANDOM.nextDouble() * 90;
        double h = 45 + RANDOM.nextDouble() * 70;
        Vec3 mid = new Vec3(cam.x + Math.cos(a) * r, Math.max(cam.y, 63) + h, cam.z + Math.sin(a) * r);
        Vec3 start = mid.subtract(vel.scale(life * 0.5));
        float size = 0.7f + RANDOM.nextFloat() * 0.6f;
        Streak s = new Streak(start, vel, life, size);
        st.streaks.add(s);
        return s;
    }

    // ------------------------------------------------------------------ falling meteors

    private void tickIncoming(ClientDisaster d, State st, ClientLevel level, Camera camera, Vec3 cam, long now, float density) {
        Frustum frustum = camera.getCullFrustum();
        Iterator<Incoming> it = st.incoming.iterator();
        while (it.hasNext()) {
            Incoming m = it.next();
            if (now >= m.impactTick) {
                it.remove();
                Impact im = new Impact(m.target, m.size, now);
                st.impacts.add(im);
                impactEffects(d, level, cam, im, density);
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
            boolean visible = frustum == null || frustum.isVisible(new AABB(head, next).inflate(4 + m.size * 3));
            if (!visible) continue;
            // Bigger and brighter than a streak; keeps a readable size at distance.
            float size = (1.2f + m.size * 2.2f) * (float) Math.max(1.0, far / 70.0);
            spawn(ModRegistries.METEOR_FIREBALL.get(), head, vel, size);
            float lod = far < 128 ? 1f : far < 256 ? 0.6f : 0.3f;
            int trail = count((5 + m.size * 7) * density * lod);
            for (int i = 0; i < trail; i++) {
                Vec3 at = head.add(vel.scale(-RANDOM.nextDouble())).add(jitter(0.4 + m.size * 0.6));
                Vec3 drift = jitter(0.06);
                spawn(ModRegistries.METEOR_STREAK.get(), at, drift, size * (0.45f + RANDOM.nextFloat() * 0.3f));
            }
            // Dark smoke trail lingering behind the fireball.
            if (far < 220) {
                int smoke = count((1.5f + m.size * 2f) * density * lod);
                for (int i = 0; i < smoke; i++) {
                    Vec3 at = head.add(vel.scale(-1 - RANDOM.nextDouble() * 2));
                    level.addParticle(ParticleTypes.LARGE_SMOKE, true, true, at.x, at.y, at.z, 0, 0.02, 0);
                }
            }
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

    private void impactEffects(ClientDisaster d, ClientLevel level, Vec3 cam, Impact im, float density) {
        double dist = im.pos.distanceTo(cam);
        float size = im.size;
        // Flash: vanilla sky flash (like lightning) plus a white-out for close impacts.
        if (dist < 420) level.setSkyFlashTime(2);
        MeteorFlashOverlay.flash(size * 0.75f * (1f - smoothstep(20f, 260f, (float) dist)));

        if (dist > 320) return;
        Vec3 p = im.pos.add(0, 0.6, 0);
        float lod = dist < 96 ? 1f : dist < 192 ? 0.6f : 0.35f;
        float n = density * lod;
        level.addParticle(ColorParticleOption.create(ParticleTypes.FLASH, 0xFFFFF4D8), true, true, p.x, p.y + 1, p.z, 0, 0, 0);
        if (size >= 0.75f) level.addParticle(ParticleTypes.EXPLOSION_EMITTER, true, true, p.x, p.y + 1, p.z, 1, 0, 0);
        int booms = count((2 + size * 4) * Math.max(0.5f, n));
        for (int i = 0; i < booms; i++) {
            Vec3 at = p.add(jitter(1.5 + size * 2));
            level.addParticle(ParticleTypes.EXPLOSION, true, true, at.x, at.y + 1, at.z, 1, 0, 0);
        }
        // Fireball burst of glowing embers.
        int embers = count((24 + size * 40) * n);
        for (int i = 0; i < embers; i++) {
            double a = RANDOM.nextDouble() * Math.PI * 2;
            double out = 0.25 + RANDOM.nextDouble() * 0.5 * (0.6 + size);
            spawn(ModRegistries.METEOR_EMBER.get(), p.add(jitter(0.8)),
                    new Vec3(Math.cos(a) * out, 0.35 + RANDOM.nextDouble() * 0.7, Math.sin(a) * out), 1f);
        }
        // Thrown soil, using the real block texture of the ground that was hit.
        BlockState ground = level.getBlockState(BlockPos.containing(im.pos).below());
        if (!ground.isAir()) {
            int clods = count((20 + size * 30) * n);
            ParticleOptions block = new BlockParticleOption(ParticleTypes.BLOCK, ground);
            for (int i = 0; i < clods; i++) {
                double a = RANDOM.nextDouble() * Math.PI * 2;
                double out = 0.2 + RANDOM.nextDouble() * 0.6;
                level.addParticle(block, true, true, p.x, p.y, p.z, Math.cos(a) * out, 0.4 + RANDOM.nextDouble() * 0.8, Math.sin(a) * out);
            }
        }
        // Dust ring racing outward with the shockwave.
        int ring = count((26 + size * 26) * n);
        for (int i = 0; i < ring; i++) {
            double a = i / (double) Math.max(1, ring) * Math.PI * 2 + RANDOM.nextDouble() * 0.2;
            double v = 0.55 + RANDOM.nextDouble() * 0.35 + size * 0.25;
            level.addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, true, true, p.x + Math.cos(a), p.y - 0.2, p.z + Math.sin(a),
                    Math.cos(a) * v, 0.015, Math.sin(a) * v);
        }
    }

    private void tickImpacts(ClientDisaster d, State st, ClientLevel level, Vec3 cam, long now, float density) {
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
                            1.2f + im.size * 1.2f, pitch, RANDOM, false, 0, SoundInstance.Attenuation.LINEAR,
                            im.pos.x, im.pos.y + 1, im.pos.z, false));
                }
                float farVol = (0.5f + 0.6f * im.size) * (1f - smoothstep(80f, 700f, (float) dist)) * smoothstep(8f, 60f, (float) dist + 20f);
                if (farVol > 0.02f) {
                    Vec3 at = cam.add(im.pos.subtract(cam).normalize().scale(Math.min(dist, 14)));
                    sounds.play(new SimpleSoundInstance(ModRegistries.METEOR_IMPACT_DISTANT.get().location(), SoundSource.WEATHER,
                            farVol, pitch * 0.9f, RANDOM, false, 0, SoundInstance.Attenuation.NONE, at.x, at.y, at.z, false));
                }
            }
            // Smouldering crater: smoke column and the odd spark, thinning out over two minutes.
            if (dist < 192 && age > 4) {
                float fade = 1f - age / (float) SMOKE_TICKS;
                float lod = dist < 64 ? 1f : 0.5f;
                int smoke = count((0.3f + im.size * 0.6f) * fade * density * lod);
                for (int i = 0; i < smoke; i++) {
                    Vec3 at = im.pos.add(jitter(0.6 + im.size));
                    level.addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, true, true, at.x, at.y - 0.5, at.z, 0, 0.05, 0);
                }
                if (age < 600 && RANDOM.nextFloat() < 0.15f * fade * density) {
                    Vec3 at = im.pos.add(jitter(0.6 + im.size));
                    spawn(ModRegistries.METEOR_EMBER.get(), at, new Vec3(0, 0.15 + RANDOM.nextDouble() * 0.15, 0), 1f);
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

    private static void spawn(net.minecraft.core.particles.SimpleParticleType type, Vec3 at, Vec3 vel, float scale) {
        Particle particle = Minecraft.getInstance().particleEngine.createParticle(type, at.x, at.y, at.z, vel.x, vel.y, vel.z);
        if (particle != null && scale != 1f) particle.scale(scale);
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

    /** Mirrors MeteorShowerDisaster#craterRadius without the server-only config scale. */
    private static float craterRadius(ClientDisaster d, float size) {
        return (1.6f + d.intensity * 0.75f) * size;
    }

    private static Vec3 camPos() {
        return Minecraft.getInstance().gameRenderer.getMainCamera().position();
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
