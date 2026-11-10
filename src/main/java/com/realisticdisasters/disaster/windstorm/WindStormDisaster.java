package com.realisticdisasters.disaster.windstorm;

import com.realisticdisasters.config.DisasterConfig;
import com.realisticdisasters.disaster.Disaster;
import com.realisticdisasters.disaster.DisasterManager;
import com.realisticdisasters.disaster.DisasterProtection;
import com.realisticdisasters.disaster.DisasterType;
import com.realisticdisasters.disaster.Phase;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Shared base for wide-area wind storms (blizzard, sandstorm). The storm is a big disc that drifts slowly with a
 * prevailing wind. Inside it: a gusting wind pushes exposed entities downwind, animals look for a roof, and the
 * ground slowly gets buried (batched through the block queue). Subclasses decide what accumulates and what the
 * storm does to exposed creatures; the client draws particles that follow the synced wind vector.
 */
public abstract class WindStormDisaster extends Disaster {
    /** Indices into the sync data array (shared contract with {@code client.windstorm.WindStormVisual}). */
    public static final int D_WIND_X = 0, D_WIND_Z = 1, D_WIND_SPEED = 2, D_GUST = 3, D_VARIANT = 4;

    /** Shelter search: how far mobs look for a roof, and how many spots they try per check. */
    private static final int SHELTER_RANGE = 14, SHELTER_TRIES = 8, MAX_MOBS_PER_PASS = 40;

    protected final float radius;
    protected final float push;
    private final double driftSpeed;
    private double heading;
    private double headingDrift;
    private float gust = 1f;

    protected WindStormDisaster(DisasterType type, ServerLevel level, int id, Vec3 pos, int intensity, double durationScale) {
        super(type, level, id, pos, intensity);
        int i = this.intensity;
        this.radius = 40f + i * 16f;               // 56 .. 120
        this.push = 0.010f + i * 0.006f;           // blocks/tick² of downwind acceleration at full gust
        this.driftSpeed = 0.015 + i * 0.004;       // the storm itself creeps along with the wind
        this.heading = random.nextDouble() * Math.PI * 2;
        setDuration(Phase.WARNING, 400);
        setDuration(Phase.BUILDUP, 400);
        setDuration(Phase.PEAK, (int) ((1200 + 400 * i) * durationScale));
        setDuration(Phase.AFTERMATH, 400);
    }

    // ------------------------------------------------------------------ subclass hooks

    /** Translation key prefix, e.g. {@code disaster.realisticdisasters.blizzard}. */
    protected abstract String key();

    /** Accumulate at one surface column. {@code top} is the first non-solid position above the ground. Runs from the block queue. */
    protected abstract void accumulate(BlockPos top, int drift);

    /** Effect on an exposed living entity (freezing, sand abrasion). Called every 10 ticks. */
    protected void affectExposed(LivingEntity entity, float force) {}

    /** Extra per-tick work (weather etc.). */
    protected void tickExtra() {}

    /** Sync value for the client palette (e.g. red sand). */
    protected float variant() {
        return 0f;
    }

    // ------------------------------------------------------------------ disaster

    @Override
    public Component describe() {
        return Component.translatable(key());
    }

    @Override
    public float effectRadius() {
        return radius;
    }

    public Vec3 wind() {
        return new Vec3(Math.cos(heading), 0, Math.sin(heading));
    }

    /** Wind speed the particles travel at, in blocks per tick. */
    public float windSpeed() {
        return (0.35f + intensity * 0.12f) * (0.25f + 0.75f * strength());
    }

    /** Current gust factor (0.55 .. 1.35). Shared with the client so pushes and particles surge together. */
    public float gust() {
        return gust;
    }

    @Override
    protected float[] syncData() {
        return new float[]{(float) Math.cos(heading), (float) Math.sin(heading), windSpeed(), gust, variant()};
    }

    @Override
    protected void onPhaseChange(Phase from, Phase to) {
        if (to == Phase.BUILDUP) broadcast(Component.translatable(key() + ".onset").withStyle(net.minecraft.ChatFormatting.RED));
    }

    @Override
    protected void tickDisaster() {
        if (age() == 1) broadcast(Component.translatable(key() + ".warning").withStyle(net.minecraft.ChatFormatting.GOLD));
        updateWind();
        tickExtra();

        float s = strength();
        if (age() % 40 == 0 && phase() != Phase.AFTERMATH) seekShelter();
        if (s > 0.1f) {
            // Entity scan every other tick with a doubled push: same feel, half the cost.
            if (age() % 2 == 0) pushEntities(s);
            if (s > 0.3f) queueAccumulation(s);
        }
    }

    // ------------------------------------------------------------------ wind

    private void updateWind() {
        headingDrift = Mth.clamp(headingDrift + (random.nextDouble() - 0.5) * 0.0002, -0.0015, 0.0015);
        heading += headingDrift;
        // Layered slow sines: long surges with shorter gusts on top.
        double t = age();
        gust = (float) (0.95 + 0.25 * Math.sin(t * 0.021 + id) + 0.15 * Math.sin(t * 0.077 + id * 2.3));
        if (phase() == Phase.WARNING) return;
        double nx = pos.x + Math.cos(heading) * driftSpeed;
        double nz = pos.z + Math.sin(heading) * driftSpeed;
        BlockPos column = BlockPos.containing(nx, pos.y, nz);
        if (!level.isLoaded(column)) {
            beginAftermath();
            return;
        }
        double ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ());
        pos = new Vec3(nx, pos.y + (ground - pos.y) * 0.02, nz);
    }

    /** True if nothing solid is above this point (open sky = exposed to the storm). */
    protected boolean exposed(Entity e) {
        BlockPos eye = BlockPos.containing(e.getX(), e.getEyeY(), e.getZ());
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING, eye.getX(), eye.getZ()) <= eye.getY();
    }

    /** 0..1 how strongly a point is inside the storm disc (soft edge over the outer 25 %). */
    protected float coverage(double x, double z) {
        double dx = x - pos.x, dz = z - pos.z;
        double r = Math.sqrt(dx * dx + dz * dz);
        return 1f - smooth((float) ((r - radius * 0.75) / (radius * 0.25)));
    }

    private void pushEntities(float s) {
        AABB box = new AABB(pos.x - radius, pos.y - 48, pos.z - radius, pos.x + radius, pos.y + 96, pos.z + radius);
        Vec3 w = wind();
        boolean hurtTick = age() % 10 == 0;
        for (Entity e : level.getEntities((Entity) null, box, e -> !e.isSpectator() && !(e instanceof FallingBlockEntity))) {
            if (e instanceof Player p && p.getAbilities().flying) continue;
            if (e.isPassenger() || e.isInWater()) continue;
            float cover = coverage(e.getX(), e.getZ());
            if (cover <= 0.01f || !exposed(e)) continue;
            float force = s * cover;
            if (hurtTick && e instanceof LivingEntity le) affectExposed(le, force);

            double resist = 0;
            if (e instanceof LivingEntity le) resist = Mth.clamp(le.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE), 0, 1) * 0.8;
            double a = 2 * push * force * gust * (1 - resist) * (e.onGround() ? 1.0 : 0.6);
            Vec3 v = e.getDeltaMovement();
            // Push toward the wind's own speed, not beyond: walking into it is slow, walking with it is quick.
            double along = v.x * w.x + v.z * w.z;
            double cap = 0.12 + intensity * 0.05;
            if (along > cap) continue;
            e.setDeltaMovement(v.x + w.x * a, v.y, v.z + w.z * a);
            e.hurtMarked = true;
        }
    }

    // ------------------------------------------------------------------ mobs take shelter

    private void seekShelter() {
        double r = radius + (phase() == Phase.WARNING ? 48 : 16);
        AABB box = new AABB(pos.x - r, pos.y - 32, pos.z - r, pos.x + r, pos.y + 48, pos.z + r);
        int handled = 0;
        Vec3 w = wind();
        for (PathfinderMob mob : level.getEntitiesOfClass(PathfinderMob.class, box, m -> !(m instanceof Enemy))) {
            if (mob instanceof Villager villager) {
                // Same signal a ringing bell sends: villagers run home and stay indoors.
                villager.getBrain().setMemory(MemoryModuleType.HEARD_BELL_TIME, level.getGameTime());
                continue;
            }
            if (mob.isLeashed() || mob.isPassenger() || !exposed(mob)) continue;
            if (handled++ >= MAX_MOBS_PER_PASS) break;
            BlockPos shelter = findShelter(mob.blockPosition());
            double speed = phase() == Phase.WARNING ? 1.15 : 1.45;
            if (shelter != null) {
                mob.getNavigation().moveTo(shelter.getX() + 0.5, shelter.getY(), shelter.getZ() + 0.5, speed);
            } else {
                // No roof nearby: turn tail and drift downwind, like herds do.
                Vec3 target = mob.position().add(w.scale(10 + random.nextInt(6)));
                mob.getNavigation().moveTo(target.x, target.y, target.z, speed);
            }
        }
    }

    /** A random nearby standable spot that has a roof over it, or null. */
    private BlockPos findShelter(BlockPos from) {
        for (int i = 0; i < SHELTER_TRIES; i++) {
            int x = from.getX() + random.nextInt(SHELTER_RANGE * 2 + 1) - SHELTER_RANGE;
            int z = from.getZ() + random.nextInt(SHELTER_RANGE * 2 + 1) - SHELTER_RANGE;
            BlockPos column = new BlockPos(x, from.getY(), z);
            if (!level.isLoaded(column)) continue;
            int roof = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
            if (roof <= from.getY() + 1) continue; // open sky here
            // Scan a few blocks up/down for floor + two blocks of headroom under that roof.
            for (int dy = -3; dy <= 3; dy++) {
                BlockPos p = new BlockPos(x, from.getY() + dy, z);
                if (p.getY() >= roof - 1) break;
                if (level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP)
                        && level.getBlockState(p).getCollisionShape(level, p).isEmpty()
                        && level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty()) {
                    return p;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ accumulation

    private void queueAccumulation(float s) {
        if (!DisasterConfig.BLOCK_DESTRUCTION.getAsBoolean()) return;
        float rate = accumulationRate();
        int attempts = count((2f + intensity * 2.5f) * s * rate);
        var queue = DisasterManager.blockQueue(level);
        Vec3 w = wind();
        for (int i = 0; i < attempts; i++) {
            double a = random.nextDouble() * Math.PI * 2;
            double rr = Math.sqrt(random.nextDouble()) * radius;
            int x = Mth.floor(pos.x + Math.cos(a) * rr);
            int z = Mth.floor(pos.z + Math.sin(a) * rr);
            if (random.nextFloat() > coverage(x + 0.5, z + 0.5)) continue;
            if (!level.isLoaded(new BlockPos(x, 0, z))) continue;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
            BlockPos top = new BlockPos(x, y, z);
            int drift = driftBonus(top, w);
            if (!queue.add(l -> {
                if (!isFinished()) accumulate(top, drift);
            })) return;
        }
    }

    /** Admin / test hook: point the wind (radians, 0 = +X, PI/2 = +Z). */
    public void setWindHeading(double radians) {
        heading = radians;
        headingDrift = 0;
    }

    /** Admin / test hook: one accumulation step on a column right now (bypasses the queue, still protection-checked). */
    public void accumulateColumn(int x, int z) {
        BlockPos top = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), z);
        accumulate(top, driftBonus(top, wind()));
    }

    /** Override to speed up / slow down burial. */
    protected float accumulationRate() {
        return 1f;
    }

    /**
     * Extra layers where the wind drops its load: against the windward face of an obstacle (the next block downwind
     * is higher than this column) and in its lee (the block upwind is higher). Open ground gets none.
     */
    private int driftBonus(BlockPos top, Vec3 w) {
        int dx = (int) Math.signum(Math.round(w.x * 1.4)), dz = (int) Math.signum(Math.round(w.z * 1.4));
        if (dx == 0 && dz == 0) return 0;
        int down = level.getHeight(Heightmap.Types.MOTION_BLOCKING, top.getX() + dx, top.getZ() + dz);
        int up = level.getHeight(Heightmap.Types.MOTION_BLOCKING, top.getX() - dx, top.getZ() - dz);
        int bonus = 0;
        if (down > top.getY()) bonus += Math.min(4, down - top.getY()) + 1;
        if (up > top.getY()) bonus += Math.min(3, up - top.getY());
        return bonus;
    }

    /**
     * Places or grows a layer block (snow-layer style, {@code LAYERS} 1..8) at the surface; plants get buried.
     * Layer blocks don't block motion, so the heightmap position is the layer block itself.
     *
     * @return the new layer count, or 0 if nothing changed
     */
    protected int addLayer(BlockPos top, BlockState layerDefault, int maxLayers) {
        BlockState there = level.getBlockState(top);
        var layers = net.minecraft.world.level.block.SnowLayerBlock.LAYERS;
        if (there.is(layerDefault.getBlock())) {
            int n = there.getValue(layers);
            if (n >= Math.min(8, maxLayers) || !DisasterProtection.canModify(level, top, there)) return 0;
            level.setBlock(top, there.setValue(layers, n + 1), 3);
            return n + 1;
        }
        if (!(there.isAir() || there.canBeReplaced() && there.getFluidState().isEmpty() && !there.hasBlockEntity())) return 0;
        if (!layerDefault.canSurvive(level, top) || !DisasterProtection.canModify(level, top, there)) return 0;
        level.setBlock(top, layerDefault, 3);
        return 1;
    }

    // ------------------------------------------------------------------ helpers

    protected int count(float expected) {
        int n = (int) expected;
        if (random.nextFloat() < expected - n) n++;
        return n;
    }

    protected void broadcast(Component message) {
        double range = radius + 200;
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(pos.x, p.getY(), pos.z) < range * range) p.sendOverlayMessage(message);
        }
    }
}
