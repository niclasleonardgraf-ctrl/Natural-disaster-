package com.realisticdisasters.client.tornado;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.realisticdisasters.RealisticDisasters;
import com.realisticdisasters.client.ClientDisaster;
import com.realisticdisasters.client.ClientDisasters;
import com.realisticdisasters.client.EffectsManager;
import com.realisticdisasters.config.ClientConfig;
import com.realisticdisasters.disaster.DisasterType;
import com.realisticdisasters.disaster.tornado.TornadoWind;
import com.realisticdisasters.registry.ModRegistries;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

/**
 * Renders the tornado funnel as real 3D geometry: nested, procedurally generated tube meshes that follow the
 * snaking, tilted funnel axis every frame, with scrolling textures (rotation + rising air), helical striations,
 * side lighting, soft silhouettes and lightning flashes.
 *
 * <p>Layers, drawn inside-out so translucency blends correctly:
 * <ol>
 *   <li>condensation core: dense, striated</li>
 *   <li>outer shell: torn streaks, scrolling faster</li>
 *   <li>outer veil: faint, slow, slightly wider</li>
 *   <li>debris sheath: brown, churning, flaring out at the ground</li>
 * </ol>
 * Submitted through NeoForge's {@link SubmitCustomGeometryEvent} into vanilla's translucent entity pipeline, so fog,
 * lightmap and shader packs treat it like other world geometry. Back faces are culled per quad (the pipeline itself
 * does not cull), which also keeps the inside-out drawing order valid.
 */
@EventBusSubscriber(modid = RealisticDisasters.MODID, value = Dist.CLIENT)
public final class FunnelRenderer {
    private static final Identifier CORE = ModRegistries.id("textures/entity/funnel_core.png");
    private static final Identifier VEIL = ModRegistries.id("textures/entity/funnel_veil.png");
    private static final Identifier DEBRIS = ModRegistries.id("textures/entity/debris_sheath.png");

    private static final int RINGS = 44;
    private static final int SEGMENTS = 64;
    /** Height fraction where the funnel hands over to the wall cloud. */
    private static final float TOP = 0.84f;
    private static final double LIGHT_ANGLE = Math.PI * 0.8;
    private static final float LX, LY, LZ;

    static {
        double lx = Math.cos(LIGHT_ANGLE), ly = 0.35, lz = Math.sin(LIGHT_ANGLE);
        double len = Math.sqrt(lx * lx + ly * ly + lz * lz);
        LX = (float) (lx / len);
        LY = (float) (ly / len);
        LZ = (float) (lz / len);
    }

    /** One tube layer. */
    private record Layer(Identifier texture, float radiusScale, float alpha, float uRepeat, float vBlocks,
                         float spin, float rise, float slant, float r, float g, float b, boolean debris,
                         float edgeSoftness) {}

    private static final Layer[] LAYERS = {
            new Layer(CORE, 1.25f, 1.35f, 5f, 28f, 1.0f, 0.010f, 0.06f, 0.46f, 0.47f, 0.50f, false, 0.3f),
            new Layer(VEIL, 1.38f, 0.7f, 6f, 22f, 1.25f, 0.018f, 0.09f, 0.66f, 0.67f, 0.69f, false, 0.45f),
            new Layer(VEIL, 1.55f, 0.38f, 4f, 34f, 0.8f, 0.008f, 0.05f, 0.70f, 0.71f, 0.72f, false, 0.55f),
            new Layer(DEBRIS, 1.0f, 0.4f, 4f, 9f, 1.4f, 0.03f, 0.3f, 0.34f, 0.30f, 0.26f, true, 0.85f),
    };

    @SubscribeEvent
    public static void onSubmitGeometry(SubmitCustomGeometryEvent event) {
        if (!ClientConfig.MESH_FUNNEL.getAsBoolean()) return;
        Minecraft mc = Minecraft.getInstance();
        float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Vec3 cam = event.getLevelRenderState().cameraRenderState.pos;
        for (ClientDisaster d : ClientDisasters.all()) {
            if (d.type == DisasterType.TORNADO) submit(event, d, cam, pt);
        }
    }

    private static void submit(SubmitCustomGeometryEvent event, ClientDisaster d, Vec3 cam, float pt) {
        float strength = d.strength(pt);
        float td = TornadoVisual.touchdown(d);
        float bottom = (1f - td) * 0.78f;
        if (strength < 0.02f || bottom >= TOP - 0.03f) return;
        Vec3 c = d.pos(pt);
        double dist = Math.sqrt((cam.x - c.x) * (cam.x - c.x) + (cam.z - c.z) * (cam.z - c.z));
        if (dist > Minecraft.getInstance().options.getEffectiveRenderDistance() * 16 + 64) return;

        double time = EffectsManager.ticks() + pt;
        float height = TornadoVisual.height(d);
        float core = TornadoVisual.coreRadius(d);
        double omega = TornadoWind.maxWind(d.intensity) * 0.8 * strength / Math.max(1.0, core);   // rad / tick
        float flash = EffectsManager.flash();

        // Ring geometry shared by all layers (axis centre per ring, relative to the tornado's ground point).
        float[] hs = new float[RINGS + 1];
        double[] ax = new double[RINGS + 1], az = new double[RINGS + 1];
        double[] baseR = new double[RINGS + 1];
        for (int i = 0; i <= RINGS; i++) {
            float h = bottom + (TOP - bottom) * i / RINGS;
            hs[i] = h;
            Vec3 off = TornadoVisual.axisOffset(d, h, time);
            ax[i] = off.x;
            az[i] = off.z;
            baseR[i] = TornadoVisual.funnelRadius(d, Math.min(h, 0.8f));
        }

        PoseStack ps = event.getPoseStack();
        ps.pushPose();
        ps.translate(c.x - cam.x, c.y - cam.y, c.z - cam.z);
        float camX = (float) (cam.x - c.x), camY = (float) (cam.y - c.y), camZ = (float) (cam.z - c.z);
        for (Layer layer : LAYERS) {
            if (layer.debris && td < 0.35f) continue;
            event.getSubmitNodeCollector().submitCustomGeometry(ps, RenderTypes.entityTranslucent(layer.texture),
                    (pose, buf) -> emitLayer(pose, buf, layer, d, hs, ax, az, baseR, height, core, strength, td, time,
                            omega, flash, camX, camY, camZ));
        }
        ps.popPose();
    }

    private static void emitLayer(PoseStack.Pose pose, VertexConsumer buf, Layer layer, ClientDisaster d, float[] hs,
                                  double[] ax, double[] az, double[] baseR, float height, float core, float strength,
                                  float td, double time, double omega, float flash, float camX, float camY, float camZ) {
        int rings = layer.debris ? RINGS / 3 : RINGS;
        float uScroll = (float) (time * omega * layer.spin / (Math.PI * 2) * layer.uRepeat);
        float vScroll = (float) (time * layer.rise);
        int light = LightCoordsUtil.pack(10, 15);

        // vertex grid for this layer
        float[][] px = new float[rings + 1][SEGMENTS + 1], py = new float[rings + 1][SEGMENTS + 1], pz = new float[rings + 1][SEGMENTS + 1];
        float[][] nx = new float[rings + 1][SEGMENTS + 1], ny = new float[rings + 1][SEGMENTS + 1], nz = new float[rings + 1][SEGMENTS + 1];
        float[][] alpha = new float[rings + 1][SEGMENTS + 1], shade = new float[rings + 1][SEGMENTS + 1];
        float[] u = new float[SEGMENTS + 1];
        float[] v = new float[rings + 1];
        double[] radius = new double[rings + 1];
        double[] yy = new double[rings + 1];
        double[] cx = new double[rings + 1], cz = new double[rings + 1];

        for (int i = 0; i <= rings; i++) {
            float h;
            if (layer.debris) {
                // debris sheath: from the ground up to ~20 % of the funnel, flaring out toward the ground
                float f = (float) i / rings;
                h = f * 0.2f;
                yy[i] = h * height - 0.5;
                double flare = 1.0 - f;
                radius[i] = core * (1.1 + 0.75 * flare * flare) * (0.6 + 0.4 * td);
                Vec3 off = TornadoVisual.axisOffset(d, 0f, time);
                cx[i] = off.x;
                cz[i] = off.z;
            } else {
                h = hs[i];
                yy[i] = h * height;
                radius[i] = baseR[i] * layer.radiusScale;
                cx[i] = ax[i];
                cz[i] = az[i];
            }
            v[i] = (float) (yy[i] / layer.vBlocks) - vScroll;
        }
        for (int i = 0; i <= rings; i++) {
            float h = layer.debris ? (float) i / rings * 0.2f : hs[i];
            int iPrev = Math.max(0, i - 1), iNext = Math.min(rings, i + 1);
            double dr = (radius[iNext] - radius[iPrev]) / Math.max(1e-3, yy[iNext] - yy[iPrev]);
            // fade: top hands over to the wall cloud, a hanging (not touched down) funnel tapers off at its tip
            float a = layer.alpha * strength;
            if (!layer.debris) {
                a *= 1f - smooth((h - (TOP - 0.1f)) / 0.1f);
                if (i < 4 && td < 0.95f) a *= i / 4f;
            } else {
                a *= 1f - smooth((h - 0.06f) / 0.14f);   // thins out as it rises into the funnel
            }
            for (int j = 0; j <= SEGMENTS; j++) {
                double theta = Math.PI * 2 * j / SEGMENTS;
                double cos = Math.cos(theta), sin = Math.sin(theta);
                // irregular, living outline: never a perfect circle
                double wobble = 1 + 0.07 * Math.sin(3 * theta + time * 0.05 + h * 9)
                        + 0.045 * Math.sin(5 * theta - time * 0.08 + h * 17)
                        + (layer.debris ? 0.12 * Math.sin(2 * theta + time * 0.11 + h * 25) : 0);
                double r = radius[i] * wobble;
                float x = (float) (cx[i] + cos * r), y = (float) yy[i], z = (float) (cz[i] + sin * r);
                px[i][j] = x;
                py[i][j] = y;
                pz[i][j] = z;
                float nnx = (float) cos, nny = (float) -dr, nnz = (float) sin;
                float nl = Mth.sqrt(nnx * nnx + nny * nny + nnz * nnz);
                nnx /= nl;
                nny /= nl;
                nnz /= nl;
                nx[i][j] = nnx;
                ny[i][j] = nny;
                nz[i][j] = nnz;
                // facing the camera? 1 = straight on, 0 = silhouette
                float vx = camX - x, vy = camY - y, vz = camZ - z;
                float vl = Mth.sqrt(vx * vx + vy * vy + vz * vz) + 1e-4f;
                float facing = (nnx * vx + nny * vy + nnz * vz) / vl;
                // Up close / inside the vortex there is no visible "wall", only murk: fade the mesh out near the
                // camera (EffectsManager closes in with dark fog instead).
                double camAxis = Math.sqrt((camX - cx[i]) * (camX - cx[i]) + (camZ - cz[i]) * (camZ - cz[i]));
                float near = smooth((float) ((camAxis - radius[i] * 1.1) / (radius[i] * 1.4)));
                alpha[i][j] = a * near * smooth((facing + 0.02f) / layer.edgeSoftness);
                // lit flank vs. shadowed flank, darker higher up where it meets the cloud base
                float lit = Math.max(0f, nnx * LX + nny * LY + nnz * LZ);
                // strong key light: bright lit flank, dark shadow flank (reads against dark sky and bright horizon)
                float s = (0.28f + 1.05f * lit) * (1f - 0.25f * h);
                if (!layer.debris && h < 0.2f) s *= 0.9f;
                s *= 1f + flash * 0.6f;
                shade[i][j] = s;
            }
        }
        for (int j = 0; j <= SEGMENTS; j++) u[j] = (float) j / SEGMENTS * layer.uRepeat + uScroll;

        float r0 = layer.r, g0 = layer.g, b0 = layer.b;
        for (int i = 0; i < rings; i++) {
            for (int j = 0; j < SEGMENTS; j++) {
                // per-quad back-face cull (the camera can be inside the funnel: then everything is drawn)
                if (alpha[i][j] + alpha[i][j + 1] + alpha[i + 1][j] + alpha[i + 1][j + 1] < 0.01f) continue;
                vertex(buf, pose, i, j, px, py, pz, nx, ny, nz, alpha, shade, u[j] + v[i] * layer.slant * layer.uRepeat, v[i], r0, g0, b0, light);
                vertex(buf, pose, i, j + 1, px, py, pz, nx, ny, nz, alpha, shade, u[j + 1] + v[i] * layer.slant * layer.uRepeat, v[i], r0, g0, b0, light);
                vertex(buf, pose, i + 1, j + 1, px, py, pz, nx, ny, nz, alpha, shade, u[j + 1] + v[i + 1] * layer.slant * layer.uRepeat, v[i + 1], r0, g0, b0, light);
                vertex(buf, pose, i + 1, j, px, py, pz, nx, ny, nz, alpha, shade, u[j] + v[i + 1] * layer.slant * layer.uRepeat, v[i + 1], r0, g0, b0, light);
            }
        }
    }

    private static void vertex(VertexConsumer buf, PoseStack.Pose pose, int i, int j, float[][] px, float[][] py, float[][] pz,
                               float[][] nx, float[][] ny, float[][] nz, float[][] alpha, float[][] shade, float u, float v,
                               float r, float g, float b, int light) {
        float s = shade[i][j];
        buf.addVertex(pose, px[i][j], py[i][j], pz[i][j])
                .setColor(Math.min(1f, r * s), Math.min(1f, g * s), Math.min(1f, b * s), Mth.clamp(alpha[i][j], 0f, 1f))
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, nx[i][j], ny[i][j], nz[i][j]);
    }

    private static float smooth(float t) {
        t = Mth.clamp(t, 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    private FunnelRenderer() {}
}
