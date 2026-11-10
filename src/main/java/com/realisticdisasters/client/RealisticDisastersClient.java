package com.realisticdisasters.client;

import com.realisticdisasters.RealisticDisasters;
import com.realisticdisasters.client.particle.VortexParticle;
import com.realisticdisasters.network.DisasterRemovePayload;
import com.realisticdisasters.network.DisasterSyncPayload;
import com.realisticdisasters.registry.ModRegistries;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;

/** Client entry point; never loaded on a dedicated server. */
@Mod(value = RealisticDisasters.MODID, dist = Dist.CLIENT)
public class RealisticDisastersClient {
    public RealisticDisastersClient(IEventBus modBus, ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        modBus.addListener(RealisticDisastersClient::registerPayloadHandlers);
        modBus.addListener(RealisticDisastersClient::registerParticles);
    }

    private static void registerPayloadHandlers(RegisterClientPayloadHandlersEvent event) {
        event.register(DisasterSyncPayload.TYPE, ClientDisasters::handleSync);
        event.register(DisasterRemovePayload.TYPE, ClientDisasters::handleRemove);
    }

    private static void registerParticles(RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(ModRegistries.FUNNEL_CLOUD.get(), s -> VortexParticle.provider(VortexParticle.Kind.CLOUD, s));
        event.registerSpriteSet(ModRegistries.STORM_DEBRIS.get(), s -> VortexParticle.provider(VortexParticle.Kind.DEBRIS, s));
        event.registerSpriteSet(ModRegistries.WALL_CLOUD.get(), s -> VortexParticle.provider(VortexParticle.Kind.WALL, s));
        event.registerSpriteSet(ModRegistries.DUST_INFLOW.get(), s -> VortexParticle.provider(VortexParticle.Kind.INFLOW, s));
        event.registerSpriteSet(ModRegistries.DUST_CLOUD.get(), s -> VortexParticle.provider(VortexParticle.Kind.DUST, s));
    }
}
