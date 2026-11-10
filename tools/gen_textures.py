#!/usr/bin/env python3
"""Generates the mod's pixel-art textures (16x16 / 8x8, hard-edged, small vanilla-style palettes).

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
DUST = [(240, 226, 196), (219, 200, 160), (191, 168, 124), (158, 135, 95)]
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


# Meteor textures are deliberately NOT hard pixel art: light and smoke are soft in reality, so these are smooth
# alpha gradients (white, tinted per particle in code). Smoke keeps a faint 2x2-texel grain so it sits next to
# vanilla blocks without looking like a photo. (Smoke was first tried with a 2x2-texel grain; at 10+ block puff
# sizes the grain showed up as blocky silhouettes, so smoke is smooth too.)


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


def fireball_texture(seed, size=32):
    rng = random.Random(seed)
    img = soft_glow(size, 0.42 + rng.uniform(-0.03, 0.03), core=0.18)
    return img


def streak_texture(seed, size=32):
    rng = random.Random(seed)
    return soft_glow(size, 0.36 + seed % 4 * 0.03)


def ember_texture(seed, size=16):
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


def smoke_texture(seed, size=32):
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
    for i in range(4):
        write(cloud_texture(100 + i, CLOUD, lobes=4 + i % 2), f"funnel_cloud_{i}")
        write(cloud_texture(200 + i, DUST, lobes=3, fill=0.82), f"dust_cloud_{i}")
        write(debris_texture(300 + i), f"storm_debris_{i}")

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
