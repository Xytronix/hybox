package io.github.xytronix.hybox.core.metrics;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ModMetrics {

    public record Series(String mod, String name, boolean counter, double value) {
    }

    private record Key(String mod, String name) {
    }

    private static final Comparator<Series> ORDER = Comparator.comparing(Series::mod).thenComparing(Series::name);

    private final Map<Key, Series> series = new ConcurrentHashMap<>();

    public void add(String mod, String name, boolean counter, double value) {
        if (!Double.isFinite(value) || counter && value < 0) {
            return;
        }
        Series next = new Series(mod, name, counter, value);
        series.merge(new Key(mod, name), next, (previous, update) -> previous.counter() && update.counter()
            ? new Series(mod, name, true, previous.value() + update.value()) : update);
    }

    public List<Series> snapshot() {
        return series.values().stream().sorted(ORDER).toList();
    }

    public void clear() {
        series.clear();
    }
}
