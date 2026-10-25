package io.github.xytronix.hybox.core.report;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.xytronix.hybox.core.health.JfrTimeline;

/** Resolves package fallbacks only when recorded class-loader ownership identifies one plugin. */
final class ReportAttribution {
    private final Map<String, String> packageOwners = new HashMap<>();

    ReportAttribution(List<JfrTimeline.ModCpu> mods) {
        Set<String> ambiguousPackages = new HashSet<>();
        for (JfrTimeline.ModCpu mod : mods) {
            if (mod.id().indexOf(':') < 0 || mod.method() == null || platform(mod.method())) {
                continue;
            }
            String root = packageRoot(mod.method());
            if (root != null) {
                String previous = packageOwners.putIfAbsent(root, mod.id());
                if (previous != null && !previous.equals(mod.id())) {
                    ambiguousPackages.add(root);
                }
            }
        }
        ambiguousPackages.forEach(packageOwners::remove);
    }

    String canonical(String id) {
        if (id == null || platform(id)) {
            return null;
        }
        if (id.indexOf(':') >= 0) {
            return id;
        }
        String exact = packageOwners.get(id);
        if (exact != null) {
            return exact;
        }
        String root = packageRoot(id);
        return root == null ? id : packageOwners.getOrDefault(root, id);
    }

    List<JfrTimeline.ModCpu> cpu(List<JfrTimeline.ModCpu> rows) {
        Map<String, CpuAggregate> merged = new LinkedHashMap<>();
        for (JfrTimeline.ModCpu row : rows) {
            String id = canonical(row.id());
            if (id == null) {
                continue;
            }
            CpuAggregate aggregate = merged.computeIfAbsent(id, ignored -> new CpuAggregate());
            aggregate.samples += row.samples();
            aggregate.methods.merge(row.method(), row.samples(), Long::sum);
            for (JfrTimeline.ThreadShare thread : row.threads()) {
                aggregate.threads.merge(thread.thread(), thread.samples(), Long::sum);
            }
        }
        List<JfrTimeline.ModCpu> result = new ArrayList<>(merged.size());
        for (Map.Entry<String, CpuAggregate> entry : merged.entrySet()) {
            CpuAggregate aggregate = entry.getValue();
            String method = aggregate.methods.entrySet().stream()
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
            List<JfrTimeline.ThreadShare> threads = aggregate.threads.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(thread -> new JfrTimeline.ThreadShare(thread.getKey(), thread.getValue())).toList();
            result.add(new JfrTimeline.ModCpu(entry.getKey(), aggregate.samples, method, threads));
        }
        result.sort((a, b) -> Long.compare(b.samples(), a.samples()));
        return result;
    }

    Map<String, Long> counts(Map<String, Long> rows) {
        Map<String, Long> merged = new HashMap<>();
        for (Map.Entry<String, Long> row : rows.entrySet()) {
            String id = canonical(row.getKey());
            if (id != null) {
                merged.merge(id, row.getValue(), Long::sum);
            }
        }
        Map<String, Long> sorted = new LinkedHashMap<>();
        merged.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        return sorted;
    }

    private static boolean platform(String type) {
        return type.startsWith("java.") || type.startsWith("javax.") || type.startsWith("jdk.")
            || type.startsWith("sun.") || type.startsWith("com.sun.");
    }

    private static String packageRoot(String type) {
        int first = type.indexOf('.');
        int second = first < 0 ? -1 : type.indexOf('.', first + 1);
        int third = second < 0 ? -1 : type.indexOf('.', second + 1);
        return third < 0 ? null : type.substring(0, third);
    }

    private static final class CpuAggregate {
        long samples;
        final Map<String, Long> methods = new HashMap<>();
        final Map<String, Long> threads = new HashMap<>();
    }
}
