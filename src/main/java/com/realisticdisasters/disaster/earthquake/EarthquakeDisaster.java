package com.realisticdisasters.disaster.earthquake;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.realisticdisasters.config.DisasterConfig;
import com.realisticdisasters.disaster.BlockChangeQueue;
import com.realisticdisasters.disaster.Disaster;
import com.realisticdisasters.disaster.DisasterManager;
import com.realisticdisasters.disaster.DisasterProtection;
import com.realisticdisasters.disaster.DisasterType;
import com.realisticdisasters.disaster.Phase;
import com.realisticdisasters.registry.ModRegistries;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * An earthquake with a fixed epicentre. A "tremor" value (foreshocks → escalating main shock → aftershocks) drives
 * everything: fissures that tear open in batched steps, unsupported blocks collapsing as falling-block debris
 * (cascading through structures), cracked bricks and shattered glass, stumbling entities and fleeing mobs.
 * Visuals live in the client package and are driven purely by {@link #syncData()}.
 */
public class EarthquakeDisaster extends Disaster {
    /** Indices into the sync data array (shared contract with the client visual). */
    public static final int D_RADIUS = 0, D_TREMOR = 1, D_CRACKING = 2, D_TIP_COUNT = 3, D_TIPS = 4;
    /** Number of fissure tips synced (each as dx, dz relative to the epicentre at D_TIPS + 2 * n). */
    public static final int MAX_SYNCED_TIPS = 3;

    private static final int MAX_PENDING_CHECKS = 2048;

    private final float radius;
    private final float maxHardness;
    private final int maxDebris;
    private final int maxFissures;
    private final int fissureSteps;
    private final float fissureHalfWidth;
    private final int fissureDepth;
    private final List<Fissure> fissures = new ArrayList<>();
    private final List<FallingBlockEntity> debris = new ArrayList<>();
    /** Blocks whose support changed (a neighbour fell / the ground below was carved): re-checked over the next ticks. */
    private final ArrayDeque<BlockPos> pendingChecks = new ArrayDeque<>();
    private final Set<BlockPos> pendingSet = new HashSet<>();

    private float tremor;
    private int shockAge = 1000;
    private int nextShock;
    private float shockAmp;
    private int lastCrack = -1000;

    /** One crack in the ground that grows step by step from its origin. */
    private static final class Fissure {
        double x, z, heading;
        int step;
        final int steps;

        Fissure(double x, double z, double heading, int steps) {
            this.x = x;
            this.z = z;
            this.heading = heading;
            this.steps = steps;
        }

        boolean done() {
            return step >= steps;
        }
    }

    public EarthquakeDisaster(ServerLevel level, int id, Vec3 pos, int intensity) {
        super(DisasterType.EARTHQUAKE, level, id, pos, intensity);
        int i = this.intensity;
        this.radius = 20f + i * 10f;                       // 30 .. 70
        this.maxHardness = 1.0f + i * 0.6f;                // 1.6 (soil, glass) .. 4.0 (deepslate, ores)
        this.maxDebris = Math.max(6, DisasterConfig.EARTHQUAKE_MAX_DEBRIS.getAsInt() * i / 5);
        this.maxFissures = (i + 1) / 2 + (i >= 4 ? 1 : 0);  // 1, 1, 2, 3, 4
        this.fissureSteps = (int) ((6 + i * 5) * DisasterConfig.EARTHQUAKE_FISSURE_LENGTH_SCALE.get());
        this.fissureHalfWidth = 0.4f + i * 0.25f;          // 1 .. 3 blocks wide
        this.fissureDepth = 2 + i * 2;                     // 4 .. 12 deep
        this.nextShock = 40 + random.nextInt(60);

        double scale = DisasterConfig.EARTHQUAKE_DURATION_SCALE.get();
        setDuration(Phase.WARNING, 420);
        setDuration(Phase.BUILDUP, 200);
        setDuration(Phase.PEAK, (int) ((500 + 160 * i) * scale));
        setDuration(Phase.AFTERMATH, 500);
    }

    @Override
    public Component describe() {
        return Component.translatable("disaster.realisticdisasters.earthquake");
    }

    @Override
    public float effectRadius() {
        return radius;
    }

    /** Current ground motion 0..1 (what the client turns into camera shake). */
    public float tremor() {
        return tremor;
    }

    @Override
    protected float[] syncData() {
        float[] data = new float[D_TIPS + MAX_SYNCED_TIPS * 2];
        data[D_RADIUS] = radius;
        data[D_TREMOR] = tremor;
        data[D_CRACKING] = age() - lastCrack < 12 ? 1f : 0f;
        int n = 0;
        for (int k = fissures.size() - 1; k >= 0 && n < MAX_SYNCED_TIPS; k--) {
            Fissure f = fissures.get(k);
            data[D_TIPS + n * 2] = (float) (f.x - pos.x);
            data[D_TIPS + n * 2 + 1] = (float) (f.z - pos.z);
            n++;
        }
        data[D_TIP_COUNT] = n;
        return data;
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onPhaseChange(Phase from, Phase to) {
        if (to == Phase.BUILDUP) {
            broadcast(Component.translatable("disaster.realisticdisasters.earthquake.onset").withStyle(ChatFormatting.RED), radius + 160);
        }
    }

    @Override
    protected void onEnd() {
        debris.clear();
        pendingChecks.clear();
        pendingSet.clear();
    }

    @Override
    protected void tickDisaster() {
        if (age() == 1) {
            broadcast(Component.translatable("disaster.realisticdisasters.earthquake.warning").withStyle(ChatFormatting.GOLD), radius + 200);
        }
        updateTremor();

        if (age() % 20 == 0 && phase() != Phase.AFTERMATH) alertMobs();
        if (tremor > 0.08f && age() % 2 == 0) shakeEntities();

        if (DisasterConfig.BLOCK_DESTRUCTION.getAsBoolean()) {
            if (tremor > 0.25f) crumble();
            growFissures();
            processSupportChecks();
        }
        if (age() % 20 == 0) debris.removeIf(fb -> fb.isRemoved() || fb.time > 200);
    }

    // ------------------------------------------------------------------ ground motion

    /**
     * Foreshocks during the warning, an escalating main shock, a rolling peak and decaying aftershocks.
     * Shocks are short pulses on top of the smooth base level.
     */
    private void updateTremor() {
        float p = phaseProgress();
        int t = age();
        float wobble = (float) (0.5 + 0.25 * Math.sin(t * 0.13) + 0.25 * Math.sin(t * 0.047 + 1.3));
        float base = switch (phase()) {
            case WARNING -> 0.04f * smooth(p);
            case BUILDUP -> 0.15f + 0.65f * smooth(p) * (0.85f + 0.15f * wobble);
            case PEAK -> 0.78f + 0.22f * wobble;
            case AFTERMATH -> 0.55f * (1f - smooth(Math.min(1f, p * 2.5f)));
        };

        // Discrete shocks: foreshocks in the warning, jolts in the peak, aftershocks afterwards.
        if (--nextShock <= 0) {
            switch (phase()) {
                case WARNING -> {
                    shockAmp = (0.12f + 0.04f * intensity) * (0.6f + 0.4f * random.nextFloat()) * (0.5f + p);
                    nextShock = 90 + random.nextInt(110);
                }
                case BUILDUP, PEAK -> {
                    shockAmp = 0.2f + 0.1f * random.nextFloat();
                    nextShock = 50 + random.nextInt(80);
                }
                case AFTERMATH -> {
                    shockAmp = (0.25f + 0.35f * random.nextFloat()) * (1f - p);
                    nextShock = 70 + random.nextInt(120);
                }
            }
            shockAge = 0;
        }
        shockAge++;
        float pulse = shockAge < 40 ? shockAmp * (float) Math.sin(Math.PI * shockAge / 40.0) : 0f;
        tremor = Mth.clamp(base + pulse, 0f, 1f);
    }

    private void shakeEntities() {
        AABB box = new AABB(pos.x - radius, pos.y - 24, pos.z - radius, pos.x + radius, pos.y + 40, pos.z + radius);
        DamageSource source = level.damageSources().source(ModRegistries.EARTHQUAKE_DAMAGE);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box, e -> !e.isSpectator() && e.onGround())) {
            if (e instanceof Player pl && (pl.getAbilities().flying || pl.isCreative())) continue;
            double dx = e.getX() - pos.x, dz = e.getZ() - pos.z;
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d > radius) continue;
            float near = 1f - (float) (d / radius);
            float push = 0.035f * tremor * (0.5f + 0.15f * intensity) * (0.4f + 0.6f * near);
            double hop = tremor > 0.7f && random.nextFloat() < 0.15f * near ? 0.12 + 0.03 * intensity : 0;
            e.push((random.nextDouble() - 0.5) * 2 * push, hop, (random.nextDouble() - 0.5) * 2 * push);
            e.hurtMarked = true;
            // Being thrown about hurts at higher intensities near the epicentre.
            if (intensity >= 3 && tremor > 0.75f && near > 0.55f && age() % 60 == 0) {
                e.hurtServer(level, source, 0.5f * (intensity - 2));
            }
        }
    }

    // ------------------------------------------------------------------ mob reactions

    private void alertMobs() {
        double r = radius + 24 + (phase() == Phase.WARNING ? 32 : 0);
        AABB box = new AABB(pos.x - r, pos.y - 24, pos.z - r, pos.x + r, pos.y + 40, pos.z + r);
        for (PathfinderMob mob : level.getEntitiesOfClass(PathfinderMob.class, box, m -> !(m instanceof Enemy))) {
            if (mob instanceof Villager villager) {
                // Same signal a ringing bell sends: villagers run home and hide.
                villager.getBrain().setMemory(MemoryModuleType.HEARD_BELL_TIME, level.getGameTime());
                continue;
            }
            if (mob.isLeashed() || mob.isPassenger()) continue;
            Vec3 away = new Vec3(mob.getX() - pos.x, 0, mob.getZ() - pos.z);
            away = away.lengthSqr() < 1.0e-4 ? new Vec3(1, 0, 0) : away.normalize();
            Vec3 target = mob.position().add(away.scale(14 + random.nextInt(8)));
            mob.getNavigation().moveTo(target.x, target.y, target.z, phase() == Phase.WARNING ? 1.2 : 1.6);
        }
    }

    // ------------------------------------------------------------------ fissures

    private void growFissures() {
        if (!DisasterConfig.EARTHQUAKE_FISSURES.getAsBoolean() || fissureSteps <= 0) return;
        boolean opening = phase() == Phase.PEAK || phase() == Phase.BUILDUP && phaseProgress() > 0.6f;
        if (!opening || tremor < 0.45f) return;

        // Fissures open one after another through the main shock.
        float progress = phase() == Phase.PEAK ? phaseProgress() : 0f;
        int wanted = Math.min(maxFissures, 1 + (int) (progress * maxFissures * 1.6f));
        while (fissures.size() < wanted) {
            double a = random.nextDouble() * Math.PI * 2;
            double off = random.nextDouble() * radius * 0.25;
            fissures.add(new Fissure(pos.x + Math.cos(a) * off, pos.z + Math.sin(a) * off,
                    random.nextDouble() * Math.PI * 2, fissureSteps));
        }

        int interval = Math.max(2, 9 - intensity);
        if (age() % interval != 0) return;
        BlockChangeQueue queue = DisasterManager.blockQueue(level);
        for (Fissure f : fissures) {
            if (f.done()) continue;
            f.heading += (random.nextDouble() - 0.5) * 0.5;
            f.x += Math.cos(f.heading);
            f.z += Math.sin(f.heading);
            float along = (f.step + 0.5f) / f.steps;
            f.step++;
            // Widest in the middle, tapering to a hairline at both ends.
            float taper = (float) Math.sin(Math.PI * along);
            float half = fissureHalfWidth * (0.35f + 0.65f * taper) * (0.85f + 0.3f * random.nextFloat());
            int depth = Math.max(2, Math.round(fissureDepth * (0.4f + 0.6f * taper) * (0.8f + 0.4f * random.nextFloat())));
            carveStep(queue, f, half, depth);
            lastCrack = age();
        }
    }

    /** One batched step: every column across the fissure at its tip becomes one queued change. */
    private void carveStep(BlockChangeQueue queue, Fissure f, float half, int depth) {
        double px = -Math.sin(f.heading), pz = Math.cos(f.heading);
        Set<Long> columns = new HashSet<>();
        for (double o = -half; o <= half + 1.0e-3; o += 0.5) {
            int x = Mth.floor(f.x + px * o), z = Mth.floor(f.z + pz * o);
            if (columns.add(BlockPos.asLong(x, 0, z))) {
                // Jagged floor: inner columns go deeper than the rim.
                int d = Math.max(1, Math.round(depth * (1f - 0.5f * (float) Math.abs(o) / Math.max(0.5f, half))));
                if (!queue.add(l -> carveColumn(x, z, d))) return;
            }
        }
    }

    private void carveColumn(int x, int z, int depth) {
        if (isFinished()) return;
        BlockPos column = new BlockPos(x, 0, z);
        if (!level.isLoaded(column)) return;
        int ground = groundLevel(x, z);
        // Don't tear up mountainsides far above / below the epicentre: the fault runs through the ground near it.
        if (Math.abs(ground - pos.y) > 24) return;
        boolean first = true;
        for (int y = ground; y > ground - depth && y > level.getMinY() + 1; y--) {
            BlockPos bp = new BlockPos(x, y, z);
            BlockState st = level.getBlockState(bp);
            if (st.isAir()) continue;
            if (st.getBlock() instanceof LiquidBlock || !st.getFluidState().isEmpty()) return; // don't drain lakes
            if (st.hasBlockEntity()) continue;
            float hardness = st.getDestroySpeed(level, bp);
            if (hardness < 0 || hardness > 50) return; // bedrock / obsidian
            if (!DisasterProtection.canModify(level, bp, st)) return;
            if (first) {
                level.levelEvent(2001, bp, Block.getId(st)); // block-break particles + sound at the surface
                first = false;
            }
            level.setBlock(bp, Blocks.AIR.defaultBlockState(), 3);
        }
        // Whatever stood on this column (walls, fences, plants) lost its footing.
        queueCheck(new BlockPos(x, ground + 1, z));
        for (Direction dir : Direction.Plane.HORIZONTAL) queueCheck(new BlockPos(x, ground + 1, z).relative(dir));
    }

    /** Topmost natural ground block in a column, looking through buildings standing on it. */
    private int groundLevel(int x, int z) {
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(x, top, z);
        for (int y = top; y > top - 20; y--) {
            m.setY(y);
            if (terrain(level.getBlockState(m))) return y;
        }
        return top;
    }

    // ------------------------------------------------------------------ crumbling structures

    /** Samples random columns (denser toward the epicentre) and lets weak blocks crack, shatter or fall. */
    private void crumble() {
        int attempts = Math.round((2 + intensity * 3) * tremor);
        for (int k = 0; k < attempts; k++) {
            double a = random.nextDouble() * Math.PI * 2;
            double r = random.nextDouble() * radius;
            int x = Mth.floor(pos.x + Math.cos(a) * r), z = Mth.floor(pos.z + Math.sin(a) * r);
            if (!level.isLoaded(new BlockPos(x, 0, z))) continue;
            float falloff = 1f - 0.6f * (float) (r / radius);
            int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
            if (top < pos.y - 32 || top > pos.y + 48) continue;
            for (int y = top; y > top - 8; y--) {
                evaluate(new BlockPos(x, y, z), falloff);
            }
        }
    }

    private void processSupportChecks() {
        int budget = 8 + intensity * 6;
        for (int k = 0; k < budget && !pendingChecks.isEmpty(); k++) {
            BlockPos bp = pendingChecks.poll();
            pendingSet.remove(bp);
            if (level.isLoaded(bp)) evaluate(bp, 1.5f);
        }
    }

    private void queueCheck(BlockPos bp) {
        if (pendingSet.size() >= MAX_PENDING_CHECKS || !pendingSet.add(bp)) return;
        pendingChecks.add(bp);
    }

    /** Neighbours needed to hold an unsupported block (bridging); at intensity 5 nothing hangs in the air. */
    private int neighboursNeeded() {
        return intensity;
    }

    private static boolean solid(BlockState st) {
        return !st.isAir() && !st.canBeReplaced() && st.getFluidState().isEmpty();
    }

    private static boolean terrain(BlockState st) {
        return st.is(BlockTags.DIRT) || st.is(BlockTags.SAND) || st.is(BlockTags.BASE_STONE_OVERWORLD)
                || st.is(Blocks.GRAVEL) || st.is(BlockTags.SNOW) || st.is(BlockTags.TERRACOTTA)
                || st.is(Blocks.CLAY) || st.is(Blocks.SANDSTONE) || st.is(Blocks.RED_SANDSTONE);
    }

    private void evaluate(BlockPos bp, float chanceScale) {
        BlockState st = level.getBlockState(bp);
        if (!solid(st) || st.hasBlockEntity() || st.is(BlockTags.LEAVES)) return;
        if (st.getCollisionShape(level, bp).isEmpty()) return;
        float hardness = st.getDestroySpeed(level, bp);
        if (hardness < 0) return;

        // Masonry cracks before it fails.
        BlockState cracked = crackedVariant(st);
        if (cracked != null) {
            if (random.nextFloat() < 0.08f * tremor * chanceScale * intensity) {
                DisasterManager.blockQueue(level).add(l -> replace(bp, st, cracked));
            }
            return;
        }
        // Glass shatters.
        if (st.getSoundType() == SoundType.GLASS) {
            if (random.nextFloat() < 0.05f * tremor * chanceScale * intensity) {
                DisasterManager.blockQueue(level).add(l -> shatter(bp, st));
            }
            return;
        }
        if (hardness > maxHardness) return;

        BlockState below = level.getBlockState(bp.below());
        if (!solid(below)) {
            int neighbours = 0;
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                if (solid(level.getBlockState(bp.relative(dir)))) neighbours++;
            }
            if (neighbours < neighboursNeeded() && random.nextFloat() < 0.5f * chanceScale) {
                DisasterManager.blockQueue(level).add(l -> collapse(bp, st, Vec3.ZERO));
            }
            return;
        }

        // Walls shed their top courses: loose blocks with a free side get thrown off.
        if (intensity >= 3 && !terrain(st) && !solid(level.getBlockState(bp.above()))
                && random.nextFloat() < 0.04f * (intensity - 2) * tremor * chanceScale) {
            List<Direction> free = new ArrayList<>(4);
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                if (!solid(level.getBlockState(bp.relative(dir)))) free.add(dir);
            }
            if (!free.isEmpty()) {
                Direction dir = free.get(random.nextInt(free.size()));
                Vec3 push = new Vec3(dir.getStepX() * 0.18, 0.12, dir.getStepZ() * 0.18);
                DisasterManager.blockQueue(level).add(l -> collapse(bp, st, push));
            }
        }
    }

    private static BlockState crackedVariant(BlockState st) {
        if (st.is(Blocks.STONE_BRICKS)) return Blocks.CRACKED_STONE_BRICKS.defaultBlockState();
        if (st.is(Blocks.DEEPSLATE_BRICKS)) return Blocks.CRACKED_DEEPSLATE_BRICKS.defaultBlockState();
        if (st.is(Blocks.DEEPSLATE_TILES)) return Blocks.CRACKED_DEEPSLATE_TILES.defaultBlockState();
        if (st.is(Blocks.NETHER_BRICKS)) return Blocks.CRACKED_NETHER_BRICKS.defaultBlockState();
        if (st.is(Blocks.POLISHED_BLACKSTONE_BRICKS)) return Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS.defaultBlockState();
        return null;
    }

    private void replace(BlockPos bp, BlockState expected, BlockState to) {
        if (isFinished() || level.getBlockState(bp) != expected) return;
        if (!DisasterProtection.canModify(level, bp, expected)) return;
        level.setBlock(bp, to, 3);
        level.levelEvent(2001, bp, Block.getId(expected));
    }

    private void shatter(BlockPos bp, BlockState expected) {
        if (isFinished() || level.getBlockState(bp) != expected) return;
        if (!DisasterProtection.canModify(level, bp, expected)) return;
        level.destroyBlock(bp, false);
        queueNeighbours(bp);
    }

    private void collapse(BlockPos bp, BlockState expected, Vec3 push) {
        if (isFinished() || level.getBlockState(bp) != expected) return;
        if (!DisasterProtection.canModify(level, bp, expected)) return;
        debris.removeIf(fb -> fb.isRemoved() || fb.time > 200);
        if (debris.size() >= maxDebris) {
            queueCheck(bp); // try again once some debris has landed
            return;
        }
        FallingBlockEntity fb = FallingBlockEntity.fall(level, bp, expected);
        fb.setHurtsEntities(0.8f + intensity * 0.3f, 6 + intensity * 3);
        if (push != Vec3.ZERO) {
            fb.setDeltaMovement(push.x + (random.nextDouble() - 0.5) * 0.05, push.y, push.z + (random.nextDouble() - 0.5) * 0.05);
            fb.hurtMarked = true;
        }
        debris.add(fb);
        queueNeighbours(bp);
    }

    private void queueNeighbours(BlockPos bp) {
        queueCheck(bp.above());
        for (Direction dir : Direction.Plane.HORIZONTAL) queueCheck(bp.relative(dir));
    }

    // ------------------------------------------------------------------ util

    private void broadcast(Component message, double range) {
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(pos.x, p.getY(), pos.z) < range * range) p.sendOverlayMessage(message);
        }
    }

    public int fissureCount() {
        return fissures.size();
    }
}
