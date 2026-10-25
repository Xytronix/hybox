package io.github.xytronix.hybox.core.report;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.xytronix.hybox.core.health.HealthSnapshot;
import io.github.xytronix.hybox.core.health.JfrTimeline;
import io.github.xytronix.hybox.core.incident.IncidentReport;
import io.github.xytronix.hybox.core.json.JsonWriter;

/** Recording-wide diagnosis shared by HTML and in-game reports; spans contain text, never markup. */
record ReportFindings(List<Span> intro, List<Entry> evidence, String severity, String direction,
                      List<Entry> steps, List<String> tags, String caveat) {
    private static final double GIB = 1024.0 * 1024 * 1024;
    private static final double GB = 1e9;
    private static final String CAVEAT = "Correlate CPU share, blocked threads and short GC pauses with timing and stacks.";

    record Span(String text, String style) {}
    record Entry(List<Span> spans, String section, String label) {}
    private record World(String name, Long samples, boolean stalled) {}
    private record ExceptionSpike(double peak, double average) {}

    static ReportFindings build(IncidentReport report, JfrTimeline timeline, ReportSections sections,
                                Long stallMs, String created) {
        HealthSnapshot snapshot = report.snapshot();
        long total = timeline == null ? 0 : Math.max(0, timeline.totalSamples());
        List<World> worlds = worlds(report, timeline);
        World topWorld = null;
        World stalled = null;
        int stalledIndex = -1;
        for (int i = 0; i < worlds.size(); i++) {
            World world = worlds.get(i);
            if (world.samples() != null && world.samples() > 0
                && (topWorld == null || world.samples() > topWorld.samples())) {
                topWorld = world;
            }
            if (stalled == null && world.stalled()) {
                stalled = world;
                stalledIndex = i;
            }
        }
        int stalledRank = 0;
        if (stalled != null) {
            stalledRank = 1;
            long stalledSamples = stalled.samples() == null ? 0 : stalled.samples();
            for (int i = 0; i < worlds.size(); i++) {
                Long samples = worlds.get(i).samples();
                long value = samples == null ? 0 : samples;
                if (value > stalledSamples || (value == stalledSamples && i < stalledIndex)) {
                    stalledRank++;
                }
            }
        }

        HealthSnapshot.Threads threads = snapshot == null ? null : snapshot.threads();
        Integer blocked = threads == null || threads.byState().isEmpty()
            ? null : threads.byState().getOrDefault("BLOCKED", 0);
        int deadlocked = threads == null ? 0 : threads.deadlocked().size();
        List<Entry> evidence = new ArrayList<>();
        if (topWorld != null && total > 0) {
            evidence.add(entry("cpu", "CPU & load", code("WorldThread - " + topWorld.name()),
                text(" produced "), bold(percent(topWorld.samples(), total, 1) + "%"),
                text(" of " + fmt(total) + " CPU samples, the largest world-thread share.")));
        }
        if (timeline != null && !timeline.hotMethods().isEmpty() && total > 0) {
            JfrTimeline.HotMethod method = timeline.hotMethods().getFirst();
            evidence.add(entry("methods", "hot methods", text("Top frame "), code(leafName(method.method())),
                text(" holds "), bold(percent(method.samples(), total, 2) + "%"),
                text(" of samples (" + fmt(method.samples()) + " hits).")));
        }
        if (stalled != null && stalled.samples() != null && total > 0) {
            evidence.add(entry("worlds", "worlds", text("The stalled world "), code(worldLabel(stalled.name())),
                text(" drew "), bold(percent(stalled.samples(), total, 1) + "%"),
                text(" of samples, #" + stalledRank + " of " + worlds.size() + " by CPU.")));
        }
        JfrTimeline.GcStats gc = timeline == null ? null : timeline.gc();
        if (gc != null) {
            evidence.add(entry("memory", "memory & GC", text(gcName(timeline.gcConfig()) + " ran "),
                bold(number(gc.collections())), text(" collections, longest pause "),
                bold(number(gc.maxPauseMs()) + " ms"), text(" (" + number(gc.totalPauseMs()) + " ms total).")));
        }
        if (deadlocked > 0) {
            evidence.add(entry("states", "deadlocked threads", text("The JVM detected a deadlock involving "),
                bold(number(deadlocked)), text(" thread" + (deadlocked == 1 ? "" : "s") + ".")));
        }
        if (blocked != null) {
            evidence.add(entry("states", "thread states", bold(number(blocked)),
                text(" thread(s) were BLOCKED at capture.")));
        }
        JfrTimeline.CpuStats cpu = timeline == null ? null : timeline.cpu();
        if (cpu != null && Double.isFinite(cpu.machMax())) {
            evidence.add(entry("cpu", "CPU & load", text("Machine CPU peaked at "),
                bold(fixed(cpu.machMax(), 1) + "%"), text((Double.isFinite(cpu.machAvg())
                    ? " (average " + fixed(cpu.machAvg(), 1) + "%)" : "") + ".")));
        }
        JfrTimeline.Container container = timeline == null ? null : timeline.container();
        boolean containerLimit = container != null && container.memLimit() > 0;
        long memoryLimit = containerLimit ? container.memLimit()
            : snapshot != null && snapshot.system() != null ? snapshot.system().totalRamBytes() : 0;
        long[] rss = timeline == null ? null : timeline.rssSeries();
        Long heapMax = ReportSystemWriter.heapMax(snapshot, timeline);
        if (rss != null && memoryLimit > 0) {
            long last = rss[rss.length - 1];
            List<Span> spans = new ArrayList<>();
            spans.add(text("Process RSS "));
            spans.add(bold(fixed(last / GB, 1) + " GB"));
            spans.add(text(" of " + fixed(memoryLimit / GB, 1) + " GB "
                + (containerLimit ? "container limit" : "machine RAM") + " ("
                + fixed(100.0 * last / memoryLimit, 0) + "%)"));
            Long heapPeak = heapPeak(snapshot, timeline);
            if (heapMax != null && heapPeak != null) {
                spans.add(text("; heap peaked at "));
                spans.add(bold(fixed(heapPeak / GIB, 1)));
                spans.add(text(" of " + fixed(heapMax / GIB, 1) + " GiB"));
            }
            spans.add(text("."));
            evidence.add(new Entry(List.copyOf(spans), "memory", "memory & GC"));
        }
        ExceptionSpike spike = exceptionSpike(timeline);
        if (spike != null) {
            evidence.add(entry("metrics", "key metrics", bold(fmt(spike.peak())),
                text(" exceptions/interval right before the incident, "),
                bold(fixed(spike.peak() / spike.average(), 0) + "×"),
                text(" the window average of " + fixed(spike.average(), 1) + ".")));
        }
        JfrTimeline.Alloc alloc = timeline == null ? null : timeline.alloc();
        if (alloc != null && !alloc.bySubsystem().isEmpty()) {
            Map.Entry<String, Long> lead = alloc.bySubsystem().entrySet().iterator().next();
            if (!lead.getKey().startsWith("Other")) {
                evidence.add(entry("memory", "allocation", bold(lead.getKey()),
                    text(" leads allocation pressure at "), bold(fmtBytes(lead.getValue())), text(" sampled.")));
            }
        }
        if (containerLimit && heapMax != null && heapMax > 0.9 * container.memLimit()) {
            evidence.add(entry("system", "system", text("Heap -Xmx "), bold(fixed(heapMax / GIB, 1) + " GiB"),
                text(" is at or above the container memory limit of "),
                bold(fixed(container.memLimit() / GIB, 1) + " GiB"),
                text("; the JVM can be OOM-killed before GC reacts.")));
        }
        int incompatible = 0;
        if (sections.plugins() != null) {
            for (String value : sections.plugins().values()) {
                String[] parts = value.split(" @ ", 3);
                if (parts.length > 2 && "INCOMPATIBLE".equals(parts[2])) {
                    incompatible++;
                }
            }
        }
        if (incompatible > 0) {
            evidence.add(entry("plugins", "plugins", bold(number(incompatible)), text(" installed mod"
                + (incompatible == 1 ? " declares" : "s declare")
                + " a server version range that excludes the running server.")));
        }

        String direction = deadlocked > 0 ? "Inspect the detected deadlock and lock ownership"
            : topWorld != null ? "Inspect the busiest world thread alongside tick timing"
            : "Correlate stacks and timing in recording.jfr using JDK Mission Control";
        List<Entry> steps = new ArrayList<>(3);
        if (deadlocked > 0) {
            steps.add(entry("states", "thread states", text("Inspect the " + deadlocked
                + " detected deadlocked thread" + (deadlocked == 1 ? "" : "s") + " and their lock owners ("),
                text("thread states"), text(")")));
        } else if (blocked != null && blocked > 0) {
            steps.add(entry("states", "thread states", text("Investigate lock contention in the " + blocked
                + " blocked thread(s) ("), text("thread states"), text(")")));
        }
        if (topWorld != null) {
            steps.add(entry(null, null, text("Profile "), code("WorldThread - " + topWorld.name()),
                text(" in JDK Mission Control (recording.jfr)")));
        }
        if (timeline != null && total > 0 && steps.size() < 3) {
            for (Map.Entry<String, Long> sub : timeline.subsystems().entrySet()) {
                if (!sub.getKey().startsWith("Other")) {
                    steps.add(entry("methods", "hot methods", text("Review the " + sub.getKey() + " hot path: "
                        + fmt(sub.getValue()) + " samples, " + percent(sub.getValue(), total, 1)
                        + "% of samples ("), text("hot methods"), text(")")));
                    break;
                }
            }
        }
        int conflicts = sections.mixins() == null ? 0
            : (int) sections.mixins().keySet().stream().filter(key -> key.startsWith("Conflict: ")).count();
        if (conflicts > 0 && steps.size() < 3) {
            steps.add(entry("config", "mixins", text("Inspect the " + conflicts + " class"
                + (conflicts == 1 ? "" : "es") + " patched by multiple mixin configs ("), text("mixins"), text(")")));
        }
        if (spike != null && steps.size() < 3) {
            steps.add(entry("log", "server log", text("Trace the exception burst in the "),
                text("server log"), text(" tail")));
        }
        List<Span> intro;
        String worldShort = ReportHtml.worldShort(report.meta().world());
        if (stallMs != null && worldShort != null && !worldShort.isEmpty()) {
            intro = List.of(code(report.meta().trigger()), text(" fired after "), bold(worldShort),
                text(" missed its heartbeat by "), bold(fmt(stallMs) + " ms"),
                text("."));
        } else {
            intro = List.of(code(report.meta().trigger()), text(" fired at " + created + "."));
        }
        List<String> tags = new ArrayList<>(2);
        if (gc != null && Double.isFinite(gc.maxPauseMs())) {
            tags.add("Longest recorded GC pause: " + number(gc.maxPauseMs()) + " ms");
        }
        if (deadlocked > 0) tags.add("Deadlock detected");
        return new ReportFindings(intro, List.copyOf(evidence), report.meta().severity().name(), direction,
            List.copyOf(steps), List.copyOf(tags), CAVEAT);
    }

    void write(JsonWriter json) throws IOException {
        json.name("findings").beginObject();
        json.name("intro");
        writeSpans(json, intro);
        writeEntries(json, "evidence", evidence);
        json.name("severity").value(severity);
        json.name("direction").value(direction);
        writeEntries(json, "steps", steps);
        json.name("tags").beginArray();
        for (String tag : tags) {
            json.value(tag);
        }
        json.endArray();
        json.name("caveat").value(caveat);
        json.endObject();
    }

    private static void writeEntries(JsonWriter json, String name, List<Entry> entries) throws IOException {
        json.name(name).beginArray();
        for (Entry entry : entries) {
            json.beginObject();
            json.name("spans");
            writeSpans(json, entry.spans());
            json.name("section").value(entry.section());
            json.name("label").value(entry.label());
            json.endObject();
        }
        json.endArray();
    }

    private static void writeSpans(JsonWriter json, List<Span> spans) throws IOException {
        json.beginArray();
        for (Span span : spans) {
            json.beginArray();
            json.value(span.text());
            json.value(span.style());
            json.endArray();
        }
        json.endArray();
    }

    private static Entry entry(String section, String label, Span... spans) {
        return new Entry(List.of(spans), section, label);
    }

    private static Span text(String value) { return new Span(value, ""); }
    private static Span bold(String value) { return new Span(value, "b"); }
    private static Span code(String value) { return new Span(value, "code"); }

    private static List<World> worlds(IncidentReport report, JfrTimeline timeline) {
        Map<String, Long> samples = timeline == null ? Map.of() : timeline.worldSamples();
        List<World> worlds = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        if (report.snapshot() != null) {
            for (HealthSnapshot.World world : report.snapshot().worlds()) {
                seen.add(ReportActivityWriter.worldKey(world.name()));
                worlds.add(new World(world.name(), ReportActivityWriter.samplesForWorld(samples, world.name()),
                    world.name().equals(report.meta().world())));
            }
        }
        for (Map.Entry<String, Long> world : samples.entrySet()) {
            if (seen.add(ReportActivityWriter.worldKey(world.getKey()))) {
                worlds.add(new World(world.getKey(), world.getValue(), world.getKey().equals(report.meta().world())));
            }
        }
        return worlds;
    }

    private static Long heapPeak(HealthSnapshot snapshot, JfrTimeline timeline) {
        long[] heap = timeline == null ? null : timeline.heapSeries();
        if (heap != null) {
            long peak = Long.MIN_VALUE;
            for (long value : heap) {
                peak = Math.max(peak, value);
            }
            return peak;
        }
        return snapshot != null && snapshot.memory() != null && snapshot.memory().heapUsed() > 0
            ? snapshot.memory().heapUsed() : null;
    }

    private static ExceptionSpike exceptionSpike(JfrTimeline timeline) {
        double[] series = timeline == null ? null : timeline.exceptionsSeries();
        if (series == null || series.length < 10) {
            return null;
        }
        int maxIndex = 0;
        double maximum = ReportJson.round2(series[0]);
        double sum = 0;
        for (int i = 0; i < series.length; i++) {
            double value = ReportJson.round2(series[i]);
            sum += value;
            if (value > maximum) {
                maximum = value;
                maxIndex = i;
            }
        }
        double average = sum / series.length;
        return average > 0 && maximum > 5 * average && maxIndex >= (int) Math.floor(series.length * 0.9)
            ? new ExceptionSpike(maximum, average) : null;
    }

    private static String gcName(JfrTimeline.GcConfig config) {
        if (config == null) {
            return "GC";
        }
        String young = config.young();
        String old = config.old();
        String combined = ((young == null ? "" : young) + " " + (old == null ? "" : old)).toLowerCase(Locale.ROOT);
        if (combined.contains("shenandoah")) return "Shenandoah";
        if (combined.contains("g1")) return "G1";
        if (combined.contains("z ") || combined.contains("zgc") || combined.trim().startsWith("z")) return "ZGC";
        if (combined.contains("parallel")) return "Parallel";
        if (combined.contains("serial")) return "Serial";
        boolean hasYoung = young != null && !young.isEmpty();
        boolean hasOld = old != null && !old.isEmpty();
        return hasYoung && hasOld ? young + " + " + old : hasYoung ? young : hasOld ? old : "GC";
    }

    private static String leafName(String method) {
        int last = method.lastIndexOf('.');
        int previous = last < 0 ? -1 : method.lastIndexOf('.', last - 1);
        return previous < 0 ? method : method.substring(previous + 1);
    }

    private static String worldLabel(String name) {
        return (name.startsWith("instance-") ? name.substring("instance-".length()) : name).replace('_', ' ');
    }

    private static String percent(long numerator, long denominator, int digits) {
        return fixed(100.0 * numerator / denominator, digits);
    }

    // BigDecimal(double), unlike decimal printf formatting, preserves JavaScript toFixed's binary ties.
    static String fixed(double value, int digits) {
        if (!Double.isFinite(value) || Math.abs(value) >= 1e21) {
            return number(value);
        }
        String result = new BigDecimal(value).setScale(digits, RoundingMode.HALF_UP).toPlainString();
        return value < 0 && result.charAt(0) != '-' ? "-" + result : result;
    }

    private static String number(double value) {
        if (value == 0) return "0";
        if (!Double.isFinite(value)) return "null";
        BigDecimal decimal = BigDecimal.valueOf(value).stripTrailingZeros();
        if (Math.abs(value) >= 1e-6 && Math.abs(value) < 1e21) {
            return decimal.toPlainString();
        }
        return decimal.toString().replace("E", "e");
    }

    private static String fmt(double value) {
        String decimal = new BigDecimal(value).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        int point = decimal.indexOf('.');
        int end = point < 0 ? decimal.length() : point;
        int start = decimal.charAt(0) == '-' ? 1 : 0;
        StringBuilder grouped = new StringBuilder(decimal.length() + (end - start) / 3);
        for (int i = 0; i < decimal.length(); i++) {
            if (i > start && i < end && (end - i) % 3 == 0) grouped.append(',');
            grouped.append(decimal.charAt(i));
        }
        return grouped.toString();
    }

    private static String fmtBytes(long bytes) {
        if (bytes < 0) return "n/a";
        if (bytes < 1024) return number(bytes) + " B";
        String[] units = {"KiB", "MiB", "GiB", "TiB"};
        double value = bytes;
        int unit = -1;
        do {
            value /= 1024;
            unit++;
        } while (value >= 1024 && unit < units.length - 1);
        return fixed(value, 1) + " " + units[unit];
    }
}
