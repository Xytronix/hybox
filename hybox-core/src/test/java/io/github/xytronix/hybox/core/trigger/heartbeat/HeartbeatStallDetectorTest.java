package io.github.xytronix.hybox.core.trigger.heartbeat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import io.github.xytronix.hybox.core.testutil.MutableClock;
import io.github.xytronix.hybox.core.trigger.TriggerEvent;

class HeartbeatStallDetectorTest {

    @Test
    void emitsOncePerStallTransition() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-11T00:00:00Z"), ZoneOffset.UTC);
        HeartbeatRegistry registry = new HeartbeatRegistry(clock);
        HeartbeatStallDetector detector = new HeartbeatStallDetector(clock, registry, 1000);

        registry.beat("world");

        assertEquals(0, detector.check().size());

        clock.advance(Duration.ofMillis(1100));
        assertEquals(1, detector.check().size());

        clock.advance(Duration.ofMillis(200));
        assertEquals(0, detector.check().size());

        registry.beat("world");
        clock.advance(Duration.ofMillis(1200));
        assertEquals(1, detector.check().size());
    }

    @Test
    void doesNotEmitForRemovedScope() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-11T00:00:00Z"), ZoneOffset.UTC);
        HeartbeatRegistry registry = new HeartbeatRegistry(clock);
        HeartbeatStallDetector detector = new HeartbeatStallDetector(clock, registry, 1000);

        registry.beat("world");
        registry.retain(Set.of());

        clock.advance(Duration.ofMillis(1100));
        assertEquals(0, detector.check().size());
    }

    @Test
    void stallIsTimeTheLatestPostHasWaited() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-11T00:00:00Z"), ZoneOffset.UTC);
        HeartbeatRegistry registry = new HeartbeatRegistry(clock);
        HeartbeatStallDetector detector = new HeartbeatStallDetector(clock, registry, 1000);

        registry.posted("world");
        registry.beat("world");
        clock.advance(Duration.ofMillis(900));
        registry.posted("world");
        clock.advance(Duration.ofMillis(900));
        assertEquals(0, detector.check().size());

        clock.advance(Duration.ofMillis(200));
        List<TriggerEvent> events = detector.check();
        assertEquals(1, events.size());
        assertEquals("1100", events.get(0).attrs().get("stallMs"));
    }

    @Test
    void includesBeatingThreadIdInEvent() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-11T00:00:00Z"), ZoneOffset.UTC);
        HeartbeatRegistry registry = new HeartbeatRegistry(clock);
        HeartbeatStallDetector detector = new HeartbeatStallDetector(clock, registry, 1000);

        registry.beat("world");
        clock.advance(Duration.ofMillis(1100));

        List<TriggerEvent> events = detector.check();
        assertEquals(1, events.size());
        assertEquals(Long.toString(Thread.currentThread().threadId()), events.get(0).attrs().get("threadId"));
    }

    @Test
    void settleMeasuresTheWholeStallOnceTheWorldBeatsAgain() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-11T00:00:00Z"), ZoneOffset.UTC);
        HeartbeatRegistry registry = new HeartbeatRegistry(clock);
        HeartbeatStallDetector detector = new HeartbeatStallDetector(clock, registry, 2000);
        registry.beat("world");
        clock.advance(Duration.ofMillis(2100));
        TriggerEvent event = detector.check().get(0);

        clock.advance(Duration.ofSeconds(40));
        registry.beat("world");
        clock.advance(Duration.ofMillis(250));
        TriggerEvent settled = HeartbeatStallDetector.settle(event, registry, clock.millis());

        assertEquals("42100", settled.attrs().get("stallMs"));
        assertNull(settled.attrs().get("frozen"));
    }

    @Test
    void settleUsesTheBeatThatEndedTheStallNotTheNewestBeat() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-11T00:00:00Z"), ZoneOffset.UTC);
        HeartbeatRegistry registry = new HeartbeatRegistry(clock);
        HeartbeatStallDetector detector = new HeartbeatStallDetector(clock, registry, 2000);
        registry.beat("world");
        clock.advance(Duration.ofMillis(250));
        registry.posted("world");
        clock.advance(Duration.ofMillis(2100));
        TriggerEvent event = detector.check().get(0);
        assertEquals("2100", event.attrs().get("stallMs"));

        clock.advance(Duration.ofMillis(150));
        registry.beat("world");
        clock.advance(Duration.ofMillis(250));
        registry.posted("world");
        registry.beat("world");
        clock.advance(Duration.ofMillis(250));
        registry.posted("world");
        registry.beat("world");
        clock.advance(Duration.ofMillis(300));
        TriggerEvent settled = HeartbeatStallDetector.settle(event, registry, clock.millis());

        assertEquals("2250", settled.attrs().get("stallMs"));
        assertNull(settled.attrs().get("frozen"));
    }

    @Test
    void settleMarksAWorldStillFrozenAtSaveTime() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-11T00:00:00Z"), ZoneOffset.UTC);
        HeartbeatRegistry registry = new HeartbeatRegistry(clock);
        HeartbeatStallDetector detector = new HeartbeatStallDetector(clock, registry, 2000);
        registry.beat("world");
        clock.advance(Duration.ofMillis(2100));
        TriggerEvent event = detector.check().get(0);

        clock.advance(Duration.ofMinutes(2));
        TriggerEvent settled = HeartbeatStallDetector.settle(event, registry, clock.millis());

        assertEquals("122100", settled.attrs().get("stallMs"));
        assertEquals("true", settled.attrs().get("frozen"));
    }

    @Test
    void settleKeepsTheEventWhenTheWorldIsGone() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-11T00:00:00Z"), ZoneOffset.UTC);
        HeartbeatRegistry registry = new HeartbeatRegistry(clock);
        HeartbeatStallDetector detector = new HeartbeatStallDetector(clock, registry, 2000);
        registry.beat("world");
        clock.advance(Duration.ofMillis(2100));
        TriggerEvent event = detector.check().get(0);
        registry.retain(Set.of());

        assertEquals(event, HeartbeatStallDetector.settle(event, registry, clock.millis() + 60_000));
    }
}
