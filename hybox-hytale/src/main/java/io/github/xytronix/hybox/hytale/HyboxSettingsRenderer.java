package io.github.xytronix.hybox.hytale;

import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import io.github.xytronix.hybox.core.retention.RetentionText;
import io.github.xytronix.hybox.core.retention.RetentionUsage;
import io.github.xytronix.hybox.hytale.HytaleHyboxConfig.HyboxSettings;
import io.github.xytronix.hybox.hytale.HytaleHyboxConfig.SettingField;
import java.util.List;

final class HyboxSettingsRenderer {
    record Feedback(String text, HyboxChatFormatter.Tone tone) {}

    private HyboxSettingsRenderer() {}

    static void render(HyboxSettings settings, String storage, Feedback feedback, List<SettingField> pendingRestart,
                       boolean unsaved, UICommandBuilder commands, UIEventBuilder events) {
        for (var field : HytaleHyboxConfig.SETTINGS_FIELDS) {
            if (field.kind() == HytaleHyboxConfig.SettingKind.TOGGLE) {
                commands.set(field.selector(), (boolean) settings.value(field));
            } else {
                commands.set(field.selector(), (double) (long) settings.value(field));
            }
            String control = field.selector().substring(0, field.selector().lastIndexOf('.'));
            events.addEventBinding(CustomUIEventBindingType.ValueChanged, control, values("change"), false);
        }
        commands.set("#StorageUsage.Text", storage);
        renderFeedback(feedback, commands);
        renderStatus(pendingRestart, unsaved, commands);

        events.addEventBinding(CustomUIEventBindingType.Activating, "#SaveButton", values("save"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#BackButton", values("back"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#Content #CloseButton", values("close"), false);
    }

    private static EventData values(String action) {
        EventData data = EventData.of("Action", action);
        for (var field : HytaleHyboxConfig.SETTINGS_FIELDS) data.append(field.eventKey(), field.selector());
        return data;
    }

    static String storage(RetentionUsage usage) {
        String expired = RetentionText.expired(usage);
        return "In use now: " + RetentionText.usage(usage) + "." + (expired == null ? "" : " " + expired)
            + (usage.lowDisk() ? " Low disk space." : "");
    }

    static String restartNote(List<SettingField> pendingRestart) {
        if (pendingRestart.isEmpty()) return "";
        List<String> names = pendingRestart.stream().map(SettingField::label).toList();
        String joined = names.size() == 1 ? names.getFirst()
            : String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.getLast();
        return "Needs a restart: " + joined + ". The server keeps the old "
            + (names.size() == 1 ? "value" : "values") + " until then.";
    }

    static void renderStatus(List<SettingField> pendingRestart, boolean unsaved, UICommandBuilder commands) {
        String note = restartNote(pendingRestart);
        commands.set("#RestartNote.Text", note);
        commands.set("#RestartNote.Visible", !note.isEmpty());
        commands.set("#UnsavedNote.Visible", unsaved);
    }

    static void renderFeedback(Feedback feedback, UICommandBuilder commands) {
        commands.set("#Feedback.TextSpans", Message.raw(feedback.text()).color(feedback.tone().color));
        commands.set("#Feedback.Visible", !feedback.text().isBlank());
    }
}
