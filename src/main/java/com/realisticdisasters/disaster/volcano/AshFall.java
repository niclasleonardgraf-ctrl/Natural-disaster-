package com.realisticdisasters.disaster.volcano;

import com.realisticdisasters.config.DisasterConfig;
import com.realisticdisasters.disaster.DisasterProtection;
import com.realisticdisasters.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Tephra fall-out: ash settles out of the drifting cloud and blankets the land downwind like a grey snowfall. Like a
 * real isopach map, the deposit is thickest near the vent along the wind axis and thins out with distance.
 */
final class AshFall {
    private AshFall() {}

    /**
     * Drops ash at a few random spots of the fall-out area this tick.
     *
     * @param thickness 0..1 multiplier for how much ash falls (eruption strength)
     */
    static void deposit(ServerLevel level, RandomSource random, double cx, double cz, double windAngle, double radius,
                        int intensity, float thickness, int samples) {
        if (!DisasterConfig.BLOCK_DESTRUCTION.getAsBoolean() || thickness <= 0.02f) return;
        double wx = Math.cos(windAngle), wz = Math.sin(windAngle);
        for (int k = 0; k < samples; k++) {
            // Elongated downwind lobe: along-wind distance exponential, cross-wind gaussian.
            double along = -Math.log(1 - random.nextDouble() * 0.98) * radius * 0.35 - radius * 0.08;
            double across = random.nextGaussian() * radius * 0.18 * (0.4 + along / radius);
            double x = cx + wx * along - wz * across, z = cz + wz * along + wx * across;
            double rel = Math.max(0, along) / radius + Math.abs(across) / radius;
            if (rel > 1.2) continue;
            int maxLayers = Mth.clamp((int) Math.round((1 + intensity) * thickness * Math.exp(-rel * 2.5)), 1, 8);
            int bx = Mth.floor(x), bz = Mth.floor(z);
            if (!level.isLoaded(new BlockPos(bx, 0, bz))) continue;
            // a small patch, so the blanket closes up instead of speckling
            int patch = rel < 0.4 ? 2 : 1;
            for (int dx = -patch; dx <= patch; dx++) {
                for (int dz = -patch; dz <= patch; dz++) {
                    if (dx * dx + dz * dz > patch * patch || random.nextFloat() < 0.25f) continue;
                    int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx + dx, bz + dz);
                    BlockPos p = new BlockPos(bx + dx, y, bz + dz);
                    BlockState below = level.getBlockState(p.below());
                    if (below.is(ModRegistries.VOLCANIC_ASH.get())) p = p.below();
                    if (current(level, p) < maxLayers) addLayers(level, p, 1);
                }
            }
        }
    }

    private static int current(ServerLevel level, BlockPos p) {
        BlockState st = level.getBlockState(p);
        return st.is(ModRegistries.VOLCANIC_ASH.get()) ? st.getValue(SnowLayerBlock.LAYERS) : 0;
    }

    /** Adds ash layers at {@code p} (an air block, a plant, snow, or an existing ash layer). */
    static void addLayers(ServerLevel level, BlockPos p, int add) {
        BlockState st = level.getBlockState(p);
        var ash = ModRegistries.VOLCANIC_ASH.get();
        int have;
        if (st.is(ash)) {
            have = st.getValue(SnowLayerBlock.LAYERS);
        } else if (st.isAir() || st.canBeReplaced() && !(st.getBlock() instanceof LiquidBlock) && !st.hasBlockEntity()
                || st.is(BlockTags.SNOW)) {
            have = 0; // plants and snow get buried
        } else {
            return;
        }
        BlockState layer = ash.defaultBlockState().setValue(SnowLayerBlock.LAYERS, Mth.clamp(have + add, 1, 8));
        if (!layer.canSurvive(level, p) || !DisasterProtection.canModify(level, p, st)) return;
        level.setBlock(p, layer, Block.UPDATE_CLIENTS);
    }
}
