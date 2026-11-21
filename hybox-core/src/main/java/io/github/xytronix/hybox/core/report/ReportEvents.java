package io.github.xytronix.hybox.core.report;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import io.github.xytronix.hybox.core.health.JfrTimeline;
import io.github.xytronix.hybox.core.json.JsonWriter;

final class ReportEvents {
    private static final int MAX_EVENTS = 120;
    private static final String MOD_TYPE = "mod:";

    private ReportEvents() {
    }

    private record ReportEvent(double frac, String type, String label, int count) {
        ReportEvent bump() {
            return new ReportEvent(frac, type, label, count + 1);
        }
    }

    static void write(JsonWriter json, List<ReportHtml.LogLine> log, JfrTimeline timeline, ModNames modNames,
                      String incidentId) throws IOException {
        List<ReportEvent> all = buildEvents(log, timeline, modNames, incidentId);
        int hidden = Math.max(0, all.size() - MAX_EVENTS);
        List<ReportEvent> shown = hidden > 0 ? all.subList(all.size() - MAX_EVENTS, all.size()) : all;
        writeEvents(json, shown);
        ReportJson.writeNullable(json, "eventsHidden", hidden > 0 ? (long) hidden : null);
    }

    private static String truncate(String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        int cut = Character.isHighSurrogate(s.charAt(max - 1)) ? max - 1 : max;
        return s.substring(0, cut) + "…";
    }

    private static List<ReportEvent> buildEvents(List<ReportHtml.LogLine> log, JfrTimeline timeline, ModNames modNames,
                                                 String incidentId) {
        if (timeline == null || timeline.start() == null || timeline.end() == null) {
            return List.of();
        }
        long start = timeline.start().toEpochMilli();
        long end = timeline.end().toEpochMilli();
        if (end <= start) {
            return List.of();
        }
        List<ReportEvent> raw = new ArrayList<>();
        List<JfrTimeline.ConnEvent> conns = timeline.connectionEvents();
        for (ReportHtml.LogLine line : log) {
            String type = switch (line.level()) {
                case "ERROR" -> "error";
                case "WARN" -> "warn";
                default -> conns.isEmpty() ? ReportHtml.connectionEventType(line) : null;
            };
            if (type == null || line.epochMs() < start || line.epochMs() > end) {
                continue;
            }
            String msg = line.message();
            int nl = msg.indexOf('\n');
            if (nl >= 0) {
                msg = msg.substring(0, nl);
            }
            msg = truncate(msg, 80);
            String label = line.source().isBlank() ? msg : line.source() + ": " + msg;
            raw.add(new ReportEvent((line.epochMs() - start) / (double) (end - start), type, label, 1));
        }
        for (long t : timeline.explicitGcTimes()) {
            if (t >= start && t <= end) {
                raw.add(new ReportEvent((t - start) / (double) (end - start), "gc", "System.gc()", 1));
            }
        }
        for (JfrTimeline.PluginEvent pe : timeline.pluginEvents()) {
            if (pe.timeMs() < start || pe.timeMs() > end) {
                continue;
            }
            int slash = pe.category().indexOf('/');
            String type = slash > 0 ? MOD_TYPE + modNames.id(pe.category().substring(0, slash)) : "plugin";
            String category = slash > 0 ? pe.category().substring(slash + 1) : pe.category();
            String label = category.isBlank() ? pe.message() : "[" + category + "] " + pe.message();
            raw.add(new ReportEvent((pe.timeMs() - start) / (double) (end - start), type, truncate(label, 80), 1));
        }
        for (JfrTimeline.ModAction action : timeline.modActions()) {
            if (action.timeMs() < start || action.timeMs() > end) {
                continue;
            }
            String label = action.category().isEmpty() ? action.message()
                : action.message().isEmpty() ? "[" + action.category() + "]"
                : "[" + action.category() + "] " + action.message();
            raw.add(new ReportEvent((action.timeMs() - start) / (double) (end - start),
                MOD_TYPE + modNames.id(action.mod()), truncate(label, 80), 1));
        }
        for (JfrTimeline.ConnEvent ce : conns) {
            if (ce.timeMs() < start || ce.timeMs() > end) {
                continue;
            }
            String label = ce.player() + " " + ce.phase()
                + (ce.detail() == null || ce.detail().isBlank() ? "" : " · " + ce.detail());
            label = truncate(label, 80);
            raw.add(new ReportEvent((ce.timeMs() - start) / (double) (end - start), "conn", label, 1));
        }
        for (JfrTimeline.IncidentMarker mark : timeline.incidentMarkers()) {
            if (mark.id().equals(incidentId) || mark.timeMs() < start || mark.timeMs() > end) {
                continue;
            }
            String label = mark.headline().isBlank() ? mark.id() : mark.id() + ": " + mark.headline();
            raw.add(new ReportEvent((mark.timeMs() - start) / (double) (end - start), "incident", truncate(label, 80), 1));
        }
        raw.sort((a, b) -> Double.compare(a.frac(), b.frac()));

        List<ReportEvent> collapsed = new ArrayList<>();
        for (ReportEvent event : raw) {
            ReportEvent last = collapsed.isEmpty() ? null : collapsed.get(collapsed.size() - 1);
            if (last != null && last.type().equals(event.type()) && event.frac() - last.frac() < 0.005) {
                collapsed.set(collapsed.size() - 1, last.bump());
            } else {
                collapsed.add(event);
            }
        }
        return collapsed;
    }

    private static void writeEvents(JsonWriter json, List<ReportEvent> events) throws IOException {
        if (events.isEmpty()) {
            json.name("events").nullValue();
            return;
        }
        json.name("events").beginArray();
        for (ReportEvent event : events) {
            json.beginArray();
            json.value(Math.round(event.frac() * 10000) / 10000.0);
            json.value(event.type());
            json.value(event.count() > 1 ? event.label() + " ×" + event.count() : event.label());
            json.endArray();
        }
        json.endArray();
    }
}
