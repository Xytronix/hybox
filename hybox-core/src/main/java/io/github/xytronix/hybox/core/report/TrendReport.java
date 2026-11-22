package io.github.xytronix.hybox.core.report;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import io.github.xytronix.hybox.core.json.JsonWriter;
import io.github.xytronix.hybox.core.metrics.MetricsLog;
import io.github.xytronix.hybox.core.trigger.TriggerNames;

public final class TrendReport {
    public record Incident(long atMs, String severity, String headline, String trigger, String file) {}

    private static final String TEMPLATE_RESOURCE = "trend-template.html";
    private static final String TITLE_TOKEN = "__TITLE__";
    private static final String DATA_TOKEN = "/*__DATA__*/null";
    private static final int MAX_POINTS = 600;
    private static final double MB = 1024 * 1024;

    private static final DateTimeFormatter GENERATED_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.ROOT).withZone(ZoneOffset.UTC);

    private TrendReport() {
    }

    public static void write(MetricsLog.Trend trend, int days, Instant generatedAt, List<Incident> incidents,
                             OutputStream out) throws IOException {
        String template = loadTemplate();
        String html = replaceOnce(template, TITLE_TOKEN, "Hybox Health Trends · " + days + "d");
        String json = buildData(trend, days, generatedAt, incidents).replace("</", "<\\/");
        html = replaceOnce(html, DATA_TOKEN, json);
        OutputStreamWriter writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        writer.write(html);
        writer.flush();
    }

    private static String buildData(MetricsLog.Trend trend, int days, Instant generatedAt, List<Incident> incidents)
        throws IOException {
        int n = trend.size();
        int buckets = Math.min(n, MAX_POINTS);
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
            json.value((Number) trend.timestamps()[0]);
        }
        json.name("end");
        if (n == 0) {
            json.nullValue();
        } else {
            json.value((Number) trend.timestamps()[n - 1]);
        }

        json.name("t").beginArray();
        for (int b = 0; b < buckets; b++) {
            int lo = (int) ((long) b * n / buckets);
            int hi = (int) ((long) (b + 1) * n / buckets);
            json.value((Number) meanTime(trend.timestamps(), lo, hi));
        }
        json.endArray();

        writeSeries(json, "tickAvgMs", trend.tickAvgMs(), n, buckets, 1);
        writeSeries(json, "tps", trend.tps(), n, buckets, 1);
        writeSeries(json, "players", trend.players(), n, buckets, 1);
        writeSeries(json, "heapMb", trend.heapBytes(), n, buckets, MB);
        writeSeries(json, "rssMb", trend.rssBytes(), n, buckets, MB);
        writeSeries(json, "gcPct", trend.gcPct(), n, buckets, 1);
        writeSeries(json, "cpuPct", trend.cpuPct(), n, buckets, 1);
        writeSeries(json, "allocMbPerSec", trend.allocMbPerSec(), n, buckets, 1);
        writeSeries(json, "queuedPackets", trend.queuedPackets(), n, buckets, 1);

        json.name("incidents").beginArray();
        if (incidents != null) {
            for (Incident incident : incidents) {
                json.beginObject();
                json.name("t").value((Number) incident.atMs());
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

    private static void writeSeries(JsonWriter json, String name, double[] values, int n, int buckets,
                                    double divisor) throws IOException {
        json.name(name).beginObject();
        json.name("buckets").beginArray();
        double observedSum = 0;
        double observedMin = Double.POSITIVE_INFINITY;
        double observedMax = Double.NEGATIVE_INFINITY;
        double observedLast = Double.NaN;
        int observedCount = 0;
        for (int b = 0; b < buckets; b++) {
            int lo = (int) ((long) b * n / buckets);
            int hi = (int) ((long) (b + 1) * n / buckets);
            double sum = 0;
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            double last = Double.NaN;
            int count = 0;
            for (int i = lo; i < hi; i++) {
                double value = values[i];
                if (!Double.isFinite(value)) {
                    continue;
                }
                sum += value;
                min = Math.min(min, value);
                max = Math.max(max, value);
                last = value;
                count++;
                observedSum += value;
            }
            writeStatistics(json, sum, min, max, last, count, divisor);
            if (count != 0) {
                observedMin = Math.min(observedMin, min);
                observedMax = Math.max(observedMax, max);
                observedLast = last;
                observedCount += count;
            }
        }
        json.endArray();
        json.name("observed");
        writeStatistics(json, observedSum, observedMin, observedMax, observedLast, observedCount, divisor);
        json.endObject();
    }

    private static void writeStatistics(JsonWriter json, double sum, double min, double max,
                                        double last, int count, double divisor) throws IOException {
        json.beginObject();
        json.name("mean").value((Number) (count == 0 ? Double.NaN : sum / count / divisor));
        json.name("min").value((Number) (min / divisor));
        json.name("max").value((Number) (max / divisor));
        json.name("last").value((Number) (last / divisor));
        json.name("count").value((Number) count);
        json.endObject();
    }

    private static long meanTime(long[] values, int lo, int hi) {
        long sum = 0;
        for (int i = lo; i < hi; i++) {
            sum += values[i];
        }
        return sum / Math.max(1, hi - lo);
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
