package com.example.optimization;

import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Bounded, lazy LRU cache. All map operations and timestamps share one lock. */
public final class IdleCache {
    private final int capacity;
    private final long timeout;
    private final LongSupplier clock;
    private LinkedHashMap<String, Entry> entries;
    private static final class Entry {
        final Object value;
        long accessed;
        Entry(Object value, long accessed) { this.value = value; this.accessed = accessed; }
    }
    public IdleCache(int capacity, long timeout, LongSupplier clock) {
        if (capacity < 1 || timeout <= 0) throw new IllegalArgumentException();
        this.capacity = capacity;
        this.timeout = timeout;
        this.clock = Objects.requireNonNull(clock);
    }
    public synchronized void put(String key, Object value) {
        if (key == null || value == null) return;
        if (entries == null) entries = new LinkedHashMap<>(16, 0.75f, true);
        entries.put(key, new Entry(value, clock.getAsLong()));
        while (entries.size() > capacity) entries.pollFirstEntry();
    }
    public synchronized Object get(String key) {
        if (key == null || entries == null) return null;
        Entry entry = entries.get(key);
        if (entry == null) return null;
        // Preserve the original idle-cache semantics: get refreshes the idle timer.
        entry.accessed = clock.getAsLong();
        return entry.value;
    }
    public synchronized void remove(String key) {
        if (key != null && entries != null) {
            entries.remove(key);
            if (entries.isEmpty()) entries = null;
        }
    }
    public synchronized int cleanup() {
        if (entries == null) return 0;
        long now = clock.getAsLong();
        int removed = 0;
        while (!entries.isEmpty()) {
            if (now - entries.firstEntry().getValue().accessed < timeout) break;
            entries.pollFirstEntry();
            removed++;
        }
        if (entries.isEmpty()) entries = null;
        return removed;
    }
    public synchronized int clear() {
        int count = size();
        entries = null;
        return count;
    }
    public synchronized int size() { return entries == null ? 0 : entries.size(); }
}
