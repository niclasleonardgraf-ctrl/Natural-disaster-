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

    // ---- earthquake ----
    public static final ModConfigSpec.BooleanValue EARTHQUAKE_ENABLED;
    public static final ModConfigSpec.IntValue EARTHQUAKE_WEIGHT;
    public static final ModConfigSpec.IntValue EARTHQUAKE_MAX_DEBRIS;
    public static final ModConfigSpec.DoubleValue EARTHQUAKE_DURATION_SCALE;
    public static final ModConfigSpec.BooleanValue EARTHQUAKE_FISSURES;
    public static final ModConfigSpec.DoubleValue EARTHQUAKE_FISSURE_LENGTH_SCALE;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> EARTHQUAKE_BIOMES;
    // ---- wildfire ----
    public static final ModConfigSpec.BooleanValue WILDFIRE_ENABLED;
    public static final ModConfigSpec.IntValue WILDFIRE_WEIGHT;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> WILDFIRE_BIOMES;
    public static final ModConfigSpec.DoubleValue WILDFIRE_DRY_BIOME_BONUS;
    public static final ModConfigSpec.IntValue WILDFIRE_MAX_IGNITIONS_PER_TICK;
    public static final ModConfigSpec.IntValue WILDFIRE_MAX_AREA;
    public static final ModConfigSpec.DoubleValue WILDFIRE_SPREAD_SCALE;
    public static final ModConfigSpec.DoubleValue WILDFIRE_DURATION_SCALE;
    public static final ModConfigSpec.BooleanValue WILDFIRE_RAIN_SLOWS;
    public static final ModConfigSpec.BooleanValue WILDFIRE_REGROWTH;

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

        B.push("earthquake");
        EARTHQUAKE_ENABLED = B.define("enabled", true);
        EARTHQUAKE_WEIGHT = B.comment("Relative weight when picking a natural disaster.")
                .defineInRange("weight", 10, 0, 1000);
        EARTHQUAKE_MAX_DEBRIS = B.comment("Max falling-block debris entities per earthquake (scaled by intensity / 5).")
                .defineInRange("maxDebris", 120, 0, 400);
        EARTHQUAKE_DURATION_SCALE = B.comment("Multiplier for how long the main shock lasts.")
                .defineInRange("durationScale", 1.0, 0.1, 10.0);
        EARTHQUAKE_FISSURES = B.comment("Whether fissures open in the ground (needs blockDestruction).")
                .define("fissures", true);
        EARTHQUAKE_FISSURE_LENGTH_SCALE = B.comment("Multiplier for fissure length.")
                .defineInRange("fissureLengthScale", 1.0, 0.1, 4.0);
        EARTHQUAKE_BIOMES = B.comment("Biomes (ids or #tags) where earthquakes can happen naturally.")
                .defineListAllowEmpty("biomes", List.of(
                        "#minecraft:is_mountain", "#minecraft:is_badlands", "#minecraft:is_hill",
                        "minecraft:stony_shore", "minecraft:windswept_gravelly_hills", "minecraft:windswept_savanna",
                        "minecraft:savanna_plateau", "minecraft:desert"),
                        () -> "#minecraft:is_mountain", o -> o instanceof String);
        B.push("wildfire");
        WILDFIRE_ENABLED = B.define("enabled", true);
        WILDFIRE_WEIGHT = B.comment("Relative weight when picking a natural disaster (before the dry-biome bonus).")
                .defineInRange("weight", 10, 0, 1000);
        WILDFIRE_BIOMES = B.comment("Biomes (ids or #tags) where wildfires can start naturally.")
                .defineListAllowEmpty("biomes", List.of(
                        "#minecraft:is_savanna", "#minecraft:is_forest", "#minecraft:is_taiga",
                        "minecraft:plains", "minecraft:sunflower_plains", "minecraft:meadow",
                        "minecraft:sparse_jungle", "minecraft:wooded_badlands", "minecraft:windswept_savanna"),
                        () -> "minecraft:savanna", o -> o instanceof String);
        WILDFIRE_DRY_BIOME_BONUS = B.comment("How much more likely wildfires are in hot, dry biomes. The weight is multiplied by up to (1 + bonus)",
                        "for a hot biome without rain (savanna, badlands) and reduced for cold or wet ones. 0 = same everywhere.")
                .defineInRange("dryBiomeBonus", 2.0, 0.0, 20.0);
        WILDFIRE_MAX_IGNITIONS_PER_TICK = B.comment("Cap on new burning columns per tick per wildfire (block changes also go through the shared queue).")
                .defineInRange("maxIgnitionsPerTick", 6, 1, 256);
        WILDFIRE_MAX_AREA = B.comment("Max columns one wildfire may burn at intensity 5 (scaled down for lower intensities).")
                .defineInRange("maxArea", 9000, 100, 200_000);
        WILDFIRE_SPREAD_SCALE = B.comment("Multiplier for how fast the fire front advances.")
                .defineInRange("spreadScale", 1.0, 0.1, 10.0);
        WILDFIRE_DURATION_SCALE = B.comment("Multiplier for how long the fire keeps spreading (peak phase).")
                .defineInRange("durationScale", 1.0, 0.1, 10.0);
        WILDFIRE_RAIN_SLOWS = B.comment("Rain slows the fire front down and shortens burning.")
                .define("rainSlows", true);
        WILDFIRE_REGROWTH = B.comment("Scorched earth slowly weathers back into dirt (grass then regrows on it). Off = burn scars stay forever.")
                .define("regrowth", true);
        B.pop();
    }

    public static final ModConfigSpec SPEC = B.build();

    private static boolean validArea(Object o) {
        return o instanceof String s && s.split(",").length == 4;
    }

    private DisasterConfig() {}
}
