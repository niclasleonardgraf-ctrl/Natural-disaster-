package com.realisticdisasters.client.windstorm;

import com.realisticdisasters.client.ClientDisaster;
import com.realisticdisasters.disaster.windstorm.WindStormDisaster;
import com.realisticdisasters.registry.ModRegistries;

/** Sandstorm: a sandy-orange brown-out (rust-red over the badlands), stinging grains and a dry, hissing howl. */
public final class SandstormVisual extends WindStormVisual {
    private static final Palette SAND = new Palette(
            0xC28A4E,   // dusty orange sky ahead of the storm
            0xC6965C,   // sandy orange fog
            0x8E5A2E,   // deep brown-orange at the core of a strong haboob
            0xB0804C,   // clouds
            0.36f,      // "day turns to night" at high intensity
            40f, 6f,    // fog end at intensity 1 / 5 (near-zero visibility)
            30f, 15f,   // haboob wall height: 45 .. 105 blocks
            ModRegistries.SAND_GRAIN::get, ModRegistries.SAND_HAZE::get,
            ModRegistries.SANDSTORM_HOWL::get, ModRegistries.SANDSTORM_DISTANT::get);
    private static final Palette RED_SAND = new Palette(
            0xA85E34,
            0xB46A3C,
            0x7A3A1E,
            0x985636,
            0.4f,
            40f, 6f,
            30f, 15f,
            ModRegistries.SAND_GRAIN::get, ModRegistries.SAND_HAZE::get,
            ModRegistries.SANDSTORM_HOWL::get, ModRegistries.SANDSTORM_DISTANT::get);

    @Override
    protected Palette palette(ClientDisaster d) {
        return d.data(WindStormDisaster.D_VARIANT) > 0.5f ? RED_SAND : SAND;
    }
}
