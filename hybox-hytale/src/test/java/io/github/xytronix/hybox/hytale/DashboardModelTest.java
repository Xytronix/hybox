package io.github.xytronix.hybox.hytale;

import static org.junit.jupiter.api.Assertions.*;

import io.github.xytronix.hybox.core.capture.CaptureResult;
import io.github.xytronix.hybox.core.incident.IncidentId;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class DashboardModelTest {
    private static final Instant START = Instant.parse("2026-10-22T12:00:00Z");

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
    void healthySnapshotUsesSixHealthyTilesWithoutWarningBanner() {
        var model = model(health(true, null, null, null, false,
            new CallbackExecutor.Health(10, 0, 0, 0, 0, 0, null)), "INFO");

        assertEquals(List.of("Recorder", "Capture", "Bundles", "Last incident", "Storage", "Callbacks"),
            model.tiles().stream().map(DashboardModel.Tile::label).toList());
        assertTrue(model.tiles().stream().allMatch(tile -> tile.tone() == DashboardModel.Tone.OK));
        assertTrue(model.warnings().isEmpty());
        assertNull(model.captureStep());
        assertTrue(model.captureAllowed());
        assertEquals("1m ago", model.tiles().get(3).value());
        assertEquals("INFO", model.tiles().get(3).severity());
        assertEquals("Ready · click Capture incident", model.tiles().get(1).detail());
        assertEquals("Within storage budget", model.tiles().get(4).detail());
    }

    @Test
    void degradedHealthHasDistinctTileTonesAndExplicitWarnings() {
        var model = model(health(false, "snapshot failed", "disk full", "budget reached", true,
            new CallbackExecutor.Health(10, 1, 1, 2, 1, 2, "bad callback")), "CRITICAL");

        assertEquals(List.of(DashboardModel.Tone.ERROR, DashboardModel.Tone.ERROR, DashboardModel.Tone.OK,
            DashboardModel.Tone.ERROR, DashboardModel.Tone.WARN, DashboardModel.Tone.ERROR),
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
        assertEquals(DashboardModel.Tone.WARN, model.tiles().get(3).tone());
        assertEquals(DashboardModel.Tone.WARN, model.tiles().get(5).tone());
        assertEquals("1 problem", model.tiles().get(5).value());
        assertEquals(DashboardModel.Tone.WARN, DashboardModel.severityTone(null));
    }

    @Test
    void captureStageIndicesMatchRuntimeStepsAndKeepUnknownStepsRaw() {
        var steps = List.of("queued", "collecting", "waiting for post-incident evidence", "dumping recording", "building bundle");
        for (int i = 0; i < steps.size(); i++) assertEquals(i, activity(steps.get(i), Duration.ofMinutes(15), false, List.of()).captureStepIndex());
        assertEquals(-1, activity("starting profile", Duration.ofMinutes(15), false, List.of()).captureStepIndex());
        assertEquals(-1, activity(null, Duration.ofMinutes(15), false, List.of()).captureStepIndex());
    }

    @Test
    void captureHintUsesConfiguredRecordingWindow() {
        assertEquals("Saves the last 15 minutes of recording, thread dumps, server log and config as an incident bundle.",
            activity(null, Duration.ofMinutes(15), false, List.of()).captureHint());
        assertEquals("Saves the last 1 day of recording, thread dumps, server log and config as an incident bundle.",
            activity(null, Duration.ofDays(1), false, List.of()).captureHint());
        assertEquals("Saves the last 1 hour of recording, thread dumps, server log and config as an incident bundle.",
            activity(null, Duration.ofHours(1), false, List.of()).captureHint());
        assertEquals("Saves the last 75 seconds of recording, thread dumps, server log and config as an incident bundle.",
            activity(null, Duration.ofSeconds(75), false, List.of()).captureHint());
        assertEquals("Saves the rolling recording, thread dumps, server log and config as an incident bundle.",
            activity(null, Duration.ZERO, false, List.of()).captureHint());
    }

    @Test
    void openLastIncidentRequiresSuccessfulCompletionReloadAndNoCaptureInProgress() {
        var incident = new HyboxIncidents.Incident("000010", Path.of("incident-000010.zip"), START,
            "MANUAL", "Manual capture", "INFO", "server");
        assertFalse(activity(null, Duration.ofMinutes(15), false, List.of(incident)).canOpenLastIncident());
        assertFalse(activity(null, Duration.ofMinutes(15), true, List.of()).canOpenLastIncident());
        assertFalse(activity("collecting", Duration.ofMinutes(15), true, List.of(incident)).canOpenLastIncident());
        assertTrue(activity(null, Duration.ofMinutes(15), true, List.of(incident)).canOpenLastIncident());
    }

    @Test
    void profileActionsRequireTheirOwnPermissionAndAnActiveProfile() {
        var health = health(true, null, null, null, false, new CallbackExecutor.Health(0, 0, 0, 0, 0, 0, null));
        for (boolean active : List.of(false, true)) {
            var profile = active ? new DashboardModel.Profile(START, Duration.ofMinutes(5), START, null) : null;
            for (boolean stopAllowed : List.of(false, true)) {
                for (boolean cancelAllowed : List.of(false, true)) {
                    var model = DashboardModel.from(health, START, profile, List.of(), "", List.of(), "", "", true,
                        Duration.ofMinutes(15), false, stopAllowed, cancelAllowed, false, false, true, DashboardModel.ProfileForm.DEFAULT, DashboardModel.Heap.EMPTY);
                    assertEquals(active && stopAllowed, model.profileStopAllowed());
                    assertEquals(active && cancelAllowed, model.profileCancelAllowed());
                }
            }
        }
    }

    @Test
    void trendAccessPreservesItsOwnPermissionIndependentOfCaptureState() {
        for (boolean captureBusy : List.of(false, true)) {
            var health = health(true, null, null, null, captureBusy, new CallbackExecutor.Health(0, 0, 0, 0, 0, 0, null));
            for (boolean trendsAllowed : List.of(false, true)) {
                var model = DashboardModel.from(health, START, null, List.of(), "", List.of(), "", "", true,
                    Duration.ofMinutes(15), false, false, false, false, false, trendsAllowed, DashboardModel.ProfileForm.DEFAULT, DashboardModel.Heap.EMPTY);
                assertEquals(trendsAllowed, model.trendsAllowed());
            }
        }
    }

    @Test
    void captureFeedbackNamesSavedIncidentAndIncludesFailureReason() {
        assertEquals("Incident 000010 saved · incident-000010.zip",
            DashboardModel.captureFeedback(CaptureResult.captured(new IncidentId("000010")), Path.of("incidents")));
        assertEquals("Capture failed: IOException",
            DashboardModel.captureFeedback(CaptureResult.failed("IOException"), Path.of("incidents")));
        assertEquals("Capture busy; try again shortly.",
            DashboardModel.captureFeedback(CaptureResult.of(CaptureResult.Status.BUSY), Path.of("incidents")));
    }

    private static DashboardModel activity(String step, Duration maxAge, boolean openLast, List<HyboxIncidents.Incident> incidents) {
        return new DashboardModel(List.of(), List.of(), null, step, incidents, "", List.of(), "", "", true, maxAge, openLast, true, true, true, false, true, DashboardModel.ProfileForm.DEFAULT, DashboardModel.Heap.EMPTY);
    }

    private static DashboardModel model(HyboxRuntime.Health health, String severity) {
        var incident = new HyboxIncidents.Incident("example", Path.of("example.zip"), START.minusSeconds(60),
            "MANUAL", "Manual capture", severity, "default");
        return DashboardModel.from(health, START, null, List.of(incident), "", List.of(), "", "", true, Duration.ofMinutes(15), false, true, true, true, false, true, DashboardModel.ProfileForm.DEFAULT, DashboardModel.Heap.EMPTY);
    }

    private static HyboxRuntime.Health health(boolean running, String snapshotError, String captureError,
                                              String storagePressure, boolean captureBusy, CallbackExecutor.Health callbacks) {
        return new HyboxRuntime.Health(running, START.minusSeconds(5), snapshotError, captureBusy, "building bundle",
            "example", START.minusSeconds(60), CaptureResult.captured(new IncidentId("example")), captureError,
            0, 0, 0, 4, storagePressure, true, callbacks);
    }
}
