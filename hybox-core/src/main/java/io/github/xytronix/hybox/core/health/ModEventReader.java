package io.github.xytronix.hybox.core.health;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

import jdk.jfr.EventType;
import jdk.jfr.FlightRecorder;
import jdk.jfr.consumer.EventStream;
import jdk.jfr.consumer.RecordedEvent;

import io.github.xytronix.hybox.core.metrics.ModMetrics;

public final class ModEventReader implements AutoCloseable {
    private static final long JOIN_MILLIS = 3_000;
    private static final long SCAN_NANOS = 5_000_000_000L;

    private final ModMetrics metrics;
    private final UnaryOperator<String> modIds;
    private final System.Logger logger;
    private final EventStream stream;
    private final Thread thread;
    private final Set<String> subscribed = ConcurrentHashMap.newKeySet();
    private final Map<Long, String> mods = new HashMap<>();
    private final Map<String, String> ids = new HashMap<>();
    private final Map<String, Map<String, Boolean>> kinds = new HashMap<>();
    private volatile boolean closed;
    private int scannedTypes = -1;
    private long scannedAt;

    private ModEventReader(ModMetrics metrics, UnaryOperator<String> modIds, System.Logger logger) throws IOException {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.modIds = Objects.requireNonNull(modIds, "modIds");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.stream = EventStream.openRepository();
        stream.setOrdered(false);
        stream.setReuse(true);
        stream.onFlush(this::flush);
        this.thread = new Thread(this::run, "hybox-mod-events");
        thread.setDaemon(true);
    }

    public static ModEventReader start(ModMetrics metrics, UnaryOperator<String> modIds, System.Logger logger)
        throws IOException {
        if (!FlightRecorder.isAvailable()) {
            throw new IOException("JDK Flight Recorder is not available in this Java runtime.");
        }
        ModEventReader reader = new ModEventReader(metrics, modIds, logger);
        try {
            reader.scan();
            reader.thread.start();
        } catch (RuntimeException | Error failure) {
            reader.stream.close();
            throw failure;
        }
        return reader;
    }

    @Override
    public void close() {
        closed = true;
        stream.close();
        if (Thread.currentThread() != thread) {
            try {
                thread.join(JOIN_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        metrics.clear();
    }

    private void run() {
        try {
            stream.start();
        } catch (Throwable failure) {
            if (!closed) {
                logger.log(System.Logger.Level.WARNING, "Hybox stopped reading mod JFR events for Prometheus.", failure);
            }
        }
    }

    private void flush() {
        if (System.nanoTime() - scannedAt < SCAN_NANOS) {
            return;
        }
        try {
            scan();
        } catch (RuntimeException ignored) {
        }
    }

    private void scan() {
        scannedAt = System.nanoTime();
        List<EventType> types = FlightRecorder.getFlightRecorder().getEventTypes();
        if (types.size() == scannedTypes) {
            return;
        }
        scannedTypes = types.size();
        for (EventType type : types) {
            subscribe(type);
        }
    }

    private void subscribe(EventType type) {
        if (!subscribed.contains(type.getName()) && JfrModEvents.metricMod(type) != null
            && subscribed.add(type.getName())) {
            stream.onEvent(type.getName(), this::metric);
        }
    }

    private void metric(RecordedEvent event) {
        if (closed) {
            return;
        }
        try {
            EventType type = event.getEventType();
            String mod = mods.computeIfAbsent(type.getId(), id -> modId(type));
            if (mod.isEmpty()) {
                return;
            }
            String name = JfrModEvents.clip(event.getString("name"), JfrModEvents.NAME_CHARS);
            if (name.isEmpty()) {
                return;
            }
            Map<String, Boolean> names = kinds.computeIfAbsent(mod, key -> new HashMap<>());
            Boolean counter = names.get(name);
            if (counter == null) {
                if (names.size() >= JfrModEvents.METRICS_PER_MOD) {
                    return;
                }
                counter = "counter".equalsIgnoreCase(JfrModEvents.clip(event.getString("kind"), JfrModEvents.NAME_CHARS));
                names.put(name, counter);
            }
            metrics.add(mod, name, counter, event.getDouble("value"));
        } catch (RuntimeException ignored) {
        }
    }

    private String modId(EventType type) {
        String mod = JfrModEvents.metricMod(type);
        if (mod == null) {
            return "";
        }
        return ids.computeIfAbsent(mod, this::resolve);
    }

    private String resolve(String mod) {
        try {
            String id = modIds.apply(mod);
            return id == null || id.isBlank() ? mod : id;
        } catch (RuntimeException e) {
            return mod;
        }
    }
}
