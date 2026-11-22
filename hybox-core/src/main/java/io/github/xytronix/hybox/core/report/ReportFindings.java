package io.github.xytronix.hybox.core.report;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import io.github.xytronix.hybox.core.health.FrameLabels;
import io.github.xytronix.hybox.core.health.HealthSnapshot;
import io.github.xytronix.hybox.core.health.JfrTimeline;
import io.github.xytronix.hybox.core.incident.IncidentReport;
import io.github.xytronix.hybox.core.json.JsonWriter;
import io.github.xytronix.hybox.core.text.Units;

/** Recording-wide diagnosis shared by HTML and in-game reports; spans contain text, never markup. */
record ReportFindings(List<Entry> evidence, List<Entry> steps) {
    private static final double GIB = 1024.0 * 1024 * 1024;
    private static final double MOD_SHARE = 10;
    private static final long MOD_MIN_SAMPLES = 300;

    record Span(String text, String style) {}
    record Entry(List<Span> spans, String section, String label) {}
    private record World(String name, Long samples, boolean stalled) {}
    private record ExceptionSpike(double peak, double average) {}

    static ReportFindings build(IncidentReport report, JfrTimeline timeline, ReportSections sections) {
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
        long spanMs = timeline == null || timeline.start() == null || timeline.end() == null ? 0
            : Math.max(0, timeline.end().toEpochMilli() - timeline.start().toEpochMilli());
        List<Entry> evidence = new ArrayList<>();
        boolean busyWorld = topWorld != null && total > 0 && Math.round(100.0 * topWorld.samples() / total) > 50;
        if (busyWorld) {
            evidence.add(entry("cpu", "CPU & load", text("World "), code(worldLabel(topWorld.name())),
                text(" used "), bold(percent(topWorld.samples(), total, 0) + "%"),
                text(" of the CPU samples, more than any other world and above the 50% threshold.")));
        }
        if (timeline != null && !timeline.hotMethods().isEmpty() && total > 0) {
            JfrTimeline.HotMethod method = timeline.hotMethods().getFirst();
            double share = 100.0 * method.samples() / total;
            if (share >= 10) {
                String mixin = FrameLabels.labelOf(method.method());
                evidence.add(entry("methods", "Hot methods", text("The most sampled method was "),
                    code(leafName(method.method())), text((mixin == null ? "" : " (" + mixin + ")") + ", in "),
                    bold(fixed(share, 1) + "%"), text(" of CPU samples, above the 10% threshold.")));
            }
        }
        ReportAttribution attribution = timeline == null ? null
            : new ReportAttribution(timeline.cpuByMod(), sections.pluginClasses());
        Map<String, String> busyMods = new LinkedHashMap<>();
        if (attribution != null && total >= MOD_MIN_SAMPLES) {
            List<JfrTimeline.ModCpu> mods = new ArrayList<>(attribution.cpu(timeline.cpuByMod()));
            mods.sort((a, b) -> Long.compare(b.self(), a.self()));
            for (JfrTimeline.ModCpu mod : mods) {
                double share = 100.0 * mod.self() / total;
                if (share >= MOD_SHARE && nameable(mod.id())) {
                    evidence.add(entry("cpu", "CPU & load", bold(modName(mod.id())),
                        text(possessive(mod.id()) + " own code ran in "), bold(fixed(share, 0) + "%"),
                        text(" of CPU samples, above the 10% threshold.")));
                    busyMods.putIfAbsent(mod.id(), "cpu");
                }
            }
        }
        if (stalled != null && stalled.samples() != null && total > 0 && !(busyWorld && stalled == topWorld)) {
            evidence.add(entry("worlds", "Worlds", text("The stalled world "), code(worldLabel(stalled.name())),
                text(" used "), bold(percent(stalled.samples(), total, 0) + "%"),
                text(worlds.size() > 1 ? " of the CPU samples, #" + stalledRank + " of " + worlds.size() + " worlds by CPU."
                    : " of the CPU samples.")));
        }
        JfrTimeline.GcStats gc = timeline == null ? null : timeline.gc();
        if (gc != null) {
            double gcPct = spanMs > 0 ? 100.0 * gc.totalPauseMs() / spanMs : 0;
            boolean longPause = gc.maxPauseMs() >= 50;
            boolean gcTime = gcPct >= 5;
            List<Span> spans = new ArrayList<>(5);
            if (longPause) {
                spans.add(text("GC paused the server for up to "));
                spans.add(bold(fixed(gc.maxPauseMs(), 0) + " ms"));
                spans.add(text(", above the 50 ms threshold." + (gcTime ? " " : "")));
            }
            if (gcTime) {
                spans.add(bold(fixed(gcPct, 0) + "%"));
                spans.add(text(" of the time went to GC pauses, above the 5% threshold."));
            }
            if (!spans.isEmpty()) {
                evidence.add(new Entry(List.copyOf(spans), "memory", "Memory & GC"));
            }
        }
        if (deadlocked > 0) {
            evidence.add(entry("states", "Thread states", text("Java detected a deadlock between "),
                bold(number(deadlocked)), text(" thread" + (deadlocked == 1 ? "" : "s") + ".")));
        }
        if (blocked != null && blocked > 0) {
            evidence.add(entry("states", "Thread states", bold(number(blocked)),
                text(blocked == 1 ? " thread was waiting for a lock at capture." : " threads were waiting for a lock at capture.")));
        }
        JfrTimeline.CpuStats cpu = timeline == null ? null : timeline.cpu();
        if (cpu != null && Double.isFinite(cpu.machAvg()) && cpu.machAvg() >= 90) {
            evidence.add(entry("cpu", "CPU & load", text("The machine's CPU averaged "),
                bold(fixed(cpu.machAvg(), 0) + "%"), text(", above the 90% threshold."
                    + (Double.isFinite(cpu.jvmAvg()) && cpu.jvmAvg() >= 0
                        ? " The server itself used " + fixed(cpu.jvmAvg(), 0) + "%." : ""))));
        }
        JfrTimeline.Container container = timeline == null ? null : timeline.container();
        boolean containerLimit = container != null && container.memLimit() > 0;
        long memoryLimit = containerLimit ? container.memLimit()
            : snapshot != null && snapshot.system() != null ? snapshot.system().totalRamBytes() : 0;
        long[] rss = timeline == null ? null : timeline.rssSeries();
        if (rss != null && rss.length > 0 && memoryLimit > 0) {
            long rssPeak = Long.MIN_VALUE;
            for (long value : rss) {
                rssPeak = Math.max(rssPeak, value);
            }
            double rssPct = 100.0 * rssPeak / memoryLimit;
            if (rssPct >= 80) {
                evidence.add(entry("memory", "Memory & GC", text("Server memory (RSS) peaked at "),
                    bold(fixed(rssPeak / GIB, 1) + " of " + fixed(memoryLimit / GIB, 1) + " GiB"),
                    text(" of " + (containerLimit ? "the container limit" : "machine RAM") + " ("
                        + fixed(rssPct, 0) + "%), above the 80% threshold.")));
            }
        }
        Long heapMax = ReportSystemWriter.heapMax(snapshot, timeline);
        Long heapPeak = heapPeak(snapshot, timeline);
        if (heapMax != null && heapMax > 0 && heapPeak != null && heapPeak >= 0.9 * heapMax) {
            evidence.add(entry("memory", "Memory & GC", text("Heap peaked at "),
                bold(fixed(heapPeak / GIB, 1) + " of " + fixed(heapMax / GIB, 1) + " GiB"),
                text(" (" + fixed(100.0 * heapPeak / heapMax, 0) + "%), above the 90% threshold.")));
        }
        ExceptionSpike spike = exceptionSpike(timeline);
        if (spike != null) {
            int buckets = timeline.exceptionsSeries().length;
            String window = spanMs > 0
                ? " in the " + Units.elapsed(Math.round((double) spanMs / buckets)) + " before the incident, "
                : " right before the incident, ";
            evidence.add(entry("metrics", "Key metrics", bold(fmt(spike.peak())),
                text(" exception" + (spike.peak() == 1 ? "" : "s") + window),
                bold(fixed(spike.peak() / spike.average(), 0) + " times"),
                text(" the average, above the 5 times threshold.")));
        }
        JfrTimeline.Alloc alloc = timeline == null ? null : timeline.alloc();
        if (alloc != null && !alloc.bySubsystem().isEmpty()) {
            Map.Entry<String, Long> lead = alloc.bySubsystem().entrySet().iterator().next();
            if (!lead.getKey().startsWith("Other")) {
                evidence.add(entry("memory", "Memory & GC", bold(lead.getKey()),
                    text(" allocated the most memory: "), bold(fmtBytes(lead.getValue())),
                    text(alloc.totalBytes() > 0 ? " of " + fmtBytes(alloc.totalBytes()) + "." : ".")));
            }
        }
        if (attribution != null && alloc != null && alloc.totalBytes() > 0 && alloc.samples() >= MOD_MIN_SAMPLES) {
            List<Map.Entry<String, JfrTimeline.ModShare>> mods = new ArrayList<>(attribution.shares(alloc.byMod()).entrySet());
            mods.sort((a, b) -> Long.compare(b.getValue().self(), a.getValue().self()));
            for (Map.Entry<String, JfrTimeline.ModShare> mod : mods) {
                double share = 100.0 * mod.getValue().self() / alloc.totalBytes();
                if (share >= MOD_SHARE && nameable(mod.getKey())) {
                    evidence.add(entry("memory", "Memory & GC", bold(modName(mod.getKey())),
                        text(possessive(mod.getKey()) + " own code allocated "), bold(fixed(share, 0) + "%"),
                        text(" of sampled memory, above the 10% threshold.")));
                    busyMods.putIfAbsent(mod.getKey(), "memory");
                }
            }
        }
        if (containerLimit && heapMax != null && heapMax > 0.9 * container.memLimit()) {
            evidence.add(entry("system", "System & hardware", text("The heap limit (-Xmx "),
                bold(fixed(heapMax / GIB, 1) + " GiB"), text(") is "),
                bold(fixed(100.0 * heapMax / container.memLimit(), 0) + "%"),
                text(" of the container's "), bold(fixed(container.memLimit() / GIB, 1) + " GiB"),
                text(", above the 90% threshold. The container can kill the server. "
                    + "Set -Xmx to about 75% of the container limit.")));
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
            evidence.add(entry("plugins", "Plugins", bold(number(incompatible)), text(incompatible == 1
                ? " mod was built for another Hytale version." : " mods were built for another Hytale version.")));
        }
        MixinSection.Parsed mixins = MixinSection.parse(sections.mixins());
        if (mixins != null && !mixins.problems().isEmpty()) {
            long errors = mixins.errors();
            long warnings = mixins.warnings();
            List<Span> spans = new ArrayList<>(4);
            if (errors > 0) {
                spans.add(bold(number(errors)));
                spans.add(text(" Mixin error" + (errors == 1 ? "" : "s") + (warnings > 0 ? " and " : "")));
            }
            if (warnings > 0) {
                spans.add(bold(number(warnings)));
                spans.add(text(" Mixin warning" + (warnings == 1 ? "" : "s")));
            }
            spans.add(text(" since startup."));
            evidence.add(new Entry(List.copyOf(spans), "mixins", "Mixins"));
        }

        List<Entry> steps = new ArrayList<>(3);
        if (deadlocked > 0) {
            steps.add(entry("states", "Thread states", text(Units.count(deadlocked, "thread")
                + (deadlocked == 1 ? " is" : " are") + " deadlocked and will not recover. Restart the server. "),
                text("Thread states"), text(deadlocked == 1 ? " names the thread and what it holds."
                    : deadlocked == 2 ? " names both threads and what each one holds."
                    : " names the threads and what each one holds.")));
        } else if (blocked != null && blocked > 0) {
            steps.add(entry("states", "Thread states", text(Units.count(blocked, "thread")
                + (blocked == 1 ? " was" : " were") + " waiting for a lock. "),
                text("Thread states"), text(blocked == 1 ? " shows what it waited for." : " shows what each one waited for.")));
        }
        if (busyWorld) {
            steps.add(entry("flame", "Flame graph", text("See what "), code("WorldThread - " + topWorld.name()),
                text(" ran: open it in the "), text("Flame graph"), text(".")));
        }
        for (Map.Entry<String, String> mod : busyMods.entrySet()) {
            if (steps.size() >= 3) {
                break;
            }
            boolean cpuBusy = "cpu".equals(mod.getValue());
            steps.add(entry(mod.getValue(), cpuBusy ? "CPU & load" : "Memory & GC", text(modName(mod.getKey()) + possessive(mod.getKey())
                + " own code is busy. Check its settings or report it to its author with this bundle.")));
        }
        if (timeline != null && total > 0 && steps.size() < 3) {
            for (Map.Entry<String, Long> sub : timeline.subsystems().entrySet()) {
                if (!sub.getKey().startsWith("Other")) {
                    steps.add(entry("methods", "Hot methods", text("Most CPU went to " + sub.getKey() + " ("
                        + percent(sub.getValue(), total, 1) + "%). "), text("Hot methods"), text(" lists the methods.")));
                    break;
                }
            }
        }
        int conflicts = mixins == null ? 0 : mixins.versions() == null ? mixins.legacyConflicts().size()
            : mixins.overlaps().size();
        if (conflicts > 0 && steps.size() < 3) {
            String changed = mixins.versions() == null
                ? conflicts + (conflicts == 1 ? " class is" : " classes are")
                : conflicts + (conflicts == 1 ? " method is" : " methods are");
            steps.add(entry("mixins", "Mixins", text(changed + " changed by more than one mod. "
                + "If the problem started after you added a mod, test without it. "),
                text("Mixins"), text(conflicts == 1 ? " lists it." : " lists them.")));
        }
        if (spike != null && steps.size() < 3) {
            steps.add(entry("log", "Server log", text("Exceptions spiked before the incident. "),
                text("Server log"), text(" shows the errors from that time.")));
        }
        return new ReportFindings(List.copyOf(evidence), List.copyOf(steps));
    }

    void write(JsonWriter json) throws IOException {
        json.name("findings").beginObject();
        writeEntries(json, "evidence", evidence);
        writeEntries(json, "steps", steps);
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

    private static boolean nameable(String id) {
        return !id.startsWith("com.hypixel.") && !id.startsWith("Hytale:") && !id.equals(JfrTimeline.CAPTURE_MOD);
    }

    private static String modName(String id) {
        int colon = id.indexOf(':');
        return colon >= 0 && colon < id.length() - 1 ? id.substring(colon + 1) : id;
    }

    private static String possessive(String id) {
        return modName(id).endsWith("s") ? "'" : "'s";
    }

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

    static Long heapPeak(HealthSnapshot snapshot, JfrTimeline timeline) {
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

    private static String leafName(String method) {
        int last = method.lastIndexOf('.');
        int previous = last < 0 ? -1 : method.lastIndexOf('.', last - 1);
        int synthetic = method.indexOf('$', last + 1);
        return method.substring(previous + 1, synthetic < 0 ? method.length() : synthetic);
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
