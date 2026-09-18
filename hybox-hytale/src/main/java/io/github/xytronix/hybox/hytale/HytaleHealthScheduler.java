package io.github.xytronix.hybox.hytale;

import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import io.github.xytronix.hybox.core.jfr.JfrController;
import io.github.xytronix.hybox.core.trigger.TriggerEvent;
import io.github.xytronix.hybox.core.trigger.heartbeat.HeartbeatStallDetector;
import io.github.xytronix.hybox.core.trigger.tick.TickDegradedDetector;

final class HytaleHealthScheduler {
    private static final int TICK_AVG_PERIOD_INDEX = 1;

    private final HyboxRuntime runtime;
    private final System.Logger logger;
    private final ScheduledExecutorService scheduler;
    private final HytaleHeartbeatPump heartbeatPump;
    private final HytaleTelemetrySampler telemetry;
    private final HytaleNetSampler netSampler;
    private final JfrController jfr;
    private final Path rollingFile;
    private final Supplier<HyboxRuntime.Engine> engine;

    private final AtomicBoolean stallCheckRunning = new AtomicBoolean(false);
    private final AtomicBoolean snapshotRunning = new AtomicBoolean(false);
    private final AtomicBoolean tickSampleRunning = new AtomicBoolean(false);
    private final AtomicBoolean healthCheckRunning = new AtomicBoolean(false);
    private CompletableFuture<Void> readyTask;
    private CompletableFuture<Void> snapshotStartTask;
    private boolean closed;
    private boolean ownsRollingSnapshot;

    HytaleHealthScheduler(HyboxRuntime runtime, System.Logger logger, ScheduledExecutorService scheduler,
                          HytaleHeartbeatPump heartbeatPump, HytaleTelemetrySampler telemetry,
                          HytaleNetSampler netSampler, JfrController jfr, Path rollingFile,
                          Supplier<HyboxRuntime.Engine> engine) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.heartbeatPump = Objects.requireNonNull(heartbeatPump, "heartbeatPump");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.netSampler = Objects.requireNonNull(netSampler, "netSampler");
        this.jfr = Objects.requireNonNull(jfr, "jfr");
        this.rollingFile = Objects.requireNonNull(rollingFile, "rollingFile");
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    synchronized void startSnapshotWriter(long intervalMillis, CompletableFuture<Void> recovery) {
        if (intervalMillis <= 0 || closed) {
            return;
        }
        long millis = Math.max(1000L, intervalMillis);
        snapshotStartTask = recovery.thenRun(() -> {
            synchronized (this) {
                if (!closed) {
                    if (Files.exists(rollingFile, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                        logger.log(System.Logger.Level.WARNING,
                            "Rolling snapshot writer disabled to preserve an unrecovered recording at " + rollingFile);
                        return;
                    }
                    scheduler.scheduleAtFixedRate(this::scheduleSnapshot, millis, millis, TimeUnit.MILLISECONDS);
                    ownsRollingSnapshot = true;
                }
            }
        });
    }
    synchronized boolean ownsRollingSnapshot() {
        return ownsRollingSnapshot;
    }


    private void scheduleSnapshot() {
        if (!snapshotRunning.compareAndSet(false, true)) {
            return;
        }
        runtime.submitWorker(() -> {
            try {
                writeRollingSnapshot();
            } finally {
                snapshotRunning.set(false);
            }
        }, () -> snapshotRunning.set(false));
    }

    private void writeRollingSnapshot() {
        try {
            Files.createDirectories(rollingFile.getParent());
            Path tmp = rollingFile.resolveSibling("rolling.jfr.tmp");
            jfr.dump(tmp);
            Files.move(tmp, rollingFile, StandardCopyOption.REPLACE_EXISTING);
            runtime.snapshotSucceeded();
        } catch (Exception e) {
            runtime.snapshotFailed(e);
            logger.log(System.Logger.Level.WARNING, "Failed to write rolling JFR snapshot.", e);
        }
    }

    synchronized void start() {
        Universe universe;
        try {
            universe = Universe.get();
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "Universe.get() failed; heartbeats disabled until restart.", e);
            return;
        }

        long sampleMs = engine.get().config().jfrSampleInterval().toMillis();
        readyTask = universe.getUniverseReady().thenRun(() -> startTasks(sampleMs));
        if (readyTask.isCompletedExceptionally()) {
            readyTask.join();
        }
    }

    private synchronized void startTasks(long sampleMs) {
        if (closed) {
            return;
        }
        scheduler.scheduleAtFixedRate(heartbeatPump::tick, 0L, 50L, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(this::scheduleStallCheck, 250L, 250L, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(this::scheduleTickSample, sampleMs, sampleMs, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(this::scheduleHealthCheck, 5_000L, 5_000L, TimeUnit.MILLISECONDS);
    }

    synchronized void close() {
        closed = true;
        if (readyTask != null) {
            readyTask.cancel(false);
        }
        if (snapshotStartTask != null) {
            snapshotStartTask.cancel(false);
        }
    }

    private void scheduleHealthCheck() {
        if (!healthCheckRunning.compareAndSet(false, true)) {
            return;
        }
        runtime.submitWorker(() -> {
            try {
                HyboxRuntime.Engine current = engine.get();
                checkDetector(current.deadlockDetector() == null
                    ? null : current.deadlockDetector()::check, "Deadlock");
                checkDetector(current.heapPressureDetector() == null
                    ? null : current.heapPressureDetector()::check, "Heap pressure");
                checkDetector(current.gcPressureDetector() == null
                    ? null : current.gcPressureDetector()::check, "GC pressure");
                checkDetector(current.cpuSaturationDetector() == null
                    ? null : current.cpuSaturationDetector()::check, "CPU saturation");
                checkDetector(current.netSaturationDetector() == null
                    ? null : current.netSaturationDetector()::check, "Net saturation");
            } finally {
                healthCheckRunning.set(false);
            }
        }, () -> healthCheckRunning.set(false));
    }

    private void checkDetector(java.util.function.Supplier<java.util.List<TriggerEvent>> check, String name) {
        if (check == null) {
            return;
        }
        try {
            for (TriggerEvent event : check.get()) {
                runtime.scheduleCapture(event);
            }
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, name + " detector failed.", e);
        }
    }

    void syncNetSampler() {
        netSampler.sync(engine.get().netSaturationDetector() != null);
    }

    private void scheduleTickSample() {
        if (!tickSampleRunning.compareAndSet(false, true)) {
            return;
        }
        runtime.submitWorker(() -> {
            try {
                telemetry.sampleWorldTicks();
            } catch (Exception e) {
                logger.log(System.Logger.Level.WARNING, "World tick sampling failed.", e);
            } finally {
                tickSampleRunning.set(false);
            }
        }, () -> tickSampleRunning.set(false));
    }

    private void scheduleStallCheck() {
        if (!stallCheckRunning.compareAndSet(false, true)) {
            return;
        }
        runtime.submitWorker(() -> {
            try {
                HyboxRuntime.Engine current = engine.get();
                HeartbeatStallDetector stall = current.stallDetector();
                if (stall != null) {
                    for (TriggerEvent event : stall.check()) {
                        runtime.scheduleCapture(event);
                    }
                }
                TickDegradedDetector tick = current.tickDegradedDetector();
                if (tick != null) {
                    for (TriggerEvent event : tick.check()) {
                        runtime.scheduleCapture(event);
                    }
                }
            } catch (Exception e) {
                logger.log(System.Logger.Level.WARNING, "Stall check failed.", e);
            } finally {
                stallCheckRunning.set(false);
            }
        }, () -> stallCheckRunning.set(false));
    }

    static Map<String, Double> tickAverageMillis() {
        Map<String, Double> out = new HashMap<>();
        try {
            for (Map.Entry<String, World> entry : Universe.get().getWorlds().entrySet()) {
                String scope = entry.getKey();
                World world = entry.getValue();
                if (scope == null || scope.isBlank() || world == null) {
                    continue;
                }
                try {
                    double avgDeltaNanos = world.getBufferedTickLengthMetricSet().getAverage(TICK_AVG_PERIOD_INDEX);
                    if (avgDeltaNanos > 0) {
                        out.put(scope, avgDeltaNanos / 1_000_000.0);
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }
}
