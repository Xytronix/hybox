package io.github.xytronix.hybox.hytale;

import static org.junit.jupiter.api.Assertions.*;

import io.github.xytronix.hybox.core.trigger.TriggerEvent;
import io.github.xytronix.hybox.core.trigger.TriggerKind;
import io.github.xytronix.hybox.core.trigger.heartbeat.HeartbeatRegistry;
import com.hypixel.hytale.server.core.universe.world.World;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import com.hypixel.hytale.server.core.util.thread.TickingThread;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

class HytaleHeartbeatPumpTest {
    private static final System.Logger LOGGER = System.getLogger("heartbeat-pump-test");

    @Test
    void removedOrStoppedWorldIsSkippedWithoutWarningAndDropsItsHeartbeat() throws Exception {
        HeartbeatRegistry registry = new HeartbeatRegistry(Clock.systemUTC());
        RecordingLogger logger = new RecordingLogger();
        HytaleHeartbeatPump pump = new HytaleHeartbeatPump(logger, registry);
        World world = world(World.class);
        pump.tick(Map.of("instance", world));
        run(world);
        assertNotNull(registry.lastBeat("instance"));

        set(World.class, world, "acceptingTasks", new AtomicBoolean(false));
        set(World.class, world, "alive", new AtomicBoolean(false));
        pump.tick(Map.of("instance", world));

        assertTrue(logger.warnings.isEmpty(), logger.warnings::toString);
        assertNull(registry.lastBeat("instance"));
        assertTrue(queue(world).isEmpty());
    }

    @Test
    void rejectionFromStoppingWorldClearsStaleBeatWithoutWarning() throws Exception {
        HeartbeatRegistry registry = new HeartbeatRegistry(Clock.systemUTC());
        RecordingLogger logger = new RecordingLogger();
        HytaleHeartbeatPump pump = new HytaleHeartbeatPump(logger, registry);
        World world = world(World.class);
        pump.tick(Map.of("instance", world));
        run(world);
        assertNotNull(registry.lastBeat("instance"));

        set(World.class, world, "acceptingTasks", new AtomicBoolean(false));
        pump.tick(Map.of("instance", world));

        assertTrue(logger.warnings.isEmpty(), logger.warnings::toString);
        assertNull(registry.lastBeat("instance"));
    }

    @Test
    void unrelatedPostFailureOnLiveWorldIsStillReported() throws Exception {
        RecordingLogger logger = new RecordingLogger();
        HytaleHeartbeatPump pump = new HytaleHeartbeatPump(logger, new HeartbeatRegistry(Clock.systemUTC()));
        pump.tick(Map.of("instance", world(FailingWorld.class)));
        assertEquals(1, logger.warnings.size(), logger.warnings::toString);
        assertTrue(logger.warnings.get(0).startsWith("Failed to post heartbeat for instance"));
    }

    @Test
    void liveWorldThatNeverRunsTasksKeepsLastBeatWithoutRepost() throws Exception {
        HeartbeatRegistry registry = new HeartbeatRegistry(Clock.systemUTC());
        HytaleHeartbeatPump pump = new HytaleHeartbeatPump(new RecordingLogger(), registry);
        World world = world(World.class);
        pump.tick(Map.of("default", world));
        run(world);
        pump.tick(Map.of("default", world));
        var before = registry.lastBeat("default");
        for (int i = 0; i < 5; i++) {
            pump.tick(Map.of("default", world));
        }
        assertEquals(1, queue(world).size());
        assertEquals(before, registry.lastBeat("default"));
    }

    @Test
    void scopeReusedByNewWorldIgnoresOldPendingTaskAndBeatsForNewWorld() throws Exception {
        HeartbeatRegistry registry = new HeartbeatRegistry(Clock.systemUTC());
        HytaleHeartbeatPump pump = new HytaleHeartbeatPump(new RecordingLogger(), registry);
        World old = world(World.class);
        pump.tick(Map.of("instance", old));
        run(old);
        pump.tick(Map.of("instance", old));
        assertEquals(1, queue(old).size());

        World replacement = world(World.class);
        pump.tick(Map.of("instance", replacement));
        assertNull(registry.lastBeat("instance"));
        assertEquals(1, queue(replacement).size());

        run(old);
        assertNull(registry.lastBeat("instance"));
        run(replacement);
        assertNotNull(registry.lastBeat("instance"));
    }

    @Test
    void immediateBeatFromReplacementWorldSurvivesTheSameTick() throws Exception {
        HeartbeatRegistry registry = new HeartbeatRegistry(Clock.systemUTC());
        HytaleHeartbeatPump pump = new HytaleHeartbeatPump(new RecordingLogger(), registry);
        World old = world(World.class);
        pump.tick(Map.of("instance", old));
        run(old);
        var oldBeat = registry.lastBeat("instance");

        pump.tick(Map.of("instance", world(ImmediateWorld.class)));

        assertNotNull(registry.lastBeat("instance"));
        assertTrue(registry.lastBeat("instance").compareTo(oldBeat) >= 0);
        assertEquals(Thread.currentThread().threadId(), registry.lastBeatThreadId("instance"));
    }

    static class ImmediateWorld extends World {
        ImmediateWorld() {
            super(null, null, null);
        }

        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }

    static class FailingWorld extends World {
        FailingWorld() {
            super(null, null, null);
        }

        @Override
        public void execute(Runnable command) {
            throw new IllegalStateException("queue failure");
        }
    }

    private static World world(Class<? extends World> type) throws Exception {
        World world = (World) unsafe().allocateInstance(type);
        set(World.class, world, "alive", new AtomicBoolean(true));
        set(World.class, world, "acceptingTasks", new AtomicBoolean(true));
        set(World.class, world, "taskQueue", new LinkedBlockingDeque<Runnable>());
        set(TickingThread.class, world, "currentRun", new AtomicReference<>());
        return world;
    }

    @SuppressWarnings("unchecked")
    private static Deque<Runnable> queue(World world) throws Exception {
        Field field = World.class.getDeclaredField("taskQueue");
        field.setAccessible(true);
        return (Deque<Runnable>) field.get(world);
    }

    private static void run(World world) throws Exception {
        Runnable task;
        while ((task = queue(world).poll()) != null) {
            task.run();
        }
    }

    private static void set(Class<?> owner, World world, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(world, value);
    }

    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }

    private static final class RecordingLogger implements System.Logger {
        private final List<String> warnings = new ArrayList<>();

        @Override
        public String getName() {
            return "recording";
        }

        @Override
        public boolean isLoggable(Level level) {
            return true;
        }

        @Override
        public void log(Level level, java.util.ResourceBundle bundle, String msg, Throwable thrown) {
            if (level.getSeverity() >= Level.WARNING.getSeverity()) {
                warnings.add(msg + (thrown == null ? "" : ": " + thrown));
            }
        }

        @Override
        public void log(Level level, java.util.ResourceBundle bundle, String format, Object... params) {
            if (level.getSeverity() >= Level.WARNING.getSeverity()) {
                warnings.add(format);
            }
        }
    }

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
