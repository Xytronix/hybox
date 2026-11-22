package io.github.xytronix.hybox.hytale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.xytronix.hybox.hytale.HytaleHyboxConfig.HyboxSettings;
import io.github.xytronix.hybox.core.config.HyboxConfig;

class HytaleHyboxConfigTest {

    @Test
    void previousDefaultArtifactListGainsMetrics(@TempDir Path dataDir) throws Exception {
        Files.writeString(HytaleHyboxConfig.path(dataDir), """
            {"Version": 1, "Capture": {"Artifacts":
              ["server", "heartbeats", "worlds", "plugins", "serverLog", "threads", "env", "report", "jfr"]}}
            """, StandardCharsets.UTF_8);

        HyboxConfig config = HytaleHyboxConfig.loadOrCreate(dataDir, System.getLogger("hytale-config-test"));

        assertEquals(java.util.Set.of("jfr", "report", "env", "threads", "serverLog", "plugins", "worlds", "heartbeats",
            "server", "metrics"), config.capturePolicy().artifacts());
    }

    @Test
    void customArtifactListIsKeptAsWritten(@TempDir Path dataDir) throws Exception {
        Files.writeString(HytaleHyboxConfig.path(dataDir), """
            {"Version": 1, "Capture": {"Artifacts": ["jfr", "report", "env", "threads", "serverLog", "plugins", "worlds"]}}
            """, StandardCharsets.UTF_8);

        HyboxConfig config = HytaleHyboxConfig.loadOrCreate(dataDir, System.getLogger("hytale-config-test"));

        assertEquals(java.util.Set.of("jfr", "report", "env", "threads", "serverLog", "plugins", "worlds"),
            config.capturePolicy().artifacts());
    }

    @Test
    void parsesCaptureAndDisabledEventsFields(@TempDir Path dataDir) throws Exception {
        Path configPath = HytaleHyboxConfig.path(dataDir);
        Files.writeString(configPath, """
            {
              "Version": 1,
              "Jfr": {
                "DisabledEvents": ["io.github.xytronix.hybox.marker", "jdk.CPULoad"]
              },
              "Capture": {
                "Enabled": false,
                "AllowPluginExtras": false,
                "LogTailLines": 12,
                "IncludeServerLog": true,
                "IncludeModConfigs": true,
                "AllowModConfigOptIn": false,
                "RedactPatterns": ["CUSTOM_SECRET_[A-Z0-9]+"]
              }
            }
            """, StandardCharsets.UTF_8);

        HyboxConfig config = HytaleHyboxConfig.loadOrCreate(dataDir, System.getLogger("hytale-config-test"));

        assertEquals(List.of("io.github.xytronix.hybox.marker", "jdk.CPULoad"), config.jfrDisabledEvents());
        assertFalse(config.capturePolicy().enabled());
        assertFalse(config.capturePolicy().allowPluginExtras());
        assertEquals(12, config.capturePolicy().logTailLines());
        assertTrue(config.capturePolicy().includeServerLog());
        assertTrue(config.capturePolicy().includeModConfigs());
        assertFalse(config.capturePolicy().allowModConfigOptIn());
        assertEquals(List.of("CUSTOM_SECRET_[A-Z0-9]+"), config.capturePolicy().redactPatterns());
    }

    @Test
    void skipsNullListEntriesWithoutDiscardingCustomSettings(@TempDir Path dataDir) throws Exception {
        Path configPath = HytaleHyboxConfig.path(dataDir);
        String json = """
            {
              "Version": 1,
              "Jfr": {
                "RecordingName": "custom-recorder",
                "DisabledEvents": [null, "jdk.CPULoad", null]
              },
              "Capture": {
                "Enabled": false,
                "LogTailLines": 12,
                "RedactPatterns": [null, "CUSTOM_SECRET_[A-Z0-9]+", null]
              }
            }
            """;
        Files.writeString(configPath, json, StandardCharsets.UTF_8);

        HyboxConfig config = HytaleHyboxConfig.loadOrCreate(dataDir, System.getLogger("hytale-config-test"));

        assertEquals(List.of("jdk.CPULoad"), config.jfrDisabledEvents());
        assertEquals(List.of("CUSTOM_SECRET_[A-Z0-9]+"), config.capturePolicy().redactPatterns());
        assertEquals("custom-recorder", config.jfrRecordingName());
        assertFalse(config.capturePolicy().enabled());
        assertEquals(12, config.capturePolicy().logTailLines());
        assertEquals(json, Files.readString(configPath, StandardCharsets.UTF_8));
    }

    @Test
    void defaultsNewFieldsWhenOmitted(@TempDir Path dataDir) throws Exception {
        Path configPath = HytaleHyboxConfig.path(dataDir);
        Files.writeString(configPath, """
            {
              "Version": 1
            }
            """, StandardCharsets.UTF_8);

        HyboxConfig config = HytaleHyboxConfig.loadOrCreate(dataDir, System.getLogger("hytale-config-test"));

        assertTrue(config.jfrDisabledEvents().isEmpty());
        assertTrue(config.capturePolicy().enabled());
        assertTrue(config.capturePolicy().allowPluginExtras());
        assertEquals(500, config.capturePolicy().logTailLines());
        assertFalse(config.capturePolicy().includeServerLog());
        assertFalse(config.capturePolicy().includeModConfigs());
        assertTrue(config.capturePolicy().allowModConfigOptIn());
        assertFalse(config.capturePolicy().redactPatterns().isEmpty());
        assertEquals(Duration.ofMinutes(2), config.postIncidentMaxWait());
        assertEquals(Duration.ofMinutes(10), config.jfrSnapshotInterval());
        assertEquals(Duration.ofSeconds(10), config.jfrSampleInterval());
        assertFalse(config.jfrOldObjectSampling());
        assertEquals(100, config.triggerPolicy().tickAvgDegradedMs());
        assertEquals(250, config.triggerPolicy().tickAvgCriticalMs());
        assertFalse(config.triggerPolicy().detectors().modules().logError());
        assertEquals(Duration.ofMinutes(15), config.triggerPolicy().detectors().logErrorDedupeWindow());
        assertTrue(config.triggerPolicy().detectors().logErrorIgnore().isEmpty());
    }

    @Test
    void parsesJfrOldObjectSampling(@TempDir Path dataDir) throws Exception {
        Path configPath = HytaleHyboxConfig.path(dataDir);
        Files.writeString(configPath, """
            {
              "Version": 1,
              "Jfr": {
                "OldObjectSampling": true
              }
            }
            """, StandardCharsets.UTF_8);

        HyboxConfig config = HytaleHyboxConfig.loadOrCreate(dataDir, System.getLogger("hytale-config-test"));

        assertTrue(config.jfrOldObjectSampling());
    }

    @Test
    void parsesJfrSampleInterval(@TempDir Path dataDir) throws Exception {
        Path configPath = HytaleHyboxConfig.path(dataDir);
        Files.writeString(configPath, """
            {
              "Version": 1,
              "Jfr": {
                "SampleInterval": "PT30S"
              }
            }
            """, StandardCharsets.UTF_8);

        HyboxConfig config = HytaleHyboxConfig.loadOrCreate(dataDir, System.getLogger("hytale-config-test"));

        assertEquals(Duration.ofSeconds(30), config.jfrSampleInterval());
    }

    @Test
    void parsesTickAvgThresholds(@TempDir Path dataDir) throws Exception {
        Path configPath = HytaleHyboxConfig.path(dataDir);
        Files.writeString(configPath, """
            {
              "Version": 1,
              "Trigger": {
                "TickAvgDegradedMs": 150,
                "TickAvgCriticalMs": 400
              }
            }
            """, StandardCharsets.UTF_8);

        HyboxConfig config = HytaleHyboxConfig.loadOrCreate(dataDir, System.getLogger("hytale-config-test"));

        assertEquals(150, config.triggerPolicy().tickAvgDegradedMs());
        assertEquals(400, config.triggerPolicy().tickAvgCriticalMs());
        assertEquals(2000, config.triggerPolicy().stallDegradedMs());
        assertEquals(10000, config.triggerPolicy().stallCriticalMs());
    }

    @Test
    void parsesLogErrorIgnorePatterns(@TempDir Path dataDir) throws Exception {
        Path configPath = HytaleHyboxConfig.path(dataDir);
        Files.writeString(configPath, """
            {
              "Version": 1,
              "Trigger": {
                "LogErrorIgnore": ["Non-finite entity rotation", "(unclosed"]
              }
            }
            """, StandardCharsets.UTF_8);

        HyboxConfig config = HytaleHyboxConfig.loadOrCreate(dataDir, System.getLogger("hytale-config-test"));

        List<Pattern> ignore = config.triggerPolicy().detectors().logErrorIgnore();
        assertEquals(1, ignore.size());
        assertTrue(ignore.get(0).matcher(
            "[BoundingBox] Non-finite entity rotation reached applyRotation").find());
    }

    @Test
    void parsesMetricsCpuAndAllocation(@TempDir Path dataDir) throws Exception {
        Path configPath = HytaleHyboxConfig.path(dataDir);
        Files.writeString(configPath, """
            {
              "Version": 1,
              "Metrics": {
                "Cpu": false,
                "Allocation": true
              }
            }
            """, StandardCharsets.UTF_8);

        HyboxConfig config = HytaleHyboxConfig.loadOrCreate(dataDir, System.getLogger("hytale-config-test"));

        assertFalse(config.metricsCpu());
        assertTrue(config.metricsAllocation());
    }

    @Test
    void settingsReadAndWriteEveryFieldUsingItsExactKeyAndUnit(@TempDir Path dataDir) throws Exception {
        Path configPath = HytaleHyboxConfig.path(dataDir);
        var expectedDocument = settingsDocument();
        Files.writeString(configPath, expectedDocument.toString(), StandardCharsets.UTF_8);
        HyboxSettings original = HytaleHyboxConfig.read(dataDir, SETTINGS_LOGGER);
        var expectedValues = new java.util.LinkedHashMap<String, Object>();

        assertEquals(SETTINGS_CASES.size(), HytaleHyboxConfig.SETTINGS_FIELDS.size());
        for (SettingCase setting : SETTINGS_CASES) {
            var field = settingField(setting.id());
            assertEquals(setting.configKey(), field.configKey(), setting.id());
            assertEquals(setting.unit(), field.unit().name(), setting.id());
            assertEquals(setting.unit().equals("BOOLEAN") ? "TOGGLE" : "NUMBER", field.kind().name(), setting.id());
            assertEquals(setting.original(), original.value(field), setting.id());
            expectedValues.put(setting.id(), setting.original());
        }
        assertEquals(new HyboxSettings(expectedValues), original);
        assertTrue(original.validate().isEmpty());

        for (SettingCase setting : SETTINGS_CASES) {
            HyboxSettings submitted = changedSettings(original, java.util.Map.of(setting.id(), setting.updated()));
            assertTrue(submitted.validate().isEmpty(), setting.id());

            HytaleHyboxConfig.write(dataDir, original, submitted, SETTINGS_LOGGER);

            putConfigValue(expectedDocument, setting.configKey(), storedSettingValue(setting.unit(), setting.updated()));
            var savedDocument = com.google.gson.JsonParser.parseString(Files.readString(configPath)).getAsJsonObject();
            assertEquals(expectedDocument, savedDocument, setting.id());
            assertEquals(storedSettingValue(setting.unit(), setting.updated()),
                configValue(savedDocument, setting.configKey()), setting.id());
            HyboxSettings restored = HytaleHyboxConfig.read(dataDir, SETTINGS_LOGGER);
            assertEquals(submitted, restored, setting.id());
            original = restored;
        }
    }

    @Test
    void settingsReadDefaultsWithoutCreatingTheFile(@TempDir Path dataDir) {
        HyboxSettings settings = HytaleHyboxConfig.read(dataDir, SETTINGS_LOGGER);

        assertEquals(SETTINGS_CASES.size(), settings.values().size());
        for (var field : HytaleHyboxConfig.SETTINGS_FIELDS) {
            assertEquals(field.kind() == HytaleHyboxConfig.SettingKind.TOGGLE ? Boolean.class : Long.class,
                settings.value(field).getClass(), field.id());
        }
        assertEquals(true, settings.value(settingField("HeartbeatStall")));
        assertEquals(false, settings.value(settingField("LogError")));
        assertEquals(30L, settings.value(settingField("Cooldown")));
        assertEquals(2L, settings.value(settingField("Debounce")));
        assertEquals(2000L, settings.value(settingField("StallDegraded")));
        assertEquals(25L, settings.value(settingField("RetentionMaxCount")));
        assertEquals(1024L, settings.value(settingField("RetentionMaxTotalMiB")));
        assertEquals(7L, settings.value(settingField("RetentionMaxAgeDays")));
        assertEquals(false, settings.value(settingField("MetricsEnabled")));
        assertEquals(7L, settings.value(settingField("MetricsRetentionDays")));
        assertEquals(15L, settings.value(settingField("JfrMaxAgeMinutes")));
        assertEquals(256L, settings.value(settingField("JfrMaxSizeMiB")));
        assertEquals(10L, settings.value(settingField("JfrSampleIntervalSeconds")));
        assertEquals(10L, settings.value(settingField("JfrSnapshotIntervalMinutes")));
        assertEquals(false, settings.value(settingField("JfrOldObjectSampling")));
        assertTrue(settings.validate().isEmpty());
        assertFalse(Files.exists(HytaleHyboxConfig.path(dataDir)));
    }

    @Test
    void settingsAcceptAndRoundTripEveryNumericTableBoundary(@TempDir Path dataDir) {
        HyboxSettings original = HytaleHyboxConfig.read(dataDir, SETTINGS_LOGGER);

        for (var field : HytaleHyboxConfig.SETTINGS_FIELDS) {
            if (field.kind() != HytaleHyboxConfig.SettingKind.NUMBER) continue;
            for (long boundary : new long[] {field.min(), field.max()}) {
                HyboxSettings submitted = settingsWithNumericPartner(original, field, boundary);
                String label = field.id() + "=" + boundary;
                assertEquals(List.of(), submitted.validate(), label);

                HytaleHyboxConfig.write(dataDir, original, submitted, SETTINGS_LOGGER);

                HyboxSettings restored = HytaleHyboxConfig.read(dataDir, SETTINGS_LOGGER);
                assertEquals(submitted, restored, label);
                original = restored;
            }
        }
    }

    @Test
    void settingsAcceptBothValuesOfEveryToggle(@TempDir Path dataDir) {
        HyboxSettings original = HytaleHyboxConfig.read(dataDir, SETTINGS_LOGGER);

        for (var field : HytaleHyboxConfig.SETTINGS_FIELDS) {
            if (field.kind() != HytaleHyboxConfig.SettingKind.TOGGLE) continue;
            for (boolean enabled : new boolean[] {false, true}) {
                var submitted = changedSettings(original, java.util.Map.of(field.id(), enabled));
                assertEquals(List.of(), submitted.validate(), field.id() + "=" + enabled);
            }
        }
    }

    @Test
    void settingsRejectValuesImmediatelyOutsideEveryNumericTableRangeWithoutWriting(@TempDir Path dataDir)
            throws Exception {
        Path configPath = HytaleHyboxConfig.path(dataDir);
        String json = settingsDocument().toString();
        Files.writeString(configPath, json, StandardCharsets.UTF_8);
        HyboxSettings original = HytaleHyboxConfig.read(dataDir, SETTINGS_LOGGER);

        for (var field : HytaleHyboxConfig.SETTINGS_FIELDS) {
            if (field.kind() != HytaleHyboxConfig.SettingKind.NUMBER) continue;
            for (long invalidValue : new long[] {field.min() - 1, field.max() + 1}) {
                var invalid = changedSettings(original, java.util.Map.of(field.id(), invalidValue));
                String label = field.id() + "=" + invalidValue;
                assertTrue(invalid.validate().stream().anyMatch(problem ->
                    problem.startsWith(field.errorName() + " must be between ")), label);

                assertThrows(IllegalArgumentException.class,
                    () -> HytaleHyboxConfig.write(dataDir, original, invalid, SETTINGS_LOGGER), label);

                assertEquals(json, Files.readString(configPath, StandardCharsets.UTF_8), label);
            }
        }
    }

    @Test
    void settingsRequireCriticalAtLeastDegradedForEnabledAndDisabledModules(@TempDir Path dataDir) throws Exception {
        Path configPath = HytaleHyboxConfig.path(dataDir);
        String json = settingsDocument().toString();
        Files.writeString(configPath, json, StandardCharsets.UTF_8);
        HyboxSettings original = HytaleHyboxConfig.read(dataDir, SETTINGS_LOGGER);

        for (String[] pair : List.of(
                new String[] {"HeartbeatStall", "StallDegraded", "StallCritical",
                    "Heartbeat critical threshold must be at least the degraded threshold."},
                new String[] {"TickDegraded", "TickAvgDegraded", "TickAvgCritical",
                    "Tick critical threshold must be at least the degraded threshold."})) {
            long lower = settingField(pair[1]).min();
            long higher = lower + 1;
            for (boolean enabled : new boolean[] {false, true}) {
                var equal = changedSettings(original, java.util.Map.of(pair[0], enabled,
                    pair[1], higher, pair[2], higher));
                var greater = changedSettings(equal, java.util.Map.of(pair[1], lower));
                var invalid = changedSettings(equal, java.util.Map.of(pair[2], lower));
                assertEquals(List.of(), equal.validate(), pair[1]);
                assertEquals(List.of(), greater.validate(), pair[1]);
                assertEquals(List.of(pair[3]), invalid.validate(), pair[1]);

                assertThrows(IllegalArgumentException.class,
                    () -> HytaleHyboxConfig.write(dataDir, original, invalid, SETTINGS_LOGGER));

                assertEquals(json, Files.readString(configPath, StandardCharsets.UTF_8));
            }
        }
    }

    @Test
    void settingsClampEveryNumericDisplayValueWithoutRewritingUneditedRawValues(@TempDir Path dataDir)
            throws Exception {
        Path configPath = HytaleHyboxConfig.path(dataDir);
        for (boolean below : new boolean[] {true, false}) {
            var expectedDocument = settingsDocument();
            var expectedValues = new java.util.LinkedHashMap<String, Object>();
            for (SettingCase setting : SETTINGS_CASES) {
                var field = settingField(setting.id());
                if (field.kind() == HytaleHyboxConfig.SettingKind.NUMBER) {
                    long boundary = below ? field.min() : field.max();
                    long rawValue = below ? boundary - 1 : boundary + 1;
                    putConfigValue(expectedDocument, setting.configKey(), storedSettingValue(setting.unit(), rawValue));
                    expectedValues.put(setting.id(), boundary);
                } else {
                    expectedValues.put(setting.id(), setting.original());
                }
            }
            Files.writeString(configPath, expectedDocument.toString(), StandardCharsets.UTF_8);
            HyboxSettings original = HytaleHyboxConfig.read(dataDir, SETTINGS_LOGGER);
            assertEquals(new HyboxSettings(expectedValues), original);
            assertEquals(List.of(), original.validate());
            var submitted = changedSettings(original, java.util.Map.of("CaptureEnabled", false));

            HytaleHyboxConfig.write(dataDir, original, submitted, SETTINGS_LOGGER);

            putConfigValue(expectedDocument, "Capture.Enabled", new com.google.gson.JsonPrimitive(false));
            assertEquals(expectedDocument,
                com.google.gson.JsonParser.parseString(Files.readString(configPath)).getAsJsonObject());
        }
    }

    @Test
    void settingsTruncateNonRoundUnitsButUnrelatedSavePreservesTheirExactValues(@TempDir Path dataDir)
            throws Exception {
        Path configPath = HytaleHyboxConfig.path(dataDir);
        var expectedDocument = settingsDocument();
        putConfigValue(expectedDocument, "Jfr.MaxAge", new com.google.gson.JsonPrimitive("PT90S"));
        putConfigValue(expectedDocument, "Jfr.MaxSizeBytes", new com.google.gson.JsonPrimitive(536870913L));
        putConfigValue(expectedDocument, "Jfr.SnapshotInterval", new com.google.gson.JsonPrimitive("PT23S"));
        putConfigValue(expectedDocument, "Jfr.SampleInterval", new com.google.gson.JsonPrimitive("PT5.9S"));
        putConfigValue(expectedDocument, "Jfr.PostIncidentMaxWait", new com.google.gson.JsonPrimitive("PT37.5S"));
        putConfigValue(expectedDocument, "Jfr.ThreadDumpInterval", new com.google.gson.JsonPrimitive("PT17.75S"));
        putConfigValue(expectedDocument, "Trigger.Cooldown", new com.google.gson.JsonPrimitive("PT33.750S"));
        putConfigValue(expectedDocument, "Retention.MaxTotalBytes", new com.google.gson.JsonPrimitive(3145729L));
        putConfigValue(expectedDocument, "Retention.MaxAge", new com.google.gson.JsonPrimitive("PT37H"));
        Files.writeString(configPath, expectedDocument.toString(), StandardCharsets.UTF_8);
        HyboxSettings original = HytaleHyboxConfig.read(dataDir, SETTINGS_LOGGER);

        assertEquals(1L, original.value(settingField("JfrMaxAgeMinutes")));
        assertEquals(512L, original.value(settingField("JfrMaxSizeMiB")));
        assertEquals(0L, original.value(settingField("JfrSnapshotIntervalMinutes")));
        assertEquals(5L, original.value(settingField("JfrSampleIntervalSeconds")));
        assertEquals(37L, original.value(settingField("PostIncidentMaxWait")));
        assertEquals(17L, original.value(settingField("ThreadDumpInterval")));
        assertEquals(33L, original.value(settingField("Cooldown")));
        assertEquals(3L, original.value(settingField("RetentionMaxTotalMiB")));
        assertEquals(1L, original.value(settingField("RetentionMaxAgeDays")));
        assertEquals(List.of(), original.validate());
        var submitted = changedSettings(original, java.util.Map.of("CaptureEnabled", false));

        HytaleHyboxConfig.write(dataDir, original, submitted, SETTINGS_LOGGER);

        putConfigValue(expectedDocument, "Capture.Enabled", new com.google.gson.JsonPrimitive(false));
        assertEquals(expectedDocument,
            com.google.gson.JsonParser.parseString(Files.readString(configPath)).getAsJsonObject());
        assertEquals(submitted, HytaleHyboxConfig.read(dataDir, SETTINGS_LOGGER));

        var converted = changedSettings(submitted, java.util.Map.of(
            "JfrMaxAgeMinutes", 2L, "JfrMaxSizeMiB", 513L, "JfrSnapshotIntervalMinutes", 3L,
            "RetentionMaxAgeDays", 2L, "RetentionMaxTotalMiB", 4L, "Cooldown", 34L));

        HytaleHyboxConfig.write(dataDir, submitted, converted, SETTINGS_LOGGER);

        putConfigValue(expectedDocument, "Jfr.MaxAge", new com.google.gson.JsonPrimitive("PT2M"));
        putConfigValue(expectedDocument, "Jfr.MaxSizeBytes", new com.google.gson.JsonPrimitive(537919488L));
        putConfigValue(expectedDocument, "Jfr.SnapshotInterval", new com.google.gson.JsonPrimitive("PT3M"));
        putConfigValue(expectedDocument, "Retention.MaxAge", new com.google.gson.JsonPrimitive("PT48H"));
        putConfigValue(expectedDocument, "Retention.MaxTotalBytes", new com.google.gson.JsonPrimitive(4194304L));
        putConfigValue(expectedDocument, "Trigger.Cooldown", new com.google.gson.JsonPrimitive("PT34S"));
        assertEquals(expectedDocument,
            com.google.gson.JsonParser.parseString(Files.readString(configPath)).getAsJsonObject());
        assertEquals(converted, HytaleHyboxConfig.read(dataDir, SETTINGS_LOGGER));
    }

    @Test
    void settingsSavePreservesFreshFileEditsToEveryUnchangedFormField(@TempDir Path dataDir) throws Exception {
        Path configPath = HytaleHyboxConfig.path(dataDir);
        var freshDocument = settingsDocument();
        Files.writeString(configPath, freshDocument.toString(), StandardCharsets.UTF_8);
        HyboxSettings original = HytaleHyboxConfig.read(dataDir, SETTINGS_LOGGER);
        var submitted = changedSettings(original, java.util.Map.of("Cooldown", 69L));

        for (SettingCase setting : SETTINGS_CASES) {
            putConfigValue(freshDocument, setting.configKey(), storedSettingValue(setting.unit(), setting.updated()));
        }
        putConfigValue(freshDocument, "Jfr.MaxAge", new com.google.gson.JsonPrimitive("PT90S"));
        putConfigValue(freshDocument, "Jfr.SnapshotInterval", new com.google.gson.JsonPrimitive("PT23S"));
        putConfigValue(freshDocument, "Trigger.Debounce", new com.google.gson.JsonPrimitive("PT4.125S"));
        putConfigValue(freshDocument, "Retention.MaxAge", new com.google.gson.JsonPrimitive("PT23H"));
        putConfigValue(freshDocument, "Jfr.RecordingName", new com.google.gson.JsonPrimitive("edited-on-disk"));
        putConfigValue(freshDocument, "Discord.Username", new com.google.gson.JsonPrimitive("Fresh Username"));
        putConfigValue(freshDocument, "Metrics.Prometheus.Port", new com.google.gson.JsonPrimitive(9288));
        putConfigValue(freshDocument, "Trigger.Modules.FutureModule", new com.google.gson.JsonPrimitive(false));
        putConfigValue(freshDocument, "FreshRoot.Nested.Value", new com.google.gson.JsonPrimitive("keep fresh"));
        putConfigValue(freshDocument, "Capture.FutureCapture.Added", new com.google.gson.JsonPrimitive(77));
        Files.writeString(configPath, freshDocument.toString(), StandardCharsets.UTF_8);

        HytaleHyboxConfig.write(dataDir, original, submitted, SETTINGS_LOGGER);

        putConfigValue(freshDocument, "Trigger.Cooldown", new com.google.gson.JsonPrimitive("PT1M9S"));
        assertEquals(freshDocument,
            com.google.gson.JsonParser.parseString(Files.readString(configPath)).getAsJsonObject());
        HyboxSettings restored = HytaleHyboxConfig.read(dataDir, SETTINGS_LOGGER);
        assertEquals(69L, restored.value(settingField("Cooldown")));
        assertEquals(1L, restored.value(settingField("JfrMaxAgeMinutes")));
        assertEquals(0L, restored.value(settingField("JfrSnapshotIntervalMinutes")));
        assertEquals(1L, restored.value(settingField("RetentionMaxAgeDays")));
        assertEquals(false, restored.value(settingField("JfrOldObjectSampling")));
    }

    @Test
    void settingsRoundTripNetworkLimitsWithTheModuleEnabledEvenWhenBothLimitsAreZero(@TempDir Path dataDir)
            throws Exception {
        Path configPath = HytaleHyboxConfig.path(dataDir);
        var document = settingsDocument();
        putConfigValue(document, "Trigger.Modules.NetSaturation", new com.google.gson.JsonPrimitive(false));
        Files.writeString(configPath, document.toString(), StandardCharsets.UTF_8);
        HyboxSettings original = HytaleHyboxConfig.read(dataDir, SETTINGS_LOGGER);
        var inboundField = settingField("NetInMbps");
        var outboundField = settingField("NetOutMbps");

        for (long inbound : new long[] {inboundField.min(), inboundField.max()}) {
            for (long outbound : new long[] {outboundField.min(), outboundField.max()}) {
                var submitted = changedSettings(original, java.util.Map.of(
                    "NetSaturation", true, "NetInMbps", inbound, "NetOutMbps", outbound));
                assertEquals(List.of(), submitted.validate());

                HytaleHyboxConfig.write(dataDir, original, submitted, SETTINGS_LOGGER);

                HyboxSettings restored = HytaleHyboxConfig.read(dataDir, SETTINGS_LOGGER);
                assertEquals(submitted, restored);
                var saved = com.google.gson.JsonParser.parseString(Files.readString(configPath)).getAsJsonObject();
                assertEquals(inbound, configValue(saved, "Trigger.NetInMbps").getAsLong());
                assertEquals(outbound, configValue(saved, "Trigger.NetOutMbps").getAsLong());
                var detectors = HytaleHyboxConfig.loadOrCreate(dataDir, SETTINGS_LOGGER).triggerPolicy().detectors();
                assertTrue(detectors.modules().netSaturation());
                assertEquals(inbound, detectors.netInMbps());
                assertEquals(outbound, detectors.netOutMbps());
                original = restored;
            }
        }
    }

    @Test
    void settingsDisplaySubdayRetentionAsOneDayButPreserveItAndRejectZeroOnSave(@TempDir Path dataDir)
            throws Exception {
        Path configPath = HytaleHyboxConfig.path(dataDir);
        var expectedDocument = settingsDocument();
        putConfigValue(expectedDocument, "Retention.MaxAge", new com.google.gson.JsonPrimitive("PT23H"));
        Files.writeString(configPath, expectedDocument.toString(), StandardCharsets.UTF_8);
        HyboxSettings original = HytaleHyboxConfig.read(dataDir, SETTINGS_LOGGER);
        assertEquals(1L, original.value(settingField("RetentionMaxAgeDays")));
        assertEquals(List.of(), original.validate());

        HytaleHyboxConfig.write(dataDir, original, original, SETTINGS_LOGGER);

        assertEquals(expectedDocument,
            com.google.gson.JsonParser.parseString(Files.readString(configPath)).getAsJsonObject());
        var submitted = changedSettings(original, java.util.Map.of("CaptureEnabled", false));

        HytaleHyboxConfig.write(dataDir, original, submitted, SETTINGS_LOGGER);

        putConfigValue(expectedDocument, "Capture.Enabled", new com.google.gson.JsonPrimitive(false));
        String savedJson = Files.readString(configPath, StandardCharsets.UTF_8);
        assertEquals(expectedDocument, com.google.gson.JsonParser.parseString(savedJson).getAsJsonObject());
        HyboxSettings saved = HytaleHyboxConfig.read(dataDir, SETTINGS_LOGGER);
        var invalid = changedSettings(saved, java.util.Map.of("RetentionMaxAgeDays", 0L));
        assertTrue(invalid.validate().stream().anyMatch(problem -> problem.startsWith("Retention age")));

        assertThrows(IllegalArgumentException.class,
            () -> HytaleHyboxConfig.write(dataDir, saved, invalid, SETTINGS_LOGGER));

        assertEquals(savedJson, Files.readString(configPath, StandardCharsets.UTF_8));
    }

    @Test
    void settingsWriteCreatesMissingConfigAndKeepsEveryOtherDefault(@TempDir Path dataDir) throws Exception {
        Path configPath = HytaleHyboxConfig.path(dataDir);
        HyboxSettings original = HytaleHyboxConfig.read(dataDir, SETTINGS_LOGGER);
        assertFalse(Files.exists(configPath));
        var submitted = changedSettings(original, java.util.Map.of(
            "CaptureEnabled", false, "JfrMaxAgeMinutes", 23L, "NetInMbps", 71L));

        HytaleHyboxConfig.write(dataDir, original, submitted, SETTINGS_LOGGER);

        assertTrue(Files.exists(configPath));
        assertEquals(submitted, HytaleHyboxConfig.read(dataDir, SETTINGS_LOGGER));
        assertEquals(defaultSettingsDocument(submitted),
            com.google.gson.JsonParser.parseString(Files.readString(configPath)).getAsJsonObject());
        HyboxConfig reloaded = HytaleHyboxConfig.loadOrCreate(dataDir, SETTINGS_LOGGER);
        assertEquals("hybox", reloaded.jfrRecordingName());
        assertFalse(reloaded.metricsEnabled());
        assertTrue(reloaded.discordWebhook().webhookUrl().isEmpty());
    }

    @Test
    void invalidSettingsDoNotCreateMissingConfig(@TempDir Path dataDir) {
        HyboxSettings original = HytaleHyboxConfig.read(dataDir, SETTINGS_LOGGER);
        var field = settingField("RetentionMaxAgeDays");
        var invalid = changedSettings(original, java.util.Map.of(field.id(), field.min() - 1));

        assertThrows(IllegalArgumentException.class,
            () -> HytaleHyboxConfig.write(dataDir, original, invalid, SETTINGS_LOGGER));

        assertFalse(Files.exists(HytaleHyboxConfig.path(dataDir)));
    }

    private static HytaleHyboxConfig.SettingField settingField(String id) {
        return HytaleHyboxConfig.SETTINGS_FIELDS.stream().filter(field -> field.id().equals(id)).findFirst().orElseThrow();
    }

    private static HyboxSettings changedSettings(HyboxSettings original, java.util.Map<String, Object> changes) {
        var values = new java.util.LinkedHashMap<>(original.values());
        values.putAll(changes);
        return new HyboxSettings(values);
    }

    private static HyboxSettings settingsWithNumericPartner(HyboxSettings original,
            HytaleHyboxConfig.SettingField field, long value) {
        var values = new java.util.LinkedHashMap<>(original.values());
        values.put(field.id(), value);
        String partnerId = switch (field.id()) {
            case "StallDegraded" -> "StallCritical";
            case "StallCritical" -> "StallDegraded";
            case "TickAvgDegraded" -> "TickAvgCritical";
            case "TickAvgCritical" -> "TickAvgDegraded";
            default -> null;
        };
        if (partnerId != null) {
            var partner = settingField(partnerId);
            values.put(partnerId, Math.clamp(value, partner.min(), partner.max()));
        }
        return new HyboxSettings(values);
    }

    private static com.google.gson.JsonElement storedSettingValue(String unit, Object value) {
        return switch (unit) {
            case "BOOLEAN" -> new com.google.gson.JsonPrimitive((Boolean) value);
            case "SECONDS" -> new com.google.gson.JsonPrimitive(Duration.ofSeconds((long) value).toString());
            case "MINUTES" -> new com.google.gson.JsonPrimitive(Duration.ofMinutes((long) value).toString());
            case "DAYS" -> new com.google.gson.JsonPrimitive(Duration.ofDays((long) value).toString());
            case "MEBIBYTES" -> new com.google.gson.JsonPrimitive(Math.multiplyExact((long) value, 1024L * 1024L));
            case "COUNT", "DAY_COUNT", "PERCENT", "MILLISECONDS", "MBPS" -> new com.google.gson.JsonPrimitive((long) value);
            default -> throw new AssertionError(unit);
        };
    }

    private static com.google.gson.JsonElement configValue(com.google.gson.JsonObject document, String path) {
        com.google.gson.JsonElement value = document;
        for (String key : path.split("\\.")) value = value.getAsJsonObject().get(key);
        return value;
    }

    private static void putConfigValue(com.google.gson.JsonObject document, String path,
            com.google.gson.JsonElement value) {
        String[] keys = path.split("\\.");
        var target = document;
        for (int i = 0; i < keys.length - 1; i++) {
            if (!target.has(keys[i])) target.add(keys[i], new com.google.gson.JsonObject());
            target = target.getAsJsonObject(keys[i]);
        }
        target.add(keys[keys.length - 1], value);
    }

    private static com.google.gson.JsonObject settingsDocument() {
        var document = com.google.gson.JsonParser.parseString("""
            {
              "Version": 1,
              "Jfr": {
                "RecordingName": "custom-recorder", "DisabledEvents": ["jdk.CPULoad", "custom.Event"],
                "Configuration": "profile", "FutureJfr": { "Nested": [1, "keep", null] }
              },
              "Discord": {
                "WebhookUrl": "https://example.invalid/custom-hook", "Cooldown": "PT43.125S",
                "RequestTimeout": "PT19S", "Username": "Custom Hybox", "FutureDiscord": false
              },
              "Metrics": {
                "Prometheus": {
                  "Enabled": true, "Port": 9187, "Bind": "192.0.2.17", "FuturePrometheus": ["keep"]
                },
                "FutureMetrics": { "Nested": true }
              },
              "Trigger": {
                "CooldownExemptKinds": ["HEARTBEAT_STALL"],
                "Modules": { "FutureModule": true, "FutureNestedModule": { "Enabled": true } },
                "LogErrorSkipSentry": false, "LogErrorRequireThrowable": false,
                "LogErrorIgnore": ["custom warning", "secret.*"], "FutureTrigger": null
              },
              "Capture": {
                "HeapHistogram": true, "AllowPluginExtras": false, "LogTailLines": 17,
                "IncludeServerLog": true, "IncludeServerConfig": true, "IncludeModConfigs": true,
                "AllowModConfigOptIn": false, "SanitizeLog": false,
                "RedactPatterns": ["CUSTOM_SECRET_[A-Z0-9]+"], "Artifacts": ["jfr", "report", "env"],
                "FutureCapture": { "Nested": [false, 3] }
              },
              "Retention": {
                "FailedRecordingMaxCount": 7, "StagingMaxTotalBytes": 8765432109, "StagingMaxAge": "PT23H",
                "RepositoryMaxTotalBytes": 7654321098, "RepositoryMaxAge": "PT37H",
                "FutureRetention": "keep"
              },
              "FutureRoot": { "Nested": [1, false, "keep"], "NullValue": null }
            }
            """).getAsJsonObject();
        for (SettingCase setting : SETTINGS_CASES) {
            putConfigValue(document, setting.configKey(), storedSettingValue(setting.unit(), setting.original()));
        }
        return document;
    }

    private static com.google.gson.JsonObject defaultSettingsDocument(HyboxSettings settings) {
        var document = com.google.gson.JsonParser.parseString("""
            {
              "Version": 1,
              "Jfr": { "RecordingName": "hybox", "DisabledEvents": [], "Configuration": "default" },
              "Discord": {
                "WebhookUrl": "", "Cooldown": "PT1M", "RequestTimeout": "PT10S", "Username": "Hybox"
              },
              "Metrics": { "Prometheus": { "Enabled": false, "Port": 9099, "Bind": "127.0.0.1" } },
              "Trigger": {
                "CooldownExemptKinds": ["DEADLOCK", "HEARTBEAT_STALL", "WORLD_FAILURE"],
                "LogErrorSkipSentry": true, "LogErrorRequireThrowable": true, "LogErrorIgnore": []
              },
              "Capture": {
                "HeapHistogram": false, "AllowPluginExtras": true, "LogTailLines": 500,
                "IncludeServerLog": false, "IncludeServerConfig": false, "IncludeModConfigs": false,
                "AllowModConfigOptIn": true,
                "Artifacts": ["jfr", "report", "env", "threads", "serverLog", "plugins", "worlds", "heartbeats", "server", "metrics"]
              },
              "Retention": {
                "FailedRecordingMaxCount": 5, "StagingMaxTotalBytes": 536870912, "StagingMaxAge": "PT168H",
                "RepositoryMaxTotalBytes": 1073741824, "RepositoryMaxAge": "PT168H"
              }
            }
            """).getAsJsonObject();
        var redactPatterns = new com.google.gson.JsonArray();
        for (String pattern : io.github.xytronix.hybox.core.env.TextRedactor.DEFAULT_PATTERNS) redactPatterns.add(pattern);
        putConfigValue(document, "Capture.RedactPatterns", redactPatterns);
        for (SettingCase setting : SETTINGS_CASES) {
            putConfigValue(document, setting.configKey(), storedSettingValue(setting.unit(), settings.value(settingField(setting.id()))));
        }
        return document;
    }

    private record SettingCase(String id, String configKey, String unit, Object original, Object updated) {
    }

    private static final System.Logger SETTINGS_LOGGER = System.getLogger("hytale-settings-test");

    private static final List<SettingCase> SETTINGS_CASES = List.of(
        new SettingCase("HeartbeatStall", "Trigger.Modules.HeartbeatStall", "BOOLEAN", true, false),
        new SettingCase("StallDegraded", "Trigger.StallDegradedMs", "MILLISECONDS", 2100L, 3100L),
        new SettingCase("StallCritical", "Trigger.StallCriticalMs", "MILLISECONDS", 11000L, 13000L),
        new SettingCase("TickDegraded", "Trigger.Modules.TickDegraded", "BOOLEAN", false, true),
        new SettingCase("TickAvgDegraded", "Trigger.TickAvgDegradedMs", "MILLISECONDS", 125L, 150L),
        new SettingCase("TickAvgCritical", "Trigger.TickAvgCriticalMs", "MILLISECONDS", 350L, 450L),
        new SettingCase("Deadlock", "Trigger.Modules.Deadlock", "BOOLEAN", true, false),
        new SettingCase("HeapPressure", "Trigger.Modules.HeapPressure", "BOOLEAN", false, true),
        new SettingCase("HeapPressurePct", "Trigger.HeapPressurePct", "PERCENT", 88L, 87L),
        new SettingCase("HeapPressureSustain", "Trigger.HeapPressureSustain", "SECONDS", 11L, 21L),
        new SettingCase("GcPressure", "Trigger.Modules.GcPressure", "BOOLEAN", true, false),
        new SettingCase("GcPressurePct", "Trigger.GcPressurePct", "PERCENT", 30L, 27L),
        new SettingCase("GcPressureWindow", "Trigger.GcPressureWindow", "SECONDS", 13L, 22L),
        new SettingCase("CpuSaturation", "Trigger.Modules.CpuSaturation", "BOOLEAN", false, true),
        new SettingCase("CpuSaturationPct", "Trigger.CpuSaturationPct", "PERCENT", 93L, 91L),
        new SettingCase("CpuSaturationSustain", "Trigger.CpuSaturationSustain", "SECONDS", 17L, 23L),
        new SettingCase("NetSaturation", "Trigger.Modules.NetSaturation", "BOOLEAN", true, false),
        new SettingCase("NetInMbps", "Trigger.NetInMbps", "MBPS", 71L, 72L),
        new SettingCase("NetOutMbps", "Trigger.NetOutMbps", "MBPS", 83L, 84L),
        new SettingCase("NetSustain", "Trigger.NetSustain", "SECONDS", 19L, 24L),
        new SettingCase("PlayerDrop", "Trigger.Modules.PlayerDrop", "BOOLEAN", false, true),
        new SettingCase("PlayerDropPct", "Trigger.PlayerDropPct", "PERCENT", 50L, 45L),
        new SettingCase("PlayerDropWindow", "Trigger.PlayerDropWindow", "SECONDS", 29L, 25L),
        new SettingCase("PlayerDropMinPlayers", "Trigger.PlayerDropMinPlayers", "COUNT", 17L, 18L),
        new SettingCase("LogError", "Trigger.Modules.LogError", "BOOLEAN", false, true),
        new SettingCase("LogErrorDedupeWindow", "Trigger.LogErrorDedupeWindow", "SECONDS", 41L, 42L),
        new SettingCase("Cooldown", "Trigger.Cooldown", "SECONDS", 33L, 34L),
        new SettingCase("Debounce", "Trigger.Debounce", "SECONDS", 4L, 5L),
        new SettingCase("CaptureEnabled", "Capture.Enabled", "BOOLEAN", true, false),
        new SettingCase("RetentionMaxCount", "Retention.MaxCount", "COUNT", 25L, 26L),
        new SettingCase("RetentionMaxTotalMiB", "Retention.MaxTotalBytes", "MEBIBYTES", 1234L, 2345L),
        new SettingCase("RetentionMaxAgeDays", "Retention.MaxAge", "DAYS", 2L, 3L),
        new SettingCase("MetricsEnabled", "Metrics.Enabled", "BOOLEAN", true, false),
        new SettingCase("MetricsRetentionDays", "Metrics.RetentionDays", "DAY_COUNT", 19L, 20L),
        new SettingCase("MetricsCpu", "Metrics.Cpu", "BOOLEAN", false, true),
        new SettingCase("MetricsAllocation", "Metrics.Allocation", "BOOLEAN", true, false),
        new SettingCase("PostIncidentMaxWait", "Jfr.PostIncidentMaxWait", "SECONDS", 37L, 38L),
        new SettingCase("ThreadDumpCount", "Jfr.ThreadDumpCount", "COUNT", 7L, 8L),
        new SettingCase("ThreadDumpInterval", "Jfr.ThreadDumpInterval", "SECONDS", 17L, 18L),
        new SettingCase("JfrMaxAgeMinutes", "Jfr.MaxAge", "MINUTES", 45L, 46L),
        new SettingCase("JfrMaxSizeMiB", "Jfr.MaxSizeBytes", "MEBIBYTES", 512L, 513L),
        new SettingCase("JfrSampleIntervalSeconds", "Jfr.SampleInterval", "SECONDS", 31L, 32L),
        new SettingCase("JfrSnapshotIntervalMinutes", "Jfr.SnapshotInterval", "MINUTES", 10L, 11L),
        new SettingCase("JfrOldObjectSampling", "Jfr.OldObjectSampling", "BOOLEAN", true, false)
    );

}
