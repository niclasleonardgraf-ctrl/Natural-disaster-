package com.realisticdisasters.mixin;

import com.realisticdisasters.client.earthquake.EarthquakeCamera;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets the earthquake move the camera itself (a small positional jolt / bob), not only rotate it. Real quake footage
 * is dominated by the camera being translated back and forth, which rotation alone can't reproduce. The offset is
 * applied after vanilla has placed the camera and before the culling frustum is built.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow
    public abstract Vec3 position();

    @Shadow
    protected abstract void setPosition(Vec3 position);

    @Inject(method = "alignWithEntity", at = @At("TAIL"))
    private void realisticdisasters$quakeOffset(float partialTicks, CallbackInfo ci) {
        Vec3 offset = EarthquakeCamera.positionOffset(partialTicks);
        if (offset != Vec3.ZERO) setPosition(position().add(offset));
    }
}
