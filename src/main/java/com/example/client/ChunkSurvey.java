package com.example.client;

import java.util.Arrays;

/** Screen-owned survey: no work when the screen is closed, at most 64 probes per tick. */
final class ChunkSurvey {
    static final int BUDGET = 64;
    interface Probe { boolean loaded(int x, int z); }
    private Object world;
    private int radius = -1, centerX, centerZ, cursor, loaded, near, cooldown;
    private boolean[] front = new boolean[1089], back = new boolean[1089];
    private boolean ready;
    private int visibleLoaded, visibleNear;

    boolean update(Object world, int x, int z, int radius, Probe probe) {
        radius = Math.clamp(radius, 0, 16);
        if (world != this.world || x != centerX || z != centerZ || radius != this.radius) {
            this.world = world;
            this.centerX = x;
            this.centerZ = z;
            this.radius = radius;
            invalidate();
        }
        if (world == null) return false;
        if (cooldown > 0) { cooldown--; return false; }
        int side = radius * 2 + 1, count = side * side;
        int end = Math.min(count, cursor + BUDGET);
        for (; cursor < end; cursor++) {
            int dx = cursor % side - radius, dz = cursor / side - radius;
            boolean present = probe.loaded(x + dx, z + dz);
            back[cursor] = present;
            if (present) {
                loaded++;
                if (dx * dx + dz * dz <= 64) near++;
            }
        }
        if (cursor != count) return false;
        boolean[] oldFront = front; front = back; back = oldFront;
        visibleLoaded = loaded; visibleNear = near; ready = true;
        cursor = loaded = near = 0;
        // Keep starts at least 40 ticks apart (two seconds at 20 TPS).
        cooldown = Math.max(0, 40 - (count + BUDGET - 1) / BUDGET);
        return true;
    }
    void invalidate() {
        cursor = loaded = near = cooldown = 0;
        visibleLoaded = visibleNear = 0;
        ready = false;
        Arrays.fill(front, false);
    }
    boolean ready() { return ready; }
    int radius() { return Math.max(0, radius); }
    int centerX() { return centerX; }
    int centerZ() { return centerZ; }
    boolean[] cells() { return front; }
    int loaded() { return visibleLoaded; }
    int near() { return visibleNear; }
}
