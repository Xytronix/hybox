package io.github.xytronix.hybox.core.trigger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class TriggerPolicyTest {

    private static TriggerPolicy policy(long degradedMs, long criticalMs) {
        return new TriggerPolicy(Duration.ZERO, Duration.ZERO, 1000, 5000, degradedMs, criticalMs);
    }

    @Test
    void autoFollowsTheTickBudget() {
        TriggerPolicy auto = policy(0, 0);

        assertEquals(100.0, auto.tickDegradedMs(30));
        assertEquals(250.0, auto.tickCriticalMs(30));
        assertEquals(50.0, auto.tickDegradedMs(60));
        assertEquals(125.0, auto.tickCriticalMs(60));
        assertEquals(150.0, auto.tickDegradedMs(20));
        assertEquals(375.0, auto.tickCriticalMs(20));
    }

    @Test
    void autoUsesThirtyTpsWhenTargetIsUnknown() {
        TriggerPolicy auto = policy(0, 0);

        assertEquals(100.0, auto.tickDegradedMs(0));
        assertEquals(250.0, auto.tickCriticalMs(-5));
        assertEquals(100.0, auto.tickDegradedMs(Double.NaN));
    }

    @Test
    void fixedLimitsIgnoreTheTarget() {
        TriggerPolicy fixed = policy(150, 400);

        assertEquals(150.0, fixed.tickDegradedMs(30));
        assertEquals(150.0, fixed.tickDegradedMs(60));
        assertEquals(400.0, fixed.tickCriticalMs(20));
        assertEquals(400.0, fixed.tickCriticalMs(60));
    }

    @Test
    void criticalNeverFallsBelowDegradedWhenMixingFixedAndAuto() {
        TriggerPolicy fixedDegraded = policy(300, 0);
        assertEquals(300.0, fixedDegraded.tickDegradedMs(60));
        assertEquals(300.0, fixedDegraded.tickCriticalMs(60));
        assertEquals(375.0, fixedDegraded.tickCriticalMs(20));

        TriggerPolicy fixedCritical = policy(0, 80);
        assertEquals(150.0, fixedCritical.tickDegradedMs(20));
        assertEquals(150.0, fixedCritical.tickCriticalMs(20));
        assertEquals(80.0, fixedCritical.tickCriticalMs(60));
    }

    @Test
    void validatesTickThresholds() {
        assertDoesNotThrow(() -> policy(0, 0));
        assertDoesNotThrow(() -> policy(300, 0));
        assertDoesNotThrow(() -> policy(0, 80));
        assertThrows(IllegalArgumentException.class, () -> policy(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> policy(0, -1));
        assertThrows(IllegalArgumentException.class, () -> policy(300, 200));
    }
}
