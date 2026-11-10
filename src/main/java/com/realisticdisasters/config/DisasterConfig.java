package com.realisticdisasters.config;

import java.util.List;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server-side (common) settings. Loaded on both physical sides, but only the logical server reads it.
 */
public final class DisasterConfig {
    private static final ModConfigSpec.Builder B = new ModConfigSpec.Builder();

    // ---- general ----
    public static final ModConfigSpec.BooleanValue NATURAL_SPAWNING;
    public static final ModConfigSpec.IntValue SPAWN_CHECK_INTERVAL;
    public static final ModConfigSpec.DoubleValue SPAWN_CHANCE;
    public static final ModConfigSpec.IntValue MAX_INTENSITY;
    public static final ModConfigSpec.IntValue MAX_ACTIVE;
    public static final ModConfigSpec.IntValue SYNC_RANGE;

    // ---- destruction / protection ----
    public static final ModConfigSpec.BooleanValue BLOCK_DESTRUCTION;
    public static final ModConfigSpec.IntValue MAX_BLOCK_CHANGES_PER_TICK;
    public static final ModConfigSpec.IntValue SPAWN_PROTECTION_RADIUS;
    public static final ModConfigSpec.BooleanValue RESPECT_CLAIMS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> PROTECTED_AREAS;

    // ---- tornado ----
    public static final ModConfigSpec.BooleanValue TORNADO_ENABLED;
    public static final ModConfigSpec.IntValue TORNADO_WEIGHT;
    public static final ModConfigSpec.IntValue TORNADO_MAX_DEBRIS;
    public static final ModConfigSpec.DoubleValue TORNADO_DURATION_SCALE;
    public static final ModConfigSpec.BooleanValue TORNADO_CHANGES_WEATHER;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> TORNADO_BIOMES;

    // ---- volcano ----
    public static final ModConfigSpec.BooleanValue VOLCANO_ENABLED;
    public static final ModConfigSpec.IntValue VOLCANO_WEIGHT;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> VOLCANO_BIOMES;
    public static final ModConfigSpec.IntValue VOLCANO_MAX_BOMBS;
    public static final ModConfigSpec.DoubleValue VOLCANO_DURATION_SCALE;
    public static final ModConfigSpec.BooleanValue VOLCANO_BUILD_CONE;
    public static final ModConfigSpec.BooleanValue VOLCANO_LAVA_FLOWS;
    public static final ModConfigSpec.IntValue VOLCANO_COOLING_TICKS;
    public static final ModConfigSpec.BooleanValue VOLCANO_LIGHTNING;
    public static final ModConfigSpec.BooleanValue VOLCANO_PYROCLASTIC_FLOWS;
    public static final ModConfigSpec.BooleanValue VOLCANO_ASH_FALL;

    static {
        B.push("general");
        NATURAL_SPAWNING = B.comment("Whether disasters start naturally near players.")
                .define("naturalSpawning", true);
        SPAWN_CHECK_INTERVAL = B.comment("Ticks between natural spawn rolls (per dimension). 6000 = 5 minutes.")
                .defineInRange("spawnCheckInterval", 6000, 200, 1_000_000);
        SPAWN_CHANCE = B.comment("Chance (0-1) that a roll actually starts a disaster. Frequency = chance / interval.")
                .defineInRange("spawnChance", 0.12, 0.0, 1.0);
        MAX_INTENSITY = B.comment("Intensity cap (1-5) for natural and command-started disasters.")
                .defineInRange("maxIntensity", 5, 1, 5);
        MAX_ACTIVE = B.comment("Maximum simultaneously active disasters per dimension.")
                .defineInRange("maxActive", 3, 1, 32);
        SYNC_RANGE = B.comment("Players within this many blocks of a disaster receive its effects.")
                .defineInRange("syncRange", 512, 64, 4096);
        B.pop();

        B.push("destruction");
        BLOCK_DESTRUCTION = B.comment("Whether disasters may change or destroy blocks at all.")
                .define("blockDestruction", true);
        MAX_BLOCK_CHANGES_PER_TICK = B.comment("Block changes applied per tick per dimension (batched across ticks).")
                .defineInRange("maxBlockChangesPerTick", 48, 1, 4096);
        SPAWN_PROTECTION_RADIUS = B.comment("No block damage within this horizontal radius of the world spawn. 0 = off.")
                .defineInRange("spawnProtectionRadius", 48, 0, 100_000);
        RESPECT_CLAIMS = B.comment("Ask claim/protection mods before breaking blocks (fires a cancellable block-break event as a fake player).")
                .define("respectClaims", true);
        PROTECTED_AREAS = B.comment("Extra protected areas: \"dimension,x,z,radius\", e.g. \"minecraft:overworld,0,0,100\".")
                .defineListAllowEmpty("protectedAreas", List.of(), () -> "minecraft:overworld,0,0,64", DisasterConfig::validArea);
        B.pop();

        B.push("tornado");
        TORNADO_ENABLED = B.define("enabled", true);
        TORNADO_WEIGHT = B.comment("Relative weight when picking a natural disaster.")
                .defineInRange("weight", 10, 0, 1000);
        TORNADO_MAX_DEBRIS = B.comment("Max airborne falling-block debris entities per tornado (scaled by intensity / 5).")
                .defineInRange("maxDebris", 60, 0, 400);
        TORNADO_DURATION_SCALE = B.comment("Multiplier for how long a tornado stays on the ground.")
                .defineInRange("durationScale", 1.0, 0.1, 10.0);
        TORNADO_CHANGES_WEATHER = B.comment("Start rain/thunder while a tornado is active.")
                .define("changesWeather", true);
        TORNADO_BIOMES = B.comment("Biomes (ids or #tags) where tornadoes can form naturally.")
                .defineListAllowEmpty("biomes", List.of(
                        "minecraft:plains", "minecraft:sunflower_plains", "minecraft:savanna", "minecraft:savanna_plateau",
                        "minecraft:meadow", "minecraft:forest", "minecraft:birch_forest", "minecraft:flower_forest",
                        "minecraft:swamp", "minecraft:badlands", "minecraft:wooded_badlands"),
                        () -> "minecraft:plains", o -> o instanceof String);
        B.pop();

        B.push("volcano");
        VOLCANO_ENABLED = B.define("enabled", true);
        VOLCANO_WEIGHT = B.comment("Relative weight when picking a natural disaster.")
                .defineInRange("weight", 8, 0, 1000);
        VOLCANO_BIOMES = B.comment("Biomes (ids or #tags) where volcanoes can erupt naturally.")
                .defineListAllowEmpty("biomes", List.of(
                        "#minecraft:is_mountain", "minecraft:windswept_hills", "minecraft:windswept_gravelly_hills",
                        "minecraft:windswept_forest"),
                        () -> "#minecraft:is_mountain", o -> o instanceof String);
        VOLCANO_MAX_BOMBS = B.comment("Max lava bombs in the air at once per volcano (scaled by intensity / 5).")
                .defineInRange("maxBombs", 40, 0, 400);
        VOLCANO_DURATION_SCALE = B.comment("Multiplier for how long the eruption lasts.")
                .defineInRange("durationScale", 1.0, 0.1, 10.0);
        VOLCANO_BUILD_CONE = B.comment("Grow the volcano's mountain (from a ~10-block cone at intensity 1 to a ~130-block mountain at 5).")
                .define("buildCone", true);
        VOLCANO_LAVA_FLOWS = B.comment("Let lava flows pour downhill from the vent.")
                .define("lavaFlows", true);
        VOLCANO_COOLING_TICKS = B.comment("Ticks before placed lava cools into basalt / blackstone (always cooled when the volcano ends).")
                .defineInRange("coolingTicks", 900, 20, 72_000);
        VOLCANO_LIGHTNING = B.comment("Volcanic lightning in the ash column (visual only, never starts fires).")
                .define("lightning", true);
        VOLCANO_PYROCLASTIC_FLOWS = B.comment("Pyroclastic flows (hot ash avalanches) at intensity 3 and above.")
                .define("pyroclasticFlows", true);
        VOLCANO_ASH_FALL = B.comment("Volcanic ash settles in layers on the ground downwind.")
                .define("ashFall", true);
        B.pop();
    }

    public static final ModConfigSpec SPEC = B.build();

    private static boolean validArea(Object o) {
        return o instanceof String s && s.split(",").length == 4;
    }

    private DisasterConfig() {}
}
