package com.realisticdisasters.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Client-only visual settings. Values are plain data, safe to load on a dedicated server. */
public final class ClientConfig {
    private static final ModConfigSpec.Builder B = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue CAMERA_SHAKE;
    public static final ModConfigSpec.DoubleValue CAMERA_SHAKE_STRENGTH;
    public static final ModConfigSpec.BooleanValue FOV_PULSE;
    public static final ModConfigSpec.DoubleValue PARTICLE_DENSITY;
    public static final ModConfigSpec.BooleanValue FOG;
    public static final ModConfigSpec.BooleanValue SKY_TINT;

    static {
        B.push("camera");
        CAMERA_SHAKE = B.comment("Distance-based camera shake.").define("cameraShake", true);
        CAMERA_SHAKE_STRENGTH = B.defineInRange("cameraShakeStrength", 1.0, 0.0, 2.0);
        FOV_PULSE = B.comment("Subtle field-of-view pulse near the strongest effects.").define("fovPulse", true);
        B.pop();
        B.push("visuals");
        PARTICLE_DENSITY = B.comment("Multiplier for disaster particles (also scaled by the vanilla particle setting).")
                .defineInRange("particleDensity", 1.0, 0.0, 2.0);
        FOG = B.comment("Custom disaster fog.").define("fog", true);
        SKY_TINT = B.comment("Custom sky / cloud tint.").define("skyTint", true);
        B.pop();
    }

    public static final ModConfigSpec SPEC = B.build();

    private ClientConfig() {}
}
