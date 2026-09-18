package io.github.xytronix.hybox.hytale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import sun.misc.Unsafe;
import org.junit.jupiter.api.Test;
import io.github.xytronix.hybox.core.bundle.BundleExtrasRegistry;
import io.github.xytronix.hybox.core.capture.BundleExtrasProvider;
import io.github.xytronix.hybox.core.incident.DiagnosticSection;

class HyboxApiTest {
    private CallbackExecutor callbacks;

    @BeforeEach
    void setUp() throws Exception {
        var field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var runtime = (HyboxRuntime) ((Unsafe) field.get(null)).allocateInstance(HyboxRuntime.class);
        var registry = HyboxRuntime.class.getDeclaredField("extrasRegistry");
        registry.setAccessible(true);
        registry.set(runtime, new BundleExtrasRegistry(System.getLogger("api-test")));
        callbacks = new CallbackExecutor();
        var executor = HyboxRuntime.class.getDeclaredField("callbacks");
        executor.setAccessible(true);
        executor.set(runtime, callbacks);
        HyboxApi.init(runtime);
    }

    @AfterEach
    void tearDown() {
        HyboxApi.shutdown();
        callbacks.close();
    }

    @Test
    void registeredDiagnosticsAreCollected() {
        HyboxApi.registerDiagnostics("My Plugin", "Status", () -> Map.of("mode", "fast"));

        List<DiagnosticSection> sections = HyboxApi.collectDiagnostics();

        assertEquals(1, sections.size());
        assertEquals("Status", sections.get(0).title());
        assertEquals(Map.of("mode", "fast"), sections.get(0).entries());
    }

    @Test
    void unregisteringDiagnosticsRemovesSection() {
        HyboxApi.Registration registration =
            HyboxApi.registerDiagnostics("My Plugin", "Status", () -> Map.of("mode", "fast"));

        registration.unregister();

        assertTrue(HyboxApi.collectDiagnostics().isEmpty());
    }

    @Test
    void diagnosticsCapIsPerPlugin() {
        for (int i = 0; i < 64; i++) {
            HyboxApi.registerDiagnostics("noisy", "section-" + i, Map::of);
        }

        assertThrows(IllegalStateException.class,
            () -> HyboxApi.registerDiagnostics("noisy", "one-too-many", Map::of));

        HyboxApi.registerDiagnostics("other", "ok", Map::of);
        assertTrue(HyboxApi.collectDiagnostics().stream().anyMatch(s -> s.title().equals("ok")));
    }

    @Test
    void registeredGaugeIsPulledFreshEachRead() {
        double[] value = {1.0};
        HyboxApi.registerGauge("p", "example gauge", () -> value[0]);

        assertEquals(1.0, HyboxApi.sampleSupplierGauges().get("p/example gauge"));

        value[0] = 42.0;
        assertEquals(42.0, HyboxApi.sampleSupplierGauges().get("p/example gauge"));
    }

    @Test
    void unregisteringGaugeRemovesIt() {
        HyboxApi.Registration registration = HyboxApi.registerGauge("p", "temp", () -> 5.0);
        assertEquals(5.0, HyboxApi.sampleSupplierGauges().get("p/temp"));

        registration.unregister();

        assertFalse(HyboxApi.sampleSupplierGauges().containsKey("p/temp"));
    }

    @Test
    void gaugeCapIsPerPlugin() {
        for (int i = 0; i < 64; i++) {
            HyboxApi.registerGauge("noisy", "g-" + i, () -> 1.0);
        }

        assertThrows(IllegalStateException.class,
            () -> HyboxApi.registerGauge("noisy", "one-too-many", () -> 1.0));
    }

    @Test
    void nonFiniteGaugeValueIsOmitted() {
        HyboxApi.registerGauge("p", "bad", () -> Double.NaN);

        assertFalse(HyboxApi.sampleSupplierGauges().containsKey("p/bad"));
    }

    @Test
    void sameMetricNameFromDifferentPluginsDoesNotCollide() {
        HyboxApi.recordGauge("a", "requests", 1.0);
        HyboxApi.recordGauge("b", "requests", 2.0);

        Map<String, Double> gauges = HyboxApi.pluginGauges();
        assertEquals(1.0, gauges.get("a/requests"));
        assertEquals(2.0, gauges.get("b/requests"));
    }

    @Test
    void countersFromDifferentPluginsAccumulateSeparately() {
        HyboxApi.recordCount("a", "hits", 3);
        HyboxApi.recordCount("b", "hits", 5);
        HyboxApi.recordCount("a", "hits", 2);

        Map<String, Double> counters = HyboxApi.pluginCounters();
        assertEquals(5.0, counters.get("a/hits"));
        assertEquals(5.0, counters.get("b/hits"));
    }

    @Test
    void clearPluginRemovesOnlyThatPluginsState() {
        HyboxApi.registerGauge("a", "g", () -> 1.0);
        HyboxApi.recordGauge("a", "m", 2.0);
        HyboxApi.registerDiagnostics("a", "d", Map::of);
        HyboxApi.registerGauge("b", "g", () -> 9.0);

        HyboxApi.clearPlugin("a");

        assertFalse(HyboxApi.sampleSupplierGauges().containsKey("a/g"));
        assertFalse(HyboxApi.pluginGauges().containsKey("a/m"));
        assertTrue(HyboxApi.collectDiagnostics().isEmpty());
        assertEquals(9.0, HyboxApi.sampleSupplierGauges().get("b/g"));
    }

    @Test
    void shutdownClearsAccumulatedMetrics() {
        HyboxApi.recordGauge("a", "g", 1.0);
        HyboxApi.recordCount("a", "c", 1);

        HyboxApi.shutdown();

        assertTrue(HyboxApi.pluginGauges().isEmpty());
        assertTrue(HyboxApi.pluginCounters().isEmpty());
    }

    @Test
    void registerConfigNoOpsWhenUninitialized() {
        HyboxApi.shutdown();
        HyboxApi.Registration registration =
            HyboxApi.registerConfig("x", java.nio.file.Path.of("mods", "x", "config.json"));

        registration.unregister();

        assertTrue(HyboxApi.registeredConfigPaths().isEmpty());
    }

    @Test
    void extrasRegistryUnregistersProviders() {
        BundleExtrasRegistry registry = new BundleExtrasRegistry(System.getLogger("api-test"));
        BundleExtrasProvider provider = (report, event) -> List.of();

        registry.register(provider);
        assertEquals(1, registry.size());

        registry.unregister(provider);
        assertEquals(0, registry.size());
    }

    @Test
    void diagnosticsFailureDoesNotHideHealthySections() {
        HyboxApi.registerDiagnostics("broken", "Broken", () -> {
            throw new AssertionError("plugin failure");
        });
        HyboxApi.registerDiagnostics("healthy", "Healthy", () -> Map.of("state", "ok"));

        assertEquals(List.of(new DiagnosticSection("Healthy", Map.of("state", "ok"))), HyboxApi.collectDiagnostics());
        assertEquals(1, callbacks.health().failures());
        assertEquals(0, callbacks.health().quarantined());
        assertEquals(1, HyboxApi.collectDiagnostics().size());
        assertEquals(2, callbacks.health().failures());
    }

    @Test
    void timedOutGaugeCannotOverwriteReregistrationOrRunAgain() throws Exception {
        var release = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        var old = HyboxApi.registerGauge("plugin", "value", () -> {
            try {
                while (release.getCount() != 0) {
                    try {
                        release.await();
                    } catch (InterruptedException ignored) {
                    }
                }
                return 100.0;
            } finally {
                exited.countDown();
            }
        });
        try {
            assertTrue(org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(1),
                HyboxApi::sampleSupplierGauges).isEmpty());
            assertEquals(1, callbacks.health().timeouts());
            assertTrue(HyboxApi.sampleSupplierGauges().isEmpty());
            HyboxApi.registerGauge("plugin", "value", () -> 9.0);
            old.close();
            assertEquals(0, callbacks.health().quarantined());
            assertEquals(Map.of("plugin/value", 9.0), HyboxApi.sampleSupplierGauges());
            release.countDown();
            assertTrue(exited.await(2, TimeUnit.SECONDS));
            assertEquals(Map.of("plugin/value", 9.0), HyboxApi.sampleSupplierGauges());
        } finally {
            release.countDown();
        }
    }
}
