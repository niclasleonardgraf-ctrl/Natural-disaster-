package com.realisticdisasters.disaster.meteor;

import java.util.ArrayList;
import java.util.List;

import com.realisticdisasters.config.DisasterConfig;
import com.realisticdisasters.disaster.BlockChangeQueue;
import com.realisticdisasters.disaster.DisasterManager;
import com.realisticdisasters.disaster.DisasterProtection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.StainedGlassBlock;
import net.minecraft.world.level.block.TransparentBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * What the air blast does to the world around an impact (besides the crater):
 * <ul>
 *   <li>Glass and glass panes shatter out to about six crater radii (like the windows blown in for kilometres
 *       around the Chelyabinsk airburst), always close in, increasingly patchy toward the edge.</li>
 *   <li>Leaves are stripped and plants, flowers and snow layers flattened within about two crater radii.</li>
 * </ul>
 * Work is done one chunk section (glass) or one section layer (vegetation) per queued change, nearest first, and
 * sections whose palette can't contain anything breakable are skipped without being scanned, so plain terrain
 * costs next to nothing. Every change re-checks {@link DisasterProtection}.
 */
final class BlastDamage {
    private static final int MAX_GLASS_RADIUS = 64;
    private static final int MAX_PLANT_RADIUS = 28;

    static void enqueue(MeteorShowerDisaster d, BlockPos center, float craterR) {
        if (!DisasterConfig.BLOCK_DESTRUCTION.getAsBoolean() || !DisasterConfig.METEOR_BLAST_DAMAGE.getAsBoolean()) return;
        ServerLevel level = d.serverLevel();
        BlockChangeQueue queue = DisasterManager.blockQueue(level);
        float glassR = Math.min(MAX_GLASS_RADIUS, craterR * 6f + 8f);
        float plantR = Math.min(MAX_PLANT_RADIUS, craterR * 2.2f);
        long seed = d.rng().nextLong();

        List<long[]> sections = new ArrayList<>(); // {sectionX, sectionY, sectionZ, distSq}
        int minS = SectionPos.blockToSectionCoord(center.getX() - glassR), maxS = SectionPos.blockToSectionCoord(center.getX() + glassR);
        int minSz = SectionPos.blockToSectionCoord(center.getZ() - glassR), maxSz = SectionPos.blockToSectionCoord(center.getZ() + glassR);
        int minSy = SectionPos.blockToSectionCoord(Math.max(level.getMinY(), center.getY() - glassR * 0.5f));
        int maxSy = SectionPos.blockToSectionCoord(Math.min(level.getMaxY(), center.getY() + glassR * 0.75f));
        for (int sx = minS; sx <= maxS; sx++) {
            for (int sz = minSz; sz <= maxSz; sz++) {
                for (int sy = minSy; sy <= maxSy; sy++) {
                    double dx = SectionPos.sectionToBlockCoord(sx) + 8 - center.getX();
                    double dy = SectionPos.sectionToBlockCoord(sy) + 8 - center.getY();
                    double dz = SectionPos.sectionToBlockCoord(sz) + 8 - center.getZ();
                    double distSq = dx * dx + dy * dy + dz * dz;
                    if (distSq > (glassR + 14) * (glassR + 14)) continue;
                    sections.add(new long[]{sx, sy, sz, (long) distSq});
                }
            }
        }
        sections.sort((a, b) -> Long.compare(a[3], b[3]));
        for (long[] s : sections) {
            int sx = (int) s[0], sy = (int) s[1], sz = (int) s[2];
            LevelChunkSection section = section(level, sx, sy, sz);
            if (section == null || section.hasOnlyAir()) continue;
            if (section.maybeHas(BlastDamage::fragile)) {
                queue.add(l -> shatter(l, sx, sy, sz, center, glassR, seed));
            }
            if (s[3] < (plantR + 14) * (plantR + 14) && section.maybeHas(BlastDamage::flattenable)) {
                for (int layer = 0; layer < 16; layer++) {
                    int y = SectionPos.sectionToBlockCoord(sy) + layer;
                    if (Math.abs(y - center.getY()) > plantR) continue;
                    queue.add(l -> flatten(l, sx, y, sz, center, plantR, seed));
                }
            }
        }
    }

    private static LevelChunkSection section(ServerLevel level, int sx, int sy, int sz) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(sx, sz);
        if (chunk == null) return null;
        int index = chunk.getSectionIndexFromSectionY(sy);
        if (index < 0 || index >= chunk.getSections().length) return null;
        return chunk.getSection(index);
    }

    /** Glass blocks and panes (not ice, glowstone, sea lanterns etc., which merely share the glass sound). */
    static boolean fragile(BlockState st) {
        Block b = st.getBlock();
        return (b instanceof TransparentBlock || b instanceof StainedGlassBlock || b instanceof IronBarsBlock)
                && st.getSoundType() == SoundType.GLASS && !st.is(BlockTags.ICE);
    }

    static boolean flattenable(BlockState st) {
        return st.is(BlockTags.LEAVES) || st.is(Blocks.SNOW) || !st.isAir() && st.canBeReplaced() && st.getFluidState().isEmpty()
                || st.is(BlockTags.FLOWERS) || st.is(BlockTags.SAPLINGS);
    }

    private static void shatter(ServerLevel level, int sx, int sy, int sz, BlockPos center, float radius, long seed) {
        LevelChunkSection section = section(level, sx, sy, sz);
        if (section == null || !section.maybeHas(BlastDamage::fragile)) return;
        int bx = SectionPos.sectionToBlockCoord(sx), by = SectionPos.sectionToBlockCoord(sy), bz = SectionPos.sectionToBlockCoord(sz);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockState st = section.getBlockState(x, y, z);
                    if (!fragile(st)) continue;
                    p.set(bx + x, by + y, bz + z);
                    double dist = Math.sqrt(p.distSqr(center));
                    if (dist > radius) continue;
                    // Always within half the radius, then patchier (sheltered panes survive).
                    double chance = dist < radius * 0.5 ? 1.0 : 1.0 - (dist / radius - 0.5) * 1.4;
                    if (hash(seed, p) >= chance) continue;
                    if (!DisasterProtection.canModify(level, p, st)) continue;
                    level.destroyBlock(p, false); // shatter sound + shards
                }
            }
        }
    }

    private static void flatten(ServerLevel level, int sx, int y, int sz, BlockPos center, float radius, long seed) {
        int sy = SectionPos.blockToSectionCoord(y);
        LevelChunkSection section = section(level, sx, sy, sz);
        if (section == null || !section.maybeHas(BlastDamage::flattenable)) return;
        int bx = SectionPos.sectionToBlockCoord(sx), bz = SectionPos.sectionToBlockCoord(sz);
        int ly = y & 15;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                BlockState st = section.getBlockState(x, ly, z);
                if (!flattenable(st)) continue;
                p.set(bx + x, y, bz + z);
                double dist = Math.sqrt(p.distSqr(center));
                if (dist > radius) continue;
                boolean leaves = st.is(BlockTags.LEAVES);
                // Plants go everywhere in range; leaves are stripped fully close in, partly further out.
                double chance = leaves ? Mth.clamp(1.3 - dist / radius, 0, 1) : 1.0;
                if (hash(seed, p) >= chance) continue;
                if (!DisasterProtection.canModify(level, p, st)) continue;
                level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
    }

    /** Deterministic 0..1 value per position (no shared RNG state across queued changes). */
    private static double hash(long seed, BlockPos p) {
        long h = seed ^ p.asLong() * 0x9E3779B97F4A7C15L;
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53;
    }

    private BlastDamage() {}
}
