package io.github.xytronix.hybox.hytale;

import static org.junit.jupiter.api.Assertions.*;

import io.github.xytronix.hybox.core.trigger.TriggerEvent;
import io.github.xytronix.hybox.core.trigger.TriggerKind;
import io.github.xytronix.hybox.core.trigger.heartbeat.HeartbeatRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class HytaleHeartbeatPumpTest {
    private static final System.Logger LOGGER = System.getLogger("heartbeat-pump-test");

    @Test
    void zeroDumpCountTakesNoInitialOrSpacedDumps() {
        Clock clock = Clock.systemUTC();
        TriggerEvent event = new TriggerEvent(TriggerKind.HEARTBEAT_STALL, "world", clock.instant(), Map.of());
        List<String> dumps = HytaleHeartbeatPump.awaitRecovery(event, new HeartbeatRegistry(clock), clock,
            Duration.ofMillis(600), 0, Duration.ofMillis(1), null, LOGGER);
        assertTrue(dumps.isEmpty());
    }

    @Test
    void spacedDumpsStopAtConfiguredCount() {
        Clock clock = Clock.systemUTC();
        TriggerEvent event = new TriggerEvent(TriggerKind.TICK_DEGRADED, "world", clock.instant(), Map.of());
        List<String> dumps = HytaleHeartbeatPump.awaitRecovery(event, new HeartbeatRegistry(clock), clock,
            Duration.ofMillis(900), 2, Duration.ofMillis(1), null, LOGGER);
        assertEquals(2, dumps.size());
    }

    @Test
    void detectorRecoveryEndsWaitAndKeepsInitialEvidence() {
        Clock clock = Clock.systemUTC();
        AtomicBoolean recovered = new AtomicBoolean();
        TriggerEvent event = new TriggerEvent(TriggerKind.GC_PRESSURE, "jvm", clock.instant(), Map.of());
        long started = System.nanoTime();
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
            }
            recovered.set(true);
        });
        List<String> dumps = HytaleHeartbeatPump.awaitRecovery(event, new HeartbeatRegistry(clock), clock,
            Duration.ofSeconds(30), 3, Duration.ofSeconds(10), recovered::get, LOGGER);
        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(5)) < 0);
        assertEquals(1, dumps.size());
    }

    @Test
    void heartbeatFromAnotherScopeDoesNotEndNonHeartbeatWait() {
        Clock clock = Clock.systemUTC();
        HeartbeatRegistry registry = new HeartbeatRegistry(clock);
        TriggerEvent event = new TriggerEvent(TriggerKind.TICK_DEGRADED, "world", clock.instant(), Map.of());
        registry.beat("world");
        long started = System.nanoTime();
        HytaleHeartbeatPump.awaitRecovery(event, registry, clock, Duration.ofMillis(600), 0,
            Duration.ofMillis(1), () -> false, LOGGER);
        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofMillis(550)) >= 0);
    }
}
