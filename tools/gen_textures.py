#!/usr/bin/env python3
"""Generates the mod's textures: soft, noise-based 64x64 storm textures (realistic look) plus small debris specks.

Deterministic: re-running produces identical PNGs. Requires Pillow.
Usage: python3 tools/gen_textures.py
"""
import math
import os
import random

from PIL import Image

ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources")
PARTICLES = os.path.join(ROOT, "assets", "realisticdisasters", "textures", "particle")

# Light -> dark ramps (4 steps, no gradients). Particles are tinted in code, so clouds stay near-neutral.
CLOUD = [(236, 236, 232), (206, 207, 201), (172, 174, 168), (138, 140, 134)]
DUST = [(232, 228, 220), (206, 201, 192), (176, 170, 160), (144, 138, 128)]  # neutral: tinted in code
DEBRIS = [(118, 86, 54), (92, 66, 40), (86, 120, 52), (64, 92, 38), (128, 128, 128), (98, 98, 98)]

def blob(size, rng, lobes, fill=1.0):
    """Union of a few circles -> boolean mask plus a per-pixel 'height' for shading."""
    circles = []
    for _ in range(lobes):
        r = rng.uniform(size * 0.22, size * 0.36)
        cx = rng.uniform(r * 0.8, size - r * 0.8)
        cy = rng.uniform(r * 0.8, size - r * 0.8)
        circles.append((cx, cy, r))
    mask = {}
    for y in range(size):
        for x in range(size):
            best = None
            for cx, cy, r in circles:
                d = math.hypot(x + 0.5 - cx, y + 0.5 - cy) / r
                if d <= 1.0:
                    # light from top-left: offset distance toward lower right is darker
                    shade = d * 0.55 + ((x - cx) + (y - cy)) / (2.2 * r) * 0.45
                    best = shade if best is None else min(best, shade)
            if best is not None and rng.random() < fill:
                mask[(x, y)] = best
    return mask


def quantize(v, steps):
    v = max(0.0, min(0.999, (v + 0.35) / 1.2))
    return int(v * steps)


def cloud_texture(seed, ramp, size=16, lobes=4, fill=1.0):
    rng = random.Random(seed)
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    mask = blob(size, rng, lobes, fill)
    for (x, y), s in mask.items():
        idx = quantize(s, len(ramp))
        # ordered 2x2 dither at band edges keeps the pixel-art look without smooth gradients
        if (x + y) % 2 == 0 and rng.random() < 0.25:
            idx = min(len(ramp) - 1, idx + 1)
        img.putpixel((x, y), ramp[idx] + (255,))
    # outline the bottom-right edge with the darkest tone, like vanilla smoke puffs
    for (x, y) in list(mask):
        if (x + 1, y) not in mask or (x, y + 1) not in mask:
            img.putpixel((x, y), ramp[-1] + (255,))
    return img


def debris_texture(seed, size=8):
    rng = random.Random(seed)
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    base = rng.choice([(0, 1), (2, 3), (4, 5)])
    cx, cy = rng.randint(2, 5), rng.randint(2, 5)
    for _ in range(rng.randint(4, 9)):
        x = max(0, min(size - 1, cx + rng.randint(-2, 2)))
        y = max(0, min(size - 1, cy + rng.randint(-2, 2)))
        img.putpixel((x, y), DEBRIS[rng.choice(base)] + (255,))
    return img


def fbm(size, rng, octaves=5, base=4):
    """Tileless fractal value noise in 0..1 (numpy), used for soft realistic cloud textures."""
    import numpy as np
    out = np.zeros((size, size))
    amp, total = 1.0, 0.0
    for o in range(octaves):
        cells = base * (2 ** o)
        grid = rng.random((cells + 1, cells + 1))
        xs = np.linspace(0, cells, size, endpoint=False)
        xi = xs.astype(int)
        xf = xs - xi
        xf = xf * xf * (3 - 2 * xf)
        g00 = grid[np.ix_(xi, xi)]
        g10 = grid[np.ix_(xi + 1, xi)]
        g01 = grid[np.ix_(xi, xi + 1)]
        g11 = grid[np.ix_(xi + 1, xi + 1)]
        top = g00 + (g10 - g00) * xf[:, None]
        bot = g01 + (g11 - g01) * xf[:, None]
        out += (top + (bot - top) * xf[None, :]) * amp
        total += amp
        amp *= 0.5
    return out / total


def deck_cloud(seed, size=64):
    """Cloud-base puff: very soft edges (no visible disc) so many overlap into one smooth, flat mass."""
    import numpy as np
    rng = np.random.default_rng(seed)
    n = fbm(size, rng, octaves=5, base=3)
    y, x = np.mgrid[0:size, 0:size] / (size - 1) * 2 - 1
    r = np.sqrt(x * x + (y * 1.25) ** 2)          # slightly flattened
    fall = np.clip(1 - r, 0, 1) ** 1.8           # long, gentle falloff to zero at the edge
    a = np.clip(fall * (0.55 + 0.9 * n), 0, 1)
    light = np.clip(0.66 + 0.2 * (-y * 0.5 + 0.5) + 0.18 * (n - 0.5), 0, 1)
    rgb = (light * 255).astype("uint8")
    return Image.fromarray(np.dstack([rgb, rgb, rgb, (a * 255).astype("uint8")]), "RGBA")


def soft_cloud(seed, size=64, density=1.0, wispy=False):
    """Soft, volumetric-looking cloud puff: noisy round falloff for alpha, top-lit shading for colour."""
    import numpy as np
    rng = np.random.default_rng(seed)
    n = fbm(size, rng, octaves=6 if wispy else 5)
    y, x = np.mgrid[0:size, 0:size] / (size - 1) * 2 - 1
    r = np.sqrt(x * x + y * y)
    # noise pushes the edge in and out -> irregular, billowy outline instead of a disc
    edge = r + (n - 0.5) * 0.9
    falloff = np.clip(1.0 - edge / 0.9, 0, 1)
    falloff = falloff * falloff * (3 - 2 * falloff)
    a = np.clip((n - (0.3 if wispy else 0.12)) * 2.2, 0, 1) * falloff * density
    a = np.clip(a * 2.0, 0, 1)
    # light from above: brighter towards the top, darker underside, plus noise detail
    light = 0.62 + 0.28 * (-y * 0.5 + 0.5) + 0.22 * (n - 0.5)
    light = np.clip(light, 0, 1)
    rgb = (light * 255).astype("uint8")
    img = np.dstack([rgb, rgb, rgb, (a * 255).astype("uint8")])
    return Image.fromarray(img, "RGBA")


def smooth_cloud(seed, size=64):
    """Condensation-funnel puff: dense, smooth core with a soft but well-defined edge and only fine texture."""
    import numpy as np
    rng = np.random.default_rng(seed)
    n = fbm(size, rng, octaves=5, base=6)
    y, x = np.mgrid[0:size, 0:size] / (size - 1) * 2 - 1
    r = np.sqrt(x * x + y * y) + (n - 0.5) * 0.18
    t = np.clip((0.95 - r) / 0.35, 0, 1)
    a = t * t * (3 - 2 * t)
    light = np.clip(0.7 + 0.18 * (-y * 0.5 + 0.5) + 0.12 * (n - 0.5), 0, 1)
    rgb = (light * 255).astype("uint8")
    return Image.fromarray(np.dstack([rgb, rgb, rgb, (a * 255).astype("uint8")]), "RGBA")


def funnel_streak(seed, size=64):
    """Funnel surface texture: fine vertical streaks (laid on the funnel at a slant -> helical striations)."""
    import numpy as np
    rng = np.random.default_rng(seed)
    cols = fbm(size, rng, octaves=4, base=10)[size // 2]          # streak profile across x
    vert = fbm(size, rng, octaves=3, base=2)                       # slow variation along the streaks
    light = np.clip(0.62 + 0.55 * (cols[None, :] - 0.5) + 0.15 * (vert - 0.5), 0, 1)
    y = np.linspace(-1, 1, size)[:, None]
    x = np.linspace(-1, 1, size)[None, :]
    a = np.clip(1 - np.abs(x) ** 2.2, 0, 1) * np.clip(1 - np.abs(y) ** 4, 0, 1) * (0.8 + 0.2 * vert)
    rgb = (light * 255).astype("uint8")
    return Image.fromarray(np.dstack([rgb, rgb, rgb, (np.clip(a, 0, 1) * 255).astype("uint8")]), "RGBA")


def deck_band(seed, size=64):
    """Curved-looking horizontal band for cloud-base striations: long soft streak along x."""
    import numpy as np
    rng = np.random.default_rng(seed)
    along = fbm(size, rng, octaves=4, base=3)[size // 3]           # variation along the band
    fine = fbm(size, rng, octaves=5, base=8)
    y = np.linspace(-1, 1, size)[:, None]
    x = np.linspace(-1, 1, size)[None, :]
    a = np.exp(-(y / 0.42) ** 2) * np.clip(1 - np.abs(x) ** 3, 0, 1) * (0.45 + 0.55 * along[None, :]) * (0.75 + 0.25 * fine)
    light = np.clip(0.7 + 0.25 * (fine - 0.5) + 0.12 * (-y), 0, 1)
    rgb = (light * 255).astype("uint8")
    return Image.fromarray(np.dstack([rgb, rgb, rgb, (np.clip(a, 0, 1) * 255).astype("uint8")]), "RGBA")


def base_tile(seed, size=64):
    """Cloud-base tile: mostly opaque, with rolls/streaks along x and a soft rounded-square rim, so many tiles
    merge into one continuous, structured ceiling (no visible round puffs)."""
    import numpy as np
    rng = np.random.default_rng(seed)
    big = fbm(size, rng, octaves=3, base=2)
    rows = fbm(size, rng, octaves=4, base=6)[:, size // 2]           # roll structure across y
    fine = fbm(size, rng, octaves=5, base=10)
    y = np.linspace(-1, 1, size)[:, None]
    x = np.linspace(-1, 1, size)[None, :]
    # wide, noisy, rounded rim: no straight tile edges even when lightning lights the base
    d = np.sqrt((np.abs(x) ** 4 + np.abs(y) ** 4) ** 0.5) + (big - 0.5) * 0.35
    rim = np.clip((1.0 - d) / 0.75, 0, 1)
    rim = rim * rim * (3 - 2 * rim)
    a = np.clip(rim * (0.82 + 0.3 * (big - 0.5)), 0, 1)
    light = 0.55 + 0.45 * (rows[:, None] - 0.5) * 2 * 0.6 + 0.25 * (fine - 0.5) + 0.2 * (big - 0.5)
    light = np.clip(light, 0, 1)
    rgb = (light * 255).astype("uint8")
    return Image.fromarray(np.dstack([rgb, rgb, rgb, (a * 255).astype("uint8")]), "RGBA")


def realistic_rain(seed, w=64, h=256):
    """Replacement for vanilla's rain.png: dense, thin, tapered, semi-transparent grey-white streaks.
    Vanilla maps the full width to one block and 64 px of height to one block, tiling vertically."""
    import numpy as np
    rng = np.random.default_rng(seed)
    a = np.zeros((h, w))
    for _ in range(48):
        x = rng.integers(0, w)
        length = int(rng.integers(14, 52))
        y0 = int(rng.integers(0, h))
        peak = rng.uniform(0.12, 0.42)
        t = np.linspace(0, 1, length)
        profile = np.sin(np.pi * t) ** 1.5 * peak        # tapered both ends (motion blur look)
        for i, v in enumerate(profile):
            yy = (y0 + i) % h
            a[yy, x] = max(a[yy, x], v)
            if peak > 0.36 and x + 1 < w:                  # a few heavier near-field drops
                a[yy, x + 1] = max(a[yy, x + 1], v * 0.35)
    rgb = np.zeros((h, w, 3), dtype="uint8")
    rgb[..., 0], rgb[..., 1], rgb[..., 2] = 208, 214, 222
    return Image.fromarray(np.dstack([rgb, (np.clip(a, 0, 1) * 255).astype("uint8")]), "RGBA")


def rain_shaft(seed, size=64):
    """Translucent rain curtain: soft vertical streaks fading out at the top and bottom."""
    import numpy as np
    rng = np.random.default_rng(seed)
    cols = fbm(size, rng, octaves=3, base=8)[0]  # one row of noise -> per-column density
    streak = np.tile(np.clip((cols - 0.35) * 2.2, 0, 1), (size, 1))
    y = np.linspace(-1, 1, size)[:, None]
    x = np.linspace(-1, 1, size)[None, :]
    fade = np.clip(1 - np.abs(y) ** 3, 0, 1) * np.clip(1 - np.abs(x) ** 2, 0, 1)
    detail = fbm(size, rng, octaves=4, base=2) * 0.6 + 0.4
    a = np.clip(streak * fade * detail, 0, 1) * 0.8
    rgb = np.full((size, size), 205, dtype="uint8")
    return Image.fromarray(np.dstack([rgb, rgb, rgb, (a * 255).astype("uint8")]), "RGBA")


def whitewater(seed, size=64):
    """Turbulent white water: a dense, billowing foam mass with darker churned veins and a ragged, soft edge.
    Greyscale; tinted in code (white-grey at sea, brown-grey once it carries sediment inland)."""
    import numpy as np
    rng = np.random.default_rng(seed)
    n = fbm(size, rng, octaves=6, base=3)
    veins = np.abs(fbm(size, rng, octaves=5, base=5) - 0.5) * 2      # ridged noise -> churn lines
    y, x = np.mgrid[0:size, 0:size] / (size - 1) * 2 - 1
    r = np.sqrt(x * x + y * y) + (n - 0.5) * 0.8
    t = np.clip((0.95 - r) / 0.2, 0, 1)                                # crisper, more liquid edge
    a = np.clip(t * t * (3 - 2 * t) * (0.9 + 0.4 * n), 0, 1)
    billow = fbm(size, rng, octaves=4, base=4)                         # bright foam lumps, shaded undersides
    light = (0.8 + 0.15 * (-y * 0.5 + 0.5) + 0.3 * (billow - 0.5) + 0.12 * (n - 0.5)
             - 0.1 * np.clip(0.25 - veins, 0, 1) / 0.25)
    rgb = (np.clip(light, 0, 1) * 255).astype("uint8")
    return Image.fromarray(np.dstack([rgb, rgb, rgb, (a * 255).astype("uint8")]), "RGBA")


def droplets(seed, size=32):
    """Spray droplets: a loose cluster of small soft dots of varying size."""
    import numpy as np
    rng = np.random.default_rng(seed)
    a = np.zeros((size, size))
    yy, xx = np.mgrid[0:size, 0:size]
    for _ in range(rng.integers(7, 13)):
        cx, cy = rng.normal(size / 2, size / 6, 2)
        rad = rng.uniform(0.8, 2.6)
        d = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2) / rad
        a = np.maximum(a, np.clip(1.2 - d, 0, 1))
    light = np.full((size, size), 235, dtype="uint8")
    return Image.fromarray(np.dstack([light, light, light, (np.clip(a, 0, 1) * 255).astype("uint8")]), "RGBA")


def flood_debris(seed, size=32):
    """Floating debris: splintered wood, a leafy twig or a dark clump of mud and plants (coloured, not tinted)."""
    import numpy as np
    rng = np.random.default_rng(seed)
    yy, xx = np.mgrid[0:size, 0:size].astype(float)
    a = np.zeros((size, size))
    col = np.zeros((size, size, 3))
    kinds = [((112, 82, 52), (84, 60, 38)), ((70, 92, 44), (52, 70, 34)), ((74, 62, 48), (52, 44, 34))]
    for _ in range(rng.integers(2, 5)):
        light, dark = kinds[rng.integers(0, 3)]
        cx, cy = rng.uniform(size * 0.3, size * 0.7, 2)
        ang = rng.uniform(0, np.pi)
        length, width = rng.uniform(4, 11), rng.uniform(1.0, 2.6)
        u = (xx - cx) * np.cos(ang) + (yy - cy) * np.sin(ang)
        v = -(xx - cx) * np.sin(ang) + (yy - cy) * np.cos(ang)
        d = np.maximum(np.abs(u) / length, np.abs(v) / width)
        m = np.clip((1.0 - d) * 3, 0, 1)
        grain = 0.5 + 0.5 * np.sin(v * 0.9 + u * 0.15 + rng.uniform(0, 6))
        c = np.array(dark)[None, None, :] + (np.array(light) - np.array(dark))[None, None, :] * grain[:, :, None]
        col = np.where(m[:, :, None] > a[:, :, None], c, col)
        a = np.maximum(a, m)
    return Image.fromarray(np.dstack([col.astype("uint8"), (a * 255).astype("uint8")]), "RGBA")


def periodic_field(rng, size, frames, waves, kmax, aniso=1.0, cycles=(1, 2)):
    """Tileable (in x, y and time) noise: sum of plane waves with integer wave numbers. Returns [frames, size, size]."""
    import numpy as np
    y, x = np.mgrid[0:size, 0:size] / size
    out = np.zeros((frames, size, size))
    for _ in range(waves):
        kx, ky = 0, 0
        while kx == 0 and ky == 0:
            kx, ky = rng.integers(-kmax, kmax + 1), rng.integers(-int(kmax * aniso), int(kmax * aniso) + 1)
        amp = 1.0 / (kx * kx + ky * ky) ** 0.5
        phase = rng.uniform(0, 2 * np.pi)
        w = rng.integers(cycles[0], cycles[1] + 1) * rng.choice([-1, 1])
        for f in range(frames):
            out[f] += amp * np.sin(2 * np.pi * (kx * x + ky * y) + phase + 2 * np.pi * w * f / frames)
    lo, hi = out.min(), out.max()
    return (out - lo) / (hi - lo)


def tsunami_block_textures(frames=32):
    """Animated 16x16 block textures for the wave surface, at vanilla water's texel density so the wave and the
    water blocks read as one body. Greyscale (tinted in code like vanilla water). Frames scroll down the face, as the
    water tumbles forward."""
    import numpy as np
    rng = np.random.default_rng(4242)
    size = 64  # one frame covers 4x4 blocks at 16 px per block, so the pattern doesn't repeat every block
    out = os.path.join(ROOT, "assets", "realisticdisasters", "textures", "block")
    os.makedirs(out, exist_ok=True)

    # Turbulent water: vanilla-like grey with stronger, streaky churn; more opaque than calm water (silt and air).
    churn = periodic_field(rng, size, frames, 40, 10, aniso=1.6)
    streak = periodic_field(rng, size, frames, 24, 14, aniso=2.5)
    water = np.zeros((size * frames, size, 4), dtype=np.uint8)
    for f in range(frames):
        shift = (f * size) // frames                    # one full tile of downward motion per loop
        c = np.roll(churn[f], shift, axis=0)
        st = np.roll(streak[f], shift, axis=0)
        luma = 128 + 70 * c + 35 * (st - 0.5)
        water[f * size:(f + 1) * size, :, 0:3] = np.clip(luma, 0, 255)[:, :, None]
        water[f * size:(f + 1) * size, :, 3] = 226
    Image.fromarray(water, "RGBA").save(os.path.join(out, "tsunami_water.png"))

    # White water: dense aerated foam with lacy gaps and grey shading between the bubbles.
    body = periodic_field(rng, size, frames, 40, 7)
    fine = periodic_field(rng, size, frames, 48, 18)
    foam = np.zeros((size * frames, size, 4), dtype=np.uint8)
    for f in range(frames):
        shift = (2 * f * size) // frames
        b = np.roll(body[f], shift, axis=0)
        fi = np.roll(fine[f], shift, axis=0)
        luma = 196 + 59 * fi - 30 * (1 - b)
        alpha = np.clip((0.35 + 0.9 * b + 0.25 * (fi - 0.5)) * 255, 60, 255)
        foam[f * size:(f + 1) * size, :, 0:3] = np.clip(luma, 0, 255)[:, :, None]
        foam[f * size:(f + 1) * size, :, 3] = alpha
    Image.fromarray(foam, "RGBA").save(os.path.join(out, "tsunami_foam.png"))
    for name in ("tsunami_water", "tsunami_foam"):
        with open(os.path.join(out, name + ".png.mcmeta"), "w") as fh:
            fh.write('{\n  "animation": {\n    "frametime": 2\n  }\n}\n')


def write(img, name):
    os.makedirs(PARTICLES, exist_ok=True)
    img.save(os.path.join(PARTICLES, name + ".png"))


def main():
    # Realistic, soft 64x64 textures for the storm volume (tinted in code).
    for i in range(4):
        write(smooth_cloud(100 + i), f"funnel_cloud_{i}")
        write(deck_cloud(500 + i), f"deck_cloud_{i}")
        write(funnel_streak(600 + i), f"funnel_streak_{i}")
        write(deck_band(700 + i), f"deck_band_{i}")
        write(base_tile(800 + i), f"base_tile_{i}")

        write(soft_cloud(200 + i, density=0.85, wispy=True), f"dust_cloud_{i}")
        write(rain_shaft(400 + i), f"rain_shaft_{i}")
        write(debris_texture(300 + i), f"storm_debris_{i}")
        write(whitewater(700 + i), f"wave_water_{i}")
        write(soft_cloud(800 + i, density=0.7, wispy=True), f"sea_spray_{i}")
        write(droplets(900 + i), f"wave_foam_{i}")
        write(flood_debris(1000 + i), f"flood_debris_{i}")

    # Realistic rain (overrides vanilla's blue rain texture; applies to all rain in the game).
    env = os.path.join(ROOT, "assets", "minecraft", "textures", "environment")
    os.makedirs(env, exist_ok=True)
    realistic_rain(900).save(os.path.join(env, "rain.png"))

    tsunami_block_textures()

    # 16x16 mod icon (tornado silhouette on a storm sky), upscaled with nearest neighbour for the mod list.
    icon = Image.new("RGBA", (16, 16), (78, 91, 71, 255))
    rng = random.Random(7)
    for y in range(16):
        for x in range(16):
            if y < 4 and rng.random() < 0.6:
                icon.putpixel((x, y), (51, 56, 47, 255))
    for y in range(3, 15):
        half = max(0.6, (15 - y) * 0.42)
        cx = 8 + math.sin(y * 0.6) * 1.2
        for x in range(16):
            if abs(x + 0.5 - cx) <= half:
                shade = 150 if (x + y) % 3 else 128
                icon.putpixel((x, y), (shade, shade + 2, shade - 4, 255))
    for x in range(16):
        icon.putpixel((x, 15), (92, 66, 40, 255))
    icon.resize((128, 128), Image.NEAREST).save(os.path.join(ROOT, "realisticdisasters_logo.png"))


if __name__ == "__main__":
    main()
