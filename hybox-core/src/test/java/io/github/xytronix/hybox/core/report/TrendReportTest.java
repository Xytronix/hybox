package io.github.xytronix.hybox.core.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import io.github.xytronix.hybox.core.metrics.MetricsLog;

class TrendReportTest {
    private static final Instant START = Instant.parse("2026-06-01T00:00:00Z");
    private static final Pattern STATISTICS = Pattern.compile(
        "\\{\"mean\":([^,]+),\"min\":([^,]+),\"max\":([^,]+),\"last\":([^,]+),\"count\":(\\d+)}");

    @Test
    void downsamplingPreservesSingleSpikeForDisplayedMaximumAndTrueLast() throws Exception {
        MetricsLog.Trend trend = trend(10_080);
        Arrays.fill(trend.tickAvgMs(), 50);
        Arrays.fill(trend.tps(), 20);
        trend.tickAvgMs()[5043] = 3000;
        trend.tps()[5043] = 2;
        trend.tickAvgMs()[10_079] = 73.25;
        trend.tps()[10_079] = 19.5;

        String data = data(render(trend));
        Series ticks = series(data, "tickAvgMs");
        Series tps = series(data, "tps");

        assertEquals(600, ticks.buckets().size());
        assertStatistics(ticks.buckets().get(300), 234.375, 50, 3000, 50, 16);
        assertStatistics(ticks.buckets().get(599), (16 * 50 + 73.25) / 17, 50, 73.25, 73.25, 17);
        assertStatistics(ticks.observed(), (10_078 * 50 + 3000 + 73.25) / 10_080,
            50, 3000, 73.25, 10_080);
        assertStatistics(tps.buckets().get(300), (15 * 20 + 2.0) / 16, 2, 20, 20, 16);
        assertEquals(2.0, tps.observed().min(), 0.000000001);
        assertEquals(19.5, tps.observed().last(), 0.000000001);
        assertTrue(data.contains("\"count\":10080,\"start\":" + START.toEpochMilli()
            + ",\"end\":" + trend.timestamps()[10_079]));
    }

    @Test
    void missingValuesLeaveBucketGapsAndDoNotBiasObservedStatistics() throws Exception {
        MetricsLog.Trend trend = trend(1200);
        trend.tickAvgMs()[0] = 2;
        trend.tickAvgMs()[1] = 6;
        trend.tickAvgMs()[2] = Double.POSITIVE_INFINITY;
        trend.tickAvgMs()[3] = Double.NEGATIVE_INFINITY;
        trend.tickAvgMs()[4] = 12;
        trend.tickAvgMs()[1198] = 100;

        String data = data(render(trend));
        Series ticks = series(data, "tickAvgMs");

        assertEquals(600, ticks.buckets().size());
        assertStatistics(ticks.buckets().get(0), 4, 2, 6, 6, 2);
        assertMissing(ticks.buckets().get(1));
        assertStatistics(ticks.buckets().get(2), 12, 12, 12, 12, 1);
        assertMissing(ticks.buckets().get(3));
        assertStatistics(ticks.buckets().get(599), 100, 100, 100, 100, 1);
        assertStatistics(ticks.observed(), 30, 2, 100, 100, 4);
        Series unavailable = series(data, "tps");
        assertMissing(unavailable.observed());
        assertTrue(unavailable.buckets().stream().allMatch(bucket -> bucket.count() == 0));
        assertFalse(data.contains("NaN"));
        assertFalse(data.contains("Infinity"));
    }

    @Test
    void unevenBucketsKeepEverySampleAndFractionalMeans() throws Exception {
        MetricsLog.Trend trend = trend(601);
        for (int i = 0; i < trend.size(); i++) {
            trend.players()[i] = i;
        }

        Series players = series(data(render(trend)), "players");

        assertEquals(600, players.buckets().size());
        assertEquals(601, players.buckets().stream().mapToInt(Statistics::count).sum());
        assertStatistics(players.buckets().get(0), 0, 0, 0, 0, 1);
        assertStatistics(players.buckets().get(599), 599.5, 599, 600, 600, 2);
        assertStatistics(players.observed(), 300, 0, 600, 600, 601);
    }

    @Test
    void singleSamplePreservesPrecisionUnitsAndZeros() throws Exception {
        MetricsLog.Trend trend = trend(1);
        trend.tickAvgMs()[0] = 12.3456789;
        trend.heapBytes()[0] = 42.125 * 1024 * 1024;
        trend.tps()[0] = 0;

        String data = data(render(trend));
        Series ticks = series(data, "tickAvgMs");
        Series heap = series(data, "heapMb");
        Series tps = series(data, "tps");

        assertEquals(1, ticks.buckets().size());
        assertStatistics(ticks.buckets().getFirst(), 12.3456789, 12.3456789, 12.3456789, 12.3456789, 1);
        assertEquals(ticks.buckets().getFirst(), ticks.observed());
        assertStatistics(heap.observed(), 42.125, 42.125, 42.125, 42.125, 1);
        assertEquals(heap.observed(), heap.buckets().getFirst());
        assertStatistics(tps.observed(), 0, 0, 0, 0, 1);
        assertTrue(data.contains("\"t\":[" + START.toEpochMilli() + "]"));
    }

    @Test
    void emptyTrendIncludesRealMetricsConfigurationGuidance() throws Exception {
        String html = render(MetricsLog.Trend.EMPTY);
        String data = data(html);
        Series ticks = series(data, "tickAvgMs");

        assertTrue(data.contains("\"count\":0,\"start\":null,\"end\":null,\"t\":[]"));
        assertTrue(ticks.buckets().isEmpty());
        assertMissing(ticks.observed());
        assertTrue(html.contains("No health metrics recorded."));
        assertTrue(html.contains("hybox.json"));
        assertTrue(html.contains("Metrics.Enabled</code> to <code>true"));
        assertTrue(html.contains("/hybox reload"));
        assertTrue(html.contains("Metrics history in <code>/hybox settings</code>"));
    }

    @Test
    void incidentsCarryTimeSeverityHeadlinePlainTriggerAndBundleFile() throws Exception {
        MetricsLog.Trend trend = trend(10);
        Arrays.fill(trend.tps(), 30);
        long at = START.toEpochMilli() + 4 * 60_000L;
        String data = data(render(trend, List.of(new TrendReport.Incident(at, "DEGRADED",
            "World default stopped responding", "HEARTBEAT_STALL", "incident-20260601-000400.000+0000-000001.zip"))));

        assertTrue(data.contains("\"incidents\":[{\"t\":" + at + ",\"severity\":\"DEGRADED\","
            + "\"headline\":\"World default stopped responding\",\"trigger\":\"Freeze\","
            + "\"file\":\"incident-20260601-000400.000+0000-000001.zip\"}]"));
    }

    private static MetricsLog.Trend trend(int count) {
        long[] timestamps = new long[count];
        for (int i = 0; i < count; i++) {
            timestamps[i] = START.toEpochMilli() + i * 60_000L;
        }
        return new MetricsLog.Trend(timestamps, missing(count), missing(count), missing(count),
            missing(count), missing(count), missing(count), missing(count), missing(count), missing(count));
    }

    private static double[] missing(int count) {
        double[] values = new double[count];
        Arrays.fill(values, Double.NaN);
        return values;
    }

    private static String render(MetricsLog.Trend trend) throws Exception {
        return render(trend, List.of());
    }

    private static String render(MetricsLog.Trend trend, List<TrendReport.Incident> incidents) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        TrendReport.write(trend, 7, START.plusSeconds(7 * 86_400L), incidents, out);
        return out.toString(StandardCharsets.UTF_8);
    }

    private static String data(String html) {
        String prefix = "const D = ";
        int start = html.indexOf(prefix);
        assertTrue(start >= 0);
        start += prefix.length();
        int end = html.indexOf(";\n", start);
        assertTrue(end >= 0);
        return html.substring(start, end);
    }

    private static Series series(String data, String name) {
        Matcher series = Pattern.compile("\"" + Pattern.quote(name)
            + "\":\\{\"buckets\":\\[(.*?)\\],\"observed\":(\\{[^}]*})}").matcher(data);
        assertTrue(series.find(), "Missing series " + name);
        List<Statistics> buckets = new ArrayList<>();
        Matcher bucket = STATISTICS.matcher(series.group(1));
        while (bucket.find()) {
            buckets.add(statistics(bucket.group()));
        }
        return new Series(buckets, statistics(series.group(2)));
    }

    private static Statistics statistics(String data) {
        Matcher matcher = STATISTICS.matcher(data);
        assertTrue(matcher.matches(), "Invalid statistics " + data);
        return new Statistics(number(matcher.group(1)), number(matcher.group(2)), number(matcher.group(3)),
            number(matcher.group(4)), Integer.parseInt(matcher.group(5)));
    }

    private static Double number(String value) {
        return value.equals("null") ? null : Double.valueOf(value);
    }

    private static void assertStatistics(Statistics statistics, double mean, double min,
                                         double max, double last, int count) {
        assertEquals(count, statistics.count());
        assertEquals(mean, statistics.mean(), 0.000000001);
        assertEquals(min, statistics.min(), 0.000000001);
        assertEquals(max, statistics.max(), 0.000000001);
        assertEquals(last, statistics.last(), 0.000000001);
    }

    private static void assertMissing(Statistics statistics) {
        assertEquals(0, statistics.count());
        assertNull(statistics.mean());
        assertNull(statistics.min());
        assertNull(statistics.max());
        assertNull(statistics.last());
    }

    private record Statistics(Double mean, Double min, Double max, Double last, int count) {
    }

    private record Series(List<Statistics> buckets, Statistics observed) {
    }
}
