package com.realisticdisasters.disaster.wildfire;

import com.realisticdisasters.RealisticDisasters;
import com.realisticdisasters.registry.ModRegistries;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

/** Adds the burn-scar blocks (charred log, scorched earth, scorched leaves) to the Natural Blocks creative tab. */
@EventBusSubscriber(modid = RealisticDisasters.MODID)
public final class WildfireSetup {
    @SubscribeEvent
    public static void onBuildTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.NATURAL_BLOCKS) {
            event.accept(ModRegistries.CHARRED_LOG_ITEM.get());
            event.accept(ModRegistries.SCORCHED_EARTH_ITEM.get());
            event.accept(ModRegistries.SCORCHED_LEAVES_ITEM.get());
        }
    }

    private WildfireSetup() {}
}
