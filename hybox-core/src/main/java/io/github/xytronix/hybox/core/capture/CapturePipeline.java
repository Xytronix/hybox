package io.github.xytronix.hybox.core.capture;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import io.github.xytronix.hybox.core.bundle.BundleArtifacts;
import io.github.xytronix.hybox.core.bundle.BundleAttachment;
import io.github.xytronix.hybox.core.bundle.BundleBuilder;
import io.github.xytronix.hybox.core.env.TextRedactor;
import io.github.xytronix.hybox.core.health.DeadlockCycle;
import io.github.xytronix.hybox.core.health.HealthCollector;
import io.github.xytronix.hybox.core.health.HealthSnapshot;
import io.github.xytronix.hybox.core.health.JfrHotThreads;
import io.github.xytronix.hybox.core.health.JfrSnapshot;
import io.github.xytronix.hybox.core.health.JfrThreadDump;
import io.github.xytronix.hybox.core.health.StalledThread;
import io.github.xytronix.hybox.core.health.WorldStatsProvider;
import io.github.xytronix.hybox.core.incident.DiagnosticSection;
import io.github.xytronix.hybox.core.incident.IncidentId;
import io.github.xytronix.hybox.core.incident.IncidentIds;
import io.github.xytronix.hybox.core.incident.IncidentMetadata;
import io.github.xytronix.hybox.core.incident.IncidentReport;
import io.github.xytronix.hybox.core.incident.IncidentSummary;
import io.github.xytronix.hybox.core.incident.Severity;
import io.github.xytronix.hybox.core.jfr.JfrRepository;
import io.github.xytronix.hybox.core.retention.RetentionManager;
import io.github.xytronix.hybox.core.trigger.TriggerDecision;
import io.github.xytronix.hybox.core.trigger.TriggerEngine;
import io.github.xytronix.hybox.core.trigger.TriggerEvent;
import io.github.xytronix.hybox.core.trigger.TriggerResult;

/**
 * Orchestrates trigger evaluation through capture and retention.
 */
public final class CapturePipeline {
    private static final String FAILED_DIR_NAME = "failed";

    private final Clock clock;
    private final TriggerEngine triggerEngine;
    private final RecordingDumper dumper;
    private final BundleBuilder bundleBuilder;
    private final RetentionManager retentionManager;
    private final IncidentNotifier notifier;
    private final BundleExtrasProvider extrasProvider;
    private final Path incidentDir;
    private final Path tempDir;
    private final CapturePolicy policy;
    private final System.Logger logger;
    private final TextRedactor redactor;
    private final WorldStatsProvider worldStatsProvider;
    private final DiagnosticsProvider diagnosticsProvider;
    private final PostIncidentWaiter postIncidentWaiter;
    private volatile boolean recoveryStorageWithinBudget = true;
    private final java.util.concurrent.atomic.AtomicBoolean stagingBudgetChecked = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile String storagePressure;
    private volatile boolean storageChecked;

    public CapturePipeline(
        Clock clock,
        TriggerEngine triggerEngine,
        RecordingDumper dumper,
        BundleBuilder bundleBuilder,
        RetentionManager retentionManager,
        IncidentNotifier notifier,
        Path incidentDir,
        Path tempDir,
        CapturePolicy policy,
        System.Logger logger
    ) {
        this(
            clock,
            triggerEngine,
            dumper,
            bundleBuilder,
            retentionManager,
            notifier,
            BundleExtrasProvider.none(),
            incidentDir,
            tempDir,
            policy,
            logger
        );
    }

    public CapturePipeline(
        Clock clock,
        TriggerEngine triggerEngine,
        RecordingDumper dumper,
        BundleBuilder bundleBuilder,
        RetentionManager retentionManager,
        IncidentNotifier notifier,
        BundleExtrasProvider extrasProvider,
        Path incidentDir,
        Path tempDir,
        CapturePolicy policy,
        System.Logger logger
    ) {
        this(clock, triggerEngine, dumper, bundleBuilder, retentionManager, notifier, extrasProvider,
            WorldStatsProvider.none(), incidentDir, tempDir, policy, logger);
    }

    public CapturePipeline(
        Clock clock,
        TriggerEngine triggerEngine,
        RecordingDumper dumper,
        BundleBuilder bundleBuilder,
        RetentionManager retentionManager,
        IncidentNotifier notifier,
        BundleExtrasProvider extrasProvider,
        WorldStatsProvider worldStatsProvider,
        Path incidentDir,
        Path tempDir,
        CapturePolicy policy,
        System.Logger logger
    ) {
        this(clock, triggerEngine, dumper, bundleBuilder, retentionManager, notifier, extrasProvider,
            worldStatsProvider, DiagnosticsProvider.none(),
            PostIncidentWaiter.none(), incidentDir, tempDir, policy, logger);
    }

    public CapturePipeline(
        Clock clock,
        TriggerEngine triggerEngine,
        RecordingDumper dumper,
        BundleBuilder bundleBuilder,
        RetentionManager retentionManager,
        IncidentNotifier notifier,
        BundleExtrasProvider extrasProvider,
        WorldStatsProvider worldStatsProvider,
        DiagnosticsProvider diagnosticsProvider,
        PostIncidentWaiter postIncidentWaiter,
        Path incidentDir,
        Path tempDir,
        CapturePolicy policy,
        System.Logger logger
    ) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.triggerEngine = Objects.requireNonNull(triggerEngine, "triggerEngine");
        this.dumper = Objects.requireNonNull(dumper, "dumper");
        this.bundleBuilder = Objects.requireNonNull(bundleBuilder, "bundleBuilder");
        this.retentionManager = Objects.requireNonNull(retentionManager, "retentionManager");
        this.notifier = Objects.requireNonNull(notifier, "notifier");
        this.extrasProvider = Objects.requireNonNull(extrasProvider, "extrasProvider");
        this.worldStatsProvider = Objects.requireNonNull(worldStatsProvider, "worldStatsProvider");
        this.diagnosticsProvider = Objects.requireNonNull(diagnosticsProvider, "diagnosticsProvider");
        this.postIncidentWaiter = Objects.requireNonNull(postIncidentWaiter, "postIncidentWaiter");
        this.incidentDir = Objects.requireNonNull(incidentDir, "incidentDir");
        this.tempDir = Objects.requireNonNull(tempDir, "tempDir");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.redactor = new TextRedactor(policy.redactPatterns());
    }

    public boolean isEnabled() {
        return policy.enabled();
    }

    public String storagePressure() {
        return storagePressure;
    }

    public boolean storageChecked() {
        return storageChecked;
    }

    public CaptureResult handle(TriggerEvent event) {
        if (Thread.currentThread().isInterrupted()) {
            return CaptureResult.of(CaptureResult.Status.CANCELLED);
        }
        if (!policy.enabled()) {
            return CaptureResult.of(CaptureResult.Status.DISABLED);
        }
        boolean stagingChecked = stagingBudgetChecked.getAndSet(false);
        if (!recoveryStorageWithinBudget) {
            return new CaptureResult(CaptureResult.Status.STORAGE_PRESSURE, null, storagePressure);
        }
        if (!stagingChecked) {
            storagePressure = retentionManager.enforceStaging(tempDir, policy.retention())
                ? null : "Staging storage budget reached or unavailable";
            storageChecked = true;
        }
        if (storagePressure != null) {
            return new CaptureResult(CaptureResult.Status.STORAGE_PRESSURE, null, storagePressure);
        }
        Path tempRecording = null;
        Path dumpedRecording = null;
        boolean bundled = false;
        try {
            TriggerResult result = triggerEngine.evaluate(event);
            if (result.decision() != TriggerDecision.ACCEPT) {
                return CaptureResult.of(switch (result.decision()) {
                    case COOLDOWN -> CaptureResult.Status.COOLDOWN;
                    case DEBOUNCE -> CaptureResult.Status.DEBOUNCE;
                    case ACCEPT -> throw new IllegalStateException("Accepted trigger was rejected");
                });
            }

            IncidentId id = IncidentIds.next(clock);
            Instant createdAt = event.at();

            boolean wantReport = policy.artifacts().contains(BundleArtifacts.REPORT);
            HealthSnapshot snapshot = wantReport ? captureSnapshot() : null;

            Files.createDirectories(tempDir);
            Files.createDirectories(incidentDir);

            tempRecording = tempDir.resolve(id.value() + ".jfr");
            if (result.severity() == Severity.DEGRADED) {
                postIncidentWaiter.awaitResolution(event);
            }
            if (Thread.currentThread().isInterrupted()) {
                return CaptureResult.of(CaptureResult.Status.CANCELLED);
            }
            dumpedRecording = dumper.dump(tempRecording);
            if (Thread.currentThread().isInterrupted()) {
                return CaptureResult.of(CaptureResult.Status.CANCELLED);
            }

            if (wantReport) {
                snapshot = withHotThreads(snapshot, dumpedRecording);
            }
            List<DiagnosticSection> diagnostics = wantReport
                ? withModContribution(safeDiagnostics(), dumpedRecording)
                : List.of();
            if (wantReport) {
                diagnostics = StalledThread.prependTo(diagnostics, event, policy.frameworkPrefixes());
                diagnostics = DeadlockCycle.prependTo(diagnostics, event);
                diagnostics = MemoryPools.appendTo(diagnostics);
            }
            if (wantReport && policy.heapHistogram()) {
                diagnostics = HeapHistogram.appendTo(diagnostics);
            }
            if (wantReport && redactor.hasPatterns()) {
                diagnostics = redactDiagnostics(diagnostics);
            }
            IncidentReport report = buildReport(id, createdAt, result, event, snapshot, diagnostics);

            Path outputZip = incidentDir.resolve("incident-" + id.value() + ".zip");

            List<BundleAttachment> extras = List.of();
            try {
                extras = extrasProvider.extras(report, event);
            } catch (Exception e) {
                logger.log(System.Logger.Level.WARNING, "Bundle extras provider failed.", e);
            }

            if (redactor.hasPatterns()) {
                extras = redactExtras(extras);
            }
            if (Thread.currentThread().isInterrupted()) {
                return CaptureResult.of(CaptureResult.Status.CANCELLED);
            }

            bundleBuilder.build(report, dumpedRecording, outputZip, extras, policy.artifacts());
            bundled = true;

            try {
                retentionManager.enforce(incidentDir, policy.retention());
            } catch (Exception e) {
                logger.log(System.Logger.Level.WARNING, "Retention enforcement failed.", e);
            }

            try {
                notifier.onIncident(report, outputZip);
            } catch (Exception e) {
                logger.log(System.Logger.Level.WARNING, "Incident notification failed.", e);
            }

            return CaptureResult.captured(id);
        } catch (InterruptedException | java.nio.channels.ClosedByInterruptException e) {
            Thread.currentThread().interrupt();
            return CaptureResult.of(CaptureResult.Status.CANCELLED);
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted()) {
                return CaptureResult.of(CaptureResult.Status.CANCELLED);
            }
            logger.log(System.Logger.Level.WARNING, "Capture pipeline failed.", e);
            return CaptureResult.failed(e.getClass().getSimpleName());
        } finally {
            finishTemporaryRecording(tempRecording, bundled);
            if (dumpedRecording != null && !dumpedRecording.equals(tempRecording)
                && dumpedRecording.toAbsolutePath().normalize().getParent().equals(tempDir.toAbsolutePath().normalize())) {
                finishTemporaryRecording(dumpedRecording, bundled);
            }
        }
    }

    private void finishTemporaryRecording(Path recording, boolean bundled) {
        if (recording == null || !Files.isRegularFile(recording, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            if (bundled || Files.size(recording) == 0) {
                Files.deleteIfExists(recording);
            } else {
                setAsideRecording(tempDir.resolve("pending"), recording);
            }
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "Failed to preserve or clean temporary recording " + recording + ".", e);
        }
    }

    public void recoverTemporaryRecordings() {
        if (!policy.enabled() || Thread.currentThread().isInterrupted()) {
            return;
        }
        Path failedDir = tempDir.resolve(FAILED_DIR_NAME);
        recoverOrphans(failedDir, failedDir);
        recoverOrphans(tempDir.resolve("pending"), failedDir);
        recoverOrphans(tempDir, failedDir);
        if (!Thread.currentThread().isInterrupted()) {
            retentionManager.enforceStaging(tempDir, policy.retention());
        }
    }

    public void checkRecoveryStorage(List<Path> repositories, Path recoveryDir) {
        if (!policy.enabled() || Thread.currentThread().isInterrupted()) {
            return;
        }
        boolean stagingWithinBudget = retentionManager.enforceStaging(tempDir, policy.retention(), recoveryDir);
        boolean repositoryWithinBudget = retentionManager.checkRepositoryBudget(repositories, policy.retention());
        storagePressure = !stagingWithinBudget ? "Staging storage budget reached or unavailable"
            : !repositoryWithinBudget ? "Recovery repository budget reached or unavailable" : null;
        recoveryStorageWithinBudget = repositoryWithinBudget && stagingWithinBudget;
        storageChecked = true;
        stagingBudgetChecked.set(true);
    }

    public Optional<IncidentId> recoverFromRecording(Path recording, Instant occurredAt) {
        Objects.requireNonNull(recording, "recording");
        if (!policy.enabled() || Thread.currentThread().isInterrupted()
            || !Files.isRegularFile(recording, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try {
            try {
                JfrRepository.finalizeOrphan(recording);
            } catch (Exception e) {
                logger.log(System.Logger.Level.WARNING,
                    "Failed to finalize recovered recording " + recording + "; it may be unreadable.", e);
            }
            IncidentId id = IncidentIds.next(clock);
            Instant when = occurredAt != null ? occurredAt : clock.instant();
            boolean wantReport = policy.artifacts().contains(BundleArtifacts.REPORT);
            HealthSnapshot snapshot = wantReport ? JfrSnapshot.parse(recording, 1000) : null;
            List<DiagnosticSection> diagnostics = wantReport
                ? withModContribution(safeDiagnostics(), recording) : List.of();
            String threadDump = wantReport ? JfrThreadDump.lastDump(recording) : null;
            if (wantReport && threadDump != null) {
                DiagnosticSection stuck = JfrThreadDump.stuckSection(threadDump, policy.frameworkPrefixes());
                if (stuck != null) {
                    List<DiagnosticSection> withStuck = new ArrayList<>(diagnostics.size() + 1);
                    withStuck.add(stuck);
                    withStuck.addAll(diagnostics);
                    diagnostics = withStuck;
                }
            }
            if (wantReport && redactor.hasPatterns()) {
                diagnostics = redactDiagnostics(diagnostics);
            }
            IncidentMetadata meta = new IncidentMetadata(
                id, when, Severity.DEGRADED, "UNCLEAN_SHUTDOWN", null, "Recovered after an unclean shutdown");
            IncidentSummary summary = new IncidentSummary(
                "Unknown; the previous run did not shut down cleanly",
                List.of("The JVM exited without a clean shutdown; this bundle was rebuilt on the next startup from the "
                    + "rolling recording. The snapshot below is reconstructed from the recording's last samples."),
                List.of("Open recording.jfr in JDK Mission Control for the timeline leading up to the exit."));
            IncidentReport report = new IncidentReport(
                meta, summary, Map.of("recovered", "true"), snapshot, diagnostics);
            Files.createDirectories(incidentDir);
            Path outputZip = incidentDir.resolve("incident-" + id.value() + ".zip");
            List<BundleAttachment> extras = new ArrayList<>(extrasProvider.historicalExtras());
            extras.addAll(extrasProvider.configExtras());
            if (threadDump != null) {
                extras.add(new BundleAttachment("extras/threads.txt",
                    threadDump.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            }
            if (redactor.hasPatterns()) {
                extras = redactExtras(extras);
            }
            bundleBuilder.build(report, recording, outputZip, extras, policy.artifacts());
            try {
                retentionManager.enforce(incidentDir, policy.retention());
            } catch (Exception e) {
                logger.log(System.Logger.Level.WARNING, "Retention enforcement failed.", e);
            }
            try {
                notifier.onIncident(report, outputZip);
            } catch (Exception e) {
                logger.log(System.Logger.Level.WARNING, "Recovery notification failed.", e);
            }
            logger.log(System.Logger.Level.INFO, "Rebuilt incident bundle " + id.value() + " from a recovered recording.");
            return Optional.of(id);
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "Failed to rebuild bundle from recovered recording " + recording + ".", e);
            return Optional.empty();
        }
    }

    public List<IncidentId> recoverOrphans(Path dir) {
        Path failedDir = dir.resolve(FAILED_DIR_NAME);
        List<IncidentId> recovered = new ArrayList<>(recoverOrphans(failedDir, failedDir));
        recovered.addAll(recoverOrphans(dir, failedDir));
        if (policy.enabled() && !Thread.currentThread().isInterrupted()) {
            retentionManager.enforceStaging(dir, policy.retention());
        }
        return recovered;
    }

    private List<IncidentId> recoverOrphans(Path dir, Path failedDir) {
        Objects.requireNonNull(dir, "dir");
        if (!policy.enabled() || Thread.currentThread().isInterrupted()) {
            return List.of();
        }
        List<IncidentId> recovered = new ArrayList<>();
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return recovered;
        }
        List<Path> recordings;
        try (Stream<Path> stream = Files.list(dir)) {
            recordings = stream
                .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                .filter(p -> p.getFileName().toString().endsWith(".jfr"))
                .sorted()
                .toList();
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "Failed to scan " + dir + " for recovered recordings.", e);
            return recovered;
        }
        for (Path recording : recordings) {
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            Optional<IncidentId> id = recoverFromRecording(recording, lastModified(recording));
            if (id.isPresent()) {
                recovered.add(id.get());
                try {
                    Files.deleteIfExists(recording);
                } catch (Exception e) {
                    logger.log(System.Logger.Level.WARNING, "Failed to delete recovered recording " + recording + ".", e);
                }
            } else {
                setAsideRecording(failedDir, recording);
            }
        }
        return recovered;
    }

    private void setAsideRecording(Path failedDir, Path recording) {
        if (recording.getParent().equals(failedDir)) {
            return;
        }
        try {
            if (Files.exists(failedDir, LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(failedDir, LinkOption.NOFOLLOW_LINKS)) {
                throw new java.io.IOException("Not a real staging directory: " + failedDir);
            }
            Files.createDirectories(failedDir);
            Path target = failedDir.resolve(recording.getFileName().toString());
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                target = failedDir.resolve(java.util.UUID.randomUUID() + "-" + recording.getFileName());
            }
            Files.move(recording, target);
            logger.log(System.Logger.Level.WARNING,
                "Preserved recording " + recording + " at " + target + " for recovery or inspection.");
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING,
                "Failed to set aside unrecoverable recording " + recording + ".", e);
        }
    }


    private Instant lastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toInstant();
        } catch (Exception e) {
            return clock.instant();
        }
    }

    private List<DiagnosticSection> safeDiagnostics() {
        try {
            return diagnosticsProvider.sections();
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "Diagnostics provider failed.", e);
            return List.of();
        }
    }

    private IncidentReport buildReport(
        IncidentId id,
        Instant createdAt,
        TriggerResult result,
        TriggerEvent event,
        HealthSnapshot snapshot,
        List<DiagnosticSection> diagnostics
    ) {
        Map<String, String> context = event.attrs();
        if (redactor.hasPatterns()) {
            context = redactContext(context);
        }

        String headline = redactor.hasPatterns() ? redactor.redact(result.headline()) : result.headline();
        IncidentMetadata meta = new IncidentMetadata(
            id,
            createdAt,
            result.severity(),
            event.kind().name(),
            event.scope(),
            headline
        );

        String error = context.get("error");
        boolean hasError = error != null && !error.isBlank();
        List<String> whatHappened = new ArrayList<>();
        whatHappened.add("Triggered by " + event.kind().name());
        if (hasError) {
            whatHappened.add(error);
        }
        IncidentSummary summary = new IncidentSummary(
            hasError ? error : "Unknown",
            whatHappened,
            List.of("Review the incident report and recording.")
        );
        return new IncidentReport(meta, summary, context, snapshot, diagnostics);
    }

    private HealthSnapshot captureSnapshot() {
        try {
            WorldStatsProvider.Worlds worlds = worldStatsProvider.worlds();
            return new HealthSnapshot(
                worlds.targetTps(),
                HealthCollector.cpu(),
                HealthCollector.memory(),
                HealthCollector.gc(),
                HealthCollector.system(),
                HealthCollector.disk(),
                HealthCollector.threads(),
                worlds.worlds(),
                List.of()
            );
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "Health snapshot capture failed.", e);
            return null;
        }
    }

    private List<DiagnosticSection> withModContribution(List<DiagnosticSection> base, Path recording) {
        try {
            java.util.LinkedHashMap<String, String> mods = JfrHotThreads.modContribution(recording, 1000);
            if (mods.isEmpty()) {
                return base;
            }
            List<DiagnosticSection> out = new ArrayList<>(base.size() + 1);
            out.add(new DiagnosticSection("Mod hot-path contribution (JFR)", mods));
            out.addAll(base);
            return out;
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "JFR mod-attribution parse failed.", e);
            return base;
        }
    }

    private HealthSnapshot withHotThreads(HealthSnapshot snapshot, Path recording) {
        if (snapshot == null) {
            return null;
        }
        try {
            return new HealthSnapshot(
                snapshot.targetTps(),
                snapshot.cpu(),
                snapshot.memory(),
                snapshot.gc(),
                snapshot.system(),
                snapshot.disk(),
                snapshot.threads(),
                snapshot.worlds(),
                JfrHotThreads.parse(recording, 1000)
            );
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "JFR hot-thread parse failed.", e);
            return snapshot;
        }
    }

    private static final java.util.Set<String> TEXT_EXTENSIONS = java.util.Set.of(
        ".txt", ".json", ".html", ".log", ".properties", ".xml", ".yaml", ".yml", ".cfg"
    );

    private List<BundleAttachment> redactExtras(List<BundleAttachment> extras) {
        List<BundleAttachment> result = new ArrayList<>(extras.size());
        for (BundleAttachment extra : extras) {
            if (isTextPath(extra.pathInZip())) {
                result.add(new BundleAttachment(extra.pathInZip(), redactor.redact(extra.data())));
            } else {
                result.add(extra);
            }
        }
        return result;
    }

    private List<DiagnosticSection> redactDiagnostics(List<DiagnosticSection> sections) {
        List<DiagnosticSection> out = new ArrayList<>(sections.size());
        for (DiagnosticSection section : sections) {
            Map<String, String> entries = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : section.entries().entrySet()) {
                entries.put(entry.getKey(), redactor.redact(entry.getValue()));
            }
            String pre = section.preformatted() == null ? null : redactor.redact(section.preformatted());
            out.add(new DiagnosticSection(section.title(), entries, pre));
        }
        return out;
    }

    private Map<String, String> redactContext(Map<String, String> context) {
        Map<String, String> redacted = new LinkedHashMap<>(context.size());
        for (Map.Entry<String, String> entry : context.entrySet()) {
            redacted.put(entry.getKey(), redactor.redact(entry.getValue()));
        }
        return redacted;
    }

    private static boolean isTextPath(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        return TEXT_EXTENSIONS.contains(path.substring(dot).toLowerCase(java.util.Locale.ROOT));
    }
}
