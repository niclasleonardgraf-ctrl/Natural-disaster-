package com.realisticdisasters.client;

import com.realisticdisasters.RealisticDisasters;
import com.realisticdisasters.client.gui.DisasterControllerScreen;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Development only: {@code -Drealisticdisasters.openController=<ticks>} opens the controller menu after that many ticks in a world. */
@EventBusSubscriber(modid = RealisticDisasters.MODID, value = Dist.CLIENT)
public final class DevHooks {
    private static final String OPEN = System.getProperty("realisticdisasters.openController");
    private static int ticksInWorld;
    private static boolean opened;

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        if (OPEN == null || OPEN.isBlank() || opened || FMLEnvironment.isProduction()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        if (++ticksInWorld >= Integer.parseInt(OPEN.trim())) {
            opened = true;
            if (Boolean.getBoolean("realisticdisasters.openActiveTab")) DisasterControllerScreen.showActiveTab();
            mc.setScreen(new DisasterControllerScreen());
        }
    }

    private DevHooks() {}
}
