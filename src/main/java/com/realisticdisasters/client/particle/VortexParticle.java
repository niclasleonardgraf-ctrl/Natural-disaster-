package com.realisticdisasters.client.particle;

import com.realisticdisasters.client.ClientDisaster;
import com.realisticdisasters.client.ClientDisasters;
import com.realisticdisasters.client.tornado.TornadoVisual;
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
 * A particle bound to a tornado. It looks its disaster up every tick, so the whole vortex follows the storm, tilts,
 * snakes and ropes out with it. Roles:
 * <ul>
 *   <li>CLOUD: condensation funnel, sized to the local funnel radius, shaded by its side relative to the light</li>
 *   <li>WALL: the broad, slowly rotating wall cloud / mesocyclone base</li>
 *   <li>DEBRIS: dark flecks whipped around the base and flung out</li>
 *   <li>DUST: the churning brown debris cloud at the base</li>
 *   <li>INFLOW: dust streamers spiralling in along the ground</li>
 *   <li>RAIN: grey rain curtains wrapping the rear flank of the storm</li>
 * </ul>
 * Spawn args: xAux = disaster id, yAux = start height fraction, zAux = radius jitter (+100 per variant step).
 */
public class VortexParticle extends SingleQuadParticle {
    public enum Kind { CLOUD, WALL, DEBRIS, DUST, INFLOW, RAIN }

    /** Direction the "sun" lights the vortex from (radians, world space). */
    private static final double LIGHT_ANGLE = Math.PI * 0.8;

    private final Kind kind;
    private final int disasterId;
    private final float jitter;
    private final float sizeVar;
    private final float baseR, baseG, baseB;
    private final int minBlockLight;
    private double angle;
    private float heightFrac;
    private float rise;
    private float spin;
    private float radial;      // extra radial offset (fling / inflow)
    private float targetSize;
    private final boolean translucent;

    protected VortexParticle(ClientLevel level, double x, double y, double z, Kind kind, int disasterId,
                             float heightFrac, float jitter, SpriteSet sprites) {
        super(level, x, y, z, sprites.first());
        this.kind = kind;
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
            Vec3 c = d.pos().add(TornadoVisual.axisOffset(d, heightFrac));
            angle = Math.atan2(z - c.z, x - c.x);
        }
        this.sizeVar = 0.75f + r.nextFloat() * 0.5f;
        float shade;
        switch (kind) {
            case CLOUD -> {
                // condensation funnel: cool grey, slightly blue in shadow
                lifetime = 60 + r.nextInt(60);
                rise = 0.0015f + r.nextFloat() * 0.003f;
                spin = 0.12f + r.nextFloat() * 0.05f;
                shade = 0.36f + r.nextFloat() * 0.14f;
                baseR = shade * 0.97f; baseG = shade * 0.98f; baseB = shade * 1.03f;
                minBlockLight = 7;
                translucent = true;
            }
            case WALL -> {
                // cloud base / storm deck: dark slate
                lifetime = 160 + r.nextInt(120);
                rise = 0f;
                spin = jitter >= 4f ? 0.0025f + r.nextFloat() * 0.002f : 0.01f + r.nextFloat() * 0.008f;
                shade = (jitter >= 4f ? 0.27f : 0.22f) + r.nextFloat() * 0.06f;   // low contrast = smooth base
                baseR = shade * 0.94f; baseG = shade * 0.98f; baseB = shade * 1.06f;
                minBlockLight = 5;
                translucent = true;
            }
            case RAIN -> {
                lifetime = 70 + r.nextInt(50);
                rise = 0f;
                spin = 0.004f + r.nextFloat() * 0.004f;
                shade = 0.5f + r.nextFloat() * 0.15f;
                baseR = shade * 0.93f; baseG = shade * 0.97f; baseB = shade * 1.04f;
                minBlockLight = 6;
                translucent = true;
            }
            case DEBRIS -> {
                lifetime = 30 + r.nextInt(40);
                rise = 0.008f + r.nextFloat() * 0.012f;
                spin = 0.20f + r.nextFloat() * 0.10f;
                shade = 0.45f + r.nextFloat() * 0.35f;
                baseR = shade; baseG = shade; baseB = shade;
                minBlockLight = 5;
                translucent = false;
            }
            case DUST -> {
                lifetime = 60 + r.nextInt(60);
                rise = 0.004f + r.nextFloat() * 0.006f;
                spin = 0.10f + r.nextFloat() * 0.06f;
                shade = 0.38f + r.nextFloat() * 0.22f;
                // dusty grey-brown, with some darker soil clumps
                float soil = r.nextFloat() < 0.35f ? 0.75f : 1f;
                baseR = shade * 0.80f * soil; baseG = shade * 0.73f * soil; baseB = shade * 0.64f * soil;
                minBlockLight = 7;
                translucent = true;
            }
            default -> { // INFLOW
                lifetime = 60 + r.nextInt(40);
                rise = 0f;
                spin = 0.035f + r.nextFloat() * 0.03f;
                shade = 0.6f + r.nextFloat() * 0.2f;
                baseR = shade * 0.80f; baseG = shade * 0.74f; baseB = shade * 0.64f;
                minBlockLight = 7;
                translucent = true;
            }
        }
        setColor(baseR, baseG, baseB);
        if (translucent) setAlpha(switch (kind) {
            case CLOUD -> 0.95f + r.nextFloat() * 0.05f;
            case WALL -> 0.9f + r.nextFloat() * 0.1f;
            case RAIN -> 0.2f + r.nextFloat() * 0.18f;
            default -> 0.55f + r.nextFloat() * 0.35f;
        });
        this.quadSize = this.targetSize = 0.5f;
        this.roll = this.oRoll = kind == Kind.RAIN ? 0f : r.nextFloat() * Mth.TWO_PI;
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
            x += xd;
            y += yd + 0.02;
            z += zd;
            return;
        }
        Vec3 c = d.pos();
        float height = TornadoVisual.height(d);
        float core = TornadoVisual.coreRadius(d);
        float strength = d.strength();
        heightFrac = Math.min(kind == Kind.CLOUD ? 0.8f : 1.0f, heightFrac + rise);

        double r;
        double ny;
        float spinScale = 0.35f + 0.65f * strength;
        switch (kind) {
            case CLOUD -> {
                r = TornadoVisual.funnelRadius(d, heightFrac) * jitter;
                ny = c.y + heightFrac * height;
                targetSize = Mth.clamp((float) r * 0.7f, 0.8f, 5.0f) * (0.85f + 0.3f * (sizeVar - 0.75f) * 2f) * 0.9f;
                spinScale *= (float) (1.3 - 0.75 * heightFrac);   // tighter = faster
            }
            case WALL -> {
                // Flat-bottomed: all wall-cloud and deck particles share one base height, only the tops vary.
                r = core * jitter;
                boolean deck = jitter >= 4f;
                // Supercell bases are flat: little vertical spread, big overlapping puffs. The wall cloud is a
                // distinct lowered step around the funnel.
                double base = c.y + height * (deck ? 0.98 : 0.86);
                ny = base + (deck ? 1.2 : 1.5) * sizeVar;
                targetSize = deck ? (14f + jitter * 0.6f) * sizeVar : (8f + jitter * 1.2f) * sizeVar;
            }
            case DEBRIS -> {
                if (heightFrac > 0.18f) radial += 0.18f;            // thrown out once lifted
                r = TornadoVisual.funnelRadius(d, heightFrac) * jitter * 1.25 + radial;
                ny = c.y + heightFrac * height * 0.55;
                targetSize = 0.12f + 0.2f * sizeVar;
            }
            case DUST -> {
                // Wide skirt at the ground that is drawn inward as it rises into the funnel.
                float lift = Mth.clamp(heightFrac / 0.15f, 0f, 1f);
                radial += 0.03f * (1f - lift);
                r = (core * (0.5 + 1.3 * jitter) + radial) * (1.0 - 0.6 * lift);
                ny = c.y + 0.5 + heightFrac * Math.min(24, height * 0.28);
                targetSize = (2f + age / (float) lifetime * 4.5f) * sizeVar;
            }
            case RAIN -> {
                r = core * jitter;
                ny = c.y + heightFrac * height;
                targetSize = 7f * sizeVar;
            }
            default -> { // INFLOW: spiral in along the ground, then get swept up
                radial -= 0.09f + 0.05f * strength;
                r = Math.max(core * 0.6, core * jitter + radial);
                if (r <= core * 0.6 + 0.01) heightFrac = Math.min(0.2f, heightFrac + 0.01f);
                ny = c.y + 0.25 + heightFrac * height * 0.4;
                targetSize = (0.9f + 0.7f * sizeVar);
            }
        }
        angle += spin * spinScale;
        float h = kind == Kind.CLOUD || kind == Kind.WALL ? heightFrac : 0f;
        Vec3 axis = TornadoVisual.axisOffset(d, h);
        double nx = c.x + axis.x + Math.cos(angle) * r;
        double nz = c.z + axis.z + Math.sin(angle) * r;
        xd = nx - x;
        yd = ny - y;
        zd = nz - z;
        x = nx;
        y = ny;
        z = nz;
        quadSize += (targetSize - quadSize) * 0.15f;
        if (kind != Kind.RAIN) roll += spin * spinScale * 0.4f;

        // Side lighting gives the vortex volume: the lit flank is paler, the far flank in shadow.
        float lit = (float) (0.5 + 0.5 * Math.cos(angle - LIGHT_ANGLE));
        float shade = 0.6f + 0.4f * lit;
        if (kind == Kind.CLOUD) {
            // Helical striations: bands wound around the funnel make the rotation readable.
            shade *= (float) (0.84 + 0.16 * Math.sin(angle * 2.0 - heightFrac * 34.0));
        }
        if (kind == Kind.CLOUD && heightFrac < 0.25f) {
            // lower funnel picks up the brown of the debris it is ingesting
            float m = (0.25f - heightFrac) / 0.25f * 0.45f;
            setColor(Mth.lerp(m, baseR, 0.50f) * shade, Mth.lerp(m, baseG, 0.45f) * shade, Mth.lerp(m, baseB, 0.39f) * shade);
        } else {
            setColor(baseR * shade, baseG * shade, baseB * shade);
        }
    }

    @Override
    public float getQuadSize(float partialTick) {
        float t = (age + partialTick) / lifetime;
        float grow = Mth.clamp(t * 6f, 0f, 1f);
        float shrink = Mth.clamp((1f - t) * 4f, 0f, 1f);
        return quadSize * grow * shrink;
    }

    @Override
    protected int getLightCoords(float a) {
        int lc = super.getLightCoords(a);
        return LightCoordsUtil.pack(Math.max(LightCoordsUtil.block(lc), minBlockLight), LightCoordsUtil.sky(lc));
    }

    @Override
    protected Layer getLayer() {
        return translucent ? Layer.TRANSLUCENT : Layer.OPAQUE;
    }

    public static ParticleProvider<SimpleParticleType> provider(Kind kind, SpriteSet sprites) {
        return (type, level, x, y, z, xa, ya, za, random) ->
                new VortexParticle(level, x, y, z, kind, (int) xa, (float) ya, (float) za, sprites);
    }
}
