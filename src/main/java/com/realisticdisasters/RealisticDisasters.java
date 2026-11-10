package com.realisticdisasters;

import com.mojang.logging.LogUtils;
import com.realisticdisasters.config.ClientConfig;
import com.realisticdisasters.config.DisasterConfig;
import com.realisticdisasters.network.ModNetwork;
import com.realisticdisasters.registry.ModRegistries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

/**
 * Common entry point. Everything reachable from here is safe on a dedicated server; client code lives in
 * {@code com.realisticdisasters.client} and is loaded only through {@link com.realisticdisasters.client.RealisticDisastersClient}.
 */
@Mod(RealisticDisasters.MODID)
public class RealisticDisasters {
    public static final String MODID = "realisticdisasters";
    public static final Logger LOGGER = LogUtils.getLogger();

    public RealisticDisasters(IEventBus modBus, ModContainer container) {
        ModRegistries.register(modBus);
        modBus.addListener(ModNetwork::register);
        container.registerConfig(ModConfig.Type.COMMON, DisasterConfig.SPEC);
        container.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
        if (!FMLEnvironment.isProduction()) {
            com.realisticdisasters.gametest.DisasterGameTests.register(modBus);
        }
    }
}
