package com.realisticdisasters.client;

import com.realisticdisasters.RealisticDisasters;
import com.realisticdisasters.client.particle.VortexParticle;
import com.realisticdisasters.client.particle.QuakeDustParticle;
import com.realisticdisasters.client.particle.WildfireParticle;
import com.realisticdisasters.client.volcano.AshParticle;
import com.realisticdisasters.client.volcano.SmokeLayer;
import com.realisticdisasters.client.volcano.SparkParticle;
import com.realisticdisasters.client.volcano.VoxelCloudRenderer;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import com.realisticdisasters.client.volcano.VolcanoVisual;
import com.realisticdisasters.disaster.volcano.LavaBombEntity;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
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
        modBus.addListener(RealisticDisastersClient::registerEntityRenderers);
        modBus.addListener((RegisterRenderPipelinesEvent e) -> e.registerPipeline(SmokeLayer.PIPELINE));
        modBus.addListener(VoxelCloudRenderer::registerPipelines);
        LavaBombEntity.CLIENT_TRAIL = VolcanoVisual::bombTrail;
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
        event.registerSpriteSet(ModRegistries.FUNNEL_SURFACE.get(), s -> VortexParticle.provider(VortexParticle.Kind.SURFACE, s));
        event.registerSpriteSet(ModRegistries.CLOUD_SHEET.get(), s -> VortexParticle.provider(VortexParticle.Kind.SHEET, s));
        event.registerSpriteSet(ModRegistries.CLOUD_BAND.get(), s -> VortexParticle.provider(VortexParticle.Kind.BAND, s));
        event.registerSpriteSet(ModRegistries.RAIN_SHAFT.get(), s -> VortexParticle.provider(VortexParticle.Kind.RAIN, s));
        event.registerSpriteSet(ModRegistries.DUST_CLOUD.get(), s -> VortexParticle.provider(VortexParticle.Kind.DUST, s));
        event.registerSpriteSet(ModRegistries.QUAKE_DUST.get(), QuakeDustParticle::provider);
        event.registerSpriteSet(ModRegistries.COLLAPSE_DUST.get(), QuakeDustParticle::collapseProvider);
        event.registerSpriteSet(ModRegistries.WILDFIRE_SMOKE.get(), s -> WildfireParticle.provider(WildfireParticle.Kind.SMOKE, s));
        event.registerSpriteSet(ModRegistries.ASH_FLAKE.get(), s -> WildfireParticle.provider(WildfireParticle.Kind.ASH, s));
        event.registerSpriteSet(ModRegistries.EMBER.get(), s -> WildfireParticle.provider(WildfireParticle.Kind.EMBER, s));
        event.registerSpriteSet(ModRegistries.SMOKE_BILLOW.get(), s -> WildfireParticle.provider(WildfireParticle.Kind.BILLOW, s));
        event.registerSpriteSet(ModRegistries.SMOKE_PALL.get(), s -> WildfireParticle.provider(WildfireParticle.Kind.PALL, s));
        event.registerSpriteSet(ModRegistries.SMOLDER_SMOKE.get(), s -> WildfireParticle.provider(WildfireParticle.Kind.SMOLDER, s));
        event.registerSpriteSet(ModRegistries.FIRE_TONGUE.get(), s -> WildfireParticle.provider(WildfireParticle.Kind.FLAME, s));
        event.registerSpriteSet(ModRegistries.FIRE_WHIRL.get(), s -> WildfireParticle.provider(WildfireParticle.Kind.WHIRL_FLAME, s));
        event.registerSpriteSet(ModRegistries.WHIRL_SMOKE.get(), s -> WildfireParticle.provider(WildfireParticle.Kind.WHIRL_SMOKE, s));
        event.registerSpriteSet(ModRegistries.ASH_CLOUD.get(), s -> AshParticle.provider(AshParticle.Kind.PLUME, s));
        event.registerSpriteSet(ModRegistries.ASH_TRAIL.get(), s -> AshParticle.provider(AshParticle.Kind.TRAIL, s));
        event.registerSpriteSet(ModRegistries.ASH_FALL.get(), s -> AshParticle.provider(AshParticle.Kind.FALL, s));
        event.registerSpriteSet(ModRegistries.ASH_CURTAIN.get(), s -> AshParticle.provider(AshParticle.Kind.CURTAIN, s));
        event.registerSpriteSet(ModRegistries.LAVA_SPARK.get(), SparkParticle::provider);
    }

    private static void registerEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        // Lava bombs render as a tumbling, full-bright magma block.
        event.registerEntityRenderer(ModRegistries.LAVA_BOMB.get(), ctx -> new ThrownItemRenderer<>(ctx, 2.6f, true));
    }
}
