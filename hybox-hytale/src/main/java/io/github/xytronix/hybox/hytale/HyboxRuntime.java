package io.github.xytronix.hybox.hytale;

import com.hypixel.hytale.server.core.universe.PlayerRef;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import io.github.xytronix.hybox.core.bundle.BundleBuilder;
import io.github.xytronix.hybox.core.bundle.BundleExtrasRegistry;
import io.github.xytronix.hybox.core.capture.CapturePipeline;
import io.github.xytronix.hybox.core.capture.HeapHistogram;
import io.github.xytronix.hybox.core.capture.CaptureResult;
import io.github.xytronix.hybox.core.capture.IncidentNotifier;
import io.github.xytronix.hybox.core.capture.PostIncidentWaiter;
import io.github.xytronix.hybox.core.capture.RecordingDumper;
import io.github.xytronix.hybox.core.config.HyboxConfig;
import io.github.xytronix.hybox.core.env.TextRedactor;
import io.github.xytronix.hybox.core.capture.DiagnosticsProvider;
import io.github.xytronix.hybox.core.health.FrameLabels;
import io.github.xytronix.hybox.core.health.ThreadDumpProgress;
import io.github.xytronix.hybox.core.incident.DiagnosticSection;
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
import io.github.xytronix.hybox.core.retention.RetentionUsage;
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
    private final ExecutorService captureExecutor;

    private final CallbackExecutor callbacks = new CallbackExecutor();
    private final JfrController jfr;
    private final HeartbeatRegistry heartbeatRegistry;
    private final HytaleHeartbeatPump heartbeatPump;
    private final BundleExtrasRegistry extrasRegistry;
    private final HytaleConnectionTracker connectionTracker;
    private final JfrSessionRecovery sessionRecovery;
    private final ProfileSessionController profileSessions;
    private final HyboxProgressHuds progressHuds;
    private final HytaleNetSampler netSampler;
    private final HealthGauges healthGauges = new HealthGauges();
    private final HytaleTelemetrySampler telemetry;
    private final HyboxNotifications.Throttle alerts;
    private volatile HyboxCommand command;
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
    private volatile HyboxConfig startupConfig;
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
    private final AtomicBoolean skipRecoveryWait = new AtomicBoolean();
    private final AtomicBoolean captureAdmitted = new AtomicBoolean();
    private final AtomicReference<PendingProfile> pendingProfile = new AtomicReference<>();
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
    private volatile HeapView lastHeap;
    private boolean started;
    private HyboxApi.Registration diagnosticsRegistration;
    private volatile Map<String, Object> previousHyinit;

    static HyboxRuntime start(HyboxPlugin plugin) throws Exception {
        Objects.requireNonNull(plugin, "plugin");
        System.Logger logger = System.getLogger(HyboxRuntime.class.getName());
        Path dataDir = plugin.getDataDirectory();
        HyboxConfig config = HytaleHyboxConfig.loadOrCreate(dataDir, logger);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            new NamedThreadFactory("hybox-scheduler"));
        ExecutorService worker = null;
        ExecutorService captureExecutor = null;
        JfrController jfr = null;
        HyboxRuntime runtime = null;
        try {
            worker = newWorker();
            captureExecutor = newCaptureExecutor();
            jfr = new JfrController(
                config.jfrMaxAge(), config.jfrMaxSizeBytes(), config.jfrRecordingName(),
                config.jfrDisabledEvents(), config.jfrConfiguration(), config.jfrOldObjectSampling());
            jfr.start();
            runtime = new HyboxRuntime(plugin, Clock.systemUTC(), logger, dataDir, scheduler, worker, captureExecutor,
                jfr);
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
                if (captureExecutor != null) {
                    rollback(failure, captureExecutor::shutdownNow);
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

    static ExecutorService newCaptureExecutor() {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1), new NamedThreadFactory("hybox-capture"));
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
        previousHyinit = HytaleMixins.rotateSaved(dataDir.resolve("live").resolve("hyinit.json"));
        FrameLabels.install(HytaleMixins.labels(HyinitReader.get()));
        engine = buildEngine(config);
        startupConfig = config;
        long snapshotMillis = config.jfrSnapshotInterval().toMillis();
        extrasRegistry.register(new HytaleBundleExtrasProvider(
            heartbeatRegistry,
            () -> engine.config().capturePolicy().logTailLines(),
            () -> engine.config().capturePolicy().includeServerLog(),
            dataDir.resolve("metrics"),
            nameMasker,
            () -> lastSpacedDumps.getAndSet(List.of()),
            () -> engine.config().threadDumpCount() > 0));
        healthScheduler.syncNetSampler();
        healthScheduler.start();
        registerCommands();
        worldFailureListener.register();
        connectionTracker.register();
        try {
            plugin.getEventRegistry().registerGlobal(com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent.class,
                event -> progressHuds.playerReady(event.getPlayerRef()));
        } catch (RuntimeException | LinkageError failure) {
            logger.log(System.Logger.Level.WARNING, "Progress HUD cannot follow world changes. It reappears on the next capture.", failure);
        }
        logErrorWatcher.register();
        diagnosticsRegistration = HyboxApi.registerDiagnostics("hybox", "Environment", HytaleEnvironment::snapshot);
        refreshBundleStats();
        startMetricsExporter();
        healthScheduler.startSnapshotWriter(snapshotMillis, recoverOrphanedRecordings());
        logStartup();
    }

    private Engine buildEngine(HyboxConfig config) {
        this.textRedactor = new TextRedactor(config.capturePolicy().redactPatterns(), HytalePlugins::knownVersions);
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
        GcPressureDetector gcPressureDetector = modules.gcPressure() && detectors.gcPressurePct() > 0
            && !detectors.gcPressureWindow().isZero()
            ? new GcPressureDetector(clock, JvmProbes::stwGcPauseMillis,
                detectors.gcPressurePct() / 100.0, detectors.gcPressureWindow())
            : null;
        TriggerEngine triggerEngine = new TriggerEngine(clock, config.triggerPolicy());

        RecordingDumper dumper = (target) -> {
            try {
                captureProgress = "saving the recording";
                jfr.dump(target);
                captureProgress = "building bundle";
                return target;
            } finally {
                capturePending.set(false);
            }
        };
        PostIncidentWaiter postIncidentWaiter = (event) -> {
            captureProgress = "waiting for the server to recover";
            capturePending.set(true);
            skipRecoveryWait.set(false);
            try {
                lastSpacedDumps.set(HytaleHeartbeatPump.awaitRecovery(event, heartbeatRegistry, clock,
                    config.postIncidentMaxWait(), config.threadDumpCount(), config.threadDumpInterval(),
                    recoverySignal(event, tickDegradedDetector, gcPressureDetector), skipRecoveryWait::get, logger));
            } finally {
                skipRecoveryWait.set(false);
            }
        };
        IncidentNotifier configuredNotifier = buildNotifier(clock, config, logger,
            task -> submitWorker(task, () -> logger.log(System.Logger.Level.WARNING,
                "Discord notification skipped: Hybox worker is busy or stopping.")));
        IncidentNotifier notifier = (report, zip) -> {
            recordIncidentGauge(report);
            announce(report, zip);
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
            new DiagnosticsProvider() {
                @Override
                public List<DiagnosticSection> sections() {
                    return diagnostics(config, false);
                }

                @Override
                public List<DiagnosticSection> recoveredSections() {
                    return diagnostics(config, true);
                }

                @Override
                public Collection<String> knownVersions() {
                    return HytalePlugins.knownVersions();
                }
            },
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
            gcPressureDetector,
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

    private List<DiagnosticSection> diagnostics(HyboxConfig config, boolean recovered) {
        List<DiagnosticSection> base = HytalePlugins.appendTo(
            HytaleEntities.appendTo(HytaleTickSystems.appendTo(HytaleHeartbeats.appendTo(HytaleServerLog.appendTo(
                HyboxApi.collectDiagnostics(), config.capturePolicy().includeServerLog(),
                config.capturePolicy().logTailLines(), nameMasker), heartbeatRegistry, clock))));
        List<DiagnosticSection> mixins = recovered ? HytaleMixins.appendSaved(base, previousHyinit) : HytaleMixins.appendTo(base);
        return ThreadDumpProgress.appendTo(HytaleAssetPacks.appendTo(HytaleServerSettings.appendTo(HytaleModConfigs.appendTo(mixins,
            config.capturePolicy().includeModConfigs(), HyboxApi.registeredConfigPaths()), config.capturePolicy().includeServerConfig())), lastSpacedDumps.get());
    }

    private static BooleanSupplier recoverySignal(TriggerEvent event, TickDegradedDetector tick,
                                                  GcPressureDetector gc) {
        return switch (event.kind()) {
            case TICK_DEGRADED -> tick == null ? null : () -> tick.recovered(event.scope());
            case GC_PRESSURE -> gc == null ? null : gc::recovered;
            default -> null;
        };
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
        this(plugin, clock, logger, dataDir, scheduler, worker, newCaptureExecutor(), jfr);
    }

    private HyboxRuntime(
        HyboxPlugin plugin,
        Clock clock,
        System.Logger logger,
        Path dataDir,
        ScheduledExecutorService scheduler,
        ExecutorService worker,
        ExecutorService captureExecutor,
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
        this.captureExecutor = captureExecutor;
        this.jfr = jfr;
        this.heartbeatRegistry = new HeartbeatRegistry(clock);
        this.heartbeatPump = new HytaleHeartbeatPump(logger, heartbeatRegistry);
        this.extrasRegistry = new BundleExtrasRegistry(logger);
        this.worldStats = new HytaleWorldStatsProvider(nameMasker);
        this.connectionTracker = new HytaleConnectionTracker(plugin, logger, nameMasker, healthGauges);
        this.sessionRecovery = new JfrSessionRecovery(clock, logger, rollingFile,
            dataDir.resolve("live").resolve("recover"), dataDir.resolve("live").resolve("jfr-session"));
        this.profileSessions = new ProfileSessionController(clock, logger, scheduler, jfr,
            this::performCapture, this::enqueueProfileCapture, this::remember);
        this.progressHuds = new HyboxProgressHuds(this, logger);
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
        this.alerts = new HyboxNotifications.Throttle(clock);
    }

    void registerCommands() {
        HyboxCommand command = new HyboxCommand(this);
        command.setOwner(plugin);
        plugin.getCommandRegistry().registerCommand(command);
        this.command = command;
    }

    CompletableFuture<CaptureResult> captureManual(PlayerRef requester) {
        CompletableFuture<CaptureResult> capture = enqueueCapture(
            new TriggerEvent(TriggerKind.MANUAL, "server", clock.instant(), Map.of()));
        HyboxProgressHuds.Handle hud = capture.isDone() ? null : progressHuds.start(requester, HyboxProgressHuds.Kind.CAPTURE);
        capture.whenComplete((result, failure) -> {
            progressHuds.stop(hud);
            notifyRequester(requester, failure == null ? result : CaptureResult.failed(failure.getClass().getSimpleName()), false);
        });
        return capture;
    }

    ScheduledFuture<?> scheduleDashboardRefresh(Runnable refresh) {
        return scheduler.scheduleAtFixedRate(refresh, 1, 1, TimeUnit.SECONDS);
    }

    void scheduleCapture(TriggerEvent event) {
        if (admitCapture(false) == null) {
            submitCapture(() -> runCapture(event), () -> {
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
        submitAdmittedCapture(event, result);
        return result;
    }

    private void submitAdmittedCapture(TriggerEvent event, CompletableFuture<CaptureResult> result) {
        submitCapture(() -> {
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
    }

    private CompletableFuture<CaptureResult> enqueueProfileCapture(TriggerEvent event) {
        CompletableFuture<CaptureResult> result = new CompletableFuture<>();
        result.whenComplete((outcome, failure) -> {
            if (failure != null) {
                logServer(Level.WARNING, "Profile capture failed: " + failure.getClass().getSimpleName());
            } else if (outcome.captured()) {
                logServer(Level.INFO, "Profile capture finished: " + outcome.incidentId().value());
            } else {
                logServer(Level.WARNING, "Profile capture finished: " + outcome.status()
                    + (outcome.detail() == null ? "" : " (" + outcome.detail() + ")"));
            }
        });
        if (closed) {
            result.complete(remember(CaptureResult.of(CaptureResult.Status.STOPPED)));
            return result;
        }
        if (captureAdmitted.compareAndSet(false, true)) {
            captureProgress = "queued";
            submitAdmittedCapture(event, result);
            return result;
        }
        if (!pendingProfile.compareAndSet(null, new PendingProfile(event, result))) {
            busyCaptures.incrementAndGet();
            result.complete(remember(CaptureResult.of(CaptureResult.Status.BUSY)));
            return result;
        }
        logServer(Level.INFO, "Profile capture waiting for the in-flight capture to finish.");
        if (captureAdmitted.compareAndSet(false, true)) {
            releaseCapture();
        }
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

    private void notifyRequester(PlayerRef requester, CaptureResult result, boolean profile) {
        if (requester == null) return;
        try {
            HyboxNotifications.completed(requester, result, profile,
                result.captured() ? HyboxChatFormatter.saved(result, incidentDir, true) : null);
        } catch (RuntimeException | LinkageError failure) {
            logger.log(System.Logger.Level.WARNING, "Unable to notify capture requester.", failure);
        }
    }

    private void announce(IncidentReport report, Path zip) {
        IncidentMetadata meta = report.meta();
        if ("MANUAL".equals(meta.trigger())) return;
        String severity = meta.severity().name();
        HyboxNotifications.Alert alert = alerts.admit(severity, meta.headline());
        int told = alert == null ? -1 : alertStaff(alert);
        long bytes;
        try {
            bytes = Files.size(zip);
        } catch (IOException | RuntimeException failure) {
            bytes = -1;
        }
        logServer(meta.severity() == io.github.xytronix.hybox.core.incident.Severity.INFO ? Level.INFO : Level.WARNING,
            HyboxChatFormatter.alertLog(severity, meta.headline(), zip, bytes, told));
    }

    private int alertStaff(HyboxNotifications.Alert alert) {
        HyboxCommand root = command;
        if (root == null) return 0;
        try {
            var incident = root.getSubCommand("incident");
            return HyboxNotifications.alertStaff(com.hypixel.hytale.server.core.universe.Universe.get().getPlayers(),
                incident::hasPermission, alert, logger);
        } catch (RuntimeException | LinkageError failure) {
            logger.log(System.Logger.Level.WARNING, "Unable to tell staff about the incident.", failure);
            return 0;
        }
    }

    private void releaseCapture() {
        PendingProfile next = pendingProfile.getAndSet(null);
        if (next != null) {
            if (!closed) {
                captureProgress = "queued";
                submitAdmittedCapture(next.event(), next.result());
                return;
            }
            next.result().complete(remember(CaptureResult.of(CaptureResult.Status.STOPPED)));
        }
        captureProgress = "idle";
        captureAdmitted.set(false);
        if (pendingProfile.get() != null && captureAdmitted.compareAndSet(false, true)) {
            releaseCapture();
        }
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

    private void submitCapture(Runnable work, Runnable rejected) {
        QueuedWork queued = new QueuedWork(work, rejected);
        if (closed) {
            rejectedWork.incrementAndGet();
            rejected.run();
            return;
        }
        try {
            captureExecutor.execute(queued);
        } catch (RejectedExecutionException failure) {
            rejectedWork.incrementAndGet();
            rejected.run();
        }
    }

    private record PendingProfile(TriggerEvent event, CompletableFuture<CaptureResult> result) {}

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
                  CallbackExecutor.Health callbacks, HytaleMixins.ProblemCounts mixinProblems) {}

    Health health() {
        Engine current = engine;
        return new Health(jfr.isRunning(), lastSnapshotAt, lastSnapshotError, captureAdmitted.get(),
            captureProgress, lastIncidentId.get(), lastIncidentAt.get(), lastCapture,
            lastCaptureError, busyCaptures.get(), droppedCaptures.get(), rejectedWork.get(), bundleCount,
            current == null ? null : current.capturePipeline().storagePressure(),
            current != null && current.capturePipeline().storageChecked(), callbacks.health(),
            HytaleMixins.problemCounts());
    }

    HytaleTelemetrySampler.Status liveStatus() {
        return telemetry.status();
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

    RetentionUsage storageUsage() {
        return engine.capturePipeline().storageUsage();
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

    HyboxConfig startupConfig() {
        return startupConfig;
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
        if (profileSessions.active() || pendingProfile.get() != null || !captureAdmitted.compareAndSet(false, true)) {
            return "Reload failed: capture or profile session is busy.";
        }
        captureProgress = "reloading";
        try {
            if (profileSessions.active()) {
                return "Reload failed: capture or profile session is busy.";
            }
            return reloadAdmitted();
        } finally {
            releaseCapture();
        }
    }

    private String reloadAdmitted() {
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
        if (previous.prometheusEnabled() != fresh.prometheusEnabled()
            || previous.prometheusPort() != fresh.prometheusPort()
            || !previous.prometheusBind().equals(fresh.prometheusBind())) {
            stopMetricsExporter();
            startMetricsExporter();
        }
        return reloadSummary(previous, fresh);
    }

    static String reloadSummary(HyboxConfig previous, HyboxConfig fresh) {
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
        if (previous.metricsEnabled() != fresh.metricsEnabled()
            || previous.metricsRetentionDays() != fresh.metricsRetentionDays()
            || previous.metricsCpu() != fresh.metricsCpu()
            || previous.metricsAllocation() != fresh.metricsAllocation()) {
            changed.add("Metrics");
        }
        if (previous.prometheusEnabled() != fresh.prometheusEnabled()
            || previous.prometheusPort() != fresh.prometheusPort()
            || !previous.prometheusBind().equals(fresh.prometheusBind())) {
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
        if (previous.jfrOldObjectSampling() != fresh.jfrOldObjectSampling()) {
            restartRequired.add("Jfr.OldObjectSampling");
        }

        StringBuilder out = new StringBuilder("Reloaded. Changed: ")
            .append(changed.isEmpty() ? "nothing" : String.join(", ", changed));
        if (!restartRequired.isEmpty()) {
            out.append(". Restart required for: ").append(String.join(", ", restartRequired));
        }
        return out.toString();
    }

    synchronized CompletableFuture<String> startProfileSession(int minutes, boolean keepBuffer, PlayerRef requester) {
        CompletableFuture<String> result = new CompletableFuture<>();
        result.whenComplete((error, failure) -> {
            if (failure != null) {
                logServer(Level.WARNING, "Profile session not started: " + failure);
            } else if (error != null) {
                logServer(Level.WARNING, "Profile session not started: " + error);
            } else {
                logServer(Level.INFO, "Profile session started: " + minutes + " min, keep-buffer=" + keepBuffer + ".");
            }
        });
        if (closed) {
            result.complete("Hybox is not running.");
            return result;
        }
        if (pendingProfile.get() != null || !captureAdmitted.compareAndSet(false, true)) {
            result.complete("Cannot start profile: a capture is busy.");
            return result;
        }
        AtomicReference<HyboxProgressHuds.Handle> hud = new AtomicReference<>();
        AtomicBoolean completed = new AtomicBoolean();
        captureProgress = "starting profile";
        submitCapture(() -> {
            try {
                String outcome;
                try {
                    synchronized (profileSessions) {
                        CaptureResult before = lastCapture;
                        outcome = pendingProfile.get() != null
                            ? "Cannot start profile: a capture is busy."
                            : profileSessions.start(minutes, keepBuffer, requesterName(requester), capture -> {
                                completed.set(true);
                                progressHuds.stop(hud.get());
                                notifyRequester(requester, capture, true);
                            });
                        CaptureResult leadUp = lastCapture;
                        if (!keepBuffer && leadUp != before && leadUp != null && leadUp.retention() != null) {
                            notifyRequester(requester, leadUp, false);
                        }
                        if (outcome == null && !completed.get()) {
                            HyboxProgressHuds.Handle started = progressHuds.start(requester, HyboxProgressHuds.Kind.PROFILE);
                            hud.set(started);
                            if (completed.get()) progressHuds.stop(started);
                        }
                    }
                } finally {
                    releaseCapture();
                }
                result.complete(outcome);
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        }, () -> {
            releaseCapture();
            result.complete(closed ? "Hybox is not running." : "Cannot start profile: a capture is busy.");
        });
        return result;
    }

    record HeapView(HeapHistogram.Snapshot snapshot, Instant takenAt) {}

    HeapView walkHeap() {
        HeapHistogram.Snapshot snapshot = HeapHistogram.snapshot(DashboardModel.Heap.ROWS);
        if (snapshot.entries().isEmpty()) return null;
        HeapView view = new HeapView(snapshot, clock.instant());
        lastHeap = view;
        logServer(Level.INFO, "Heap histogram taken: " + snapshot.entries().size() + " classes listed.");
        return view;
    }

    HeapView lastHeap() {
        return lastHeap;
    }

    HyboxTrendModel readTrend(int requestedDays) {
        int days = HyboxTrendModel.days(requestedDays);
        Instant now = clock.instant();
        boolean enabled = config().metricsEnabled();
        MetricsLog.Trend trend = enabled ? new MetricsLog(dataDir.resolve("metrics")).read(now, days) : MetricsLog.Trend.EMPTY;
        List<HyboxIncidents.Incident> incidents = incidentsWithin(now.minus(Duration.ofDays(days)), now);
        HytaleTelemetrySampler.Status live = telemetry.status();
        return HyboxTrendModel.from(enabled, days, now, trend,
            incidents.stream().mapToLong(incident -> incident.createdAt().toEpochMilli()).toArray(),
            live == null ? 0 : live.targetTps());
    }

    Path generateTrendReport(int days) throws IOException {
        Instant now = clock.instant();
        MetricsLog.Trend trend = new MetricsLog(dataDir.resolve("metrics")).read(now, days);
        if (trend.isEmpty()) {
            return null;
        }
        List<TrendReport.Incident> incidents = incidentsWithin(now.minus(Duration.ofDays(days)), now).stream()
            .map(HyboxIncidents::details)
            .map(incident -> new TrendReport.Incident(incident.createdAt().toEpochMilli(), incident.severity(),
                incident.headline(), incident.kind(), incident.path().getFileName().toString()))
            .toList();
        Path out = dataDir.resolve("trend-report.html");
        try (OutputStream os = Files.newOutputStream(out)) {
            TrendReport.write(trend, days, now, incidents, os);
        }
        return out;
    }

    private List<HyboxIncidents.Incident> incidentsWithin(Instant from, Instant to) {
        try {
            return HyboxIncidents.within(incidentDir, from, to);
        } catch (IOException e) {
            return List.of();
        }
    }

    boolean profileActive() {
        return profileSessions.active();
    }

    long profileRemainingSeconds() {
        return profileSessions.remainingSeconds();
    }

    Instant profileStartedAt() {
        return profileSessions.startedAt();
    }

    Duration profileTotalDuration() {
        return profileSessions.totalDuration();
    }

    String profileRequesterName() {
        return profileSessions.requesterName();
    }

    boolean profileCapturePending() {
        return pendingProfile.get() != null;
    }

    boolean recoveryWaitActive() {
        return "waiting for the server to recover".equals(captureProgress);
    }

    String skipRecoveryWait() {
        if (!recoveryWaitActive()) return "No capture is waiting for the server to recover.";
        skipRecoveryWait.set(true);
        logServer(Level.INFO, "Recovery wait skipped. Writing the bundle now.");
        return null;
    }

    String finishProfileNow() {
        String outcome = profileSessions.finishNow();
        if (outcome == null) logServer(Level.INFO, "Profile session ended early. Saving the bundle now.");
        return outcome;
    }

    String cancelProfile() {
        String outcome = profileSessions.cancel();
        if (outcome == null) logServer(Level.INFO, "Profile session cancelled. Nothing was saved.");
        return outcome;
    }

    private String requesterName(PlayerRef requester) {
        if (requester == null) return null;
        try {
            return requester.getUsername();
        } catch (RuntimeException | LinkageError failure) {
            logger.log(System.Logger.Level.WARNING, "Unable to read profile requester name.", failure);
            return null;
        }
    }

    BundleExtrasRegistry extrasRegistry() {
        return extrasRegistry;
    }

    System.Logger logger() {
        return logger;
    }

    private void logServer(Level level, String message) {
        try {
            plugin.getLogger().at(level).log(message);
        } catch (Exception e) {
            logger.log(level == Level.WARNING ? System.Logger.Level.WARNING : System.Logger.Level.INFO, message);
        }
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
        boolean admitted = captureAdmitted.compareAndSet(false, true);
        if (admitted) {
            captureProgress = "recovering";
        }
        CompletableFuture<Void> recovery;
        try {
            recovery = sessionRecovery.recoverOrphanedRecordings(
                !engine.config().jfrSnapshotInterval().isZero(),
                () -> engine.capturePipeline(),
                captureExecutor);
        } catch (RuntimeException | Error failure) {
            if (admitted) {
                releaseCapture();
            }
            throw failure;
        }
        return admitted ? recovery.whenComplete((ignored, failure) -> releaseCapture()) : recovery;
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
        cleanup("Frame labels", () -> FrameLabels.install(null));
        cleanup("Commands", () -> plugin.getCommandRegistry().shutdownAndCleanup(false));
        cleanup("Event listeners", () -> plugin.getEventRegistry().shutdownAndCleanup(false));
        cleanup("Health scheduler", healthScheduler::close);
        cleanup("Progress HUDs", progressHuds::close);
        cleanup("Scheduler executor", scheduler::shutdownNow);
        cleanup("Worker executor", worker::shutdown);
        cleanup("Capture executor", captureExecutor::shutdown);
        cleanup("Network sampler", netSampler);
        awaitTermination(scheduler);
        drain("Worker executor", worker);
        drain("Capture executor", captureExecutor);
        cleanup("Profile session", profileSessions::close);
        PendingProfile pending = pendingProfile.getAndSet(null);
        if (pending != null) {
            pending.result().complete(remember(CaptureResult.of(CaptureResult.Status.STOPPED)));
        }

        if (started && healthScheduler.ownsRollingSnapshot()) {
            cleanup("Rolling snapshot", () -> {
                if (capturePending.get()) {
                    Files.createDirectories(rollingFile.getParent());
                    jfr.dump(rollingFile);
                    logger.log(System.Logger.Level.WARNING,
                        "Shutdown during an in-flight capture. Left a rolling snapshot at " + rollingFile
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

    private void drain(String resource, ExecutorService executor) {
        if (!awaitTermination(executor)) {
            cleanup(resource + " interruption", () -> {
                for (Runnable queued : executor.shutdownNow()) {
                    if (queued instanceof QueuedWork work) {
                        work.rejected().run();
                    }
                }
            });
            awaitTermination(executor);
        }
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
