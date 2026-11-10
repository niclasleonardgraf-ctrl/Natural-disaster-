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


# Meteor textures: soft 64x64 alpha gradients (white, tinted per particle in code). Light and smoke are soft in
# reality; smoke sprites fade to zero well inside the quad so even 15-block puffs never show straight edges.


def soft_glow(size, sigma, core=0.0):
    """Gaussian glow: alpha falls off smoothly; optional flat white-hot core radius (fraction of size)."""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    c = size / 2
    for y in range(size):
        for x in range(size):
            d = math.hypot(x + 0.5 - c, y + 0.5 - c) / c
            a = math.exp(-(d / sigma) ** 2)
            if d < core:
                a = 1.0
            if d >= 1.0:
                a = 0.0
            a = max(0.0, min(1.0, a * 1.05))
            img.putpixel((x, y), (255, 255, 255, int(a * 255)))
    return img


def fireball_texture(seed, size=64):
    rng = random.Random(seed)
    img = soft_glow(size, 0.42 + rng.uniform(-0.03, 0.03), core=0.18)
    return img


def streak_texture(seed, size=64):
    rng = random.Random(seed)
    return soft_glow(size, 0.36 + seed % 4 * 0.03)


def ember_texture(seed, size=32):
    return soft_glow(size, 0.45 + seed % 2 * 0.1, core=0.15)


def value_noise(size, cells, rng):
    grid = [[rng.random() for _ in range(cells + 1)] for _ in range(cells + 1)]
    out = [[0.0] * size for _ in range(size)]
    for y in range(size):
        for x in range(size):
            gx, gy = x / size * cells, y / size * cells
            ix, iy = int(gx), int(gy)
            fx, fy = gx - ix, gy - iy
            fx, fy = fx * fx * (3 - 2 * fx), fy * fy * (3 - 2 * fy)
            a = grid[iy][ix] * (1 - fx) + grid[iy][ix + 1] * fx
            b = grid[iy + 1][ix] * (1 - fx) + grid[iy + 1][ix + 1] * fx
            out[y][x] = a * (1 - fy) + b * fy
    return out


def smoke_texture(seed, size=64):
    """Soft billowy puff: a few overlapping lobes, low-frequency noise, light from above."""
    rng = random.Random(seed)
    lobes = [(rng.uniform(0.38, 0.62) * size, rng.uniform(0.4, 0.62) * size, rng.uniform(0.2, 0.3) * size) for _ in range(4)]
    n1 = value_noise(size, 4, rng)
    n2 = value_noise(size, 8, rng)
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    for y in range(size):
        for x in range(size):
            dens = 0.0
            for cx, cy, r in lobes:
                d = math.hypot(x + 0.5 - cx, y + 0.5 - cy) / r
                dens = max(dens, 1 - d * d)
            if dens <= 0:
                continue
            dens = dens * (0.6 + 0.4 * n1[y][x]) * (0.85 + 0.3 * n2[y][x])
            a = max(0.0, min(1.0, dens ** 1.6 * 1.4))
            # Radial window: alpha reaches exactly 0 well inside the quad, so huge puffs never show straight edges.
            rr = math.hypot(x + 0.5 - size / 2, y + 0.5 - size / 2) / (size / 2)
            a *= max(0.0, min(1.0, (0.92 - rr) / 0.35))
            shade = int(255 * (0.82 + 0.18 * (1 - y / size)) * (0.9 + 0.1 * n2[y][x]))
            img.putpixel((x, y), (shade, shade, shade, int(a * 235)))
    return img


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

    # Realistic rain (overrides vanilla's blue rain texture; applies to all rain in the game).
    env = os.path.join(ROOT, "assets", "minecraft", "textures", "environment")
    os.makedirs(env, exist_ok=True)
    realistic_rain(900).save(os.path.join(env, "rain.png"))

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

    for i in range(4):
        write(fireball_texture(400 + i), f"meteor_fireball_{i}")
        write(streak_texture(500 + i), f"meteor_streak_{i}")
        write(ember_texture(600 + i), f"meteor_ember_{i}")
        write(smoke_texture(700 + i), f"meteor_smoke_{i}")


if __name__ == "__main__":
    main()
