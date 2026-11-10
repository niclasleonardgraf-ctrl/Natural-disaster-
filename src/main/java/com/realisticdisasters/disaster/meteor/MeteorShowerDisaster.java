package com.realisticdisasters.disaster.meteor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

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
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
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
    public static final int D_SCATTER = 0, D_RADIANT_X = 1, D_RADIANT_Z = 2, D_CRATER = 3, D_SLOTS = 4;
    /** Ring buffer of recent / incoming meteors: SLOT_COUNT slots of SLOT_SIZE floats starting at {@link #D_SLOTS}. */
    public static final int SLOT_COUNT = 4, SLOT_SIZE = 6; // 4 + 4 * 6 = 28 floats; the sync payload carries at most 32
    /** Offsets inside a slot: sequence number (0 = empty), offset from the disaster centre, size, ticks until impact. */
    public static final int S_SEQ = 0, S_DX = 1, S_DY = 2, S_DZ = 3, S_SIZE = 4, S_TICKS = 5;
    /** Time from a meteor becoming visible to its impact. */
    public static final int FLIGHT_TICKS = 50;
    /**
     * Speed of the blast front in blocks per tick: the speed of sound, 343 m/s = ~17 blocks/tick (the front is
     * supersonic only inside the crater, which it starts beyond). The client delays shake and sound by the same amount.
     */
    public static final double WAVE_SPEED = 17.0;
    /** Minecraft falling-entity physics: gravity per tick and drag factor per tick. */
    private static final double GRAVITY = 0.04, DRAG = 0.98;

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
    private final List<Ejecta> ejecta = new ArrayList<>();
    /** Horizontal direction the meteors travel in (away from the radiant); ejecta favour this "downrange" side. */
    private final double travelAz;

    /** A block thrown out of a crater, flying on real falling-block physics; becomes ejecta blanket where it lands. */
    private record Ejecta(FallingBlockEntity entity, BlockState landsAs) {}

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
        this.travelAz = radiant + Math.PI;
        double scale = DisasterConfig.METEOR_DURATION_SCALE.get();
        this.peakTicks = (int) ((500 + 220 * i) * scale);  // 36 .. 80 s
        this.expectedImpacts = (float) ((2 + i * 2) * DisasterConfig.METEOR_IMPACT_RATE.get() * Math.sqrt(scale));
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
        return scatter + shockRadius(2f);
    }

    // ------------------------------------------------------------------ sizes (shared with tests)

    /** Crater radius for a meteor of relative size {@code size} (1 = typical). ~4-10 blocks, the big one up to ~20. */
    public float craterRadius(float size) {
        return craterBase() * size;
    }

    /** Crater radius of a size-1 meteor (synced so the client scales its effects the same way). */
    public float craterBase() {
        return (float) ((3.0 + intensity * 1.4) * DisasterConfig.METEOR_CRATER_SCALE.get());
    }

    /** Reach of the blast: knockback and damage fade out here (scaled distance r / R of about 6). */
    public float shockRadius(float size) {
        return 10f + craterRadius(size) * 6f;
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
        out[D_CRATER] = craterBase();
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
        // Ejecta still in the air keeps flying and lands as normal (handled by its own physics); just stop tracking.
        for (Ejecta e : ejecta) settle(e);
        ejecta.clear();
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
        tickEjecta();
        if (age() % 20 == 0 && phase() != Phase.AFTERMATH) alertMobs();
    }

    private void scheduleNaturalMeteors() {
        switch (phase()) {
            case BUILDUP -> {
                // a handful of small forerunners
                spawnBudget += (1f + intensity * 0.4f) / 300f * smooth(phaseProgress());
                if (spawnBudget >= 1f && random.nextFloat() < 0.08f) {
                    spawnBudget -= 1f;
                    scheduleRandom(0.5f + random.nextFloat() * 0.2f);
                }
            }
            case PEAK -> {
                spawnBudget += expectedImpacts / peakTicks;
                if (spawnBudget >= 1f && random.nextFloat() < 0.12f) {
                    spawnBudget -= 1f;
                    // Every impact is a big hit; a few are bigger (sizes follow a steep power law like real impactors).
                    scheduleRandom(0.75f + 0.45f * (float) Math.pow(random.nextFloat(), 2.5));
                }
                if (intensity >= 3 && !bigOneScheduled && phaseProgress() > 0.6f) {
                    bigOneScheduled = true;
                    // The big one: lands closer to the middle of the shower.
                    scheduleAt(randomTarget(0.4f), 1.5f + (intensity - 3) * 0.2f, FLIGHT_TICKS);
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
        // The front starts at the transient crater rim: inside it everything is simply obliterated.
        Shockwave wave = new Shockwave(center, shockRadius(m.size), craterR, m.size);
        wave.radius = craterR * 0.5;
        shockwaves.add(wave);
        hitInside(wave);
        if (DisasterConfig.BLOCK_DESTRUCTION.getAsBoolean()) {
            // Blast damage first (it arrives within ticks), then ballistic ejecta, then the excavated crater.
            BlastDamage.enqueue(this, BlockPos.containing(center), craterR);
            launchEjecta(BlockPos.containing(center), craterR);
            Crater.carve(this, BlockPos.containing(center), craterR, m.size);
        }
    }

    // ------------------------------------------------------------------ blast physics

    private void hitInside(Shockwave w) {
        DamageSource source = level.damageSources().source(ModRegistries.METEOR_DAMAGE);
        double r = w.radius;
        AABB box = new AABB(w.center.x - r, w.center.y - r, w.center.z - r, w.center.x + r, w.center.y + r * 1.5, w.center.z + r);
        for (Entity e : level.getEntities((Entity) null, box, e -> !e.isSpectator() && !e.isPassenger())) {
            double d = e.position().distanceTo(w.center);
            if (d <= r && w.hit.add(e.getId())) hitByShockwave(e, w, d, source);
        }
    }

    private void tickShockwaves() {
        if (shockwaves.isEmpty()) return;
        DamageSource source = level.damageSources().source(ModRegistries.METEOR_DAMAGE);
        Iterator<Shockwave> it = shockwaves.iterator();
        while (it.hasNext()) {
            Shockwave w = it.next();
            w.radius = Math.min(w.maxRadius, w.radius + WAVE_SPEED);
            double r = w.radius;
            Vec3 c = w.center;
            AABB box = new AABB(c.x - r, c.y - r * 0.5 - 4, c.z - r, c.x + r, c.y + r * 0.7 + 6, c.z + r);
            for (Entity e : level.getEntities((Entity) null, box, e -> !e.isSpectator() && !e.isPassenger()
                    && !(e instanceof FallingBlockEntity))) {
                double d = e.position().distanceTo(c);
                if (d > r || !w.hit.add(e.getId())) continue;
                hitByShockwave(e, w, d, source);
            }
            if (w.radius >= w.maxRadius) it.remove();
        }
    }

    /**
     * Blast scaling: effects depend on the scaled distance z = r / R (R = crater radius, a proxy for the cube root
     * of the impact energy, as in Hopkinson-Cranz blast scaling). Peak overpressure falls off roughly as 1/z far out
     * and much faster close in: lethal inside the crater, injuring to z ~ 3, a strong shove to z ~ 6.
     */
    private void hitByShockwave(Entity e, Shockwave w, double d, DamageSource source) {
        double z = Math.max(0.3, d / w.craterRadius);
        double fade = 1 - Mth.clamp((d - w.maxRadius * 0.7) / (w.maxRadius * 0.3), 0, 1);
        double pressure = Math.min(1.6, 1.3 / z) * fade;           // dynamic pressure ~ wind behind the front
        if (e instanceof Player p && p.getAbilities().flying) pressure *= 0.4;
        double resist = e instanceof LivingEntity le ? Mth.clamp(le.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE), 0, 1) * 0.5 : 0;
        Vec3 dir = new Vec3(e.getX() - w.center.x, 0, e.getZ() - w.center.z);
        dir = dir.lengthSqr() < 1.0e-4 ? new Vec3(random.nextDouble() - 0.5, 0, random.nextDouble() - 0.5).normalize() : dir.normalize();
        double push = (0.8 + intensity * 0.22) * Math.min(2.0, w.size) * pressure * (1 - resist);
        // Inside the crater the ground itself is thrown: mostly upward.
        double up = z < 1 ? 0.9 + push * 0.6 : 0.2 + push * 0.45;
        Vec3 v = e.getDeltaMovement().add(dir.x * push * 1.5, up, dir.z * push * 1.5);
        double max = 3.2;
        if (v.lengthSqr() > max * max) v = v.normalize().scale(max);
        e.setDeltaMovement(v);
        e.hurtMarked = true;

        if (e instanceof LivingEntity le) {
            float dmg;
            if (z < 1) dmg = (10f + intensity * 5f) * w.size;                       // ground zero
            else dmg = (float) ((4 + intensity * 3) * w.size * Math.pow(1.3 / z, 2) * fade);
            if (dmg > 0.5f) le.hurtServer(level, source, dmg);
            // Thermal pulse: the fireball's heat sets anything close on fire.
            if (z < 1.8) le.igniteForTicks((int) (80 + intensity * 30 * (1.8 - z)));
        }
    }

    // ------------------------------------------------------------------ ejecta

    /**
     * Throws real blocks out of the crater on ballistic arcs (45 degree launch, Minecraft gravity and drag), aimed to
     * land 1.2-3 crater radii out, so the ejecta blanket thins with distance as in real craters. Oblique impacts throw
     * more downrange. They hurt what they hit and land as crushed material.
     */
    private void launchEjecta(BlockPos center, float craterR) {
        int max = DisasterConfig.METEOR_MAX_EJECTA.getAsInt();
        ejecta.removeIf(e -> e.entity.isRemoved() && settle(e));
        int budget = Math.min(max, (int) (6 + craterR * 2.2f));
        budget = Math.min(budget, max * 3 - ejecta.size()); // hard cap on simultaneously flying blocks per shower
        for (int i = 0; i < budget; i++) {
            double a = random.nextDouble() * Math.PI * 2;
            double rr = Math.sqrt(random.nextDouble()) * craterR * 0.7;
            int x = Mth.floor(center.getX() + 0.5 + Math.cos(a) * rr), z = Mth.floor(center.getZ() + 0.5 + Math.sin(a) * rr);
            if (!level.isLoaded(new BlockPos(x, 0, z))) continue;
            BlockPos p = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1 - random.nextInt(2), z);
            BlockState st = level.getBlockState(p);
            if (st.isAir() || st.hasBlockEntity() || !st.getFluidState().isEmpty() || st.getDestroySpeed(level, p) < 0
                    || !st.isCollisionShapeFullBlock(level, p) || !DisasterProtection.canModify(level, p, st)) continue;
            FallingBlockEntity fb = FallingBlockEntity.fall(level, p, st);
            fb.dropItem = false;
            fb.disableDrop();            // we place the landing block ourselves (with a protection check)
            fb.setHurtsEntities(1.5f + intensity * 0.3f, 30);
            // Launch: 45 deg +-12, range 1.2R..3R with more material landing close; 1/3 of throws go downrange.
            double az = random.nextFloat() < 0.35f ? travelAz + (random.nextDouble() - 0.5) * 1.6 : random.nextDouble() * Math.PI * 2;
            double range = craterR * (1.2 + 1.8 * Math.pow(random.nextDouble(), 2));
            double theta = Math.toRadians(45 + (random.nextDouble() - 0.5) * 24);
            double speed = Math.sqrt(range * GRAVITY / Math.sin(2 * theta)) / Math.pow(DRAG, range / 4); // drag allowance
            fb.setDeltaMovement(Math.cos(az) * Math.cos(theta) * speed, Math.sin(theta) * speed, Math.sin(az) * Math.cos(theta) * speed);
            fb.hurtMarked = true;
            ejecta.add(new Ejecta(fb, Crater.crushedForEjecta(st, random.nextFloat())));
        }
    }

    private void tickEjecta() {
        if (ejecta.isEmpty()) return;
        ejecta.removeIf(e -> (e.entity.isRemoved() || e.entity.onGround()) && settle(e));
    }

    /** The block has landed (or the shower ended): leave it on the ground as ejecta. Always returns true. */
    private boolean settle(Ejecta e) {
        FallingBlockEntity fb = e.entity;
        if (!fb.isRemoved()) fb.discard();
        int x = Mth.floor(fb.getX()), z = Mth.floor(fb.getZ());
        BlockState to = e.landsAs;
        DisasterManager.blockQueue(level).add(l -> Crater.placeOnSurface(l, x, z, to));
        return true;
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
