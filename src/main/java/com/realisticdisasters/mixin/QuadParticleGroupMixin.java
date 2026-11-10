package com.realisticdisasters.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.realisticdisasters.client.particle.VortexParticle;
import com.realisticdisasters.client.particle.WildfireParticle;
import net.minecraft.client.particle.QuadParticleGroup;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Vanilla culls particles by their centre point. Storm cloud sheets are up to ~40 blocks wide, so they would pop out
 * while still covering part of the screen. Cull our large particles by their real extent instead.
 */
@Mixin(QuadParticleGroup.class)
public abstract class QuadParticleGroupMixin {
    @WrapOperation(method = "extractRenderState",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/culling/Frustum;pointInFrustum(DDD)Z"))
    private boolean realisticdisasters$cullLargeParticles(Frustum frustum, double x, double y, double z,
                                                          Operation<Boolean> original, @Local SingleQuadParticle particle) {
        if (particle instanceof VortexParticle vortex) {
            float r = vortex.cullRadius();
            if (r > 0f) return frustum.isVisible(new AABB(x - r, y - r, z - r, x + r, y + r, z + r));
        }
        if (particle instanceof WildfireParticle fire) {
            float r = fire.cullRadius();
            if (r > 0f) return frustum.isVisible(new AABB(x - r, y - r, z - r, x + r, y + r, z + r));
        }
        if (particle instanceof com.realisticdisasters.client.volcano.AshParticle ash) {
            float r = ash.cullRadius();
            if (r > 0f) return frustum.isVisible(new AABB(x - r, y - r, z - r, x + r, y + r, z + r));
        }
        return original.call(frustum, x, y, z);
    }
}
