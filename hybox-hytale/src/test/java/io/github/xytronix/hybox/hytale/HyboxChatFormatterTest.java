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

        var lines = HyboxChatFormatter.status(health, null, NOW, 0);

        assertTrue(lines.size() <= 3, lines.toString());
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("Recorder running, capture idle, 5 bundles."));
        assertTrue(lines.get(1).contains(INCIDENT));
        assertTrue(lines.get(1).contains("1 min ago. Outcome: captured."));
        assertFalse(String.join("\n", lines).contains("Callbacks"));
        assertFalse(String.join("\n", lines).contains("Storage"));
        assertFalse(String.join("\n", lines).contains("Config:"));
    }

    @Test
    void statusLeadsWithLiveHealthNumbers() {
        var health = health(null, null, new CallbackExecutor.Health(42, 0, 0, 0, 0, 0, null));
        long gib = 1024L * 1024 * 1024;
        var live = new HytaleTelemetrySampler.Status(30, 3.94, 9.4, 966367641L, 4 * gib, 30);

        var lines = HyboxChatFormatter.status(health, live, NOW, 0);

        assertEquals("TPS 30.0, tick 3.9 ms, CPU 9%, heap 0.9 of 4.0 GiB", lines.get(1));
        assertEquals("TPS 30.0, heap 0.9 GiB",
            HyboxChatFormatter.healthLine(new HytaleTelemetrySampler.Status(30, -1, -1, 966367641L, -1, 30)));
        assertNull(HyboxChatFormatter.healthLine(null));
    }

    @Test
    void degradedStatusKeepsAllWarningsWithinThreeLines() {
        var callbacks = new CallbackExecutor.Health(7, 2, 1, 4, 1, 2, "failing callback");
        var health = health("IOException", "Staging budget reached", callbacks);

        var lines = HyboxChatFormatter.status(health, null, NOW, 29);

        assertEquals(3, lines.size());
        assertTrue(lines.get(2).startsWith("Warnings: crash backup error, storage pressure, 2 callback failures, "
            + "1 callback timeout, 1 callback quarantined, 4 callbacks rejected, callback workers exhausted."), lines.get(2));
        assertTrue(lines.get(2).endsWith(" Profile ends in 29 s."), lines.get(2));
        assertFalse(lines.get(2).contains(";"));
        assertFalse(lines.get(2).contains("="));
        assertTrue(lines.stream().noneMatch(line -> line.contains("\n")));
    }

    @Test
    void stoppedRecorderAndCaptureFailureAreNotPresentedAsHealthy() {
        var health = new HyboxRuntime.Health(false, NOW, null, false, "idle", null, null,
            CaptureResult.failed("disk full"), "disk full", 0, 0, 0, 0, null, true,
            new CallbackExecutor.Health(0, 0, 0, 0, 0, 0, null), new HytaleMixins.ProblemCounts(0, 0));

        var messages = HyboxChatFormatter.statusMessages(health, null, NOW, 0);

        assertEquals(3, messages.size());
        assertEquals("#f0626b", messages.get(0).getChildren().get(1).getColor());
        assertEquals("#f0626b", messages.get(1).getChildren().get(1).getColor());
        assertEquals("#f0626b", messages.get(2).getChildren().get(1).getColor());
        assertEquals("#ffd21f", messages.get(0).getChildren().get(0).getColor());
        assertTrue(HyboxChatFormatter.status(health, null, NOW, 0).get(2).contains("capture error"));
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
        assertTrue(summary.contains("Off: Heartbeat stall, Slow ticks, Network saturation, Log errors."), summary);
        assertFalse(summary.contains(">="));
        assertEquals("Heartbeat stall (off): Degraded at 2 s, critical at 10 s", triggers.get(0).details());
        assertEquals("At most one capture every 60 s. Repeats within 5 s are ignored.", HyboxChatFormatter.timing(policy));
        assertFalse(triggers.stream().anyMatch(trigger -> trigger.threshold().contains("PT")), triggers.toString());
    }

    @Test
    void slowTickTextShowsAutoAsBudgetMultiplesAndFixedAsMillis() {
        assertEquals("Degraded at 3 times the tick budget, critical at 7.5 times",
            HyboxChatFormatter.tickLimits(new TriggerPolicy(Duration.ZERO, Duration.ZERO, 2000, 10000, 0, 0)));
        assertEquals("Degraded at 150 ms average tick time, critical at 400 ms",
            HyboxChatFormatter.tickLimits(new TriggerPolicy(Duration.ZERO, Duration.ZERO, 2000, 10000, 150, 400)));
        assertEquals("Degraded at 150 ms average tick time, critical at 7.5 times the tick budget",
            HyboxChatFormatter.tickLimits(new TriggerPolicy(Duration.ZERO, Duration.ZERO, 2000, 10000, 150, 0)));
        assertEquals("Degraded at 3 times the tick budget, critical at 400 ms",
            HyboxChatFormatter.tickLimits(new TriggerPolicy(Duration.ZERO, Duration.ZERO, 2000, 10000, 0, 400)));
    }

    @Test
    void compactIncidentRowsFlattenHeadlinesAndHidePathsUntilRequested() {
        var incident = new HyboxIncidents.Incident(INCIDENT, Path.of("/private/server/incidents/example.zip"),
            NOW.minusSeconds(120), "HEARTBEAT_STALL", "World stopped\nwith a tab\tand details", "DEGRADED", "world",
            null, false, true);

        String compact = HyboxChatFormatter.incidentLine(incident, NOW, false);

        assertTrue(compact.contains("Freeze: World stopped with a tab and details"), compact);
        assertFalse(compact.contains("HEARTBEAT_STALL"), compact);
        assertTrue(compact.endsWith("2 min ago"));
        assertFalse(compact.contains("/private/"));
        assertTrue(HyboxChatFormatter.incidentLine(incident, NOW, true).endsWith(incident.path().toString()));
    }

    @Test
    void captureAndReportPathsDistinguishPlayersFromConsole() {
        var result = CaptureResult.captured(new IncidentId(INCIDENT));
        Path directory = Path.of("/private/server/incidents");
        String player = HyboxChatFormatter.captureLine(result, directory, true);
        String console = HyboxChatFormatter.captureLine(result, directory, false);

        assertTrue(player.startsWith("Incident captured at 11:59 UTC"), player);
        assertFalse(player.contains("/private/"));
        assertTrue(console.contains(directory.toString()));
        assertEquals("trend.html", HyboxChatFormatter.outputPath(directory.resolve("trend.html"), true));
        assertEquals(directory.resolve("trend.html").toString(),
            HyboxChatFormatter.outputPath(directory.resolve("trend.html"), false));
    }

    @Test
    void busyCaptureFeedbackAsksForAnotherAttempt() {
        var result = CaptureResult.of(CaptureResult.Status.BUSY);

        assertEquals("A capture is already running. Try again when it ends.", HyboxChatFormatter.captureMessage(result));
        assertEquals("A capture is already running. Try again when it ends.",
            HyboxChatFormatter.captureLine(result, Path.of("incidents"), true));
    }

    @Test
    void captureResultAndStatusSayWhatRetentionRemovedAndWhatIsInUse() {
        var result = CaptureResult.captured(new IncidentId(INCIDENT), "Removed 15 bundles older than 7 days.");
        assertEquals("Incident captured at 11:59 UTC in incidents. Removed 15 bundles older than 7 days. "
            + HyboxChatFormatter.SHARING_NOTE, HyboxChatFormatter.captureLine(result, Path.of("incidents"), true));
        assertEquals("Captured incident " + INCIDENT + ". Removed 15 bundles older than 7 days.",
            HyboxChatFormatter.captureMessage(result));

        long mib = 1024L * 1024;
        var policy = new io.github.xytronix.hybox.core.retention.RetentionPolicy(25, 1024 * mib, Duration.ofHours(168));
        var usage = new io.github.xytronix.hybox.core.retention.RetentionUsage(16, 640 * mib, 15, 8 * 1024 * mib,
            100 * 1024 * mib, policy);
        assertEquals("Storage: 16 of 25 bundles · 640 MiB of 1 GiB · kept for 7 days · 8 GiB free on disk. "
            + "15 bundles older than 7 days are removed after the next capture.", HyboxChatFormatter.storageLine(usage));
        assertEquals(HyboxChatFormatter.Tone.WARN, HyboxChatFormatter.storageTone(usage));
    }

    @Test
    void verboseStatusReportsEachFactOnceIncludingErrors(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        var config = HytaleHyboxConfig.loadOrCreate(dir, System.getLogger("formatter-test"));
        var health = new HyboxRuntime.Health(true, NOW.minusSeconds(5), "snapshot broke", false, "idle", INCIDENT,
            NOW.minusSeconds(60), CaptureResult.failed("disk full"), "disk full", 0, 0, 0, 5, "Staging budget reached",
            true, new CallbackExecutor.Health(3, 1, 0, 0, 0, 0, "bad callback"), new HytaleMixins.ProblemCounts(0, 0));

        var lines = HyboxChatFormatter.statusDetails(health, config, dir.resolve("hybox.json"), dir, NOW, false);

        var labels = lines.stream().map(line -> line.substring(0, line.indexOf(':'))).toList();
        assertEquals(labels.size(), labels.stream().distinct().count(), () -> "duplicate rows: " + lines);
        String all = String.join("\n", lines);
        for (String fact : java.util.List.of("snapshot broke", "disk full", "Staging budget reached", "bad callback")) {
            assertTrue(all.contains(fact), () -> fact + " missing from " + lines);
        }
    }

    @Test
    void verboseStatusForPlayersHidesAbsolutePaths(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        var config = HytaleHyboxConfig.loadOrCreate(dir, System.getLogger("formatter-test"));
        var health = health(null, null, new CallbackExecutor.Health(0, 0, 0, 0, 0, 0, null));

        String player = String.join("\n",
            HyboxChatFormatter.statusDetails(health, config, dir.resolve("hybox.json"), dir, NOW, true));
        String console = String.join("\n",
            HyboxChatFormatter.statusDetails(health, config, dir.resolve("hybox.json"), dir, NOW, false));

        assertFalse(player.contains(dir.toAbsolutePath().toString()), player);
        assertTrue(player.contains("hybox.json"), player);
        assertTrue(console.contains(dir.resolve("hybox.json").toString()), console);
    }

    private static HyboxRuntime.Health health(String snapshotError, String storagePressure,
                                               CallbackExecutor.Health callbacks) {
        return new HyboxRuntime.Health(true, NOW.minusSeconds(5), snapshotError, false, "idle", INCIDENT,
            NOW.minusSeconds(60), CaptureResult.captured(new IncidentId(INCIDENT)), null,
            0, 0, 0, 5, storagePressure, true, callbacks, new HytaleMixins.ProblemCounts(0, 0));
    }
}
