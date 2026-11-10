package com.realisticdisasters.client.volcano;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import org.joml.Quaternionf;

/**
 * Soft, translucent volcanic ash and gas, modelled on real eruption footage:
 * <ul>
 *   <li>PLUME: convective billows of the eruption column. They rise on the column's updraft, slow as they lose
 *   buoyancy, swell as they entrain air, bend downwind aloft and slowly churn.</li>
 *   <li>UMBRELLA: the cloud that spreads sideways at neutral buoyancy and drifts far downwind.</li>
 *   <li>GLOW: billows near the vent lit orange from below by the lava fountain; they cool to ash as they rise.</li>
 *   <li>CURTAIN: upright streaky fall-out shafts hanging under the drifting umbrella cloud.</li>
 *   <li>STEAM: pale fumes rising off fresh lava.</li>
 *   <li>TRAIL: smoke shed by flying lava bombs.</li>
 *   <li>FALL: fine ash flakes drifting down around the viewer.</li>
 * </ul>
 * The volcano visual spawns these directly (to give each puff its own size, colour and motion); the registered
 * providers load the sprite sets and still allow spawning plain puffs.
 */
public class AshParticle extends SingleQuadParticle {
    public enum Kind { PLUME, UMBRELLA, GLOW, CURTAIN, STEAM, TRAIL, FALL }

    static SpriteSet cloudSprites, curtainSprites, flakeSprites;

    private final Kind kind;
    private float baseSize;
    private float growth = 1f;
    private float baseAlpha = 1f;
    private int minBlockLight;
    private double updraft;
    private double windX, windZ;
    private final float spinRate;
    private final float sway;
    private float r0, g0, b0;

    public AshParticle(ClientLevel level, double x, double y, double z, double xd, double yd, double zd,
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
        this.friction = 1f;
        this.sway = r.nextFloat() * Mth.TWO_PI;
        // Billows stay nearly upright so their top-lit shading reads as one sun lighting the whole cloud
        // (bright crowns, dark undersides) instead of swirling like a vortex.
        boolean lit = kind == Kind.PLUME || kind == Kind.UMBRELLA || kind == Kind.GLOW || kind == Kind.CURTAIN;
        this.roll = this.oRoll = lit ? (kind == Kind.CURTAIN ? 0f : (r.nextFloat() - 0.5f) * 0.5f) : r.nextFloat() * Mth.TWO_PI;
        this.spinRate = (r.nextFloat() - 0.5f) * (lit ? 0.002f : 0.01f);
        this.updraft = kind == Kind.CURTAIN ? -0.12 : 0;
        switch (kind) {
            case PLUME, GLOW -> defaults(110 + r.nextInt(60), 5f + r.nextFloat() * 4f, 1.8f, 0.3f, 0.95f, 5);
            case UMBRELLA -> defaults(260 + r.nextInt(120), 9f + r.nextFloat() * 6f, 2.2f, 0.42f, 0.9f, 4);
            case CURTAIN -> defaults(90 + r.nextInt(40), 12f + r.nextFloat() * 8f, 1.2f, 0.3f, 0.28f, 3);
            case STEAM -> defaults(40 + r.nextInt(40), 0.8f + r.nextFloat() * 1.2f, 2.5f, 0.78f, 0.35f, 6);
            case TRAIL -> defaults(25 + r.nextInt(25), 0.6f + r.nextFloat() * 0.7f, 2.2f, 0.25f, 0.7f, 4);
            case FALL -> {
                defaults(120 + r.nextInt(80), 0.05f + r.nextFloat() * 0.05f, 1f, 0.6f, 0.85f, 0);
                hasPhysics = true;
            }
        }
    }

    private void defaults(int life, float size, float grow, float shade, float alpha, int light) {
        lifetime = life;
        baseSize = quadSize = size;
        growth = grow;
        float s = shade * (0.85f + random.nextFloat() * 0.3f);
        tint(s, s * 0.93f, s * 0.86f); // warm grey-brown, like sunlit ash
        baseAlpha = alpha;
        setAlpha(alpha);
        minBlockLight = light;
    }

    // ------------------------------------------------------------------ configuration (used by the visual)

    public AshParticle tint(float r, float g, float b) {
        r0 = r;
        g0 = g;
        b0 = b;
        setColor(r, g, b);
        return this;
    }

    public AshParticle size(float size, float grow) {
        baseSize = quadSize = size;
        growth = grow;
        return this;
    }

    public AshParticle life(int ticks) {
        lifetime = ticks;
        return this;
    }

    public AshParticle opacity(float alpha) {
        baseAlpha = alpha;
        setAlpha(alpha);
        return this;
    }

    /** Vertical speed the puff relaxes towards (the column's updraft at its height). */
    public AshParticle updraft(double up) {
        updraft = up;
        return this;
    }

    /** Horizontal wind velocity the puff relaxes towards. */
    public AshParticle wind(double x, double z) {
        windX = x;
        windZ = z;
        return this;
    }

    public AshParticle light(int minBlock) {
        minBlockLight = minBlock;
        return this;
    }

    /** Radius for frustum culling by extent (see QuadParticleGroupMixin); 0 = vanilla point culling. */
    public float cullRadius() {
        return baseSize > 2.5f ? baseSize * growth : 0f;
    }

    // ------------------------------------------------------------------ simulation

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
        float t = (float) age / lifetime;
        switch (kind) {
            case PLUME, GLOW -> {
                // Buoyant rise that weakens with age; drag pulls the puff toward the ambient wind.
                double up = updraft * (1.0 - 0.6 * t);
                yd += (up - yd) * 0.06;
                xd += (windX - xd) * 0.03;
                zd += (windZ - zd) * 0.03;
                roll += spinRate;
                if (kind == Kind.GLOW) {
                    // Lava light fades as the puff rises away from the fountain.
                    float hot = Mth.clamp(1f - t * 1.6f, 0f, 1f);
                    float ash = 0.27f;
                    setColor(Mth.lerp(hot, ash, r0), Mth.lerp(hot, ash * 0.93f, g0), Mth.lerp(hot, ash * 0.86f, b0));
                    minBlockLight = (int) (4 + 11 * hot);
                }
            }
            case UMBRELLA -> {
                // Gravity current: fast radial spread that slows, then drifts with the upper-level wind.
                yd += (updraft - yd) * 0.05;
                xd += (windX - xd) * 0.012;
                zd += (windZ - zd) * 0.012;
                roll += spinRate * 0.5f;
            }
            case CURTAIN -> {
                yd += (updraft - yd) * 0.05;
                xd += (windX - xd) * 0.05;
                zd += (windZ - zd) * 0.05;
            }
            case STEAM -> {
                yd = yd * 0.94 + 0.012;
                xd = xd * 0.95 + windX * 0.05;
                zd = zd * 0.95 + windZ * 0.05;
                roll += spinRate * 2;
            }
            case TRAIL -> yd = yd * 0.9 + 0.012;
            case FALL -> {
                double s = age * 0.12 + sway;
                xd = xd * 0.9 + Math.sin(s) * 0.006 + windX * 0.1;
                zd = zd * 0.9 + Math.cos(s * 0.8) * 0.006 + windZ * 0.1;
                if (onGround) {
                    remove();
                    return;
                }
            }
        }
        move(xd, yd, zd);
        // Fade in, thin out at the end instead of popping.
        float in = Mth.clamp(t * (kind == Kind.CURTAIN || kind == Kind.UMBRELLA ? 5f : 10f), 0f, 1f);
        alpha = baseAlpha * in * Mth.clamp((1f - t) * 3f, 0f, 1f);
    }

    @Override
    public float getQuadSize(float partialTick) {
        float t = Mth.clamp((age + partialTick) / lifetime, 0f, 1f);
        // Entrainment: puffs keep swelling (fast at first).
        return baseSize * (1f + (growth - 1f) * (float) Math.sqrt(t));
    }

    @Override
    public void extract(QuadParticleRenderState state, Camera camera, float partialTick) {
        if (kind == Kind.CURTAIN) {
            // Fall-out shafts stay upright: turn only around the vertical axis.
            Quaternionf q = new Quaternionf();
            FacingCameraMode.LOOKAT_Y.setRotation(q, camera, partialTick);
            extractRotatedQuad(state, camera, q, partialTick);
            return;
        }
        super.extract(state, camera, partialTick);
    }

    @Override
    protected int getLightCoords(float a) {
        int lc = super.getLightCoords(a);
        return LightCoordsUtil.pack(Math.max(LightCoordsUtil.block(lc), minBlockLight), LightCoordsUtil.sky(lc));
    }

    @Override
    protected Layer getLayer() {
        return SmokeLayer.LAYER;
    }

    public static ParticleProvider<SimpleParticleType> provider(Kind kind, SpriteSet sprites) {
        switch (kind) {
            case CURTAIN -> curtainSprites = sprites;
            case FALL -> flakeSprites = sprites;
            case PLUME -> cloudSprites = sprites;
            default -> {
            }
        }
        return (type, level, x, y, z, xa, ya, za, random) -> new AshParticle(level, x, y, z, xa, ya, za, kind, sprites);
    }
}
