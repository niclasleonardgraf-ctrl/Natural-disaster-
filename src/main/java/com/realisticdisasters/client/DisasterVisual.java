package com.realisticdisasters.client;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;

/**
 * Client presentation of one disaster type: what it contributes to the shared atmosphere (fog, sky, shake)
 * and its own particles and sounds.
 */
public interface DisasterVisual {
    /** Adds this disaster's atmosphere to the frame target. Called once per client tick. */
    void contribute(ClientDisaster d, Vec3 camera, Atmosphere out);

    /** Spawn particles, manage sounds. Called once per client tick. */
    void tick(ClientDisaster d, ClientLevel level, Camera camera);

    /** The disaster is gone (fade complete or world left): release sounds etc. */
    default void onRemoved(ClientDisaster d) {}

    /** Accumulator for blended atmosphere targets. Colors are weighted averages, the rest take the maximum. */
    final class Atmosphere {
        float skyR, skyG, skyB, skyWeight;
        float fogR, fogG, fogB, fogWeight;
        float cloudR, cloudG, cloudB, cloudWeight;
        float darkness;
        float fogEnd = Float.MAX_VALUE;
        float fogAmount;
        float shake;
        float fovPulse;

        void reset() {
            skyR = skyG = skyB = skyWeight = 0;
            fogR = fogG = fogB = fogWeight = 0;
            cloudR = cloudG = cloudB = cloudWeight = 0;
            darkness = 0;
            fogEnd = Float.MAX_VALUE;
            fogAmount = 0;
            shake = 0;
            fovPulse = 0;
        }

        public void sky(int rgb, float w) {
            if (w <= 0) return;
            skyR += ((rgb >> 16) & 255) / 255f * w;
            skyG += ((rgb >> 8) & 255) / 255f * w;
            skyB += (rgb & 255) / 255f * w;
            skyWeight += w;
        }

        public void fogColor(int rgb, float w) {
            if (w <= 0) return;
            fogR += ((rgb >> 16) & 255) / 255f * w;
            fogG += ((rgb >> 8) & 255) / 255f * w;
            fogB += (rgb & 255) / 255f * w;
            fogWeight += w;
        }

        public void clouds(int rgb, float w) {
            if (w <= 0) return;
            cloudR += ((rgb >> 16) & 255) / 255f * w;
            cloudG += ((rgb >> 8) & 255) / 255f * w;
            cloudB += (rgb & 255) / 255f * w;
            cloudWeight += w;
        }

        /** @param end fog end distance in blocks; @param amount 0..1 how strongly to apply it */
        public void fog(float end, float amount) {
            if (amount <= 0) return;
            fogEnd = Math.min(fogEnd, end);
            fogAmount = Math.max(fogAmount, amount);
        }

        public void darken(float amount) {
            darkness = Math.max(darkness, amount);
        }

        public void shake(float amount) {
            shake += amount;
        }

        public void fovPulse(float amount) {
            fovPulse = Math.max(fovPulse, amount);
        }
    }
}
