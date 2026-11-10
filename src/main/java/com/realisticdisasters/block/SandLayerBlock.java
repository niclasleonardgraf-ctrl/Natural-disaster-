package com.realisticdisasters.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Thin layer of sand (1..8 layers) left by sandstorms. Shapes, stacking and placement behave like snow layers,
 * but sand never melts.
 */
public class SandLayerBlock extends SnowLayerBlock {
    public static final MapCodec<SandLayerBlock> CODEC = simpleCodec(SandLayerBlock::new);

    public SandLayerBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    @SuppressWarnings("unchecked")
    public MapCodec<SnowLayerBlock> codec() {
        return (MapCodec<SnowLayerBlock>) (MapCodec<? extends Block>) CODEC;
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        // Unlike snow, sand doesn't melt near light.
    }
}
