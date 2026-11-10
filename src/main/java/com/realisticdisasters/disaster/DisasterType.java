package com.realisticdisasters.disaster;

import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import com.realisticdisasters.config.DisasterConfig;
import com.realisticdisasters.disaster.tornado.TornadoDisaster;
import com.realisticdisasters.disaster.windstorm.BlizzardDisaster;
import com.realisticdisasters.disaster.windstorm.SandstormDisaster;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * All disaster kinds. New disasters add an entry here plus a {@link Disaster} subclass and a client visual.
 */
public enum DisasterType {
    TORNADO(TornadoDisaster::new, DisasterConfig.TORNADO_ENABLED::getAsBoolean,
            DisasterConfig.TORNADO_WEIGHT::getAsInt, DisasterConfig.TORNADO_BIOMES::get),
    BLIZZARD(BlizzardDisaster::new, DisasterConfig.BLIZZARD_ENABLED::getAsBoolean,
            DisasterConfig.BLIZZARD_WEIGHT::getAsInt, DisasterConfig.BLIZZARD_BIOMES::get),
    SANDSTORM(SandstormDisaster::new, DisasterConfig.SANDSTORM_ENABLED::getAsBoolean,
            DisasterConfig.SANDSTORM_WEIGHT::getAsInt, DisasterConfig.SANDSTORM_BIOMES::get);

    public interface Factory {
        Disaster create(ServerLevel level, int id, Vec3 pos, int intensity);
    }

    private final Factory factory;
    private final BooleanSupplier enabled;
    private final IntSupplier weight;
    private final Supplier<List<? extends String>> biomes;

    DisasterType(Factory factory, BooleanSupplier enabled, IntSupplier weight, Supplier<List<? extends String>> biomes) {
        this.factory = factory;
        this.enabled = enabled;
        this.weight = weight;
        this.biomes = biomes;
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
