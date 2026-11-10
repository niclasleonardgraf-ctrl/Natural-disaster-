package com.realisticdisasters.client.volcano;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.realisticdisasters.RealisticDisasters;
import com.realisticdisasters.client.ClientDisaster;
import com.realisticdisasters.client.ClientDisasters;
import com.realisticdisasters.disaster.DisasterType;
import com.realisticdisasters.registry.ModRegistries;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

/**
 * Draws the volcanoes' voxel clouds ({@link VoxelCloud}) into the world: solid shaded boxes like vanilla clouds, plus
 * a translucent, depth-sorted pass for the fall-out haze. Works through vanilla's own submission pipeline.
 */
@EventBusSubscriber(modid = RealisticDisasters.MODID, value = Dist.CLIENT)
public final class VoxelCloudRenderer {
    public static final RenderPipeline SOLID_PIPELINE = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
            .withLocation(ModRegistries.id("pipeline/voxel_cloud"))
            .withVertexShader(ModRegistries.id("core/voxel_cloud"))
            .withFragmentShader(ModRegistries.id("core/voxel_cloud"))
            .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS)
            .withDepthStencilState(DepthStencilState.DEFAULT)
            .withCull(false)
            .build();

    public static final RenderPipeline HAZE_PIPELINE = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
            .withLocation(ModRegistries.id("pipeline/voxel_haze"))
            .withVertexShader(ModRegistries.id("core/voxel_cloud"))
            .withFragmentShader(ModRegistries.id("core/voxel_cloud"))
            .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS)
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
            .withCull(false)
            .build();

    private static final RenderType SOLID = RenderType.create("realisticdisasters_voxel_cloud",
            RenderSetup.builder(SOLID_PIPELINE).bufferSize(1 << 20).createRenderSetup());
    private static final RenderType HAZE = RenderType.create("realisticdisasters_voxel_haze",
            RenderSetup.builder(HAZE_PIPELINE).bufferSize(1 << 18).sortOnUpload().createRenderSetup());

    public static void registerPipelines(RegisterRenderPipelinesEvent event) {
        event.registerPipeline(SOLID_PIPELINE);
        event.registerPipeline(HAZE_PIPELINE);
    }

    @SubscribeEvent
    public static void onSubmit(SubmitCustomGeometryEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 cam = camera.position();
        var frustum = camera.getCullFrustum();
        PoseStack pose = event.getPoseStack();
        for (ClientDisaster d : ClientDisasters.all()) {
            if (d.type != DisasterType.VOLCANO || !(d.visualState instanceof VolcanoVisual.State st) || st.cloud == null) continue;
            submit(event, pose, cam, frustum, st.cloud.dense, SOLID);
            submit(event, pose, cam, frustum, st.cloud.haze, HAZE);
        }
    }

    private static void submit(SubmitCustomGeometryEvent event, PoseStack pose, Vec3 cam,
                               net.minecraft.client.renderer.culling.Frustum frustum, VoxelCloud.Mesh mesh, RenderType type) {
        if (mesh == null || mesh.quads == 0) return;
        if (frustum != null && !frustum.isVisible(mesh.bounds)) return;
        float dx = (float) (mesh.ox - cam.x), dy = (float) (mesh.oy - cam.y), dz = (float) (mesh.oz - cam.z);
        event.getSubmitNodeCollector().submitCustomGeometry(pose, type, (p, buffer) -> emit(p, buffer, mesh, dx, dy, dz));
    }

    private static void emit(PoseStack.Pose p, VertexConsumer buffer, VoxelCloud.Mesh mesh, float dx, float dy, float dz) {
        float[] pos = mesh.pos;
        int[] col = mesh.color;
        for (int q = 0; q < mesh.quads; q++) {
            int o = q * 12, c = col[q];
            for (int v = 0; v < 4; v++) {
                int i = o + v * 3;
                buffer.addVertex(p, pos[i] + dx, pos[i + 1] + dy, pos[i + 2] + dz).setColor(c);
            }
        }
    }

    private VoxelCloudRenderer() {}
}
