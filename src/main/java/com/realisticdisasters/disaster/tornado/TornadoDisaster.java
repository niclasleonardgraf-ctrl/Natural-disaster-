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
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundSource;
import net.neoforged.neoforge.common.Tags;
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
    private final List<Debris> debris = new ArrayList<>();
    /** Horizontal speed of each pushed entity last tick (for wall-impact damage). */
    private it.unimi.dsi.fastutil.ints.Int2DoubleOpenHashMap lastSpeed = new it.unimi.dsi.fastutil.ints.Int2DoubleOpenHashMap();

    /** A block thrown by the tornado: a projectile with material-dependent drag. */
    private static final class Debris {
        final FallingBlockEntity entity;
        final BlockState state;
        final double drag;
        final double mass;
        final it.unimi.dsi.fastutil.ints.IntOpenHashSet hit = new it.unimi.dsi.fastutil.ints.IntOpenHashSet();
        Vec3 lastPos;
        int age;

        Debris(FallingBlockEntity entity, BlockState state, double drag, double mass) {
            this.entity = entity;
            this.state = state;
            this.drag = drag;
            this.mass = mass;
            this.lastPos = entity.position();
        }
    }
    private double heading;
    private double headingDrift;
    private double currentSpeed;
    private boolean changedWeather;

    public TornadoDisaster(ServerLevel level, int id, Vec3 pos, int intensity) {
        super(DisasterType.TORNADO, level, id, pos, intensity);
        int i = this.intensity;
        this.coreRadius = 3f + i * 2f;              // 5 .. 13
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

    /**
     * Instant removal (/disaster stop ... now): airborne debris goes with it, so nothing keeps raining down afterwards.
     * A natural end (aftermath) still lets the debris land.
     */
    @Override
    public void forceFinish() {
        for (Debris d : debris) {
            if (!d.entity.isRemoved()) d.entity.discard();
        }
        super.forceFinish();
    }

    @Override
    protected void onEnd() {
        debris.clear();
        lastSpeed.clear();
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
        TornadoWind wind = wind();
        if (force > 0.05f) {
            applyWindToEntities(wind, force);
            if (force > 0.25f) loadBlocks(wind, force);
        }
        tickDebris(wind);

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

    /** The tornado's current wind field (see {@link TornadoWind}). */
    public TornadoWind wind() {
        return new TornadoWind(pos, coreRadius, height, intensity, Math.cos(heading), Math.sin(heading), currentSpeed,
                strength() * touchdown());
    }

    /**
     * Fraction of the wind reaching a point. Out in the open: all of it. Under a roof it depends on how enclosed the
     * spot is; underground or in a closed room almost nothing gets through (until the roof is torn off).
     */
    private float exposure(BlockPos at) {
        if (level.canSeeSky(at)) return 1f;
        int open = 0;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos a = at.relative(dir), b = a.relative(dir);
            if (!level.getBlockState(a).blocksMotion() && !level.getBlockState(b).blocksMotion()) open++;
        }
        // A sealed room (basement, bunker) gets no wind at all; each open side lets more in.
        return 0.2f * open;
    }

    // ------------------------------------------------------------------ entities

    private void applyWindToEntities(TornadoWind wind, float force) {
        double outer = wind.outerRadius();
        AABB box = new AABB(pos.x - outer, pos.y - 8, pos.z - outer, pos.x + outer, pos.y + height, pos.z + outer);
        DamageSource abrasion = level.damageSources().source(ModRegistries.TORNADO_DAMAGE);
        var speeds = new it.unimi.dsi.fastutil.ints.Int2DoubleOpenHashMap();
        for (Entity e : level.getEntities((Entity) null, box, e -> !e.isSpectator() && !(e instanceof FallingBlockEntity))) {
            if (e instanceof Player p && p.getAbilities().flying) continue;
            if (e.isPassenger()) continue;
            Vec3 w = wind.at(e.getX(), e.getY() + e.getBbHeight() * 0.5, e.getZ());
            if (w.lengthSqr() < 0.0025) continue;
            float shelter = exposure(BlockPos.containing(e.getEyePosition()));
            w = w.scale(shelter);

            if (e instanceof net.minecraft.world.entity.decoration.HangingEntity hanging) {
                // Paintings and item frames get torn off the wall.
                if (w.horizontalDistance() > 0.65 && random.nextFloat() < 0.15f) hanging.hurtServer(level, level.damageSources().generic(), 1f);
                continue;
            }

            // Slammed into a wall at speed? That hurts (same rule as flying into a wall with elytra).
            double prev = lastSpeed.get(e.getId());
            if (e.horizontalCollision && prev > 0.55 && e instanceof LivingEntity le) {
                le.hurtServer(level, level.damageSources().flyIntoWall(), (float) ((prev - 0.55) * 14));
            }

            // Aerodynamic drag: the push depends on the speed difference between wind and object, and on how
            // much surface the object offers per unit of mass. Objects already moving with the wind feel nothing.
            Vec3 v = e.getDeltaMovement();
            Vec3 rel = w.subtract(v);
            double k = dragCoefficient(e);
            if (e.onGround()) k *= 0.55;   // friction / grip until a gust gets underneath
            Vec3 nv = v.add(rel.scale(k * Math.min(1.5, 0.35 + rel.length())));
            double max = 1.8;
            if (nv.lengthSqr() > max * max) nv = nv.normalize().scale(max);
            e.setDeltaMovement(nv);
            e.hurtMarked = true;
            speeds.put(e.getId(), nv.horizontalDistance());

            // Inside the debris-laden core you get sandblasted.
            double dx = e.getX() - pos.x, dz = e.getZ() - pos.z;
            if (e instanceof LivingEntity le && age() % 10 == 0 && shelter > 0.5f && dx * dx + dz * dz < coreRadius * coreRadius) {
                le.hurtServer(level, abrasion, (0.5f + intensity * 0.5f) * force);
            }
        }
        lastSpeed = speeds;
    }

    /** Drag per tick relative to the wind: big light objects fly, small heavy ones resist. */
    private static double dragCoefficient(Entity e) {
        double k;
        if (e instanceof net.minecraft.world.entity.item.ItemEntity || e instanceof net.minecraft.world.entity.ExperienceOrb) {
            k = 0.16;
        } else if (e instanceof net.minecraft.world.entity.projectile.Projectile) {
            k = 0.05;
        } else if (e instanceof LivingEntity le) {
            double area = e.getBbWidth() * e.getBbHeight();
            k = 0.1 / Math.max(0.6, Math.sqrt(area));
            k *= 1 - 0.8 * Mth.clamp(le.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE), 0, 1);
            if (le.isShiftKeyDown()) k *= 0.6;   // crouching lowers your profile
        } else {
            k = 0.07;   // boats, minecarts, armor stands...
        }
        if (e.isInWater()) k *= 0.3;
        return k;
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

    // ------------------------------------------------------------------ blocks

    /**
     * Wind loading on blocks: samples points in and around the funnel (including walls, roofs and tree crowns, not
     * just the ground), compares the local wind against the block's wind resistance and how exposed it is, and fails
     * it the way that material would fail. Changes go through the batched block queue.
     */
    private void loadBlocks(TornadoWind wind, float force) {
        if (!DisasterConfig.BLOCK_DESTRUCTION.getAsBoolean()) return;
        int attempts = Math.round((16 + intensity * 16) * force);
        var queue = DisasterManager.blockQueue(level);
        double reach = coreRadius * 2.0;
        int bottom = Mth.floor(pos.y) - 2;
        for (int i = 0; i < attempts; i++) {
            double a = random.nextDouble() * Math.PI * 2;
            // most samples where the winds peak (inside ~1.25 core radii), the rest in the outer field
            double rr = random.nextFloat() < 0.7f ? Math.sqrt(random.nextDouble()) * coreRadius * 1.25
                    : coreRadius * 1.25 + random.nextDouble() * (reach - coreRadius * 1.25);
            int x = Mth.floor(pos.x + Math.cos(a) * rr);
            int z = Mth.floor(pos.z + Math.sin(a) * rr);
            if (!level.isLoaded(new BlockPos(x, 0, z))) continue;
            int top = Math.min(level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1, bottom + 48);
            if (top < bottom) continue;
            // biased toward the top of the column: exposed surfaces (roofs, crowns, the ground) take the wind
            double u = random.nextDouble();
            int y = top - (int) (u * u * (top - bottom + 1));
            double speed = wind.at(x + 0.5, y + 0.5, z + 0.5).length();
            if (speed < 0.2) continue;
            // Evaluate the wind load right away (cheap reads); only blocks that actually fail go through the shared,
            // budgeted block-change queue, so the tornado doesn't crowd out other disasters' work.
            BlockPos failing = windLoad(new BlockPos(x, y, z), speed);
            if (failing != null && !queue.add(l -> applyFailure(failing))) return;
        }
    }

    /** Re-validates and applies a failure decided earlier (the world may have changed while it was queued). */
    private void applyFailure(BlockPos p) {
        if (isFinished()) return;
        BlockState st = level.getBlockState(p);
        if (st.isAir() || st.getBlock() instanceof LiquidBlock) return;
        int open = openFaces(p);
        if (open == 0) return;
        if (!DisasterProtection.canModify(level, p, st)) return;
        fail(p, st, open);
    }

    /** @return the block that fails under this wind, or null */
    private BlockPos windLoad(BlockPos sample, double windSpeed) {
        // A sample in the air drops to the surface below it: wind attacks surfaces.
        BlockPos p = sample;
        BlockState st = level.getBlockState(p);
        for (int i = 0; i < 6 && st.isAir(); i++) {
            p = p.below();
            st = level.getBlockState(p);
        }
        if (st.isAir() || st.getBlock() instanceof LiquidBlock) return null;
        int open = openFaces(p);
        if (open == 0) return null;   // fully enclosed: the wind can't get at it
        // Wind only loads faces that face the outside: a floor or wall inside a sealed room is protected until the
        // roof or walls around it are torn away.
        float reach = 0f;
        if (!level.getBlockState(p.above()).blocksMotion()) reach = Math.max(reach, exposure(p.above()));
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            if (reach >= 1f) break;
            BlockPos q = p.relative(dir);
            if (!level.getBlockState(q).blocksMotion()) reach = Math.max(reach, exposure(q));
        }
        if (reach <= 0f) return null;
        double resist = windResistance(st, p);
        double load = windSpeed * reach * (0.45 + 0.55 * open / 5.0);
        double over = load / resist - 1;
        if (over <= 0 || random.nextDouble() > Math.min(1, over * 2.5)) return null;
        return p;
    }

    private int openFaces(BlockPos p) {
        int open = 0;
        if (!level.getBlockState(p.above()).blocksMotion()) open++;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            if (!level.getBlockState(p.relative(dir)).blocksMotion()) open++;
        }
        return open;
    }

    /** Wind speed (blocks/tick, fully exposed) a block withstands. */
    private double windResistance(BlockState st, BlockPos p) {
        if (st.hasBlockEntity()) return Double.MAX_VALUE;
        float hardness = st.getDestroySpeed(level, p);
        if (hardness < 0) return Double.MAX_VALUE;
        // Calibrated so a tornado does tornado-like damage: EF1-ish (intensity 1) strips trees and breaks windows,
        // intensity 3 tears roofs off and snaps trunks, intensity 4-5 levels wooden houses and breaks stone walls.
        if (st.canBeReplaced() || st.is(BlockTags.FLOWERS) || st.is(BlockTags.SAPLINGS) || st.is(BlockTags.CROPS)) return 0.08;
        if (st.is(BlockTags.LEAVES)) return 0.15;
        if (st.is(Tags.Blocks.GLASS_BLOCKS) || st.is(Tags.Blocks.GLASS_PANES)) return 0.25;
        if (st.is(BlockTags.WOOL) || st.is(BlockTags.WOOL_CARPETS)) return 0.3;
        if (st.is(BlockTags.PLANKS) || st.is(BlockTags.WOODEN_FENCES) || st.is(BlockTags.WOODEN_DOORS)
                || st.is(BlockTags.WOODEN_STAIRS) || st.is(BlockTags.WOODEN_SLABS) || st.is(BlockTags.WOODEN_TRAPDOORS)) return 0.4;
        if (st.is(BlockTags.LOGS)) return 0.5;
        if (isTurf(st) || isSoil(st)) return 0.55;
        return 0.2 + hardness * 0.3;   // stone ~0.65, cobble/bricks ~0.8, deepslate ~1.1, obsidian: never
    }

    private static boolean isTurf(BlockState st) {
        return st.is(Blocks.GRASS_BLOCK) || st.is(Blocks.PODZOL) || st.is(Blocks.MYCELIUM) || st.is(Blocks.DIRT_PATH);
    }

    private static boolean isSoil(BlockState st) {
        return st.is(BlockTags.DIRT) || st.is(BlockTags.SAND) || st.is(Blocks.GRAVEL);
    }

    private static boolean isFragile(BlockState st) {
        return !st.isAir() && (st.canBeReplaced() || st.is(BlockTags.LEAVES) || st.is(Tags.Blocks.GLASS_BLOCKS)
                || st.is(Tags.Blocks.GLASS_PANES) || st.is(BlockTags.FLOWERS) || st.is(BlockTags.CROPS))
                && st.getFluidState().isEmpty();
    }

    /** Fails a block the way its material fails in real wind. */
    private void fail(BlockPos p, BlockState st, int open) {
        boolean topExposed = !level.getBlockState(p.above()).blocksMotion();
        if (isTurf(st)) {
            if (!topExposed) return;
            level.levelEvent(2001, p, net.minecraft.world.level.block.Block.getId(st));
            level.setBlock(p, random.nextFloat() < 0.35f ? Blocks.COARSE_DIRT.defaultBlockState() : Blocks.DIRT.defaultBlockState(), 3);
            return;
        }
        if (isSoil(st)) {
            // Only violent tornadoes scour the soil itself.
            if (topExposed && intensity >= 3 && random.nextFloat() < 0.3f) launch(p, st, true);
            return;
        }
        if (st.canBeReplaced() || st.is(BlockTags.FLOWERS) || st.is(BlockTags.CROPS) || st.is(BlockTags.SAPLINGS)) {
            level.destroyBlock(p, false);
            return;
        }
        if (st.is(Tags.Blocks.GLASS_BLOCKS) || st.is(Tags.Blocks.GLASS_PANES)) {
            level.destroyBlock(p, false);   // shatters (vanilla glass-break sound and shards)
            return;
        }
        if (st.is(BlockTags.LOGS)) {
            snapTree(p);
            return;
        }
        if (st.is(BlockTags.LEAVES) && random.nextFloat() < 0.5f) {
            level.destroyBlock(p, false);
            return;
        }
        if (!launch(p, st, false)) level.destroyBlock(p, false);
    }

    /** A trunk fails at this point: everything above it goes with the wind. */
    private void snapTree(BlockPos base) {
        level.playSound(null, base, ModRegistries.WOOD_SNAP.get(), SoundSource.BLOCKS, 2.5f, 0.8f + random.nextFloat() * 0.3f);
        BlockPos p = base;
        for (int i = 0; i < 10; i++) {
            BlockState st = level.getBlockState(p);
            if (!st.is(BlockTags.LOGS) && !(i > 0 && st.is(BlockTags.LEAVES))) break;
            if (!DisasterProtection.canModify(level, p, st)) break;
            if (!launch(p, st, false)) level.destroyBlock(p, false);
            p = p.above();
        }
    }

    /** Throws a block into the wind as falling-block debris. Returns false when the debris cap is reached. */
    private boolean launch(BlockPos p, BlockState st, boolean crumble) {
        if (debris.size() >= maxDebris) return false;
        FallingBlockEntity fb = FallingBlockEntity.fall(level, p, st);
        fb.dropItem = false;
        if (crumble) fb.disableDrop();   // soil clods crumble on impact instead of littering the land
        fb.setHurtsEntities(0.6f + intensity * 0.3f, 8 + intensity * 4);
        Vec3 w = wind().at(fb.getX(), fb.getY() + 0.5, fb.getZ());
        fb.setDeltaMovement(w.x * 0.7, Math.max(0.3, w.y * 0.6) + 0.15, w.z * 0.7);
        fb.hurtMarked = true;
        double drag, mass;
        if (st.is(BlockTags.LEAVES) || st.is(BlockTags.WOOL)) { drag = 0.14; mass = 0.2; }
        else if (st.is(BlockTags.LOGS) || st.is(BlockTags.PLANKS) || st.getSoundType() == net.minecraft.world.level.block.SoundType.WOOD) { drag = 0.08; mass = 0.6; }
        else if (isSoil(st)) { drag = 0.07; mass = 0.7; }
        else { drag = 0.045; mass = 1.0; }
        debris.add(new Debris(fb, st, drag, mass));
        return true;
    }

    // ------------------------------------------------------------------ debris

    private void tickDebris(TornadoWind wind) {
        if (debris.isEmpty()) return;
        var it = debris.iterator();
        while (it.hasNext()) {
            Debris d = it.next();
            FallingBlockEntity fb = d.entity;
            d.age++;
            if (fb.isRemoved() || d.age > 400) {
                land(d);
                it.remove();
                continue;
            }
            Vec3 v = fb.getDeltaMovement();
            Vec3 w = wind.at(fb.getX(), fb.getY() + 0.5, fb.getZ());
            Vec3 rel = w.subtract(v);
            v = v.add(rel.scale(d.drag * Math.min(1.5, 0.35 + rel.length())));
            double speed = v.length();
            if (speed > 2.0) v = v.scale(2.0 / speed);

            if (speed > 0.5) {
                // Smashes through glass, leaves and plants in its path.
                BlockPos ahead = BlockPos.containing(fb.position().add(v));
                BlockState s = level.getBlockState(ahead);
                if (isFragile(s) && DisasterConfig.BLOCK_DESTRUCTION.getAsBoolean() && DisasterProtection.canModify(level, ahead, s)) {
                    level.destroyBlock(ahead, false);
                }
                // Hits creatures in mid-air: damage scales with speed and mass.
                for (LivingEntity le : level.getEntitiesOfClass(LivingEntity.class, fb.getBoundingBox().inflate(0.4),
                        le -> !d.hit.contains(le.getId()) && !le.isSpectator())) {
                    d.hit.add(le.getId());
                    le.hurtServer(level, level.damageSources().fallingBlock(fb), (float) (1 + speed * 7 * d.mass));
                    le.push(v.x * 0.5 * d.mass, 0.2, v.z * 0.5 * d.mass);
                    level.playSound(null, le.getX(), le.getY(), le.getZ(), ModRegistries.DEBRIS_THUD.get(), SoundSource.BLOCKS, 1.2f, 1.2f);
                }
            }
            fb.setDeltaMovement(v);
            fb.hurtMarked = true;
            d.lastPos = fb.position();
        }
    }

    /** Impact: the block's own material sound plus a heavy thud, scaled by mass. */
    private void land(Debris d) {
        Vec3 p = d.lastPos;
        var sound = d.state.getSoundType();
        level.playSound(null, p.x, p.y, p.z, sound.getBreakSound(), SoundSource.BLOCKS, 1.3f, sound.getPitch() * 0.8f);
        if (d.mass >= 0.5) {
            level.playSound(null, p.x, p.y, p.z, ModRegistries.DEBRIS_THUD.get(), SoundSource.BLOCKS,
                    (float) (0.8 + d.mass), 0.8f + random.nextFloat() * 0.3f);
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
