package com.realisticdisasters.mixin;

import com.realisticdisasters.client.EffectsManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.attribute.EnvironmentAttributes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Adds disaster tint layers on top of the vanilla environment attributes (sky, fog, cloud colour and sky light).
 * Going through the attribute system keeps vanilla, Iris and other renderers reading the same values.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
    @Inject(method = "addEnvironmentAttributeLayers", at = @At("RETURN"))
    private void realisticdisasters$addLayers(EnvironmentAttributeSystem.Builder builder,
                                              CallbackInfoReturnable<EnvironmentAttributeSystem.Builder> cir) {
        builder.addTimeBasedLayer(EnvironmentAttributes.SKY_COLOR, (value, tick) -> EffectsManager.skyColor(value));
        builder.addTimeBasedLayer(EnvironmentAttributes.FOG_COLOR, (value, tick) -> EffectsManager.fogColor(value));
        builder.addTimeBasedLayer(EnvironmentAttributes.CLOUD_COLOR, (value, tick) -> EffectsManager.cloudColor(value));
        builder.addTimeBasedLayer(EnvironmentAttributes.SKY_LIGHT_FACTOR, (value, tick) -> EffectsManager.skyLightFactor(value));
    }
}
