package io.github.xytronix.hybox.core.health;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import jdk.jfr.EventType;
import jdk.jfr.ValueDescriptor;
import jdk.jfr.consumer.RecordedEvent;

import io.github.xytronix.hybox.core.health.JfrTimeline.ModAction;
import io.github.xytronix.hybox.core.health.JfrTimeline.ModSettings;
import io.github.xytronix.hybox.core.health.JfrTimelineParser.Pt;

final class JfrModEvents {
    static final String CATEGORY = "Mods";
    private static final String HYBOX_PREFIX = "io.github.xytronix.hybox.";
    private static final Set<String> NUMBERS = Set.of("double", "float", "long", "int", "short", "byte");
    private static final int MOD_CHARS = 48;
    private static final int NAME_CHARS = 64;
    private static final int CATEGORY_CHARS = 40;
    private static final int TEXT_CHARS = 200;
    private static final int METRICS_PER_MOD = 64;
    private static final int SETTINGS_PER_MOD = 128;

    private enum Shape { NONE, METRIC, ACTION, SETTING }

    private record Kind(Shape shape, String mod) {
        static final Kind NONE = new Kind(Shape.NONE, null);
    }

    record Metric(String mod, String name, boolean counter, List<Pt> points) {}

    private record Setting(long t, String value) {}

    private final int actionCap;
    private final Map<Long, Kind> kinds = new HashMap<>();
    private final Map<String, Metric> metrics = new LinkedHashMap<>();
    private final Map<String, Integer> metricsPerMod = new HashMap<>();
    private final ArrayDeque<ModAction> actions = new ArrayDeque<>();
    private final Map<String, Map<String, Setting>> settings = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    JfrModEvents(int actionCap) {
        this.actionCap = actionCap;
    }

    void record(RecordedEvent e, long t) {
        EventType type = e.getEventType();
        String name = type.getName();
        if (t <= 0 || name.startsWith("jdk.") || name.startsWith(HYBOX_PREFIX)) {
            return;
        }
        Kind kind = kinds.computeIfAbsent(type.getId(), id -> classify(type));
        try {
            switch (kind.shape()) {
                case METRIC -> metric(e, t, kind.mod());
                case ACTION -> action(e, t, kind.mod());
                case SETTING -> setting(e, t, kind.mod());
                case NONE -> { }
            }
        } catch (RuntimeException ignored) {
        }
    }

    Collection<Metric> metrics() {
        return metrics.values();
    }

    List<ModAction> actions() {
        return List.copyOf(actions);
    }

    List<ModSettings> settings() {
        List<ModSettings> out = new ArrayList<>(settings.size());
        settings.forEach((mod, values) -> {
            Map<String, String> last = new LinkedHashMap<>();
            values.forEach((name, setting) -> last.put(name, setting.value()));
            out.add(new ModSettings(mod, last));
        });
        return out;
    }

    private static Kind classify(EventType type) {
        List<String> category = type.getCategoryNames();
        if (category.size() < 2 || !CATEGORY.equalsIgnoreCase(category.get(0).trim())) {
            return Kind.NONE;
        }
        String mod = clip(category.get(1), MOD_CHARS).replace('/', '_');
        if (mod.isEmpty()) {
            return Kind.NONE;
        }
        boolean named = text(type, "name");
        ValueDescriptor value = type.getField("value");
        if (named && text(type, "kind") && value != null && NUMBERS.contains(value.getTypeName())) {
            return new Kind(Shape.METRIC, mod);
        }
        if (text(type, "category") && text(type, "message")) {
            return new Kind(Shape.ACTION, mod);
        }
        if (named && value != null && (NUMBERS.contains(value.getTypeName()) || plain(value))) {
            return new Kind(Shape.SETTING, mod);
        }
        return Kind.NONE;
    }

    private static boolean text(EventType type, String field) {
        ValueDescriptor descriptor = type.getField(field);
        return descriptor != null && String.class.getName().equals(descriptor.getTypeName());
    }

    private static boolean plain(ValueDescriptor value) {
        String type = value.getTypeName();
        return String.class.getName().equals(type) || "boolean".equals(type) || "char".equals(type);
    }

    private void metric(RecordedEvent e, long t, String mod) {
        String name = clip(e.getString("name"), NAME_CHARS);
        double value = e.getDouble("value");
        if (name.isEmpty() || !Double.isFinite(value)) {
            return;
        }
        String key = mod + "/" + name;
        Metric metric = metrics.get(key);
        if (metric == null) {
            if (metricsPerMod.getOrDefault(mod, 0) >= METRICS_PER_MOD) {
                return;
            }
            metricsPerMod.merge(mod, 1, Integer::sum);
            metric = new Metric(mod, name, "counter".equalsIgnoreCase(clip(e.getString("kind"), NAME_CHARS)),
                new ArrayList<>());
            metrics.put(key, metric);
        }
        metric.points().add(new Pt(t, value));
    }

    private void action(RecordedEvent e, long t, String mod) {
        String category = clip(e.getString("category"), CATEGORY_CHARS);
        String message = clip(e.getString("message"), TEXT_CHARS);
        if (category.isEmpty() && message.isEmpty()) {
            return;
        }
        actions.addLast(new ModAction(t, mod, category, message));
        if (actions.size() > actionCap) {
            actions.removeFirst();
        }
    }

    private void setting(RecordedEvent e, long t, String mod) {
        String name = clip(e.getString("name"), NAME_CHARS);
        if (name.isEmpty()) {
            return;
        }
        Object raw = e.getValue("value");
        String value = clip(raw == null ? "" : String.valueOf(raw), TEXT_CHARS);
        Map<String, Setting> values = settings.computeIfAbsent(mod, k -> new LinkedHashMap<>());
        Setting previous = values.get(name);
        if (previous == null && values.size() >= SETTINGS_PER_MOD) {
            return;
        }
        if (previous == null || t >= previous.t()) {
            values.put(name, new Setting(t, value));
        }
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= max) {
            return trimmed;
        }
        int cut = Character.isHighSurrogate(trimmed.charAt(max - 1)) ? max - 1 : max;
        return trimmed.substring(0, cut);
    }
}
