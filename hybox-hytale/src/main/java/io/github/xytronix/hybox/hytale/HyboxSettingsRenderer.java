package io.github.xytronix.hybox.hytale;

import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import io.github.xytronix.hybox.hytale.HytaleHyboxConfig.HyboxSettings;

final class HyboxSettingsRenderer {
    record Feedback(String text, HyboxChatFormatter.Tone tone) {}

    private HyboxSettingsRenderer() {}

    static void render(HyboxSettings settings, Feedback feedback, UICommandBuilder commands, UIEventBuilder events) {
        EventData save = EventData.of("Action", "save");
        for (var field : HytaleHyboxConfig.SETTINGS_FIELDS) {
            if (field.kind() == HytaleHyboxConfig.SettingKind.TOGGLE) {
                commands.set(field.selector(), (boolean) settings.value(field));
            } else {
                commands.set(field.selector(), (double) (long) settings.value(field));
            }
            save.append(field.eventKey(), field.selector());
        }
        renderFeedback(feedback, commands);

        events.addEventBinding(CustomUIEventBindingType.Activating, "#SaveButton", save, false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#BackButton", EventData.of("Action", "back"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#Content #CloseButton", EventData.of("Action", "close"), false);
    }

    static void renderFeedback(Feedback feedback, UICommandBuilder commands) {
        commands.set("#Feedback.TextSpans", Message.raw(feedback.text()).color(switch (feedback.tone()) {
            case OK -> "#80c991";
            case WARN -> "#efbd62";
            case ERROR -> "#ef8181";
        }));
        commands.set("#Feedback.Visible", !feedback.text().isBlank());
    }
}
