package io.github.xytronix.hybox.core.jfr;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.UUID;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;
import jdk.jfr.RecordingState;
import org.junit.jupiter.api.Test;

class JfrLifecycleSafetyTest {
    @Test
    void failedStartDoesNotLeakARecording() {
        String name = "hybox-failed-start-" + UUID.randomUUID();
        try {
            assertThrows(IllegalArgumentException.class, () -> {
                try (var controller = new JfrController(Duration.ofSeconds(-1), 1024, name)) {
                    controller.start();
                }
            });
            assertTrue(FlightRecorder.getFlightRecorder().getRecordings().stream()
                .noneMatch(recording -> recording.getName().equals(name)));
        } finally {
            FlightRecorder.getFlightRecorder().getRecordings().stream()
                .filter(recording -> recording.getName().equals(name)).forEach(Recording::close);
        }
    }

    @Test
    void closeReleasesAnAlreadyStoppedRecording() {
        String name = "hybox-stopped-" + UUID.randomUUID();
        var controller = new JfrController(Duration.ofSeconds(60), 1024 * 1024, name);
        try {
            controller.start();
            Recording recording = FlightRecorder.getFlightRecorder().getRecordings().stream()
                .filter(candidate -> candidate.getName().equals(name)).findFirst().orElseThrow();
            recording.stop();
            assertDoesNotThrow(controller::close);
            assertEquals(RecordingState.CLOSED, recording.getState());
            assertDoesNotThrow(controller::close);
        } finally {
            FlightRecorder.getFlightRecorder().getRecordings().stream()
                .filter(recording -> recording.getName().equals(name)).forEach(Recording::close);
        }
    }
}
