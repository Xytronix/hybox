package io.github.xytronix.hybox.core.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Collectors;
import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import jdk.jfr.StackTrace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.xytronix.hybox.core.jfr.HyboxConnectionEvent;
import io.github.xytronix.hybox.core.jfr.HyboxMarkerEvent;
import io.github.xytronix.hybox.core.jfr.HyboxPluginEvent;
import io.github.xytronix.hybox.core.jfr.HyboxPluginMetricEvent;
import io.github.xytronix.hybox.core.jfr.HyboxSystemTickEvent;
import io.github.xytronix.hybox.core.jfr.HyboxWorldTickEvent;
import io.github.xytronix.hybox.core.jfr.IncidentMark;

class JfrTimelineTest {

    @Test
    void decodesJvmArrayDescriptors() {
        assertEquals("long[]", JfrTimelineParser.decodeTypeName("[J"));
        assertEquals("byte[]", JfrTimelineParser.decodeTypeName("[B"));
        assertEquals("int[][]", JfrTimelineParser.decodeTypeName("[[I"));
        assertEquals("java.lang.Object[]", JfrTimelineParser.decodeTypeName("[Ljava.lang.Object;"));
        assertEquals("java.lang.String", JfrTimelineParser.decodeTypeName("java.lang.String"));
        assertNull(JfrTimelineParser.decodeTypeName(null));
    }

    @Test
    void onlyHytaleWorldThreadsBecomeWorlds() {
        assertEquals("default", JfrTimelineParser.worldOf("WorldThread - default"));
        assertEquals("default", JfrTimelineParser.worldOf("ChunkLighting - default"));
        assertEquals("instance-a", JfrTimelineParser.worldOf("WorldMap - instance-a"));
        assertEquals(JfrTimelineParser.NO_WORLD, JfrTimelineParser.worldOf("Document Writer - 1"));
        assertEquals(JfrTimelineParser.NO_WORLD, JfrTimelineParser.worldOf("pool-3-thread-1"));
        assertEquals(JfrTimelineParser.NO_WORLD, JfrTimelineParser.worldOf(null));
    }

    @Test
    void readsJavaVersionAndArchitectureFromTheRecordedJvm() {
        JfrTimeline.JvmInfo mac = JfrTimelineParser.jvmInfo("OpenJDK 64-Bit Server VM",
            "OpenJDK 64-Bit Server VM (26.0.2+10) for bsd-aarch64 JRE (26.0.2+10), built on 2026-07-21T00:00:00Z");
        assertEquals(new JfrTimeline.JvmInfo("OpenJDK 64-Bit Server VM", "26.0.2+10", "aarch64"), mac);
        JfrTimeline.JvmInfo linux = JfrTimelineParser.jvmInfo("OpenJDK 64-Bit Server VM",
            "OpenJDK 64-Bit Server VM (25.0.4.1+1-LTS) for linux-amd64 JRE (25.0.4.1+1-LTS), built on 2026-07-15");
        assertEquals("25.0.4.1+1-LTS", linux.release());
        assertEquals("amd64", linux.arch());
    }

    @Test
    void parsesWorldTickSeriesAndWindow(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("recording.jfr");
        try (Recording recording = new Recording()) {
            recording.enable(HyboxWorldTickEvent.class).withoutStackTrace();
            recording.start();
            for (int i = 0; i < 4; i++) {
                HyboxWorldTickEvent event = new HyboxWorldTickEvent();
                event.world = "default";
                event.tps = 30 - i;
                event.mspt = 33 + i;
                event.players = 5 + i;
                event.entities = 100 + i;
                event.commit();
                Thread.sleep(60);
            }
            recording.dump(target);
        }

        JfrTimeline timeline = JfrTimeline.parse(target);

        assertNotNull(timeline);
        assertNotNull(timeline.start());
        assertNotNull(timeline.end());
        assertTrue(timeline.end().isAfter(timeline.start()));
        assertTrue(timeline.ticks().containsKey("default"));

        double[] tps = timeline.ticks().get("default").tps();
        assertEquals(JfrTimeline.BUCKETS, tps.length);
        assertEquals(30.0, tps[0], 0.001);
        assertEquals(27.0, tps[tps.length - 1], 0.001);

        double[] players = timeline.playersSeries();
        assertNotNull(players);
        assertEquals(8.0, players[players.length - 1], 0.001);

        double[] entities = timeline.entitiesSeries();
        assertNotNull(entities);
        assertEquals(JfrTimeline.BUCKETS, entities.length);
        assertEquals(100.0, entities[0], 0.001);
        assertEquals(103.0, entities[entities.length - 1], 0.001);

        assertNull(timeline.hostMemSeries());
        assertNull(timeline.heapCommittedSeries());
        assertTrue(timeline.swapTotal() <= 0);
        assertTrue(timeline.netOthers().isEmpty());
    }

    @Test
    void readsLockWaitsOfWorldThreadsAndContendedLocksOverTheThreshold(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("recording.jfr");
        try (Recording recording = new Recording()) {
            recording.enable("jdk.ThreadPark").withThreshold(Duration.ofMillis(50)).withStackTrace();
            recording.enable("jdk.JavaMonitorEnter").withThreshold(Duration.ofMillis(50)).withStackTrace();
            recording.start();
            ReentrantLock worldLock = new ReentrantLock();
            ReentrantLock poolLock = new ReentrantLock();
            ReentrantLock briefLock = new ReentrantLock();
            ReentrantLock idleLock = new ReentrantLock();
            Condition idle = idleLock.newCondition();
            Object monitor = new Object();
            CountDownLatch held = new CountDownLatch(4);
            List<Thread> threads = List.of(
                new Thread(() -> holdLock(worldLock, held, 800), "holder-world"),
                new Thread(() -> holdLock(poolLock, held, 800), "holder-pool"),
                new Thread(() -> holdLock(briefLock, held, 150), "holder-brief"),
                new Thread(() -> {
                    synchronized (monitor) {
                        held.countDown();
                        pause(800);
                    }
                }, "holder-monitor"),
                new Thread(() -> lockAfter(worldLock, held), "WorldThread - test"),
                new Thread(() -> lockAfter(poolLock, held), "pool-7"),
                new Thread(() -> lockAfter(briefLock, held), "WorldThread - brief"),
                new Thread(() -> {
                    awaitLatch(held);
                    synchronized (monitor) {
                        monitor.notifyAll();
                    }
                }, "monitor-waiter"),
                new Thread(() -> {
                    idleLock.lock();
                    try {
                        idle.await(800, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        idleLock.unlock();
                    }
                }, "worker-1"));
            threads.forEach(Thread::start);
            for (Thread thread : threads) {
                thread.join();
            }
            recording.dump(target);
        }

        JfrTimeline timeline = JfrTimeline.parse(target);

        assertNotNull(timeline);
        Map<String, JfrTimeline.LockWait> waits = timeline.lockWaits().stream()
            .collect(Collectors.toMap(JfrTimeline.LockWait::thread, Function.identity(), (a, b) -> a));
        assertTrue(!waits.containsKey("WorldThread - brief") && !waits.containsKey("worker-1"), waits.keySet().toString());
        JfrTimeline.LockWait world = waits.get("WorldThread - test");
        assertNotNull(world, waits.keySet().toString());
        assertEquals("java.util.concurrent.locks.ReentrantLock$NonfairSync", world.lock());
        assertTrue(world.durationMs() >= JfrTimeline.LOCK_WAIT_MS, String.valueOf(world.durationMs()));
        assertEquals(JfrTimelineTest.class.getName() + ".lockAfter", world.frame());
        assertEquals("pool", waits.get("pool-7").group());
        JfrTimeline.LockWait monitorWait = waits.get("monitor-waiter");
        assertTrue(monitorWait.monitor());
        assertEquals("java.lang.Object", monitorWait.lock());
        assertEquals("holder-monitor", monitorWait.owner());
    }

    private static void holdLock(Lock lock, CountDownLatch held, long millis) {
        lock.lock();
        try {
            held.countDown();
            pause(millis);
        } finally {
            lock.unlock();
        }
    }

    private static void lockAfter(Lock lock, CountDownLatch held) {
        awaitLatch(held);
        lock.lock();
        lock.unlock();
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void playerTotalsDoNotCountAWorldAfterItStopsReporting(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("recording.jfr");
        try (Recording recording = new Recording()) {
            recording.enable(HyboxWorldTickEvent.class).withoutStackTrace();
            recording.start();
            for (int i = 0; i < 12; i++) {
                boolean inInstance = i >= 4 && i < 8;
                worldTick("default", inInstance ? 0 : 1, 100);
                if (inInstance) {
                    worldTick("instance-forgotten-temple", 1, 40);
                }
                Thread.sleep(300);
            }
            recording.dump(target);
        }

        JfrTimeline timeline = JfrTimeline.parse(target);

        assertNotNull(timeline);
        for (double players : timeline.playersSeries()) {
            assertEquals(1.0, players, 0.001);
        }
        double[] entities = timeline.entitiesSeries();
        assertEquals(100.0, entities[0], 0.001);
        assertEquals(100.0, entities[entities.length - 1], 0.001);
        assertEquals(140.0, java.util.Arrays.stream(entities).max().orElseThrow(), 0.001);
    }

    @Test
    void playerTotalsUseTheUniverseCountDuringAWorldTransfer(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("recording.jfr");
        try (Recording recording = new Recording()) {
            recording.enable(HyboxWorldTickEvent.class).withoutStackTrace();
            recording.start();
            for (int i = 0; i < 12; i++) {
                boolean inTransfer = i >= 4 && i < 8;
                worldTick("default", inTransfer ? 1 : 2, 100, 2);
                worldTick("instance-forgotten-temple", 0, 40, 2);
                Thread.sleep(300);
            }
            recording.dump(target);
        }

        JfrTimeline timeline = JfrTimeline.parse(target);

        assertNotNull(timeline);
        for (double players : timeline.playersSeries()) {
            assertEquals(2.0, players, 0.001);
        }
    }

    @Test
    void slowIoLimitIsTheLowestFileThresholdInTheRecording(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("recording.jfr");
        try (Recording recording = new Recording()) {
            recording.enable(HyboxWorldTickEvent.class).withoutStackTrace();
            recording.enable("jdk.ActiveSetting");
            recording.enable("jdk.FileRead").withThreshold(java.time.Duration.ofMillis(10));
            recording.enable("jdk.FileWrite").withThreshold(java.time.Duration.ofMillis(20));
            recording.start();
            for (int i = 0; i < 3; i++) {
                worldTick("default", 1, 100);
                Thread.sleep(100);
            }
            recording.dump(target);
        }

        assertEquals(10, JfrTimeline.parse(target).slowIoThresholdMs());
    }

    @Test
    void consoleDevicesAndPipesAreNotDiskPaths() {
        assertTrue(JfrTimelineParser.diskPath("universe/worlds/default/chunks/0.0.region.bin"));
        assertTrue(JfrTimelineParser.diskPath("C:\\Hytale\\Saves\\Test\\config.json"));
        assertTrue(!JfrTimelineParser.diskPath(null));
        assertTrue(!JfrTimelineParser.diskPath("/dev/tty"));
        assertTrue(!JfrTimelineParser.diskPath("/dev/stdin"));
        assertTrue(!JfrTimelineParser.diskPath("/proc/self/fd/0"));
        assertTrue(!JfrTimelineParser.diskPath("\\\\.\\pipe\\hytale"));
        assertTrue(!JfrTimelineParser.diskPath("\\\\.\\CONIN$"));
    }

    private static void worldTick(String world, int players, int entities) {
        worldTick(world, players, entities, -1);
    }

    private static void worldTick(String world, int players, int entities, int onlinePlayers) {
        HyboxWorldTickEvent event = new HyboxWorldTickEvent();
        event.world = world;
        event.tps = 30;
        event.mspt = 5;
        event.players = players;
        event.onlinePlayers = onlinePlayers;
        event.entities = entities;
        event.commit();
    }

    @Test
    void parsesPingSeriesAndPluginEvents(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("recording.jfr");
        try (Recording recording = new Recording()) {
            recording.enable(HyboxWorldTickEvent.class).withoutStackTrace();
            recording.enable(HyboxPluginEvent.class).withoutStackTrace();
            recording.start();
            for (int i = 0; i < 4; i++) {
                HyboxWorldTickEvent event = new HyboxWorldTickEvent();
                event.world = "default";
                event.tps = 30;
                event.mspt = 33;
                event.players = 10;
                event.entities = -1;
                event.avgPingMs = 40 + i;
                event.commit();
                if (i == 2) {
                    HyboxPluginEvent plugin = new HyboxPluginEvent();
                    plugin.category = "AiTickThrottler";
                    plugin.message = "throttled 42 entities";
                    plugin.commit();
                }
                Thread.sleep(60);
            }
            recording.dump(target);
        }

        JfrTimeline timeline = JfrTimeline.parse(target);

        assertNotNull(timeline);
        double[] ping = timeline.pingSeries();
        assertNotNull(ping);
        assertEquals(JfrTimeline.BUCKETS, ping.length);
        assertEquals(40.0, ping[0], 0.001);
        assertEquals(43.0, ping[ping.length - 1], 0.001);

        assertNull(timeline.entitiesSeries());

        assertEquals(1, timeline.pluginEvents().size());
        JfrTimeline.PluginEvent event = timeline.pluginEvents().get(0);
        assertEquals("AiTickThrottler", event.category());
        assertEquals("throttled 42 entities", event.message());
        assertTrue(event.timeMs() >= timeline.start().toEpochMilli());
        assertTrue(event.timeMs() <= timeline.end().toEpochMilli());
    }

    @Test
    void readsIncidentMarksAndIgnoresOtherMarkers(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("recording.jfr");
        try (Recording recording = new Recording()) {
            recording.enable(HyboxMarkerEvent.class).withoutStackTrace();
            recording.start();
            IncidentMark.commit("inc-7", "DEGRADED", "TPS dropped to 12");
            HyboxMarkerEvent dump = new HyboxMarkerEvent();
            dump.message = "hybox dump: recording.jfr";
            dump.commit();
            Thread.sleep(20);
            recording.dump(target);
        }

        JfrTimeline timeline = JfrTimeline.parse(target);

        assertNotNull(timeline);
        assertEquals(1, timeline.incidentMarkers().size());
        JfrTimeline.IncidentMarker mark = timeline.incidentMarkers().get(0);
        assertEquals("inc-7", mark.id());
        assertEquals("DEGRADED", mark.severity());
        assertEquals("TPS dropped to 12", mark.headline());
        assertTrue(mark.timeMs() >= timeline.start().toEpochMilli());
        assertTrue(mark.timeMs() <= timeline.end().toEpochMilli());
    }

    static final class Leak {
        static final List<char[]> CACHE = new java.util.ArrayList<>();
    }

    @Test
    void oldObjectSamplesKeepTheirMostCommonPathToAGcRoot(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("recording.jfr");
        try (Recording recording = new Recording()) {
            recording.enable("jdk.OldObjectSample").with("cutoff", "1 s");
            recording.start();
            for (int i = 0; i < 200; i++) {
                Leak.CACHE.add(new char[64 * 1024]);
            }
            System.gc();
            recording.stop();
            recording.dump(target);
        } finally {
            Leak.CACHE.clear();
        }

        JfrTimeline timeline = JfrTimeline.parse(target);

        assertNotNull(timeline);
        JfrTimeline.Retained retained = timeline.retained().stream()
            .filter(r -> r.className().equals("char[]")).findFirst().orElseThrow();
        JfrTimeline.RetainedChain chain = retained.chain();
        assertNotNull(chain);
        assertTrue(chain.samples() > 0 && chain.samples() <= chain.withPath(), chain.toString());
        assertTrue(chain.withPath() <= retained.count(), chain.toString());
        assertNotNull(chain.rootType(), chain.toString());
        assertTrue(chain.holders().stream().anyMatch(h -> h.array() && h.field() == null), chain.toString());
        assertTrue(chain.holders().stream().anyMatch(h -> "elementData".equals(h.field())
            && h.type().equals("java.util.ArrayList") && h.mod() == null), chain.toString());
        assertTrue(chain.holders().stream().anyMatch(h -> h.isStatic() && "CACHE".equals(h.field())
            && h.type().endsWith("JfrTimelineTest$Leak")), chain.toString());
    }

    @Test
    void parsesConnectionEvents(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("recording.jfr");
        try (Recording recording = new Recording()) {
            recording.enable(HyboxConnectionEvent.class).withoutStackTrace();
            recording.start();
            HyboxConnectionEvent connect = new HyboxConnectionEvent();
            connect.player = "Wrex";
            connect.phase = "connect";
            connect.commit();
            Thread.sleep(60);
            HyboxConnectionEvent join = new HyboxConnectionEvent();
            join.player = "Shepard";
            join.phase = "join";
            join.detail = "arena10 · joined in 2.1s";
            join.commit();
            Thread.sleep(60);
            HyboxConnectionEvent leave = new HyboxConnectionEvent();
            leave.player = "Wrex";
            leave.phase = "setup disconnect";
            leave.detail = "noWorldAvailable";
            leave.commit();
            Thread.sleep(60);
            recording.dump(target);
        }

        JfrTimeline timeline = JfrTimeline.parse(target);

        assertNotNull(timeline);
        assertEquals(3, timeline.connectionEvents().size());
        JfrTimeline.ConnEvent first = timeline.connectionEvents().get(0);
        assertEquals("Wrex", first.player());
        assertEquals("connect", first.phase());
        JfrTimeline.ConnEvent last = timeline.connectionEvents().get(2);
        assertEquals("setup disconnect", last.phase());
        assertEquals("noWorldAvailable", last.detail());
        assertEquals(1.0, sum(timeline.connectsSeries()), 0.001);
        assertEquals(1.0, sum(timeline.joinsSeries()), 0.001);
        assertNull(timeline.leavesSeries());
    }

    private static double sum(double[] series) {
        assertNotNull(series);
        double total = 0;
        for (double v : series) {
            total += v;
        }
        return total;
    }

    @Test
    void parsesPluginMetricSeries(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("recording.jfr");
        try (Recording recording = new Recording()) {
            recording.enable(HyboxPluginMetricEvent.class).withoutStackTrace();
            recording.start();
            commitMetric("ChunkUnloader unloaded", 120, true, 120);
            commitMetric("AiTickThrottler frozen", 42, false, 0);
            Thread.sleep(120);
            commitMetric("ChunkUnloader unloaded", 60, true, 180);
            commitMetric("AiTickThrottler frozen", 50, false, 0);
            recording.dump(target);
        }

        JfrTimeline timeline = JfrTimeline.parse(target);

        assertNotNull(timeline);
        assertEquals(2, timeline.pluginMetrics().size());

        JfrTimeline.PluginMetric count = metric(timeline, "ChunkUnloader unloaded");
        assertEquals("count", count.kind());
        assertEquals(JfrTimeline.BUCKETS, count.series().length);
        assertEquals(2, count.events());
        assertEquals(60.0, count.total(), 0.001);
        assertEquals(180.0, count.cumulative(), 0.001);
        assertEquals(60.0, java.util.Arrays.stream(count.amounts()).sum(), 0.001);
        double rate = count.series()[JfrTimeline.BUCKETS / 2];
        assertTrue(rate > 0);
        for (double v : count.series()) {
            assertTrue(Double.isNaN(v) || Math.abs(v - rate) < 1e-6 * rate);
        }

        JfrTimeline.PluginMetric gauge = metric(timeline, "AiTickThrottler frozen");
        assertEquals("gauge", gauge.kind());
        assertEquals(42.0, gauge.series()[0], 0.001);
        assertEquals(42.0, gauge.series()[JfrTimeline.BUCKETS / 2], 0.001);
        assertEquals(50.0, gauge.series()[gauge.series().length - 1], 0.001);
        assertTrue(Double.isNaN(gauge.cumulative()));

        assertTrue(timeline.retained().isEmpty());
    }

    @Test
    void keepsSingleEventAndZeroPluginMetrics(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("recording.jfr");
        try (Recording recording = new Recording()) {
            recording.enable(HyboxPluginMetricEvent.class).withoutStackTrace();
            recording.start();
            commitMetric("radius", 8, false, 0);
            commitMetric("boot only", 9063, true, 9063);
            commitMetric("never reached", 0, true, 0);
            Thread.sleep(120);
            commitMetric("radius", 8, false, 0);
            recording.dump(target);
        }

        JfrTimeline timeline = JfrTimeline.parse(target);

        assertNotNull(timeline);
        assertEquals(java.util.List.of("boot only", "never reached", "radius"),
            timeline.pluginMetrics().stream().map(JfrTimeline.PluginMetric::name).toList());
        JfrTimeline.PluginMetric boot = metric(timeline, "boot only");
        assertEquals(1, boot.events());
        assertEquals(9063.0, boot.total(), 0.001);
        assertEquals("single", boot.uncharted());
        assertNull(boot.series());
        JfrTimeline.PluginMetric never = metric(timeline, "never reached");
        assertEquals("zero", never.uncharted());
        assertNull(never.series());
        assertEquals(0.0, never.total(), 0.001);
        assertNull(metric(timeline, "radius").uncharted());
    }

    @Name("testmod.Metric")
    @Category({"Mods", "TestMod"})
    @StackTrace(false)
    static final class ModMetric extends Event {
        String name;
        String kind;
        double value;
    }

    @Name("testmod.Metric")
    @Category({"Mods", "TestMod"})
    @StackTrace(false)
    static final class ModMetricOtherLoader extends Event {
        String name;
        String kind;
        double value;
    }

    @Name("testmod.Action")
    @Category({"Mods", "TestMod"})
    @StackTrace(false)
    static final class ModAction extends Event {
        String category;
        String message;
    }

    @Name("testmod.Setting")
    @Category({"Mods", "TestMod"})
    @StackTrace(false)
    static final class ModSetting extends Event {
        String name;
        String value;
    }

    @Name("other.Metric")
    @Category({"Tools", "TestMod"})
    @StackTrace(false)
    static final class OtherCategoryMetric extends Event {
        String name;
        String kind;
        double value;
    }

    @Name("io.github.xytronix.hybox.TestModMetric")
    @Category({"Mods", "Hybox"})
    @StackTrace(false)
    static final class HyboxNamedMetric extends Event {
        String name;
        String kind;
        double value;
    }

    @Test
    void readsModEventsByCategoryAndFieldNames(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("recording.jfr");
        try (Recording recording = new Recording()) {
            recording.start();
            ModMetric froze = new ModMetric();
            froze.name = "froze";
            froze.kind = "counter";
            froze.value = 4;
            froze.commit();
            ModMetric radius = new ModMetric();
            radius.name = "radius";
            radius.kind = "gauge";
            radius.value = 8;
            radius.commit();
            modSetting("Version", "1.0");
            modSetting("Mode", "fast");
            ModAction action = new ModAction();
            action.category = "Watchdog";
            action.message = "restarted world 'default'";
            action.commit();
            OtherCategoryMetric other = new OtherCategoryMetric();
            other.name = "ignored";
            other.kind = "gauge";
            other.value = 1;
            other.commit();
            HyboxNamedMetric own = new HyboxNamedMetric();
            own.name = "ignored";
            own.kind = "gauge";
            own.value = 1;
            own.commit();
            Thread.sleep(120);
            ModMetricOtherLoader later = new ModMetricOtherLoader();
            later.name = "froze";
            later.kind = "counter";
            later.value = 6;
            later.commit();
            ModMetric lower = new ModMetric();
            lower.name = "radius";
            lower.kind = "gauge";
            lower.value = 5;
            lower.commit();
            modSetting("Mode", "slow");
            recording.dump(target);
        }

        JfrTimeline timeline = JfrTimeline.parse(target);

        assertNotNull(timeline);
        assertEquals(List.of("TestMod/froze", "TestMod/radius"),
            timeline.pluginMetrics().stream().map(JfrTimeline.PluginMetric::name).toList());
        JfrTimeline.PluginMetric counter = metric(timeline, "TestMod/froze");
        assertEquals("count", counter.kind());
        assertEquals("TestMod", counter.mod());
        assertEquals(2, counter.events());
        assertEquals(6.0, counter.total(), 0.001);
        assertEquals(10.0, counter.cumulative(), 0.001);
        assertNotNull(counter.series());
        JfrTimeline.PluginMetric gauge = metric(timeline, "TestMod/radius");
        assertEquals("gauge", gauge.kind());
        assertEquals(5.0, gauge.total(), 0.001);

        assertEquals(1, timeline.modActions().size());
        JfrTimeline.ModAction recorded = timeline.modActions().getFirst();
        assertEquals("TestMod", recorded.mod());
        assertEquals("Watchdog", recorded.category());
        assertEquals("restarted world 'default'", recorded.message());

        assertEquals(1, timeline.modSettings().size());
        JfrTimeline.ModSettings settings = timeline.modSettings().getFirst();
        assertEquals("TestMod", settings.mod());
        assertEquals(Map.of("Version", "1.0", "Mode", "slow"), settings.values());
        assertEquals(List.of("Version", "Mode"), List.copyOf(settings.values().keySet()));
        assertTrue(timeline.pluginEvents().isEmpty());
    }

    private static void modSetting(String name, String value) {
        ModSetting setting = new ModSetting();
        setting.name = name;
        setting.value = value;
        setting.commit();
    }

    @Test
    void convertsCounterDeltasToRateOverIrregularIntervals() {
        java.util.List<JfrTimelineParser.Pt> deltas = java.util.List.of(
            new JfrTimelineParser.Pt(1000, 10),
            new JfrTimelineParser.Pt(3000, 40),
            new JfrTimelineParser.Pt(3500, 2),
            new JfrTimelineParser.Pt(3500, 3));

        double[] rate = JfrTimelineParser.counterRate(deltas, 0, 9999, 10);

        double[] expected = {Double.NaN, 20, 20, 10, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN};
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], rate[i], 1e-9, "bucket " + i);
        }
    }

    @Test
    void lonePluginCounterDeltaHasNoRate() {
        assertNull(JfrTimelineParser.counterRate(
            java.util.List.of(new JfrTimelineParser.Pt(4500, 7), new JfrTimelineParser.Pt(4500, 2)), 0, 9999, 10));
    }

    @Test
    void parsesSystemTickSeries(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("recording.jfr");
        try (Recording recording = new Recording()) {
            recording.enable(HyboxSystemTickEvent.class).withoutStackTrace();
            recording.start();
            commitSystemTick("EntityTickingSystem", "default", 5);
            commitSystemTick("FluidSystems", "default", 1);
            Thread.sleep(120);
            commitSystemTick("EntityTickingSystem", "default", 9);
            recording.dump(target);
        }

        JfrTimeline timeline = JfrTimeline.parse(target);

        assertNotNull(timeline);
        assertEquals(1, timeline.tickSeries().size());
        JfrTimeline.SystemTick tick = timeline.tickSeries().get(0);
        assertEquals("EntityTickingSystem @ default", tick.name());
        assertEquals(JfrTimeline.BUCKETS, tick.series().length);
        assertEquals(5.0, tick.series()[0], 0.001);
        assertEquals(9.0, tick.series()[tick.series().length - 1], 0.001);
    }

    private static void commitSystemTick(String system, String world, double avgMs) {
        HyboxSystemTickEvent event = new HyboxSystemTickEvent();
        event.world = world;
        event.system = system;
        event.avgMs = avgMs;
        event.commit();
    }

    private static void commitMetric(String name, double value, boolean counter, double total) {
        HyboxPluginMetricEvent event = new HyboxPluginMetricEvent();
        event.name = name;
        event.value = value;
        event.counter = counter;
        event.total = total;
        event.commit();
    }

    private static JfrTimeline.PluginMetric metric(JfrTimeline timeline, String name) {
        return timeline.pluginMetrics().stream()
            .filter(m -> m.name().equals(name))
            .findFirst()
            .orElseThrow();
    }

    @Test
    void parsesDiskIoRateSeries(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("recording.jfr");
        try (Recording recording = new Recording()) {
            recording.enable(io.github.xytronix.hybox.core.jfr.HyboxDiskEvent.class).withoutStackTrace();
            recording.start();
            long[] reads = {0L, 12_000_000L, 24_000_000L};
            long[] writes = {0L, 6_000_000L, 12_000_000L};
            for (int i = 0; i < 3; i++) {
                io.github.xytronix.hybox.core.jfr.HyboxDiskEvent event =
                    new io.github.xytronix.hybox.core.jfr.HyboxDiskEvent();
                event.freeBytes = 50_000_000_000L;
                event.totalBytes = 100_000_000_000L;
                event.readBytes = reads[i];
                event.writeBytes = writes[i];
                event.commit();
                Thread.sleep(60);
            }
            recording.dump(target);
        }

        JfrTimeline timeline = JfrTimeline.parse(target);

        assertNotNull(timeline);
        double[] read = timeline.diskReadSeries();
        double[] write = timeline.diskWriteSeries();
        assertNotNull(read);
        assertNotNull(write);
        assertEquals(JfrTimeline.BUCKETS, read.length);
        assertEquals(0.0, read[0], 0.001);

        double bucketSeconds = (timeline.end().toEpochMilli() - timeline.start().toEpochMilli())
            / 1000.0 / JfrTimeline.BUCKETS;
        double readTotalMb = java.util.Arrays.stream(read).sum() * bucketSeconds;
        double writeTotalMb = java.util.Arrays.stream(write).sum() * bucketSeconds;
        assertEquals(24.0, readTotalMb, 0.1);
        assertEquals(12.0, writeTotalMb, 0.1);
        assertTrue(java.util.Arrays.stream(read).allMatch(v -> v >= 0));

        assertTrue(timeline.slowIo().isEmpty());
    }

    @Test
    void returnsNullForUnreadableFile(@TempDir Path tempDir) throws Exception {
        Path bogus = tempDir.resolve("bogus.jfr");
        Files.write(bogus, new byte[] {1, 2, 3});

        assertNull(JfrTimeline.parse(bogus));
    }

    @Test
    void flamePrunesLowWeightSubtrees() {
        JfrTimelineParser.FlameNode root = new JfrTimelineParser.FlameNode("all");
        for (int i = 0; i < 7; i++) {
            JfrTimelineParser.flameInsert(root, java.util.List.of("Main.run", "Hot.path"));
        }
        for (int i = 0; i < 2; i++) {
            JfrTimelineParser.flameInsert(root, java.util.List.of("Main.run", "Cold.path"));
        }

        JfrTimeline.Flame flame = JfrTimelineParser.flamePrune(root, 9);

        assertNotNull(flame);
        assertEquals("all", flame.name());
        assertEquals(9, flame.samples());
        assertEquals(1, flame.children().size());
        JfrTimeline.Flame main = flame.children().get(0);
        assertEquals("Main.run", main.name());
        assertEquals(9, main.samples());
        assertEquals(1, main.children().size());
        assertEquals("Hot.path", main.children().get(0).name());
        assertEquals(7, main.children().get(0).samples());
    }

    @Test
    void flameNodeCapDoublesThresholdUntilItFits() {
        JfrTimelineParser.FlameNode root = new JfrTimelineParser.FlameNode("all");
        for (int p = 0; p < 100; p++) {
            java.util.List<String> path = new java.util.ArrayList<>();
            for (int d = 0; d < 64; d++) {
                path.add("P" + p + ".f" + d);
            }
            for (int i = 0; i < 100; i++) {
                JfrTimelineParser.flameInsert(root, path);
            }
        }

        JfrTimeline.Flame flame = JfrTimelineParser.flamePrune(root, root.samples);

        assertNotNull(flame);
        assertEquals(10000, flame.samples());
        assertTrue(countNodes(flame) <= 6000);
    }

    @Test
    void flamePruneIsNullWithoutSamples() {
        assertNull(JfrTimelineParser.flamePrune(new JfrTimelineParser.FlameNode("all"), 0));
    }

    @Test
    void bucketCountScalesWithWindowAndClamps() {
        assertEquals(240, JfrTimelineParser.bucketCount(0, 30 * 60 * 1000L));
        assertEquals(720, JfrTimelineParser.bucketCount(0, 2 * 60 * 60 * 1000L));
        assertEquals(960, JfrTimelineParser.bucketCount(0, 8 * 60 * 60 * 1000L));
        assertEquals(960, JfrTimelineParser.bucketCount(0, 7 * 24 * 60 * 60 * 1000L));
        assertEquals(240, JfrTimelineParser.bucketCount(5, 5));
        assertEquals(240, JfrTimelineParser.bucketCount(10, 5));
    }

    @Test
    void normalizesThreadNames() {
        assertEquals("ServerWorkerGroup", JfrTimelineParser.normalizeThreadName("ServerWorkerGroup-3"));
        assertEquals("pool-2-thread", JfrTimelineParser.normalizeThreadName("pool-2-thread-7"));
        assertEquals("Thread", JfrTimelineParser.normalizeThreadName("Thread#12"));
        assertEquals("WorldThread - Farmwelt", JfrTimelineParser.normalizeThreadName("WorldThread - Farmwelt"));
        assertEquals("WorldThread - world-2", JfrTimelineParser.normalizeThreadName("WorldThread - world-2"));
        assertEquals("ChunkLighting - instance-a-0b7fd9ff-9045-524f-5ffa-3225b9f7c3c0",
            JfrTimelineParser.normalizeThreadName("ChunkLighting - instance-a-0b7fd9ff-9045-524f-5ffa-3225b9f7c3c0"));
        assertEquals("main", JfrTimelineParser.normalizeThreadName("main"));
        assertEquals("Worker", JfrTimelineParser.normalizeThreadName("Worker"));
        assertEquals("io-worker", JfrTimelineParser.normalizeThreadName("io-worker-0"));
        assertEquals("quiche-conn-*",
            JfrTimelineParser.normalizeThreadName("quiche-conn-0b7fd9ff9045524f5ffa3225b9f7c3c000000000"));
        assertEquals("cache-deadbeefcafe", JfrTimelineParser.normalizeThreadName("cache-deadbeefcafe"));
        assertEquals("hash-0123456789a", JfrTimelineParser.normalizeThreadName("hash-0123456789a"));
        assertNull(JfrTimelineParser.idGroup("Thread-12"));
    }

    @Test
    void longLivedObjectsNameTheirAllocationSiteByTheSelfRuleWithItsOwner(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("recording.jfr");
        List<Object[]> kept = new java.util.ArrayList<>();
        try (Recording recording = new Recording(jdk.jfr.Configuration.getConfiguration("default"))) {
            recording.enable("jdk.OldObjectSample").withStackTrace().with("cutoff", "0 ns");
            recording.start();
            for (int i = 0; i < 4000; i++) {
                kept.add(grow(i));
            }
            youngCollection();
            recording.stop();
            recording.dump(target);
        }
        JfrTimeline timeline = JfrTimeline.parse(target);
        assertNotNull(timeline);
        assertEquals(4000, kept.size());
        JfrTimeline.Retained arrays = timeline.retained().stream()
            .filter(r -> r.className().equals("java.lang.Object[]")).findFirst().orElseThrow();
        assertEquals(JfrTimelineTest.class.getName() + ".grow", arrays.allocSite());
        assertEquals("io.github.xytronix", arrays.allocMod());
    }

    private static Object[] grow(int i) {
        return java.util.Arrays.copyOf(KEEP_SHAPE, 4096 + i % 7);
    }

    private static final Object[] KEEP_SHAPE = new Object[1];

    private static volatile Object churn;

    private static void youngCollection() {
        long before = collections();
        for (int i = 0; i < 1 << 16 && collections() == before; i++) {
            churn = new byte[64 * 1024];
        }
    }

    private static long collections() {
        long count = 0;
        for (java.lang.management.GarbageCollectorMXBean bean
            : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
            count += Math.max(0, bean.getCollectionCount());
        }
        return count;
    }

    private static int countNodes(JfrTimeline.Flame node) {
        int n = 1;
        for (JfrTimeline.Flame child : node.children()) {
            n += countNodes(child);
        }
        return n;
    }
}
