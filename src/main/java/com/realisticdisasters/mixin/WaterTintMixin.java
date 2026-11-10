package com.realisticdisasters.mixin;

import com.realisticdisasters.client.tsunami.FloodTint;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tints water inside an active tsunami flood zone muddy (see {@link FloodTint}). NeoForge doesn't allow replacing
 * the vanilla water model's tint source, and both the vanilla and NeoForge water tints go through this method.
 */
@Mixin(BiomeColors.class)
public abstract class WaterTintMixin {
    @Inject(method = "getAverageWaterColor", at = @At("RETURN"), cancellable = true)
    private static void realisticdisasters$floodTint(BlockAndTintGetter level, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        if (FloodTint.active()) cir.setReturnValue(FloodTint.apply(pos, cir.getReturnValueI()));
    }
}
