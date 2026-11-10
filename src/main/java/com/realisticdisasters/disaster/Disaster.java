package com.realisticdisasters.disaster;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

import com.realisticdisasters.network.DisasterSyncPayload;

/**
 * Server-side base for every disaster. Handles the warning → buildup → peak → aftermath lifecycle and a smooth
 * 0..1 "strength" envelope that both server logic (forces, damage) and client effects (fog, shake) scale with.
 */
public abstract class Disaster {
    protected final DisasterType type;
    protected final int id;
    protected final ServerLevel level;
    protected final RandomSource random;
    protected final int intensity;
    protected Vec3 pos;

    private Phase phase = Phase.WARNING;
    private int phaseAge;
    private int age;
    private boolean finished;
    private final int[] durations = new int[Phase.values().length];

    protected Disaster(DisasterType type, ServerLevel level, int id, Vec3 pos, int intensity) {
        this.type = type;
        this.level = level;
        this.id = id;
        this.pos = pos;
        this.intensity = Mth.clamp(intensity, 1, 5);
        this.random = RandomSource.create(level.getRandom().nextLong());
    }

    /** Sets the length of a phase in ticks. Subclasses call this from their constructor. */
    protected final void setDuration(Phase phase, int ticks) {
        durations[phase.ordinal()] = Math.max(1, ticks);
    }

    public final void tick() {
        if (finished) return;
        age++;
        phaseAge++;
        if (phaseAge >= durations[phase.ordinal()]) {
            if (phase == Phase.AFTERMATH) {
                finished = true;
                onEnd();
                return;
            }
            Phase old = phase;
            phase = phase.next();
            phaseAge = 0;
            onPhaseChange(old, phase);
        }
        tickDisaster();
    }

    /** Skip ahead to the aftermath (used by /disaster stop so effects still fade out smoothly). */
    public void beginAftermath() {
        if (phase != Phase.AFTERMATH) {
            Phase old = phase;
            phase = Phase.AFTERMATH;
            phaseAge = 0;
            onPhaseChange(old, phase);
        }
    }

    /** Jump forward to a later phase (testing / admin use). */
    public void skipTo(Phase target) {
        while (phase.ordinal() < target.ordinal()) {
            Phase old = phase;
            phase = phase.next();
            phaseAge = 0;
            onPhaseChange(old, phase);
        }
    }

    /** Remove immediately without fade. */
    public void forceFinish() {
        if (!finished) {
            finished = true;
            onEnd();
        }
    }

    protected abstract void tickDisaster();

    protected void onPhaseChange(Phase from, Phase to) {}

    protected void onEnd() {}

    /** Horizontal radius within which the disaster's atmosphere is felt (for sync / effects). */
    public abstract float effectRadius();

    /** Type-specific values for the client (see the client visual for meaning). */
    protected float[] syncData() {
        return new float[0];
    }

    public abstract Component describe();

    // ---- envelope ----

    public float phaseProgress() {
        return Mth.clamp(phaseAge / (float) durations[phase.ordinal()], 0f, 1f);
    }

    /**
     * Overall strength 0..1 with smooth blends: warning ramps to 0.2, buildup eases to 1, peak holds,
     * aftermath eases back to 0.
     */
    public float strength() {
        float p = phaseProgress();
        return switch (phase) {
            case WARNING -> 0.2f * smooth(p);
            case BUILDUP -> 0.2f + 0.8f * smooth(p);
            case PEAK -> 1f;
            case AFTERMATH -> 1f - smooth(p);
        };
    }

    protected static float smooth(float t) {
        t = Mth.clamp(t, 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    public DisasterSyncPayload toPayload() {
        return new DisasterSyncPayload(id, type.ordinal(), phase.ordinal(), phaseProgress(), strength(), intensity,
                pos.x, pos.y, pos.z, effectRadius(), syncData());
    }

    // ---- accessors ----
    public DisasterType type() { return type; }
    public int id() { return id; }
    public ServerLevel level() { return level; }
    public Vec3 pos() { return pos; }
    public int intensity() { return intensity; }
    public Phase phase() { return phase; }
    public int age() { return age; }
    public int phaseAge() { return phaseAge; }
    public boolean isFinished() { return finished; }
}
