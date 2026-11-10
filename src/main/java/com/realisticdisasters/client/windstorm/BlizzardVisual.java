package com.realisticdisasters.client.windstorm;

import com.realisticdisasters.client.ClientDisaster;
import com.realisticdisasters.registry.ModRegistries;

/** Blizzard: a cold white-out. Pale blue-grey sky, white fog, horizontal snow, a high whistling howl. */
public final class BlizzardVisual extends WindStormVisual {
    private static final Palette PALETTE = new Palette(
            0xC4CDD6,   // overcast blue-grey sky
            0xE4EAF0,   // white fog
            0xD8DEE4,   // clouds
            0.18f,      // light darkening: a blizzard is bright, just blind
            36f, 9f,    // fog end at intensity 1 / 5 (blocks)
            ModRegistries.BLIZZARD_SNOW::get, ModRegistries.SNOW_HAZE::get,
            ModRegistries.BLIZZARD_HOWL::get, ModRegistries.BLIZZARD_DISTANT::get);

    @Override
    protected Palette palette(ClientDisaster d) {
        return PALETTE;
    }
}
