package io.github.xytronix.hybox.hytale;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.xytronix.hybox.core.config.HyboxConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class HyboxSettingsReloadTest {
    @TempDir Path dataDir;

    @Test
    void unchangedConfigHasNoChanges() throws Exception {
        assertEquals("Reloaded. Changed: nothing", HyboxRuntime.reloadSummary(config(""), config("")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "\"Enabled\": true",
        "\"RetentionDays\": 14",
        "\"Cpu\": false",
        "\"Allocation\": true"
    })
    void metricsSettingsApplyLive(String field) throws Exception {
        assertChange("\"Metrics\": {" + field + "}", "Reloaded. Changed: Metrics");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "\"Enabled\": true",
        "\"Port\": 9199",
        "\"Bind\": \"0.0.0.0\""
    })
    void prometheusSettingsApplyLive(String field) throws Exception {
        assertChange("\"Metrics\": {\"Prometheus\": {" + field + "}}",
            "Reloaded. Changed: Metrics.Prometheus");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "\"MaxAge\": \"PT30M\"",
        "\"MaxSizeBytes\": 536870912",
        "\"RecordingName\": \"custom-recorder\"",
        "\"DisabledEvents\": [\"jdk.CPULoad\"]",
        "\"Configuration\": \"profile\""
    })
    void recordingSettingsRequireRestart(String field) throws Exception {
        assertChange("\"Jfr\": {" + field + "}",
            "Reloaded. Changed: nothing. Restart required for: Jfr recording settings");
    }

    @ParameterizedTest
    @CsvSource(value = {
        "SnapshotInterval|PT20M|Jfr.SnapshotInterval",
        "SampleInterval|PT20S|Jfr.SampleInterval"
    }, delimiter = '|')
    void startupIntervalsRequireRestart(String field, String value, String label) throws Exception {
        assertChange("\"Jfr\": {\"" + field + "\": \"" + value + "\"}",
            "Reloaded. Changed: nothing. Restart required for: " + label);
    }

    @Test
    void oldObjectSamplingRequiresRestartInBothDirections() throws Exception {
        HyboxConfig disabled = config("\"Jfr\": {\"OldObjectSampling\": false}");
        HyboxConfig enabled = config("\"Jfr\": {\"OldObjectSampling\": true}");
        String expected = "Reloaded. Changed: nothing. Restart required for: Jfr.OldObjectSampling";

        assertEquals(expected, HyboxRuntime.reloadSummary(disabled, enabled));
        assertEquals(expected, HyboxRuntime.reloadSummary(enabled, disabled));
    }

    @ParameterizedTest
    @CsvSource(value = {
        "\"PostIncidentMaxWait\": \"PT30S\"|Jfr.PostIncidentMaxWait",
        "\"ThreadDumpCount\": 5|Jfr.ThreadDumps",
        "\"ThreadDumpInterval\": \"PT5S\"|Jfr.ThreadDumps"
    }, delimiter = '|')
    void postIncidentAndThreadDumpSettingsApplyLive(String field, String label) throws Exception {
        assertChange("\"Jfr\": {" + field + "}", "Reloaded. Changed: " + label);
    }

    @ParameterizedTest
    @CsvSource(value = {
        "\"Trigger\": {\"Cooldown\": \"PT1M\"}|Trigger",
        "\"Capture\": {\"Enabled\": false}|Capture/Retention",
        "\"Retention\": {\"MaxCount\": 30}|Capture/Retention",
        "\"Discord\": {\"Username\": \"Custom Hybox\"}|Discord"
    }, delimiter = '|')
    void existingLiveSettingsKeepTheirSummary(String fields, String label) throws Exception {
        assertChange(fields, "Reloaded. Changed: " + label);
    }

    @Test
    void combinedChangesKeepOrderingAndReportEachGroupOnce() throws Exception {
        assertChange("""
            "Jfr": {
              "MaxAge": "PT30M", "MaxSizeBytes": 536870912,
              "RecordingName": "custom-recorder", "DisabledEvents": ["jdk.CPULoad"],
              "Configuration": "profile", "SnapshotInterval": "PT20M", "SampleInterval": "PT20S",
              "OldObjectSampling": true, "PostIncidentMaxWait": "PT30S",
              "ThreadDumpCount": 5, "ThreadDumpInterval": "PT5S"
            },
            "Trigger": {"Cooldown": "PT1M"},
            "Capture": {"Enabled": false},
            "Retention": {"MaxCount": 30},
            "Discord": {"Username": "Custom Hybox"},
            "Metrics": {
              "Enabled": true, "RetentionDays": 14, "Cpu": false, "Allocation": true,
              "Prometheus": {"Enabled": true, "Port": 9199, "Bind": "0.0.0.0"}
            }
            """, "Reloaded. Changed: Trigger, Capture/Retention, Discord, Metrics, Metrics.Prometheus, "
                + "Jfr.PostIncidentMaxWait, Jfr.ThreadDumps. Restart required for: Jfr recording settings, "
                + "Jfr.SnapshotInterval, Jfr.SampleInterval, Jfr.OldObjectSampling");
    }

    private void assertChange(String fields, String expected) throws IOException {
        HyboxConfig previous = config("");
        HyboxConfig fresh = config(fields);
        assertEquals(expected, HyboxRuntime.reloadSummary(previous, fresh));
        assertEquals(expected, HyboxRuntime.reloadSummary(fresh, previous));
    }

    private HyboxConfig config(String fields) throws IOException {
        Files.writeString(HytaleHyboxConfig.path(dataDir),
            "{\"Version\":1" + (fields.isEmpty() ? "" : "," + fields) + "}", StandardCharsets.UTF_8);
        return HytaleHyboxConfig.loadOrCreate(dataDir, System.getLogger("hybox-settings-reload-test"));
    }
}
