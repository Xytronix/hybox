package io.github.xytronix.hybox.core.report;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.xytronix.hybox.core.health.JfrTimeline;
import io.github.xytronix.hybox.core.incident.IncidentReport;
import io.github.xytronix.hybox.core.json.JsonWriter;

final class ReportLocks {
    static final double SHARE = 0.5;
    static final String WORLD_THREAD_PREFIX = "WorldThread - ";
    private static final long TICK_SLACK_MS = 1000;
    private static final int ROWS = 10;
    private static final String JUC = "java.util.concurrent.";

    record Overlap(String thread, boolean stall, double targetMs, long totalMs, double share, int locks,
                   JfrTimeline.LockWait lead, boolean gcExcluded) {}

    record Row(String thread, String lock, String owner, String frame, String mod, long totalMs, int count) {}

    private record Key(String thread, String lock, String frame, String mod) {}

    private ReportLocks() {
    }

    static Overlap overlap(IncidentReport report, JfrTimeline timeline, ReportActivityWriter.TickSelection tick) {
        if (timeline == null || timeline.lockWaits().isEmpty()) {
            return null;
        }
        String world;
        long from;
        long to;
        double target;
        boolean stall;
        Long stallMs = ReportHtml.parseStallMs(report.context());
        if (stallMs != null && stallMs > 0 && "HEARTBEAT_STALL".equals(report.meta().trigger())
            && report.meta().world() != null) {
            to = ReportHtml.profileStartMs(report.context(), report.meta().createdAt().toEpochMilli());
            from = to - stallMs;
            target = stallMs;
            stall = true;
            world = report.meta().world();
        } else {
            double[] worst = tick == null ? null : tick.worst();
            if (worst == null || tick.world() == null || timeline.start() == null || timeline.end() == null) {
                return null;
            }
            int at = -1;
            double peak = 0;
            for (int i = 0; i < worst.length; i++) {
                if (!Double.isNaN(worst[i]) && worst[i] > peak) {
                    peak = worst[i];
                    at = i;
                }
            }
            if (at < 0 || peak < JfrTimeline.LOCK_WAIT_MS) {
                return null;
            }
            long start = timeline.start().toEpochMilli();
            long span = timeline.end().toEpochMilli() - start + 1;
            from = start + span * at / worst.length - (long) Math.ceil(peak) - TICK_SLACK_MS;
            to = start + span * (at + 1) / worst.length;
            target = peak;
            stall = false;
            world = tick.world();
        }
        String thread = WORLD_THREAD_PREFIX + world;
        List<JfrTimeline.GcPause> pauses = timeline.gcPauses();
        Map<String, long[]> byLock = new LinkedHashMap<>();
        Map<String, JfrTimeline.LockWait> leads = new LinkedHashMap<>();
        long covered = 0;
        long total = 0;
        boolean gcExcluded = false;
        for (JfrTimeline.LockWait wait : timeline.lockWaits()) {
            long overlapFrom = Math.max(from, wait.startMs());
            long overlapTo = Math.min(to, wait.endMs());
            if (!thread.equals(wait.thread()) || overlapTo <= overlapFrom) {
                continue;
            }
            long inWindow = outsideGc(overlapFrom, overlapTo, pauses);
            long whole = outsideGc(wait.startMs(), wait.endMs(), pauses);
            gcExcluded |= whole < wait.durationMs();
            covered += inWindow;
            total += whole;
            String key = lockName(wait.lock());
            long[] sum = byLock.computeIfAbsent(key, k -> new long[1]);
            sum[0] += inWindow;
            JfrTimeline.LockWait lead = leads.get(key);
            if (lead == null || wait.durationMs() > lead.durationMs()) {
                leads.put(key, wait);
            }
        }
        double share = Math.min(1, covered / target);
        if (share < SHARE) {
            return null;
        }
        String top = null;
        for (Map.Entry<String, long[]> en : byLock.entrySet()) {
            if (top == null || en.getValue()[0] > byLock.get(top)[0]) {
                top = en.getKey();
            }
        }
        return new Overlap(thread, stall, target, total, share, byLock.size(), leads.get(top), gcExcluded);
    }

    private static long outsideGc(long from, long to, List<JfrTimeline.GcPause> pauses) {
        long gc = 0;
        for (JfrTimeline.GcPause pause : pauses) {
            long overlap = Math.min(to, pause.endMs()) - Math.max(from, pause.startMs());
            if (overlap > 0) {
                gc += Math.min(Math.round(pause.pauseMs()), overlap);
            }
        }
        return Math.max(0, to - from - gc);
    }

    static List<Row> rows(JfrTimeline timeline, ReportAttribution attribution) {
        if (timeline == null || timeline.lockWaits().isEmpty()) {
            return List.of();
        }
        Map<Key, long[]> sums = new LinkedHashMap<>();
        Map<Key, Map<String, long[]>> owners = new LinkedHashMap<>();
        for (JfrTimeline.LockWait wait : timeline.lockWaits()) {
            Key key = new Key(wait.group(), lockName(wait.lock()) + (wait.monitor() ? " (synchronized)" : ""),
                wait.frame(), wait.mod() == null ? null : attribution.canonical(wait.mod()));
            long[] sum = sums.computeIfAbsent(key, k -> new long[2]);
            sum[0] += wait.durationMs();
            sum[1]++;
            if (wait.owner() != null) {
                owners.computeIfAbsent(key, k -> new LinkedHashMap<>())
                    .computeIfAbsent(wait.owner(), k -> new long[1])[0] += wait.durationMs();
            }
        }
        List<Row> rows = new ArrayList<>(sums.size());
        sums.forEach((key, sum) -> {
            String owner = null;
            long best = -1;
            for (Map.Entry<String, long[]> en : owners.getOrDefault(key, Map.of()).entrySet()) {
                if (en.getValue()[0] > best) {
                    best = en.getValue()[0];
                    owner = en.getKey();
                }
            }
            rows.add(new Row(key.thread(), key.lock(), owner, key.frame(), key.mod(), sum[0], (int) sum[1]));
        });
        rows.sort((a, b) -> Long.compare(b.totalMs(), a.totalMs()));
        return rows.subList(0, Math.min(ROWS, rows.size()));
    }

    static void write(JsonWriter json, JfrTimeline timeline, ReportAttribution attribution) throws IOException {
        List<Row> rows = rows(timeline, attribution);
        if (rows.isEmpty()) {
            json.name("locks").nullValue();
            return;
        }
        json.name("locks").beginObject();
        json.name("minMs").value(JfrTimeline.LOCK_WAIT_MS);
        json.name("rows").beginArray();
        for (Row row : rows) {
            json.beginArray();
            json.value(row.thread());
            json.value(row.lock());
            json.value(row.totalMs());
            json.value(row.count());
            json.value(row.frame());
            json.value(row.mod());
            json.value(row.owner());
            json.endArray();
        }
        json.endArray();
        json.endObject();
    }

    static String lockName(String type) {
        if (type == null) {
            return "";
        }
        String simple = type.substring(type.lastIndexOf('.') + 1);
        int nested = simple.indexOf('$');
        if (nested > 0 && type.startsWith(JUC)) {
            String inner = simple.substring(nested + 1);
            if (inner.equals("ConditionObject")) {
                return "Condition";
            }
            if (inner.endsWith("Sync") || inner.equals("Signaller")) {
                return simple.substring(0, nested);
            }
        }
        return simple.replace('$', '.');
    }
}
