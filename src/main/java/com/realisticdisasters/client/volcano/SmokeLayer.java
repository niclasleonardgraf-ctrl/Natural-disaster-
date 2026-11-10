package com.realisticdisasters.client.volcano;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.realisticdisasters.registry.ModRegistries;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.TextureAtlas;

/**
 * Render layer for soft smoke and ash. Vanilla's translucent particle pipeline writes depth and doesn't sort, so a
 * faint puff drawn first punches a sky-coloured hole through the dense cloud behind it. This pipeline is identical
 * (same particle shader, translucent blending, depth-tested against the world) except that it doesn't write depth,
 * so overlapping puffs always blend into one continuous cloud.
 */
public final class SmokeLayer {
    public static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.PARTICLE_SNIPPET)
            .withLocation(ModRegistries.id("pipeline/smoke_particle"))
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
            .build();

    public static final SingleQuadParticle.Layer LAYER =
            new SingleQuadParticle.Layer(true, TextureAtlas.LOCATION_PARTICLES, PIPELINE);

    private SmokeLayer() {}
}
