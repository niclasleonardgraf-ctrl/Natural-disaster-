package com.realisticdisasters.client.particle;

import com.realisticdisasters.client.ClientDisaster;
import com.realisticdisasters.client.ClientDisasters;
import com.realisticdisasters.disaster.windstorm.StormWind;
import com.realisticdisasters.disaster.windstorm.WindStormDisaster;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * A particle carried by a wind storm. Every tick it reads its storm's synced wind vector and gust, so the whole
 * cloud of flakes / grains turns and surges together.
 * <ul>
 * <li>FLAKE / GRAIN: small and fast. Rendered as motion-blur streaks: the quad is rolled to the particle's on-screen
 * direction of travel and the sprite gets longer the faster it crosses the view, so snow driving straight at the
 * camera reads as dots and snow crossing it as long streaks, like in real footage.</li>
 * <li>Ground-drift flag: hugs the terrain a hand's width up (drifting "snow snakes", saltating sand), lifting over
 * obstacles.</li>
 * <li>SNOW_HAZE / SAND_HAZE: big soft puffs building the white-out / brown-out; with the wall flag they form the
 * towering front seen from outside (dark at the base, lit at the top).</li>
 * </ul>
 * Spawn args: xAux = disaster id, yAux = size multiplier, zAux = flags (+1 red sand, +2 ground drift, +4 wall)
 * plus a fractional part = height fraction for wall puffs.
 */
public class WindParticle extends SingleQuadParticle {
    public static final int RED = 1, DRIFT = 2, WALL = 4, SKIRT = 8;

    public enum Kind {
        FLAKE(false), GRAIN(false), SNOW_HAZE(true), SAND_HAZE(true);

        final boolean haze;

        Kind(boolean haze) {
            this.haze = haze;
        }
    }

    private final Kind kind;
    private final SpriteSet sprites;
    private final int disasterId;
    private final boolean drift, wall, skirt;
    private final float baseSize;
    private float speedJitter;
    private final float lift;
    private final float phase;
    private final float hover;
    private float baseAlpha = 1f;
    private int stuck;
    private float streakScale = 1f;

    protected WindParticle(ClientLevel level, double x, double y, double z, Kind kind, int disasterId, float sizeMul,
                           float flagsAndFrac, SpriteSet sprites) {
        super(level, x, y, z, sprites.first());
        this.kind = kind;
        this.sprites = sprites;
        this.disasterId = disasterId;
        int flags = (int) flagsAndFrac;
        float frac = flagsAndFrac - flags;
        boolean red = (flags & RED) != 0;
        this.drift = (flags & DRIFT) != 0 && !kind.haze;
        this.wall = (flags & WALL) != 0 && kind.haze;
        this.skirt = wall && (flags & SKIRT) != 0;
        RandomSource r = this.random;
        setSprite(kind.haze ? sprites.get(r) : sprites.get(0, 3));
        this.gravity = 0;
        this.friction = 1f;
        this.phase = r.nextFloat() * Mth.TWO_PI;
        float hoverRoll = r.nextFloat();
        switch (kind) {
            case FLAKE -> {
                lifetime = drift ? 30 + r.nextInt(30) : 40 + r.nextInt(30);
                baseSize = (drift ? 0.08f + r.nextFloat() * 0.08f : 0.1f + r.nextFloat() * 0.1f) * sizeMul;
                speedJitter = drift ? 0.65f + r.nextFloat() * 0.35f : 0.8f + r.nextFloat() * 0.45f;
                // Wind-driven snow falls at a shallow angle (roughly 10-25 degrees below horizontal).
                lift = -0.06f - r.nextFloat() * 0.12f;
                hover = 0.05f + hoverRoll * hoverRoll * 0.9f;
                float shade = 0.96f + r.nextFloat() * 0.04f;
                setColor(shade, shade, shade);
                setSize(0.08f, 0.08f);
                baseAlpha = drift ? 0.55f + r.nextFloat() * 0.35f : 0.75f + r.nextFloat() * 0.25f;
            }
            case GRAIN -> {
                lifetime = drift ? 25 + r.nextInt(25) : 30 + r.nextInt(25);
                baseSize = (0.08f + r.nextFloat() * 0.08f) * sizeMul;
                speedJitter = drift ? 0.6f + r.nextFloat() * 0.35f : 0.9f + r.nextFloat() * 0.4f;
                lift = -0.02f + r.nextFloat() * 0.025f;
                hover = 0.03f + hoverRoll * hoverRoll * 0.45f; // saltation layer: most grains stay below half a block
                float shade = 0.8f + r.nextFloat() * 0.2f;
                if (red) setColor(shade * 0.8f, shade * 0.47f, shade * 0.29f);
                else setColor(shade * 0.88f, shade * 0.74f, shade * 0.52f);
                setSize(0.06f, 0.06f);
                baseAlpha = 0.7f + r.nextFloat() * 0.3f;
            }
            case SNOW_HAZE -> {
                lifetime = wall ? 120 + r.nextInt(80) : 60 + r.nextInt(50);
                baseSize = (2.4f + r.nextFloat() * 2.6f) * sizeMul;
                // Wall puffs creep with the storm front (a few % of the wind) and billow upward.
                speedJitter = wall ? 0.04f + r.nextFloat() * 0.04f : 0.55f + r.nextFloat() * 0.3f;
                lift = wall ? 0.004f + r.nextFloat() * 0.008f : -0.01f + r.nextFloat() * 0.015f;
                hover = 0;
                float shade = wall ? 0.7f + 0.28f * frac : 0.95f + r.nextFloat() * 0.05f;
                setColor(shade * 0.95f, shade * 0.97f, shade);
                baseAlpha = wall ? 0.95f + r.nextFloat() * 0.05f : 0.25f + r.nextFloat() * 0.25f;
            }
            default -> {
                lifetime = wall ? 120 + r.nextInt(80) : 60 + r.nextInt(50);
                baseSize = (2.4f + r.nextFloat() * 2.8f) * sizeMul;
                speedJitter = wall ? 0.04f + r.nextFloat() * 0.04f : 0.5f + r.nextFloat() * 0.3f;
                lift = wall ? 0.006f + r.nextFloat() * 0.012f : -0.005f + r.nextFloat() * 0.015f;
                hover = 0;
                // Haboob front: dark, dense base; the billowing top catches the sun.
                float shade = wall ? 0.48f + 0.5f * frac : 0.82f + r.nextFloat() * 0.12f;
                if (red) setColor(shade * 0.84f, shade * 0.52f, shade * 0.33f);
                else setColor(shade * 0.9f, shade * 0.73f, shade * 0.5f);
                baseAlpha = wall ? 1f : 0.3f + r.nextFloat() * 0.3f;
            }
        }
        if (skirt) {
            // Outflow skirt: runs ahead of the wall much faster than the wall itself, hugging the ground.
            speedJitter = 0.45f + r.nextFloat() * 0.35f;
            lifetime = 40 + r.nextInt(40);
            baseAlpha = 0.45f + r.nextFloat() * 0.25f;
        }
        this.hasPhysics = !kind.haze && !drift;
        this.quadSize = baseSize;
        this.roll = this.oRoll = kind.haze ? r.nextFloat() * Mth.TWO_PI : 0f;
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
            double wx = d.data(WindStormDisaster.D_WIND_X), wz = d.data(WindStormDisaster.D_WIND_Z);
            // Same gust field the server pushes entities with: gust fronts visibly sweep through the snow / dust.
            float gust = StormWind.gust(d.data(WindStormDisaster.D_GUST), x, z, wx, wz, level.getGameTime(), disasterId);
            float speed = d.data(WindStormDisaster.D_WIND_SPEED) * gust * speedJitter;
            // Turbulence: a cross-wind wobble and vertical swirl, different per particle.
            float t = age * (kind.haze ? 0.05f : 0.25f) + phase;
            double cross = Mth.sin(t) * speed * (kind.haze ? 0.12 : drift ? 0.15 : 0.3);
            double tx = wx * speed - wz * cross;
            double tz = wz * speed + wx * cross;
            double ty;
            if (drift) {
                // Follow the ground a little above it; ride up and over obstacles like real drifting snow / sand.
                int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(x + tx), Mth.floor(z + tz));
                double bounce = kind == Kind.GRAIN ? Math.abs(Mth.sin(t * 0.8f)) * 0.25 : Mth.sin(t * 0.5f) * 0.08;
                ty = (ground + hover + bounce - y) * 0.35;
            } else {
                ty = lift + Mth.cos(t * 1.3f) * (kind.haze ? 0.01 : 0.05) * gust;
            }
            double k = kind.haze ? 0.08 : 0.25;
            xd += (tx - xd) * k;
            yd = drift ? ty : yd + (ty - yd) * k;
            zd += (tz - zd) * k;
        } else {
            // Storm gone: settle and fade.
            xd *= 0.9;
            zd *= 0.9;
            if (age < lifetime - 10) age = lifetime - 10;
        }
        move(xd, yd, zd);
        if (!kind.haze && !drift) {
            // Flakes / grains that hit the ground or a wall linger briefly, then vanish.
            if (onGround || xd == 0 || zd == 0) stuck++;
            if (stuck > 6) remove();
        } else if (kind.haze) {
            roll += wall ? 0.004f : 0.01f;
        }
    }

    @Override
    public void extract(QuadParticleRenderState state, Camera camera, float partialTick) {
        if (kind.haze) {
            super.extract(state, camera, partialTick);
            return;
        }
        // Motion blur: express the velocity in camera space, roll the quad to its on-screen direction, and pick a
        // longer streak sprite the faster it sweeps across the view (angular speed).
        Quaternionf rotation = new Quaternionf(camera.rotation());
        Vector3f v = new Vector3f((float) xd, (float) yd, (float) zd);
        new Quaternionf(rotation).conjugate().transform(v);
        float planar = Mth.sqrt(v.x * v.x + v.y * v.y);
        Vec3 cam = camera.position();
        double px = Mth.lerp(partialTick, xo, x) - cam.x, py = Mth.lerp(partialTick, yo, y) - cam.y, pz = Mth.lerp(partialTick, zo, z) - cam.z;
        float dist = (float) Math.max(0.5, Math.sqrt(px * px + py * py + pz * pz));
        int streak = Mth.clamp((int) (planar / dist * 28f), 0, 3);
        setSprite(sprites.get(streak, 3));
        streakScale = 1f + 0.2f * streak;
        if (planar > 1.0e-4f) rotation.rotateZ((float) Mth.atan2(v.y, v.x));
        extractRotatedQuad(state, camera, rotation, partialTick);
    }

    @Override
    protected void extractRotatedQuad(QuadParticleRenderState state, Quaternionf rotation, float x, float y, float z,
                                      float partialTick) {
        float t = (age + partialTick) / lifetime;
        float in = wall ? 3f : kind.haze ? 5f : 10f;
        float fade = Mth.clamp(t * in, 0f, 1f) * Mth.clamp((1f - t) * (kind.haze ? 4f : 6f), 0f, 1f);
        this.alpha = baseAlpha * fade;
        // Wall puffs keep billowing outward over their life; the rest just ease in.
        this.quadSize = wall ? baseSize * (0.8f + 0.5f * t) : baseSize * (0.7f + 0.3f * fade) * streakScale;
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
