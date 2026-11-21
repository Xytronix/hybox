package io.github.xytronix.hybox.core.jfr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JfrControllerTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void healthReflectsExternalStopAndCloseWithoutControllerLock(boolean close) throws Exception {
        try (JfrController controller = new JfrController(Duration.ofMinutes(1), 1024 * 1024, "hybox-health")) {
            controller.start();
            assertTrue(controller.isRunning());
            Recording owned = extractRecording(controller);
            synchronized (controller) {
                CompletableFuture.runAsync(() -> {
                    if (close) {
                        owned.close();
                    } else {
                        owned.stop();
                    }
                }).get(5, TimeUnit.SECONDS);
                assertFalse(CompletableFuture.supplyAsync(controller::isRunning).get(1, TimeUnit.SECONDS));
            }
            controller.restart(null);
            assertTrue(controller.isRunning());
        }
    }

    @Test
    void dumpsRecordingWithEventsByDefault(@TempDir Path tempDir) throws Exception {
        assertTrue(FlightRecorder.isAvailable(), "JFR is not available in this runtime.");
        Path dumpPath = tempDir.resolve("recording.jfr");

        try (JfrController controller = new JfrController(Duration.ofSeconds(60), 16L * 1024L * 1024L,
            "hybox-test")) {
            controller.start();
            controller.dump(dumpPath);
        }

        assertTrue(Files.exists(dumpPath), "Expected JFR dump to exist.");
        assertTrue(Files.size(dumpPath) > 0, "Expected JFR dump to be non-empty.");

        boolean foundAnyEvent = false;
        try (RecordingFile recordingFile = new RecordingFile(dumpPath)) {
            if (recordingFile.hasMoreEvents()) {
                foundAnyEvent = true;
            }
        }

        assertTrue(foundAnyEvent, "Expected at least one event in the recording.");
    }

    @Test
    void dumpsRecordingWithMarkerEvent(@TempDir Path tempDir) throws Exception {
        assertTrue(FlightRecorder.isAvailable(), "JFR is not available in this runtime.");
        Path dumpPath = tempDir.resolve("recording.jfr");

        try (JfrController controller = new JfrController(Duration.ofSeconds(60), 16L * 1024L * 1024L,
            "hybox-test")) {
            controller.start();

            for (int i = 0; i < 3; i++) {
                HyboxMarkerEvent event = new HyboxMarkerEvent();
                event.message = "marker-" + i;
                event.commit();
            }

            controller.dump(dumpPath);
        }

        assertTrue(Files.exists(dumpPath), "Expected JFR dump to exist.");
        assertTrue(Files.size(dumpPath) > 0, "Expected JFR dump to be non-empty.");

        boolean foundMarker = false;
        try (RecordingFile recordingFile = new RecordingFile(dumpPath)) {
            while (recordingFile.hasMoreEvents()) {
                RecordedEvent event = recordingFile.readEvent();
                if ("io.github.xytronix.hybox.marker".equals(event.getEventType().getName())) {
                    foundMarker = true;
                    break;
                }
            }
        }

        assertTrue(foundMarker, "Expected at least one marker event in the recording.");
    }

    @Test
    void disabledEventsAreAppliedToRecordingSettings(@TempDir Path tempDir) throws Exception {
        assertTrue(FlightRecorder.isAvailable(), "JFR is not available in this runtime.");
        try (JfrController controller = new JfrController(
            Duration.ofSeconds(60),
            16L * 1024L * 1024L,
            "hybox-test",
            List.of("jdk.CPULoad")
        )) {
            controller.start();
            Recording recording = extractRecording(controller);
            assertEquals("false", recording.getSettings().get("jdk.CPULoad#enabled"));
        }
    }

    @Test
    void oldObjectSamplingIsOffByDefault(@TempDir Path tempDir) throws Exception {
        assertTrue(FlightRecorder.isAvailable(), "JFR is not available in this runtime.");
        try (JfrController controller = new JfrController(Duration.ofSeconds(60), 16L * 1024L * 1024L,
            "hybox-test")) {
            controller.start();
            Recording recording = extractRecording(controller);
            assertNotEquals("true", recording.getSettings().get("jdk.OldObjectSample#stackTrace"));
        }
    }

    @Test
    void oldObjectSamplingIsEnabledWhenOptedIn(@TempDir Path tempDir) throws Exception {
        assertTrue(FlightRecorder.isAvailable(), "JFR is not available in this runtime.");
        try (JfrController controller = new JfrController(Duration.ofSeconds(60), 16L * 1024L * 1024L,
            "hybox-test", List.of(), "default", true)) {
            controller.start();
            Recording recording = extractRecording(controller);
            assertEquals("true", recording.getSettings().get("jdk.OldObjectSample#enabled"));
            assertEquals("true", recording.getSettings().get("jdk.OldObjectSample#stackTrace"));
        }
    }

    @Test
    void defaultConfigurationKeepsReportEventsAndDropsUnreadOnes() throws Exception {
        try (JfrController controller = new JfrController(Duration.ofSeconds(60), 16L * 1024L * 1024L,
            "hybox-test")) {
            controller.start();
            Recording recording = extractRecording(controller);
            for (String event : List.of("jdk.ThreadSleep", "jdk.JavaMonitorWait",
                "jdk.NativeMethodSample", "jdk.SystemProcess", "jdk.InitialEnvironmentVariable")) {
                assertEquals("false", recording.getSettings().get(event + "#enabled"), event);
            }
            for (String event : List.of("jdk.ExecutionSample", "jdk.ObjectAllocationSample", "jdk.OldObjectSample",
                "jdk.CPULoad", "jdk.GarbageCollection", "jdk.InitialSystemProperty", "jdk.ThreadDump",
                "jdk.FileRead", "jdk.SafepointBegin", "jdk.JavaMonitorEnter", "jdk.ThreadPark")) {
                assertEquals("true", recording.getSettings().get(event + "#enabled"), event);
            }
            assertEquals("500 ms", recording.getSettings().get("jdk.ThreadPark#threshold"));
            assertEquals("500 ms", recording.getSettings().get("jdk.JavaMonitorEnter#threshold"));

            controller.applyConfiguration(ProfileMode.LAG);
            assertEquals("true", recording.getSettings().get("jdk.ThreadSleep#enabled"));
            assertNotEquals("500 ms", recording.getSettings().get("jdk.ThreadPark#threshold"));
            controller.applyConfiguration(null);
            assertEquals("false", recording.getSettings().get("jdk.ThreadSleep#enabled"));
            assertEquals("500 ms", recording.getSettings().get("jdk.ThreadPark#threshold"));
        }
    }

    @Test
    void profileModesParseTheirCommandWordsOnly() {
        assertEquals(ProfileMode.LAG, ProfileMode.parse("lag"));
        assertEquals(ProfileMode.MEMORY, ProfileMode.parse(" Memory "));
        assertEquals(null, ProfileMode.parse("cpu"));
        assertEquals(null, ProfileMode.parse(null));
    }

    @Test
    void lagModeKeepsTheWholeProfilePresetWithFastSamplesAndLowLockThresholds() throws Exception {
        Map<String, String> preset = JfrController.settings("profile");
        Map<String, String> lag = JfrController.profileSettings(ProfileMode.LAG);
        Set<String> tuned = Set.of("jdk.ExecutionSample#enabled", "jdk.ExecutionSample#period",
            "jdk.ThreadPark#enabled", "jdk.ThreadPark#threshold", "jdk.JavaMonitorEnter#enabled", "jdk.JavaMonitorEnter#threshold");
        for (Map.Entry<String, String> entry : preset.entrySet()) {
            if (!tuned.contains(entry.getKey())) assertEquals(entry.getValue(), lag.get(entry.getKey()), entry.getKey());
        }
        assertEquals("true", lag.get("jdk.ExecutionSample#enabled"));
        assertEquals("10 ms", lag.get("jdk.ExecutionSample#period"));
        assertEquals("10 ms", lag.get("jdk.ThreadPark#threshold"));
        assertEquals("10 ms", lag.get("jdk.JavaMonitorEnter#threshold"));
        assertEquals("0 ns", lag.get("jdk.OldObjectSample#cutoff"));
    }

    @Test
    void memoryModeAddsAllocationSamplesAndRootChainsOnTopOfLag() throws Exception {
        Map<String, String> lag = JfrController.profileSettings(ProfileMode.LAG);
        Map<String, String> memory = JfrController.profileSettings(ProfileMode.MEMORY);
        Set<String> tuned = Set.of("jdk.ObjectAllocationSample#enabled", "jdk.ObjectAllocationSample#throttle",
            "jdk.OldObjectSample#enabled", "jdk.OldObjectSample#stackTrace", "jdk.OldObjectSample#cutoff");
        for (Map.Entry<String, String> entry : lag.entrySet()) {
            if (!tuned.contains(entry.getKey())) assertEquals(entry.getValue(), memory.get(entry.getKey()), entry.getKey());
        }
        assertEquals("1000/s", memory.get("jdk.ObjectAllocationSample#throttle"));
        assertEquals("true", memory.get("jdk.OldObjectSample#enabled"));
        assertEquals("true", memory.get("jdk.OldObjectSample#stackTrace"));
        assertEquals("1 s", memory.get("jdk.OldObjectSample#cutoff"));
    }

    @Test
    void memoryModeKeepsItsRootSearchWhenOldObjectSamplingIsOnAndNormalModeTurnsItOff() throws Exception {
        try (JfrController controller = new JfrController(Duration.ofSeconds(60), 16L * 1024L * 1024L,
            "hybox-test", List.of(), "default", true)) {
            controller.start();
            Recording recording = extractRecording(controller);
            assertEquals("0 ns", recording.getSettings().get("jdk.OldObjectSample#cutoff"));
            controller.applyConfiguration(ProfileMode.MEMORY);
            assertEquals("1 s", recording.getSettings().get("jdk.OldObjectSample#cutoff"));
            assertEquals("1000/s", recording.getSettings().get("jdk.ObjectAllocationSample#throttle"));
            controller.applyConfiguration(ProfileMode.LAG);
            assertEquals("0 ns", recording.getSettings().get("jdk.OldObjectSample#cutoff"));
            controller.applyConfiguration(null);
            assertEquals("0 ns", recording.getSettings().get("jdk.OldObjectSample#cutoff"));
            assertEquals("500 ms", recording.getSettings().get("jdk.ThreadPark#threshold"));
        }
    }

    @Test
    void applyConfigurationSwitchesSettingsLiveWithoutRestart(@TempDir Path tempDir) throws Exception {
        assertTrue(FlightRecorder.isAvailable(), "JFR is not available in this runtime.");
        try (JfrController controller = new JfrController(
            Duration.ofSeconds(60), 16L * 1024L * 1024L, "hybox-test", List.of("jdk.CPULoad"))) {
            controller.start();
            Recording before = extractRecording(controller);

            controller.applyConfiguration(ProfileMode.LAG);
            Recording after = extractRecording(controller);

            assertSame(before, after);
            assertEquals("false", after.getSettings().get("jdk.CPULoad#enabled"));
        }
    }

    @Test
    void restartSwapsRecordingAndPreservesNameAndCaps(@TempDir Path tempDir) throws Exception {
        assertTrue(FlightRecorder.isAvailable(), "JFR is not available in this runtime.");
        Path dumpPath = tempDir.resolve("recording.jfr");

        try (JfrController controller = new JfrController(Duration.ofSeconds(60), 16L * 1024L * 1024L,
            "hybox-test")) {
            controller.start();
            Recording before = extractRecording(controller);

            controller.restart(ProfileMode.MEMORY);
            Recording profiled = extractRecording(controller);
            assertNotSame(before, profiled);
            assertEquals("hybox-test", profiled.getName());
            assertEquals(Duration.ofSeconds(60), profiled.getMaxAge());
            assertEquals(16L * 1024L * 1024L, profiled.getMaxSize());
            assertEquals("1 s", profiled.getSettings().get("jdk.OldObjectSample#cutoff"));

            controller.restart(null);
            Recording reverted = extractRecording(controller);
            assertNotSame(profiled, reverted);
            assertEquals("hybox-test", reverted.getName());

            controller.dump(dumpPath);
        }

        assertTrue(Files.exists(dumpPath), "Expected JFR dump to exist after restart.");
        assertTrue(Files.size(dumpPath) > 0, "Expected JFR dump to be non-empty after restart.");
    }

    private static Recording extractRecording(JfrController controller) throws Exception {
        Field field = JfrController.class.getDeclaredField("recording");
        field.setAccessible(true);
        return (Recording) field.get(controller);
    }
}
