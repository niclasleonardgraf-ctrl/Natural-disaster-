package com.realisticdisasters.disaster.wildfire;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;

/** A burnt trunk. Keeps the log's axis; smoulders (smoke wisps, embers) for a while after the fire passed. */
public class CharredLogBlock extends RotatedPillarBlock {
    public static final MapCodec<CharredLogBlock> CODEC = simpleCodec(CharredLogBlock::new);

    public CharredLogBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(Smoldering.SMOLDERING, false));
    }

    @Override
    public MapCodec<? extends RotatedPillarBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(Smoldering.SMOLDERING);
    }

    @Override
    protected boolean isRandomlyTicking(BlockState state) {
        return state.getValue(Smoldering.SMOLDERING);
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (random.nextInt(3) == 0 || level.isRainingAt(pos.above())) {
            level.setBlock(pos, state.setValue(Smoldering.SMOLDERING, false), Block.UPDATE_CLIENTS);
        }
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (state.getValue(Smoldering.SMOLDERING) && random.nextInt(2) == 0) {
            Smoldering.wisps(level, pos, random, random.nextDouble());
        }
    }
}
