package com.realisticdisasters.client.particle;

import com.realisticdisasters.client.ClientDisaster;
import com.realisticdisasters.client.ClientDisasters;
import com.realisticdisasters.disaster.windstorm.WindStormDisaster;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/**
 * A particle carried by a wind storm. Every tick it reads its storm's synced wind vector and gust, so the whole
 * cloud of flakes / grains turns and surges together. Kinds:
 * FLAKE (blizzard snow), GRAIN (sand) - small, fast, collide with terrain;
 * SNOW_HAZE / SAND_HAZE - big drifting puffs that build up the white-out / brown-out.
 *
 * Spawn args: xAux = disaster id, yAux = size multiplier, zAux = variant (1 = red sand).
 */
public class WindParticle extends SingleQuadParticle {
    public enum Kind {
        FLAKE(false), GRAIN(false), SNOW_HAZE(true), SAND_HAZE(true);

        final boolean haze;

        Kind(boolean haze) {
            this.haze = haze;
        }
    }

    private final Kind kind;
    private final int disasterId;
    private final float baseSize;
    private final float speedJitter;
    private final float lift;
    private final float phase;
    private float baseAlpha = 1f;
    private int stuck;

    protected WindParticle(ClientLevel level, double x, double y, double z, Kind kind, int disasterId, float sizeMul,
                           float variant, SpriteSet sprites) {
        super(level, x, y, z, sprites.first());
        this.kind = kind;
        this.disasterId = disasterId;
        RandomSource r = this.random;
        setSprite(sprites.get(r));
        this.gravity = 0;
        this.friction = 1f;
        this.phase = r.nextFloat() * Mth.TWO_PI;
        boolean red = variant > 0.5f;
        switch (kind) {
            case FLAKE -> {
                lifetime = 40 + r.nextInt(30);
                baseSize = (0.12f + r.nextFloat() * 0.12f) * sizeMul;
                speedJitter = 0.8f + r.nextFloat() * 0.45f;
                lift = -0.03f - r.nextFloat() * 0.06f;
                float shade = 0.9f + r.nextFloat() * 0.1f;
                setColor(shade * 0.93f, shade * 0.96f, shade);
                setSize(0.08f, 0.08f);
                baseAlpha = 0.8f + r.nextFloat() * 0.2f;
            }
            case GRAIN -> {
                lifetime = 30 + r.nextInt(25);
                baseSize = (0.08f + r.nextFloat() * 0.08f) * sizeMul;
                speedJitter = 0.9f + r.nextFloat() * 0.4f;
                lift = -0.015f + r.nextFloat() * 0.02f;
                float shade = 0.82f + r.nextFloat() * 0.18f;
                if (red) setColor(shade * 0.82f, shade * 0.46f, shade * 0.27f);
                else setColor(shade * 0.9f, shade * 0.78f, shade * 0.55f);
                setSize(0.06f, 0.06f);
                baseAlpha = 0.75f + r.nextFloat() * 0.25f;
            }
            case SNOW_HAZE -> {
                lifetime = 60 + r.nextInt(50);
                baseSize = (2.4f + r.nextFloat() * 2.6f) * sizeMul;
                speedJitter = 0.55f + r.nextFloat() * 0.3f;
                lift = -0.01f + r.nextFloat() * 0.015f;
                float shade = 0.86f + r.nextFloat() * 0.1f;
                setColor(shade * 0.96f, shade * 0.98f, shade);
                baseAlpha = 0.55f + r.nextFloat() * 0.3f;
            }
            default -> {
                lifetime = 60 + r.nextInt(50);
                baseSize = (2.4f + r.nextFloat() * 2.8f) * sizeMul;
                speedJitter = 0.5f + r.nextFloat() * 0.3f;
                lift = -0.005f + r.nextFloat() * 0.015f;
                float shade = 0.8f + r.nextFloat() * 0.15f;
                if (red) setColor(shade * 0.84f, shade * 0.52f, shade * 0.33f);
                else setColor(shade * 0.9f, shade * 0.75f, shade * 0.52f);
                baseAlpha = 0.6f + r.nextFloat() * 0.3f;
            }
        }
        this.hasPhysics = !kind.haze;
        this.quadSize = baseSize;
        // Flakes / grains use streak textures smeared along X: keep them near-horizontal like the wind.
        this.roll = this.oRoll = kind.haze ? r.nextFloat() * Mth.TWO_PI : (r.nextFloat() - 0.5f) * 0.35f;
        this.alpha = 0f;
        // Start already moving so the first frames don't show a still particle.
        ClientDisaster d = ClientDisasters.get(disasterId);
        if (d != null) {
            float speed = d.data(WindStormDisaster.D_WIND_SPEED) * d.data(WindStormDisaster.D_GUST) * speedJitter;
            xd = d.data(WindStormDisaster.D_WIND_X) * speed;
            zd = d.data(WindStormDisaster.D_WIND_Z) * speed;
        }
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
        ClientDisaster d = ClientDisasters.get(disasterId);
        if (d != null) {
            float gust = d.data(WindStormDisaster.D_GUST);
            float speed = d.data(WindStormDisaster.D_WIND_SPEED) * gust * speedJitter;
            double wx = d.data(WindStormDisaster.D_WIND_X), wz = d.data(WindStormDisaster.D_WIND_Z);
            // Turbulence: a cross-wind wobble and vertical swirl, different per particle.
            float t = age * (kind.haze ? 0.05f : 0.25f) + phase;
            double cross = Mth.sin(t) * speed * (kind.haze ? 0.12 : 0.3);
            double tx = wx * speed - wz * cross;
            double tz = wz * speed + wx * cross;
            double ty = lift + Mth.cos(t * 1.3f) * (kind.haze ? 0.01 : 0.05) * gust;
            double k = kind.haze ? 0.08 : 0.25;
            xd += (tx - xd) * k;
            yd += (ty - yd) * k;
            zd += (tz - zd) * k;
        } else {
            // Storm gone: settle and fade.
            xd *= 0.9;
            zd *= 0.9;
            if (age < lifetime - 10) age = lifetime - 10;
        }
        move(xd, yd, zd);
        if (!kind.haze) {
            // Flakes / grains that hit the ground or a wall linger briefly, then vanish.
            if (onGround || xd == 0 || zd == 0) stuck++;
            if (stuck > 6) remove();
        } else {
            roll += 0.01f;
        }
    }

    @Override
    protected void extractRotatedQuad(net.minecraft.client.renderer.state.level.QuadParticleRenderState state,
                                      org.joml.Quaternionf rotation, float x, float y, float z, float partialTick) {
        float t = (age + partialTick) / lifetime;
        float fade = Mth.clamp(t * (kind.haze ? 5f : 10f), 0f, 1f) * Mth.clamp((1f - t) * (kind.haze ? 4f : 6f), 0f, 1f);
        this.alpha = baseAlpha * fade;
        this.quadSize = baseSize * (0.7f + 0.3f * fade);
        super.extractRotatedQuad(state, rotation, x, y, z, partialTick);
    }

    @Override
    protected Layer getLayer() {
        return Layer.TRANSLUCENT; // soft alpha textures (SPEC §1)
    }

    public static ParticleProvider<SimpleParticleType> provider(Kind kind, SpriteSet sprites) {
        return (type, level, x, y, z, xa, ya, za, random) ->
                new WindParticle(level, x, y, z, kind, (int) xa, (float) ya <= 0 ? 1f : (float) ya, (float) za, sprites);
    }
}
