package com.realisticdisasters.disaster.wildfire;

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
import net.minecraft.core.Holder;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A wind-driven wildfire. The fire is simulated on a grid of block columns: every burning column gets a burn timer,
 * and each tick a capped number of ignition attempts push the front outward, strongly biased downwind (with the odd
 * ember "spotting" a few blocks ahead). Columns are scored by their fuel (grass, brush, forest canopy) so the fire races
 * through forests, crawls over meadows and stops at water, sand and stone.
 * <p>
 * Every block change goes through the shared {@link BlockChangeQueue}: flames on ignition, then charring when a column
 * burns out (grass → ash-covered scorched earth, logs → smouldering charred logs, canopy consumed or scorched brown). The client renders smoke columns, ash
 * and flames from {@link #syncData()} only.
 */
public class WildfireDisaster extends Disaster {
    /** Indices into the sync data array (shared contract with the client visual). */
    public static final int D_WIND_X = 0, D_WIND_Z = 1, D_WIND_SPEED = 2, D_FRONT_RADIUS = 3, D_BURNING = 4,
            D_HEAT = 5, D_SMOKE_HEIGHT = 6, D_POINT_COUNT = 7, D_POINTS = 8;
    /** Max front sample points per packet (the payload carries at most 32 floats). */
    public static final int MAX_POINTS = 12;

    private static final int FUEL_NONE = 0, FUEL_GRASS = 1, FUEL_BRUSH = 2, FUEL_FOREST = 3;

    private final Vec3 origin;
    private final int maxArea;
    private int maxRadius;
    private final float baseWindSpeed;
    private double windAngle;
    private double windDrift;
    private float windSpeed;

    // Burning columns: parallel lists (swap-remove) + key -> index map for O(1) lookups.
    private final LongArrayList burning = new LongArrayList();
    private final IntArrayList timers = new IntArrayList();
    private final IntArrayList fuels = new IntArrayList();
    private final Long2IntOpenHashMap index = new Long2IntOpenHashMap();
    /** Columns that burned, are burning, or can never burn. */
    private final LongOpenHashSet visited = new LongOpenHashSet();
    private int burntCount;
    private int ignitedCount;
    private float frontRadius = 6f;
    private boolean cancelled;

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
        for (int k = 0; k < n; k++) {
            long key = burning.getLong(n == burning.size() ? k : random.nextInt(burning.size()));
            out[D_POINTS + k * 2] = (float) (BlockPos.getX(key) + 0.5 - pos.x);
            out[D_POINTS + k * 2 + 1] = (float) (BlockPos.getZ(key) + 0.5 - pos.z);
        }
        return out;
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
            for (int k = 0; k < burning.size(); k++) queueChar(burning.getLong(k), fuels.getInt(k));
        }
        burning.clear();
        timers.clear();
        fuels.clear();
        index.clear();
    }

    @Override
    protected void tickDisaster() {
        if (age() == 1) {
            broadcast(Component.translatable("disaster.realisticdisasters.wildfire.warning").withStyle(ChatFormatting.GOLD), 320);
            igniteOrigin();
        }
        updateWind();
        if (phase() != Phase.AFTERMATH) spread();
        burnDown();

        if (age() % 20 == 0) {
            updateCentre();
            if (burning.isEmpty() && phase() != Phase.AFTERMATH) {
                beginAftermath(); // ran out of fuel
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
                    int fuel = fuelAt(x, z);
                    if (fuel > FUEL_NONE) {
                        ignite(x, z, fuel);
                        lit++;
                    }
                }
            }
        }
        if (lit == 0 && level.isLoaded(BlockPos.containing(origin))) {
            ignite(ox, oz, FUEL_GRASS); // nothing to burn nearby: it smoulders briefly and dies
        }
    }

    private void spread() {
        if (burning.isEmpty() || ignitedCount >= maxArea) return;
        int cap = DisasterConfig.WILDFIRE_MAX_IGNITIONS_PER_TICK.getAsInt();
        // Every burning column tries to spread with this chance per tick, so the front advances at the same speed
        // however long it gets; the per-tick caps keep a huge front from costing more than a small one.
        float rate = (0.04f + 0.025f * intensity) * (float) (double) DisasterConfig.WILDFIRE_SPREAD_SCALE.get()
                * (0.12f + 0.88f * strength());
        float expected = rate * burning.size();
        int attempts = (int) expected;
        if (random.nextFloat() < expected - attempts) attempts++;
        if (burning.size() < 8) attempts = Math.max(attempts, 1); // a young fire doesn't fizzle out by bad luck
        attempts = Math.min(attempts, cap * 4);
        boolean raining = DisasterConfig.WILDFIRE_RAIN_SLOWS.getAsBoolean() && level.isRaining();
        double windBias = 0.35 + 0.55 * Math.min(1f, windSpeed);
        double spotChance = 0.012 * intensity * windSpeed;
        int ignitions = 0;

        for (int a = 0; a < attempts && ignitions < cap; a++) {
            int k = random.nextInt(burning.size());
            long from = burning.getLong(k);
            int fx = BlockPos.getX(from), fz = BlockPos.getZ(from);
            // Pick a target column; one retry if the first lands on ground that already burnt.
            int x = fx, z = fz;
            long key = 0;
            double dist = 1.0;
            boolean found = false;
            for (int tries = 0; tries < 2 && !found; tries++) {
                double angle;
                if (random.nextDouble() < windBias) {
                    angle = windAngle + random.nextGaussian() * (1.1 - 0.6 * Math.min(1f, windSpeed));
                } else {
                    angle = random.nextDouble() * Math.PI * 2; // backing / flanking spread
                }
                dist = random.nextDouble() < spotChance ? 3 + random.nextInt(3 + intensity * 2) : 1.0; // wind-blown embers
                x = fx + (int) Math.round(Math.cos(angle) * dist);
                z = fz + (int) Math.round(Math.sin(angle) * dist);
                if (x == fx && z == fz) continue;
                key = BlockPos.asLong(x, 0, z);
                found = !visited.contains(key);
            }
            if (!found) continue;
            double ox = x + 0.5 - origin.x, oz = z + 0.5 - origin.z;
            if (ox * ox + oz * oz > (double) maxRadius * maxRadius) continue;
            if (!level.isLoaded(new BlockPos(x, 0, z))) continue;

            int fuel = fuelAt(x, z);
            if (fuel == FUEL_NONE) {
                visited.add(key);
                continue;
            }
            float chance = switch (fuel) {
                case FUEL_GRASS -> 0.45f;
                case FUEL_BRUSH -> 0.7f;
                default -> 0.85f;
            };
            int ty = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            if (fuel != FUEL_FOREST && fuels.getInt(k) != FUEL_FOREST) {
                // Surface fire: cliffs and walls stop it, it climbs upslope faster and struggles going down.
                // (Tree columns are skipped: their trunks are not terrain, and crown fires jump between canopies.)
                int fy = level.getHeight(Heightmap.Types.WORLD_SURFACE, fx, fz);
                if (Math.abs(ty - fy) > 3 + dist) continue;
                chance *= Mth.clamp(1f + (ty - fy) * 0.15f, 0.5f, 1.5f);
            }
            if (raining && level.isRainingAt(new BlockPos(x, ty, z))) chance *= 0.35f;
            if (random.nextFloat() < chance) {
                ignite(x, z, fuel);
                ignitions++;
            }
        }
    }

    private void ignite(int x, int z, int fuel) {
        long key = BlockPos.asLong(x, 0, z);
        if (index.containsKey(key)) return;
        visited.add(key);
        int burn = switch (fuel) {
            case FUEL_GRASS -> 40 + random.nextInt(40);
            case FUEL_BRUSH -> 70 + random.nextInt(60);
            default -> 140 + random.nextInt(120);
        };
        index.put(key, burning.size());
        burning.add(key);
        timers.add(burn);
        fuels.add(fuel);
        ignitedCount++;
        queueFlames(x, z);
    }

    private void burnDown() {
        boolean raining = DisasterConfig.WILDFIRE_RAIN_SLOWS.getAsBoolean() && level.isRaining();
        int step = raining && age() % 2 == 0 ? 2 : 1; // rain shortens burning by a third
        for (int k = burning.size() - 1; k >= 0; k--) {
            int t = timers.getInt(k) - step;
            if (t <= 0) {
                long key = burning.getLong(k);
                int fuel = fuels.getInt(k);
                removeAt(k);
                burntCount++;
                queueChar(key, fuel);
            } else {
                timers.set(k, t);
                // Long-burning forest columns flare up again (flames burn out on their own).
                if (t == 90 && fuels.getInt(k) == FUEL_FOREST) {
                    long key = burning.getLong(k);
                    queueFlames(BlockPos.getX(key), BlockPos.getZ(key));
                }
            }
        }
    }

    private void removeAt(int k) {
        long key = burning.getLong(k);
        int last = burning.size() - 1;
        if (k != last) {
            long moved = burning.getLong(last);
            burning.set(k, moved);
            timers.set(k, timers.getInt(last));
            fuels.set(k, fuels.getInt(last));
            index.put(moved, k);
        }
        burning.removeLong(last);
        timers.removeInt(last);
        fuels.removeInt(last);
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

    // ------------------------------------------------------------------ fuel

    /** What a column offers the fire. Reads a handful of block states. */
    private int fuelAt(int x, int z) {
        int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, top, z);
        BlockState st = level.getBlockState(p);
        if (!st.getFluidState().isEmpty() || st.is(Blocks.SNOW) || st.is(BlockTags.ICE) || st.is(Blocks.POWDER_SNOW)) {
            return FUEL_NONE;
        }
        if (st.is(BlockTags.LEAVES) || st.is(BlockTags.LOGS)) return FUEL_FOREST;
        if (isPlant(st) || st.is(Blocks.HAY_BLOCK)) {
            // brush on top of something: look at the ground under it
            BlockState ground = level.getBlockState(p.move(Direction.DOWN));
            if (!ground.getFluidState().isEmpty()) return FUEL_NONE;
            return FUEL_BRUSH;
        }
        if (st.is(Blocks.GRASS_BLOCK) || st.is(Blocks.PODZOL) || st.is(Blocks.MYCELIUM) || st.is(Blocks.MOSS_BLOCK)
                || st.is(BlockTags.WOOL) || st.is(BlockTags.PLANKS)) {
            return FUEL_GRASS;
        }
        if (st.isFlammable(level, p, Direction.UP)) return FUEL_BRUSH;
        return FUEL_NONE;
    }

    private static boolean isPlant(BlockState st) {
        return st.is(BlockTags.REPLACEABLE_BY_TREES) || st.is(BlockTags.FLOWERS) || st.is(BlockTags.SAPLINGS)
                || st.is(BlockTags.CROPS) || st.is(Blocks.SWEET_BERRY_BUSH) || st.is(Blocks.DEAD_BUSH)
                || st.is(Blocks.FERN) || st.is(Blocks.LARGE_FERN) || st.is(Blocks.SHORT_GRASS) || st.is(Blocks.TALL_GRASS);
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
        placeFlameAt(new BlockPos(x, top, z));
        if (ground < top - 1) placeFlameAt(new BlockPos(x, ground, z)); // surface fire under the burning canopy
    }

    private void placeFlameAt(BlockPos p) {
        BlockState here = level.getBlockState(p);
        BlockPos at = p;
        // A plant on top is consumed by the flame itself.
        BlockState below = level.getBlockState(p.below());
        if (isPlant(below) && below.canBeReplaced()) {
            at = p.below();
            here = below;
        }
        if (!here.isAir() && !(here.canBeReplaced() && here.getFluidState().isEmpty())) return;
        BlockState flame = ModRegistries.WILDFIRE_FLAME.get().defaultBlockState();
        if (!flame.canSurvive(level, at)) return;
        if (!DisasterProtection.canModify(level, at, here)) return;
        level.setBlock(at, flame, Block.UPDATE_ALL);
    }

    private void queueChar(long key, int fuel) {
        if (!destructionOn()) return;
        int x = BlockPos.getX(key), z = BlockPos.getZ(key);
        DisasterManager.blockQueue(level).add(l -> charColumn(x, z, fuel));
    }

    /** Leaves the burn scar: canopy and plants gone, trunks charred, turf scorched. */
    private void charColumn(int x, int z, int fuel) {
        if (cancelled || !level.isLoaded(new BlockPos(x, 0, z))) return;
        int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        int bottom = top - 40;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, top, z);
        boolean protectedChecked = false;
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
            BlockState result = charred(st);
            if (result == null) {
                if (st.is(BlockTags.LEAVES) || st.is(BlockTags.LOGS) || isPlant(st) || st.canBeReplaced()
                        || st.is(ModRegistries.SCORCHED_LEAVES.get()) || st.is(ModRegistries.CHARRED_LOG.get())) continue;
                break; // non-flammable ground or structure: the scar ends here
            }
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
    }

    private static boolean isGround(BlockState st) {
        return st.is(Blocks.GRASS_BLOCK) || st.is(Blocks.PODZOL) || st.is(Blocks.MYCELIUM) || st.is(Blocks.MOSS_BLOCK)
                || st.is(BlockTags.DIRT) || st.is(ModRegistries.SCORCHED_EARTH.get());
    }

    /** @return the burnt replacement, {@code st} itself to keep it, or null if fire doesn't affect it */
    private BlockState charred(BlockState st) {
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
            // Crown fires consume the foliage; where the fire was weaker the crown is only scorched brown.
            return random.nextFloat() < 0.5f + intensity * 0.1f
                    ? Blocks.AIR.defaultBlockState() : ModRegistries.SCORCHED_LEAVES.get().defaultBlockState();
        }
        if (isPlant(st) || st.is(Blocks.HAY_BLOCK) || st.is(Blocks.VINE)
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
        double r = frontRadius + 2;
        AABB box = new AABB(pos.x - r, pos.y - 24, pos.z - r, pos.x + r, pos.y + 40, pos.z + r);
        DamageSource source = level.damageSources().source(ModRegistries.WILDFIRE_DAMAGE);
        float s = Math.max(0.3f, strength());
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box, e -> !e.isSpectator() && !e.fireImmune())) {
            if (e instanceof Player p && p.isCreative()) continue;
            int x = Mth.floor(e.getX()), z = Mth.floor(e.getZ());
            boolean inFire = index.containsKey(BlockPos.asLong(x, 0, z));
            if (!inFire) continue;
            int ground = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            if (e.getY() > ground + 3 || e.isInWater()) continue; // above the flames or in water
            e.igniteForSeconds(3f + intensity);
            e.hurtServer(level, source, (0.5f + intensity * 0.3f) * s);
        }
    }

    private void alertMobs() {
        double r = frontRadius + 24 + (phase() == Phase.WARNING ? 16 : 0);
        AABB box = new AABB(pos.x - r, pos.y - 16, pos.z - r, pos.x + r, pos.y + 24, pos.z + r);
        Vec3 downwind = new Vec3(Math.cos(windAngle), 0, Math.sin(windAngle));
        for (PathfinderMob mob : level.getEntitiesOfClass(PathfinderMob.class, box, m -> !(m instanceof Enemy))) {
            if (mob instanceof Villager villager) {
                villager.getBrain().setMemory(MemoryModuleType.HEARD_BELL_TIME, level.getGameTime());
                continue;
            }
            if (mob.isLeashed() || mob.isPassenger()) continue;
            Vec3 away = new Vec3(mob.getX() - pos.x, 0, mob.getZ() - pos.z);
            away = away.lengthSqr() < 1.0e-4 ? downwind : away.normalize();
            // Run away from the fire, sideways out of the wind-driven head where possible.
            Vec3 dir = away.add(downwind.scale(-0.35 * Math.max(0, away.dot(downwind)))).normalize();
            Vec3 target = mob.position().add(dir.scale(16 + random.nextInt(10)));
            mob.getNavigation().moveTo(target.x, target.y, target.z, phase() == Phase.WARNING ? 1.25 : 1.7);
        }
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

    public double windAngle() {
        return windAngle;
    }

    /** Fixes the wind direction (radians, 0 = +X / east). Used by tests and commands. */
    public void setWindAngle(double angle) {
        this.windAngle = angle;
        this.windDrift = 0;
    }

    /** Limits how far from the ignition point the fire may travel (tests keep it inside their arena). */
    public void setMaxRadius(int radius) {
        this.maxRadius = Math.max(2, radius);
    }
}
