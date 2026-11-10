package com.realisticdisasters.disaster.wildfire;

import com.realisticdisasters.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * What a block column offers a fire, read from the blocks themselves.
 * <p>
 * Uses each block's own fire properties (vanilla / NeoForge flammability and fire-spread speed, so modded blocks work
 * too) to derive a fuel type, how easily it ignites, and its fuel load (how long it burns). Fuel moisture comes from
 * the biome's rainfall and temperature, rain, nearby water and the time of day: the same inputs real fire-danger
 * ratings use.
 */
public final class FireFuel {
    public static final int NONE = 0, GRASS = 1, BRUSH = 2, FOREST = 3, STRUCTURE = 4;

    /** Result of a column scan. */
    public record Column(int type, int top, int ground, float ignitability, float load, int leaves, int logs) {
        static final Column EMPTY = new Column(NONE, 0, 0, 0f, 0f, 0, 0);

        public boolean burnable() {
            return type != NONE;
        }
    }

    /**
     * Scans one column from the surface down to the first non-flammable solid block (max 28 blocks).
     * Cost: a heightmap lookup and a handful of block reads.
     */
    public static Column scan(ServerLevel level, int x, int z) {
        int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, top, z);
        BlockState topState = level.getBlockState(p);
        if (!topState.getFluidState().isEmpty() || isCovering(topState)) return Column.EMPTY;

        float ign = 0f, load = 0f;
        int leaves = 0, logs = 0, plants = 0, structure = 0;
        boolean litter = false;
        int ground = top;
        for (int y = top; y > top - 28; y--) {
            p.setY(y);
            BlockState st = y == top ? topState : level.getBlockState(p);
            if (st.isAir() || st.getBlock() instanceof WildfireFlameBlock) continue;
            if (!st.getFluidState().isEmpty()) break;
            if (st.is(ModRegistries.CHARRED_LOG.get()) || st.is(ModRegistries.SCORCHED_LEAVES.get())) {
                continue; // already burnt: no fuel left, fire passes over it
            }
            if (st.is(BlockTags.LEAVES)) {
                leaves++;
                load += 1.2f;
                ign = Math.max(ign, 0.8f);
                continue;
            }
            if (st.is(BlockTags.LOGS)) {
                logs++;
                load += 3f;
                continue;
            }
            int spread = st.getFireSpreadSpeed(level, p, Direction.UP);
            int flam = st.getFlammability(level, p, Direction.UP);
            if (isPlant(st)) {
                plants++;
                load += 0.6f;
                ign = Math.max(ign, Math.max(0.8f, spread / 70f));
                continue;
            }
            if (flam > 0 || spread > 0) {
                // man-made or other flammable blocks: planks, wool, hay, bookshelves, modded wood...
                structure++;
                load += 1f + flam / 25f;
                ign = Math.max(ign, Mth.clamp(spread / 70f, 0.1f, 0.9f));
                continue;
            }
            if (st.is(Blocks.GRASS_BLOCK) || st.is(Blocks.PODZOL) || st.is(Blocks.MYCELIUM) || st.is(Blocks.MOSS_BLOCK)) {
                litter = true; // cured grass and litter on the ground carry a fire
                load += 0.8f;
                ign = Math.max(ign, 0.6f);
            }
            ground = y;
            break;
        }
        int type;
        if (leaves > 0 || logs > 0) type = FOREST;
        else if (structure > 0) type = STRUCTURE;
        else if (plants > 0) type = BRUSH;
        else if (litter) type = GRASS;
        else type = NONE;
        return new Column(type, top, ground, ign, load, leaves, logs);
    }

    /** Snow, ice and similar covers keep the fuel underneath from burning. */
    static boolean isCovering(BlockState st) {
        return st.is(Blocks.SNOW) || st.is(Blocks.POWDER_SNOW) || st.is(Blocks.SNOW_BLOCK) || st.is(BlockTags.ICE);
    }

    static boolean isPlant(BlockState st) {
        return st.is(BlockTags.REPLACEABLE_BY_TREES) || st.is(BlockTags.FLOWERS) || st.is(BlockTags.SAPLINGS)
                || st.is(BlockTags.CROPS) || st.is(Blocks.SWEET_BERRY_BUSH) || st.is(Blocks.DEAD_BUSH)
                || st.is(Blocks.FERN) || st.is(Blocks.LARGE_FERN) || st.is(Blocks.SHORT_GRASS) || st.is(Blocks.TALL_GRASS);
    }

    /**
     * Fuel dryness 0..~1.3 (1 = typical dry fuel). Lower in wet biomes, near water, in the rain and at night;
     * higher in hot, dry biomes in the afternoon. Multiplies the ignition chance.
     */
    public static float dryness(ServerLevel level, int x, int ground, int z, boolean rainSlows) {
        BlockPos pos = new BlockPos(x, ground + 1, z);
        Biome biome = level.getBiome(pos).value();
        Biome.ClimateSettings climate = biome.getModifiedClimateSettings();
        float d = (1f - 0.65f * Mth.clamp(climate.downfall(), 0f, 1f)) * (0.85f + 0.15f * Mth.clamp(climate.temperature(), 0f, 2f));
        if (nearWater(level, x, ground, z)) d *= 0.55f;               // riparian vegetation is green and wet
        if (rainSlows && level.isRaining() && level.isRainingAt(pos)) d *= 0.3f;
        d *= 1.1f - 0.035f * level.getSkyDarken();                   // diurnal cycle: fires lie down at night
        return Mth.clamp(d, 0.05f, 1.35f);
    }

    private static boolean nearWater(ServerLevel level, int x, int y, int z) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int[] o : new int[][]{{3, 0}, {-3, 0}, {0, 3}, {0, -3}}) {
            p.set(x + o[0], y, z + o[1]);
            if (level.isLoaded(p) && level.getFluidState(p).is(FluidTags.WATER)) return true;
        }
        return false;
    }

    private FireFuel() {}
}
