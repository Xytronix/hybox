package io.github.xytronix.hybox.hytale;

import static org.junit.jupiter.api.Assertions.*;

import com.hypixel.hytale.protocol.packets.interface_.CustomUICommand;
import com.hypixel.hytale.protocol.packets.interface_.CustomUICommandType;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import io.github.xytronix.hybox.core.jfr.ProfileMode;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class HyboxDashboardRendererTest {
    private static final String HOSTILE = "mod\"; } Group #Injected { Text: \"not markup";
    private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");

    @Test
    void dynamicValuesAreOnlySetAsTextNeverInlineMarkupOrSelectors() {
        var model = model();
        boolean sawHostileText = false;
        for (String tab : List.of("overview", "incidents", "triggers")) {
            var commands = new UICommandBuilder();
            var events = new UIEventBuilder();
            HyboxDashboardRenderer.render(model, tab, commands, events);
            for (var command : commands.getCommands()) {
                if (command.type == CustomUICommandType.AppendInline) {
                    assertEquals("#ProfileTimerHost", command.selector);
                    assertTrue(command.text.startsWith("TimerLabel { Seconds: 150;"));
                    assertFalse(command.text.contains("Injected"));
                }
                assertNotEquals(CustomUICommandType.InsertBeforeInline, command.type);
                assertFalse(command.selector != null && command.selector.contains(HOSTILE));
                if (Stream.of(command.data, command.text).anyMatch(value -> value != null && value.contains("Injected"))) {
                    sawHostileText = true;
                    assertEquals(CustomUICommandType.Set, command.type);
                    assertTrue(command.selector.endsWith(".Text") || command.selector.endsWith(".TextSpans"), command.selector);
                }
            }
            assertTrue(Arrays.stream(events.getEvents()).noneMatch(event -> event.data != null && event.data.contains(HOSTILE)));
            assertTrue(commands.getCommands().length < 200);
            assertTrue(Arrays.stream(commands.getCommands()).noneMatch(command -> "Pages/HyboxDashboard.ui".equals(command.data)));
        }
        assertTrue(sawHostileText);
    }

    @Test
    void theOpenTabIsMarkedWithTheSelectedStyleAndItsBar() {
        for (String tab : List.of("overview", "incidents", "triggers", "heap")) {
            var commands = new UICommandBuilder();
            HyboxDashboardRenderer.render(model(), tab, commands, new UIEventBuilder());
            for (String other : List.of("Overview", "Incidents", "Triggers", "Heap")) {
                boolean open = other.equalsIgnoreCase(tab);
                assertEquals(open, bool(commands, "#" + other + "Marker.Visible"), other);
                assertEquals(open, command(commands, "#" + other + "Button.Style").data.contains("SelectedButtonStyle"), other);
            }
        }
    }

    @Test
    void profileUsesNativeCountdownAndCaptureUsesUnknownStepWithoutFakeEta() {
        var commands = new UICommandBuilder();
        HyboxDashboardRenderer.render(model(), "overview", commands, new UIEventBuilder());

        assertEquals(0.5, org.bson.BsonDocument.parse(command(commands, "#ProfileProgress.Value").data)
            .getNumber("0").doubleValue());
        var timer = command(commands, "#ProfileTimerHost", CustomUICommandType.AppendInline);
        assertTrue(timer.text.contains("TimerLabel { Seconds: 150;"));
        assertNotNull(command(commands, "#ProfileTimerHost", CustomUICommandType.Clear));
        assertTrue(Arrays.stream(commands.getCommands()).noneMatch(command -> command.selector != null && command.selector.endsWith(".Seconds")));
        assertTrue(command(commands, "#ProfileCaptureAt.Text").data.contains("12:02:30 UTC, started by"));
        assertTrue(command(commands, "#CaptureStep.Text").data.contains("Injected"));
        assertTrue(bool(commands, "#CaptureStep.Visible"));
        assertTrue(Arrays.stream(commands.getCommands()).noneMatch(command -> command.selector != null
            && (command.selector.contains("CaptureProgress") || command.selector.contains("CaptureEta"))));
    }

    @Test
    void incidentRowsBindTheButtonRootToAnIndexRatherThanBundleText() {
        var commands = new UICommandBuilder();
        var events = new UIEventBuilder();
        HyboxDashboardRenderer.render(model(), "incidents", commands, events);

        var rows = Arrays.stream(events.getEvents()).filter(event -> event.selector.startsWith("#IncidentRows")).toList();
        assertEquals(1, rows.size());
        assertEquals("#IncidentRows[0]", rows.getFirst().selector);
        assertTrue(rows.getFirst().data.contains("incident:" + DashboardModel.listVersion(model().incidentRows()) + ":0"), rows.getFirst().data);
        assertNotNull(command(commands, "#IncidentRows[0] #Time.Text"));
        assertNotNull(command(commands, "#IncidentRows[0] #Severity.TextSpans"));
        assertNotNull(command(commands, "#IncidentRows[0] #Trigger.Text"));
        assertNotNull(command(commands, "#IncidentRows[0] #World.Text"));
        assertNotNull(command(commands, "#IncidentRows[0] #Headline.Text"));
    }

    @Test
    void incidentRowsShowTriggerAndSeverityInPlainWords() {
        var incident = new HyboxIncidents.Incident("000011", Path.of("incident-000011.zip"), NOW, "HEARTBEAT_STALL", "World stopped", "CRITICAL", "default", null, false, true);
        var model = new DashboardModel(List.of(), List.of(), null, null, List.of(incident), "",
            List.of(new HyboxChatFormatter.Trigger("DEADLOCK", "Deadlock", false, "When JVM threads deadlock")), "", "", true, "", false, true, true, true, false, true,
            DashboardModel.ProfileForm.DEFAULT, DashboardModel.Heap.EMPTY, new DashboardModel.Live(List.of(), 0, NOW.plusSeconds(720)), false, DashboardModel.ProfileQueue.NONE, false);
        var commands = new UICommandBuilder();
        HyboxDashboardRenderer.render(model, "incidents", commands, new UIEventBuilder());
        assertEquals("Freeze", string(commands, "#IncidentRows[0] #Trigger.Text"));
        assertTrue(command(commands, "#IncidentRows[0] #Severity.TextSpans").data.contains("Critical"));
        assertFalse(command(commands, "#IncidentRows[0] #Severity.TextSpans").data.contains("CRITICAL"));
        var triggers = new UICommandBuilder();
        HyboxDashboardRenderer.render(model, "triggers", triggers, new UIEventBuilder());
        assertTrue(command(triggers, "#TriggerRows[0] #Armed.TextSpans").data.contains("Off"));
        assertEquals("No saved incident", string(triggers, "#TriggerRows[0] #LastFired.Text"));
        assertEquals("12 min ago", string(commands, "#IncidentRows[0] #Age.Text"));
        assertEquals("Info", DashboardModel.severityLabel("INFO"));
        assertEquals("Degraded", DashboardModel.severityLabel("DEGRADED"));
        assertEquals("Unknown", DashboardModel.severityLabel(null));
        assertEquals("#a29d93", DashboardModel.Tone.INFO.color);
    }

    @Test
    void warningBannerIsHiddenForHealthyModel() {
        var healthy = new DashboardModel(List.of(), List.of(), null, null,
            List.of(), "", List.of(), "", "", true, "", false, true, true, true, false, true, DashboardModel.ProfileForm.DEFAULT, DashboardModel.Heap.EMPTY,
            DashboardModel.Live.EMPTY, false, DashboardModel.ProfileQueue.NONE, false);
        var commands = new UICommandBuilder();
        HyboxDashboardRenderer.render(healthy, "overview", commands, new UIEventBuilder());
        assertFalse(org.bson.BsonDocument.parse(command(commands, "#Warnings.Visible").data).getBoolean("0").getValue());
        assertTrue(org.bson.BsonDocument.parse(command(commands, "#NothingRunning.Visible").data).getBoolean("0").getValue());
        assertTrue(Arrays.stream(commands.getCommands()).noneMatch(command -> "#WarningText.TextSpans".equals(command.selector)));
    }

    @Test
    void sectionCaptionsAreShownOnlyForIncidentsAndTriggers() {
        var model = model();
        for (String tab : List.of("overview", "incidents", "triggers")) {
            var commands = new UICommandBuilder();
            HyboxDashboardRenderer.render(model, tab, commands, new UIEventBuilder());
            assertEquals(switch (tab) {
                case "incidents" -> "Select an incident to view its details.";
                case "triggers" -> model.triggerTiming();
                default -> "";
            }, string(commands, "#SectionCaption.Text"));
            assertEquals(!tab.equals("overview"), bool(commands, "#SectionCaption.Visible"));
        }
    }

    @Test
    void runtimeColorsAreClientParseable() {
        var alphaColor = java.util.regex.Pattern.compile("#[0-9a-fA-F]{6}\\(");
        for (var tone : DashboardModel.Tone.values()) {
            var model = new DashboardModel(List.of(new DashboardModel.Tile("t", "v", "d", tone, "SEV")),
                List.of(new DashboardModel.Warning("w", tone)), null, "saving the recording",
                List.of(new HyboxIncidents.Incident("i", Path.of("i.zip"), NOW, "K", "h", "CRITICAL", "w", null, false, true)),
                "", List.of(new HyboxChatFormatter.Trigger("K", "t", tone == DashboardModel.Tone.OK, "x")), "", "", true, "", false, true, true, true, false, true, DashboardModel.ProfileForm.DEFAULT, DashboardModel.Heap.EMPTY,
                new DashboardModel.Live(List.of(new DashboardModel.Stat("TPS", "30.0", "target 30", tone)), 1, NOW), false, DashboardModel.ProfileQueue.NONE, false);
            for (String tab : List.of("overview", "incidents", "triggers", "heap")) {
                var commands = new UICommandBuilder();
                HyboxDashboardRenderer.render(model, tab, commands, new UIEventBuilder());
                for (var command : commands.getCommands()) {
                    if (command.text != null) assertFalse(alphaColor.matcher(command.text).find(), command.selector + " = " + command.text);
                    if (command.type != CustomUICommandType.Set || command.data == null) continue;
                    assertFalse(alphaColor.matcher(command.data).find(), command.selector + " = " + command.data);
                }
            }
        }
    }

    @Test
    void captureStepListDistinguishesCompletedCurrentAndPendingSteps() {
        var commands = new UICommandBuilder();
        var model = new DashboardModel(List.of(), List.of(), null, "saving the recording", List.of(), "", List.of(), "", "", false,
            "", false, true, true, true, false, true, DashboardModel.ProfileForm.DEFAULT, DashboardModel.Heap.EMPTY, DashboardModel.Live.EMPTY, false, DashboardModel.ProfileQueue.NONE, false);
        HyboxDashboardRenderer.render(model, "overview", commands, new UIEventBuilder());
        for (int i = 0; i < 5; i++) {
            assertEquals(i < 3 ? "#4cc38a" : i == 3 ? "#ece9e2" : "#a29d93",
                string(commands, "#CaptureStage" + i + ".Style.TextColor"));
            assertEquals(i == 3, bool(commands, "#CaptureStage" + i + ".Style.RenderBold"));
        }
        assertFalse(bool(commands, "#CaptureStep.Visible"));
    }

    @Test
    void profileRequesterIsHiddenWhenUnknown() {
        var model = new DashboardModel(List.of(), List.of(), new DashboardModel.Profile(NOW, Duration.ofMinutes(5), NOW, null, io.github.xytronix.hybox.core.jfr.ProfileMode.LAG),
            null, List.of(), "", List.of(), "", "", true, "", false, true, true, true, false, true, DashboardModel.ProfileForm.DEFAULT, DashboardModel.Heap.EMPTY,
            DashboardModel.Live.EMPTY, false, DashboardModel.ProfileQueue.NONE, false);
        var commands = new UICommandBuilder();
        HyboxDashboardRenderer.render(model, "overview", commands, new UIEventBuilder());
        assertFalse(command(commands, "#ProfileCaptureAt.Text").data.contains("started by"));
    }

    @Test
    void openIncidentButtonNeedsPermission() {
        var incident = new HyboxIncidents.Incident("000010", Path.of("incident-000010.zip"), NOW, "MANUAL", "Manual capture", "INFO", "server", null, false, true);
        for (boolean ready : List.of(false, true)) {
            var model = new DashboardModel(List.of(), List.of(), null, null, List.of(incident), "", List.of(), "", "", true,
                "Saves thread dumps as an incident bundle.", ready, true, true, true, false, true, DashboardModel.ProfileForm.DEFAULT, DashboardModel.Heap.EMPTY,
                DashboardModel.Live.EMPTY, false, DashboardModel.ProfileQueue.NONE, false);
            var commands = new UICommandBuilder();
            var events = new UIEventBuilder();
            HyboxDashboardRenderer.render(model, "overview", commands, events);
            assertEquals(ready, bool(commands, "#OpenLastButton.Visible"));
            assertEquals("Saves thread dumps as an incident bundle.", string(commands, "#CaptureHint.Text"));
            assertTrue(Arrays.stream(commands.getCommands()).noneMatch(command -> "#CaptureHint.TooltipText".equals(command.selector)));
        }
    }

    @Test
    void openIncidentButtonStaysHiddenWithoutASavedBundle() {
        for (String step : Arrays.asList(null, "collecting")) {
            var model = new DashboardModel(List.of(), List.of(), null, step, List.of(),
                "Loading recent incidents...", List.of(), "", "", false, "", true, true, true, true, false, true, DashboardModel.ProfileForm.DEFAULT, DashboardModel.Heap.EMPTY,
                DashboardModel.Live.EMPTY, false, DashboardModel.ProfileQueue.NONE, false);
            var commands = new UICommandBuilder();
            var events = new UIEventBuilder();
            HyboxDashboardRenderer.render(model, "overview", commands, events);
            assertFalse(bool(commands, "#OpenLastButton.Visible"));
            assertTrue(Arrays.stream(events.getEvents()).noneMatch(event -> "#OpenLastButton".equals(event.selector)));
        }
    }

    @Test
    void navigationBindsTrendsSettingsSavedIncidentAndClose() {
        var events = new UIEventBuilder();
        HyboxDashboardRenderer.bindNavigation(events);
        assertTrue(Arrays.stream(events.getEvents()).anyMatch(event -> "#TrendsButton".equals(event.selector) && event.data.contains("trends")));
        assertTrue(Arrays.stream(events.getEvents()).anyMatch(event -> "#SettingsButton".equals(event.selector) && event.data.contains("settings")));
        assertEquals(1, Arrays.stream(events.getEvents()).filter(event -> "#OpenLastButton".equals(event.selector)
            && event.data.contains("incident:latest")).count());
        assertTrue(Arrays.stream(events.getEvents()).anyMatch(event -> "#DismissButton".equals(event.selector) && event.data.contains("close")));
    }

    @Test
    void trendsButtonIsDisabledOnlyWhenTrendPermissionIsDenied() {
        for (boolean trendsAllowed : List.of(false, true)) {
            var model = new DashboardModel(List.of(), List.of(), null, null, List.of(), "", List.of(), "", "", false,
                "", false, false, false, false, false, trendsAllowed, DashboardModel.ProfileForm.DEFAULT, DashboardModel.Heap.EMPTY,
                DashboardModel.Live.EMPTY, false, DashboardModel.ProfileQueue.NONE, false);
            for (String tab : List.of("overview", "incidents", "triggers")) {
                var commands = new UICommandBuilder();
                HyboxDashboardRenderer.render(model, tab, commands, new UIEventBuilder());
                assertEquals(!trendsAllowed, bool(commands, "#TrendsButton.Disabled"));
            }
        }
    }

    @Test
    void refreshUpdatesValuesInPlaceWithoutRebuildingRowsOrRestartingTheTimer() {
        for (String tab : List.of("overview", "incidents", "triggers")) {
            var commands = new UICommandBuilder();
            HyboxDashboardRenderer.refresh(model(), tab, commands, false);
            assertTrue(Arrays.stream(commands.getCommands()).noneMatch(command -> command.type == CustomUICommandType.Append
                || command.type == CustomUICommandType.AppendInline || command.type == CustomUICommandType.Clear
                || command.type == CustomUICommandType.Remove), tab);
        }
        var triggers = new UICommandBuilder();
        HyboxDashboardRenderer.refresh(model(), "triggers", triggers, false);
        assertTrue(Arrays.stream(triggers.getCommands()).anyMatch(command -> "#TriggerRows[0] #Threshold.Text".equals(command.selector)));
        var restarted = new UICommandBuilder();
        HyboxDashboardRenderer.refresh(model(), "overview", restarted, true);
        assertNotNull(command(restarted, "#ProfileTimerHost", CustomUICommandType.AppendInline));
    }

    @Test
    void stopControlsAreBoundAndOnlyShownWhenPermittedAndApplicable() {
        var events = new UIEventBuilder();
        HyboxDashboardRenderer.render(model(), "overview", new UICommandBuilder(), events);
        for (String[] binding : new String[][] {{"#ProfileSaveNow", "profile:stop"}, {"#ProfileCancel", "profile:cancel"},
            {"#ProfileCancelYes", "profile:cancel:yes"}, {"#ProfileCancelNo", "profile:cancel:no"}, {"#SkipWait", "capture:skip"}}) {
            assertTrue(Arrays.stream(events.getEvents()).anyMatch(event -> binding[0].equals(event.selector) && event.data.contains(binding[1])), binding[0]);
        }
        var profile = new DashboardModel.Profile(NOW, Duration.ofMinutes(5), NOW, null, io.github.xytronix.hybox.core.jfr.ProfileMode.LAG);
        for (boolean stopAllowed : List.of(false, true)) {
            for (boolean cancelAllowed : List.of(false, true)) {
                var running = new DashboardModel(List.of(), List.of(), profile, "waiting for the server to recover", List.of(), "",
                    List.of(), "", "", true, "", false, stopAllowed, cancelAllowed, stopAllowed, false, true, DashboardModel.ProfileForm.DEFAULT, DashboardModel.Heap.EMPTY,
                    DashboardModel.Live.EMPTY, false, DashboardModel.ProfileQueue.NONE, false);
                var commands = new UICommandBuilder();
                HyboxDashboardRenderer.render(running, "overview", commands, new UIEventBuilder());
                assertEquals(stopAllowed, bool(commands, "#ProfileSaveNow.Visible"));
                assertEquals(cancelAllowed, bool(commands, "#ProfileCancel.Visible"));
                assertEquals(stopAllowed, bool(commands, "#SkipWait.Visible"));
                assertEquals(!stopAllowed || !cancelAllowed, bool(commands, "#ProfileStopNote.Visible"));
                if (!stopAllowed || !cancelAllowed) {
                    assertEquals(!stopAllowed && !cancelAllowed
                        ? "Requires /hybox profile stop to save or /hybox profile cancel to cancel."
                        : !stopAllowed ? "Requires /hybox profile stop to save." : "Requires /hybox profile cancel to cancel.",
                        string(commands, "#ProfileStopNote.Text"));
                }
            }
        }
        var confirming = new DashboardModel(List.of(), List.of(), profile, null, List.of(), "",
            List.of(), "", "", true, "", false, true, true, false, false, true, DashboardModel.ProfileForm.DEFAULT, DashboardModel.Heap.EMPTY,
            DashboardModel.Live.EMPTY, true, DashboardModel.ProfileQueue.NONE, false);
        var asked = new UICommandBuilder();
        HyboxDashboardRenderer.render(confirming, "overview", asked, new UIEventBuilder());
        assertFalse(bool(asked, "#ProfileCancel.Visible"), "Cancel asks before it throws the profile away");
        assertTrue(bool(asked, "#ProfileCancelConfirm.Visible"));
        assertTrue(bool(asked, "#ProfileSaveNow.Visible"));
        var dumping = new DashboardModel(List.of(), List.of(), null, "saving the recording", List.of(), "",
            List.of(), "", "", true, "", false, true, true, true, false, true, DashboardModel.ProfileForm.DEFAULT, DashboardModel.Heap.EMPTY,
            DashboardModel.Live.EMPTY, false, DashboardModel.ProfileQueue.NONE, false);
        var commands = new UICommandBuilder();
        HyboxDashboardRenderer.render(dumping, "overview", commands, new UIEventBuilder());
        assertFalse(bool(commands, "#SkipWait.Visible"), "Writing the bundle itself cannot be skipped");
    }

    @Test
    void saveNowShowsSavingUntilTheCaptureStepsTakeOver() {
        var waiting = DashboardModel.from(health(false), DashboardModelTest.STORAGE, NOW, null, List.of(), "", List.of(), "", "", true,
            "", false, true, true, false, true, true, DashboardModel.ProfileForm.DEFAULT, DashboardModel.Heap.EMPTY, DashboardModel.Live.EMPTY, false, DashboardModel.ProfileQueue.NONE, false);
        assertTrue(waiting.profileSaving());
        var commands = new UICommandBuilder();
        HyboxDashboardRenderer.render(waiting, "overview", commands, new UIEventBuilder());
        assertTrue(bool(commands, "#ProfileSaving.Visible"));
        assertFalse(bool(commands, "#NothingRunning.Visible"));
        var capturing = DashboardModel.from(health(true), DashboardModelTest.STORAGE, NOW, null, List.of(), "", List.of(), "", "", true,
            "", false, true, true, false, true, true, DashboardModel.ProfileForm.DEFAULT, DashboardModel.Heap.EMPTY, DashboardModel.Live.EMPTY, false, DashboardModel.ProfileQueue.NONE, false);
        assertFalse(capturing.profileSaving(), "Capture steps replace the saving card once the bundle is being written");
        var stillProfiling = DashboardModel.from(health(false), DashboardModelTest.STORAGE, NOW,
            new DashboardModel.Profile(NOW, Duration.ofMinutes(5), NOW, null, io.github.xytronix.hybox.core.jfr.ProfileMode.LAG),
            List.of(), "", List.of(), "", "", true, "", false, true, true, false, true, true, DashboardModel.ProfileForm.DEFAULT, DashboardModel.Heap.EMPTY,
            DashboardModel.Live.EMPTY, false, DashboardModel.ProfileQueue.NONE, false);
        assertFalse(stillProfiling.profileSaving());
    }

    @Test
    void startProfilePanelOffersDurationsAndSendsTheKeepBufferCheckbox() {
        var form = new DashboardModel.ProfileForm(true, 15, false, false);
        var idle = idle(form, DashboardModel.Heap.EMPTY);
        var commands = new UICommandBuilder();
        var events = new UIEventBuilder();
        HyboxDashboardRenderer.render(idle, "overview", commands, events);
        assertTrue(bool(commands, "#ProfileStartPanel.Visible"));
        for (int minutes : HyboxDashboardRenderer.PROFILE_MINUTES) {
            assertEquals(minutes == 15, bool(commands, "#ProfileMinutes" + minutes + ".Disabled"), "minutes " + minutes);
            assertEquals(minutes == 15, bool(commands, "#ProfileMinutes" + minutes + "Marker.Visible"), "minutes " + minutes);
            assertEquals(minutes == 15, command(commands, "#ProfileMinutes" + minutes + ".Style").data.contains("SelectedButtonStyle"));
            assertTrue(Arrays.stream(events.getEvents()).anyMatch(event -> ("#ProfileMinutes" + minutes).equals(event.selector)
                && event.data.contains("profile:minutes:" + minutes)));
        }
        assertFalse(bool(commands, "#ProfileKeepBuffer.Value"));
        assertFalse(bool(commands, "#ProfileStartButton.Disabled"));
        assertEquals("byte[]", HyboxDashboardRenderer.shortClass("[B"));
        assertEquals("lang.Object[][]", HyboxDashboardRenderer.shortClass("[[Ljava.lang.Object;"));
        assertEquals("chunk.WorldChunk", HyboxDashboardRenderer.shortClass("com.hypixel.hytale.chunk.WorldChunk"));
        var start = Arrays.stream(events.getEvents()).filter(event -> "#ProfileStartButton".equals(event.selector)).findFirst().orElseThrow();
        assertTrue(start.data.contains("profile:start") && start.data.contains(HyboxDashboardRenderer.KEEP_BUFFER_KEY)
            && start.data.contains("#ProfileKeepBuffer.Value"), start.data);

        for (var blocked : List.of(new DashboardModel.ProfileForm(false, 5, true, false), new DashboardModel.ProfileForm(true, 5, true, true))) {
            var denied = new UICommandBuilder();
            HyboxDashboardRenderer.render(idle(blocked, DashboardModel.Heap.EMPTY), "overview", denied, new UIEventBuilder());
            assertTrue(bool(denied, "#ProfileStartButton.Disabled"));
            assertTrue(bool(denied, "#ProfileMinutes1.Disabled"));
            assertEquals(blocked.allowed() ? "Starting profile..." : "Requires permission to use /hybox profile.",
                string(denied, "#ProfileStartNote.Text"));
        }
        var running = new UICommandBuilder();
        HyboxDashboardRenderer.render(model(), "overview", running, new UIEventBuilder());
        assertFalse(bool(running, "#ProfileStartPanel.Visible"), "No second profile while one is running");
    }

    @Test
    void startPanelOffersLagAndMemoryWithMeasuredCostsAndStaysOpenDuringACapture() {
        var capturing = DashboardModel.from(health(true), DashboardModelTest.STORAGE, NOW, null, List.of(), "", List.of(), "", "", true,
            "", false, true, true, false, false, true, DashboardModel.ProfileForm.DEFAULT, DashboardModel.Heap.EMPTY,
            DashboardModel.Live.EMPTY, false, DashboardModel.ProfileQueue.NONE, false);
        var commands = new UICommandBuilder();
        var events = new UIEventBuilder();
        HyboxDashboardRenderer.render(capturing, "overview", commands, events);
        assertTrue(bool(commands, "#CaptureRunning.Visible"));
        assertTrue(bool(commands, "#ProfileStartPanel.Visible"), "A profile can be queued while an incident is saved");
        assertFalse(bool(commands, "#ProfileStartButton.Disabled"));
        assertFalse(bool(commands, "#MemoryStartButton.Disabled"));
        for (String[] button : new String[][] {{"#ProfileStartButton", "profile:start:lag"}, {"#MemoryStartButton", "profile:start:memory"}}) {
            var bound = Arrays.stream(events.getEvents()).filter(event -> button[0].equals(event.selector)).findFirst().orElseThrow();
            assertTrue(bound.data.contains(button[1]) && bound.data.contains(HyboxDashboardRenderer.KEEP_BUFFER_KEY), bound.data);
        }
        String lag = string(commands, "#ProfileLagCost.Text");
        String memory = string(commands, "#ProfileMemoryCost.Text");
        assertTrue(memory.contains("Pauses the server for up to "), memory);
        for (String cost : List.of(lag, memory)) {
            assertTrue(cost.matches(".*\\d.*") && !cost.matches(".*[A-Z]{3}_[A-Z]+.*"), cost);
        }
    }

    @Test
    void queuedProfileShowsItsStatusAndCancelsWithoutAsking() {
        var queue = new DashboardModel.ProfileQueue(new DashboardModel.QueuedStart(ProfileMode.MEMORY, 5, "Steve"), false);
        for (boolean cancelAllowed : List.of(true, false)) {
            var model = DashboardModel.from(health(true), DashboardModelTest.STORAGE, NOW, null, List.of(), "", List.of(), "", "", true,
                "", false, true, cancelAllowed, false, false, true, DashboardModel.ProfileForm.DEFAULT, DashboardModel.Heap.EMPTY,
                DashboardModel.Live.EMPTY, true, queue, false);
            var commands = new UICommandBuilder();
            var events = new UIEventBuilder();
            HyboxDashboardRenderer.render(model, "overview", commands, events);
            assertTrue(bool(commands, "#ProfileQueued.Visible"));
            assertEquals("Memory profile for 5 min, started by Steve.", string(commands, "#ProfileQueuedDetail.Text"));
            assertEquals(cancelAllowed, bool(commands, "#ProfileQueuedCancel.Visible"));
            assertFalse(model.cancelConfirming(), "Only a running profile asks before cancelling");
            assertTrue(bool(commands, "#CaptureRunning.Visible"));
            assertFalse(bool(commands, "#ProfileStartPanel.Visible"));
            assertFalse(bool(commands, "#NothingRunning.Visible"));
            assertTrue(Arrays.stream(events.getEvents()).anyMatch(event -> "#ProfileQueuedCancel".equals(event.selector)
                && event.data.contains("profile:cancel")));
        }
    }

    @Test
    void queuedFinalBundleAsksBeforeItIsCancelledFromTheSavingCard() {
        var queued = DashboardModel.from(health(true), DashboardModelTest.STORAGE, NOW, null, List.of(), "", List.of(), "", "", true,
            "", false, true, true, false, false, true, DashboardModel.ProfileForm.DEFAULT, DashboardModel.Heap.EMPTY,
            DashboardModel.Live.EMPTY, false, new DashboardModel.ProfileQueue(null, true), false);
        var commands = new UICommandBuilder();
        var events = new UIEventBuilder();
        HyboxDashboardRenderer.render(queued, "overview", commands, events);
        assertTrue(bool(commands, "#ProfileSaving.Visible"));
        assertEquals("Profile bundle queued, saves once the incident is saved", string(commands, "#ProfileSavingText.Text"));
        assertTrue(bool(commands, "#ProfileSavingCancel.Visible"));
        assertTrue(Arrays.stream(events.getEvents()).anyMatch(event -> "#ProfileSavingCancel".equals(event.selector)
            && event.data.contains("profile:cancel")));
        assertFalse(bool(commands, "#ProfileCancelConfirm.Visible"));
        var asking = DashboardModel.from(health(true), DashboardModelTest.STORAGE, NOW, null, List.of(), "", List.of(), "", "", true,
            "", false, true, true, false, false, true, DashboardModel.ProfileForm.DEFAULT, DashboardModel.Heap.EMPTY,
            DashboardModel.Live.EMPTY, true, new DashboardModel.ProfileQueue(null, true), false);
        assertTrue(asking.cancelConfirming(), "A queued final bundle holds recorded data, so cancel asks first");
        var asked = new UICommandBuilder();
        HyboxDashboardRenderer.render(asking, "overview", asked, new UIEventBuilder());
        assertTrue(bool(asked, "#ProfileCancelConfirm.Visible"));
        assertFalse(bool(asked, "#ProfileSavingCancel.Visible"));
        var writing = DashboardModel.from(health(false), DashboardModelTest.STORAGE, NOW, null, List.of(), "", List.of(), "", "", true,
            "", false, true, true, false, true, true, DashboardModel.ProfileForm.DEFAULT, DashboardModel.Heap.EMPTY,
            DashboardModel.Live.EMPTY, false, DashboardModel.ProfileQueue.NONE, false);
        var saving = new UICommandBuilder();
        HyboxDashboardRenderer.render(writing, "overview", saving, new UIEventBuilder());
        assertEquals("Saving the profile bundle...", string(saving, "#ProfileSavingText.Text"));
        assertFalse(bool(saving, "#ProfileSavingCancel.Visible"), "A bundle being written cannot be cancelled");
    }

    @Test
    void runningProfileNamesItsModeAndMemoryWarnsAboutTheEndPause() {
        for (ProfileMode mode : ProfileMode.values()) {
            var model = new DashboardModel(List.of(), List.of(), new DashboardModel.Profile(NOW, Duration.ofMinutes(5), NOW, "Steve", mode),
                null, List.of(), "", List.of(), "", "", true, "", false, true, true, true, false, true, DashboardModel.ProfileForm.DEFAULT,
                DashboardModel.Heap.EMPTY, DashboardModel.Live.EMPTY, false, DashboardModel.ProfileQueue.NONE, false);
            var commands = new UICommandBuilder();
            HyboxDashboardRenderer.render(model, "overview", commands, new UIEventBuilder());
            assertEquals(mode == ProfileMode.MEMORY ? "Memory profile" : "Lag profile", string(commands, "#ProfileTitle.Text"));
            assertEquals(mode == ProfileMode.MEMORY, string(commands, "#ProfileCaptureAt.Text").contains("Pauses the server for up to"));
        }
    }

    @Test
    void heapTabAsksBeforeWalkingAndReusesShareRowsForTheResult() {
        var empty = new UICommandBuilder();
        var events = new UIEventBuilder();
        HyboxDashboardRenderer.render(idle(DashboardModel.ProfileForm.DEFAULT, DashboardModel.Heap.EMPTY), "heap", empty, events);
        assertEquals("Walk heap", string(empty, "#HeapWalkButton.Text"));
        assertFalse(bool(empty, "#HeapConfirm.Visible"));
        assertTrue(bool(empty, "#HeapMarker.Visible"));
        for (String[] binding : new String[][] {{"#HeapWalkButton", "heap:ask"}, {"#HeapConfirmButton", "heap:walk"}, {"#HeapCancelButton", "heap:cancel"}}) {
            assertTrue(Arrays.stream(events.getEvents()).anyMatch(event -> binding[0].equals(event.selector) && event.data.contains(binding[1])), binding[0]);
        }
        var confirming = new UICommandBuilder();
        HyboxDashboardRenderer.render(idle(DashboardModel.ProfileForm.DEFAULT,
            new DashboardModel.Heap(true, true, false, null, null, null)), "heap", confirming, new UIEventBuilder());
        assertTrue(bool(confirming, "#HeapConfirm.Visible"));
        assertTrue(bool(confirming, "#HeapWalkButton.Disabled"));

        List<io.github.xytronix.hybox.core.capture.HeapHistogram.Entry> entries = new java.util.ArrayList<>();
        entries.add(new io.github.xytronix.hybox.core.capture.HeapHistogram.Entry(HOSTILE, 7, 600));
        for (int i = 0; i < 29; i++) entries.add(new io.github.xytronix.hybox.core.capture.HeapHistogram.Entry("com.example.Type" + i, 1, 10));
        var snapshot = new io.github.xytronix.hybox.core.capture.HeapHistogram.Snapshot(entries, 100, 1000);
        var heap = new DashboardModel.Heap(true, false, false, snapshot, NOW, null);
        var commands = new UICommandBuilder();
        HyboxDashboardRenderer.render(idle(DashboardModel.ProfileForm.DEFAULT, heap), "heap", commands, new UIEventBuilder());
        assertEquals(DashboardModel.Heap.ROWS, Arrays.stream(commands.getCommands())
            .filter(command -> command.type == CustomUICommandType.Append && "#HeapRows".equals(command.selector)
                && "Pages/HyboxIncidentRow.ui".equals(command.text)).count());
        assertTrue(bool(commands, "#HeapRows[0] #Share.Visible"));
        assertEquals(0.6, org.bson.BsonDocument.parse(command(commands, "#HeapRows[0] #Share.Value").data).getNumber("0").doubleValue(), 1e-6);
        assertTrue(string(commands, "#HeapRows[0] #Value.Text").endsWith("60.0% of the memory found"));
        assertEquals("Walk again", string(commands, "#HeapWalkButton.Text"));
        assertTrue(string(commands, "#HeapSummary.Text").startsWith("Taken at 12:00:00 UTC"));
        for (var command : commands.getCommands()) {
            if (Stream.of(command.data, command.text).anyMatch(value -> value != null && value.contains("Injected"))) {
                assertEquals(CustomUICommandType.Set, command.type);
                assertTrue(command.selector.endsWith(".Text") || command.selector.endsWith(".TooltipText"), command.selector);
            }
        }

        var denied = new UICommandBuilder();
        HyboxDashboardRenderer.render(idle(DashboardModel.ProfileForm.DEFAULT,
            new DashboardModel.Heap(false, true, false, null, null, null)), "heap", denied, new UIEventBuilder());
        assertTrue(bool(denied, "#HeapWalkButton.Disabled"));
        assertFalse(bool(denied, "#HeapConfirm.Visible"));
        assertEquals("Requires permission to use /hybox histogram.", string(denied, "#HeapSummary.Text"));
    }

    private static DashboardModel idle(DashboardModel.ProfileForm form, DashboardModel.Heap heap) {
        return new DashboardModel(List.of(), List.of(), null, null, List.of(), "", List.of(), "", "", true,
            "", false, true, true, false, false, true, form, heap, DashboardModel.Live.EMPTY, false, DashboardModel.ProfileQueue.NONE, false);
    }

    private static HyboxRuntime.Health health(boolean capturing) {
        return new HyboxRuntime.Health(true, NOW, null, capturing, capturing ? "collecting" : "idle", null, null, null, null,
            0, 0, 0, 0, null, true, new CallbackExecutor.Health(0, 0, 0, 0, 0, 0, null), new HytaleMixins.ProblemCounts(0, 0));
    }

    private static boolean bool(UICommandBuilder commands, String selector) {
        return org.bson.BsonDocument.parse(command(commands, selector).data).getBoolean("0").getValue();
    }

    private static String string(UICommandBuilder commands, String selector) {
        return org.bson.BsonDocument.parse(command(commands, selector).data).getString("0").getValue();
    }

    private static CustomUICommand command(UICommandBuilder commands, String selector, CustomUICommandType type) {
        return Arrays.stream(commands.getCommands()).filter(command -> selector.equals(command.selector) && command.type == type).findFirst().orElseThrow();
    }

    private static CustomUICommand command(UICommandBuilder commands, String selector) {
        return Arrays.stream(commands.getCommands()).filter(command -> selector.equals(command.selector)).findFirst().orElseThrow();
    }

    private static DashboardModel model() {
        return new DashboardModel(List.of(new DashboardModel.Tile(HOSTILE, HOSTILE, HOSTILE, DashboardModel.Tone.WARN, HOSTILE)),
            List.of(new DashboardModel.Warning(HOSTILE, DashboardModel.Tone.WARN)),
            new DashboardModel.Profile(NOW.minusSeconds(150), Duration.ofMinutes(5), NOW, HOSTILE, io.github.xytronix.hybox.core.jfr.ProfileMode.LAG), HOSTILE,
            List.of(new HyboxIncidents.Incident(HOSTILE, Path.of("example.zip"), NOW, HOSTILE, HOSTILE, HOSTILE, HOSTILE, null, false, true)),
            "", List.of(new HyboxChatFormatter.Trigger(HOSTILE, HOSTILE, true, HOSTILE)), HOSTILE, HOSTILE, true, "", false, true, true, true, false, true, DashboardModel.ProfileForm.DEFAULT, DashboardModel.Heap.EMPTY,
            new DashboardModel.Live(List.of(new DashboardModel.Stat(HOSTILE, HOSTILE, HOSTILE, DashboardModel.Tone.WARN)), 1, NOW), false, DashboardModel.ProfileQueue.NONE, false);
    }
}
