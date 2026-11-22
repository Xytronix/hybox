package io.github.xytronix.hybox.core.report;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

final class ModNames {
    private final Map<String, String> ids = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    ModNames(Map<String, String> plugins) {
        Set<String> shared = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String key : plugins == null ? Set.<String>of() : plugins.keySet()) {
            String id = key.replaceFirst(" #\\d+$", "");
            String name = id.substring(id.indexOf(':') + 1);
            String previous = ids.putIfAbsent(name, id);
            if (previous != null && !previous.equals(id)) {
                shared.add(name);
            }
        }
        shared.forEach(ids::remove);
    }

    String id(String mod) {
        return ids.getOrDefault(mod, mod);
    }
}
