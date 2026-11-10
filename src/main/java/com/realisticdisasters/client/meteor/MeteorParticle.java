package com.realisticdisasters.client.meteor;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;

/**
 * Soft meteor particles, configured after creation by {@link MeteorShowerVisual} (see the fluent setters):
 * <ul>
 *   <li>GLOW: self-lit soft glow. Streak wakes, persistent trains and fireball halos. Colour shifts from
 *       {@code start} to {@code end} and fades out.</li>
 *   <li>FIREBALL: the white-hot head of a meteor. Lives two ticks and is respawned every tick by the visual, moving
 *       with the meteor so it interpolates smoothly.</li>
 *   <li>EMBER: a hot fragment. Falls, bounces and cools from yellow-white to dull red.</li>
 *   <li>SMOKE: soft smoke or dust puff that grows, slows down and fades. Optionally starts hot (self-lit, glowing
 *       orange) and cools to its smoke colour, which is how the impact fireball and lit smoke trails work.</li>
 * </ul>
 * Spawn args: xa/ya/za = velocity in blocks per tick (used as-is, without vanilla's random jitter).
 */
public class MeteorParticle extends SingleQuadParticle {
    public enum Kind { GLOW, FIREBALL, EMBER, SMOKE }

    private static final int FULL_BRIGHT = 0xF000F0;

    private final Kind kind;
    private float spin;
    private float sr = 1f, sg = 1f, sb = 1f;     // start colour
    private float er = 1f, eg = 1f, eb = 1f;     // end colour
    private float alpha0 = 1f;
    private float growTo = 1f;
    private float hotFraction;                   // SMOKE: part of the lifetime spent glowing
    private float buoyancy;
    private boolean emissive;

    protected MeteorParticle(ClientLevel level, double x, double y, double z, double xa, double ya, double za,
                             Kind kind, SpriteSet sprites) {
        super(level, x, y, z, sprites.first());
        this.kind = kind;
        setSprite(sprites.get(random));
        this.xd = xa;
        this.yd = ya;
        this.zd = za;
        this.hasPhysics = false;
        this.gravity = 0;
        this.roll = this.oRoll = random.nextFloat() * Mth.TWO_PI;
        switch (kind) {
            case GLOW -> {
                friction = 0.9f;
                lifetime = 5 + random.nextInt(4);
                quadSize = 0.5f;
                emissive = true;
                colors(1f, 0.95f, 0.85f, 0.9f, 0.4f, 0.15f);
            }
            case FIREBALL -> {
                friction = 1f;
                lifetime = 2;
                quadSize = 1f;
                emissive = true;
                colors(1f, 0.98f, 0.92f, 1f, 0.98f, 0.92f);
            }
            case EMBER -> {
                hasPhysics = true;
                gravity = 0.75f;
                friction = 0.97f;
                lifetime = 22 + random.nextInt(26);
                quadSize = 0.12f + random.nextFloat() * 0.1f;
                emissive = true;
                spin = (random.nextFloat() - 0.5f) * 0.4f;
                colors(1f, 0.92f, 0.65f, 0.6f, 0.15f, 0.08f);
            }
            default -> {
                friction = 0.92f;
                lifetime = 60 + random.nextInt(40);
                quadSize = 1f;
                growTo = 3f;
                alpha0 = 0.6f;
                spin = (random.nextFloat() - 0.5f) * 0.03f;
                float g = 0.42f + random.nextFloat() * 0.1f;
                colors(g, g, g, g, g, g);
            }
        }
        apply(0f);
    }

    // ------------------------------------------------------------------ configuration (fluent, for the visual)

    public MeteorParticle colors(float r0, float g0, float b0, float r1, float g1, float b1) {
        sr = r0; sg = g0; sb = b0; er = r1; eg = g1; eb = b1;
        apply(age / (float) Math.max(1, lifetime));
        return this;
    }

    public MeteorParticle life(int ticks) {
        lifetime = Math.max(1, ticks);
        return this;
    }

    public MeteorParticle size(float size) {
        quadSize = size;
        return this;
    }

    public MeteorParticle alpha(float a) {
        alpha0 = a;
        apply(age / (float) Math.max(1, lifetime));
        return this;
    }

    /** Final size as a multiple of the start size. */
    public MeteorParticle grow(float factor) {
        growTo = factor;
        return this;
    }

    public MeteorParticle drag(float friction) {
        this.friction = friction;
        return this;
    }

    /** Upward acceleration per tick (hot gas / dust rising). */
    public MeteorParticle buoyancy(float b) {
        buoyancy = b;
        return this;
    }

    /** SMOKE only: glow for this fraction of its life before cooling to the smoke colour. */
    public MeteorParticle hot(float fraction) {
        hotFraction = fraction;
        return this;
    }

    // ------------------------------------------------------------------ simulation

    @Override
    public void tick() {
        oRoll = roll;
        roll += spin;
        yd += buoyancy;
        super.tick();
        apply(age / (float) lifetime);
    }

    private void apply(float t) {
        t = Mth.clamp(t, 0f, 1f);
        switch (kind) {
            case FIREBALL -> {
                setColor(sr, sg, sb);
                alpha = alpha0;
            }
            case SMOKE -> {
                if (hotFraction > 0 && t < hotFraction) {
                    // incandescent: white-yellow -> orange -> deep red, then smoke takes over
                    float h = t / hotFraction;
                    setColor(1f, Mth.lerp(h, 0.92f, 0.35f), Mth.lerp(h, 0.7f, 0.08f));
                } else {
                    float c = hotFraction > 0 ? Mth.clamp((t - hotFraction) / 0.15f, 0f, 1f) : 1f;
                    setColor(Mth.lerp(c, 0.55f, sr), Mth.lerp(c, 0.2f, sg), Mth.lerp(c, 0.1f, sb));
                }
                // fade in quickly, thin out slowly
                float in = Mth.clamp(t * 8f, 0f, 1f);
                alpha = alpha0 * in * (1f - t * t);
            }
            default -> {
                setColor(Mth.lerp(t, sr, er), Mth.lerp(t, sg, eg), Mth.lerp(t, sb, eb));
                alpha = alpha0 * (t < 0.5f ? 1f : (1f - t) * 2f);
            }
        }
    }

    @Override
    public float getQuadSize(float partialTick) {
        float t = Mth.clamp((age + partialTick) / lifetime, 0f, 1f);
        return switch (kind) {
            case GLOW -> quadSize * Mth.lerp(t, 1f, growTo);
            case FIREBALL -> quadSize;
            case EMBER -> quadSize * (t > 0.8f ? (1f - t) * 5f : 1f);
            case SMOKE -> quadSize * Mth.lerp((float) Math.sqrt(t), 1f, growTo);
        };
    }

    @Override
    protected int getLightCoords(float partialTick) {
        if (emissive) return FULL_BRIGHT;
        if (kind == Kind.SMOKE && hotFraction > 0 && age < lifetime * hotFraction) return FULL_BRIGHT;
        return super.getLightCoords(partialTick);
    }

    @Override
    protected Layer getLayer() {
        return Layer.TRANSLUCENT;
    }

    public static ParticleProvider<SimpleParticleType> provider(Kind kind, SpriteSet sprites) {
        return (type, level, x, y, z, xa, ya, za, random) -> new MeteorParticle(level, x, y, z, xa, ya, za, kind, sprites);
    }
}
