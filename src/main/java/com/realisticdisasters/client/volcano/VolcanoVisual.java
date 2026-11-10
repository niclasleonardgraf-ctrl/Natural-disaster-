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
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Volcano presentation: an ash column with a spreading umbrella cloud that leans downwind, a grey, darkened sky
 * under the ash cloud, falling ash flakes, lava fountaining at the vent, smoking bomb trails, a deep rumble and
 * blast sounds that arrive with a distance delay together with a camera jolt.
 */
public final class VolcanoVisual implements DisasterVisual {
    private static final int SKY = 0x6B6763;     // ash grey
    private static final int FOG = 0x5A5550;
    private static final int CLOUDS = 0x403C39;
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

    // ------------------------------------------------------------------ atmosphere

    @Override
    public void contribute(ClientDisaster d, Vec3 cam, Atmosphere out) {
        float s = d.strength();
        float ashR = ashRadius(d);
        double ashDist = horizontal(ashCenter(d), cam);
        float under = 1f - smoothstep(ashR * 0.55f, ashR * 1.35f, (float) ashDist);
        // The ash cloud keeps hanging around a while after the eruption weakens.
        float ash = Math.max(s, column(d) * 0.8f) * under;

        out.sky(SKY, ash);
        out.clouds(CLOUDS, ash);
        out.fogColor(FOG, ash);
        out.darken(ash * (0.3f + d.intensity * 0.08f));
        float fogEnd = 36f + (5 - d.intensity) * 16f + (float) ashDist * 0.3f;
        out.fog(fogEnd, ash * (d.phase == Phase.WARNING ? 0.3f : 0.8f));

        double dist = horizontal(d.pos(), cam);
        float i5 = d.intensity / 5f;
        float near = 1f - smoothstep(6f, 50f + d.intensity * 12f, (float) dist);
        float far = 1f - smoothstep(50f, 260f, (float) dist);
        float blast = d.visualState instanceof State st ? st.blastShake : 0f;
        // Constant volcanic tremor (also during the warning) plus jolts from each blast.
        out.shake(s * i5 * (near * 0.45f + far * 0.1f) + blast);
        out.fovPulse(Math.min(1f, blast * 1.2f) * near);
    }

    // ------------------------------------------------------------------ particles + sound

    @Override
    public void tick(ClientDisaster d, ClientLevel level, Camera camera) {
        State st = d.visualState instanceof State s ? s : new State();
        d.visualState = st;
        Vec3 cam = camera.position();
        tickBlasts(d, st, cam);
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

    private void spawnParticles(ClientDisaster d, ClientLevel level, Camera camera, Vec3 cam) {
        float density = density();
        if (density <= 0) return;

        Vec3 vent = vent(d);
        Vec3 wind = wind(d);
        float col = column(d);
        float s = d.strength();
        float height = plumeHeight(d) * (0.3f + 0.7f * col);
        double dist = horizontal(d.pos(), cam);
        float lod = dist < 128 ? 1f : dist < 256 ? 0.6f : dist < 400 ? 0.35f : 0.15f;
        double spread = height * 0.6;
        AABB box = new AABB(vent.x - spread, vent.y - 4, vent.z - spread, vent.x + spread, vent.y + height + 12, vent.z + spread)
                .expandTowards(wind.scale(height * 0.4));
        boolean visible = camera.getCullFrustum() == null || camera.getCullFrustum().isVisible(box);
        if (!visible) lod *= 0.1f;
        float budget = (6 + d.intensity * 4) * density * lod;

        // Warning: thin steam and smoke wisps from the vent.
        if (d.phase == Phase.WARNING || col < 0.05f) {
            if (dist < 220 && RANDOM.nextFloat() < 0.4f * density * lod + s) {
                level.addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, true, false,
                        vent.x + RANDOM.nextGaussian() * 0.8, vent.y + 0.2, vent.z + RANDOM.nextGaussian() * 0.8,
                        wind.x * 0.01, 0.05 + RANDOM.nextDouble() * 0.04, wind.z * 0.01);
            }
            if (col < 0.05f) return;
        }

        // Eruption column: puffs along the leaning stem.
        int stem = count(budget * col);
        for (int i = 0; i < stem; i++) {
            float hf = (float) Math.pow(RANDOM.nextFloat(), 0.8);
            double lean = Math.pow(hf, 1.6) * height * 0.35;
            double r = (1.5 + hf * height * 0.1) * RANDOM.nextFloat();
            double a = RANDOM.nextDouble() * Math.PI * 2;
            level.addParticle(ModRegistries.ASH_CLOUD.get(), true, true,
                    vent.x + wind.x * lean + Math.cos(a) * r, vent.y + 1 + hf * height, vent.z + wind.z * lean + Math.sin(a) * r,
                    wind.x * 0.04 * hf, 0.08 + RANDOM.nextDouble() * 0.12 * (1 - hf), wind.z * 0.04 * hf);
        }
        // Umbrella cloud spreading out at the top of the column.
        int cap = count(budget * 0.8f * col);
        for (int i = 0; i < cap; i++) {
            double a = RANDOM.nextDouble() * Math.PI * 2;
            double r = Math.sqrt(RANDOM.nextDouble()) * height * 0.45;
            double cx = vent.x + wind.x * height * 0.35, cz = vent.z + wind.z * height * 0.35;
            double y = vent.y + height * (0.85 + RANDOM.nextFloat() * 0.18) - r * 0.12;
            level.addParticle(ModRegistries.ASH_CLOUD.get(), true, true, cx + Math.cos(a) * r, y, cz + Math.sin(a) * r,
                    Math.cos(a) * 0.08 + wind.x * 0.08, 0.005, Math.sin(a) * 0.08 + wind.z * 0.08);
        }

        // Lava fountain + glow at the vent.
        if (dist < 260) {
            int lava = count((1 + d.intensity) * s * density * lod * (d.phase == Phase.PEAK ? 1f : 0.4f));
            for (int i = 0; i < lava; i++) {
                level.addParticle(ParticleTypes.LAVA, true, false, vent.x + RANDOM.nextGaussian() * 0.8, vent.y + 0.2,
                        vent.z + RANDOM.nextGaussian() * 0.8, 0, 0, 0);
            }
            int flames = count(2 * s * density * lod);
            for (int i = 0; i < flames; i++) {
                level.addParticle(ParticleTypes.FLAME, vent.x + RANDOM.nextGaussian() * 0.7, vent.y + 0.3,
                        vent.z + RANDOM.nextGaussian() * 0.7, 0, 0.08 + RANDOM.nextDouble() * 0.1, 0);
            }
        }

        // Falling ash around the viewer when under the ash cloud (only where it can reach: open sky).
        float ashR = ashRadius(d);
        double ashDist = horizontal(ashCenter(d), cam);
        float under = (1f - smoothstep(ashR * 0.5f, ashR * 1.2f, (float) ashDist)) * Math.max(s, col * 0.8f);
        if (under > 0.02f && d.phase != Phase.WARNING) {
            int flakes = count(under * (10 + d.intensity * 6) * density);
            for (int i = 0; i < flakes; i++) {
                double x = cam.x + (RANDOM.nextDouble() - 0.5) * 40, z = cam.z + (RANDOM.nextDouble() - 0.5) * 40;
                double y = cam.y + 4 + RANDOM.nextDouble() * 14;
                if (y < level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(x), Mth.floor(z))) continue;
                level.addParticle(ModRegistries.ASH_FALL.get(), x, y, z,
                        wind.x * 0.03, -0.035 - RANDOM.nextDouble() * 0.03, wind.z * 0.03);
            }
        }
    }

    /** Fire and smoke trail behind a flying lava bomb (called from the bomb's client tick). */
    public static void bombTrail(LavaBombEntity bomb) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        float density = density();
        if (density <= 0) return;
        double dist = mc.gameRenderer.getMainCamera().position().distanceTo(bomb.position());
        float lod = dist < 64 ? 1f : dist < 160 ? 0.5f : 0.2f;
        float n = 1.6f * density * lod;
        Vec3 p = bomb.position();
        Vec3 v = bomb.getDeltaMovement();
        int smoke = count(n);
        for (int i = 0; i < smoke; i++) {
            double t = RANDOM.nextDouble();
            mc.level.addParticle(ModRegistries.ASH_TRAIL.get(), true, false, p.x - v.x * t, p.y + 0.3 - v.y * t, p.z - v.z * t,
                    0, 0.02, 0);
        }
        if (RANDOM.nextFloat() < 0.8f * density * lod) {
            mc.level.addParticle(ParticleTypes.FLAME, p.x, p.y + 0.3, p.z, v.x * 0.1, v.y * 0.1, v.z * 0.1);
        }
        if (RANDOM.nextFloat() < 0.15f * density * lod) {
            mc.level.addParticle(ParticleTypes.LAVA, p.x, p.y + 0.3, p.z, 0, 0, 0);
        }
    }

    private void tickBlasts(ClientDisaster d, State st, Vec3 cam) {
        st.blastShake *= 0.9f;
        int blasts = d.data.length > VolcanoDisaster.D_BLASTS ? Math.round(d.data[VolcanoDisaster.D_BLASTS]) : 0;
        double dist = cam.distanceTo(vent(d));
        if (st.lastBlasts < 0) {
            st.lastBlasts = blasts;
        } else if (blasts > st.lastBlasts) {
            st.lastBlasts = blasts;
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
