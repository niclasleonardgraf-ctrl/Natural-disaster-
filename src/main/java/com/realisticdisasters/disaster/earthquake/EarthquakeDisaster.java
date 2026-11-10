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
import net.minecraft.world.level.block.LeavesBlock;
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
    /**
     * D_ROLL: low-frequency surface-wave motion 0..1 (the "rolling" felt far away and late in the shock);
     * D_DUST: how much dust collapses and rockfalls have thrown up 0..1 (lingers and settles slowly).
     */
    public static final int D_ROLL = D_TIPS + MAX_SYNCED_TIPS * 2, D_DUST = D_ROLL + 1;

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

    private final EarthquakePhysics physics;
    private float tremor;
    private float roll;
    private float dust;
    private float noise, noiseTarget;
    private int noiseTimer;
    private int shockAge = 1000;
    private int nextShock;
    private float shockAmp;
    private int lastCrack = -1000;

    /** One crack in the ground that grows step by step from its origin. */
    private static final class Fissure {
        double x, z, heading;
        int step;
        final int steps;
        /** 1 for a main rupture, smaller for the short en-echelon cracks that branch off it. */
        final float scale;
        /** Which side drops (fault scarp): -1 / +1, and by how many blocks (0 = no vertical offset). */
        final int throwSide, throwBlocks;
        int nextKink;

        Fissure(double x, double z, double heading, int steps, float scale, int throwSide, int throwBlocks) {
            this.x = x;
            this.z = z;
            this.heading = heading;
            this.steps = steps;
            this.scale = scale;
            this.throwSide = throwSide;
            this.throwBlocks = throwBlocks;
            this.nextKink = 2;
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
        this.physics = new EarthquakePhysics(this, level, random, this.intensity);

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
        float[] full = new float[D_DUST + 1];
        System.arraycopy(data, 0, full, 0, data.length);
        full[D_ROLL] = roll;
        full[D_DUST] = dust;
        return full;
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
        physics.clearStagger(true, java.util.Set.of());
        physics.releaseTopples();
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
        if (age() % 2 == 0) {
            if (tremor > 0.08f) shakeEntities();
            else physics.clearStagger(true, java.util.Set.of());
        }

        if (DisasterConfig.BLOCK_DESTRUCTION.getAsBoolean()) {
            if (tremor > 0.25f) {
                crumble();
                physics.scanNearPlayers(radius);
            }
            growFissures();
            physics.tickSlides();
            physics.tickTopples();
            processSupportChecks();
            sweepCanopies();
        }
        if (age() % 20 == 0) debris.removeIf(fb -> fb.isRemoved() || fb.time > 200);
        dust *= 0.9985f; // thrown-up dust lingers for half a minute or so, then settles
    }

    public float dust() {
        return dust;
    }

    public float roll() {
        return roll;
    }

    void addDust(float amount) {
        dust = Math.min(1f, dust + amount);
    }

    // ------------------------------------------------------------------ ground motion

    /**
     * Ground motion modelled on real records: small foreshocks during the warning; the main shock starts with a sharp
     * P-wave jolt, the stronger S-wave shaking arrives a few seconds later, and long-period surface waves ("rolling")
     * build up behind it and outlast it. Aftershocks decay through the aftermath. Shaking is irregular (smoothed
     * random noise), not a regular oscillation.
     */
    private void updateTremor() {
        float p = phaseProgress();
        // Irregular amplitude envelope: a new random target every few ticks, glided toward.
        if (--noiseTimer <= 0) {
            noiseTarget = random.nextFloat();
            noiseTimer = 3 + random.nextInt(9);
        }
        noise += (noiseTarget - noise) * 0.25f;

        float base, rollTarget;
        switch (phase()) {
            case WARNING -> {
                base = 0.03f * smooth(p);
                rollTarget = 0f;
            }
            case BUILDUP -> {
                // P-wave: a sharp, short jolt right at the start of the main shock.
                float pw = p < 0.12f ? (float) Math.sin(Math.PI * p / 0.12f) : 0f;
                // S-wave: arrives a few seconds later and builds fast.
                float sw = smooth((p - 0.25f) / 0.35f);
                base = Math.max(0.35f * pw + 0.05f, (0.45f + 0.4f * noise) * sw);
                rollTarget = smooth((p - 0.45f) / 0.55f) * 0.8f;
            }
            case PEAK -> {
                // Strong motion slowly loses its sharpness toward the end; rolling persists.
                float fade = 1f - 0.25f * smooth((p - 0.7f) / 0.3f);
                base = (0.6f + 0.4f * noise) * fade;
                rollTarget = 0.75f + 0.25f * noise;
            }
            default -> {
                base = 0.5f * (1f - smooth(Math.min(1f, p * 3f))) * (0.6f + 0.4f * noise);
                rollTarget = 0.7f * (1f - smooth(Math.min(1f, p * 1.6f)));
            }
        }
        roll += (rollTarget - roll) * 0.03f;

        // Discrete shocks: foreshocks in the warning, jolts in the peak, aftershocks afterwards.
        if (--nextShock <= 0) {
            switch (phase()) {
                case WARNING -> {
                    shockAmp = (0.12f + 0.04f * intensity) * (0.6f + 0.4f * random.nextFloat()) * (0.5f + p);
                    nextShock = 90 + random.nextInt(110);
                }
                case BUILDUP, PEAK -> {
                    shockAmp = 0.15f + 0.15f * random.nextFloat();
                    nextShock = 40 + random.nextInt(70);
                }
                case AFTERMATH -> {
                    shockAmp = (0.25f + 0.35f * random.nextFloat()) * (1f - p);
                    nextShock = 70 + random.nextInt(120);
                }
            }
            shockAge = 0;
        }
        shockAge++;
        // Each shock: sharp onset, longer ringing tail.
        float pulse = shockAge < 50 ? shockAmp * (shockAge < 6 ? shockAge / 6f : (float) Math.exp(-(shockAge - 6) / 14.0)) : 0f;
        tremor = Mth.clamp(base + pulse, 0f, 1f);
    }

    private void shakeEntities() {
        AABB box = new AABB(pos.x - radius, pos.y - 24, pos.z - radius, pos.x + radius, pos.y + 40, pos.z + radius);
        DamageSource source = level.damageSources().source(ModRegistries.EARTHQUAKE_DAMAGE);
        java.util.Set<java.util.UUID> staggering = new java.util.HashSet<>();
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box, e -> !e.isSpectator() && e.onGround())) {
            if (e instanceof Player pl && (pl.getAbilities().flying || pl.isCreative())) continue;
            double dx = e.getX() - pos.x, dz = e.getZ() - pos.z;
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d > radius) continue;
            float near = 1f - (float) (d / radius);
            if (e instanceof Player pl) {
                physics.stagger(pl, 0.4f + 0.6f * near);
                staggering.add(pl.getUUID());
            }
            float push = 0.035f * tremor * (0.5f + 0.15f * intensity) * (0.4f + 0.6f * near);
            double hop = tremor > 0.7f && random.nextFloat() < 0.15f * near ? 0.12 + 0.03 * intensity : 0;
            e.push((random.nextDouble() - 0.5) * 2 * push, hop, (random.nextDouble() - 0.5) * 2 * push);
            e.hurtMarked = true;
            // Being thrown about hurts at higher intensities near the epicentre.
            if (intensity >= 3 && tremor > 0.75f && near > 0.55f && age() % 60 == 0) {
                e.hurtServer(level, source, 0.5f * (intensity - 2));
            }
        }
        physics.clearStagger(false, staggering);
        physics.shakeObjects(box, radius, age() % 20 == 0);
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
            int throwBlocks = intensity >= 5 ? 1 + random.nextInt(2) : intensity >= 3 ? 1 : 0;
            fissures.add(new Fissure(pos.x + Math.cos(a) * off, pos.z + Math.sin(a) * off,
                    random.nextDouble() * Math.PI * 2, fissureSteps, 1f, random.nextBoolean() ? 1 : -1, throwBlocks));
        }

        int interval = Math.max(2, 9 - intensity);
        boolean due = age() % interval == 0;
        BlockChangeQueue queue = DisasterManager.blockQueue(level);
        List<Fissure> branches = new ArrayList<>();
        for (Fissure f : fissures) {
            if (f.done()) continue;
            // The main rupture races across the ground as the strong shaking arrives (several steps per tick),
            // then the cracks keep working open more slowly.
            boolean racing = f == fissures.get(0) && f.step < f.steps * 0.6f;
            int steps = racing ? 1 + intensity / 2 : due ? 1 : 0;
            for (int k = 0; k < steps && !f.done(); k++) {
                // Real ruptures zig-zag: mostly straight runs with sudden kinks, plus small wander.
                f.heading += (random.nextDouble() - 0.5) * 0.18;
                if (--f.nextKink <= 0) {
                    f.heading += (random.nextBoolean() ? 1 : -1) * (0.35 + random.nextDouble() * 0.45);
                    f.nextKink = 2 + random.nextInt(4);
                }
                f.x += Math.cos(f.heading);
                f.z += Math.sin(f.heading);
                float along = (f.step + 0.5f) / f.steps;
                f.step++;
                // Widest in the middle, tapering to a hairline at both ends.
                float taper = (float) Math.sin(Math.PI * along);
                float half = fissureHalfWidth * f.scale * (0.35f + 0.65f * taper) * (0.75f + 0.5f * random.nextFloat());
                int depth = Math.max(1, Math.round(fissureDepth * f.scale * (0.4f + 0.6f * taper) * (0.7f + 0.6f * random.nextFloat())));
                carveStep(queue, f, half, depth, taper);
                lastCrack = age();
                // En-echelon cracks: short, narrow fractures splaying off the main rupture at an angle.
                if (f.scale >= 1f && random.nextFloat() < 0.14f && fissures.size() + branches.size() < maxFissures * 6) {
                    double side = random.nextBoolean() ? 1 : -1;
                    branches.add(new Fissure(f.x, f.z, f.heading + side * (0.5 + random.nextDouble() * 0.5),
                            3 + random.nextInt(4), 0.45f, 0, 0));
                }
            }
        }
        fissures.addAll(branches);
    }

    /**
     * One batched step: every column across the fissure at its tip becomes one queued change. Columns just outside the
     * crack may slump into it (crumbling edges), and on the down-thrown side the ground drops to form a scarp.
     */
    private void carveStep(BlockChangeQueue queue, Fissure f, float half, int depth, float taper) {
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
        // Crumbling edges: rim blocks break off and slide into the crack.
        for (int side = -1; side <= 1; side += 2) {
            if (random.nextFloat() > 0.35f * taper * f.scale) continue;
            double o = side * (half + 1.0);
            int x = Mth.floor(f.x + px * o), z = Mth.floor(f.z + pz * o);
            if (columns.contains(BlockPos.asLong(x, 0, z))) continue;
            Vec3 push = new Vec3(-px * side * 0.12, 0.05, -pz * side * 0.12);
            queue.add(l -> slough(x, z, push));
        }
        // Fault scarp: the down-thrown side drops a block or two along the crack.
        if (f.throwBlocks > 0 && taper > 0.25f) {
            for (double o = half + 0.5; o <= half + 3.0; o += 0.5) {
                double oo = f.throwSide * o;
                int x = Mth.floor(f.x + px * oo), z = Mth.floor(f.z + pz * oo);
                if (columns.add(BlockPos.asLong(x, 0, z))) {
                    int drop = taper > 0.6f ? f.throwBlocks : 1;
                    queue.add(l -> dropColumn(x, z, drop));
                }
            }
        }
    }

    /** The top ground block of a rim column breaks off and slides into the open crack. */
    private void slough(int x, int z, Vec3 push) {
        if (isFinished() || !level.isLoaded(new BlockPos(x, 0, z))) return;
        int ground = groundLevel(x, z);
        BlockPos bp = new BlockPos(x, ground, z);
        BlockState st = level.getBlockState(bp);
        if (!terrain(st) || st.hasBlockEntity()) return;
        // Only if there really is an opening next to it.
        BlockPos into = BlockPos.containing(x + 0.5 + push.x * 8, ground, z + 0.5 + push.z * 8);
        if (solid(level.getBlockState(into))) return;
        collapse(bp, st, push);
    }

    /** Lowers the ground surface of a column by {@code drop} blocks (the top block moves down, keeping grass on top). */
    private void dropColumn(int x, int z, int drop) {
        if (isFinished() || !level.isLoaded(new BlockPos(x, 0, z))) return;
        int ground = groundLevel(x, z);
        if (Math.abs(ground - pos.y) > 24) return;
        BlockPos top = new BlockPos(x, ground, z);
        BlockState topState = level.getBlockState(top);
        if (!terrain(topState)) return;
        for (int k = 0; k <= drop; k++) {
            BlockPos bp = top.below(k);
            BlockState st = level.getBlockState(bp);
            if (!terrain(st) || !DisasterProtection.canModify(level, bp, st)) return;
        }
        BlockPos above = top.above();
        if (!level.getBlockState(above).isAir() && level.getBlockState(above).canBeReplaced()) level.destroyBlock(above, false);
        for (int k = 0; k < drop; k++) level.setBlock(top.below(k), Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(top.below(drop), topState, 3);
        queueCheck(above);
        for (Direction dir : Direction.Plane.HORIZONTAL) queueCheck(above.relative(dir));
    }

    private void carveColumn(int x, int z, int depth) {
        if (isFinished()) return;
        BlockPos column = new BlockPos(x, 0, z);
        if (!level.isLoaded(column)) return;
        int ground = groundLevel(x, z);
        // Don't tear up mountainsides far above / below the epicentre: the fault runs through the ground near it.
        if (Math.abs(ground - pos.y) > 24) return;
        boolean first = true;
        int bottom = Integer.MAX_VALUE;
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
                addDust(0.0012f);
                first = false;
            }
            level.setBlock(bp, Blocks.AIR.defaultBlockState(), 3);
            bottom = y;
        }
        // Groundwater: in low-lying ground, the bottom of the crack floods.
        if (bottom <= level.getSeaLevel() && ground <= level.getSeaLevel() + 4 && random.nextFloat() < 0.6f) {
            BlockPos bp = new BlockPos(x, bottom, z);
            if (level.getBlockState(bp).isAir()) level.setBlock(bp, Blocks.WATER.defaultBlockState(), 3);
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
            BlockState topState = level.getBlockState(new BlockPos(x, top, z));
            if (terrain(topState)) physics.considerSlope(x, z, top, falloff);
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

    void queueCheck(BlockPos bp) {
        if (pendingSet.size() >= MAX_PENDING_CHECKS || !pendingSet.add(bp)) return;
        pendingChecks.add(bp);
    }

    /** A log with leaves above it (a living tree rather than a log beam in a building). */
    private boolean hasCanopy(BlockPos log) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int y = 1; y <= 10; y++) {
            BlockState st = level.getBlockState(m.set(log.getX(), log.getY() + y, log.getZ()));
            if (st.is(BlockTags.LEAVES)) return true;
            if (!st.is(BlockTags.LOGS)) return false;
        }
        return false;
    }

    static boolean solid(BlockState st) {
        return !st.isAir() && !st.canBeReplaced() && st.getFluidState().isEmpty();
    }

    static boolean terrain(BlockState st) {
        return st.is(BlockTags.DIRT) || st.is(BlockTags.SAND) || st.is(BlockTags.BASE_STONE_OVERWORLD)
                || st.is(Blocks.GRAVEL) || st.is(BlockTags.SNOW) || st.is(BlockTags.TERRACOTTA)
                || st.is(Blocks.CLAY) || st.is(Blocks.SANDSTONE) || st.is(Blocks.RED_SANDSTONE);
    }

    void evaluate(BlockPos bp, float chanceScale) {
        BlockState st = level.getBlockState(bp);
        if (st.isAir()) return;
        if (physics.reactFixture(bp, st, chanceScale)) return;
        if (!solid(st) || st.hasBlockEntity() || st.is(BlockTags.LEAVES)) return;
        if (st.getCollisionShape(level, bp).isEmpty()) return;
        float hardness = st.getDestroySpeed(level, bp);
        if (hardness < 0) return;

        // Masonry cracks before it fails.
        BlockState cracked = crackedVariant(st);
        if (cracked != null) {
            if (random.nextFloat() < 0.2f * tremor * chanceScale * intensity) {
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
            // An undermined tree goes over as a whole instead of dissolving log by log.
            if (st.is(BlockTags.LOGS) && hasCanopy(bp)) {
                if (random.nextFloat() < 0.5f * chanceScale) physics.toppleTree(bp);
                return;
            }
            // Load path: is something on the ground within this material's (shaking-reduced) span?
            int span = physics.effectiveSpan(st, hardness);
            if (!physics.hasLoadPath(bp, span) && random.nextFloat() < 0.6f * chanceScale) {
                DisasterManager.blockQueue(level).add(l -> collapse(bp, st, Vec3.ZERO));
            }
            return;
        }

        // Top of a free-standing column (chimney, tower, pillar): it may snap and topple as one piece.
        if (!terrain(st) && !solid(level.getBlockState(bp.above())) && physics.considerTopple(bp, chanceScale)) return;

        // Walls shed their top courses: loose blocks with a free side get thrown off. Only real wall tops (sitting on
        // more masonry / wood, not rubble lying on the ground).
        if (intensity >= 3 && !terrain(st) && !terrain(below) && !solid(level.getBlockState(bp.above()))
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

    float maxHardness() {
        return maxHardness;
    }

    void trackDebris(FallingBlockEntity fb) {
        debris.add(fb);
    }

    void collapse(BlockPos bp, BlockState expected, Vec3 push) {
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
        addDust(terrain(expected) ? 0.003f : 0.005f);
        queueNeighbours(bp);
        // A falling trunk brings its canopy down too (checked once vanilla has updated the leaves' log distance).
        if (expected.is(BlockTags.LOGS) && canopySweeps.size() < 64) canopySweeps.add(new long[]{bp.asLong(), age() + 30});
    }

    /** Pending canopy sweeps: {block pos, due age}. */
    private final List<long[]> canopySweeps = new ArrayList<>();

    /**
     * Natural leaves that no longer connect to any log (vanilla's leaf DISTANCE reached 7) come down right away
     * instead of floating in the air until random-tick decay gets to them minutes later.
     */
    private void sweepCanopies() {
        if (canopySweeps.isEmpty()) return;
        var it = canopySweeps.iterator();
        while (it.hasNext()) {
            long[] sweep = it.next();
            if (sweep[1] > age()) continue;
            it.remove();
            BlockPos c = BlockPos.of(sweep[0]);
            BlockChangeQueue queue = DisasterManager.blockQueue(level);
            for (BlockPos p : BlockPos.betweenClosed(c.offset(-4, -2, -4), c.offset(4, 8, 4))) {
                if (!level.isLoaded(p)) continue;
                BlockState st = level.getBlockState(p);
                if (!st.is(BlockTags.LEAVES) || !st.hasProperty(LeavesBlock.PERSISTENT) || st.getValue(LeavesBlock.PERSISTENT)
                        || !st.hasProperty(LeavesBlock.DISTANCE) || st.getValue(LeavesBlock.DISTANCE) < LeavesBlock.DECAY_DISTANCE) continue;
                BlockPos at = p.immutable();
                queue.add(l -> {
                    BlockState now = level.getBlockState(at);
                    if (isFinished() || now != st || !DisasterProtection.canModify(level, at, now)) return;
                    level.destroyBlock(at, true); // leaf particles + the usual sapling / stick drops
                });
            }
        }
    }

    void queueNeighbours(BlockPos bp) {
        queueCheck(bp.above());
        for (Direction dir : Direction.Plane.HORIZONTAL) queueCheck(bp.relative(dir));
    }

    // ------------------------------------------------------------------ util

    private void broadcast(Component message, double range) {
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(pos.x, p.getY(), pos.z) < range * range) p.sendOverlayMessage(message);
        }
    }

    /** Topples the free-standing structure whose top block is {@code top} immediately (GameTests / admin use). */
    public boolean toppleNow(BlockPos top, Vec3 direction) {
        return physics.toppleNow(top, direction);
    }

    /** Lets the current shaking act on one block, as if the sampler had picked it (GameTests use). */
    public void shakeBlock(BlockPos pos, float chanceScale) {
        if (DisasterConfig.BLOCK_DESTRUCTION.getAsBoolean()) evaluate(pos, chanceScale);
    }

    public int fissureCount() {
        return fissures.size();
    }
}
