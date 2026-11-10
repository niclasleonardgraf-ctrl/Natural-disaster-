package com.realisticdisasters.disaster.wildfire;

import java.util.ArrayList;
import java.util.List;

import com.realisticdisasters.config.DisasterConfig;
import com.realisticdisasters.disaster.BlockChangeQueue;
import com.realisticdisasters.disaster.Disaster;
import com.realisticdisasters.disaster.DisasterManager;
import com.realisticdisasters.disaster.DisasterProtection;
import com.realisticdisasters.disaster.DisasterType;
import com.realisticdisasters.disaster.Phase;
import com.realisticdisasters.registry.ModRegistries;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A wildfire driven by the same factors as a real one.
 * <ul>
 * <li><b>Spread</b> (Rothermel-style): each burning block column tries to ignite its neighbours. The chance depends on
 *     the target's fuel (read from the blocks' own flammability), fuel moisture (biome rainfall and temperature,
 *     rain, nearby water, time of day), slope (fire runs uphill) and wind (head fires race downwind, backing fires
 *     creep upwind). Burn time follows the fuel load: grass flashes over, trunks and houses burn for minutes.</li>
 * <li><b>Surface vs crown fire</b>: in forests, wind and intensity decide whether the fire climbs into the crowns.
 *     Crown fires spread faster, throw embers and consume the canopy; surface fires only scorch it.</li>
 * <li><b>Spotting</b>: burning brands are lofted by the plume, carried by the wind, fall under gravity and start spot
 *     fires where they land on receptive fuel (dry grass, leaves, roofs, hay), so rivers and roads aren't always enough.</li>
 * <li><b>Fire whirls</b>: big, wind-driven fires spin up whirls that travel with the wind, ignite their path, pull
 *     in and loft light objects and throw embers.</li>
 * <li><b>Interactions</b>: water puts burning ground out (rivers are firebreaks, buckets work) with steam; heat melts
 *     snow and ice, primes TNT, lights campfires and candles; radiant heat hurts creatures near the front; dropped
 *     items burn; burnt trees topple; animals flee, preferably to water; villagers take shelter.</li>
 * </ul>
 * Every block change goes through the shared {@link BlockChangeQueue} and is checked against protection when applied.
 * The client renders everything from {@link #syncData()}.
 */
public class WildfireDisaster extends Disaster {
    /** Indices into the sync data array (shared contract with the client visual). */
    public static final int D_WIND_X = 0, D_WIND_Z = 1, D_WIND_SPEED = 2, D_FRONT_RADIUS = 3, D_BURNING = 4,
            D_HEAT = 5, D_SMOKE_HEIGHT = 6, D_POINT_COUNT = 7,
            D_WHIRL_X = 8, D_WHIRL_Z = 9, D_WHIRL_POWER = 10, D_WHIRL_HEIGHT = 11, D_POINTS = 12;
    /** Max front sample points per packet (the payload carries at most 32 floats). */
    public static final int MAX_POINTS = 10;
    /** Added to a point's z offset when that column is a crown fire (decoded by the client). */
    public static final float CROWN_FLAG = 4096f;

    private static final int FLAG_CROWN = 1;
    private static final int MAX_EMBERS = 64;

    private final Vec3 origin;
    private final int maxArea;
    private int maxRadius;
    private float baseWindSpeed;
    private double windAngle;
    private double windDrift;
    private float windSpeed;
    private boolean spotting = true;
    private boolean whirls = true;

    // Burning columns: parallel lists (swap-remove) + key -> index map for O(1) lookups.
    private final LongArrayList burning = new LongArrayList();
    private final IntArrayList timers = new IntArrayList();
    private final IntArrayList fuels = new IntArrayList();
    private final IntArrayList flags = new IntArrayList();
    private final Long2IntOpenHashMap index = new Long2IntOpenHashMap();
    /** Columns that burned, are burning, or can never burn. */
    private final LongOpenHashSet visited = new LongOpenHashSet();
    private int burntCount, ignitedCount, crownCount, spotFires, extinguished, waterCheckCursor;
    private float frontRadius = 6f;
    private boolean cancelled;

    private final List<Ember> embers = new ArrayList<>();
    private FireWhirl whirl;
    private int whirlCount;

    /** A burning brand in flight. */
    private static final class Ember {
        double x, y, z, vx, vy, vz;
        int life;
    }

    /** A fire whirl: a rotating column of fire travelling with the wind. */
    private static final class FireWhirl {
        double x, z, vx, vz;
        int age, life;
        float power, height;
    }

    public WildfireDisaster(ServerLevel level, int id, Vec3 pos, int intensity) {
        super(DisasterType.WILDFIRE, level, id, pos, intensity);
        int i = this.intensity;
        this.origin = pos;
        this.maxArea = Math.max(150, DisasterConfig.WILDFIRE_MAX_AREA.getAsInt() * i / 5);
        this.maxRadius = 36 + i * 22;                         // 58 .. 146 blocks from the ignition point
        this.baseWindSpeed = 0.35f + i * 0.12f;               // 0.47 .. 0.95 (0..1 "how wind-driven")
        this.windAngle = random.nextDouble() * Math.PI * 2;
        this.windSpeed = baseWindSpeed;
        this.index.defaultReturnValue(-1);

        double scale = DisasterConfig.WILDFIRE_DURATION_SCALE.get();
        setDuration(Phase.WARNING, 400);
        setDuration(Phase.BUILDUP, 400);
        setDuration(Phase.PEAK, (int) ((1200 + 400 * i) * scale));
        setDuration(Phase.AFTERMATH, 500);
    }

    /** Spawn-weight adjustment: hot, dry biomes (savanna, badlands) burn far more often than cool, wet ones. */
    public static int biomeWeight(Holder<Biome> biome, int weight) {
        double bonus = DisasterConfig.WILDFIRE_DRY_BIOME_BONUS.get();
        Biome b = biome.value();
        float heat = Mth.clamp((b.getBaseTemperature() - 0.7f) / 1.3f, -1f, 1f);
        float dry = b.hasPrecipitation() ? 0f : 1f;
        double factor = Math.max(0.2, 1 + bonus * (0.5 * heat + 0.5 * dry));
        return (int) Math.round(weight * factor);
    }

    @Override
    public Component describe() {
        return Component.translatable("disaster.realisticdisasters.wildfire");
    }

    @Override
    public float effectRadius() {
        return frontRadius;
    }

    @Override
    protected float[] syncData() {
        int n = Math.min(MAX_POINTS, burning.size());
        float[] out = new float[D_POINTS + n * 2];
        out[D_WIND_X] = (float) Math.cos(windAngle);
        out[D_WIND_Z] = (float) Math.sin(windAngle);
        out[D_WIND_SPEED] = windSpeed;
        out[D_FRONT_RADIUS] = frontRadius;
        out[D_BURNING] = burning.size();
        out[D_HEAT] = heat();
        out[D_SMOKE_HEIGHT] = 28f + intensity * 9f;
        out[D_POINT_COUNT] = n;
        if (whirl != null) {
            out[D_WHIRL_X] = (float) (whirl.x - pos.x);
            out[D_WHIRL_Z] = (float) (whirl.z - pos.z);
            out[D_WHIRL_POWER] = whirl.power;
            out[D_WHIRL_HEIGHT] = whirl.height;
        }
        for (int k = 0; k < n; k++) {
            int idx = n == burning.size() ? k : random.nextInt(burning.size());
            long key = burning.getLong(idx);
            boolean crown = (flags.getInt(idx) & FLAG_CROWN) != 0;
            out[D_POINTS + k * 2] = (float) (BlockPos.getX(key) + 0.5 - pos.x);
            out[D_POINTS + k * 2 + 1] = (float) (BlockPos.getZ(key) + 0.5 - pos.z) + (crown ? CROWN_FLAG : 0f);
        }
        return out;
    }

    /**
     * Fire danger: intensity stands for how long the land has been in drought (like a fire-danger index). Cured,
     * drought-stressed fuels ignite far more readily than the same vegetation after a wet spell.
     */
    private float drought() {
        return 0.75f + 0.12f * intensity;
    }

    /** 0..1: how big the fire currently is relative to what this intensity can reach. */
    public float heat() {
        return Math.min(1f, burning.size() / (30f + intensity * 30f));
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onPhaseChange(Phase from, Phase to) {
        if (to == Phase.BUILDUP) {
            broadcast(Component.translatable("disaster.realisticdisasters.wildfire.spreading").withStyle(ChatFormatting.RED), 260);
        }
    }

    @Override
    public void forceFinish() {
        // Instant removal (/disaster stop now, tests): drop pending flames/charring of this fire.
        cancelled = true;
        super.forceFinish();
    }

    @Override
    protected void onEnd() {
        if (!cancelled) {
            // Whatever is still smouldering leaves its scar.
            for (int k = 0; k < burning.size(); k++) queueChar(burning.getLong(k), fuels.getInt(k), flags.getInt(k));
        }
        burning.clear();
        timers.clear();
        fuels.clear();
        flags.clear();
        index.clear();
        embers.clear();
        whirl = null;
    }

    @Override
    protected void tickDisaster() {
        if (age() == 1) {
            broadcast(Component.translatable("disaster.realisticdisasters.wildfire.warning").withStyle(ChatFormatting.GOLD), 320);
            igniteOrigin();
        }
        updateWind();
        if (phase() != Phase.AFTERMATH) {
            spread();
            launchEmbers();
            maybeSpawnWhirl();
        }
        tickEmbers();
        tickWhirl();
        burnDown();
        checkWater();

        if (age() % 20 == 0) {
            updateCentre();
            if (burning.isEmpty() && phase() != Phase.AFTERMATH) {
                beginAftermath(); // ran out of fuel (or was put out)
            }
        }
        if (age() % 10 == 5) burnEntities();
        if (age() % 20 == 10 && phase() != Phase.AFTERMATH) alertMobs();
        if (phase() != Phase.WARNING && !burning.isEmpty() && random.nextInt(Math.max(6, 30 - intensity * 4)) == 0) {
            crackSound();
        }
    }

    // ------------------------------------------------------------------ wind

    private void updateWind() {
        // Slowly veering wind with gusts; the front follows it.
        windDrift = Mth.clamp(windDrift + (random.nextDouble() - 0.5) * 0.0004, -0.0025, 0.0025);
        windAngle += windDrift;
        float gust = (float) (Math.sin(age() * 0.013) * 0.12 + Math.sin(age() * 0.041 + 1.3) * 0.08);
        windSpeed = Mth.clamp(baseWindSpeed * (1f + gust), 0.1f, 1.2f);
    }

    // ------------------------------------------------------------------ spread

    private void igniteOrigin() {
        int ox = Mth.floor(origin.x), oz = Mth.floor(origin.z);
        // The ignition (lightning, a campfire) catches the nearest vegetation: up to 9 fuel columns within 8 blocks.
        int lit = 0;
        for (int r = 0; r <= 8 && lit < 9; r++) {
            for (int dx = -r; dx <= r && lit < 9; dx++) {
                for (int dz = -r; dz <= r && lit < 9; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue; // ring r only
                    int x = ox + dx, z = oz + dz;
                    if (!level.isLoaded(new BlockPos(x, 0, z))) continue;
                    FireFuel.Column col = FireFuel.scan(level, x, z);
                    if (col.burnable()) {
                        ignite(x, z, col, false);
                        lit++;
                    }
                }
            }
        }
        if (lit == 0 && level.isLoaded(BlockPos.containing(origin))) {
            // nothing to burn nearby: it smoulders briefly and dies
            ignite(ox, oz, new FireFuel.Column(FireFuel.GRASS, Mth.floor(origin.y), Mth.floor(origin.y), 0.4f, 0.4f, 0, 0), false);
        }
    }

    /** Relative spread rate of a burning column (crown fires run, structures smoulder). */
    private static float columnRate(int fuel, int flag) {
        if ((flag & FLAG_CROWN) != 0) return 1.8f;
        return switch (fuel) {
            case FireFuel.GRASS -> 1.3f;   // fine, dry fuel: fast
            case FireFuel.BRUSH -> 1.1f;
            case FireFuel.FOREST -> 0.75f; // surface fire under canopy
            case FireFuel.STRUCTURE -> 0.5f;
            default -> 0.8f;
        };
    }

    private void spread() {
        if (burning.isEmpty() || ignitedCount >= maxArea) return;
        int cap = DisasterConfig.WILDFIRE_MAX_IGNITIONS_PER_TICK.getAsInt();
        float u = Math.min(1.2f, windSpeed);
        // Rothermel: rate of spread grows with wind (phi_w) on top of the no-wind rate.
        float rate = (0.03f + 0.018f * intensity) * (float) (double) DisasterConfig.WILDFIRE_SPREAD_SCALE.get()
                * (0.12f + 0.88f * strength()) * (1f + 0.8f * u);
        float expected = rate * 1.8f * burning.size();
        int attempts = (int) expected;
        if (random.nextFloat() < expected - attempts) attempts++;
        if (burning.size() < 8) attempts = Math.max(attempts, 1); // a young fire doesn't fizzle out by bad luck
        attempts = Math.min(attempts, cap * 4);
        boolean rainSlows = DisasterConfig.WILDFIRE_RAIN_SLOWS.getAsBoolean();
        double windBias = 0.35 + 0.5 * Math.min(1f, u);
        double sigma = 1.2 - 0.65 * Math.min(1f, u);
        int ignitions = 0;

        for (int a = 0; a < attempts && ignitions < cap; a++) {
            int k = random.nextInt(burning.size());
            int srcFuel = fuels.getInt(k), srcFlag = flags.getInt(k);
            if (random.nextFloat() * 1.8f > columnRate(srcFuel, srcFlag)) continue;
            long from = burning.getLong(k);
            int fx = BlockPos.getX(from), fz = BlockPos.getZ(from);
            // Direction: mostly downwind (head fire), sometimes flanking / backing.
            int x = fx, z = fz;
            long key = 0;
            double cosToWind = 0;
            boolean found = false;
            for (int tries = 0; tries < 2 && !found; tries++) {
                double angle = random.nextDouble() < windBias
                        ? windAngle + random.nextGaussian() * sigma
                        : random.nextDouble() * Math.PI * 2;
                x = fx + (int) Math.round(Math.cos(angle));
                z = fz + (int) Math.round(Math.sin(angle));
                if (x == fx && z == fz) continue;
                key = BlockPos.asLong(x, 0, z);
                found = !visited.contains(key);
                cosToWind = Math.cos(angle - windAngle);
            }
            if (!found || !inRange(x, z)) continue;

            FireFuel.Column col = FireFuel.scan(level, x, z);
            if (!col.burnable()) {
                visited.add(key);
                continue;
            }
            float chance = col.ignitability() * FireFuel.dryness(level, x, col.ground(), z, rainSlows) * drought();
            // head fire gets a boost, backing fire is held back (flame tilt / pre-heating by the wind)
            chance *= (float) Mth.clamp(1 + 0.7 * u * cosToWind, 0.25, 1.8);
            boolean forestEdge = col.type() == FireFuel.FOREST || srcFuel == FireFuel.FOREST;
            if (!forestEdge) {
                // terrain slope: fire climbs uphill fast (pre-heating), struggles going down; cliffs stop it
                int fy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, fx, fz);
                int ty = col.ground() + 1;
                int dy = ty - fy;
                if (Math.abs(dy) > 4) continue;
                chance *= Mth.clamp(1f + 0.35f * dy * Math.abs(dy) * 0.5f + 0.15f * dy, 0.35f, 2.5f);
            }
            if (random.nextFloat() < chance) {
                ignite(x, z, col, (srcFlag & FLAG_CROWN) != 0);
                ignitions++;
            }
        }
    }

    private boolean inRange(int x, int z) {
        double ox = x + 0.5 - origin.x, oz = z + 0.5 - origin.z;
        return ox * ox + oz * oz <= (double) maxRadius * maxRadius && level.isLoaded(new BlockPos(x, 0, z));
    }

    private void ignite(int x, int z, FireFuel.Column col, boolean fromCrown) {
        long key = BlockPos.asLong(x, 0, z);
        if (index.containsKey(key)) return;
        visited.add(key);
        int flag = 0;
        if (col.type() == FireFuel.FOREST && col.leaves() > 0) {
            // Crown fire initiation: wind, intensity and a crown fire next door push it into the canopy.
            float u = Math.min(1.2f, windSpeed);
            float p = -0.15f + 0.55f * u + 0.1f * intensity + (fromCrown ? 0.3f : 0f);
            if (random.nextFloat() < Mth.clamp(p, 0.05f, 0.95f)) flag |= FLAG_CROWN;
        }
        // Burn time follows the fuel load: grass flashes over, trunks and houses burn for minutes.
        int burn = (int) ((25 + col.load() * 10) * (0.75f + random.nextFloat() * 0.5f));
        if ((flag & FLAG_CROWN) != 0) burn = (int) (burn * 0.8f);
        burn = Mth.clamp(burn, 40, 600);
        index.put(key, burning.size());
        burning.add(key);
        timers.add(burn);
        fuels.add(col.type());
        flags.add(flag);
        ignitedCount++;
        if ((flag & FLAG_CROWN) != 0) crownCount++;
        queueFlames(x, z);
    }

    private void burnDown() {
        boolean raining = DisasterConfig.WILDFIRE_RAIN_SLOWS.getAsBoolean() && level.isRaining();
        int step = raining && age() % 2 == 0 ? 2 : 1; // rain shortens burning by a third
        for (int k = burning.size() - 1; k >= 0; k--) {
            int t = timers.getInt(k) - step;
            if (t <= 0) {
                long key = burning.getLong(k);
                int fuel = fuels.getInt(k), flag = flags.getInt(k);
                removeAt(k);
                burntCount++;
                queueChar(key, fuel, flag);
            } else {
                timers.set(k, t);
                // Long-burning columns flare up again (flames burn out on their own).
                if (t % 90 == 0 && fuels.getInt(k) >= FireFuel.FOREST) {
                    long key = burning.getLong(k);
                    queueFlames(BlockPos.getX(key), BlockPos.getZ(key));
                }
            }
        }
    }

    private void removeAt(int k) {
        long key = burning.getLong(k);
        if ((flags.getInt(k) & FLAG_CROWN) != 0) crownCount--;
        int last = burning.size() - 1;
        if (k != last) {
            long moved = burning.getLong(last);
            burning.set(k, moved);
            timers.set(k, timers.getInt(last));
            fuels.set(k, fuels.getInt(last));
            flags.set(k, flags.getInt(last));
            index.put(moved, k);
        }
        burning.removeLong(last);
        timers.removeInt(last);
        fuels.removeInt(last);
        flags.removeInt(last);
        index.remove(key);
    }

    /** Recentres the disaster on the burning front (sync range, client effects). */
    private void updateCentre() {
        if (burning.isEmpty()) return;
        double sx = 0, sz = 0;
        for (int k = 0; k < burning.size(); k++) {
            long key = burning.getLong(k);
            sx += BlockPos.getX(key) + 0.5;
            sz += BlockPos.getZ(key) + 0.5;
        }
        double cx = sx / burning.size(), cz = sz / burning.size();
        double maxD = 0;
        for (int k = 0; k < burning.size(); k++) {
            long key = burning.getLong(k);
            double dx = BlockPos.getX(key) + 0.5 - cx, dz = BlockPos.getZ(key) + 0.5 - cz;
            maxD = Math.max(maxD, dx * dx + dz * dz);
        }
        frontRadius = (float) Math.sqrt(maxD) + 6f;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(cx), Mth.floor(cz));
        pos = new Vec3(cx, y, cz);
    }

    // ------------------------------------------------------------------ water

    /** Water on a burning column puts it out (rivers, buckets, rising floods), with steam and a hiss. */
    private void checkWater() {
        if (burning.isEmpty()) return;
        int checks = Math.max(1, burning.size() / 20);
        for (int c = 0; c < checks && !burning.isEmpty(); c++) {
            waterCheckCursor = (waterCheckCursor + 1) % burning.size();
            long key = burning.getLong(waterCheckCursor);
            int x = BlockPos.getX(key), z = BlockPos.getZ(key);
            int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
            BlockPos p = new BlockPos(x, top, z);
            if (level.getFluidState(p).is(FluidTags.WATER) || level.getFluidState(p.above()).is(FluidTags.WATER)) {
                removeAt(waterCheckCursor);
                extinguished++;
                steam(x + 0.5, top + 1.0, z + 0.5, 6);
                level.playSound(null, x + 0.5, top + 1, z + 0.5, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.7f,
                        1.6f + random.nextFloat() * 0.6f);
            }
        }
    }

    private void steam(double x, double y, double z, int puffs) {
        for (int i = 0; i < puffs; i++) {
            // count 0 => the "offset" arguments are the particle's own args: no disaster, pale, ground y
            level.sendParticles(ModRegistries.SMOLDER_SMOKE.get(), x + random.nextGaussian() * 0.4, y,
                    z + random.nextGaussian() * 0.4, 0, -1, 0.0, y, 1.0);
        }
    }

    // ------------------------------------------------------------------ embers (spotting)

    private void launchEmbers() {
        int structures = fuelsContainStructures();
        if (!spotting || crownCount <= 0 && structures == 0) return;
        float u = Math.min(1.2f, windSpeed);
        float expected = (0.004f * crownCount + 0.002f * structuresBurning) * u * (1f + intensity * 0.3f);
        int n = (int) expected;
        if (random.nextFloat() < expected - n) n++;
        for (int i = 0; i < n && embers.size() < MAX_EMBERS; i++) {
            int k = random.nextInt(burning.size());
            if ((flags.getInt(k) & FLAG_CROWN) == 0 && fuels.getInt(k) != FireFuel.STRUCTURE) continue;
            long key = burning.getLong(k);
            int x = BlockPos.getX(key), z = BlockPos.getZ(key);
            launch(x + random.nextDouble(), level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) + 1, z + random.nextDouble(),
                    0.3 + random.nextDouble() * 0.45);
        }
    }

    private int structuresBurning;

    private int fuelsContainStructures() {
        if (age() % 20 == 0) {
            int s = 0;
            for (int k = 0; k < fuels.size(); k++) if (fuels.getInt(k) == FireFuel.STRUCTURE) s++;
            structuresBurning = s;
        }
        return structuresBurning;
    }

    private void launch(double x, double y, double z, double lift) {
        Ember e = new Ember();
        e.x = x;
        e.y = y;
        e.z = z;
        e.vx = Math.cos(windAngle) * windSpeed * 0.15;
        e.vz = Math.sin(windAngle) * windSpeed * 0.15;
        e.vy = lift;
        e.life = 60 + random.nextInt(160); // brands burn out in the air
        embers.add(e);
    }

    private void tickEmbers() {
        if (embers.isEmpty()) return;
        double wx = Math.cos(windAngle) * windSpeed * 0.5, wz = Math.sin(windAngle) * windSpeed * 0.5;
        boolean rainSlows = DisasterConfig.WILDFIRE_RAIN_SLOWS.getAsBoolean();
        for (int i = embers.size() - 1; i >= 0; i--) {
            Ember e = embers.get(i);
            // drag toward the wind, gravity toward a low terminal velocity (light brands drift far)
            e.vx += (wx - e.vx) * 0.06;
            e.vz += (wz - e.vz) * 0.06;
            e.vy = Math.max(e.vy - 0.016, -0.16);
            e.x += e.vx;
            e.y += e.vy;
            e.z += e.vz;
            int bx = Mth.floor(e.x), bz = Mth.floor(e.z);
            if (--e.life <= 0 || !level.isLoaded(new BlockPos(bx, 0, bz))) {
                embers.remove(i);
                continue;
            }
            int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE, bx, bz);
            if (e.y > surface) continue;
            embers.remove(i);
            // landed: a spot fire if the fuel is receptive and dry enough
            long key = BlockPos.asLong(bx, 0, bz);
            if (visited.contains(key) || ignitedCount >= maxArea) continue;
            double ox = bx + 0.5 - origin.x, oz = bz + 0.5 - origin.z;
            double reach = maxRadius + 24;
            if (ox * ox + oz * oz > reach * reach) continue;
            FireFuel.Column col = FireFuel.scan(level, bx, bz);
            if (!col.burnable()) continue;
            float receptive = switch (col.type()) {
                case FireFuel.GRASS, FireFuel.BRUSH -> 0.6f;
                case FireFuel.FOREST -> 0.35f;
                default -> 0.25f; // roofs, hay
            };
            if (random.nextFloat() < receptive * FireFuel.dryness(level, bx, col.ground(), bz, rainSlows) * drought()) {
                ignite(bx, bz, col, false);
                spotFires++;
            }
        }
    }

    // ------------------------------------------------------------------ fire whirls

    private void maybeSpawnWhirl() {
        // Dev only (visual checks): -Drealisticdisasters.wildfire.whirlAt=<ticks> forces a whirl at that age.
        if (whirl == null && !burning.isEmpty() && !net.neoforged.fml.loading.FMLEnvironment.isProduction()
                && Integer.getInteger("realisticdisasters.wildfire.whirlAt", -1) == age()) {
            spawnWhirl(origin.x, origin.z); // at the ignition point, where the dev camera looks
            return;
        }
        if (!whirls || whirl != null || phase() != Phase.PEAK || intensity < 3 || windSpeed < 0.55f || burning.size() < 80) return;
        if (random.nextInt(Math.max(200, 900 - intensity * 120)) != 0) return;
        long key = burning.getLong(random.nextInt(burning.size()));
        spawnWhirl(BlockPos.getX(key) + 0.5, BlockPos.getZ(key) + 0.5);
    }

    private void spawnWhirl(double x, double z) {
        FireWhirl w = new FireWhirl();
        w.x = x;
        w.z = z;
        w.life = 220 + random.nextInt(260);
        w.height = 14 + intensity * 4 + random.nextInt(8);
        whirl = w;
        whirlCount++;
    }

    private void tickWhirl() {
        FireWhirl w = whirl;
        if (w == null) return;
        w.age++;
        float in = Mth.clamp(w.age / 40f, 0f, 1f), out = Mth.clamp((w.life - w.age) / 60f, 0f, 1f);
        w.power = in * out * (0.6f + intensity * 0.08f);
        if (w.age >= w.life || !level.isLoaded(BlockPos.containing(w.x, 0, w.z))) {
            whirl = null;
            return;
        }
        // travels with the wind, wandering
        // whirls stay anchored near the hottest part of the fire and creep downwind
        double wx = Math.cos(windAngle) * windSpeed * 0.035, wz = Math.sin(windAngle) * windSpeed * 0.035;
        w.vx += (wx - w.vx) * 0.05 + (random.nextDouble() - 0.5) * 0.006;
        w.vz += (wz - w.vz) * 0.05 + (random.nextDouble() - 0.5) * 0.006;
        w.x += w.vx;
        w.z += w.vz;
        int bx = Mth.floor(w.x), bz = Mth.floor(w.z);
        int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
        if (w.power < 0.05f) return;

        // ignites its path
        if (w.age % 4 == 0) {
            for (int i = 0; i < 2; i++) {
                int x = bx + random.nextInt(5) - 2, z = bz + random.nextInt(5) - 2;
                long key = BlockPos.asLong(x, 0, z);
                if (visited.contains(key) || !level.isLoaded(new BlockPos(x, 0, z))) continue;
                FireFuel.Column col = FireFuel.scan(level, x, z);
                if (col.burnable() && random.nextFloat() < 0.6f * w.power) ignite(x, z, col, true);
            }
        }
        // throws embers high
        if (spotting && random.nextFloat() < 0.25f * w.power && embers.size() < MAX_EMBERS) {
            launch(w.x, ground + 2, w.z, 0.7 + random.nextDouble() * 0.6);
        }
        // Air flow of the whirl (Rankine vortex): solid-body rotation inside the core radius, 1/r outside; inflow along
        // the ground; a strong updraft in the core. Objects follow the air through drag, so light things get pulled
        // in and carried up the column while heavy ones are only shoved.
        double rc = 1.8, reach = 8.0;
        double vMax = 0.55 * w.power, vUp = 0.6 * w.power;
        AABB box = new AABB(w.x - reach, ground - 2, w.z - reach, w.x + reach, ground + w.height, w.z + reach);
        for (Entity e : level.getEntities((Entity) null, box, e -> !e.isSpectator())) {
            if (e instanceof Player p && (p.getAbilities().flying || p.isCreative())) continue;
            double dx = e.getX() - w.x, dz = e.getZ() - w.z;
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d > reach || d < 1.0e-3) continue;
            double nx = dx / d, nz = dz / d;
            double vt = d < rc ? vMax * d / rc : vMax * rc / d;
            double vr = -0.45 * vt * Mth.clamp(1 - (e.getY() - ground) / w.height, 0, 1); // inflow, strongest low down
            double vz = vUp * Math.exp(-(d / rc) * (d / rc)) * Mth.clamp(1 - (e.getY() - ground) / w.height, 0, 1);
            Vec3 air = new Vec3(nx * vr - nz * vt, vz, nz * vr + nx * vt);
            // drag coefficient ~ exposed area per mass: items and debris follow the air, big animals barely
            double drag = e instanceof ItemEntity || e instanceof FallingBlockEntity ? 0.2
                    : e instanceof LivingEntity le ? 0.12 * Mth.clamp(1.0 - le.getBbWidth() * le.getBbHeight() * 0.3, 0.15, 1.0) : 0.08;
            Vec3 v = e.getDeltaMovement();
            e.setDeltaMovement(v.add(air.subtract(v).scale(drag)));
            e.hurtMarked = true;
            if (d < 2.5 && e instanceof LivingEntity le && !le.fireImmune()) le.igniteForSeconds(4f);
            if (d < 2.5 && e instanceof ItemEntity item) item.hurtServer(level, level.damageSources().inFire(), 2f);
        }
    }

    // ------------------------------------------------------------------ block changes (all queued)

    private boolean destructionOn() {
        return DisasterConfig.BLOCK_DESTRUCTION.getAsBoolean();
    }

    private void queueFlames(int x, int z) {
        if (!destructionOn()) return;
        DisasterManager.blockQueue(level).add(l -> placeFlames(x, z));
    }

    private void placeFlames(int x, int z) {
        if (cancelled || !level.isLoaded(new BlockPos(x, 0, z))) return;
        int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        heatColumn(x, top - 1, z);
        placeFlameAt(new BlockPos(x, top, z));
        if (ground < top - 1) placeFlameAt(new BlockPos(x, ground, z)); // surface fire under the burning canopy
    }

    /**
     * Radiant and convective heat around a freshly burning column: explosives go off (anything that reacts to
     * catching fire, through NeoForge's onCaughtFire), campfires and candles light, snow and ice melt, and water
     * hisses into steam.
     */
    private void heatColumn(int x, int y, int z) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        boolean steamed = false;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE, x + dx, z + dz) - 1;
                int floor = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x + dx, z + dz) - 1;
                // the top block, and the forest floor under a canopy
                for (int sy : floor < surface ? new int[]{surface, floor} : new int[]{surface}) {
                    if (Math.abs(sy - y) > 3 && sy != floor) continue;
                    p.set(x + dx, sy, z + dz);
                    steamed = heatBlock(p, steamed);
                }
            }
        }
    }

    /** @return whether steam was already shown for this column */
    private boolean heatBlock(BlockPos.MutableBlockPos p, boolean steamed) {
        BlockState st = level.getBlockState(p);
        if (st.getBlock() instanceof TntBlock) {
            if (DisasterProtection.canModify(level, p, st)) {
                st.onCaughtFire(level, p.immutable(), Direction.UP, null);
                level.removeBlock(p, false);
            }
        } else if (st.hasProperty(BlockStateProperties.LIT) && !st.getValue(BlockStateProperties.LIT)
                && (st.is(BlockTags.CAMPFIRES) || st.is(BlockTags.CANDLES))
                && !(st.hasProperty(BlockStateProperties.WATERLOGGED) && st.getValue(BlockStateProperties.WATERLOGGED))) {
            if (DisasterProtection.canModify(level, p, st)) level.setBlock(p, st.setValue(BlockStateProperties.LIT, true), Block.UPDATE_ALL);
        } else if (st.is(Blocks.SNOW) || st.is(Blocks.POWDER_SNOW) || st.is(Blocks.SNOW_BLOCK)) {
            if (DisasterProtection.canModify(level, p, st)) {
                level.removeBlock(p, false);
                if (!steamed) steam(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5, 3);
                steamed = true;
            }
        } else if (st.is(Blocks.ICE)) {
            if (DisasterProtection.canModify(level, p, st)) level.setBlock(p, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
        } else if (!steamed && st.getFluidState().is(FluidTags.WATER) && random.nextInt(3) == 0) {
            steam(p.getX() + 0.5, p.getY() + 1.0, p.getZ() + 0.5, 2);
            steamed = true;
        }
        return steamed;
    }

    private void placeFlameAt(BlockPos p) {
        BlockState here = level.getBlockState(p);
        BlockPos at = p;
        // A plant on top is consumed by the flame itself.
        BlockState below = level.getBlockState(p.below());
        if (FireFuel.isPlant(below) && below.canBeReplaced()) {
            at = p.below();
            here = below;
        }
        if (!here.isAir() && !(here.canBeReplaced() && here.getFluidState().isEmpty())) return;
        BlockState flame = ModRegistries.WILDFIRE_FLAME.get().defaultBlockState();
        if (!flame.canSurvive(level, at)) return;
        if (!DisasterProtection.canModify(level, at, here)) return;
        level.setBlock(at, flame, Block.UPDATE_ALL);
    }

    private void queueChar(long key, int fuel, int flag) {
        if (!destructionOn()) return;
        int x = BlockPos.getX(key), z = BlockPos.getZ(key);
        DisasterManager.blockQueue(level).add(l -> charColumn(x, z, fuel, flag));
    }

    /** Leaves the burn scar: canopy consumed or scorched, trunks charred (some topple), turf scorched. */
    private void charColumn(int x, int z, int fuel, int flag) {
        if (cancelled || !level.isLoaded(new BlockPos(x, 0, z))) return;
        int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        int bottom = top - 40;
        boolean crown = (flag & FLAG_CROWN) != 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, top, z);
        boolean protectedChecked = false;
        IntArrayList trunk = new IntArrayList();
        for (int y = top; y > bottom; y--) {
            p.setY(y);
            BlockState st = level.getBlockState(p);
            if (st.isAir() || st.getBlock() instanceof WildfireFlameBlock) continue;
            if (!st.getFluidState().isEmpty() || st.hasBlockEntity()) break;
            if (!protectedChecked) {
                // One full protection check per column (spawn radius, areas, claims) before touching anything.
                if (!DisasterProtection.canModify(level, p, st)) return;
                protectedChecked = true;
            }
            BlockState result = charred(st, crown);
            if (result == null) {
                if (st.is(BlockTags.LEAVES) || st.is(BlockTags.LOGS) || FireFuel.isPlant(st) || st.canBeReplaced()
                        || st.is(ModRegistries.SCORCHED_LEAVES.get()) || st.is(ModRegistries.CHARRED_LOG.get())) continue;
                break; // non-flammable ground or structure: the scar ends here
            }
            if (st.is(BlockTags.LOGS)) trunk.add(y);
            if (result != st) level.setBlock(p, result, Block.UPDATE_ALL);
            if (isGround(st)) {
                // A few residual flames linger on the burnt ground behind the front.
                BlockPos above = p.above();
                if (level.getBlockState(above).isAir() && random.nextFloat() < 0.15f) {
                    BlockState flame = ModRegistries.WILDFIRE_FLAME.get().defaultBlockState();
                    if (flame.canSurvive(level, above)) level.setBlock(above, flame, Block.UPDATE_ALL);
                }
                break;
            }
        }
        if (trunk.size() >= 4 && random.nextFloat() < 0.1f + 0.03f * intensity) topple(x, z, trunk);
    }

    /**
     * A burnt-through trunk snaps near the base and falls downwind: the upper part becomes falling charred logs, laid
     * along the fall direction, with a crack and a crash.
     */
    private void topple(int x, int z, IntArrayList trunkYs) {
        // trunkYs is top-down; keep the bottom two logs as a stump
        double dirX = Math.cos(windAngle + (random.nextDouble() - 0.5) * 0.8), dirZ = Math.sin(windAngle + (random.nextDouble() - 0.5) * 0.8);
        Direction.Axis axis = Math.abs(dirX) > Math.abs(dirZ) ? Direction.Axis.X : Direction.Axis.Z;
        int stumpTop = trunkYs.getInt(trunkYs.size() - 1) + 1;
        int fallen = 0;
        for (int i = trunkYs.size() - 1; i >= 0; i--) {
            int y = trunkYs.getInt(i);
            if (y <= stumpTop) continue;
            BlockPos lp = new BlockPos(x, y, z);
            BlockState st = level.getBlockState(lp);
            if (!st.is(ModRegistries.CHARRED_LOG.get())) continue;
            double h = y - stumpTop;
            FallingBlockEntity fb = FallingBlockEntity.fall(level, lp, st.setValue(BlockStateProperties.AXIS, axis));
            fb.setDeltaMovement(dirX * 0.07 * h, 0.05, dirZ * 0.07 * h); // higher parts swing further out
            fb.setHurtsEntities(1.5f, 20);
            fb.hurtMarked = true;
            fallen++;
        }
        if (fallen > 0) {
            level.playSound(null, x + 0.5, stumpTop + 1, z + 0.5, ModRegistries.WOOD_SNAP.get(), SoundSource.BLOCKS, 2.0f,
                    0.8f + random.nextFloat() * 0.2f);
        }
    }

    private static boolean isGround(BlockState st) {
        return st.is(Blocks.GRASS_BLOCK) || st.is(Blocks.PODZOL) || st.is(Blocks.MYCELIUM) || st.is(Blocks.MOSS_BLOCK)
                || st.is(BlockTags.DIRT) || st.is(ModRegistries.SCORCHED_EARTH.get());
    }

    /** @return the burnt replacement, {@code st} itself to keep it, or null if fire doesn't affect it */
    private BlockState charred(BlockState st, boolean crown) {
        if (st.is(ModRegistries.CHARRED_LOG.get()) || st.is(ModRegistries.SCORCHED_EARTH.get())
                || st.is(ModRegistries.SCORCHED_LEAVES.get())) {
            return st;
        }
        if (st.is(BlockTags.LOGS)) {
            BlockState log = ModRegistries.CHARRED_LOG.get().defaultBlockState().setValue(Smoldering.SMOLDERING, true);
            if (st.hasProperty(BlockStateProperties.AXIS)) {
                log = log.setValue(BlockStateProperties.AXIS, st.getValue(BlockStateProperties.AXIS));
            }
            return log;
        }
        if (st.is(BlockTags.LEAVES)) {
            // A crown fire consumes the foliage; a surface fire only scorches it brown (heat-killed crowns).
            float consumed = crown ? 0.85f + intensity * 0.03f : 0.1f + intensity * 0.04f;
            return random.nextFloat() < consumed
                    ? Blocks.AIR.defaultBlockState() : ModRegistries.SCORCHED_LEAVES.get().defaultBlockState();
        }
        if (FireFuel.isPlant(st) || st.is(Blocks.HAY_BLOCK) || st.is(Blocks.VINE)
                || st.is(Blocks.MOSS_CARPET) || st.is(BlockTags.WOOL_CARPETS)) {
            return Blocks.AIR.defaultBlockState();
        }
        if (st.is(Blocks.GRASS_BLOCK) || st.is(Blocks.PODZOL) || st.is(Blocks.MYCELIUM) || st.is(Blocks.MOSS_BLOCK)) {
            // Mostly black, ash-covered ground; a patchy mosaic like real burn scars.
            return random.nextFloat() < 0.85f
                    ? ModRegistries.SCORCHED_EARTH.get().defaultBlockState().setValue(Smoldering.SMOLDERING, random.nextFloat() < 0.7f)
                    : Blocks.COARSE_DIRT.defaultBlockState();
        }
        if (st.is(BlockTags.DIRT)) return st;
        // Wooden structures and other flammables: higher intensities burn them down.
        if (st.ignitedByLava() && random.nextFloat() < 0.15f + intensity * 0.12f) {
            return Blocks.AIR.defaultBlockState();
        }
        return st.ignitedByLava() ? st : null;
    }

    // ------------------------------------------------------------------ entities

    private void burnEntities() {
        if (burning.isEmpty()) return;
        double r = frontRadius + 3;
        AABB box = new AABB(pos.x - r, pos.y - 24, pos.z - r, pos.x + r, pos.y + 40, pos.z + r);
        DamageSource source = level.damageSources().source(ModRegistries.WILDFIRE_DAMAGE);
        float s = Math.max(0.3f, strength());
        for (Entity e : level.getEntities((Entity) null, box, e -> !e.isSpectator() && !e.fireImmune())) {
            if (e instanceof Player p && p.isCreative()) continue;
            if (!(e instanceof LivingEntity) && !(e instanceof ItemEntity)) continue;
            int x = Mth.floor(e.getX()), z = Mth.floor(e.getZ());
            int ground = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            if (e.getY() > ground + 3 || e.isInWater()) continue; // above the flames or in water
            boolean inFire = index.containsKey(BlockPos.asLong(x, 0, z));
            if (e instanceof ItemEntity item) {
                if (inFire) item.hurtServer(level, level.damageSources().inFire(), 4f); // dropped items burn up
                continue;
            }
            LivingEntity le = (LivingEntity) e;
            if (inFire) {
                le.igniteForSeconds(3f + intensity);
                le.hurtServer(level, source, (0.5f + intensity * 0.3f) * s);
                continue;
            }
            // radiant heat from a wall of flame right next to you
            int hot = 0;
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++)
                    if (index.containsKey(BlockPos.asLong(x + dx, 0, z + dz))) hot++;
            if (hot >= 3) {
                le.hurtServer(level, source, 0.25f * hot * s);
                if (random.nextFloat() < 0.15f * hot) le.igniteForSeconds(2f);
            }
        }
    }

    private void alertMobs() {
        double r = frontRadius + 24 + (phase() == Phase.WARNING ? 16 : 0);
        AABB box = new AABB(pos.x - r, pos.y - 16, pos.z - r, pos.x + r, pos.y + 24, pos.z + r);
        Vec3 downwind = new Vec3(Math.cos(windAngle), 0, Math.sin(windAngle));
        List<Vec3> refuges = findWater(r + 16);
        for (PathfinderMob mob : level.getEntitiesOfClass(PathfinderMob.class, box, m -> !(m instanceof Enemy))) {
            if (mob instanceof Villager villager) {
                villager.getBrain().setMemory(MemoryModuleType.HEARD_BELL_TIME, level.getGameTime());
                continue;
            }
            if (mob.isLeashed() || mob.isPassenger() || mob.isInWater()) continue;
            Vec3 away = new Vec3(mob.getX() - pos.x, 0, mob.getZ() - pos.z);
            away = away.lengthSqr() < 1.0e-4 ? downwind : away.normalize();
            // Animals run for water if there is some on their side of the fire, otherwise away and out of the head.
            Vec3 target = null;
            double best = Double.MAX_VALUE;
            for (Vec3 w : refuges) {
                Vec3 to = w.subtract(mob.position());
                if (to.horizontalDistanceSqr() < best && to.normalize().dot(away) > -0.2) {
                    best = to.horizontalDistanceSqr();
                    target = w;
                }
            }
            if (target == null || best > 40 * 40) {
                Vec3 dir = away.add(downwind.scale(-0.35 * Math.max(0, away.dot(downwind)))).normalize();
                target = mob.position().add(dir.scale(16 + random.nextInt(10)));
            }
            mob.getNavigation().moveTo(target.x, target.y, target.z, phase() == Phase.WARNING ? 1.25 : 1.7);
        }
    }

    /** A few water surfaces around the fire (sampled on rings), as refuges for fleeing animals. */
    private List<Vec3> findWater(double radius) {
        List<Vec3> out = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            double a = i * (Math.PI * 2 / 24) + random.nextDouble() * 0.2;
            double d = radius * (0.5 + 0.5 * random.nextDouble());
            int x = Mth.floor(pos.x + Math.cos(a) * d), z = Mth.floor(pos.z + Math.sin(a) * d);
            if (!level.isLoaded(new BlockPos(x, 0, z))) continue;
            int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
            if (level.getFluidState(new BlockPos(x, y, z)).is(FluidTags.WATER)) out.add(new Vec3(x + 0.5, y, z + 0.5));
        }
        return out;
    }

    // ------------------------------------------------------------------ atmosphere

    private void crackSound() {
        long key = burning.getLong(random.nextInt(burning.size()));
        int x = BlockPos.getX(key), z = BlockPos.getZ(key);
        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        level.playSound(null, x + 0.5, y, z + 0.5, ModRegistries.WILDFIRE_POP.get(), SoundSource.BLOCKS,
                0.8f + random.nextFloat() * 0.8f, 0.8f + random.nextFloat() * 0.4f);
        if (random.nextInt(4) == 0) {
            level.playSound(null, x + 0.5, y, z + 0.5, SoundEvents.FIRE_AMBIENT, SoundSource.BLOCKS, 1.5f, 0.6f);
        }
    }

    private void broadcast(Component message, double range) {
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(pos.x, p.getY(), pos.z) < range * range) p.sendOverlayMessage(message);
        }
    }

    // ------------------------------------------------------------------ accessors (tests, commands)

    public int burningCount() {
        return burning.size();
    }

    public int burntCount() {
        return burntCount;
    }

    public int ignitedCount() {
        return ignitedCount;
    }

    public int spotFires() {
        return spotFires;
    }

    public int extinguishedCount() {
        return extinguished;
    }

    public int whirlCount() {
        return whirlCount;
    }

    public boolean isBurning(int x, int z) {
        return index.containsKey(BlockPos.asLong(x, 0, z));
    }

    /** Positions of currently burning columns (copy; tests). */
    public long[] burningColumns() {
        return burning.toLongArray();
    }

    public double windAngle() {
        return windAngle;
    }

    /** Fixes the wind direction (radians, 0 = +X / east). Used by tests and commands. */
    public void setWindAngle(double angle) {
        this.windAngle = angle;
        this.windDrift = 0;
    }

    /** Sets the mean wind speed (0..1.2; 0.2 = light breeze, 1 = strong wind). Used by tests and commands. */
    public void setWindSpeed(float speed) {
        this.baseWindSpeed = Mth.clamp(speed, 0.05f, 1.2f);
        this.windSpeed = baseWindSpeed;
    }

    /** Limits how far from the ignition point the fire may travel (tests keep it inside their arena). */
    public void setMaxRadius(int radius) {
        this.maxRadius = Math.max(2, radius);
    }

    /** Ember spotting on/off (tests that need a deterministic firebreak). */
    public void setSpotting(boolean spotting) {
        this.spotting = spotting;
        if (!spotting) embers.clear();
    }

    /** Fire whirls on/off. */
    public void setWhirls(boolean whirls) {
        this.whirls = whirls;
    }

    /** Starts a fire whirl at the given position (testing / showcase). */
    public void forceWhirl(double x, double z) {
        spawnWhirl(x, z);
    }
}
