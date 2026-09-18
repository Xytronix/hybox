package io.github.xytronix.hybox.hytale;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

import io.github.xytronix.hybox.core.capture.BundleExtrasProvider;
import io.github.xytronix.hybox.core.bundle.BundleAttachment;
import io.github.xytronix.hybox.core.incident.DiagnosticSection;
import io.github.xytronix.hybox.core.jfr.HyboxPluginEvent;
import io.github.xytronix.hybox.core.jfr.HyboxPluginMetricEvent;
import io.github.xytronix.hybox.core.incident.IncidentReport;
import io.github.xytronix.hybox.core.trigger.TriggerEvent;

/**
 * Public API for third-party plugins to integrate with Hybox.
 * Every entry point takes a {@code plugin} owner token used for namespacing,
 * per-plugin quotas, and {@link #clearPlugin(String)} cleanup.
 * Example:
 * HyboxApi.registerExtras("my-plugin", (report, event) -> List.of(
 *     new BundleAttachment("extras/my-plugin.txt", myData.getBytes())
 * ));
 */
public final class HyboxApi {
    private static final int MAX_PER_PLUGIN = 64;
    private static final Registration NO_OP = () -> {
    };

    private static volatile HyboxRuntime runtime;
    private static BundleExtrasProvider extrasProvider;
    private static final List<DiagnosticRegistration> DIAGNOSTICS = new CopyOnWriteArrayList<>();
    private static final List<OwnedConfig> CONFIG_PATHS = new CopyOnWriteArrayList<>();
    private static final List<OwnedExtras> EXTRAS = new CopyOnWriteArrayList<>();
    private static final ConcurrentHashMap<String, Double> PLUGIN_GAUGES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Double> PLUGIN_COUNTERS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, GaugeRegistration> PLUGIN_GAUGE_SUPPLIERS = new ConcurrentHashMap<>();

    private HyboxApi() {
    }

    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        void unregister();

        @Override
        default void close() {
            unregister();
        }
    }

    static synchronized void init(HyboxRuntime runtime) {
        if (HyboxApi.runtime == runtime) {
            return;
        }
        if (HyboxApi.runtime != null) {
            shutdown();
        }
        HyboxApi.runtime = runtime;
        extrasProvider = new PluginExtrasProvider(runtime);
        runtime.extrasRegistry().register(extrasProvider);
    }

    static synchronized void shutdown() {
        HyboxRuntime previous = runtime;
        if (previous != null && extrasProvider != null) {
            previous.extrasRegistry().unregister(extrasProvider);
        }
        extrasProvider = null;
        HyboxApi.runtime = null;
        DIAGNOSTICS.forEach(r -> r.token().close());
        EXTRAS.forEach(r -> r.token().close());
        PLUGIN_GAUGE_SUPPLIERS.values().forEach(r -> r.token().close());
        DIAGNOSTICS.clear();
        CONFIG_PATHS.clear();
        EXTRAS.clear();
        PLUGIN_GAUGES.clear();
        PLUGIN_COUNTERS.clear();
        PLUGIN_GAUGE_SUPPLIERS.clear();
    }

    public static synchronized void clearPlugin(String plugin) {
        String owner = owner(plugin);
        DIAGNOSTICS.removeIf(r -> {
            if (!r.owner().equals(owner)) {
                return false;
            }
            r.token().close();
            return true;
        });
        CONFIG_PATHS.removeIf(c -> c.owner().equals(owner));
        EXTRAS.removeIf(e -> {
            if (!e.owner().equals(owner)) {
                return false;
            }
            e.token().close();
            return true;
        });
        String prefix = owner + "/";
        PLUGIN_GAUGES.keySet().removeIf(k -> k.startsWith(prefix));
        PLUGIN_COUNTERS.keySet().removeIf(k -> k.startsWith(prefix));
        PLUGIN_GAUGE_SUPPLIERS.entrySet().removeIf(e -> {
            if (!e.getKey().startsWith(prefix)) {
                return false;
            }
            e.getValue().token().close();
            return true;
        });
    }

    public static synchronized Registration registerExtras(String plugin, BundleExtrasProvider provider) {
        Objects.requireNonNull(provider, "provider");
        String owner = owner(plugin);
        HyboxRuntime rt = runtime;
        if (rt == null || !rt.config().capturePolicy().allowPluginExtras()) {
            return NO_OP;
        }
        if (ownerCount(EXTRAS, owner) >= MAX_PER_PLUGIN) {
            throw new IllegalStateException("Extras registration limit reached for '" + owner + "' (" + MAX_PER_PLUGIN + ").");
        }
        OwnedExtras entry = new OwnedExtras(owner, provider, new CallbackExecutor.Token(owner + "/extras"));
        EXTRAS.add(entry);
        return () -> {
            entry.token().close();
            EXTRAS.remove(entry);
        };
    }

    public static synchronized Registration registerConfig(String plugin, Path file) {
        Objects.requireNonNull(file, "file");
        String owner = owner(plugin);
        HyboxRuntime rt = runtime;
        if (rt == null || !rt.config().capturePolicy().allowModConfigOptIn()) {
            return NO_OP;
        }
        if (CONFIG_PATHS.stream().anyMatch(c -> c.file().equals(file))) {
            return () -> CONFIG_PATHS.removeIf(c -> c.file().equals(file));
        }
        if (ownerCount(CONFIG_PATHS, owner) >= MAX_PER_PLUGIN) {
            throw new IllegalStateException("Config registration limit reached for '" + owner + "' (" + MAX_PER_PLUGIN + ").");
        }
        OwnedConfig entry = new OwnedConfig(owner, file);
        CONFIG_PATHS.add(entry);
        return () -> CONFIG_PATHS.remove(entry);
    }

    static List<Path> registeredConfigPaths() {
        HyboxRuntime rt = runtime;
        if (rt == null || !rt.config().capturePolicy().allowModConfigOptIn()) {
            return List.of();
        }
        return CONFIG_PATHS.stream().map(OwnedConfig::file).distinct().toList();
    }

    public static void recordEvent(String plugin, String category, String message) {
        try {
            HyboxRuntime rt = runtime;
            String maskedCategory = rt == null ? category : rt.maskText(category);
            String maskedMessage = rt == null ? message : rt.maskText(message);
            HyboxPluginEvent event = new HyboxPluginEvent();
            event.category = clip(owner(plugin) + "/" + clip(maskedCategory, 40), 64);
            event.message = clip(maskedMessage, 200);
            event.commit();
        } catch (Throwable ignored) {
        }
    }

    public static void recordCount(String plugin, String name, long delta) {
        commitMetric(owner(plugin), name, delta, true);
    }

    public static void recordGauge(String plugin, String name, double value) {
        commitMetric(owner(plugin), name, value, false);
    }

    public static synchronized Registration registerGauge(String plugin, String name, DoubleSupplier supplier) {
        Objects.requireNonNull(supplier, "supplier");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank.");
        }
        String owner = owner(plugin);
        String key = key(owner, name);
        if (!PLUGIN_GAUGE_SUPPLIERS.containsKey(key) && ownerEntries(PLUGIN_GAUGE_SUPPLIERS, owner) >= MAX_PER_PLUGIN) {
            throw new IllegalStateException("Gauge registration limit reached for '" + owner + "' (" + MAX_PER_PLUGIN + ").");
        }
        GaugeRegistration entry = new GaugeRegistration(supplier, new CallbackExecutor.Token(owner + "/gauge/" + clip(name, 64)));
        GaugeRegistration previous = PLUGIN_GAUGE_SUPPLIERS.put(key, entry);
        if (previous != null) {
            previous.token().close();
        }
        return () -> {
            entry.token().close();
            PLUGIN_GAUGE_SUPPLIERS.remove(key, entry);
        };
    }

    private static void commitMetric(String owner, String name, double value, boolean counter) {
        try {
            if (name == null || name.isBlank() || !Double.isFinite(value)) {
                return;
            }
            String key = key(owner, name);
            ConcurrentHashMap<String, Double> store = counter ? PLUGIN_COUNTERS : PLUGIN_GAUGES;
            if (!store.containsKey(key) && ownerEntries(store, owner) >= MAX_PER_PLUGIN) {
                return;
            }
            HyboxPluginMetricEvent event = new HyboxPluginMetricEvent();
            event.name = key;
            event.value = value;
            event.counter = counter;
            event.commit();
            if (counter) {
                store.merge(key, value, Double::sum);
            } else {
                store.put(key, value);
            }
        } catch (Throwable ignored) {
        }
    }

    static Map<String, Double> pluginGauges() {
        return Map.copyOf(PLUGIN_GAUGES);
    }

    static Map<String, Double> sampleSupplierGauges() {
        HyboxRuntime rt = runtime;
        if (rt == null) {
            return Map.of();
        }
        long deadline = rt.callbacks().deadline();
        Map<String, Double> sampled = new HashMap<>();
        for (Map.Entry<String, GaugeRegistration> entry : PLUGIN_GAUGE_SUPPLIERS.entrySet()) {
            if (System.nanoTime() - deadline >= 0) {
                break;
            }
            GaugeRegistration reg = entry.getValue();
            Double value = rt.callbacks().invoke(reg.token(), () ->
                runtime == rt && reg.token().active() ? reg.supplier().getAsDouble() : null, deadline);
            if (value == null || !Double.isFinite(value) || runtime != rt || !reg.token().active()) {
                continue;
            }
            sampled.put(entry.getKey(), value);
            HyboxPluginMetricEvent event = new HyboxPluginMetricEvent();
            event.name = entry.getKey();
            event.value = value;
            event.counter = false;
            event.commit();
        }
        return runtime == rt ? Map.copyOf(sampled) : Map.of();
    }

    static Map<String, Double> pluginCounters() {
        return Map.copyOf(PLUGIN_COUNTERS);
    }

    public static synchronized Registration registerDiagnostics(String plugin, String title, Supplier<Map<String, String>> supplier) {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(supplier, "supplier");
        String owner = owner(plugin);
        if (ownerCount(DIAGNOSTICS, owner) >= MAX_PER_PLUGIN) {
            throw new IllegalStateException("Diagnostic registration limit reached for '" + owner + "' (" + MAX_PER_PLUGIN + ").");
        }
        DiagnosticRegistration registration = new DiagnosticRegistration(owner, title, supplier,
            new CallbackExecutor.Token(owner + "/diagnostics/" + clip(title, 64)));
        DIAGNOSTICS.add(registration);
        return () -> {
            registration.token().close();
            DIAGNOSTICS.remove(registration);
        };
    }

    static List<DiagnosticSection> collectDiagnostics() {
        HyboxRuntime rt = runtime;
        if (rt == null) {
            return List.of();
        }
        long deadline = rt.callbacks().deadline();
        List<DiagnosticSection> out = new ArrayList<>();
        for (DiagnosticRegistration reg : DIAGNOSTICS) {
            if (System.nanoTime() - deadline >= 0) {
                break;
            }
            DiagnosticSection section = rt.callbacks().invoke(reg.token(), () ->
                runtime == rt && reg.token().active() ? new DiagnosticSection(reg.title(), reg.supplier().get()) : null,
                deadline);
            if (section != null && runtime == rt && reg.token().active()) {
                out.add(section);
            }
        }
        return runtime == rt ? out : List.of();
    }

    private static String owner(String plugin) {
        String trimmed = plugin == null ? "" : plugin.trim().replace('/', '_');
        return trimmed.isEmpty() ? "unknown" : clip(trimmed, 48);
    }

    private static String key(String owner, String name) {
        return owner + "/" + clip(name, 64);
    }

    private static long ownerEntries(Map<String, ?> store, String owner) {
        String prefix = owner + "/";
        return store.keySet().stream().filter(k -> k.startsWith(prefix)).count();
    }

    private static long ownerCount(List<? extends Owned> entries, String owner) {
        return entries.stream().filter(e -> e.owner().equals(owner)).count();
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private interface Owned {
        String owner();
    }

    private record DiagnosticRegistration(String owner, String title, Supplier<Map<String, String>> supplier,
                                          CallbackExecutor.Token token) implements Owned {
    }

    private record OwnedConfig(String owner, Path file) implements Owned {
    }

    private record OwnedExtras(String owner, BundleExtrasProvider provider, CallbackExecutor.Token token) implements Owned {
    }

    private record GaugeRegistration(DoubleSupplier supplier, CallbackExecutor.Token token) {
    }

    private static final class PluginExtrasProvider implements BundleExtrasProvider {
        private final HyboxRuntime captured;

        private PluginExtrasProvider(HyboxRuntime captured) {
            this.captured = captured;
        }

        private boolean allowed(boolean config) {
            return HyboxApi.runtime == captured
                && captured.config().capturePolicy().allowPluginExtras()
                && (!config || captured.config().capturePolicy().allowModConfigOptIn());
        }

        private List<BundleAttachment> collect(boolean config, ExtrasCall call) {
            if (!allowed(config)) {
                return List.of();
            }
            long deadline = captured.callbacks().deadline();
            var policy = captured.config();
            List<BundleAttachment> all = new ArrayList<>();
            for (OwnedExtras entry : EXTRAS) {
                if (System.nanoTime() - deadline >= 0) {
                    break;
                }
                if (!allowed(config) || captured.config() != policy) {
                    return List.of();
                }
                List<BundleAttachment> attachments = captured.callbacks().invoke(entry.token(), () -> {
                    if (!allowed(config) || captured.config() != policy || !entry.token().active()) {
                        return List.of();
                    }
                    List<BundleAttachment> result = call.call(entry.provider());
                    return result == null ? List.of() : List.copyOf(result);
                }, deadline);
                if (attachments != null && allowed(config) && captured.config() == policy && entry.token().active()) {
                    all.addAll(attachments);
                }
            }
            return allowed(config) && captured.config() == policy ? all : List.of();
        }

        @Override
        public List<BundleAttachment> extras(IncidentReport report, TriggerEvent event) {
            return collect(false, provider -> provider.extras(report, event));
        }

        @Override
        public List<BundleAttachment> historicalExtras() {
            return collect(false, BundleExtrasProvider::historicalExtras);
        }

        @Override
        public List<BundleAttachment> configExtras() {
            return collect(true, BundleExtrasProvider::configExtras);
        }
    }

    @FunctionalInterface
    private interface ExtrasCall {
        List<BundleAttachment> call(BundleExtrasProvider provider) throws Exception;
    }
}
