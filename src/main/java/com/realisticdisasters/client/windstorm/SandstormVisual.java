package com.realisticdisasters.client.windstorm;

import com.realisticdisasters.client.ClientDisaster;
import com.realisticdisasters.disaster.windstorm.WindStormDisaster;
import com.realisticdisasters.registry.ModRegistries;

/** Sandstorm: a sandy-orange brown-out (rust-red over the badlands), stinging grains and a dry, hissing howl. */
public final class SandstormVisual extends WindStormVisual {
    private static final Palette SAND = new Palette(
            0xC28A4E,   // dusty orange sky
            0xC99A5E,   // sandy orange fog
            0xB0804C,   // clouds
            0.28f,
            40f, 10f,
            ModRegistries.SAND_GRAIN::get, ModRegistries.SAND_HAZE::get,
            ModRegistries.SANDSTORM_HOWL::get, ModRegistries.SANDSTORM_DISTANT::get);
    private static final Palette RED_SAND = new Palette(
            0xA85E34,
            0xB86C3E,
            0x985636,
            0.32f,
            40f, 10f,
            ModRegistries.SAND_GRAIN::get, ModRegistries.SAND_HAZE::get,
            ModRegistries.SANDSTORM_HOWL::get, ModRegistries.SANDSTORM_DISTANT::get);

    @Override
    protected Palette palette(ClientDisaster d) {
        return d.data(WindStormDisaster.D_VARIANT) > 0.5f ? RED_SAND : SAND;
    }
}
