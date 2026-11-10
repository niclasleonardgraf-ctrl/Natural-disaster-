package com.realisticdisasters.disaster.volcano;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;

import com.realisticdisasters.config.DisasterConfig;
import com.realisticdisasters.disaster.Disaster;
import com.realisticdisasters.disaster.DisasterManager;
import com.realisticdisasters.disaster.DisasterProtection;
import com.realisticdisasters.disaster.DisasterType;
import com.realisticdisasters.disaster.Phase;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * An erupting volcanic vent. On flat ground it first piles up a small cinder cone. During the eruption it throws
 * {@link LavaBombEntity lava bombs}, fires periodic blasts, sends lava flows downhill and lets the lava cool into
 * basalt and blackstone later (batched). Ash, sky, sound and shake are client-side, driven by {@link #syncData()}.
 */
public class VolcanoDisaster extends Disaster {
    /** Indices into the sync data array (shared contract with the client visual). */
    public static final int D_VENT_HEIGHT = 0, D_PLUME_HEIGHT = 1, D_ASH_RADIUS = 2, D_BLASTS = 3, D_CONE_RADIUS = 4,
            D_WIND_X = 5, D_WIND_Z = 6;

    private final int ventX, ventZ;
    private int groundY;
    private final int coneRadius;
    private int coneHeight;
    private boolean buildCone;
    /** The cone / ground decision waits until the vent area is loaded (a disaster may start before its chunks). */
    private boolean terrainDecided;
    private int decideWait;
    private final int maxBombs;
    private final int maxLava;
    private final float plumeHeight, ashRadius;
    private final double windAngle;
    private final List<LavaBombEntity> bombs = new ArrayList<>();
    private final List<FlowHead> flows = new ArrayList<>();
    private final PriorityQueue<Cooling> cooling = new PriorityQueue<>((a, b) -> Long.compare(a.at, b.at));

    private int coneLayersQueued;
    private boolean ventPrepared;
    private int blasts;
    private int nextBlast = 60;
    private int bombsLaunched;
    private int lavaPlaced;
    private int lavaCooled;
    private int rockFormed;
    private int coneBlocks;
    private int flowsStarted;
    private int lightning;

    /** stage 0 = molten lava (crusts over into glowing magma), stage 1 = crust (turns into rock). */
    private record Cooling(BlockPos pos, long at, int stage) {}

    private static final class FlowHead {
        int x, z, steps, dirX, dirZ;
        FlowHead(int x, int z, int steps) {
            this.x = x;
            this.z = z;
            this.steps = steps;
        }
    }

    public VolcanoDisaster(ServerLevel level, int id, Vec3 pos, int intensity) {
        super(DisasterType.VOLCANO, level, id, pos, intensity);
        int i = this.intensity;
        this.ventX = Mth.floor(pos.x);
        this.ventZ = Mth.floor(pos.z);
        this.groundY = Mth.floor(pos.y) - 1; // pos sits on top of the surface block
        this.coneRadius = 4 + i;                 // 5 .. 9
        this.maxBombs = Math.max(3, DisasterConfig.VOLCANO_MAX_BOMBS.getAsInt() * i / 5);
        this.maxLava = 40 + 40 * i;
        this.plumeHeight = 50f + i * 22f;        // 72 .. 160
        this.ashRadius = 70f + i * 35f;          // 105 .. 245
        this.windAngle = random.nextDouble() * Math.PI * 2;
        this.pos = new Vec3(ventX + 0.5, groundY + 1, ventZ + 0.5);

        double scale = DisasterConfig.VOLCANO_DURATION_SCALE.get();
        setDuration(Phase.WARNING, 400);
        setDuration(Phase.BUILDUP, 300);
        setDuration(Phase.PEAK, (int) ((900 + 300 * i) * scale));
        setDuration(Phase.AFTERMATH, 500);
    }

    @Override
    public Component describe() {
        return Component.translatable("disaster.realisticdisasters.volcano");
    }

    @Override
    public float effectRadius() {
        return ashRadius;
    }

    /** y of the first air block above the crater floor (the crater's lava lake sits here, one block below the rim). */
    public int ventY() {
        return groundY + 1 + Math.max(0, coneHeight - 2);
    }

    @Override
    protected float[] syncData() {
        return new float[]{ventY() - (float) pos.y, plumeHeight, ashRadius, blasts, buildCone ? coneRadius : 0,
                (float) Math.cos(windAngle), (float) Math.sin(windAngle)};
    }

    // ------------------------------------------------------------------ terrain decision

    /** Decides (once) whether to build a cone and re-reads the ground height; false while the area isn't loaded. */
    private boolean decideTerrain() {
        if (terrainDecided) return true;
        Boolean flat = isFlat(coneRadius);
        if (flat == null && ++decideWait < 200) return false;
        terrainDecided = true;
        if (level.isLoaded(new BlockPos(ventX, 0, ventZ))) {
            groundY = surface(ventX, ventZ) - 1;
            pos = new Vec3(ventX + 0.5, groundY + 1, ventZ + 0.5);
        }
        buildCone = DisasterConfig.VOLCANO_BUILD_CONE.getAsBoolean() && Boolean.TRUE.equals(flat);
        coneHeight = buildCone ? 2 + intensity : 0;  // 3 .. 7
        return true;
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onPhaseChange(Phase from, Phase to) {
        if (to == Phase.BUILDUP) {
            broadcast(Component.translatable("disaster.realisticdisasters.volcano.onset").withStyle(ChatFormatting.RED), ashRadius + 60);
        }
        if (to.ordinal() >= Phase.PEAK.ordinal() && decideTerrain()) {
            // Skipped or short buildup: finish the cone before the eruption proper.
            queueConeLayers(coneHeight);
            prepareVent();
        }
    }

    @Override
    protected void onEnd() {
        bombs.clear();
        flows.clear();
        // Everything still molten cools now (still batched through the queue).
        var queue = DisasterManager.blockQueue(level);
        while (!cooling.isEmpty()) {
            Cooling c = cooling.poll();
            if (!queue.add(l -> cool(c, true))) break;
        }
    }

    @Override
    protected void tickDisaster() {
        if (age() == 1) {
            broadcast(Component.translatable("disaster.realisticdisasters.volcano.warning").withStyle(ChatFormatting.GOLD), ashRadius + 80);
        }
        if (age() % 20 == 0 && phase() != Phase.AFTERMATH) alertMobs();

        if (phase() != Phase.WARNING && !terrainDecided) {
            if (!decideTerrain()) return; // wait for the vent's chunks
            if (phase() != Phase.BUILDUP) {
                queueConeLayers(coneHeight);
                prepareVent();
            }
        }
        if (phase() == Phase.BUILDUP) {
            queueConeLayers(Mth.ceil(coneHeight * Math.min(1f, phaseProgress() * 1.25f)));
            if (phaseProgress() > 0.8f) prepareVent();
        }

        float s = strength();
        boolean erupting = phase() == Phase.PEAK || phase() == Phase.BUILDUP && phaseProgress() > 0.6f
                || phase() == Phase.AFTERMATH && phaseProgress() < 0.6f;
        if (erupting) {
            tickBombs(s);
            if (phase() == Phase.PEAK) {
                tickLightning();
                tickBlasts();
                tickFlows();
                if (age() % 2 == 0) scorchAround();
            }
        }
        pruneBombs();
        tickCooling();
    }

    // ------------------------------------------------------------------ terrain checks

    /** @return whether the ground within the radius is flat, or null if part of it isn't loaded */
    private Boolean isFlat(int radius) {
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int dx = -radius; dx <= radius; dx += 2) {
            for (int dz = -radius; dz <= radius; dz += 2) {
                if (dx * dx + dz * dz > radius * radius) continue;
                int x = ventX + dx, z = ventZ + dz;
                if (!level.isLoaded(new BlockPos(x, 0, z))) return null;
                int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                min = Math.min(min, h);
                max = Math.max(max, h);
            }
        }
        return max - min <= 3;
    }

    private int surface(int x, int z) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    }

    // ------------------------------------------------------------------ cone

    /** Target top height (relative to the ground) of the cone column at horizontal distance d. */
    private int coneColumnHeight(double d) {
        if (d > coneRadius) return 0;
        double crater = craterRadius();
        if (d < crater) return coneHeight - 2; // crater floor: the lava lake on it stays below the rim
        // Rim at full height, sloping down to the ground at the cone's foot.
        int h = (int) Math.round(coneHeight * (1 - (d - crater) / (coneRadius - crater + 0.5)) + 0.5);
        return Mth.clamp(h, 0, coneHeight);
    }

    private double craterRadius() {
        return 1.0 + intensity / 3.0;
    }

    private void queueConeLayers(int upTo) {
        if (!buildCone || !DisasterConfig.BLOCK_DESTRUCTION.getAsBoolean()) {
            coneLayersQueued = Math.max(coneLayersQueued, upTo);
            return;
        }
        var queue = DisasterManager.blockQueue(level);
        while (coneLayersQueued < upTo) {
            int layer = ++coneLayersQueued;
            int y = groundY + layer;
            for (int dx = -coneRadius; dx <= coneRadius; dx++) {
                for (int dz = -coneRadius; dz <= coneRadius; dz++) {
                    double d = Math.sqrt(dx * dx + dz * dz);
                    int h = coneColumnHeight(d);
                    if (h < layer) continue;
                    BlockPos p = new BlockPos(ventX + dx, y, ventZ + dz);
                    boolean craterFloor = layer == h && d < craterRadius();
                    boolean top = layer == h;
                    long seed = random.nextLong();
                    queue.add(l -> placeCone(p, craterFloor, top, d, seed));
                }
            }
        }
    }

    private void placeCone(BlockPos p, boolean craterFloor, boolean top, double d, long seed) {
        if (isFinished()) return;
        BlockState st = level.getBlockState(p);
        if (!st.isAir() && !st.canBeReplaced()) return;
        if (st.hasBlockEntity() || !DisasterProtection.canModify(level, p, st)) return;
        var rng = net.minecraft.util.RandomSource.create(seed);
        BlockState put;
        if (craterFloor) {
            put = Blocks.MAGMA_BLOCK.defaultBlockState();
        } else if (top) {
            float r = rng.nextFloat();
            boolean nearCrater = d < 2.5 + intensity / 3.0;
            put = nearCrater && r < 0.3f ? Blocks.MAGMA_BLOCK.defaultBlockState()
                    : r < 0.5f ? Blocks.BLACKSTONE.defaultBlockState()
                    : r < 0.75f ? Blocks.BASALT.defaultBlockState()
                    : r < 0.9f ? Blocks.TUFF.defaultBlockState()
                    : Blocks.COBBLED_DEEPSLATE.defaultBlockState();
        } else {
            put = rng.nextFloat() < 0.6f ? Blocks.TUFF.defaultBlockState() : Blocks.BLACKSTONE.defaultBlockState();
        }
        level.setBlock(p, put, 3);
        coneBlocks++;
    }

    /** Opens the vent: a lava pool in the crater (or a scorched magma throat on uneven ground). */
    private void prepareVent() {
        if (ventPrepared) return;
        ventPrepared = true;
        if (!DisasterConfig.BLOCK_DESTRUCTION.getAsBoolean()) return;
        var queue = DisasterManager.blockQueue(level);
        int vy = ventY();
        if (!buildCone) {
            // Natural slopes: melt the ground around the vent.
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int x = ventX + dx, z = ventZ + dz;
                    BlockPos p = new BlockPos(x, surface(x, z) - 1, z);
                    queue.add(l -> {
                        BlockState st = level.getBlockState(p);
                        float hardness = st.getDestroySpeed(level, p);
                        if (st.isAir() || st.hasBlockEntity() || hardness < 0 || hardness > 50) return;
                        if (!DisasterProtection.canModify(level, p, st)) return;
                        level.setBlock(p, Blocks.MAGMA_BLOCK.defaultBlockState(), 3);
                    });
                }
            }
            queue.add(l -> placeLava(new BlockPos(ventX, surface(ventX, ventZ), ventZ)));
        } else {
            queue.add(l -> placeLava(new BlockPos(ventX, vy, ventZ)));
        }
    }

    // ------------------------------------------------------------------ bombs and blasts

    private Vec3 ventTop() {
        return new Vec3(ventX + 0.5, ventY() + 0.6, ventZ + 0.5);
    }

    private void tickBombs(float s) {
        float rate = (0.03f + 0.025f * intensity) * s;
        if (random.nextFloat() < rate) launchBomb(1f);
    }

    private void launchBomb(float power) {
        pruneBombs();
        if (bombs.size() >= maxBombs) return;
        Vec3 from = ventTop();
        if (!level.isLoaded(BlockPos.containing(from))) return;
        double a = random.nextDouble() * Math.PI * 2;
        double h = (0.12 + random.nextDouble() * 0.38) * (0.7 + intensity * 0.1) * power;
        double v = (0.9 + random.nextDouble() * 0.6) * (0.85 + intensity * 0.05) * power;
        Vec3 vel = new Vec3(Math.cos(a) * h, v, Math.sin(a) * h);
        bombs.add(LavaBombEntity.launch(level, from.add(vel.x * 2, 0.4, vel.z * 2), vel, intensity));
        bombsLaunched++;
    }

    private void pruneBombs() {
        bombs.removeIf(LavaBombEntity::isRemoved);
    }

    private void tickBlasts() {
        if (--nextBlast > 0) return;
        nextBlast = Math.max(30, 200 - intensity * 30) + random.nextInt(100);
        blasts++;
        int burst = 2 + intensity + random.nextInt(intensity + 1);
        for (int i = 0; i < burst; i++) launchBomb(1.15f);
        Vec3 v = ventTop();
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.EXPLOSION_EMITTER, v.x, v.y + 1, v.z, 1, 0, 0, 0, 0);
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.LAVA, v.x, v.y + 0.5, v.z, 20 + intensity * 6, 1.2, 0.6, 1.2, 0);
        // Blast wave: knock nearby entities back and singe those right at the vent.
        double r = 6 + intensity * 2;
        AABB box = new AABB(v.x - r, v.y - r, v.z - r, v.x + r, v.y + r, v.z + r);
        for (var e : level.getEntities((net.minecraft.world.entity.Entity) null, box, e -> e instanceof net.minecraft.world.entity.LivingEntity && !e.isSpectator())) {
            Vec3 away = e.position().subtract(v);
            double d = away.length();
            if (d > r || d < 1.0e-3) continue;
            double push = (1 - d / r) * (0.6 + intensity * 0.15);
            e.setDeltaMovement(e.getDeltaMovement().add(away.normalize().scale(push).add(0, push * 0.4, 0)));
            e.hurtMarked = true;
            if (d < r * 0.5) {
                ((net.minecraft.world.entity.LivingEntity) e).hurtServer(level,
                        level.damageSources().source(com.realisticdisasters.registry.ModRegistries.VOLCANO_DAMAGE), 2f + intensity);
            }
        }
    }

    // ------------------------------------------------------------------ volcanic lightning

    /**
     * Ash particles colliding in the column charge it like a thunderstorm ("dirty thunderstorm"): frequent bolts
     * near the vent. Visual only (no fire); vanilla handles the flash and thunder.
     */
    private void tickLightning() {
        if (!DisasterConfig.VOLCANO_LIGHTNING.getAsBoolean() || intensity < 2) return;
        if (random.nextInt(Math.max(30, 230 - intensity * 40)) != 0) return;
        double a = random.nextDouble() * Math.PI * 2;
        double d = coneRadius * 0.6 + random.nextDouble() * (8 + intensity * 4);
        double x = ventX + 0.5 + Math.cos(a) * d + Math.cos(windAngle) * random.nextDouble() * 10;
        double z = ventZ + 0.5 + Math.sin(a) * d + Math.sin(windAngle) * random.nextDouble() * 10;
        BlockPos col = BlockPos.containing(x, 0, z);
        if (!level.isLoaded(col)) return;
        var bolt = net.minecraft.world.entity.EntityType.LIGHTNING_BOLT.create(level, net.minecraft.world.entity.EntitySpawnReason.EVENT);
        if (bolt == null) return;
        bolt.setVisualOnly(true);
        bolt.snapTo(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, col.getX(), col.getZ()), z);
        level.addFreshEntity(bolt);
        lightning++;
    }

    // ------------------------------------------------------------------ lava flows

    private void tickFlows() {
        if (!DisasterConfig.VOLCANO_LAVA_FLOWS.getAsBoolean() || !DisasterConfig.BLOCK_DESTRUCTION.getAsBoolean()) return;
        int maxHeads = 1 + intensity / 2;
        if (flows.size() < maxHeads && lavaPlaced < maxLava && (flowsStarted == 0 || random.nextInt(120) == 0)) {
            // Like real cinder cones, flows break out at the foot of the cone instead of pouring over the rim.
            int fx = ventX, fz = ventZ;
            if (buildCone) {
                double a = random.nextDouble() * Math.PI * 2;
                fx = Mth.floor(ventX + 0.5 + Math.cos(a) * (coneRadius + 1));
                fz = Mth.floor(ventZ + 0.5 + Math.sin(a) * (coneRadius + 1));
                BlockPos breach = new BlockPos(fx, surface(fx, fz), fz);
                DisasterManager.blockQueue(level).add(l -> placeLava(breach));
            }
            flows.add(new FlowHead(fx, fz, 10 + intensity * 6));
            flowsStarted++;
        }
        int interval = Math.max(3, 8 - intensity);
        if (age() % interval != 0) return;
        var queue = DisasterManager.blockQueue(level);
        var it = flows.iterator();
        while (it.hasNext()) {
            FlowHead f = it.next();
            if (f.steps-- <= 0 || lavaPlaced >= maxLava || !advance(f)) {
                it.remove();
                continue;
            }
            BlockPos target = new BlockPos(f.x, surface(f.x, f.z), f.z);
            queue.add(l -> placeLava(target));
            // Flows spread sideways into lobes as they go (wider on flat ground, narrower in channels).
            if (random.nextFloat() < 0.45f) {
                int side = random.nextBoolean() ? 1 : -1;
                int lx = f.x + f.dirZ * side, lz = f.z - f.dirX * side;
                if (level.isLoaded(new BlockPos(lx, 0, lz)) && surface(lx, lz) <= surface(f.x, f.z)) {
                    BlockPos lobe = new BlockPos(lx, surface(lx, lz), lz);
                    queue.add(l -> placeLava(lobe));
                }
            }
        }
    }

    /** Moves a flow head one block downhill (or level, preferring its current direction). */
    private boolean advance(FlowHead f) {
        int here = surface(f.x, f.z);
        int bestScore = Integer.MAX_VALUE;
        int bx = 0, bz = 0;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            int x = f.x + d[0], z = f.z + d[1];
            if (!level.isLoaded(new BlockPos(x, 0, z))) continue;
            int h = surface(x, z);
            if (h > here) continue;
            // lower is better; keep momentum; small randomness to braid the flow
            int score = (h - here) * 10 - (d[0] == f.dirX && d[1] == f.dirZ ? 2 : 0) + random.nextInt(8);
            BlockState top = level.getBlockState(new BlockPos(x, h - 1, z));
            if (top.is(Blocks.LAVA)) score += 6; // prefer fresh ground over our own lava
            if (score < bestScore) {
                bestScore = score;
                bx = d[0];
                bz = d[1];
            }
        }
        if (bestScore == Integer.MAX_VALUE) return false;
        f.x += bx;
        f.z += bz;
        f.dirX = bx;
        f.dirZ = bz;
        return true;
    }

    private void placeLava(BlockPos p) {
        if (isFinished()) return;
        BlockState st = level.getBlockState(p);
        if (!(st.isAir() || st.canBeReplaced()) || st.hasBlockEntity()) return;
        if (st.getBlock() instanceof LiquidBlock && st.getFluidState().isSource()) return;
        if (!DisasterProtection.canModify(level, p, st)) return;
        // Keep a margin from protected ground: vanilla lava spreads a few blocks from each source.
        for (int[] d : new int[][]{{3, 0}, {-3, 0}, {0, 3}, {0, -3}}) {
            BlockPos q = p.offset(d[0], 0, d[1]);
            if (!DisasterProtection.canModify(level, q, level.getBlockState(q))) return;
        }
        level.setBlock(p, Blocks.LAVA.defaultBlockState(), 3);
        lavaPlaced++;
        long delay = DisasterConfig.VOLCANO_COOLING_TICKS.getAsInt();
        cooling.add(new Cooling(p, level.getGameTime() + delay + random.nextInt((int) Math.max(1, delay / 3)), 0));
    }

    // ------------------------------------------------------------------ cooling

    private void tickCooling() {
        if (cooling.isEmpty()) return;
        long now = level.getGameTime();
        var queue = DisasterManager.blockQueue(level);
        for (int n = 0; n < 8 && !cooling.isEmpty() && cooling.peek().at() <= now; n++) {
            Cooling c = cooling.poll();
            if (!queue.add(l -> cool(c, false))) {
                cooling.add(c);
                return;
            }
        }
    }

    /**
     * Cools a lava source we placed, like a real flow: first the surface crusts over but still glows through the
     * cracks (magma block), later it solidifies into rock. Flowing lava fed by the source drains away on its own.
     * {@code finish} skips straight to rock (used when the volcano ends).
     */
    private void cool(Cooling c, boolean finish) {
        BlockPos p = c.pos();
        BlockState st = level.getBlockState(p);
        if (c.stage() == 0 && st.is(Blocks.LAVA)) {
            if (!DisasterProtection.canModify(level, p, st)) return;
            lavaCooled++;
            level.levelEvent(1501, p, 0); // hiss + smoke
            if (!finish) {
                level.setBlock(p, Blocks.MAGMA_BLOCK.defaultBlockState(), 3);
                long delay = Math.max(20, DisasterConfig.VOLCANO_COOLING_TICKS.getAsInt() / 2);
                cooling.add(new Cooling(p, level.getGameTime() + delay + random.nextInt((int) Math.max(1, delay / 2)), 1));
                return;
            }
        } else if (!(c.stage() == 1 && st.is(Blocks.MAGMA_BLOCK))) {
            return; // something else happened to the block meanwhile
        }
        if (!DisasterProtection.canModify(level, p, st)) return;
        float r = level.getRandom().nextFloat();
        boolean nearVent = Math.abs(p.getX() - ventX) <= 1 && Math.abs(p.getZ() - ventZ) <= 1;
        BlockState rock = nearVent && r < 0.4f ? Blocks.MAGMA_BLOCK.defaultBlockState()
                : r < 0.6f ? Blocks.BASALT.defaultBlockState()
                : r < 0.95f ? Blocks.BLACKSTONE.defaultBlockState()
                : Blocks.MAGMA_BLOCK.defaultBlockState();
        level.setBlock(p, rock, 3);
        rockFormed++;
    }

    // ------------------------------------------------------------------ heat damage around the vent

    /**
     * Tephra apron: scoria and ash rain down thickest at the foot of the cone, so samples concentrate there. Near the
     * cone this builds a continuous black scoria blanket; farther out it thins into scorched, ash-covered soil.
     */
    private void scorchAround() {
        if (!DisasterConfig.BLOCK_DESTRUCTION.getAsBoolean()) return;
        double a = random.nextDouble() * Math.PI * 2;
        double apron = 2 + intensity;
        double rr = (buildCone ? coneRadius : 2) + Math.abs(random.nextGaussian()) * apron;
        int x = Mth.floor(ventX + 0.5 + Math.cos(a) * rr), z = Mth.floor(ventZ + 0.5 + Math.sin(a) * rr);
        if (!level.isLoaded(new BlockPos(x, 0, z))) return;
        BlockPos p = new BlockPos(x, level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1, z);
        DisasterManager.blockQueue(level).add(l -> {
            if (isFinished()) return;
            BlockState st = level.getBlockState(p);
            if (!DisasterProtection.canModify(level, p, st)) return;
            boolean soil = st.is(Blocks.GRASS_BLOCK) || st.is(Blocks.PODZOL) || st.is(Blocks.MYCELIUM)
                    || st.is(Blocks.DIRT) || st.is(Blocks.COARSE_DIRT);
            double d = Math.sqrt((p.getX() - ventX) * (p.getX() - ventX) + (p.getZ() - ventZ) * (p.getZ() - ventZ));
            if (soil && d < (buildCone ? coneRadius : 2) + apron) {
                level.setBlock(p, level.getRandom().nextFloat() < 0.8f ? Blocks.BLACKSTONE.defaultBlockState()
                        : Blocks.TUFF.defaultBlockState(), 3);
            } else if (soil && !st.is(Blocks.COARSE_DIRT)) {
                level.setBlock(p, Blocks.COARSE_DIRT.defaultBlockState(), 3);
            } else if (st.canBeReplaced() && !st.isAir() && !(st.getBlock() instanceof LiquidBlock) || st.is(BlockTags.SNOW)) {
                level.destroyBlock(p, false);
            }
        });
    }

    // ------------------------------------------------------------------ mob reactions

    private void alertMobs() {
        double r = coneRadius + 30 + intensity * 10 + (phase() == Phase.WARNING ? 30 : 0);
        AABB box = new AABB(pos.x - r, pos.y - 24, pos.z - r, pos.x + r, pos.y + 40, pos.z + r);
        for (PathfinderMob mob : level.getEntitiesOfClass(PathfinderMob.class, box, m -> !(m instanceof Enemy))) {
            if (mob instanceof Villager villager) {
                villager.getBrain().setMemory(MemoryModuleType.HEARD_BELL_TIME, level.getGameTime());
                continue;
            }
            if (mob.isLeashed() || mob.isPassenger()) continue;
            Vec3 away = new Vec3(mob.getX() - pos.x, 0, mob.getZ() - pos.z);
            away = away.lengthSqr() < 1.0e-4 ? new Vec3(1, 0, 0) : away.normalize();
            Vec3 target = mob.position().add(away.scale(16 + random.nextInt(10)));
            mob.getNavigation().moveTo(target.x, target.y, target.z, phase() == Phase.WARNING ? 1.2 : 1.6);
        }
    }

    private void broadcast(Component message, double range) {
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(pos.x, p.getY(), pos.z) < range * range) p.sendOverlayMessage(message);
        }
    }

    // ------------------------------------------------------------------ test / debug accessors

    public int bombsLaunched() { return bombsLaunched; }
    public int lavaPlaced() { return lavaPlaced; }
    public int lavaCooled() { return lavaCooled; }
    public int rockFormed() { return rockFormed; }
    public int lightningStrikes() { return lightning; }
    public int coneBlocks() { return coneBlocks; }
    public boolean hasCone() { return buildCone; }
    public int blasts() { return blasts; }
}
