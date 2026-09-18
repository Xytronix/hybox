package io.github.xytronix.hybox.hytale;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.xytronix.hybox.core.bundle.BundleAttachment;
import io.github.xytronix.hybox.core.bundle.BundleExtrasRegistry;
import io.github.xytronix.hybox.core.capture.BundleExtrasProvider;
import io.github.xytronix.hybox.core.incident.IncidentReport;
import io.github.xytronix.hybox.core.trigger.TriggerEvent;
import sun.misc.Unsafe;

class HyboxApiPrivacyTest {
    @TempDir Path dir;

    private CallbackExecutor callbacks;
    @AfterEach
    void cleanup() {
        HyboxApi.shutdown();
        if (callbacks != null) {
            callbacks.close();
        }
    }

    @Test
    void livePrivacySwitchStopsExistingExtrasProviders() throws Exception {
        var runtime = runtime();
        var calls = new AtomicInteger();
        var registration = HyboxApi.registerExtras("third-party", provider(calls));
        assertEquals(1, runtime.extrasRegistry().extras(null, null).size());
        setPolicy(runtime, false, true);
        assertTrue(runtime.extrasRegistry().extras(null, null).isEmpty());
        assertTrue(runtime.extrasRegistry().historicalExtras().isEmpty());
        assertTrue(runtime.extrasRegistry().configExtras().isEmpty());
        assertEquals(1, calls.get());
        setPolicy(runtime, true, true);
        assertEquals(1, runtime.extrasRegistry().extras(null, null).size());
        registration.close();
        assertTrue(runtime.extrasRegistry().extras(null, null).isEmpty());
    }

    @Test
    void configPrivacySwitchStopsAlreadyRegisteredPaths() throws Exception {
        var runtime = runtime();
        var file = dir.resolve("plugin.json");
        HyboxApi.registerConfig("third-party", file);
        assertEquals(List.of(file), HyboxApi.registeredConfigPaths());
        setPolicy(runtime, true, false);
        assertTrue(HyboxApi.registeredConfigPaths().isEmpty());
        setPolicy(runtime, true, true);
        assertEquals(List.of(file), HyboxApi.registeredConfigPaths());
    }

    @Test
    void configPrivacySwitchAlsoGatesRecoveryProviderConfigs() throws Exception {
        var runtime = runtime();
        var calls = new AtomicInteger();
        HyboxApi.registerExtras("third-party", provider(calls));
        assertEquals(1, runtime.extrasRegistry().configExtras().size());
        setPolicy(runtime, true, false);
        assertTrue(runtime.extrasRegistry().configExtras().isEmpty());
        assertEquals(1, calls.get());
        assertEquals(1, runtime.extrasRegistry().historicalExtras().size());
    }

    @Test
    void staleRegistryCannotInvokeProvidersAfterApiShutdown() throws Exception {
        var runtime = runtime();
        var calls = new AtomicInteger();
        HyboxApi.registerExtras("third-party", provider(calls));
        HyboxApi.shutdown();
        assertTrue(runtime.extrasRegistry().extras(null, null).isEmpty());
        assertTrue(runtime.extrasRegistry().historicalExtras().isEmpty());
        assertTrue(runtime.extrasRegistry().configExtras().isEmpty());
        assertEquals(0, calls.get());
    }

    @Test
    void inFlightExtrasAreDiscardedOnUnregister() throws Exception {
        discardInFlight("unregister");
    }

    @Test
    void inFlightExtrasAreDiscardedOnPluginClear() throws Exception {
        discardInFlight("clear");
    }

    @Test
    void inFlightExtrasAreDiscardedOnShutdown() throws Exception {
        discardInFlight("shutdown");
    }

    @Test
    void inFlightExtrasAreDiscardedOnPrivacyChange() throws Exception {
        discardInFlight("privacy");
    }

    @Test
    void externalExtrasShareOneCollectionDeadline() throws Exception {
        var runtime = runtime();
        var calls = new AtomicInteger();
        for (int i = 0; i < 20; i++) {
            HyboxApi.registerExtras("plugin-" + i, (report, event) -> {
                calls.incrementAndGet();
                Thread.sleep(70);
                return List.of(new BundleAttachment("extras/test.txt", new byte[] {1}));
            });
        }
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> runtime.extrasRegistry().extras(null, null));
        assertTrue(calls.get() < 20);
    }

    @Test
    void throwingExtrasDoNotHideHealthyRecoveryAttachments() throws Exception {
        var runtime = runtime();
        HyboxApi.registerExtras("broken", new BundleExtrasProvider() {
            public List<BundleAttachment> extras(IncidentReport report, TriggerEvent event) {
                throw new IllegalStateException("broken");
            }
            public List<BundleAttachment> historicalExtras() {
                throw new IllegalStateException("broken");
            }
            public List<BundleAttachment> configExtras() {
                throw new IllegalStateException("broken");
            }
        });
        HyboxApi.registerExtras("healthy", provider(new AtomicInteger()));
        assertEquals(1, runtime.extrasRegistry().extras(null, null).size());
        assertEquals(1, runtime.extrasRegistry().historicalExtras().size());
        assertEquals(1, runtime.extrasRegistry().configExtras().size());
        assertEquals(3, callbacks.health().failures());
        assertEquals(0, callbacks.health().quarantined());
    }

    private void discardInFlight(String mutation) throws Exception {
        var runtime = runtime();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        var registration = HyboxApi.registerExtras("p", (report, event) -> {
            entered.countDown();
            try {
                while (release.getCount() != 0) {
                    try {
                        release.await();
                    } catch (InterruptedException ignored) {
                    }
                }
                return List.of(new BundleAttachment("extras/late.txt", new byte[] {1}));
            } finally {
                exited.countDown();
            }
        });
        try (var callers = Executors.newSingleThreadExecutor()) {
            var result = callers.submit(() -> runtime.extrasRegistry().extras(null, null));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            switch (mutation) {
                case "unregister" -> registration.close();
                case "clear" -> HyboxApi.clearPlugin("p");
                case "shutdown" -> HyboxApi.shutdown();
                case "privacy" -> setPolicy(runtime, false, true);
                default -> throw new AssertionError(mutation);
            }
            release.countDown();
            assertTrue(result.get(2, TimeUnit.SECONDS).isEmpty());
            assertTrue(exited.await(2, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }

    private HyboxRuntime runtime() throws Exception {
        var field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var runtime = (HyboxRuntime) ((Unsafe) field.get(null)).allocateInstance(HyboxRuntime.class);
        var registry = HyboxRuntime.class.getDeclaredField("extrasRegistry");
        registry.setAccessible(true);
        registry.set(runtime, new BundleExtrasRegistry(System.getLogger("privacy-test")));
        callbacks = new CallbackExecutor();
        var executor = HyboxRuntime.class.getDeclaredField("callbacks");
        executor.setAccessible(true);
        executor.set(runtime, callbacks);
        setPolicy(runtime, true, true);
        HyboxApi.init(runtime);
        return runtime;
    }

    private void setPolicy(HyboxRuntime runtime, boolean extras, boolean configs) throws Exception {
        Files.writeString(dir.resolve("hybox.json"),
            "{\"Version\":1,\"Capture\":{\"AllowPluginExtras\":" + extras
                + ",\"AllowModConfigOptIn\":" + configs + "}}");
        var config = HytaleHyboxConfig.loadOrCreate(dir, System.getLogger("privacy-test"));
        assertEquals(extras, config.capturePolicy().allowPluginExtras());
        assertEquals(configs, config.capturePolicy().allowModConfigOptIn());
        var engine = HyboxRuntime.class.getDeclaredField("engine");
        engine.setAccessible(true);
        engine.set(runtime, new HyboxRuntime.Engine(config, null, null, null, null, null, null, null, null, null));
    }

    private static BundleExtrasProvider provider(AtomicInteger calls) {
        return new BundleExtrasProvider() {
            private List<BundleAttachment> attachment() {
                calls.incrementAndGet();
                return List.of(new BundleAttachment("extras/test.txt", new byte[] {1}));
            }
            public List<BundleAttachment> extras(IncidentReport report, TriggerEvent event) { return attachment(); }
            public List<BundleAttachment> historicalExtras() { return attachment(); }
            public List<BundleAttachment> configExtras() { return attachment(); }
        };
    }
}
