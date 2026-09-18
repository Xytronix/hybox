package io.github.xytronix.hybox.hytale;

import static org.junit.jupiter.api.Assertions.*;

import com.hypixel.hytale.event.EventRegistry;
import com.hypixel.hytale.server.core.command.system.CommandRegistry;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import io.github.xytronix.hybox.core.jfr.JfrController;
import io.github.xytronix.hybox.core.capture.CaptureResult;
import io.github.xytronix.hybox.core.config.HyboxConfig;
import io.github.xytronix.hybox.core.incident.IncidentId;
import io.github.xytronix.hybox.core.trigger.TriggerEvent;
import io.github.xytronix.hybox.core.trigger.TriggerKind;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import jdk.jfr.FlightRecorder;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import sun.misc.Unsafe;

class HyboxLifecycleTest {
    @TempDir Path dir;

    @AfterEach
    void cleanup() {
        HyboxApi.shutdown();
    }

    @Test
    void failedStartupClosesRecordingAndPropagatesToFramework() throws Exception {
        String recordingName = "hybox-startup-" + dir.getFileName();
        Files.writeString(dir.resolve("hybox.json"),
            "{\"Version\":1,\"Jfr\":{\"RecordingName\":\"" + recordingName
                + "\",\"SnapshotInterval\":\"PT9223372036854776S\"}}");
        HyboxPlugin plugin = plugin();
        Path rolling = dir.resolve("live/rolling.jfr");
        Files.createDirectories(rolling.getParent());
        Files.writeString(rolling, "previous-session");
        try {
            IllegalStateException failure = assertThrows(IllegalStateException.class, plugin::start);
            assertInstanceOf(ArithmeticException.class, failure.getCause());
            assertTrue(FlightRecorder.getFlightRecorder().getRecordings().stream()
                .noneMatch(recording -> recordingName.equals(recording.getName())));
            HyboxApi.registerConfig("probe", dir.resolve("probe.json"));
            assertTrue(HyboxApi.registeredConfigPaths().isEmpty());
            assertEquals("previous-session", Files.readString(rolling));
            assertDoesNotThrow(plugin::shutdown);
        } finally {
            FlightRecorder.getFlightRecorder().getRecordings().stream()
                .filter(recording -> recordingName.equals(recording.getName()))
                .forEach(jdk.jfr.Recording::close);
        }
    }

    @Test
    void partialShutdownContinuesAfterListenerCleanupFailureAndCannotRestart() throws Exception {
        HyboxPlugin plugin = plugin();
        AtomicInteger eventCleanup = new AtomicInteger();
        set(PluginBase.class, plugin, "eventRegistry", new EventRegistry(
            new ArrayList<>(List.of(force -> eventCleanup.incrementAndGet())), () -> true, "test", null));
        set(PluginBase.class, plugin, "commandRegistry", new CommandRegistry(
            new ArrayList<>(List.of(force -> { throw new IllegalStateException("cleanup failure"); })),
            () -> true, "test", plugin));
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        JfrController recorder = new JfrController(Duration.ofMinutes(1), 1024 * 1024, "hybox-close-test");
        recorder.start();
        var constructor = HyboxRuntime.class.getDeclaredConstructor(HyboxPlugin.class, Clock.class,
            System.Logger.class, Path.class, ScheduledExecutorService.class, ExecutorService.class,
            JfrController.class);
        constructor.setAccessible(true);
        HyboxRuntime runtime = (HyboxRuntime) constructor.newInstance(plugin, Clock.systemUTC(),
            System.getLogger("lifecycle-test"), dir, scheduler, worker, recorder);
        try {
            assertDoesNotThrow(runtime::close);
            assertTrue(scheduler.isShutdown());
            assertTrue(worker.isShutdown());
            assertEquals(1, eventCleanup.get());
            assertEquals(CaptureResult.Status.STOPPED, runtime.captureManual().join().status());
            assertTrue(runtime.reload().contains("not running"));
            assertNotNull(runtime.startProfileSession(1, false));
            assertDoesNotThrow(runtime::close);
            assertEquals(1, eventCleanup.get());
        } finally {
            scheduler.shutdownNow();
            worker.shutdownNow();
            recorder.close();
        }
    }

    @Test
    void profileFinishingAfterShutdownDoesNotRestartRecorder() throws Exception {
        String recordingName = "hybox-profile-" + dir.getFileName();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        JfrController recorder = new JfrController(Duration.ofMinutes(1), 1024 * 1024, recordingName);
        CompletableFuture<CaptureResult> completedCapture = new CompletableFuture<>();
        ProfileSessionController profiles = new ProfileSessionController(Clock.systemUTC(),
            System.getLogger("lifecycle-test"), scheduler, recorder,
            event -> CaptureResult.captured(new IncidentId("lead-up")), event -> completedCapture, result -> {});
        recorder.start();
        try {
            assertNull(profiles.start(1, true));
            finishProfile(profiles);
            assertFalse(profiles.active());
            profiles.close();
            recorder.close();
            completedCapture.complete(CaptureResult.captured(new IncidentId("profile-result")));
            assertTrue(FlightRecorder.getFlightRecorder().getRecordings().stream()
                .noneMatch(recording -> recordingName.equals(recording.getName())));
        } finally {
            profiles.close();
            scheduler.shutdownNow();
            recorder.close();
        }
    }

    @Test
    void snapshotWriterCannotReplacePreviousRecordingBeforeRecoveryCompletes() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        JfrController recorder = new JfrController(Duration.ofMinutes(1), 1024 * 1024, "hybox-snapshot-test");
        recorder.start();
        var constructor = HyboxRuntime.class.getDeclaredConstructor(HyboxPlugin.class, Clock.class,
            System.Logger.class, Path.class, ScheduledExecutorService.class, ExecutorService.class,
            JfrController.class);
        constructor.setAccessible(true);
        HyboxRuntime runtime = (HyboxRuntime) constructor.newInstance(plugin(), Clock.systemUTC(),
            System.getLogger("lifecycle-test"), dir, scheduler, worker, recorder);
        Field healthField = HyboxRuntime.class.getDeclaredField("healthScheduler");
        healthField.setAccessible(true);
        HytaleHealthScheduler health = (HytaleHealthScheduler) healthField.get(runtime);
        Path rolling = dir.resolve("live/rolling.jfr");
        Path recovered = dir.resolve("recovered.jfr");
        Files.createDirectories(rolling.getParent());
        Files.writeString(rolling, "previous-session");
        CompletableFuture<Void> recovery = new CompletableFuture<>();
        try {
            health.startSnapshotWriter(1, recovery);
            scheduler.schedule(() -> {}, 1100, TimeUnit.MILLISECONDS).get(5, TimeUnit.SECONDS);
            worker.submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertEquals("previous-session", Files.readString(rolling));

            Files.move(rolling, recovered);
            recovery.complete(null);
            scheduler.schedule(() -> {}, 1100, TimeUnit.MILLISECONDS).get(5, TimeUnit.SECONDS);
            worker.submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertEquals("previous-session", Files.readString(recovered));
            assertTrue(RecordingFile.readAllEvents(rolling).stream().anyMatch(event ->
                event.getEventType().getName().equals("io.github.xytronix.hybox.marker")
                    && event.getString("message").equals("hybox dump: rolling.jfr.tmp")));
        } finally {
            runtime.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shutdownPreservesUnrecoveredSnapshotEvenDuringCapture(boolean capturePending) throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        JfrController recorder = new JfrController(Duration.ofMinutes(1), 1024 * 1024, "hybox-preserve-test");
        recorder.start();
        var constructor = HyboxRuntime.class.getDeclaredConstructor(HyboxPlugin.class, Clock.class,
            System.Logger.class, Path.class, ScheduledExecutorService.class, ExecutorService.class,
            JfrController.class);
        constructor.setAccessible(true);
        HyboxRuntime runtime = (HyboxRuntime) constructor.newInstance(plugin(), Clock.systemUTC(),
            System.getLogger("lifecycle-test"), dir, scheduler, worker, recorder);
        set(HyboxRuntime.class, runtime, "started", true);
        set(HyboxRuntime.class, runtime, "capturePending", new AtomicBoolean(capturePending));
        Field healthField = HyboxRuntime.class.getDeclaredField("healthScheduler");
        healthField.setAccessible(true);
        HytaleHealthScheduler health = (HytaleHealthScheduler) healthField.get(runtime);
        Path rolling = dir.resolve("live/rolling.jfr");
        Files.createDirectories(rolling.getParent());
        Files.writeString(rolling, "previous-session");
        try {
            health.startSnapshotWriter(1, CompletableFuture.completedFuture(null));
            runtime.close();
            assertEquals("previous-session", Files.readString(rolling));
        } finally {
            runtime.close();
        }
    }

    @Test
    void shutdownLetsInFlightRecordingDumpFinishBeforeClosingRecorder() throws Exception {
        CountDownLatch dumping = new CountDownLatch(1);
        CountDownLatch draining = new CountDownLatch(1);
        ExecutorService worker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>()) {
            @Override
            public void shutdown() {
                super.shutdown();
                draining.countDown();
            }
        };
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        JfrController recorder = new JfrController(Duration.ofMinutes(1), 1024 * 1024, "hybox-drain-test");
        recorder.start();
        var constructor = HyboxRuntime.class.getDeclaredConstructor(HyboxPlugin.class, Clock.class,
            System.Logger.class, Path.class, ScheduledExecutorService.class, ExecutorService.class,
            JfrController.class);
        constructor.setAccessible(true);
        HyboxRuntime runtime = (HyboxRuntime) constructor.newInstance(plugin(), Clock.systemUTC(),
            System.getLogger("lifecycle-test"), dir, scheduler, worker, recorder);
        Path dump = dir.resolve("completed.jfr");
        try {
            var capture = worker.submit(() -> {
                dumping.countDown();
                if (!draining.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Shutdown did not begin draining");
                }
                recorder.dump(dump);
                return dump;
            });
            assertTrue(dumping.await(5, TimeUnit.SECONDS));
            runtime.close();
            assertEquals(dump, capture.get(5, TimeUnit.SECONDS));
            assertTrue(RecordingFile.readAllEvents(dump).stream().anyMatch(event ->
                event.getEventType().getName().equals("io.github.xytronix.hybox.marker")
                    && event.getString("message").equals("hybox dump: completed.jfr")));
        } finally {
            draining.countDown();
            runtime.close();
            worker.shutdownNow();
            scheduler.shutdownNow();
        }
    }

    @Test
    void captureBurstAdmitsOneAndHealthDoesNotWaitForWorkerOrRecorder() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        ThreadPoolExecutor worker = (ThreadPoolExecutor) HyboxRuntime.newWorker();
        JfrController recorder = new JfrController(Duration.ofMinutes(1), 1024 * 1024, "hybox-admission-test");
        recorder.start();
        HyboxRuntime runtime = runtime(scheduler, worker, recorder);
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            worker.execute(() -> {
                synchronized (recorder) {
                    blocked.countDown();
                    await(release);
                }
            });
            assertTrue(blocked.await(5, TimeUnit.SECONDS));
            CompletableFuture<CaptureResult> admitted = runtime.captureManual();
            for (int i = 0; i < 200; i++) {
                assertEquals(CaptureResult.Status.BUSY, runtime.captureManual().get(1, TimeUnit.SECONDS).status());
                runtime.scheduleCapture(new TriggerEvent(TriggerKind.MANUAL, "burst", Clock.systemUTC().instant(), Map.of()));
            }
            assertEquals(1, worker.getQueue().size());
            var health = CompletableFuture.supplyAsync(runtime::health).get(1, TimeUnit.SECONDS);
            assertTrue(health.running());
            assertTrue(health.captureBusy());
            assertEquals(200, health.busy());
            assertEquals(200, health.dropped());
            assertEquals(CaptureResult.Status.BUSY, health.lastOutcome().status());
            assertTrue(runtime.reload().contains("busy"));
            assertNotNull(runtime.startProfileSession(1, false));
            release.countDown();
            assertEquals(CaptureResult.Status.DISABLED, admitted.get(5, TimeUnit.SECONDS).status());
            assertFalse(runtime.health().captureBusy());
            assertEquals(CaptureResult.Status.DISABLED, runtime.health().lastOutcome().status());
            assertTrue(runtime.reload().startsWith("Reloaded."));
        } finally {
            release.countDown();
            runtime.close();
        }
        assertFalse(runtime.health().running());
        assertEquals(CaptureResult.Status.STOPPED, runtime.captureManual().join().status());
    }

    @Test
    void fullWorkerRejectsWithoutStrandingAdmissionOrPeriodicSnapshot() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        ThreadPoolExecutor worker = (ThreadPoolExecutor) HyboxRuntime.newWorker();
        JfrController recorder = new JfrController(Duration.ofMinutes(1), 1024 * 1024, "hybox-rejection-test");
        recorder.start();
        HyboxRuntime runtime = runtime(scheduler, worker, recorder);
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch drained = new CountDownLatch(16);
        Field healthField = HyboxRuntime.class.getDeclaredField("healthScheduler");
        healthField.setAccessible(true);
        HytaleHealthScheduler health = (HytaleHealthScheduler) healthField.get(runtime);
        var snapshot = HytaleHealthScheduler.class.getDeclaredMethod("scheduleSnapshot");
        snapshot.setAccessible(true);
        try {
            worker.execute(() -> {
                blocked.countDown();
                await(release);
            });
            assertTrue(blocked.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 16; i++) {
                worker.execute(drained::countDown);
            }
            assertEquals(CaptureResult.Status.BUSY, runtime.captureManual().join().status());
            assertFalse(runtime.health().captureBusy());
            snapshot.invoke(health);
            snapshot.invoke(health);
            assertEquals(3, runtime.health().rejected());
            assertEquals(16, worker.getQueue().size());
            release.countDown();
            assertTrue(drained.await(5, TimeUnit.SECONDS));
            snapshot.invoke(health);
            worker.submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertNotNull(runtime.health().lastSnapshot());
            assertNull(runtime.health().snapshotError());
            assertEquals(CaptureResult.Status.DISABLED, runtime.captureManual().get(5, TimeUnit.SECONDS).status());
        } finally {
            release.countDown();
            runtime.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void profileDeadlineRestoresPresetEvenWhenFinalCaptureCannotRun(boolean fullQueue) throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        ThreadPoolExecutor worker = (ThreadPoolExecutor) HyboxRuntime.newWorker();
        String recordingName = "hybox-profile-bound-" + dir.getFileName();
        JfrController recorder = new JfrController(Duration.ofMinutes(1), 1024 * 1024, recordingName);
        recorder.start();
        var original = FlightRecorder.getFlightRecorder().getRecordings().stream()
            .filter(recording -> recordingName.equals(recording.getName())).findFirst().orElseThrow();
        var settings = original.getSettings();
        HyboxRuntime runtime = runtime(scheduler, worker, recorder);
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            assertNull(runtime.startProfileSession(1, true));
            worker.execute(() -> {
                blocked.countDown();
                await(release);
            });
            assertTrue(blocked.await(5, TimeUnit.SECONDS));
            if (fullQueue) {
                for (int i = 0; i < 16; i++) {
                    worker.execute(() -> {});
                }
            }
            Field field = HyboxRuntime.class.getDeclaredField("profileSessions");
            field.setAccessible(true);
            ProfileSessionController profiles = (ProfileSessionController) field.get(runtime);
            finishProfile(profiles);
            assertFalse(profiles.active());
            assertEquals(settings, original.getSettings());
            assertTrue(recorder.isRunning());
            if (fullQueue) {
                assertEquals(CaptureResult.Status.FAILED, runtime.health().lastOutcome().status());
                assertNotNull(runtime.health().captureError());
                assertEquals(1, runtime.health().rejected());
                assertFalse(runtime.health().captureBusy());
            } else {
                assertEquals(1, worker.getQueue().size());
                assertTrue(runtime.health().captureBusy());
                assertNotNull(runtime.startProfileSession(1, false));
                assertEquals(settings, original.getSettings());
            }
            release.countDown();
        } finally {
            release.countDown();
            runtime.close();
        }
    }

    @Test
    void rejectedProfileStartRestoresAndDoesNotRemainActive() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        JfrController recorder = new JfrController(Duration.ofMinutes(1), 1024 * 1024, "hybox-profile-stopped");
        recorder.start();
        ProfileSessionController profiles = new ProfileSessionController(Clock.systemUTC(),
            System.getLogger("lifecycle-test"), scheduler, recorder,
            event -> CaptureResult.of(CaptureResult.Status.DISABLED),
            event -> CompletableFuture.completedFuture(CaptureResult.of(CaptureResult.Status.DISABLED)), result -> {});
        try {
            assertNotNull(profiles.start(1, false));
            assertFalse(profiles.active());
            scheduler.shutdownNow();
            assertNotNull(profiles.start(1, true));
            assertFalse(profiles.active());
            assertTrue(recorder.isRunning());
        } finally {
            profiles.close();
            scheduler.shutdownNow();
            recorder.close();
        }
    }

    private static void finishProfile(ProfileSessionController profiles) throws Exception {
        Field generation = ProfileSessionController.class.getDeclaredField("generation");
        generation.setAccessible(true);
        var finish = ProfileSessionController.class.getDeclaredMethod("finish", long.class);
        finish.setAccessible(true);
        finish.invoke(profiles, generation.getLong(profiles));
    }

    private HyboxRuntime runtime(ScheduledExecutorService scheduler, ExecutorService worker,
                                 JfrController recorder) throws Exception {
        Files.writeString(dir.resolve("hybox.json"), "{\"Version\":1,\"Capture\":{\"Enabled\":false}}");
        var constructor = HyboxRuntime.class.getDeclaredConstructor(HyboxPlugin.class, Clock.class,
            System.Logger.class, Path.class, ScheduledExecutorService.class, ExecutorService.class,
            JfrController.class);
        constructor.setAccessible(true);
        HyboxRuntime runtime = (HyboxRuntime) constructor.newInstance(plugin(), Clock.systemUTC(),
            System.getLogger("lifecycle-test"), dir, scheduler, worker, recorder);
        var build = HyboxRuntime.class.getDeclaredMethod("buildEngine", HyboxConfig.class);
        build.setAccessible(true);
        set(HyboxRuntime.class, runtime, "engine",
            build.invoke(runtime, HytaleHyboxConfig.loadOrCreate(dir, System.getLogger("lifecycle-test"))));
        return runtime;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for release");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }

    private HyboxPlugin plugin() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        HyboxPlugin plugin = (HyboxPlugin) ((Unsafe) field.get(null)).allocateInstance(HyboxPlugin.class);
        set(PluginBase.class, plugin, "dataDirectory", dir);
        set(HyboxPlugin.class, plugin, "logger", System.getLogger("lifecycle-test"));
        set(PluginBase.class, plugin, "eventRegistry", new EventRegistry(new ArrayList<>(), () -> true, "test", null));
        set(PluginBase.class, plugin, "commandRegistry", new CommandRegistry(new ArrayList<>(), () -> true, "test", plugin));
        return plugin;
    }

    private static void set(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
