package io.github.xytronix.hybox.hytale;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import io.github.xytronix.hybox.core.capture.CaptureResult;
import io.github.xytronix.hybox.core.incident.IncidentId;
import io.github.xytronix.hybox.core.trigger.DetectorPolicy;
import io.github.xytronix.hybox.core.trigger.ModulePolicy;
import io.github.xytronix.hybox.core.trigger.TriggerPolicy;

class HyboxChatFormatterTest {
    private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");
    private static final String INCIDENT = "20261022-115900.000+0000-test";

    @Test
    void healthyStatusIsCompactWithoutDiagnosticNoise() {
        var health = health(null, null, new CallbackExecutor.Health(42, 0, 0, 0, 0, 0, null));

        var lines = HyboxChatFormatter.status(health, NOW, 0);

        assertTrue(lines.size() <= 3, lines.toString());
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("Recorder running · capture idle"));
        assertTrue(lines.get(1).contains(INCIDENT));
        assertTrue(lines.get(1).contains("1m ago · outcome: captured"));
        assertFalse(String.join("\n", lines).contains("Callbacks"));
        assertFalse(String.join("\n", lines).contains("Storage"));
        assertFalse(String.join("\n", lines).contains("Config:"));
    }

    @Test
    void degradedStatusKeepsAllWarningsWithinThreeLines() {
        var callbacks = new CallbackExecutor.Health(7, 2, 1, 4, 1, 2, "failing callback");
        var health = health("IOException", "Staging budget reached", callbacks);

        var lines = HyboxChatFormatter.status(health, NOW, 29);

        assertEquals(3, lines.size());
        assertTrue(lines.get(2).contains("snapshot error"));
        assertTrue(lines.get(2).contains("storage pressure"));
        assertTrue(lines.get(2).contains("callback failures=2"));
        assertTrue(lines.get(2).contains("callback timeouts=1"));
        assertTrue(lines.get(2).contains("callbacks quarantined=1"));
        assertTrue(lines.get(2).contains("callbacks rejected=4"));
        assertTrue(lines.get(2).contains("callback workers exhausted"));
        assertTrue(lines.get(2).contains("profile 29s remaining"));
        assertTrue(lines.stream().noneMatch(line -> line.contains("\n")));
    }

    @Test
    void stoppedRecorderAndCaptureFailureAreNotPresentedAsHealthy() {
        var health = new HyboxRuntime.Health(false, NOW, null, false, "idle", null, null,
            CaptureResult.failed("disk full"), "disk full", 0, 0, 0, 0, null, true,
            new CallbackExecutor.Health(0, 0, 0, 0, 0, 0, null));

        var messages = HyboxChatFormatter.statusMessages(health, NOW, 0);

        assertEquals(3, messages.size());
        assertEquals("#ef8181", messages.get(0).getChildren().get(1).getColor());
        assertEquals("#ef8181", messages.get(1).getChildren().get(1).getColor());
        assertEquals("#ef8181", messages.get(2).getChildren().get(1).getColor());
        assertTrue(HyboxChatFormatter.status(health, NOW, 0).get(2).contains("capture error"));
    }

    @Test
    void disabledHeartbeatAndTickModulesAreNotListedAsArmed() {
        var modules = new ModulePolicy(false, false, true, true, true, true, true, true, false);
        var defaults = DetectorPolicy.defaults();
        var detectors = new DetectorPolicy(modules, defaults.heapPressurePct(), defaults.heapPressureSustain(),
            defaults.gcPressurePct(), defaults.gcPressureWindow(), defaults.cpuSaturationPct(),
            defaults.cpuSaturationSustain(), defaults.netInMbps(), defaults.netOutMbps(), defaults.netSustain(),
            defaults.playerDropPct(), defaults.playerDropWindow(), defaults.playerDropMinPlayers(),
            defaults.logErrorSkipSentry(), defaults.logErrorRequireThrowable(), defaults.logErrorDedupeWindow(),
            defaults.logErrorIgnore());
        var policy = new TriggerPolicy(Duration.ofMinutes(1), Duration.ofSeconds(5), 2000, 10000,
            50, 100, detectors, null);
        var triggers = HyboxChatFormatter.triggers(policy,
            new HyboxChatFormatter.ArmedTriggers(true, true, true, true, false, true));

        assertFalse(triggers.get(0).armed());
        assertFalse(triggers.get(1).armed());
        assertTrue(triggers.get(2).armed());
        String summary = HyboxChatFormatter.triggerSummary(triggers);
        assertTrue(summary.contains("disabled: heartbeat stall, tick degraded, net saturation, log error"), summary);
        assertFalse(summary.contains(">="));
        assertTrue(triggers.get(0).details().contains("2000 ms"));
    }

    @Test
    void compactIncidentRowsFlattenHeadlinesAndHidePathsUntilRequested() {
        var incident = new HyboxIncidents.Incident(INCIDENT, Path.of("/private/server/incidents/example.zip"),
            NOW.minusSeconds(120), "HEAP_PRESSURE", "Heap pressure\nwith a tab\tand details", "DEGRADED", "world");

        String compact = HyboxChatFormatter.incidentLine(incident, NOW, false);

        assertTrue(compact.contains("HEAP_PRESSURE/Heap pressure with a tab and details"));
        assertTrue(compact.endsWith("2m ago"));
        assertFalse(compact.contains("/private/"));
        assertTrue(HyboxChatFormatter.incidentLine(incident, NOW, true).endsWith(incident.path().toString()));
    }

    @Test
    void captureAndReportPathsDistinguishPlayersFromConsole() {
        var result = CaptureResult.captured(new IncidentId(INCIDENT));
        Path directory = Path.of("/private/server/incidents");
        String player = HyboxChatFormatter.captureLine(result, directory, true);
        String console = HyboxChatFormatter.captureLine(result, directory, false);

        assertTrue(player.endsWith("incident-" + INCIDENT + ".zip"));
        assertFalse(player.contains("/private/"));
        assertTrue(console.contains(directory.toString()));
        assertEquals("trend.html", HyboxChatFormatter.outputPath(directory.resolve("trend.html"), true));
        assertEquals(directory.resolve("trend.html").toString(),
            HyboxChatFormatter.outputPath(directory.resolve("trend.html"), false));
    }

    @Test
    void busyCaptureFeedbackAsksForAnotherAttempt() {
        var result = CaptureResult.of(CaptureResult.Status.BUSY);

        assertEquals("Capture busy; try again shortly.", HyboxChatFormatter.captureMessage(result));
        assertEquals("Capture busy; try again shortly.",
            HyboxChatFormatter.captureLine(result, Path.of("incidents"), true));
    }

    @Test
    void verboseStatusReportsEachFactOnceIncludingErrors(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        var config = HytaleHyboxConfig.loadOrCreate(dir, System.getLogger("formatter-test"));
        var health = new HyboxRuntime.Health(true, NOW.minusSeconds(5), "snapshot broke", false, "idle", INCIDENT,
            NOW.minusSeconds(60), CaptureResult.failed("disk full"), "disk full", 0, 0, 0, 5, "Staging budget reached",
            true, new CallbackExecutor.Health(3, 1, 0, 0, 0, 0, "bad callback"));

        var lines = HyboxChatFormatter.statusDetails(health, config, dir.resolve("hybox.json"), dir, NOW);

        var labels = lines.stream().map(line -> line.substring(0, line.indexOf(':'))).toList();
        assertEquals(labels.size(), labels.stream().distinct().count(), () -> "duplicate rows: " + lines);
        String all = String.join("\n", lines);
        for (String fact : java.util.List.of("snapshot broke", "disk full", "Staging budget reached", "bad callback")) {
            assertTrue(all.contains(fact), () -> fact + " missing from " + lines);
        }
    }

    private static HyboxRuntime.Health health(String snapshotError, String storagePressure,
                                               CallbackExecutor.Health callbacks) {
        return new HyboxRuntime.Health(true, NOW.minusSeconds(5), snapshotError, false, "idle", INCIDENT,
            NOW.minusSeconds(60), CaptureResult.captured(new IncidentId(INCIDENT)), null,
            0, 0, 0, 5, storagePressure, true, callbacks);
    }
}
