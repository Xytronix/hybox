package io.github.xytronix.hybox.hytale;

import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.util.NotificationUtil;
import io.github.xytronix.hybox.core.capture.CaptureResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Predicate;

final class HyboxNotifications {
    static final Duration ALERT_QUIET = Duration.ofMinutes(5);

    private HyboxNotifications() {}

    record Alert(String severity, String headline, int earlier) {}

    static final class Throttle {
        private final Clock clock;
        private Instant lastSent;
        private int lastRank;
        private int held;

        Throttle(Clock clock) {
            this.clock = clock;
        }

        synchronized Alert admit(String severity, String headline) {
            Instant now = clock.instant();
            int rank = rank(severity);
            if (lastSent != null && now.isBefore(lastSent.plus(ALERT_QUIET)) && rank <= lastRank) {
                held++;
                return null;
            }
            Alert alert = new Alert(severity, headline, held);
            lastSent = now;
            lastRank = rank;
            held = 0;
            return alert;
        }

        private static int rank(String severity) {
            return "CRITICAL".equalsIgnoreCase(severity) ? 2 : "DEGRADED".equalsIgnoreCase(severity) ? 1 : 0;
        }
    }

    static NotificationStyle style(CaptureResult result) {
        return switch (result.status()) {
            case CAPTURED -> NotificationStyle.Success;
            case FAILED, STOPPED -> NotificationStyle.Danger;
            case DISABLED, COOLDOWN, DEBOUNCE, STORAGE_PRESSURE, CANCELLED, BUSY -> NotificationStyle.Warning;
        };
    }

    static NotificationStyle alertStyle(String severity) {
        return "CRITICAL".equalsIgnoreCase(severity) ? NotificationStyle.Danger : NotificationStyle.Warning;
    }

    static void completed(PlayerRef requester, CaptureResult result, boolean profile, String saved) {
        if (requester == null) return;
        String prefix = profile ? "Hybox profile: " : "Hybox: ";
        if (result.captured() && saved != null) {
            NotificationUtil.sendNotification(requester.getPacketHandler(), Message.raw(prefix + saved),
                Message.raw(HyboxChatFormatter.SHARING_NOTE), style(result));
            return;
        }
        NotificationUtil.sendNotification(requester.getPacketHandler(),
            Message.raw(prefix + HyboxChatFormatter.captureMessage(result)), style(result));
    }

    static int alertStaff(Iterable<PlayerRef> players, Predicate<PlayerRef> staff, Alert alert, System.Logger logger) {
        Message title = Message.raw(HyboxChatFormatter.alertTitle(alert));
        Message detail = Message.raw(HyboxChatFormatter.singleLine(alert.headline()));
        Message chat = HyboxChatFormatter.message(HyboxChatFormatter.alertTone(alert.severity()), HyboxChatFormatter.alertChat(alert));
        NotificationStyle style = alertStyle(alert.severity());
        int told = 0;
        for (PlayerRef player : players) {
            try {
                if (!staff.test(player)) continue;
                NotificationUtil.sendNotification(player.getPacketHandler(), title, detail, style);
                player.sendMessage(chat);
                told++;
            } catch (RuntimeException | LinkageError failure) {
                logger.log(System.Logger.Level.WARNING, "Unable to tell a staff member about the incident.", failure);
            }
        }
        return told;
    }
}
