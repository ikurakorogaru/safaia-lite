package com.example.optimization;

public final class SafaiaCacheManager {
    private static final IdleCache CACHE = new IdleCache(256, 300_000_000_000L, System::nanoTime);
    private SafaiaCacheManager() {}
    public static void put(String key, Object value) { CACHE.put(key, value); }
    public static Object get(String key) { return CACHE.get(key); }
    public static void remove(String key) { CACHE.remove(key); }
    public static int cleanup() { return CACHE.cleanup(); }
    public static int clear() { return CACHE.clear(); }
    public static int getCacheCount() { return CACHE.size(); }
}
