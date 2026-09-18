package io.github.xytronix.hybox.core.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.xytronix.hybox.core.incident.IncidentId;

class CaptureResultTest {
    @Test
    void onlyCapturedResultsCarryAnIncidentId() {
        IncidentId id = new IncidentId("incident");
        assertThrows(IllegalArgumentException.class, () -> CaptureResult.of(CaptureResult.Status.CAPTURED));
        assertThrows(IllegalArgumentException.class,
            () -> new CaptureResult(CaptureResult.Status.FAILED, id, "IOException"));
        assertTrue(CaptureResult.captured(id).captured());
        assertFalse(CaptureResult.failed("IOException").captured());
    }

    @Test
    void failureDetailsCannotInjectControlsOrGrowWithoutBound() {
        CaptureResult result = CaptureResult.failed("IOException\n\r\u001b\u202e" + "x".repeat(1024));
        assertTrue(result.detail().length() <= 128);
        assertEquals("IOException", result.detail().substring(0, 11));
        assertFalse(result.detail().chars().anyMatch(character -> Character.isISOControl(character)
            || Character.getType(character) == Character.FORMAT));
    }
}
