#!/usr/bin/env python3
"""Adds the player-facing English text: Disaster Controller menu, warnings, intensity/direction names, disaster
descriptions, and a readable name for every config option (parsed from the config classes). Existing keys are kept;
running it again is safe. Usage: python3 tools/gen_lang.py
"""
import json
import os
import re

BASE = os.path.join(os.path.dirname(__file__), "..")
LANG = os.path.join(BASE, "src/main/resources/assets/realisticdisasters/lang/en_us.json")
CONFIGS = ["src/main/java/com/realisticdisasters/config/DisasterConfig.java",
           "src/main/java/com/realisticdisasters/config/ClientConfig.java"]

TEXT = {
    # item, tab, welcome
    "item.realisticdisasters.disaster_controller": "Disaster Controller",
    "item.realisticdisasters.disaster_controller.desc": "Summon and command natural disasters.",
    "item.realisticdisasters.disaster_controller.use": "Right-click to open",
    "itemGroup.realisticdisasters": "Realistic Natural Disasters",
    "welcome.realisticdisasters.title": "⚡ Realistic Natural Disasters",
    "welcome.realisticdisasters.body": "You received a Disaster Controller. Hold it and right-click to start a tornado, earthquake, wildfire and more. Disasters also strike on their own, so watch the sky.",

    # menu
    "gui.realisticdisasters.controller.title": "Disaster Controller",
    "gui.realisticdisasters.controller.subtitle": "Realistic Natural Disasters",
    "gui.realisticdisasters.tab.start": "Start",
    "gui.realisticdisasters.tab.active": "Active (%s)",
    "gui.realisticdisasters.start": "▶  Start %s",
    "gui.realisticdisasters.settings": "Settings",
    "gui.realisticdisasters.intensity": "Intensity  %s  %s",
    "gui.realisticdisasters.intensity_label": "Intensity: %s",
    "gui.realisticdisasters.location": "Location: %s",
    "gui.realisticdisasters.expect": "Expect: %s",
    "gui.realisticdisasters.stop": "Stop",
    "gui.realisticdisasters.stop.tooltip": "Let it die down naturally (fades out over a few seconds).",
    "gui.realisticdisasters.remove": "Remove",
    "gui.realisticdisasters.remove.tooltip": "Remove it instantly. Damage already done stays.",
    "gui.realisticdisasters.stop_all": "Stop all",
    "gui.realisticdisasters.active.header": "Active disasters nearby",
    "gui.realisticdisasters.active.none": "The skies are calm.",
    "gui.realisticdisasters.active.none2": "No disasters are active near you.",
    "gui.realisticdisasters.active.row": "%s · %s blocks %s",
    "gui.realisticdisasters.active.more": "+%s more further away",

    "placement.realisticdisasters.ahead": "In front of you",
    "placement.realisticdisasters.ahead.tooltip": "Forms about 55 blocks ahead of where you are looking, close enough to watch.",
    "placement.realisticdisasters.nearby": "Somewhere nearby",
    "placement.realisticdisasters.nearby.tooltip": "Forms 90-140 blocks away in a random direction. You will get a warning.",
    "placement.realisticdisasters.here": "Right here",
    "placement.realisticdisasters.here.tooltip": "Forms right where you stand. Dangerous: find shelter quickly!",

    "intensity.realisticdisasters.1": "Weak",
    "intensity.realisticdisasters.2": "Moderate",
    "intensity.realisticdisasters.3": "Strong",
    "intensity.realisticdisasters.4": "Severe",
    "intensity.realisticdisasters.5": "Catastrophic",

    "direction.realisticdisasters.north": "to the north",
    "direction.realisticdisasters.north_east": "to the north-east",
    "direction.realisticdisasters.east": "to the east",
    "direction.realisticdisasters.south_east": "to the south-east",
    "direction.realisticdisasters.south": "to the south",
    "direction.realisticdisasters.south_west": "to the south-west",
    "direction.realisticdisasters.west": "to the west",
    "direction.realisticdisasters.north_west": "to the north-west",

    # announcements and feedback
    "announce.realisticdisasters.warning": "%s Warning",
    "announce.realisticdisasters.subtitle": "%s · %s",
    "announce.realisticdisasters.distance": "%s blocks %s",
    "announce.realisticdisasters.here": "right on top of you",
    "announce.realisticdisasters.ended": "The %s has passed.",
    "control.realisticdisasters.started": "%s started (%s).",
    "control.realisticdisasters.stopped": "The disaster is dying down.",
    "control.realisticdisasters.stopped_all": "Stopping %s disaster(s).",
    "control.realisticdisasters.none_active": "There are no active disasters here.",
    "control.realisticdisasters.not_found": "That disaster is already over.",
    "control.realisticdisasters.disabled": "%s is disabled in the server settings.",
    "control.realisticdisasters.too_many": "Too many disasters at once (limit %s). Stop one first.",
    "control.realisticdisasters.cooldown": "The controller is recharging (%ss).",
    "control.realisticdisasters.no_permission": "Only operators can use the Disaster Controller on this server.",
    "control.realisticdisasters.need_item": "Hold a Disaster Controller to do that.",

    # descriptions
    "desc.realisticdisasters.tornado": "A rotating funnel drops from a supercell. It lifts and hurls anything in its path and leaves a scar of shredded ground behind.",
    "desc.realisticdisasters.earthquake": "The ground shakes in growing waves. Fissures tear open, buildings crumble and towers topple.",
    "desc.realisticdisasters.wildfire": "A wind-driven fire front races through forest and grass under a towering smoke column, leaving charred land behind.",
    "desc.realisticdisasters.volcano": "A mountain rises and erupts with lava bombs, ash clouds and glowing flows that cool into basalt.",
    "desc.realisticdisasters.meteor_shower": "Fireballs streak across the sky. Impacts blast craters, throw ejecta and leave rare ore behind.",
    "desc.realisticdisasters.tsunami": "The sea pulls back, then a wall of water floods the coast, carrying wreckage inland before it recedes.",
    "desc.realisticdisasters.blizzard": "Howling wind and whiteout snow. Drifts pile up and the cold freezes water.",
    "desc.realisticdisasters.sandstorm": "A towering wall of dust rolls in. Visibility collapses and sand piles into dunes.",

    "expect.realisticdisasters.tornado.1": "strips leaves, breaks windows, scours grass.",
    "expect.realisticdisasters.tornado.2": "tears off roofs, snaps trees, throws animals.",
    "expect.realisticdisasters.tornado.3": "levels houses, rips out walls, flings debris far.",
    "expect.realisticdisasters.earthquake.1": "rattling, small cracks, loose blocks fall.",
    "expect.realisticdisasters.earthquake.2": "open fissures, damaged buildings.",
    "expect.realisticdisasters.earthquake.3": "collapsing structures, wide chasms.",
    "expect.realisticdisasters.wildfire.1": "a slow grass fire.",
    "expect.realisticdisasters.wildfire.2": "a fast front with ember spotting.",
    "expect.realisticdisasters.wildfire.3": "a firestorm with fire whirls.",
    "expect.realisticdisasters.volcano.1": "ash, rumbling, small lava flows.",
    "expect.realisticdisasters.volcano.2": "lava bombs and a large ash plume.",
    "expect.realisticdisasters.volcano.3": "a huge eruption and pyroclastic flows.",
    "expect.realisticdisasters.meteor_shower.1": "a few small impacts.",
    "expect.realisticdisasters.meteor_shower.2": "craters and blast damage.",
    "expect.realisticdisasters.meteor_shower.3": "huge impacts, shockwaves, ejecta.",
    "expect.realisticdisasters.tsunami.1": "a surge along low coasts.",
    "expect.realisticdisasters.tsunami.2": "a wave wall that floods inland.",
    "expect.realisticdisasters.tsunami.3": "a towering wave carrying wreckage.",
    "expect.realisticdisasters.blizzard.1": "snowfall and wind.",
    "expect.realisticdisasters.blizzard.2": "whiteout and deep drifts.",
    "expect.realisticdisasters.blizzard.3": "a deadly, freezing storm.",
    "expect.realisticdisasters.sandstorm.1": "dust and haze.",
    "expect.realisticdisasters.sandstorm.2": "a wall of dust and dunes.",
    "expect.realisticdisasters.sandstorm.3": "a haboob that buries everything.",
}

ACRONYMS = {"fov": "FOV", "tps": "TPS", "op": "Op"}


def humanize(key):
    words = re.sub(r"([a-z0-9])([A-Z])", r"\1 \2", key).replace("_", " ").split()
    words = [ACRONYMS.get(w.lower(), w) for w in words]
    return (" ".join(words)).capitalize() if words else key


def config_keys():
    keys = set()
    for path in CONFIGS:
        src = open(os.path.join(BASE, path)).read()
        keys |= set(re.findall(r'\.(?:define|defineInRange|defineListAllowEmpty|defineList|defineEnum)\(\s*"([A-Za-z0-9_]+)"', src))
        keys |= set(re.findall(r'B\.push\("([A-Za-z0-9_]+)"\)', src))
    return keys


def main():
    lang = json.load(open(LANG))
    added = 0
    for k, v in TEXT.items():
        if lang.get(k) != v:
            lang[k] = v
            added += 1
    for key in sorted(config_keys()):
        lk = f"realisticdisasters.configuration.{key}"
        if lk not in lang:
            lang[lk] = humanize(key)
            added += 1
    with open(LANG, "w") as f:
        json.dump(lang, f, indent=2, ensure_ascii=False)
        f.write("\n")
    print(f"{added} entries added/updated, {len(lang)} total")


if __name__ == "__main__":
    main()
