package com.realisticdisasters.client.tsunami;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;

/**
 * Muddy flood water. Tsunami water on land is not clear sea water: near the shore it is a turbid grey-green, and
 * further inland it turns dark brown with sediment and debris. Water inside an active flood zone (above sea level,
 * on the land side) gets this colour instead of the biome's water colour.
 *
 * Read from chunk-meshing worker threads, written on the client thread, hence the concurrent map of immutable zones.
 */
public final class FloodTint {
    private static final int SHORE = 0x5C6A58; // turbid grey-green
    private static final int INLAND = 0x4B3D2C; // dark, sediment-laden brown

    record Zone(double px, double pz, double dx, double dz, float halfWidth, float reach, int waterY) {}

    private static final Map<Integer, Zone> ZONES = new ConcurrentHashMap<>();

    static void update(int id, Zone zone) {
        ZONES.put(id, zone);
    }

    static void remove(int id) {
        ZONES.remove(id);
    }

    public static boolean active() {
        return !ZONES.isEmpty();
    }

    /** @return the water colour at {@code pos}: the biome colour, or flood mud inside a flood zone */
    public static int apply(BlockPos pos, int biomeColor) {
        for (Zone z : ZONES.values()) {
            if (pos.getY() <= z.waterY()) continue;
            double x = pos.getX() + 0.5 - z.px(), zz = pos.getZ() + 0.5 - z.pz();
            double axial = -x * z.dx() - zz * z.dz();
            double lateral = -x * z.dz() + zz * z.dx();
            if (axial < -3 || axial > z.reach() + 6 || Math.abs(lateral) > z.halfWidth() + 3) continue;
            float inland = Mth.clamp((float) axial / z.reach(), 0f, 1f);
            int mud = lerp(SHORE, INLAND, inland);
            // fade in over the last couple of blocks of the zone so edges don't show a seam
            float edge = Mth.clamp((float) Math.min(axial + 3, z.halfWidth() + 3 - Math.abs(lateral)) / 3f, 0f, 1f);
            return lerp(biomeColor, mud, 0.9f * edge) | 0xFF000000;
        }
        return biomeColor;
    }

    private static int lerp(int a, int b, float t) {
        int r = (int) Mth.lerp(t, (a >> 16) & 255, (b >> 16) & 255);
        int g = (int) Mth.lerp(t, (a >> 8) & 255, (b >> 8) & 255);
        int bl = (int) Mth.lerp(t, a & 255, b & 255);
        return (a & 0xFF000000) | r << 16 | g << 8 | bl;
    }

    private FloodTint() {}
}
