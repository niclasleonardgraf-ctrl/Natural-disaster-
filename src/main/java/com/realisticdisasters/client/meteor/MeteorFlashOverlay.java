package com.realisticdisasters.client.meteor;

import com.realisticdisasters.RealisticDisasters;
import com.realisticdisasters.registry.ModRegistries;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;

/**
 * Brief white-hot screen flash when a meteor lands nearby. Honours the vanilla "Hide Lightning Flashes"
 * accessibility option. Drawn as a GUI layer below the HUD.
 */
@EventBusSubscriber(modid = RealisticDisasters.MODID, value = Dist.CLIENT)
public final class MeteorFlashOverlay {
    private static float flash, prevFlash;

    /** Adds a flash of the given strength (0..1). */
    public static void flash(float amount) {
        if (Minecraft.getInstance().options.hideLightningFlash().get()) return;
        flash = Math.min(0.85f, Math.max(flash, amount));
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        prevFlash = flash;
        if (flash > 0) flash = flash < 0.01f ? 0f : flash * 0.72f;
    }

    @SubscribeEvent
    public static void registerLayer(RegisterGuiLayersEvent event) {
        event.registerBelowAll(ModRegistries.id("meteor_flash"), (graphics, delta) -> {
            float a = Mth.lerp(delta.getGameTimeDeltaPartialTick(false), prevFlash, flash);
            if (a < 0.004f) return;
            graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), ARGB.colorFromFloat(a, 1f, 0.95f, 0.82f));
        });
    }

    private MeteorFlashOverlay() {}
}
