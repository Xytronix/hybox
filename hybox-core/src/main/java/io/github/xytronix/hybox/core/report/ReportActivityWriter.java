package io.github.xytronix.hybox.core.report;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.xytronix.hybox.core.health.FrameLabels;
import io.github.xytronix.hybox.core.health.HealthSnapshot;
import io.github.xytronix.hybox.core.health.JfrTimeline;
import io.github.xytronix.hybox.core.incident.IncidentMetadata;
import io.github.xytronix.hybox.core.incident.IncidentReport;
import io.github.xytronix.hybox.core.json.JsonWriter;

final class ReportActivityWriter {

    private static final double SLOWER_WORLD_TPS = 1;

    private ReportActivityWriter() {
    }

    record TickSelection(String world, double[] tps, double[] mspt, double[] worst, String reason,
                         double[] incidentMspt, double[] incidentWorst) {}

    static TickSelection selectTicks(IncidentReport report, JfrTimeline timeline) {
        if (timeline == null || timeline.ticks().isEmpty()) {
            return null;
        }
        IncidentMetadata meta = report.meta();
        HealthSnapshot snapshot = report.snapshot();
        Map<String, JfrTimeline.WorldTicks> ticks = timeline.ticks();
        String world = null;
        String reason;
        if (meta.world() != null && ticks.containsKey(meta.world())) {
            world = meta.world();
            reason = "incident";
        } else {
            long best = -1;
            for (String candidate : ticks.keySet()) {
                long samples = samplesOrZero(timeline, candidate);
                if (samples > best) {
                    best = samples;
                    world = candidate;
                }
            }
            reason = "busiest";
        }
        String picked = world;
        String slowest = world;
        double slowestShare = tpsShare(ticks.get(world), HealthSnapshot.targetTps(snapshot, world));
        for (Map.Entry<String, JfrTimeline.WorldTicks> candidate : ticks.entrySet()) {
            double share = tpsShare(candidate.getValue(), HealthSnapshot.targetTps(snapshot, candidate.getKey()));
            if (share < slowestShare) {
                slowest = candidate.getKey();
                slowestShare = share;
            }
        }
        JfrTimeline.WorldTicks slow = ticks.get(slowest);
        double slowTarget = HealthSnapshot.targetTps(snapshot, slowest);
        double worldTarget = HealthSnapshot.targetTps(snapshot, world);
        if (!slowest.equals(world)
            && slowestShare * slowTarget <= tpsShare(ticks.get(world), worldTarget) * slowTarget - SLOWER_WORLD_TPS
            && ReportJson.min(slow.tps()) < 0.9 * slowTarget && ReportJson.min(ticks.get(world).tps()) >= 0.9 * worldTarget) {
            world = slowest;
            reason = "slowest";
        }
        JfrTimeline.WorldTicks wt = ticks.get(world);
        if (wt == null) {
            return null;
        }
        JfrTimeline.WorldTicks incident = !world.equals(picked) && picked.equals(meta.world())
            ? ticks.get(picked) : null;
        return new TickSelection(world, wt.tps(), wt.mspt(),
            worstOf(report, timeline, wt, world.equals(meta.world())), reason,
            incident == null ? null : incident.mspt(),
            incident == null ? null : worstOf(report, timeline, incident, true));
    }

    private static double[] worstOf(IncidentReport report, JfrTimeline timeline, JfrTimeline.WorldTicks ticks,
                                    boolean incidentWorld) {
        if (ticks.worst() != null || !incidentWorld) {
            return ticks.worst();
        }
        return stallPoint(report, timeline, ticks.tps() == null ? 0 : ticks.tps().length);
    }

    private static double tpsShare(JfrTimeline.WorldTicks ticks, double target) {
        return ticks == null || ticks.tps() == null || ticks.tps().length == 0
            ? Double.POSITIVE_INFINITY : ReportJson.avg(ticks.tps()) / target;
    }

    private static double[] stallPoint(IncidentReport report, JfrTimeline timeline, int buckets) {
        Long stallMs = ReportHtml.parseStallMs(report.context());
        if (stallMs == null || buckets < 2 || timeline.start() == null || timeline.end() == null
            || !"HEARTBEAT_STALL".equals(report.meta().trigger())) {
            return null;
        }
        long start = timeline.start().toEpochMilli();
        long span = timeline.end().toEpochMilli() - start;
        long marker = ReportHtml.profileStartMs(report.context(), report.meta().createdAt().toEpochMilli());
        if (span <= 0 || marker < start || marker > start + span) {
            return null;
        }
        double[] series = new double[buckets];
        java.util.Arrays.fill(series, Double.NaN);
        series[(int) Math.round((double) (marker - start) / span * (buckets - 1))] = stallMs;
        return series;
    }

    static void writeTps(JsonWriter json, TickSelection tick, HealthSnapshot snapshot) throws IOException {
        if (tick == null || tick.tps() == null) {
            json.name("tps").nullValue();
            return;
        }
        json.name("tps").beginObject();
        json.name("world").value(tick.world());
        json.name("pick").value(tick.reason());
        ReportJson.writeNullable(json, "target", Math.round(HealthSnapshot.targetTps(snapshot, tick.world())));
        json.name("avg").value(ReportJson.round2(ReportJson.avg(tick.tps())));
        json.name("min").value(ReportJson.round2(ReportJson.min(tick.tps())));
        json.endObject();
    }

    static void writeSeries(JsonWriter json, JfrTimeline timeline, TickSelection tick) throws IOException {
        json.name("series").beginObject();
        if (timeline != null) {
            ReportJson.writeDoubleSeries(json, "cpu", timeline.cpuMachineSeries());
            ReportJson.writeDoubleSeries(json, "cpuJvm", timeline.cpuJvmSeries());
            ReportJson.writeLongSeries(json, "heap", timeline.heapSeries());
            ReportJson.writeLongSeries(json, "heapCommitted", timeline.heapCommittedSeries());
            ReportJson.writeLongSeries(json, "rss", timeline.rssSeries());
            ReportJson.writeLongSeries(json, "hostMem", timeline.hostMemSeries());
            ReportJson.writeDoubleSeries(json, "entities", timeline.entitiesSeries());
            ReportJson.writeDoubleSeries(json, "chunks", timeline.chunksSeries());
            ReportJson.writeDoubleSeries(json, "chunksGenerated", timeline.chunksGeneratedSeries());
            ReportJson.writeDoubleSeries(json, "chunksLoaded", timeline.chunksLoadedSeries());
            ReportJson.writeDoubleSeries(json, "netIn", timeline.netInSeries());
            ReportJson.writeDoubleSeries(json, "netOut", timeline.netOutSeries());
            ReportJson.writeLongSeries(json, "threads", timeline.threadSeries());
            ReportJson.writeDoubleSeries(json, "gcPause", timeline.gcPauseSeries());
            ReportJson.writeDoubleSeries(json, "gcTime", timeline.gcTimeSeries());
            ReportJson.writeDoubleSeries(json, "players", timeline.playersSeries());
            ReportJson.writeDoubleSeries(json, "exceptions", timeline.exceptionsSeries());
            ReportJson.writeDoubleSeries(json, "connects", timeline.connectsSeries());
            ReportJson.writeDoubleSeries(json, "joins", timeline.joinsSeries());
            ReportJson.writeDoubleSeries(json, "leaves", timeline.leavesSeries());
            ReportJson.writeDoubleSeries(json, "ping", timeline.pingSeries());
            ReportJson.writeLongSeries(json, "disk", timeline.diskFreeSeries());
            ReportJson.writeDoubleSeries(json, "diskRead", timeline.diskReadSeries());
            ReportJson.writeDoubleSeries(json, "diskWrite", timeline.diskWriteSeries());
        }
        if (tick != null) {
            ReportJson.writeDoubleSeries(json, "tps", tick.tps());
            ReportJson.writeDoubleSeries(json, "mspt", tick.mspt());
            ReportJson.writeSparseSeries(json, "msptWorst", tick.worst());
            if (tick.incidentMspt() != null) {
                ReportJson.writeDoubleSeries(json, "msptIncident", tick.incidentMspt());
                ReportJson.writeSparseSeries(json, "msptWorstIncident", tick.incidentWorst());
            }
        }
        json.endObject();
    }

    static void writePluginMetrics(JsonWriter json, JfrTimeline timeline, ModNames modNames) throws IOException {
        List<JfrTimeline.PluginMetric> metrics = timeline == null ? List.of() : timeline.pluginMetrics();
        if (metrics.isEmpty()) {
            json.name("pluginMetrics").nullValue();
            return;
        }
        List<Map.Entry<String, JfrTimeline.PluginMetric>> named = new ArrayList<>(metrics.size());
        for (JfrTimeline.PluginMetric metric : metrics) {
            String name = metric.mod() == null ? metric.name()
                : modNames.id(metric.mod()) + metric.name().substring(metric.mod().length());
            named.add(Map.entry(name, metric));
        }
        named.sort(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER));
        json.name("pluginMetrics").beginArray();
        for (Map.Entry<String, JfrTimeline.PluginMetric> entry : named) {
            JfrTimeline.PluginMetric metric = entry.getValue();
            boolean counter = "count".equals(metric.kind());
            json.beginObject();
            json.name("name").value(entry.getKey());
            json.name("label").value(ModNames.metricLabel(entry.getKey()));
            json.name("kind").value(metric.kind());
            json.name("events").value(metric.events());
            json.name("uncharted").value(metric.uncharted());
            json.name("total").value(ReportJson.round2(metric.total()));
            if (counter) {
                json.name("bucketSec").value(significant(metric.bucketSeconds()));
                ReportJson.writeNullableDouble(json, "cumulative",
                    Double.isFinite(metric.cumulative()) ? metric.cumulative() : null);
                if (metric.amounts() != null) {
                    ReportJson.writeDoubleSeries(json, "amounts", metric.amounts());
                }
            }
            json.name("series");
            if (metric.series() == null) {
                json.nullValue();
            } else {
                json.beginArray();
                for (double v : metric.series()) {
                    json.value(counter ? significant(v) : ReportJson.round2(v));
                }
                json.endArray();
            }
            json.endObject();
        }
        json.endArray();
    }

    private static double significant(double v) {
        return v == 0 || !Double.isFinite(v) ? v : new BigDecimal(v).round(new MathContext(4)).doubleValue();
    }

    record TickSystemRow(String system, String world, double avg1m, double max1m,
                         double avg5m, double max5m) {}

    static List<TickSystemRow> tickSystemRows(Map<String, String> tickSystems) {
        List<TickSystemRow> rows = new ArrayList<>();
        if (tickSystems == null) {
            return rows;
        }
        for (Map.Entry<String, String> e : tickSystems.entrySet()) {
            String key = e.getKey().replaceFirst(" #\\d+$", "");
            int at = key.lastIndexOf(" @ ");
            if ("Note".equals(e.getKey()) || at < 0) {
                continue;
            }
            String[] parts = e.getValue().trim().split("\\s+");
            if (parts.length < 4) {
                continue;
            }
            try {
                rows.add(new TickSystemRow(
                    key.substring(0, at), key.substring(at + " @ ".length()),
                    Double.parseDouble(parts[0]), Double.parseDouble(parts[1]),
                    Double.parseDouble(parts[2]), Double.parseDouble(parts[3])));
            } catch (NumberFormatException ignored) {
            }
        }
        rows.sort((a, b) -> Double.compare(b.avg1m(), a.avg1m()));
        return rows;
    }

    static void writeTickSystems(JsonWriter json, Map<String, String> tickSystems) throws IOException {
        String note = tickSystems == null ? null : tickSystems.get("Note");
        List<TickSystemRow> rows = tickSystemRows(tickSystems);
        if (rows.isEmpty()) {
            json.name("tickSystems").nullValue();
        } else {
            json.name("tickSystems").beginArray();
            for (int i = 0; i < rows.size() && i < 20; i++) {
                TickSystemRow row = rows.get(i);
                json.beginArray();
                json.value(row.system());
                json.value(row.world());
                json.value(ReportJson.round2(row.avg1m()));
                json.value(ReportJson.round2(row.max1m()));
                json.value(ReportJson.round2(row.avg5m()));
                json.value(ReportJson.round2(row.max5m()));
                json.endArray();
            }
            json.endArray();
        }
        json.name("tickSystemsNote").value(note);
    }

    static void writeTickSeries(JsonWriter json, JfrTimeline timeline) throws IOException {
        List<JfrTimeline.SystemTick> series = timeline == null ? List.of() : timeline.tickSeries();
        if (series.isEmpty()) {
            json.name("tickSeries").nullValue();
            return;
        }
        json.name("tickSeries").beginArray();
        for (JfrTimeline.SystemTick entry : series) {
            json.beginObject();
            json.name("name").value(entry.name());
            ReportJson.writeDoubleSeries(json, "series", entry.series());
            json.endObject();
        }
        json.endArray();
    }

    static void writeModCpu(JsonWriter json, List<JfrTimeline.ModCpu> recorded, ReportAttribution attribution,
                            Map<String, Integer> threadGroups, long samples) throws IOException {
        List<JfrTimeline.ModCpu> rows = attribution.cpu(recorded);
        if (rows.isEmpty()) {
            json.name("modCpu").nullValue();
            return;
        }
        json.name("modCpu").beginArray();
        for (JfrTimeline.ModCpu row : rows) {
            json.beginArray();
            json.value(row.id());
            json.value(row.samples());
            json.value(row.method());
            writeThreadShares(json, row.threads(), threadGroups);
            json.value(row.self());
            json.value(row.total());
            json.value(row.selfMethod());
            writeThreadShares(json, row.selfThreads(), threadGroups);
            json.value(FrameLabels.labelOf(row.selfMethod()));
            json.value(row.totalMethod());
            json.value(FrameLabels.labelOf(row.totalMethod()));
            json.value(ModNames.onStackSentence(row.id(), row.total(), samples, "samples", row.totalMethod()));
            json.endArray();
        }
        json.endArray();
    }

    static void writeSliceTop(JsonWriter json, JfrTimeline timeline, ReportAttribution attribution) throws IOException {
        JfrTimeline.Slices slices = timeline == null ? null : timeline.slices();
        if (slices == null) {
            json.name("sliceTop").nullValue();
            return;
        }
        json.name("sliceTop").beginObject();
        json.name("sec").value(slices.seconds());
        json.name("min").value(slices.minSamples());
        json.name("frames").beginArray();
        for (JfrTimeline.SliceFrame frame : slices.frames()) {
            json.beginArray();
            json.value(frame.method());
            json.value(frame.mod() == null ? null : attribution.canonical(frame.mod()));
            json.endArray();
        }
        json.endArray();
        json.name("threads").beginArray();
        for (String thread : slices.threads()) {
            json.value(thread);
        }
        json.endArray();
        json.name("b").beginArray();
        for (int[] bucket : slices.buckets()) {
            json.beginArray();
            for (int v : bucket) {
                json.value(v);
            }
            json.endArray();
        }
        json.endArray();
        json.endObject();
    }

    private static void writeThreadShares(JsonWriter json, List<JfrTimeline.ThreadShare> threads,
                                          Map<String, Integer> threadGroups) throws IOException {
        json.beginArray();
        for (JfrTimeline.ThreadShare thread : threads) {
            json.beginArray();
            json.value(thread.thread());
            json.value(thread.samples());
            writeGroupSize(json, thread.thread(), threadGroups);
            json.endArray();
        }
        json.endArray();
    }

    static void writeGroupSize(JsonWriter json, String thread, Map<String, Integer> threadGroups) throws IOException {
        Integer size = threadGroups.get(thread);
        if (size != null) {
            json.value(size.longValue());
        }
    }

    static void writeFlame(JsonWriter json, JfrTimeline timeline) throws IOException {
        JfrTimeline.Flame flame = timeline == null ? null : timeline.flame();
        json.name("flame");
        if (flame == null) {
            json.nullValue();
            return;
        }
        writeFlameNode(json, flame);
    }

    private static void writeFlameNode(JsonWriter json, JfrTimeline.Flame node) throws IOException {
        json.beginArray();
        json.value(node.name());
        json.value(node.samples());
        json.beginArray();
        for (JfrTimeline.Flame child : node.children()) {
            writeFlameNode(json, child);
        }
        json.endArray();
        json.value(node.owner());
        if (node.label() != null) {
            json.value(node.label());
        }
        json.endArray();
    }

    static void writeThreadTimeline(JsonWriter json, JfrTimeline timeline) throws IOException {
        if (timeline == null || timeline.threadTimeline().isEmpty()) {
            json.name("threadTimeline").nullValue();
            return;
        }
        json.name("threadTimeline").beginArray();
        for (JfrTimeline.ThreadLane lane : timeline.threadTimeline()) {
            json.beginObject();
            json.name("name").value(lane.name());
            json.name("states").beginArray();
            for (int state : lane.states()) {
                json.value(state);
            }
            json.endArray();
            json.endObject();
        }
        json.endArray();
    }

    static void writeEntities(JsonWriter json, Map<String, String> entities) throws IOException {
        if (entities == null || entities.isEmpty()) {
            json.name("entities").nullValue();
            return;
        }
        json.name("entities").beginArray();
        for (Map.Entry<String, String> e : entities.entrySet()) {
            json.beginArray();
            json.value(e.getKey());
            json.beginArray();
            for (String part : e.getValue().split(", ")) {
                int idx = part.lastIndexOf(" x");
                if (idx <= 0) {
                    continue;
                }
                try {
                    long count = Long.parseLong(part.substring(idx + 2).trim());
                    json.beginArray();
                    json.value(part.substring(0, idx).trim());
                    json.value(count);
                    json.endArray();
                } catch (NumberFormatException ignored) {
                }
            }
            json.endArray();
            json.endArray();
        }
        json.endArray();
    }

    static void writeThreads(JsonWriter json, HealthSnapshot snapshot, JfrTimeline timeline,
                             ReportAttribution attribution) throws IOException {
        HealthSnapshot.Threads threads = snapshot == null ? null : snapshot.threads();
        ReportJson.writeNullable(json, "threadTotal", threads != null && threads.total() > 0 ? (long) threads.total() : null);
        if (threads == null || threads.byState().isEmpty()) {
            json.name("threadStates").nullValue();
        } else {
            json.name("threadStates").beginObject();
            for (Map.Entry<String, Integer> e : threads.byState().entrySet()) {
                json.name(e.getKey()).value(e.getValue());
            }
            json.endObject();
        }
        if (threads == null || threads.deadlocked().isEmpty()) {
            json.name("deadlocked").nullValue();
        } else {
            json.name("deadlocked").beginArray();
            for (String d : threads.deadlocked()) {
                json.value(d);
            }
            json.endArray();
        }

        json.name("hotThreads").beginArray();
        if (snapshot != null) {
            Map<String, String> states = timeline == null ? Map.of() : timeline.threadStates();
            for (HealthSnapshot.HotThread hot : snapshot.hotThreads()) {
                json.beginArray();
                json.value(hot.samples());
                json.value(states.get(hot.thread()));
                json.value(hot.thread());
                json.value(hot.topMethod());
                json.beginArray();
                for (HealthSnapshot.MethodSample m : hot.methods()) {
                    json.beginArray();
                    json.value(m.method());
                    json.value(m.samples());
                    String label = FrameLabels.labelOf(m.method());
                    if (label != null) {
                        json.value(label);
                    }
                    json.endArray();
                }
                json.endArray();
                String topLabel = FrameLabels.labelOf(hot.topMethod());
                Map<String, JfrTimeline.ModShare> shares = attribution.shares(hot.mods());
                if (topLabel != null || !shares.isEmpty()) {
                    json.value(topLabel);
                }
                if (!shares.isEmpty()) {
                    json.beginArray();
                    List<Map.Entry<String, JfrTimeline.ModShare>> mods = new ArrayList<>(shares.entrySet());
                    mods.sort((a, b) -> a.getValue().total() != b.getValue().total()
                        ? Long.compare(b.getValue().total(), a.getValue().total())
                        : Long.compare(b.getValue().self(), a.getValue().self()));
                    for (Map.Entry<String, JfrTimeline.ModShare> mod : mods) {
                        json.beginArray();
                        json.value(mod.getKey());
                        json.value(mod.getValue().self());
                        json.value(mod.getValue().total());
                        json.endArray();
                    }
                    json.endArray();
                }
                json.endArray();
            }
        }
        json.endArray();
    }

    static void writeHotMethods(JsonWriter json, JfrTimeline timeline) throws IOException {
        json.name("hotMethods").beginArray();
        if (timeline != null) {
            for (JfrTimeline.HotMethod hm : timeline.hotMethods()) {
                json.beginArray();
                json.value(hm.samples());
                json.value(hm.method());
                json.beginArray();
                for (String caller : hm.callers()) {
                    json.value(caller);
                }
                json.endArray();
                String label = FrameLabels.labelOf(hm.method());
                if (label != null || !hm.callerLabels().isEmpty()) {
                    json.value(label);
                }
                if (!hm.callerLabels().isEmpty()) {
                    json.beginArray();
                    for (String callerLabel : hm.callerLabels()) {
                        json.value(callerLabel);
                    }
                    json.endArray();
                }
                json.endArray();
            }
        }
        json.endArray();
    }

    static void writeWorlds(JsonWriter json, IncidentMetadata meta, HealthSnapshot snapshot,
                            JfrTimeline timeline) throws IOException {
        Map<String, Long> samples = ReportFindings.worldCpu(snapshot, timeline);
        json.name("worlds").beginArray();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        if (snapshot != null) {
            for (HealthSnapshot.World w : snapshot.worlds()) {
                seen.add(worldKey(w.name()));
                json.beginObject();
                json.name("name").value(w.name());
                ReportJson.writeNullable(json, "samples", samplesForWorld(samples, w.name()));
                json.name("stalled").value(w.name().equals(meta.world()));
                ReportJson.writeNullable(json, "players", w.players() >= 0 ? (long) w.players() : null);
                if (w.playerNames().isEmpty()) {
                    json.name("playerNames").nullValue();
                } else {
                    json.name("playerNames").beginArray();
                    for (String name : w.playerNames()) {
                        json.value(name);
                    }
                    json.endArray();
                }
                ReportJson.writeNullable(json, "entities", w.entities() >= 0 ? (long) w.entities() : null);
                ReportJson.writeNullable(json, "chunks", w.chunks() >= 0 ? (long) w.chunks() : null);
                long[] churn = timeline == null ? null : timeline.chunkChurnByWorld().get(w.name());
                ReportJson.writeNullable(json, "chunksGenerated", churn != null && churn[0] >= 0 ? churn[0] : null);
                ReportJson.writeNullable(json, "chunksLoaded", churn != null && churn[1] >= 0 ? churn[1] : null);
                ReportJson.writeNullableDouble(json, "tps", w.tps() >= 0 ? w.tps() : null);
                ReportJson.writeNullableDouble(json, "mspt", w.mspt() >= 0 ? w.mspt() : null);
                ReportJson.writeNullableDouble(json, "msptP50", w.msptP50() >= 0 ? w.msptP50() : null);
                ReportJson.writeNullableDouble(json, "msptP95", w.msptP95() >= 0 ? w.msptP95() : null);
                ReportJson.writeNullableDouble(json, "msptMax", w.msptMax() >= 0 ? w.msptMax() : null);
                ReportJson.writeNullable(json, "targetTps", w.targetTps() > 0 ? (long) w.targetTps() : null);
                writeRadius(json, w.radius());
                json.endObject();
            }
        }
        for (Map.Entry<String, Long> e : samples.entrySet()) {
            if (!seen.add(worldKey(e.getKey()))) {
                continue;
            }
            json.beginObject();
            json.name("name").value(e.getKey());
            json.name("samples").value(e.getValue());
            json.name("stalled").value(e.getKey().equals(meta.world()));
            json.name("players").nullValue();
            json.name("playerNames").nullValue();
            json.name("entities").nullValue();
            json.name("chunks").nullValue();
            json.name("chunksGenerated").nullValue();
            json.name("chunksLoaded").nullValue();
            json.name("tps").nullValue();
            json.name("mspt").nullValue();
            json.name("msptP50").nullValue();
            json.name("msptP95").nullValue();
            json.name("msptMax").nullValue();
            json.name("targetTps").nullValue();
            json.name("radius").nullValue();
            json.endObject();
        }
        json.endArray();
    }

    private static void writeRadius(JsonWriter json, HealthSnapshot.Radius radius) throws IOException {
        json.name("radius");
        if (radius == null) {
            json.nullValue();
            return;
        }
        json.beginObject();
        json.name("players").value((Number) radius.players());
        json.name("view").beginArray();
        known(json, radius.viewMin());
        known(json, radius.viewMedian());
        known(json, radius.viewMax());
        json.endArray();
        json.name("hot").beginArray();
        known(json, radius.hotMin());
        known(json, radius.hotMedian());
        known(json, radius.hotMax());
        json.endArray();
        ReportJson.writeNullable(json, "sections", radius.sections() >= 0 ? radius.sections() : null);
        ReportJson.writeNullable(json, "sectionsMax", radius.sectionsMax() >= 0 ? (long) radius.sectionsMax() : null);
        json.name("hotLowered").value((Number) radius.hotLowered());
        ReportJson.writeNullable(json, "hotLoweredTo", radius.hotLoweredTo() >= 0 ? (long) radius.hotLoweredTo() : null);
        json.name("top").beginArray();
        for (HealthSnapshot.PlayerLoad load : radius.top()) {
            json.beginArray();
            json.value(load.name());
            known(json, load.view());
            known(json, load.hot());
            known(json, load.sections());
            json.endArray();
        }
        json.endArray();
        json.endObject();
    }

    private static void known(JsonWriter json, int value) throws IOException {
        if (value < 0) {
            json.nullValue();
        } else {
            json.value((Number) value);
        }
    }

    static String worldKey(String world) {
        return world == null ? null : world.toLowerCase(Locale.ROOT);
    }

    static Long samplesForWorld(Map<String, Long> samples, String name) {
        Long exact = samples.get(name);
        if (exact != null) {
            return exact;
        }
        String key = worldKey(name);
        long sum = 0;
        boolean found = false;
        for (Map.Entry<String, Long> e : samples.entrySet()) {
            if (worldKey(e.getKey()).equals(key)) {
                sum += e.getValue();
                found = true;
            }
        }
        return found ? sum : null;
    }

    private static long samplesOrZero(JfrTimeline timeline, String world) {
        Long matched = samplesForWorld(timeline.worldSamples(), world);
        return matched == null ? 0L : matched;
    }

    static void writeWorldTps(JsonWriter json, JfrTimeline timeline, TickSelection tick)
        throws IOException {
        if (timeline == null || timeline.ticks().isEmpty()) {
            json.name("worldTps").nullValue();
            return;
        }
        Map<String, JfrTimeline.WorldTicks> ticks = timeline.ticks();
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        if (tick != null) {
            selected.add(tick.world());
        }
        ticks.keySet().stream()
            .sorted((a, b) -> Long.compare(samplesOrZero(timeline, b), samplesOrZero(timeline, a)))
            .forEach(selected::add);
        json.name("worldTps").beginArray();
        for (String world : selected) {
            JfrTimeline.WorldTicks wt = ticks.get(world);
            if (wt == null || wt.tps() == null) {
                continue;
            }
            json.beginObject();
            json.name("name").value(world);
            ReportJson.writeDoubleSeries(json, "series", wt.tps());
            json.endObject();
        }
        json.endArray();
    }

    static void writeSubsystems(JsonWriter json, JfrTimeline timeline) throws IOException {
        if (timeline == null || timeline.subsystems().isEmpty()) {
            json.name("subsystems").nullValue();
            return;
        }
        json.name("subsystems").beginArray();
        for (Map.Entry<String, Long> e : timeline.subsystems().entrySet()) {
            json.beginArray();
            json.value(e.getKey());
            json.value(e.getValue());
            json.endArray();
        }
        json.endArray();
    }

    static void writeCpuByWorld(JsonWriter json, JfrTimeline timeline, ReportAttribution attribution)
        throws IOException {
        List<JfrTimeline.WorldCpu> worlds = timeline == null ? List.of() : timeline.cpuByWorld();
        if (worlds.isEmpty()) {
            json.name("cpuByWorld").nullValue();
            return;
        }
        json.name("cpuByWorld").beginArray();
        for (JfrTimeline.WorldCpu w : worlds) {
            json.beginObject();
            json.name("world").value(w.world());
            json.name("samples").value(w.samples());
            json.name("subsystems").beginArray();
            for (Map.Entry<String, Long> e : w.subsystems().entrySet()) {
                json.beginArray();
                json.value(e.getKey());
                json.value(e.getValue());
                json.endArray();
            }
            json.endArray();
            json.name("mods");
            writeModShares(json, attribution.shares(w.mods()));
            json.endObject();
        }
        json.endArray();
    }

    static void writeModShares(JsonWriter json, Map<String, JfrTimeline.ModShare> mods) throws IOException {
        json.beginArray();
        for (Map.Entry<String, JfrTimeline.ModShare> e : mods.entrySet()) {
            json.beginArray();
            json.value(e.getKey());
            json.value(e.getValue().samples());
            json.value(e.getValue().self());
            json.value(e.getValue().total());
            json.endArray();
        }
        json.endArray();
    }
}
