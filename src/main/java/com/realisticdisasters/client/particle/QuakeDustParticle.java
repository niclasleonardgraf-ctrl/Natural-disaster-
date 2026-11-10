package com.realisticdisasters.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/**
 * A billowing puff of rock dust kicked up by an earthquake. It drifts with its spawn velocity, slows down, swells
 * and settles. Unlike the tornado's vortex particles it is not bound to its disaster, so it keeps hanging in the
 * air after the shaking stops.
 *
 * Spawn args: xAux / yAux / zAux = initial velocity.
 */
public class QuakeDustParticle extends SingleQuadParticle {
    private final float baseSize;
    private final float spin;

    protected QuakeDustParticle(ClientLevel level, double x, double y, double z, double xd, double yd, double zd, SpriteSet sprites) {
        super(level, x, y, z, sprites.first());
        RandomSource r = this.random;
        setSprite(sprites.get(r));
        this.xd = xd;
        this.yd = yd;
        this.zd = zd;
        this.hasPhysics = true;
        this.gravity = -0.002f;       // dust rises very slightly while it's warm, then hangs
        this.friction = 0.93f;
        this.lifetime = 40 + r.nextInt(50);
        this.baseSize = 0.45f + r.nextFloat() * 0.9f;
        this.quadSize = baseSize;
        this.spin = (r.nextFloat() - 0.5f) * 0.03f;
        this.roll = this.oRoll = r.nextFloat() * Mth.TWO_PI;
        float shade = 0.72f + r.nextFloat() * 0.22f;
        setColor(shade, shade * 0.95f, shade * 0.88f);
    }

    @Override
    public void tick() {
        oRoll = roll;
        roll += spin;
        super.tick();
        // Slow sideways drift so clouds spread out instead of stacking.
        xd += (random.nextFloat() - 0.5f) * 0.004f;
        zd += (random.nextFloat() - 0.5f) * 0.004f;
    }

    @Override
    public float getQuadSize(float partialTick) {
        float t = (age + partialTick) / lifetime;
        float grow = 0.45f + 0.55f * Mth.clamp(t * 3f, 0f, 1f);   // billows out
        float shrink = Mth.clamp((1f - t) * 5f, 0f, 1f);          // settles away at the end
        return quadSize * grow * shrink;
    }

    @Override
    protected Layer getLayer() {
        return Layer.OPAQUE;
    }

    public static ParticleProvider<SimpleParticleType> provider(SpriteSet sprites) {
        return (type, level, x, y, z, xa, ya, za, random) -> new QuakeDustParticle(level, x, y, z, xa, ya, za, sprites);
    }
}
