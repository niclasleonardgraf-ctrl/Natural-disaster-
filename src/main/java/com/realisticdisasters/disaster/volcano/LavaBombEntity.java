package com.realisticdisasters.disaster.volcano;

import java.util.function.Consumer;

import com.realisticdisasters.config.DisasterConfig;
import com.realisticdisasters.disaster.DisasterManager;
import com.realisticdisasters.disaster.DisasterProtection;
import com.realisticdisasters.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A glowing chunk of molten rock thrown out of a volcano's vent. It flies on a ballistic arc, leaves a fire and
 * smoke trail (spawned client-side by the volcano visual through {@link #CLIENT_TRAIL}), and on impact hurts and
 * ignites nearby entities and scorches the ground. Block changes go through the batched queue and protection checks.
 */
public class LavaBombEntity extends ThrowableItemProjectile {
    /** Client hook for the trail particles (set by the client visual; a no-op on dedicated servers). */
    public static Consumer<LavaBombEntity> CLIENT_TRAIL = b -> {};

    private int intensity = 3;

    public LavaBombEntity(EntityType<? extends LavaBombEntity> type, Level level) {
        super(type, level);
    }

    public static LavaBombEntity launch(ServerLevel level, Vec3 from, Vec3 velocity, int intensity) {
        LavaBombEntity bomb = new LavaBombEntity(ModRegistries.LAVA_BOMB.get(), level);
        bomb.setPos(from);
        bomb.setItem(new ItemStack(Items.MAGMA_BLOCK));
        bomb.setDeltaMovement(velocity);
        bomb.intensity = Mth.clamp(intensity, 1, 5);
        level.addFreshEntity(bomb);
        return bomb;
    }

    @Override
    protected Item getDefaultItem() {
        return Items.MAGMA_BLOCK;
    }

    @Override
    protected double getDefaultGravity() {
        return 0.04;
    }

    @Override
    public boolean isOnFire() {
        return false; // it glows on its own; the vanilla fire overlay would hide the block
    }

    @Override
    protected boolean canHitEntity(Entity entity) {
        return !(entity instanceof LavaBombEntity) && super.canHitEntity(entity);
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) {
            CLIENT_TRAIL.accept(this);
        } else if (tickCount > 400 || getY() < level().getMinY()) {
            discard();
        }
    }

    @Override
    protected void onHitEntity(EntityHitResult hit) {
        super.onHitEntity(hit);
        if (level() instanceof ServerLevel sl && hit.getEntity() instanceof LivingEntity le) {
            le.hurtServer(sl, damage(sl), 3f + intensity * 1.5f);
        }
    }

    @Override
    protected void onHit(HitResult hit) {
        super.onHit(hit);
        if (!(level() instanceof ServerLevel sl) || isRemoved()) return;
        Vec3 at = hit.getLocation();
        impact(sl, at);
        discard();
    }

    private DamageSource damage(ServerLevel sl) {
        return sl.damageSources().source(ModRegistries.VOLCANO_DAMAGE);
    }

    private void impact(ServerLevel sl, Vec3 at) {
        sl.playSound(null, at.x, at.y, at.z, ModRegistries.LAVA_BOMB_IMPACT.get(), SoundSource.WEATHER,
                1.2f + intensity * 0.25f, 0.8f + random.nextFloat() * 0.3f);
        sl.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y + 0.3, at.z, 1, 0, 0, 0, 0);
        sl.sendParticles(ParticleTypes.LAVA, at.x, at.y + 0.3, at.z, 6 + intensity * 2, 0.4, 0.2, 0.4, 0);
        sl.sendParticles(ParticleTypes.LARGE_SMOKE, at.x, at.y + 0.5, at.z, 4 + intensity, 0.5, 0.3, 0.5, 0.03);

        // Splash damage and burns.
        double r = 1.5 + intensity * 0.4;
        AABB box = new AABB(at.x - r, at.y - r, at.z - r, at.x + r, at.y + r, at.z + r);
        for (LivingEntity le : sl.getEntitiesOfClass(LivingEntity.class, box, e -> !e.isSpectator())) {
            double d = le.position().distanceTo(at);
            if (d > r) continue;
            le.hurtServer(sl, damage(sl), (float) ((1.5 + intensity) * (1 - d / r * 0.6)));
            le.igniteForSeconds(2f + intensity);
        }

        if (!DisasterConfig.BLOCK_DESTRUCTION.getAsBoolean()) return;
        BlockPos center = BlockPos.containing(at.x, at.y - 0.2, at.z);
        int rad = intensity >= 4 ? 2 : 1;
        long seed = random.nextLong();
        int bombIntensity = intensity;
        DisasterManager.blockQueue(sl).add(l -> scorch(l, center, rad, bombIntensity, seed));
    }

    /** Burns the landing site: a magma core, scorched soil around it and a few fires. */
    static void scorch(ServerLevel level, BlockPos center, int rad, int intensity, long seed) {
        var rng = net.minecraft.util.RandomSource.create(seed);
        // Find the solid block that was hit (the impact point can be just above it).
        BlockPos core = center;
        if (level.getBlockState(core).isAir()) core = core.below();
        for (int dx = -rad; dx <= rad; dx++) {
            for (int dz = -rad; dz <= rad; dz++) {
                if (dx * dx + dz * dz > rad * rad + 1) continue;
                boolean isCore = dx == 0 && dz == 0;
                // a coherent little crater: full inner ring, ragged outer ring
                if (dx * dx + dz * dz > 2 && rng.nextFloat() < 0.5f) continue;
                // walk to the local surface (within ±2 blocks)
                BlockPos p = core.offset(dx, 2, dz);
                for (int i = 0; i < 5 && (level.getBlockState(p).isAir() || level.getBlockState(p).canBeReplaced()); i++) p = p.below();
                BlockState st = level.getBlockState(p);
                if (st.isAir() || st.getBlock() instanceof LiquidBlock || st.hasBlockEntity()) continue;
                float hardness = st.getDestroySpeed(level, p);
                if (hardness < 0 || hardness > 3.5f) continue;
                if (!DisasterProtection.canModify(level, p, st)) continue;

                BlockState above = level.getBlockState(p.above());
                if (!above.isAir() && above.canBeReplaced() && !(above.getBlock() instanceof LiquidBlock)) {
                    level.setBlock(p.above(), Blocks.AIR.defaultBlockState(), 3); // grass, flowers, snow layers burn off
                }
                if (st.is(BlockTags.LEAVES) || st.is(BlockTags.LOGS) || st.is(BlockTags.PLANKS) || st.is(BlockTags.WOOL)) {
                    // Flammable: set it alight instead of converting it.
                    BlockPos fire = p.above();
                    if (level.getBlockState(fire).isAir() && BaseFireBlock.canBePlacedAt(level, fire, net.minecraft.core.Direction.UP)) {
                        level.setBlock(fire, BaseFireBlock.getState(level, fire), 11);
                    }
                    continue;
                }
                BlockState burnt;
                if (isCore) {
                    // the bomb itself: a lump of dark scoria, some still glowing inside
                    burnt = rng.nextFloat() < 0.35f ? Blocks.MAGMA_BLOCK.defaultBlockState() : Blocks.BLACKSTONE.defaultBlockState();
                } else if (st.is(BlockTags.DIRT) || st.is(Blocks.GRASS_BLOCK) || st.is(Blocks.MOSS_BLOCK)) {
                    burnt = Blocks.COARSE_DIRT.defaultBlockState();
                } else if (st.is(BlockTags.SAND)) {
                    continue; // sand just takes the heat
                } else if (st.is(Blocks.SNOW_BLOCK) || st.is(Blocks.POWDER_SNOW) || st.is(Blocks.ICE)) {
                    burnt = Blocks.AIR.defaultBlockState();
                } else {
                    continue; // rock just takes the hit
                }
                // Bare soil and rock don't burn: fires only start where there is something to burn (handled above).
                level.setBlock(p, burnt, 3);
            }
        }
    }
}
