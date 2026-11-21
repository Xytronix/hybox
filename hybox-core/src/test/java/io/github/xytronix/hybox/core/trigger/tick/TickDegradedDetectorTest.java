package io.github.xytronix.hybox.core.trigger.tick;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import io.github.xytronix.hybox.core.testutil.MutableClock;
import io.github.xytronix.hybox.core.trigger.TriggerEvent;
import io.github.xytronix.hybox.core.trigger.TriggerKind;
import io.github.xytronix.hybox.core.trigger.TriggerPolicy;
import io.github.xytronix.hybox.core.trigger.tick.TickDegradedDetector.Tick;

class TickDegradedDetectorTest {

    private static final TriggerPolicy FIXED = new TriggerPolicy(Duration.ZERO, Duration.ZERO, 1000, 5000, 100, 250);
    private static final TriggerPolicy AUTO = new TriggerPolicy(Duration.ZERO, Duration.ZERO, 1000, 5000, 0, 0);

    @Test
    void recoveredOnlyAfterAverageFallsBelowRearmLevel() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-11T00:00:00Z"), ZoneOffset.UTC);
        Map<String, Tick> ticks = new HashMap<>();
        TickDegradedDetector detector = new TickDegradedDetector(clock, () -> ticks, FIXED);

        ticks.put("world", new Tick(150.0, 30));
        assertEquals(1, detector.check().size());
        assertFalse(detector.recovered("world"));
        assertTrue(detector.recovered("other"));

        ticks.put("world", new Tick(95.0, 30));
        detector.check();
        assertFalse(detector.recovered("world"));

        ticks.put("world", new Tick(80.0, 30));
        detector.check();
        assertTrue(detector.recovered("world"));
    }

    @Test
    void emitsOncePerDegradedTransition() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-11T00:00:00Z"), ZoneOffset.UTC);
        Map<String, Tick> ticks = new HashMap<>();
        TickDegradedDetector detector = new TickDegradedDetector(clock, () -> ticks, FIXED);

        ticks.put("world", new Tick(50.0, 30));
        assertEquals(0, detector.check().size());

        ticks.put("world", new Tick(150.0, 30));
        List<TriggerEvent> events = detector.check();
        assertEquals(1, events.size());
        assertEquals(TriggerKind.TICK_DEGRADED, events.get(0).kind());
        assertEquals("world", events.get(0).scope());
        assertEquals("150", events.get(0).attrs().get("tickAvgMs"));
        assertEquals("30", events.get(0).attrs().get("targetTps"));

        ticks.put("world", new Tick(300.0, 30));
        assertEquals(0, detector.check().size());

        ticks.put("world", new Tick(80.0, 30));
        assertEquals(0, detector.check().size());

        ticks.put("world", new Tick(120.0, 30));
        assertEquals(1, detector.check().size());
    }

    @Test
    void requiresRecoveryMarginBeforeReArming() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-11T00:00:00Z"), ZoneOffset.UTC);
        Map<String, Tick> ticks = new HashMap<>();
        TickDegradedDetector detector = new TickDegradedDetector(clock, () -> ticks, FIXED);

        ticks.put("world", new Tick(150.0, 30));
        assertEquals(1, detector.check().size());

        ticks.put("world", new Tick(95.0, 30));
        assertEquals(0, detector.check().size());

        ticks.put("world", new Tick(120.0, 30));
        assertEquals(0, detector.check().size());

        ticks.put("world", new Tick(80.0, 30));
        assertEquals(0, detector.check().size());

        ticks.put("world", new Tick(120.0, 30));
        assertEquals(1, detector.check().size());
    }

    @Test
    void clearsStateForRemovedScope() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-11T00:00:00Z"), ZoneOffset.UTC);
        Map<String, Tick> ticks = new HashMap<>();
        TickDegradedDetector detector = new TickDegradedDetector(clock, () -> ticks, FIXED);

        ticks.put("world", new Tick(150.0, 30));
        assertEquals(1, detector.check().size());

        ticks.clear();
        assertEquals(0, detector.check().size());

        ticks.put("world", new Tick(150.0, 30));
        assertEquals(1, detector.check().size());
    }

    @Test
    void autoFiresEachWorldAgainstItsOwnTickBudget() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-11T00:00:00Z"), ZoneOffset.UTC);
        Map<String, Tick> ticks = new HashMap<>();
        TickDegradedDetector detector = new TickDegradedDetector(clock, () -> ticks, AUTO);

        ticks.put("fast", new Tick(60.0, 60));
        ticks.put("normal", new Tick(60.0, 30));
        List<TriggerEvent> events = detector.check();
        assertEquals(1, events.size());
        assertEquals("fast", events.get(0).scope());
        assertEquals("60", events.get(0).attrs().get("tickAvgMs"));
        assertEquals("60", events.get(0).attrs().get("targetTps"));
        assertFalse(detector.recovered("fast"));
        assertTrue(detector.recovered("normal"));

        ticks.put("normal", new Tick(100.0, 30));
        events = detector.check();
        assertEquals(1, events.size());
        assertEquals("normal", events.get(0).scope());
        assertEquals("30", events.get(0).attrs().get("targetTps"));

        ticks.put("fast", new Tick(46.0, 60));
        detector.check();
        assertFalse(detector.recovered("fast"));

        ticks.put("fast", new Tick(44.0, 60));
        detector.check();
        assertTrue(detector.recovered("fast"));

        ticks.put("fast", new Tick(50.0, 60));
        assertEquals(1, detector.check().size());
    }

    @Test
    void autoUsesThirtyTpsWhenTargetIsUnknown() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-11T00:00:00Z"), ZoneOffset.UTC);
        Map<String, Tick> ticks = new HashMap<>();
        TickDegradedDetector detector = new TickDegradedDetector(clock, () -> ticks, AUTO);

        ticks.put("world", new Tick(99.0, 0));
        assertEquals(0, detector.check().size());

        ticks.put("world", new Tick(100.0, 0));
        List<TriggerEvent> events = detector.check();
        assertEquals(1, events.size());
        assertEquals("30", events.get(0).attrs().get("targetTps"));
    }
}
