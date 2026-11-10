package com.realisticdisasters.disaster.wildfire;

import com.realisticdisasters.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/**
 * Shared "still smouldering" behaviour of burnt blocks: thin smoke wisps and the odd ember for a minute or two after
 * the front has passed (vanilla random ticks put it out), like the smoking ground behind a real fire line.
 */
public final class Smoldering {
    public static final BooleanProperty SMOLDERING = BooleanProperty.create("smoldering");

    /** Client-side, from animateTick. Particle args: disaster id (-1 = none), smoke shade (0 light), ground y. */
    public static void wisps(Level level, BlockPos pos, RandomSource random, double topOffset) {
        if (random.nextInt(3) == 0) {
            level.addParticle(ModRegistries.SMOLDER_SMOKE.get(), pos.getX() + 0.2 + random.nextDouble() * 0.6,
                    pos.getY() + topOffset, pos.getZ() + 0.2 + random.nextDouble() * 0.6, -1, 0.15 + random.nextDouble() * 0.2,
                    pos.getY() + topOffset);
        }
        if (random.nextInt(40) == 0) {
            level.addParticle(ModRegistries.EMBER.get(), pos.getX() + random.nextDouble(), pos.getY() + topOffset,
                    pos.getZ() + random.nextDouble(), 0, 0.05 + random.nextDouble() * 0.05, 0);
        }
    }

    private Smoldering() {}
}
