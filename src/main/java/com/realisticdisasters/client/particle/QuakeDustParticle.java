package com.realisticdisasters.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/**
 * A soft, translucent billow of rock dust kicked up by an earthquake. It drifts with its spawn velocity, slows down,
 * swells and fades. Many overlapping puffs build up a dust cloud with real volume. Unlike the tornado's vortex
 * particles it is not bound to its disaster, so the dust keeps hanging in the air after the shaking stops.
 *
 * Spawn args: xAux / yAux / zAux = initial velocity.
 */
public class QuakeDustParticle extends SingleQuadParticle {
    private final float spin;
    private final float peakAlpha;

    protected QuakeDustParticle(ClientLevel level, double x, double y, double z, double xd, double yd, double zd, SpriteSet sprites) {
        super(level, x, y, z, sprites.first());
        RandomSource r = this.random;
        setSprite(sprites.get(r));
        this.xd = xd;
        this.yd = yd;
        this.zd = zd;
        this.hasPhysics = true;
        this.gravity = -0.0015f;      // warm dust lifts very slightly, then hangs
        this.friction = 0.94f;
        this.lifetime = 70 + r.nextInt(70);
        this.quadSize = 0.9f + r.nextFloat() * 1.5f;
        // Distance LOD: far away fewer particles are spawned, so each one is bigger to keep the cloud readable.
        double camDist = net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera().position().distanceTo(new net.minecraft.world.phys.Vec3(x, y, z));
        this.quadSize *= 1f + Mth.clamp((float) (camDist - 24) / 30f, 0f, 3f);
        setPos(x, y + quadSize * 0.5, z); // sit on the ground instead of half inside it
        this.spin = (r.nextFloat() - 0.5f) * 0.02f;
        this.roll = this.oRoll = r.nextFloat() * Mth.TWO_PI;
        // Desaturated grey-brown rock and soil dust; some darker, earthier clumps.
        float shade = 0.5f + r.nextFloat() * 0.22f;
        float soil = r.nextFloat() < 0.3f ? 0.8f : 1f;
        setColor(shade * 0.86f * soil, shade * 0.80f * soil, shade * 0.71f * soil);
        this.peakAlpha = 0.35f + r.nextFloat() * 0.3f;
        setAlpha(0f);
    }

    @Override
    public void tick() {
        oRoll = roll;
        roll += spin;
        super.tick();
        // Slow sideways drift so clouds spread out instead of stacking.
        xd += (random.nextFloat() - 0.5f) * 0.004f;
        zd += (random.nextFloat() - 0.5f) * 0.004f;
        float t = (float) age / lifetime;
        // Quick fade in, long fade out as the dust thins.
        setAlpha(peakAlpha * Mth.clamp(t * 8f, 0f, 1f) * Mth.clamp((1f - t) * 1.6f, 0f, 1f));
    }

    @Override
    public float getQuadSize(float partialTick) {
        float t = (age + partialTick) / lifetime;
        return quadSize * (0.4f + 0.6f * Mth.clamp(t * 2.5f, 0f, 1f)); // billows out
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
        return (type, level, x, y, z, xa, ya, za, random) -> new QuakeDustParticle(level, x, y, z, xa, ya, za, sprites);
    }
}
