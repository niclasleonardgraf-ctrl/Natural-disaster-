package com.realisticdisasters.disaster.wildfire;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import com.realisticdisasters.registry.ModRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The visible flames of a wildfire. Looks, lights and burns entities like vanilla fire, but it never spreads on its
 * own and goes out after a few seconds through a scheduled tick (so flames left behind after a server restart still
 * burn out). Spread is driven entirely by {@link WildfireDisaster}, which keeps it capped and batched.
 */
public class WildfireFlameBlock extends BaseFireBlock {
    public static final MapCodec<WildfireFlameBlock> CODEC = simpleCodec(WildfireFlameBlock::new);

    public WildfireFlameBlock(BlockBehaviour.Properties properties) {
        super(properties, 1.0F);
    }

    @Override
    protected MapCodec<? extends BaseFireBlock> codec() {
        return CODEC;
    }

    @Override
    protected boolean canBurn(BlockState state) {
        return state.ignitedByLava();
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockState below = level.getBlockState(pos.below());
        return !below.isAir() && below.getFluidState().isEmpty()
                && (below.isFaceSturdy(level, pos.below(), Direction.UP) || below.ignitedByLava());
    }

    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos,
                                     Direction direction, BlockPos neighbourPos, BlockState neighbourState, RandomSource random) {
        return canSurvive(state, level, pos) ? state : Blocks.AIR.defaultBlockState();
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (!oldState.is(this)) {
            level.scheduleTick(pos, this, 60 + level.getRandom().nextInt(140));
        }
    }

    /** Fire sounds like vanilla, but realistic smoke and embers instead of vanilla's pixel smoke puffs. */
    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (random.nextInt(24) == 0) {
            level.playLocalSound(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, SoundEvents.FIRE_AMBIENT,
                    SoundSource.BLOCKS, 1.0F + random.nextFloat(), random.nextFloat() * 0.7F + 0.3F, false);
        }
        if (random.nextInt(4) == 0) {
            level.addParticle(ModRegistries.WILDFIRE_SMOKE.get(), pos.getX() + random.nextDouble(), pos.getY() + 1.0,
                    pos.getZ() + random.nextDouble(), -1, 0.3 + random.nextDouble() * 0.4, pos.getY());
        }
        if (random.nextInt(6) == 0) {
            level.addParticle(ModRegistries.EMBER.get(), pos.getX() + random.nextDouble(), pos.getY() + 0.6,
                    pos.getZ() + random.nextDouble(), 0, 0.12 + random.nextDouble() * 0.12, 0);
        }
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (level.isRainingAt(pos) || random.nextInt(4) != 0) {
            level.removeBlock(pos, false);
        } else {
            // Flicker on a little longer.
            level.scheduleTick(pos, this, 20 + random.nextInt(60));
        }
    }
}
