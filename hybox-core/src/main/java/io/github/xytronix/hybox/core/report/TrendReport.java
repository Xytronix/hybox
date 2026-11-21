package io.github.xytronix.hybox.core.report;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import io.github.xytronix.hybox.core.json.JsonWriter;
import io.github.xytronix.hybox.core.metrics.MetricsLog;
import io.github.xytronix.hybox.core.metrics.TrendTimeline;
import io.github.xytronix.hybox.core.trigger.TriggerNames;

public final class TrendReport {
    public record Incident(long atMs, String severity, String headline, String trigger, String file) {}

    static final int BUCKETS = 480;
    private static final String TEMPLATE_RESOURCE = "trend-template.html";
    private static final String TITLE_TOKEN = "__TITLE__";
    private static final String DATA_TOKEN = "/*__DATA__*/null";
    private static final double MB = 1024 * 1024;

    private static final DateTimeFormatter GENERATED_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.ROOT).withZone(ZoneOffset.UTC);

    private TrendReport() {
    }

    public static void write(MetricsLog.Trend trend, int days, Instant generatedAt, List<Incident> incidents,
                             int tpsTarget, OutputStream out) throws IOException {
        String template = loadTemplate();
        String html = replaceOnce(template, TITLE_TOKEN, "Hybox Health Trends · " + days + "d");
        String json = buildData(trend, days, generatedAt, incidents, tpsTarget).replace("</", "<\\/");
        html = replaceOnce(html, DATA_TOKEN, json);
        OutputStreamWriter writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        writer.write(html);
        writer.flush();
    }

    private static String buildData(MetricsLog.Trend trend, int days, Instant generatedAt, List<Incident> incidents,
                                    int tpsTarget) throws IOException {
        long to = generatedAt.toEpochMilli();
        long from = generatedAt.minus(Duration.ofDays(Math.max(1, days))).toEpochMilli();
        TrendTimeline timeline = TrendTimeline.of(trend.timestamps(), from, to, BUCKETS);
        int n = timeline.samples();
        int[] counts = timeline.sampleCounts();
        int[] shown = new int[counts.length];
        int emitted = 0;
        for (int b = 0; b < counts.length; b++) {
            shown[b] = counts[b] > 0 ? emitted++ : -1;
        }
        StringBuilder out = new StringBuilder(8192);
        JsonWriter json = new JsonWriter(out);
        json.beginObject();
        json.name("days").value((Number) days);
        json.name("generated").value(GENERATED_FORMAT.format(generatedAt));
        json.name("count").value((Number) n);
        json.name("start");
        if (n == 0) {
            json.nullValue();
        } else {
            json.value((Number) timeline.from());
        }
        json.name("end");
        if (n == 0) {
            json.nullValue();
        } else {
            json.value((Number) timeline.to());
        }
        json.name("collapsed").value(timeline.collapsed());
        json.name("interval").value((Number) timeline.intervalMs());

        long[] times = timeline.bucketTimes();
        long[][] ranges = timeline.bucketRanges();
        json.name("t").beginArray();
        for (int b = 0; b < counts.length; b++) {
            if (counts[b] > 0) {
                json.value((Number) times[b]);
            }
        }
        json.endArray();
        json.name("t0").beginArray();
        for (int b = 0; b < counts.length; b++) {
            if (counts[b] > 0) {
                json.value((Number) ranges[b][0]);
            }
        }
        json.endArray();
        json.name("t1").beginArray();
        for (int b = 0; b < counts.length; b++) {
            if (counts[b] > 0) {
                json.value((Number) ranges[b][1]);
            }
        }
        json.endArray();
        json.name("x").beginArray();
        for (int b = 0; b < counts.length; b++) {
            if (counts[b] > 0) {
                json.value((Number) round(timeline.bucketX(b)));
            }
        }
        json.endArray();
        json.name("gaps").beginArray();
        for (TrendTimeline.Gap gap : timeline.gaps()) {
            json.beginArray();
            json.value((Number) gap.startMs());
            json.value((Number) gap.endMs());
            json.value((Number) round(gap.fromX()));
            json.value((Number) round(gap.toX()));
            json.endArray();
        }
        json.endArray();
        json.name("sessions").beginArray();
        long[] sessions = timeline.sessionStarts();
        for (int s = 0; s < sessions.length; s++) {
            json.beginArray();
            json.value((Number) sessions[s]);
            json.value((Number) round(timeline.sessionX(s)));
            json.endArray();
        }
        json.endArray();

        writeSeries(json, "tickAvgMs", timeline.series(trend.tickAvgMs()), shown);
        writeSeries(json, "tps", timeline.series(trend.tps()), shown);
        writeSeries(json, "players", timeline.series(trend.players()), shown);
        writeSeries(json, "heapMb", timeline.series(trend.heapBytes(), MB), shown);
        writeSeries(json, "rssMb", timeline.series(trend.rssBytes(), MB), shown);
        writeSeries(json, "gcPct", timeline.series(trend.gcPct()), shown);
        writeSeries(json, "cpuPct", timeline.series(trend.cpuPct()), shown);
        writeSeries(json, "allocMbPerSec", timeline.series(trend.allocMbPerSec()), shown);
        writeSeries(json, "queuedPackets", timeline.series(trend.queuedPackets()), shown);
        TrendTimeline.Series targets = timeline.series(trend.targetTps());
        writeSeries(json, "targetTps", targets, shown);
        writeSeries(json, "maxViewRadius", timeline.series(trend.maxViewRadius()), shown);
        writeSeries(json, "viewRadiusMedian", timeline.series(trend.viewRadiusMedian()), shown);
        writeSeries(json, "hotRadiusMedian", timeline.series(trend.hotRadiusMedian()), shown);
        writeSeries(json, "loadedSections", timeline.series(trend.loadedSections()), shown);
        long target = targets.observedCount() > 0 && targets.last() > 0 ? Math.round(targets.last()) : tpsTarget;
        ReportJson.writeNullable(json, "tpsTarget", target > 0 ? target : null);
        ReportJson.writeLogScale(json);

        json.name("incidents").beginArray();
        if (incidents != null) {
            for (Incident incident : incidents) {
                json.beginObject();
                json.name("t").value((Number) incident.atMs());
                json.name("x");
                if (n == 0 || incident.atMs() < timeline.from() || incident.atMs() > timeline.to()) {
                    json.nullValue();
                } else {
                    json.value((Number) round(timeline.x(incident.atMs())));
                }
                json.name("severity").value(incident.severity());
                json.name("headline").value(incident.headline());
                json.name("trigger").value(incident.trigger() == null ? null : TriggerNames.label(incident.trigger()));
                json.name("file").value(incident.file());
                json.endObject();
            }
        }
        json.endArray();

        json.endObject();
        return out.toString();
    }

    private static void writeSeries(JsonWriter json, String name, TrendTimeline.Series series, int[] shown)
        throws IOException {
        json.name(name).beginObject();
        json.name("buckets").beginArray();
        for (int b = 0; b < shown.length; b++) {
            if (shown[b] >= 0) {
                writeStatistics(json, series.mean()[b], series.min()[b], series.max()[b], series.lastIn()[b],
                    series.count()[b]);
            }
        }
        json.endArray();
        json.name("observed");
        writeStatistics(json, series.observedMean(), series.observedMin(), series.observedMax(), series.last(),
            series.observedCount());
        json.name("p98").value((Number) series.stats().p98());
        json.name("posMedian").value((Number) series.stats().positiveMedian());
        json.name("posMin").value((Number) series.stats().positiveMin());
        json.name("brk").beginArray();
        for (int b = 0; b < shown.length; b++) {
            if (shown[b] >= 0 && series.breakBefore()[b]) {
                json.value((Number) shown[b]);
            }
        }
        json.endArray();
        json.endObject();
    }

    private static void writeStatistics(JsonWriter json, double mean, double min, double max, double last, int count)
        throws IOException {
        json.beginObject();
        json.name("mean").value((Number) (count == 0 ? Double.NaN : mean));
        json.name("min").value((Number) (count == 0 ? Double.NaN : min));
        json.name("max").value((Number) (count == 0 ? Double.NaN : max));
        json.name("last").value((Number) (count == 0 ? Double.NaN : last));
        json.name("count").value((Number) count);
        json.endObject();
    }

    private static double round(double fraction) {
        return Math.round(fraction * 1e5) / 1e5;
    }

    private static String loadTemplate() throws IOException {
        try (InputStream in = TrendReport.class.getResourceAsStream(TEMPLATE_RESOURCE)) {
            if (in == null) {
                throw new IOException("Missing resource " + TEMPLATE_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String replaceOnce(String haystack, String token, String replacement) throws IOException {
        int i = haystack.indexOf(token);
        if (i < 0) {
            throw new IOException("Trend template is missing token " + token);
        }
        return haystack.substring(0, i) + replacement + haystack.substring(i + token.length());
    }
}
