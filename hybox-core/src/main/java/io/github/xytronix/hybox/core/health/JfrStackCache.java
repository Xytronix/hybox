package io.github.xytronix.hybox.core.health;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Function;

final class JfrStackCache<K, V> {
    static final int DEFAULT_CAPACITY = 4_096;

    private final Map<K, V> entries = new IdentityHashMap<>();
    private final int capacity;
    private final Function<K, V> compute;

    JfrStackCache(Function<K, V> compute) {
        this(DEFAULT_CAPACITY, compute);
    }

    JfrStackCache(int capacity, Function<K, V> compute) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0.");
        }
        this.capacity = capacity;
        this.compute = compute;
    }

    V get(K key) {
        V cached = entries.get(key);
        if (cached != null || entries.containsKey(key)) {
            return cached;
        }
        V value = compute.apply(key);
        if (entries.size() >= capacity) {
            entries.clear();
        }
        entries.put(key, value);
        return value;
    }

    int size() {
        return entries.size();
    }
}
