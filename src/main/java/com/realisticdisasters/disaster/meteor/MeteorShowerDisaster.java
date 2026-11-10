package com.realisticdisasters.disaster.meteor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import com.realisticdisasters.config.DisasterConfig;
import com.realisticdisasters.disaster.Disaster;
import com.realisticdisasters.disaster.DisasterType;
import com.realisticdisasters.disaster.Phase;
import com.realisticdisasters.registry.ModRegistries;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A meteor shower. Warning: streaks across the sky (client only). Buildup: the first small meteors reach the ground.
 * Peak: a barrage of impacts, each with a flash, an expanding shockwave (knockback, damage, fire) and a crater with a
 * scorched lining and a meteorite core that may hold rare ores. Aftermath: smouldering craters.
 * <p>
 * Every impact is scheduled {@link #FLIGHT_TICKS} ahead and synced in a small ring buffer, so clients can show the
 * fireball falling before it lands (see the client's MeteorShowerVisual).
 */
public class MeteorShowerDisaster extends Disaster {
    /** Indices into the sync data array (shared contract with the client visual). */
    public static final int D_SCATTER = 0, D_RADIANT_X = 1, D_RADIANT_Z = 2, D_SLOTS = 3;
    /** Ring buffer of recent / incoming meteors: SLOT_COUNT slots of SLOT_SIZE floats starting at {@link #D_SLOTS}. */
    public static final int SLOT_COUNT = 4, SLOT_SIZE = 6; // 3 + 4 * 6 = 27 floats; the sync payload carries at most 32
    /** Offsets inside a slot: sequence number (0 = empty), offset from the disaster centre, size, ticks until impact. */
    public static final int S_SEQ = 0, S_DX = 1, S_DY = 2, S_DZ = 3, S_SIZE = 4, S_TICKS = 5;
    /** Time from a meteor becoming visible to its impact. */
    public static final int FLIGHT_TICKS = 50;
    /** Speed of the shockwave front in blocks per tick (the client delays camera shake by the same amount). */
    public static final double WAVE_SPEED = 2.5;

    private final float scatter;
    private final double radiant;
    private final int peakTicks;
    private final float expectedImpacts;
    private final List<Meteor> meteors = new ArrayList<>();
    private final List<Shockwave> shockwaves = new ArrayList<>();
    private int nextSeq = 1;
    private int landed;
    private float spawnBudget;
    private boolean bigOneScheduled;

    /** A scheduled (incoming) or recently landed meteor. */
    private static final class Meteor {
        final int seq;
        final double x, z;
        double y;
        final float size;
        final long impactTick;
        boolean impacted;

        Meteor(int seq, double x, double y, double z, float size, long impactTick) {
            this.seq = seq;
            this.x = x;
            this.y = y;
            this.z = z;
            this.size = size;
            this.impactTick = impactTick;
        }
    }

    private static final class Shockwave {
        final Vec3 center;
        final double maxRadius, craterRadius;
        final float size;
        final Set<Integer> hit = new HashSet<>();
        double radius;

        Shockwave(Vec3 center, double maxRadius, double craterRadius, float size) {
            this.center = center;
            this.maxRadius = maxRadius;
            this.craterRadius = craterRadius;
            this.size = size;
        }
    }

    public MeteorShowerDisaster(ServerLevel level, int id, Vec3 pos, int intensity) {
        super(DisasterType.METEOR_SHOWER, level, id, pos, intensity);
        int i = this.intensity;
        this.scatter = 18f + i * 10f;                     // 28 .. 68
        this.radiant = random.nextDouble() * Math.PI * 2;
        double scale = DisasterConfig.METEOR_DURATION_SCALE.get();
        this.peakTicks = (int) ((500 + 220 * i) * scale);  // 36 .. 80 s
        this.expectedImpacts = (float) ((3 + i * 2.5) * DisasterConfig.METEOR_IMPACT_RATE.get() * Math.sqrt(scale));
        setDuration(Phase.WARNING, 560);
        setDuration(Phase.BUILDUP, 300);
        setDuration(Phase.PEAK, peakTicks);
        setDuration(Phase.AFTERMATH, 400);
    }

    @Override
    public Component describe() {
        return Component.translatable("disaster.realisticdisasters.meteor_shower");
    }

    @Override
    public float effectRadius() {
        return scatter + 48f;
    }

    // ------------------------------------------------------------------ sizes (shared with tests)

    public float craterRadius(float size) {
        return (float) ((1.6 + intensity * 0.75) * size * DisasterConfig.METEOR_CRATER_SCALE.get());
    }

    public float shockRadius(float size) {
        return 8f + craterRadius(size) * 4f;
    }

    public int impactsLanded() {
        return landed;
    }

    /** Blocks a meteorite core may contain as ore (expanded by weight). */
    public static List<net.minecraft.world.level.block.Block> oreTable() {
        return Crater.ores();
    }

    public int pendingImpacts() {
        int n = 0;
        for (Meteor m : meteors) if (!m.impacted) n++;
        return n;
    }

    // ------------------------------------------------------------------ sync

    @Override
    protected float[] syncData() {
        float[] out = new float[D_SLOTS + SLOT_COUNT * SLOT_SIZE];
        out[D_SCATTER] = scatter;
        out[D_RADIANT_X] = (float) Math.cos(radiant);
        out[D_RADIANT_Z] = (float) Math.sin(radiant);
        long now = level.getGameTime();
        for (Meteor m : meteors) {
            int base = D_SLOTS + (m.seq % SLOT_COUNT) * SLOT_SIZE;
            if (out[base + S_SEQ] > m.seq) continue;
            out[base + S_SEQ] = m.seq;
            out[base + S_DX] = (float) (m.x - pos.x);
            out[base + S_DY] = (float) (m.y - pos.y);
            out[base + S_DZ] = (float) (m.z - pos.z);
            out[base + S_SIZE] = m.size;
            out[base + S_TICKS] = m.impactTick - now;
        }
        return out;
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onPhaseChange(Phase from, Phase to) {
        if (to == Phase.BUILDUP) {
            broadcast(Component.translatable("disaster.realisticdisasters.meteor_shower.impacts").withStyle(ChatFormatting.RED), 260);
        }
    }

    @Override
    protected void onEnd() {
        meteors.clear();
        shockwaves.clear();
    }

    @Override
    protected void tickDisaster() {
        if (age() == 1) {
            broadcast(Component.translatable("disaster.realisticdisasters.meteor_shower.warning").withStyle(ChatFormatting.GOLD), 300);
        }
        scheduleNaturalMeteors();

        long now = level.getGameTime();
        for (Meteor m : meteors) {
            if (!m.impacted && now >= m.impactTick) {
                m.impacted = true;
                impact(m);
            }
        }
        // Keep landed meteors around a little so late syncs still carry them, then forget them.
        meteors.removeIf(m -> m.impacted && now - m.impactTick > 60);

        tickShockwaves();
        if (age() % 20 == 0 && phase() != Phase.AFTERMATH) alertMobs();
    }

    private void scheduleNaturalMeteors() {
        switch (phase()) {
            case BUILDUP -> {
                // a handful of small forerunners
                spawnBudget += (1f + intensity * 0.4f) / 300f * smooth(phaseProgress());
                if (spawnBudget >= 1f && random.nextFloat() < 0.08f) {
                    spawnBudget -= 1f;
                    scheduleRandom(0.35f + random.nextFloat() * 0.25f);
                }
            }
            case PEAK -> {
                spawnBudget += expectedImpacts / peakTicks;
                if (spawnBudget >= 1f && random.nextFloat() < 0.12f) {
                    spawnBudget -= 1f;
                    scheduleRandom(0.5f + random.nextFloat() * 0.5f);
                }
                if (intensity >= 4 && !bigOneScheduled && phaseProgress() > 0.6f) {
                    bigOneScheduled = true;
                    // The big one: lands closer to the middle of the shower.
                    scheduleAt(randomTarget(0.4f), 1.35f + (intensity - 4) * 0.25f, FLIGHT_TICKS);
                }
            }
            default -> {}
        }
    }

    private Vec3 randomTarget(float spread) {
        double a = random.nextDouble() * Math.PI * 2;
        double r = Math.sqrt(random.nextDouble()) * scatter * spread;
        return new Vec3(pos.x + Math.cos(a) * r, pos.y, pos.z + Math.sin(a) * r);
    }

    private void scheduleRandom(float size) {
        scheduleAt(randomTarget(1f), size, FLIGHT_TICKS);
    }

    /**
     * Schedules a meteor to land at the given column after {@code delay} ticks. Public for tests and commands.
     *
     * @return false if the target column is not loaded
     */
    public boolean scheduleAt(Vec3 target, float size, int delay) {
        BlockPos column = BlockPos.containing(target.x, 0, target.z);
        if (!level.isLoaded(column)) return false;
        double y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ());
        Meteor m = new Meteor(nextSeq++, target.x, y, target.z, size, level.getGameTime() + Math.max(1, delay));
        meteors.add(m);
        warnMobsAt(new Vec3(m.x, m.y, m.z), shockRadius(size) * 1.3);
        return true;
    }

    // ------------------------------------------------------------------ impact

    private void impact(Meteor m) {
        landed++;
        BlockPos column = BlockPos.containing(m.x, 0, m.z);
        if (!level.isLoaded(column)) return;
        // Ground level now (terrain may have changed since the meteor was scheduled).
        m.y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ());
        Vec3 center = new Vec3(m.x, m.y, m.z);
        float craterR = craterRadius(m.size);
        shockwaves.add(new Shockwave(center, shockRadius(m.size), craterR, m.size));
        Crater.carve(this, BlockPos.containing(center), craterR, m.size);
    }

    private void tickShockwaves() {
        if (shockwaves.isEmpty()) return;
        DamageSource source = level.damageSources().source(ModRegistries.METEOR_DAMAGE);
        Iterator<Shockwave> it = shockwaves.iterator();
        while (it.hasNext()) {
            Shockwave w = it.next();
            double inner = w.radius;
            w.radius = Math.min(w.maxRadius, w.radius + WAVE_SPEED);
            double r = w.radius;
            Vec3 c = w.center;
            AABB box = new AABB(c.x - r, c.y - r * 0.5 - 4, c.z - r, c.x + r, c.y + r * 0.7 + 6, c.z + r);
            for (Entity e : level.getEntities((Entity) null, box, e -> !e.isSpectator() && !e.isPassenger())) {
                double d = e.position().distanceTo(c);
                if (d > r || !w.hit.add(e.getId())) continue;
                if (d < inner - 0.01 && inner > 0) continue; // was already inside when the front passed (shouldn't happen)
                hitByShockwave(e, w, d, source);
            }
            if (w.radius >= w.maxRadius) it.remove();
        }
    }

    private void hitByShockwave(Entity e, Shockwave w, double d, DamageSource source) {
        double falloff = 1 - d / w.maxRadius;
        if (e instanceof Player p && p.getAbilities().flying) falloff *= 0.4;
        double resist = e instanceof LivingEntity le ? Mth.clamp(le.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE), 0, 1) * 0.6 : 0;
        Vec3 dir = new Vec3(e.getX() - w.center.x, 0, e.getZ() - w.center.z);
        dir = dir.lengthSqr() < 1.0e-4 ? new Vec3(random.nextDouble() - 0.5, 0, random.nextDouble() - 0.5).normalize() : dir.normalize();
        double push = (0.55 + intensity * 0.22) * Math.min(1.6, w.size) * falloff * falloff * (1 - resist);
        Vec3 v = e.getDeltaMovement().add(dir.x * push * 1.6, 0.25 + push * 0.55, dir.z * push * 1.6);
        double max = 2.6;
        if (v.lengthSqr() > max * max) v = v.normalize().scale(max);
        e.setDeltaMovement(v);
        e.hurtMarked = true;

        if (e instanceof LivingEntity le) {
            double damageR = w.craterRadius * 2.4;
            if (d < damageR) {
                float dmg = (float) ((2.5 + intensity * 2.2) * w.size * (1 - d / damageR));
                if (dmg > 0.5f) le.hurtServer(level, source, dmg);
            }
            if (d < w.craterRadius * 1.6) le.igniteForTicks(60 + intensity * 20);
        }
    }

    // ------------------------------------------------------------------ mob reactions

    private void alertMobs() {
        // Animals sense the shower: they drift away from its centre and bolt from scheduled impacts (warnMobsAt).
        double r = scatter + 40;
        AABB box = new AABB(pos.x - r, pos.y - 24, pos.z - r, pos.x + r, pos.y + 40, pos.z + r);
        for (PathfinderMob mob : level.getEntitiesOfClass(PathfinderMob.class, box, m -> !(m instanceof Enemy))) {
            if (phase() == Phase.WARNING && random.nextInt(3) != 0) continue;
            flee(mob, pos, phase() == Phase.WARNING ? 1.15 : 1.4);
        }
    }

    private void warnMobsAt(Vec3 target, double radius) {
        AABB box = new AABB(target.x - radius, target.y - 16, target.z - radius, target.x + radius, target.y + 24, target.z + radius);
        for (PathfinderMob mob : level.getEntitiesOfClass(PathfinderMob.class, box, m -> !(m instanceof Enemy))) {
            flee(mob, target, 1.7);
        }
    }

    private void flee(PathfinderMob mob, Vec3 from, double speed) {
        if (mob instanceof Villager villager) {
            // Same signal a ringing bell sends: villagers run home and hide.
            villager.getBrain().setMemory(MemoryModuleType.HEARD_BELL_TIME, level.getGameTime());
            return;
        }
        if (mob.isLeashed() || mob.isPassenger()) return;
        Vec3 away = new Vec3(mob.getX() - from.x, 0, mob.getZ() - from.z);
        away = away.lengthSqr() < 1.0e-4 ? new Vec3(1, 0, 0) : away.normalize();
        Vec3 target = mob.position().add(away.scale(14 + random.nextInt(10)));
        mob.getNavigation().moveTo(target.x, target.y, target.z, speed);
    }

    // ------------------------------------------------------------------ helpers

    private void broadcast(Component message, double range) {
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(pos.x, p.getY(), pos.z) < range * range) p.sendOverlayMessage(message);
        }
    }

    ServerLevel serverLevel() {
        return level;
    }

    net.minecraft.util.RandomSource rng() {
        return random;
    }
}
