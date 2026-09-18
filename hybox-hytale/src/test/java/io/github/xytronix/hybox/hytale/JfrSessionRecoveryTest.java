package io.github.xytronix.hybox.hytale;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.xytronix.hybox.core.bundle.BundleArtifacts;
import io.github.xytronix.hybox.core.bundle.BundleBuilder;
import io.github.xytronix.hybox.core.capture.CapturePipeline;
import io.github.xytronix.hybox.core.capture.CapturePolicy;
import io.github.xytronix.hybox.core.capture.CaptureResult;
import io.github.xytronix.hybox.core.capture.IncidentNotifier;
import io.github.xytronix.hybox.core.retention.FileDeleter;
import io.github.xytronix.hybox.core.retention.RetentionManager;
import io.github.xytronix.hybox.core.retention.RetentionPolicy;
import io.github.xytronix.hybox.core.trigger.TriggerEngine;
import io.github.xytronix.hybox.core.trigger.TriggerPolicy;

class JfrSessionRecoveryTest {
    @TempDir Path dir;

    @Test
    void disabledCapturePreservesPreviousRepository() throws Exception {
        Path previous = repository();
        assertFalse(harvest(previous, pipeline(false, false)));
        assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(previous.resolve("chunk.jfr")));
    }

    @Test
    void failedBundlePreservesPreviousRepositoryAndStagedRecording() throws Exception {
        Path previous = repository();
        assertFalse(harvest(previous, pipeline(true, true)));
        assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(previous.resolve("chunk.jfr")));
        try (var files = Files.list(dir.resolve("recover"))) {
            assertTrue(files.anyMatch(file -> file.getFileName().toString().startsWith("harvested-")));
        }
    }

    @Test
    void successfulHarvestDoesNotDeleteUnrelatedRepositoryFiles() throws Exception {
        Path previous = repository();
        Path unrelated = Files.writeString(previous.resolve("keep.txt"), "not a recording");
        assertTrue(harvest(previous, pipeline(true, false)));
        assertEquals("not a recording", Files.readString(unrelated));
        assertFalse(Files.exists(previous.resolve("chunk.jfr")));
    }

    @Test
    void liveRepositoryIsNeverHarvested(@TempDir Path base) throws Exception {
        Path active = Files.createDirectories(base.resolve("2026_09_18_00_00_00_" + ProcessHandle.current().pid()));
        Path chunk = Files.write(active.resolve("chunk.jfr"), new byte[] {1, 2, 3});
        var recovery = new JfrSessionRecovery(Clock.systemUTC(), System.getLogger("recovery-test"),
            dir.resolve("rolling.jfr"), dir.resolve("recover"), dir.resolve("pointer"), base);
        assertFalse(invokeHarvest(recovery, active, pipeline(true, false)));
        assertEquals(3, Files.size(chunk));
        assertFalse(Files.exists(dir.resolve("recover")));
    }

    @Test
    void repositoryPointerOutsideConfiguredBaseIsNeverHarvested(@TempDir Path external) throws Exception {
        Path previous = Files.createDirectories(external.resolve("2026_09_18_00_00_00_2147483647"));
        Path chunk = Files.write(previous.resolve("chunk.jfr"), new byte[] {1, 2, 3});
        assertFalse(harvest(previous, pipeline(true, false)));
        assertEquals(3, Files.size(chunk));
    }

    @Test
    void failedRepositoryPointerSurvivesStartupAndIsRetried() throws Exception {
        Path previous = repository();
        Path pointer = dir.resolve("pointer");
        Files.writeString(pointer, previous.toString());
        var recovery = new JfrSessionRecovery(Clock.systemUTC(), System.getLogger("recovery-test"),
            dir.resolve("rolling.jfr"), dir.resolve("recover"), pointer, dir);
        CapturePipeline pipeline = pipeline(true, true);
        try (var worker = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            recovery.recoverOrphanedRecordings(false, () -> pipeline, worker);
            worker.shutdown();
            assertTrue(worker.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));
        }
        assertTrue(Files.readAllLines(pointer).contains(previous.toString()));
        assertEquals(3, Files.size(previous.resolve("chunk.jfr")));
        Files.delete(dir.resolve("incidents"));
        CapturePipeline retry = pipeline(true, false);
        try (var worker = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            recovery.recoverOrphanedRecordings(false, () -> retry, worker);
            worker.shutdown();
            assertTrue(worker.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));
        }
        assertFalse(Files.readAllLines(pointer).contains(previous.toString()));
        assertFalse(Files.exists(previous.resolve("chunk.jfr")));
    }

    @Test
    void emptyRepositoryStillRecoversRollingSnapshot() throws Exception {
        Path previous = repository();
        Files.delete(previous.resolve("chunk.jfr"));
        Files.writeString(dir.resolve("pointer"), previous.toString());
        Files.write(dir.resolve("rolling.jfr"), new byte[] {1, 2, 3});
        var recovery = new JfrSessionRecovery(Clock.systemUTC(), System.getLogger("recovery-test"),
            dir.resolve("rolling.jfr"), dir.resolve("recover"), dir.resolve("pointer"), dir);
        CapturePipeline pipeline = pipeline(true, false);
        try (var worker = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            recovery.recoverOrphanedRecordings(true, () -> pipeline, worker);
            worker.shutdown();
            assertTrue(worker.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));
        }
        assertFalse(Files.exists(dir.resolve("rolling.jfr")));
        assertFalse(Files.readAllLines(dir.resolve("pointer")).contains(previous.toString()));
        try (var incidents = Files.list(dir.resolve("incidents"))) {
            assertEquals(1, incidents.filter(path -> path.toString().endsWith(".zip")).count());
        }
    }

    @Test
    void nextStartupRetriesFailedRecoveryQuarantine() throws Exception {
        Path recoverDir = Files.createDirectories(dir.resolve("recover"));
        Files.write(recoverDir.resolve("rolling.jfr"), new byte[] {1, 2, 3});
        CapturePipeline failed = pipeline(true, true);
        assertTrue(failed.recoverOrphans(recoverDir).isEmpty());
        Path quarantined = recoverDir.resolve("failed/rolling.jfr");
        assertTrue(Files.exists(quarantined));
        Files.delete(dir.resolve("incidents"));
        CapturePipeline retry = pipeline(true, false);
        var recovery = new JfrSessionRecovery(Clock.systemUTC(), System.getLogger("recovery-test"),
            dir.resolve("rolling.jfr"), recoverDir, dir.resolve("pointer"), dir);
        try (var worker = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            recovery.recoverOrphanedRecordings(false, () -> retry, worker);
            worker.shutdown();
            assertTrue(worker.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));
        }
        assertFalse(Files.exists(quarantined));
        try (var incidents = Files.list(dir.resolve("incidents"))) {
            assertEquals(1, incidents.filter(path -> path.toString().endsWith(".zip")).count());
        }
    }

    @Test
    void failedSnapshotStagingFailsCompletionAndPreservesRecording() throws Exception {
        Path rolling = Files.write(dir.resolve("rolling.jfr"), new byte[] {1, 2, 3});
        Files.writeString(dir.resolve("recover"), "blocks staging directory creation");
        var recovery = new JfrSessionRecovery(Clock.systemUTC(), System.getLogger("recovery-test"),
            rolling, dir.resolve("recover"), dir.resolve("pointer"), dir);
        CapturePipeline pipeline = pipeline(true, false);
        try (var worker = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var completion = recovery.recoverOrphanedRecordings(true, () -> pipeline, worker);
            assertThrows(java.util.concurrent.ExecutionException.class,
                () -> completion.get(10, java.util.concurrent.TimeUnit.SECONDS));
        }
        assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(rolling));
    }

    @Test
    void disabledRecoveryLeavesRollingSnapshotForInspection() throws Exception {
        Path rolling = Files.write(dir.resolve("rolling.jfr"), new byte[] {1, 2, 3});
        var recovery = new JfrSessionRecovery(Clock.systemUTC(), System.getLogger("recovery-test"),
            rolling, dir.resolve("recover"), dir.resolve("pointer"), dir);
        CapturePipeline pipeline = pipeline(false, false);
        try (var worker = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            recovery.recoverOrphanedRecordings(true, () -> pipeline, worker)
                .get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(rolling));
        assertFalse(Files.exists(dir.resolve("recover")));
    }

    @Test
    void repositoryPressureSurvivesPipelineReplacementAndClearsAfterCleanup() throws Exception {
        Path previous = repository();
        Files.writeString(dir.resolve("pointer"), previous.toString());
        var recovery = new JfrSessionRecovery(Clock.systemUTC(), System.getLogger("recovery-test"),
            dir.resolve("rolling.jfr"), dir.resolve("recover"), dir.resolve("pointer"), dir);
        CapturePipeline original = admissionPipeline();
        recovery.recheckStorageBudgets(original);
        assertEquals(CaptureResult.Status.STORAGE_PRESSURE, original.handle(manualEvent()).status());
        CapturePipeline reloaded = admissionPipeline();
        recovery.recheckStorageBudgets(reloaded);
        assertEquals(CaptureResult.Status.STORAGE_PRESSURE, reloaded.handle(manualEvent()).status());
        assertEquals(3, Files.size(previous.resolve("chunk.jfr")));
        Files.delete(previous.resolve("chunk.jfr"));
        recovery.recheckStorageBudgets(reloaded);
        assertEquals(CaptureResult.Status.CAPTURED, reloaded.handle(manualEvent()).status());
    }

    @Test
    void recoveryStagingPressureBlocksCaptureWithoutDeletingPendingRecording() throws Exception {
        Path pending = Files.createDirectories(dir.resolve("recover"));
        Path recording = Files.write(pending.resolve("pending.jfr"), new byte[] {1, 2, 3});
        var recovery = new JfrSessionRecovery(Clock.systemUTC(), System.getLogger("recovery-test"),
            dir.resolve("rolling.jfr"), pending, dir.resolve("pointer"), dir);
        CapturePipeline pipeline = admissionPipeline();
        recovery.recheckStorageBudgets(pipeline);
        assertEquals(CaptureResult.Status.STORAGE_PRESSURE, pipeline.handle(manualEvent()).status());
        assertEquals(3, Files.size(recording));
        Files.delete(recording);
        recovery.recheckStorageBudgets(pipeline);
        assertEquals(CaptureResult.Status.CAPTURED, pipeline.handle(manualEvent()).status());
    }

    @Test
    void activeRepositoryDoesNotConsumeRetainedRepositoryBudget() throws Exception {
        Path active = Files.createDirectories(dir.resolve("2026_09_18_00_00_00_" + ProcessHandle.current().pid()));
        Files.write(active.resolve("chunk.jfr"), new byte[] {1, 2, 3});
        Files.writeString(dir.resolve("pointer"), active.toString());
        var recovery = new JfrSessionRecovery(Clock.systemUTC(), System.getLogger("recovery-test"),
            dir.resolve("rolling.jfr"), dir.resolve("recover"), dir.resolve("pointer"), dir);
        CapturePipeline pipeline = admissionPipeline();
        recovery.recheckStorageBudgets(pipeline);
        assertEquals(CaptureResult.Status.CAPTURED, pipeline.handle(manualEvent()).status());
        assertEquals(3, Files.size(active.resolve("chunk.jfr")));
    }

    private CapturePipeline admissionPipeline() {
        Clock clock = Clock.systemUTC();
        return new CapturePipeline(clock,
            new TriggerEngine(clock, new TriggerPolicy(Duration.ZERO, Duration.ZERO, 1000, 5000, 100, 250)),
            target -> Files.write(target, new byte[] {1}), new BundleBuilder(clock),
            new RetentionManager(clock, System.getLogger("recovery-test"), FileDeleter.defaultDeleter()),
            IncidentNotifier.noop(), dir.resolve("incidents"), dir.resolve("temp"),
            new CapturePolicy(new RetentionPolicy(0, 0L, null, 5, 2L, Duration.ofDays(7), 2L, Duration.ofDays(7)),
                true, false, 0, List.of(), Set.of(BundleArtifacts.JFR)),
            System.getLogger("recovery-test"));
    }

    private io.github.xytronix.hybox.core.trigger.TriggerEvent manualEvent() {
        return new io.github.xytronix.hybox.core.trigger.TriggerEvent(
            io.github.xytronix.hybox.core.trigger.TriggerKind.MANUAL, "world", java.time.Instant.now(), java.util.Map.of());
    }

    private Path repository() throws Exception {
        Path previous = Files.createDirectories(dir.resolve("2026_09_18_00_00_00_2147483647"));
        Files.write(previous.resolve("chunk.jfr"), new byte[] {1, 2, 3});
        return previous;
    }

    private CapturePipeline pipeline(boolean enabled, boolean fail) throws Exception {
        Path incidents = dir.resolve("incidents");
        if (fail) {
            Files.writeString(incidents, "blocks creation of the incident directory");
        }
        Clock clock = Clock.systemUTC();
        return new CapturePipeline(clock,
            new TriggerEngine(clock, new TriggerPolicy(Duration.ZERO, Duration.ZERO, 1000, 5000, 100, 250)),
            target -> target, new BundleBuilder(clock),
            new RetentionManager(clock, System.getLogger("recovery-test"), FileDeleter.defaultDeleter()),
            IncidentNotifier.noop(), incidents, dir.resolve("temp"),
            new CapturePolicy(new RetentionPolicy(0, 0L, null), enabled, false, 0, List.of(), Set.of(BundleArtifacts.JFR)),
            System.getLogger("recovery-test"));
    }

    private boolean harvest(Path previous, CapturePipeline pipeline) throws Exception {
        var recovery = new JfrSessionRecovery(Clock.systemUTC(), System.getLogger("recovery-test"),
            dir.resolve("rolling.jfr"), dir.resolve("recover"), dir.resolve("pointer"), dir);
        return invokeHarvest(recovery, previous, pipeline);
    }

    private boolean invokeHarvest(JfrSessionRecovery recovery, Path previous, CapturePipeline pipeline) throws Exception {
        var method = JfrSessionRecovery.class.getDeclaredMethod("harvestPreviousRepository", String.class, Supplier.class);
        method.setAccessible(true);
        return (boolean) method.invoke(recovery, previous.toString(), (Supplier<CapturePipeline>) () -> pipeline);
    }
}
