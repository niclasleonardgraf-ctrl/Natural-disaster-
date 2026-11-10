package com.realisticdisasters.disaster.windstorm;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The wind field of a wind storm, shared by the server (pushing entities) and the client (particles, fog, sound),
 * so what you see and what you feel come from the same numbers. Pure functions of synced values and game time.
 * <ul>
 * <li><b>Gusts</b> are fronts that travel downwind across the land: a slow global surge plus a train of gust
 * fronts. You can watch a gust sweep toward you in the snow/dust before it hits.</li>
 * <li><b>Height profile</b>: wind is slowed by the ground (logarithmic profile), so it's calmer at your feet than
 * on a rooftop or a ridge.</li>
 * <li><b>Shelter</b>: no wind under a roof; in the lee of a wall or a building the wind is weak right behind it and
 * recovers over a few blocks.</li>
 * </ul>
 */
public final class StormWind {
    /** Gust fronts: wavelength (blocks) and travel speed (blocks/tick). */
    private static final double GUST_WAVELENGTH = 56, GUST_SPEED = 0.55;
    private static final double GUST_K = Math.PI * 2 / GUST_WAVELENGTH;

    private StormWind() {}

    /**
     * Local gust factor (about 0.5 .. 1.6) at a point.
     *
     * @param surge    the storm's slow global surge (synced as {@code D_GUST})
     * @param wx       unit wind direction x
     * @param wz       unit wind direction z
     * @param gameTime level game time (same on both sides)
     */
    public static float gust(float surge, double x, double z, double wx, double wz, long gameTime, int stormId) {
        double along = x * wx + z * wz;
        double across = -x * wz + z * wx;
        // Fronts are not straight lines forever: their phase wanders slowly across the wind.
        double phase = GUST_K * (along - GUST_SPEED * gameTime) + Math.sin(across * 0.03 + stormId) * 1.4 + stormId * 0.7;
        double front = Math.max(0, Math.sin(phase));
        front = front * front * front;                 // short, sharp gusts with lulls between them
        double lull = 0.85 + 0.15 * Math.sin(phase * 0.5 + 1.3);
        return (float) Mth.clamp(surge * lull + 0.45 * front - 0.1, 0.45, 1.65);
    }

    /** Logarithmic wind profile: 0.55 at the surface, 1.0 at ~6 blocks up, a bit more on high ground. */
    public static float heightFactor(double heightAboveGround) {
        double h = Math.max(0.3, heightAboveGround + 0.8);
        return (float) Mth.clamp(Math.log(h / 0.1) / Math.log(68), 0.55, 1.25);
    }

    /**
     * 0..1 exposure at a point for wind blowing along (wx, wz): 0 under a roof, low right behind a wall (lee),
     * recovering with distance from the obstacle (about 5 blocks per block of wall height in reality; compressed
     * to a few blocks here). Samples a handful of blocks, cheap enough for every entity every few ticks.
     */
    public static float exposure(Level level, double x, double y, double z, double wx, double wz) {
        int bx = Mth.floor(x), by = Mth.floor(y), bz = Mth.floor(z);
        if (level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz) > by + 1) return 0f;   // roofed
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int d = 1; d <= 6; d++) {
            int ux = Mth.floor(x - wx * d), uz = Mth.floor(z - wz * d);
            p.set(ux, by, uz);
            boolean low = !level.getBlockState(p).getCollisionShape(level, p).isEmpty();
            p.set(ux, by + 1, uz);
            boolean high = !level.getBlockState(p).getCollisionShape(level, p).isEmpty();
            if (low || high) {
                // A wall d blocks upwind: wind shadow, strongest close behind it.
                float shadow = (low && high) ? 0.12f : 0.4f;
                return shadow + (1f - shadow) * (d - 1) / 6f;
            }
        }
        return 1f;
    }

    /** Height of a point above the local surface (motion-blocking heightmap). */
    public static double heightAboveGround(Level level, double x, double y, double z) {
        return y - level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(x), Mth.floor(z));
    }
}
