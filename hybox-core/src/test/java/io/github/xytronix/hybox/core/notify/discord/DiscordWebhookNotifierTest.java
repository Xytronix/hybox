package io.github.xytronix.hybox.core.notify.discord;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.xytronix.hybox.core.incident.IncidentId;
import io.github.xytronix.hybox.core.incident.IncidentMetadata;
import io.github.xytronix.hybox.core.incident.IncidentReport;
import io.github.xytronix.hybox.core.incident.Severity;
import io.github.xytronix.hybox.core.testutil.MutableClock;

class DiscordWebhookNotifierTest {
    private static final Instant START = Instant.parse("2026-01-11T00:00:00Z");

    @Test
    void savedMessageNamesTheServerWorldIncidentEvidenceAndNextStep(@TempDir Path dir) throws Exception {
        MutableClock clock = new MutableClock(START, ZoneOffset.UTC);
        FakeTransport transport = new FakeTransport();
        DiscordWebhookNotifier notifier = notifier(clock, transport, Duration.ZERO, new ArrayList<>(), new RecordingLogger());
        Path bundle = dir.resolve("incident.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(bundle))) {
            zip.putNextEntry(new ZipEntry("report.html"));
            zip.write(("<script>const D = {\"incident\":{\"id\":\"x\"},\"diagnosis\":{\"kind\":\"cause\",\"cause\":{\"text\":"
                + "\"The world thread was busy in ChunkSaver.save for 4 min.\",\"spans\":[[\"The world thread was busy in \",\"\"],"
                + "[\"ChunkSaver.save\",\"code\"],[\" for 4 min.\",\"\"]],\"section\":\"flame\",\"label\":\"Flame graph\","
                + "\"hover\":null,\"confidence\":\"likely\"},\"why\":[],\"steps\":[{\"text\":\"Update the mod that owns ChunkSaver.\","
                + "\"spans\":[[\"Update the mod that owns ChunkSaver.\",\"\"]],\"section\":\"plugins\",\"label\":\"Plugins\","
                + "\"hover\":null}],\"other\":[],\"terms\":[]}};</script>").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        notifier.onIncident(report(clock, Severity.CRITICAL, "HEARTBEAT_STALL", "default",
            "World default stopped responding for 4 min and was still frozen when the bundle was saved"), bundle);

        assertEquals(1, transport.payloads.size());
        String payload = transport.payloads.get(0);
        assertTrue(payload.contains("**Survival One** Critical incident saved: World default stopped responding for 4 min"),
            payload);
        assertTrue(payload.contains("World default, incident 20260111-000000.000Z-abcdef."), payload);
        assertTrue(payload.contains("Cause: The world thread was busy in ChunkSaver.save for 4 min."), payload);
        assertTrue(payload.contains("Next step: Update the mod that owns ChunkSaver."), payload);
        assertTrue(payload.contains("\"allowed_mentions\":{\"parse\":[]}"), payload);
        assertTrue(payload.contains("\"username\":\"Hybox\""), payload);
    }

    @Test
    void acceptedIncidentPostsAShortLineAndItsFollowUpIgnoresTheCooldown() {
        MutableClock clock = new MutableClock(START, ZoneOffset.UTC);
        FakeTransport transport = new FakeTransport();
        DiscordWebhookNotifier notifier = notifier(clock, transport, Duration.ofMinutes(1), new ArrayList<>(),
            new RecordingLogger());
        IncidentReport report = report(clock, Severity.DEGRADED, "HEARTBEAT_STALL", "default",
            "World default stopped responding for 2.1 s");

        notifier.onAccepted(report.meta());
        clock.advance(Duration.ofSeconds(20));
        notifier.onIncident(report, Path.of("missing.zip"));

        assertEquals(2, transport.payloads.size());
        assertTrue(transport.payloads.get(0).contains("Degraded incident: World default stopped responding for 2.1 s"));
        assertTrue(transport.payloads.get(0).contains("Hybox is saving the bundle now."));
        assertTrue(transport.payloads.get(1).contains("Degraded incident saved:"));
    }

    @Test
    void cooldownDropsRepeatsButNeverACriticalIncident() {
        MutableClock clock = new MutableClock(START, ZoneOffset.UTC);
        FakeTransport transport = new FakeTransport();
        DiscordWebhookNotifier notifier = notifier(clock, transport, Duration.ofSeconds(10), new ArrayList<>(),
            new RecordingLogger());

        notifier.onIncident(report(clock, Severity.DEGRADED, "CPU_SATURATION", "server", "Server CPU at 97%"), null);
        notifier.onIncident(report(clock, Severity.DEGRADED, "CPU_SATURATION", "server", "Server CPU at 98%"), null);
        assertEquals(1, transport.payloads.size());

        clock.advance(Duration.ofSeconds(5));
        notifier.onIncident(report(clock, Severity.CRITICAL, "DEADLOCK", "server", "2 threads are deadlocked"), null);
        assertEquals(2, transport.payloads.size());
        assertFalse(transport.payloads.get(1).contains("World "), transport.payloads.get(1));

        clock.advance(Duration.ofSeconds(9));
        notifier.onIncident(report(clock, Severity.DEGRADED, "CPU_SATURATION", "server", "Server CPU at 99%"), null);
        assertEquals(2, transport.payloads.size());

        clock.advance(Duration.ofSeconds(1));
        notifier.onIncident(report(clock, Severity.DEGRADED, "CPU_SATURATION", "server", "Server CPU at 99%"), null);
        assertEquals(3, transport.payloads.size());
    }

    @Test
    void rateLimitIsRetriedOnceAfterRetryAfter() {
        MutableClock clock = new MutableClock(START, ZoneOffset.UTC);
        FakeTransport transport = new FakeTransport();
        transport.statuses.add(new WebhookTransport.Response(429, null, "{\"retry_after\": 1.5, \"global\": false}"));
        transport.statuses.add(new WebhookTransport.Response(204, null, ""));
        List<Duration> waits = new ArrayList<>();
        RecordingLogger logger = new RecordingLogger();
        DiscordWebhookNotifier notifier = notifier(clock, transport, Duration.ZERO, waits, logger);

        notifier.onIncident(report(clock, Severity.CRITICAL, "DEADLOCK", "server", "2 threads are deadlocked"), null);

        assertEquals(List.of(Duration.ofMillis(1500)), waits);
        assertEquals(2, transport.payloads.size());
        assertEquals(transport.payloads.get(0), transport.payloads.get(1));
        assertEquals(List.of(), logger.warnings);
    }

    @Test
    void aSecondRateLimitDropsTheAlertWithOneWarning() {
        MutableClock clock = new MutableClock(START, ZoneOffset.UTC);
        FakeTransport transport = new FakeTransport();
        transport.statuses.add(new WebhookTransport.Response(429, "2", ""));
        transport.statuses.add(new WebhookTransport.Response(429, "2", ""));
        List<Duration> waits = new ArrayList<>();
        RecordingLogger logger = new RecordingLogger();
        DiscordWebhookNotifier notifier = notifier(clock, transport, Duration.ZERO, waits, logger);

        notifier.onIncident(report(clock, Severity.CRITICAL, "DEADLOCK", "server", "2 threads are deadlocked"), null);

        assertEquals(List.of(Duration.ofSeconds(2)), waits);
        assertEquals(2, transport.payloads.size());
        assertEquals(1, logger.warnings.size());
        assertTrue(logger.warnings.get(0).contains("rate limited"), logger.warnings.get(0));
    }

    @Test
    void aDeletedWebhookIsLoggedOnceUntilAMessageGoesThrough() {
        MutableClock clock = new MutableClock(START, ZoneOffset.UTC);
        FakeTransport transport = new FakeTransport();
        transport.statuses.add(new WebhookTransport.Response(404, null, "{\"message\": \"Unknown Webhook\"}"));
        transport.statuses.add(new WebhookTransport.Response(404, null, ""));
        transport.statuses.add(new WebhookTransport.Response(500, null, ""));
        transport.statuses.add(new WebhookTransport.Response(204, null, ""));
        transport.statuses.add(new WebhookTransport.Response(404, null, ""));
        RecordingLogger logger = new RecordingLogger();
        DiscordWebhookNotifier notifier = notifier(clock, transport, Duration.ZERO, new ArrayList<>(), logger);

        for (int i = 0; i < 5; i++) {
            notifier.onIncident(report(clock, Severity.CRITICAL, "DEADLOCK", "server", "2 threads are deadlocked"), null);
        }

        assertEquals(5, transport.payloads.size());
        assertEquals(3, logger.warnings.size(), logger.warnings.toString());
        assertTrue(logger.warnings.get(0).contains("HTTP 404"), logger.warnings.get(0));
        assertTrue(logger.warnings.get(1).contains("HTTP 500"), logger.warnings.get(1));
        assertTrue(logger.warnings.get(2).contains("HTTP 404"), logger.warnings.get(2));
    }

    @Test
    void transportFailuresDoNotEscape() {
        MutableClock clock = new MutableClock(START, ZoneOffset.UTC);
        RecordingLogger logger = new RecordingLogger();
        DiscordWebhookNotifier notifier = new DiscordWebhookNotifier(clock, logger, config(Duration.ZERO),
            (uri, payload) -> {
                throw new IllegalStateException("Simulated transport failure.");
            }, Runnable::run, (wait, task) -> task.run());

        assertDoesNotThrow(() -> notifier.onIncident(report(clock, Severity.CRITICAL, "DEADLOCK", "server", "x"), null));
        assertDoesNotThrow(() -> notifier.onIncident(report(clock, Severity.CRITICAL, "DEADLOCK", "server", "x"), null));
        assertEquals(1, logger.warnings.size());
    }

    @Test
    void aFailedCaptureAfterTheFirstMessageGetsANotSavedLine() {
        MutableClock clock = new MutableClock(START, ZoneOffset.UTC);
        FakeTransport transport = new FakeTransport();
        DiscordWebhookNotifier notifier = notifier(clock, transport, Duration.ofMinutes(1), new ArrayList<>(),
            new RecordingLogger());
        IncidentReport report = report(clock, Severity.CRITICAL, "DEADLOCK", "server", "2 threads are deadlocked");

        notifier.onFailed(report.meta());
        assertEquals(0, transport.payloads.size(), "Nothing was announced, so nothing is taken back");
        notifier.onAccepted(report.meta());
        notifier.onFailed(report.meta());
        notifier.onFailed(report.meta());

        assertEquals(2, transport.payloads.size());
        assertTrue(transport.payloads.get(1).contains("Critical incident not saved: 2 threads are deadlocked"),
            transport.payloads.get(1));
        assertTrue(transport.payloads.get(1).contains("Hybox could not save the bundle. See the server log."));
    }

    @Test
    void aRateLimitLongerThanAMinuteIsDroppedWithoutClaimingAWait() {
        MutableClock clock = new MutableClock(START, ZoneOffset.UTC);
        FakeTransport transport = new FakeTransport();
        transport.statuses.add(new WebhookTransport.Response(429, null, "{\"retry_after\": 300}"));
        List<Duration> waits = new ArrayList<>();
        RecordingLogger logger = new RecordingLogger();
        DiscordWebhookNotifier notifier = notifier(clock, transport, Duration.ZERO, waits, logger);

        notifier.onIncident(report(clock, Severity.CRITICAL, "DEADLOCK", "server", "2 threads are deadlocked"), null);

        assertEquals(List.of(), waits);
        assertEquals(1, transport.payloads.size());
        assertEquals(1, logger.warnings.size());
        assertTrue(logger.warnings.get(0).contains("asked Hybox to wait 300 s"), logger.warnings.get(0));
    }

    @Test
    void retryAfterPrefersTheBodyThenTheHeader() {
        assertEquals(Duration.ofMillis(250), DiscordWebhookNotifier.retryAfter(
            new WebhookTransport.Response(429, "7", "{\"retry_after\":0.25}")));
        assertEquals(Duration.ofSeconds(7), DiscordWebhookNotifier.retryAfter(new WebhookTransport.Response(429, "7", "")));
        assertEquals(Duration.ofSeconds(1), DiscordWebhookNotifier.retryAfter(new WebhookTransport.Response(429, null, null)));
    }

    private static DiscordWebhookNotifier notifier(MutableClock clock, FakeTransport transport, Duration cooldown,
                                                   List<Duration> waits, RecordingLogger logger) {
        return new DiscordWebhookNotifier(clock, logger, config(cooldown), transport, Runnable::run, (wait, task) -> {
            waits.add(wait);
            task.run();
        });
    }

    private static DiscordWebhookConfig config(Duration cooldown) {
        return new DiscordWebhookConfig("https://example.test/webhook", cooldown, Duration.ofSeconds(2), "Hybox",
            "Survival One");
    }

    private static IncidentReport report(MutableClock clock, Severity severity, String trigger, String world,
                                         String headline) {
        return new IncidentReport(new IncidentMetadata(new IncidentId("20260111-000000.000Z-abcdef"), clock.instant(),
            severity, trigger, world, headline));
    }

    private static final class FakeTransport implements WebhookTransport {
        private final List<String> payloads = new ArrayList<>();
        private final Deque<Response> statuses = new ArrayDeque<>();

        @Override
        public CompletableFuture<Response> post(URI uri, String jsonPayload) {
            payloads.add(jsonPayload);
            Response next = statuses.poll();
            return CompletableFuture.completedFuture(next == null ? new Response(204, null, "") : next);
        }
    }

    private static final class RecordingLogger implements System.Logger {
        private final List<String> warnings = new ArrayList<>();

        @Override
        public String getName() {
            return "discord-test";
        }

        @Override
        public boolean isLoggable(Level level) {
            return true;
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String msg, Throwable thrown) {
            if (level == Level.WARNING) {
                warnings.add(msg);
            }
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String format, Object... params) {
            if (level == Level.WARNING) {
                warnings.add(format);
            }
        }

        @Override
        public void log(Level level, Supplier<String> msgSupplier) {
            if (level == Level.WARNING) {
                warnings.add(msgSupplier.get());
            }
        }
    }
}
