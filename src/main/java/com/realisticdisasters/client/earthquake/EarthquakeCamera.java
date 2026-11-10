package com.realisticdisasters.client.earthquake;

import com.realisticdisasters.RealisticDisasters;
import com.realisticdisasters.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * Earthquake camera motion, modelled on how quakes look in real footage:
 * <ul>
 *   <li><b>Strong motion</b> (body waves, near the epicentre): irregular, jerky back-and-forth with no fixed rhythm,
 *       mostly as a translation of the camera plus small angle kicks. Built from per-tick random noise (~10 Hz)
 *       layered over a slower random wander (~2-3 Hz), so it never repeats like a sine wave.</li>
 *   <li><b>Rolling</b> (surface waves, felt far away and late in the shock): a slow, sea-sick heave with a period
 *       of a few seconds. Rayleigh-wave style: the camera moves along the direction to the epicentre and up/down,
 *       a quarter period apart (an elliptical roll), with a gentle tilt.</li>
 * </ul>
 * Uses the same camera-shake switches as the shared disaster shake.
 */
@EventBusSubscriber(modid = RealisticDisasters.MODID, value = Dist.CLIENT)
public final class EarthquakeCamera {
    private static final RandomSource RANDOM = RandomSource.create();
    private static final int YAW = 0, PITCH = 1, ROLL = 2, DX = 3, DY = 4, DZ = 5, CH = 6;

    // per-tick inputs (summed over nearby quakes)
    private static float joltIn, rollIn, dirXIn, dirZIn;
    // smoothed amplitudes
    private static float jolt, roll;
    private static float dirX = 1, dirZ;
    // noise state
    private static final float[] fast = new float[CH], slowFrom = new float[CH], slowTo = new float[CH];
    private static int slowTimer, slowLen = 1;
    private static double rollPhase;
    // resulting motion this tick and last tick (render interpolates)
    private static final float[] cur = new float[CH], prev = new float[CH];

    /** Called by {@link EarthquakeVisual#contribute} every client tick. dir = horizontal unit vector away from the epicentre. */
    static void add(float joltAmount, float rollAmount, double dx, double dz) {
        joltIn += joltAmount;
        rollIn += rollAmount;
        dirXIn += (float) dx * rollAmount;
        dirZIn += (float) dz * rollAmount;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.isPaused()) return;
        System.arraycopy(cur, 0, prev, 0, CH);

        // Fast attack (jolts hit at once), slower release (motion rings out).
        jolt += (Math.min(joltIn, 1.3f) - jolt) * (joltIn > jolt ? 0.6f : 0.1f);
        roll += (Math.min(rollIn, 1.2f) - roll) * 0.04f;
        float dl = (float) Math.sqrt(dirXIn * dirXIn + dirZIn * dirZIn);
        if (dl > 1.0e-3f) {
            dirX += (dirXIn / dl - dirX) * 0.05f;
            dirZ += (dirZIn / dl - dirZ) * 0.05f;
        }
        joltIn = rollIn = dirXIn = dirZIn = 0;

        if (jolt < 0.002f && roll < 0.002f) {
            java.util.Arrays.fill(cur, 0f);
            return;
        }

        // Noise: a new random sample every tick (fast) and a smoothly interpolated slower wander.
        for (int c = 0; c < CH; c++) fast[c] = RANDOM.nextFloat() * 2f - 1f;
        if (++slowTimer >= slowLen) {
            slowTimer = 0;
            slowLen = 2 + RANDOM.nextInt(5);
            for (int c = 0; c < CH; c++) {
                slowFrom[c] = slowTo[c];
                slowTo[c] = RANDOM.nextFloat() * 2f - 1f;
            }
        }
        float u = (float) slowTimer / slowLen;
        u = u * u * (3 - 2 * u);
        // Surface waves: period of ~2.5-3.5 s with a little drift so it doesn't feel mechanical.
        rollPhase += Math.PI * 2 / (50 + RANDOM.nextInt(20));

        float[] slow = new float[CH];
        for (int c = 0; c < CH; c++) slow[c] = Mth.lerp(u, slowFrom[c], slowTo[c]);

        float j = jolt, r = roll;
        float rs = (float) Math.sin(rollPhase), rc = (float) Math.cos(rollPhase);
        // Angles (degrees): small kicks; the heave tilts the view slowly.
        cur[YAW] = j * (fast[YAW] * 0.35f + slow[YAW] * 0.55f) + r * rs * 0.5f;
        cur[PITCH] = j * (fast[PITCH] * 0.45f + slow[PITCH] * 0.6f) + r * rc * 0.6f;
        cur[ROLL] = j * (fast[ROLL] * 0.3f + slow[ROLL] * 0.5f) + r * (float) Math.sin(rollPhase * 0.5 + 1.1) * 1.4f;
        // Translation (blocks): the dominant part of real footage.
        float along = r * rc * 0.07f;
        cur[DX] = j * (fast[DX] * 0.04f + slow[DX] * 0.09f) + dirX * along;
        cur[DZ] = j * (fast[DZ] * 0.04f + slow[DZ] * 0.09f) + dirZ * along;
        cur[DY] = j * (fast[DY] * 0.035f + slow[DY] * 0.05f) + r * rs * 0.07f;

        if (DEBUG) {
            float off = (float) Math.sqrt(cur[DX] * cur[DX] + cur[DY] * cur[DY] + cur[DZ] * cur[DZ]);
            float ang = Math.abs(cur[YAW]) + Math.abs(cur[PITCH]) + Math.abs(cur[ROLL]);
            debugMaxOffset = Math.max(debugMaxOffset, off);
            debugMaxAngle = Math.max(debugMaxAngle, ang);
            if (++debugTicks % 40 == 0) {
                RealisticDisasters.LOGGER.info("[debug] quake camera: jolt={} roll={} maxOffset={} blocks maxAngle={} deg",
                        String.format("%.2f", jolt), String.format("%.2f", roll), String.format("%.3f", debugMaxOffset),
                        String.format("%.2f", debugMaxAngle));
                debugMaxOffset = debugMaxAngle = 0;
            }
        }
    }

    private static final boolean DEBUG = Boolean.getBoolean("realisticdisasters.debug");
    private static float debugMaxOffset, debugMaxAngle;
    private static int debugTicks;

    private static float strength() {
        if (!ClientConfig.CAMERA_SHAKE.getAsBoolean()) return 0f;
        return ClientConfig.CAMERA_SHAKE_STRENGTH.get().floatValue();
    }

    private static float value(int channel, float partialTick) {
        return Mth.lerp(partialTick, prev[channel], cur[channel]);
    }

    /** Camera position offset for this frame (used by CameraMixin). */
    public static Vec3 positionOffset(float partialTick) {
        float s = strength();
        if (s <= 0f || (jolt < 0.002f && roll < 0.002f)) return Vec3.ZERO;
        return new Vec3(value(DX, partialTick) * s, value(DY, partialTick) * s, value(DZ, partialTick) * s);
    }

    @SubscribeEvent
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        float s = strength();
        if (s <= 0f || (jolt < 0.002f && roll < 0.002f)) return;
        float pt = (float) event.getPartialTick();
        event.setYaw(event.getYaw() + value(YAW, pt) * s);
        event.setPitch(event.getPitch() + value(PITCH, pt) * s);
        event.setRoll(event.getRoll() + value(ROLL, pt) * s);
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        joltIn = rollIn = dirXIn = dirZIn = jolt = roll = 0;
        java.util.Arrays.fill(cur, 0f);
        java.util.Arrays.fill(prev, 0f);
    }

    private EarthquakeCamera() {}
}
