package com.realisticdisasters.client.particle;

import com.realisticdisasters.client.ClientDisaster;
import com.realisticdisasters.client.ClientDisasters;
import com.realisticdisasters.client.tornado.TornadoVisual;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * A particle that orbits a tornado's funnel. It looks up its disaster every tick, so the whole funnel follows the
 * tornado as it moves and ropes out when it weakens. Three flavours share the motion code:
 * cloud (funnel body + wall cloud), debris (fast flecks near the ground) and dust (base cloud).
 *
 * Spawn args: xAux = disaster id, yAux = start height fraction (0..1), zAux = radius jitter.
 */
public class VortexParticle extends SingleQuadParticle {
    public enum Kind { CLOUD, DEBRIS, DUST }

    private final Kind kind;
    private final SpriteSet sprites;
    private final int disasterId;
    private final float jitter;
    private final float baseSize;
    private double angle;
    private float heightFrac;
    private float rise;
    private float spin;
    private float fling;

    protected VortexParticle(ClientLevel level, double x, double y, double z, Kind kind, int disasterId,
                             float heightFrac, float jitter, SpriteSet sprites) {
        super(level, x, y, z, sprites.first());
        this.kind = kind;
        this.sprites = sprites;
        this.disasterId = disasterId;
        this.heightFrac = heightFrac;
        this.jitter = jitter;
        this.hasPhysics = false;
        this.gravity = 0;
        this.friction = 1f;
        RandomSource r = this.random;
        setSprite(sprites.get(r));
        ClientDisaster d = ClientDisasters.get(disasterId);
        if (d != null) {
            Vec3 c = d.pos();
            angle = Math.atan2(z - c.z, x - c.x);
        }
        switch (kind) {
            case CLOUD -> {
                lifetime = 50 + r.nextInt(50);
                rise = 0.004f + r.nextFloat() * 0.006f;
                spin = 0.10f + r.nextFloat() * 0.06f;
                baseSize = 1.4f + r.nextFloat() * 1.8f;
                float shade = 0.36f + r.nextFloat() * 0.22f;
                setColor(shade, shade * 1.02f, shade * 0.96f);
            }
            case DEBRIS -> {
                lifetime = 30 + r.nextInt(40);
                rise = 0.012f + r.nextFloat() * 0.014f;
                spin = 0.22f + r.nextFloat() * 0.12f;
                baseSize = 0.18f + r.nextFloat() * 0.22f;
                float shade = 0.55f + r.nextFloat() * 0.35f;
                setColor(shade, shade, shade);
            }
            default -> {
                lifetime = 40 + r.nextInt(40);
                rise = 0.001f + r.nextFloat() * 0.003f;
                spin = 0.06f + r.nextFloat() * 0.05f;
                baseSize = 1.2f + r.nextFloat() * 1.6f;
                float shade = 0.75f + r.nextFloat() * 0.2f;
                setColor(shade * 0.85f, shade * 0.74f, shade * 0.58f);
            }
        }
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
        ClientDisaster d = ClientDisasters.get(disasterId);
        if (d == null) {
            // Orphaned: drift and fade away.
            x += xd;
            y += yd + 0.02;
            z += zd;
            return;
        }
        Vec3 c = d.pos();
        float height = TornadoVisual.height(d);
        float strength = d.strength();
        heightFrac = Math.min(1.05f, heightFrac + rise);
        // Inner funnel spins faster (conservation of angular momentum).
        double speed = spin * (1.25 - 0.6 * heightFrac) * (0.4 + 0.6 * strength);
        angle += speed;
        double r = TornadoVisual.funnelRadius(d, heightFrac) * jitter;
        double ny;
        switch (kind) {
            case DEBRIS -> {
                r = r * 1.3 + fling;
                if (heightFrac > 0.25f) fling += 0.12f;      // thrown outward once lifted
                ny = c.y + heightFrac * height * 0.6;
            }
            case DUST -> {
                fling += 0.05f;
                r = r * 1.6 + fling;
                ny = c.y + 0.3 + heightFrac * 6;
            }
            default -> ny = c.y + heightFrac * height;
        }
        double nx = c.x + Math.cos(angle) * r;
        double nz = c.z + Math.sin(angle) * r;
        xd = nx - x;
        yd = ny - y;
        zd = nz - z;
        x = nx;
        y = ny;
        z = nz;
        roll += (float) speed * 0.5f;
    }

    @Override
    public float getQuadSize(float partialTick) {
        float t = (age + partialTick) / lifetime;
        float grow = Mth.clamp(t * 6f, 0f, 1f);
        float shrink = Mth.clamp((1f - t) * 4f, 0f, 1f);
        return quadSize * grow * shrink;
    }

    @Override
    protected Layer getLayer() {
        return Layer.OPAQUE;
    }

    @Override
    public Particle scale(float scale) {
        return super.scale(scale);
    }

    public static ParticleProvider<SimpleParticleType> provider(Kind kind, SpriteSet sprites) {
        return (type, level, x, y, z, xa, ya, za, random) ->
                new VortexParticle(level, x, y, z, kind, (int) xa, (float) ya, (float) za, sprites);
    }
}
