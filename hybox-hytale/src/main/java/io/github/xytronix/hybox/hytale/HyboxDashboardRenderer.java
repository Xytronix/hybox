package io.github.xytronix.hybox.hytale;

import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

final class HyboxDashboardRenderer {
    private static final DateTimeFormatter INCIDENT_TIME =
        DateTimeFormatter.ofPattern("MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter CAPTURE_TIME =
        DateTimeFormatter.ofPattern("HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);
    static final int[] PROFILE_MINUTES = {1, 5, 15, 30};
    static final String KEEP_BUFFER_KEY = "@KeepBuffer";

    private HyboxDashboardRenderer() {}

    static void bindNavigation(UIEventBuilder events) {
        bind(events, "#OverviewButton", "overview");
        bind(events, "#IncidentsButton", "incidents");
        bind(events, "#TriggersButton", "triggers");
        bind(events, "#HeapButton", "heap");
        bind(events, "#CaptureButton", "capture");
        bind(events, "#TrendsButton", "trends");
        bind(events, "#SettingsButton", "settings");
        bind(events, "#OpenLastButton", "incident:0");
        bind(events, "#DismissButton", "close");
    }

    static String shape(DashboardModel model, String tab) {
        return switch (tab) {
            case "incidents" -> "incidents:" + Math.min(20, model.incidents().size());
            case "triggers" -> "triggers:" + model.triggers().size();
            case "heap" -> "heap:" + model.heap().rows();
            default -> "overview:" + model.tiles().size();
        };
    }

    static String timerKey(DashboardModel model) {
        return model.profile() == null ? "" : model.profile().startedAt() + "/" + model.profile().total();
    }

    static void render(DashboardModel model, String tab, UICommandBuilder commands, UIEventBuilder events) {
        commands.set("#SectionTitle.Text", switch (tab) {
            case "incidents" -> "Recent incidents";
            case "triggers" -> "Capture triggers";
            case "heap" -> "Heap histogram";
            default -> "Recorder overview";
        });
        commands.set("#OverviewMarker.Visible", tab.equals("overview"));
        commands.set("#IncidentsMarker.Visible", tab.equals("incidents"));
        commands.set("#TriggersMarker.Visible", tab.equals("triggers"));
        commands.set("#HeapMarker.Visible", tab.equals("heap"));
        commands.clear("#Section");
        switch (tab) {
            case "incidents" -> incidents(model, commands, events);
            case "triggers" -> triggers(model, commands);
            case "heap" -> heap(model, commands, events);
            default -> overview(model, commands, events);
        }
        refresh(model, tab, commands, true);
    }

    static void refresh(DashboardModel model, String tab, UICommandBuilder commands, boolean restartTimer) {
        commands.set("#SectionCaption.Text", switch (tab) {
            case "incidents" -> "Select an incident to view its details.";
            case "triggers" -> model.triggerTiming();
            case "heap" -> "Largest classes by shallow size, from a point-in-time walk of the live heap.";
            default -> "";
        });
        commands.set("#SectionCaption.Visible", !tab.equals("overview"));
        commands.set("#Feedback.Text", model.feedback());
        commands.set("#Feedback.Visible", model.feedback() != null && !model.feedback().isBlank());
        commands.set("#CaptureButton.Disabled", !model.captureAllowed());
        commands.set("#TrendsButton.Disabled", !model.trendsAllowed());
        commands.set("#CaptureHint.Text", model.captureStep() != null ? "Capture in progress · " + model.captureStep()
            : !model.captureAllowed() ? "Capture requires permission to use /hybox dump." : model.captureHint());
        commands.set("#OpenLastButton.Visible", model.canOpenLastIncident());
        switch (tab) {
            case "incidents" -> incidentValues(model, commands);
            case "triggers" -> triggerValues(model, commands);
            case "heap" -> heapValues(model, commands);
            default -> overviewValues(model, commands, restartTimer);
        }
    }

    private static void overview(DashboardModel model, UICommandBuilder commands, UIEventBuilder events) {
        commands.append("#Section", "Pages/HyboxDashboardOverview.ui");
        for (int i = 0; i < model.tiles().size(); i++) commands.append("#Tiles", "Pages/HyboxDashboardTile.ui");
        bind(events, "#ProfileSaveNow", "profile:stop");
        bind(events, "#ProfileCancel", "profile:cancel");
        bind(events, "#SkipWait", "capture:skip");
        for (int minutes : PROFILE_MINUTES) bind(events, "#ProfileMinutes" + minutes, "profile:minutes:" + minutes);
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#ProfileKeepBuffer",
            EventData.of("Action", "profile:keep").append(KEEP_BUFFER_KEY, "#ProfileKeepBuffer.Value"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ProfileStartButton",
            EventData.of("Action", "profile:start").append(KEEP_BUFFER_KEY, "#ProfileKeepBuffer.Value"), false);
    }

    private static void overviewValues(DashboardModel model, UICommandBuilder commands, boolean restartTimer) {
        commands.set("#Warnings.Visible", !model.warnings().isEmpty());
        if (!model.warnings().isEmpty()) {
            String warningText = String.join("  ·  ", model.warnings().stream()
                .map(warning -> HyboxChatFormatter.singleLine(warning.text())).toList());
            var tone = model.warnings().stream().anyMatch(warning -> warning.tone() == DashboardModel.Tone.ERROR)
                ? DashboardModel.Tone.ERROR : DashboardModel.Tone.WARN;
            colored(commands, "#WarningText", warningText, tone);
            background(commands, "#Warnings", tone, "Banner");
        }
        for (int i = 0; i < model.tiles().size(); i++) {
            var tile = model.tiles().get(i);
            String selector = "#Tiles[" + i + "]";
            text(commands, selector + " #TileLabel", tile.label());
            colored(commands, selector + " #TileValue", tile.value(), tile.tone());
            text(commands, selector + " #TileDetail", tile.detail());
            commands.set(selector + " #SeverityPill.Visible", tile.severity() != null);
            if (tile.severity() != null) {
                colored(commands, selector + " #Severity", tile.severity(), tile.tone());
                background(commands, selector + " #SeverityPill", tile.tone(), "Pill");
            }
        }
        commands.set("#ProfileRunning.Visible", model.profile() != null);
        if (model.profile() != null) {
            var profile = model.profile();
            commands.set("#ProfileProgress.Value", Math.round(profile.fraction() * 50) / 50f);
            if (restartTimer) {
                commands.clear("#ProfileTimerHost");
                commands.appendInline("#ProfileTimerHost", "TimerLabel { Seconds: " + profile.remainingSeconds()
                    + "; Style: (FontSize: 26, RenderBold: true, TextColor: #80c991, HorizontalAlignment: End); }");
            }
            boolean requesterKnown = profile.requesterName() != null && !profile.requesterName().isBlank();
            text(commands, "#ProfileCaptureAt", "Capture at " + CAPTURE_TIME.format(profile.captureAt())
                + (requesterKnown ? " · started by " + profile.requesterName() : ""));
            commands.set("#ProfileSaveNow.Visible", model.profileStopAllowed());
            commands.set("#ProfileCancel.Visible", model.profileCancelAllowed());
            commands.set("#ProfileStopNote.Visible", !model.profileStopAllowed() || !model.profileCancelAllowed());
            if (!model.profileStopAllowed() || !model.profileCancelAllowed()) {
                text(commands, "#ProfileStopNote", !model.profileStopAllowed() && !model.profileCancelAllowed()
                    ? "Requires /hybox profile stop to save or /hybox profile cancel to cancel."
                    : !model.profileStopAllowed() ? "Requires /hybox profile stop to save."
                    : "Requires /hybox profile cancel to cancel.");
            }
        }
        commands.set("#CaptureRunning.Visible", model.captureStep() != null);
        if (model.captureStep() != null) {
            int current = model.captureStepIndex();
            commands.set("#CaptureStep.Visible", current < 0);
            if (current < 0) text(commands, "#CaptureStep", model.captureStep());
            for (int i = 0; i < 5; i++) {
                String selector = "#CaptureStage" + i;
                commands.set(selector + ".Style.TextColor", i < current ? "#80c991" : i == current ? "#d3e3ed" : "#96a9be");
                commands.set(selector + ".Style.RenderBold", i == current);
            }
        }
        commands.set("#SkipWait.Visible", model.skipWaitAllowed() && model.captureStepIndex() == 2);
        commands.set("#ProfileSaving.Visible", model.profileSaving());
        commands.set("#NothingRunning.Visible", model.profile() == null && model.captureStep() == null && !model.profileSaving());
        var form = model.profileForm();
        commands.set("#ProfileStartPanel.Visible", model.profileIdle());
        text(commands, "#ProfileStartNote", form.starting() ? "Starting profile…"
            : !form.allowed() ? "Requires permission to use /hybox profile."
            : "Records detailed CPU, allocation and lock data, then saves an incident bundle automatically at the end.");
        for (int minutes : PROFILE_MINUTES) {
            commands.set("#ProfileMinutes" + minutes + ".Disabled", !model.canStartProfile() || minutes == form.minutes());
        }
        commands.set("#ProfileKeepBuffer.Value", form.keepBuffer());
        commands.set("#ProfileKeepBuffer.Disabled", !model.canStartProfile());
        commands.set("#ProfileStartButton.Disabled", !model.canStartProfile());
        commands.set("#ProfileStartButton.Text", form.starting() ? "Starting…" : "Start profile");
    }

    private static void incidents(DashboardModel model, UICommandBuilder commands, UIEventBuilder events) {
        commands.append("#Section", "Pages/HyboxDashboardIncidents.ui");
        for (int i = 0; i < Math.min(20, model.incidents().size()); i++) {
            commands.append("#IncidentRows", "Pages/HyboxRow.ui");
            bind(events, "#IncidentRows[" + i + "]", "incident:" + i);
        }
    }

    private static void incidentValues(DashboardModel model, UICommandBuilder commands) {
        String empty = model.incidentsMessage();
        if ((empty == null || empty.isBlank()) && model.incidents().isEmpty()) empty = "No incidents yet.";
        commands.set("#IncidentEmpty.Visible", empty != null && !empty.isBlank());
        text(commands, "#IncidentEmpty", empty == null ? "" : empty);
        for (int i = 0; i < Math.min(20, model.incidents().size()); i++) {
            var incident = model.incidents().get(i);
            String selector = "#IncidentRows[" + i + "]";
            text(commands, selector + " #Time", incident.createdAt() == null ? "Unknown time" : INCIDENT_TIME.format(incident.createdAt()));
            String severity = incident.severity() == null ? "UNKNOWN" : incident.severity();
            colored(commands, selector + " #Severity", severity, DashboardModel.severityTone(severity));
            background(commands, selector + " #SeverityPill", DashboardModel.severityTone(severity), "Pill");
            text(commands, selector + " #Trigger", incident.kind() == null ? "Unknown trigger" : incident.kind());
            text(commands, selector + " #World", incident.world() == null ? "Server" : incident.world());
            text(commands, selector + " #Headline", incident.headline());
        }
    }

    private static void triggers(DashboardModel model, UICommandBuilder commands) {
        commands.append("#Section", "Pages/HyboxDashboardTriggers.ui");
        for (int i = 0; i < model.triggers().size(); i++) commands.append("#TriggerRows", "Pages/HyboxDashboardTriggerRow.ui");
    }

    private static void triggerValues(DashboardModel model, UICommandBuilder commands) {
        for (int i = 0; i < model.triggers().size(); i++) {
            var trigger = model.triggers().get(i);
            String selector = "#TriggerRows[" + i + "]";
            text(commands, selector + " #Trigger", trigger.name());
            colored(commands, selector + " #Armed", trigger.armed() ? "ARMED" : "DISABLED",
                trigger.armed() ? DashboardModel.Tone.OK : DashboardModel.Tone.WARN);
            background(commands, selector + " #StatePill",
                trigger.armed() ? DashboardModel.Tone.OK : DashboardModel.Tone.WARN, "Pill");
            text(commands, selector + " #Threshold", trigger.threshold());
        }
    }

    private static void heap(DashboardModel model, UICommandBuilder commands, UIEventBuilder events) {
        commands.append("#Section", "Pages/HyboxDashboardHeap.ui");
        for (int i = 0; i < model.heap().rows(); i++) commands.append("#HeapRows", "Pages/HyboxIncidentRow.ui");
        bind(events, "#HeapWalkButton", "heap:ask");
        bind(events, "#HeapConfirmButton", "heap:walk");
        bind(events, "#HeapCancelButton", "heap:cancel");
    }

    private static void heapValues(DashboardModel model, UICommandBuilder commands) {
        var heap = model.heap();
        var snapshot = heap.snapshot();
        String summary;
        if (!heap.allowed()) summary = "Requires permission to use /hybox histogram.";
        else if (heap.message() != null) summary = heap.message();
        else if (snapshot == null) summary = "No heap walk yet. Walk the heap to list the classes using the most memory.";
        else summary = "Taken at " + CAPTURE_TIME.format(heap.takenAt()) + " · "
            + (snapshot.totalBytes() > 0 ? HyboxChatFormatter.bytes(snapshot.totalBytes()) + " in "
                + String.format(java.util.Locale.US, "%,d", snapshot.totalInstances()) + " objects · " : "")
            + "top " + heap.rows() + " classes";
        text(commands, "#HeapSummary", summary);
        commands.set("#HeapWalkButton.Text", snapshot == null ? "Walk heap" : "Walk again");
        commands.set("#HeapWalkButton.Disabled", !heap.allowed() || heap.walking() || heap.confirming());
        commands.set("#HeapConfirm.Visible", heap.allowed() && heap.confirming() && !heap.walking());
        commands.set("#HeapWalking.Visible", heap.walking());
        long base = snapshot == null ? 1 : snapshot.shareBase();
        for (int i = 0; i < heap.rows(); i++) {
            var entry = snapshot.entries().get(i);
            String selector = "#HeapRows[" + i + "]";
            text(commands, selector + " #Key", "#" + (i + 1) + " · " + shortClass(entry.className()));
            text(commands, selector + " #Value", String.format(java.util.Locale.US, "%,d", entry.instances()) + " objects · "
                + HyboxChatFormatter.bytes(entry.bytes()) + " · " + HyboxChatFormatter.heapShare(entry, base) + " of heap");
            commands.set(selector + " #Value.TooltipText", HyboxChatFormatter.singleLine(entry.className()));
            commands.set(selector + " #Share.Visible", true);
            commands.set(selector + " #Share.Value", (float) Math.max(0, Math.min(1, (double) entry.bytes() / base)));
        }
    }

    static String shortClass(String name) {
        int dims = 0;
        while (dims < name.length() && name.charAt(dims) == '[') dims++;
        String component = name.substring(dims);
        if (dims > 0) {
            component = switch (component) {
                case "B" -> "byte"; case "C" -> "char"; case "D" -> "double"; case "F" -> "float";
                case "I" -> "int"; case "J" -> "long"; case "S" -> "short"; case "Z" -> "boolean";
                default -> component.startsWith("L") && component.endsWith(";")
                    ? component.substring(1, component.length() - 1) : component;
            };
        }
        String[] parts = component.split("\\.");
        String shortName = parts.length >= 3 ? parts[parts.length - 2] + "." + parts[parts.length - 1] : component;
        return shortName + "[]".repeat(dims);
    }

    private static void background(UICommandBuilder commands, String selector, DashboardModel.Tone tone, String kind) {
        commands.set(selector + ".Background", Value.ref("Pages/HyboxTones.ui", tone.name().charAt(0) + tone.name().substring(1).toLowerCase(java.util.Locale.ROOT) + kind));
    }

    private static void text(UICommandBuilder commands, String selector, String value) {
        commands.set(selector + ".Text", HyboxChatFormatter.singleLine(value));
    }

    private static void colored(UICommandBuilder commands, String selector, String value, DashboardModel.Tone tone) {
        commands.set(selector + ".TextSpans", Message.raw(HyboxChatFormatter.singleLine(value)).color(tone.color));
    }

    private static void bind(UIEventBuilder events, String selector, String action) {
        events.addEventBinding(CustomUIEventBindingType.Activating, selector, EventData.of("Action", action), false);
    }
}
