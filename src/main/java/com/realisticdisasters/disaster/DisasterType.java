package com.realisticdisasters.disaster;

import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import com.realisticdisasters.config.DisasterConfig;
import com.realisticdisasters.disaster.tornado.TornadoDisaster;
import com.realisticdisasters.disaster.earthquake.EarthquakeDisaster;
import com.realisticdisasters.disaster.wildfire.WildfireDisaster;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * All disaster kinds. New disasters add an entry here plus a {@link Disaster} subclass and a client visual.
 */
public enum DisasterType {
    TORNADO(TornadoDisaster::new, DisasterConfig.TORNADO_ENABLED::getAsBoolean,
            DisasterConfig.TORNADO_WEIGHT::getAsInt, DisasterConfig.TORNADO_BIOMES::get),
    EARTHQUAKE(EarthquakeDisaster::new, DisasterConfig.EARTHQUAKE_ENABLED::getAsBoolean,
            DisasterConfig.EARTHQUAKE_WEIGHT::getAsInt, DisasterConfig.EARTHQUAKE_BIOMES::get),
    WILDFIRE(WildfireDisaster::new, DisasterConfig.WILDFIRE_ENABLED::getAsBoolean,
            DisasterConfig.WILDFIRE_WEIGHT::getAsInt, DisasterConfig.WILDFIRE_BIOMES::get, WildfireDisaster::biomeWeight);

    public interface Factory {
        Disaster create(ServerLevel level, int id, Vec3 pos, int intensity);
    }

    private final Factory factory;
    private final BooleanSupplier enabled;
    private final IntSupplier weight;
    private final Supplier<List<? extends String>> biomes;
    private final BiomeWeight biomeWeight;

    /** Optional per-biome adjustment of the spawn weight (e.g. wildfires prefer hot, dry biomes). */
    public interface BiomeWeight {
        int adjust(Holder<Biome> biome, int baseWeight);
    }

    DisasterType(Factory factory, BooleanSupplier enabled, IntSupplier weight, Supplier<List<? extends String>> biomes) {
        this(factory, enabled, weight, biomes, (biome, w) -> w);
    }

    DisasterType(Factory factory, BooleanSupplier enabled, IntSupplier weight, Supplier<List<? extends String>> biomes,
                 BiomeWeight biomeWeight) {
        this.factory = factory;
        this.enabled = enabled;
        this.weight = weight;
        this.biomes = biomes;
        this.biomeWeight = biomeWeight;
    }

    public Disaster create(ServerLevel level, int id, Vec3 pos, int intensity) {
        return factory.create(level, id, pos, intensity);
    }

    public boolean enabled() {
        return enabled.getAsBoolean();
    }

    public int weight() {
        return weight.getAsInt();
    }

    /** Spawn weight for a specific biome (defaults to {@link #weight()}). */
    public int weight(Holder<Biome> biome) {
        return Math.max(0, biomeWeight.adjust(biome, weight()));
    }

    public List<? extends String> biomes() {
        return biomes.get();
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static DisasterType byId(int ordinal) {
        return values()[Math.floorMod(ordinal, values().length)];
    }
}
