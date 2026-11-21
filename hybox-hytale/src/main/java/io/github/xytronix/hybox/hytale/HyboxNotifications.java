package io.github.xytronix.hybox.hytale;

import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.util.NotificationUtil;
import io.github.xytronix.hybox.core.capture.CaptureResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;

final class HyboxNotifications {
    static final Duration ALERT_QUIET = Duration.ofMinutes(5);

    private HyboxNotifications() {}

    enum Stage { ACCEPTED, SAVED, NOT_SAVED }

    record Alert(String severity, String headline, int earlier, Stage stage) {}

    static final class Throttle {
        private static final int REMEMBERED = 32;

        private final Clock clock;
        private final Set<String> alerted = new LinkedHashSet<>();
        private final Set<String> held = new LinkedHashSet<>();
        private Instant lastSent;
        private int lastRank;
        private int heldCount;

        Throttle(Clock clock) {
            this.clock = clock;
        }

        synchronized Alert admit(String id, String severity, String headline) {
            Alert alert = pass(severity, headline, Stage.ACCEPTED);
            remember(alert == null ? held : alerted, id);
            if (alert == null) heldCount++;
            return alert;
        }

        synchronized Alert saved(String id, String severity, String headline) {
            if (alerted.remove(id)) {
                lastRank = Math.max(lastRank, rank(severity));
                return new Alert(severity, headline, 0, Stage.SAVED);
            }
            boolean wasHeld = held.remove(id);
            Alert alert = pass(severity, headline, Stage.SAVED);
            if (alert == null && !wasHeld) heldCount++;
            if (alert != null && wasHeld) return new Alert(severity, headline, Math.max(0, alert.earlier() - 1), Stage.SAVED);
            return alert;
        }

        synchronized Alert notSaved(String id, String severity, String headline) {
            held.remove(id);
            return alerted.remove(id) ? new Alert(severity, headline, 0, Stage.NOT_SAVED) : null;
        }

        private Alert pass(String severity, String headline, Stage stage) {
            Instant now = clock.instant();
            int rank = rank(severity);
            if (lastSent != null && now.isBefore(lastSent.plus(ALERT_QUIET)) && rank <= lastRank) {
                return null;
            }
            Alert alert = new Alert(severity, headline, heldCount, stage);
            lastSent = now;
            lastRank = rank;
            heldCount = 0;
            return alert;
        }

        private static void remember(Set<String> ids, String id) {
            ids.add(id);
            if (ids.size() > REMEMBERED) {
                Iterator<String> oldest = ids.iterator();
                oldest.next();
                oldest.remove();
            }
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
        String text = profile && result.status() == CaptureResult.Status.CANCELLED
            ? "Profile canceled. Nothing was saved." : HyboxChatFormatter.captureMessage(result);
        NotificationUtil.sendNotification(requester.getPacketHandler(), Message.raw(prefix + text), style(result));
    }

    static void profileNote(PlayerRef requester, String text, boolean ok) {
        if (requester == null) return;
        NotificationUtil.sendNotification(requester.getPacketHandler(), Message.raw("Hybox profile: " + text),
            ok ? NotificationStyle.Success : NotificationStyle.Warning);
        requester.sendMessage(HyboxChatFormatter.message(ok ? HyboxChatFormatter.Tone.OK : HyboxChatFormatter.Tone.WARN, text));
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
