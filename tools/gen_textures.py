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


if __name__ == "__main__":
    main()
