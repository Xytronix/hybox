package io.github.xytronix.hybox.core.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.xytronix.hybox.core.bundle.BundleAttachment;
import io.github.xytronix.hybox.core.bundle.BundleBuilder;
import io.github.xytronix.hybox.core.env.TextRedactor;
import io.github.xytronix.hybox.core.health.HealthSnapshot;
import io.github.xytronix.hybox.core.health.JfrSnapshot;
import io.github.xytronix.hybox.core.incident.IncidentId;
import io.github.xytronix.hybox.core.jfr.JfrController;
import io.github.xytronix.hybox.core.retention.FileDeleter;
import io.github.xytronix.hybox.core.retention.RetentionManager;
import io.github.xytronix.hybox.core.retention.RetentionPolicy;
import io.github.xytronix.hybox.core.testutil.MutableClock;
import io.github.xytronix.hybox.core.trigger.TriggerEngine;
import io.github.xytronix.hybox.core.trigger.TriggerEvent;
import io.github.xytronix.hybox.core.trigger.TriggerKind;
import io.github.xytronix.hybox.core.trigger.TriggerPolicy;

class CapturePipelineTest {

    @Test
    void burstCooldownCreatesSingleZipThenAnotherAfterCooldown(@TempDir Path tempDir) throws Exception {
        Path incidentDir = tempDir.resolve("incidents");
        Path tempRecordings = tempDir.resolve("temp");
        MutableClock clock = new MutableClock(Instant.parse("2026-01-11T00:00:00Z"), ZoneOffset.UTC);

        TriggerPolicy policy = new TriggerPolicy(Duration.ofSeconds(30), Duration.ZERO, 1000, 5000, 100, 250);
        TriggerEngine engine = new TriggerEngine(clock, policy);
        CapturePipeline pipeline = new CapturePipeline(
            clock,
            engine,
            new FakeRecordingDumper(new byte[] {1, 2, 3}),
            new BundleBuilder(clock),
            new RetentionManager(clock, System.getLogger("retention-test"), FileDeleter.defaultDeleter()),
            IncidentNotifier.noop(),
            incidentDir,
            tempRecordings,
            new CapturePolicy(new RetentionPolicy(0, 0L, null)),
            System.getLogger("capture-test")
        );

        TriggerEvent event = new TriggerEvent(TriggerKind.MANUAL, "world", clock.instant(), Map.of());
        assertTrue(pipeline.handle(event).captured());
        for (int i = 1; i < 100; i++) {
            assertEquals(CaptureResult.Status.COOLDOWN, pipeline.handle(event).status());
        }

        assertEquals(1, countZips(incidentDir));

        clock.advance(Duration.ofSeconds(30));
        TriggerEvent laterEvent = new TriggerEvent(TriggerKind.MANUAL, "world", clock.instant(), Map.of());
        assertTrue(pipeline.handle(laterEvent).captured());

        assertEquals(2, countZips(incidentDir));
    }

    @Test
    void stallSeverityIsReflectedInIncidentJson(@TempDir Path tempDir) throws Exception {
        Path incidentDir = tempDir.resolve("incidents");
        Path tempRecordings = tempDir.resolve("temp");
        Clock clock = Clock.fixed(Instant.parse("2026-01-11T00:00:00Z"), ZoneOffset.UTC);

        TriggerPolicy policy = new TriggerPolicy(Duration.ZERO, Duration.ZERO, 2000, 6000, 100, 250);
        TriggerEngine engine = new TriggerEngine(clock, policy);
        CapturePipeline pipeline = new CapturePipeline(
            clock,
            engine,
            new FakeRecordingDumper(new byte[] {9, 9, 9}),
            new BundleBuilder(clock),
            new RetentionManager(clock, System.getLogger("retention-test"), FileDeleter.defaultDeleter()),
            IncidentNotifier.noop(),
            incidentDir,
            tempRecordings,
            new CapturePolicy(new RetentionPolicy(0, 0L, null)),
            System.getLogger("capture-test")
        );

        CaptureResult degradedId = pipeline.handle(new TriggerEvent(
            TriggerKind.HEARTBEAT_STALL,
            "world",
            clock.instant(),
            Map.of("stallMs", "2000")
        ));

        CaptureResult criticalId = pipeline.handle(new TriggerEvent(
            TriggerKind.HEARTBEAT_STALL,
            "world",
            clock.instant(),
            Map.of("stallMs", "6000")
        ));

        assertTrue(degradedId.captured());
        assertTrue(criticalId.captured());

        assertTrue(readSeverity(incidentDir, degradedId.incidentId()).contains("\"severity\":\"DEGRADED\""));
        assertTrue(readSeverity(incidentDir, criticalId.incidentId()).contains("\"severity\":\"CRITICAL\""));
    }

    @Test
    void contextAndTextExtrasAreRedacted(@TempDir Path tempDir) throws Exception {
        Path incidentDir = tempDir.resolve("incidents");
        Path tempRecordings = tempDir.resolve("temp");
        Clock clock = Clock.fixed(Instant.parse("2026-01-11T00:00:00Z"), ZoneOffset.UTC);

        TriggerPolicy triggerPolicy = new TriggerPolicy(Duration.ZERO, Duration.ZERO, 1000, 5000, 100, 250);
        TriggerEngine engine = new TriggerEngine(clock, triggerPolicy);
        CapturePipeline pipeline = new CapturePipeline(
            clock,
            engine,
            new FakeRecordingDumper(new byte[] {4, 5, 6}),
            new BundleBuilder(clock),
            new RetentionManager(clock, System.getLogger("retention-test"), FileDeleter.defaultDeleter()),
            IncidentNotifier.noop(),
            (report, event) -> List.of(new BundleAttachment(
                "extras/server-log.txt",
                "password=hunter2 ip=192.168.10.20 custom=CUSTOM_SECRET_ABC123".getBytes(StandardCharsets.UTF_8)
            )),
            incidentDir,
            tempRecordings,
            new CapturePolicy(
                new RetentionPolicy(0, 0L, null),
                true,
                true,
                0,
                Stream.concat(TextRedactor.DEFAULT_PATTERNS.stream(), Stream.of("CUSTOM_SECRET_[A-Z0-9]+")).toList()
            ),
            System.getLogger("capture-test")
        );

        CaptureResult id = pipeline.handle(new TriggerEvent(
            TriggerKind.MANUAL,
            "world",
            clock.instant(),
            Map.of("ip", "203.0.113.7", "custom", "CUSTOM_SECRET_ABC123")
        ));

        assertTrue(id.captured());
        Path zipPath = incidentDir.resolve("incident-" + id.incidentId().value() + ".zip");

        String json = readEntry(zipPath, "incident.json");
        assertTrue(json.contains("\"context\""));
        assertTrue(json.contains("[REDACTED]"));
        assertFalse(json.contains("203.0.113.7"));
        assertFalse(json.contains("CUSTOM_SECRET_ABC123"));

        String reportHtml = readEntry(zipPath, "report.html");
        assertTrue(reportHtml.contains("[REDACTED]"));
        assertFalse(reportHtml.contains("203.0.113.7"));
        assertFalse(reportHtml.contains("CUSTOM_SECRET_ABC123"));

        String logTail = readEntry(zipPath, "extras/server-log.txt");
        assertTrue(logTail.contains("[REDACTED]"));
        assertTrue(logTail.contains("hunter2"));
        assertFalse(logTail.contains("192.168.10.20"));
        assertFalse(logTail.contains("CUSTOM_SECRET_ABC123"));
    }

    @Test
    void recoverOrphansRebuildsBundleAndReconstructsSnapshot(@TempDir Path tempDir) throws Exception {
        Path incidentDir = tempDir.resolve("incidents");
        Path tempRecordings = tempDir.resolve("temp");
        Path recoverDir = tempDir.resolve("recover");
        Files.createDirectories(recoverDir);
        Clock clock = Clock.fixed(Instant.parse("2026-01-11T00:00:00Z"), ZoneOffset.UTC);

        Path recording = recoverDir.resolve("rolling-1.jfr");
        try (JfrController controller =
                 new JfrController(Duration.ofSeconds(60), 16L * 1024 * 1024, "hybox-recovery-test")) {
            controller.start();
            controller.dump(recording);
        }
        assertTrue(Files.size(recording) > 0);

        HealthSnapshot snapshot = JfrSnapshot.parse(recording, 50);
        assertNotNull(snapshot);
        assertNotNull(snapshot.cpu());
        assertNotNull(snapshot.memory());

        CapturePipeline pipeline = new CapturePipeline(
            clock,
            new TriggerEngine(clock, new TriggerPolicy(Duration.ofSeconds(30), Duration.ZERO, 1000, 5000, 100, 250)),
            new FakeRecordingDumper(new byte[] {1}),
            new BundleBuilder(clock),
            new RetentionManager(clock, System.getLogger("retention-test"), FileDeleter.defaultDeleter()),
            IncidentNotifier.noop(),
            incidentDir,
            tempRecordings,
            new CapturePolicy(new RetentionPolicy(0, 0L, null)),
            System.getLogger("recovery-test")
        );

        List<IncidentId> recovered = pipeline.recoverOrphans(recoverDir);

        assertEquals(1, recovered.size());
        assertEquals(1, countZips(incidentDir));
        assertFalse(Files.exists(recording));

        Path zip = incidentDir.resolve("incident-" + recovered.get(0).value() + ".zip");
        assertTrue(readEntry(zip, "incident.json").contains("UNCLEAN_SHUTDOWN"));
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            assertNotNull(zf.getEntry("report.html"));
            assertNotNull(zf.getEntry("recording.jfr"));
        }
    }

    @Test
    void recoverOrphansSetsAsideRecordingsItCannotBundle(@TempDir Path tempDir) throws Exception {
        Path incidentDir = tempDir.resolve("incidents");
        Files.writeString(incidentDir, "blocks directory creation");
        Path recoverDir = tempDir.resolve("recover");
        Files.createDirectories(recoverDir);
        Path recording = recoverDir.resolve("rolling-1.jfr");
        Files.write(recording, new byte[] {1, 2, 3});
        Clock clock = Clock.fixed(Instant.parse("2026-01-11T00:00:00Z"), ZoneOffset.UTC);

        CapturePipeline pipeline = new CapturePipeline(
            clock,
            new TriggerEngine(clock, new TriggerPolicy(Duration.ofSeconds(30), Duration.ZERO, 1000, 5000, 100, 250)),
            new FakeRecordingDumper(new byte[] {1}),
            new BundleBuilder(clock),
            new RetentionManager(clock, System.getLogger("retention-test"), FileDeleter.defaultDeleter()),
            IncidentNotifier.noop(),
            incidentDir,
            tempDir.resolve("temp"),
            new CapturePolicy(new RetentionPolicy(0, 0L, null)),
            System.getLogger("recovery-test")
        );

        List<IncidentId> recovered = pipeline.recoverOrphans(recoverDir);

        assertTrue(recovered.isEmpty());
        assertFalse(Files.exists(recording));
        assertTrue(Files.exists(recoverDir.resolve("failed").resolve("rolling-1.jfr")));
    }

    @Test
    void failedRecordingCapIsConfigurable(@TempDir Path tempDir) throws Exception {
        Path incidentDir = tempDir.resolve("incidents");
        Files.writeString(incidentDir, "block directory creation");
        Path recoverDir = tempDir.resolve("recover");
        Path failedDir = recoverDir.resolve("failed");
        Files.createDirectories(failedDir);
        for (int i = 0; i < 3; i++) {
            Path old = failedDir.resolve("old-" + i + ".jfr");
            Files.write(old, new byte[] {(byte) i});
            Files.setLastModifiedTime(old, FileTime.from(Instant.parse("2026-01-1" + i + "T00:00:00Z")));
        }
        Path recording = recoverDir.resolve("rolling-1.jfr");
        Files.write(recording, new byte[] {1, 2, 3});
        Files.setLastModifiedTime(recording, FileTime.from(Instant.parse("2026-01-20T00:00:00Z")));
        Clock clock = Clock.fixed(Instant.parse("2026-01-21T00:00:00Z"), ZoneOffset.UTC);

        CapturePipeline pipeline = new CapturePipeline(
            clock,
            new TriggerEngine(clock, new TriggerPolicy(Duration.ofSeconds(30), Duration.ZERO, 1000, 5000, 100, 250)),
            new FakeRecordingDumper(new byte[] {1}),
            new BundleBuilder(clock),
            new RetentionManager(clock, System.getLogger("retention-test"), FileDeleter.defaultDeleter()),
            IncidentNotifier.noop(),
            incidentDir,
            tempDir.resolve("temp"),
            new CapturePolicy(new RetentionPolicy(0, 0L, null, 2, 0L, null, 0L, null)),
            System.getLogger("recovery-test")
        );

        pipeline.recoverOrphans(recoverDir);

        try (Stream<Path> remaining = Files.list(failedDir)) {
            List<String> names = remaining.map(p -> p.getFileName().toString()).sorted().toList();
            assertEquals(List.of("old-2.jfr", "rolling-1.jfr"), names);
        }
    }

    @Test
    void disabledRecoveryLeavesAllRecordingsUntouched(@TempDir Path dir) throws Exception {
        Path recovery = Files.createDirectories(dir.resolve("recover"));
        Path recording = Files.write(recovery.resolve("rolling.jfr"), new byte[] {1, 2, 3});
        Path failed = Files.createDirectories(recovery.resolve("failed"));
        Path older = Files.write(failed.resolve("older.jfr"), new byte[] {4, 5, 6});
        var clock = Clock.systemUTC();
        var pipeline = new CapturePipeline(clock,
            new TriggerEngine(clock, new TriggerPolicy(Duration.ZERO, Duration.ZERO, 1000, 5000, 100, 250)),
            target -> { throw new AssertionError("Disabled recovery must not dump"); },
            new BundleBuilder(clock),
            new RetentionManager(clock, System.getLogger("retention-test"), FileDeleter.defaultDeleter()),
            IncidentNotifier.noop(), dir.resolve("incidents"), dir.resolve("temp"),
            new CapturePolicy(new RetentionPolicy(0, 0L, null, 1), false, false, 0),
            System.getLogger("disabled-recovery-test"));
        assertEquals(CaptureResult.Status.DISABLED,
            pipeline.handle(new TriggerEvent(TriggerKind.MANUAL, "world", clock.instant(), Map.of())).status());
        assertTrue(pipeline.recoverOrphans(recovery).isEmpty());
        assertTrue(Files.exists(recording), "Disabled capture must preserve pending recovery");
        assertTrue(Files.exists(older), "Disabled capture must not prune recovery evidence");
        assertFalse(Files.exists(dir.resolve("incidents")));
    }

    @Test
    void failedDumpPreservesNonemptyRecordingForStartupRecovery(@TempDir Path dir) throws Exception {
        Clock clock = Clock.systemUTC();
        CapturePipeline pipeline = failurePipeline(dir, clock, target -> {
            Files.write(target, new byte[] {1, 2, 3});
            throw new java.io.IOException("password=secret\n" + "private".repeat(100));
        });
        CaptureResult result = pipeline.handle(new TriggerEvent(TriggerKind.MANUAL, "world", clock.instant(), Map.of()));
        assertEquals(CaptureResult.Status.FAILED, result.status());
        assertNull(result.incidentId());
        assertEquals("IOException", result.detail());
        try (Stream<Path> pending = Files.list(dir.resolve("temp/pending"))) {
            Path recording = pending.findFirst().orElseThrow();
            assertEquals(3, Files.size(recording));
        }
        pipeline.recoverTemporaryRecordings();
        assertEquals(1, countZips(dir.resolve("incidents")));
        try (Stream<Path> pending = Files.list(dir.resolve("temp/pending"))) {
            assertEquals(0, pending.count());
        }
    }

    @Test
    void failedEmptyDumpIsRemoved(@TempDir Path dir) throws Exception {
        Clock clock = Clock.systemUTC();
        CapturePipeline pipeline = failurePipeline(dir, clock, target -> {
            Files.createFile(target);
            throw new java.io.IOException("dump interrupted");
        });
        assertEquals(CaptureResult.Status.FAILED,
            pipeline.handle(new TriggerEvent(TriggerKind.MANUAL, "world", clock.instant(), Map.of())).status());
        try (Stream<Path> files = Files.list(dir.resolve("temp"))) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void bundleFailurePreservesDumpUntilRecoverySucceeds(@TempDir Path dir) throws Exception {
        Clock clock = Clock.systemUTC();
        CapturePipeline pipeline = failurePipeline(dir, clock, target -> {
            Files.write(target, new byte[] {1, 2, 3});
            Files.delete(dir.resolve("incidents"));
            Files.writeString(dir.resolve("incidents"), "blocks bundling");
            return target;
        });
        assertEquals(CaptureResult.Status.FAILED,
            pipeline.handle(new TriggerEvent(TriggerKind.MANUAL, "world", clock.instant(), Map.of())).status());
        try (Stream<Path> files = Files.list(dir.resolve("temp"))) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".jfr")));
        }
        Files.delete(dir.resolve("incidents"));
        pipeline.recoverTemporaryRecordings();
        assertEquals(1, countZips(dir.resolve("incidents")));
    }

    @Test
    void protectedPendingBudgetRefusesNewCaptureWithoutDeletingEvidence(@TempDir Path dir) throws Exception {
        Clock clock = Clock.systemUTC();
        Path pending = Files.createDirectories(dir.resolve("temp/pending"));
        Path evidence = Files.write(pending.resolve("pending.jfr"), new byte[] {1, 2, 3, 4});
        CapturePipeline pipeline = new CapturePipeline(clock,
            new TriggerEngine(clock, new TriggerPolicy(Duration.ZERO, Duration.ZERO, 1000, 5000, 100, 250)),
            target -> { throw new AssertionError("Over-budget staging must not dump"); },
            new BundleBuilder(clock),
            new RetentionManager(clock, System.getLogger("retention-test"), FileDeleter.defaultDeleter()),
            IncidentNotifier.noop(), dir.resolve("incidents"), dir.resolve("temp"),
            new CapturePolicy(new RetentionPolicy(0, 0L, null, 5, 4L, Duration.ofDays(7), 0L, null)),
            System.getLogger("capture-test"));
        assertNull(pipeline.storagePressure());
        assertFalse(pipeline.storageChecked());
        CaptureResult result = pipeline.handle(new TriggerEvent(TriggerKind.MANUAL, "world", clock.instant(), Map.of()));
        assertTrue(pipeline.storageChecked());
        assertEquals(CaptureResult.Status.STORAGE_PRESSURE, result.status());
        assertNotNull(pipeline.storagePressure());
        assertEquals(pipeline.storagePressure(), result.detail());
        assertEquals(4, Files.size(evidence));
    }

    private static CapturePipeline failurePipeline(Path dir, Clock clock, RecordingDumper dumper) {
        return new CapturePipeline(clock,
            new TriggerEngine(clock, new TriggerPolicy(Duration.ZERO, Duration.ZERO, 1000, 5000, 100, 250)),
            dumper, new BundleBuilder(clock),
            new RetentionManager(clock, System.getLogger("retention-test"), FileDeleter.defaultDeleter()),
            IncidentNotifier.noop(), dir.resolve("incidents"), dir.resolve("temp"),
            new CapturePolicy(new RetentionPolicy(0, 0L, null), true, false, 0,
                List.of(), java.util.Set.of(io.github.xytronix.hybox.core.bundle.BundleArtifacts.JFR)),
            System.getLogger("capture-test"));
    }

    @Test
    void interruptedCaptureNeverInvokesDumper(@TempDir Path dir) throws Exception {
        Clock clock = Clock.systemUTC();
        CapturePipeline pipeline = failurePipeline(dir, clock, target -> {
            throw new AssertionError("Cancelled capture must not dump");
        });
        try {
            Thread.currentThread().interrupt();
            assertEquals(CaptureResult.Status.CANCELLED,
                pipeline.handle(new TriggerEvent(TriggerKind.MANUAL, "world", clock.instant(), Map.of())).status());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void interruptedFileChannelPreservesEvidenceWithoutReportingCaptureFailure(@TempDir Path dir) throws Exception {
        List<Throwable> reportedFailures = new java.util.ArrayList<>();
        System.Logger logger = new System.Logger() {
            public String getName() { return "cancellation-test"; }
            public boolean isLoggable(Level level) { return true; }
            public void log(Level level, java.util.ResourceBundle bundle, String message, Throwable thrown) {
                if (thrown != null) {
                    reportedFailures.add(thrown);
                }
            }
            public void log(Level level, java.util.ResourceBundle bundle, String format, Object... params) {}
        };
        Clock clock = Clock.systemUTC();
        RecordingDumper dumper = target -> {
            try (var channel = java.nio.channels.FileChannel.open(target,
                java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE)) {
                channel.write(java.nio.ByteBuffer.wrap(new byte[] {1, 2, 3}));
                Thread.currentThread().interrupt();
                channel.write(java.nio.ByteBuffer.wrap(new byte[] {4}));
            }
            throw new AssertionError("Interrupted channel write must abort");
        };
        CapturePipeline pipeline = new CapturePipeline(clock,
            new TriggerEngine(clock, new TriggerPolicy(Duration.ZERO, Duration.ZERO, 1000, 5000, 100, 250)),
            dumper, new BundleBuilder(clock),
            new RetentionManager(clock, logger, FileDeleter.defaultDeleter()),
            IncidentNotifier.noop(), dir.resolve("incidents"), dir.resolve("temp"),
            new CapturePolicy(new RetentionPolicy(0, 0L, null)), logger);
        try {
            assertEquals(CaptureResult.Status.CANCELLED,
                pipeline.handle(new TriggerEvent(TriggerKind.MANUAL, "world", clock.instant(), Map.of())).status());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertTrue(reportedFailures.isEmpty(), "Cancellation must not be reported as a capture failure");
        try (Stream<Path> pending = Files.list(dir.resolve("temp/pending"))) {
            assertEquals(3, Files.size(pending.findFirst().orElseThrow()));
        }
    }

    @Test
    void debounceRejectsOnlyTheRepeatedScopeUntilItExpires(@TempDir Path dir) throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-11T00:00:00Z"), ZoneOffset.UTC);
        CapturePipeline pipeline = new CapturePipeline(clock,
            new TriggerEngine(clock, new TriggerPolicy(Duration.ZERO, Duration.ofSeconds(30), 1000, 5000, 100, 250)),
            new FakeRecordingDumper(new byte[] {1}), new BundleBuilder(clock),
            new RetentionManager(clock, System.getLogger("retention-test"), FileDeleter.defaultDeleter()),
            IncidentNotifier.noop(), dir.resolve("incidents"), dir.resolve("temp"),
            new CapturePolicy(new RetentionPolicy(0, 0L, null), true, false, 0,
                List.of(), java.util.Set.of(io.github.xytronix.hybox.core.bundle.BundleArtifacts.JFR)),
            System.getLogger("capture-test"));
        TriggerEvent event = new TriggerEvent(TriggerKind.MANUAL, "world", clock.instant(), Map.of());
        assertTrue(pipeline.handle(event).captured());
        assertEquals(CaptureResult.Status.DEBOUNCE, pipeline.handle(event).status());
        assertTrue(pipeline.handle(new TriggerEvent(TriggerKind.MANUAL, "other", clock.instant(), Map.of())).captured());
        clock.advance(Duration.ofSeconds(30));
        assertTrue(pipeline.handle(new TriggerEvent(TriggerKind.MANUAL, "world", clock.instant(), Map.of())).captured());
        assertEquals(3, countZips(dir.resolve("incidents")));
    }

    @Test
    void repositoryPressureRemainsCachedUntilStorageIsRechecked(@TempDir Path dir) throws Exception {
        Clock clock = Clock.systemUTC();
        Path repository = Files.createDirectories(dir.resolve("repository"));
        Path evidence = Files.write(repository.resolve("chunk.jfr"), new byte[] {1, 2, 3, 4});
        CapturePipeline pipeline = new CapturePipeline(clock,
            new TriggerEngine(clock, new TriggerPolicy(Duration.ZERO, Duration.ZERO, 1000, 5000, 100, 250)),
            new FakeRecordingDumper(new byte[] {1}), new BundleBuilder(clock),
            new RetentionManager(clock, System.getLogger("retention-test"), FileDeleter.defaultDeleter()),
            IncidentNotifier.noop(), dir.resolve("incidents"), dir.resolve("temp"),
            new CapturePolicy(new RetentionPolicy(0, 0L, null, 5, 0L, null, 4L, null), true, false, 0,
                List.of(), java.util.Set.of(io.github.xytronix.hybox.core.bundle.BundleArtifacts.JFR)),
            System.getLogger("capture-test"));
        assertFalse(pipeline.storageChecked());
        pipeline.checkRecoveryStorage(List.of(repository), dir.resolve("recover"));
        assertTrue(pipeline.storageChecked());
        String pressure = pipeline.storagePressure();
        assertNotNull(pressure);
        assertEquals(CaptureResult.Status.STORAGE_PRESSURE,
            pipeline.handle(new TriggerEvent(TriggerKind.MANUAL, "world", clock.instant(), Map.of())).status());
        assertEquals(4, Files.size(evidence));
        Files.delete(evidence);
        assertEquals(pressure, pipeline.storagePressure());
        pipeline.checkRecoveryStorage(List.of(repository), dir.resolve("recover"));
        assertTrue(pipeline.storageChecked());
        assertNull(pipeline.storagePressure());
        assertTrue(pipeline.handle(new TriggerEvent(TriggerKind.MANUAL, "world", clock.instant(), Map.of())).captured());
    }

    @Test
    void cancellationAfterDumpPreservesEvidenceInsteadOfPublishingBundle(@TempDir Path dir) throws Exception {
        Clock clock = Clock.systemUTC();
        CapturePipeline pipeline = failurePipeline(dir, clock, target -> {
            Files.write(target, new byte[] {1, 2, 3});
            Thread.currentThread().interrupt();
            return target;
        });
        try {
            assertEquals(CaptureResult.Status.CANCELLED,
                pipeline.handle(new TriggerEvent(TriggerKind.MANUAL, "world", clock.instant(), Map.of())).status());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertEquals(0, countZips(dir.resolve("incidents")));
        try (Stream<Path> pending = Files.list(dir.resolve("temp/pending"))) {
            assertEquals(3, Files.size(pending.findFirst().orElseThrow()));
        }
    }

    private static int countZips(Path incidentDir) throws Exception {
        if (!Files.exists(incidentDir)) {
            return 0;
        }
        try (Stream<Path> stream = Files.list(incidentDir)) {
            return (int) stream.filter(path -> path.getFileName().toString().endsWith(".zip")).count();
        }
    }

    private static String readSeverity(Path incidentDir, IncidentId id) throws Exception {
        Path zipPath = incidentDir.resolve("incident-" + id.value() + ".zip");
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            ZipEntry entry = zip.getEntry("incident.json");
            byte[] bytes = zip.getInputStream(entry).readAllBytes();
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static String readEntry(Path zipPath, String entryName) throws Exception {
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            ZipEntry entry = zip.getEntry(entryName);
            assertNotNull(entry, "Expected zip entry: " + entryName);
            byte[] bytes = zip.getInputStream(entry).readAllBytes();
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private record FakeRecordingDumper(byte[] bytes) implements RecordingDumper {

        @Override
            public Path dump(Path target) throws Exception {
                Files.createDirectories(target.getParent());
                Files.write(target, bytes);
                return target;
            }
        }
}
