package io.github.xytronix.hybox.hytale;

import static org.junit.jupiter.api.Assertions.*;

import com.hypixel.hytale.protocol.packets.interface_.CustomUICommand;
import com.hypixel.hytale.protocol.packets.interface_.CustomUICommandType;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
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
    void profileUsesNativeCountdownAndCaptureUsesUnknownStepWithoutFakeEta() {
        var commands = new UICommandBuilder();
        HyboxDashboardRenderer.render(model(), "overview", commands, new UIEventBuilder());

        assertEquals(0.5, org.bson.BsonDocument.parse(command(commands, "#ProfileProgress.Value").data)
            .getNumber("0").doubleValue());
        var timer = command(commands, "#ProfileTimerHost", CustomUICommandType.AppendInline);
        assertTrue(timer.text.contains("TimerLabel { Seconds: 150;"));
        assertNotNull(command(commands, "#ProfileTimerHost", CustomUICommandType.Clear));
        assertTrue(Arrays.stream(commands.getCommands()).noneMatch(command -> command.selector != null && command.selector.endsWith(".Seconds")));
        assertTrue(command(commands, "#ProfileCaptureAt.Text").data.contains("12:02:30 UTC · started by"));
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

        assertEquals(1, events.getEvents().length);
        assertEquals("#IncidentRows[0]", events.getEvents()[0].selector);
        assertTrue(events.getEvents()[0].data.contains("incident:0"));
        assertNotNull(command(commands, "#IncidentRows[0] #Time.Text"));
        assertNotNull(command(commands, "#IncidentRows[0] #Severity.TextSpans"));
        assertNotNull(command(commands, "#IncidentRows[0] #Trigger.Text"));
        assertNotNull(command(commands, "#IncidentRows[0] #World.Text"));
        assertNotNull(command(commands, "#IncidentRows[0] #Headline.Text"));
    }

    @Test
    void warningBannerIsHiddenForHealthyModel() {
        var healthy = new DashboardModel(List.of(), List.of(), null, null,
            List.of(), "", List.of(), "", "", true, "", false, true, true, true, false, true, DashboardModel.ProfileForm.DEFAULT, DashboardModel.Heap.EMPTY);
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
                List.of(new DashboardModel.Warning("w", tone)), null, "dumping recording",
                List.of(new HyboxIncidents.Incident("i", Path.of("i.zip"), NOW, "K", "h", "CRITICAL", "w")),
                "", List.of(new HyboxChatFormatter.Trigger("t", tone == DashboardModel.Tone.OK, "x")), "", "", true, "", false, true, true, true, false, true, DashboardModel.ProfileForm.DEFAULT, DashboardModel.Heap.EMPTY);
            for (String tab : List.of("overview", "incidents", "triggers")) {
                var commands = new UICommandBuilder();
                HyboxDashboardRenderer.render(model, tab, commands, new UIEventBuilder());
                for (var command : commands.getCommands()) {
                    if (command.type != CustomUICommandType.Set || command.data == null) continue;
                    assertFalse(alphaColor.matcher(command.data).find(), command.selector + " = " + command.data);
                }
            }
        }
    }

    @Test
    void captureStepListDistinguishesCompletedCurrentAndPendingSteps() {
        var commands = new UICommandBuilder();
        var model = new DashboardModel(List.of(), List.of(), null, "dumping recording", List.of(), "", List.of(), "", "", false,
            "", false, true, true, true, false, true, DashboardModel.ProfileForm.DEFAULT, DashboardModel.Heap.EMPTY);
        HyboxDashboardRenderer.render(model, "overview", commands, new UIEventBuilder());
        for (int i = 0; i < 5; i++) {
            assertEquals(i < 3 ? "#80c991" : i == 3 ? "#d3e3ed" : "#96a9be",
                string(commands, "#CaptureStage" + i + ".Style.TextColor"));
            assertEquals(i == 3, bool(commands, "#CaptureStage" + i + ".Style.RenderBold"));
        }
        assertFalse(bool(commands, "#CaptureStep.Visible"));
    }

    @Test
    void profileRequesterIsHiddenWhenUnknown() {
        var model = new DashboardModel(List.of(), List.of(), new DashboardModel.Profile(NOW, Duration.ofMinutes(5), NOW, null),
            null, List.of(), "", List.of(), "", "", true, "", false, true, true, true, false, true, DashboardModel.ProfileForm.DEFAULT, DashboardModel.Heap.EMPTY);
        var commands = new UICommandBuilder();
        HyboxDashboardRenderer.render(model, "overview", commands, new UIEventBuilder());
        assertFalse(command(commands, "#ProfileCaptureAt.Text").data.contains("started by"));
    }

    @Test
    void openIncidentButtonAppearsOnlyAfterCompletedCaptureReload() {
        var incident = new HyboxIncidents.Incident("000010", Path.of("incident-000010.zip"), NOW, "MANUAL", "Manual capture", "INFO", "server");
        for (boolean ready : List.of(false, true)) {
            var model = new DashboardModel(List.of(), List.of(), null, null, List.of(incident), "", List.of(), "", "", true,
                "Saves thread dumps as an incident bundle.", ready, true, true, true, false, true, DashboardModel.ProfileForm.DEFAULT, DashboardModel.Heap.EMPTY);
            var commands = new UICommandBuilder();
            var events = new UIEventBuilder();
            HyboxDashboardRenderer.render(model, "overview", commands, events);
            assertEquals(ready, bool(commands, "#OpenLastButton.Visible"));
            assertEquals("Saves thread dumps as an incident bundle.", string(commands, "#CaptureHint.Text"));
            assertTrue(Arrays.stream(commands.getCommands()).noneMatch(command -> "#CaptureHint.TooltipText".equals(command.selector)));
        }
    }

    @Test
    void openIncidentButtonStaysHiddenUntilHistoryReloadsAndDuringCapture() {
        var incident = new HyboxIncidents.Incident("000010", Path.of("incident-000010.zip"), NOW, "MANUAL", "Manual capture", "INFO", "server");
        for (String step : Arrays.asList(null, "collecting")) {
            var model = new DashboardModel(List.of(), List.of(), null, step, step == null ? List.of() : List.of(incident),
                "Loading recent incidents…", List.of(), "", "", false, "", true, true, true, true, false, true, DashboardModel.ProfileForm.DEFAULT, DashboardModel.Heap.EMPTY);
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
            && event.data.contains("incident:0")).count());
        assertTrue(Arrays.stream(events.getEvents()).anyMatch(event -> "#DismissButton".equals(event.selector) && event.data.contains("close")));
    }

    @Test
    void trendsButtonIsDisabledOnlyWhenTrendPermissionIsDenied() {
        for (boolean trendsAllowed : List.of(false, true)) {
            var model = new DashboardModel(List.of(), List.of(), null, null, List.of(), "", List.of(), "", "", false,
                "", false, false, false, false, false, trendsAllowed, DashboardModel.ProfileForm.DEFAULT, DashboardModel.Heap.EMPTY);
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
        for (String[] binding : new String[][] {{"#ProfileSaveNow", "profile:stop"}, {"#ProfileCancel", "profile:cancel"}, {"#SkipWait", "capture:skip"}}) {
            assertTrue(Arrays.stream(events.getEvents()).anyMatch(event -> binding[0].equals(event.selector) && event.data.contains(binding[1])), binding[0]);
        }
        var profile = new DashboardModel.Profile(NOW, Duration.ofMinutes(5), NOW, null);
        for (boolean stopAllowed : List.of(false, true)) {
            for (boolean cancelAllowed : List.of(false, true)) {
                var running = new DashboardModel(List.of(), List.of(), profile, "waiting for post-incident evidence", List.of(), "",
                    List.of(), "", "", true, "", false, stopAllowed, cancelAllowed, stopAllowed, false, true, DashboardModel.ProfileForm.DEFAULT, DashboardModel.Heap.EMPTY);
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
        var dumping = new DashboardModel(List.of(), List.of(), null, "dumping recording", List.of(), "",
            List.of(), "", "", true, "", false, true, true, true, false, true, DashboardModel.ProfileForm.DEFAULT, DashboardModel.Heap.EMPTY);
        var commands = new UICommandBuilder();
        HyboxDashboardRenderer.render(dumping, "overview", commands, new UIEventBuilder());
        assertFalse(bool(commands, "#SkipWait.Visible"), "Writing the bundle itself cannot be skipped");
    }

    @Test
    void saveNowShowsSavingUntilTheCaptureStepsTakeOver() {
        var waiting = DashboardModel.from(health(false), DashboardModelTest.STORAGE, NOW, null, List.of(), "", List.of(), "", "", true,
            "", false, true, true, false, true, true, DashboardModel.ProfileForm.DEFAULT, DashboardModel.Heap.EMPTY);
        assertTrue(waiting.profileSaving());
        var commands = new UICommandBuilder();
        HyboxDashboardRenderer.render(waiting, "overview", commands, new UIEventBuilder());
        assertTrue(bool(commands, "#ProfileSaving.Visible"));
        assertFalse(bool(commands, "#NothingRunning.Visible"));
        var capturing = DashboardModel.from(health(true), DashboardModelTest.STORAGE, NOW, null, List.of(), "", List.of(), "", "", true,
            "", false, true, true, false, true, true, DashboardModel.ProfileForm.DEFAULT, DashboardModel.Heap.EMPTY);
        assertFalse(capturing.profileSaving(), "Capture steps replace the saving card once the bundle is being written");
        var stillProfiling = DashboardModel.from(health(false), DashboardModelTest.STORAGE, NOW,
            new DashboardModel.Profile(NOW, Duration.ofMinutes(5), NOW, null),
            List.of(), "", List.of(), "", "", true, "", false, true, true, false, true, true, DashboardModel.ProfileForm.DEFAULT, DashboardModel.Heap.EMPTY);
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
            assertEquals(blocked.allowed() ? "Starting profile…" : "Requires permission to use /hybox profile.",
                string(denied, "#ProfileStartNote.Text"));
        }
        var running = new UICommandBuilder();
        HyboxDashboardRenderer.render(model(), "overview", running, new UIEventBuilder());
        assertFalse(bool(running, "#ProfileStartPanel.Visible"), "No second profile while one is running");
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
        assertTrue(string(commands, "#HeapRows[0] #Value.Text").endsWith("60.0% of objects found"));
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
            "", false, true, true, false, false, true, form, heap);
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
            new DashboardModel.Profile(NOW.minusSeconds(150), Duration.ofMinutes(5), NOW, HOSTILE), HOSTILE,
            List.of(new HyboxIncidents.Incident(HOSTILE, Path.of("example.zip"), NOW, HOSTILE, HOSTILE, HOSTILE, HOSTILE)),
            "", List.of(new HyboxChatFormatter.Trigger(HOSTILE, true, HOSTILE)), HOSTILE, HOSTILE, true, "", false, true, true, true, false, true, DashboardModel.ProfileForm.DEFAULT, DashboardModel.Heap.EMPTY);
    }
}
