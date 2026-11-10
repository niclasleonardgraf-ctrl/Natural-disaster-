package com.realisticdisasters.client.particle;

import com.realisticdisasters.client.ClientDisaster;
import com.realisticdisasters.client.ClientDisasters;
import com.realisticdisasters.disaster.wildfire.WildfireDisaster;
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
 * Wildfire particles, modelled on real fire footage:
 * <ul>
 * <li>SMOKE: dense smoke boiling off the flames. Buoyant: rises fast near the fire, slows with height, and is bent
 *     over by wind that gets stronger with altitude (wind shear), so the column leans downwind.</li>
 * <li>BILLOW: the convection column. Large cauliflower puffs that tower up to the inversion height, darker and
 *     browner at the base, bright and sunlit at the top.</li>
 * <li>PALL: the plume streaming downwind from the top of the column: big, soft, slowly spreading puffs.</li>
 * <li>SMOLDER: thin pale wisps rising from burnt ground and trunks.</li>
 * <li>FLAME: an upright, animated flame tongue (looping sprite frames), self-lit.</li>
 * <li>EMBER: glowing spark lofted by the plume and carried downwind.</li>
 * <li>ASH: grey-white flakes tumbling down.</li>
 * </ul>
 * Smoke spawn args: xAux = disaster id (-1 = none), yAux = shade (0 pale grass smoke .. 1 dark forest smoke),
 * zAux = ground height. Flame args: size, rise speed, variant. Ember / ash args: velocity.
 */
public class WildfireParticle extends SingleQuadParticle {
    public enum Kind { SMOKE, BILLOW, PALL, SMOLDER, FLAME, EMBER, ASH }

    private static final int FLAME_FRAMES = 8;

    private final Kind kind;
    private final SpriteSet sprites;
    private int disasterId;
    private final double groundY;
    private final float shade;
    private final float baseSize;
    private final float maxAlpha;
    private final float phase;
    private final float spin;
    private final int variant;
    // wind cached from the disaster (kept when it ends so the smoke keeps drifting)
    private double windX, windZ;
    private float windSpeed = 0.25f;
    private float ceiling = 50f;

    protected WildfireParticle(ClientLevel level, double x, double y, double z, double xa, double ya, double za,
                               Kind kind, SpriteSet sprites) {
        super(level, x, y, z, sprites.first());
        this.kind = kind;
        this.sprites = sprites;
        RandomSource r = this.random;
        setSprite(sprites.get(r));
        this.phase = r.nextFloat() * Mth.TWO_PI;
        this.gravity = 0;
        this.hasPhysics = false;
        this.friction = 1f;
        boolean smoke = kind == Kind.SMOKE || kind == Kind.BILLOW || kind == Kind.PALL || kind == Kind.SMOLDER;
        this.disasterId = smoke ? (int) xa : -1;
        this.shade = smoke ? Mth.clamp((float) ya, 0f, 1f) : 0f;
        this.groundY = smoke ? za : y;
        this.variant = kind == Kind.FLAME ? Mth.clamp((int) za, 0, 1) : 0;
        if (smoke) {
            xd = yd = zd = 0;
            windX = 1;
            pullWind();
        } else {
            xd = kind == Kind.FLAME ? 0 : xa;
            yd = ya;
            zd = kind == Kind.FLAME ? 0 : za;
        }
        switch (kind) {
            case SMOKE -> {
                lifetime = 110 + r.nextInt(90);
                baseSize = 1.3f + r.nextFloat() * 1.4f;
                maxAlpha = 0.7f + r.nextFloat() * 0.25f;
                spin = (r.nextFloat() - 0.5f) * 0.02f;
                yd = 0.2;
            }
            case BILLOW -> {
                lifetime = 260 + r.nextInt(140);
                baseSize = 3.2f + r.nextFloat() * 2.8f;
                maxAlpha = 0.85f + r.nextFloat() * 0.15f;
                spin = (r.nextFloat() - 0.5f) * 0.008f;
                yd = 0.3;
            }
            case PALL -> {
                lifetime = 420 + r.nextInt(240);
                baseSize = 10f + r.nextFloat() * 9f;
                maxAlpha = 0.35f + r.nextFloat() * 0.25f;
                spin = (r.nextFloat() - 0.5f) * 0.002f;
            }
            case SMOLDER -> {
                lifetime = 60 + r.nextInt(60);
                baseSize = 0.25f + r.nextFloat() * 0.3f;
                maxAlpha = 0.18f + r.nextFloat() * 0.17f;
                spin = (r.nextFloat() - 0.5f) * 0.05f;
                yd = 0.04;
            }
            case FLAME -> {
                lifetime = 10 + r.nextInt(12);
                baseSize = (float) xa;
                maxAlpha = 0.95f;
                spin = 0;
            }
            case EMBER -> {
                lifetime = 30 + r.nextInt(60);
                baseSize = 0.04f + r.nextFloat() * 0.06f;
                maxAlpha = 1f;
                spin = 0;
                friction = 0.96f;
            }
            default -> {
                lifetime = 120 + r.nextInt(100);
                baseSize = 0.035f + r.nextFloat() * 0.045f; // flakes are small: a few millimetres to a centimetre
                maxAlpha = 0.85f;
                spin = (r.nextFloat() - 0.5f) * 0.25f;
                hasPhysics = true;
                friction = 0.97f;
            }
        }
        this.quadSize = baseSize;
        this.roll = this.oRoll = r.nextFloat() * Mth.TWO_PI;
        if (kind == Kind.FLAME) updateFlameSprite();
        updateLook();
    }

    private void pullWind() {
        if (disasterId < 0) disasterId = nearestWildfire();
        if (disasterId < 0) return;
        ClientDisaster d = ClientDisasters.get(disasterId);
        if (d == null) return;
        double wx = d.data(WildfireDisaster.D_WIND_X), wz = d.data(WildfireDisaster.D_WIND_Z);
        double len = Math.sqrt(wx * wx + wz * wz);
        if (len > 1.0e-3) {
            windX = wx / len;
            windZ = wz / len;
        }
        windSpeed = Math.max(0.1f, d.data(WildfireDisaster.D_WIND_SPEED));
        ceiling = Math.max(20f, d.data(WildfireDisaster.D_SMOKE_HEIGHT));
    }

    /** Smoke from flame blocks / smouldering ground isn't tied to a disaster: follow the nearest wildfire's wind. */
    private int nearestWildfire() {
        int best = -1;
        double bestD = 400 * 400;
        for (ClientDisaster d : ClientDisasters.all()) {
            if (d.type != com.realisticdisasters.disaster.DisasterType.WILDFIRE) continue;
            double dx = d.pos().x - x, dz = d.pos().z - z;
            double dd = dx * dx + dz * dz;
            if (dd < bestD) {
                bestD = dd;
                best = d.id;
            }
        }
        return best;
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
        if ((age & 7) == 0) pullWind();
        double h = Math.max(0, y - groundY);
        switch (kind) {
            case SMOKE, BILLOW, SMOLDER -> {
                double top = kind == Kind.BILLOW ? ceiling * 1.15 : kind == Kind.SMOKE ? ceiling * 0.8 : 6;
                double lift = kind == Kind.BILLOW ? 0.34 : kind == Kind.SMOKE ? 0.22 : 0.05;
                // buoyancy fades out toward the inversion; above it the smoke only spreads
                double rise = lift * Math.sqrt(Mth.clamp(1 - h / top, 0, 1)) + 0.004;
                yd += (rise - yd) * 0.06;
                // wind shear: calm near the ground, strong aloft (the core of a big column resists it)
                double shear = windSpeed * Math.min(0.32, 0.02 + 0.0055 * h) * (kind == Kind.BILLOW ? 0.7 : 1.0);
                if (kind == Kind.SMOLDER) shear = windSpeed * 0.02;
                xd += (windX * shear - xd) * 0.04 + (random.nextFloat() - 0.5f) * 0.004;
                zd += (windZ * shear - zd) * 0.04 + (random.nextFloat() - 0.5f) * 0.004;
            }
            case PALL -> {
                yd *= 0.9;
                xd += (windX * windSpeed * 0.12 - xd) * 0.02;
                zd += (windZ * windSpeed * 0.12 - zd) * 0.02;
            }
            case FLAME -> {
                yd = Math.max(yd * 0.9, 0.01);
                updateFlameSprite();
            }
            case EMBER -> {
                yd = yd * 0.97 + 0.003 - (age > lifetime / 2 ? 0.006 : 0);
                xd += Math.sin(age * 0.6 + phase) * 0.008;
                zd += Math.cos(age * 0.5 + phase) * 0.008;
            }
            default -> {
                yd = Math.max(yd - 0.0012, -0.045);
                xd += Math.sin(age * 0.21 + phase) * 0.003;
                zd += Math.cos(age * 0.17 + phase) * 0.003;
                if (onGround) {
                    xd = zd = 0;
                    if (age < lifetime - 30) age = lifetime - 30; // settle, then fade
                }
            }
        }
        roll += spin;
        if (hasPhysics) move(xd, yd, zd);
        else {
            x += xd;
            y += yd;
            z += zd;
        }
        xd *= friction;
        zd *= friction;
        updateLook();
    }

    private void updateFlameSprite() {
        int frame = (int) ((age * 0.6f + phase * 2) % FLAME_FRAMES);
        setSprite(sprites.get(variant * FLAME_FRAMES + frame, 2 * FLAME_FRAMES - 1));
    }

    /** Colour and opacity for the current age / height. */
    private void updateLook() {
        float t = age / (float) lifetime;
        float fade = Mth.clamp(t * (kind == Kind.PALL ? 4f : 8f), 0f, 1f) * Mth.clamp((1f - t) * 3.5f, 0f, 1f);
        switch (kind) {
            case SMOKE, BILLOW, PALL, SMOLDER -> {
                double h = Math.max(0, y - groundY);
                float hf = (float) Mth.clamp(h / ceiling, 0, 1);
                // dark grey-brown forest smoke vs pale grey-white grass smoke, brightening (sunlit) with height
                float dark = shade * (1f - 0.65f * hf);
                float r = Mth.lerp(dark, 0.86f, 0.38f), g = Mth.lerp(dark, 0.85f, 0.34f), b = Mth.lerp(dark, 0.82f, 0.30f);
                // a yellow-brown cast in the middle of the column, like smoke backlit by the sun
                float tan = 0.08f * (1f - Math.abs(hf - 0.45f) * 2f);
                r += tan;
                g += tan * 0.6f;
                // lit orange from below by the flames
                float glow = kind == Kind.SMOLDER ? 0f : (float) Mth.clamp(1 - h / 10.0, 0, 1) * 0.7f;
                setColor(Mth.lerp(glow, r, 1.0f), Mth.lerp(glow, g, 0.52f), Mth.lerp(glow, b, 0.22f));
                setAlpha(maxAlpha * fade);
            }
            case FLAME -> {
                setColor(1f, 1f, 1f);
                setAlpha(maxAlpha * Mth.clamp(t * 6f, 0f, 1f) * Mth.clamp((1f - t) * 3f, 0f, 1f));
            }
            case EMBER -> {
                setColor(1f, Mth.lerp(t, 0.8f, 0.3f), Mth.lerp(t, 0.35f, 0.05f)); // yellow-white -> dull red
                setAlpha(Mth.clamp((1f - t) * 3f, 0f, 1f));
            }
            default -> {
                float v = 0.8f + (phase / Mth.TWO_PI) * 0.2f; // pale grey to off-white
                setColor(v, v * 0.98f, v * 0.96f);
                setAlpha(maxAlpha * fade);
            }
        }
    }

    @Override
    public float getQuadSize(float partialTick) {
        float t = (age + partialTick) / lifetime;
        return switch (kind) {
            // smoke entrains air and widens as it rises
            case SMOKE -> baseSize * (0.5f + 2.6f * Mth.sqrt(t));
            case BILLOW -> baseSize * (0.6f + 3.2f * Mth.sqrt(t));
            case PALL -> baseSize * (0.7f + 0.9f * t);
            case SMOLDER -> baseSize * (0.6f + 2.2f * t);
            case FLAME -> baseSize * (0.7f + 0.3f * Mth.clamp(t * 4f, 0f, 1f)) * (1f - 0.35f * t);
            case EMBER -> baseSize * (0.8f + 0.2f * Mth.sin((age + partialTick) * 1.9f + phase));
            default -> baseSize;
        };
    }

    /** Horizontal extent for culling big particles by size, not by their centre (see QuadParticleGroupMixin). */
    public float cullRadius() {
        return kind == Kind.SMOKE || kind == Kind.BILLOW || kind == Kind.PALL || kind == Kind.FLAME ? getQuadSize(0f) : 0f;
    }

    @Override
    public void extract(QuadParticleRenderState state, Camera camera, float partialTick) {
        float keep = alpha;
        if (kind == Kind.SMOKE || kind == Kind.BILLOW || kind == Kind.PALL) {
            // near fade: a puff right in front of the camera dissolves instead of filling the screen with one quad
            double px = Mth.lerp(partialTick, xo, x) - camera.position().x;
            double py = Mth.lerp(partialTick, yo, y) - camera.position().y;
            double pz = Mth.lerp(partialTick, zo, z) - camera.position().z;
            float size = getQuadSize(partialTick);
            float dist = (float) Math.sqrt(px * px + py * py + pz * pz);
            alpha *= Mth.clamp((dist - size * 0.4f) / (size * 1.6f), 0f, 1f);
            if (alpha < 0.01f) {
                alpha = keep;
                return;
            }
        }
        extractOriented(state, camera, partialTick);
        alpha = keep;
    }

    private void extractOriented(QuadParticleRenderState state, Camera camera, float partialTick) {
        Quaternionf q = new Quaternionf();
        switch (kind) {
            // flames stay upright and only turn to face the camera around the vertical axis
            case FLAME -> FacingCameraMode.LOOKAT_Y.setRotation(q, camera, partialTick);
            default -> {
                super.extract(state, camera, partialTick);
                return;
            }
        }
        extractRotatedQuad(state, camera, q, partialTick);
    }

    @Override
    protected int getLightCoords(float a) {
        if (kind == Kind.FLAME || kind == Kind.EMBER) return LightCoordsUtil.FULL_BRIGHT;
        int lc = super.getLightCoords(a);
        if (kind == Kind.SMOKE || kind == Kind.BILLOW) {
            // the base of the column is lit by the fire (visible as an orange glow at night)
            // (block light is warm and only dominates at night, so this is subtle by day and an orange glow at night)
            int glow = (int) (15 * Mth.clamp(1.15 - (y - groundY) / 32.0, 0, 1));
            return LightCoordsUtil.pack(Math.max(LightCoordsUtil.block(lc), glow), LightCoordsUtil.sky(lc));
        }
        return lc;
    }

    @Override
    protected Layer getLayer() {
        return Layer.TRANSLUCENT;
    }

    public static ParticleProvider<SimpleParticleType> provider(Kind kind, SpriteSet sprites) {
        return (type, level, x, y, z, xa, ya, za, random) -> new WildfireParticle(level, x, y, z, xa, ya, za, kind, sprites);
    }
}
