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
    private static final Block[] SCORCHED = {Blocks.BLACKSTONE, Blocks.BLACKSTONE, Blocks.BASALT, Blocks.COARSE_DIRT,
            Blocks.SMOOTH_BASALT, Blocks.MAGMA_BLOCK};
    private static final Block[] METEORITE = {Blocks.BLACKSTONE, Blocks.BLACKSTONE, Blocks.BASALT, Blocks.MAGMA_BLOCK,
            Blocks.OBSIDIAN};
    private static final Block[] EJECTA = {Blocks.COARSE_DIRT, Blocks.COARSE_DIRT, Blocks.GRAVEL, Blocks.COBBLESTONE,
            Blocks.BLACKSTONE};

    /** Parsed ore table, rebuilt when the config list changes. */
    private static List<? extends String> cachedOreConfig;
    private static List<Block> oreTable = List.of();

    static void carve(MeteorShowerDisaster d, BlockPos center, float radius, float size) {
        if (!DisasterConfig.BLOCK_DESTRUCTION.getAsBoolean()) return;
        ServerLevel level = d.serverLevel();
        RandomSource random = d.rng();
        BlockChangeQueue queue = DisasterManager.blockQueue(level);
        float r = Math.max(1.2f, radius);
        float depth = r * 0.62f;
        float above = r * 0.7f;
        int ir = Mth.ceil(r * 1.25f) + 1;
        float shell = 1.4f / r;               // ~1.4 blocks of scorched lining
        boolean fires = DisasterConfig.METEOR_STARTS_FIRES.getAsBoolean();

        List<BlockPos> carve = new ArrayList<>();
        List<BlockPos> scorch = new ArrayList<>();
        // Top-down so loose blocks above the bowl are removed before what they rest on.
        for (int dy = Mth.ceil(above); dy >= -Mth.ceil(depth) - 2; dy--) {
            for (int dx = -ir; dx <= ir; dx++) {
                for (int dz = -ir; dz <= ir; dz++) {
                    double h2 = (dx * dx + dz * dz) / (double) (r * r);
                    double v = dy <= 0 ? dy / (double) depth : dy / (double) above;
                    double s = Math.sqrt(h2 + v * v);
                    double edge = 1 + (random.nextDouble() - 0.5) * 0.28;
                    BlockPos p = center.offset(dx, dy, dz);
                    if (s < edge) {
                        if (!level.getBlockState(p).isAir()) carve.add(p);
                    } else if (s < edge + shell && dy <= 1) {
                        scorch.add(p);
                    }
                }
            }
        }
        for (BlockPos p : carve) queue.add(l -> apply(l, p, Blocks.AIR.defaultBlockState(), Mode.ANY));
        for (BlockPos p : scorch) {
            BlockState to = SCORCHED[random.nextInt(SCORCHED.length)].defaultBlockState();
            queue.add(l -> apply(l, p, to, Mode.SOLID_ONLY));
        }

        // Meteorite core, half buried in the crater floor.
        float coreR = 0.9f + r * 0.22f * Math.min(1.5f, size + 0.3f);
        double coreY = center.getY() - depth * 0.85;
        int ic = Mth.ceil(coreR);
        float oreChance = DisasterConfig.METEOR_ORE_CHANCE.get().floatValue();
        List<Block> ores = ores();
        for (int dy = -ic; dy <= ic; dy++) {
            for (int dx = -ic; dx <= ic; dx++) {
                for (int dz = -ic; dz <= ic; dz++) {
                    double dist = Math.sqrt(dx * dx + dy * dy * 1.3 + dz * dz);
                    if (dist > coreR + (random.nextDouble() - 0.5) * 0.5) continue;
                    BlockPos p = BlockPos.containing(center.getX() + dx, coreY + dy, center.getZ() + dz);
                    Block b = !ores.isEmpty() && random.nextFloat() < oreChance
                            ? ores.get(random.nextInt(ores.size()))
                            : METEORITE[random.nextInt(METEORITE.length)];
                    BlockState to = b.defaultBlockState();
                    queue.add(l -> apply(l, p, to, Mode.ANY));
                }
            }
        }

        // Ejecta blanket around the rim and scattered fires.
        int ejecta = Math.round(r * r * 0.9f);
        for (int i = 0; i < ejecta; i++) {
            double a = random.nextDouble() * Math.PI * 2;
            double dist = r * (1.05 + random.nextDouble() * 0.7);
            int x = Mth.floor(center.getX() + 0.5 + Math.cos(a) * dist), z = Mth.floor(center.getZ() + 0.5 + Math.sin(a) * dist);
            BlockState to = EJECTA[random.nextInt(EJECTA.length)].defaultBlockState();
            queue.add(l -> placeOnSurface(l, x, z, to));
        }
        if (fires) {
            int count = Math.round(r * (1.2f + d.intensity() * 0.3f));
            for (int i = 0; i < count; i++) {
                double a = random.nextDouble() * Math.PI * 2;
                double dist = r * (0.85 + random.nextDouble() * 0.9);
                int x = Mth.floor(center.getX() + 0.5 + Math.cos(a) * dist), z = Mth.floor(center.getZ() + 0.5 + Math.sin(a) * dist);
                queue.add(Crater.fireAt(x, z));
            }
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
