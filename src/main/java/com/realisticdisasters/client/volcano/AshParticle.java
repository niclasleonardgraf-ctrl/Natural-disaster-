package com.realisticdisasters.client.volcano;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/**
 * Volcanic ash in three flavours:
 * PLUME (huge, slowly rising and spreading eruption-column puffs), TRAIL (lava-bomb smoke) and FALL (small flakes
 * drifting down around the player). Motion comes from the spawn velocity, so the visual controls the shape.
 */
public class AshParticle extends SingleQuadParticle {
    public enum Kind { PLUME, TRAIL, FALL }

    private final Kind kind;
    private final float baseSize;
    private final float sway;
    private final float baseAlpha;
    private final int minBlockLight;

    protected AshParticle(ClientLevel level, double x, double y, double z, double xd, double yd, double zd,
                          Kind kind, SpriteSet sprites) {
        super(level, x, y, z, sprites.first());
        this.kind = kind;
        this.xd = xd;
        this.yd = yd;
        this.zd = zd;
        RandomSource r = this.random;
        setSprite(sprites.get(r));
        this.gravity = 0;
        this.hasPhysics = false;
        switch (kind) {
            case PLUME -> {
                lifetime = 90 + r.nextInt(70);
                friction = 0.985f;
                baseSize = 4.0f + r.nextFloat() * 5.5f;
                // desaturated, slightly warm grey-brown ash
                float shade = 0.36f + r.nextFloat() * 0.22f;
                setColor(shade, shade * 0.95f, shade * 0.9f);
                baseAlpha = 0.85f + r.nextFloat() * 0.15f;
                minBlockLight = 5;
            }
            case TRAIL -> {
                lifetime = 25 + r.nextInt(25);
                friction = 0.92f;
                baseSize = 0.7f + r.nextFloat() * 0.9f;
                float shade = 0.22f + r.nextFloat() * 0.18f;
                setColor(shade, shade * 0.97f, shade * 0.95f);
                baseAlpha = 0.6f + r.nextFloat() * 0.25f;
                minBlockLight = 4;
            }
            default -> {
                lifetime = 120 + r.nextInt(80);
                friction = 1f;
                hasPhysics = true;
                baseSize = 0.06f + r.nextFloat() * 0.06f;
                float shade = 0.55f + r.nextFloat() * 0.25f;
                setColor(shade, shade * 0.98f, shade * 0.95f);
                baseAlpha = 0.75f + r.nextFloat() * 0.25f;
                minBlockLight = 0;
            }
        }
        setAlpha(baseAlpha);
        this.sway = r.nextFloat() * Mth.TWO_PI;
        this.quadSize = baseSize;
        this.roll = this.oRoll = r.nextFloat() * Mth.TWO_PI;
    }

    @Override
    public void tick() {
        xo = x;
        yo = y;
        zo = z;
        oRoll = roll;
        if (age++ >= lifetime) {
            remove();
            return;
        }
        switch (kind) {
            case PLUME -> {
                yd *= 0.975;
                roll += 0.004f;
            }
            case TRAIL -> {
                yd = yd * 0.9 + 0.012;
            }
            default -> {
                // Flakes flutter as they fall.
                double t = age * 0.12 + sway;
                xd = xd * 0.9 + Math.sin(t) * 0.006;
                zd = zd * 0.9 + Math.cos(t * 0.8) * 0.006;
                if (onGround) {
                    remove();
                    return;
                }
            }
        }
        move(xd, yd, zd);
        // Thin out towards the end instead of popping.
        float t = (float) age / lifetime;
        alpha = baseAlpha * Mth.clamp((1f - t) * 3f, 0f, 1f);
        xd *= friction;
        zd *= friction;
    }

    @Override
    public float getQuadSize(float partialTick) {
        float t = (age + partialTick) / lifetime;
        float grow = kind == Kind.FALL ? 1f : Mth.clamp(t * 5f, 0f, 1f) * (1f + t * (kind == Kind.PLUME ? 0.9f : 1.5f));
        return quadSize * grow;
    }

    @Override
    protected int getLightCoords(float a) {
        int lc = super.getLightCoords(a);
        return LightCoordsUtil.pack(Math.max(LightCoordsUtil.block(lc), minBlockLight), LightCoordsUtil.sky(lc));
    }

    @Override
    protected Layer getLayer() {
        return Layer.TRANSLUCENT;
    }

    @Override
    public Particle scale(float scale) {
        return super.scale(scale);
    }

    public static ParticleProvider<SimpleParticleType> provider(Kind kind, SpriteSet sprites) {
        return (type, level, x, y, z, xa, ya, za, random) -> new AshParticle(level, x, y, z, xa, ya, za, kind, sprites);
    }
}
