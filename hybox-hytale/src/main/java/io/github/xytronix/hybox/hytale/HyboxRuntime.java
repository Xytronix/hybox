package io.github.xytronix.hybox.hytale;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import io.github.xytronix.hybox.core.bundle.BundleBuilder;
import io.github.xytronix.hybox.core.bundle.BundleExtrasRegistry;
import io.github.xytronix.hybox.core.capture.CapturePipeline;
import io.github.xytronix.hybox.core.capture.CaptureResult;
import io.github.xytronix.hybox.core.capture.IncidentNotifier;
import io.github.xytronix.hybox.core.capture.PostIncidentWaiter;
import io.github.xytronix.hybox.core.capture.RecordingDumper;
import io.github.xytronix.hybox.core.config.HyboxConfig;
import io.github.xytronix.hybox.core.env.TextRedactor;
import io.github.xytronix.hybox.core.health.ThreadDumpProgress;
import io.github.xytronix.hybox.core.incident.IncidentMetadata;
import io.github.xytronix.hybox.core.incident.IncidentReport;
import io.github.xytronix.hybox.core.jfr.JfrController;
import io.github.xytronix.hybox.core.metrics.HealthGauges;
import io.github.xytronix.hybox.core.metrics.MetricsLog;
import io.github.xytronix.hybox.core.metrics.PrometheusExporter;
import io.github.xytronix.hybox.core.report.TrendReport;
import io.github.xytronix.hybox.core.notify.discord.DiscordWebhookNotifier;
import io.github.xytronix.hybox.core.notify.discord.HttpClientWebhookTransport;
import io.github.xytronix.hybox.core.retention.FileDeleter;
import io.github.xytronix.hybox.core.retention.RetentionManager;
import io.github.xytronix.hybox.core.trigger.DetectorPolicy;
import io.github.xytronix.hybox.core.trigger.ModulePolicy;
import io.github.xytronix.hybox.core.trigger.TriggerEvent;
import io.github.xytronix.hybox.core.trigger.TriggerKind;
import io.github.xytronix.hybox.core.trigger.TriggerEngine;
import io.github.xytronix.hybox.core.trigger.heartbeat.HeartbeatRegistry;
import io.github.xytronix.hybox.core.trigger.heartbeat.HeartbeatStallDetector;
import io.github.xytronix.hybox.core.trigger.jvm.CpuSaturationDetector;
import io.github.xytronix.hybox.core.trigger.jvm.DeadlockDetector;
import io.github.xytronix.hybox.core.trigger.jvm.GcPressureDetector;
import io.github.xytronix.hybox.core.trigger.jvm.HeapPressureDetector;
import io.github.xytronix.hybox.core.trigger.net.NetSaturationDetector;
import io.github.xytronix.hybox.core.trigger.player.PlayerDropDetector;
import io.github.xytronix.hybox.core.trigger.tick.TickDegradedDetector;

final class HyboxRuntime implements AutoCloseable {
    private static final DateTimeFormatter INCIDENT_TS =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss.SSSZ").withLocale(Locale.ROOT);
    private final HyboxPlugin plugin;
    private final Clock clock;
    private final System.Logger logger;
    private final Path dataDir;
    private final Path configPath;
    private final Path incidentDir;
    private final Path tempDir;
    private final Path rollingFile;

    private final ScheduledExecutorService scheduler;
    private final ExecutorService worker;

    private final CallbackExecutor callbacks = new CallbackExecutor();
    private final JfrController jfr;
    private final HeartbeatRegistry heartbeatRegistry;
    private final HytaleHeartbeatPump heartbeatPump;
    private final BundleExtrasRegistry extrasRegistry;
    private final HytaleConnectionTracker connectionTracker;
    private final JfrSessionRecovery sessionRecovery;
    private final ProfileSessionController profileSessions;
    private final HytaleNetSampler netSampler;
    private final HealthGauges healthGauges = new HealthGauges();
    private final HytaleTelemetrySampler telemetry;
    private volatile PrometheusExporter prometheusExporter;

    record Engine(
        HyboxConfig config,
        HeartbeatStallDetector stallDetector,
        TickDegradedDetector tickDegradedDetector,
        CapturePipeline capturePipeline,
        DeadlockDetector deadlockDetector,
        HeapPressureDetector heapPressureDetector,
        GcPressureDetector gcPressureDetector,
        CpuSaturationDetector cpuSaturationDetector,
        NetSaturationDetector netSaturationDetector,
        PlayerDropDetector playerDropDetector
    ) {}

    private volatile Engine engine;
    private volatile TextRedactor textRedactor;
    private final PlayerNameMasker nameMasker = new PlayerNameMasker();
    private final HytaleLogErrorWatcher logErrorWatcher;
    private final HytaleWorldFailureListener worldFailureListener;
    private final HytaleHealthScheduler healthScheduler;

    private final HytaleWorldStatsProvider worldStats;

    private final AtomicReference<Instant> lastIncidentAt = new AtomicReference<>();
    private final AtomicReference<String> lastIncidentId = new AtomicReference<>();
    private final AtomicBoolean capturePending = new AtomicBoolean(false);
    private final AtomicReference<List<String>> lastSpacedDumps = new AtomicReference<>(List.of());
    private final AtomicBoolean captureAdmitted = new AtomicBoolean();
    private final AtomicLong busyCaptures = new AtomicLong();
    private final AtomicLong droppedCaptures = new AtomicLong();
    private final AtomicLong rejectedWork = new AtomicLong();
    private volatile String captureProgress = "idle";
    private volatile CaptureResult lastCapture;
    private volatile String lastCaptureError;
    private volatile Instant lastSnapshotAt;
    private volatile String lastSnapshotError;
    private volatile long bundleCount;
    private volatile boolean closed;
    private boolean started;
    private HyboxApi.Registration diagnosticsRegistration;

    static HyboxRuntime start(HyboxPlugin plugin) throws Exception {
        Objects.requireNonNull(plugin, "plugin");
        System.Logger logger = System.getLogger(HyboxRuntime.class.getName());
        Path dataDir = plugin.getDataDirectory();
        HyboxConfig config = HytaleHyboxConfig.loadOrCreate(dataDir, logger);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            new NamedThreadFactory("hybox-scheduler"));
        ExecutorService worker = null;
        JfrController jfr = null;
        HyboxRuntime runtime = null;
        try {
            worker = newWorker();
            jfr = new JfrController(
                config.jfrMaxAge(), config.jfrMaxSizeBytes(), config.jfrRecordingName(),
                config.jfrDisabledEvents(), config.jfrConfiguration(), config.jfrOldObjectSampling());
            jfr.start();
            runtime = new HyboxRuntime(plugin, Clock.systemUTC(), logger, dataDir, scheduler, worker, jfr);
            runtime.initialize(config);
            runtime.started = true;
            return runtime;
        } catch (Exception | Error failure) {
            if (runtime != null) {
                rollback(failure, runtime);
            } else {
                if (worker != null) {
                    rollback(failure, worker::shutdownNow);
                }
                rollback(failure, scheduler::shutdownNow);
                if (jfr != null) {
                    rollback(failure, jfr);
                }
            }
            throw failure;
        }
    }


    static ExecutorService newWorker() {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(16), new NamedThreadFactory("hybox-worker"));
    }
    private static void rollback(Throwable failure, AutoCloseable resource) {
        try {
            resource.close();
        } catch (Throwable cleanupFailure) {
            if (cleanupFailure != failure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
    }

    private void initialize(HyboxConfig config) {
        engine = buildEngine(config);
        long snapshotMillis = config.jfrSnapshotInterval().toMillis();
        extrasRegistry.register(new HytaleBundleExtrasProvider(
            heartbeatRegistry,
            () -> engine.config().capturePolicy().logTailLines(),
            () -> engine.config().capturePolicy().includeServerLog(),
            dataDir.resolve("metrics"),
            nameMasker,
            () -> lastSpacedDumps.getAndSet(List.of())));
        healthScheduler.syncNetSampler();
        healthScheduler.start();
        registerCommands();
        worldFailureListener.register();
        connectionTracker.register();
        logErrorWatcher.register();
        diagnosticsRegistration = HyboxApi.registerDiagnostics("hybox", "Environment", HytaleEnvironment::snapshot);
        refreshBundleStats();
        startMetricsExporter();
        healthScheduler.startSnapshotWriter(snapshotMillis, recoverOrphanedRecordings());
        logStartup();
    }

    private Engine buildEngine(HyboxConfig config) {
        this.textRedactor = new TextRedactor(config.capturePolicy().redactPatterns());
        DetectorPolicy detectors = config.triggerPolicy().detectors();
        ModulePolicy modules = detectors.modules();

        HeartbeatStallDetector stallDetector = modules.heartbeatStall()
            ? new HeartbeatStallDetector(
                clock,
                heartbeatRegistry,
                config.triggerPolicy().stallDegradedMs())
            : null;
        TickDegradedDetector tickDegradedDetector = modules.tickDegraded()
            ? new TickDegradedDetector(
                clock,
                HytaleHealthScheduler::tickAverageMillis,
                config.triggerPolicy().tickAvgDegradedMs())
            : null;
        TriggerEngine triggerEngine = new TriggerEngine(clock, config.triggerPolicy());

        RecordingDumper dumper = (target) -> {
            try {
                captureProgress = "dumping recording";
                jfr.dump(target);
                captureProgress = "building bundle";
                return target;
            } finally {
                capturePending.set(false);
            }
        };
        PostIncidentWaiter postIncidentWaiter = (event) -> {
            captureProgress = "waiting for post-incident evidence";
            capturePending.set(true);
            lastSpacedDumps.set(HytaleHeartbeatPump.awaitRecovery(event, heartbeatRegistry, clock,
                config.postIncidentMaxWait(), config.threadDumpCount(), config.threadDumpInterval(), logger));
        };
        IncidentNotifier configuredNotifier = buildNotifier(clock, config, logger,
            task -> submitWorker(task, () -> logger.log(System.Logger.Level.WARNING,
                "Discord notification skipped: Hybox worker is busy or stopping.")));
        IncidentNotifier notifier = (report, zip) -> {
            recordIncidentGauge(report);
            configuredNotifier.onIncident(report, zip);
        };

        CapturePipeline capturePipeline = new CapturePipeline(
            clock,
            triggerEngine,
            dumper,
            new BundleBuilder(clock, logger, textRedactor, nameMasker::maskText),
            new RetentionManager(clock, logger, FileDeleter.defaultDeleter()),
            notifier,
            extrasRegistry,
            worldStats,
            () -> ThreadDumpProgress.appendTo(HytaleAssetPacks.appendTo(HytaleServerSettings.appendTo(HytaleModConfigs.appendTo(HytaleMixins.appendTo(HytalePlugins.appendTo(
                HytaleEntities.appendTo(HytaleTickSystems.appendTo(HytaleHeartbeats.appendTo(HytaleServerLog.appendTo(
                    HyboxApi.collectDiagnostics(), config.capturePolicy().includeServerLog(),
                    config.capturePolicy().logTailLines(), nameMasker,
                    config.capturePolicy().sanitizeLog()), heartbeatRegistry, clock))))),
                config.capturePolicy().includeModConfigs(), HyboxApi.registeredConfigPaths()), config.capturePolicy().includeServerConfig())), lastSpacedDumps.get()),
            postIncidentWaiter,
            incidentDir,
            tempDir,
            config.capturePolicy(),
            logger
        );

        return new Engine(
            config,
            stallDetector,
            tickDegradedDetector,
            capturePipeline,
            modules.deadlock()
                ? new DeadlockDetector(clock, JvmProbes::deadlockedThreadIds)
                : null,
            modules.heapPressure() && detectors.heapPressurePct() > 0
                ? new HeapPressureDetector(clock, JvmProbes::tenuredAfterGcFraction,
                    detectors.heapPressurePct() / 100.0, detectors.heapPressureSustain())
                : null,
            modules.gcPressure() && detectors.gcPressurePct() > 0 && !detectors.gcPressureWindow().isZero()
                ? new GcPressureDetector(clock, JvmProbes::stwGcPauseMillis,
                    detectors.gcPressurePct() / 100.0, detectors.gcPressureWindow())
                : null,
            modules.cpuSaturation() && detectors.cpuSaturationPct() > 0
                ? new CpuSaturationDetector(clock, JvmProbes::processCpuLoad,
                    detectors.cpuSaturationPct() / 100.0, detectors.cpuSaturationSustain())
                : null,
            modules.netSaturation() && (detectors.netInMbps() > 0 || detectors.netOutMbps() > 0)
                ? new NetSaturationDetector(clock, netSampler::latestRates,
                    detectors.netInMbps(), detectors.netOutMbps(), detectors.netSustain())
                : null,
            modules.playerDrop() && detectors.playerDropPct() > 0 && !detectors.playerDropWindow().isZero()
                ? new PlayerDropDetector(clock, detectors.playerDropPct() / 100.0,
                    detectors.playerDropWindow(), detectors.playerDropMinPlayers())
                : null
        );
    }

    private static IncidentNotifier buildNotifier(
        Clock clock,
        HyboxConfig config,
        System.Logger logger,
        Executor worker
    ) {
        if (config.discordWebhook().webhookUrl().isBlank()) {
            return IncidentNotifier.noop();
        }
        return new DiscordWebhookNotifier(
            clock,
            logger,
            config.discordWebhook(),
            new HttpClientWebhookTransport(config.discordWebhook().requestTimeout()),
            worker
        );
    }

    private void recordIncidentGauge(IncidentReport report) {
        try {
            IncidentMetadata meta = report.meta();
            healthGauges.incrementIncident(meta.trigger(),
                meta.severity().name().toLowerCase(Locale.ROOT), meta.createdAt().getEpochSecond());
        } catch (Exception e) {
            logger.log(System.Logger.Level.DEBUG, "Failed to record incident gauge.", e);
        }
        refreshBundleStats();
    }

    private void refreshBundleStats() {
        try (var entries = Files.list(incidentDir)) {
            long[] acc = {0L, 0L};
            entries.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".zip"))
                .forEach(p -> {
                    try {
                        acc[0]++;
                        acc[1] += Files.size(p);
                    } catch (IOException ignored) {
                    }
                });
            healthGauges.setBundles(acc[0], acc[1]);
            bundleCount = acc[0];
        } catch (Exception e) {
            logger.log(System.Logger.Level.DEBUG, "Failed to refresh bundle stats.", e);
        }
    }

    private void startMetricsExporter() {
        HyboxConfig cfg = engine.config();
        if (!cfg.prometheusEnabled()) {
            return;
        }
        try {
            prometheusExporter = new PrometheusExporter(
                healthGauges, cfg.prometheusBind(), cfg.prometheusPort(), "/metrics");
            logger.log(System.Logger.Level.INFO, "Prometheus metrics on http://"
                + cfg.prometheusBind() + ":" + prometheusExporter.port() + "/metrics");
        } catch (IOException e) {
            logger.log(System.Logger.Level.WARNING, "Failed to start Prometheus metrics endpoint.", e);
        }
    }

    private void stopMetricsExporter() {
        PrometheusExporter exporter = prometheusExporter;
        if (exporter != null) {
            try {
                exporter.close();
            } catch (Exception e) {
                logger.log(System.Logger.Level.WARNING, "Failed to stop Prometheus metrics endpoint.", e);
            }
            prometheusExporter = null;
        }
    }

    private HyboxRuntime(
        HyboxPlugin plugin,
        Clock clock,
        System.Logger logger,
        Path dataDir,
        ScheduledExecutorService scheduler,
        ExecutorService worker,
        JfrController jfr
    ) {
        this.plugin = plugin;
        this.clock = clock;
        this.logger = logger;
        this.dataDir = dataDir;
        this.configPath = HytaleHyboxConfig.path(dataDir);
        this.incidentDir = dataDir.resolve("incidents");
        this.tempDir = dataDir.resolve("temp");
        this.rollingFile = dataDir.resolve("live").resolve("rolling.jfr");
        this.scheduler = scheduler;
        this.worker = worker;
        this.jfr = jfr;
        this.heartbeatRegistry = new HeartbeatRegistry(clock);
        this.heartbeatPump = new HytaleHeartbeatPump(logger, heartbeatRegistry);
        this.extrasRegistry = new BundleExtrasRegistry(logger);
        this.worldStats = new HytaleWorldStatsProvider(nameMasker);
        this.connectionTracker = new HytaleConnectionTracker(plugin, logger, nameMasker, healthGauges);
        this.sessionRecovery = new JfrSessionRecovery(clock, logger, rollingFile,
            dataDir.resolve("live").resolve("recover"), dataDir.resolve("live").resolve("jfr-session"));
        this.profileSessions = new ProfileSessionController(clock, logger, scheduler, jfr,
            this::performCapture, this::enqueueCapture, this::remember);
        this.netSampler = new HytaleNetSampler(clock, logger);
        this.telemetry = new HytaleTelemetrySampler(clock, logger, worldStats,
            new MetricsLog(dataDir.resolve("metrics")), healthGauges, heartbeatRegistry,
            () -> engine.config(), () -> engine.playerDropDetector(), this::scheduleCapture);
        this.logErrorWatcher = new HytaleLogErrorWatcher(this, clock, logger,
            () -> { Engine e = engine; return e == null ? null : e.config().triggerPolicy().detectors(); });
        this.worldFailureListener = new HytaleWorldFailureListener(this, plugin, clock, logger);
        this.healthScheduler = new HytaleHealthScheduler(this, logger, scheduler, heartbeatPump,
            telemetry, netSampler, jfr, rollingFile, () -> engine);
        Package pkg = HyboxRuntime.class.getPackage();
        healthGauges.setVersion(pkg == null ? null : pkg.getImplementationVersion());
    }

    void registerCommands() {
        HyboxCommand command = new HyboxCommand(this);
        command.setOwner(plugin);
        plugin.getCommandRegistry().registerCommand(command);
    }

    CompletableFuture<CaptureResult> captureManual() {
        return enqueueCapture(new TriggerEvent(TriggerKind.MANUAL, "server", clock.instant(), Map.of()));
    }

    void scheduleCapture(TriggerEvent event) {
        if (admitCapture(false) == null) {
            submitWorker(() -> runCapture(event), () -> {
                remember(CaptureResult.of(closed ? CaptureResult.Status.STOPPED : CaptureResult.Status.BUSY));
                releaseCapture();
            });
        }
    }

    private CaptureResult admitCapture(boolean manual) {
        if (closed) {
            return remember(CaptureResult.of(CaptureResult.Status.STOPPED));
        }
        if (!captureAdmitted.compareAndSet(false, true)) {
            if (manual) {
                busyCaptures.incrementAndGet();
            } else {
                droppedCaptures.incrementAndGet();
            }
            return remember(CaptureResult.of(CaptureResult.Status.BUSY));
        }
        captureProgress = "queued";
        return null;
    }

    private CompletableFuture<CaptureResult> enqueueCapture(TriggerEvent event) {
        CaptureResult denied = admitCapture(true);
        if (denied != null) {
            return CompletableFuture.completedFuture(denied);
        }
        CompletableFuture<CaptureResult> result = new CompletableFuture<>();
        submitWorker(() -> {
            try {
                result.complete(runCapture(event));
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        }, () -> {
            CaptureResult outcome = remember(CaptureResult.of(closed
                ? CaptureResult.Status.STOPPED : CaptureResult.Status.BUSY));
            releaseCapture();
            result.complete(outcome);
        });
        return result;
    }

    private CaptureResult runCapture(TriggerEvent event) {
        try {
            return performCapture(event);
        } finally {
            releaseCapture();
        }
    }

    private CaptureResult performCapture(TriggerEvent event) {
        captureProgress = "collecting";
        try {
            CapturePipeline pipeline = engine.capturePipeline();
            sessionRecovery.recheckStorageBudgets(pipeline);
            CaptureResult result = pipeline.handle(maskAttrs(event));
            if (result.captured()) {
                lastIncidentAt.set(clock.instant());
                lastIncidentId.set(result.incidentId().value());
            }
            return remember(result);
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "Capture pipeline threw unexpectedly.", e);
            return remember(CaptureResult.failed(e.getClass().getSimpleName()));
        }
    }

    private CaptureResult remember(CaptureResult result) {
        lastCapture = result;
        if (result.status() == CaptureResult.Status.FAILED) {
            lastCaptureError = result.detail() + " @ " + clock.instant();
        } else if (result.captured()) {
            lastCaptureError = null;
        }
        return result;
    }

    private void releaseCapture() {
        captureProgress = "idle";
        captureAdmitted.set(false);
    }

    void executeWorker(Runnable work) {
        try {
            worker.execute(work);
        } catch (RejectedExecutionException rejected) {
            rejectedWork.incrementAndGet();
            throw rejected;
        }
    }

    void submitWorker(Runnable work, Runnable rejected) {
        QueuedWork queued = new QueuedWork(work, rejected);
        if (closed) {
            rejectedWork.incrementAndGet();
            rejected.run();
            return;
        }
        try {
            executeWorker(queued);
        } catch (RejectedExecutionException failure) {
            rejected.run();
        }
    }

    private record QueuedWork(Runnable work, Runnable rejected) implements Runnable {
        @Override
        public void run() {
            work.run();
        }
    }

    record Health(boolean running, Instant lastSnapshot, String snapshotError, boolean captureBusy,
                  String captureProgress, String lastIncident, Instant lastSuccess, CaptureResult lastOutcome,
                  String captureError, long busy, long dropped, long rejected, long bundles,
                  String storagePressure, boolean storageChecked,
                  CallbackExecutor.Health callbacks) {}

    Health health() {
        Engine current = engine;
        return new Health(jfr.isRunning(), lastSnapshotAt, lastSnapshotError, captureAdmitted.get(),
            captureProgress, lastIncidentId.get(), lastIncidentAt.get(), lastCapture,
            lastCaptureError, busyCaptures.get(), droppedCaptures.get(), rejectedWork.get(), bundleCount,
            current == null ? null : current.capturePipeline().storagePressure(),
            current != null && current.capturePipeline().storageChecked(), callbacks.health());
    }

    void snapshotSucceeded() {
        lastSnapshotAt = clock.instant();
        lastSnapshotError = null;
    }

    void snapshotFailed(Exception failure) {
        lastSnapshotError = failure.getClass().getSimpleName();
    }

    CallbackExecutor callbacks() {
        return callbacks;
    }

    private TriggerEvent maskAttrs(TriggerEvent event) {
        if (event.attrs().isEmpty()) {
            return event;
        }
        Map<String, String> masked = new HashMap<>();
        event.attrs().forEach((key, value) -> masked.put(key, nameMasker.maskText(value)));
        return new TriggerEvent(event.kind(), event.scope(), event.at(), masked);
    }

    String maskText(String text) {
        return nameMasker.maskText(text);
    }

    Path incidentDir() {
        return incidentDir;
    }

    Path dataDir() {
        return dataDir;
    }

    Path configPath() {
        return configPath;
    }

    HyboxConfig config() {
        return engine.config();
    }

    boolean deadlockArmed() {
        return engine.deadlockDetector() != null;
    }

    boolean heapPressureArmed() {
        return engine.heapPressureDetector() != null;
    }

    boolean gcPressureArmed() {
        return engine.gcPressureDetector() != null;
    }

    boolean cpuSaturationArmed() {
        return engine.cpuSaturationDetector() != null;
    }

    boolean netSaturationArmed() {
        return engine.netSaturationDetector() != null;
    }

    boolean playerDropArmed() {
        return engine.playerDropDetector() != null;
    }

    synchronized String reload() {
        if (closed) {
            return "Reload failed: Hybox is not running.";
        }
        if (captureAdmitted.get() || profileSessions.active()) {
            return "Reload failed: capture or profile session is busy.";
        }
        HyboxConfig previous = engine.config();
        HyboxConfig fresh;
        Engine next;
        try {
            fresh = HytaleHyboxConfig.loadOrCreate(dataDir, logger);
            next = buildEngine(fresh);
        } catch (Exception e) {
            return "Reload failed: " + e + ", keeping previous config.";
        }
        this.engine = next;
        healthScheduler.syncNetSampler();

        List<String> changed = new ArrayList<>();
        if (!previous.triggerPolicy().equals(fresh.triggerPolicy())) {
            changed.add("Trigger");
        }
        if (!previous.capturePolicy().equals(fresh.capturePolicy())) {
            changed.add("Capture/Retention");
        }
        if (!previous.discordWebhook().equals(fresh.discordWebhook())) {
            changed.add("Discord");
        }
        if (previous.prometheusEnabled() != fresh.prometheusEnabled()
            || previous.prometheusPort() != fresh.prometheusPort()
            || !previous.prometheusBind().equals(fresh.prometheusBind())) {
            stopMetricsExporter();
            startMetricsExporter();
            changed.add("Metrics.Prometheus");
        }
        if (!previous.postIncidentMaxWait().equals(fresh.postIncidentMaxWait())) {
            changed.add("Jfr.PostIncidentMaxWait");
        }
        if (previous.threadDumpCount() != fresh.threadDumpCount()
            || !previous.threadDumpInterval().equals(fresh.threadDumpInterval())) {
            changed.add("Jfr.ThreadDumps");
        }

        List<String> restartRequired = new ArrayList<>();
        if (!previous.jfrMaxAge().equals(fresh.jfrMaxAge())
            || previous.jfrMaxSizeBytes() != fresh.jfrMaxSizeBytes()
            || !previous.jfrRecordingName().equals(fresh.jfrRecordingName())
            || !previous.jfrDisabledEvents().equals(fresh.jfrDisabledEvents())
            || !previous.jfrConfiguration().equals(fresh.jfrConfiguration())) {
            restartRequired.add("Jfr recording settings");
        }
        if (!previous.jfrSnapshotInterval().equals(fresh.jfrSnapshotInterval())) {
            restartRequired.add("Jfr.SnapshotInterval");
        }
        if (!previous.jfrSampleInterval().equals(fresh.jfrSampleInterval())) {
            restartRequired.add("Jfr.SampleInterval");
        }

        StringBuilder out = new StringBuilder("Reloaded. Changed: ")
            .append(changed.isEmpty() ? "nothing" : String.join(", ", changed));
        if (!restartRequired.isEmpty()) {
            out.append(". Restart required for: ").append(String.join(", ", restartRequired));
        }
        return out.toString();
    }

    synchronized String startProfileSession(int minutes, boolean keepBuffer) {
        if (closed) {
            return "Hybox is not running.";
        }
        synchronized (profileSessions) {
            if (!captureAdmitted.compareAndSet(false, true)) {
                return "Cannot start profile: a capture is busy.";
            }
            captureProgress = "starting profile";
            try {
                return profileSessions.start(minutes, keepBuffer);
            } finally {
                releaseCapture();
            }
        }
    }

    Path generateTrendReport(int days) throws IOException {
        Instant now = clock.instant();
        MetricsLog.Trend trend = new MetricsLog(dataDir.resolve("metrics")).read(now, days);
        if (trend.isEmpty()) {
            return null;
        }
        long fromMs = now.minus(Duration.ofDays(days)).toEpochMilli();
        long[] incidents = incidentTimesWithin(fromMs, now.toEpochMilli());
        Path out = dataDir.resolve("trend-report.html");
        try (OutputStream os = Files.newOutputStream(out)) {
            TrendReport.write(trend, days, now, incidents, os);
        }
        return out;
    }

    private long[] incidentTimesWithin(long fromMs, long toMs) {
        if (!Files.isDirectory(incidentDir)) {
            return new long[0];
        }
        List<Long> times = new ArrayList<>();
        try (Stream<Path> entries = Files.list(incidentDir)) {
            entries.filter(p -> p.getFileName().toString().endsWith(".zip")).forEach(p -> {
                String base = p.getFileName().toString();
                base = base.substring(0, base.length() - ".zip".length());
                if (base.startsWith("incident-")) {
                    base = base.substring("incident-".length());
                }
                int lastDash = base.lastIndexOf('-');
                if (lastDash <= 0) {
                    return;
                }
                try {
                    long ms = OffsetDateTime.parse(base.substring(0, lastDash), INCIDENT_TS)
                        .toInstant().toEpochMilli();
                    if (ms >= fromMs && ms <= toMs) {
                        times.add(ms);
                    }
                } catch (RuntimeException ignored) {
                }
            });
        } catch (IOException e) {
            return new long[0];
        }
        times.sort(null);
        long[] out = new long[times.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = times.get(i);
        }
        return out;
    }

    boolean profileActive() {
        return profileSessions.active();
    }

    long profileRemainingSeconds() {
        return profileSessions.remainingSeconds();
    }

    BundleExtrasRegistry extrasRegistry() {
        return extrasRegistry;
    }

    ExecutorService worker() {
        return worker;
    }

    Optional<String> lastIncidentId() {
        return Optional.ofNullable(lastIncidentId.get());
    }

    Optional<Instant> lastIncidentAt() {
        return Optional.ofNullable(lastIncidentAt.get());
    }

    private void logStartup() {
        try {
            plugin.getLogger().at(Level.INFO).log("Hybox started.");
            plugin.getLogger().at(Level.INFO).log("Config: %s", configPath);
        } catch (Exception e) {
            logger.log(System.Logger.Level.INFO, "Hybox started.");
        }
    }

    private CompletableFuture<Void> recoverOrphanedRecordings() {
        return sessionRecovery.recoverOrphanedRecordings(
            !engine.config().jfrSnapshotInterval().isZero(),
            () -> engine.capturePipeline(),
            worker);
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        cleanup("Metrics endpoint", this::stopMetricsExporter);
        cleanup("Log watcher", logErrorWatcher::unregister);
        if (diagnosticsRegistration != null) {
            cleanup("Diagnostics", diagnosticsRegistration);
        }
        cleanup("Commands", () -> plugin.getCommandRegistry().shutdownAndCleanup(false));
        cleanup("Event listeners", () -> plugin.getEventRegistry().shutdownAndCleanup(false));
        cleanup("Health scheduler", healthScheduler::close);
        cleanup("Scheduler executor", scheduler::shutdownNow);
        cleanup("Profile session", profileSessions::close);
        cleanup("Worker executor", worker::shutdown);
        cleanup("Network sampler", netSampler);
        awaitTermination(scheduler);
        if (!awaitTermination(worker)) {
            cleanup("Worker executor interruption", () -> {
                for (Runnable queued : worker.shutdownNow()) {
                    if (queued instanceof QueuedWork work) {
                        work.rejected().run();
                    }
                }
            });
            awaitTermination(worker);
        }

        if (started && healthScheduler.ownsRollingSnapshot()) {
            cleanup("Rolling snapshot", () -> {
                if (capturePending.get()) {
                    Files.createDirectories(rollingFile.getParent());
                    jfr.dump(rollingFile);
                    logger.log(System.Logger.Level.WARNING,
                        "Shutdown during an in-flight capture; left a rolling snapshot at " + rollingFile
                        + " for recovery on the next startup.");
                } else {
                    Files.deleteIfExists(rollingFile);
                    Files.deleteIfExists(rollingFile.resolveSibling("rolling.jfr.tmp"));
                }
            });
        }
        cleanup("Callbacks", callbacks);
        cleanup("JFR", jfr);
    }

    private boolean awaitTermination(ExecutorService executor) {
        try {
            return executor.awaitTermination(Duration.ofSeconds(2).toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "Executor shutdown failed.", e);
        }
        return false;
    }

    private void cleanup(String resource, AutoCloseable action) {
        try {
            action.close();
        } catch (Throwable failure) {
            logger.log(System.Logger.Level.WARNING, resource + " shutdown failed.", failure);
        }
    }
}
