package io.github.xytronix.hybox.hytale;

import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import io.github.xytronix.hybox.core.jfr.ProfileMode;
import io.github.xytronix.hybox.core.text.Units;
import io.github.xytronix.hybox.core.trigger.TriggerNames;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

final class HyboxDashboardRenderer {
    private static final DateTimeFormatter INCIDENT_TIME =
        DateTimeFormatter.ofPattern("MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter CAPTURE_TIME =
        DateTimeFormatter.ofPattern("HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);
    static final int[] PROFILE_MINUTES = {1, 5, 15, 30};
    static final String KEEP_BUFFER_KEY = "@KeepBuffer";
    static final String TONES = "Pages/HyboxTones.ui";
    static final String MEMORY_PAUSE = "1 s";
    static final String LAG_COST = "Finds lag from CPU and lock waits. Costs about 0.3% of one CPU core and 1.1 MiB of recording per minute.";
    static final String MEMORY_COST = "Adds allocations and leak paths. Costs about 0.3% of one CPU core and 1.1 MiB per minute. "
        + "Pauses the server for up to " + MEMORY_PAUSE + " when it ends.";

    private HyboxDashboardRenderer() {}

    static void bindNavigation(UIEventBuilder events) {
        bind(events, "#OverviewButton", "overview");
        bind(events, "#IncidentsButton", "incidents");
        bind(events, "#TriggersButton", "triggers");
        bind(events, "#HeapButton", "heap");
        bind(events, "#CaptureButton", "capture");
        bind(events, "#TrendsButton", "trends");
        bind(events, "#SettingsButton", "settings");
        bind(events, "#OpenLastButton", "incident:latest");
        bind(events, "#DismissButton", "close");
    }

    static String shape(DashboardModel model, String tab) {
        return switch (tab) {
            case "incidents" -> "incidents:" + Math.min(ROWS, model.incidentRows().size()) + ":" + DashboardModel.listVersion(model.incidentRows());
            case "triggers" -> "triggers:" + model.triggers().size();
            case "heap" -> "heap:" + model.heap().rows();
            default -> "overview:" + model.tiles().size() + ":" + model.live().stats().size();
        };
    }

    static final int ROWS = 50;

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
        choice(commands, "#OverviewButton", "#OverviewMarker", tab.equals("overview"), "TabButtonStyle");
        choice(commands, "#IncidentsButton", "#IncidentsMarker", tab.equals("incidents"), "TabButtonStyle");
        choice(commands, "#TriggersButton", "#TriggersMarker", tab.equals("triggers"), "TabButtonStyle");
        choice(commands, "#HeapButton", "#HeapMarker", tab.equals("heap"), "TabButtonStyle");
        commands.clear("#Section");
        switch (tab) {
            case "incidents" -> incidents(model, commands, events);
            case "triggers" -> triggers(model, commands, events);
            case "heap" -> heap(model, commands, events);
            default -> overview(model, commands, events);
        }
        refresh(model, tab, commands, true);
    }

    static void refresh(DashboardModel model, String tab, UICommandBuilder commands, boolean restartTimer) {
        commands.set("#SectionCaption.Text", switch (tab) {
            case "incidents" -> "Select an incident to view its details.";
            case "triggers" -> model.triggerTiming();
            case "heap" -> "The classes that use the most memory right now.";
            default -> "";
        });
        commands.set("#SectionCaption.Visible", !tab.equals("overview"));
        commands.set("#Feedback.Text", model.feedback() == null ? "" : model.feedback());
        commands.set("#CaptureButton.Disabled", !model.captureAllowed());
        commands.set("#TrendsButton.Disabled", !model.trendsAllowed());
        commands.set("#CaptureHint.Text", model.captureStep() != null ? "Capture in progress: " + model.captureStep()
            : !model.captureAllowed() ? "Capture requires permission to use /hybox dump." : model.captureHint());
        commands.set("#OpenLastButton.Visible", model.canOpenLastIncident() && !tab.equals("incidents"));
        switch (tab) {
            case "incidents" -> incidentValues(model, commands);
            case "triggers" -> triggerValues(model, commands);
            case "heap" -> heapValues(model, commands);
            default -> overviewValues(model, commands, restartTimer);
        }
    }

    private static void overview(DashboardModel model, UICommandBuilder commands, UIEventBuilder events) {
        commands.append("#Section", "Pages/HyboxDashboardOverview.ui");
        for (int i = 0; i < model.live().stats().size(); i++) commands.append("#Stats", "Pages/HyboxDashboardStat.ui");
        String tile = model.tiles().size() == 4 ? "Pages/HyboxDashboardTileWide.ui" : "Pages/HyboxDashboardTile.ui";
        for (int i = 0; i < model.tiles().size(); i++) commands.append("#Tiles", tile);
        bind(events, "#ProfileSaveNow", "profile:stop");
        bind(events, "#ProfileCancel", "profile:cancel");
        bind(events, "#ProfileCancelYes", "profile:cancel:yes");
        bind(events, "#ProfileCancelNo", "profile:cancel:no");
        bind(events, "#ProfileQueuedCancel", "profile:cancel");
        bind(events, "#ProfileSavingCancel", "profile:cancel");
        bind(events, "#SkipWait", "capture:skip");
        for (int minutes : PROFILE_MINUTES) bind(events, "#ProfileMinutes" + minutes, "profile:minutes:" + minutes);
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#ProfileKeepBuffer",
            EventData.of("Action", "profile:keep").append(KEEP_BUFFER_KEY, "#ProfileKeepBuffer.Value"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ProfileStartButton",
            EventData.of("Action", "profile:start:lag").append(KEEP_BUFFER_KEY, "#ProfileKeepBuffer.Value"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#MemoryStartButton",
            EventData.of("Action", "profile:start:memory").append(KEEP_BUFFER_KEY, "#ProfileKeepBuffer.Value"), false);
    }

    static String profileName(ProfileMode mode) {
        return mode == ProfileMode.MEMORY ? "Memory profile" : "Lag profile";
    }

    private static void overviewValues(DashboardModel model, UICommandBuilder commands, boolean restartTimer) {
        commands.set("#Warnings.Visible", !model.warnings().isEmpty());
        if (!model.warnings().isEmpty()) {
            String warningText = String.join("  ·  ", model.warnings().stream()
                .map(warning -> HyboxChatFormatter.singleLine(warning.text())).toList());
            var tone = model.warnings().stream().anyMatch(warning -> warning.tone() == DashboardModel.Tone.ERROR)
                ? DashboardModel.Tone.ERROR : DashboardModel.Tone.WARN;
            colored(commands, "#WarningText", warningText, tone);
            commands.set("#WarningTitle.Style.TextColor", tone.color);
            background(commands, "#Warnings", tone, "Banner");
        }
        for (int i = 0; i < model.live().stats().size(); i++) {
            var stat = model.live().stats().get(i);
            String selector = "#Stats[" + i + "]";
            text(commands, selector + " #StatLabel", stat.label());
            colored(commands, selector + " #StatValue", stat.value(), stat.tone());
            text(commands, selector + " #StatDetail", stat.detail());
        }
        text(commands, "#Watching", DashboardModel.watching(model.live().watching()));
        for (int i = 0; i < model.tiles().size(); i++) {
            var tile = model.tiles().get(i);
            String selector = "#Tiles[" + i + "]";
            text(commands, selector + " #TileLabel", tile.label());
            colored(commands, selector + " #TileValue", tile.value(), tile.tone());
            text(commands, selector + " #TileDetail", tile.detail());
            commands.set(selector + " #SeverityPill.Visible", tile.severity() != null);
            if (tile.severity() != null) {
                var severityTone = DashboardModel.severityTone(tile.severity());
                colored(commands, selector + " #Severity", DashboardModel.severityLabel(tile.severity()), severityTone);
                background(commands, selector + " #SeverityPill", severityTone, "Pill");
            }
        }
        commands.set("#ProfileRunning.Visible", model.profile() != null);
        if (model.profile() != null) {
            var profile = model.profile();
            text(commands, "#ProfileTitle", profileName(profile.mode()));
            commands.set("#ProfileProgress.Value", Math.round(profile.fraction() * 50) / 50f);
            if (restartTimer) {
                commands.clear("#ProfileTimerHost");
                commands.appendInline("#ProfileTimerHost", "TimerLabel { Seconds: " + profile.remainingSeconds()
                    + "; Style: (FontSize: 26, RenderBold: true, TextColor: " + DashboardModel.Tone.NEUTRAL.color + ", HorizontalAlignment: End); }");
            }
            boolean requesterKnown = profile.requesterName() != null && !profile.requesterName().isBlank();
            text(commands, "#ProfileCaptureAt", "Capture at " + CAPTURE_TIME.format(profile.captureAt())
                + (requesterKnown ? ", started by " + profile.requesterName() : "")
                + (profile.mode() == ProfileMode.MEMORY ? ". Pauses the server for up to " + MEMORY_PAUSE + " then." : ""));
            commands.set("#ProfileSaveNow.Visible", model.profileStopAllowed());
            commands.set("#ProfileCancel.Visible", model.profileCancelAllowed() && !model.cancelConfirming());
            commands.set("#ProfileStopNote.Visible", !model.profileStopAllowed() || !model.profileCancelAllowed());
            if (!model.profileStopAllowed() || !model.profileCancelAllowed()) {
                text(commands, "#ProfileStopNote", !model.profileStopAllowed() && !model.profileCancelAllowed()
                    ? "Requires /hybox profile stop to save or /hybox profile cancel to cancel."
                    : !model.profileStopAllowed() ? "Requires /hybox profile stop to save."
                    : "Requires /hybox profile cancel to cancel.");
            }
        }
        var queued = model.queue().start();
        commands.set("#ProfileQueued.Visible", queued != null);
        if (queued != null) {
            boolean requesterKnown = queued.requesterName() != null && !queued.requesterName().isBlank();
            text(commands, "#ProfileQueuedDetail", profileName(queued.mode()) + " for " + queued.minutes() + " min"
                + (requesterKnown ? ", started by " + queued.requesterName() : "") + ".");
            commands.set("#ProfileQueuedCancel.Visible", model.profileCancelAllowed());
        }
        commands.set("#CaptureRunning.Visible", model.captureStep() != null);
        if (model.captureStep() != null) {
            int current = model.captureStepIndex();
            commands.set("#CaptureStep.Visible", current < 0);
            if (current < 0) text(commands, "#CaptureStep", model.captureStep());
            for (int i = 0; i < 5; i++) {
                String selector = "#CaptureStage" + i;
                commands.set(selector + ".Style.TextColor", (i < current ? DashboardModel.Tone.OK
                    : i == current ? DashboardModel.Tone.NEUTRAL : DashboardModel.Tone.MUTED).color);
                commands.set(selector + ".Style.RenderBold", i == current);
            }
        }
        commands.set("#SkipWait.Visible", model.skipWaitAllowed() && model.captureStepIndex() == 2);
        commands.set("#ProfileSaving.Visible", model.profileSaving());
        text(commands, "#ProfileSavingText", model.queue().finalQueued()
            ? "Profile bundle queued, saves once the incident is saved" : "Saving the profile bundle...");
        commands.set("#ProfileSavingCancel.Visible", model.queue().finalQueued() && model.profileCancelAllowed()
            && !model.cancelConfirming());
        commands.set("#ProfileCancelConfirm.Visible", model.cancelConfirming());
        commands.set("#NothingRunning.Visible", model.profile() == null && model.captureStep() == null && !model.profileSaving()
            && queued == null);
        var form = model.profileForm();
        commands.set("#ProfileStartPanel.Visible", model.profileIdle());
        text(commands, "#ProfileStartNote", form.starting() ? "Starting profile..."
            : !form.allowed() ? "Requires permission to use /hybox profile."
            : "Saves an incident bundle automatically at the end.");
        for (int minutes : PROFILE_MINUTES) {
            boolean chosen = minutes == form.minutes();
            choice(commands, "#ProfileMinutes" + minutes, "#ProfileMinutes" + minutes + "Marker", chosen, "SecondaryButtonStyle");
            commands.set("#ProfileMinutes" + minutes + ".Disabled", !model.canStartProfile() || chosen);
        }
        commands.set("#ProfileKeepBuffer.Value", form.keepBuffer());
        commands.set("#ProfileKeepBuffer.Disabled", !model.canStartProfile());
        commands.set("#ProfileStartButton.Disabled", !model.canStartProfile());
        commands.set("#MemoryStartButton.Disabled", !model.canStartProfile());
        text(commands, "#ProfileLagCost", LAG_COST);
        text(commands, "#ProfileMemoryCost", MEMORY_COST);
    }

    private static void incidents(DashboardModel model, UICommandBuilder commands, UIEventBuilder events) {
        commands.append("#Section", "Pages/HyboxDashboardIncidents.ui");
        var rows = model.incidentRows();
        String version = DashboardModel.listVersion(rows);
        for (int i = 0; i < Math.min(ROWS, rows.size()); i++) {
            commands.append("#IncidentRows", "Pages/HyboxRow.ui");
            bind(events, "#IncidentRows[" + i + "]", "incident:" + version + ":" + i);
        }
        bind(events, "#ManualToggle", "incidents:manual");
    }

    private static void incidentValues(DashboardModel model, UICommandBuilder commands) {
        var rows = model.incidentRows();
        long manual = model.manualCount();
        String empty = model.incidentsMessage();
        if ((empty == null || empty.isBlank()) && rows.isEmpty()) empty = model.incidents().isEmpty() ? "No incidents yet."
            : "No automatic incidents. " + Units.count(manual, "manual capture") + (manual == 1 ? " is" : " are") + " hidden.";
        commands.set("#IncidentEmpty.Visible", empty != null && !empty.isBlank());
        text(commands, "#IncidentEmpty", empty == null ? "" : empty);
        commands.set("#ManualToggle.Visible", manual > 0);
        text(commands, "#ManualToggle", model.showManual() ? "Hide manual captures" : "Show manual captures");
        long shown = model.incidents().size() - manual;
        text(commands, "#IncidentFilter", Units.count(shown, "incident") + "."
            + (manual == 0 ? "" : " " + Units.count(manual, "manual capture") + (model.showManual() ? " shown." : " hidden."))
            + (model.incidents().size() >= ROWS ? " Only the newest " + ROWS + " bundles are listed." : ""));
        for (int i = 0; i < Math.min(ROWS, rows.size()); i++) {
            var incident = rows.get(i).incident();
            int repeats = rows.get(i).count();
            String selector = "#IncidentRows[" + i + "]";
            text(commands, selector + " #Age", incident.createdAt() == null ? "Unknown age" : HyboxChatFormatter.age(incident.createdAt(), model.live().now()));
            text(commands, selector + " #Time", incident.createdAt() == null ? "" : INCIDENT_TIME.format(incident.createdAt()));
            String severity = incident.severity();
            colored(commands, selector + " #Severity", DashboardModel.severityLabel(severity), DashboardModel.severityTone(severity));
            background(commands, selector + " #SeverityPill", DashboardModel.severityTone(severity), "Pill");
            text(commands, selector + " #Trigger", TriggerNames.label(incident.kind()) + (repeats > 1 ? ", " + repeats + " times" : ""));
            String world = HyboxIncidentRenderer.worldShort(incident.world());
            text(commands, selector + " #World", world.isEmpty() ? "Server" : world);
            commands.set(selector + " #World.TooltipText", world.isEmpty() || world.equals(incident.world()) ? "" : incident.world());
            text(commands, selector + " #Headline", HyboxIncidentRenderer.screen(HyboxIncidentRenderer.headline(incident.kind(), incident.world(),
                incident.stallMs(), incident.frozen(), incident.headline())));
            commands.set(selector + " #CleanColumn.Visible", !incident.cleaned());
        }
    }

    private static void triggers(DashboardModel model, UICommandBuilder commands, UIEventBuilder events) {
        commands.append("#Section", "Pages/HyboxDashboardTriggers.ui");
        for (int i = 0; i < model.triggers().size(); i++) commands.append("#TriggerRows", "Pages/HyboxDashboardTriggerRow.ui");
        bind(events, "#TriggerSettingsButton", "settings");
    }

    private static void triggerValues(DashboardModel model, UICommandBuilder commands) {
        for (int i = 0; i < model.triggers().size(); i++) {
            var trigger = model.triggers().get(i);
            String selector = "#TriggerRows[" + i + "]";
            text(commands, selector + " #Trigger", trigger.name());
            var state = trigger.armed() ? DashboardModel.Tone.OK : DashboardModel.Tone.MUTED;
            colored(commands, selector + " #Armed", trigger.armed() ? "On" : "Off", state);
            background(commands, selector + " #StatePill", state, "Pill");
            text(commands, selector + " #Threshold", trigger.threshold());
            text(commands, selector + " #LastFired", DashboardModel.lastFired(trigger, model.incidents(), model.live().now()));
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
            + (snapshot.totalBytes() > 0 ? Units.bytes(snapshot.totalBytes()) + " in "
                + Units.number(snapshot.totalInstances()) + " objects · " : "")
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
            text(commands, selector + " #Value", Units.number(entry.instances()) + " objects, "
                + Units.bytes(entry.bytes()) + ", " + HyboxChatFormatter.heapShare(entry, base) + " of the memory found");
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

    static void background(UICommandBuilder commands, String selector, DashboardModel.Tone tone, String kind) {
        commands.set(selector + ".Background", Value.ref(TONES, tone.name().charAt(0) + tone.name().substring(1).toLowerCase(java.util.Locale.ROOT) + kind));
    }

    static void choice(UICommandBuilder commands, String button, String marker, boolean selected, String idleStyle) {
        commands.set(button + ".Style", Value.ref(TONES, selected ? "SelectedButtonStyle" : idleStyle));
        commands.set(marker + ".Visible", selected);
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
