package com.realisticdisasters.disaster.windstorm;

import com.realisticdisasters.config.DisasterConfig;
import com.realisticdisasters.disaster.DisasterType;
import com.realisticdisasters.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Sandstorm (haboob): a wall of sand rolling across desert or badlands. Lays sand (or red sand) layers over the
 * ground, builds full-block piles against walls and in their lee, and at higher intensity sand-blasts anyone
 * outside without a helmet.
 */
public class SandstormDisaster extends WindStormDisaster {
    private final boolean red;

    public SandstormDisaster(ServerLevel level, int id, Vec3 pos, int intensity) {
        super(DisasterType.SANDSTORM, level, id, pos, intensity, DisasterConfig.SANDSTORM_DURATION_SCALE.get());
        BlockPos at = BlockPos.containing(pos);
        boolean redGround = level.isLoaded(at) && isRedGround(level.getBlockState(
                new BlockPos(at.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ()) - 1, at.getZ())));
        this.red = redGround || level.isLoaded(at) && level.getBiome(at).is(BiomeTags.IS_BADLANDS);
    }

    private static boolean isRedGround(BlockState s) {
        return s.is(Blocks.RED_SAND) || s.is(BlockTags.TERRACOTTA) || s.is(Blocks.RED_SANDSTONE)
                || s.is(ModRegistries.RED_SAND_LAYER.get());
    }

    public boolean red() {
        return red;
    }

    @Override
    protected String key() {
        return "disaster.realisticdisasters.sandstorm";
    }

    @Override
    protected float variant() {
        return red ? 1f : 0f;
    }

    @Override
    protected float accumulationRate() {
        return DisasterConfig.SANDSTORM_SAND_RATE.get().floatValue();
    }

    @Override
    protected void accumulate(BlockPos top, int drift) {
        BlockState ground = level.getBlockState(top.below());
        boolean redHere = isRedGround(ground) || red && !ground.is(Blocks.SAND) && !ground.is(Blocks.SANDSTONE)
                && !ground.is(ModRegistries.SAND_LAYER.get());
        BlockState layer = (redHere ? ModRegistries.RED_SAND_LAYER : ModRegistries.SAND_LAYER).get().defaultBlockState();
        // Open ground gets a thin even blanket; drifts against obstacles grow into full-block piles.
        int max = Math.min(8, Math.max(1, (intensity + 1) / 2) + drift * 2);
        if (addLayer(top, layer, max) == 8) {
            // A full stack settles into a real sand block; the pile keeps growing on top while the drift lasts.
            level.setBlock(top, (redHere ? Blocks.RED_SAND : Blocks.SAND).defaultBlockState(), 3);
        }
    }

    @Override
    protected void affectExposed(LivingEntity entity, float force) {
        if (intensity < 3 || force < 0.6f || age() % 40 != 0) return;
        if (!entity.getItemBySlot(EquipmentSlot.HEAD).isEmpty()) return; // a helmet keeps the sand out of your eyes
        entity.hurtServer(level, level.damageSources().source(ModRegistries.SANDSTORM_DAMAGE), 0.5f * (intensity - 2) * force);
    }
}
