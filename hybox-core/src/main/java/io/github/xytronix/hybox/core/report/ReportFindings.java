package io.github.xytronix.hybox.core.report;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.github.xytronix.hybox.core.health.FrameLabels;
import io.github.xytronix.hybox.core.health.HealthSnapshot;
import io.github.xytronix.hybox.core.health.JfrSamples;
import io.github.xytronix.hybox.core.health.JfrTimeline;
import io.github.xytronix.hybox.core.incident.DiagnosticSection;
import io.github.xytronix.hybox.core.incident.IncidentReport;
import io.github.xytronix.hybox.core.json.JsonWriter;
import io.github.xytronix.hybox.core.text.Units;

record ReportFindings(String kind, Entry cause, String confidence, List<Entry> why, List<Entry> steps,
                      List<Entry> other, List<ReportTerms.Term> terms) {
    private static final double GIB = 1024.0 * 1024 * 1024;
    static final double MOD_SHARE = 10;
    static final long MIN_SAMPLES = 300;
    private static final double GC_PAUSE_MS = 50;
    private static final double GC_TIME_PCT = 5;
    private static final double GC_CAUSE_SHARE = 0.5;
    private static final double CLEAR_SHARE = 0.9;
    private static final long STALL_SLACK_MS = 1000;
    private static final long SWAP_BYTES = 256L * 1024 * 1024;
    private static final double LOAD_PER_CPU = 2;
    private static final double SLOW_FILE_MS = 250;
    private static final double DISK_FREE_PCT = 5;
    private static final double DISK_FREE_GIB = 2;
    private static final double TPS_SHARE = 0.9;
    private static final double WORLD_SHARE = 50;
    private static final double METHOD_SHARE = 10;
    private static final double MACHINE_CPU_PCT = 90;
    private static final double RSS_PCT = 80;
    private static final double HEAP_PCT = 90;
    private static final double CONTAINER_HEAP_SHARE = 0.9;
    private static final double EXCEPTION_SPIKE = 5;
    private static final double TICK_BUDGET_SHARE = 0.3;
    private static final double CHUNK_SPIKE = 5;
    private static final int BUSY_MIN_SAMPLES = 20;
    private static final double BUSY_SHARE = 0.5;
    private static final double LEAK_RISE = 0.1;
    private static final int LEAK_MIN_GCS = 6;
    private static final double LEAK_FLOOR = 0.5;
    private static final long DENSITY_BEFORE_MS = 30_000;
    private static final double DENSITY_DROP = 0.5;
    private static final double SPIKE_RATIO = 20;
    private static final Map<String, String[]> SYSTEM_FAMILIES = Map.of(
        "WorldConfigSaveSystem", new String[] {"world config save", "Check the disk speed: the world thread of ",
            " waits for each save of its config and resources to finish.", "memory", "Slow file operations"},
        "EntityModel", new String[] {"entity model updates", "Check how many entities are near players in ",
            ": this system sends their model changes to every player who can see them.", "metrics", "Key metrics"},
        "BehaviourTickSystem", new String[] {"NPC behaviour", "Check how many NPCs are loaded in ",
            ": this system runs their behaviour every tick.", "metrics", "Key metrics"},
        "SteeringSystem", new String[] {"NPC movement", "Check how many NPCs are loaded in ",
            ": this system steers their movement every tick.", "metrics", "Key metrics"});
    private static final double XMX_GROWTH = 1.5;
    private static final double RAM_HEADROOM_GIB = 1;
    private static final int MAX_WHY = 2;
    private static final int MAX_STEPS = 3;
    private static final int MAX_NOTABLE = 3;
    static final String CAUSE = "cause";
    static final String NOTABLE = "notable";
    static final String CLEAR = "clear";
    static final String LIKELY = "likely";
    static final String UNCLEAR = "unclear";

    record Span(String text, String style, String term) {
        Span(String text, String style) {
            this(text, style, null);
        }
    }

    record Entry(List<Span> spans, String section, String label, String hover) {
        String text() {
            StringBuilder text = new StringBuilder();
            spans.forEach(span -> text.append(span.text()));
            return text.toString();
        }
    }

    private record Signal(Entry entry, String brief) {}
    private record World(String name, Long samples, boolean stalled, int players) {}
    private record ExceptionSpike(double peak, double average) {}
    private record GcOverlap(boolean stall, double targetMs, double longestMs, int count, double share, String cause) {}
    private record Cause(Entry entry, String confidence, Set<String> used, List<Entry> why, List<Entry> steps) {}
    private record Window(long from, long to, long ms) {}

    static ReportFindings build(IncidentReport report, JfrTimeline timeline, ReportSections sections,
                                ReportHistory history) {
        Facts facts = new Facts(report, timeline, sections);
        Map<String, Signal> signals = facts.signals(history);
        String trigger = report.meta().trigger();
        Cause cause = switch (trigger == null ? "" : trigger) {
            case "HEARTBEAT_STALL", "DEADLOCK" -> facts.freeze(signals);
            case "TICK_DEGRADED", "CPU_SATURATION" -> facts.ticks(signals);
            case "GC_PRESSURE", "HEAP_PRESSURE" -> facts.memory(signals);
            default -> null;
        };
        List<Entry> why = new ArrayList<>();
        List<Entry> steps = new ArrayList<>();
        Set<String> used = new HashSet<>();
        if (cause != null) {
            used.addAll(cause.used());
            for (Entry line : cause.why()) {
                if (why.size() < MAX_WHY && line != null) {
                    why.add(line);
                }
            }
            Set<String> said = new HashSet<>();
            said.add(cause.entry().text());
            why.forEach(line -> said.add(line.text()));
            for (Entry step : cause.steps()) {
                if (steps.size() < MAX_STEPS && step != null && said.add(step.text())) {
                    steps.add(step);
                }
            }
        } else {
            for (Map.Entry<String, Signal> signal : signals.entrySet()) {
                if (why.size() >= MAX_NOTABLE) {
                    break;
                }
                why.add(signal.getValue().entry());
                used.add(signal.getKey());
            }
        }
        Set<String> shown = new HashSet<>();
        why.forEach(line -> shown.add(line.text()));
        List<Entry> other = new ArrayList<>();
        for (Map.Entry<String, Signal> signal : signals.entrySet()) {
            Entry line = signal.getValue().entry();
            if (!used.contains(signal.getKey()) && shown.add(line.text())) {
                other.add(line);
            }
        }
        List<List<Span>> lines = new ArrayList<>();
        if (cause != null) {
            lines.add(new ArrayList<>(cause.entry().spans()));
        }
        for (List<Entry> group : List.of(why, steps, other)) {
            group.forEach(line -> lines.add(new ArrayList<>(line.spans())));
        }
        List<ReportTerms.Term> terms = ReportTerms.tag(lines);
        int at = 0;
        Entry causeLine = null;
        if (cause != null) {
            causeLine = respan(cause.entry(), lines.get(at++));
        }
        List<List<Entry>> tagged = new ArrayList<>(3);
        for (List<Entry> group : List.of(why, steps, other)) {
            List<Entry> out = new ArrayList<>(group.size());
            for (Entry line : group) {
                out.add(respan(line, lines.get(at++)));
            }
            tagged.add(List.copyOf(out));
        }
        return new ReportFindings(cause == null ? NOTABLE : CAUSE, causeLine, cause == null ? null : cause.confidence(),
            tagged.get(0), tagged.get(1), tagged.get(2), terms);
    }

    private static Entry respan(Entry line, List<Span> spans) {
        return new Entry(List.copyOf(spans), line.section(), line.label(), line.hover());
    }

    private static final class Facts {
        private final IncidentReport report;
        private final JfrTimeline timeline;
        private final ReportSections sections;
        private final HealthSnapshot snapshot;
        private final WorldNames names;
        private final long total;
        private final boolean sampled;
        private final List<World> worlds;
        private final ReportActivityWriter.TickSelection tick;
        private final ReportAttribution attribution;
        private final GcOverlap overlap;
        private final ReportLocks.Overlap locks;
        private final Long heapMax;
        private final Long heapPeak;
        private final double target;
        private final long spanMs;
        private final String world;
        private final Window stall;
        private Map.Entry<String, Signal> allocLead;
        private String lockWord;

        Facts(IncidentReport report, JfrTimeline timeline, ReportSections sections) {
            this.report = report;
            this.timeline = timeline;
            this.sections = sections;
            this.snapshot = report.snapshot();
            this.names = WorldNames.of(report, timeline, sections.tickSystems());
            this.total = timeline == null ? 0 : Math.max(0, timeline.totalSamples());
            this.sampled = total >= MIN_SAMPLES;
            this.worlds = worlds(report, timeline);
            this.tick = ReportActivityWriter.selectTicks(report, timeline);
            this.attribution = timeline == null ? null : new ReportAttribution(timeline.cpuByMod(), sections.pluginClasses());
            this.overlap = gcOverlap(report, timeline, tick);
            this.locks = ReportLocks.overlap(report, timeline, tick);
            this.heapMax = ReportSystemWriter.heapMax(snapshot, timeline);
            this.heapPeak = ReportFindings.heapPeak(snapshot, timeline);
            this.target = HealthSnapshot.targetTps(snapshot, tick == null ? report.meta().world() : tick.world());
            this.spanMs = timeline == null || timeline.start() == null || timeline.end() == null ? 0
                : Math.max(0, timeline.end().toEpochMilli() - timeline.start().toEpochMilli());
            this.world = report.meta().world();
            Long stallMs = ReportHtml.parseStallMs(report.context());
            if (stallMs != null && stallMs > 0 && "HEARTBEAT_STALL".equals(report.meta().trigger())) {
                long marker = ReportHtml.profileStartMs(report.context(), report.meta().createdAt().toEpochMilli());
                this.stall = new Window(marker - stallMs, marker, stallMs);
            } else {
                this.stall = null;
            }
        }

        Map<String, Signal> signals(ReportHistory history) {
            Map<String, Signal> out = new LinkedHashMap<>();
            HealthSnapshot.Threads threads = snapshot == null ? null : snapshot.threads();
            int deadlocked = threads == null ? 0 : threads.deadlocked().size();
            if (deadlocked > 0) {
                out.put("deadlock", new Signal(line("states", "Thread states", null, text("Java found a deadlock between "),
                    bold(Units.count(deadlocked, "thread")), text(".")), "a deadlock"));
            }
            if (locks != null) {
                out.put("lock", new Signal(lockEntry(), "a " + Units.elapsed(locks.totalMs()) + " lock wait"));
            }
            if (overlap != null) {
                out.put("gcoverlap", new Signal(overlapEntry(), "a " + fmt(Math.round(overlap.longestMs())) + " ms GC pause"));
            }
            double[] tps = tick == null ? null : tick.tps();
            if (tps != null && tps.length > 0) {
                double threshold = TPS_SHARE * target;
                double low = ReportJson.round2(ReportJson.min(tps));
                if (low < threshold) {
                    int below = 0;
                    for (double value : tps) {
                        if (value < threshold) {
                            below++;
                        }
                    }
                    List<Span> spans = new ArrayList<>(6);
                    spans.add(text("TPS"));
                    if (timeline.ticks().size() > 1) {
                        spans.add(text(" in world "));
                        spans.add(code(names.label(tick.world())));
                    }
                    spans.add(text(" fell to "));
                    spans.add(bold(fixed(low, 1)));
                    if (spanMs > 0) {
                        spans.add(text(" and stayed low for "));
                        spans.add(bold(Units.elapsed(Math.round((double) spanMs * below / tps.length))));
                    }
                    spans.add(text("."));
                    out.put("tps", new Signal(new Entry(List.copyOf(spans), "metrics", "Key metrics",
                        "Low means under " + trimmed(threshold) + " TPS, 90% of the " + trimmed(target) + " TPS target."),
                        "TPS fell to " + fixed(low, 1)));
                }
            }
            Entry usual = history.evidence(report, timeline, tick);
            if (usual != null) {
                String text = usual.text();
                int comma = text.indexOf(", ");
                out.put("usual", new Signal(usual, comma < 0 ? text : text.substring(0, comma)));
            }
            List<JfrTimeline.ModCpu> mods = new ArrayList<>(attribution == null || !sampled ? List.of()
                : attribution.cpu(timeline.cpuByMod()));
            mods.sort((a, b) -> Long.compare(b.self(), a.self()));
            for (JfrTimeline.ModCpu mod : mods) {
                double share = 100.0 * mod.self() / total;
                if (share >= MOD_SHARE && nameable(mod.id())) {
                    out.put("modcpu:" + mod.id(), new Signal(line("cpu", "CPU & load",
                        "Own code only, not the engine code it calls. Shown from " + trimmed(MOD_SHARE) + "% of CPU samples.",
                        bold(modName(mod.id())), text(possessive(mod.id()) + " own code ran in "),
                        bold(fixed(share, 0) + "%"), text(" of CPU samples.")), modName(mod.id()) + " at " + fixed(share, 0) + "% CPU"));
                }
            }
            World topWorld = null;
            World stalled = null;
            int stalledIndex = -1;
            for (int i = 0; i < worlds.size(); i++) {
                World candidate = worlds.get(i);
                if (candidate.samples() != null && candidate.samples() > 0
                    && (topWorld == null || candidate.samples() > topWorld.samples())) {
                    topWorld = candidate;
                }
                if (stalled == null && candidate.stalled()) {
                    stalled = candidate;
                    stalledIndex = i;
                }
            }
            long played = worlds.stream().filter(w -> w.players() > 0).count();
            boolean onlyPlayedWorld = topWorld != null && topWorld.players() > 0 && played == 1;
            boolean busyWorld = sampled && topWorld != null && !onlyPlayedWorld
                && Math.round(100.0 * topWorld.samples() / total) > WORLD_SHARE;
            if (busyWorld) {
                out.put("busyworld", new Signal(line("cpu", "CPU & load",
                    "Shown above " + trimmed(WORLD_SHARE) + "%, counting every thread of the world.",
                    text("World "), code(names.label(topWorld.name())), text(" used "),
                    bold(percent(topWorld.samples(), total, 0) + "%"), text(" of the CPU samples.")),
                    "world " + names.label(topWorld.name()) + " at " + percent(topWorld.samples(), total, 0) + "% CPU"));
            }
            if (sampled && !timeline.hotMethods().isEmpty() && !JfrTimeline.isRunLoop(timeline.hotMethods().getFirst().method())) {
                JfrTimeline.HotMethod method = timeline.hotMethods().getFirst();
                double share = 100.0 * method.samples() / total;
                if (share >= METHOD_SHARE) {
                    String mixin = FrameLabels.labelOf(method.method());
                    out.put("hotmethod", new Signal(line("methods", "Hot methods",
                        "The most sampled method. Shown from " + trimmed(METHOD_SHARE) + "% of CPU samples.",
                        code(leafName(method.method())), text((mixin == null ? "" : " (" + mixin + ")") + " was in "),
                        bold(fixed(share, 1) + "%"), text(" of CPU samples.")),
                        leafName(method.method()) + " in " + fixed(share, 1) + "% of CPU samples"));
                }
            }
            if (sampled && stalled != null && stalled.samples() != null && !(busyWorld && stalled == topWorld)) {
                long[] samples = new long[worlds.size()];
                for (int i = 0; i < samples.length; i++) {
                    Long value = worlds.get(i).samples();
                    samples[i] = value == null ? 0 : value;
                }
                int rank = WorldNames.cpuRank(samples, stalledIndex);
                List<Span> spans = new ArrayList<>(6);
                String role = "HEARTBEAT_STALL".equals(report.meta().trigger()) ? "stalled" : "incident";
                spans.add(text("The " + role + " world "));
                spans.add(code(names.label(stalled.name())));
                spans.add(text(" used "));
                spans.add(bold(percent(stalled.samples(), total, 0) + "%"));
                if (worlds.size() > 1) {
                    spans.add(text(" of the CPU samples, "));
                    spans.add(bold("#" + rank + " of " + worlds.size()));
                    spans.add(text(" worlds."));
                } else {
                    spans.add(text(" of the CPU samples."));
                }
                out.put("stalledworld", new Signal(new Entry(List.copyOf(spans), "worlds", "Worlds", null),
                    role + " world at " + percent(stalled.samples(), total, 0) + "% CPU"));
            }
            JfrTimeline.GcStats gc = timeline == null ? null : timeline.gc();
            if (gc != null) {
                double gcPct = spanMs > 0 ? 100.0 * gc.totalPauseMs() / spanMs : 0;
                boolean longPause = gc.maxPauseMs() >= GC_PAUSE_MS
                    && (overlap == null || gc.maxPauseMs() > overlap.longestMs() + 0.5);
                boolean gcTime = gcPct >= GC_TIME_PCT;
                List<Span> spans = new ArrayList<>(5);
                if (longPause) {
                    spans.add(text("GC paused the server for up to "));
                    spans.add(bold(fmt(Math.round(gc.maxPauseMs())) + " ms"));
                    spans.add(text("." + (gcTime ? " " : "")));
                }
                if (gcTime) {
                    spans.add(text("GC pauses took "));
                    spans.add(bold(fixed(gcPct, 0) + "%"));
                    spans.add(text(" of the time."));
                }
                if (!spans.isEmpty()) {
                    out.put("gc", new Signal(new Entry(List.copyOf(spans), "memory", "Memory & GC",
                        "Pauses shown from " + trimmed(GC_PAUSE_MS) + " ms, total GC time from " + trimmed(GC_TIME_PCT) + "%."),
                        longPause ? "GC pauses up to " + fmt(Math.round(gc.maxPauseMs())) + " ms" : "GC " + fixed(gcPct, 0) + "% of the time"));
                }
            }
            boolean heapLine = heapMax != null && heapMax > 0 && heapPeak != null && heapPeak >= HEAP_PCT / 100 * heapMax;
            if (heapLine) {
                String pct = fixed(100.0 * heapPeak / heapMax, 0);
                out.put("heap", new Signal(line("memory", "Memory & GC", "Shown from " + trimmed(HEAP_PCT) + "% of the heap limit.",
                    text("Heap peaked at "), bold(fixed(heapPeak / GIB, 1) + " of " + fixed(heapMax / GIB, 1) + " GiB"),
                    text(" ("), bold(pct + "%"), text(").")), "heap at " + pct + "%"));
            }
            Entry leak = leakEntry();
            if (leak != null) {
                out.put("leak", new Signal(leak, "a rising post-GC floor"));
            }
            JfrTimeline.Alloc alloc = timeline == null ? null : timeline.alloc();
            if (alloc != null && !alloc.bySubsystem().isEmpty()) {
                Map.Entry<String, Long> lead = alloc.bySubsystem().entrySet().iterator().next();
                if (!lead.getKey().startsWith("Other")) {
                    List<Span> spans = new ArrayList<>(4);
                    spans.add(bold(lead.getKey()));
                    spans.add(text(" allocated the most memory, "));
                    spans.add(bold(alloc.totalBytes() > 0 ? fmtBytes(lead.getValue()) + " of " + fmtBytes(alloc.totalBytes())
                        : fmtBytes(lead.getValue())));
                    spans.add(text("."));
                    allocLead = Map.entry("allocsub:" + lead.getKey(), new Signal(new Entry(List.copyOf(spans), "memory",
                        "Memory & GC", "Sampled allocations grouped by the engine subsystem that asked for the memory."),
                        lead.getKey() + " allocations"));
                    if (out.containsKey("gc") || out.containsKey("heap")
                        || overlap != null && overlap.share() >= GC_CAUSE_SHARE) {
                        out.put(allocLead.getKey(), allocLead.getValue());
                    }
                }
            }
            if (attribution != null && alloc != null && alloc.totalBytes() > 0 && alloc.samples() >= MIN_SAMPLES) {
                List<Map.Entry<String, JfrTimeline.ModShare>> allocMods = new ArrayList<>(attribution.shares(alloc.byMod()).entrySet());
                allocMods.sort((a, b) -> Long.compare(b.getValue().self(), a.getValue().self()));
                for (Map.Entry<String, JfrTimeline.ModShare> mod : allocMods) {
                    double share = 100.0 * mod.getValue().self() / alloc.totalBytes();
                    if (share >= MOD_SHARE && nameable(mod.getKey())) {
                        out.put("allocmod:" + mod.getKey(), new Signal(line("memory", "Memory & GC",
                            "Own code only. Shown from " + trimmed(MOD_SHARE) + "% of sampled memory.",
                            bold(modName(mod.getKey())), text(possessive(mod.getKey()) + " own code allocated "),
                            bold(fixed(share, 0) + "%"), text(" of sampled memory.")),
                            modName(mod.getKey()) + " at " + fixed(share, 0) + "% of allocations"));
                    }
                }
            }
            JfrTimeline.Container container = timeline == null ? null : timeline.container();
            boolean containerLimit = container != null && container.memLimit() > 0;
            long memoryLimit = containerLimit ? container.memLimit()
                : snapshot != null && snapshot.system() != null ? snapshot.system().totalRamBytes() : 0;
            long rssPeak = max(timeline == null ? null : timeline.rssSeries());
            if (rssPeak > 0 && memoryLimit > 0 && 100.0 * rssPeak / memoryLimit >= RSS_PCT) {
                String pct = fixed(100.0 * rssPeak / memoryLimit, 0);
                out.put("rss", new Signal(line("memory", "Memory & GC", "Shown from " + trimmed(RSS_PCT) + "%.",
                    text("Server memory (RSS) peaked at "), bold(fixed(rssPeak / GIB, 1) + " of " + fixed(memoryLimit / GIB, 1) + " GiB"),
                    text(" of " + (containerLimit ? "the container limit" : "machine RAM") + " ("), bold(pct + "%"), text(").")),
                    "RSS at " + pct + "% of " + (containerLimit ? "the limit" : "RAM")));
            }
            if (containerLimit && heapMax != null && heapMax > CONTAINER_HEAP_SHARE * container.memLimit()) {
                out.put("container", new Signal(line("system", "System & hardware",
                    "Shown above " + fixed(100 * CONTAINER_HEAP_SHARE, 0) + "% of the container limit. "
                        + "Set -Xmx to about 75% of the container limit.",
                    text("The -Xmx of "), bold(fixed(heapMax / GIB, 1) + " GiB"), text(" is "),
                    bold(fixed(100.0 * heapMax / container.memLimit(), 0) + "%"), text(" of the container's "),
                    bold(fixed(container.memLimit() / GIB, 1) + " GiB"), text(", so the container can kill the server.")),
                    "-Xmx near the container limit"));
            }
            JfrTimeline.CpuStats cpu = timeline == null ? null : timeline.cpu();
            if (cpu != null && Double.isFinite(cpu.machAvg()) && cpu.machAvg() >= MACHINE_CPU_PCT) {
                List<Span> spans = new ArrayList<>(4);
                spans.add(text("Machine CPU averaged "));
                spans.add(bold(fixed(cpu.machAvg(), 0) + "%"));
                if (Double.isFinite(cpu.jvmAvg()) && cpu.jvmAvg() >= 0) {
                    spans.add(text(", the server used "));
                    spans.add(bold(fixed(cpu.jvmAvg(), 0) + "%"));
                }
                spans.add(text("."));
                out.put("machinecpu", new Signal(new Entry(List.copyOf(spans), "cpu", "CPU & load",
                    "Shown from " + trimmed(MACHINE_CPU_PCT) + "%."), "machine CPU at " + fixed(cpu.machAvg(), 0) + "%"));
            }
            Signal swap = swapSignal();
            if (swap != null) {
                out.put("swap", swap);
            }
            Signal load = loadSignal();
            if (load != null) {
                out.put("load", load);
            }
            Integer blocked = threads == null || threads.byState().isEmpty()
                ? null : threads.byState().getOrDefault("BLOCKED", 0);
            if (blocked != null && blocked > 0) {
                out.put("blocked", new Signal(line("states", "Thread states", null, bold(number(blocked)),
                    text(blocked == 1 ? " thread was waiting for a lock at capture." : " threads were waiting for a lock at capture.")),
                    Units.count(blocked, "thread") + " blocked"));
            }
            ExceptionSpike spike = exceptionSpike(timeline);
            if (spike != null) {
                int buckets = timeline.exceptionsSeries().length;
                String window = spanMs > 0
                    ? " in the " + Units.elapsed(Math.round((double) spanMs / buckets)) + " before the incident, "
                    : " right before the incident, ";
                out.put("exceptions", new Signal(line("log", "Server log",
                    "Shown from " + trimmed(EXCEPTION_SPIKE) + " times the average, in the last tenth of the window.",
                    bold(fmt(spike.peak())), text(" exception" + (spike.peak() == 1 ? "" : "s") + window),
                    bold(fixed(spike.peak() / spike.average(), 0) + " times"), text(" the average.")), "an exception spike"));
            }
            Signal slowFile = slowFileSignal();
            if (slowFile != null) {
                out.put("slowfile", slowFile);
            }
            Signal disk = diskSignal(ReportSystemWriter.disk(snapshot, timeline));
            if (disk != null) {
                out.put("disk", disk);
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
                out.put("incompatible", new Signal(line("plugins", "Plugins", null, bold(number(incompatible)), text(incompatible == 1
                    ? " mod was built for another Hytale version." : " mods were built for another Hytale version.")),
                    Units.count(incompatible, "incompatible mod")));
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
                out.put("mixins", new Signal(new Entry(List.copyOf(spans), "mixins", "Mixins", null),
                    errors > 0 ? Units.count(errors, "Mixin error") : Units.count(warnings, "Mixin warning")));
            }
            Signal hot = hotLoweredSignal();
            if (hot != null) {
                out.put("hotradius", hot);
            }
            return out;
        }

        Cause freeze(Map<String, Signal> signals) {
            String freeze = stall == null ? "freeze" : Units.elapsed(stall.ms()) + " freeze";
            if (overlap != null && overlap.stall() && overlap.share() >= GC_CAUSE_SHARE) {
                return gcCause(signals, freezeGcEntry(), Set.of("gcoverlap"));
            }
            HealthSnapshot.Threads threads = snapshot == null ? null : snapshot.threads();
            if (threads != null && !threads.deadlocked().isEmpty()) {
                return deadlockCause(threads.deadlocked());
            }
            String worldThread = world == null ? null : ReportLocks.WORLD_THREAD_PREFIX + world;
            JfrSamples samples = timeline == null ? null : timeline.samples();
            JfrSamples.Top top = stall == null || samples == null ? null : samples.top(worldThread, stall.from(), stall.to());
            if (top != null && top.samples() >= BUSY_MIN_SAMPLES && top.samples() >= BUSY_SHARE * top.threadSamples()
                && !JfrTimeline.isRunLoop(top.method())) {
                String owner = owner(top.owner(), top.method());
                List<Span> spans = new ArrayList<>(6);
                spans.add(text("The world thread was busy in "));
                spans.add(code(leafName(top.method())));
                if (owner != null) {
                    spans.add(text(" ("));
                    spans.add(bold(owner));
                    spans.add(text(")"));
                }
                spans.add(text(" for "));
                spans.add(bold(Units.elapsed(stall.ms())));
                spans.add(text("."));
                double share = (double) top.samples() / top.threadSamples();
                Entry cause = new Entry(List.copyOf(spans), "flame", "Flame graph",
                    top.samples() + " of the world thread's " + top.threadSamples() + " CPU samples in the freeze ("
                        + fixed(100 * share, 0) + "%) were in this method. Named from " + fixed(100 * BUSY_SHARE, 0)
                        + "% and " + BUSY_MIN_SAMPLES + " samples.");
                List<Entry> why = new ArrayList<>();
                Set<String> used = new HashSet<>();
                String mod = top.owner() != null && nameable(top.owner()) ? top.owner() : null;
                addSignal(why, used, signals, mod == null ? null : "modcpu:" + mod);
                addSignal(why, used, signals, "hotmethod");
                List<Entry> steps = new ArrayList<>();
                if (mod != null) {
                    steps.add(reportStep(mod, "it held the world thread for ", Units.elapsed(stall.ms())));
                } else {
                    steps.add(line("flame", "Flame graph", null, text("Open "), code(worldThread),
                        text(" in the flame graph to see what called "), code(leafName(top.method())), text(".")));
                }
                return new Cause(cause, share >= CLEAR_SHARE ? CLEAR : LIKELY, used, why, steps);
            }
            if (locks != null && locks.stall()) {
                return lockCause(signals, samples);
            }
            if (stall != null && samples != null && samples.around(stall.from(), stall.to())
                && samples.count(stall.from(), stall.to()) == 0) {
                Entry cause = line("cpu", "CPU & load",
                    "No CPU sample from any thread between the last heartbeat and the trigger. "
                        + "The machine was overloaded, suspended or asleep.",
                    text("The whole process got no CPU during the "), bold(Units.elapsed(stall.ms())), text(" freeze."));
                return starvedCause(signals, cause, Set.of(), LIKELY);
            }
            Signal load = signals.get("load");
            if (load != null) {
                double before = stall == null || samples == null ? Double.NaN
                    : samples.count(stall.from() - DENSITY_BEFORE_MS, stall.from() - 1) * 1000.0 / DENSITY_BEFORE_MS;
                double during = stall == null || samples == null ? Double.NaN
                    : samples.count(stall.from(), stall.to()) * 1000.0 / stall.ms();
                boolean dropped = before > 0 && during < DENSITY_DROP * before;
                String density = Double.isNaN(before) ? " No CPU samples to compare."
                    : " CPU samples went from " + fixed(before, 1) + " to " + fixed(during, 1) + " per second during the freeze"
                        + (dropped ? "." : ", no clear drop.");
                Entry cause = new Entry(concat(concat(List.of(text("The machine was overloaded during the " + freeze + ": ")),
                    loadSpans()), List.of(text("."))), "system", "System & hardware",
                    "The load is the 1-minute average at capture, not a value from the freeze itself." + density
                        + " Named from " + trimmed(LOAD_PER_CPU) + " per " + cpuUnitName(cpus()) + ".");
                return starvedCause(signals, cause, Set.of("load"), dropped ? LIKELY : UNCLEAR);
            }
            return unclear(signals, "freeze");
        }

        Cause ticks(Map<String, Signal> signals) {
            if (overlap != null && !overlap.stall() && overlap.share() >= GC_CAUSE_SHARE) {
                return gcCause(signals, tickGcEntry(), Set.of("gcoverlap"));
            }
            double budget = 1000.0 / target;
            String budgetText = fixed(budget, 1) + " ms";
            String tickWorld = tick == null ? world : tick.world();
            ReportActivityWriter.TickSystemRow system = null;
            for (ReportActivityWriter.TickSystemRow row : ReportActivityWriter.tickSystemRows(sections.tickSystems())) {
                if ((tickWorld == null || ReportActivityWriter.worldKey(row.world()).equals(ReportActivityWriter.worldKey(tickWorld)))
                    && (system == null || row.avg1m() > system.avg1m())) {
                    system = row;
                }
            }
            String mod = null;
            double modMs = 0;
            double avgTick = tick == null ? Double.NaN : mean(tick.mspt());
            Long worldSamples = tickWorld == null || timeline == null ? null : timeline.worldSamples().get(tickWorld);
            if (attribution != null && sampled && worldSamples != null && worldSamples > 0 && Double.isFinite(avgTick)) {
                String thread = ReportLocks.WORLD_THREAD_PREFIX + tickWorld;
                for (JfrTimeline.ModCpu row : attribution.cpu(timeline.cpuByMod())) {
                    if (!nameable(row.id())) {
                        continue;
                    }
                    for (JfrTimeline.ThreadShare share : row.selfThreads()) {
                        if (thread.equals(share.thread())) {
                            double ms = avgTick * share.samples() / worldSamples;
                            if (ms > modMs) {
                                modMs = ms;
                                mod = row.id();
                            }
                        }
                    }
                }
            }
            double systemMs = system == null ? 0 : system.avg1m();
            if (mod != null && modMs >= TICK_BUDGET_SHARE * budget && modMs >= systemMs) {
                Entry cause = line("cpu", "CPU & load",
                    "Own code only, from its share of the world thread's CPU samples times the average tick time. "
                        + "Named from " + fixed(100 * TICK_BUDGET_SHARE, 0) + "% of the " + budgetText + " tick budget.",
                    bold(modName(mod)), text(possessive(mod) + " own code took about "), bold(fixed(modMs, 1) + " ms"),
                    text(" of each tick, "), bold(budgetShare(modMs, budget)), text(" the tick budget."));
                Set<String> used = new HashSet<>();
                List<Entry> why = new ArrayList<>();
                addSignal(why, used, signals, "tps");
                addSignal(why, used, signals, "modcpu:" + mod);
                return new Cause(cause, LIKELY, used, why,
                    List.of(reportStep(mod, "its code took ", fixed(modMs, 1) + " ms", " of each tick")));
            }
            if (system != null && systemMs >= TICK_BUDGET_SHARE * budget) {
                String[] family = SYSTEM_FAMILIES.get(system.system());
                String timings = " Hytale's system timings at capture: 1-minute average " + fixed(systemMs, 1)
                    + " ms, 5-minute average " + fixed(system.avg5m(), 1) + " ms, slowest tick " + fmt(Math.round(system.max1m()))
                    + " ms.";
                String named = " Named when the 1-minute average reaches " + fixed(100 * TICK_BUDGET_SHARE, 0) + "% of the "
                    + budgetText + " tick budget.";
                boolean spike = system.max1m() >= budget && system.max1m() >= SPIKE_RATIO * systemMs
                    && system.avg5m() < TICK_BUDGET_SHARE * budget;
                List<Span> spans = new ArrayList<>(8);
                String hover;
                if (spike) {
                    spans.add(text("One "));
                    spans.add(bold(system.system()));
                    spans.add(text(" tick took "));
                    spans.add(bold(pauseText(system.max1m())));
                    spans.add(text(family == null ? "." : " (" + family[0] + ")."));
                    hover = "One slow tick drives the 1-minute average, the 5-minute average stays low."
                        + timings + named;
                } else {
                    spans.add(text("Engine system "));
                    spans.add(bold(system.system()));
                    spans.add(text((family == null ? "" : " (" + family[0] + ")") + " took about "));
                    spans.add(bold(fixed(systemMs, 1) + " ms"));
                    spans.add(text(" of each tick, "));
                    spans.add(bold(budgetShare(systemMs, budget)));
                    spans.add(text(" the tick budget."));
                    hover = "A steady cost, not one slow tick." + timings + named;
                }
                Entry cause = new Entry(List.copyOf(spans), "engine", "Engine systems", hover);
                Set<String> used = new HashSet<>();
                List<Entry> why = new ArrayList<>();
                addSignal(why, used, signals, "tps");
                String lower = system.system().toLowerCase(Locale.ROOT);
                Entry radius = lower.contains("blocktick") || lower.contains("fluid") ? hotEntry(system.world())
                    : lower.contains("chunk") || lower.contains("worldgen") || lower.contains("generat") ? viewEntry(system.world())
                    : null;
                if (radius != null) {
                    why.add(radius);
                    used.add("hotradius");
                }
                addSignal(why, used, signals, "hotmethod");
                addSignal(why, used, signals, "busyworld");
                Entry step = family == null
                    ? line("engine", "Engine systems", null, text("Compare "), bold(system.system()),
                        text(" with the other systems of "), code(names.label(system.world())), text(" in Engine systems."))
                    : line(family[3], family[4], null, text(family[1]), code(names.label(system.world())), text(family[2]));
                return new Cause(cause, LIKELY, used, why, List.of(step));
            }
            Entry chunks = chunkEntry();
            if (chunks != null) {
                Set<String> used = new HashSet<>();
                List<Entry> why = new ArrayList<>();
                addSignal(why, used, signals, "tps");
                Entry radius = viewEntry(tick == null ? world : tick.world());
                if (radius != null) {
                    why.add(radius);
                }
                Entry step = line(null, null, null,
                    text("Pre-generate the area players explore, or lower the view distance so fewer chunks load at once."));
                return new Cause(chunks, LIKELY, used, why, List.of(step));
            }
            for (String key : List.of("machinecpu", "swap", "load")) {
                Signal signal = signals.get(key);
                if (signal != null) {
                    Entry cause = switch (key) {
                        case "machinecpu" -> line("cpu", "CPU & load", signal.entry().hover(),
                            text("The machine was short of CPU: it averaged "), bold(fixed(timeline.cpu().machAvg(), 0) + "%"),
                            text("."));
                        case "swap" -> line("system", "System & hardware", signal.entry().hover(),
                            text("The machine was short of RAM: "), bold(fmtBytes(swapMoved())), text(" went through swap."));
                        default -> new Entry(concat(concat(List.of(text("The machine was overloaded: ")), loadSpans()),
                            List.of(text("."))), "system", "System & hardware", signal.entry().hover());
                    };
                    return starvedCause(signals, cause, Set.of(key), LIKELY);
                }
            }
            return unclear(signals, "slowdown");
        }

        Cause memory(Map<String, Signal> signals) {
            String modKey = first(signals, "allocmod:");
            String subKey = first(signals, "allocsub:");
            if (subKey == null && allocLead != null) {
                signals.put(allocLead.getKey(), allocLead.getValue());
                subKey = allocLead.getKey();
            }
            String key = modKey != null ? modKey : subKey;
            if (key == null) {
                return unclear(signals, "memory");
            }
            Set<String> used = new HashSet<>(Set.of(key));
            List<Entry> why = new ArrayList<>();
            addSignal(why, used, signals, "leak");
            addSignal(why, used, signals, "heap");
            addSignal(why, used, signals, "gc");
            List<Entry> steps = new ArrayList<>();
            steps.add(signals.containsKey("heap") ? xmxStep() : null);
            steps.add(allocatorStep(key));
            return new Cause(signals.get(key).entry(), LIKELY, used, why, steps);
        }

        private Cause gcCause(Map<String, Signal> signals, Entry cause, Set<String> taken) {
            Set<String> used = new HashSet<>(taken);
            List<Entry> why = new ArrayList<>();
            addSignal(why, used, signals, "heap");
            addSignal(why, used, signals, "leak");
            String allocator = first(signals, "allocmod:");
            if (allocator == null) {
                allocator = first(signals, "allocsub:");
            }
            addSignal(why, used, signals, allocator);
            addSignal(why, used, signals, "swap");
            List<Entry> steps = new ArrayList<>();
            steps.add(signals.containsKey("heap") ? xmxStep() : null);
            if (allocator != null) {
                steps.add(allocatorStep(allocator));
            }
            return new Cause(cause, overlap.share() >= CLEAR_SHARE ? CLEAR : LIKELY, used, why, steps);
        }

        private Cause deadlockCause(List<String> deadlocked) {
            Map<String, String> frames = new LinkedHashMap<>();
            for (DiagnosticSection section : report.diagnostics() == null ? List.<DiagnosticSection>of() : report.diagnostics()) {
                if ("Deadlocked thread".equals(section.title()) && section.entries().get("Thread") != null) {
                    String frame = section.entries().get(io.github.xytronix.hybox.core.health.StalledThread.FRAME_KEY);
                    frames.put(section.entries().get("Thread"), frame == null ? null : frame.split(", ", 2)[0]);
                }
            }
            List<String> threads = new ArrayList<>(deadlocked);
            frames.keySet().forEach(name -> {
                if (!threads.contains(name)) {
                    threads.add(name);
                }
            });
            List<Span> spans = new ArrayList<>();
            spans.add(text("Threads "));
            String mod = null;
            int shown = Math.min(threads.size(), 3);
            for (int i = 0; i < shown; i++) {
                String name = threads.get(i);
                if (i > 0) {
                    spans.add(text(i == threads.size() - 1 ? " and " : ", "));
                }
                spans.add(code(name));
                String frame = frames.get(name);
                if (frame != null) {
                    spans.add(text(" (in "));
                    spans.add(code(leafName(frame)));
                    spans.add(text(")"));
                    int dot = frame.lastIndexOf('.');
                    String owner = dot < 0 || attribution == null ? null : attribution.owner(frame.substring(0, dot));
                    if (mod == null && owner != null && nameable(owner)) {
                        mod = owner;
                    }
                }
            }
            if (threads.size() > shown) {
                spans.add(text(" and " + (threads.size() - shown) + " more"));
            }
            spans.add(text(" are deadlocked."));
            Entry cause = new Entry(List.copyOf(spans), "states", "Thread states",
                "Java's deadlock check found these threads at capture.");
            List<Entry> steps = new ArrayList<>();
            steps.add(line("states", "Thread states", null, text("Restart the server. Deadlocked threads never recover.")));
            if (mod != null) {
                steps.add(reportStep(mod, "its code is part of the deadlock", null));
            }
            return new Cause(cause, CLEAR, Set.of("deadlock", "blocked"), List.of(), steps);
        }

        private Cause lockCause(Map<String, Signal> signals, JfrSamples samples) {
            JfrTimeline.LockWait lead = locks.lead();
            JfrSamples.Top holder = lead.owner() == null || samples == null ? null
                : samples.top(lead.owner(), stall.from(), stall.to());
            List<Span> spans = new ArrayList<>(10);
            spans.add(text("The world thread waited "));
            spans.add(bold(Units.elapsed(locks.totalMs())));
            String mod = null;
            if (lead.owner() != null) {
                spans.add(text(" for a lock held by "));
                spans.add(code(lead.owner()));
                if (holder != null) {
                    spans.add(text(", busy in "));
                    spans.add(code(leafName(holder.method())));
                    String owner = owner(holder.owner(), holder.method());
                    if (owner != null) {
                        spans.add(text(" ("));
                        spans.add(bold(owner));
                        spans.add(text(")"));
                    }
                    mod = holder.owner() != null && nameable(holder.owner()) ? holder.owner() : null;
                }
            } else {
                spans.add(text(" on "));
                spans.add(code(ReportLocks.lockName(lead.lock())));
                if (lead.frame() != null) {
                    spans.add(text(" in "));
                    spans.add(code(leafName(lead.frame())));
                }
            }
            spans.add(text("."));
            if (mod == null && lead.mod() != null && attribution != null && nameable(attribution.canonical(lead.mod()))) {
                mod = attribution.canonical(lead.mod());
            }
            Entry cause = new Entry(List.copyOf(spans), "cpu", "Lock waits", lockWord + " Named from "
                + fixed(100 * ReportLocks.SHARE, 0) + "% of the freeze.");
            List<Entry> steps = new ArrayList<>();
            if (mod != null) {
                steps.add(reportStep(mod, "it held the world thread for ", Units.elapsed(locks.totalMs())));
            } else if (lead.owner() != null) {
                steps.add(line("flame", "Flame graph", null, text("Open "), code(lead.owner()),
                    text(" in the flame graph to see what it ran while it held the lock.")));
            }
            List<Entry> why = new ArrayList<>();
            Set<String> used = new HashSet<>(Set.of("lock"));
            addSignal(why, used, signals, "blocked");
            return new Cause(cause, locks.share() >= CLEAR_SHARE ? CLEAR : LIKELY, used, why, steps);
        }

        private Cause starvedCause(Map<String, Signal> signals, Entry cause, Set<String> taken, String confidence) {
            Set<String> used = new HashSet<>(taken);
            List<Entry> why = new ArrayList<>();
            for (String key : List.of("swap", "machinecpu", "load")) {
                addSignal(why, used, signals, key);
            }
            List<Span> facts = new ArrayList<>();
            if (signals.containsKey("load") && !taken.contains("load")) {
                facts.addAll(loadSpans());
            }
            JfrTimeline.Swap swap = timeline == null ? null : timeline.swap();
            if (signals.containsKey("swap") && !taken.contains("swap") && swap != null) {
                if (!facts.isEmpty()) {
                    facts.add(text(", "));
                }
                facts.add(bold(fmtBytes(swapMoved())));
                facts.add(text(" swapped"));
            }
            JfrTimeline.CpuStats cpu = timeline == null ? null : timeline.cpu();
            if (signals.containsKey("machinecpu") && !taken.contains("machinecpu") && cpu != null) {
                if (!facts.isEmpty()) {
                    facts.add(text(", "));
                }
                facts.add(text("machine CPU at "));
                facts.add(bold(fixed(cpu.machAvg(), 0) + "%"));
            }
            Entry step = !facts.isEmpty()
                ? new Entry(concat(concat(List.of(text("Check the host: ")), facts), List.of(text("."))),
                    "system", "System & hardware", null)
                : taken.isEmpty()
                ? line("system", "System & hardware", null,
                    text("Check whether the host slept, was suspended or ran other heavy programs at that time."))
                : line("system", "System & hardware", null,
                    text("Find what else ran on the host at that time and move it off, or give the server more cores or RAM."));
            return new Cause(cause, confidence, used, why, List.of(step));
        }

        private Cause unclear(Map<String, Signal> signals, String what) {
            List<String> briefs = new ArrayList<>(2);
            for (Signal signal : signals.values()) {
                if (briefs.size() < 2) {
                    briefs.add(signal.brief());
                }
            }
            Entry cause = briefs.isEmpty()
                ? line(null, null, null, text("No clear cause, and nothing else crossed a threshold."))
                : line(null, null, null, text("No clear cause. Strongest signals: " + String.join(", ", briefs) + "."));
            String mode = "memory".equals(what) ? "memory" : "lag";
            String when = "memory".equals(what) ? "while memory climbs" : "during the next " + what;
            Entry step = line(null, null, null, text("Capture " + when + " with "), code("/hybox profile " + mode), text("."));
            return new Cause(cause, UNCLEAR, Set.of(), List.of(), List.of(step));
        }

        private Entry freezeGcEntry() {
            boolean one = overlap.count() == 1;
            boolean whole = overlap.share() >= CLEAR_SHARE;
            List<Span> spans = new ArrayList<>(8);
            if (one) {
                spans.add(text("A "));
                spans.add(bold(pauseText(overlap.longestMs())));
                spans.add(text(" GC pause covers "));
            } else {
                spans.add(bold(number(overlap.count())));
                spans.add(text(" GC pauses, the longest "));
                spans.add(bold(pauseText(overlap.longestMs())));
                spans.add(text(", cover "));
            }
            if (whole) {
                spans.add(text("the whole "));
            } else {
                spans.add(bold(fixed(100 * overlap.share(), 0) + "%"));
                spans.add(text(" of the "));
            }
            spans.add(bold(Units.elapsed(Math.round(overlap.targetMs()))));
            spans.add(text(" freeze."));
            return new Entry(List.copyOf(spans), "memory", "Memory & GC", gcHover());
        }

        private Entry tickGcEntry() {
            boolean one = overlap.count() == 1;
            List<Span> spans = new ArrayList<>(8);
            if (one) {
                spans.add(text("A "));
                spans.add(bold(pauseText(overlap.longestMs())));
                spans.add(text(" GC pause took "));
            } else {
                spans.add(bold(number(overlap.count())));
                spans.add(text(" GC pauses took "));
            }
            spans.add(bold(fixed(100 * overlap.share(), 0) + "%"));
            spans.add(text(" of the worst tick ("));
            spans.add(bold(fmt(Math.round(overlap.targetMs())) + " ms"));
            spans.add(text(")."));
            return new Entry(List.copyOf(spans), "memory", "Memory & GC", gcHover());
        }

        private String gcHover() {
            return "GC pauses in the same interval, counted from 1 s before it. GC stops every server thread. Named from "
                + fixed(100 * GC_CAUSE_SHARE, 0) + "%." + (allocationFailure(overlap.cause())
                ? " The longest came from an allocation failure: the heap filled faster than GC could free it." : "");
        }

        private Entry overlapEntry() {
            boolean one = overlap.count() == 1;
            List<Span> spans = new ArrayList<>(9);
            if (one) {
                spans.add(text("A "));
                spans.add(bold(pauseText(overlap.longestMs())));
                spans.add(text(" GC pause "));
            } else {
                spans.add(bold(number(overlap.count())));
                spans.add(text(" GC pauses, the longest "));
                spans.add(bold(pauseText(overlap.longestMs())));
                spans.add(text(", "));
            }
            if (overlap.stall()) {
                spans.add(text((one ? "overlaps" : "overlap") + " the "));
                spans.add(bold(Units.elapsed(Math.round(overlap.targetMs()))));
                spans.add(text(" freeze, "));
            } else {
                spans.add(text("ran in the worst tick ("));
                spans.add(bold(fmt(Math.round(overlap.targetMs())) + " ms"));
                spans.add(text("), "));
            }
            spans.add(bold(fixed(100 * overlap.share(), 0) + "%"));
            spans.add(text(" of it."));
            return new Entry(List.copyOf(spans), "memory", "Memory & GC", gcHover());
        }

        private Entry lockEntry() {
            JfrTimeline.LockWait lead = locks.lead();
            List<Span> spans = new ArrayList<>(14);
            spans.add(code(locks.thread()));
            spans.add(text(" waited "));
            spans.add(bold(Units.elapsed(locks.totalMs())));
            spans.add(text(locks.locks() > 1 ? " on " + locks.locks() + " locks, most on " : " on "));
            spans.add(code(ReportLocks.lockName(lead.lock())));
            if (lead.frame() != null) {
                spans.add(text(" in "));
                spans.add(code(leafName(lead.frame())));
                String mod = lead.mod() == null || attribution == null ? null : attribution.canonical(lead.mod());
                if (mod != null && nameable(mod)) {
                    spans.add(text(" ("));
                    spans.add(bold(modName(mod)));
                    spans.add(text(")"));
                }
            }
            if (lead.owner() != null) {
                spans.add(text(", held by "));
                spans.add(code(lead.owner()));
            }
            spans.add(text("."));
            lockWord = (lead.monitor() ? "A synchronized lock. " : "") + "Share of the "
                + (locks.stall() ? Units.elapsed(Math.round(locks.targetMs())) + " freeze: "
                    : "worst tick (" + fmt(Math.round(locks.targetMs())) + " ms): ")
                + fixed(100 * locks.share(), 0) + "%. Waits of " + JfrTimeline.LOCK_WAIT_MS + " ms or more count"
                + (locks.gcExcluded() ? ", time inside GC pauses does not." : ".");
            return new Entry(List.copyOf(spans), "cpu", "Lock waits", lockWord + " Shown from "
                + fixed(100 * ReportLocks.SHARE, 0) + "%.");
        }

        private Entry leakEntry() {
            long[] floor = timeline == null ? null : timeline.postGcHeap();
            if (floor == null || floor.length < LEAK_MIN_GCS || heapMax == null || heapMax <= 0) {
                return null;
            }
            int third = floor.length / 3;
            long first = Long.MAX_VALUE;
            long last = Long.MAX_VALUE;
            for (int i = 0; i < third; i++) {
                first = Math.min(first, floor[i]);
                last = Math.min(last, floor[floor.length - 1 - i]);
            }
            if (last - first < LEAK_RISE * heapMax || last < LEAK_FLOOR * heapMax) {
                return null;
            }
            return line("memory", "Memory & GC",
                "Lowest heap after a GC in the first and in the last third of the window. Shown when it rose by "
                    + fixed(100 * LEAK_RISE, 0) + "% of the heap limit or more and ends at "
                    + fixed(100 * LEAK_FLOOR, 0) + "% of it or more.",
                text("The post-GC floor rose from "), bold(fixed(first / GIB, 1) + " GiB"), text(" to "),
                bold(fixed(last / GIB, 1) + " GiB"), text(", a possible leak."));
        }

        private HealthSnapshot.World snapshotWorld(String name) {
            if (snapshot == null || name == null) {
                return null;
            }
            for (HealthSnapshot.World candidate : snapshot.worlds()) {
                if (candidate.name().equalsIgnoreCase(name)) {
                    return candidate;
                }
            }
            return null;
        }

        private Entry viewEntry(String name) {
            HealthSnapshot.World w = snapshotWorld(name);
            HealthSnapshot.Radius radius = w == null ? null : w.radius();
            if (radius == null || radius.viewMax() < 0) {
                return null;
            }
            List<Span> spans = new ArrayList<>(8);
            spans.add(text("Players in "));
            spans.add(code(names.label(w.name())));
            spans.add(text(" see up to "));
            spans.add(bold(Units.count(radius.viewMax(), "chunk")));
            if (snapshot.maxViewRadius() > 0) {
                spans.add(text(" of the server max "));
                spans.add(bold(number(snapshot.maxViewRadius())));
            }
            if (radius.sections() >= 0) {
                spans.add(text(" and load "));
                spans.add(bold(fmt(radius.sections()) + " sections"));
                spans.add(text(" in total"));
            }
            spans.add(text("."));
            return new Entry(List.copyOf(spans), "worlds", "Worlds",
                "View radius sets how many chunks each player loads and generates. Shown when chunk loading or "
                    + "world generation leads the slow ticks. MaxViewRadius in the server config.json caps it.");
        }

        private Entry hotEntry(String name) {
            HealthSnapshot.World w = snapshotWorld(name);
            HealthSnapshot.Radius radius = w == null ? null : w.radius();
            if (radius == null || radius.hotMax() < 0) {
                return null;
            }
            String range = radius.hotMin() == radius.hotMax() ? Units.count(radius.hotMax(), "chunk")
                : radius.hotMin() + " to " + Units.count(radius.hotMax(), "chunk");
            return line("worlds", "Worlds",
                "Blocks, fluids and farming tick only inside the hot radius around each player. Shown when block or "
                    + "fluid ticking leads the slow ticks.",
                text("The hot radius in "), code(names.label(w.name())), text(" is "), bold(range),
                text(" for "), bold(Units.count(radius.players(), "player")),
                text(", so blocks and fluids tick in that range around each of them."));
        }

        private Signal hotLoweredSignal() {
            HealthSnapshot.World lowered = null;
            String tickWorld = tick == null ? world : tick.world();
            for (HealthSnapshot.World candidate : snapshot == null ? List.<HealthSnapshot.World>of() : snapshot.worlds()) {
                HealthSnapshot.Radius radius = candidate.radius();
                if (radius != null && radius.hotLowered() > 0
                    && (lowered == null || candidate.name().equalsIgnoreCase(tickWorld))) {
                    lowered = candidate;
                }
            }
            if (lowered == null) {
                return null;
            }
            HealthSnapshot.Radius radius = lowered.radius();
            return new Signal(line("worlds", "Worlds",
                "Hytale keeps blocks and fluids ticking within " + HealthSnapshot.Radius.DEFAULT_HOT_RADIUS
                    + " chunks of each player, and only mods change that. Shown when a player's hot radius is lower.",
                text("A mod lowered the hot radius to "), bold(Units.count(radius.hotLoweredTo(), "chunk")),
                text(" for "), bold(radius.hotLowered() + " of " + Units.count(radius.players(), "player")),
                text(" in "), code(names.label(lowered.name())), text(".")),
                "hot radius lowered to " + radius.hotLoweredTo());
        }

        private Entry chunkEntry() {
            double[] worst = tick == null ? null : tick.worst();
            if (worst == null) {
                return null;
            }
            int at = -1;
            for (int i = 0; i < worst.length; i++) {
                if (!Double.isNaN(worst[i]) && (at < 0 || worst[i] > worst[at])) {
                    at = i;
                }
            }
            if (at < 0) {
                return null;
            }
            for (String kind : List.of("generation", "loading")) {
                double[] series = timeline == null ? null
                    : "generation".equals(kind) ? timeline.chunksGeneratedSeries() : timeline.chunksLoadedSeries();
                if (series == null || series.length != worst.length) {
                    continue;
                }
                double sum = 0;
                double peak = 0;
                for (int i = 0; i < series.length; i++) {
                    double value = Double.isNaN(series[i]) ? 0 : series[i];
                    sum += value;
                    if (Math.abs(i - at) <= 1) {
                        peak = Math.max(peak, value);
                    }
                }
                double average = sum / series.length;
                if (average > 0 && peak >= CHUNK_SPIKE * average) {
                    return line("metrics", "Key metrics",
                        "Chunk " + kind + " at the worst tick against its average over the window. Named from "
                            + trimmed(CHUNK_SPIKE) + " times.",
                        text("Chunk " + kind + " spiked to "), bold(fixed(peak / average, 0) + " times"),
                        text(" its average at the worst tick."));
                }
            }
            return null;
        }

        private Entry xmxStep() {
            if (heapMax == null || heapMax <= 0) {
                return null;
            }
            double max = heapMax / GIB;
            long free = freeRam();
            if (free < 0) {
                double wanted = Math.floor(max * XMX_GROWTH * 2) / 2;
                return line("memory", "Memory & GC", null, text("Raise -Xmx from "), bold(trimmed(max) + " GiB"),
                    text(" to "), bold(trimmed(wanted) + " GiB"), text(" if the machine has that much RAM free."));
            }
            double room = free / GIB - RAM_HEADROOM_GIB;
            double wanted = Math.floor((max + Math.min(max * (XMX_GROWTH - 1), room)) * 2) / 2;
            if (wanted - max < 0.5) {
                return line("system", "System & hardware",
                    "Raising -Xmx needs free RAM on top of " + trimmed(RAM_HEADROOM_GIB) + " GiB left for the system.",
                    text("Free memory on the machine first: only "), bold(fixed(free / GIB, 1) + " GiB"),
                    text(" is free, too little to raise -Xmx."));
            }
            return line("memory", "Memory & GC",
                "About " + trimmed(XMX_GROWTH) + " times the current limit, keeping " + trimmed(RAM_HEADROOM_GIB)
                    + " GiB of RAM for the system.",
                text("Raise -Xmx from "), bold(trimmed(max) + " GiB"), text(" to "), bold(trimmed(wanted) + " GiB"),
                text(", the machine has "), bold(fixed(free / GIB, 1) + " GiB"), text(" free."));
        }

        private long freeRam() {
            JfrTimeline.Container container = timeline == null ? null : timeline.container();
            if (container != null && container.memLimit() > 0) {
                long rss = max(timeline.rssSeries());
                return rss > 0 ? Math.max(0, container.memLimit() - rss) : -1;
            }
            long totalRam = snapshot != null && snapshot.system() != null ? snapshot.system().totalRamBytes() : 0;
            long[] used = timeline == null ? null : timeline.hostMemSeries();
            long last = -1;
            for (int i = used == null ? -1 : used.length - 1; i >= 0; i--) {
                if (used[i] > 0) {
                    last = used[i];
                    break;
                }
            }
            return totalRam > 0 && last > 0 ? Math.max(0, totalRam - last) : -1;
        }

        private Entry allocatorStep(String key) {
            String id = key.substring(key.indexOf(':') + 1);
            if (key.startsWith("allocmod:")) {
                return line("plugins", "Plugins", null, text("Check "), bold(modName(id)),
                    text(possessive(id) + " settings, or report its memory use to its author with this bundle."));
            }
            return line("memory", "Memory & GC", null, text("Check the allocation paths under "), bold(id),
                text(" to see which code asked for the memory."));
        }

        private Entry reportStep(String mod, String reason, String value, String... rest) {
            List<Span> spans = new ArrayList<>(6);
            spans.add(text("Report it to "));
            spans.add(bold(modName(mod)));
            spans.add(text(" with this bundle: " + reason));
            if (value != null) {
                spans.add(bold(value));
            }
            spans.add(text((rest.length > 0 ? rest[0] : "") + "."));
            return new Entry(List.copyOf(spans), "plugins", "Plugins", null);
        }

        private String owner(String owner, String method) {
            if (owner != null && nameable(owner)) {
                return modName(owner);
            }
            return method != null && method.startsWith("com.hypixel.") ? "Hytale" : null;
        }

        private List<Span> loadSpans() {
            double load = snapshot.system().loadAverage();
            long cpus = cpus();
            return List.of(text("load "), bold(fixed(load, 1)), text(" on "), bold(number(cpus) + " " + cpuUnit(cpus)));
        }

        private long cpus() {
            JfrTimeline.CpuInfo info = timeline == null ? null : timeline.cpuInfo();
            return info != null && info.hwThreads() > 0 ? info.hwThreads()
                : snapshot != null && snapshot.cpu() != null && snapshot.cpu().cores() > 0 ? snapshot.cpu().cores()
                : info != null ? info.cores() : 0;
        }

        private String cpuUnit(long cpus) {
            return cpuUnitName(cpus) + (cpus == 1 ? "" : "s");
        }

        private String cpuUnitName(long cpus) {
            JfrTimeline.CpuInfo info = timeline == null ? null : timeline.cpuInfo();
            return info != null && info.cores() > 0 && info.cores() != cpus ? "CPU thread" : "core";
        }

        private long swapMoved() {
            JfrTimeline.Swap swap = timeline.swap();
            return swap.inBytes() >= 0 && swap.outBytes() >= 0 ? swap.inBytes() + swap.outBytes() : swap.growthBytes();
        }

        private Signal swapSignal() {
            JfrTimeline.Swap swap = timeline == null ? null : timeline.swap();
            if (swap == null) {
                return null;
            }
            String hover = "Shown from " + fmtBytes(SWAP_BYTES) + ". A machine short of RAM slows every program on it.";
            if (swap.inBytes() >= 0 && swap.outBytes() >= 0) {
                long moved = swap.inBytes() + swap.outBytes();
                return moved < SWAP_BYTES ? null : new Signal(line("system", "System & hardware", hover,
                    text("The machine moved "), bold(fmtBytes(moved)), text(" between RAM and swap, "),
                    bold(fmtBytes(swap.inBytes())), text(" of it read back.")), fmtBytes(moved) + " swapped");
            }
            return swap.growthBytes() < SWAP_BYTES ? null : new Signal(line("system", "System & hardware", hover,
                text("Swap in use grew by "), bold(fmtBytes(swap.growthBytes())), text(".")),
                "swap up " + fmtBytes(swap.growthBytes()));
        }

        private Signal loadSignal() {
            double load = snapshot == null || snapshot.system() == null ? -1 : snapshot.system().loadAverage();
            long cpus = cpus();
            if (load < 0 || cpus <= 0 || load < LOAD_PER_CPU * cpus) {
                return null;
            }
            String unit = cpuUnitName(cpus);
            return new Signal(new Entry(concat(List.of(text("The 1-minute load average was ")),
                List.of(bold(fixed(load, 1)), text(" on "), bold(number(cpus) + " " + cpuUnit(cpus)), text("."))),
                "system", "System & hardware", "The 1-minute average at capture. Shown from " + trimmed(LOAD_PER_CPU)
                    + " per " + unit + " (" + trimmed(LOAD_PER_CPU * cpus) + "). Programs waited for a free " + unit + "."),
                "load " + fixed(load, 0) + " on " + number(cpus) + " " + cpuUnit(cpus));
        }

        private Signal slowFileSignal() {
            JfrTimeline.SlowIo worst = null;
            int count = 0;
            for (JfrTimeline.SlowIo io : timeline == null ? List.<JfrTimeline.SlowIo>of() : timeline.slowIo()) {
                if (io.durationMs() >= SLOW_FILE_MS) {
                    count++;
                    if (worst == null || io.durationMs() > worst.durationMs()) {
                        worst = io;
                    }
                }
            }
            if (worst == null) {
                return null;
            }
            String path = ReportSystemWriter.maskPlayers(worst.path(), new LinkedHashMap<>()).replace('\\', '/');
            String op = "write".equals(worst.kind()) ? "write" : "read";
            List<Span> spans = new ArrayList<>(8);
            spans.add(text("A file " + op + " took "));
            spans.add(bold(fmt(Math.round(worst.durationMs())) + " ms"));
            spans.add(text(" ("));
            spans.add(code(path.substring(path.lastIndexOf('/') + 1)));
            spans.add(text(", " + fileKind(path) + ")"));
            if (count > 1) {
                spans.add(text(", "));
                spans.add(bold(number(count)));
                spans.add(text(" slow file operations in total"));
            }
            spans.add(text("."));
            return new Signal(new Entry(List.copyOf(spans), "memory", "Slow file operations",
                "Shown from " + trimmed(SLOW_FILE_MS) + " ms. A slow disk delays world saves and chunk loads."),
                "a " + fmt(Math.round(worst.durationMs())) + " ms file " + op);
        }

        private Signal diskSignal(long[] disk) {
            if (disk == null) {
                return null;
            }
            double free = disk[0];
            double size = disk[1];
            double pct = 100.0 * free / size;
            if (pct >= DISK_FREE_PCT && free >= DISK_FREE_GIB * GIB) {
                return null;
            }
            return new Signal(line("system", "System & hardware",
                "Shown under " + trimmed(DISK_FREE_PCT) + "% or " + trimmed(DISK_FREE_GIB)
                    + " GiB free. A full disk stops world saves and Hybox bundles.",
                text("The server disk had "), bold(fixed(free / GIB, 1) + " GiB"),
                text(" free of " + fixed(size / GIB, 1) + " GiB ("), bold(fixed(pct, 0) + "%"), text(").")),
                fixed(free / GIB, 1) + " GiB disk free");
        }
    }

    private static void addSignal(List<Entry> why, Set<String> used, Map<String, Signal> signals, String key) {
        Signal signal = key == null || used.contains(key) ? null : signals.get(key);
        if (signal != null && why.size() < MAX_WHY) {
            why.add(signal.entry());
            used.add(key);
        }
    }

    private static String first(Map<String, Signal> signals, String prefix) {
        for (String key : signals.keySet()) {
            if (key.startsWith(prefix)) {
                return key;
            }
        }
        return null;
    }

    private static List<Span> concat(List<Span> head, List<Span> tail) {
        List<Span> out = new ArrayList<>(head.size() + tail.size());
        out.addAll(head);
        out.addAll(tail);
        return List.copyOf(out);
    }

    private static String budgetShare(double ms, double budget) {
        double share = ms / budget;
        return share >= 1 ? fixed(share, 1) + " times" : fixed(100 * share, 0) + "% of";
    }

    private static String pauseText(double ms) {
        return ms >= 1000 ? Units.elapsed(Math.round(ms)) : fmt(Math.round(ms)) + " ms";
    }

    private static double mean(double[] values) {
        double sum = 0;
        int count = 0;
        for (double value : values == null ? new double[0] : values) {
            if (Double.isFinite(value)) {
                sum += value;
                count++;
            }
        }
        return count == 0 ? Double.NaN : sum / count;
    }

    private static long max(long[] values) {
        long peak = Long.MIN_VALUE;
        for (long value : values == null ? new long[0] : values) {
            peak = Math.max(peak, value);
        }
        return peak;
    }

    private static GcOverlap gcOverlap(IncidentReport report, JfrTimeline timeline,
                                       ReportActivityWriter.TickSelection tick) {
        if (timeline == null || timeline.gcPauses().isEmpty() || timeline.start() == null || timeline.end() == null) {
            return null;
        }
        long from;
        long to;
        double target;
        boolean stall;
        Long stallMs = ReportHtml.parseStallMs(report.context());
        if (stallMs != null && stallMs > 0 && "HEARTBEAT_STALL".equals(report.meta().trigger())) {
            long marker = ReportHtml.profileStartMs(report.context(), report.meta().createdAt().toEpochMilli());
            from = marker - stallMs - STALL_SLACK_MS;
            to = marker + STALL_SLACK_MS;
            target = stallMs;
            stall = true;
        } else {
            double[] worst = tick == null ? null : tick.worst();
            int at = -1;
            double peak = 0;
            for (int i = 0; worst != null && i < worst.length; i++) {
                if (!Double.isNaN(worst[i]) && worst[i] > peak) {
                    peak = worst[i];
                    at = i;
                }
            }
            if (at < 0) {
                return null;
            }
            long start = timeline.start().toEpochMilli();
            long span = timeline.end().toEpochMilli() - start + 1;
            from = start + span * at / worst.length - (long) Math.ceil(peak) - STALL_SLACK_MS;
            to = start + span * (at + 1) / worst.length;
            target = peak;
            stall = false;
        }
        double covered = 0;
        double longest = 0;
        int count = 0;
        String cause = null;
        for (JfrTimeline.GcPause pause : timeline.gcPauses()) {
            long overlapFrom = Math.max(from, pause.startMs());
            long overlapTo = Math.min(to, pause.endMs());
            if (overlapTo < overlapFrom) {
                continue;
            }
            covered += Math.min(pause.pauseMs(), Math.max(1, overlapTo - overlapFrom));
            count++;
            if (pause.pauseMs() > longest) {
                longest = pause.pauseMs();
                cause = pause.cause();
            }
        }
        return count == 0 ? null : new GcOverlap(stall, target, longest, count, Math.min(1, covered / target), cause);
    }

    private static boolean allocationFailure(String cause) {
        return cause != null && (cause.contains("Allocation Failure") || cause.contains("Humongous"));
    }

    private static String fileKind(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".region.bin")) return "a world region file";
        if (lower.contains("chunkstorage") || lower.contains("/chunks/")) return "chunk storage";
        if (lower.contains("/players/")) return "a player file";
        if (lower.contains("xytronix_hybox") || lower.contains(".hybox-") || lower.endsWith(".jfr")) return "a Hybox recording or bundle";
        if (lower.endsWith(".log") || lower.contains("/logs/")) return "a log file";
        if (lower.contains(".json")) return "a config or data file";
        return "a file";
    }

    void write(JsonWriter json) throws IOException {
        json.name("diagnosis").beginObject();
        json.name("kind").value(kind);
        json.name("cause");
        if (cause == null) {
            json.nullValue();
        } else {
            writeEntry(json, cause, confidence);
        }
        writeEntries(json, "why", why);
        writeEntries(json, "steps", steps);
        writeEntries(json, "other", other);
        json.name("terms").beginArray();
        for (ReportTerms.Term term : terms) {
            json.beginObject();
            json.name("term").value(term.term());
            json.name("meaning").value(term.meaning());
            json.name("doc").value(term.doc());
            json.endObject();
        }
        json.endArray();
        json.endObject();
    }

    private static void writeEntries(JsonWriter json, String name, List<Entry> entries) throws IOException {
        json.name(name).beginArray();
        for (Entry entry : entries) {
            writeEntry(json, entry, null);
        }
        json.endArray();
    }

    private static void writeEntry(JsonWriter json, Entry entry, String confidence) throws IOException {
        json.beginObject();
        json.name("text").value(entry.text());
        json.name("spans").beginArray();
        for (Span span : entry.spans()) {
            json.beginArray();
            json.value(span.text());
            json.value(span.style());
            if (span.term() != null) {
                json.value(span.term());
            }
            json.endArray();
        }
        json.endArray();
        json.name("section").value(entry.section());
        json.name("label").value(entry.label());
        json.name("hover").value(entry.hover());
        if (confidence != null) {
            json.name("confidence").value(confidence);
        }
        json.endObject();
    }

    private static Entry line(String section, String label, String hover, Span... spans) {
        return new Entry(List.of(spans), section, label, hover);
    }

    private static Span text(String value) { return new Span(value, ""); }
    private static Span bold(String value) { return new Span(value, "b"); }
    private static Span code(String value) { return new Span(value, "code"); }

    static boolean nameable(String id) {
        return !id.startsWith("com.hypixel.") && !id.startsWith("Hytale:") && !id.equals(JfrTimeline.CAPTURE_MOD);
    }

    static String modName(String id) {
        int colon = id.indexOf(':');
        return colon >= 0 && colon < id.length() - 1 ? id.substring(colon + 1) : id;
    }

    private static String possessive(String id) {
        return modName(id).endsWith("s") ? "'" : "'s";
    }

    private static List<World> worlds(IncidentReport report, JfrTimeline timeline) {
        Map<String, Long> samples = worldCpu(report.snapshot(), timeline);
        List<World> worlds = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        if (report.snapshot() != null) {
            for (HealthSnapshot.World world : report.snapshot().worlds()) {
                seen.add(ReportActivityWriter.worldKey(world.name()));
                worlds.add(new World(world.name(), ReportActivityWriter.samplesForWorld(samples, world.name()),
                    world.name().equals(report.meta().world()), world.players()));
            }
        }
        for (Map.Entry<String, Long> world : samples.entrySet()) {
            if (seen.add(ReportActivityWriter.worldKey(world.getKey()))) {
                worlds.add(new World(world.getKey(), world.getValue(), world.getKey().equals(report.meta().world()), -1));
            }
        }
        return worlds;
    }

    static Map<String, Long> worldCpu(HealthSnapshot snapshot, JfrTimeline timeline) {
        if (timeline == null) {
            return Map.of();
        }
        Map<String, Long> threads = timeline.worldSamples();
        Set<String> known = new HashSet<>();
        threads.keySet().forEach(name -> known.add(ReportActivityWriter.worldKey(name)));
        if (snapshot != null) {
            snapshot.worlds().forEach(world -> known.add(ReportActivityWriter.worldKey(world.name())));
        }
        Map<String, Long> attributed = new LinkedHashMap<>();
        for (JfrTimeline.WorldCpu world : timeline.cpuByWorld()) {
            if (known.contains(ReportActivityWriter.worldKey(world.world()))) {
                attributed.merge(world.world(), world.samples(), Long::sum);
            }
        }
        return attributed.isEmpty() ? threads : attributed;
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
        return average > 0 && maximum > EXCEPTION_SPIKE * average && maxIndex >= (int) Math.floor(series.length * 0.9)
            ? new ExceptionSpike(maximum, average) : null;
    }

    static String leafName(String method) {
        int last = method.lastIndexOf('.');
        int previous = last < 0 ? -1 : method.lastIndexOf('.', last - 1);
        int synthetic = method.indexOf('$', last + 1);
        return method.substring(previous + 1, synthetic < 0 ? method.length() : synthetic);
    }

    private static String percent(long numerator, long denominator, int digits) {
        return fixed(100.0 * numerator / denominator, digits);
    }

    private static String trimmed(double value) {
        String text = fixed(value, 1);
        return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
    }

    static String fixed(double value, int digits) {
        if (!Double.isFinite(value) || Math.abs(value) >= 1e21) {
            return number(value);
        }
        return Units.decimal(value, digits);
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
