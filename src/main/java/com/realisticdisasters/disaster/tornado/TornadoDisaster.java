package com.realisticdisasters.disaster.tornado;

import java.util.ArrayList;
import java.util.List;

import com.realisticdisasters.config.DisasterConfig;
import com.realisticdisasters.disaster.Disaster;
import com.realisticdisasters.disaster.DisasterManager;
import com.realisticdisasters.disaster.DisasterProtection;
import com.realisticdisasters.disaster.DisasterType;
import com.realisticdisasters.disaster.Phase;
import com.realisticdisasters.registry.ModRegistries;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A wandering tornado. Server side handles movement, the wind field (pull, swirl, lift, fling), block ripping as
 * falling-block debris, mob reactions and damage. Visuals live in the client package and are driven purely by
 * {@link #syncData()}.
 */
public class TornadoDisaster extends Disaster {
    /** Indices into the sync data array (shared contract with the client visual). */
    public static final int D_CORE_RADIUS = 0, D_HEIGHT = 1, D_HEADING_X = 2, D_HEADING_Z = 3, D_TOUCHDOWN = 4, D_SPEED = 5;

    private final float coreRadius;
    private final float pullRadius;
    private final float height;
    private final float maxHardness;
    private final double baseSpeed;
    private final int maxDebris;
    private final List<FallingBlockEntity> debris = new ArrayList<>();
    private double heading;
    private double headingDrift;
    private double currentSpeed;
    private boolean changedWeather;

    public TornadoDisaster(ServerLevel level, int id, Vec3 pos, int intensity) {
        super(DisasterType.TORNADO, level, id, pos, intensity);
        int i = this.intensity;
        this.coreRadius = 2.5f + i * 1.5f;          // 4 .. 10
        this.pullRadius = coreRadius * 3.2f;        // 13 .. 32
        this.height = 38f + i * 12f;                // 50 .. 98
        this.maxHardness = 0.4f + i * 0.55f;        // 0.95 (soil, leaves, glass) .. 3.15 (stone, wood, ores)
        this.baseSpeed = 0.07 + i * 0.012;          // blocks / tick
        this.maxDebris = Math.max(4, DisasterConfig.TORNADO_MAX_DEBRIS.getAsInt() * i / 5);
        this.heading = random.nextDouble() * Math.PI * 2;

        double scale = DisasterConfig.TORNADO_DURATION_SCALE.get();
        setDuration(Phase.WARNING, 500);
        setDuration(Phase.BUILDUP, 300);
        setDuration(Phase.PEAK, (int) ((900 + 300 * i) * scale));
        setDuration(Phase.AFTERMATH, 320);
    }

    @Override
    public Component describe() {
        return Component.translatable("disaster.realisticdisasters.tornado");
    }

    @Override
    public float effectRadius() {
        return pullRadius;
    }

    /** 0 = funnel aloft, 1 = funnel on the ground. Ropes out during the aftermath. */
    public float touchdown() {
        return switch (phase()) {
            case WARNING -> 0f;
            case BUILDUP -> smooth(phaseProgress());
            case PEAK -> 1f;
            case AFTERMATH -> 1f - smooth(Math.min(1f, phaseProgress() * 1.4f));
        };
    }

    @Override
    protected float[] syncData() {
        return new float[]{coreRadius, height, (float) Math.cos(heading), (float) Math.sin(heading), touchdown(), (float) currentSpeed};
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onPhaseChange(Phase from, Phase to) {
        if (to == Phase.BUILDUP) {
            broadcast(Component.translatable("disaster.realisticdisasters.tornado.touchdown").withStyle(ChatFormatting.RED), 220);
        }
    }

    @Override
    protected void onEnd() {
        debris.clear();
    }

    @Override
    protected void tickDisaster() {
        if (age() == 1) {
            broadcast(Component.translatable("disaster.realisticdisasters.tornado.warning").withStyle(ChatFormatting.GOLD), 260);
            if (DisasterConfig.TORNADO_CHANGES_WEATHER.getAsBoolean() && level.dimension() == net.minecraft.world.level.Level.OVERWORLD
                    && !level.isThundering()) {
                level.getServer().setWeatherParameters(0, 6000, true, intensity >= 3);
                changedWeather = true;
            }
        }

        move();

        float force = strength() * touchdown();
        if (age() % 20 == 0 && phase() != Phase.AFTERMATH) {
            alertMobs();
        }
        if (force > 0.05f) {
            applyWindToEntities(force);
            if (force > 0.3f) ripBlocks(force);
        }
        tickDebris(force);

        if (phase() == Phase.PEAK && intensity >= 2 && random.nextInt(Math.max(40, 260 - intensity * 40)) == 0) {
            strikeLightning();
        }
    }

    // ------------------------------------------------------------------ movement

    private void move() {
        if (phase() == Phase.WARNING) {
            currentSpeed = 0.0;
            return;
        }
        headingDrift = Mth.clamp(headingDrift + (random.nextDouble() - 0.5) * 0.0006, -0.004, 0.004);
        heading += headingDrift;
        double target = baseSpeed * (0.5 + 0.5 * strength());
        currentSpeed += (target - currentSpeed) * 0.05;
        double nx = pos.x + Math.cos(heading) * currentSpeed;
        double nz = pos.z + Math.sin(heading) * currentSpeed;
        BlockPos column = BlockPos.containing(nx, pos.y, nz);
        if (!level.isLoaded(column)) {
            // Wandered into unloaded terrain: let it dissipate instead of loading chunks.
            beginAftermath();
            return;
        }
        double ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ());
        double ny = pos.y + (ground - pos.y) * 0.1;
        pos = new Vec3(nx, ny, nz);
    }

    // ------------------------------------------------------------------ wind field

    /** Horizontal + vertical wind acting at a point. Shared by entities and debris. */
    private Vec3 windAt(double x, double y, double z, double force, double liftBoost) {
        double dx = x - pos.x, dz = z - pos.z;
        double r = Math.sqrt(dx * dx + dz * dz);
        if (r > pullRadius || r < 1.0e-3) return Vec3.ZERO;
        double nx = dx / r, nz = dz / r;
        double rel = r / pullRadius;
        double power = force * (0.6 + intensity * 0.15);
        double above = y - pos.y;
        double flingHeight = 8 + intensity * 4;

        double inward = 0.05 * (1 - rel) * power;
        double swirl = 0.11 * (1 - rel * 0.55) * power;
        double lift = 0;
        double coreEdge = coreRadius * 1.25;
        if (r < coreEdge) {
            lift = (0.11 + 0.022 * intensity) * (1 - r / coreEdge * 0.6) * power * liftBoost;
        }
        if (above > flingHeight) {
            // Thrown out of the funnel near the top.
            lift = Math.min(lift, 0.02);
            inward = -0.16 * power;
        }
        // counter-clockwise rotation seen from above
        return new Vec3(-nx * inward - nz * swirl, lift, -nz * inward + nx * swirl);
    }

    private void applyWindToEntities(float force) {
        AABB box = new AABB(pos.x - pullRadius, pos.y - 6, pos.z - pullRadius, pos.x + pullRadius, pos.y + height, pos.z + pullRadius);
        DamageSource source = level.damageSources().source(ModRegistries.TORNADO_DAMAGE);
        for (Entity e : level.getEntities((Entity) null, box, e -> !e.isSpectator() && !(e instanceof FallingBlockEntity))) {
            if (e instanceof Player p && (p.getAbilities().flying || p.isCreative() && p.isShiftKeyDown())) continue;
            if (e.isPassenger()) continue;
            double resist = 0;
            if (e instanceof LivingEntity le) resist = Mth.clamp(le.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE), 0, 1) * 0.7;
            Vec3 wind = windAt(e.getX(), e.getY(), e.getZ(), force, 1.0).scale(1 - resist);
            if (wind == Vec3.ZERO) continue;
            Vec3 v = e.getDeltaMovement().add(wind);
            double max = 1.4;
            if (v.lengthSqr() > max * max) v = v.normalize().scale(max);
            e.setDeltaMovement(v);
            e.hurtMarked = true;

            double dx = e.getX() - pos.x, dz = e.getZ() - pos.z;
            if (e instanceof LivingEntity le && age() % 10 == 0 && dx * dx + dz * dz < coreRadius * coreRadius) {
                le.hurtServer(level, source, (0.5f + intensity * 0.5f) * force);
            }
        }
    }

    // ------------------------------------------------------------------ mob reactions

    private void alertMobs() {
        double r = pullRadius * 2 + 24 + (phase() == Phase.WARNING ? 40 : 0);
        AABB box = new AABB(pos.x - r, pos.y - 16, pos.z - r, pos.x + r, pos.y + 32, pos.z + r);
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

    // ------------------------------------------------------------------ block damage

    private void ripBlocks(float force) {
        if (!DisasterConfig.BLOCK_DESTRUCTION.getAsBoolean()) return;
        int attempts = Math.round((1 + intensity * 1.6f) * force);
        var queue = DisasterManager.blockQueue(level);
        for (int i = 0; i < attempts; i++) {
            double a = random.nextDouble() * Math.PI * 2;
            double rr = Math.sqrt(random.nextDouble()) * coreRadius * 1.1;
            int x = Mth.floor(pos.x + Math.cos(a) * rr);
            int z = Mth.floor(pos.z + Math.sin(a) * rr);
            BlockPos column = new BlockPos(x, 0, z);
            if (!level.isLoaded(column)) continue;
            int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
            if (top < pos.y - 6 || top > pos.y + height * 0.5) continue;
            BlockPos target = new BlockPos(x, top, z);
            if (!queue.add(l -> rip(target))) return;
        }
    }

    private void rip(BlockPos bp) {
        if (isFinished()) return;
        BlockState st = level.getBlockState(bp);
        if (st.isAir() || st.getBlock() instanceof LiquidBlock || st.hasBlockEntity()) return;
        float hardness = st.getDestroySpeed(level, bp);
        if (hardness < 0 || hardness > maxHardness) return;
        if (!DisasterProtection.canModify(level, bp, st)) return;

        // Scour the turf: leaves a visible, permanent damage path.
        if (st.is(Blocks.GRASS_BLOCK) || st.is(Blocks.PODZOL) || st.is(Blocks.MYCELIUM) || st.is(Blocks.DIRT_PATH)) {
            level.levelEvent(2001, bp, net.minecraft.world.level.block.Block.getId(st));
            level.setBlock(bp, random.nextFloat() < 0.35f ? Blocks.COARSE_DIRT.defaultBlockState() : Blocks.DIRT.defaultBlockState(), 3);
            return;
        }
        boolean soil = st.is(BlockTags.DIRT) || st.is(BlockTags.SAND) || st.is(Blocks.GRAVEL);
        if (soil && (intensity < 5 || random.nextFloat() > 0.15f)) return;

        // Plants, snow layers and other replaceables just get shredded.
        if (st.canBeReplaced() || !st.isCollisionShapeFullBlock(level, bp) && !st.is(BlockTags.LEAVES) && random.nextFloat() < 0.5f) {
            level.destroyBlock(bp, false);
            return;
        }

        prune();
        if (debris.size() < maxDebris && random.nextFloat() < 0.7f) {
            FallingBlockEntity fb = FallingBlockEntity.fall(level, bp, st);
            fb.dropItem = false;
            if (soil) fb.disableDrop(); // clods crumble on impact instead of littering the land with dirt blocks
            fb.setHurtsEntities(0.6f + intensity * 0.3f, 8 + intensity * 4);
            Vec3 w = windAt(fb.getX(), fb.getY(), fb.getZ(), 1.0, 1.0);
            fb.setDeltaMovement(w.x * 4, 0.45 + random.nextDouble() * 0.35, w.z * 4);
            fb.hurtMarked = true;
            debris.add(fb);
        } else {
            level.destroyBlock(bp, false);
        }
    }

    private void prune() {
        debris.removeIf(fb -> fb.isRemoved() || fb.time > 220);
    }

    private void tickDebris(float force) {
        if (debris.isEmpty()) return;
        prune();
        for (FallingBlockEntity fb : debris) {
            Vec3 w = windAt(fb.getX(), fb.getY(), fb.getZ(), force, 1.6);
            if (w == Vec3.ZERO) continue;
            Vec3 v = fb.getDeltaMovement().add(w.x, w.y + 0.03 * force, w.z); // counteract part of gravity
            double max = 1.8;
            if (v.lengthSqr() > max * max) v = v.normalize().scale(max);
            fb.setDeltaMovement(v);
            fb.hurtMarked = true;
        }
    }

    // ------------------------------------------------------------------ atmosphere

    private void strikeLightning() {
        double a = random.nextDouble() * Math.PI * 2;
        double d = pullRadius + 10 + random.nextDouble() * 50;
        int x = Mth.floor(pos.x + Math.cos(a) * d), z = Mth.floor(pos.z + Math.sin(a) * d);
        if (!level.isLoaded(new BlockPos(x, 0, z))) return;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level, EntitySpawnReason.EVENT);
        if (bolt == null) return;
        bolt.setVisualOnly(!DisasterConfig.BLOCK_DESTRUCTION.getAsBoolean() || random.nextFloat() < 0.6f);
        bolt.snapTo(x + 0.5, y, z + 0.5);
        level.addFreshEntity(bolt);
    }

    private void broadcast(Component message, double range) {
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(pos.x, p.getY(), pos.z) < range * range) p.sendOverlayMessage(message);
        }
    }

    public boolean changedWeather() {
        return changedWeather;
    }
}
