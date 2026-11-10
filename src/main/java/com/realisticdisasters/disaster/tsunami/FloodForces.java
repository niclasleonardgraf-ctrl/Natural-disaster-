package com.realisticdisasters.disaster.tsunami;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.Tags;

/**
 * How the moving water loads what it meets. The flow's force at a point scales with depth x velocity^2 (momentum
 * flux); every block gets a resistance by material, the way real tsunamis sort structures: glass and light timber
 * fail first, wooden frames and trees go next at high intensity, while masonry, concrete, metal and earth hold.
 * Both are on the same arbitrary scale: a block fails when {@code force * (0.6..1.4) > resistance}.
 */
final class FloodForces {
    static final float HOLDS = Float.MAX_VALUE;

    /** Resistance of a block to the flow (see class doc). {@link #HOLDS} means the flow can't move it. */
    static float resistance(BlockState st) {
        if (st.isAir()) return 0f;
        if (!st.getFluidState().isEmpty() || st.hasBlockEntity()) return HOLDS;
        if (st.canBeReplaced()) return 0f;                     // grass, flowers, snow layers
        if (st.is(BlockTags.LEAVES)) return 0.35f;
        if (st.is(Tags.Blocks.GLASS_PANES) || st.is(Tags.Blocks.GLASS_BLOCKS)) return 0.8f;
        if (!st.blocksMotion()) return 0.3f;                   // crops, torches, rails, carpets, buttons...
        if (st.is(BlockTags.WOOL)) return 1.2f;
        if (st.is(BlockTags.WOODEN_DOORS) || st.is(BlockTags.WOODEN_TRAPDOORS) || st.is(BlockTags.FENCE_GATES)) return 1.6f;
        if (st.is(BlockTags.WOODEN_FENCES) || st.is(BlockTags.WOODEN_SLABS) || st.is(BlockTags.WOODEN_STAIRS)) return 2.0f;
        if (st.is(BlockTags.PLANKS)) return 2.4f;
        if (st.is(BlockTags.LOGS)) return 3.0f;
        return HOLDS;                                          // stone, brick, concrete, metal, soil
    }

    /** Pieces that float away instead of sinking or shattering. */
    static boolean floats(BlockState st) {
        return st.is(BlockTags.LEAVES) || st.is(BlockTags.PLANKS) || st.is(BlockTags.LOGS) || st.is(BlockTags.WOOL)
                || st.is(BlockTags.WOODEN_FENCES) || st.is(BlockTags.WOODEN_SLABS) || st.is(BlockTags.WOODEN_STAIRS)
                || st.is(BlockTags.WOODEN_TRAPDOORS);
    }

    /**
     * The connected logs and leaves of a tree standing on {@code trunk} (capped, and kept within a few blocks of the
     * trunk so a forest isn't torn out as one piece). Logs are listed first, bottom-up.
     */
    static List<BlockPos> tree(ServerLevel level, BlockPos trunk, int cap) {
        List<BlockPos> logs = new ArrayList<>(), leaves = new ArrayList<>();
        java.util.HashSet<BlockPos> seen = new java.util.HashSet<>();
        ArrayDeque<BlockPos> open = new ArrayDeque<>();
        open.add(trunk);
        seen.add(trunk);
        while (!open.isEmpty() && logs.size() + leaves.size() < cap) {
            BlockPos p = open.poll();
            BlockState st = level.getBlockState(p);
            boolean log = st.is(BlockTags.LOGS);
            if (!log && !st.is(BlockTags.LEAVES)) continue;
            (log ? logs : leaves).add(p);
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = 0; dy <= 1; dy++)
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos n = p.offset(dx, dy, dz);
                        if (n.getY() < trunk.getY() || Math.abs(n.getX() - trunk.getX()) > 4 || Math.abs(n.getZ() - trunk.getZ()) > 4) continue;
                        if (seen.add(n) && level.isLoaded(n)) open.add(n);
                    }
        }
        logs.sort(java.util.Comparator.comparingInt(BlockPos::getY));
        logs.addAll(leaves);
        return logs;
    }

    private FloodForces() {}
}
