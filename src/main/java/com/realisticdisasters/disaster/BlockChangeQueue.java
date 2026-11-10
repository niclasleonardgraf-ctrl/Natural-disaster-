package com.realisticdisasters.disaster;

import java.util.ArrayDeque;

import net.minecraft.server.level.ServerLevel;

/**
 * Per-dimension FIFO of pending block changes. Disasters enqueue work, the manager drains a fixed budget each
 * tick so a big disaster spreads its block updates over many ticks instead of spiking one.
 */
public final class BlockChangeQueue {
    @FunctionalInterface
    public interface Change {
        void apply(ServerLevel level);
    }

    private static final int HARD_CAP = 8192;
    private final ArrayDeque<Change> queue = new ArrayDeque<>();

    /** @return false if the queue is saturated and the change was dropped */
    public boolean add(Change change) {
        if (queue.size() >= HARD_CAP) return false;
        queue.add(change);
        return true;
    }

    public int size() {
        return queue.size();
    }

    public void drain(ServerLevel level, int budget) {
        for (int i = 0; i < budget && !queue.isEmpty(); i++) {
            queue.poll().apply(level);
        }
    }

    public void clear() {
        queue.clear();
    }
}
