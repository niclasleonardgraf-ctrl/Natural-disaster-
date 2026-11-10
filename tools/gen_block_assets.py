#!/usr/bin/env python3
"""Writes the JSON assets for the sand layer blocks (blockstates, models, item definitions, loot tables, tags).
They reuse vanilla's snow-layer model shapes with the vanilla sand / red sand textures.
Usage: python3 tools/gen_block_assets.py
"""
import json
import os

ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources")
ASSETS = os.path.join(ROOT, "assets", "realisticdisasters")
DATA = os.path.join(ROOT, "data")
NS = "realisticdisasters"
LAYERS = {"sand_layer": "sand", "red_sand_layer": "red_sand"}


def dump(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump(obj, f, indent=2)
        f.write("\n")


def main():
    for block, texture in LAYERS.items():
        variants = {}
        for n in range(1, 9):
            if n == 8:
                model = f"minecraft:block/{texture}"
            else:
                model = f"{NS}:block/{block}_height{n * 2}"
                dump(os.path.join(ASSETS, "models", "block", f"{block}_height{n * 2}.json"), {
                    "parent": f"minecraft:block/snow_height{n * 2}",
                    "textures": {"particle": f"minecraft:block/{texture}", "texture": f"minecraft:block/{texture}"},
                })
            variants[f"layers={n}"] = {"model": model}
        dump(os.path.join(ASSETS, "blockstates", f"{block}.json"), {"variants": variants})
        dump(os.path.join(ASSETS, "items", f"{block}.json"),
             {"model": {"type": "minecraft:model", "model": f"{NS}:block/{block}_height2"}})
        # One layer item per layer, like snow drops one snowball per layer.
        children = [{
            "type": "minecraft:item",
            "name": f"{NS}:{block}",
            "conditions": [{"condition": "minecraft:block_state_property", "block": f"{NS}:{block}",
                            "properties": {"layers": str(n)}}],
            "functions": [{"function": "minecraft:set_count", "count": float(n), "add": False}],
        } for n in range(1, 9)]
        dump(os.path.join(DATA, NS, "loot_table", "blocks", f"{block}.json"), {
            "type": "minecraft:block",
            "pools": [{"rolls": 1.0, "bonus_rolls": 0.0,
                       "conditions": [{"condition": "minecraft:survives_explosion"}],
                       "entries": [{"type": "minecraft:alternatives", "children": children}]}],
            "random_sequence": f"{NS}:blocks/{block}",
        })
    dump(os.path.join(DATA, "minecraft", "tags", "block", "mineable", "shovel.json"),
         {"replace": False, "values": [f"{NS}:{b}" for b in LAYERS]})


if __name__ == "__main__":
    main()
