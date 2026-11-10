package com.realisticdisasters.disaster.volcano;

import com.realisticdisasters.config.DisasterConfig;
import com.realisticdisasters.disaster.DisasterManager;
import com.realisticdisasters.disaster.DisasterProtection;
import com.realisticdisasters.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A pyroclastic density current: part of the eruption column collapses and races down the mountain as a ground-hugging
 * avalanche of hot ash and gas (several hundred °C, often over 100 km/h). It runs out in a sector from the summit,
 * accelerating down steep flanks and slowing on flat ground. Within its front it kills and ignites what it catches,
 * strips trees, scorches the ground, melts snow and leaves an ash deposit behind.
 */
final class PyroclasticFlow {
    final double angle, halfWidth, maxRadius;
    double radius;
    double speed;
    private final ServerLevel level;
    private final RandomSource random;
    private final int intensity;
    private final int cx, cz;
    private final double topY;
    int age;

    PyroclasticFlow(ServerLevel level, RandomSource random, int intensity, int cx, int cz, double startRadius,
                    double maxRadius, double angle, double halfWidth, double topY) {
        this.level = level;
        this.random = random;
        this.intensity = intensity;
        this.cx = cx;
        this.cz = cz;
        this.radius = startRadius;
        this.maxRadius = maxRadius;
        this.angle = angle;
        this.halfWidth = halfWidth;
        this.topY = topY;
        this.speed = 0.4 + intensity * 0.1;
    }

    boolean done() {
        return radius >= maxRadius || speed < 0.12 || age > 1200;
    }

    /** Head height of the current above the ground (the billowing front is the tallest part). */
    double headHeight() {
        return 6 + intensity * 3;
    }

    void tick() {
        age++;
        double prev = radius;
        // Gravity-driven: speed follows the slope under the front; friction slows it on flat ground.
        Vec3 front = pointAt(radius, angle);
        Vec3 ahead = pointAt(radius + 4, angle);
        double slope = (front.y - ahead.y) / 4.0;      // > 0 downhill
        // Real pyroclastic flows run ~50-150 km/h (up to ~2 blocks per tick here).
        speed = Mth.clamp(speed + slope * 0.04 - 0.006, 0.0, 1.0 + intensity * 0.2);
        speed *= slope < 0.05 ? 0.993 : 1.0;
        radius += speed;

        if (age % 2 == 0) hitEntities();
        scorch(prev, radius);
    }

    private Vec3 pointAt(double r, double a) {
        int x = Mth.floor(cx + 0.5 + Math.cos(a) * r), z = Mth.floor(cz + 0.5 + Math.sin(a) * r);
        double y = level.isLoaded(new BlockPos(x, 0, z)) ? level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) : topY;
        return new Vec3(x + 0.5, y, z + 0.5);
    }

    private boolean inSector(double x, double z) {
        double a = Math.atan2(z - (cz + 0.5), x - (cx + 0.5));
        double diff = Math.abs(Mth.wrapDegrees((float) Math.toDegrees(a - angle)));
        return diff <= Math.toDegrees(halfWidth);
    }

    /** Within the hot cloud: the turbulent head and the dense body behind it. */
    private void hitEntities() {
        double outer = radius + 4, inner = Math.max(0, radius - 30 - intensity * 6);
        AABB box = new AABB(cx - outer, level.getMinY(), cz - outer, cx + outer, topY + 40, cz + outer);
        var src = level.damageSources().source(ModRegistries.VOLCANO_DAMAGE);
        for (Entity e : level.getEntities((Entity) null, box, e -> !e.isSpectator())) {
            double dx = e.getX() - (cx + 0.5), dz = e.getZ() - (cz + 0.5);
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d > outer || d < inner || !inSector(e.getX(), e.getZ())) continue;
            int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, e.getBlockX(), e.getBlockZ());
            if (e.getY() > ground + headHeight() * 1.5) continue;
            boolean head = d > radius - 8;
            if (e instanceof LivingEntity le) {
                // Being underwater or deep underground doesn't help much against the heat, but a roof over you does a little.
                boolean sheltered = !level.canSeeSky(e.blockPosition().above());
                float dmg = (head ? 4f + intensity * 1.6f : 1.5f + intensity * 0.6f) * (sheltered ? 0.5f : 1f);
                le.hurtServer(level, src, dmg);
                le.igniteForSeconds(6f + intensity);
            }
            if (head) {
                // The front shoves everything ahead of it.
                Vec3 out = new Vec3(dx, 0, dz).normalize().scale(speed * 0.5);
                e.setDeltaMovement(e.getDeltaMovement().add(out.x, 0.12, out.z));
                e.hurtMarked = true;
            }
        }
    }

    /** Effects on the ground swept by the front this tick (batched through the block queue). */
    private void scorch(double from, double to) {
        if (!DisasterConfig.BLOCK_DESTRUCTION.getAsBoolean()) return;
        var queue = DisasterManager.blockQueue(level);
        double arc = halfWidth * 2 * Math.max(from, 1);
        int samples = Mth.clamp((int) (arc * (to - from) / 6.0), 2, 40);
        for (int k = 0; k < samples; k++) {
            double r = from + random.nextDouble() * (to - from);
            double a = angle + (random.nextDouble() * 2 - 1) * halfWidth;
            int x = Mth.floor(cx + 0.5 + Math.cos(a) * r), z = Mth.floor(cz + 0.5 + Math.sin(a) * r);
            if (!level.isLoaded(new BlockPos(x, 0, z))) continue;
            long seed = random.nextLong();
            if (!queue.add(l -> scorchColumn(x, z, seed))) return;
        }
    }

    private void scorchColumn(int x, int z, long seed) {
        RandomSource rng = RandomSource.create(seed);
        int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, top, z);
        // Strip the canopy: leaves are torn off and burnt, trunks stay standing (charred forests).
        BlockPos trunkTop = null;
        for (int i = 0; i < 12; i++) {
            BlockState st = level.getBlockState(p);
            if (st.is(BlockTags.LEAVES) || st.canBeReplaced() && !st.isAir() && !(st.getBlock() instanceof LiquidBlock)) {
                if (DisasterProtection.canModify(level, p, st)) level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
                p.move(Direction.DOWN);
                continue;
            }
            if (st.is(BlockTags.LOGS)) {
                if (trunkTop == null) trunkTop = p.immutable();
                p.move(Direction.DOWN);
                continue;
            }
            break;
        }
        if (trunkTop != null && rng.nextFloat() < 0.4f && level.getBlockState(trunkTop.above()).isAir()
                && DisasterProtection.canModify(level, trunkTop, level.getBlockState(trunkTop))) {
            level.setBlock(trunkTop.above(), BaseFireBlock.getState(level, trunkTop.above()), 11);
        }
        BlockState ground = level.getBlockState(p);
        if (ground.isAir() || !DisasterProtection.canModify(level, p, ground)) return;
        if (ground.is(Blocks.GRASS_BLOCK) || ground.is(Blocks.PODZOL) || ground.is(Blocks.MYCELIUM) || ground.is(Blocks.MOSS_BLOCK)) {
            level.setBlock(p, Blocks.COARSE_DIRT.defaultBlockState(), 3);
        } else if (ground.is(Blocks.SNOW_BLOCK) || ground.is(Blocks.POWDER_SNOW) || ground.is(Blocks.ICE)) {
            // Snow and ice flash-melt: meltwater runs off (a small lahar).
            level.setBlock(p, rng.nextFloat() < 0.5f ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
            return;
        }
        BlockPos above = p.above();
        if (!level.getBlockState(above).isAir()) return;
        // The current leaves a deposit of ash behind.
        if (rng.nextFloat() < 0.8f) AshFall.addLayers(level, above, 1 + rng.nextInt(1 + intensity / 2));
    }
}
