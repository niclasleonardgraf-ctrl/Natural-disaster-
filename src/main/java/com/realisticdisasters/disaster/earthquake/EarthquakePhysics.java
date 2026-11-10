package com.realisticdisasters.disaster.earthquake;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.realisticdisasters.config.DisasterConfig;
import com.realisticdisasters.disaster.BlockChangeQueue;
import com.realisticdisasters.disaster.DisasterManager;
import com.realisticdisasters.disaster.DisasterProtection;
import com.realisticdisasters.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.TorchBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The earthquake's physical interaction with the world, kept apart from the disaster's lifecycle code:
 * <ul>
 *   <li><b>Load paths:</b> a block with nothing under it only stays up if a chain of solid blocks reaches something
 *       standing on the ground within its material's span (wood beams span further than masonry, glass hardly at
 *       all). Shaking shortens every span, so wide roofs and long overhangs fail first, small huts survive small
 *       quakes, and collapses propagate as each fall removes support from its neighbours.</li>
 *   <li><b>Rigid toppling:</b> slender things (chimneys, towers, pillars, undermined trees) don't crumble block by
 *       block; they snap near the base and tip over as one rigid body (simulated with a falling rod's equation of
 *       motion), then break up and fly the rest of the way, ending up lying along the ground.</li>
 *   <li><b>Landslides:</b> on steep slopes the surface lets go and slides downhill; the scar works its way upslope.</li>
 *   <li><b>Everything else:</b> doors and gates bang, bells ring by themselves, hanging lanterns, candles and pots
 *       fall, wall torches drop, paintings and item frames come off the wall, ponds slop over their banks, loose
 *       items, boats and minecarts get thrown about, and people can barely keep their footing.</li>
 * </ul>
 */
final class EarthquakePhysics {
    private static final net.minecraft.resources.Identifier STAGGER = ModRegistries.id("earthquake_stagger");

    private final EarthquakeDisaster quake;
    private final ServerLevel level;
    private final RandomSource random;
    private final int intensity;
    private final List<Slide> slides = new ArrayList<>();
    private final Set<UUID> staggered = new HashSet<>();
    private int topples;

    /** A landslide: the source scar retreats upslope while the released material flows downhill. */
    private static final class Slide {
        int x, z;
        final Direction down;
        int steps;

        Slide(int x, int z, Direction down, int steps) {
            this.x = x;
            this.z = z;
            this.down = down;
            this.steps = steps;
        }
    }

    EarthquakePhysics(EarthquakeDisaster quake, ServerLevel level, RandomSource random, int intensity) {
        this.quake = quake;
        this.level = level;
        this.random = random;
        this.intensity = intensity;
    }

    // ------------------------------------------------------------------ load paths

    /** How many blocks this material can span from a support when undisturbed. */
    static int baseSpan(BlockState st, float hardness) {
        if (st.is(BlockTags.SAND) || st.is(Blocks.GRAVEL)) return 0;
        if (st.is(BlockTags.DIRT) || st.is(Blocks.CLAY) || st.is(BlockTags.SNOW)) return 1;
        if (st.is(BlockTags.LOGS) || st.is(BlockTags.PLANKS) || st.getSoundType() == SoundType.WOOD) return 4;
        if (st.is(BlockTags.BASE_STONE_OVERWORLD)) return 4;   // natural rock arches are strong
        if (hardness >= 5f) return 6;                           // iron, obsidian-like
        if (hardness >= 1.5f) return 3;                         // masonry, terracotta, concrete
        return 1;                                               // glass, wool, light stuff
    }

    /** Span under the current shaking: strong motion shortens what a beam or slab can carry. */
    int effectiveSpan(BlockState st, float hardness) {
        float loss = Mth.clamp(0.1f * intensity * quake.tremor() + (intensity >= 5 ? 0.1f : 0f), 0f, 0.8f);
        return (int) Math.floor(baseSpan(st, hardness) * (1f - loss));
    }

    /**
     * Breadth-first search along solid blocks at the same height: is something standing on the ground within
     * {@code span} steps? (The block itself has nothing solid below it.)
     */
    boolean hasLoadPath(BlockPos start, int span) {
        if (span <= 0) return false;
        ArrayDeque<BlockPos> frontier = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        frontier.add(start);
        seen.add(start.asLong());
        for (int depth = 1; depth <= span && !frontier.isEmpty(); depth++) {
            int n = frontier.size();
            for (int i = 0; i < n; i++) {
                BlockPos p = frontier.poll();
                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    BlockPos q = p.relative(dir);
                    if (!seen.add(q.asLong()) || !level.isLoaded(q)) continue;
                    BlockState qs = level.getBlockState(q);
                    if (!EarthquakeDisaster.solid(qs) || qs.is(BlockTags.LEAVES)) continue;
                    if (EarthquakeDisaster.solid(level.getBlockState(q.below()))) return true;
                    frontier.add(q);
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ rigid toppling

    /** Slender structures found by the sampler (top position -> free-standing height), re-checked every second. */
    private final java.util.Map<BlockPos, Integer> towers = new java.util.HashMap<>();

    /**
     * Called for the top block of a column standing on something: if it's the top of a slender, free-standing
     * structure (chimney, tower, pillar), remember it. Such structures are the classic earthquake casualty.
     */
    void noteColumnTop(BlockPos top) {
        if (intensity < 3 || towers.size() >= 48 || towers.containsKey(top)) return;
        int open = 0;
        for (Direction dir : Direction.Plane.HORIZONTAL) if (!EarthquakeDisaster.solid(level.getBlockState(top.relative(dir)))) open++;
        if (open < 2) return; // cheap pre-filter: flat roofs and wall tops are not towers
        int free = freeStandingHeight(top);
        if (free >= 4) towers.put(top.immutable(), free);
    }

    /** Every second, each known tower may snap and topple; taller and stronger shaking means more likely. */
    void tickTowers() {
        if (towers.isEmpty() || quake.age() % 20 != 0 || topples >= intensity * 3) return;
        var it = towers.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            BlockPos top = e.getKey();
            if (!level.isLoaded(top) || !EarthquakeDisaster.solid(level.getBlockState(top))) {
                it.remove();
                continue;
            }
            int free = e.getValue();
            float p = 0.12f * quake.tremor() * (intensity - 2) * Math.min(1.5f, free / 8f);
            if (random.nextFloat() > p) continue;
            it.remove();
            // Masonry snaps somewhere in the lower part of its free height; the part above falls as one piece.
            int breakY = top.getY() - free + 1 + random.nextInt(Math.max(1, free / 3));
            List<BlockPos> body = gather(top, breakY, 2, 220, false);
            if (body.size() < 3) continue;
            Vec3 dir = fallDirection(top);
            topples++;
            DisasterManager.blockQueue(level).add(l -> topple(body, breakY, dir, top));
        }
    }

    /** Topples the slender structure whose top is {@code top} right now, in {@code dir} (tests / admin use). */
    boolean toppleNow(BlockPos top, Vec3 dir) {
        int free = freeStandingHeight(top);
        if (free < 2) return false;
        int breakY = top.getY() - free + 1;
        List<BlockPos> body = gather(top, breakY, 2, 220, false);
        if (body.isEmpty()) return false;
        topple(body, breakY, dir.normalize(), top);
        return true;
    }

    /** An undermined tree goes over as a whole, roots first, canopy and all. */
    boolean toppleTree(BlockPos log) {
        if (topples >= intensity * 3) return false;
        List<BlockPos> body = gather(log, log.getY(), 4, 200, true);
        if (body.size() < 3) return false;
        topples++;
        Vec3 dir = fallDirection(log);
        DisasterManager.blockQueue(level).add(l -> topple(body, log.getY(), dir, log));
        return true;
    }

    /** Height of the slender part of a column, walking down from its top while the 5x5 slice stays narrow. */
    private int freeStandingHeight(BlockPos top) {
        int h = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int y = top.getY(); y > top.getY() - 24; y--) {
            m.set(top.getX(), y, top.getZ());
            BlockState st = level.getBlockState(m);
            if (!EarthquakeDisaster.solid(st) || EarthquakeDisaster.terrain(st) || st.is(BlockTags.LEAVES)) break;
            int count = 0;
            for (int dx = -2; dx <= 2; dx++)
                for (int dz = -2; dz <= 2; dz++)
                    if (EarthquakeDisaster.solid(level.getBlockState(m.set(top.getX() + dx, y, top.getZ() + dz)))) count++;
            if (count > 9) break;
            h++;
        }
        return h;
    }

    /** Connected solid blocks of the structure at or above {@code minY}, within {@code reach} of the start column. */
    private List<BlockPos> gather(BlockPos start, int minY, int reach, int limit, boolean tree) {
        List<BlockPos> out = new ArrayList<>();
        ArrayDeque<BlockPos> frontier = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        frontier.add(start);
        seen.add(start.asLong());
        while (!frontier.isEmpty() && out.size() < limit) {
            BlockPos p = frontier.poll();
            BlockState st = level.getBlockState(p);
            boolean part = tree ? st.is(BlockTags.LOGS) || st.is(BlockTags.LEAVES)
                    : EarthquakeDisaster.solid(st) && !EarthquakeDisaster.terrain(st) && !st.is(BlockTags.LEAVES);
            if (!part || st.hasBlockEntity()) continue;
            out.add(p);
            for (Direction dir : Direction.values()) {
                BlockPos q = p.relative(dir);
                if (q.getY() < minY || Math.abs(q.getX() - start.getX()) > reach || Math.abs(q.getZ() - start.getZ()) > reach) continue;
                if (seen.add(q.asLong()) && level.isLoaded(q)) frontier.add(q);
            }
        }
        return out;
    }

    /** Falls away from the epicentre (the direction the ground lurched), with some scatter. */
    private Vec3 fallDirection(BlockPos at) {
        Vec3 c = quake.pos();
        double dx = at.getX() + 0.5 - c.x, dz = at.getZ() + 0.5 - c.z;
        double a = (dx * dx + dz * dz < 4 ? random.nextDouble() * Math.PI * 2 : Math.atan2(dz, dx))
                + (random.nextDouble() - 0.5) * 1.2;
        return new Vec3(Math.cos(a), 0, Math.sin(a));
    }

    /** A structure falling over as one rigid body, simulated until it has tipped far enough to break up. */
    private static final class Topple {
        final List<FallingBlockEntity> parts = new ArrayList<>();
        final List<double[]> offsets = new ArrayList<>(); // {u (along fall), w (up), s (sideways)} from the hinge
        final Vec3 hinge, dir, side;
        final double length;
        double theta = 0.03, omega;
        int ticks;

        Topple(Vec3 hinge, Vec3 dir, double length, double kick) {
            this.hinge = hinge;
            this.dir = dir;
            this.side = new Vec3(-dir.z, 0, dir.x);
            this.length = length;
            this.omega = kick;
        }

        Vec3 at(double[] o) {
            double c = Math.cos(theta), sn = Math.sin(theta);
            double u = o[0] * c + o[1] * sn, w = -o[0] * sn + o[1] * c;
            return hinge.add(dir.scale(u)).add(0, w, 0).add(side.scale(o[2]));
        }

        Vec3 velocity(double[] o) {
            double c = Math.cos(theta), sn = Math.sin(theta);
            return dir.scale((-o[0] * sn + o[1] * c) * omega).add(0, (-o[0] * c - o[1] * sn) * omega, 0);
        }
    }

    private final List<Topple> topplesInMotion = new ArrayList<>();
    private static final double FALLING_BLOCK_GRAVITY = 0.04;

    /**
     * Starts a rigid-body topple: the body tips about a hinge at the leading edge of its base. Its tilt follows the
     * equation of motion of a falling rod (theta'' = 3g / 2L * sin theta: slow at first, then faster and faster); every
     * block is steered along its arc each tick. Once it has tipped far enough, the pieces are released with their
     * real tangential velocity and fly the rest of the way, so the structure ends up lying along the ground.
     */
    void topple(List<BlockPos> body, int pivotY, Vec3 dir, BlockPos origin) {
        if (quake.isFinished()) return;
        Vec3 base = new Vec3(origin.getX() + 0.5, pivotY, origin.getZ() + 0.5);
        double lead = 0, top = pivotY;
        for (BlockPos p : body) {
            lead = Math.max(lead, (p.getX() + 0.5 - base.x) * dir.x + (p.getZ() + 0.5 - base.z) * dir.z);
            top = Math.max(top, p.getY() + 1);
        }
        Vec3 hinge = base.add(dir.scale(lead + 0.5));
        Topple t = new Topple(hinge, dir, Math.max(2, top - pivotY), 0.012 + 0.003 * intensity);
        List<BlockPos> sorted = new ArrayList<>(body);
        sorted.sort(Comparator.comparingInt(BlockPos::getY));
        for (BlockPos p : sorted) {
            BlockState st = level.getBlockState(p);
            if (!EarthquakeDisaster.solid(st) || st.hasBlockEntity() || !DisasterProtection.canModify(level, p, st)) continue;
            FallingBlockEntity fb = FallingBlockEntity.fall(level, p, st);
            if (st.is(BlockTags.LEAVES)) fb.dropItem = false;
            fb.setHurtsEntities(1.0f + intensity * 0.4f, 8 + intensity * 4);
            fb.setDeltaMovement(0, FALLING_BLOCK_GRAVITY, 0); // hold still for the first tick (cancels gravity)
            Vec3 o = fb.position().subtract(hinge);
            t.parts.add(fb);
            t.offsets.add(new double[]{o.x * dir.x + o.z * dir.z, o.y, o.x * t.side.x + o.z * t.side.z});
            quake.trackDebris(fb);
        }
        if (t.parts.isEmpty()) return;
        topplesInMotion.add(t);
        quake.addDust(Math.min(0.08f, t.parts.size() * 0.002f));
        level.playSound(null, origin, ModRegistries.QUAKE_CRACK.get(), SoundSource.BLOCKS, 3f, 0.55f + random.nextFloat() * 0.1f);
        BlockPos hingeBlock = new BlockPos(origin.getX(), pivotY, origin.getZ());
        quake.queueNeighbours(hingeBlock);
        quake.queueCheck(hingeBlock.below());
    }

    /** Advances every rigid topple by one tick (runs after entities have moved this tick). */
    void tickTopples() {
        if (topplesInMotion.isEmpty()) return;
        var it = topplesInMotion.iterator();
        while (it.hasNext()) {
            Topple t = it.next();
            t.ticks++;
            double alpha = 1.5 * FALLING_BLOCK_GRAVITY / t.length * Math.sin(t.theta);
            t.omega += alpha;
            t.theta += t.omega;
            boolean release = t.theta >= 1.15 || t.ticks > 160 || quake.isFinished();
            boolean anyAlive = false;
            for (int i = 0; i < t.parts.size(); i++) {
                FallingBlockEntity fb = t.parts.get(i);
                if (fb.isRemoved()) continue;
                anyAlive = true;
                double[] o = t.offsets.get(i);
                Vec3 v = release ? t.velocity(o)
                        : t.at(o).subtract(fb.position()).add(0, FALLING_BLOCK_GRAVITY, 0); // reach the arc next tick
                fb.setDeltaMovement(v);
                fb.hurtMarked = true;
            }
            if (release || !anyAlive) it.remove();
        }
    }

    /** Lets go of everything still being steered (quake removed). */
    void releaseTopples() {
        for (Topple t : topplesInMotion) {
            for (int i = 0; i < t.parts.size(); i++) {
                if (!t.parts.get(i).isRemoved()) t.parts.get(i).setDeltaMovement(t.velocity(t.offsets.get(i)));
            }
        }
        topplesInMotion.clear();
    }

    // ------------------------------------------------------------------ landslides

    /** The top block of a terrain column was sampled: on steep slopes the surface may let go. */
    void considerSlope(int x, int z, int top, float chanceScale) {
        if (intensity < 2 || slides.size() >= intensity * 2) return;
        if (random.nextFloat() > 0.06f * quake.tremor() * chanceScale * (intensity - 1)) return;
        Direction best = null;
        int bestDrop = 0;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            int nx = x + dir.getStepX() * 2, nz = z + dir.getStepZ() * 2;
            if (!level.isLoaded(new BlockPos(nx, 0, nz))) continue;
            int drop = top - (level.getHeight(Heightmap.Types.WORLD_SURFACE, nx, nz) - 1);
            if (drop > bestDrop) {
                bestDrop = drop;
                best = dir;
            }
        }
        // 3+ blocks of fall over 2 blocks of run: steeper than ~55 degrees.
        if (best == null || bestDrop < 3) return;
        slides.add(new Slide(x, z, best, 3 + intensity * 2));
    }

    void tickSlides() {
        if (slides.isEmpty() || quake.age() % 3 != 0) return;
        BlockChangeQueue queue = DisasterManager.blockQueue(level);
        var it = slides.iterator();
        while (it.hasNext()) {
            Slide s = it.next();
            if (s.steps-- <= 0 || !level.isLoaded(new BlockPos(s.x, 0, s.z))) {
                it.remove();
                continue;
            }
            int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, s.x, s.z) - 1;
            int depth = 1 + random.nextInt(1 + intensity / 2);
            for (int k = 0; k < depth; k++) {
                BlockPos bp = new BlockPos(s.x, top - k, s.z);
                BlockState st = level.getBlockState(bp);
                if (!EarthquakeDisaster.terrain(st) || st.hasBlockEntity() || st.getDestroySpeed(level, bp) > quake.maxHardness()) break;
                Vec3 push = new Vec3(s.down.getStepX() * (0.18 + random.nextDouble() * 0.12), 0.06,
                        s.down.getStepZ() * (0.18 + random.nextDouble() * 0.12));
                queue.add(l -> quake.collapse(bp, st, push));
            }
            // The scar retreats upslope while it is still steep.
            int ux = s.x - s.down.getStepX(), uz = s.z - s.down.getStepZ();
            int upTop = level.getHeight(Heightmap.Types.WORLD_SURFACE, ux, uz) - 1;
            if (upTop < top) {
                it.remove();
                continue;
            }
            s.x = ux;
            s.z = uz;
            if (random.nextFloat() < 0.35f) { // widen the scar sideways
                Direction sideways = random.nextBoolean() ? s.down.getClockWise() : s.down.getCounterClockWise();
                s.x += sideways.getStepX();
                s.z += sideways.getStepZ();
            }
        }
    }

    // ------------------------------------------------------------------ fixtures and fluids

    /**
     * Reactions of small things that aren't structure: returns true if the block was one of them (handled or not).
     * All changes go through the block queue and the protection checks.
     */
    boolean reactFixture(BlockPos bp, BlockState st, float chanceScale) {
        float t = quake.tremor() * chanceScale;
        BlockChangeQueue queue = DisasterManager.blockQueue(level);
        if (st.getBlock() instanceof DoorBlock door) {
            if (st.is(BlockTags.WOODEN_DOORS) && st.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER && random.nextFloat() < 0.15f * t) {
                queue.add(l -> {
                    BlockState now = level.getBlockState(bp);
                    if (now.getBlock() != door || !DisasterProtection.canModify(level, bp, now)) return;
                    door.setOpen(null, level, now, bp, !door.isOpen(now));
                });
            }
            return true;
        }
        if (st.getBlock() instanceof TrapDoorBlock || st.getBlock() instanceof FenceGateBlock) {
            boolean wooden = st.is(BlockTags.WOODEN_TRAPDOORS) || st.is(BlockTags.FENCE_GATES);
            if (wooden && st.hasProperty(BlockStateProperties.OPEN) && random.nextFloat() < 0.12f * t) {
                boolean gate = st.getBlock() instanceof FenceGateBlock;
                queue.add(l -> {
                    BlockState now = level.getBlockState(bp);
                    if (now != st || !DisasterProtection.canModify(level, bp, now)) return;
                    BlockState toggled = now.cycle(BlockStateProperties.OPEN);
                    level.setBlock(bp, toggled, 10);
                    boolean open = toggled.getValue(BlockStateProperties.OPEN);
                    level.playSound(null, bp, gate ? (open ? SoundEvents.FENCE_GATE_OPEN : SoundEvents.FENCE_GATE_CLOSE)
                                    : (open ? SoundEvents.WOODEN_TRAPDOOR_OPEN : SoundEvents.WOODEN_TRAPDOOR_CLOSE),
                            SoundSource.BLOCKS, 1f, 0.9f + random.nextFloat() * 0.2f);
                });
            }
            return true;
        }
        if (st.getBlock() instanceof BellBlock bell) {
            // Bells ring by themselves in strong quakes (a classic eyewitness detail).
            if (random.nextFloat() < 0.3f * t) queue.add(l -> bell.attemptToRing(level, bp, null));
            return true;
        }
        boolean hangingLantern = st.getBlock() instanceof LanternBlock && st.getValue(LanternBlock.HANGING);
        boolean loose = st.getBlock() instanceof LanternBlock || st.is(BlockTags.CANDLES) || st.getBlock() instanceof FlowerPotBlock;
        boolean torch = st.getBlock() instanceof TorchBlock;
        if (loose || torch) {
            float chance = hangingLantern ? 0.1f : torch ? (st.hasProperty(BlockStateProperties.HORIZONTAL_FACING) ? 0.04f : 0.015f) : 0.06f;
            if (intensity >= 2 && random.nextFloat() < chance * t) {
                queue.add(l -> {
                    BlockState now = level.getBlockState(bp);
                    if (now != st || !DisasterProtection.canModify(level, bp, now)) return;
                    level.destroyBlock(bp, true); // falls off and breaks: particles, sound, drops
                });
            }
            return true;
        }
        if (st.is(Blocks.WATER) && st.getFluidState().isSource()) {
            if (intensity >= 2 && random.nextFloat() < 0.2f * t) slosh(bp);
            return true;
        }
        return false;
    }

    /** Seiche: water slops over the bank as a short-lived flowing sheet (no source, so it drains away again). */
    private void slosh(BlockPos water) {
        if (!level.getBlockState(water.above()).isAir()) return;
        Direction dir = Direction.Plane.HORIZONTAL.getRandomDirection(random);
        BlockPos bank = water.relative(dir);
        BlockPos onto = bank.above();
        if (!EarthquakeDisaster.solid(level.getBlockState(bank)) || !level.getBlockState(onto).isAir()) return;
        DisasterManager.blockQueue(level).add(l -> {
            if (quake.isFinished() || !level.getBlockState(onto).isAir() || !DisasterProtection.canModify(level, onto, Blocks.WATER.defaultBlockState())) return;
            level.setBlock(onto, Fluids.WATER.getFlowing(5 + random.nextInt(2), false).createLegacyBlock(), 3);
        });
    }

    // ------------------------------------------------------------------ entities

    /** Non-living things: loose items hop, boats and carts are shoved, things fall off walls. */
    void shakeObjects(AABB box, double radius, boolean frameTick) {
        float t = quake.tremor();
        Vec3 c = quake.pos();
        for (Entity e : level.getEntities((Entity) null, box, e -> e instanceof ItemEntity || e instanceof AbstractBoat
                || e instanceof AbstractMinecart || e instanceof HangingEntity)) {
            double d = Math.sqrt((e.getX() - c.x) * (e.getX() - c.x) + (e.getZ() - c.z) * (e.getZ() - c.z));
            if (d > radius) continue;
            float near = 1f - (float) (d / radius);
            if (e instanceof HangingEntity hanging) {
                if (frameTick && intensity >= 2 && DisasterConfig.BLOCK_DESTRUCTION.getAsBoolean()
                        && random.nextFloat() < 0.15f * t * near
                        && DisasterProtection.canModify(level, hanging.blockPosition(), level.getBlockState(hanging.blockPosition()))) {
                    hanging.hurtServer(level, level.damageSources().generic(), 1f); // pops the item / drops off the wall
                }
                continue;
            }
            double push = 0.05 * t * near * (0.6 + 0.2 * intensity);
            double hop = e.onGround() && e instanceof ItemEntity && random.nextFloat() < 0.3f * t ? 0.08 + 0.05 * t : 0;
            e.push((random.nextDouble() - 0.5) * 2 * push, hop, (random.nextDouble() - 0.5) * 2 * push);
            e.hurtMarked = true;
        }
    }

    /** People in strong shaking can barely walk: a temporary speed penalty that follows the shaking. */
    void stagger(Player player, float near) {
        AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) return;
        speed.removeModifier(STAGGER);
        float amount = 0.55f * quake.tremor() * near;
        if (amount < 0.03f) {
            staggered.remove(player.getUUID());
            return;
        }
        speed.addTransientModifier(new AttributeModifier(STAGGER, -amount, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        staggered.add(player.getUUID());
    }

    /** Removes the footing penalty from everyone (quake over) or from players no longer affected. */
    void clearStagger(boolean all, Set<UUID> keep) {
        var it = staggered.iterator();
        while (it.hasNext()) {
            UUID id = it.next();
            if (!all && keep.contains(id)) continue;
            Player p = level.getPlayerByUUID(id);
            if (p != null) {
                AttributeInstance speed = p.getAttribute(Attributes.MOVEMENT_SPEED);
                if (speed != null) speed.removeModifier(STAGGER);
            }
            it.remove();
        }
    }

    // ------------------------------------------------------------------ dense sampling where people are

    /** Dense sampling around players inside the zone, so what you can see reacts consistently. */
    void scanNearPlayers(double radius) {
        Vec3 c = quake.pos();
        for (Player p : level.players()) {
            if (p.isSpectator()) continue;
            double dx = p.getX() - c.x, dz = p.getZ() - c.z;
            if (dx * dx + dz * dz > radius * radius) continue;
            int n = Math.round(30 * quake.tremor());
            for (int i = 0; i < n; i++) {
                BlockPos bp = p.blockPosition().offset(random.nextInt(33) - 16, random.nextInt(17) - 6, random.nextInt(33) - 16);
                if (level.isLoaded(bp)) quake.evaluate(bp, 1.2f);
            }
        }
    }
}
