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
import com.realisticdisasters.registry.ModRegistries;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
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
 * An erupting stratovolcano. Its size scales with intensity: a ~10-block cinder cone at intensity 1 up to a ~130-block
 * mountain at intensity 5 that grows out of the ground during the eruption ({@link VolcanoEdifice}).
 * <p>
 * Server-side physics: lava bombs ({@link LavaBombEntity}), explosive blasts with a pressure wave, a lava lake in the
 * summit crater that overflows into lava rivers running down the flanks and cooling in two stages, pyroclastic density
 * currents racing down the mountain ({@link PyroclasticFlow}), ash fall-out blanketing the land downwind
 * ({@link AshFall}), snow and ice melting into meltwater floods, steaming ground fissures as precursors, volcanic
 * lightning, fleeing mobs and the ground rising under whatever stands on it. All block changes are batched and checked
 * against protection. Visuals and sound are client-side, driven by {@link #syncData()}.
 */
public class VolcanoDisaster extends Disaster {
    /** Indices into the sync data array (shared contract with the client visual). */
    public static final int D_VENT_HEIGHT = 0, D_PLUME_HEIGHT = 1, D_ASH_RADIUS = 2, D_BLASTS = 3, D_CONE_RADIUS = 4,
            D_WIND_X = 5, D_WIND_Z = 6, D_PDC_RADIUS = 7, D_PDC_ANGLE = 8, D_PDC_WIDTH = 9, D_PDC_ON = 10,
            D_GROWTH = 11, D_MOUNTAIN_HEIGHT = 12, D_PDC_HEAD = 13;

    private int ventX, ventZ;
    private int groundY;
    private VolcanoEdifice edifice;
    private boolean terrainDecided;
    private int decideWait;
    private final int maxBombs;
    private final int maxLava;
    private final float plumeHeight, ashRadius;
    private final double windAngle;
    private final List<LavaBombEntity> bombs = new ArrayList<>();
    private final List<FlowHead> flows = new ArrayList<>();
    private final PriorityQueue<Cooling> cooling = new PriorityQueue<>((a, b) -> Long.compare(a.at, b.at));
    private PyroclasticFlow pdc;
    private boolean collapsed;
    /** Test hook: keeps everything within this radius of the vent (GameTest arenas sit close together). */
    private double confine = Double.MAX_VALUE;

    private int blasts;
    private int nextBlast = 60;
    private int bombsLaunched;
    private int lavaPlaced;
    private int lavaCooled;
    private int rockFormed;
    private int flowsStarted;
    private int lightning;
    private int pdcCount;
    private int fissures;

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
        moveAwayFromPlayers();
        this.groundY = Mth.floor(this.pos.y) - 1;
        this.maxBombs = Math.max(3, DisasterConfig.VOLCANO_MAX_BOMBS.getAsInt() * i / 5);
        this.maxLava = 60 + 90 * i;                // lava sources per eruption
        this.plumeHeight = 60f + i * 45f;          // 105 .. 285 above the vent
        this.ashRadius = 80f + i * 50f;            // 130 .. 330
        this.windAngle = random.nextDouble() * Math.PI * 2;
        this.pos = new Vec3(ventX + 0.5, groundY + 1, ventZ + 0.5);

        double scale = DisasterConfig.VOLCANO_DURATION_SCALE.get();
        setDuration(Phase.WARNING, 400);
        setDuration(Phase.BUILDUP, 300 + 60 * i);
        setDuration(Phase.PEAK, (int) ((900 + 300 * i) * scale));
        setDuration(Phase.AFTERMATH, 500 + 100 * i);
    }

    /** Final mountain height above its base for an intensity (see {@link VolcanoEdifice}). */
    public static int plannedHeight(int intensity) {
        return VolcanoEdifice.HEIGHT[Mth.clamp(intensity, 1, 5)];
    }

    /** Final mountain radius for an intensity (see {@link VolcanoEdifice}). */
    public static double plannedRadius(int intensity) {
        return plannedHeight(intensity) * 1.5 + 2;
    }

    /**
     * A big volcano started right next to a player would swallow them: move the vent so the nearest player ends up on
     * the lower flank, if that spot is loaded.
     */
    private void moveAwayFromPlayers() {
        double need = plannedRadius(intensity) * 0.7 + 8;
        ServerPlayer nearest = null;
        double best = Double.MAX_VALUE;
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator()) continue;
            double d = Math.hypot(p.getX() - (ventX + 0.5), p.getZ() - (ventZ + 0.5));
            if (d < best) {
                best = d;
                nearest = p;
            }
        }
        if (nearest == null || best >= need) return;
        Vec3 dir = new Vec3(ventX + 0.5 - nearest.getX(), 0, ventZ + 0.5 - nearest.getZ());
        dir = dir.lengthSqr() < 1.0e-4 ? new Vec3(1, 0, 0) : dir.normalize();
        for (double d = need; d > best; d -= 8) {
            int x = Mth.floor(nearest.getX() + dir.x * d), z = Mth.floor(nearest.getZ() + dir.z * d);
            if (level.hasChunkAt(new BlockPos(x, 0, z))) {
                ventX = x;
                ventZ = z;
                pos = new Vec3(x + 0.5, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z + 0.5);
                return;
            }
        }
    }

    @Override
    public Component describe() {
        return Component.translatable("disaster.realisticdisasters.volcano");
    }

    @Override
    public float effectRadius() {
        double mountain = edifice != null ? edifice.radius : plannedRadius(intensity);
        return (float) Math.max(ashRadius, mountain * 1.4 + 60);
    }

    private boolean building() {
        return edifice != null && DisasterConfig.VOLCANO_BUILD_CONE.getAsBoolean() && DisasterConfig.BLOCK_DESTRUCTION.getAsBoolean();
    }

    /** y of the first air block above the crater floor (where the lava lake and the vent are). */
    public int ventY() {
        return building() ? edifice.ventY() : groundY + 1;
    }

    private int craterRadius() {
        return building() ? (int) Math.ceil(edifice.craterRadius()) : 1;
    }

    @Override
    protected float[] syncData() {
        PyroclasticFlow f = pdc;
        return new float[]{ventY() - (float) pos.y, plumeHeight, ashRadius, blasts,
                building() ? (float) (edifice.radius * edifice.scale()) : 0f,
                (float) Math.cos(windAngle), (float) Math.sin(windAngle),
                f != null ? (float) f.radius : 0f, f != null ? (float) f.angle : 0f, f != null ? (float) f.halfWidth : 0f,
                f != null ? 1f : 0f, building() ? edifice.scale() : 0f,
                building() ? (float) edifice.rimHeight() : 0f, f != null ? (float) f.headHeight() : 0f};
    }

    // ------------------------------------------------------------------ terrain decision

    /** Creates the edifice once the vent area is loaded (a disaster may start before its chunks). */
    private boolean decideTerrain() {
        if (terrainDecided) return true;
        boolean loaded = level.isLoaded(new BlockPos(ventX, 0, ventZ));
        if (!loaded && ++decideWait < 200) return false;
        terrainDecided = true;
        if (loaded) {
            groundY = surface(ventX, ventZ) - 1;
            pos = new Vec3(ventX + 0.5, groundY + 1, ventZ + 0.5);
        }
        edifice = new VolcanoEdifice(level, random, ventX, ventZ, groundY + 1, intensity,
                DisasterConfig.VOLCANO_BUILD_CONE.getAsBoolean());
        return true;
    }

    private int surface(int x, int z) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onPhaseChange(Phase from, Phase to) {
        if (to == Phase.BUILDUP) {
            broadcast(Component.translatable("disaster.realisticdisasters.volcano.onset").withStyle(ChatFormatting.RED), effectRadius() + 60);
        }
    }

    @Override
    protected void onEnd() {
        bombs.clear();
        flows.clear();
        pdc = null;
        // Everything still molten cools now (still batched through the queue).
        var queue = DisasterManager.blockQueue(level);
        while (!cooling.isEmpty()) {
            Cooling c = cooling.poll();
            if (!queue.add(l -> cool(c, true))) break;
        }
    }

    /** Growth of the mountain over the eruption: a first cone in the buildup, the bulk during the peak. */
    private float growthTarget() {
        float p = phaseProgress();
        return switch (phase()) {
            case WARNING -> 0f;
            case BUILDUP -> 0.25f * smooth(p);
            case PEAK -> 0.25f + 0.75f * smooth(Math.min(1f, p / 0.9f));
            case AFTERMATH -> 1f;
        };
    }

    @Override
    protected void tickDisaster() {
        if (age() == 1) {
            broadcast(Component.translatable("disaster.realisticdisasters.volcano.warning").withStyle(ChatFormatting.GOLD), effectRadius() + 80);
        }
        if (age() % 20 == 0 && phase() != Phase.AFTERMATH) alertMobs();
        if (phase() == Phase.WARNING) {
            tickFissures();
            tickCooling();
            return;
        }
        if (!decideTerrain()) return;

        float growth = growthTarget();
        if (confine < Double.MAX_VALUE) growth = (float) Math.min(growth, confine / edifice.radius);
        edifice.setScale(growth);
        if (building()) {
            edifice.tick();
            if (age() % 4 == 0) edifice.liftEntities();
        }

        float s = strength();
        boolean erupting = phase() == Phase.PEAK || phase() == Phase.BUILDUP && phaseProgress() > 0.5f
                || phase() == Phase.AFTERMATH && phaseProgress() < 0.5f;
        if (erupting) {
            tickBombs(s);
            if (age() % 60 == 0) feedLavaLake();
        }
        if (phase() == Phase.PEAK) {
            tickLightning();
            tickBlasts();
            tickFlows();
            if (age() % 2 == 0) scorchAround();
            meltSnow();
            if (intensity >= 5 && !collapsed && phaseProgress() > 0.55f) {
                collapsed = true;
                startPyroclasticFlow(true); // the climax: the whole column collapses
            }
        }
        if ((phase() == Phase.PEAK || phase() == Phase.AFTERMATH && phaseProgress() < 0.5f) && DisasterConfig.VOLCANO_ASH_FALL.getAsBoolean()) {
            AshFall.deposit(level, random, ventX + 0.5, ventZ + 0.5, windAngle, Math.min(ashRadius, confine), intensity, s, 1 + intensity / 2);
        }
        if (pdc != null) {
            pdc.tick();
            if (pdc.done()) pdc = null;
        }
        pruneBombs();
        tickCooling();
    }

    // ------------------------------------------------------------------ precursors

    /** Ground deformation: fissures tear open around the future vent and steam, glowing at the bottom. */
    private void tickFissures() {
        if (!DisasterConfig.BLOCK_DESTRUCTION.getAsBoolean() || fissures >= 1 + intensity / 2) return;
        if (age() < 120 || random.nextInt(140) != 0) return;
        fissures++;
        double a = random.nextDouble() * Math.PI * 2;
        double start = 2 + random.nextDouble() * 4;
        int length = 6 + intensity * 4;
        var queue = DisasterManager.blockQueue(level);
        for (int k = 0; k < length; k++) {
            double r = start + k, wobble = (random.nextDouble() - 0.5) * 0.25;
            int x = Mth.floor(ventX + 0.5 + Math.cos(a + wobble) * r), z = Mth.floor(ventZ + 0.5 + Math.sin(a + wobble) * r);
            if (!level.isLoaded(new BlockPos(x, 0, z))) continue;
            int depth = k < length / 2 ? 2 : 1;
            queue.add(l -> {
                int top = surface(x, z) - 1;
                for (int d = 0; d < depth; d++) {
                    BlockPos p = new BlockPos(x, top - d, z);
                    BlockState st = level.getBlockState(p);
                    if (st.hasBlockEntity() || st.getDestroySpeed(level, p) < 0 || !DisasterProtection.canModify(level, p, st)) return;
                    level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
                }
                BlockPos bottom = new BlockPos(x, top - depth, z);
                BlockState st = level.getBlockState(bottom);
                if (!st.isAir() && st.getDestroySpeed(level, bottom) >= 0 && DisasterProtection.canModify(level, bottom, st)) {
                    level.setBlock(bottom, Blocks.MAGMA_BLOCK.defaultBlockState(), 3);
                }
            });
        }
    }

    // ------------------------------------------------------------------ crater, bombs and blasts

    private Vec3 ventTop() {
        return new Vec3(ventX + 0.5, ventY() + 0.6, ventZ + 0.5);
    }

    /** Keeps a lava lake in the summit crater; as the mountain grows, the crater floor rises and buries the old lake. */
    private void feedLavaLake() {
        if (!DisasterConfig.BLOCK_DESTRUCTION.getAsBoolean()) return;
        int vy = ventY();
        int cr = Math.max(0, craterRadius() - 1);
        var queue = DisasterManager.blockQueue(level);
        for (int dx = -cr; dx <= cr; dx++)
            for (int dz = -cr; dz <= cr; dz++) {
                if (dx * dx + dz * dz > cr * cr || (dx != 0 || dz != 0) && random.nextFloat() < 0.6f) continue;
                int x = ventX + dx, z = ventZ + dz;
                queue.add(l -> placeLava(new BlockPos(x, Math.min(vy, surface(x, z)), z)));
            }
    }

    private void tickBombs(float s) {
        float rate = (0.03f + 0.03f * intensity) * s;
        if (random.nextFloat() < rate) launchBomb(1f);
    }

    private void launchBomb(float power) {
        pruneBombs();
        if (bombs.size() >= maxBombs) return;
        Vec3 from = ventTop();
        if (!level.isLoaded(BlockPos.containing(from))) return;
        double a = random.nextDouble() * Math.PI * 2;
        double reach = (0.85 + intensity * 0.1) * Math.min(1.0, confine / 60.0);
        double h = (0.12 + random.nextDouble() * 0.38) * reach * power;
        double v = (0.9 + random.nextDouble() * 0.7) * reach * power;
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
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, v.x, v.y + 1, v.z, 1, 0, 0, 0, 0);
        level.sendParticles(ParticleTypes.LAVA, v.x, v.y + 0.5, v.z, 20 + intensity * 6, 1.2, 0.6, 1.2, 0);
        // Pressure wave: knocks everything near the crater back and burns what is right at the vent.
        double r = 8 + intensity * 4;
        AABB box = new AABB(v.x - r, v.y - r, v.z - r, v.x + r, v.y + r, v.z + r);
        for (Entity e : level.getEntities((Entity) null, box, e -> !e.isSpectator())) {
            Vec3 away = e.position().subtract(v);
            double d = away.length();
            if (d > r || d < 1.0e-3) continue;
            double push = (1 - d / r) * (0.6 + intensity * 0.2);
            e.setDeltaMovement(e.getDeltaMovement().add(away.normalize().scale(push).add(0, push * 0.4, 0)));
            e.hurtMarked = true;
            if (d < r * 0.5 && e instanceof LivingEntity le) {
                le.hurtServer(level, level.damageSources().source(ModRegistries.VOLCANO_DAMAGE), 2f + intensity);
            }
        }
        // Explosive eruptions can collapse part of the column into a pyroclastic flow.
        float chance = intensity >= 5 ? 0.45f : intensity == 4 ? 0.3f : intensity == 3 ? 0.12f : 0f;
        if (pdc == null && random.nextFloat() < chance) startPyroclasticFlow(false);
    }

    private void startPyroclasticFlow(boolean fullCollapse) {
        if (pdc != null && !fullCollapse || !DisasterConfig.VOLCANO_PYROCLASTIC_FLOWS.getAsBoolean()) return;
        double mountain = edifice != null ? edifice.radius * Math.max(0.3f, edifice.scale()) : 10;
        double maxR = Math.min(confine, mountain * 1.2 + 40 + intensity * 15);
        double halfWidth = fullCollapse ? Math.PI : 0.45 + random.nextDouble() * 0.6;
        pdc = new PyroclasticFlow(level, random, intensity, ventX, ventZ, craterRadius() + 1, maxR,
                random.nextDouble() * Math.PI * 2, halfWidth, ventY());
        pdcCount++;
        broadcast(Component.translatable("disaster.realisticdisasters.volcano.pyroclastic").withStyle(ChatFormatting.DARK_RED), maxR + 80);
    }

    // ------------------------------------------------------------------ volcanic lightning

    /**
     * Ash particles colliding in the column charge it like a thunderstorm ("dirty thunderstorm"): frequent bolts
     * around the vent. Visual only (no fire); vanilla handles the flash and thunder.
     */
    private void tickLightning() {
        if (!DisasterConfig.VOLCANO_LIGHTNING.getAsBoolean() || intensity < 2) return;
        if (random.nextInt(Math.max(30, 230 - intensity * 40)) != 0) return;
        double a = random.nextDouble() * Math.PI * 2;
        double d = craterRadius() + 2 + random.nextDouble() * (8 + intensity * 6);
        double x = ventX + 0.5 + Math.cos(a) * d + Math.cos(windAngle) * random.nextDouble() * 10;
        double z = ventZ + 0.5 + Math.sin(a) * d + Math.sin(windAngle) * random.nextDouble() * 10;
        BlockPos col = BlockPos.containing(x, 0, z);
        if (!level.isLoaded(col)) return;
        var bolt = EntityType.LIGHTNING_BOLT.create(level, EntitySpawnReason.EVENT);
        if (bolt == null) return;
        bolt.setVisualOnly(true);
        bolt.snapTo(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, col.getX(), col.getZ()), z);
        level.addFreshEntity(bolt);
        lightning++;
    }

    // ------------------------------------------------------------------ lava flows

    /** Lava overflows the crater rim and runs down the flanks as rivers that braid and spread into lobes. */
    private void tickFlows() {
        if (!DisasterConfig.VOLCANO_LAVA_FLOWS.getAsBoolean() || !DisasterConfig.BLOCK_DESTRUCTION.getAsBoolean()) return;
        int maxHeads = 1 + intensity / 2;
        if (flows.size() < maxHeads && lavaPlaced < maxLava && (flowsStarted == 0 || random.nextInt(100) == 0)) {
            double a = random.nextDouble() * Math.PI * 2;
            double r = craterRadius() + 1;
            int fx = Mth.floor(ventX + 0.5 + Math.cos(a) * r), fz = Mth.floor(ventZ + 0.5 + Math.sin(a) * r);
            if (level.isLoaded(new BlockPos(fx, 0, fz))) {
                BlockPos breach = new BlockPos(fx, surface(fx, fz), fz);
                DisasterManager.blockQueue(level).add(l -> placeLava(breach));
                double mountain = edifice != null ? edifice.radius * edifice.scale() : 10;
                flows.add(new FlowHead(fx, fz, (int) Math.min(confine, mountain * 1.3 + 12 + intensity * 6)));
                flowsStarted++;
            }
        }
        int interval = Math.max(2, 7 - intensity);
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

    // ------------------------------------------------------------------ heat around the mountain

    /**
     * Tephra apron: scoria rains down thickest at the foot of the cone, so samples concentrate there. Near the cone
     * this builds a continuous black scoria blanket; farther out it thins into scorched ground.
     */
    private void scorchAround() {
        if (!DisasterConfig.BLOCK_DESTRUCTION.getAsBoolean()) return;
        double a = random.nextDouble() * Math.PI * 2;
        double foot = building() ? edifice.radius * edifice.scale() : 2;
        double apron = 3 + intensity * 2;
        double rr = foot + Math.abs(random.nextGaussian()) * apron;
        int x = Mth.floor(ventX + 0.5 + Math.cos(a) * rr), z = Mth.floor(ventZ + 0.5 + Math.sin(a) * rr);
        if (!level.isLoaded(new BlockPos(x, 0, z))) return;
        BlockPos p = new BlockPos(x, level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1, z);
        DisasterManager.blockQueue(level).add(l -> {
            if (isFinished()) return;
            BlockState st = level.getBlockState(p);
            if (!DisasterProtection.canModify(level, p, st)) return;
            boolean soil = st.is(Blocks.GRASS_BLOCK) || st.is(Blocks.PODZOL) || st.is(Blocks.MYCELIUM)
                    || st.is(Blocks.DIRT) || st.is(Blocks.COARSE_DIRT);
            double d = Math.hypot(p.getX() - ventX, p.getZ() - ventZ);
            if (soil && d < foot + apron * 0.6) {
                level.setBlock(p, level.getRandom().nextFloat() < 0.8f ? Blocks.BLACKSTONE.defaultBlockState()
                        : Blocks.TUFF.defaultBlockState(), 3);
            } else if (soil && !st.is(Blocks.COARSE_DIRT)) {
                level.setBlock(p, Blocks.COARSE_DIRT.defaultBlockState(), 3);
            } else if (st.canBeReplaced() && !st.isAir() && !(st.getBlock() instanceof LiquidBlock) && !st.is(ModRegistries.VOLCANIC_ASH.get())) {
                level.destroyBlock(p, false);
            }
        });
    }

    /** Heat and ash melt snow and ice on and around the mountain; the meltwater runs off downhill (lahars). */
    private void meltSnow() {
        if (!DisasterConfig.BLOCK_DESTRUCTION.getAsBoolean()) return;
        double range = (edifice != null ? edifice.radius : 10) * 1.3 + 20;
        var queue = DisasterManager.blockQueue(level);
        for (int k = 0; k < 2 + intensity; k++) {
            double a = random.nextDouble() * Math.PI * 2, r = Math.sqrt(random.nextDouble()) * range;
            int x = Mth.floor(ventX + 0.5 + Math.cos(a) * r), z = Mth.floor(ventZ + 0.5 + Math.sin(a) * r);
            if (!level.isLoaded(new BlockPos(x, 0, z))) continue;
            BlockPos p = new BlockPos(x, level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1, z);
            BlockState st = level.getBlockState(p);
            if (!(st.is(BlockTags.SNOW) || st.is(Blocks.ICE) || st.is(Blocks.PACKED_ICE))) continue;
            boolean water = random.nextFloat() < 0.4f;
            queue.add(l -> {
                BlockState now = level.getBlockState(p);
                if (!(now.is(BlockTags.SNOW) || now.is(Blocks.ICE) || now.is(Blocks.PACKED_ICE))) return;
                if (!DisasterProtection.canModify(level, p, now)) return;
                boolean layer = now.is(Blocks.SNOW);
                level.setBlock(p, !layer && water ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
            });
        }
    }

    // ------------------------------------------------------------------ mob reactions

    private void alertMobs() {
        double mountain = edifice != null ? edifice.radius : plannedRadius(intensity);
        double r = mountain + 30 + intensity * 10 + (phase() == Phase.WARNING ? 30 : 0);
        AABB box = new AABB(pos.x - r, pos.y - 24, pos.z - r, pos.x + r, pos.y + 60, pos.z + r);
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
    public int coneBlocks() { return edifice != null ? edifice.placedBlocks() : 0; }
    public boolean hasCone() { return building(); }
    public int blasts() { return blasts; }
    public int pyroclasticFlows() { return pdcCount; }
    public int mountainHeight() { return edifice != null ? edifice.height : 0; }
    public int mountainRadius() { return edifice != null ? edifice.radius : 0; }
    public float growth() { return edifice != null ? edifice.scale() : 0f; }
    public BlockPos ventPos() { return new BlockPos(ventX, ventY(), ventZ); }

    /** Test hook: keep the mountain, flows, ash fall and bombs within {@code radius} blocks of the vent. */
    public void confineForTest(double radius) {
        confine = radius;
    }

    /** Test hook: start a pyroclastic flow now. */
    public void triggerPyroclasticFlow() {
        if (decideTerrain()) startPyroclasticFlow(false);
    }
}
