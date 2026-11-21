package io.github.xytronix.hybox.core.report;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import io.github.xytronix.hybox.core.health.JfrTimeline;

public final class ModNames {
    private static final Set<String> ACRONYMS = Set.of("ai", "npc", "tps", "gc", "io", "cpu", "ui", "jvm", "id", "ram",
        "rss", "jfr", "ecs");
    private final Map<String, String> ids = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    ModNames(Map<String, String> plugins) {
        this(plugins == null ? Set.of() : plugins.keySet());
    }

    public ModNames(Collection<String> pluginIds) {
        Set<String> shared = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String key : pluginIds) {
            String id = key.replaceFirst(" #\\d+$", "");
            String name = id.substring(id.indexOf(':') + 1);
            String previous = ids.putIfAbsent(name, id);
            if (previous != null && !previous.equals(id)) {
                shared.add(name);
            }
        }
        shared.forEach(ids::remove);
    }

    public String id(String mod) {
        return ids.getOrDefault(mod, mod);
    }

    public static String metricLabel(String name) {
        if (name == null) {
            return null;
        }
        int slash = name.indexOf('/');
        String owner = slash < 0 ? null : name.substring(0, slash);
        owner = owner == null ? null : owner.substring(owner.indexOf(':') + 1);
        String metric = slash < 0 ? name : name.substring(slash + 1);
        List<String> words = new ArrayList<>();
        for (String part : metric.trim().split("\\s+")) {
            for (String word : part.split("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")) {
                if (!word.isEmpty()) {
                    words.add(word);
                }
            }
        }
        StringBuilder label = new StringBuilder(metric.length() + 8);
        for (String word : words) {
            String lower = word.toLowerCase(Locale.ROOT);
            boolean acronym = ACRONYMS.contains(lower) || word.length() > 1 && word.equals(word.toUpperCase(Locale.ROOT))
                && !word.equals(lower);
            if (!label.isEmpty()) {
                label.append(' ');
            }
            label.append(acronym ? word.toUpperCase(Locale.ROOT) : lower);
        }
        String shown = capitalized(label.toString());
        return owner == null || owner.isBlank() ? shown : capitalized(owner) + " · " + shown;
    }

    public static String onStackSentence(String modId, long onStack, long of, String unit, String totalMethod) {
        if (modId == null || onStack <= 0 || of <= 0 || modId.equals(JfrTimeline.CAPTURE_MOD)) {
            return null;
        }
        String path = enginePath(totalMethod);
        return ReportFindings.modName(modId) + " code was in the call chain of "
            + ReportFindings.fixed(100.0 * onStack / of, 1) + "% of " + unit
            + (path == null ? "" : ", mostly Hytale's " + path + " that runs through its code")
            + ". This is not its cost.";
    }

    private static String enginePath(String method) {
        int dot = method == null ? -1 : method.lastIndexOf('.');
        if (dot < 0 || !method.startsWith("com.hypixel.")) {
            return null;
        }
        String type = method.substring(0, dot);
        String simple = type.substring(type.lastIndexOf('.') + 1);
        int inner = simple.lastIndexOf('$');
        if (inner >= 0 && inner + 1 < simple.length() && Character.isUpperCase(simple.charAt(inner + 1))) {
            simple = simple.substring(inner + 1);
        } else if (inner >= 0) {
            simple = simple.substring(0, simple.indexOf('$'));
        }
        String words = String.join(" ", simple.split("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])"))
            .toLowerCase(Locale.ROOT);
        return words.isBlank() ? null : words;
    }

    private static String capitalized(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
