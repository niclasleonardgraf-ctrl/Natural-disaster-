package com.realisticdisasters.disaster.tsunami;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.mojang.math.Transformation;
import com.realisticdisasters.config.DisasterConfig;
import com.realisticdisasters.disaster.DisasterManager;
import com.realisticdisasters.disaster.DisasterProtection;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Floating wreckage: pieces of smashed houses and uprooted trees as block-display entities that ride the flood.
 * They float half-submerged on the water surface, are dragged by the local current (inland with the surge, out to sea
 * with the backwash), tumble faster in fast water, knock against walls, and when the water drains away they drop and
 * settle as real blocks: the debris fields a real tsunami leaves behind. Pieces still afloat on the sea at the end are
 * lost to the ocean.
 *
 * Display entities have no physics or collision of their own, so this is cheap: the server moves each piece a little
 * every tick and the client interpolates (teleport_duration).
 */
final class FloodDebris {
    private static final int LERP = 3;

    private final TsunamiDisaster tsunami;
    private final ServerLevel level;
    private final RandomSource random;
    private final int max;
    private final List<Piece> pieces = new ArrayList<>();

    private static final class Piece {
        final Display.BlockDisplay entity;
        final BlockState state;
        Vec3 vel;
        float yaw, pitch, spinYaw, spinPitch;
        int grounded;

        Piece(Display.BlockDisplay entity, BlockState state, Vec3 vel, RandomSource r) {
            this.entity = entity;
            this.state = state;
            this.vel = vel;
            this.yaw = r.nextFloat() * 360f;
            this.pitch = (r.nextFloat() - 0.5f) * 60f;
            this.spinYaw = (r.nextFloat() - 0.5f) * 14f;
            this.spinPitch = (r.nextFloat() - 0.5f) * 10f;
        }
    }

    FloodDebris(TsunamiDisaster tsunami, ServerLevel level, RandomSource random, int max) {
        this.tsunami = tsunami;
        this.level = level;
        this.random = random;
        this.max = max;
    }

    int size() {
        return pieces.size();
    }

    boolean full() {
        return pieces.size() >= max;
    }

    /** Sets a piece of {@code state} afloat at {@code at}. @return false when the cap is reached */
    boolean spawn(BlockState state, Vec3 at, Vec3 vel) {
        if (full()) return false;
        float s = 0.7f + random.nextFloat() * 0.3f;
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "minecraft:block_display");
        tag.store("block_state", BlockState.CODEC, state.getBlock().defaultBlockState());
        // centred on the entity so it tumbles around its middle
        tag.store("transformation", Transformation.EXTENDED_CODEC, new Transformation(new Vector3f(-s / 2, -s / 2, -s / 2),
                new Quaternionf(), new Vector3f(s, s, s), new Quaternionf()));
        tag.putInt("teleport_duration", LERP);
        tag.putFloat("view_range", 2.5f);
        Entity e = EntityType.loadEntityRecursive(tag, level, EntitySpawnReason.EVENT, ent -> {
            ent.snapTo(at.x, at.y, at.z, random.nextFloat() * 360f, 0f);
            return ent;
        });
        if (!(e instanceof Display.BlockDisplay display)) return false;
        display.addTag("realisticdisasters_debris");
        if (!level.addFreshEntity(display)) return false;
        pieces.add(new Piece(display, state.getBlock().defaultBlockState(), vel, random));
        return true;
    }

    void tick() {
        Iterator<Piece> it = pieces.iterator();
        while (it.hasNext()) {
            Piece p = it.next();
            Display.BlockDisplay e = p.entity;
            if (e.isRemoved()) {
                it.remove();
                continue;
            }
            Vec3 pos = e.position();
            BlockPos column = BlockPos.containing(pos.x, 0, pos.z);
            if (!level.isLoaded(column)) {
                e.discard();
                it.remove();
                continue;
            }
            // Look only at the piece's own surroundings (never the column top: it may be under a roof or a tree).
            int waterTop = waterSurfaceNear(column.getX(), Mth.floor(pos.y), column.getZ());
            boolean onWater = waterTop != Integer.MIN_VALUE;
            Vec3 flow = tsunami.flowAt(pos.x, pos.z);
            double vy;
            if (onWater) {
                // Half submerged, bobbing; dragged toward the current's velocity.
                double surface = waterTop + 0.88 + Math.sin((e.tickCount + e.getId()) * 0.25) * 0.08;
                vy = (surface - pos.y) * 0.3;
                p.vel = p.vel.add(flow.subtract(p.vel.x, 0, p.vel.z).scale(0.12));
                p.grounded = 0;
            } else {
                // The water has gone: it drops onto the ground and comes to rest.
                vy = Math.max(-0.6, (p.vel.y - 0.08));
                p.vel = p.vel.multiply(0.7, 1, 0.7);
                int ground = groundBelow(column.getX(), Mth.floor(pos.y), column.getZ());
                if (pos.y - (ground + 1.5) < 0.05) {
                    vy = 0;
                    if (++p.grounded > 12) {
                        settle(p, new BlockPos(column.getX(), ground + 1, column.getZ()));
                        it.remove();
                        continue;
                    }
                }
            }
            p.vel = new Vec3(p.vel.x, vy, p.vel.z);
            double nx = pos.x + p.vel.x, nz = pos.z + p.vel.z;
            // Knock against walls instead of passing through them.
            BlockPos ahead = BlockPos.containing(nx, pos.y, nz);
            if (level.getBlockState(ahead).blocksMotion()) {
                p.vel = new Vec3(-p.vel.x * 0.3, p.vel.y, -p.vel.z * 0.3);
                nx = pos.x;
                nz = pos.z;
                p.spinYaw = -p.spinYaw;
            }
            double speed = Math.sqrt(p.vel.x * p.vel.x + p.vel.z * p.vel.z);
            float tumble = onWater ? (float) Mth.clamp(0.15 + speed * 4, 0.15, 1.6) : 0.05f;
            p.yaw += p.spinYaw * tumble;
            p.pitch = Mth.clamp(p.pitch + p.spinPitch * tumble, -80f, 80f);
            if (Math.abs(p.pitch) >= 80f) p.spinPitch = -p.spinPitch;
            e.snapTo(nx, pos.y + vy, nz, p.yaw, p.pitch);
        }
    }

    /** Highest water block from just above the piece down to a few blocks below it, or MIN_VALUE if none. */
    private int waterSurfaceNear(int x, int y, int z) {
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        for (int yy = y + 1; yy >= y - 3; yy--) {
            if (!level.getFluidState(mp.set(x, yy, z)).isEmpty()) return yy;
        }
        return Integer.MIN_VALUE;
    }

    /** The first solid block at or below the piece (it can only fall, never jump onto a roof above it). */
    private int groundBelow(int x, int y, int z) {
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        for (int yy = y; yy > y - 24 && yy > level.getMinY(); yy--) {
            BlockState st = level.getBlockState(mp.set(x, yy, z));
            if (st.blocksMotion() || !st.getFluidState().isEmpty()) return yy;
        }
        return y - 24;
    }

    /** Leaves the piece where it lies: a real block if there's room and it may build there, otherwise an item. */
    private void settle(Piece p, BlockPos at) {
        Display.BlockDisplay e = p.entity;
        e.discard();
        if (!DisasterConfig.BLOCK_DESTRUCTION.getAsBoolean()) return;
        BlockState st = p.state;
        DisasterManager.blockQueue(level).add(l -> {
            if (!l.isLoaded(at)) return;
            BlockState here = l.getBlockState(at);
            if (here.isAir() && l.getFluidState(at).isEmpty() && st.canSurvive(l, at)
                    && DisasterProtection.canModify(l, at, here)) {
                l.setBlock(at, st, Block.UPDATE_ALL);
                l.playSound(null, at, com.realisticdisasters.registry.ModRegistries.DEBRIS_THUD.get(),
                        net.minecraft.sounds.SoundSource.BLOCKS, 0.7f, 0.9f + l.getRandom().nextFloat() * 0.2f);
            } else {
                Block.dropResources(st, l, at);
            }
        });
    }

    /** End of the disaster: pieces on dry land settle, pieces still afloat (out at sea) are lost to the ocean. */
    void finish() {
        for (Piece p : pieces) {
            Display.BlockDisplay e = p.entity;
            if (e.isRemoved()) continue;
            BlockPos at = e.blockPosition();
            if (waterSurfaceNear(at.getX(), at.getY(), at.getZ()) != Integer.MIN_VALUE) e.discard();
            else settle(p, new BlockPos(at.getX(), groundBelow(at.getX(), at.getY(), at.getZ()) + 1, at.getZ()));
        }
        pieces.clear();
    }
}
