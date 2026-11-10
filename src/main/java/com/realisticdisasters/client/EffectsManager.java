package com.realisticdisasters.client;

import com.realisticdisasters.RealisticDisasters;
import com.realisticdisasters.config.ClientConfig;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * Blends every nearby disaster's atmosphere into one smoothly-changing state and applies it: sky / cloud / fog
 * tint (through environment-attribute layers, see ClientLevelMixin), fog distance, camera shake and FOV pulse.
 * All of this works through vanilla's own pipeline, so shader packs receive the same values.
 */
@EventBusSubscriber(modid = RealisticDisasters.MODID, value = Dist.CLIENT)
public final class EffectsManager {
    private static final DisasterVisual.Atmosphere TARGET = new DisasterVisual.Atmosphere();

    // smoothed state (current + previous tick for partial-tick interpolation)
    private static final float[] sky = new float[4], fog = new float[4], cloud = new float[4]; // r,g,b,weight
    private static float darkness, fogEnd = 512f, fogAmount, shake, prevShake, fovPulse;
    private static long ticks;
    // in-cloud lightning: a short flicker sequence
    private static final float[] FLICKER = {1f, 0.25f, 0.85f, 0.15f, 0.55f, 0.1f, 0.25f, 0f};
    private static int flashStep = -1;
    private static float flashStrength, flash;

    /** Starts an in-cloud lightning flicker (0..1 strength). */
    public static void triggerFlash(float strength) {
        flashStep = 0;
        flashStrength = Mth.clamp(strength, 0f, 1f);
    }

    /** Current lightning flash brightness 0..1. */
    public static float flash() {
        return flash;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.isPaused()) return;
        ticks++;
        if (flashStep >= 0) {
            flash = flashStep < FLICKER.length ? FLICKER[flashStep] * flashStrength : 0f;
            if (++flashStep > FLICKER.length) flashStep = -1;
        } else {
            flash = 0f;
        }
        ClientDisasters.tick();

        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 cam = camera.position();
        TARGET.reset();
        for (ClientDisaster d : ClientDisasters.all()) {
            DisasterVisual visual = DisasterVisuals.get(d.type);
            visual.contribute(d, cam, TARGET);
            visual.tick(d, mc.level, camera);
        }

        // Blend toward the target (≈2 s time constant: smooth in and out).
        final float k = 0.05f;
        blendColor(sky, TARGET.skyR, TARGET.skyG, TARGET.skyB, TARGET.skyWeight, k);
        blendColor(fog, TARGET.fogR, TARGET.fogG, TARGET.fogB, TARGET.fogWeight, k);
        blendColor(cloud, TARGET.cloudR, TARGET.cloudG, TARGET.cloudB, TARGET.cloudWeight, k);
        darkness += (TARGET.darkness - darkness) * k;
        fogAmount += (TARGET.fogAmount - fogAmount) * k;
        if (TARGET.fogAmount > 0.001f) fogEnd += (TARGET.fogEnd - fogEnd) * 0.1f;
        prevShake = shake;
        shake += (Math.min(TARGET.shake, 1.5f) - shake) * 0.2f;
        fovPulse += (TARGET.fovPulse - fovPulse) * 0.1f;

        if (DEBUG && ticks % 40 == 0) {
            for (ClientDisaster d : ClientDisasters.all()) {
                RealisticDisasters.LOGGER.info("[debug] #{} {} phase={} str={} dist={} rel={} data={} skyW={} fogEnd={} shake={}",
                        d.id, d.type, d.phase, String.format("%.2f", d.strength()), String.format("%.1f", d.pos().distanceTo(cam)),
                        d.pos().subtract(cam), java.util.Arrays.toString(d.data), String.format("%.2f", sky[3]),
                        String.format("%.0f", fogEnd), String.format("%.2f", shake));
            }
        }
    }

    private static final boolean DEBUG = Boolean.getBoolean("realisticdisasters.debug");

    private static void blendColor(float[] c, float r, float g, float b, float w, float k) {
        float weight = Math.min(w, 1f);
        if (w > 0) {
            // only move the hue while something is actually contributing
            c[0] += (r / w - c[0]) * k * 2;
            c[1] += (g / w - c[1]) * k * 2;
            c[2] += (b / w - c[2]) * k * 2;
        }
        c[3] += (weight - c[3]) * k;
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientDisasters.clear();
        java.util.Arrays.fill(sky, 0);
        java.util.Arrays.fill(fog, 0);
        java.util.Arrays.fill(cloud, 0);
        darkness = fogAmount = shake = prevShake = fovPulse = 0;
        fogEnd = 512f;
    }

    // ------------------------------------------------------------------ environment attribute layers

    private static int tint(int base, float[] c, float maxBlend) {
        float w = c[3] * maxBlend;
        if (w < 0.002f) return base;
        int target = ARGB.colorFromFloat(1f, Mth.clamp(c[0], 0, 1), Mth.clamp(c[1], 0, 1), Mth.clamp(c[2], 0, 1));
        return ARGB.srgbLerp(Mth.clamp(w, 0, 1), base, target);
    }

    public static int skyColor(int base) {
        if (!ClientConfig.SKY_TINT.getAsBoolean()) return base;
        int c = tint(base, sky, 0.95f);
        return flash > 0f ? ARGB.srgbLerp(flash * 0.55f, c, 0xFFC9CEDD) : c;
    }

    public static int cloudColor(int base) {
        if (!ClientConfig.SKY_TINT.getAsBoolean()) return base;
        int tinted = tint(base, cloud, 0.9f);
        // Vanilla's blocky clouds give way to the storm's own soft cloud deck.
        float keep = 1f - Mth.clamp(cloud[3], 0f, 1f) * 0.95f;
        return ARGB.color(Math.round(ARGB.alpha(base) * keep), tinted);
    }

    public static int fogColor(int base) {
        return ClientConfig.FOG.getAsBoolean() ? tint(base, fog, 0.85f) : base;
    }

    public static float skyLightFactor(float base) {
        return ClientConfig.SKY_TINT.getAsBoolean() ? base * (1f - Mth.clamp(darkness, 0, 0.85f) * (1f - flash * 0.7f)) : base;
    }

    // ------------------------------------------------------------------ viewport events

    @SubscribeEvent
    public static void onRenderFog(ViewportEvent.RenderFog event) {
        if (fogAmount < 0.003f || !ClientConfig.FOG.getAsBoolean() || event.getType() != FogType.ATMOSPHERIC) return;
        float far = event.getFarPlaneDistance();
        float targetFar = Math.min(far, fogEnd);
        float newFar = Mth.lerp(Mth.clamp(fogAmount, 0, 1), far, targetFar);
        event.setFarPlaneDistance(newFar);
        event.setNearPlaneDistance(Math.min(event.getNearPlaneDistance(), newFar * 0.4f));
    }

    @SubscribeEvent
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (!ClientConfig.CAMERA_SHAKE.getAsBoolean()) return;
        float pt = (float) event.getPartialTick();
        float amp = Mth.lerp(pt, prevShake, shake) * ClientConfig.CAMERA_SHAKE_STRENGTH.get().floatValue();
        if (amp < 0.002f) return;
        float t = ticks + pt;
        // Layered sines = cheap smooth noise; a fast "rattle" layer on top of a slow sway.
        float yaw = (float) (Math.sin(t * 0.9) * 0.6 + Math.sin(t * 2.3 + 1.7) * 0.3 + Math.sin(t * 5.1 + 0.3) * 0.1);
        float pitch = (float) (Math.sin(t * 1.1 + 2.1) * 0.55 + Math.sin(t * 2.9 + 0.4) * 0.3 + Math.sin(t * 6.3 + 2.2) * 0.15);
        float roll = (float) (Math.sin(t * 0.7 + 4.0) * 0.7 + Math.sin(t * 3.7) * 0.3);
        float deg = amp * 2.2f;
        event.setYaw(event.getYaw() + yaw * deg);
        event.setPitch(event.getPitch() + pitch * deg);
        event.setRoll(event.getRoll() + roll * deg * 0.8f);
    }

    @SubscribeEvent
    public static void onComputeFov(ViewportEvent.ComputeFov event) {
        if (!ClientConfig.FOV_PULSE.getAsBoolean() || !ClientConfig.CAMERA_SHAKE.getAsBoolean() || fovPulse < 0.003f) return;
        float t = ticks + (float) event.getPartialTick();
        float pulse = (float) (Math.sin(t * 0.21) * 0.6 + Math.sin(t * 0.53 + 1.3) * 0.4);
        event.setFOV(event.getFOV() * (1f + pulse * 0.035f * Math.min(fovPulse, 1f)));
    }

    public static long ticks() {
        return ticks;
    }

    private EffectsManager() {}
}
