package com.realisticdisasters.client;

import java.util.EnumMap;
import java.util.Map;

import com.realisticdisasters.client.tornado.TornadoVisual;
import com.realisticdisasters.client.earthquake.EarthquakeVisual;
import com.realisticdisasters.disaster.DisasterType;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;

/** Maps each {@link DisasterType} to its client visual. */
public final class DisasterVisuals {
    private static final DisasterVisual NONE = new DisasterVisual() {
        @Override
        public void contribute(ClientDisaster d, Vec3 camera, Atmosphere out) {}

        @Override
        public void tick(ClientDisaster d, ClientLevel level, Camera camera) {}
    };

    private static final Map<DisasterType, DisasterVisual> VISUALS = new EnumMap<>(DisasterType.class);

    static {
        VISUALS.put(DisasterType.TORNADO, new TornadoVisual());
        VISUALS.put(DisasterType.EARTHQUAKE, new EarthquakeVisual());
    }

    public static DisasterVisual get(DisasterType type) {
        return VISUALS.getOrDefault(type, NONE);
    }

    private DisasterVisuals() {}
}
