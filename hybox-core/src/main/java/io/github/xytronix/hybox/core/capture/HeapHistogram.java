package io.github.xytronix.hybox.core.capture;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.management.ObjectName;

import io.github.xytronix.hybox.core.incident.DiagnosticSection;

public final class HeapHistogram {
    private static final int LIMIT = 30;
    private static final Pattern HISTOGRAM_LINE = Pattern.compile("^\\s*\\d+:\\s+(\\d+)\\s+(\\d+)\\s+(\\S+)");
    private static final Pattern TOTAL_LINE = Pattern.compile("(?m)^Total\\s+(\\d+)\\s+(\\d+)\\s*$");

    private HeapHistogram() {
    }

    public static List<DiagnosticSection> appendTo(List<DiagnosticSection> base) {
        Map<String, String> entries = collect();
        if (entries.isEmpty()) {
            return base;
        }
        List<DiagnosticSection> out = new ArrayList<>(base);
        out.add(new DiagnosticSection("Heap histogram", entries));
        return out;
    }

    public record Entry(String className, long instances, long bytes) {}

    public record Snapshot(List<Entry> entries, long totalInstances, long totalBytes) {
        public Snapshot {
            entries = List.copyOf(entries);
        }

        public long shareBase() {
            long listed = entries.stream().mapToLong(Entry::bytes).sum();
            return Math.max(1, Math.max(totalBytes, listed));
        }
    }

    public static Snapshot snapshot(int limit) {
        return snapshot(raw(), limit);
    }

    static Snapshot snapshot(String raw, int limit) {
        List<Entry> entries = new ArrayList<>();
        for (Map.Entry<String, String> entry : parse(raw).entrySet()) {
            if (entries.size() >= limit) {
                break;
            }
            String[] parts = entry.getValue().split(" ");
            entries.add(new Entry(entry.getKey(), Long.parseLong(parts[0]), Long.parseLong(parts[1])));
        }
        long totalInstances = 0;
        long totalBytes = 0;
        if (raw != null) {
            Matcher total = TOTAL_LINE.matcher(raw);
            if (total.find()) {
                totalInstances = Long.parseLong(total.group(1));
                totalBytes = Long.parseLong(total.group(2));
            }
        }
        return new Snapshot(entries, totalInstances, totalBytes);
    }

    private static Map<String, String> collect() {
        return parse(raw());
    }

    private static String raw() {
        try {
            return (String) ManagementFactory.getPlatformMBeanServer().invoke(
                new ObjectName("com.sun.management:type=DiagnosticCommand"),
                "gcClassHistogram",
                new Object[] {null},
                new String[] {String[].class.getName()});
        } catch (Throwable t) {
            return null;
        }
    }

    static Map<String, String> parse(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String line : raw.split("\n")) {
            Matcher m = HISTOGRAM_LINE.matcher(line);
            if (!m.find()) {
                continue;
            }
            out.putIfAbsent(m.group(3), m.group(1) + " " + m.group(2));
            if (out.size() >= LIMIT) {
                break;
            }
        }
        return out;
    }
}
