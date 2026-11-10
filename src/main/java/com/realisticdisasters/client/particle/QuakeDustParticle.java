package com.realisticdisasters.client.particle;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * Soft, translucent rock and soil dust from an earthquake. Two behaviours share the code:
 * <ul>
 *   <li><b>Ground dust</b> ({@code quake_dust}): low puffs shaken out of loose ground or spurting from a fissure,
 *       short-lived.</li>
 *   <li><b>Collapse dust</b> ({@code collapse_dust}): the cloud where a structure or rock face comes down. Like in
 *       real footage, it bursts out, billows upward and rolls outward low and wide, keeps growing, and hangs in the
 *       air for many seconds before thinning out.</li>
 * </ul>
 * Not bound to its disaster, so dust keeps hanging after the shaking stops. Spawn args: initial velocity.
 */
public class QuakeDustParticle extends SingleQuadParticle {
    private final float spin;
    private final float peakAlpha;
    private final float growth;

    protected QuakeDustParticle(ClientLevel level, double x, double y, double z, double xd, double yd, double zd,
                                SpriteSet sprites, boolean collapse) {
        super(level, x, y, z, sprites.first());
        RandomSource r = this.random;
        setSprite(sprites.get(r));
        this.xd = xd;
        this.yd = yd;
        this.zd = zd;
        this.hasPhysics = true;
        if (collapse) {
            this.gravity = -0.0008f;          // the cloud is buoyant at first, then just hangs
            this.friction = 0.955f;           // the outward rush slows down quickly
            this.lifetime = 140 + r.nextInt(140);
            this.quadSize = 1.2f + r.nextFloat() * 1.4f;
            this.growth = 2.2f + r.nextFloat() * 1.3f;
            this.peakAlpha = 0.45f + r.nextFloat() * 0.3f;
        } else {
            this.gravity = -0.0015f;
            this.friction = 0.94f;
            this.lifetime = 70 + r.nextInt(70);
            this.quadSize = 0.9f + r.nextFloat() * 1.5f;
            this.growth = 1.6f;
            this.peakAlpha = 0.3f + r.nextFloat() * 0.3f;
        }
        // Distance LOD: far away fewer particles are spawned, so each one is bigger to keep the cloud readable.
        double camDist = Minecraft.getInstance().gameRenderer.getMainCamera().position().distanceTo(new Vec3(x, y, z));
        this.quadSize *= 1f + Mth.clamp((float) (camDist - 24) / 30f, 0f, 3f);
        setPos(x, y + quadSize * 0.5, z); // sit on the ground instead of half inside it
        this.spin = (r.nextFloat() - 0.5f) * 0.02f;
        this.roll = this.oRoll = r.nextFloat() * Mth.TWO_PI;
        // Desaturated grey-beige (stone, mortar, soil); collapse clouds are a little paler, some puffs earthier.
        float shade = (collapse ? 0.6f : 0.5f) + r.nextFloat() * 0.2f;
        float soil = r.nextFloat() < 0.3f ? 0.85f : 1f;
        setColor(shade * 0.92f * soil, shade * 0.88f * soil, shade * 0.80f * soil);
        setAlpha(0f);
    }

    @Override
    public void tick() {
        oRoll = roll;
        roll += spin;
        super.tick();
        // Turbulent drift so clouds spread and churn instead of stacking.
        xd += (random.nextFloat() - 0.5f) * 0.004f;
        zd += (random.nextFloat() - 0.5f) * 0.004f;
        float t = (float) age / lifetime;
        // Quick fade in, long fade out as the dust thins.
        setAlpha(peakAlpha * Mth.clamp(t * 8f, 0f, 1f) * Mth.clamp((1f - t) * 1.6f, 0f, 1f));
    }

    @Override
    public float getQuadSize(float partialTick) {
        float t = (age + partialTick) / lifetime;
        // Billows out fast, then keeps slowly expanding.
        float g = 1f - (float) Math.exp(-t * 4f);
        return quadSize * (0.4f + (growth - 0.4f) * g);
    }

    @Override
    protected int getLightCoords(float a) {
        int lc = super.getLightCoords(a);
        return LightCoordsUtil.pack(Math.max(LightCoordsUtil.block(lc), 6), LightCoordsUtil.sky(lc));
    }

    @Override
    protected Layer getLayer() {
        return Layer.TRANSLUCENT;
    }

    public static ParticleProvider<SimpleParticleType> provider(SpriteSet sprites) {
        return (type, level, x, y, z, xa, ya, za, random) -> new QuakeDustParticle(level, x, y, z, xa, ya, za, sprites, false);
    }

    public static ParticleProvider<SimpleParticleType> collapseProvider(SpriteSet sprites) {
        return (type, level, x, y, z, xa, ya, za, random) -> new QuakeDustParticle(level, x, y, z, xa, ya, za, sprites, true);
    }
}
