package com.example.client;

import com.example.optimization.IdleCache;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.lang.management.ManagementFactory;

/** Standalone checks use real production algorithms, with no Minecraft/world mutation. */
public final class CoreChecks {
    private static int assertions;
    private static volatile long sink;
    private static void check(boolean ok, String message) {
        assertions++;
        if (!ok) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        offsets();
        cache();
        survey();
        videoBackup();
        if (args.length > 0) originalJar(args[0]);
        System.out.println("PASS: " + assertions + " assertions (offsets, cache, survey, backup I/O)");
        benchmark();
    }

    private static void offsets() {
        for (int radius = 0; radius <= 16; radius++) {
            List<int[]> expected = originalOffsets(radius);
            int matched = 0;
            HashSet<Long> unique = new HashSet<>();
            for (int i = 0; i < ChunkOffsets.pairCount(); i++) {
                int x = ChunkOffsets.x(i), z = ChunkOffsets.z(i);
                if (Math.abs(x) > radius || Math.abs(z) > radius) continue;
                check(x*x+z*z > 64, "Protected radius excluded");
                check(unique.add(((long)x << 32) | (z & 0xffffffffL)), "No duplicates");
                int[] point = expected.get(matched++);
                check(point[0] == x && point[1] == z, "Original traversal order");
            }
            check(matched == expected.size(), "Original traversal coverage");
            for (int limit : new int[]{1,10,20,50,100}) {
                check(Math.min(matched,limit) == Math.min(expected.size(),limit), "Unload limit");
            }
        }
    }

    private static void originalJar(String path) throws Exception {
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{java.nio.file.Path.of(path).toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            var original = Class.forName("com.example.client.ChunkOffsets", false, loader).getDeclaredMethod("farFirst", int.class);
            original.setAccessible(true);
            for (int radius=0; radius<=16; radius++) {
                List<?> actual = (List<?>)original.invoke(null,radius);
                List<int[]> fixture = originalOffsets(radius);
                check(actual.size()==fixture.size(),"Actual original JAR coverage");
                for(int i=0;i<actual.size();i++) {
                    check(java.util.Arrays.equals((int[])actual.get(i),fixture.get(i)),"Actual original JAR order");
                }
            }
        }
        System.out.println("PASS: offset fixture matches the supplied original JAR for all radii 0..16");
    }

    private static void cache() throws Exception {
        AtomicLong time = new AtomicLong(0);
        IdleCache cache = new IdleCache(3, 100, time::get);
        check(cache.cleanup()==0 && cache.clear()==0 && cache.get(null)==null, "Empty cache");
        cache.put(null,"x"); cache.put("x",null);
        check(cache.size()==0,"Ignore nulls");
        cache.put("a",1); time.set(10); cache.put("b",2); time.set(20); cache.put("c",3);
        time.set(50); check(cache.get("a").equals(1), "Access refreshes timestamp and LRU order");
        cache.put("d",4);
        check(cache.get("b")==null && cache.size()==3,"Evict least recently used");
        time.set(119); check(cache.cleanup()==0,"Not expired before exact boundary");
        time.set(120); check(cache.cleanup()==1 && cache.get("c")==null,"Exact timeout");
        time.set(149); check(cache.cleanup()==0,"Refreshed entries retained");
        time.set(150); check(cache.cleanup()==2 && cache.size()==0,"Expired entries removed");
        cache.put("a",1); cache.put("a",2);
        check(cache.size()==1 && cache.get("a").equals(2),"Replacement");
        cache.remove("a"); check(cache.size()==0,"Remove last entry");
        // nanoTime wraps; subtraction must continue to represent elapsed time.
        time.set(Long.MAX_VALUE - 50); cache.put("wrap",1);
        time.set(Long.MIN_VALUE + 48); check(cache.cleanup()==0,"Clock wrap before expiry");
        time.set(Long.MIN_VALUE + 49); check(cache.cleanup()==1,"Clock wrap at expiry");
        IdleCache concurrent = new IdleCache(256, Long.MAX_VALUE, System::nanoTime);
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread[] workers = new Thread[4];
        for (int t=0;t<workers.length;t++) {
            final int id=t;
            workers[t]=Thread.ofPlatform().unstarted(() -> {
                for(int n=0;n<2000;n++) {
                    String key=id+":"+n;
                    concurrent.put(key,n); concurrent.get(key);
                    if(n%3==0) concurrent.remove(key);
                    if(n%100==0) concurrent.cleanup();
                }
            });
            workers[t].setUncaughtExceptionHandler((thread,error)->failure.compareAndSet(null,error));
            workers[t].start();
        }
        for(Thread t:workers) t.join();
        check(failure.get()==null,"Concurrent operations completed without exceptions");
        check(concurrent.size()<=256,"Bounded under concurrent access");
        check(concurrent.clear()<=256 && concurrent.size()==0,"Release storage");
    }

    private static void survey() {
        Object world = new Object();
        ChunkSurvey scan = new ChunkSurvey();
        int[] probes = {0};
        ChunkSurvey.Probe all = (x,z) -> { probes[0]++; return true; };
        int completions=0, lastCompletion=0;
        boolean[] first=null,second=null;
        for(int tick=1;tick<=100;tick++) {
            int before=probes[0];
            if(scan.update(world,0,0,16,all)) {
                completions++;
                check(scan.loaded()==1089 && scan.near()==197,"Complete counts");
                if(completions==1) { check(tick==18,"First full scan in 18 ticks"); first=scan.cells(); }
                else check(tick-lastCompletion==40,"40-tick scan period");
                if(completions==2) second=scan.cells();
                if(completions==3) check(scan.cells()==first && first!=second,"Reuse two buffers");
                lastCompletion=tick;
            }
            check(probes[0]-before<=64,"Per-tick probe budget");
        }
        check(completions==3,"Expected number of scans");
        scan.update(world,100,-100,16,all);
        check(!scan.ready(),"Discard snapshot on movement");
        scan.update(new Object(),100,-100,16,all);
        check(!scan.ready(),"Discard snapshot on world change");
        int before=probes[0];
        scan.update(null,0,0,0,all);
        check(probes[0]==before && !scan.ready(),"No disconnected probes");
        check(scan.update(world,Integer.MAX_VALUE,Integer.MIN_VALUE,0,all),"Radius zero completes");
        check(scan.loaded()==1 && scan.near()==1,"Radius zero counts");
        scan.invalidate(); check(!scan.ready(),"Explicit invalidation");
        for(int r=0;r<=16;r++) {
            ChunkSurvey partial=new ChunkSurvey();
            ChunkSurvey.Probe pattern=(x,z)->((x*31+z)&3)==0;
            for(int t=0;t<18;t++) partial.update(world,-10,20,r,pattern);
            int count=0,near=0,side=r*2+1;
            for(int z=-r;z<=r;z++) for(int x=-r;x<=r;x++) {
                boolean expected=pattern.loaded(-10+x,20+z);
                check(partial.cells()[(z+r)*side+x+r]==expected,"Cell positions and partial occupancy");
                if(expected) {count++;if(x*x+z*z<=64)near++;}
            }
            check(partial.loaded()==count && partial.near()==near,"Partial counts");
        }
    }

    private static void videoBackup() throws Exception {
        java.nio.file.Path build = java.nio.file.Path.of("build", "test-state");
        java.nio.file.Files.createDirectories(build);
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory(build, "backup-");
        java.nio.file.Path active = dir.resolve("active.properties"), last = dir.resolve("last.properties");
        java.util.Properties original = new java.util.Properties();
        original.setProperty("render", "12");
        original.setProperty("name", "Safaia 日本語");
        VideoBackup.saveOriginal(active, original);
        check(VideoBackup.read(active).equals(original), "Backup text round trip");
        java.util.Properties different = new java.util.Properties();
        different.setProperty("render", "8");
        VideoBackup.saveOriginal(active, different);
        check(VideoBackup.read(active).equals(original), "Repeat apply preserves original");
        VideoBackup.retainRestored(active, last);
        check(!java.nio.file.Files.exists(active) && VideoBackup.read(last).equals(original), "Recovery retained after restore");
        VideoBackup.saveOriginal(active, different);
        check(VideoBackup.read(active).equals(different), "New session captures current settings");
        VideoBackup.retainRestored(active, last);
        check(VideoBackup.read(last).equals(different), "Latest recovery replaces previous one");
        java.nio.file.Path file = dir.resolve("parent-is-file");
        java.nio.file.Files.writeString(file,"occupied");
        boolean failed=false;
        try { VideoBackup.saveOriginal(file.resolve("backup"), original); }
        catch(java.io.IOException e) { failed=true; }
        check(failed, "Backup write failure is propagated before settings can change");
        check(VideoBackup.read(last).equals(different), "Failure preserves recovery");
        try(var files=java.nio.file.Files.list(dir)) {
            check(files.noneMatch(p->p.toString().endsWith(".tmp")), "No leftover partial backups");
        }
    }

    /** Exact restored 1.1.0 allocation algorithm, retained only as a benchmark fixture. */
    static List<int[]> originalOffsets(int radius) {
        ArrayList<int[]> result=new ArrayList<>();
        for(int ring=Math.min(16,radius);ring>=1;ring--) {
            for(int x=-ring;x<=ring;x++){add(result,x,-ring);add(result,x,ring);}
            for(int z=-ring+1;z<ring;z++){add(result,-ring,z);add(result,ring,z);}
        }
        return result;
    }
    static void add(List<int[]> result,int x,int z){if(x*x+z*z>64)result.add(new int[]{x,z});}
    static long oldTraversal() {
        long sum=0; for(int[] xy:originalOffsets(16))sum+=xy[0]*31L+xy[1]; return sum;
    }
    static long newTraversal() {
        long sum=0;for(int i=0;i<ChunkOffsets.pairCount();i++)sum+=ChunkOffsets.x(i)*31L+ChunkOffsets.z(i);return sum;
    }
    private static void benchmark() {
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        if(!bean.isThreadAllocatedMemorySupported()){System.out.println("Allocation counter unavailable");return;}
        bean.setThreadAllocatedMemoryEnabled(true);
        for(int i=0;i<3000;i++){sink=oldTraversal();sink=newTraversal();}
        int repeats=10000;
        long tid=Thread.currentThread().threadId();
        long before=bean.getThreadAllocatedBytes(tid);
        for(int i=0;i<repeats;i++)sink=oldTraversal();
        long oldBytes=bean.getThreadAllocatedBytes(tid)-before;
        before=bean.getThreadAllocatedBytes(tid);
        for(int i=0;i<repeats;i++)sink=newTraversal();
        long newBytes=bean.getThreadAllocatedBytes(tid)-before;
        System.out.println("Offset traversal allocation: original="+(oldBytes/repeats)+" B/op, lite="+(newBytes/repeats)+" B/op");
        System.out.println("Benchmark scope: offset generation/traversal only; not Minecraft FPS or total RAM.");
    }
}
