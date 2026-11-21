package io.github.xytronix.hybox.hytale;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import io.github.xytronix.hybox.core.config.HyboxConfig;
import io.github.xytronix.hybox.core.health.HealthSnapshot;
import io.github.xytronix.hybox.core.health.HostCpuStats;
import io.github.xytronix.hybox.core.health.WorldStatsProvider;
import io.github.xytronix.hybox.core.jfr.HyboxDiskEvent;
import io.github.xytronix.hybox.core.jfr.HyboxHostCpuEvent;
import io.github.xytronix.hybox.core.jfr.HyboxSystemTickEvent;
import io.github.xytronix.hybox.core.jfr.HyboxWorldTickEvent;
import io.github.xytronix.hybox.core.metrics.HealthGauges;
import io.github.xytronix.hybox.core.metrics.MetricsLog;
import io.github.xytronix.hybox.core.trigger.TriggerEvent;
import io.github.xytronix.hybox.core.trigger.heartbeat.HeartbeatRegistry;
import io.github.xytronix.hybox.core.trigger.player.PlayerDropDetector;

final class HytaleTelemetrySampler {
    private static final int SYSTEM_TICK_PER_WORLD_LIMIT = 4;

    private final Clock clock;
    private final System.Logger logger;
    private final HytaleWorldStatsProvider worldStats;
    private final MetricsLog metricsLog;
    private final HealthGauges healthGauges;
    private final HeartbeatRegistry heartbeatRegistry;
    private final Supplier<HyboxConfig> config;
    private final Supplier<PlayerDropDetector> playerDrop;
    private final Consumer<TriggerEvent> capture;
    private long lastGcMillis = -1;
    private long lastGcWallMillis;
    private long lastAllocBytes = -1;
    private Instant lastAllocAt;
    private volatile Status status;
    private volatile List<HealthGauges.World> worlds = List.of();

    record Status(double tps, double tickMs, double cpuPct, long heapUsed, long heapMax, int targetTps) {}

    HytaleTelemetrySampler(Clock clock, System.Logger logger, HytaleWorldStatsProvider worldStats,
                           MetricsLog metricsLog, HealthGauges healthGauges, HeartbeatRegistry heartbeatRegistry,
                           Supplier<HyboxConfig> config,
                           Supplier<PlayerDropDetector> playerDrop, Consumer<TriggerEvent> capture) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.worldStats = Objects.requireNonNull(worldStats, "worldStats");
        this.metricsLog = Objects.requireNonNull(metricsLog, "metricsLog");
        this.healthGauges = Objects.requireNonNull(healthGauges, "healthGauges");
        this.heartbeatRegistry = Objects.requireNonNull(heartbeatRegistry, "heartbeatRegistry");
        this.config = Objects.requireNonNull(config, "config");
        this.playerDrop = Objects.requireNonNull(playerDrop, "playerDrop");
        this.capture = Objects.requireNonNull(capture, "capture");
    }

    void sampleWorldTicks() {
        long startNanos = System.nanoTime();
        WorldStatsProvider.Worlds stats = worldStats.worlds();
        Map<String, Double> worstTicks = worldStats.worstTicksSinceLastCall();
        int onlineTotal = HealthSnapshot.onlineTotal(stats.onlinePlayers(), stats.worlds());
        HealthSnapshot.World worst = null;
        List<HealthGauges.World> worldGauges = new ArrayList<>();
        for (HealthSnapshot.World world : stats.worlds()) {
            worldGauges.add(new HealthGauges.World(world.name(), world.tps(), world.mspt(),
                world.players(), world.entities(), world.avgPingMs(), world.chunks(),
                world.chunksGeneratedTotal(), world.chunksLoadedTotal()));
            if (world.tps() < 0) {
                continue;
            }
            if (worst == null || worse(world, worst, stats.targetTps())) {
                worst = world;
            }
            HyboxWorldTickEvent event = new HyboxWorldTickEvent();
            event.world = world.name();
            event.tps = world.tps();
            event.mspt = world.mspt();
            event.players = world.players();
            event.onlinePlayers = stats.onlinePlayers();
            event.entities = world.entities();
            event.chunks = world.chunks();
            event.chunksGeneratedTotal = world.chunksGeneratedTotal();
            event.chunksLoadedTotal = world.chunksLoadedTotal();
            event.avgPingMs = world.avgPingMs();
            event.msptMax = worstTicks.getOrDefault(world.name(), -1.0);
            event.commit();
        }
        java.lang.management.MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        double cpuLoad = JvmProbes.processCpuLoad();
        double worstTps = worst == null ? -1 : worst.tps();
        double worstMspt = worst == null ? -1 : worst.mspt();
        int worstTarget = worst == null ? stats.targetTps() : target(worst, stats.targetTps());
        status = new Status(worstTps, worstMspt, cpuLoad < 0 ? -1 : cpuLoad * 100, heap.getUsed(), heap.getMax(), worstTarget);
        worlds = List.copyOf(worldGauges);
        HealthSnapshot.Radius all = stats.allPlayers();
        MetricsLog.Load load = new MetricsLog.Load(worstTarget > 0 ? worstTarget : -1, stats.maxViewRadius(),
            all == null ? -1 : all.viewMedian(), all == null ? -1 : all.hotMedian(),
            all != null ? all.sections() : onlineTotal == 0 ? 0 : -1);
        recordHealthMetrics(worstMspt, worstTps, onlineTotal, worldGauges,
            HyboxApi.sampleSupplierGauges(), cpuLoad, heap.getUsed(), load);
        sampleDisk();
        sampleHostCpu();
        PlayerDropDetector drop = playerDrop.get();
        if (drop != null && onlineTotal >= 0) {
            for (TriggerEvent event : drop.sample(onlineTotal)) {
                capture.accept(event);
            }
        }
        try {
            for (HytaleTickSystems.SystemSample sample : HytaleTickSystems.topSystems(SYSTEM_TICK_PER_WORLD_LIMIT)) {
                HyboxSystemTickEvent event = new HyboxSystemTickEvent();
                event.world = sample.world();
                event.system = sample.system();
                event.avgMs = sample.avgMs();
                event.commit();
            }
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "System tick sampling failed.", e);
        }
        healthGauges.setCollectorTimeMs((System.nanoTime() - startNanos) / 1_000_000.0);
    }

    Status status() {
        return status;
    }

    static boolean worse(HealthSnapshot.World candidate, HealthSnapshot.World current, int fallbackTarget) {
        int a = target(candidate, fallbackTarget);
        int b = target(current, fallbackTarget);
        double shareA = candidate.tps() / a;
        double shareB = current.tps() / b;
        if (Math.abs(shareA - shareB) > 1e-9) {
            return shareA < shareB;
        }
        return Math.max(0, candidate.mspt()) * a > Math.max(0, current.mspt()) * b;
    }

    static int target(HealthSnapshot.World world, int fallbackTarget) {
        return world.targetTps() > 0 ? world.targetTps()
            : fallbackTarget > 0 ? fallbackTarget : HealthSnapshot.DEFAULT_TARGET_TPS;
    }

    List<HealthGauges.World> worlds() {
        return worlds;
    }

    private void recordHealthMetrics(double tickAvgMs, double tps, int players, List<HealthGauges.World> worlds,
                                     Map<String, Double> supplierGauges, double cpuLoad, long heapUsed,
                                     MetricsLog.Load load) {
        HyboxConfig cfg = config.get();
        boolean csv = cfg.metricsEnabled();
        boolean live = cfg.prometheusEnabled();
        if (!csv && !live) {
            return;
        }
        try {
            Instant now = clock.instant();
            long rss = readRssBytes();
            double gcPct = gcFractionPercent();
            double cpu = cfg.metricsCpu() ? cpuLoad : -1;
            double cpuPct = cpu < 0 ? -1 : cpu * 100;
            double alloc = cfg.metricsAllocation() ? allocRateMbPerSec(now) : Double.NaN;
            long queued = sampleQueuedPackets();
            if (live) {
                healthGauges.set(new HealthGauges.Sample(tps, tickAvgMs, players, heapUsed, rss,
                    cpuPct, worlds));
                long[] cgroupCpu = HostCpuStats.cgroupThrottle();
                if (cgroupCpu != null) {
                    healthGauges.setCgroupCpu(cgroupCpu[2], cgroupCpu[1], cgroupCpu[0]);
                }
                healthGauges.setGcPauseMsec(JvmProbes.stwGcPauseMillis());
                if (cfg.metricsAllocation()) {
                    healthGauges.setAllocatedBytes(JvmProbes.allocatedBytes());
                }
                healthGauges.setDeadlockedThreads(JvmProbes.deadlockedThreadCount());
                healthGauges.setQueuedPackets(queued);
                healthGauges.setHeartbeats(heartbeatEpochMillis());
                Map<String, Double> gauges = new HashMap<>(HyboxApi.pluginGauges());
                gauges.putAll(supplierGauges);
                healthGauges.setPluginMetrics(gauges, HyboxApi.pluginCounters());
            }
            if (csv) {
                String row = MetricsLog.formatRow(now, tickAvgMs, tps, players,
                    heapUsed, rss, gcPct, cpuPct, alloc, queued, load);
                metricsLog.append(now, row, cfg.metricsRetentionDays());
            }
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "Failed to record health metrics.", e);
        }
    }

    private double allocRateMbPerSec(Instant now) {
        long bytes = JvmProbes.allocatedBytes();
        if (bytes < 0) {
            return Double.NaN;
        }
        double rate = Double.NaN;
        if (lastAllocBytes >= 0 && lastAllocAt != null) {
            double seconds = (now.toEpochMilli() - lastAllocAt.toEpochMilli()) / 1000.0;
            if (seconds > 0 && bytes >= lastAllocBytes) {
                rate = (bytes - lastAllocBytes) / seconds / (1024 * 1024);
            }
        }
        lastAllocBytes = bytes;
        lastAllocAt = now;
        return rate;
    }

    private double gcFractionPercent() {
        long gcNow = JvmProbes.stwGcPauseMillis();
        if (gcNow < 0) {
            gcNow = 0;
        }
        long wallNow = clock.millis();
        double percent = -1;
        if (lastGcMillis >= 0) {
            long wallDelta = wallNow - lastGcWallMillis;
            if (wallDelta > 0) {
                percent = (gcNow - lastGcMillis) * 100.0 / wallDelta;
            }
        }
        lastGcMillis = gcNow;
        lastGcWallMillis = wallNow;
        return percent;
    }

    private long readRssBytes() {
        try {
            Path statm = Path.of("/proc/self/statm");
            if (!Files.isReadable(statm)) {
                return -1;
            }
            String[] fields = Files.readString(statm).trim().split("\\s+");
            if (fields.length < 2) {
                return -1;
            }
            return Long.parseLong(fields[1]) * 4096L;
        } catch (Exception e) {
            return -1;
        }
    }

    private long sampleQueuedPackets() {
        try {
            long total = 0;
            for (var player : com.hypixel.hytale.server.core.universe.Universe.get().getPlayers()) {
                if (player == null) {
                    continue;
                }
                var handler = player.getPacketHandler();
                if (handler != null) {
                    total += handler.getQueuedPacketsCount();
                }
            }
            return total;
        } catch (Exception e) {
            return -1;
        }
    }

    private Map<String, Long> heartbeatEpochMillis() {
        Map<String, Instant> snapshot = heartbeatRegistry.snapshot();
        if (snapshot.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> out = new HashMap<>(snapshot.size());
        snapshot.forEach((scope, instant) -> {
            if (instant != null) {
                out.put(scope, instant.toEpochMilli());
            }
        });
        return out;
    }

    private void sampleHostCpu() {
        try {
            long[] throttle = HostCpuStats.cgroupThrottle();
            long[] proc = HostCpuStats.procCpu();
            long[] swap = HostCpuStats.swapBytes();
            if (throttle == null && proc == null && swap == null) {
                return;
            }
            HyboxHostCpuEvent event = new HyboxHostCpuEvent();
            event.throttledPeriods = throttle == null ? -1 : throttle[0];
            event.throttledMicros = throttle == null ? -1 : throttle[1];
            event.cpuUsageUsec = throttle == null ? -1 : throttle[2];
            event.cpuTotalJiffies = proc == null ? -1 : proc[0];
            event.cpuStealJiffies = proc == null ? -1 : proc[1];
            event.cpuIowaitJiffies = proc == null ? -1 : proc[2];
            event.swapInBytes = swap == null ? -1 : swap[0];
            event.swapOutBytes = swap == null ? -1 : swap[1];
            event.commit();
        } catch (Exception e) {
            logger.log(System.Logger.Level.DEBUG, "Host CPU sampling failed.", e);
        }
    }

    private void sampleDisk() {
        try {
            java.nio.file.FileStore store = Files.getFileStore(java.nio.file.Path.of("."));
            HyboxDiskEvent event = new HyboxDiskEvent();
            event.freeBytes = store.getUsableSpace();
            event.totalBytes = store.getTotalSpace();
            long[] io = processIoBytes();
            event.readBytes = io[0];
            event.writeBytes = io[1];
            event.commit();
        } catch (Exception e) {
            logger.log(System.Logger.Level.DEBUG, "Disk sampling failed.", e);
        }
    }

    private static long[] processIoBytes() {
        long[] io = {-1L, -1L};
        try {
            java.nio.file.Path procIo = java.nio.file.Path.of("/proc/self/io");
            if (!Files.isReadable(procIo)) {
                return io;
            }
            for (String line : Files.readAllLines(procIo)) {
                if (line.startsWith("read_bytes:")) {
                    io[0] = Long.parseLong(line.substring("read_bytes:".length()).trim());
                } else if (line.startsWith("write_bytes:")) {
                    io[1] = Long.parseLong(line.substring("write_bytes:".length()).trim());
                }
            }
        } catch (Exception ignored) {
        }
        return io;
    }
}
