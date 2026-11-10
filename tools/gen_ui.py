#!/usr/bin/env python3
"""Pixel-art UI assets in Minecraft's item style (16x16): the Disaster Controller item, one icon per disaster for the
menu, and the mod-list logo. Hand-drawn as character maps with small palettes. Deterministic.
Usage: python3 tools/gen_ui.py
"""
import os

from PIL import Image

ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources")
ASSETS = os.path.join(ROOT, "assets", "realisticdisasters", "textures")

# Each icon: (palette, rows). '.' = transparent.
ICONS = {
    "disaster_controller": ({
        "k": (27, 27, 32), "d": (58, 52, 50), "c": (176, 98, 62), "C": (214, 136, 92), "h": (122, 66, 44),
        "s": (12, 40, 26), "g": (64, 200, 110), "G": (150, 255, 170), "r": (230, 70, 60), "a": (150, 150, 158),
        "w": (226, 226, 232), "b": (40, 44, 52),
    }, [
        "..........a.....",
        "..........a.....",
        "..........w.....",
        "...kkkkkkkkkkk..",
        "..kCCCCCCCCCCCk.",
        "..kCssssssssshk.",
        "..kCsgssgssssshk",
        "..kCssggsrssshk.",
        "..kCsgsGgssgshk.",
        "..kCssggssgsshk.",
        "..kCsssgssssshk.",
        "..kChhhhhhhhhhk.",
        "..kCcbrcbccbwck.",
        "..kChcccccccchk.",
        "...kkkkkkkkkkk..",
        "................",
    ]),
    "tornado": ({
        "k": (40, 44, 50), "d": (78, 84, 92), "m": (118, 124, 132), "l": (160, 166, 172), "b": (120, 96, 70),
        "B": (90, 70, 52),
    }, [
        ".kkkkkkkkkkkkkk.",
        "kdddmmmmlllmmddk",
        ".kdmmllllmmmddk.",
        "..kdmmlllmmddk..",
        "...kdmllmmddk...",
        "....kdmlmmdk....",
        ".....kdmldk.....",
        ".....kdmldk.....",
        "......kdmk......",
        "......kdmdk.....",
        ".......kdk......",
        ".......kdk......",
        "......bkdkb.....",
        "....bBbbBbbBb...",
        "...BbBbBBbBbBb..",
        "................",
    ]),
    "earthquake": ({
        "g": (96, 160, 64), "G": (70, 128, 48), "d": (134, 96, 64), "D": (100, 70, 46), "s": (128, 128, 128),
        "S": (96, 96, 96), "k": (20, 16, 14),
    }, [
        "................",
        "................",
        "................",
        "gGgggGgk.gGggGgg",
        "GggGgggk.gggGggG",
        "dddDddk..kddDddd",
        "dDdddk....kdddDd",
        "ddddDdk..kdDdddd",
        "DdddddDk.kdddDdd",
        "ddDddddk.kddddDd",
        "sssSsskS.kssSsss",
        "SsssssSk.SssssSs",
        "sSssssskksssSsss",
        "ssssSsssksssssSs",
        "SsssssssSsssssss",
        "................",
    ]),
    "wildfire": ({
        "r": (190, 40, 20), "R": (140, 24, 14), "o": (240, 120, 30), "y": (255, 214, 80), "w": (255, 248, 200),
        "k": (54, 40, 30),
    }, [
        "........r.......",
        ".......rr.......",
        ".......ror...r..",
        "......roor..rr..",
        "..r..rooor..ror.",
        "..rr.royyor.ror.",
        ".rorrroyyyorroor",
        ".roorroywyyoroor",
        "rooorroywwyooor.",
        "roooooyywwyyoor.",
        "roooyyywwwyyoor.",
        ".rooyywwwwwyoor.",
        ".Rrooyywwwyyorr.",
        "..RrrooyyyoorR..",
        "...kkkkkkkkkk...",
        "................",
    ]),
    "volcano": ({
        "k": (36, 30, 30), "d": (70, 58, 54), "m": (98, 84, 78), "l": (255, 190, 60), "L": (240, 90, 30),
        "a": (90, 90, 96), "A": (130, 130, 136),
    }, [
        "....aAAa..AAa...",
        "...aAAAAaAAAAa..",
        "....aAAAAAAaa...",
        "......aAAa......",
        "......LllL......",
        ".....kLllLk.....",
        "....kdmLlmdk....",
        "....kdmmLmdk....",
        "...kddmLmmddk...",
        "...kdmmmLmmdk...",
        "..kddmmmmLmddk..",
        "..kdmmmmmLLmdk..",
        ".kddmmmmmmLmddk.",
        ".kdmmmmmmmmLmdk.",
        "kkkkkkkkkkkkkkkk",
        "................",
    ]),
    "meteor_shower": ({
        "w": (255, 250, 220), "y": (255, 220, 90), "o": (250, 140, 40), "r": (210, 60, 30), "k": (52, 44, 44),
        "d": (90, 76, 70), "p": (190, 120, 255),
    }, [
        "w...............",
        ".y..............",
        "..yo........p...",
        "...yo...........",
        "....oor.........",
        ".....oorr.......",
        "......orrr......",
        ".......rrkk.....",
        "........kddk....",
        "........kdddk...",
        ".........kddk...",
        "..p.......kk....",
        "................",
        "...........p....",
        "................",
        "................",
    ]),
    "tsunami": ({
        "w": (240, 250, 255), "f": (196, 226, 240), "l": (90, 160, 220), "m": (50, 112, 186), "d": (30, 70, 140),
        "k": (16, 40, 86),
    }, [
        "................",
        "......wwww......",
        "....wwfffww.....",
        "...wfllllfw.....",
        "..wflmmmllw.....",
        "..flmmddmlw.....",
        ".flmmdkkdm......",
        ".lmmdk..........",
        "flmmd...........",
        "lmmmd......ww...",
        "mmmmdd...wwfflw.",
        "mmmmmddwwfllmmdw",
        "dmmmmmmdlmmmmddd",
        "ddmmmmmmmmmmdddd",
        "kddddddddddddddk",
        "................",
    ]),
    "blizzard": ({
        "w": (250, 252, 255), "b": (170, 214, 250), "B": (110, 170, 230),
    }, [
        ".......w........",
        ".....b.w.b......",
        "......bwb.......",
        "..w....w....w...",
        "...b...w...b....",
        "....bb.w.bb.....",
        ".b...bbwbb...b..",
        "wwwwwwwBwwwwwww.",
        ".b...bbwbb...b..",
        "....bb.w.bb.....",
        "...b...w...b....",
        "..w....w....w...",
        "......bwb.......",
        ".....b.w.b......",
        ".......w........",
        "................",
    ]),
    "sandstorm": ({
        "s": (232, 204, 140), "S": (204, 168, 100), "d": (170, 130, 76), "l": (248, 228, 180),
    }, [
        "................",
        "..lllllllll.....",
        "...........ll...",
        ".sssssssss...l..",
        "..........ss..l.",
        "...SSSSSS...s..l",
        ".........S..s..l",
        ".lll.....S..s..l",
        "....lll.S..s...l",
        ".......SS.s...l.",
        "..ssss.....s.l..",
        "......sss.......",
        "..........ddd...",
        "...SSddddddSSSd.",
        "dddddSSSSSSddddd",
        "................",
    ]),
}


def draw(name):
    palette, rows = ICONS[name]
    assert len(rows) == 16 and all(len(r) == 16 for r in rows), name
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch != ".":
                img.putpixel((x, y), palette[ch] + (255,))
    return img


def save(img, *parts):
    path = os.path.join(*parts)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)


def main():
    save(draw("disaster_controller"), ASSETS, "item", "disaster_controller.png")
    disasters = ["tornado", "earthquake", "wildfire", "volcano", "meteor_shower", "tsunami", "blizzard", "sandstorm"]
    for d in disasters:
        save(draw(d), ASSETS, "gui", "icon", d + ".png")
    # Mod-list logo: the eight icons on a dark storm tile, 4x2, upscaled with nearest neighbour.
    tile = Image.new("RGBA", (72, 38), (24, 28, 34, 255))
    for i, d in enumerate(disasters):
        tile.alpha_composite(draw(d), (2 + (i % 4) * 18, 2 + (i // 4) * 18))
    tile.resize((288, 152), Image.NEAREST).save(os.path.join(ROOT, "realisticdisasters_logo.png"))


if __name__ == "__main__":
    main()
