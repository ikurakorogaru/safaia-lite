package com.example.client;

/** One immutable, packed traversal shared by every manual unload operation. */
final class ChunkOffsets {
    private static final int[] FAR_FIRST = build();
    private ChunkOffsets() {}

    static int pairCount() { return FAR_FIRST.length / 2; }
    static int x(int index) { return FAR_FIRST[index * 2]; }
    static int z(int index) { return FAR_FIRST[index * 2 + 1]; }

    private static int[] build() {
        int[] buffer = new int[33 * 33 * 2];
        int count = 0;
        for (int ring = 16; ring >= 1; ring--) {
            for (int x = -ring; x <= ring; x++) {
                count = add(buffer, count, x, -ring);
                count = add(buffer, count, x, ring);
            }
            for (int z = -ring + 1; z < ring; z++) {
                count = add(buffer, count, -ring, z);
                count = add(buffer, count, ring, z);
            }
        }
        return java.util.Arrays.copyOf(buffer, count);
    }

    private static int add(int[] buffer, int count, int x, int z) {
        if (x * x + z * z > 64) {
            buffer[count++] = x;
            buffer[count++] = z;
        }
        return count;
    }
}
