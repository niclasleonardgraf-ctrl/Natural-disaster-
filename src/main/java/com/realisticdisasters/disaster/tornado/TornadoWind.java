package com.realisticdisasters.disaster.tornado;

import net.minecraft.world.phys.Vec3;

/**
 * Wind field of a tornado, shared by server physics and client effects (sound, wind at the camera).
 *
 * <p>Model (units: blocks per tick):
 * <ul>
 *   <li><b>Rotation</b>: Rankine vortex: tangential speed grows linearly inside the core radius {@code Rc} and
 *       decays as {@code Rc / r} outside it. Counter-clockwise seen from above.</li>
 *   <li><b>Inflow</b>: near the ground (surface boundary layer) air spirals inward toward the core.</li>
 *   <li><b>Updraft</b>: strong rising motion in and around the core, weakening with height.</li>
 *   <li><b>Translation</b>: the storm's forward motion adds to the rotation, so the right flank (relative to the
 *       direction of travel) has the strongest winds, as in real tornadoes.</li>
 * </ul>
 * Pure math, no world access: safe on both sides.
 */
public final class TornadoWind {
    private final double cx, cy, cz;
    private final double coreRadius;
    private final double height;
    private final double vmax;
    private final double tx, tz;
    private final double strength;

    public TornadoWind(Vec3 center, double coreRadius, double height, int intensity, double headingX, double headingZ,
                       double translationSpeed, double strength) {
        this.cx = center.x;
        this.cy = center.y;
        this.cz = center.z;
        this.coreRadius = Math.max(1, coreRadius);
        this.height = Math.max(10, height);
        this.vmax = maxWind(intensity);
        this.tx = headingX * translationSpeed;
        this.tz = headingZ * translationSpeed;
        this.strength = strength;
    }

    /** Peak rotational wind speed (blocks/tick) for an intensity 1..5, roughly mirroring the EF scale's spread. */
    public static double maxWind(int intensity) {
        return 0.45 + 0.22 * intensity;
    }

    /** Outer edge of the wind field. */
    public double outerRadius() {
        return coreRadius * 7;
    }

    public double coreRadius() {
        return coreRadius;
    }

    /** Wind vector at a point. */
    public Vec3 at(double x, double y, double z) {
        if (strength <= 0) return Vec3.ZERO;
        double dx = x - cx, dz = z - cz;
        double r = Math.sqrt(dx * dx + dz * dz);
        double outer = outerRadius();
        if (r > outer) return Vec3.ZERO;
        double above = y - cy;
        if (above < -8 || above > height * 1.1) return Vec3.ZERO;

        double nx, nz;
        if (r < 1.0e-3) {
            nx = 1;
            nz = 0;
        } else {
            nx = dx / r;
            nz = dz / r;
        }
        double rc = coreRadius;
        // Rankine profile, smoothly faded to zero at the outer edge.
        double vt = r < rc ? vmax * r / rc : vmax * rc / r;
        double edgeFade = 1 - smooth((r - outer * 0.6) / (outer * 0.4));
        vt *= edgeFade;
        // Boundary-layer inflow: strongest within ~10 % of the funnel height above the ground.
        double ground = Math.exp(-Math.max(0, above) / (height * 0.1));
        double inflow = r > rc * 0.5 ? 0.45 * vt * ground : 0;
        // Updraft in and around the core.
        double core = Math.exp(-(r / (rc * 1.3)) * (r / (rc * 1.3)));
        double updraft = vmax * 0.75 * core * (1 - smooth(above / height));
        // Winds weaken above the funnel's lower third (the funnel widens, rotation spreads out).
        double heightFade = 1 - 0.4 * smooth(above / height);

        double wx = (-nx * inflow - nz * vt) * heightFade + tx * edgeFade;
        double wz = (-nz * inflow + nx * vt) * heightFade + tz * edgeFade;
        return new Vec3(wx * strength, updraft * strength, wz * strength);
    }

    /** Horizontal wind speed at a point. */
    public double horizontalSpeed(double x, double y, double z) {
        Vec3 w = at(x, y, z);
        return Math.sqrt(w.x * w.x + w.z * w.z);
    }

    private static double smooth(double t) {
        t = Math.max(0, Math.min(1, t));
        return t * t * (3 - 2 * t);
    }
}
