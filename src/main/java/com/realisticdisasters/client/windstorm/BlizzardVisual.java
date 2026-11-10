package com.realisticdisasters.client.windstorm;

import com.realisticdisasters.client.ClientDisaster;
import com.realisticdisasters.registry.ModRegistries;

/** Blizzard: a cold white-out. Pale blue-grey sky, white fog, horizontal snow, a high whistling howl. */
public final class BlizzardVisual extends WindStormVisual {
    private static final Palette PALETTE = new Palette(
            0xBFC8D1,   // overcast blue-grey sky (seen from outside)
            0xD3D9DF,   // white-out: light grey, faintly blue (flakes read brighter than it, as in real footage)
            0xC1C8D0,   // densest blowing snow: flat grey, no contrast left
            0xC3CAD2,   // clouds
            0.16f,      // light darkening: a blizzard is bright, just blind (flat light)
            36f, 8f,    // fog end at intensity 1 / 5 (blocks)
            14f, 6f,    // snow curtain height seen from outside
            ModRegistries.BLIZZARD_SNOW::get, ModRegistries.SNOW_HAZE::get,
            ModRegistries.BLIZZARD_HOWL::get, ModRegistries.BLIZZARD_DISTANT::get);

    @Override
    protected Palette palette(ClientDisaster d) {
        return PALETTE;
    }
}
