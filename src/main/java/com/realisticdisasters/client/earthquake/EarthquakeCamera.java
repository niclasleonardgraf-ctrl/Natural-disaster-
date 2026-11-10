package com.realisticdisasters.client.earthquake;

import com.realisticdisasters.RealisticDisasters;
import com.realisticdisasters.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * Earthquake-only camera layer on top of the shared (smooth, swaying) disaster shake: a fast, jerky rattle that
 * reads as the ground vibrating rather than wind buffeting. Obeys the same camera-shake config switches.
 */
@EventBusSubscriber(modid = RealisticDisasters.MODID, value = Dist.CLIENT)
public final class EarthquakeCamera {
    private static float target, rattle, prevRattle;
    private static long ticks;

    /** Called by {@link EarthquakeVisual#contribute} every client tick (sums nearby quakes). */
    static void addRattle(float amount) {
        target += amount;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.isPaused()) return;
        ticks++;
        prevRattle = rattle;
        // Fast attack, slower release: jolts hit hard and ring out.
        float k = target > rattle ? 0.5f : 0.12f;
        rattle += (Math.min(target, 1.2f) - rattle) * k;
        target = 0;
    }

    @SubscribeEvent
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (!ClientConfig.CAMERA_SHAKE.getAsBoolean()) return;
        float pt = (float) event.getPartialTick();
        float amp = Mth.lerp(pt, prevRattle, rattle) * ClientConfig.CAMERA_SHAKE_STRENGTH.get().floatValue();
        if (amp < 0.002f) return;
        float t = ticks + pt;
        // High-frequency, slightly irregular jitter (several detuned sines).
        float yaw = (float) (Math.sin(t * 7.3) * 0.5 + Math.sin(t * 11.9 + 0.7) * 0.3 + Math.sin(t * 17.1 + 2.0) * 0.2);
        float pitch = (float) (Math.sin(t * 8.1 + 1.1) * 0.5 + Math.sin(t * 13.3 + 2.4) * 0.35 + Math.sin(t * 19.7) * 0.15);
        float deg = amp * 1.1f;
        event.setYaw(event.getYaw() + yaw * deg);
        event.setPitch(event.getPitch() + pitch * deg * 1.2f);
        event.setRoll(event.getRoll() + yaw * deg * 0.4f);
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        target = rattle = prevRattle = 0;
    }

    private EarthquakeCamera() {}
}
