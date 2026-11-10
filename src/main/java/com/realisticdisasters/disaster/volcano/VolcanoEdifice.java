package com.realisticdisasters.disaster.volcano;

import com.realisticdisasters.config.DisasterConfig;
import com.realisticdisasters.disaster.DisasterProtection;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

/**
 * The volcano's mountain (edifice). A stratovolcano piles up from its own eruptions, so the mountain grows during the
 * eruption, keeping its shape (self-similar growth): a concave profile like Mount Fuji, steep near the summit and
 * flattening towards the foot, with a bowl-shaped summit crater. It is built solid, column by column, under a time
 * budget per tick (batched), out of layered strata of lava rock and tephra, with dark scoria at the top.
 * <p>
 * Size per intensity: ~10 blocks tall (a cinder cone) up to ~130 blocks tall and ~200 blocks in radius at intensity 5,
 * as big as the largest vanilla mountains. Columns in unloaded chunks are skipped until they load.
 */
final class VolcanoEdifice {
    /** Final summit height above the base and base radius per intensity 1..5. */
    static final int[] HEIGHT = {0, 10, 24, 46, 80, 130};
    /** Building time budget in milliseconds per tick per intensity. */
    private static final double[] BUDGET_MS = {0, 0.3, 0.5, 0.8, 1.4, 2.2};
    private static final double SLOPE_EXPONENT = 2.0;

    private final ServerLevel level;
    private final RandomSource random;
    private final int intensity;
    final int cx, cz, baseY;
    final int height, radius;
    private final boolean enabled;

    /** Columns (dx, dz) within the base radius, sorted by distance so the summit grows first. */
    private final int[] colX, colZ;
    private final float[] colDist;
    private final float[] colAngle;
    private final double phase1, phase2;
    private final int gullies;
    private final net.minecraft.world.level.levelgen.synth.ImprovedNoise surfaceNoise;
    /** Highest y placed in each column (or the original ground top), Integer.MIN_VALUE = not inspected yet. */
    private final int[] built;
    /** 0 = unknown, 1 = may modify, 2 = protected. */
    private final byte[] allowed;
    private int cursor;
    private float scale;   // current growth 0..1
    private int placed;

    VolcanoEdifice(ServerLevel level, RandomSource random, int cx, int cz, int baseY, int intensity, boolean enabled) {
        this.level = level;
        this.random = random;
        this.intensity = intensity;
        this.cx = cx;
        this.cz = cz;
        this.baseY = baseY;
        // Keep the summit (and its crater) well inside the build height.
        int maxH = Math.max(6, level.getMaxY() - 24 - baseY);
        this.height = Math.min(HEIGHT[intensity], maxH);
        this.radius = (int) Math.ceil(height * 1.5) + 2;
        this.enabled = enabled;

        int n = 0;
        int r2 = radius * radius;
        for (int dx = -radius; dx <= radius; dx++)
            for (int dz = -radius; dz <= radius; dz++)
                if (dx * dx + dz * dz <= r2) n++;
        Integer[] order = new Integer[n];
        int[] xs = new int[n], zs = new int[n];
        n = 0;
        for (int dx = -radius; dx <= radius; dx++)
            for (int dz = -radius; dz <= radius; dz++)
                if (dx * dx + dz * dz <= r2) {
                    xs[n] = dx;
                    zs[n] = dz;
                    order[n] = n;
                    n++;
                }
        java.util.Arrays.sort(order, (a, b) -> Integer.compare(xs[a] * xs[a] + zs[a] * zs[a], xs[b] * xs[b] + zs[b] * zs[b]));
        colX = new int[n];
        colZ = new int[n];
        colDist = new float[n];
        colAngle = new float[n];
        phase1 = random.nextDouble() * Math.PI * 2;
        phase2 = random.nextDouble() * Math.PI * 2;
        gullies = 9 + random.nextInt(6);
        surfaceNoise = new net.minecraft.world.level.levelgen.synth.ImprovedNoise(random);
        built = new int[n];
        allowed = new byte[n];
        for (int i = 0; i < n; i++) {
            colX[i] = xs[order[i]];
            colZ[i] = zs[order[i]];
            colDist[i] = Mth.sqrt(colX[i] * colX[i] + colZ[i] * colZ[i]);
            colAngle[i] = (float) Math.atan2(colZ[i], colX[i]);
            built[i] = Integer.MIN_VALUE;
        }
    }

    // ------------------------------------------------------------------ shape

    float scale() {
        return scale;
    }

    void setScale(float s) {
        scale = Mth.clamp(Math.max(scale, s), 0f, 1f);
    }

    double craterRadius() {
        return Math.max(1.2, 0.1 * radius * scale);
    }

    double craterDepth() {
        return Math.max(1.0, 0.1 * height * scale);
    }

    /** Current summit (rim) height above the base. */
    double rimHeight() {
        return height * scale;
    }

    /** Target top y for a column (index i), including the radial gullies and the irregular footprint. */
    private int targetTop(int i) {
        return targetTop(colDist[i], colAngle[i]);
    }

    /** Target top y (inclusive) at horizontal distance d for the current growth, or baseY - 1 outside the cone. */
    int targetTop(double d) {
        return targetTop(d, Double.NaN);
    }

    /**
     * The shape of a real stratovolcano is not a perfect cone: lava flows and lahars carve radial gullies with ridges
     * between them, deepening down the flanks, and the footprint is irregular.
     */
    private int targetTop(double d, double angle) {
        if (Double.isNaN(angle)) return targetTopRound(d, 1.0);
        double lobes = 1 + 0.09 * Math.sin(angle * 3 + phase1) + 0.05 * Math.sin(angle * 5 + phase2);
        int t = targetTopRound(d / lobes, 1.0);
        if (t < baseY) return t;
        double dn = d / (radius * scale + 1e-3);
        if (dn > 0.15) {
            double ridge = Math.abs(Math.sin(angle * gullies + 1.7 * Math.sin(d * 0.045 + phase1)));
            double depth = (1 - ridge) * (1 - ridge) * Math.min(1, (dn - 0.15) * 2.5) * (0.045 * height * scale + 1.2);
            t -= (int) Math.round(depth * Math.min(1, (t - baseY + 1) / 4.0));
        }
        return Math.max(t, baseY - 1);
    }

    private int targetTopRound(double d, double unused) {
        if (scale <= 0f) return baseY - 1;
        double sR = radius * scale, cR = craterRadius();
        if (d >= sR) return baseY - 1;
        double rim = rimHeight();
        double h;
        if (d < cR) {
            double k = d / cR;
            h = rim - craterDepth() * (1 - k * k);       // bowl-shaped crater
        } else {
            double u = (d - cR) / Math.max(1e-3, sR - cR);
            h = rim * Math.pow(1 - u, SLOPE_EXPONENT);   // concave flank
        }
        return baseY + (int) Math.floor(h);
    }

    /** y of the first air block above the crater floor (where the lava lake / vent sits). */
    int ventY() {
        return Math.max(baseY + 1, targetTop(0) + 1);
    }

    int placedBlocks() {
        return placed;
    }

    /** True once the mountain reached full size and a whole pass found nothing left to build. */
    boolean finished() {
        return scale >= 1f && fullPasses >= 2 && !lastPassWork;
    }

    private boolean workThisPass, lastPassWork = true;
    private int fullPasses;

    private void endPass() {
        lastPassWork = workThisPass;
        workThisPass = false;
        cursor = 0;
        if (scale >= 1f) fullPasses++;
    }

    // ------------------------------------------------------------------ building

    /** Raises columns toward the current target within the tick's time budget. */
    void tick() {
        if (!enabled || !DisasterConfig.BLOCK_DESTRUCTION.getAsBoolean() || scale <= 0f) return;
        long deadline = System.nanoTime() + (long) (BUDGET_MS[intensity] * 1_000_000);
        int n = colX.length;
        double sR = radius * scale;
        // Round-robin over the whole footprint so the cone rises evenly (no plinth around the summit).
        for (int visited = 0; visited < n; visited++) {
            if ((visited & 15) == 0 && System.nanoTime() > deadline) return;
            int i = cursor;
            if (colDist[i] < sR + 2 && raise(i)) workThisPass = true;
            if (++cursor >= n) endPass();
        }
    }

    /** @return true if the column still needed work */
    private boolean raise(int i) {
        int x = cx + colX[i], z = cz + colZ[i];
        BlockPos column = new BlockPos(x, baseY, z);
        if (!level.isLoaded(column)) return true;
        if (built[i] == Integer.MIN_VALUE) {
            built[i] = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        }
        int target = targetTop(i);
        if (built[i] >= target) return false;
        if (allowed[i] == 0) {
            allowed[i] = DisasterProtection.canModify(level, column, level.getBlockState(column)) ? (byte) 1 : (byte) 2;
        }
        if (allowed[i] == 2) {
            built[i] = Integer.MAX_VALUE;
            return false;
        }
        int finalTop = topAtFullSize(i);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int y = Math.max(built[i] + 1, level.getMinY()); y <= target; y++) {
            p.set(x, y, z);
            BlockState st = level.getBlockState(p);
            if (!buryable(st)) continue;
            level.setBlock(p, material(x, y, z, target, finalTop, colDist[i]), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            placed++;
        }
        built[i] = target;
        return true;
    }

    private int topAtFullSize(int i) {
        float s = scale;
        scale = 1f;
        int t = targetTop(i);
        scale = s;
        return t;
    }

    /** Air, plants, water, lava, snow, leaves and trees get buried; buildings and other solid blocks stay embedded. */
    private static boolean buryable(BlockState st) {
        if (st.isAir() || st.canBeReplaced()) return !st.hasBlockEntity();
        if (st.getBlock() instanceof LiquidBlock) return true;
        return st.is(BlockTags.LEAVES) || st.is(BlockTags.LOGS) || st.is(BlockTags.SNOW) || st.is(Blocks.ICE)
                || st.is(Blocks.MAGMA_BLOCK) || st.is(Blocks.FIRE);
    }

    /**
     * Rock for a block of the edifice: the top two blocks are the current surface (scoria and ash near the summit,
     * weathered tephra on the lower flanks), below that alternating strata of lava flows and tephra, like the
     * cross-section of a real stratovolcano.
     */
    private BlockState material(int x, int y, int z, int target, int finalTop, double d) {
        long h = Mth.getSeed(x, y, z);
        float r = ((h >>> 16) & 0xFFFF) / 65535f;
        if (d < craterRadius() + 1.5 && y >= target - 1) {
            return r < 0.35f ? Blocks.MAGMA_BLOCK.defaultBlockState() : r < 0.8f ? Blocks.BLACKSTONE.defaultBlockState()
                    : Blocks.BASALT.defaultBlockState();
        }
        if (y >= target - 1) {
            // Coherent, downslope-elongated streaks (sampled in polar coordinates) instead of per-block noise:
            // flow channels and gullies run down the mountain, like on real stratovolcanoes.
            double angle = Math.atan2(z - cz, x - cx);
            double streak = surfaceNoise.noise(angle * radius * 0.06, d * 0.018, 0.5);
            double mottle = surfaceNoise.noise(x * 0.23, 7.3, z * 0.23);
            double hf = (y - baseY) / (double) Math.max(1, height) + streak * 0.08 + mottle * 0.03;
            if (hf > 0.6) {
                // fresh, dark scoria and spatter on the upper cone
                if (streak > 0.3) return Blocks.BLACKSTONE.defaultBlockState();
                return mottle > 0.15 ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.SMOOTH_BASALT.defaultBlockState();
            }
            if (hf > 0.28) {
                if (streak < -0.35) return Blocks.SMOOTH_BASALT.defaultBlockState();          // old lava channels
                if (streak > 0.4) return Blocks.GRAVEL.defaultBlockState();                  // scree
                return mottle > 0.3 ? Blocks.ANDESITE.defaultBlockState() : Blocks.TUFF.defaultBlockState();
            }
            if (streak < -0.4) return Blocks.SMOOTH_BASALT.defaultBlockState();               // flow lobes
            if (streak > 0.5 && mottle > 0) return Blocks.COARSE_DIRT.defaultBlockState();   // weathered ash soil
            return mottle > 0.25 ? Blocks.ANDESITE.defaultBlockState() : Blocks.TUFF.defaultBlockState();
        }
        // Strata: undulating layers of lava rock and tephra.
        int band = Math.floorMod((int) Math.floor((y + 2.5 * Math.sin(x * 0.07) + 2.5 * Math.cos(z * 0.06)) / 3.0), 6);
        return switch (band) {
            case 0 -> Blocks.BASALT.defaultBlockState();
            case 1, 4 -> Blocks.STONE.defaultBlockState();
            case 2 -> Blocks.TUFF.defaultBlockState();
            case 3 -> Blocks.ANDESITE.defaultBlockState();
            default -> r < 0.5f ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.BLACKSTONE.defaultBlockState();
        };
    }

    // ------------------------------------------------------------------ entities on the rising ground

    /** The ground rises under whatever stands on it: lift entities that ended up inside the new rock. */
    void liftEntities() {
        double sR = radius * scale + 1;
        AABB box = new AABB(cx - sR, baseY - 2, cz - sR, cx + sR, baseY + height + 4, cz + sR);
        for (Entity e : level.getEntities((Entity) null, box, e -> !e.isSpectator() && !e.isPassenger())) {
            BlockPos feet = e.blockPosition();
            BlockState st = level.getBlockState(feet);
            if (st.isAir() || !st.isSuffocating(level, feet) && !st.isCollisionShapeFullBlock(level, feet)) continue;
            int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, feet.getX(), feet.getZ());
            if (top <= feet.getY()) continue;
            e.teleportTo(e.getX(), top + 0.05, e.getZ());
            e.setDeltaMovement(e.getDeltaMovement().multiply(1, 0, 1).add(0, 0.2, 0));
            e.hurtMarked = true;
        }
    }
}
