package com.realisticdisasters.client.volcano;

import java.util.ArrayList;
import java.util.List;

import com.realisticdisasters.client.ClientDisaster;
import com.realisticdisasters.disaster.Phase;
import com.realisticdisasters.disaster.volcano.VolcanoDisaster;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Volumetric voxel clouds for a volcano, drawn the way Minecraft draws its own clouds: solid, shaded boxes. The cloud is
 * a density field sampled on a coarse grid (3-8 blocks per cell depending on the eruption size):
 * <ul>
 *   <li>the eruption column: a jet at the vent that widens with height, bending downwind;</li>
 *   <li>the umbrella cloud spreading at neutral buoyancy and stretching far downwind, with fall-out curtains below;</li>
 *   <li>the pyroclastic flow: a ground-hugging cloud with a tall billowing head, lofting behind the front;</li>
 *   <li>loose puffs (steam off lava, blast jets, bomb smoke) as small free-floating boxes.</li>
 * </ul>
 * Billows are 3D noise advected upward with the updraft. Each cell is a box whose size follows its density, so the cloud
 * swells and churns smoothly instead of popping. Shading: lit tops, darker sides and bottoms (like vanilla clouds),
 * self-shadowing from the cloud above, the lava glow from below at the base, lightning flashes, and the time of day.
 */
public final class VoxelCloud {
    static final class Cell {
        float size, target;
        float shade;      // base brightness 0..1 (before lighting)
        float glow;       // lava light 0..1
        byte kind;        // 0 = dense ash, 1 = fall-out haze (translucent), 2 = pyroclastic flow
    }

    static final class Puff {
        double x, y, z, vx, vy, vz;
        float size, grow, r, g, b, glow;
        int age, life;
    }

    /** A finished mesh: quads (4 vertices each) relative to {@link #ox}/{@link #oy}/{@link #oz}. */
    static final class Mesh {
        final float[] pos;
        final int[] color;
        final int quads;
        final double ox, oy, oz;
        final AABB bounds;

        Mesh(float[] pos, int[] color, int quads, double ox, double oy, double oz, AABB bounds) {
            this.pos = pos;
            this.color = color;
            this.quads = quads;
            this.ox = ox;
            this.oy = oy;
            this.oz = oz;
            this.bounds = bounds;
        }
    }

    /** Voxel fall-out curtains read as glass pillars at this resolution; fall-out is shown by ash flakes and haze instead. */
    private static final boolean FALLOUT_CURTAINS = false;

    private final Long2ObjectOpenHashMap<Cell> cells = new Long2ObjectOpenHashMap<>();
    private final List<Puff> puffs = new ArrayList<>();
    private final ImprovedNoise noise;
    private final ImprovedNoise streaks;
    final int cell;
    private long ticks;
    private double minX, minY, minZ, maxX, maxY, maxZ;
    private float flash;
    private double flashX, flashY, flashZ;

    volatile Mesh dense, haze;

    VoxelCloud(int intensity, long seed) {
        RandomSource r = RandomSource.create(seed);
        this.noise = new ImprovedNoise(r);
        this.streaks = new ImprovedNoise(r);
        this.cell = new int[]{3, 3, 4, 5, 6, 8}[Mth.clamp(intensity, 1, 5)];
    }

    // ------------------------------------------------------------------ puffs

    Puff puff(double x, double y, double z, double vx, double vy, double vz, float size, float grow, int life,
              float r, float g, float b, float glow) {
        if (puffs.size() > 900) return null;
        Puff p = new Puff();
        p.x = x;
        p.y = y;
        p.z = z;
        p.vx = vx;
        p.vy = vy;
        p.vz = vz;
        p.size = size;
        p.grow = grow;
        p.life = life;
        p.r = r;
        p.g = g;
        p.b = b;
        p.glow = glow;
        puffs.add(p);
        return p;
    }

    // ------------------------------------------------------------------ simulation

    void tick(ClientDisaster d, ClientLevel level, float daylight) {
        ticks++;
        if (ticks % 2 == 0) sampleField(d, level);
        // Ease every cell toward its target size: the cloud swells and shrinks smoothly.
        ObjectIterator<Long2ObjectMap.Entry<Cell>> it = cells.long2ObjectEntrySet().fastIterator();
        while (it.hasNext()) {
            Cell c = it.next().getValue();
            c.size += (c.target - c.size) * (c.target > c.size ? 0.22f : 0.12f);
            if (c.target <= 0f && c.size < 0.04f) it.remove();
        }
        for (int i = puffs.size() - 1; i >= 0; i--) {
            Puff p = puffs.get(i);
            if (++p.age >= p.life) {
                puffs.remove(i);
                continue;
            }
            p.x += p.vx;
            p.y += p.vy;
            p.z += p.vz;
            p.vx *= 0.96;
            p.vz *= 0.96;
            p.vy = p.vy * 0.94 + 0.01;
            p.size += p.grow;
        }
        detectLightning(d, level);
        if (ticks % 2 == 0) buildMesh(level, daylight);
    }

    private void detectLightning(ClientDisaster d, ClientLevel level) {
        flash *= 0.75f;
        Vec3 c = d.pos();
        double r = 80;
        var bolts = level.getEntitiesOfClass(LightningBolt.class, new AABB(c.x - r, c.y - 20, c.z - r, c.x + r, c.y + 300, c.z + r));
        if (!bolts.isEmpty()) {
            LightningBolt b = bolts.getFirst();
            flash = 1f;
            flashX = b.getX();
            flashY = b.getY() + 30;
            flashZ = b.getZ();
        }
    }

    /** Cell coordinates are packed relative to an anchor cell at the volcano, so far-out worlds don't overflow. */
    private int anchorX = Integer.MIN_VALUE, anchorZ;

    private long key(int x, int y, int z) {
        x -= anchorX;
        z -= anchorZ;
        return ((long) (x & 0x3FFFFF) << 42) | ((long) (y & 0xFFFFF) << 22) | (z & 0x3FFFFF);
    }

    private Cell cellAt(int x, int y, int z) {
        return cells.get(key(x, y, z));
    }

    private void set(int ix, int iy, int iz, float density, float shade, float glow, int kind) {
        // A narrow transition band: cells are mostly full or empty (crisp like vanilla clouds), only edges swell.
        float t = smooth((density - 0.08f) / 0.14f);
        if (t <= 0.02f) return;
        long k = key(ix, iy, iz);
        Cell c = cells.get(k);
        if (c == null) {
            c = new Cell();
            cells.put(k, c);
        }
        if (t > c.target) {
            c.target = t;
            c.shade = shade;
            c.glow = glow;
            c.kind = (byte) kind;
        }
    }

    private static float smooth(float t) {
        t = Mth.clamp(t, 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    /** Re-evaluates the density field on the grid. */
    private void sampleField(ClientDisaster d, ClientLevel level) {
        if (anchorX == Integer.MIN_VALUE) {
            anchorX = Mth.floor(d.pos().x / cell);
            anchorZ = Mth.floor(d.pos().z / cell);
        }
        for (Cell c : cells.values()) c.target = 0f;
        minX = minY = minZ = Double.MAX_VALUE;
        maxX = maxY = maxZ = -Double.MAX_VALUE;
        float time = ticks * 0.05f;
        sampleColumn(d, time);
        samplePyroclasticFlow(d, level, time);
        for (Puff p : puffs) include(p.x, p.y, p.z, p.size);
    }

    private void include(double x, double y, double z, double pad) {
        minX = Math.min(minX, x - pad);
        minY = Math.min(minY, y - pad);
        minZ = Math.min(minZ, z - pad);
        maxX = Math.max(maxX, x + pad);
        maxY = Math.max(maxY, y + pad);
        maxZ = Math.max(maxZ, z + pad);
    }

    private double billow(double x, double y, double z, float time, double rise) {
        double f = 1.0 / (cell * 3.2);
        double n = noise.noise(x * f, (y - time * rise) * f, z * f) * 0.65
                + noise.noise(x * f * 2.3 + 31.7, (y - time * rise * 1.3) * f * 2.3, z * f * 2.3) * 0.35;
        return n;
    }

    private void sampleColumn(ClientDisaster d, float time) {
        float col = VolcanoVisual.column(d);
        if (col <= 0.01f) return;
        int i = d.intensity;
        Vec3 vent = VolcanoVisual.vent(d);
        Vec3 wind = VolcanoVisual.wind(d);
        double fullH = VolcanoVisual.plumeHeight(d);
        double height = fullH * (0.25 + 0.75 * col);
        // After the eruption the column detaches from the vent and the remains drift off.
        double hMin = d.phase == Phase.AFTERMATH ? fullH * (1 - col) * 0.8 : 0;
        double hu = height * 0.8;                       // neutral buoyancy: the umbrella level
        double r0 = 2 + i * 1.5;
        double ru = height * 0.42 * Math.min(1, 0.4 + col);
        double drift = ru * 0.55 + (d.phase == Phase.AFTERMATH ? (1 - col) * fullH * 0.5 : 0);
        Vec3 capCenter = vent.add(lean(wind, hu, height)).add(wind.scale(drift));
        double rise = 6 + i * 2;                        // blocks per second of the billows' updraft
        double c = cell;

        int y0 = Mth.floor((vent.y + hMin) / c), y1 = Mth.ceil((vent.y + height * 1.08) / c);
        for (int iy = y0; iy <= y1; iy++) {
            double wy = (iy + 0.5) * c;
            double h = wy - vent.y;
            double hf = Mth.clamp(h / height, 0, 1.2);
            // --- the column (up to the umbrella)
            if (h >= hMin - c && h <= hu + c) {
                Vec3 axis = vent.add(lean(wind, Math.max(0, h), height));
                double r = r0 + 0.24 * Math.max(0, h);
                double bound = r * 1.5 + c;
                int ax0 = Mth.floor((axis.x - bound) / c), ax1 = Mth.floor((axis.x + bound) / c);
                int az0 = Mth.floor((axis.z - bound) / c), az1 = Mth.floor((axis.z + bound) / c);
                float glowH = (float) Math.min(height * 0.18, 12 + i * 6);
                for (int ix = ax0; ix <= ax1; ix++)
                    for (int iz = az0; iz <= az1; iz++) {
                        double wx = (ix + 0.5) * c, wz = (iz + 0.5) * c;
                        double dist = Math.hypot(wx - axis.x, wz - axis.z);
                        double dens = 1 - dist / r;
                        if (dens < -0.6) continue;
                        dens += billow(wx, wy, wz, time, rise) * 0.55;
                        if (h < hMin) dens -= (hMin - h) / (c * 2);
                        float shade = (float) (0.32 + 0.5 * Math.pow(hf, 0.7));
                        float glow = h < glowH ? (float) (1 - h / glowH) * Math.min(1f, d.strength() * 1.3f) : 0f;
                        set(ix, iy, iz, (float) dens, shade, glow, 0);
                    }
                include(axis.x, wy, axis.z, bound);
            }
            // --- the umbrella: a lens-shaped cloud, thick in the middle, stretched downwind
            double thick = height * 0.17;
            if (Math.abs(h - hu) <= thick * 1.1 && ru > c) {
                double alongR = ru * 1.7, acrossR = ru;
                double bound = alongR + c;
                int ux0 = Mth.floor((capCenter.x - bound) / c), ux1 = Mth.floor((capCenter.x + bound) / c);
                int uz0 = Mth.floor((capCenter.z - bound) / c), uz1 = Mth.floor((capCenter.z + bound) / c);
                for (int ix = ux0; ix <= ux1; ix++)
                    for (int iz = uz0; iz <= uz1; iz++) {
                        double wx = (ix + 0.5) * c, wz = (iz + 0.5) * c;
                        double dx = wx - capCenter.x, dz = wz - capCenter.z;
                        double along = dx * wind.x + dz * wind.z, across = -dx * wind.z + dz * wind.x;
                        double e = Math.sqrt(sq(along / alongR) + sq(across / acrossR));
                        if (e > 1.25) continue;
                        double half = thick * Math.max(0.15, 1 - e * e);
                        double mid = hu + thick * 0.15 * (1 - e);   // domed top
                        double dens = Math.min(1 - e, 1 - Math.abs(h - mid) / half);
                        dens += billow(wx, wy, wz, time, rise * 0.15) * 0.5;
                        float shade = (float) (0.55 + 0.25 * Mth.clamp((h - (mid - half)) / (2 * half), 0, 1));
                        set(ix, iy, iz, (float) dens, shade, 0f, 0);
                    }
                include(capCenter.x, wy, capCenter.z, bound);
            }
            // --- fall-out curtains: grey streaks hanging from the downwind half of the umbrella
            if (FALLOUT_CURTAINS && h < hu - thick * 0.6 && h > 0 && d.phase != Phase.BUILDUP && i >= 2) {
                double alongR = ru * 1.7, acrossR = ru * 0.8;
                double bound = alongR;
                int ux0 = Mth.floor((capCenter.x - bound) / c), ux1 = Mth.floor((capCenter.x + bound) / c);
                int uz0 = Mth.floor((capCenter.z - bound) / c), uz1 = Mth.floor((capCenter.z + bound) / c);
                for (int ix = ux0; ix <= ux1; ix++)
                    for (int iz = uz0; iz <= uz1; iz++) {
                        double wx = (ix + 0.5) * c, wz = (iz + 0.5) * c;
                        double dx = wx - capCenter.x, dz = wz - capCenter.z;
                        double along = dx * wind.x + dz * wind.z, across = -dx * wind.z + dz * wind.x;
                        if (along < -ru * 0.2) continue;
                        double e = Math.sqrt(sq(along / alongR) + sq(across / acrossR));
                        if (e > 1) continue;
                        double s = streaks.noise(wx * 0.04, time * 0.05, wz * 0.04);
                        double dens = (s - 0.15) * 2.2 * (1 - e) * col;
                        set(ix, iy, iz, (float) dens, 0.42f, 0f, 1);
                    }
            }
        }
    }

    private void samplePyroclasticFlow(ClientDisaster d, ClientLevel level, float time) {
        if (d.data.length <= VolcanoDisaster.D_PDC_HEAD || d.data[VolcanoDisaster.D_PDC_ON] < 0.5f) return;
        double rf = d.data[VolcanoDisaster.D_PDC_RADIUS];
        double angle = d.data[VolcanoDisaster.D_PDC_ANGLE];
        double half = d.data[VolcanoDisaster.D_PDC_WIDTH];
        double head = d.data[VolcanoDisaster.D_PDC_HEAD];
        int i = d.intensity;
        double body = 30 + i * 10;
        Vec3 o = d.pos();
        double c = cell;
        double outer = rf + c;
        int x0 = Mth.floor((o.x - outer) / c), x1 = Mth.floor((o.x + outer) / c);
        int z0 = Mth.floor((o.z - outer) / c), z1 = Mth.floor((o.z + outer) / c);
        double inner = Math.max(0, rf - body);
        for (int ix = x0; ix <= x1; ix++)
            for (int iz = z0; iz <= z1; iz++) {
                double wx = (ix + 0.5) * c, wz = (iz + 0.5) * c;
                double r = Math.hypot(wx - o.x, wz - o.z);
                if (r > outer || r < inner) continue;
                if (half < Math.PI - 0.01) {
                    double diff = Math.abs(Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(wz - o.z, wx - o.x) - angle)));
                    if (diff > Math.toDegrees(half)) continue;
                }
                double ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(wx), Mth.floor(wz));
                double behind = rf - r;                                  // 0 at the front
                double headK = Math.max(0, 1 - behind / (body * 0.35));
                double top = ground + head * (0.45 + 0.75 * headK) + behind * 0.25;   // the body lofts behind the head
                double bottom = ground + behind * 0.12;
                int y0 = Mth.floor(bottom / c), y1 = Mth.ceil(top / c);
                for (int iy = y0; iy <= y1; iy++) {
                    double wy = (iy + 0.5) * c;
                    double v = (wy - bottom) / Math.max(1, top - bottom);
                    double dens = 1 - Math.abs(v - 0.45) * 1.6 - Math.max(0, -behind) / c;
                    dens += billow(wx, wy, wz, time, 3) * 0.6;
                    dens -= Math.max(0, behind - body * 0.8) / (body * 0.2);  // thinning tail
                    float shade = (float) (0.45 + 0.3 * v);
                    float glow = headK > 0.5 && v < 0.3 ? 0.35f : 0f;
                    set(ix, iy, iz, (float) dens, shade, glow, 2);
                }
                include(wx, top, wz, c);
                include(wx, ground, wz, c);
            }
    }

    private static Vec3 lean(Vec3 wind, double h, double height) {
        return wind.scale(0.35 * h * h / height);
    }

    private static double sq(double v) {
        return v * v;
    }

    // ------------------------------------------------------------------ meshing

    private static final float[] FACE_SHADE = {0.62f, 1.0f, 0.82f, 0.82f, 0.9f, 0.9f}; // down, up, north, south, west, east

    private void buildMesh(ClientLevel level, float daylight) {
        if (cells.isEmpty() && puffs.isEmpty()) {
            dense = null;
            haze = null;
            return;
        }
        double ox = Math.floor((minX + maxX) / 2), oy = Math.floor((minY + maxY) / 2), oz = Math.floor((minZ + maxZ) / 2);
        if (!Double.isFinite(ox)) {
            ox = oy = oz = 0;
        }
        MeshBuilder solid = new MeshBuilder(), translucent = new MeshBuilder();
        float c = cell;
        float light = Mth.clamp(daylight, 0.06f, 1f);
        ObjectIterator<Long2ObjectMap.Entry<Cell>> it = cells.long2ObjectEntrySet().fastIterator();
        while (it.hasNext()) {
            Long2ObjectMap.Entry<Cell> e = it.next();
            Cell cl = e.getValue();
            if (cl.size < 0.04f) continue;
            long k = e.getLongKey();
            int ix = (int) (k >> 42), iy = (int) ((k >> 22) & 0xFFFFF), iz = (int) (k & 0x3FFFFF);
            ix = (ix << 10) >> 10;   // sign-extend 22 bits
            iy = (iy << 12) >> 12;   // sign-extend 20 bits
            iz = (iz << 10) >> 10;
            ix += anchorX;
            iz += anchorZ;
            float half = c * 0.5f * (0.35f + 0.65f * cl.size);
            double wx = (ix + 0.5) * c, wy = (iy + 0.5) * c, wz = (iz + 0.5) * c;
            float cx = (float) (wx - ox), cy = (float) (wy - oy), cz = (float) (wz - oz);
            boolean full = cl.size > 0.97f;
            if (!full && cl.kind != 1 && isolated(ix, iy, iz)) continue; // no lone specks floating around the cloud
            // Self-shadowing: the more cloud above, the darker (dark undersides, sunlit crowns).
            int above = 0;
            for (int up = 1; up <= 3; up++) {
                Cell a = cellAt(ix, iy + up, iz);
                if (a != null && a.size > 0.5f) above++;
            }
            float shadow = 1f - above * 0.13f;
            float base = cl.shade * shadow;
            float fl = flash > 0.05f ? flash * Math.max(0, 1 - (float) Math.sqrt(sq(wx - flashX) + sq(wy - flashY) + sq(wz - flashZ)) / 90f) : 0f;
            boolean hazeCell = cl.kind == 1;
            MeshBuilder out = hazeCell ? translucent : solid;
            int alpha = hazeCell ? (int) (70 * cl.size) : 255;
            for (int face = 0; face < 6; face++) {
                if (full && !hazeCell) {
                    Cell n = switch (face) {
                        case 0 -> cellAt(ix, iy - 1, iz);
                        case 1 -> cellAt(ix, iy + 1, iz);
                        case 2 -> cellAt(ix, iy, iz - 1);
                        case 3 -> cellAt(ix, iy, iz + 1);
                        case 4 -> cellAt(ix - 1, iy, iz);
                        default -> cellAt(ix + 1, iy, iz);
                    };
                    if (n != null && n.size > 0.97f && n.kind != 1) continue;
                }
                float s = base * FACE_SHADE[face];
                // warm, slightly brownish ash
                float r = s * 0.98f * light, g = s * 0.92f * light, b = s * 0.86f * light;
                if (cl.kind == 2) {
                    r = s * 0.9f * light;
                    g = s * 0.86f * light;
                    b = s * 0.82f * light;
                }
                if (cl.glow > 0) {
                    // Lava light from below: strongest on the undersides, dominant at night.
                    float night = 1f - light;
                    float gk = cl.glow * (face == 0 ? 1f : face == 1 ? 0.25f : 0.6f) * (0.2f + 0.9f * night * night);
                    r += 0.95f * gk;
                    g += 0.38f * gk;
                    b += 0.1f * gk;
                }
                if (fl > 0) {
                    r += 0.75f * fl;
                    g += 0.72f * fl;
                    b += 0.85f * fl;
                }
                int argb = (alpha << 24) | (clamp255(r) << 16) | (clamp255(g) << 8) | clamp255(b);
                out.face(face, cx, cy, cz, half, argb);
            }
        }
        for (Puff p : puffs) {
            float t = (float) p.age / p.life;
            float half = p.size * 0.5f * Mth.clamp((1 - t) * 3f, 0f, 1f);
            if (half < 0.05f) continue;
            for (int face = 0; face < 6; face++) {
                float s = FACE_SHADE[face];
                float k = p.glow * (1 - t);
                float r = p.r * s * light + 0.9f * k, g = p.g * s * light + 0.35f * k, b = p.b * s * light + 0.08f * k;
                int argb = 0xFF000000 | (clamp255(r) << 16) | (clamp255(g) << 8) | clamp255(b);
                solid.face(face, (float) (p.x - ox), (float) (p.y - oy), (float) (p.z - oz), half, argb);
            }
        }
        AABB bounds = new AABB(minX, minY, minZ, maxX, maxY, maxZ).inflate(cell);
        dense = solid.build(ox, oy, oz, bounds);
        haze = translucent.build(ox, oy, oz, bounds);
    }

    private boolean isolated(int ix, int iy, int iz) {
        return weak(cellAt(ix + 1, iy, iz)) && weak(cellAt(ix - 1, iy, iz)) && weak(cellAt(ix, iy + 1, iz))
                && weak(cellAt(ix, iy - 1, iz)) && weak(cellAt(ix, iy, iz + 1)) && weak(cellAt(ix, iy, iz - 1));
    }

    private static boolean weak(Cell c) {
        return c == null || c.size < 0.5f;
    }

    private static int clamp255(float v) {
        return Mth.clamp((int) (v * 255f), 0, 255);
    }

    /** Collects quads for one render type. */
    static final class MeshBuilder {
        private float[] pos = new float[4096 * 12];
        private int[] col = new int[4096];
        private int quads;

        void face(int face, float x, float y, float z, float h, int argb) {
            if (quads == col.length) {
                pos = java.util.Arrays.copyOf(pos, pos.length * 2);
                col = java.util.Arrays.copyOf(col, col.length * 2);
            }
            int o = quads * 12;
            float x0 = x - h, x1 = x + h, y0 = y - h, y1 = y + h, z0 = z - h, z1 = z + h;
            float[] v = switch (face) {
                case 0 -> new float[]{x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1};
                case 1 -> new float[]{x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0};
                case 2 -> new float[]{x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0};
                case 3 -> new float[]{x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1};
                case 4 -> new float[]{x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0};
                default -> new float[]{x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1};
            };
            System.arraycopy(v, 0, pos, o, 12);
            col[quads++] = argb;
        }

        Mesh build(double ox, double oy, double oz, AABB bounds) {
            return quads == 0 ? null : new Mesh(pos, col, quads, ox, oy, oz, bounds);
        }
    }

    int cellCount() {
        return cells.size();
    }
}
