package io.github.xytronix.hybox.core.report;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.xytronix.hybox.core.health.JfrTimeline;

/** Resolves package fallbacks to the plugin that recorded class-loader ownership or a main class identifies. */
final class ReportAttribution {
    static final String HYINIT = "Hyinit";
    private static final String HYINIT_PACKAGE = "cc.irori.hyinit";
    private final Map<String, String> packageOwners = new HashMap<>();

    ReportAttribution(List<JfrTimeline.ModCpu> mods, Map<String, String> pluginClasses) {
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
        Map<String, String> mainOwners = new HashMap<>();
        Set<String> sharedRoots = new HashSet<>(ambiguousPackages);
        pluginClasses.forEach((key, mainClass) -> {
            String id = key.replaceFirst(" #\\d+$", "");
            String root = mainClass == null || id.indexOf(':') < 0 || platform(mainClass)
                || mainClass.startsWith("com.hypixel.") ? null : packageRoot(mainClass);
            if (root != null) {
                String previous = mainOwners.putIfAbsent(root, id);
                if (previous != null && !previous.equals(id)) {
                    sharedRoots.add(root);
                }
            }
        });
        mainOwners.forEach((root, id) -> {
            if (!sharedRoots.contains(root)) {
                packageOwners.putIfAbsent(root, id);
            }
        });
        packageOwners.put(HYINIT_PACKAGE, HYINIT);
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

    String owner(String className) {
        String root = packageRoot(className);
        return root == null ? null : packageOwners.get(root);
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
            aggregate.self += row.self();
            aggregate.total += row.total();
            if (row.method() != null) {
                aggregate.methods.merge(row.method(), row.samples(), Long::sum);
            }
            if (row.selfMethod() != null) {
                aggregate.selfMethods.merge(row.selfMethod(), row.self(), Long::sum);
            }
            if (row.totalMethod() != null) {
                aggregate.totalMethods.merge(row.totalMethod(), row.total(), Long::sum);
            }
            for (JfrTimeline.ThreadShare thread : row.threads()) {
                aggregate.threads.merge(thread.thread(), thread.samples(), Long::sum);
            }
            for (JfrTimeline.ThreadShare thread : row.selfThreads()) {
                aggregate.selfThreads.merge(thread.thread(), thread.samples(), Long::sum);
            }
        }
        List<JfrTimeline.ModCpu> result = new ArrayList<>(merged.size());
        for (Map.Entry<String, CpuAggregate> entry : merged.entrySet()) {
            CpuAggregate aggregate = entry.getValue();
            result.add(new JfrTimeline.ModCpu(entry.getKey(), aggregate.samples, dominant(aggregate.methods),
                threads(aggregate.threads), aggregate.self, aggregate.total, dominant(aggregate.selfMethods),
                threads(aggregate.selfThreads), dominant(aggregate.totalMethods)));
        }
        result.sort((a, b) -> a.samples() != b.samples() ? Long.compare(b.samples(), a.samples())
            : Long.compare(b.total(), a.total()));
        return result;
    }

    Map<String, JfrTimeline.ModShare> shares(Map<String, JfrTimeline.ModShare> rows) {
        Map<String, JfrTimeline.ModShare> merged = new HashMap<>();
        for (Map.Entry<String, JfrTimeline.ModShare> row : rows.entrySet()) {
            String id = canonical(row.getKey());
            if (id != null) {
                merged.merge(id, row.getValue(), (a, b) -> new JfrTimeline.ModShare(
                    a.samples() + b.samples(), a.self() + b.self(), a.total() + b.total()));
            }
        }
        Map<String, JfrTimeline.ModShare> sorted = new LinkedHashMap<>();
        merged.entrySet().stream()
            .sorted((a, b) -> a.getValue().samples() != b.getValue().samples()
                ? Long.compare(b.getValue().samples(), a.getValue().samples())
                : Long.compare(b.getValue().total(), a.getValue().total()))
            .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        return sorted;
    }

    private static String dominant(Map<String, Long> methods) {
        return methods.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    private static List<JfrTimeline.ThreadShare> threads(Map<String, Long> threads) {
        return threads.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .map(thread -> new JfrTimeline.ThreadShare(thread.getKey(), thread.getValue())).toList();
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
        long self;
        long total;
        final Map<String, Long> methods = new HashMap<>();
        final Map<String, Long> threads = new HashMap<>();
        final Map<String, Long> selfMethods = new HashMap<>();
        final Map<String, Long> selfThreads = new HashMap<>();
        final Map<String, Long> totalMethods = new HashMap<>();
    }
}
