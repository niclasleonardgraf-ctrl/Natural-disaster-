package com.realisticdisasters.client;

import com.realisticdisasters.disaster.DisasterType;
import com.realisticdisasters.disaster.Phase;
import com.realisticdisasters.network.DisasterSyncPayload;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Client mirror of a server disaster. Server updates arrive every few ticks; position and strength are
 * smoothed per tick so motion and blending stay fluid between packets.
 */
public final class ClientDisaster {
    public final int id;
    public final DisasterType type;
    public int intensity;
    public Phase phase;
    public float phaseProgress;
    public float[] data = new float[0];
    public float radius;

    private Vec3 target;
    private Vec3 pos, prevPos;
    private float targetStrength;
    private float strength, prevStrength;
    private final float[] smoothData = new float[16];
    int ticksSinceUpdate;
    /** Per-disaster scratch space for its visual (sound handles, timers). */
    public Object visualState;

    ClientDisaster(DisasterSyncPayload p) {
        this.id = p.id();
        this.type = DisasterType.byId(p.kind());
        this.target = new Vec3(p.x(), p.y(), p.z());
        this.pos = target;
        this.prevPos = target;
        apply(p);
        System.arraycopy(data, 0, smoothData, 0, Math.min(data.length, smoothData.length));
        this.strength = this.prevStrength = 0f;
    }

    void apply(DisasterSyncPayload p) {
        intensity = p.intensity();
        phase = Phase.byId(p.phase());
        phaseProgress = p.phaseProgress();
        targetStrength = p.strength();
        radius = p.radius();
        data = p.data();
        target = new Vec3(p.x(), p.y(), p.z());
        ticksSinceUpdate = 0;
    }

    void tick() {
        ticksSinceUpdate++;
        prevPos = pos;
        prevStrength = strength;
        // Glide toward the latest server position (server moves well under a block per tick).
        pos = pos.lerp(target, 0.3);
        if (pos.distanceToSqr(target) > 64 * 64) pos = target;
        strength += (targetStrength - strength) * 0.15f;
        for (int i = 0; i < Math.min(data.length, smoothData.length); i++) {
            smoothData[i] += (data[i] - smoothData[i]) * 0.15f;
        }
    }

    /** Called after the server said this disaster ended: fade out locally. */
    void fadeOut() {
        targetStrength = 0f;
    }

    public Vec3 pos() {
        return pos;
    }

    public Vec3 pos(float partialTick) {
        return prevPos.lerp(pos, partialTick);
    }

    public float strength() {
        return strength;
    }

    public float strength(float partialTick) {
        return Mth.lerp(partialTick, prevStrength, strength);
    }

    /** Smoothed type-specific value. */
    public float data(int index) {
        return index < smoothData.length && index < data.length ? smoothData[index] : 0f;
    }
}
