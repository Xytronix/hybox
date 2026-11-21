package io.github.xytronix.hybox.core.health;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record HealthSnapshot(
    int targetTps,
    int maxViewRadius,
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
    public static final int DEFAULT_TARGET_TPS = 30;

    public HealthSnapshot {
        gc = gc == null ? List.of() : List.copyOf(gc);
        worlds = worlds == null ? List.of() : List.copyOf(worlds);
        hotThreads = hotThreads == null ? List.of() : List.copyOf(hotThreads);
    }

    public static double targetTps(HealthSnapshot snapshot, String world) {
        if (snapshot == null) {
            return DEFAULT_TARGET_TPS;
        }
        if (world != null) {
            for (World candidate : snapshot.worlds()) {
                if (candidate.name().equalsIgnoreCase(world) && candidate.targetTps() > 0) {
                    return candidate.targetTps();
                }
            }
        }
        return snapshot.targetTps() > 0 ? snapshot.targetTps() : DEFAULT_TARGET_TPS;
    }

    public boolean mixedTargets() {
        int seen = 0;
        for (World world : worlds) {
            if (world.targetTps() > 0) {
                if (seen != 0 && seen != world.targetTps()) {
                    return true;
                }
                seen = world.targetTps();
            }
        }
        return false;
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

    public record Sys(String os, String jvm, long totalRamBytes, long uptimeMs, double loadAverage) {}

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
                        long chunksGeneratedTotal, long chunksLoadedTotal, int targetTps, Radius radius) {
        public World {
            playerNames = playerNames == null ? List.of() : List.copyOf(playerNames);
        }

        public World(String name, int players, int entities, int chunks, double tps, double mspt) {
            this(name, players, entities, chunks, tps, mspt, List.of(), -1, -1, -1, -1, -1, -1, 0, null);
        }
    }

    public record Radius(int players, int viewMin, int viewMedian, int viewMax, int hotMin, int hotMedian,
                         int hotMax, long sections, int sectionsMax, int hotLowered, int hotLoweredTo,
                         List<PlayerLoad> top) {
        public static final int DEFAULT_HOT_RADIUS = 8;
        public static final int TOP_PLAYERS = 5;

        public Radius {
            top = top == null ? List.of() : List.copyOf(top);
        }

        public static Radius of(List<PlayerLoad> loads, List<Integer> hotSettings) {
            if (loads == null || loads.isEmpty()) {
                return null;
            }
            int[] views = loads.stream().mapToInt(PlayerLoad::view).filter(v -> v >= 0).sorted().toArray();
            int[] hots = loads.stream().mapToInt(PlayerLoad::hot).filter(v -> v >= 0).sorted().toArray();
            long sections = 0;
            int sectionsMax = -1;
            boolean anySections = false;
            for (PlayerLoad load : loads) {
                if (load.sections() >= 0) {
                    sections += load.sections();
                    sectionsMax = Math.max(sectionsMax, load.sections());
                    anySections = true;
                }
            }
            int lowered = 0;
            int loweredTo = -1;
            for (Integer setting : hotSettings == null ? List.<Integer>of() : hotSettings) {
                if (setting != null && setting >= 0 && setting < DEFAULT_HOT_RADIUS) {
                    lowered++;
                    loweredTo = loweredTo < 0 ? setting : Math.min(loweredTo, setting);
                }
            }
            List<PlayerLoad> top = loads.stream()
                .filter(load -> load.sections() >= 0)
                .sorted((a, b) -> Integer.compare(b.sections(), a.sections()))
                .limit(TOP_PLAYERS)
                .toList();
            return new Radius(loads.size(),
                views.length == 0 ? -1 : views[0], median(views), views.length == 0 ? -1 : views[views.length - 1],
                hots.length == 0 ? -1 : hots[0], median(hots), hots.length == 0 ? -1 : hots[hots.length - 1],
                anySections ? sections : -1, sectionsMax, lowered, loweredTo, top);
        }

        public static int median(int[] sorted) {
            return sorted.length == 0 ? -1 : sorted[(sorted.length - 1) / 2];
        }
    }

    public record PlayerLoad(String name, int view, int hot, int sections) {}

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
