package com.realisticdisasters.disaster.tsunami;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.realisticdisasters.config.DisasterConfig;
import com.realisticdisasters.disaster.BlockChangeQueue;
import com.realisticdisasters.disaster.Disaster;
import com.realisticdisasters.disaster.DisasterManager;
import com.realisticdisasters.disaster.DisasterProtection;
import com.realisticdisasters.disaster.DisasterType;
import com.realisticdisasters.disaster.Phase;
import com.realisticdisasters.registry.ModRegistries;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A tsunami making landfall on a shoreline.
 * <ul>
 *   <li><b>Warning:</b> the sea draws back from the shore (top water layers near the beach are removed and remembered).</li>
 *   <li><b>Buildup:</b> a wave wall crosses the sea toward the shore, refilling the drawn-back water as it passes.</li>
 *   <li><b>Peak:</b> the wave runs up inland, pushing entities, tearing out weak blocks and flooding low ground.</li>
 *   <li><b>Aftermath:</b> the flood drains back, removing exactly the water this tsunami placed. Debris and erosion stay.</li>
 * </ul>
 * Geometry uses a local frame: {@code axial} = distance inland from the landfall point (negative = out at sea),
 * {@code lateral} = distance along the shoreline. All block work goes through {@link DisasterManager#blockQueue}.
 */
public class TsunamiDisaster extends Disaster {
    /** Indices into the sync data array (shared contract with the client visual). */
    public static final int D_DIR_X = 0, D_DIR_Z = 1, D_FRONT = 2, D_HALF_WIDTH = 3, D_CREST = 4, D_WATER_Y = 5,
            D_REACH = 6, D_SEA_DIST = 7, D_DRAWBACK = 8, D_WALL = 9,
            D_SURGE = 10, D_SURGE_WALL = 11, D_RISE = 12;

    /**
     * Flags for every flood block change: tell clients, but skip neighbour shape updates and onPlace. The placed water
     * therefore never schedules a fluid tick, so it neither spreads nor makes the sea around it flow, and removing
     * it later leaves no stray flowing water.
     */
    static final int STATIC_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SKIP_ON_PLACE;
    /** Max of this tsunami's own changes waiting in the shared queue; the rest wait in {@link #pending}. */
    private static final int FEED_LIMIT = 192;
    /** Share of the peak used by the run-up; the flood then holds. */
    private static final float RUNUP = 0.45f;

    private final float halfWidth, reach, seaDist, crestMax, floodHeight, drawDist;
    private final int drawDepth, maxWater, aftermathTicks;
    private double dirX, dirZ; // unit vector from the shore toward the sea
    private int waterY;        // y of the sea's surface block
    private boolean hasSea, shoreFound;
    private float floodStart; // axial where flooding begins: the shoreline, or the wave's origin for an inland flood

    private final ArrayDeque<BlockChangeQueue.Change> pending = new ArrayDeque<>();
    private final LongOpenHashSet placed = new LongOpenHashSet();
    private final LongOpenHashSet withdrawn = new LongOpenHashSet();
    private final List<Cell> withdrawnOrder = new ArrayList<>();
    private final LongOpenHashSet drainCheck = new LongOpenHashSet();
    /** Cells that were dry before the flood touched them (flooded cells and their side neighbours). */
    private final LongOpenHashSet dryCells = new LongOpenHashSet();
    private BoundingBox floodBox;
    private int sweepPasses;
    private List<Cell> drawColumns;
    private List<Cell> floodColumns;
    private int drawIndex, restoreIndex, floodIndex;
    private boolean drawbackOver, receding;
    private int recedeRate = 8;
    private float front, prevFront, frontSpeed, prevSurge, surgeSpeed;
    private final FloodDebris debris;
    private AABB region;

    /** A column or block in the local frame. */
    private record Cell(long pos, float axial, float lateral) {}

    /** A flooded column: water is added from {@code filled + 1} up to the rising target over time. */
    private static final class FloodCol {
        final int x, z, start, full;
        final float force;
        int filled;

        FloodCol(int x, int z, int start, int full, float force) {
            this.x = x;
            this.z = z;
            this.start = start;
            this.full = full;
            this.force = force;
            this.filled = start - 1;
        }
    }

    private final List<FloodCol> wet = new ArrayList<>();
    private int raiseCursor;
    private boolean fishSpawned;

    public TsunamiDisaster(ServerLevel level, int id, Vec3 pos, int intensity) {
        super(DisasterType.TSUNAMI, level, id, pos, intensity);
        int i = this.intensity;
        this.halfWidth = 14 + 6 * i;            // 20 .. 44 blocks of coast
        this.reach = 18 + 10 * i;               // 28 .. 68 blocks inland
        this.seaDist = 40 + 8 * i;              // the wave appears 48 .. 80 blocks out
        // Real tsunamis arrive as a turbulent bore / fast-rising flood, not a towering curl.
        this.crestMax = 2.5f + 1.5f * i;        // 4 .. 10 blocks of churning white water above the sea
        this.floodHeight = 2 + i;               // 3 .. 7 blocks of water at the shore
        this.drawDist = 14 + 4 * i;             // 18 .. 34 blocks of seabed exposed
        this.drawDepth = 1 + i / 2;             // 1 .. 3 layers
        this.maxWater = DisasterConfig.TSUNAMI_MAX_WATER_BLOCKS.getAsInt();
        locateShore();
        this.front = -seaDist;
        this.floodStart = hasSea ? 0f : -seaDist;
        this.prevFront = front;
        this.debris = new FloodDebris(this, level, random, 12 + 8 * i);

        double scale = DisasterConfig.TSUNAMI_DURATION_SCALE.get();
        setDuration(Phase.WARNING, (int) (600 * scale));
        setDuration(Phase.BUILDUP, (int) ((180 + 10 * i) * scale));
        setDuration(Phase.PEAK, (int) ((700 + 100 * i) * scale));
        this.aftermathTicks = (int) ((500 + 60 * i) * scale);
        setDuration(Phase.AFTERMATH, aftermathTicks);
    }

    @Override
    public Component describe() {
        return Component.translatable("disaster.realisticdisasters.tsunami");
    }

    @Override
    public float effectRadius() {
        return Math.max(seaDist, reach) + halfWidth;
    }

    // ------------------------------------------------------------------ geometry

    public double axialOf(double x, double z) {
        return -(x - pos.x) * dirX - (z - pos.z) * dirZ;
    }

    public double lateralOf(double x, double z) {
        return -(x - pos.x) * dirZ + (z - pos.z) * dirX;
    }

    private double worldX(double axial, double lateral) {
        return pos.x - dirX * axial - dirZ * lateral;
    }

    private double worldZ(double axial, double lateral) {
        return pos.z - dirZ * axial + dirX * lateral;
    }

    /** 0..1 how strongly the breaking wave wall is present. */
    public float wall() {
        return switch (phase()) {
            case WARNING -> 0f;
            case BUILDUP -> 0.35f + 0.65f * smooth(phaseProgress());
            case PEAK -> {
                float q = Math.min(1f, phaseProgress() / RUNUP);
                yield 1f - q * q * q;
            }
            case AFTERMATH -> 0f;
        };
    }

    /** Height of the wave crest above the sea surface where the front is now. */
    public float crest() {
        if (front < 0) return crestMax * (0.45f + 0.55f * (1f + front / seaDist));
        return 1f + (crestMax - 1f) * (float) Math.pow(Math.max(0f, 1f - front / reach), 0.7);
    }

    /** 0..1 how far the flood has risen toward its full height (the water keeps rising after the bore passes). */
    public float rise() {
        return switch (phase()) {
            case WARNING -> 0f;
            case BUILDUP -> 0.55f;
            case PEAK -> 0.55f + 0.45f * smooth(phaseProgress() / 0.8f);
            case AFTERMATH -> 1f - phaseProgress();
        };
    }

    /** Second, smaller surge that rolls in over the flood (tsunamis come as a train of waves). */
    private float surgeProgress() {
        return phase() == Phase.PEAK ? Mth.clamp((phaseProgress() - 0.5f) / 0.3f, 0f, 1f) : 0f;
    }

    public float surgeFront() {
        float q = surgeProgress();
        return reach * 0.8f * (1f - (1f - q) * (1f - q));
    }

    public float surgeWall() {
        float q = surgeProgress();
        return q <= 0f || q >= 1f ? 0f : 0.55f * Math.min(1f, q * 8f) * (1f - q * q * q);
    }

    private float drawback() {
        return switch (phase()) {
            case WARNING -> smooth((phaseProgress() - 0.1f) / 0.6f);
            case BUILDUP -> 1f - phaseProgress();
            default -> 0f;
        };
    }

    /** Target water surface (block y) of the flood at a column. */
    private int floodSurface(float axial, float lateral) {
        if (axial < floodStart || axial > reach) return waterY;
        float f = (float) Math.pow(1f - (axial - floodStart) / (reach - floodStart), 0.8);
        float g = 1f - (float) Math.pow(Math.min(1f, Math.abs(lateral) / halfWidth), 4);
        return waterY + Math.round(floodHeight * f * g);
    }

    private void updateFront() {
        float p = phaseProgress();
        front = switch (phase()) {
            case WARNING -> -seaDist;
            case BUILDUP -> -seaDist * (1f - p);
            case PEAK -> {
                float q = Math.min(1f, p / RUNUP);
                yield reach * (1f - (1f - q) * (1f - q));
            }
            case AFTERMATH -> reach * (1f - p);
        };
        frontSpeed = Math.max(0f, front - prevFront);
        prevFront = front;
        float sf = surgeFront();
        surgeSpeed = Math.max(0f, sf - prevSurge);
        prevSurge = sf;
    }

    /**
     * Force of the flow on whatever stands in it at this distance inland (see {@link FloodForces}): momentum flux,
     * i.e. depth x velocity^2, strongest where the bore first hits the coast and fading as it spreads inland.
     */
    private float flowForce(float axial) {
        float inland = Mth.clamp((axial - floodStart) / (reach - floodStart), 0f, 1f);
        return (0.6f + 0.55f * intensity) * (float) Math.pow(1f - inland, 0.6);
    }

    /**
     * Depth-averaged current at a point (blocks/tick, horizontal). The bore drives water inland at about its own
     * speed, slowing behind it; at high water it goes slack; the second surge pushes again; then the backwash pours
     * back out to sea, fastest early on and near the shore.
     */
    public Vec3 flowAt(double x, double z) {
        double a = axialOf(x, z);
        if (Math.abs(lateralOf(x, z)) > halfWidth + 4 || a > reach + 4) return Vec3.ZERO;
        double u = 0; // + = inland
        switch (phase()) {
            case WARNING -> u = a < 0 && a > -drawDist * 1.5 ? -0.03 * drawback() : 0;
            case BUILDUP, PEAK -> {
                u = bore(a, front, frontSpeed, wall());
                u = Math.max(u, bore(a, surgeFront(), surgeSpeed, surgeWall()));
                if (phase() == Phase.PEAK && a < front && u < 0.02) u = 0.02; // slack, still creeping inland
            }
            case AFTERMATH -> {
                double p = phaseProgress();
                double nearShore = 0.6 + 0.4 * (1 - Mth.clamp(a / reach, 0, 1));
                u = a < reach ? -(0.12 + 0.035 * intensity) * Math.pow(1 - p, 0.6) * nearShore : 0;
            }
        }
        return new Vec3(-dirX * u, 0, -dirZ * u);
    }

    private static double bore(double a, float front, float speed, float wall) {
        if (wall <= 0.02f || a > front + 1.5) return 0;
        double behind = front - a;
        double drive = speed * 1.2 + 0.08;
        return behind < 14 ? drive * wall * (1 - behind / 22) : drive * wall * 0.35;
    }

    @Override
    protected float[] syncData() {
        return new float[]{(float) dirX, (float) dirZ, front, halfWidth, crest(), waterY, reach, seaDist, drawback(), wall(),
                surgeFront(), surgeWall(), rise()};
    }

    // ------------------------------------------------------------------ shore detection

    /** Picks the direction with the most open water, then slides the landfall point onto the shoreline. */
    private void locateShore() {
        int cx = Mth.floor(pos.x), cz = Mth.floor(pos.z);
        double[] scores = new double[16];
        double bestScore = 0;
        for (int k = 0; k < 16; k++) {
            double a = k * Math.PI / 8;
            for (int r = 3; r <= 72; r += 3) {
                int x = cx + (int) Math.round(Math.cos(a) * r), z = cz + (int) Math.round(Math.sin(a) * r);
                if (!level.isLoaded(new BlockPos(x, 0, z))) break;
                if (surfaceIsWater(x, z)) scores[k] += 1 + r / 72.0; // far water weighs more: open sea, not a puddle
            }
            bestScore = Math.max(bestScore, scores[k]);
        }
        // Average the strong directions so a straight coast faces the sea squarely.
        double sx = 0, sz = 0;
        for (int k = 0; k < 16; k++) {
            if (scores[k] < bestScore * 0.6) continue;
            sx += Math.cos(k * Math.PI / 8) * scores[k];
            sz += Math.sin(k * Math.PI / 8) * scores[k];
        }
        double len = Math.sqrt(sx * sx + sz * sz);
        double bestAngle = len > 1.0e-3 ? Math.atan2(sz, sx) : random.nextDouble() * Math.PI * 2;
        if (len <= 1.0e-3 && bestScore > 0) {
            for (int k = 0; k < 16; k++) if (scores[k] == bestScore) bestAngle = k * Math.PI / 8;
        }
        dirX = Math.cos(bestAngle);
        dirZ = Math.sin(bestAngle);
        hasSea = bestScore >= 3;
        waterY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx, cz) - 1;
        if (!hasSea) {
            // Inland (command-started away from the coast): a flash flood rolls in from a random direction.
            pos = new Vec3(cx + 0.5, waterY + 1, cz + 0.5);
            return;
        }
        boolean startWet = surfaceIsWater(cx, cz);
        int shoreX = cx, shoreZ = cz;
        int prevX = cx, prevZ = cz;
        for (int s = 0; s <= 96; s++) {
            double d = startWet ? -s : s;
            int x = Mth.floor(pos.x + dirX * d), z = Mth.floor(pos.z + dirZ * d);
            if (!level.isLoaded(new BlockPos(x, 0, z))) break;
            boolean wet = surfaceIsWater(x, z);
            if (startWet && !wet) {
                shoreFound = true;
                shoreX = x;
                shoreZ = z;
                waterY = level.getHeight(Heightmap.Types.WORLD_SURFACE, prevX, prevZ) - 1;
                break;
            }
            if (!startWet && wet) {
                shoreFound = true;
                shoreX = prevX;
                shoreZ = prevZ;
                waterY = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
                break;
            }
            prevX = x;
            prevZ = z;
        }
        pos = new Vec3(shoreX + 0.5, waterY + 1, shoreZ + 0.5);
    }

    private boolean surfaceIsWater(int x, int z) {
        int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        return level.getFluidState(new BlockPos(x, top, z)).is(FluidTags.WATER);
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onPhaseChange(Phase from, Phase to) {
        if (to == Phase.BUILDUP) {
            broadcast(Component.translatable("disaster.realisticdisasters.tsunami.wave").withStyle(ChatFormatting.RED), 260);
        }
        if (from == Phase.WARNING) {
            drawbackOver = true;
            withdrawnOrder.sort(Comparator.comparingDouble(Cell::axial)); // seaward-most refills first
        }
        if (to == Phase.AFTERMATH) {
            startRecession();
        }
    }

    @Override
    protected void onEnd() {
        if (!receding) startRecession();
        debris.finish();
        clearFloodTicks();
        // Hand everything still outstanding to the shared queue (it outlives this disaster).
        BlockChangeQueue queue = DisasterManager.blockQueue(level);
        while (!pending.isEmpty()) {
            BlockChangeQueue.Change c = pending.poll();
            if (!queue.add(c)) c.apply(level);
        }
    }

    @Override
    protected void tickDisaster() {
        if (age() == 1) {
            broadcast(Component.translatable("disaster.realisticdisasters.tsunami.warning").withStyle(ChatFormatting.GOLD), 260);
        }
        updateFront();
        boolean blocks = DisasterConfig.BLOCK_DESTRUCTION.getAsBoolean();

        switch (phase()) {
            case WARNING -> {
                if (blocks && shoreFound) withdrawSea();
                if (!fishSpawned && drawback() > 0.6f && withdrawnOrder.size() > 8) strandFish();
            }
            case BUILDUP, PEAK -> {
                refillSea();
                // Open sea with no shoreline in range: the wave passes, there is no low ground to flood.
                if (blocks && (shoreFound || !hasSea)) flood();
            }
            case AFTERMATH -> {}
        }
        // Keep the flood static: water's fluid tick delay is 5, so clearing every 2 ticks means none ever fires.
        if (age() % 2 == 0 && phase() != Phase.WARNING) clearFloodTicks();
        debris.tick();
        feedQueue();

        if (age() % 20 == 0 && (phase() != Phase.AFTERMATH)) alertMobs();
        if (wall() > 0.05f) pushAtFront(front, wall());
        if (surgeWall() > 0.05f) pushAtFront(surgeFront(), surgeWall());
        if (phase() != Phase.WARNING) applyFlow();
    }

    /** Moves this tsunami's work into the shared queue without flooding it: the queue's budget then spreads it over ticks. */
    private void feedQueue() {
        BlockChangeQueue queue = DisasterManager.blockQueue(level);
        int limit = receding ? recedeRate : FEED_LIMIT;
        for (int n = 0; n < limit && !pending.isEmpty() && queue.size() < FEED_LIMIT; n++) {
            if (!queue.add(pending.peek())) break;
            pending.poll();
        }
    }

    private BlockPos blockPos(long packed) {
        return BlockPos.of(packed);
    }

    // ------------------------------------------------------------------ the sea withdraws

    private void withdrawSea() {
        if (drawColumns == null) drawColumns = collectColumns(-drawDist, 0f, halfWidth * 0.9f, true);
        float reachNow = drawDist * drawback();
        while (drawIndex < drawColumns.size() && -drawColumns.get(drawIndex).axial() <= reachNow) {
            Cell col = drawColumns.get(drawIndex++);
            BlockPos c = blockPos(col.pos());
            if (!level.isLoaded(c)) continue;
            // Deepest close to the beach, tapering out to sea.
            int depth = Math.max(1, Math.round(drawDepth * (1f + col.axial() / drawDist * 0.5f)));
            for (int k = 0; k < depth; k++) {
                BlockPos bp = new BlockPos(c.getX(), waterY - k, c.getZ());
                float axial = col.axial();
                pending.add(l -> withdraw(bp, axial));
            }
        }
    }

    private void withdraw(BlockPos bp, float axial) {
        if (drawbackOver || isFinished()) return;
        BlockState st = level.getBlockState(bp);
        if (!st.is(Blocks.WATER) || !st.getFluidState().isSource()) return;
        long above = bp.above().asLong();
        if (!level.getBlockState(bp.above()).isAir() && !withdrawn.contains(above)) return;
        if (!DisasterProtection.canModify(level, bp, st)) return;
        level.setBlock(bp, Blocks.AIR.defaultBlockState(), STATIC_FLAGS);
        withdrawn.add(bp.asLong());
        withdrawnOrder.add(new Cell(bp.asLong(), axial, 0));
    }

    /** Fish left flopping on the exposed seabed: one of the classic warning signs. */
    private void strandFish() {
        fishSpawned = true;
        int n = 2 + intensity;
        for (int k = 0; k < n; k++) {
            BlockPos bp = BlockPos.of(withdrawnOrder.get(random.nextInt(withdrawnOrder.size())).pos());
            if (!level.getBlockState(bp).isAir()) continue;
            var type = switch (random.nextInt(3)) {
                case 0 -> net.minecraft.world.entity.EntityType.SALMON;
                case 1 -> net.minecraft.world.entity.EntityType.TROPICAL_FISH;
                default -> net.minecraft.world.entity.EntityType.COD;
            };
            Entity fish = type.create(level, net.minecraft.world.entity.EntitySpawnReason.EVENT);
            if (fish == null) continue;
            fish.snapTo(bp.getX() + 0.5, bp.getY(), bp.getZ() + 0.5, random.nextFloat() * 360f, 0f);
            level.addFreshEntity(fish);
        }
    }

    /** As the wave passes, water returns where the sea drew back (deepest layer first). */
    private void refillSea() {
        while (restoreIndex < withdrawnOrder.size() && withdrawnOrder.get(restoreIndex).axial() <= front + 1) {
            long p = withdrawnOrder.get(restoreIndex++).pos();
            pending.add(l -> restore(p));
        }
    }

    private void restore(long p) {
        if (!withdrawn.remove(p)) return;
        BlockPos bp = BlockPos.of(p);
        if (level.isLoaded(bp) && level.getBlockState(bp).isAir()) {
            level.setBlock(bp, Blocks.WATER.defaultBlockState(), STATIC_FLAGS);
        }
    }

    // ------------------------------------------------------------------ the flood

    private void flood() {
        if (floodColumns == null) floodColumns = collectColumns(floodStart, reach, halfWidth, false);
        while (floodIndex < floodColumns.size() && floodColumns.get(floodIndex).axial() <= front) {
            floodColumn(floodColumns.get(floodIndex++));
        }
        // The water keeps rising behind the bore: top up a slice of the flooded columns each tick.
        if (!wet.isEmpty()) {
            int n = Math.min(wet.size(), Math.max(24, wet.size() / 30));
            for (int k = 0; k < n; k++) {
                if (raiseCursor >= wet.size()) raiseCursor = 0;
                raise(wet.get(raiseCursor++));
            }
        }
    }

    private void floodColumn(Cell col) {
        BlockPos c = blockPos(col.pos());
        int x = c.getX(), z = c.getZ();
        if (!level.isLoaded(c)) return;
        int surface = floodSurface(col.axial(), col.lateral());
        if (surface <= waterY) return;
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        int start;
        if (top < surface) {
            // Open to the sky below the flood line. Anything standing on the ground (walls, fences, glass, a hut)
            // is inside the water column and takes the flow's force; ground and masonry stop the scan.
            float force = flowForce(col.axial());
            int tree = level.getBlockState(new BlockPos(x, top, z)).is(net.minecraft.tags.BlockTags.LOGS) ? uproot(x, z, top, force) : NOT_A_TREE;
            if (tree == UPROOTED) return;
            start = top + 1;
            while (start - 1 > waterY && surface - start < floodHeight + 2) {
                BlockState below = level.getBlockState(new BlockPos(x, start - 1, z));
                if (FloodForces.resistance(below) == FloodForces.HOLDS) break;
                if (tree == HELD && below.is(net.minecraft.tags.BlockTags.LOGS)) break; // an anchored trunk stands
                start--;
            }
            if (start == top + 1) erode(new BlockPos(x, top, z), col.axial());
        } else {
            // Covered column (roof, wall, tree, hillside). Trees in the run-up zone may be ripped out whole; walls the
            // flow can break are hit; otherwise only an open space at the water line floods (e.g. inside a house).
            float force = flowForce(col.axial());
            BlockState atSurface = level.getBlockState(new BlockPos(x, surface, z));
            if (atSurface.is(net.minecraft.tags.BlockTags.LOGS) && uproot(x, z, surface, force) != NOT_A_TREE) return;
            if (!floodable(atSurface) && !fails(atSurface, force * 1.3f)) return;
            start = surface;
            while (start - 1 > waterY && surface - start < floodHeight + 2) {
                BlockState below = level.getBlockState(new BlockPos(x, start - 1, z));
                if (!floodable(below) && !fails(below, force * 1.3f)) break;
                start--;
            }
        }
        if (surface - start + 1 > floodHeight + 3) return; // a deep pit or the edge of the world: leave it
        FloodCol fc = new FloodCol(x, z, start, surface, flowForce(col.axial()));
        wet.add(fc);
        raise(fc);
    }

    private void raise(FloodCol c) {
        int target = Math.max(c.start, waterY + Math.round((c.full - waterY) * rise()));
        target = Math.min(target, c.full);
        // The bore hits with full force; water that rises later only pushes gently.
        float force = c.filled < c.start ? c.force : c.force * 0.35f;
        for (int y = c.filled + 1; y <= target; y++) {
            BlockPos bp = new BlockPos(c.x, y, c.z);
            pending.add(l -> placeWater(bp, force));
        }
        c.filled = Math.max(c.filled, target);
    }

    private boolean floodable(BlockState st) {
        if (st.isAir()) return true;
        if (!st.getFluidState().isEmpty() || st.hasBlockEntity()) return false;
        return st.canBeReplaced() || !st.blocksMotion();
    }

    /** Soil near the sea is scoured into sand, further inland turf is stripped. Permanent damage. */
    private void erode(BlockPos top, float axial) {
        float chance = 0.1f + 0.4f * (1f - axial / reach);
        if (random.nextFloat() > chance) return;
        boolean nearShore = axial < reach * 0.4f;
        float roll = random.nextFloat();
        pending.add(l -> {
            if (isFinished() || receding) return;
            BlockState st = level.getBlockState(top);
            if (!(st.is(Blocks.GRASS_BLOCK) || st.is(Blocks.PODZOL) || st.is(Blocks.MYCELIUM) || st.is(Blocks.DIRT_PATH)
                    || st.is(Blocks.FARMLAND) || st.is(Blocks.DIRT))) return;
            if (!DisasterProtection.canModify(level, top, st)) return;
            BlockState to = nearShore && roll < 0.55f ? Blocks.SAND.defaultBlockState()
                    : roll < 0.3f ? Blocks.GRAVEL.defaultBlockState()
                    : roll < 0.7f ? Blocks.COARSE_DIRT.defaultBlockState() : Blocks.DIRT.defaultBlockState();
            if (st.is(Blocks.DIRT) && to.is(Blocks.DIRT)) return;
            level.setBlock(top, to, STATIC_FLAGS);
        });
    }

    private boolean fails(BlockState st, float force) {
        float r = FloodForces.resistance(st);
        return r == 0f || r != FloodForces.HOLDS && force * (0.6f + 0.8f * random.nextFloat()) > r;
    }

    private void placeWater(BlockPos bp, float force) {
        if (isFinished() || receding || placed.size() >= maxWater) return;
        BlockState st = level.getBlockState(bp);
        if (!st.isAir()) {
            if (!st.getFluidState().isEmpty() || st.hasBlockEntity()) return; // existing water is never touched
            if (!fails(st, force)) return;
            if (!DisasterProtection.canModify(level, bp, st)) return;
            smash(bp, st);
        } else if (!DisasterProtection.canModify(level, bp, st)) {
            return;
        }
        level.setBlock(bp, Blocks.WATER.defaultBlockState(), STATIC_FLAGS);
        placed.add(bp.asLong());
        // Remember which cells were dry before our water reached them: only those may ever be swept dry again.
        dryCells.add(bp.asLong());
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = bp.relative(d);
            if (level.getFluidState(n).isEmpty()) dryCells.add(n.asLong());
        }
        if (floodBox == null) floodBox = BoundingBox.fromCorners(bp, bp);
        else floodBox.encapsulate(bp);
    }

    /**
     * A block gives way to the water: timber and leaves float off as wreckage, glass shatters, the rest breaks up
     * into drops. A normal block break, so attached things (torches, door halves) come down with it; the resulting
     * neighbour updates can't make the flood flow because its fluid ticks are cleared.
     */
    private void smash(BlockPos bp, BlockState st) {
        Vec3 flow = flowAt(bp.getX() + 0.5, bp.getZ() + 0.5);
        if (FloodForces.floats(st) && !st.is(net.minecraft.tags.BlockTags.LEAVES) && random.nextFloat() < 0.35f) {
            level.playSound(null, bp, ModRegistries.WOOD_SNAP.get(), net.minecraft.sounds.SoundSource.BLOCKS,
                    1.2f, 0.8f + random.nextFloat() * 0.4f);
        }
        if (FloodForces.floats(st) && !debris.full() && random.nextFloat() < 0.5f) {
            level.levelEvent(2001, bp, Block.getId(st));
            level.removeBlock(bp, false);
            debris.spawn(st, Vec3.atCenterOf(bp), flow.add(0, 0.15, 0));
        } else {
            level.destroyBlock(bp, random.nextFloat() < 0.6f);
        }
    }

    /**
     * A tree standing in the bore's path is torn out by the roots when the force beats its resistance: its leaves and
     * trunk are stripped, a few big pieces float off, and the column floods.
     */
    private static final int NOT_A_TREE = 0, HELD = 1, UPROOTED = 2;

    private int uproot(int x, int z, int top, float force) {
        int base = top;
        while (base - 1 > waterY && level.getBlockState(new BlockPos(x, base - 1, z)).is(net.minecraft.tags.BlockTags.LOGS)) base--;
        if (base > waterY + floodHeight) return NOT_A_TREE; // trunk base above the water: not in the flow
        BlockPos trunk = new BlockPos(x, base, z);
        List<BlockPos> parts = FloodForces.tree(level, trunk, 90);
        long leaves = parts.stream().filter(bp -> level.getBlockState(bp).is(net.minecraft.tags.BlockTags.LEAVES)).count();
        if (leaves < 4) return NOT_A_TREE; // log walls (cabins) are not trees: they are handled as walls
        // The whole tree resists as one, anchored by its roots: little randomness, force decides.
        if (force * (0.85f + 0.3f * random.nextFloat()) < 2.5f) return HELD;
        if (!DisasterProtection.canModify(level, trunk, level.getBlockState(trunk))) return HELD;
        Vec3 flow = flowAt(x + 0.5, z + 0.5);
        level.playSound(null, trunk, ModRegistries.WOOD_SNAP.get(), net.minecraft.sounds.SoundSource.BLOCKS, 2.2f, 0.65f);
        for (int k = 0; k < parts.size(); k++) {
            BlockPos bp = parts.get(k);
            boolean floatOff = k == 0 || k == parts.size() / 3 || k == parts.size() - 1;
            pending.add(l -> {
                if (isFinished() || receding) return;
                BlockState st = level.getBlockState(bp);
                if (!(st.is(net.minecraft.tags.BlockTags.LOGS) || st.is(net.minecraft.tags.BlockTags.LEAVES))) return;
                if (!DisasterProtection.canModify(level, bp, st)) return;
                if (floatOff && debris.spawn(st, Vec3.atCenterOf(bp), flow.add(0, 0.2, 0))) {
                    level.levelEvent(2001, bp, Block.getId(st));
                    level.setBlock(bp, Blocks.AIR.defaultBlockState(), STATIC_FLAGS);
                } else {
                    level.destroyBlock(bp, random.nextFloat() < 0.15f);
                }
            });
        }
        int surface = floodSurface((float) axialOf(x + 0.5, z + 0.5), (float) lateralOf(x + 0.5, z + 0.5));
        if (surface >= base) {
            FloodCol fc = new FloodCol(x, z, base, surface, force);
            wet.add(fc);
            raise(fc);
        }
        return UPROOTED;
    }

    // ------------------------------------------------------------------ recession

    private void startRecession() {
        if (receding) return;
        receding = true;
        drawbackOver = true;
        pending.clear(); // unplaced flood water and pending drawbacks are simply dropped
        for (int k = restoreIndex; k < withdrawnOrder.size(); k++) {
            long p = withdrawnOrder.get(k).pos();
            pending.add(l -> restore(p));
        }
        restoreIndex = withdrawnOrder.size();
        // Drain top-down (the surface sinks), inland first at each level.
        long[] order = placed.toLongArray();
        Long[] boxed = new Long[order.length];
        for (int k = 0; k < order.length; k++) boxed[k] = order[k];
        java.util.Arrays.sort(boxed, Comparator.<Long>comparingInt(p -> -BlockPos.getY(p))
                .thenComparingDouble(p -> -axialOf(BlockPos.getX(p) + 0.5, BlockPos.getZ(p) + 0.5)));
        for (Long p : boxed) pending.add(l -> removeWater(p));
        queueSweep();
        int ticks = Math.max(1, (int) (aftermathTicks * 0.6f));
        recedeRate = Math.max(8, Mth.ceil(pending.size() / (float) ticks));
    }

    /**
     * Vanilla can nudge flood water into flowing (e.g. grass dying under it fires a normal block update). A source with a
     * pending fluid tick next to a freshly drained cell would flow back in and, by the infinite-source rule, refill it.
     * So while flood water exists, pending fluid ticks above sea level in the flood area are dropped, and after the
     * recession the cells that were dry before the flood are swept until no flood water is left. Water elsewhere is
     * never removed (pre-existing water above sea level inside the flood box just doesn't flow during the event).
     */
    private void clearFloodTicks() {
        if (floodBox == null) return;
        BoundingBox box = new BoundingBox(floodBox.minX() - 1, waterY + 1, floodBox.minZ() - 1,
                floodBox.maxX() + 1, floodBox.maxY() + 1, floodBox.maxZ() + 1);
        level.getFluidTicks().clearArea(box);
    }

    private void queueSweep() {
        pending.add(l -> sweepPass(dryCells.toLongArray(), 0));
    }

    /** One pass over the formerly dry cells; repeats (up to 6 passes) until a pass finds no flood water. */
    private void sweepPass(long[] cells, int pass) {
        clearFloodTicks();
        BlockChangeQueue queue = DisasterManager.blockQueue(level);
        int[] found = {0};
        for (int k = 0; k < cells.length; k += 48) {
            int from = k, to = Math.min(cells.length, k + 48);
            BlockChangeQueue.Change c = q -> found[0] += sweep(cells, from, to);
            if (!queue.add(c)) c.apply(level);
        }
        BlockChangeQueue.Change after = q -> {
            if (found[0] > 0 && pass < 5) sweepPass(cells, pass + 1);
            else drainStrays();
        };
        if (!queue.add(after)) after.apply(level);
    }

    /** Dries flood water (source or flowing) in cells that were dry before the flood. @return cells dried */
    private int sweep(long[] cells, int from, int to) {
        int n = 0;
        for (int k = from; k < to; k++) {
            BlockPos bp = BlockPos.of(cells[k]);
            if (!level.isLoaded(bp)) continue;
            BlockState st = level.getBlockState(bp);
            if (!st.is(Blocks.WATER)) continue; // only plain water blocks; waterlogged blocks etc. are left alone
            level.setBlock(bp, Blocks.AIR.defaultBlockState(), STATIC_FLAGS);
            n++;
            for (Direction d : Direction.values()) {
                if (d == Direction.UP) continue;
                BlockPos nb = bp.relative(d);
                FluidState fs = level.getFluidState(nb);
                if (fs.is(FluidTags.WATER) && !fs.isSource()) drainCheck.add(nb.asLong());
            }
        }
        return n;
    }

    /** Removes one block of this tsunami's water. Water that was there before is never in {@link #placed}. */
    private void removeWater(long p) {
        if (!placed.remove(p)) return;
        BlockPos bp = BlockPos.of(p);
        if (!level.isLoaded(bp)) return;
        BlockState st = level.getBlockState(bp);
        if (!st.is(Blocks.WATER) || !st.getFluidState().isSource()) return; // a player changed it: leave it be
        level.setBlock(bp, Blocks.AIR.defaultBlockState(), STATIC_FLAGS);
        // Remember flowing water next to it (only possible if something nudged our water): it gets drained at the end.
        for (Direction d : Direction.values()) {
            if (d == Direction.UP) continue;
            BlockPos n = bp.relative(d);
            FluidState fs = level.getFluidState(n);
            if (fs.is(FluidTags.WATER) && !fs.isSource()) drainCheck.add(n.asLong());
        }
    }

    /** Lets stray flowing water re-evaluate: unfed water dries up, water fed by a real source stays. */
    private void drainStrays() {
        for (long p : drainCheck) {
            BlockPos bp = BlockPos.of(p);
            if (!level.isLoaded(bp)) continue;
            FluidState fs = level.getFluidState(bp);
            if (fs.is(FluidTags.WATER) && !fs.isSource()) level.scheduleTick(bp, fs.getType(), 5);
        }
        drainCheck.clear();
    }

    // ------------------------------------------------------------------ columns

    /** All loaded columns in an axial band, sorted by distance from the shoreline. */
    private List<Cell> collectColumns(float axialMin, float axialMax, float lateralMax, boolean seaward) {
        List<Cell> out = new ArrayList<>();
        AABB box = bandBox(axialMin, axialMax, lateralMax, 0, 1);
        for (int x = Mth.floor(box.minX); x <= Mth.floor(box.maxX); x++) {
            for (int z = Mth.floor(box.minZ); z <= Mth.floor(box.maxZ); z++) {
                double ax = axialOf(x + 0.5, z + 0.5), lat = lateralOf(x + 0.5, z + 0.5);
                if (ax < axialMin || ax > axialMax || Math.abs(lat) > lateralMax) continue;
                if (seaward && !surfaceIsWaterAt(x, z)) continue;
                out.add(new Cell(BlockPos.asLong(x, waterY, z), (float) ax, (float) lat));
            }
        }
        out.sort(seaward ? Comparator.comparingDouble(c -> -c.axial()) : Comparator.comparingDouble(Cell::axial));
        return out;
    }

    private boolean surfaceIsWaterAt(int x, int z) {
        BlockPos p = new BlockPos(x, waterY, z);
        if (!level.isLoaded(p)) return false;
        FluidState fs = level.getFluidState(p);
        return fs.is(FluidTags.WATER) && fs.isSource() && level.getBlockState(p.above()).isAir();
    }

    private AABB bandBox(double axialMin, double axialMax, double lateralMax, double yMin, double yMax) {
        double minX = Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (double a : new double[]{axialMin, axialMax}) {
            for (double l : new double[]{-lateralMax, lateralMax}) {
                double x = worldX(a, l), z = worldZ(a, l);
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minZ = Math.min(minZ, z);
                maxZ = Math.max(maxZ, z);
            }
        }
        return new AABB(minX, yMin, minZ, maxX, yMax, maxZ);
    }

    private AABB region() {
        if (region == null) region = bandBox(-seaDist, reach, halfWidth, waterY - 6, waterY + crestMax + 8);
        return region;
    }

    // ------------------------------------------------------------------ entities

    /** The breaking wave shoves everything in its path inland and knocks it about. */
    private void pushAtFront(float front, float wall) {
        AABB box = bandBox(front - 6, front + 2, halfWidth, waterY - 4, waterY + crestMax + 3);
        DamageSource source = level.damageSources().source(ModRegistries.TSUNAMI_DAMAGE);
        double push = (0.22 + 0.05 * intensity) * wall;
        for (Entity e : level.getEntities((Entity) null, box, e -> !e.isSpectator())) {
            if (skip(e)) continue;
            double ax = axialOf(e.getX(), e.getZ());
            if (ax < front - 6 || ax > front + 2 || Math.abs(lateralOf(e.getX(), e.getZ())) > halfWidth) continue;
            double resist = e instanceof LivingEntity le ? Mth.clamp(le.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE), 0, 1) * 0.6 : 0;
            Vec3 v = e.getDeltaMovement().add(-dirX * push * (1 - resist), 0.10 * wall, -dirZ * push * (1 - resist));
            if (v.lengthSqr() > 1.6 * 1.6) v = v.normalize().scale(1.6);
            e.setDeltaMovement(v);
            e.hurtMarked = true;
            if (e instanceof LivingEntity le && age() % 10 == 0) {
                le.hurtServer(level, source, (1f + intensity * 0.6f) * wall);
            }
        }
    }

    /**
     * Drag: anything in the water is pulled toward the local current, F = k * (u_flow - v). Light things (items,
     * boats, mobs that can't swim well) go with the flow; players can wade against slack water but not the surge or
     * the backwash. Vanilla buoyancy and drowning still apply.
     */
    private void applyFlow() {
        for (Entity e : level.getEntities((Entity) null, region(), e -> !e.isSpectator() && e.isInWater())) {
            if (skip(e) || e instanceof net.minecraft.world.entity.Display) continue;
            Vec3 u = flowAt(e.getX(), e.getZ());
            if (u == Vec3.ZERO) continue;
            double k;
            if (e instanceof net.minecraft.world.entity.item.ItemEntity || e instanceof net.minecraft.world.entity.vehicle.boat.AbstractBoat) k = 0.3;
            else if (e instanceof Player) k = 0.07;
            else if (e instanceof LivingEntity le) k = 0.13 * (1 - Mth.clamp(le.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE), 0, 1));
            else k = 0.2;
            Vec3 v = e.getDeltaMovement();
            e.setDeltaMovement(v.add((u.x - v.x) * k, 0, (u.z - v.z) * k));
            e.hurtMarked = true;
        }
    }

    private static boolean skip(Entity e) {
        if (e.isPassenger()) return true;
        return e instanceof Player p && (p.getAbilities().flying || p.isCreative() && p.isShiftKeyDown());
    }

    // ------------------------------------------------------------------ mob reactions

    /** Animals sense it first and run for high ground (inland); villagers hide as if the bell rang. */
    private void alertMobs() {
        AABB box = region().inflate(24, 8, 24);
        for (PathfinderMob mob : level.getEntitiesOfClass(PathfinderMob.class, box, m -> !(m instanceof Enemy))) {
            if (mob instanceof Villager villager) {
                villager.getBrain().setMemory(MemoryModuleType.HEARD_BELL_TIME, level.getGameTime());
                continue;
            }
            if (mob.isLeashed() || mob.isPassenger()) continue;
            double ax = axialOf(mob.getX(), mob.getZ());
            if (ax > reach + 16) continue;
            // Head for the highest ground among a few spots inland.
            double bx = mob.getX() - dirX * 16, bz = mob.getZ() - dirZ * 16;
            int best = Integer.MIN_VALUE;
            for (int k = 0; k < 5; k++) {
                double run = 12 + random.nextInt(16);
                double tx = mob.getX() - dirX * run + (random.nextDouble() - 0.5) * 20;
                double tz = mob.getZ() - dirZ * run + (random.nextDouble() - 0.5) * 20;
                if (!level.isLoaded(BlockPos.containing(tx, 0, tz))) continue;
                int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(tx), Mth.floor(tz));
                if (h > best) {
                    best = h;
                    bx = tx;
                    bz = tz;
                }
            }
            mob.getNavigation().moveTo(bx, best == Integer.MIN_VALUE ? mob.getY() : best, bz, phase() == Phase.WARNING ? 1.25 : 1.7);
        }
    }

    private void broadcast(Component message, double range) {
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(pos.x, p.getY(), pos.z) < range * range) p.sendOverlayMessage(message);
        }
    }

    // ------------------------------------------------------------------ test / debug accessors

    public int placedWaterCount() {
        return placed.size();
    }

    public int withdrawnCount() {
        return withdrawn.size();
    }

    public int pendingCount() {
        return pending.size();
    }

    public boolean hasSea() {
        return hasSea;
    }

    public int waterY() {
        return waterY;
    }

    public double seaDirX() {
        return dirX;
    }

    public double seaDirZ() {
        return dirZ;
    }

    public int debrisCount() {
        return debris.size();
    }

    public float front() {
        return front;
    }
}
