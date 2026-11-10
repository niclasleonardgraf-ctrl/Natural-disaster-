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
BLOCKS = os.path.join(ROOT, "assets", "realisticdisasters", "textures", "block")


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


def ash_cloud(seed, size=128):
    """Volcanic ash billow: dense, cauliflower-like puff with a soft, lumpy rim and a dark, shadowed underside.
    128 px because these puffs are drawn up to ~40 blocks wide."""
    import numpy as np
    rng = np.random.default_rng(seed)
    n = fbm(size, rng, octaves=6, base=4)
    y, x = np.mgrid[0:size, 0:size] / (size - 1) * 2 - 1
    r = np.sqrt(x * x + y * y) + (n - 0.5) * 0.55     # lumpy, cauliflower outline
    t = np.clip((0.95 - r) / 0.3, 0, 1)               # soft but defined rim, so individual billows read
    a = t * t * (3 - 2 * t) * np.clip(0.8 + 0.5 * n, 0, 1)
    # strong top/bottom contrast: sunlit crown, dark shadowed base (the colour itself is tinted in code)
    top = np.clip(-y * 0.5 + 0.5 - (r - 0.4) * 0.35, 0, 1)
    light = np.clip(0.32 + 0.62 * top + 0.3 * (n - 0.5), 0, 1)
    rgb = (light * 255).astype("uint8")
    return Image.fromarray(np.dstack([rgb, rgb, rgb, (np.clip(a, 0, 1) * 255).astype("uint8")]), "RGBA")


def ash_flake(seed, size=16):
    """Falling ash flake: a small, soft, slightly irregular grey speck."""
    import numpy as np
    rng = np.random.default_rng(seed)
    n = fbm(size, rng, octaves=3, base=2)
    y, x = np.mgrid[0:size, 0:size] / (size - 1) * 2 - 1
    r = np.sqrt((x * (1 + 0.4 * n)) ** 2 + (y * (1.3 - 0.4 * n)) ** 2)
    t = np.clip((0.75 - r) / 0.45, 0, 1)
    a = t * t * (3 - 2 * t)
    light = np.clip(0.72 + 0.2 * (n - 0.5), 0, 1)
    rgb = (light * 255).astype("uint8")
    return Image.fromarray(np.dstack([rgb, rgb, rgb, (a * 255).astype("uint8")]), "RGBA")


def ash_shaft(seed, size=64):
    """Ash fall-out curtain: soft, slightly slanted vertical streaks hanging from the cloud, fading at the ends."""
    import numpy as np
    rng = np.random.default_rng(seed)
    cols = fbm(size, rng, octaves=3, base=6)[0]
    streak = np.tile(np.clip((cols - 0.3) * 2.0, 0, 1), (size, 1))
    y = np.linspace(-1, 1, size)[:, None]
    x = np.linspace(-1, 1, size)[None, :]
    fade = np.clip(1 - np.abs(y + 0.15) ** 2.5, 0, 1) * np.clip(1 - np.abs(x) ** 2, 0, 1)
    detail = fbm(size, rng, octaves=4, base=3) * 0.7 + 0.3
    a = np.clip(streak * fade * detail * 1.2, 0, 1)
    rgb = np.full((size, size), 190, dtype="uint8")
    return Image.fromarray(np.dstack([rgb, rgb, rgb, (a * 255).astype("uint8")]), "RGBA")


def lava_spark(seed, size=16):
    """Incandescent clot: white-hot core with a soft glow falloff (coloured by temperature in code)."""
    import numpy as np
    rng = np.random.default_rng(seed)
    n = fbm(size, rng, octaves=2, base=2)
    y, x = np.mgrid[0:size, 0:size] / (size - 1) * 2 - 1
    r = np.sqrt(x * x + y * y) * (0.9 + 0.25 * n)
    core = np.clip(1 - r / 0.45, 0, 1)
    halo = np.clip(1 - r, 0, 1) ** 2
    a = np.clip(core * 1.5 + halo * 0.7, 0, 1)
    light = np.clip(0.75 + 0.25 * core, 0, 1)
    rgb = (light * 255).astype("uint8")
    return Image.fromarray(np.dstack([rgb, rgb, rgb, (a * 255).astype("uint8")]), "RGBA")


def volcanic_ash_block(seed=1000, size=16):
    """16x16 block texture in the vanilla style: fine, speckled grey ash with a few darker scoria grains."""
    rng = random.Random(seed)
    img = Image.new("RGBA", (size, size))
    ramp = [(132, 128, 124), (122, 118, 114), (112, 109, 106), (101, 98, 96), (90, 88, 86)]
    for y in range(size):
        for x in range(size):
            v = rng.random()
            idx = 0 if v < 0.18 else 1 if v < 0.5 else 2 if v < 0.82 else 3 if v < 0.95 else 4
            img.putpixel((x, y), ramp[idx] + (255,))
    for _ in range(6):  # dark scoria grains
        img.putpixel((rng.randrange(size), rng.randrange(size)), (64, 60, 58, 255))
    return img


# ----------------------------------------------------------------------------- 3D funnel mesh textures
# Seamlessly tileable in both directions (they scroll around and up the funnel), smooth (blur filtering).

def pfbm(w, h, rng, octaves=5, base_x=4, base_y=4, persistence=0.5):
    """Periodic fractal value noise: tiles seamlessly in x and y."""
    import numpy as np
    out = np.zeros((h, w))
    amp, total = 1.0, 0.0
    for o in range(octaves):
        cx, cy = base_x * 2 ** o, base_y * 2 ** o
        grid = rng.random((cy, cx))
        ys = np.arange(h) / h * cy
        xs = np.arange(w) / w * cx
        yi, xi = ys.astype(int), xs.astype(int)
        yf, xf = ys - yi, xs - xi
        yf, xf = yf * yf * (3 - 2 * yf), xf * xf * (3 - 2 * xf)
        y0, y1 = yi % cy, (yi + 1) % cy
        x0, x1 = xi % cx, (xi + 1) % cx
        g00, g01 = grid[np.ix_(y0, x0)], grid[np.ix_(y0, x1)]
        g10, g11 = grid[np.ix_(y1, x0)], grid[np.ix_(y1, x1)]
        top = g00 + (g01 - g00) * xf[None, :]
        bot = g10 + (g11 - g10) * xf[None, :]
        out += (top + (bot - top) * yf[:, None]) * amp
        total += amp
        amp *= persistence
    return out / total


def funnel_core_texture(seed, w=256, h=512):
    """Dense condensation: fine vertical striations, larger turbulent cells, subtle bright/dark banding."""
    import numpy as np
    rng = np.random.default_rng(seed)
    streaks = pfbm(w, h, rng, octaves=5, base_x=24, base_y=2)        # stretched: long vertical streaks
    cells = pfbm(w, h, rng, octaves=5, base_x=4, base_y=6)
    fine = pfbm(w, h, rng, octaves=4, base_x=32, base_y=32)
    light = 0.55 + 0.72 * (streaks - 0.5) + 0.42 * (cells - 0.5) + 0.15 * (fine - 0.5)
    alpha = np.clip(0.88 + 0.5 * (cells - 0.5) + 0.25 * (streaks - 0.5), 0.6, 1.0)
    return to_rgba(light, alpha)


def funnel_veil_texture(seed, w=256, h=256):
    """Wispy outer veil: torn streaks with gaps, so the inner core shows through in places."""
    import numpy as np
    rng = np.random.default_rng(seed)
    streaks = pfbm(w, h, rng, octaves=5, base_x=16, base_y=2)
    breakup = pfbm(w, h, rng, octaves=4, base_x=4, base_y=4)
    a = np.clip((streaks - 0.42) * 3.0, 0, 1) * np.clip((breakup - 0.3) * 2.2, 0, 1)
    light = 0.62 + 0.4 * (streaks - 0.5) + 0.2 * (breakup - 0.5)
    return to_rgba(light, np.clip(a, 0, 1))


def debris_sheath_texture(seed, w=256, h=256):
    """Churning debris/dust: billowy turbulent texture with dark grit (tinted brown-grey in code)."""
    import numpy as np
    rng = np.random.default_rng(seed)
    billow = 1 - np.abs(pfbm(w, h, rng, octaves=6, base_x=6, base_y=4) * 2 - 1)   # ridged = billowy
    grit = pfbm(w, h, rng, octaves=3, base_x=16, base_y=16)
    light = 0.5 + 0.55 * (billow - 0.5) - 0.12 * (grit - 0.5)
    a = np.clip(0.35 + 0.9 * (billow - 0.35), 0, 1)
    return to_rgba(light, a)


def to_rgba(light, alpha):
    import numpy as np
    l = (np.clip(light, 0, 1) * 255).astype("uint8")
    return Image.fromarray(np.dstack([l, l, l, (np.clip(alpha, 0, 1) * 255).astype("uint8")]), "RGBA")


def write_entity_texture(img, name):
    import json
    d = os.path.join(ROOT, "assets", "realisticdisasters", "textures", "entity")
    os.makedirs(d, exist_ok=True)
    img.save(os.path.join(d, name + ".png"))
    with open(os.path.join(d, name + ".png.mcmeta"), "w") as f:
        json.dump({"texture": {"blur": True}}, f)   # smooth filtering, repeat wrapping (default)


# ---------------------------------------------------------------- wildfire (realistic particles, vanilla-style blocks)

def _periodic_noise(w, h, rng, cells_x, cells_y, shift_y=0.0):
    """Value noise that tiles vertically (period = cells_y), sampled with a vertical offset (in cells)."""
    import numpy as np
    grid = rng.random((cells_y, cells_x + 1))
    ys = (np.arange(h) / h * cells_y + shift_y) % cells_y
    xs = np.arange(w) / w * cells_x
    yi = ys.astype(int)
    yf = ys - yi
    yf = yf * yf * (3 - 2 * yf)
    xi = xs.astype(int)
    xf = xs - xi
    xf = xf * xf * (3 - 2 * xf)
    y1 = (yi + 1) % cells_y
    g00 = grid[np.ix_(yi, xi)]
    g01 = grid[np.ix_(yi, xi + 1)]
    g10 = grid[np.ix_(y1, xi)]
    g11 = grid[np.ix_(y1, xi + 1)]
    top = g00 + (g01 - g00) * xf[None, :]
    bot = g10 + (g11 - g10) * xf[None, :]
    return top + (bot - top) * yf[:, None]


def fire_tongue(seed, frame, frames=8, size=64):
    """One frame of a looping flame: noise rises through a tapering tongue; white-yellow core, orange body,
    red translucent edges. Frames loop seamlessly (the noise is periodic in the scroll direction)."""
    import numpy as np
    rng = np.random.default_rng(seed)
    t = frame / frames
    layers = [(4, 4, 1.0), (8, 8, 0.5), (16, 16, 0.25)]
    n = np.zeros((size, size))
    for i, (cx, cy, amp) in enumerate(layers):
        r2 = np.random.default_rng(seed * 7 + i)
        n += _periodic_noise(size, size, r2, cx, cy, shift_y=t * cy) * amp
    n /= sum(a for _, _, a in layers)
    sway = (_periodic_noise(1, size, np.random.default_rng(seed + 99), 1, 4, shift_y=t * 4)[:, 0] - 0.5) * 0.5
    warp = _periodic_noise(size, size, np.random.default_rng(seed + 55), 6, 6, shift_y=t * 6) - 0.5
    v = np.linspace(1, 0, size)[:, None]           # 0 at the bottom row, 1 at the top
    u = np.linspace(-1, 1, size)[None, :]
    width = 0.72 * (1 - v) ** 0.55 + 0.06             # wide base, tapering tongue
    # turbulence bends the flame more the higher it gets -> licks and curls instead of a cone
    lateral = (u - sway[:, None] * v - warp * 0.55 * v) / width
    shape = np.clip(1 - lateral ** 2, 0, 1)
    shape *= np.clip(v * 8, 0, 1)                    # soft bottom edge
    # rising "erosion" threshold: the body is solid, the upper part tears into separate tongues
    heat = shape * (0.25 + 1.05 * n) - v ** 1.3 * 0.62
    heat = np.clip(heat * 1.25, 0, 1)
    # colour ramp: deep red -> orange -> yellow -> warm white
    stops = np.array([0.0, 0.25, 0.5, 0.75, 1.0])
    cols = np.array([[120, 20, 4], [215, 70, 10], [255, 140, 30], [255, 210, 90], [255, 248, 210]], dtype=float)
    rgb = np.stack([np.interp(heat, stops, cols[:, c]) for c in range(3)], axis=-1)
    a = np.clip((heat - 0.08) * 2.2, 0, 1)
    img = np.dstack([rgb.astype("uint8"), (a * 255).astype("uint8")])
    return Image.fromarray(img, "RGBA")


def flame_sheet_frame(seed, frame, frames, size=64):
    """One frame of the wildfire flame block: a row of three flame tongues of different heights, rising noise,
    white-yellow cores and red, translucent tips. Loops seamlessly over `frames`."""
    import numpy as np
    t = frame / frames
    v = np.linspace(1, 0, size)[:, None]
    u = np.linspace(-1, 1, size)[None, :]
    n = np.zeros((size, size))
    for i, (c, amp) in enumerate([(4, 1.0), (8, 0.5), (16, 0.25)]):
        n += _periodic_noise(size, size, np.random.default_rng(seed * 11 + i), c, c, shift_y=t * c) * amp
    n /= 1.75
    warp = _periodic_noise(size, size, np.random.default_rng(seed * 11 + 7), 6, 6, shift_y=t * 6) - 0.5
    heat = np.zeros((size, size))
    for k, (cx, w, hgt) in enumerate([(-0.55, 0.42, 0.75), (0.05, 0.5, 1.0), (0.6, 0.4, 0.62)]):
        sway = (_periodic_noise(1, size, np.random.default_rng(seed * 13 + k), 1, 4, shift_y=t * 4)[:, 0] - 0.5) * 0.4
        vv = v / hgt
        width = w * np.clip(1 - vv, 0, 1) ** 0.55 + 0.03
        lateral = (u - cx - sway[:, None] * vv - warp * 0.5 * vv) / width
        shape = np.clip(1 - lateral ** 2, 0, 1) * (vv < 1)
        heat = np.maximum(heat, shape * (0.3 + 1.0 * n) - vv ** 1.3 * 0.6)
    heat = np.clip(heat * 1.3, 0, 1)
    stops = np.array([0.0, 0.25, 0.5, 0.75, 1.0])
    cols = np.array([[120, 20, 4], [215, 70, 10], [255, 140, 30], [255, 210, 90], [255, 248, 210]], dtype=float)
    rgb = np.stack([np.interp(heat, stops, cols[:, c]) for c in range(3)], axis=-1)
    a = np.clip((heat - 0.08) * 2.4, 0, 1)
    return np.dstack([rgb.astype("uint8"), (a * 255).astype("uint8")])


def write_flame_block(name, seed, frames=16, size=64):
    """Animated block texture: frames stacked vertically + .mcmeta with smooth frame interpolation."""
    import json
    import numpy as np
    strip = np.vstack([flame_sheet_frame(seed, f, frames, size) for f in range(frames)])
    write_block(Image.fromarray(strip, "RGBA"), name)
    with open(os.path.join(BLOCKS, name + ".png.mcmeta"), "w") as fh:
        json.dump({"animation": {"frametime": 2, "interpolate": True}}, fh, indent=2)
        fh.write("\n")


def smoke_billow(seed, size=64):
    """Convection-column puff: a dense, lumpy body made of a few overlapping soft lobes (cauliflower outline),
    fine turbulent detail from fractal noise, lit from above (bright top, shadowed underside). Rendered at 2x and
    downsampled. Near-neutral; tinted per particle in code."""
    import numpy as np
    from PIL import ImageFilter
    final = size
    size = size * 2
    rng = np.random.default_rng(seed)
    y, x = np.mgrid[0:size, 0:size] / (size - 1) * 2 - 1
    detail = fbm(size, rng, octaves=6, base=3)
    lumps = fbm(size, rng, octaves=3, base=2)
    body = np.zeros((size, size))
    for _ in range(5):
        cx, cy = rng.uniform(-0.35, 0.35), rng.uniform(-0.35, 0.2)
        r = rng.uniform(0.35, 0.55)
        d = np.sqrt((x - cx) ** 2 + (y - cy) ** 2) / r
        body = np.maximum(body, np.clip(1 - d, 0, 1))
    edge = np.clip((1.0 - np.sqrt(x * x + y * y)) / 0.3, 0, 1)  # keep everything inside the quad
    body = np.clip(body * 1.5 + (lumps - 0.5) * 0.9 + (detail - 0.5) * 1.0, 0, 1) * edge
    a = np.clip((body - 0.18) * 2.4, 0, 1)
    a = a * a * (3 - 2 * a)
    # smooth top-lighting: brighter where there is less smoke above (blurred density lookup)
    blurred = np.asarray(Image.fromarray((body * 255).astype("uint8")).filter(ImageFilter.GaussianBlur(6)), dtype=float) / 255
    above = np.vstack([np.zeros((10, size)), blurred[:-10]])  # density 10 px higher up (no wrap-around)
    lit = np.clip(0.72 - (above - blurred) * 1.2 - y * 0.16 + (detail - 0.5) * 0.7 + (lumps - 0.5) * 0.2, 0, 1)
    rgb = (lit * 255).astype("uint8")
    img = Image.fromarray(np.dstack([rgb, rgb, rgb, (a * 255).astype("uint8")]), "RGBA")
    return img.filter(ImageFilter.GaussianBlur(1.0)).resize((final, final), Image.LANCZOS)


def glow_ember(seed, size=16):
    """Flying spark: a white-hot head with a glowing motion streak along +x (oriented along the ember's velocity
    in code, like a camera's motion blur). Tinted in code."""
    import numpy as np
    rng = np.random.default_rng(seed)
    y, x = np.mgrid[0:size, 0:size] / (size - 1) * 2 - 1
    sy = 3.0 + rng.uniform(0, 1.0)  # thin streak
    d = np.sqrt((np.where(x > 0.45, (x - 0.45) * 3.0, (x - 0.45) * 0.62)) ** 2 + (y * sy) ** 2)
    a = np.clip(1 - d, 0, 1) ** 1.4
    core = np.clip(1 - d * 2.5, 0, 1)
    v = np.clip(0.75 + 0.25 * core, 0, 1)
    rgb = (v * 255).astype("uint8")
    return Image.fromarray(np.dstack([rgb, rgb, rgb, (a * 255).astype("uint8")]), "RGBA")


def soft_ash(seed, size=16):
    """Ash flake: an irregular, thin, slightly curled grey-white flake with soft edges."""
    import numpy as np
    rng = np.random.default_rng(seed)
    y, x = np.mgrid[0:size, 0:size] / (size - 1) * 2 - 1
    ang = rng.uniform(0, np.pi)
    xr = x * np.cos(ang) + y * np.sin(ang)
    yr = -x * np.sin(ang) + y * np.cos(ang)
    n = fbm(size, rng, octaves=3, base=2)
    d = np.sqrt((xr / 0.75) ** 2 + (yr / 0.42) ** 2) + (n - 0.5) * 0.7
    a = np.clip((1 - d) * 3, 0, 1)
    v = np.clip(0.72 + 0.25 * (n - 0.5) - 0.15 * yr, 0, 1)
    rgb = (v * 255).astype("uint8")
    return Image.fromarray(np.dstack([rgb, rgb, rgb, (a * 255).astype("uint8")]), "RGBA")


# Blocks stay 16x16 with hard pixels so they sit naturally among vanilla blocks.
CHAR = [(52, 49, 47), (38, 36, 35), (27, 26, 26), (17, 16, 16)]
CHAR_SHEEN = [(92, 90, 90), (70, 68, 68)]      # silvery cracked charcoal
ASH_GROUND = [(150, 146, 140), (118, 114, 109), (86, 82, 78)]
SOIL = [(74, 58, 46), (58, 45, 36)]


def charred_log_side(seed=900):
    """Burnt bark: black blocky 'alligator' charcoal with silvery sheen on the plates and deep cracks."""
    rng = random.Random(seed)
    img = Image.new("RGBA", (16, 16))
    for y in range(16):
        for x in range(16):
            img.putpixel((x, y), CHAR[rng.choice((1, 1, 2))] + (255,))
    # charcoal plates separated by deep cracks (offset rows of 3-5 px tall blocks)
    for row, y0 in enumerate(range(0, 16, 4)):
        off = (row * 3) % 5
        for x in range(16):
            img.putpixel((x, y0), CHAR[3] + (255,))
            if (x + off) % 5 == 0:
                for y in range(y0, min(16, y0 + 4)):
                    img.putpixel((x, y), CHAR[3] + (255,))
        for x in range(16):
            if (x + off) % 5 == 2 and y0 + 1 < 16:
                img.putpixel((x, y0 + 1), CHAR_SHEEN[rng.randint(0, 1)] + (255,))
            elif (x + off) % 5 in (1, 3) and y0 + 1 < 16 and rng.random() < 0.4:
                img.putpixel((x, y0 + 1), CHAR[0] + (255,))
    return img


def charred_log_top(seed=901):
    """Burnt end grain: black rim, dark rings, grey ash at the cracked core."""
    rng = random.Random(seed)
    img = Image.new("RGBA", (16, 16))
    for y in range(16):
        for x in range(16):
            d = max(abs(x - 7.5), abs(y - 7.5))
            if d > 6.5:
                c = CHAR[3]
            elif d < 1.6:
                c = ASH_GROUND[rng.randint(0, 2)]
            else:
                c = CHAR[1 + int(d) % 2]
                if rng.random() < 0.06:
                    c = CHAR_SHEEN[1]
            img.putpixel((x, y), c + (255,))
    for i in range(2, 7):  # radial cracks
        img.putpixel((7, 7 - i), CHAR[3] + (255,))
        img.putpixel((8 + i, 9), CHAR[3] + (255,))
    return img


def scorched_earth_top(seed=910):
    """Burnt ground seen from above: black char with grey-white ash patches and a few brown soil specks."""
    rng = random.Random(seed)
    img = Image.new("RGBA", (16, 16))
    patches = [(rng.randint(0, 15), rng.randint(0, 15), rng.uniform(1.5, 3.2)) for _ in range(4)]
    for y in range(16):
        for x in range(16):
            ash = any(min(abs(x - px), 16 - abs(x - px)) ** 2 + min(abs(y - py), 16 - abs(y - py)) ** 2 < r * r
                      for px, py, r in patches)
            if ash and rng.random() < 0.8:
                c = ASH_GROUND[rng.choice((0, 1, 1, 2))]
            else:
                c = CHAR[rng.choice((0, 1, 1, 2, 3))]
                if rng.random() < 0.07:
                    c = SOIL[rng.randint(0, 1)]
            img.putpixel((x, y), c + (255,))
    return img


def scorched_earth_side(seed=911):
    """Side: vanilla-like dirt with a ragged black, ash-flecked burnt layer on top (like the grass block side)."""
    rng = random.Random(seed)
    img = Image.new("RGBA", (16, 16))
    dirt = [(134, 96, 67), (121, 85, 58), (100, 70, 48), (150, 108, 74)]
    for y in range(16):
        for x in range(16):
            img.putpixel((x, y), dirt[rng.choice((0, 0, 1, 1, 2, 3))] + (255,))
    for x in range(16):
        depth = 2 + rng.randint(0, 2) + (1 if rng.random() < 0.25 else 0)
        for y in range(depth):
            c = CHAR[rng.choice((1, 2, 3))] if rng.random() > 0.2 else ASH_GROUND[rng.randint(1, 2)]
            img.putpixel((x, y), c + (255,))
    return img


def scorched_leaves(seed=920):
    """Heat-killed foliage: vanilla leaves layout (cutout holes) in rust, tan and grey-brown dead-needle tones."""
    rng = random.Random(seed)
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    tones = [(150, 92, 46), (128, 74, 36), (104, 62, 34), (88, 66, 50), (170, 118, 66), (72, 52, 40)]
    for y in range(16):
        for x in range(16):
            if rng.random() < 0.3:
                continue  # holes, like vanilla leaves (rendered as cutout)
            img.putpixel((x, y), tones[rng.choice((0, 0, 1, 1, 2, 3, 4, 5))] + (255,))
    return img


def write_block(img, name):
    os.makedirs(BLOCKS, exist_ok=True)
    img.save(os.path.join(BLOCKS, name + ".png"))


def write_wildfire():
    import numpy as np  # noqa: F401  (fail early with a clear message if numpy is missing)
    for i in range(4):
        # 128 px: smoke quads get large on screen and particles are sampled without filtering
        write(soft_cloud(1100 + i, size=128, density=1.0), f"wildfire_smoke_{i}")
        write(smoke_billow(1200 + i, size=128), f"smoke_billow_{i}")
        write(soft_ash(1300 + i), f"ash_flake_{i}")
    for i in range(2):
        write(glow_ember(1400 + i), f"ember_{i}")
    for v in range(2):
        for f in range(8):
            write(fire_tongue(1500 + v, f), f"fire_tongue_{v * 8 + f}")
    write_block(charred_log_side(), "charred_log")
    write_block(charred_log_top(), "charred_log_top")
    write_block(scorched_earth_top(), "scorched_earth_top")
    write_block(scorched_earth_side(), "scorched_earth_side")
    write_block(scorched_leaves(), "scorched_leaves")
    write_flame_block("wildfire_flame_0", 1600)
    write_flame_block("wildfire_flame_1", 1601)


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
        write(soft_cloud(600 + i, density=0.8, wispy=True), f"quake_dust_{i}")  # earthquake rock dust
    write_wildfire()
        write(ash_cloud(600 + i), f"ash_cloud_{i}")
    for i in range(3):
        write(ash_flake(700 + i), f"ash_fall_{i}")
    for i in range(4):
        write(ash_shaft(800 + i), f"ash_shaft_{i}")
    for i in range(3):
        write(lava_spark(900 + i), f"lava_spark_{i}")
    blocks = os.path.join(ROOT, "assets", "realisticdisasters", "textures", "block")
    os.makedirs(blocks, exist_ok=True)
    volcanic_ash_block().save(os.path.join(blocks, "volcanic_ash.png"))

    # 3D funnel mesh
    write_entity_texture(funnel_core_texture(1000), "funnel_core")
    write_entity_texture(funnel_veil_texture(1001), "funnel_veil")
    write_entity_texture(debris_sheath_texture(1002), "debris_sheath")

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


if __name__ == "__main__":
    main()
