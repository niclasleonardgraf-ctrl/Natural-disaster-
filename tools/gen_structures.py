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
        "palette": (9, (10, [{"Name": (8, s)} for s in palette])),
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


def tsunami_arena():
    """A walled strip of sea (x 0-15, surface y4), a sand beach (x 16-19) and flat grassland with plants and a small
    pre-existing pond (a water source at 24,5,24 inside a cobblestone ring) that the flood must leave alone."""
    sx, sy, sz = 56, 12, 48
    blocks = {}
    for x in range(sx):
        for z in range(sz):
            blocks[(x, 0, z)] = "minecraft:stone"
            if x < 16:
                wall = x == 0 or z == 0 or z == sz - 1
                for y in range(1, 5):
                    blocks[(x, y, z)] = "minecraft:stone" if wall else "minecraft:water"
                if wall:
                    blocks[(x, 5, z)] = "minecraft:stone"
            else:
                for y in range(1, 4):
                    blocks[(x, y, z)] = "minecraft:dirt"
                blocks[(x, 4, z)] = "minecraft:sand" if x < 20 else "minecraft:grass_block"
    # the pond: water on dirt, ringed by cobblestone
    blocks[(24, 4, 24)] = "minecraft:dirt"
    blocks[(24, 5, 24)] = "minecraft:water"
    for x in range(23, 26):
        for z in range(23, 26):
            blocks[(x, 4, z)] = "minecraft:dirt"  # grass would die under the ring
            if (x, z) != (24, 24):
                blocks[(x, 5, z)] = "minecraft:cobblestone"
    # plants for the wave to tear out
    for x in range(22, 46, 3):
        for z in range(6, 43, 4):
            if 22 <= x <= 26 and 22 <= z <= 26:
                continue
            blocks[(x, 5, z)] = "minecraft:poppy" if (x + z) % 3 == 0 else "minecraft:short_grass"
    for x in range(sx):
        for y in range(1, sy):
            for z in range(sz):
                blocks.setdefault((x, y, z), "minecraft:air")
    write_structure("tsunami_arena", (sx, sy, sz), blocks)


if __name__ == "__main__":
    arena()
    tsunami_arena()
