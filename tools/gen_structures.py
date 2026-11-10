#!/usr/bin/env python3
"""Writes the GameTest arena structure (gzip NBT) used by the automated disaster tests.
Usage: python3 tools/gen_structures.py
"""
import gzip
import os
import struct

DATA_VERSION = 4790  # Minecraft 26.1.2
OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "data", "realisticdisasters", "structure")


def tag_string(s):
    b = s.encode("utf-8")
    return struct.pack(">H", len(b)) + b


def payload(tag_type, value):
    if tag_type == 3:
        return struct.pack(">i", value)
    if tag_type == 8:
        return tag_string(value)
    if tag_type == 9:
        elem_type, items = value
        return struct.pack(">bi", elem_type, len(items)) + b"".join(payload(elem_type, v) for v in items)
    if tag_type == 10:
        out = b""
        for name, (t, v) in value.items():
            out += struct.pack(">b", t) + tag_string(name) + payload(t, v)
        return out + b"\x00"
    raise ValueError(tag_type)


def palette_entry(state):
    """"minecraft:oak_leaves[persistent=true]" -> {Name, Properties}."""
    if "[" not in state:
        return {"Name": (8, state)}
    name, props = state[:-1].split("[", 1)
    values = dict(kv.split("=") for kv in props.split(","))
    return {"Name": (8, name), "Properties": (10, {k: (8, v) for k, v in values.items()})}


def write_structure(name, size, blocks):
    palette, index = [], {}
    entries = []
    for pos, state in blocks.items():
        if state not in index:
            index[state] = len(palette)
            palette.append(state)
        entries.append((pos, index[state]))
    root = {
        "DataVersion": (3, DATA_VERSION),
        "size": (9, (3, list(size))),
        "palette": (9, (10, [palette_entry(s) for s in palette])),
        "blocks": (9, (10, [{"pos": (9, (3, list(p))), "state": (3, i)} for p, i in entries])),
        "entities": (9, (10, [])),
    }
    data = struct.pack(">b", 10) + tag_string("") + payload(10, root)
    os.makedirs(OUT, exist_ok=True)
    with gzip.open(os.path.join(OUT, name + ".nbt"), "wb") as f:
        f.write(data)


def arena():
    sx, sy, sz = 32, 9, 32
    blocks = {}
    for x in range(sx):
        for z in range(sz):
            blocks[(x, 0, z)] = "minecraft:stone"
            blocks[(x, 1, z)] = "minecraft:dirt"
            blocks[(x, 2, z)] = "minecraft:dirt"
            blocks[(x, 3, z)] = "minecraft:grass_block"
    # soil under the tree and shed (grass would die under opaque blocks and confuse the tests)
    for x in range(20, 24):
        for z in range(20, 24):
            blocks[(x, 3, z)] = "minecraft:dirt"
    blocks[(8, 3, 8)] = "minecraft:dirt"
    # a small tree
    for y in range(4, 8):
        blocks[(8, y, 8)] = "minecraft:oak_log"
    for x in range(6, 11):
        for z in range(6, 11):
            for y in (6, 7):
                if (x, y, z) not in blocks:
                    blocks[(x, y, z)] = "minecraft:oak_leaves[persistent=true]"
    # a little plank shed
    for x in range(20, 24):
        for z in range(20, 24):
            for y in range(4, 6):
                if x in (20, 23) or z in (20, 23):
                    blocks[(x, y, z)] = "minecraft:oak_planks"
    # fill the rest with air so the template clears whatever was there
    for x in range(sx):
        for y in range(4, sy):
            for z in range(sz):
                blocks.setdefault((x, y, z), "minecraft:air")
    write_structure("tornado_arena", (sx, sy, sz), blocks)


def earthquake_arena():
    """40x20x40: deep ground (fissures need depth), a cobblestone house with a plank roof and windows,
    a stone-brick pillar and a plank overhang - things an earthquake can crack, shatter and bring down."""
    sx, sy, sz = 40, 20, 40
    blocks = {}
    for x in range(sx):
        for z in range(sz):
            for y in range(0, 7):
                blocks[(x, y, z)] = "minecraft:stone"
            blocks[(x, 7, z)] = "minecraft:dirt"
            blocks[(x, 8, z)] = "minecraft:dirt"
            blocks[(x, 9, z)] = "minecraft:grass_block"
    # house: walls x/z 24..30, y 10..13, flat plank roof at y 14
    for x in range(24, 31):
        for z in range(24, 31):
            blocks[(x, 9, z)] = "minecraft:dirt"
            for y in range(10, 14):
                if x in (24, 30) or z in (24, 30):
                    blocks[(x, y, z)] = "minecraft:cobblestone"
            blocks[(x, 14, z)] = "minecraft:oak_planks"
    for (x, z) in ((27, 24), (27, 30), (24, 27), (30, 27)):
        blocks[(x, 11, z)] = "minecraft:glass"
        blocks[(x, 12, z)] = "minecraft:glass"
    # stone-brick pillar
    for y in range(10, 16):
        blocks[(12, y, 12)] = "minecraft:stone_bricks"
        blocks[(13, y, 12)] = "minecraft:stone_bricks"
    # plank overhang on a cobblestone post
    for y in range(10, 13):
        blocks[(8, y, 26)] = "minecraft:cobblestone"
    for x in range(8, 13):
        blocks[(x, 13, 26)] = "minecraft:oak_planks"
    for x in range(sx):
        for y in range(10, sy):
            for z in range(sz):
                blocks.setdefault((x, y, z), "minecraft:air")
    write_structure("earthquake_arena", (sx, sy, sz), blocks)
def wildfire_arena(name="wildfire_arena", sx=32, sz=32, trees=((8, 8), (24, 10), (11, 24))):
    """Grass with brush patches and oak trees: fuel of every kind for the wildfire tests."""
    sy = 12
    blocks = {}
    for x in range(sx):
        for z in range(sz):
            blocks[(x, 0, z)] = "minecraft:stone"
            blocks[(x, 1, z)] = "minecraft:dirt"
            blocks[(x, 2, z)] = "minecraft:dirt"
            blocks[(x, 3, z)] = "minecraft:grass_block"
            # deterministic brush pattern: short grass, some tall grass and ferns
            h = (x * 7 + z * 13) % 11
            if h in (0, 3, 7):
                blocks[(x, 4, z)] = "minecraft:short_grass"
            elif h == 5:
                blocks[(x, 4, z)] = "minecraft:fern"
    for tx, tz in trees:
        blocks[(tx, 3, tz)] = "minecraft:dirt"
        for y in range(4, 9):
            blocks[(tx, y, tz)] = "minecraft:oak_log"
        for x in range(tx - 2, tx + 3):
            for z in range(tz - 2, tz + 3):
                for y in (7, 8, 9):
                    r = 2 if y < 9 else 1
                    if abs(x - tx) <= r and abs(z - tz) <= r and (x, y, z) not in blocks:
                        blocks[(x, y, z)] = "minecraft:oak_leaves[persistent=true]"
                if (x, 4, z) in blocks and blocks[(x, 4, z)] != "minecraft:oak_log":
                    del blocks[(x, 4, z)]  # no brush right under the canopy
    for x in range(sx):
        for y in range(4, sy):
            for z in range(sz):
                blocks.setdefault((x, y, z), "minecraft:air")
    write_structure(name, (sx, sy, sz), blocks)


if __name__ == "__main__":
    arena()
    earthquake_arena()
    wildfire_arena()
    # bigger field for the wildfire performance test, so an intensity-3 fire has room to build a real front
    wildfire_arena("wildfire_field", 48, 48, [(x, z) for x in range(8, 46, 13) for z in range(9, 46, 14)])
