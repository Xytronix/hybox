package io.github.xytronix.hybox.core.health;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record HealthSnapshot(
    int targetTps,
    Cpu cpu,
    Memory memory,
    List<Gc> gc,
    Sys system,
    Disk disk,
    Threads threads,
    int onlinePlayers,
    List<World> worlds,
    List<HotThread> hotThreads
) {
    public HealthSnapshot {
        gc = gc == null ? List.of() : List.copyOf(gc);
        worlds = worlds == null ? List.of() : List.copyOf(worlds);
        hotThreads = hotThreads == null ? List.of() : List.copyOf(hotThreads);
    }

    public int onlineTotal() {
        return onlineTotal(onlinePlayers, worlds);
    }

    public static int onlineTotal(int onlinePlayers, List<World> worlds) {
        if (onlinePlayers >= 0) {
            return onlinePlayers;
        }
        int sum = 0;
        boolean known = false;
        for (World world : worlds == null ? List.<World>of() : worlds) {
            if (world.players() >= 0) {
                sum += world.players();
                known = true;
            }
        }
        return known ? sum : -1;
    }

    public record Cpu(int cores) {}

    public record Memory(long heapUsed, long heapCommitted, long heapMax, long nonHeapUsed,
                         long nonHeapCommitted) {
        public Memory(long heapUsed, long heapCommitted, long heapMax, long nonHeapUsed) {
            this(heapUsed, heapCommitted, heapMax, nonHeapUsed, -1);
        }
    }

    public record Gc(String name, long count, long totalTimeMs) {}

    public record Sys(String os, String jvm, long totalRamBytes, long uptimeMs) {}

    public record Disk(long usedBytes, long totalBytes) {}

    public record Threads(int total, Map<String, Integer> byState, List<String> deadlocked) {
        public Threads {
            byState = byState == null ? Map.of() : Map.copyOf(byState);
            deadlocked = deadlocked == null ? List.of() : List.copyOf(deadlocked);
        }
    }

    public record World(String name, int players, int entities, int chunks, double tps, double mspt,
                        List<String> playerNames, double avgPingMs,
                        double msptP50, double msptP95, double msptMax,
                        long chunksGeneratedTotal, long chunksLoadedTotal) {
        public World {
            playerNames = playerNames == null ? List.of() : List.copyOf(playerNames);
        }

        public World(String name, int players, int entities, int chunks, double tps, double mspt) {
            this(name, players, entities, chunks, tps, mspt, List.of(), -1, -1, -1, -1, -1, -1);
        }
    }

    public record HotThread(String thread, long samples, String topMethod, List<MethodSample> methods,
                            Map<String, JfrTimeline.ModShare> mods) {
        public HotThread {
            methods = methods == null ? List.of() : List.copyOf(methods);
            mods = mods == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(mods));
        }

        public HotThread(String thread, long samples, String topMethod) {
            this(thread, samples, topMethod, List.of(), Map.of());
        }
    }

    public record MethodSample(String method, long samples) {}
}
