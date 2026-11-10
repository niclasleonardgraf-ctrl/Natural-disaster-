package com.realisticdisasters.disaster.wildfire;

import com.mojang.serialization.MapCodec;
import com.realisticdisasters.config.DisasterConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;

/**
 * Burnt ground: black char and grey ash over dirt. Smoulders for a while after the fire, and (if enabled) slowly
 * weathers back into dirt so grass can grow over the scar again.
 */
public class ScorchedEarthBlock extends Block {
    public static final MapCodec<ScorchedEarthBlock> CODEC = simpleCodec(ScorchedEarthBlock::new);

    public ScorchedEarthBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(Smoldering.SMOLDERING, false));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(Smoldering.SMOLDERING);
    }

    @Override
    protected boolean isRandomlyTicking(BlockState state) {
        return true;
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (state.getValue(Smoldering.SMOLDERING)) {
            if (random.nextInt(2) == 0 || level.isRainingAt(pos.above())) {
                level.setBlock(pos, state.setValue(Smoldering.SMOLDERING, false), Block.UPDATE_CLIENTS);
            }
        } else if (DisasterConfig.WILDFIRE_REGROWTH.getAsBoolean() && random.nextInt(60) == 0
                && level.getBlockState(pos.above()).isAir()) {
            level.setBlock(pos, Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL); // grass spreads back onto it
        }
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (state.getValue(Smoldering.SMOLDERING) && level.getBlockState(pos.above()).isAir()) {
            Smoldering.wisps(level, pos, random, 1.0);
        }
    }
}
