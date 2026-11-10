package com.realisticdisasters.client.volcano;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;

/**
 * Incandescent lava spatter (fountain clots, bomb sparks, blast ejecta). It flies ballistically and cools on the way:
 * yellow-white when it leaves the vent, then orange, red and finally a dark crust, like real Strombolian ejecta.
 * Self-lit while hot. Lands and briefly glows on the ground.
 */
public class SparkParticle extends SingleQuadParticle {
    static SpriteSet sprites;

    private final float baseSize;
    /** Starting temperature 0..1 (1 = fresh from the vent) and how fast it cools per tick. */
    private float heat;
    private float cooling;

    public SparkParticle(ClientLevel level, double x, double y, double z, double xd, double yd, double zd,
                         float size, float heat, int lifetime, SpriteSet set) {
        super(level, x, y, z, set.first());
        setSprite(set.get(random));
        this.xd = xd;
        this.yd = yd;
        this.zd = zd;
        this.baseSize = this.quadSize = size;
        this.heat = heat;
        this.lifetime = lifetime;
        this.cooling = heat / Math.max(1, lifetime) * (0.8f + random.nextFloat() * 0.5f);
        this.gravity = 1f;        // 0.04 blocks/tick², same as the lava bombs
        this.friction = 0.985f;
        this.hasPhysics = true;
        this.roll = this.oRoll = random.nextFloat() * Mth.TWO_PI;
        applyColor();
    }

    /** Slower cooling: fountain clots stay bright orange for their whole flight. */
    public SparkParticle coolingScale(float k) {
        cooling *= k;
        return this;
    }

    @Override
    public void tick() {
        super.tick();
        heat = Math.max(0f, heat - cooling * (onGround ? 2.5f : 1f));
        if (onGround) {
            xd *= 0.5;
            zd *= 0.5;
        }
        applyColor();
    }

    private void applyColor() {
        // Blackbody-ish ramp: yellow-white -> orange -> red -> dark crust.
        float t = heat;
        float r, g, b;
        if (t > 0.75f) {
            float k = (t - 0.75f) / 0.25f;
            r = 1f;
            g = Mth.lerp(k, 0.62f, 0.92f);
            b = Mth.lerp(k, 0.16f, 0.6f);
        } else if (t > 0.45f) {
            float k = (t - 0.45f) / 0.3f;
            r = 1f;
            g = Mth.lerp(k, 0.3f, 0.62f);
            b = Mth.lerp(k, 0.05f, 0.16f);
        } else if (t > 0.18f) {
            float k = (t - 0.18f) / 0.27f;
            r = Mth.lerp(k, 0.45f, 1f);
            g = Mth.lerp(k, 0.08f, 0.3f);
            b = Mth.lerp(k, 0.04f, 0.05f);
        } else {
            float k = t / 0.18f;
            r = Mth.lerp(k, 0.16f, 0.45f);
            g = Mth.lerp(k, 0.12f, 0.08f);
            b = Mth.lerp(k, 0.1f, 0.04f);
        }
        setColor(r, g, b);
        float end = Mth.clamp((1f - (float) age / lifetime) * 4f, 0f, 1f);
        setAlpha(end);
    }

    @Override
    public float getQuadSize(float partialTick) {
        return baseSize * (0.7f + 0.3f * heat);
    }

    @Override
    protected int getLightCoords(float a) {
        int lc = super.getLightCoords(a);
        int glow = (int) (15 * Mth.clamp(heat * 1.6f, 0f, 1f));
        return LightCoordsUtil.pack(Math.max(LightCoordsUtil.block(lc), glow), Math.max(LightCoordsUtil.sky(lc), glow));
    }

    @Override
    protected Layer getLayer() {
        return Layer.TRANSLUCENT;
    }

    public static ParticleProvider<SimpleParticleType> provider(SpriteSet set) {
        sprites = set;
        return (type, level, x, y, z, xa, ya, za, random) ->
                new SparkParticle(level, x, y, z, xa, ya, za, 0.15f + random.nextFloat() * 0.15f, 1f, 30 + random.nextInt(30), set);
    }
}
