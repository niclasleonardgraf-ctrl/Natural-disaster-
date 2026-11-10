package com.realisticdisasters.client.tsunami;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/**
 * Tsunami particles. The spawn args are the real initial velocity (no random jitter), so the visual can make a whole
 * curtain of particles travel with the wave front.
 * <ul>
 *   <li>{@link Kind#WATER}: big, short-lived blue-green puffs that together form the body of the wave wall.</li>
 *   <li>{@link Kind#FOAM}: small white droplets thrown off the breaking crest; they fall under gravity.</li>
 *   <li>{@link Kind#MIST}: large pale spray clouds that hang above the crest and drift inland.</li>
 * </ul>
 */
public class WaveParticle extends SingleQuadParticle {
    public enum Kind { WATER, FOAM, MIST }

    private final Kind kind;
    private final float growTime;

    protected WaveParticle(ClientLevel level, double x, double y, double z, double xd, double yd, double zd, Kind kind, SpriteSet sprites) {
        super(level, x, y, z, sprites.first());
        this.kind = kind;
        RandomSource r = this.random;
        setSprite(sprites.get(r));
        this.xd = xd;
        this.yd = yd;
        this.zd = zd;
        switch (kind) {
            case WATER -> {
                lifetime = 12 + r.nextInt(12);
                gravity = 0.12f;
                friction = 0.93f;
                hasPhysics = false;
                quadSize = 1.4f + r.nextFloat() * 1.6f;
                float shade = 0.82f + r.nextFloat() * 0.18f;
                setColor(shade, shade, shade);
                growTime = 0.15f;
            }
            case FOAM -> {
                lifetime = 14 + r.nextInt(18);
                gravity = 0.55f;
                friction = 0.97f;
                hasPhysics = true;
                quadSize = 0.14f + r.nextFloat() * 0.22f;
                float shade = 0.9f + r.nextFloat() * 0.1f;
                setColor(shade * 0.96f, shade, shade);
                growTime = 0.05f;
            }
            default -> {
                lifetime = 30 + r.nextInt(40);
                gravity = -0.015f;
                friction = 0.95f;
                hasPhysics = false;
                quadSize = 1.0f + r.nextFloat() * 1.6f;
                float shade = 0.86f + r.nextFloat() * 0.14f;
                setColor(shade, shade, shade);
                growTime = 0.3f;
            }
        }
        this.roll = this.oRoll = r.nextFloat() * Mth.TWO_PI;
    }

    @Override
    public void tick() {
        super.tick();
        oRoll = roll;
        roll += kind == Kind.FOAM ? 0.2f : 0.02f;
        // Droplets that land in water or on the ground just vanish.
        if (kind == Kind.FOAM && (onGround || !level.getFluidState(net.minecraft.core.BlockPos.containing(x, y, z)).isEmpty())) {
            remove();
        }
    }

    @Override
    public float getQuadSize(float partialTick) {
        float t = (age + partialTick) / lifetime;
        float grow = Mth.clamp(t / growTime, 0f, 1f);
        float shrink = Mth.clamp((1f - t) * 4f, 0f, 1f);
        return quadSize * (0.4f + 0.6f * grow) * shrink;
    }

    @Override
    protected Layer getLayer() {
        return Layer.OPAQUE;
    }

    public static ParticleProvider<SimpleParticleType> provider(Kind kind, SpriteSet sprites) {
        return (type, level, x, y, z, xa, ya, za, random) -> new WaveParticle(level, x, y, z, xa, ya, za, kind, sprites);
    }
}
