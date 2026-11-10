package com.realisticdisasters.client.tsunami;

import com.realisticdisasters.RealisticDisasters;
import com.realisticdisasters.config.ClientConfig;
import net.minecraft.client.Camera;
import net.minecraft.util.Mth;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * Under flood water you can barely see: tsunami water is opaque with sediment. When the camera is submerged inside an
 * active flood zone the underwater fog turns the flood's muddy colour and closes in to a few blocks (darker and
 * closer further inland, where the water carries more silt). Clear sea water elsewhere is untouched.
 */
@EventBusSubscriber(modid = RealisticDisasters.MODID, value = Dist.CLIENT)
public final class FloodMurk {
    private static float murk; // smoothed 0..1 so diving in / surfacing blends

    private static float target(Camera camera) {
        if (camera.getFluidInCamera() != FogType.WATER || !FloodTint.active()) return -1f;
        Vec3 p = camera.position();
        return FloodTint.inland(p.x, p.y, p.z);
    }

    @SubscribeEvent
    public static void onFogColor(ViewportEvent.ComputeFogColor event) {
        if (!ClientConfig.FOG.getAsBoolean()) return;
        float inland = target(event.getCamera());
        murk += ((inland < 0 ? 0f : 1f) - murk) * 0.08f;
        if (murk < 0.01f) return;
        // Silt-laden water scatters daylight into a dull brown-green glow (much lighter than clear deep water).
        int mud = FloodTint.mudColor(Math.max(0f, inland));
        float r = (((mud >> 16) & 255) / 255f) * 0.6f + 0.42f * 0.4f;
        float g = (((mud >> 8) & 255) / 255f) * 0.6f + 0.36f * 0.4f;
        float b = ((mud & 255) / 255f) * 0.6f + 0.24f * 0.4f;
        float k = murk * 0.95f;
        event.setRed(Mth.lerp(k, event.getRed(), r));
        event.setGreen(Mth.lerp(k, event.getGreen(), g));
        event.setBlue(Mth.lerp(k, event.getBlue(), b));
    }

    @SubscribeEvent
    public static void onFog(ViewportEvent.RenderFog event) {
        if (!ClientConfig.FOG.getAsBoolean() || event.getType() != FogType.WATER || murk < 0.01f) return;
        float inland = Math.max(0f, target(event.getCamera()));
        float far = Mth.lerp(murk, event.getFarPlaneDistance(), 7f - inland * 3f);
        event.setFarPlaneDistance(far);
        event.setNearPlaneDistance(Math.min(event.getNearPlaneDistance(), far * 0.1f));
    }

    private FloodMurk() {}
}
