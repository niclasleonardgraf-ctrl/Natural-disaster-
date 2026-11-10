package com.realisticdisasters.client.meteor;

import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

import com.realisticdisasters.client.EffectsManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * The whoosh of one falling meteor. Follows the meteor, rises in pitch while it approaches the listener (a cheap
 * Doppler effect) and cuts off at the moment of impact, where the impact sound takes over.
 */
final class MeteorFlightSound extends AbstractTickableSoundInstance {
    private final Supplier<Vec3> position;
    private final DoubleSupplier volumeFn;
    private final long endTick;
    private final float basePitch;
    private double lastDistance = -1;

    MeteorFlightSound(SoundEvent event, boolean positional, Supplier<Vec3> position, DoubleSupplier volumeFn,
                      long endTick, float basePitch) {
        super(event, SoundSource.WEATHER, RandomSource.create());
        this.position = position;
        this.volumeFn = volumeFn;
        this.endTick = endTick;
        this.basePitch = basePitch;
        this.looping = false;
        this.delay = 0;
        this.attenuation = positional ? SoundInstance.Attenuation.LINEAR : SoundInstance.Attenuation.NONE;
        this.pitch = basePitch;
        update();
    }

    @Override
    public void tick() {
        if (EffectsManager.ticks() >= endTick) {
            stop();
            return;
        }
        update();
    }

    private void update() {
        Vec3 p = position.get();
        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().position();
        double dist = p.distanceTo(cam);
        if (attenuation == SoundInstance.Attenuation.NONE) {
            // Non-attenuated layer: place it near the listener, in the meteor's direction.
            Vec3 dir = p.subtract(cam);
            Vec3 at = cam.add(dir.normalize().scale(Math.min(dist, 12)));
            x = at.x;
            y = at.y;
            z = at.z;
        } else {
            x = p.x;
            y = p.y;
            z = p.z;
        }
        if (lastDistance >= 0) {
            double closing = lastDistance - dist; // blocks per tick toward the listener
            pitch = basePitch * (float) Mth.clamp(1 + closing / 40.0, 0.8, 1.3);
        }
        lastDistance = dist;
        volume = (float) Math.max(0.0001, volumeFn.getAsDouble());
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }
}
