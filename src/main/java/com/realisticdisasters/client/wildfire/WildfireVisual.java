package com.realisticdisasters.client.wildfire;

import com.realisticdisasters.client.ClientDisaster;
import com.realisticdisasters.client.DisasterVisual;
import com.realisticdisasters.client.sound.DisasterLoopSound;
import com.realisticdisasters.config.ClientConfig;
import com.realisticdisasters.disaster.Phase;
import com.realisticdisasters.disaster.wildfire.WildfireDisaster;
import com.realisticdisasters.registry.ModRegistries;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Wildfire presentation, matched against real fire footage:
 * <ul>
 * <li>Flames: a band of animated, upright flame tongues along the front; where the fire is in the trees it torches
 *     up through the canopy with flames one to two tree heights tall.</li>
 * <li>Smoke: dense grey-brown smoke boiling off the flames (pale grey-white over grass), a towering convection
 *     column of cauliflower billows with sunlit tops, bent downwind by wind shear, spreading into a tan smoke layer
 *     that streams downwind for hundreds of blocks.</li>
 * <li>Light: blue sky upwind; under the smoke the light turns yellow-brown and dim; near the flames and at night
 *     the smoke and fog glow orange.</li>
 * <li>Embers lofted by the plume, ash snowing down under it, layered crackle / roar / distant rumble.</li>
 * </ul>
 * The server sends a dozen random burning columns per sync; the client keeps the recent ones as its picture of the
 * front, and looks at the terrain there to tell crown fires from surface fires.
 */
public final class WildfireVisual implements DisasterVisual {
    // Daylight under the smoke: yellow-brown haze (sun filtered through smoke), tan-grey smoke layer.
    private static final int SKY_DAY = 0xB59B78;
    private static final int FOG_DAY = 0xA4937E;
    private static final int CLOUDS_DAY = 0x8C8072;
    // Fire glow on the smoke (near the front, and dominant at night).
    private static final int FOG_GLOW = 0xC8743C;
    private static final int SKY_NIGHT_GLOW = 0x5C2C16;
    private static final int FOG_NIGHT_GLOW = 0x7A3A1A;

    private static final int MAX_POINTS = 160;
    private static final int POINT_LIFE = 50;
    private static final RandomSource RANDOM = RandomSource.create();

    static final class State {
        final double[] px = new double[MAX_POINTS], pz = new double[MAX_POINTS];
        final int[] ground = new int[MAX_POINTS], top = new int[MAX_POINTS];
        final boolean[] crown = new boolean[MAX_POINTS];
        final boolean[] flameBlock = new boolean[MAX_POINTS];
        final long[] expire = new long[MAX_POINTS];
        int head, count;
        float[] lastData;
        long time;
        Vec3 nearest;
        double nearestDist = Double.MAX_VALUE;
        FireLoopSound crackle, roar, whirl;
        DisasterLoopSound distant;
        int flareCooldown;
        Vec3 whirlPos;
    }

    private static State state(ClientDisaster d) {
        if (!(d.visualState instanceof State st)) {
            State s = new State();
            d.visualState = s;
            return s;
        }
        return st;
    }

    // ------------------------------------------------------------------ front tracking

    private static void updatePoints(ClientDisaster d, ClientLevel level, State st, Vec3 cam) {
        st.time++;
        if (st.flareCooldown > 0) st.flareCooldown--;
        if (whirlPower(d) > 0.03f) {
            Vec3 c = whirlCentre(d);
            st.whirlPos = new Vec3(c.x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(c.x), Mth.floor(c.z)) + 4, c.z);
        }
        if (d.data != st.lastData) {
            st.lastData = d.data;
            int n = (int) (d.data.length > WildfireDisaster.D_POINT_COUNT ? d.data[WildfireDisaster.D_POINT_COUNT] : 0);
            Vec3 c = d.pos();
            for (int k = 0; k < n; k++) {
                int i = WildfireDisaster.D_POINTS + k * 2;
                if (i + 1 >= d.data.length) break;
                float rawZ = d.data[i + 1];
                boolean crownFire = rawZ > WildfireDisaster.CROWN_FLAG / 2;
                if (crownFire) rawZ -= WildfireDisaster.CROWN_FLAG;
                double x = c.x + d.data[i], z = c.z + rawZ;
                int bx = Mth.floor(x), bz = Mth.floor(z);
                int slot = st.head;
                st.head = (st.head + 1) % MAX_POINTS;
                st.count = Math.min(MAX_POINTS, st.count + 1);
                st.px[slot] = x;
                st.pz[slot] = z;
                st.ground[slot] = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
                st.top[slot] = level.getHeight(Heightmap.Types.WORLD_SURFACE, bx, bz);
                BlockState topBlock = level.getBlockState(new BlockPos(bx, st.top[slot] - 1, bz));
                boolean canopy = topBlock.is(BlockTags.LEAVES) || topBlock.is(BlockTags.LOGS);
                // the server says whether the fire is in the crowns; a surface fire under a canopy stays low
                st.crown[slot] = crownFire && canopy;
                if (st.crown[slot]) flare(level, st, x, st.top[slot], z, cam);
                st.flameBlock[slot] = level.getBlockState(new BlockPos(bx, st.top[slot], bz)).is(ModRegistries.WILDFIRE_FLAME.get())
                        || level.getBlockState(new BlockPos(bx, st.ground[slot], bz)).is(ModRegistries.WILDFIRE_FLAME.get());
                st.expire[slot] = st.time + POINT_LIFE + RANDOM.nextInt(20);
            }
        }
        double best = Double.MAX_VALUE;
        int bi = -1;
        for (int k = 0; k < st.count; k++) {
            if (st.expire[k] < st.time) continue;
            double dx = st.px[k] - cam.x, dz = st.pz[k] - cam.z;
            double dd = dx * dx + dz * dz;
            if (dd < best) {
                best = dd;
                bi = k;
            }
        }
        if (bi >= 0) {
            st.nearest = new Vec3(st.px[bi], st.ground[bi] + 1.5, st.pz[bi]);
            st.nearestDist = Math.sqrt(best);
        } else {
            Vec3 c = d.pos();
            st.nearest = new Vec3(c.x, c.y + 1.5, c.z);
            st.nearestDist = Math.max(0, horizontalDistance(d, cam) - d.radius * 0.7);
        }
    }

    /**
     * A tree torching: the crown goes up in a column of flame several times its height with a burst of embers and a
     * deep whoosh. Rate-limited so a running crown fire is a series of flare-ups, not a constant blur.
     */
    private static void flare(ClientLevel level, State st, double x, int top, double z, Vec3 cam) {
        if (st.flareCooldown > 0) return;
        double dist = Math.sqrt((x - cam.x) * (x - cam.x) + (z - cam.z) * (z - cam.z));
        if (dist > 140) return;
        st.flareCooldown = 12 + RANDOM.nextInt(30);
        float density = ClientConfig.PARTICLE_DENSITY.get().floatValue();
        int flames = count(10 * density);
        for (int n = 0; n < flames; n++) {
            float size = 2.2f + RANDOM.nextFloat() * 2.6f;
            level.addParticle(ModRegistries.FIRE_TONGUE.get(), true, true, x + RANDOM.nextGaussian() * 1.2,
                    top - 1 + size * 0.9 + RANDOM.nextDouble() * 3, z + RANDOM.nextGaussian() * 1.2,
                    size, 0.08 + RANDOM.nextDouble() * 0.1, RANDOM.nextInt(2));
        }
        int sparks = count(24 * density);
        for (int n = 0; n < sparks; n++) {
            level.addParticle(ModRegistries.EMBER.get(), true, false, x + RANDOM.nextGaussian(), top + RANDOM.nextDouble() * 3,
                    z + RANDOM.nextGaussian(), RANDOM.nextGaussian() * 0.12, 0.35 + RANDOM.nextDouble() * 0.4, RANDOM.nextGaussian() * 0.12);
        }
        Minecraft.getInstance().getSoundManager().play(new SimpleSoundInstance(ModRegistries.WILDFIRE_WHOOSH.get().location(),
                SoundSource.BLOCKS, 1.6f + RANDOM.nextFloat() * 0.6f, 0.8f + RANDOM.nextFloat() * 0.3f, RANDOM, false, 0,
                SoundInstance.Attenuation.LINEAR, x, top + 2, z, false));
    }

    private static float whirlPower(ClientDisaster d) {
        return Mth.clamp(d.data(WildfireDisaster.D_WHIRL_POWER), 0f, 1.2f);
    }

    private static Vec3 whirlCentre(ClientDisaster d) {
        return new Vec3(d.pos().x + d.data(WildfireDisaster.D_WHIRL_X), d.pos().y, d.pos().z + d.data(WildfireDisaster.D_WHIRL_Z));
    }

    /** Spinning column of flame and smoke for an active fire whirl. */
    private void spawnWhirl(ClientDisaster d, ClientLevel level, Camera camera, Vec3 cam, float density) {
        float power = whirlPower(d);
        if (power < 0.03f) return;
        Vec3 c = whirlCentre(d);
        int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(c.x), Mth.floor(c.z));
        float height = Math.max(10f, d.data(WildfireDisaster.D_WHIRL_HEIGHT));
        double dist = Math.sqrt((c.x - cam.x) * (c.x - cam.x) + (c.z - cam.z) * (c.z - cam.z));
        AABB box = new AABB(c.x - 6, ground - 1, c.z - 6, c.x + 6, ground + height + 6, c.z + 6);
        float lod = dist < 96 ? 1f : dist < 200 ? 0.6f : 0.3f;
        if (camera.getCullFrustum() != null && !camera.getCullFrustum().isVisible(box)) lod *= 0.1f;
        int flames = count(40f * power * density * lod);
        for (int n = 0; n < flames; n++) {
            float frac = (float) Math.pow(RANDOM.nextDouble(), 1.3) * 0.7f;
            double a = RANDOM.nextDouble() * Math.PI * 2, r = 0.1 + RANDOM.nextDouble() * 0.3;
            level.addParticle(ModRegistries.FIRE_WHIRL.get(), true, true, c.x + Math.cos(a) * r, ground + frac * height,
                    c.z + Math.sin(a) * r, d.id, frac, RANDOM.nextFloat());
        }
        int smoke = count(6f * power * density * lod);
        for (int n = 0; n < smoke; n++) {
            float frac = 0.2f + RANDOM.nextFloat() * 0.5f;
            double a = RANDOM.nextDouble() * Math.PI * 2, r = 1.5 + RANDOM.nextDouble() * 1.5;
            level.addParticle(ModRegistries.WHIRL_SMOKE.get(), true, true, c.x + Math.cos(a) * r, ground + frac * height,
                    c.z + Math.sin(a) * r, d.id, frac, RANDOM.nextFloat());
        }
        int sparks = count(3f * power * density * lod);
        for (int n = 0; n < sparks; n++) {
            level.addParticle(ModRegistries.EMBER.get(), true, false, c.x + RANDOM.nextGaussian(), ground + RANDOM.nextDouble() * height * 0.6,
                    c.z + RANDOM.nextGaussian(), RANDOM.nextGaussian() * 0.25, 0.4 + RANDOM.nextDouble() * 0.4, RANDOM.nextGaussian() * 0.25);
        }
    }

    private static int randomPoint(State st) {
        if (st.count == 0) return -1;
        for (int tries = 0; tries < 4; tries++) {
            int k = RANDOM.nextInt(st.count);
            if (st.expire[k] >= st.time) return k;
        }
        return -1;
    }

    // ------------------------------------------------------------------ atmosphere

    /** 0..1 how much of the smoke layer is over the camera: near the front, or anywhere in the plume downwind. */
    private static float pallWeight(ClientDisaster d, State st, Vec3 cam) {
        double distFront = st.nearestDist == Double.MAX_VALUE ? horizontalDistance(d, cam) : st.nearestDist;
        float s = d.strength();
        float heat = heat(d);
        float reach = 110f + d.intensity * 30f;
        // only right next to the fire is the smoke all around you; further away it is a column you look at
        float near = 1f - smoothstep(8f, 40f + d.intensity * 8f, (float) distFront);
        double wx = d.data(WildfireDisaster.D_WIND_X), wz = d.data(WildfireDisaster.D_WIND_Z);
        double dx = cam.x - d.pos().x, dz = cam.z - d.pos().z;
        double along = dx * wx + dz * wz;
        double across = Math.abs(dx * wz - dz * wx);
        float plume = 0f;
        if (along > 0) {
            // the plume widens with distance downwind (like the satellite pictures of real smoke plumes)
            plume = (1f - smoothstep(reach, reach * 3.5f, (float) along))
                    * (1f - smoothstep(d.radius + 10f, d.radius + 40f + (float) along * 0.3f, (float) across));
        }
        return s * Math.max(near, plume) * (0.5f + 0.5f * heat);
    }

    private static float nightFactor() {
        ClientLevel level = Minecraft.getInstance().level;
        return level == null ? 0f : Mth.clamp(level.getSkyDarken() / 11f, 0f, 1f);
    }

    @Override
    public void contribute(ClientDisaster d, Vec3 cam, Atmosphere out) {
        State st = state(d);
        double distFront = st.nearestDist == Double.MAX_VALUE ? horizontalDistance(d, cam) : st.nearestDist;
        float s = d.strength();
        float heat = heat(d);
        float w = pallWeight(d, st, cam);
        float night = nightFactor();
        // firelight on the smoke: close to the flames by day, everywhere near the fire at night
        float close = 1f - smoothstep(4f, 45f, (float) distFront);
        float glow = Mth.clamp(close * 0.55f + night * (1f - smoothstep(20f, 220f, (float) distFront)) * 0.8f, 0f, 1f) * heat;

        out.sky(SKY_DAY, w * 0.85f * (1f - night));
        out.sky(SKY_NIGHT_GLOW, w * glow * night);
        out.clouds(CLOUDS_DAY, w);
        out.fogColor(FOG_DAY, w * (1f - glow));
        out.fogColor(night > 0.5f ? FOG_NIGHT_GLOW : FOG_GLOW, w * glow);
        out.darken(w * (0.12f + d.intensity * 0.05f) * (1f - night * 0.5f));
        float fogEnd = 20f + (5 - d.intensity) * 9f + (float) distFront * 0.4f;
        out.fog(fogEnd, w * (d.phase == Phase.WARNING ? 0.3f : 0.75f));

        // standing right at the front: the roar rattles the view and the heat shimmers
        float veryClose = 1f - smoothstep(3f, 24f, (float) distFront);
        out.shake(s * heat * veryClose * 0.18f * (0.4f + d.intensity * 0.12f));
        out.fovPulse(s * heat * veryClose * 0.7f);
        // a fire whirl close by: roaring, buffeting wind
        float wp = whirlPower(d);
        if (wp > 0.03f) {
            Vec3 wc = whirlCentre(d);
            double dw = Math.sqrt((wc.x - cam.x) * (wc.x - cam.x) + (wc.z - cam.z) * (wc.z - cam.z));
            float near = 1f - smoothstep(4f, 40f, (float) dw);
            out.shake(wp * near * 0.35f);
            out.fovPulse(wp * near);
        }
    }

    // ------------------------------------------------------------------ particles + sound

    @Override
    public void tick(ClientDisaster d, ClientLevel level, Camera camera) {
        State st = state(d);
        Vec3 cam = camera.position();
        updatePoints(d, level, st, cam);
        tickSounds(d, st);
        spawnParticles(d, st, level, camera, cam);
    }

    private void spawnParticles(ClientDisaster d, State st, ClientLevel level, Camera camera, Vec3 cam) {
        Minecraft mc = Minecraft.getInstance();
        float density = ClientConfig.PARTICLE_DENSITY.get().floatValue();
        ParticleStatus status = mc.options.particles().get();
        density *= switch (status) {
            case ALL -> 1f;
            case DECREASED -> 0.5f;
            case MINIMAL -> 0.2f;
        };
        if (density <= 0 || st.count == 0) return;

        float s = Math.max(0.15f, d.strength());
        float heat = heat(d);
        int i = d.intensity;
        double wx = d.data(WildfireDisaster.D_WIND_X), wz = d.data(WildfireDisaster.D_WIND_Z);
        float wind = d.data(WildfireDisaster.D_WIND_SPEED);
        float ceiling = Math.max(20f, d.data(WildfireDisaster.D_SMOKE_HEIGHT));
        float scale = density * s * (0.35f + 0.65f * heat);
        int id = d.id;

        // ---- flames: surface fire along the front, torching crowns where it is in the trees
        int flames = count((12f + i * 6f) * scale);
        for (int n = 0; n < flames; n++) {
            int k = randomPoint(st);
            if (k < 0) continue;
            double dist = Math.sqrt(distSq(st, k, cam));
            if (!acceptFlame(st, k, camera, dist)) continue;
            boolean crown = st.crown[k] && RANDOM.nextFloat() < 0.7f;
            // up close, the animated flame blocks already show the surface fire
            if (!crown && dist < 48 && st.flameBlock[k] && RANDOM.nextFloat() < 0.75f) continue;
            // far away, fewer but bigger flames keep the front readable (like flames seen across a valley)
            float lodScale = dist > 64 ? (float) Math.min(2.6, 1 + (dist - 64) / 80) : 1f;
            float size;
            double y;
            if (crown) {
                size = (1.8f + RANDOM.nextFloat() * (1.4f + i * 0.4f)) * lodScale;
                y = st.top[k] - 1.5 + size * 0.85 + RANDOM.nextDouble() * 1.5;
            } else {
                size = (0.5f + RANDOM.nextFloat() * (0.45f + i * 0.12f)) * lodScale;
                y = st.ground[k] + size * 0.8;
            }
            level.addParticle(ModRegistries.FIRE_TONGUE.get(), true, true,
                    st.px[k] + RANDOM.nextGaussian() * 0.7, y, st.pz[k] + RANDOM.nextGaussian() * 0.7,
                    size, 0.02 + RANDOM.nextDouble() * 0.04, RANDOM.nextInt(2));
        }

        // ---- fire whirl
        spawnWhirl(d, level, camera, cam, density);

        // ---- smoke boiling off the flames (dark over forest, pale over grass)
        int smoke = count((4f + i * 2.2f) * scale);
        for (int n = 0; n < smoke; n++) {
            int k = randomPoint(st);
            if (k < 0) continue;
            double dist = Math.sqrt(distSq(st, k, cam));
            if (!accept(st, k, camera, dist, ceiling)) continue;
            float shade = st.crown[k] ? 0.75f + RANDOM.nextFloat() * 0.25f : 0.15f + RANDOM.nextFloat() * 0.35f;
            level.addParticle(ModRegistries.WILDFIRE_SMOKE.get(), true, true,
                    st.px[k] + RANDOM.nextGaussian(), st.top[k] + 0.5 + RANDOM.nextDouble(), st.pz[k] + RANDOM.nextGaussian(),
                    id, shade, st.ground[k]);
        }

        // ---- the convection column: billows rising from the most active part of the front
        int billows = count((1.2f + i * 0.8f) * scale);
        Vec3 c = d.pos();
        for (int n = 0; n < billows; n++) {
            int k = randomPoint(st);
            if (k < 0) continue;
            // pull toward the centre of the fire: one big column rather than a curtain
            double bx = Mth.lerp(0.55, st.px[k], c.x), bz = Mth.lerp(0.55, st.pz[k], c.z);
            double dist = Math.sqrt((bx - cam.x) * (bx - cam.x) + (bz - cam.z) * (bz - cam.z));
            float lod = dist < 160 ? 1f : dist < 320 ? 0.7f : 0.4f;
            if (RANDOM.nextFloat() > lod) continue;
            float shade = 0.55f + RANDOM.nextFloat() * 0.4f;
            level.addParticle(ModRegistries.SMOKE_BILLOW.get(), true, true,
                    bx + RANDOM.nextGaussian() * 3, st.top[k] + 3 + RANDOM.nextDouble() * 6, bz + RANDOM.nextGaussian() * 3,
                    id, shade, st.ground[k]);
        }

        // ---- the plume streaming downwind from the top of the column
        if (d.phase != Phase.WARNING) {
            int sheets = count(0.6f * density * s * heat * (1 + i * 0.3f));
            for (int n = 0; n < sheets; n++) {
                double along = Math.pow(RANDOM.nextDouble(), 0.8) * (140 + i * 45);
                double across = RANDOM.nextGaussian() * (d.radius * 0.6 + along * 0.18);
                double px = c.x + wx * along - wz * across, pz = c.z + wz * along + wx * across;
                double dist = Math.sqrt((px - cam.x) * (px - cam.x) + (pz - cam.z) * (pz - cam.z));
                if (dist > 360) continue;
                double py = c.y + ceiling * (0.8 + along / 500.0) + RANDOM.nextGaussian() * (4 + along * 0.04);
                level.addParticle(ModRegistries.SMOKE_PALL.get(), true, true, px, py, pz, id, 0.6f + RANDOM.nextFloat() * 0.3f, c.y);
            }
        }

        // ---- embers lofted out of torching trees and carried downwind
        int embers = count((2f + i * 2f) * scale);
        for (int n = 0; n < embers; n++) {
            int k = randomPoint(st);
            if (k < 0 || distSq(st, k, cam) > 140 * 140) continue;
            double y = (st.crown[k] ? st.top[k] : st.ground[k]) + 0.5 + RANDOM.nextDouble() * 2;
            level.addParticle(ModRegistries.EMBER.get(), true, false,
                    st.px[k] + RANDOM.nextGaussian(), y, st.pz[k] + RANDOM.nextGaussian(),
                    wx * wind * (0.08 + RANDOM.nextDouble() * 0.12), 0.15 + RANDOM.nextDouble() * 0.25,
                    wz * wind * (0.08 + RANDOM.nextDouble() * 0.12));
        }

        // ---- ash falls wherever the smoke layer is overhead
        float pall = Math.min(1f, pallWeight(d, st, cam));
        int ash = count((2f + i * 1.2f) * density * pall);
        for (int n = 0; n < ash; n++) {
            double ax = cam.x + (RANDOM.nextDouble() - 0.5) * 32, az = cam.z + (RANDOM.nextDouble() - 0.5) * 32;
            double ay = cam.y + 3 + RANDOM.nextDouble() * 12;
            if (!level.canSeeSky(BlockPos.containing(ax, ay, az))) continue;
            level.addParticle(ModRegistries.ASH_FLAKE.get(), ax, ay, az, wx * wind * 0.05, -0.02, wz * wind * 0.05);
        }
    }

    /** Flames are what the eye looks for: gentler distance LOD than smoke (they get bigger instead). */
    private static boolean acceptFlame(State st, int k, Camera camera, double dist) {
        float lod = dist < 96 ? 1f : dist < 192 ? 0.7f : 0.45f;
        AABB box = new AABB(st.px[k] - 4, st.ground[k] - 1, st.pz[k] - 4, st.px[k] + 4, st.top[k] + 10, st.pz[k] + 4);
        if (camera.getCullFrustum() != null && !camera.getCullFrustum().isVisible(box)) return false;
        return RANDOM.nextFloat() < lod;
    }

    /** Distance LOD + frustum culling for one front point. */
    private static boolean accept(State st, int k, Camera camera, double dist, double height) {
        float lod = dist < 64 ? 1f : dist < 128 ? 0.6f : dist < 256 ? 0.35f : 0.15f;
        AABB box = new AABB(st.px[k] - 4, st.ground[k] - 1, st.pz[k] - 4, st.px[k] + 4, st.top[k] + height, st.pz[k] + 4);
        if (camera.getCullFrustum() != null && !camera.getCullFrustum().isVisible(box)) lod *= 0.08f;
        return RANDOM.nextFloat() < lod;
    }

    private static double distSq(State st, int k, Vec3 cam) {
        double dx = st.px[k] - cam.x, dy = st.ground[k] - cam.y, dz = st.pz[k] - cam.z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static int count(float expected) {
        int n = (int) expected;
        if (RANDOM.nextFloat() < expected - n) n++;
        return n;
    }

    private void tickSounds(ClientDisaster d, State st) {
        var sounds = Minecraft.getInstance().getSoundManager();
        if (st.crackle == null || st.crackle.isStopped()) {
            // Close layer: crackling and popping right at the nearest flames.
            st.crackle = new FireLoopSound(ModRegistries.WILDFIRE_CRACKLE.get(), d, () -> st.nearest,
                    x -> x.strength() * (0.5 + 0.5 * heat(x)) * 1.6, x -> 1.0);
            sounds.play(st.crackle);
        }
        if (st.roar == null || st.roar.isStopped()) {
            // Mid layer: the low roar of a big front, carries further.
            st.roar = new FireLoopSound(ModRegistries.WILDFIRE_ROAR.get(), d, () -> st.nearest,
                    x -> x.strength() * heat(x) * (1.6 + x.intensity * 0.5), x -> 0.8 + 0.04 * (5 - x.intensity));
            sounds.play(st.roar);
        }
        if (st.whirl == null || st.whirl.isStopped()) {
            // Fire whirl: a jet-like roar that sits on the whirl.
            st.whirl = new FireLoopSound(ModRegistries.WILDFIRE_WHIRL.get(), d, () -> st.whirlPos != null ? st.whirlPos : st.nearest,
                    x -> whirlPower(x) * 3.0, x -> 0.9 + 0.15 * whirlPower(x));
            sounds.play(st.whirl);
        }
        if (st.distant == null || st.distant.isStopped()) {
            // Far layer: non-attenuated rushing rumble that fades with distance.
            st.distant = new DisasterLoopSound(ModRegistries.WILDFIRE_DISTANT.get(), d, false,
                    x -> {
                        State s2 = state(x);
                        double dist = s2.nearestDist == Double.MAX_VALUE ? 1000 : s2.nearestDist;
                        return x.strength() * heat(x) * 0.6 * (1 - smoothstep(30f, 360f, (float) dist)) * (0.5 + 0.1 * x.intensity);
                    },
                    x -> 1.0);
            sounds.play(st.distant);
        }
    }

    private static float heat(ClientDisaster d) {
        return Mth.clamp(d.data(WildfireDisaster.D_HEAT), 0.15f, 1f);
    }

    @Override
    public void onRemoved(ClientDisaster d) {
        if (d.visualState instanceof State st) {
            if (st.crackle != null) st.crackle.end();
            if (st.roar != null) st.roar.end();
            if (st.whirl != null) st.whirl.end();
            if (st.distant != null) st.distant.end();
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
