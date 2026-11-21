package io.github.xytronix.hybox.hytale;

import static org.junit.jupiter.api.Assertions.*;

import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
import io.github.xytronix.hybox.core.capture.CaptureResult;
import io.github.xytronix.hybox.core.incident.IncidentId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class HyboxNotificationsTest {
    @Test
    void everyCaptureOutcomeUsesTheMatchingNativeNotificationStyle() {
        for (var status : CaptureResult.Status.values()) {
            CaptureResult result = status == CaptureResult.Status.CAPTURED
                ? CaptureResult.captured(new IncidentId("example")) : CaptureResult.of(status);
            NotificationStyle expected = switch (status) {
                case CAPTURED -> NotificationStyle.Success;
                case FAILED, STOPPED -> NotificationStyle.Danger;
                default -> NotificationStyle.Warning;
            };
            assertEquals(expected, HyboxNotifications.style(result), status.name());
        }
    }

    @Test
    void consoleCompletionDoesNotNeedAPlayerConnection() {
        assertDoesNotThrow(() -> HyboxNotifications.completed(null, CaptureResult.of(CaptureResult.Status.BUSY), false, null));
    }

    @Test
    void aBurstOfIncidentsAlertsStaffOnceUnlessItGetsWorse() {
        Instant start = Instant.parse("2026-11-19T10:00:00Z");
        Instant[] now = {start};
        var throttle = new HyboxNotifications.Throttle(new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now[0]; }
        });

        var first = throttle.admit("a", "DEGRADED", "World default is slow: 80 ms per tick");
        assertNotNull(first);
        assertEquals(0, first.earlier());
        for (int i = 1; i <= 3; i++) {
            now[0] = start.plusSeconds(40L * i);
            assertNull(throttle.admit("repeat" + i, "DEGRADED", "World default is slow"), "repeat " + i);
        }
        now[0] = start.plusSeconds(200);
        var worse = throttle.admit("b", "CRITICAL", "World default stopped responding for 12 s");
        assertNotNull(worse, "A critical incident after a degraded one is not held back");
        assertEquals(3, worse.earlier());
        now[0] = start.plusSeconds(260);
        assertNull(throttle.admit("c", "CRITICAL", "World default stopped responding for 20 s"));
        now[0] = start.plusSeconds(200).plus(HyboxNotifications.ALERT_QUIET);
        var later = throttle.admit("d", "DEGRADED", "Server CPU at 97%");
        assertNotNull(later, "The quiet window ends");
        assertEquals(1, later.earlier());
    }

    @Test
    void staffHearWhenTheBundleOfAnAlertedIncidentIsSavedAndWhenAHeldOneGetsWorse() {
        Instant start = Instant.parse("2026-11-19T10:00:00Z");
        Instant[] now = {start};
        var throttle = new HyboxNotifications.Throttle(new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now[0]; }
        });

        var accepted = throttle.admit("a", "DEGRADED", "World default stopped responding for 2.1 s");
        assertNotNull(accepted);
        assertEquals(HyboxNotifications.Stage.ACCEPTED, accepted.stage());
        now[0] = start.plusSeconds(30);
        assertNull(throttle.admit("b", "DEGRADED", "World nether stopped responding for 2.2 s"));
        now[0] = start.plusSeconds(120);
        var saved = throttle.saved("a", "DEGRADED", "World default stopped responding for 2 min");
        assertNotNull(saved, "The incident staff were told about reports its bundle");
        assertEquals(HyboxNotifications.Stage.SAVED, saved.stage());
        assertNull(throttle.saved("b", "DEGRADED", "World nether stopped responding for 3 s"),
            "A held incident stays quiet when it did not get worse");
        now[0] = start.plusSeconds(150);
        assertNull(throttle.admit("c", "DEGRADED", "World end stopped responding for 2.0 s"));
        var escalated = throttle.saved("c", "CRITICAL", "World end stopped responding for 2 min and was still frozen");
        assertNotNull(escalated, "A held incident that turned critical is reported");
        assertEquals(HyboxNotifications.Stage.SAVED, escalated.stage());
        assertEquals(1, escalated.earlier());
    }

    @Test
    void anAnnouncedIncidentWhoseBundleFailsGetsOneNotSavedAlert() {
        Instant start = Instant.parse("2026-11-19T10:00:00Z");
        var throttle = new HyboxNotifications.Throttle(Clock.fixed(start, ZoneOffset.UTC));

        assertNotNull(throttle.admit("a", "CRITICAL", "World default stopped responding for 12 s"));
        assertNull(throttle.admit("b", "CRITICAL", "World nether stopped responding for 12 s"));
        var failed = throttle.notSaved("a", "CRITICAL", "World default stopped responding for 12 s");
        assertNotNull(failed);
        assertEquals(HyboxNotifications.Stage.NOT_SAVED, failed.stage());
        assertTrue(HyboxChatFormatter.alertChat(failed).contains("Hybox could not save the bundle. See the server log."));
        assertNull(throttle.notSaved("a", "CRITICAL", "x"), "The failure is reported once");
        assertNull(throttle.notSaved("b", "CRITICAL", "x"), "A held incident gets no failure alert");
        assertNull(throttle.saved("a", "CRITICAL", "x"), "A failed incident is no longer waiting for its saved line");
    }
}
