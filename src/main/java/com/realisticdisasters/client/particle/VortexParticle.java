package com.realisticdisasters.client.particle;

import org.joml.Quaternionf;

import com.realisticdisasters.client.ClientDisaster;
import com.realisticdisasters.client.ClientDisasters;
import com.realisticdisasters.client.tornado.TornadoVisual;
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
import net.minecraft.world.phys.Vec3;

/**
 * A particle bound to a tornado. It looks its disaster up every tick, so the whole storm follows the tornado, tilts,
 * snakes and ropes out with it.
 *
 * <p>Most kinds are camera-facing puffs, but three are <b>oriented quads</b>, which is what makes the storm read as
 * real geometry rather than a cloud of sprites:
 * <ul>
 *   <li>SURFACE: lies on the funnel surface (normal = radial), streak texture slanted into a helix</li>
 *   <li>SHEET: horizontal, facing down: the flat, continuous supercell base receding in perspective</li>
 *   <li>BAND: horizontal and tangential: rotating concentric striations in the cloud base</li>
 * </ul>
 * Camera-facing kinds: CLOUD (soft funnel silhouette), WALL (wall-cloud rim), DEBRIS, DUST, INFLOW,
 * RAIN (rotates only around the vertical axis, so curtains stay upright).
 *
 * <p>Spawn args: xAux = disaster id, yAux = start height fraction, zAux = radius factor (radius / core radius,
 * or radius jitter for funnel kinds).
 */
public class VortexParticle extends SingleQuadParticle {
    public enum Kind { CLOUD, SURFACE, WALL, SHEET, BAND, DEBRIS, DUST, INFLOW, RAIN }

    /** Direction the "sun" lights the vortex from (radians, world space). */
    private static final double LIGHT_ANGLE = Math.PI * 0.8;
    private static final float HALF_PI = Mth.HALF_PI;

    private final Kind kind;
    private final int disasterId;
    private final float jitter;
    private final float sizeVar;
    private final float baseR, baseG, baseB;
    private final int minBlockLight;
    private final float yaw0;          // fixed random yaw for sheets
    private double angle;
    private double prevAngle;
    private float heightFrac;
    private float rise;
    private float spin;
    private float radial;
    private float targetSize;

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
            angle = prevAngle = Math.atan2(z - c.z, x - c.x);
        }
        this.sizeVar = 0.75f + r.nextFloat() * 0.5f;
        this.yaw0 = r.nextFloat() * Mth.TWO_PI;
        float shade;
        float alpha;
        switch (kind) {
            case CLOUD -> {
                lifetime = 60 + r.nextInt(60);
                rise = 0.0015f + r.nextFloat() * 0.003f;
                spin = 0.12f + r.nextFloat() * 0.05f;
                shade = 0.28f + r.nextFloat() * 0.12f;
                alpha = 0.9f + r.nextFloat() * 0.1f;
                minBlockLight = 7;
            }
            case SURFACE -> {
                lifetime = 50 + r.nextInt(40);
                rise = 0.002f + r.nextFloat() * 0.003f;
                spin = 0.13f + r.nextFloat() * 0.04f;
                shade = 0.30f + r.nextFloat() * 0.14f;
                alpha = 0.85f + r.nextFloat() * 0.15f;
                minBlockLight = 7;
            }
            case WALL -> {
                lifetime = 160 + r.nextInt(120);
                spin = 0.01f + r.nextFloat() * 0.008f;
                shade = 0.12f + r.nextFloat() * 0.05f;
                alpha = 0.85f + r.nextFloat() * 0.15f;
                minBlockLight = 5;
            }
            case SHEET -> {
                lifetime = 260 + r.nextInt(200);
                spin = jitter < 3.5f ? 0.009f + r.nextFloat() * 0.005f : 0.0025f + r.nextFloat() * 0.002f;
                // darker under the updraft near the centre, a little lighter toward the edges
                float outer = Mth.clamp(jitter / 14f, 0f, 1f);
                shade = jitter < 3.5f ? 0.09f + r.nextFloat() * 0.03f : (0.11f + 0.1f * outer) + r.nextFloat() * 0.04f;
                alpha = jitter < 3.5f ? 0.9f + r.nextFloat() * 0.1f : 0.75f + r.nextFloat() * 0.25f;
                minBlockLight = 2;
            }
            case BAND -> {
                lifetime = 200 + r.nextInt(120);
                spin = 0.004f + r.nextFloat() * 0.004f;
                shade = r.nextBoolean() ? 0.27f + r.nextFloat() * 0.07f : 0.08f + r.nextFloat() * 0.04f;
                alpha = 0.35f + r.nextFloat() * 0.3f;
                minBlockLight = 5;
            }
            case DEBRIS -> {
                lifetime = 30 + r.nextInt(40);
                rise = 0.008f + r.nextFloat() * 0.012f;
                spin = 0.20f + r.nextFloat() * 0.10f;
                shade = 0.45f + r.nextFloat() * 0.35f;
                alpha = 1f;
                minBlockLight = 5;
            }
            case DUST -> {
                lifetime = 60 + r.nextInt(60);
                rise = 0.004f + r.nextFloat() * 0.006f;
                spin = 0.10f + r.nextFloat() * 0.06f;
                shade = 0.38f + r.nextFloat() * 0.22f;
                alpha = 0.55f + r.nextFloat() * 0.35f;
                minBlockLight = 7;
            }
            case RAIN -> {
                lifetime = 70 + r.nextInt(50);
                spin = 0.004f + r.nextFloat() * 0.004f;
                shade = 0.5f + r.nextFloat() * 0.15f;
                alpha = 0.2f + r.nextFloat() * 0.18f;
                minBlockLight = 6;
            }
            default -> { // INFLOW
                lifetime = 60 + r.nextInt(40);
                spin = 0.035f + r.nextFloat() * 0.03f;
                shade = 0.6f + r.nextFloat() * 0.2f;
                alpha = 0.55f + r.nextFloat() * 0.35f;
                minBlockLight = 7;
            }
        }
        switch (kind) {
            case DUST, INFLOW -> {
                float soil = kind == Kind.DUST && r.nextFloat() < 0.35f ? 0.75f : 1f;
                baseR = shade * 0.80f * soil; baseG = shade * 0.73f * soil; baseB = shade * 0.64f * soil;
            }
            case DEBRIS -> { baseR = shade; baseG = shade; baseB = shade; }
            default -> { baseR = shade * 0.95f; baseG = shade * 0.98f; baseB = shade * 1.05f; }  // cool slate grey
        }
        setColor(baseR, baseG, baseB);
        setAlpha(alpha);
        this.quadSize = this.targetSize = 0.5f;
        this.roll = this.oRoll = kind == Kind.RAIN ? 0f : r.nextFloat() * Mth.TWO_PI;
    }

    /** Large particles must be culled by their extent, not their centre (see QuadParticleGroupMixin). */
    public float cullRadius() {
        return quadSize > 3f ? quadSize * 1.5f : 0f;
    }

    @Override
    public void tick() {
        xo = x;
        yo = y;
        zo = z;
        oRoll = roll;
        prevAngle = angle;
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
        boolean funnel = kind == Kind.CLOUD || kind == Kind.SURFACE;
        heightFrac = Math.min(funnel ? 0.8f : 1.0f, heightFrac + rise);

        double r;
        double ny;
        float spinScale = 0.35f + 0.65f * strength;
        switch (kind) {
            case CLOUD -> {
                r = TornadoVisual.funnelRadius(d, heightFrac) * jitter;
                ny = c.y + heightFrac * height;
                targetSize = Mth.clamp((float) r * 0.6f, 0.7f, 4.5f) * (0.85f + 0.3f * (sizeVar - 0.75f) * 2f) * 0.9f;
                spinScale *= (float) (1.3 - 0.75 * heightFrac);
            }
            case SURFACE -> {
                r = TornadoVisual.funnelRadius(d, heightFrac) * 0.96;
                ny = c.y + heightFrac * height;
                targetSize = Mth.clamp((float) r * 0.8f, 0.8f, 5.5f) * sizeVar;
                spinScale *= (float) (1.3 - 0.75 * heightFrac);
            }
            case WALL -> {
                r = core * jitter;
                ny = c.y + height * 0.86 + 1.5 * sizeVar;
                targetSize = (6f + jitter * 1.2f) * sizeVar;
            }
            case SHEET -> {
                r = core * jitter;
                ny = c.y + TornadoVisual.cloudBaseHeight(d, jitter);
                targetSize = jitter < 3.5f ? (7f + jitter * 1.5f) * sizeVar : (15f + jitter * 0.5f) * sizeVar;
            }
            case BAND -> {
                r = core * jitter;
                ny = c.y + TornadoVisual.cloudBaseHeight(d, jitter) - 0.6;
                targetSize = (9f + jitter * 0.7f) * sizeVar;
            }
            case DEBRIS -> {
                if (heightFrac > 0.18f) radial += 0.18f;
                r = TornadoVisual.funnelRadius(d, heightFrac) * jitter * 1.25 + radial;
                ny = c.y + heightFrac * height * 0.55;
                targetSize = 0.12f + 0.2f * sizeVar;
            }
            case DUST -> {
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
        float h = funnel || kind == Kind.WALL ? heightFrac : kind == Kind.SHEET || kind == Kind.BAND ? 0.9f : 0f;
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

        // Side lighting gives volume: the lit flank is paler, the far flank in shadow.
        float shade = 1f;
        if (kind != Kind.SHEET && kind != Kind.BAND) {
            float lit = (float) (0.5 + 0.5 * Math.cos(angle - LIGHT_ANGLE));
            shade = 0.6f + 0.4f * lit;
        }
        if (kind == Kind.CLOUD) {
            shade *= (float) (0.86 + 0.14 * Math.sin(angle * 2.0 - heightFrac * 34.0));
        }
        if (funnel && heightFrac < 0.25f) {
            // the lower funnel picks up the brown of the debris it is ingesting
            float m = (0.25f - heightFrac) / 0.25f * 0.45f;
            setColor(Mth.lerp(m, baseR, 0.50f) * shade, Mth.lerp(m, baseG, 0.45f) * shade, Mth.lerp(m, baseB, 0.39f) * shade);
        } else {
            setColor(baseR * shade, baseG * shade, baseB * shade);
        }
    }

    // ------------------------------------------------------------------ orientation

    @Override
    public void extract(QuadParticleRenderState state, Camera camera, float partialTick) {
        float a = (float) Mth.lerp(partialTick, prevAngle, angle);
        Quaternionf q = new Quaternionf();
        switch (kind) {
            // Normal points radially outward (local +Z is the visible face); slanted into a helix.
            case SURFACE -> q.rotateY(HALF_PI - a).rotateZ(-0.5f);
            // Horizontal, visible from below.
            case SHEET -> q.rotateY(yaw0 + a).rotateX(HALF_PI);
            // Horizontal with the band's long axis tangential -> concentric striations.
            case BAND -> q.rotateY(-(a + HALF_PI)).rotateX(HALF_PI);
            // Curtains stay upright: turn only around the vertical axis.
            case RAIN -> FacingCameraMode.LOOKAT_Y.setRotation(q, camera, partialTick);
            default -> {
                super.extract(state, camera, partialTick);
                return;
            }
        }
        extractRotatedQuad(state, camera, q, partialTick);
    }

    @Override
    public float getQuadSize(float partialTick) {
        float t = (age + partialTick) / lifetime;
        float in = kind == Kind.SHEET || kind == Kind.BAND ? 3f : 6f;
        float grow = Mth.clamp(t * in, 0f, 1f);
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
        return kind == Kind.DEBRIS ? Layer.OPAQUE : Layer.TRANSLUCENT;
    }

    public static ParticleProvider<SimpleParticleType> provider(Kind kind, SpriteSet sprites) {
        return (type, level, x, y, z, xa, ya, za, random) ->
                new VortexParticle(level, x, y, z, kind, (int) xa, (float) ya, (float) za, sprites);
    }
}
