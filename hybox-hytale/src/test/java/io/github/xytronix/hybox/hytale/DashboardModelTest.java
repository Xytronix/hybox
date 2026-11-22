package io.github.xytronix.hybox.hytale;

import static org.junit.jupiter.api.Assertions.*;

import io.github.xytronix.hybox.core.capture.CapturePolicy;
import io.github.xytronix.hybox.core.capture.CaptureResult;
import io.github.xytronix.hybox.core.incident.IncidentId;
import io.github.xytronix.hybox.core.retention.RetentionPolicy;
import io.github.xytronix.hybox.core.retention.RetentionUsage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DashboardModelTest {
    private static final Instant START = Instant.parse("2026-10-22T12:00:00Z");
    private static final long MIB = 1024L * 1024;
    private static final long GIB = 1024 * MIB;
    static final RetentionPolicy POLICY = new RetentionPolicy(25, GIB, Duration.ofDays(7));
    static final RetentionUsage STORAGE = new RetentionUsage(4, 40 * MIB, 0, 100 * GIB, 500 * GIB, POLICY);

    @Test
    void profileProgressAndCountdownClampAtBoundaries() {
        var total = Duration.ofMinutes(5);
        var zero = new DashboardModel.Profile(START, total, START, "Operator");
        assertEquals(0f, zero.fraction());
        assertEquals(300, zero.remainingSeconds());
        assertEquals("Operator", zero.requesterName());
        assertEquals(START.plusSeconds(300), zero.captureAt());

        var middle = new DashboardModel.Profile(START, total, START.plusSeconds(150), null);
        assertEquals(0.5f, middle.fraction());
        assertEquals(150, middle.remainingSeconds());

        var end = new DashboardModel.Profile(START, total, START.plusSeconds(300), null);
        assertEquals(1f, end.fraction());
        assertEquals(0, end.remainingSeconds());

        var overrun = new DashboardModel.Profile(START, total, START.plusSeconds(301), null);
        assertEquals(1f, overrun.fraction());
        assertEquals(0, overrun.remainingSeconds());
        assertEquals(0f, new DashboardModel.Profile(START, total, START.minusSeconds(1), null).fraction());
        assertEquals(1, new DashboardModel.Profile(START, total, START.plusMillis(299_999), null).remainingSeconds());
        assertEquals(1f, new DashboardModel.Profile(START, Duration.ZERO, START, null).fraction());
    }

    @Test
    void healthySnapshotHidesIdleCaptureAndQuietCallbackTiles() {
        var model = model(health(true, null, null, null, false,
            new CallbackExecutor.Health(10, 0, 0, 0, 0, 0, null)), "INFO");

        assertEquals(List.of("Recorder", "Bundles", "Last incident", "Storage"),
            model.tiles().stream().map(DashboardModel.Tile::label).toList());
        assertEquals(List.of(DashboardModel.Tone.OK, DashboardModel.Tone.OK,
            DashboardModel.Tone.NEUTRAL, DashboardModel.Tone.OK),
            model.tiles().stream().map(DashboardModel.Tile::tone).toList());
        assertTrue(model.warnings().isEmpty());
        assertNull(model.captureStep());
        assertTrue(model.captureAllowed());
        assertEquals("Saved incident bundles", model.tiles().get(1).detail());
        assertEquals("1 min ago", model.tiles().get(2).value());
        assertEquals("INFO", model.tiles().get(2).severity());
        assertEquals("Healthy", model.tiles().get(3).value());
        assertEquals("40 MiB of 1 GiB in bundles, 100 GiB free on disk", model.tiles().get(3).detail());
    }

    @Test
    void captureTileShowsAFailedCaptureAndCallbackTileShowsOnlyProblems() {
        var failed = model(health(true, null, "disk full", null, false,
            new CallbackExecutor.Health(10, 0, 0, 0, 0, 2, null)), "INFO");
        assertEquals(List.of("Recorder", "Capture", "Bundles", "Last incident", "Storage"),
            failed.tiles().stream().map(DashboardModel.Tile::label).toList());
        assertEquals("Failed", failed.tiles().get(1).value());
        assertEquals(DashboardModel.Tone.ERROR, failed.tiles().get(1).tone());
        assertTrue(failed.warnings().stream().anyMatch(warning -> warning.text().startsWith("A plugin callback")),
            "Both callback slots busy still raises the banner");
        var timedOut = model(health(true, null, null, null, false,
            new CallbackExecutor.Health(10, 0, 1, 0, 0, 0, null)), "INFO");
        assertEquals("Callbacks", timedOut.tiles().getLast().label());
        assertEquals("1 problem", timedOut.tiles().getLast().value());
    }

    @Test
    void storageTileReportsLowDiskEvenWhenBundlesAreWithinTheirLimit() {
        var health = health(true, null, null, null, false, new CallbackExecutor.Health(0, 0, 0, 0, 0, 0, null));
        var lowDisk = new RetentionUsage(16, 640 * MIB, 0, 1536 * MIB, 500 * GIB, POLICY);

        var tile = DashboardModel.storageTile(health, lowDisk);

        assertEquals("Low disk space", tile.value());
        assertEquals(DashboardModel.Tone.WARN, tile.tone());
        assertEquals("640 MiB of 1 GiB in bundles, 1.5 GiB free on disk", tile.detail());
        var model = model(health, lowDisk, "INFO");
        assertTrue(model.warnings().stream().anyMatch(warning -> warning.text().equals(
            "Low disk space: 1.5 GiB free. Captures can fail to write.")));
    }

    @Test
    void storageTileWarnsNearTheBundleSizeLimitAndListsBundlesDueForRemoval() {
        var health = health(true, null, null, null, false, new CallbackExecutor.Health(0, 0, 0, 0, 0, 0, null));
        var full = new RetentionUsage(16, 950 * MIB, 15, 100 * GIB, 500 * GIB, POLICY);

        var model = model(health, full, "INFO");

        assertEquals("Near limit", model.tiles().get(3).value());
        assertEquals(DashboardModel.Tone.WARN, model.tiles().get(3).tone());
        assertTrue(model.warnings().stream().anyMatch(warning -> warning.text().equals(
            "15 bundles older than 7 days are removed after the next capture.")));
    }

    @Test
    void oldCriticalIncidentAgeIsNeutralWhileTheSeverityStaysCritical() {
        var model = model(health(true, null, null, null, false, new CallbackExecutor.Health(0, 0, 0, 0, 0, 0, null)),
            "CRITICAL");

        assertEquals(DashboardModel.Tone.NEUTRAL, model.tiles().get(2).tone());
        assertEquals("CRITICAL", model.tiles().get(2).severity());
        assertEquals(DashboardModel.Tone.ERROR, DashboardModel.severityTone(model.tiles().get(2).severity()));
        assertEquals(DashboardModel.Tone.INFO, DashboardModel.severityTone("INFO"));
    }

    @Test
    void degradedHealthHasDistinctTileTonesAndExplicitWarnings() {
        var model = model(health(false, "snapshot failed", "disk full", "budget reached", true,
            new CallbackExecutor.Health(10, 1, 1, 2, 1, 2, "bad callback")), "CRITICAL");

        assertEquals(List.of(DashboardModel.Tone.ERROR, DashboardModel.Tone.ERROR, DashboardModel.Tone.OK,
            DashboardModel.Tone.NEUTRAL, DashboardModel.Tone.WARN, DashboardModel.Tone.ERROR),
            model.tiles().stream().map(DashboardModel.Tile::tone).toList());
        assertEquals(5, model.warnings().size());
        assertTrue(model.warnings().stream().anyMatch(warning -> warning.text().contains("snapshot failed")));
        assertTrue(model.warnings().stream().anyMatch(warning -> warning.text().contains("disk full")));
        assertTrue(model.warnings().stream().anyMatch(warning -> warning.text().contains("bad callback")));
        assertEquals("building bundle", model.captureStep());
        assertFalse(model.captureAllowed());
    }

    @Test
    void activeCaptureIsNormalButExhaustedCallbacksWarn() {
        var model = model(health(true, null, null, null, true,
            new CallbackExecutor.Health(1, 0, 0, 1, 0, 2, null)), "DEGRADED");
        assertEquals(DashboardModel.Tone.OK, model.tiles().get(1).tone());
        assertEquals("In progress", model.tiles().get(1).value());
        assertEquals(DashboardModel.Tone.NEUTRAL, model.tiles().get(3).tone());
        assertEquals(DashboardModel.Tone.WARN, DashboardModel.severityTone(model.tiles().get(3).severity()));
        assertEquals(DashboardModel.Tone.WARN, model.tiles().get(5).tone());
        assertEquals("1 problem", model.tiles().get(5).value());
        assertEquals(DashboardModel.Tone.WARN, DashboardModel.severityTone(null));
    }

    @Test
    void captureStageIndicesMatchRuntimeStepsAndKeepUnknownStepsRaw() {
        var steps = List.of("queued", "collecting", "waiting for the server to recover", "saving the recording", "building bundle");
        for (int i = 0; i < steps.size(); i++) assertEquals(i, activity(steps.get(i), "", false, List.of()).captureStepIndex());
        assertEquals(-1, activity("starting profile", "", false, List.of()).captureStepIndex());
        assertEquals(-1, activity(null, "", false, List.of()).captureStepIndex());
    }

    @Test
    void captureHintUsesConfiguredRecordingWindow(@TempDir Path dir) throws Exception {
        var everything = capture(dir, "\"IncludeServerLog\":true,\"IncludeServerConfig\":true");
        assertEquals("Saves the last 15 minutes of recording, thread dumps, server log and config as an incident bundle.",
            DashboardModel.captureHint(Duration.ofMinutes(15), 3, everything, false));
        assertEquals("Saves the last 1 day of recording, thread dumps, server log and config as an incident bundle.",
            DashboardModel.captureHint(Duration.ofDays(1), 3, everything, false));
        assertEquals("Saves the last 1 hour of recording, thread dumps, server log and config as an incident bundle.",
            DashboardModel.captureHint(Duration.ofHours(1), 3, everything, false));
        assertEquals("Saves the last 75 seconds of recording, thread dumps, server log and config as an incident bundle.",
            DashboardModel.captureHint(Duration.ofSeconds(75), 3, everything, false));
        assertEquals("Saves the rolling recording, thread dumps, server log and config as an incident bundle.",
            DashboardModel.captureHint(Duration.ZERO, 3, everything, false));
    }

    @Test
    void captureHintListsOnlyConfiguredContents(@TempDir Path dir) throws Exception {
        assertEquals("Saves the last 15 minutes of recording and thread dumps as an incident bundle.",
            DashboardModel.captureHint(Duration.ofMinutes(15), 3, capture(dir, ""), false));
        assertEquals("Saves the last 15 minutes of recording and server log as an incident bundle.",
            DashboardModel.captureHint(Duration.ofMinutes(15), 3,
                capture(dir, "\"IncludeServerLog\":true,\"Artifacts\":[\"jfr\",\"report\"]"), false));
        assertEquals("Saves thread dumps as an incident bundle.", DashboardModel.captureHint(Duration.ofMinutes(15), 3,
            capture(dir, "\"IncludeServerLog\":true,\"IncludeModConfigs\":true,\"Artifacts\":[\"threads\"]"), false));
        assertEquals("Saves an incident summary as an incident bundle.", DashboardModel.captureHint(Duration.ofMinutes(15), 3,
            capture(dir, "\"IncludeServerLog\":true,\"LogTailLines\":0,\"Artifacts\":[\"report\"]"), false));
    }

    @Test
    void captureHintFollowsThreadDumpCountAndPluginConfigOptIn(@TempDir Path dir) throws Exception {
        var defaults = capture(dir, "");
        assertEquals("Saves the last 15 minutes of recording as an incident bundle.",
            DashboardModel.captureHint(Duration.ofMinutes(15), 0, defaults, false));
        assertEquals("Saves the last 15 minutes of recording, thread dumps and config as an incident bundle.",
            DashboardModel.captureHint(Duration.ofMinutes(15), 3, defaults, true));
        assertEquals("Saves the last 15 minutes of recording and thread dumps as an incident bundle.",
            DashboardModel.captureHint(Duration.ofMinutes(15), 3, capture(dir, "\"AllowModConfigOptIn\":false"), true));
    }

    private static CapturePolicy capture(Path dir, String settings) throws Exception {
        Files.writeString(dir.resolve("hybox.json"), "{\"Version\":1,\"Capture\":{" + settings + "}}");
        return HytaleHyboxConfig.loadOrCreate(dir, System.getLogger("dashboard-test")).capturePolicy();
    }

    @Test
    void newestIncidentOpensWithOneClickWhenPermittedAndABundleExists() {
        var incident = new HyboxIncidents.Incident("000010", Path.of("incident-000010.zip"), START,
            "MANUAL", "Manual capture", "INFO", "server");
        assertFalse(activity(null, "", false, List.of(incident)).canOpenLastIncident());
        assertFalse(activity(null, "", true, List.of()).canOpenLastIncident());
        assertTrue(activity("collecting", "", true, List.of(incident)).canOpenLastIncident());
        assertTrue(activity(null, "", true, List.of(incident)).canOpenLastIncident());
    }

    @Test
    void profileActionsRequireTheirOwnPermissionAndAnActiveProfile() {
        var health = health(true, null, null, null, false, new CallbackExecutor.Health(0, 0, 0, 0, 0, 0, null));
        for (boolean active : List.of(false, true)) {
            var profile = active ? new DashboardModel.Profile(START, Duration.ofMinutes(5), START, null) : null;
            for (boolean stopAllowed : List.of(false, true)) {
                for (boolean cancelAllowed : List.of(false, true)) {
                    var model = DashboardModel.from(health, STORAGE, START, profile, List.of(), "", List.of(), "", "", true,
                        "", false, stopAllowed, cancelAllowed, false, false, true, DashboardModel.ProfileForm.DEFAULT, DashboardModel.Heap.EMPTY,
                        DashboardModel.Live.EMPTY, true);
                    assertEquals(active && stopAllowed, model.profileStopAllowed());
                    assertEquals(active && cancelAllowed, model.profileCancelAllowed());
                    assertEquals(active && cancelAllowed, model.cancelConfirming());
                }
            }
        }
    }

    @Test
    void trendAccessPreservesItsOwnPermissionIndependentOfCaptureState() {
        for (boolean captureBusy : List.of(false, true)) {
            var health = health(true, null, null, null, captureBusy, new CallbackExecutor.Health(0, 0, 0, 0, 0, 0, null));
            for (boolean trendsAllowed : List.of(false, true)) {
                var model = DashboardModel.from(health, STORAGE, START, null, List.of(), "", List.of(), "", "", true,
                    "", false, false, false, false, false, trendsAllowed, DashboardModel.ProfileForm.DEFAULT, DashboardModel.Heap.EMPTY,
                    DashboardModel.Live.EMPTY, false);
                assertEquals(trendsAllowed, model.trendsAllowed());
            }
        }
    }

    @Test
    void serverNowFlagsLowTpsAgainstTheWorldTargetAndShowsPlayersMemoryAndDisk() {
        var slow = DashboardModel.live(new HytaleTelemetrySampler.Status(24, 41.7, 30, 3 * GIB, 4 * GIB, 30), 7, STORAGE, 8, START);
        assertEquals(List.of("TPS", "Players", "Memory", "Disk"), slow.stats().stream().map(DashboardModel.Stat::label).toList());
        assertEquals(DashboardModel.Tone.WARN, slow.stats().get(0).tone());
        assertEquals("7", slow.stats().get(1).value());
        assertEquals("3.0 of 4.0 GiB", slow.stats().get(2).value());
        assertEquals(DashboardModel.Tone.NEUTRAL, slow.stats().get(3).tone());
        var lowTarget = DashboardModel.live(new HytaleTelemetrySampler.Status(19.5, 50, 30, GIB, 4 * GIB, 20), 0, STORAGE, 8, START);
        assertEquals(DashboardModel.Tone.NEUTRAL, lowTarget.stats().get(0).tone());
        var waiting = DashboardModel.live(null, -1, null, 0, START);
        assertEquals(DashboardModel.Tone.MUTED, waiting.stats().get(0).tone());
        assertEquals(4, waiting.stats().size());
    }

    @Test
    void captureFeedbackPutsTheSharingNoteOnItsOwnLineAfterTimeSizeAndFolder(@TempDir Path dir) throws Exception {
        String id = "20261022-115900.000+0000-000010";
        Files.write(dir.resolve("incident-" + id + ".zip"), new byte[1536]);
        String[] lines = DashboardModel.captureFeedback(CaptureResult.captured(new IncidentId(id),
            "Removed 15 bundles older than 7 days."), dir).split("\n");
        assertEquals(2, lines.length);
        assertTrue(lines[0].contains("11:59 UTC") && lines[0].contains("1.5 KiB") && lines[0].contains(dir.getFileName().toString()), lines[0]);
        assertTrue(lines[1].endsWith(HyboxChatFormatter.SHARING_NOTE), lines[1]);
        assertTrue(lines[1].startsWith("Removed 15 bundles"), lines[1]);
        assertEquals("Capture failed: IOException",
            DashboardModel.captureFeedback(CaptureResult.failed("IOException"), dir));
    }

    private static DashboardModel activity(String step, String hint, boolean openLast, List<HyboxIncidents.Incident> incidents) {
        return new DashboardModel(List.of(), List.of(), null, step, incidents, "", List.of(), "", "", true, hint, openLast, true, true, true, false, true, DashboardModel.ProfileForm.DEFAULT, DashboardModel.Heap.EMPTY,
            DashboardModel.Live.EMPTY, false);
    }

    private static DashboardModel model(HyboxRuntime.Health health, String severity) {
        return model(health, STORAGE, severity);
    }

    private static DashboardModel model(HyboxRuntime.Health health, RetentionUsage storage, String severity) {
        var incident = new HyboxIncidents.Incident("example", Path.of("example.zip"), START.minusSeconds(60),
            "MANUAL", "Manual capture", severity, "default");
        return DashboardModel.from(health, storage, START, null, List.of(incident), "", List.of(), "", "", true, "", false, true, true, true, false, true, DashboardModel.ProfileForm.DEFAULT, DashboardModel.Heap.EMPTY,
            DashboardModel.Live.EMPTY, false);
    }

    private static HyboxRuntime.Health health(boolean running, String snapshotError, String captureError,
                                              String storagePressure, boolean captureBusy, CallbackExecutor.Health callbacks) {
        return new HyboxRuntime.Health(running, START.minusSeconds(5), snapshotError, captureBusy, "building bundle",
            "example", START.minusSeconds(60), CaptureResult.captured(new IncidentId("example")), captureError,
            0, 0, 0, 4, storagePressure, true, callbacks, new HytaleMixins.ProblemCounts(0, 0));
    }
}
