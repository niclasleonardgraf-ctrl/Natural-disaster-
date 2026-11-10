package com.realisticdisasters.client.tsunami;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.realisticdisasters.RealisticDisasters;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

/**
 * Draws the tsunami bore as water: one continuous surface drawn with the same translucent-block shader, the same
 * 16-px-per-block texel density and the same water tint as the water blocks around it (blue at sea, muddy once it is
 * on land, see {@link FloodTint}). Its cross-section is a real bore's: it grows out of the water behind it (the actual
 * flood blocks or the sea) into a crest, pitches over into a steep, darker face and meets the sea or ground ahead,
 * and tapers into the water at both ends of the coastline - so the wave and the water are one body.
 *
 * Layers on each ~1-block quad: vanilla {@code water_still} where the back runs into calm water (a seamless join),
 * the animated, more opaque {@code tsunami_water} for the churning body, and the animated {@code tsunami_foam} white
 * water over the lip and face, plus a foam sheet spilling ahead of the toe. The crest height, lip and leading edge
 * churn along the coast over time. Textures: {@code tools/gen_textures.py}.
 */
@EventBusSubscriber(modid = RealisticDisasters.MODID, value = Dist.CLIENT)
public final class WaveRenderer {
    /** Top of a water source block (vanilla renders the surface at 8/9 of the block). */
    static final float WATER_TOP = 0.888f;

    /** One travelling wave front, sampled on the client tick (see {@link TsunamiVisual}). */
    static final class Front {
        double px, pz, dx, dz;      // local frame (see TsunamiDisaster)
        float prevAxial, axial;     // front position along the inland axis, previous and current tick
        float baseY;                // world y of the sea surface
        float lat0;                 // lateral coordinate of the first sample line
        int lines;                  // number of lateral sample lines (1 block apart)
        float[] height;             // crest height above baseY per line (already tapered at the ends)
        float[] back, ahead;        // water/ground height behind and ahead of the wave per line, above baseY
        int[] tint, light;          // water colour and light per line
        float foam;                 // 0..1 how much it is breaking

        double x(double a, double l) { return px - dx * a - dz * l; }

        double z(double a, double l) { return pz - dz * a + dx * l; }
    }

    private static final Map<Integer, List<Front>> FRONTS = new ConcurrentHashMap<>();

    static void set(int disasterId, List<Front> fronts) {
        if (fronts.isEmpty()) FRONTS.remove(disasterId);
        else FRONTS.put(disasterId, fronts);
    }

    static void remove(int disasterId) {
        FRONTS.remove(disasterId);
    }

    @SubscribeEvent
    public static void onSubmit(SubmitCustomGeometryEvent event) {
        if (FRONTS.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Vec3 cam = event.getLevelRenderState().cameraRenderState.pos;
        float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        float time = (mc.level.getGameTime() % 100000L) + pt;
        TextureAtlas atlas = mc.getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS);
        Sprites sp = new Sprites(atlas.getSprite(Identifier.withDefaultNamespace("block/water_still")),
                atlas.getSprite(Identifier.fromNamespaceAndPath(RealisticDisasters.MODID, "block/tsunami_water")),
                atlas.getSprite(Identifier.fromNamespaceAndPath(RealisticDisasters.MODID, "block/tsunami_foam")));
        List<Front> all = FRONTS.values().stream().flatMap(List::stream).toList();
        event.getSubmitNodeCollector().submitCustomGeometry(event.getPoseStack(), RenderTypes.translucentMovingBlock(),
                (pose, buffer) -> {
                    for (Front f : all) emit(pose, buffer, f, cam, pt, time, sp);
                });
    }

    private record Sprites(TextureAtlasSprite still, TextureAtlasSprite water, TextureAtlasSprite foam) {}

    /** Control points of the cross-section (see {@link #profile}) and how finely each span is subdivided. */
    private static final int POINTS = 7;

    private static void emit(PoseStack.Pose pose, VertexConsumer buf, Front f, Vec3 cam, float pt, float time, Sprites sp) {
        float front = Mth.lerp(pt, f.prevAxial, f.axial);
        double cx = f.x(front, 0) - cam.x, cz = f.z(front, 0) - cam.z;
        if (cx * cx + cz * cz > 360 * 360) return;
        int n = f.lines;
        float[][] cd = new float[n][POINTS], ch = new float[n][POINTS], cf = new float[n][POINTS];
        float maxCrest = 0;
        for (int i = 0; i < n; i++) {
            profile(f, i, cd[i], ch[i], cf[i], f.lat0 + i, time);
            maxCrest = Math.max(maxCrest, ch[i][3]);
        }
        // Same subdivision for every line (so neighbouring lines share quads): ~1 block per quad, like a water block.
        int[] steps = new int[POINTS - 1];
        int total = 1;
        for (int s = 0; s < POINTS - 1; s++) {
            float span = (float) Math.hypot(cd[n / 2][s + 1] - cd[n / 2][s], maxCrest * 0.6f * (s >= 3 ? 1 : 0.5f));
            steps[s] = Math.max(1, Math.min(8, (int) Math.ceil(span)));
            total += steps[s];
        }
        float[][] d = new float[n][total], h = new float[n][total], foam = new float[n][total], calm = new float[n][total];
        float[][] shade = new float[n][total];
        for (int i = 0; i < n; i++) sample(cd[i], ch[i], cf[i], steps, d[i], h[i], foam[i], calm[i], shade[i]);

        for (int i = 0; i + 1 < n; i++) {
            float l0 = f.lat0 + i, l1 = l0 + 1;
            int t0 = f.tint[i], t1 = f.tint[i + 1];
            int li0 = f.light[i], li1 = f.light[i + 1];
            cellU = Math.floorMod(Math.round(l0), 4);
            for (int k = 0; k + 1 < total; k++) {
                cellV = Math.floorMod(k, 4);
                float[] xa = vertex(f, cam, front, l0, d[i][k], h[i][k]);
                float[] xb = vertex(f, cam, front, l1, d[i + 1][k], h[i + 1][k]);
                float[] xc = vertex(f, cam, front, l1, d[i + 1][k + 1], h[i + 1][k + 1]);
                float[] xd = vertex(f, cam, front, l0, d[i][k + 1], h[i][k + 1]);
                // 1) calm water where the wave's back runs into the flood / sea (vanilla texture: seamless join)
                float cA = calm[i][k], cB = calm[i + 1][k], cC = calm[i + 1][k + 1], cD = calm[i][k + 1];
                if (cA + cB + cC + cD > 0.02f) {
                    quad(pose, buf, sp.still(), xa, xb, xc, xd,
                            argb(t0, shade[i][k], cA), argb(t1, shade[i + 1][k], cB),
                            argb(t1, shade[i + 1][k + 1], cC), argb(t0, shade[i][k + 1], cD), li0, li1);
                }
                // 2) the wave body: turbulent, silt-laden water
                if (cA + cB + cC + cD < 3.98f) {
                    quad(pose, buf, sp.water(), xa, xb, xc, xd,
                            argb(t0, shade[i][k], 1 - cA), argb(t1, shade[i + 1][k], 1 - cB),
                            argb(t1, shade[i + 1][k + 1], 1 - cC), argb(t0, shade[i][k + 1], 1 - cD), li0, li1);
                }
                // 3) breaking white water riding on it
                float fA = foam[i][k], fB = foam[i + 1][k], fC = foam[i + 1][k + 1], fD = foam[i][k + 1];
                if (fA + fB + fC + fD > 0.02f) {
                    float lift = 0.03f;
                    quad(pose, buf, sp.foam(),
                            vertex(f, cam, front, l0, d[i][k], h[i][k] + lift), vertex(f, cam, front, l1, d[i + 1][k], h[i + 1][k] + lift),
                            vertex(f, cam, front, l1, d[i + 1][k + 1], h[i + 1][k + 1] + lift), vertex(f, cam, front, l0, d[i][k + 1], h[i][k + 1] + lift),
                            argb(0xFFFFFF, 0.6f + 0.4f * shade[i][k], fA), argb(0xFFFFFF, 0.6f + 0.4f * shade[i + 1][k], fB),
                            argb(0xFFFFFF, 0.6f + 0.4f * shade[i + 1][k + 1], fC), argb(0xFFFFFF, 0.6f + 0.4f * shade[i][k + 1], fD), li0, li1);
                }
            }
            // Foam spilling ahead of the toe over the sea / ground.
            if (f.foam > 0.05f) {
                int last = total - 1;
                float y0 = h[i][last] + 0.03f, y1 = h[i + 1][last] + 0.03f;
                float a = 0.8f * f.foam;
                for (int k = 0; k < 2; k++) {
                    float s0 = k * 1.2f, s1 = s0 + 1.2f, a0 = a * (1 - k * 0.5f), a1 = a * (0.5f - k * 0.5f);
                    quad(pose, buf, sp.foam(),
                            vertex(f, cam, front, l0, d[i][last] + s0, y0), vertex(f, cam, front, l1, d[i + 1][last] + s0, y1),
                            vertex(f, cam, front, l1, d[i + 1][last] + s1, y1), vertex(f, cam, front, l0, d[i][last] + s1, y0),
                            argb(0xFFFFFF, 1f, a0), argb(0xFFFFFF, 1f, a0), argb(0xFFFFFF, 1f, a1), argb(0xFFFFFF, 1f, a1), li0, li1);
                }
            }
        }
    }

    /**
     * Smooth cross-section through the control points (Catmull-Rom), ~1 block per sample. Also returns per sample: foam
     * amount, "calm" (how much the surface is still the ordinary water it grows out of) and slope shading.
     */
    private static void sample(float[] cd, float[] ch, float[] cf, int[] steps, float[] d, float[] h, float[] foam,
                               float[] calm, float[] shade) {
        int k = 0;
        for (int s = 0; s < POINTS - 1; s++) {
            int a = Math.max(0, s - 1), b = s, c = s + 1, e = Math.min(POINTS - 1, s + 2);
            for (int j = 0; j < steps[s]; j++, k++) {
                float t = j / (float) steps[s];
                d[k] = catmull(cd[a], cd[b], cd[c], cd[e], t);
                h[k] = Math.max(0f, catmull(ch[a], ch[b], ch[c], ch[e], t));
                foam[k] = Mth.lerp(t, cf[b], cf[c]);
                float u = s + t; // 0..6 along the section
                calm[k] = 1f - smoothstepf(0f, 1.6f, u);
            }
        }
        d[k] = cd[POINTS - 1];
        h[k] = ch[POINTS - 1];
        foam[k] = cf[POINTS - 1];
        calm[k] = 0f;
        // Shading from the slope: lit back, darker steep face (descending forward).
        for (int m = 0; m <= k; m++) {
            int p = Math.max(0, m - 1), q = Math.min(k, m + 1);
            float dd = d[q] - d[p], dh = h[q] - h[p];
            float steep = dd <= 0.01f ? 1f : Mth.clamp(-dh / dd / 2.5f, 0f, 1f);
            shade[m] = dh < 0 ? 1f - 0.32f * steep : 0.96f;
        }
    }

    private static float catmull(float p0, float p1, float p2, float p3, float t) {
        float t2 = t * t, t3 = t2 * t;
        return 0.5f * (2 * p1 + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3);
    }

    private static float smoothstepf(float e0, float e1, float x) {
        float t = Mth.clamp((x - e0) / (e1 - e0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    /** Smooth pseudo-random churn in -1..1 along the coast and over time (a few travelling sine waves). */
    private static float churn(float l, float t, float seed) {
        return (float) (Math.sin(l * 0.23 + t * 0.19 + seed) * 0.55 + Math.sin(l * 0.57 - t * 0.31 + seed * 2.1) * 0.3
                + Math.sin(l * 1.31 + t * 0.47 + seed * 3.7) * 0.15);
    }

    /** Control points: water behind -> back slope -> crest -> pitching lip -> steep face -> toe on the water ahead. */
    private static void profile(Front f, int i, float[] d, float[] h, float[] foam, float lateral, float t) {
        float hb = f.back[i], ha = f.ahead[i];
        float raw = f.height[i] * (1f + 0.1f * churn(lateral, t, 0f));
        float crest = Math.max(raw + ha, Math.max(hb, ha) + 0.05f);
        float rough = Math.min(1f, f.height[i] / 2f);
        float len = 5f + crest * 1.5f;
        d[0] = -len;         h[0] = hb;
        d[1] = -len * 0.62f; h[1] = hb + (crest - hb) * (0.3f + 0.05f * churn(lateral, t, 5f));
        d[2] = -len * 0.3f;  h[2] = hb + (crest - hb) * (0.78f + 0.06f * churn(lateral, t, 9f) * rough);
        d[3] = -1.0f;        h[3] = crest;
        d[4] = -0.1f + 0.45f * churn(lateral, t * 1.6f, 13f) * rough;
        h[4] = crest * (0.96f + 0.04f * churn(lateral, t * 2f, 17f)) + ha * 0.03f;
        d[5] = 0.4f + 0.3f * churn(lateral, t * 1.3f, 21f) * rough;
        h[5] = ha + (crest - ha) * (0.5f + 0.1f * churn(lateral, t * 1.8f, 25f));
        d[6] = 0.9f + 0.5f * churn(lateral, t * 0.9f, 29f) * rough;
        h[6] = ha;
        float k = f.foam * Mth.clamp((crest - Math.max(hb, ha)) / 1.5f, 0f, 1f);
        foam[0] = 0f;
        foam[1] = 0f;
        foam[2] = 0.06f * k;
        foam[3] = 0.5f * k;
        foam[4] = 1.0f * k;
        foam[5] = 0.95f * k;
        foam[6] = 0.85f * k;
    }

    /** Texture cell (0..3) along the coast and along the section: the textures cover 4x4 blocks, mapped continuously. */
    private static int cellU, cellV;

    /**
     * One quad, both windings (visible from above, below and inside). Each quad shows one block's worth of texture
     * (16 px per block, like a water block), taken from cell ({@link #cellU}, {@link #cellV}) of the 4x4-block texture.
     */
    private static void quad(PoseStack.Pose pose, VertexConsumer buf, TextureAtlasSprite sp,
                             float[] a, float[] b, float[] c, float[] d, int colA, int colB, int colC, int colD,
                             int lightA, int lightB) {
        boolean big = sp.contents().width() >= 64;
        float cu = big ? cellU / 4f : 0f, cv = big ? cellV / 4f : 0f, span = big ? 0.25f : 1f;
        float u0 = sp.getU(cu), u1 = sp.getU(cu + span), v0 = sp.getV(cv), v1 = sp.getV(cv + span);
        put(pose, buf, a, colA, u0, v0, lightA);
        put(pose, buf, b, colB, u1, v0, lightB);
        put(pose, buf, c, colC, u1, v1, lightB);
        put(pose, buf, d, colD, u0, v1, lightA);
        put(pose, buf, d, colD, u0, v1, lightA);
        put(pose, buf, c, colC, u1, v1, lightB);
        put(pose, buf, b, colB, u1, v0, lightB);
        put(pose, buf, a, colA, u0, v0, lightA);
    }

    private static float[] vertex(Front f, Vec3 cam, float front, float lateral, float dAxial, float height) {
        double a = front + dAxial;
        return new float[]{(float) (f.x(a, lateral) - cam.x), (float) (f.baseY + height - cam.y), (float) (f.z(a, lateral) - cam.z)};
    }

    private static void put(PoseStack.Pose pose, VertexConsumer buf, float[] p, int argb, float u, float v, int light) {
        buf.addVertex(pose, p[0], p[1], p[2]).setColor(argb).setUv(u, v).setLight(light);
    }

    private static int argb(int rgb, float shade, float alpha) {
        int a = (int) (Mth.clamp(alpha, 0f, 1f) * 255);
        int r = (int) (((rgb >> 16) & 255) * shade), g = (int) (((rgb >> 8) & 255) * shade), b = (int) ((rgb & 255) * shade);
        return a << 24 | r << 16 | g << 8 | b;
    }

    private WaveRenderer() {}
}
