package com.realisticdisasters.disaster;

/** Lifecycle shared by every disaster. */
public enum Phase {
    WARNING, BUILDUP, PEAK, AFTERMATH;

    public Phase next() {
        return this == AFTERMATH ? AFTERMATH : values()[ordinal() + 1];
    }

    public static Phase byId(int id) {
        return values()[Math.floorMod(id, values().length)];
    }
}
