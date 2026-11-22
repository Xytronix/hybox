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

        var first = throttle.admit("DEGRADED", "World default is slow: 80 ms per tick");
        assertNotNull(first);
        assertEquals(0, first.earlier());
        for (int i = 1; i <= 3; i++) {
            now[0] = start.plusSeconds(40L * i);
            assertNull(throttle.admit("DEGRADED", "World default is slow"), "repeat " + i);
        }
        now[0] = start.plusSeconds(200);
        var worse = throttle.admit("CRITICAL", "World default stopped responding for 12 s");
        assertNotNull(worse, "A critical incident after a degraded one is not held back");
        assertEquals(3, worse.earlier());
        now[0] = start.plusSeconds(260);
        assertNull(throttle.admit("CRITICAL", "World default stopped responding for 20 s"));
        now[0] = start.plusSeconds(200).plus(HyboxNotifications.ALERT_QUIET);
        var later = throttle.admit("DEGRADED", "Server CPU at 97%");
        assertNotNull(later, "The quiet window ends");
        assertEquals(1, later.earlier());
    }
}
