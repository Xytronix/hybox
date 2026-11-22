package io.github.xytronix.hybox.hytale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.protocol.packets.interface_.CustomUICommand;
import com.hypixel.hytale.protocol.packets.interface_.CustomUICommandType;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import io.github.xytronix.hybox.hytale.HytaleHyboxConfig.HyboxSettings;
import io.github.xytronix.hybox.hytale.HytaleHyboxConfig.SettingKind;
import io.github.xytronix.hybox.hytale.HyboxChatFormatter.Tone;
import io.github.xytronix.hybox.hytale.HyboxSettingsRenderer.Feedback;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.bson.BsonBoolean;
import org.bson.BsonDocument;
import org.bson.BsonDouble;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;

class HyboxSettingsRendererTest {
    private static final HyboxSettings SETTINGS = settings();
    private static final Set<String> FIELD_IDS = Set.of(
        "HeartbeatStall", "StallDegraded", "StallCritical", "TickDegraded", "TickAvgDegraded", "TickAvgCritical",
        "Deadlock", "HeapPressure", "HeapPressurePct", "HeapPressureSustain", "GcPressure", "GcPressurePct",
        "GcPressureWindow", "CpuSaturation", "CpuSaturationPct", "CpuSaturationSustain", "NetSaturation",
        "NetInMbps", "NetOutMbps", "NetSustain", "PlayerDrop", "PlayerDropPct", "PlayerDropWindow",
        "PlayerDropMinPlayers", "LogError", "LogErrorDedupeWindow", "Cooldown", "Debounce", "CaptureEnabled",
        "RetentionMaxCount", "RetentionMaxTotalMiB", "RetentionMaxAgeDays", "MetricsEnabled", "MetricsRetentionDays",
        "MetricsCpu", "MetricsAllocation", "PostIncidentMaxWait", "ThreadDumpCount", "ThreadDumpInterval",
        "JfrMaxAgeMinutes", "JfrMaxSizeMiB", "JfrSampleIntervalSeconds", "JfrSnapshotIntervalMinutes", "JfrOldObjectSampling");

    @Test
    void fieldTableCoversEveryEditableSettingWithoutDuplicateIdsOrBindings() {
        assertEquals(FIELD_IDS, HytaleHyboxConfig.SETTINGS_FIELDS.stream()
            .map(HytaleHyboxConfig.SettingField::id).collect(Collectors.toSet()));
        assertEquals(FIELD_IDS.size(), HytaleHyboxConfig.SETTINGS_FIELDS.size());
        assertEquals(FIELD_IDS.size(), HytaleHyboxConfig.SETTINGS_FIELDS.stream()
            .map(HytaleHyboxConfig.SettingField::eventKey).distinct().count());
        assertEquals(FIELD_IDS.size(), HytaleHyboxConfig.SETTINGS_FIELDS.stream()
            .map(HytaleHyboxConfig.SettingField::configKey).distinct().count());
        assertEquals(Set.of("JfrMaxAgeMinutes", "JfrMaxSizeMiB", "JfrSampleIntervalSeconds",
            "JfrSnapshotIntervalMinutes", "JfrOldObjectSampling"), HytaleHyboxConfig.SETTINGS_FIELDS.stream()
            .filter(HytaleHyboxConfig.SettingField::restartRequired)
            .map(HytaleHyboxConfig.SettingField::id).collect(Collectors.toSet()));
    }

    @Test
    void storageUsageIsShownNextToTheRetentionLimitsInHumanUnits() {
        long mib = 1024L * 1024;
        long gib = 1024 * mib;
        var usage = new io.github.xytronix.hybox.core.retention.RetentionUsage(16, 640 * mib, 15, (long) (8.4 * gib),
            100 * gib, new io.github.xytronix.hybox.core.retention.RetentionPolicy(25, gib, java.time.Duration.ofHours(168)));
        String text = HyboxSettingsRenderer.storage(usage);
        assertEquals("In use now: 16 of 25 bundles · 640 MiB of 1 GiB · kept for 7 days · 8.4 GiB free on disk."
            + " 15 bundles older than 7 days are removed after the next capture.", text);
        var commands = new UICommandBuilder();
        HyboxSettingsRenderer.render(SETTINGS, text, new Feedback("", Tone.OK), commands, new UIEventBuilder());
        assertTrue(Arrays.stream(commands.getCommands())
            .anyMatch(c -> c.selector.equals("#StorageUsage.Text") && c.data.contains("16 of 25 bundles")));
    }

    @Test
    void initializesEveryInputToTheSnapshotValue() {
        var commands = new UICommandBuilder();
        HyboxSettingsRenderer.render(SETTINGS, "", new Feedback("", Tone.OK), commands, new UIEventBuilder());
        assertEquals(FIELD_IDS.size(), Arrays.stream(commands.getCommands())
            .filter(c -> c.selector.endsWith(".Value")).count());
        for (var field : HytaleHyboxConfig.SETTINGS_FIELDS) {
            var command = command(commands, field.selector());
            assertNotNull(command);
            assertEquals(CustomUICommandType.Set, command.type);
            var payload = BsonDocument.parse(command.data);
            if (field.kind() == SettingKind.TOGGLE) {
                assertEquals(SETTINGS.value(field), payload.getBoolean("0").getValue(), field.id());
            } else {
                assertEquals(((Number) SETTINGS.value(field)).doubleValue(), payload.getNumber("0").doubleValue(), field.id());
            }
        }
    }

    @Test
    void saveBindsEveryEditableFieldToItsCurrentClientValue() {
        var events = new UIEventBuilder();
        HyboxSettingsRenderer.render(SETTINGS, "", new Feedback("", Tone.OK), new UICommandBuilder(), events);
        assertEquals(3, events.getEvents().length);
        var save = Arrays.stream(events.getEvents()).filter(e -> e.selector.equals("#SaveButton")).findFirst().orElseThrow();
        assertEquals(CustomUIEventBindingType.Activating, save.type);
        var payload = BsonDocument.parse(save.data);
        assertEquals("save", payload.getString("Action").getValue());
        assertEquals(FIELD_IDS.size() + 1, payload.size());
        for (var field : HytaleHyboxConfig.SETTINGS_FIELDS) {
            assertEquals(field.selector(), payload.getString(field.eventKey()).getValue(), field.id());
        }
        assertTrue(Arrays.stream(events.getEvents()).anyMatch(e -> e.selector.equals("#BackButton") && e.data.contains("back")));
        assertTrue(Arrays.stream(events.getEvents()).anyMatch(e -> e.selector.equals("#Content #CloseButton") && e.data.contains("close")));
    }

    @Test
    void feedbackUsesClientParseableColorsAndNeverReplacesInputs() {
        Pattern alphaColor = Pattern.compile("#[0-9a-fA-F]{6}\\(");
        for (Tone tone : Tone.values()) {
            var commands = new UICommandBuilder();
            HyboxSettingsRenderer.render(SETTINGS, "", new Feedback("Invalid threshold\nTry again", tone), commands, new UIEventBuilder());
            for (var command : commands.getCommands()) {
                assertFalse(command.type == CustomUICommandType.AppendInline || command.type == CustomUICommandType.InsertBeforeInline);
                if (command.type == CustomUICommandType.Set && command.data != null) {
                    assertFalse(alphaColor.matcher(command.data).find(), command.selector + " = " + command.data);
                }
            }
            var feedback = new UICommandBuilder();
            HyboxSettingsRenderer.renderFeedback(new Feedback("Saved", tone), feedback);
            assertTrue(Arrays.stream(feedback.getCommands()).allMatch(c -> c.selector.startsWith("#Feedback.")));
        }
    }

    @Test
    void pageDataDecodesEveryFieldAndKeepsOmittedValues() {
        BsonDocument payload = new BsonDocument("Action", new BsonString("save"));
        Map<String, Object> expected = new LinkedHashMap<>();
        for (var field : HytaleHyboxConfig.SETTINGS_FIELDS) {
            if (field.kind() == SettingKind.TOGGLE) {
                boolean value = !(boolean) SETTINGS.value(field);
                payload.put(field.eventKey(), BsonBoolean.valueOf(value));
                expected.put(field.id(), value);
            } else {
                long value = (long) SETTINGS.value(field) + 1;
                payload.put(field.eventKey(), new BsonDouble(value));
                expected.put(field.id(), value);
            }
        }
        List<String> problems = new ArrayList<>();
        assertEquals(new HyboxSettings(expected), HyboxSettingsPage.PageData.CODEC.decode(payload, new ExtraInfo())
            .settings(SETTINGS, problems));
        assertTrue(problems.isEmpty());
        var omitted = HyboxSettingsPage.PageData.CODEC.decode(BsonDocument.parse("{\"Action\":\"back\"}"), new ExtraInfo());
        assertEquals(SETTINGS, omitted.settings(SETTINGS, problems));
        assertTrue(problems.isEmpty());
        for (var field : HytaleHyboxConfig.SETTINGS_FIELDS) {
            BsonDocument oneField = new BsonDocument(field.eventKey(), payload.get(field.eventKey()));
            Map<String, Object> partial = new LinkedHashMap<>(SETTINGS.values());
            partial.put(field.id(), expected.get(field.id()));
            assertEquals(new HyboxSettings(partial), HyboxSettingsPage.PageData.CODEC.decode(oneField, new ExtraInfo())
                .settings(SETTINGS, problems), field.id());
        }
        assertTrue(problems.isEmpty());
    }

    @Test
    void pageDataRejectsFractionalNonFiniteAndOverflowingNumbersWithoutTruncation() {
        for (var field : HytaleHyboxConfig.SETTINGS_FIELDS) {
            if (field.kind() != SettingKind.NUMBER) continue;
            for (double invalid : new double[] { 1.5, 1e20, -1e20, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY }) {
                var payload = new BsonDocument(field.eventKey(), new BsonDouble(invalid));
                var data = HyboxSettingsPage.PageData.CODEC.decode(payload, new ExtraInfo());
                List<String> problems = new ArrayList<>();
                assertEquals(SETTINGS, data.settings(SETTINGS, problems), field.id() + " = " + invalid);
                assertEquals(1, problems.size(), field.id() + " = " + invalid);
                assertTrue(problems.getFirst().startsWith(field.errorName()));
            }
        }
    }

    private static HyboxSettings settings() {
        Map<String, Object> values = new LinkedHashMap<>();
        int index = 0;
        for (var field : HytaleHyboxConfig.SETTINGS_FIELDS) {
            values.put(field.id(), field.kind() == SettingKind.TOGGLE ? index % 2 == 0
                : field.min() + 1 + index % (field.max() - field.min() - 1));
            index++;
        }
        return new HyboxSettings(values);
    }

    private static CustomUICommand command(UICommandBuilder commands, String selector) {
        return Arrays.stream(commands.getCommands()).filter(c -> selector.equals(c.selector)).findFirst().orElseThrow();
    }
}
