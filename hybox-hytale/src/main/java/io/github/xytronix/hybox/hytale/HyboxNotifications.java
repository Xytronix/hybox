package io.github.xytronix.hybox.hytale;

import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.util.NotificationUtil;
import io.github.xytronix.hybox.core.capture.CaptureResult;

final class HyboxNotifications {
    private HyboxNotifications() {}

    static NotificationStyle style(CaptureResult result) {
        return switch (result.status()) {
            case CAPTURED -> NotificationStyle.Success;
            case FAILED, STOPPED -> NotificationStyle.Danger;
            case DISABLED, COOLDOWN, DEBOUNCE, STORAGE_PRESSURE, CANCELLED, BUSY -> NotificationStyle.Warning;
        };
    }

    static void completed(PlayerRef requester, CaptureResult result, boolean profile) {
        if (requester == null) return;
        Message message = Message.raw((profile ? "Hybox profile: " : "Hybox: ")
            + HyboxChatFormatter.captureMessage(result));
        NotificationUtil.sendNotification(requester.getPacketHandler(), message, style(result));
    }
}
