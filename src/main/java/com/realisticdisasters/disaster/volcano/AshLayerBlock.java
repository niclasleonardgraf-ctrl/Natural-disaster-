package com.realisticdisasters.disaster.volcano;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Volcanic ash fall-out: settles in layers like snow (1-8), blankets the ground, plants and tree tops downwind of an
 * eruption and stays there (it doesn't melt). Dug with a shovel; drops nothing.
 */
public class AshLayerBlock extends SnowLayerBlock {
    private static final MapCodec<AshLayerBlock> CODEC = simpleCodec(AshLayerBlock::new);

    public AshLayerBlock(Properties properties) {
        super(properties);
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public MapCodec<SnowLayerBlock> codec() {
        return (MapCodec) CODEC;
    }

    /**
     * Like a thin snow layer: only a single layer gets replaced by placed blocks. (Vanilla's version compares the placing
     * item with the block's own item; ash has no item, which would make that check recurse forever.)
     */
    @Override
    protected boolean canBeReplaced(BlockState state, BlockPlaceContext context) {
        return state.getValue(LAYERS) == 1;
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        // Ash doesn't melt.
    }
}
