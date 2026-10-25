package io.github.xytronix.hybox.hytale;

import static org.junit.jupiter.api.Assertions.*;

import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
import io.github.xytronix.hybox.core.capture.CaptureResult;
import io.github.xytronix.hybox.core.incident.IncidentId;
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
        assertDoesNotThrow(() -> HyboxNotifications.completed(null, CaptureResult.of(CaptureResult.Status.BUSY), false));
    }
}
