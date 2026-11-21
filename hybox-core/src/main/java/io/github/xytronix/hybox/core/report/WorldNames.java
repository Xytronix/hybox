package io.github.xytronix.hybox.core.report;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import io.github.xytronix.hybox.core.health.HealthSnapshot;
import io.github.xytronix.hybox.core.health.JfrTimeline;
import io.github.xytronix.hybox.core.incident.IncidentReport;
import io.github.xytronix.hybox.core.json.JsonWriter;

public final class WorldNames {
    private static final String INSTANCE = "instance-";
    private static final String WORLD_THREAD = "WorldThread - ";
    private static final Pattern UUID_SUFFIX = Pattern.compile(
        "-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final java.util.Set<String> SCOPES = java.util.Set.of("server", "jvm", "network", "host");

    private final Map<String, String> cased = new LinkedHashMap<>();
    private final Map<String, Integer> stems = new HashMap<>();

    public WorldNames(Iterable<String> names) {
        for (String name : names) {
            add(name);
        }
        for (String name : cased.values()) {
            stems.merge(key(stem(name)), 1, Integer::sum);
        }
    }

    public static String display(String world) {
        if (world == null) {
            return null;
        }
        String name = world.regionMatches(true, 0, INSTANCE, 0, INSTANCE.length())
            ? world.substring(INSTANCE.length()) : world;
        return name.replace('_', ' ');
    }

    public static boolean isWorld(String scope) {
        return scope != null && !scope.isBlank() && !SCOPES.contains(scope.trim().toLowerCase(Locale.ROOT));
    }

    public static int cpuRank(long[] samples, int index) {
        int rank = 1;
        for (int i = 0; i < samples.length; i++) {
            if (samples[i] > samples[index] || samples[i] == samples[index] && i < index) {
                rank++;
            }
        }
        return rank;
    }

    public static boolean sameWorld(String a, String b) {
        return a != null && b != null && key(stem(a)).equals(key(stem(b)));
    }

    public String raw(String world) {
        if (world == null) {
            return null;
        }
        String known = cased.get(key(world));
        return known == null ? world : known;
    }

    public String label(String world) {
        if (world == null) {
            return null;
        }
        String raw = raw(world);
        String shown = stems.getOrDefault(key(stem(raw)), 0) <= 1 ? stem(raw) : raw;
        return display(shown);
    }

    public Map<String, String> labels() {
        Map<String, String> out = new LinkedHashMap<>();
        cased.forEach((key, raw) -> out.put(key, label(raw)));
        return out;
    }

    static WorldNames of(IncidentReport report, JfrTimeline timeline, Map<String, String> tickSystems) {
        List<String> names = new ArrayList<>();
        HealthSnapshot snapshot = report.snapshot();
        if (snapshot != null) {
            snapshot.worlds().forEach(world -> names.add(world.name()));
            for (HealthSnapshot.HotThread thread : snapshot.hotThreads()) {
                if (thread.thread() != null && thread.thread().startsWith(WORLD_THREAD)) {
                    names.add(thread.thread().substring(WORLD_THREAD.length()));
                }
            }
        }
        if (timeline != null) {
            names.addAll(timeline.worldSamples().keySet());
            timeline.cpuByWorld().forEach(world -> names.add(world.world()));
            names.addAll(timeline.ticks().keySet());
            names.addAll(timeline.chunkChurnByWorld().keySet());
            timeline.tickSeries().forEach(series -> names.add(after(series.name())));
        }
        if (tickSystems != null) {
            tickSystems.keySet().forEach(key -> names.add(after(key.replaceFirst(" #\\d+$", ""))));
        }
        if (isWorld(report.meta().world())) {
            names.add(report.meta().world());
        }
        return new WorldNames(names);
    }

    void write(JsonWriter json) throws IOException {
        json.name("worldNames").beginObject();
        for (Map.Entry<String, String> entry : labels().entrySet()) {
            json.name(entry.getKey()).value(entry.getValue());
        }
        json.endObject();
    }

    private void add(String name) {
        if (name == null || name.isBlank()) {
            return;
        }
        String key = key(name);
        String known = cased.get(key);
        if (known == null || known.equals(key) && !name.equals(key)) {
            cased.put(key, name);
        }
    }

    private static String after(String name) {
        int at = name == null ? -1 : name.lastIndexOf(" @ ");
        return at < 0 ? null : name.substring(at + 3);
    }

    private static String stem(String world) {
        return UUID_SUFFIX.matcher(world).replaceFirst("");
    }

    private static String key(String world) {
        return world.toLowerCase(Locale.ROOT);
    }
}
