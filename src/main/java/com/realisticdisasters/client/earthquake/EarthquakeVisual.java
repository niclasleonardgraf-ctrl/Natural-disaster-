package com.realisticdisasters.client.earthquake;

import com.realisticdisasters.client.ClientDisaster;
import com.realisticdisasters.client.DisasterVisual;
import com.realisticdisasters.client.sound.DisasterLoopSound;
import com.realisticdisasters.config.ClientConfig;
import com.realisticdisasters.disaster.Phase;
import com.realisticdisasters.disaster.earthquake.EarthquakeDisaster;
import com.realisticdisasters.registry.ModRegistries;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Earthquake presentation: a dusty haze over the epicentre, camera shake that escalates with the tremor and is
 * strongest near the epicentre (plus a fast rattle layer, see {@link EarthquakeCamera}), dust billowing from the
 * ground and from opening fissures, rock chips hopping off the ground, dust trickling from ceilings, and a layered
 * rumble (deep far rumble, grinding close rumble, cracking rock).
 */
public final class EarthquakeVisual implements DisasterVisual {
    private static final int SKY = 0xA8977A;     // dusty khaki
    private static final int FOG = 0x9C8B70;
    private static final int CLOUDS = 0x8E8270;

    private static final RandomSource RANDOM = RandomSource.create();

    private static final class State {
        DisasterLoopSound close, distant;
        int nextRumble = 20;
        int nextCrack = 10;
        int nextCreak = 30;
    }

    public static float radius(ClientDisaster d) {
        float r = d.data(EarthquakeDisaster.D_RADIUS);
        return r > 1 ? r : 20f + d.intensity * 10f;
    }

    public static float tremor(ClientDisaster d) {
        return Mth.clamp(d.data(EarthquakeDisaster.D_TREMOR), 0f, 1f);
    }

    // ------------------------------------------------------------------ atmosphere

    @Override
    public void contribute(ClientDisaster d, Vec3 cam, Atmosphere out) {
        double dist = horizontalDistance(d, cam);
        float radius = radius(d);
        float s = d.strength();
        float tremor = tremor(d);

        // Dust haze: thrown up by the main shock, hangs over the epicentre and settles in the aftermath.
        float haze = s * (1f - smoothstep(radius * 0.6f, radius * 3.5f, (float) dist));
        if (d.phase == Phase.WARNING) haze *= 0.3f;
        out.sky(SKY, haze * 0.35f);
        out.clouds(CLOUDS, haze * 0.45f);
        out.fogColor(FOG, haze * 0.8f);
        out.darken(haze * (0.04f + d.intensity * 0.02f));
        out.fog(50f + (5 - d.intensity) * 18f + (float) dist * 0.4f, haze * 0.65f);

        // Shake: strongest at the epicentre, still felt (weaker) far beyond the damage radius.
        float felt = radius * 4f + 80f;
        float near = 1f - smoothstep(radius * 0.15f, radius * 1.1f, (float) dist);
        float far = 1f - smoothstep(radius * 0.8f, felt, (float) dist);
        float scale = 0.3f + 0.16f * d.intensity;
        out.shake(tremor * scale * (near * 0.85f + far * 0.3f));
        out.fovPulse(tremor * near * 0.6f);
        EarthquakeCamera.addRattle(tremor * tremor * (0.4f + 0.12f * d.intensity) * (near + far * 0.25f));
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

        float tremor = tremor(d);
        if (tremor < 0.05f) return;
        Vec3 c = d.pos();
        float radius = radius(d);
        double dist = horizontalDistance(d, cam);
        if (dist > radius + 220) return;
        // Distance LOD.
        float lod = dist < radius ? 1f : dist < radius + 64 ? 0.6f : dist < radius + 140 ? 0.3f : 0.12f;
        Frustum frustum = camera.getCullFrustum();
        AABB box = new AABB(c.x - radius, c.y - 16, c.z - radius, c.x + radius, c.y + 24, c.z + radius);
        if (frustum != null && !frustum.isVisible(box)) return; // whole quake zone off-screen

        float budget = (6 + d.intensity * 5) * density * lod;

        // Ground dust: puffs kicked up across the zone. Inside the zone they cluster around the viewer.
        boolean inside = dist < radius;
        double sx = inside ? cam.x : c.x, sz = inside ? cam.z : c.z;
        double spread = inside ? Math.min(radius, 36) : radius;
        int dustCount = count(budget * 0.3f * tremor * tremor);
        for (int i = 0; i < dustCount; i++) {
            double a = RANDOM.nextDouble() * Math.PI * 2;
            double r = Math.sqrt(RANDOM.nextDouble()) * spread;
            double x = sx + Math.cos(a) * r, z = sz + Math.sin(a) * r;
            if (sq(x - c.x) + sq(z - c.z) > radius * radius) continue;
            if (sq(x - cam.x) + sq(z - cam.z) < 36) continue; // never right in the viewer's face
            spawnDust(level, frustum, x, z, 0.02 + RANDOM.nextDouble() * 0.04, 0.05);
        }

        // Fissures: dust plumes and rock chips bursting from the opening tips.
        float cracking = d.data(EarthquakeDisaster.D_CRACKING);
        int tips = Math.min(EarthquakeDisaster.MAX_SYNCED_TIPS, Math.round(d.data(EarthquakeDisaster.D_TIP_COUNT)));
        if (cracking > 0.05f) {
            for (int t = 0; t < tips; t++) {
                double tx = c.x + d.data(EarthquakeDisaster.D_TIPS + t * 2);
                double tz = c.z + d.data(EarthquakeDisaster.D_TIPS + t * 2 + 1);
                int plume = count(budget * 0.35f * cracking);
                for (int i = 0; i < plume; i++) {
                    double x = tx + (RANDOM.nextDouble() - 0.5) * 3, z = tz + (RANDOM.nextDouble() - 0.5) * 3;
                    spawnDust(level, frustum, x, z, 0.08 + RANDOM.nextDouble() * 0.12, 0.08);
                }
                if (sq(tx - cam.x) + sq(tz - cam.z) < 90 * 90) {
                    int chips = count(budget * 0.5f * cracking);
                    for (int i = 0; i < chips; i++) {
                        spawnChip(level, tx + (RANDOM.nextDouble() - 0.5) * 3, tz + (RANDOM.nextDouble() - 0.5) * 3, 0.35);
                    }
                }
            }
        }

        // Rock chips hopping off the ground around the viewer when the shaking is strong.
        if (inside && tremor > 0.4f) {
            int chips = count(budget * 0.4f * (tremor - 0.3f));
            for (int i = 0; i < chips; i++) {
                double a = RANDOM.nextDouble() * Math.PI * 2, r = 2 + RANDOM.nextDouble() * 14;
                spawnChip(level, cam.x + Math.cos(a) * r, cam.z + Math.sin(a) * r, 0.18);
            }
        }

        // Indoors / under overhangs: dust trickles from the ceiling.
        if (dist < radius * 1.3 && tremor > 0.2f) {
            int trickles = count(3f * density * tremor);
            for (int i = 0; i < trickles; i++) {
                BlockPos base = BlockPos.containing(cam.x + (RANDOM.nextDouble() - 0.5) * 10, cam.y + 1, cam.z + (RANDOM.nextDouble() - 0.5) * 10);
                for (int up = 0; up < 6; up++) {
                    BlockPos bp = base.above(up);
                    BlockState bs = level.getBlockState(bp);
                    if (bs.isAir()) continue;
                    if (bs.isSolidRender()) {
                        level.addParticle(new BlockParticleOption(ParticleTypes.FALLING_DUST, bs),
                                bp.getX() + RANDOM.nextDouble(), bp.getY() - 0.05, bp.getZ() + RANDOM.nextDouble(), 0, 0, 0);
                    }
                    break;
                }
            }
        }
    }

    private static void spawnDust(ClientLevel level, Frustum frustum, double x, double z, double up, double side) {
        int ix = Mth.floor(x), iz = Mth.floor(z);
        if (!level.hasChunk(ix >> 4, iz >> 4)) return;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, ix, iz);
        if (frustum != null && !frustum.isVisible(new AABB(x - 2, y - 1, z - 2, x + 2, y + 3, z + 2))) return;
        level.addParticle(ModRegistries.QUAKE_DUST.get(), true, true, x, y + 0.2, z,
                (RANDOM.nextDouble() - 0.5) * side, up, (RANDOM.nextDouble() - 0.5) * side);
    }

    /** A real block-texture chip from the ground at (x, z), thrown upward. */
    private static void spawnChip(ClientLevel level, double x, double z, double up) {
        int ix = Mth.floor(x), iz = Mth.floor(z);
        if (!level.hasChunk(ix >> 4, iz >> 4)) return;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, ix, iz) - 1;
        BlockState bs = level.getBlockState(new BlockPos(ix, y, iz));
        if (bs.isAir()) return;
        level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, bs), x, y + 1.05, z,
                (RANDOM.nextDouble() - 0.5) * 0.2, up * (0.6 + RANDOM.nextDouble() * 0.8), (RANDOM.nextDouble() - 0.5) * 0.2);
    }

    private static int count(float expected) {
        int n = (int) expected;
        if (RANDOM.nextFloat() < expected - n) n++;
        return n;
    }

    private void tickSounds(ClientDisaster d, State st, Vec3 cam) {
        Minecraft mc = Minecraft.getInstance();
        var sounds = mc.getSoundManager();
        if (st.close == null || st.close.isStopped()) {
            // Close layer: grinding, rattling rumble that comes from the ground all around you inside the zone.
            st.close = new DisasterLoopSound(ModRegistries.QUAKE_RUMBLE.get(), d, false,
                    x -> {
                        double dist = horizontalDistance(x, camPos());
                        float r = radius(x);
                        return tremor(x) * (1 - smoothstep(r * 0.8f, r * 2f, (float) dist)) * (0.6 + 0.12 * x.intensity);
                    },
                    x -> 0.8 + 0.25 * tremor(x));
            sounds.play(st.close);
        }
        if (st.distant == null || st.distant.isStopped()) {
            // Distant layer: deep low rumble that carries far and sits under the close layer.
            st.distant = new DisasterLoopSound(ModRegistries.QUAKE_DISTANT.get(), d, false,
                    x -> {
                        double dist = horizontalDistance(x, camPos());
                        float r = radius(x);
                        float t = Math.max(tremor(x), x.strength() * 0.15f);
                        return t * (0.45 + 0.1 * x.intensity) * (1 - smoothstep(r * 2f, r * 6f + 160f, (float) dist));
                    },
                    x -> 0.9 + 0.1 * tremor(x));
            sounds.play(st.distant);
        }

        double dist = horizontalDistance(d, cam);
        float radius = radius(d);
        float tremor = tremor(d);
        // Warning signs: low rumbles rolling in from the epicentre's direction.
        if (d.phase == Phase.WARNING && --st.nextRumble <= 0) {
            st.nextRumble = 120 + RANDOM.nextInt(160);
            Vec3 dir = d.pos().subtract(cam);
            Vec3 at = cam.add(dir.lengthSqr() < 1 ? Vec3.ZERO : dir.normalize().scale(Math.min(dir.length(), 24)));
            sounds.play(new SimpleSoundInstance(ModRegistries.DISTANT_RUMBLE.get().location(), SoundSource.WEATHER,
                    0.4f + 0.3f * d.strength() / 0.2f, 0.55f + RANDOM.nextFloat() * 0.2f, RANDOM, false, 0,
                    SoundInstance.Attenuation.NONE, at.x, at.y, at.z, false));
        }
        // Cracking rock where fissures tear open.
        float cracking = d.data(EarthquakeDisaster.D_CRACKING);
        int tips = Math.min(EarthquakeDisaster.MAX_SYNCED_TIPS, Math.round(d.data(EarthquakeDisaster.D_TIP_COUNT)));
        if (cracking > 0.3f && tips > 0 && dist < radius * 3 && --st.nextCrack <= 0) {
            st.nextCrack = 8 + RANDOM.nextInt(20);
            int t = RANDOM.nextInt(tips);
            double tx = d.pos().x + d.data(EarthquakeDisaster.D_TIPS + t * 2);
            double tz = d.pos().z + d.data(EarthquakeDisaster.D_TIPS + t * 2 + 1);
            sounds.play(new SimpleSoundInstance(ModRegistries.QUAKE_CRACK.get().location(), SoundSource.WEATHER,
                    1.5f + 0.3f * d.intensity, 0.7f + RANDOM.nextFloat() * 0.4f, RANDOM, false, 0,
                    SoundInstance.Attenuation.LINEAR, tx, d.pos().y, tz, false));
        }
        // Creaks and pops close by while the ground moves under you.
        if (dist < radius && tremor > 0.35f && --st.nextCreak <= 0) {
            st.nextCreak = 25 + RANDOM.nextInt(60);
            double a = RANDOM.nextDouble() * Math.PI * 2;
            sounds.play(new SimpleSoundInstance(ModRegistries.QUAKE_CRACK.get().location(), SoundSource.WEATHER,
                    0.25f + 0.35f * tremor, 1.1f + RANDOM.nextFloat() * 0.4f, RANDOM, false, 0,
                    SoundInstance.Attenuation.NONE, cam.x + Math.cos(a) * 5, cam.y - 1, cam.z + Math.sin(a) * 5, false));
        }
    }

    @Override
    public void onRemoved(ClientDisaster d) {
        if (d.visualState instanceof State st) {
            if (st.close != null) st.close.end();
            if (st.distant != null) st.distant.end();
        }
    }

    // ------------------------------------------------------------------ math

    private static Vec3 camPos() {
        return Minecraft.getInstance().gameRenderer.getMainCamera().position();
    }

    private static double sq(double v) {
        return v * v;
    }

    static double horizontalDistance(ClientDisaster d, Vec3 cam) {
        double dx = cam.x - d.pos().x, dz = cam.z - d.pos().z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    static float smoothstep(float e0, float e1, float x) {
        float t = Mth.clamp((x - e0) / (e1 - e0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }
}
