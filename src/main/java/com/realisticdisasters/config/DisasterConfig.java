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

    // ---- meteor shower ----
    public static final ModConfigSpec.BooleanValue METEOR_ENABLED;
    public static final ModConfigSpec.IntValue METEOR_WEIGHT;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> METEOR_BIOMES;
    public static final ModConfigSpec.DoubleValue METEOR_DURATION_SCALE;
    public static final ModConfigSpec.DoubleValue METEOR_IMPACT_RATE;
    public static final ModConfigSpec.DoubleValue METEOR_CRATER_SCALE;
    public static final ModConfigSpec.DoubleValue METEOR_ORE_CHANCE;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> METEOR_ORES;
    public static final ModConfigSpec.BooleanValue METEOR_STARTS_FIRES;
    public static final ModConfigSpec.IntValue METEOR_MAX_EJECTA;
    public static final ModConfigSpec.BooleanValue METEOR_BLAST_DAMAGE;

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

        B.push("meteor_shower");
        METEOR_ENABLED = B.define("enabled", true);
        METEOR_WEIGHT = B.comment("Relative weight when picking a natural disaster.")
                .defineInRange("weight", 6, 0, 1000);
        METEOR_BIOMES = B.comment("Biomes (ids or #tags) where meteor showers can happen naturally. Default: every overworld biome.")
                .defineListAllowEmpty("biomes", List.of("#minecraft:is_overworld"), () -> "#minecraft:is_overworld", o -> o instanceof String);
        METEOR_DURATION_SCALE = B.comment("Multiplier for how long the impact phase lasts.")
                .defineInRange("durationScale", 1.0, 0.1, 10.0);
        METEOR_IMPACT_RATE = B.comment("Multiplier for how many meteors reach the ground.")
                .defineInRange("impactRate", 1.0, 0.0, 10.0);
        METEOR_CRATER_SCALE = B.comment("Multiplier for crater size.")
                .defineInRange("craterScale", 1.0, 0.25, 3.0);
        METEOR_ORE_CHANCE = B.comment("Chance (0-1) that a block of a meteorite's core is a rare ore instead of meteorite rock.")
                .defineInRange("oreChance", 0.12, 0.0, 1.0);
        METEOR_ORES = B.comment("Ores a meteorite core can contain: \"block_id\" or \"block_id*weight\".")
                .defineListAllowEmpty("ores", List.of("minecraft:iron_ore*8", "minecraft:raw_iron_block*2", "minecraft:gold_ore*4",
                        "minecraft:diamond_ore*2", "minecraft:emerald_ore*1", "minecraft:ancient_debris*1"),
                        () -> "minecraft:iron_ore*1", o -> o instanceof String);
        METEOR_STARTS_FIRES = B.comment("Impacts set fire to the crater rim (only if block destruction is on).")
                .define("startsFires", true);
        METEOR_MAX_EJECTA = B.comment("Max blocks thrown out of each crater as flying ejecta (real falling blocks). 0 = none.")
                .defineInRange("maxEjecta", 40, 0, 200);
        METEOR_BLAST_DAMAGE = B.comment("The blast shatters glass, strips leaves and flattens plants around impacts (only if block destruction is on).")
                .define("blastDamage", true);
        B.pop();
    }

    public static final ModConfigSpec SPEC = B.build();

    private static boolean validArea(Object o) {
        return o instanceof String s && s.split(",").length == 4;
    }

    private DisasterConfig() {}
}
