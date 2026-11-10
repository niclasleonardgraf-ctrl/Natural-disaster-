package com.realisticdisasters.client.wildfire;

import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;

import com.realisticdisasters.client.ClientDisaster;
import com.realisticdisasters.client.sound.DisasterLoopSound;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.phys.Vec3;

/**
 * A positional disaster loop that sits at the nearest burning point instead of the disaster's centre, so a long fire
 * front sounds like it is right next to you when it is.
 */
final class FireLoopSound extends DisasterLoopSound {
    private final Supplier<Vec3> position;
    private Vec3 smoothed;

    FireLoopSound(SoundEvent event, ClientDisaster d, Supplier<Vec3> position,
                  ToDoubleFunction<ClientDisaster> volumeFn, ToDoubleFunction<ClientDisaster> pitchFn) {
        super(event, d, true, volumeFn, pitchFn);
        this.position = position;
    }

    @Override
    public void tick() {
        super.tick(); // volume, pitch, and a default position at the disaster centre
        Vec3 p = position.get();
        if (p == null) return;
        // glide so the source doesn't jump between front points
        smoothed = smoothed == null ? p : smoothed.lerp(p, 0.2);
        x = smoothed.x;
        y = smoothed.y;
        z = smoothed.z;
    }
}
