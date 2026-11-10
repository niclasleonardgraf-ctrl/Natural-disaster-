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
        float depth = Math.max(2.2f, r * 0.45f);   // D/5 = 0.4R; a touch deeper reads better in blocks
        float above = r * 0.7f;
        int ir = Mth.ceil(r * 1.2f) + 1;
        int groundY = center.getY() - 1;           // top block of the ground that was hit
        boolean fires = DisasterConfig.METEOR_STARTS_FIRES.getAsBoolean();

        List<BlockPos> carve = new ArrayList<>();
        List<BlockPos> lining = new ArrayList<>();
        List<BlockPos> melt = new ArrayList<>();
        // Top-down so loose blocks above the bowl are removed before what they rest on.
        for (int y = groundY + Mth.ceil(above); y >= groundY - Mth.ceil(depth) - 2; y--) {
            int dy = y - groundY;
            for (int dx = -ir; dx <= ir; dx++) {
                for (int dz = -ir; dz <= ir; dz++) {
                    double h2 = (dx * dx + dz * dz) / (double) (r * r);
                    double edge = 1 + (random.nextDouble() - 0.5) * 0.18;
                    // Parabolic floor below ground, a flattened dome of cleared material above it.
                    double s = dy <= 0 ? h2 + (-dy) / depth : h2 + (dy / (double) above) * (dy / (double) above);
                    BlockPos p = new BlockPos(center.getX() + dx, y, center.getZ() + dz);
                    if (s < edge) {
                        if (!level.getBlockState(p).isAir()) carve.add(p);
                    } else if (dy <= 0 && s < edge + 1.3 / depth) {
                        if (h2 < 0.22) melt.add(p);
                        else lining.add(p);
                    }
                }
            }
        }
        for (BlockPos p : carve) queue.add(l -> apply(l, p, Blocks.AIR.defaultBlockState(), Mode.ANY));
        for (BlockPos p : lining) {
            float roll = random.nextFloat();
            queue.add(l -> crush(l, p, roll));
        }
        for (BlockPos p : melt) {
            BlockState to = MELT[random.nextInt(MELT.length)].defaultBlockState();
            queue.add(l -> apply(l, p, to, Mode.SOLID_ONLY));
        }

        // The meteorite: most of the impactor vaporises, a small mass survives half-buried in the floor.
        float coreR = 0.7f + r * 0.16f * Math.min(1.5f, size + 0.3f);
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

        // Raised rim + ejecta blanket: thickness ~ (R/r)^3, continuous at the rim, patchy further out.
        int rimHeight = r >= 6.5f ? 2 : 1;
        int reach = Mth.ceil(r * 2.6f);
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                double dist = Math.sqrt(dx * dx + dz * dz) + (random.nextDouble() - 0.5) * 0.6;
                if (dist < r * 0.98 || dist > r * 2.6) continue;
                double thickness = rimHeight * Math.pow(r / Math.max(dist, r), 3) * (dist < r * 1.25 ? 1.6 : 1.0);
                int layers = (int) thickness + (random.nextDouble() < thickness - (int) thickness ? 1 : 0);
                int x = center.getX() + dx, z = center.getZ() + dz;
                if (dist < r * 1.6) {
                    // Scorched ground under and around the rim (grass burnt off).
                    queue.add(l -> scorchSurface(l, x, z));
                }
                for (int k = 0; k < layers; k++) {
                    float roll = random.nextFloat();
                    queue.add(l -> placeEjecta(l, x, z, roll));
                }
            }
        }
        if (fires) {
            int count = Math.round(r * (1.0f + d.intensity() * 0.25f));
            for (int i = 0; i < count; i++) {
                double a = random.nextDouble() * Math.PI * 2;
                double dist = r * (0.9 + random.nextDouble() * 0.9);
                int x = Mth.floor(center.getX() + 0.5 + Math.cos(a) * dist), z = Mth.floor(center.getZ() + 0.5 + Math.sin(a) * dist);
                queue.add(Crater.fireAt(x, z));
            }
        }
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

    private static void placeOnSurface(ServerLevel level, int x, int z, BlockState to) {
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
