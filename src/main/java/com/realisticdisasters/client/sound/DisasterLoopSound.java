package com.realisticdisasters.client.sound;

import java.util.function.ToDoubleFunction;

import com.realisticdisasters.client.ClientDisaster;
import com.realisticdisasters.client.ClientDisasters;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * A looping sound bound to a disaster. Its volume is recomputed every tick by the owning visual, and it glides
 * toward that value so layers cross-fade smoothly (distant rumble ↔ close roar).
 */
public class DisasterLoopSound extends AbstractTickableSoundInstance {
    private final int disasterId;
    private final ToDoubleFunction<ClientDisaster> volumeFn;
    private final ToDoubleFunction<ClientDisaster> pitchFn;
    private float currentVolume;

    public DisasterLoopSound(SoundEvent event, ClientDisaster d, boolean positional,
                             ToDoubleFunction<ClientDisaster> volumeFn, ToDoubleFunction<ClientDisaster> pitchFn) {
        super(event, SoundSource.WEATHER, RandomSource.create());
        this.disasterId = d.id;
        this.volumeFn = volumeFn;
        this.pitchFn = pitchFn;
        this.looping = true;
        this.delay = 0;
        this.volume = 0.0001f;
        this.attenuation = positional ? SoundInstance.Attenuation.LINEAR : SoundInstance.Attenuation.NONE;
        Vec3 p = d.pos();
        this.x = p.x;
        this.y = p.y;
        this.z = p.z;
    }

    @Override
    public void tick() {
        ClientDisaster d = ClientDisasters.get(disasterId);
        float target = d == null ? 0f : (float) Math.max(0, volumeFn.applyAsDouble(d));
        currentVolume += (target - currentVolume) * 0.08f;
        if (d == null && currentVolume < 0.01f) {
            stop();
            return;
        }
        volume = Math.max(0.0001f, currentVolume);
        if (d != null) {
            Vec3 p = d.pos();
            x = p.x;
            y = p.y + 4;
            z = p.z;
            pitch = (float) pitchFn.applyAsDouble(d);
        }
    }

    public void end() {
        stop();
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }
}
