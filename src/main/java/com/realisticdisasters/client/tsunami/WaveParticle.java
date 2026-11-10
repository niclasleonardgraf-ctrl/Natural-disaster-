package com.realisticdisasters.client.tsunami;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/**
 * Tsunami particles, drawn with soft translucent 64x64 textures so many of them merge into one turbulent mass.
 * The spawn args are the real initial velocity (no random jitter), so the visual can make a whole front travel
 * with the wave.
 * <ul>
 *   <li>{@link Kind#WATER}: churning white water of the bore and the dark, sediment-laden water behind it
 *       (the colour is set by the visual through {@link #tint}).</li>
 *   <li>{@link Kind#FOAM}: spray droplets thrown up where the flow hits something; they fall under gravity.</li>
 *   <li>{@link Kind#MIST}: fine spray haze that hangs over the front and drifts with the wind.</li>
 *   <li>{@link Kind#DEBRIS}: wood, twigs and mud clumps riding on the flood surface.</li>
 * </ul>
 */
public class WaveParticle extends SingleQuadParticle {
    public enum Kind { WATER, FOAM, MIST, DEBRIS }

    /**
     * Colour for the next WATER particle (0xRRGGBB). Particles are spawned on the client thread right after the
     * visual sets this, so a static hand-off is safe and keeps the vanilla provider signature.
     */
    static int tint = 0xFFFFFF;
    static float sizeScale = 1f;

    private final Kind kind;
    private final float growTime;
    private final float baseAlpha;
    private final float spin;

    protected WaveParticle(ClientLevel level, double x, double y, double z, double xd, double yd, double zd, Kind kind, SpriteSet sprites) {
        super(level, x, y, z, sprites.first());
        this.kind = kind;
        RandomSource r = this.random;
        setSprite(sprites.get(r));
        this.xd = xd;
        this.yd = yd;
        this.zd = zd;
        float shade = 0.9f + r.nextFloat() * 0.1f;
        switch (kind) {
            case WATER -> {
                lifetime = 12 + r.nextInt(12);
                gravity = 0.08f;
                friction = 0.92f;
                hasPhysics = false;
                quadSize = (1.1f + r.nextFloat() * 1.3f) * sizeScale;
                setColor(((tint >> 16) & 255) / 255f * shade, ((tint >> 8) & 255) / 255f * shade, (tint & 255) / 255f * shade);
                baseAlpha = 0.92f + r.nextFloat() * 0.08f;
                growTime = 0.2f;
                spin = (r.nextFloat() - 0.5f) * 0.12f;
            }
            case FOAM -> {
                lifetime = 18 + r.nextInt(20);
                gravity = 0.45f;
                friction = 0.97f;
                hasPhysics = true;
                quadSize = (0.22f + r.nextFloat() * 0.3f) * sizeScale;
                setColor(shade, shade, shade);
                baseAlpha = 0.8f;
                growTime = 0.08f;
                spin = (r.nextFloat() - 0.5f) * 0.3f;
            }
            case MIST -> {
                lifetime = 40 + r.nextInt(50);
                gravity = -0.01f;
                friction = 0.95f;
                hasPhysics = false;
                quadSize = (2.0f + r.nextFloat() * 2.5f) * sizeScale;
                setColor(shade, shade, shade);
                baseAlpha = 0.14f + r.nextFloat() * 0.12f;
                growTime = 0.4f;
                spin = (r.nextFloat() - 0.5f) * 0.03f;
            }
            default -> { // DEBRIS: drifts along the surface with the current
                lifetime = 60 + r.nextInt(60);
                gravity = 0f;
                friction = 0.99f;
                hasPhysics = true; // comes to rest on the ground when the water drains away
                quadSize = 0.4f + r.nextFloat() * 0.45f;
                setColor(shade, shade, shade);
                baseAlpha = 1f;
                growTime = 0.05f;
                spin = (r.nextFloat() - 0.5f) * 0.05f;
            }
        }
        setAlpha(baseAlpha);
        this.roll = this.oRoll = r.nextFloat() * Mth.TWO_PI;
    }

    @Override
    public void tick() {
        super.tick();
        oRoll = roll;
        roll += spin;
        if (kind == Kind.DEBRIS) {
            // Ride the water surface: bob up when submerged, sink onto the water (or ground) when above it.
            boolean inWater = !level.getFluidState(BlockPos.containing(x, y, z)).isEmpty();
            boolean waterBelow = !level.getFluidState(BlockPos.containing(x, y - 0.6, z)).isEmpty();
            yd = inWater ? 0.04 : waterBelow ? 0 : -0.08;
        }
        float t = (float) age / lifetime;
        setAlpha(baseAlpha * Mth.clamp((1f - t) * 3f, 0f, 1f));
        // Droplets that land in water or on the ground just vanish.
        if (kind == Kind.FOAM && (onGround || !level.getFluidState(BlockPos.containing(x, y, z)).isEmpty())) {
            remove();
        }
    }

    @Override
    public float getQuadSize(float partialTick) {
        float t = (age + partialTick) / lifetime;
        float grow = Mth.clamp(t / growTime, 0f, 1f);
        return quadSize * (0.5f + 0.5f * grow);
    }

    @Override
    protected int getLightCoords(float a) {
        // Spray scatters light: never quite as dark as the block it is in.
        int lc = super.getLightCoords(a);
        return kind == Kind.DEBRIS ? lc : LightCoordsUtil.pack(Math.max(LightCoordsUtil.block(lc), 6), LightCoordsUtil.sky(lc));
    }

    @Override
    protected Layer getLayer() {
        return kind == Kind.DEBRIS ? Layer.OPAQUE : Layer.TRANSLUCENT;
    }

    public static ParticleProvider<SimpleParticleType> provider(Kind kind, SpriteSet sprites) {
        return (type, level, x, y, z, xa, ya, za, random) -> new WaveParticle(level, x, y, z, xa, ya, za, kind, sprites);
    }
}
