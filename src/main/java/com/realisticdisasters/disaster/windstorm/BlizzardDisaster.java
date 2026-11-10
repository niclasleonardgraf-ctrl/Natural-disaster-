package com.realisticdisasters.disaster.windstorm;

import com.realisticdisasters.config.DisasterConfig;
import com.realisticdisasters.disaster.DisasterProtection;
import com.realisticdisasters.disaster.DisasterType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;

/**
 * Blizzard: white-out snow driven sideways by a howling wind. Buries the land under drifting snow layers, skins
 * still water with ice and slowly freezes anything left outside (leather armour protects, like powder snow).
 */
public class BlizzardDisaster extends WindStormDisaster {
    public BlizzardDisaster(ServerLevel level, int id, Vec3 pos, int intensity) {
        super(DisasterType.BLIZZARD, level, id, pos, intensity, DisasterConfig.BLIZZARD_DURATION_SCALE.get());
    }

    @Override
    protected String key() {
        return "disaster.realisticdisasters.blizzard";
    }

    @Override
    protected void tickExtra() {
        if (age() == 1 && DisasterConfig.BLIZZARD_CHANGES_WEATHER.getAsBoolean() && level.dimension() == Level.OVERWORLD
                && !level.isRaining() && snowsHere()) {
            // Precipitation is snow in cold biomes, which is where blizzards spawn naturally.
            level.getServer().setWeatherParameters(0, 6000, true, false);
        }
    }

    /** Precipitation only helps where it falls as snow; rain inside a blizzard would look wrong. */
    private boolean snowsHere() {
        net.minecraft.core.BlockPos at = net.minecraft.core.BlockPos.containing(pos);
        return level.isLoaded(at) && level.getBiome(at).value().coldEnoughToSnow(at, level.getSeaLevel());
    }

    @Override
    protected float accumulationRate() {
        return DisasterConfig.BLIZZARD_SNOW_RATE.get().floatValue();
    }

    @Override
    protected void accumulate(BlockPos top, int drift) {
        BlockState below = level.getBlockState(top.below());
        if (below.getFluidState().is(Fluids.WATER) && below.getFluidState().isSource() && below.is(Blocks.WATER)) {
            if (intensity >= 2 && random.nextInt(3) == 0 && DisasterProtection.canModify(level, top.below(), below)) {
                level.setBlock(top.below(), Blocks.ICE.defaultBlockState(), 3);
            }
            return;
        }
        int n = addLayer(top, Blocks.SNOW.defaultBlockState(), Math.min(8, 1 + intensity + drift));
        if (n == 8 && drift >= 3 && intensity >= 3) {
            // Deep, wind-packed-on-top but loose-underneath drifts: you sink right in (and can't climb out easily).
            level.setBlock(top, Blocks.POWDER_SNOW.defaultBlockState(), 3);
        }
    }

    @Override
    protected void affectExposed(LivingEntity entity, float force) {
        if (force < 0.5f || !entity.canFreeze()) return;
        // Vanilla thaws 2 ticks of frost per tick outside powder snow; this is called every 10 ticks, so anything
        // above 20 accumulates. Fully frozen entities take vanilla freeze damage.
        // Net gain per call ≈ 1.2 × intensity: fully frozen after ~35 s outside at intensity 3, ~12 s at intensity 5.
        int add = 20 + Math.round(intensity * force * 1.2f);
        entity.setTicksFrozen(Math.min(entity.getTicksRequiredToFreeze() + 20, entity.getTicksFrozen() + add));
    }
}
