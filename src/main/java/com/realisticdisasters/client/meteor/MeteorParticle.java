package com.realisticdisasters.client.meteor;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;

/**
 * Self-lit meteor particles. Three flavours share one class:
 * <ul>
 *   <li>TRAIL: a glowing dot left behind by streaks and fireballs; cools from white through orange to dull red.</li>
 *   <li>FIREBALL: the bright head of a falling meteor or sky streak; lives a couple of ticks and is respawned every
 *       tick by the visual, moving with the meteor's velocity so it interpolates smoothly between ticks.</li>
 *   <li>EMBER: a spark thrown out of an impact; falls, bounces and cools.</li>
 * </ul>
 * Spawn args: xa/ya/za = velocity in blocks per tick (used as-is, no vanilla jitter).
 */
public class MeteorParticle extends SingleQuadParticle {
    public enum Kind { TRAIL, FIREBALL, EMBER }

    private static final int FULL_BRIGHT = 0xF000F0;

    private final Kind kind;
    private final float baseSize;
    private final float spin;

    protected MeteorParticle(ClientLevel level, double x, double y, double z, double xa, double ya, double za,
                             Kind kind, SpriteSet sprites) {
        super(level, x, y, z, sprites.first());
        this.kind = kind;
        setSprite(sprites.get(random));
        this.xd = xa;
        this.yd = ya;
        this.zd = za;
        switch (kind) {
            case TRAIL -> {
                hasPhysics = false;
                gravity = 0;
                friction = 0.9f;
                lifetime = 7 + random.nextInt(8);
                baseSize = 0.35f + random.nextFloat() * 0.15f;
                spin = 0;
            }
            case FIREBALL -> {
                hasPhysics = false;
                gravity = 0;
                friction = 1f;
                lifetime = 2;
                baseSize = 0.9f;
                spin = (random.nextFloat() - 0.5f) * 0.6f;
            }
            default -> {
                hasPhysics = true;
                gravity = 0.75f;
                friction = 0.97f;
                lifetime = 22 + random.nextInt(26);
                baseSize = 0.12f + random.nextFloat() * 0.12f;
                spin = (random.nextFloat() - 0.5f) * 0.4f;
            }
        }
        this.quadSize = baseSize;
        this.roll = this.oRoll = random.nextFloat() * Mth.TWO_PI;
        updateColor(0f);
    }

    @Override
    public void tick() {
        oRoll = roll;
        roll += spin;
        super.tick();
        updateColor(age / (float) lifetime);
    }

    /** Hot white core -> yellow -> orange -> dull red as the particle ages (hard steps, vanilla-like). */
    private void updateColor(float t) {
        switch (kind) {
            case FIREBALL -> setColor(1f, 0.96f, 0.82f);
            case TRAIL -> {
                if (t < 0.2f) setColor(1f, 0.95f, 0.78f);
                else if (t < 0.45f) setColor(1f, 0.74f, 0.3f);
                else if (t < 0.75f) setColor(0.95f, 0.45f, 0.16f);
                else setColor(0.6f, 0.22f, 0.12f);
                alpha = t < 0.6f ? 1f : Mth.clamp((1f - t) / 0.4f, 0f, 1f);
            }
            default -> {
                if (t < 0.3f) setColor(1f, 0.85f, 0.4f);
                else if (t < 0.7f) setColor(1f, 0.5f, 0.15f);
                else setColor(0.55f, 0.18f, 0.1f);
            }
        }
    }

    @Override
    public float getQuadSize(float partialTick) {
        float t = (age + partialTick) / lifetime;
        return switch (kind) {
            case TRAIL -> quadSize * (1f - t * 0.7f);
            case FIREBALL -> quadSize;
            default -> quadSize * (t > 0.8f ? (1f - t) * 5f : 1f);
        };
    }

    @Override
    protected int getLightCoords(float partialTick) {
        return FULL_BRIGHT;
    }

    @Override
    protected Layer getLayer() {
        return Layer.TRANSLUCENT;
    }

    public static ParticleProvider<SimpleParticleType> provider(Kind kind, SpriteSet sprites) {
        return (type, level, x, y, z, xa, ya, za, random) -> new MeteorParticle(level, x, y, z, xa, ya, za, kind, sprites);
    }
}
