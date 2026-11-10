package com.realisticdisasters.disaster.meteor;

import java.util.ArrayList;
import java.util.List;

import com.realisticdisasters.RealisticDisasters;
import com.realisticdisasters.config.DisasterConfig;
import com.realisticdisasters.disaster.BlockChangeQueue;
import com.realisticdisasters.disaster.DisasterManager;
import com.realisticdisasters.disaster.DisasterProtection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Carves an impact crater through the batched {@link BlockChangeQueue}: a noisy bowl, a scorched lining, a meteorite
 * core with rare ores at the bottom, ejecta thrown onto the surrounding ground and fires on the rim. Every change
 * re-checks {@link DisasterProtection} when it is applied, so claims and the destruction toggle are always respected.
 */
final class Crater {
    /** Impact melt and charred rock: only on the floor near the centre. */
    private static final Block[] MELT = {Blocks.BLACKSTONE, Blocks.BLACKSTONE, Blocks.MAGMA_BLOCK, Blocks.OBSIDIAN,
            Blocks.SMOOTH_BASALT};
    /** The meteorite itself: dark fusion crust and iron-rich rock. */
    private static final Block[] METEORITE = {Blocks.BLACKSTONE, Blocks.BLACKSTONE, Blocks.BASALT, Blocks.SMOOTH_BASALT,
            Blocks.MAGMA_BLOCK};

    /** Parsed ore table, rebuilt when the config list changes. */
    private static List<? extends String> cachedOreConfig;
    private static List<Block> oreTable = List.of();

    /**
     * Simple-crater morphology (Melosh): parabolic bowl with depth about a fifth of the diameter, a sharp rim raised
     * by uplift plus proximal ejecta, a breccia lining of crushed native rock, an ejecta blanket thinning with the
     * inverse cube of distance out to about one diameter, and scorched vegetation around it.
     */
    static void carve(MeteorShowerDisaster d, BlockPos center, float radius, float size) {
        if (!DisasterConfig.BLOCK_DESTRUCTION.getAsBoolean()) return;
        ServerLevel level = d.serverLevel();
        RandomSource random = d.rng();
        BlockChangeQueue queue = DisasterManager.blockQueue(level);
        float r = Math.max(1.5f, radius);
        float depth = Math.max(2.2f, r * 0.42f);   // D/5 = 0.4R; a touch deeper reads better in blocks
        float above = r * 0.7f;
        int ir = Mth.ceil(r * 1.2f) + 1;
        int groundY = center.getY() - 1;           // top block of the ground that was hit
        long seed = random.nextLong();

        // One queued change per column, centre outward: the bowl opens from the middle in a fraction of a second.
        List<int[]> columns = new ArrayList<>();
        for (int dx = -ir; dx <= ir; dx++) {
            for (int dz = -ir; dz <= ir; dz++) {
                if (dx * dx + dz * dz <= (r * 1.25) * (r * 1.25)) columns.add(new int[]{dx, dz});
            }
        }
        columns.sort((p1, p2) -> Integer.compare(p1[0] * p1[0] + p1[1] * p1[1], p2[0] * p2[0] + p2[1] * p2[1]));
        for (int[] c : columns) {
            int x = center.getX() + c[0], z = center.getZ() + c[1];
            double h2 = (c[0] * c[0] + c[1] * c[1]) / (double) (r * r);
            queue.add(l -> carveColumn(l, x, z, groundY, h2, depth, above, seed));
        }

        // The meteorite: most of the impactor vaporises, a small mass survives half-buried in the floor.
        float coreR = 0.7f + r * 0.13f * Math.min(1.5f, size + 0.3f);
        double coreY = groundY - depth + 0.6;
        int ic = Mth.ceil(coreR);
        float oreChance = DisasterConfig.METEOR_ORE_CHANCE.get().floatValue();
        List<Block> ores = ores();
        for (int dy = -ic; dy <= ic; dy++) {
            for (int dx = -ic; dx <= ic; dx++) {
                for (int dz = -ic; dz <= ic; dz++) {
                    double dist = Math.sqrt(dx * dx + dy * dy * 1.3 + dz * dz);
                    if (dist > coreR + (random.nextDouble() - 0.5) * 0.4) continue;
                    BlockPos p = BlockPos.containing(center.getX() + dx, coreY + dy, center.getZ() + dz);
                    Block b = !ores.isEmpty() && random.nextFloat() < oreChance
                            ? ores.get(random.nextInt(ores.size()))
                            : METEORITE[random.nextInt(METEORITE.length)];
                    BlockState to = b.defaultBlockState();
                    queue.add(l -> apply(l, p, to, Mode.ANY));
                }
            }
        }

        // Raised rim + ejecta blanket (thickness ~ (R/r)^3) + burnt turf, one change per affected column.
        // (Flying ejecta from MeteorShowerDisaster adds to this where it lands.)
        int rimHeight = r >= 9f ? 2 : 1;
        int reach = Mth.ceil(r * 2.6f);
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                double dist = Math.sqrt(dx * dx + dz * dz) + (random.nextDouble() - 0.5) * 0.6;
                if (dist < r * 0.98 || dist > r * 2.6) continue;
                double thickness = rimHeight * Math.pow(r / Math.max(dist, r), 3) * (dist < r * 1.25 ? 1.2 : 1.0);
                int layers = (int) thickness + (random.nextDouble() < thickness - (int) thickness ? 1 : 0);
                boolean scorch = dist < r * 1.7;
                if (layers == 0 && !scorch) continue;
                int x = center.getX() + dx, z = center.getZ() + dz;
                float roll = random.nextFloat();
                queue.add(l -> {
                    if (scorch) scorchSurface(l, x, z);
                    for (int k = 0; k < layers; k++) placeEjecta(l, x, z, (roll + k * 0.37f) % 1f);
                });
            }
        }
        if (DisasterConfig.METEOR_STARTS_FIRES.getAsBoolean()) {
            // Thermal pulse: the fireball ignites the ground around the rim.
            int count = Math.round(r * (1.5f + d.intensity() * 0.3f));
            for (int i = 0; i < count; i++) {
                double a = random.nextDouble() * Math.PI * 2;
                double dist = r * (0.9 + random.nextDouble() * 1.1);
                int x = Mth.floor(center.getX() + 0.5 + Math.cos(a) * dist), z = Mth.floor(center.getZ() + 0.5 + Math.sin(a) * dist);
                queue.add(Crater.fireAt(x, z));
            }
        }
    }

    /**
     * Excavates one column of the bowl top-down and lines what is left: crushed native rock (breccia) on the walls,
     * melt and charred rock on the floor near the centre. Protection is checked once per column (claims and
     * protected areas are column-shaped); block entities, unbreakable blocks and liquids are never touched.
     */
    private static void carveColumn(ServerLevel level, int x, int z, int groundY, double h2, float depth, float above, long seed) {
        BlockPos top = new BlockPos(x, groundY, z);
        if (!level.isLoaded(top)) return;
        if (!DisasterProtection.canModify(level, top, level.getBlockState(top))) return;
        RandomSource rnd = RandomSource.create(seed ^ (x * 341873128712L + z * 132897987541L));
        double edge = 1 + (rnd.nextDouble() - 0.5) * 0.16;
        // Edge columns notify neighbours (sand slumps in, water flows in); interior ones only update clients.
        int flags = h2 > 0.55 ? Block.UPDATE_ALL : Block.UPDATE_CLIENTS;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int lined = 0;
        for (int y = groundY + Mth.ceil(above); y >= groundY - Mth.ceil(depth) - 3; y--) {
            int dy = y - groundY;
            double s = dy <= 0 ? h2 + (-dy) / depth : h2 + (dy / (double) above) * (dy / (double) above);
            p.set(x, y, z);
            BlockState st = level.getBlockState(p);
            if (st.isAir() || st.hasBlockEntity() || !st.getFluidState().isEmpty() || st.getDestroySpeed(level, p) < 0) {
                if (s >= edge && !st.isAir()) break;
                continue;
            }
            if (s < edge + (rnd.nextDouble() - 0.5) * 0.06) {
                level.setBlock(p, Blocks.AIR.defaultBlockState(), flags);
            } else if (dy <= 0) {
                // first solid block(s) under the bowl: lining
                if (!st.isCollisionShapeFullBlock(level, p) || st.is(BlockTags.LEAVES)) break;
                BlockState to = h2 < 0.2 ? MELT[rnd.nextInt(MELT.length)].defaultBlockState() : crushed(st, rnd.nextFloat());
                if (to != st) level.setBlock(p, to, flags);
                if (++lined >= 2) break;
            }
        }
    }

    /** What a block thrown out of the crater lands as. */
    static BlockState crushedForEjecta(BlockState st, float roll) {
        BlockState c = crushed(st.is(Blocks.GRASS_BLOCK) ? Blocks.DIRT.defaultBlockState() : st, roll);
        return c.isAir() || c == st && !st.is(BlockTags.SAND) ? Blocks.COARSE_DIRT.defaultBlockState() : c;
    }

    /** Breccia: the native material, shattered. Rock becomes cobble/gravel/tuff, soil becomes coarse dirt/gravel. */
    private static BlockState crushed(BlockState st, float roll) {
        if (st.is(Blocks.DEEPSLATE) || st.is(BlockTags.DEEPSLATE_ORE_REPLACEABLES) && !st.is(Blocks.TUFF)) {
            return (roll < 0.6f ? Blocks.COBBLED_DEEPSLATE : roll < 0.85f ? Blocks.GRAVEL : Blocks.TUFF).defaultBlockState();
        }
        if (st.is(BlockTags.BASE_STONE_OVERWORLD)) {
            return (roll < 0.5f ? Blocks.COBBLESTONE : roll < 0.8f ? Blocks.GRAVEL : Blocks.TUFF).defaultBlockState();
        }
        if (st.is(BlockTags.SAND)) {
            return roll < 0.25f ? Blocks.GRAVEL.defaultBlockState() : roll < 0.4f ? Blocks.SANDSTONE.defaultBlockState() : st;
        }
        if (st.is(BlockTags.DIRT) || st.is(Blocks.CLAY) || st.is(Blocks.MUD)) {
            return (roll < 0.55f ? Blocks.COARSE_DIRT : roll < 0.8f ? Blocks.GRAVEL : Blocks.DIRT).defaultBlockState();
        }
        if (st.is(BlockTags.ICE) || st.is(Blocks.SNOW_BLOCK) || st.is(Blocks.POWDER_SNOW)) {
            return Blocks.AIR.defaultBlockState(); // flash-melted
        }
        if (st.is(BlockTags.TERRACOTTA)) return roll < 0.5f ? st : Blocks.GRAVEL.defaultBlockState();
        return st;
    }

    private static void crush(ServerLevel level, BlockPos p, float roll) {
        BlockState st = level.getBlockState(p);
        BlockState to = crushed(st, roll);
        if (to != st) apply(level, p, to, Mode.SOLID_ONLY);
    }

    /** Ejecta: overturned soil and shattered rock dumped on the surface. */
    private static void placeEjecta(ServerLevel level, int x, int z, float roll) {
        BlockPos column = new BlockPos(x, 0, z);
        if (!level.isLoaded(column)) return;
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        // Ejecta settles as a blanket, not in pillars: only pile up where at least two neighbours are as high.
        int level4 = 0;
        if (level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x + 1, z) >= top) level4++;
        if (level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x - 1, z) >= top) level4++;
        if (level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z + 1) >= top) level4++;
        if (level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z - 1) >= top) level4++;
        if (level4 < 2) return;
        BlockState below = level.getBlockState(new BlockPos(x, top - 1, z));
        BlockState to = crushed(below.is(Blocks.GRASS_BLOCK) ? Blocks.DIRT.defaultBlockState() : below, roll);
        if (to.isAir() || to == below && !below.is(BlockTags.SAND)) {
            to = (roll < 0.6f ? Blocks.COARSE_DIRT : roll < 0.85f ? Blocks.GRAVEL : Blocks.COBBLESTONE).defaultBlockState();
        }
        placeOnSurface(level, x, z, to);
    }

    /** Burns the turf: grass to dirt / coarse dirt, plants on top destroyed. */
    private static void scorchSurface(ServerLevel level, int x, int z) {
        BlockPos column = new BlockPos(x, 0, z);
        if (!level.isLoaded(column)) return;
        BlockPos top = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1, z);
        BlockState st = level.getBlockState(top);
        BlockState plant = level.getBlockState(top.above());
        if (!plant.isAir() && plant.canBeReplaced() && plant.getFluidState().isEmpty()
                && DisasterProtection.canModify(level, top.above(), plant)) {
            level.setBlock(top.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        if (st.is(Blocks.GRASS_BLOCK) || st.is(Blocks.PODZOL) || st.is(Blocks.MYCELIUM) || st.is(Blocks.MOSS_BLOCK)) {
            BlockState to = ((x * 31 + z * 17) & 3) == 0 ? Blocks.DIRT.defaultBlockState() : Blocks.COARSE_DIRT.defaultBlockState();
            apply(level, top, to, Mode.SOLID_ONLY);
        }
    }

    private enum Mode { ANY, SOLID_ONLY }

    private static void apply(ServerLevel level, BlockPos p, BlockState to, Mode mode) {
        if (!level.isLoaded(p)) return;
        BlockState st = level.getBlockState(p);
        if (st == to || st.hasBlockEntity() || st.getBlock() instanceof LiquidBlock) return;
        if (mode == Mode.ANY && to.isAir() && st.isAir()) return;
        if (mode == Mode.SOLID_ONLY && (st.isAir() || st.is(BlockTags.LEAVES) || !st.isCollisionShapeFullBlock(level, p))) return;
        if (st.getDestroySpeed(level, p) < 0) return;
        if (!DisasterProtection.canModify(level, p, st)) return;
        level.setBlock(p, to, Block.UPDATE_ALL);
    }

    static void placeOnSurface(ServerLevel level, int x, int z, BlockState to) {
        BlockPos column = new BlockPos(x, 0, z);
        if (!level.isLoaded(column)) return;
        BlockPos p = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
        BlockState st = level.getBlockState(p);
        if (!st.canBeReplaced() || st.getBlock() instanceof LiquidBlock || !st.getFluidState().isEmpty()) return;
        if (!level.getBlockState(p.below()).isFaceSturdy(level, p.below(), net.minecraft.core.Direction.UP)) return;
        if (!DisasterProtection.canModify(level, p, st)) return;
        level.setBlock(p, to, Block.UPDATE_ALL);
    }

    private static BlockChangeQueue.Change fireAt(int x, int z) {
        return level -> {
            BlockPos column = new BlockPos(x, 0, z);
            if (!level.isLoaded(column)) return;
            BlockPos p = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), z);
            BlockState st = level.getBlockState(p);
            if (!st.isAir()) return;
            BlockState fire = BaseFireBlock.getState(level, p);
            if (!fire.canSurvive(level, p) || !DisasterProtection.canModify(level, p, st)) return;
            level.setBlock(p, fire, Block.UPDATE_ALL);
        };
    }

    /** The configured ore table, expanded by weight ("minecraft:diamond_ore*2" = two entries). */
    static List<Block> ores() {
        List<? extends String> config = DisasterConfig.METEOR_ORES.get();
        if (config != cachedOreConfig) {
            List<Block> table = new ArrayList<>();
            for (String entry : config) {
                String[] parts = entry.trim().split("\\*");
                int weight = 1;
                try {
                    if (parts.length > 1) weight = Mth.clamp(Integer.parseInt(parts[1].trim()), 0, 100);
                    Identifier id = Identifier.parse(parts[0].trim());
                    Block b = BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
                    if (b == null || b == Blocks.AIR) {
                        RealisticDisasters.LOGGER.warn("Meteor ore list: unknown block '{}'", parts[0]);
                        continue;
                    }
                    for (int i = 0; i < weight; i++) table.add(b);
                } catch (RuntimeException e) {
                    RealisticDisasters.LOGGER.warn("Meteor ore list: bad entry '{}'", entry);
                }
            }
            oreTable = List.copyOf(table);
            cachedOreConfig = config;
        }
        return oreTable;
    }

    private Crater() {}
}
