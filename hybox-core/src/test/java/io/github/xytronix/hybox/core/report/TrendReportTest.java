package io.github.xytronix.hybox.core.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import io.github.xytronix.hybox.core.json.JsonScanner;
import io.github.xytronix.hybox.core.metrics.MetricsLog;

class TrendReportTest {
    private static final Instant NOW = Instant.parse("2026-06-08T00:00:00Z");
    private static final long WEEK_AGO = NOW.minus(Duration.ofDays(7)).toEpochMilli();

    @Test
    void denseDataUsesFixedTimeBucketsAndKeepsASingleSpike() throws Exception {
        MetricsLog.Trend trend = trend(10_080, WEEK_AGO, 60_000L);
        Arrays.fill(trend.tickAvgMs(), 50);
        Arrays.fill(trend.tps(), 20);
        trend.tickAvgMs()[5043] = 3000;
        trend.tps()[5043] = 2;
        trend.tickAvgMs()[10_079] = 73.25;

        Map<String, Object> data = data(render(trend, 7, List.of()));
        Map<String, Object> ticks = series(data, "tickAvgMs");

        assertEquals(false, data.get("collapsed"));
        assertEquals(WEEK_AGO, number(data.get("start")), 0);
        assertEquals(NOW.toEpochMilli() - 60_000L, number(data.get("end")), 0);
        assertEquals(TrendReport.BUCKETS, list(data, "t").size());
        assertEquals(TrendReport.BUCKETS, list(ticks, "buckets").size());
        assertTrue(list(data, "gaps").isEmpty());
        assertEquals(10_080, list(ticks, "buckets").stream().mapToDouble(b -> number(((Map<?, ?>) b).get("count"))).sum(), 0);
        Map<?, ?> spike = list(ticks, "buckets").stream().map(b -> (Map<?, ?>) b)
            .filter(b -> number(b.get("max")) == 3000).findFirst().orElseThrow();
        assertEquals(50, number(spike.get("min")), 0);
        assertTrue(number(spike.get("mean")) < 3000);
        assertEquals(50, number(ticks.get("p98")), 0);
        assertEquals(50, number(ticks.get("posMedian")), 0);
        assertEquals(50, number(ticks.get("posMin")), 0);
        Map<?, ?> log = (Map<?, ?>) data.get("logScale");
        assertEquals(100, number(log.get("span")), 0);
        assertEquals(List.of("tick", "gcPause", "alloc", "network", "heap", "loaded", "packets"), log.get("families"));
        Map<?, ?> observed = (Map<?, ?>) ticks.get("observed");
        assertEquals(3000, number(observed.get("max")), 0);
        assertEquals(73.25, number(observed.get("last")), 0);
        assertEquals(2, number(((Map<?, ?>) series(data, "tps").get("observed")).get("min")), 0);
        assertTrue(list(ticks, "brk").isEmpty());
    }

    @Test
    void sparseSessionsCollapseServerOffTimeAndBreakTheLine() throws Exception {
        long first = NOW.minus(Duration.ofHours(20)).toEpochMilli();
        long second = NOW.minus(Duration.ofHours(2)).toEpochMilli();
        long[] times = new long[720];
        for (int i = 0; i < 360; i++) {
            times[i] = first + i * 10_000L;
            times[360 + i] = second + i * 10_000L;
        }
        MetricsLog.Trend trend = trend(times);
        Arrays.fill(trend.tickAvgMs(), 4);

        Map<String, Object> data = data(render(trend, 1, List.of(
            new TrendReport.Incident(second + 600_000L, "DEGRADED", "World default is slow", "TICK_DEGRADED", "a.zip"),
            new TrendReport.Incident(NOW.minus(Duration.ofHours(10)).toEpochMilli(), "INFO", "Manual", "MANUAL", "b.zip"))));

        assertEquals(true, data.get("collapsed"));
        assertEquals(10_000, number(data.get("interval")), 0);
        assertEquals(first, number(data.get("start")), 0);
        List<Object> gaps = list(data, "gaps");
        assertEquals(1, gaps.size());
        List<?> gap = (List<?>) gaps.getFirst();
        assertEquals(times[359], number(gap.get(0)), 0);
        assertEquals(second, number(gap.get(1)), 0);
        double width = number(gap.get(3)) - number(gap.get(2));
        assertTrue(width > 0 && width < 0.05, "a 16 hour gap shrinks to a divider, not " + width);
        assertEquals(2, list(data, "sessions").size());
        Map<String, Object> ticks = series(data, "tickAvgMs");
        List<Object> breaks = list(ticks, "brk");
        assertEquals(1, breaks.size());
        int at = (int) number(breaks.getFirst());
        assertTrue(number(list(data, "t0").get(at)) >= second);
        assertTrue(number(list(data, "t1").get(at - 1)) <= times[359]);
        List<Object> incidents = list(data, "incidents");
        assertTrue(number(((Map<?, ?>) incidents.get(0)).get("x")) > number(gap.get(3)));
        assertTrue(number(((Map<?, ?>) incidents.get(1)).get("x")) >= number(gap.get(2)));
        assertTrue(number(((Map<?, ?>) incidents.get(1)).get("x")) <= number(gap.get(3)));
    }

    @Test
    void shortGapsBreakTheLineInLinearMode() throws Exception {
        long start = NOW.minus(Duration.ofHours(23)).toEpochMilli();
        long[] times = new long[8000];
        for (int i = 0; i < times.length; i++) {
            times[i] = start + i * 10_000L + (i >= 4000 ? 600_000L : 0);
        }
        MetricsLog.Trend trend = trend(times);
        Arrays.fill(trend.tps(), 30);

        Map<String, Object> data = data(render(trend, 1, List.of()));

        assertEquals(false, data.get("collapsed"));
        assertEquals(1, list(data, "gaps").size());
        assertEquals(1, list(series(data, "tps"), "brk").size());
        assertTrue(list(series(data, "tickAvgMs"), "brk").isEmpty());
    }

    @Test
    void missingValuesBreakTheLineWithoutBiasingStatistics() throws Exception {
        MetricsLog.Trend trend = trend(10_080, WEEK_AGO, 60_000L);
        for (int i = 0; i < trend.size(); i++) {
            trend.gcPct()[i] = i < 3000 || i >= 6000 ? 1 : Double.NaN;
        }
        trend.gcPct()[0] = Double.POSITIVE_INFINITY;

        Map<String, Object> data = data(render(trend, 7, List.of()));
        Map<String, Object> gc = series(data, "gcPct");

        Map<?, ?> observed = (Map<?, ?>) gc.get("observed");
        assertEquals(7079, number(observed.get("count")), 0);
        assertEquals(1, number(observed.get("mean")), 0);
        assertEquals(1, list(gc, "brk").size());
        Map<String, Object> missing = series(data, "allocMbPerSec");
        assertNull(((Map<?, ?>) missing.get("observed")).get("mean"));
        assertNull(missing.get("p98"));
        String html = render(trend, 7, List.of());
        String json = html.substring(html.indexOf("const D = "), html.indexOf(";\n", html.indexOf("const D = ")));
        assertFalse(json.contains("NaN") || json.contains("Infinity"));
    }

    @Test
    void targetPerSampleWinsOverTheFallbackAndOldRowsKeepTheFallback() throws Exception {
        MetricsLog.Trend old = trend(10, NOW.minusSeconds(600).toEpochMilli(), 10_000L);
        assertEquals(30, number(data(render(old, 7, List.of())).get("tpsTarget")), 0);
        MetricsLog.Trend recent = trend(10, NOW.minusSeconds(600).toEpochMilli(), 10_000L);
        Arrays.fill(recent.targetTps(), 20);
        recent.targetTps()[9] = 60;
        assertEquals(60, number(data(render(recent, 7, List.of())).get("tpsTarget")), 0);
    }

    @Test
    void singleSampleAndEmptyTrend() throws Exception {
        MetricsLog.Trend one = trend(1, NOW.minusSeconds(60).toEpochMilli(), 10_000L);
        one.tickAvgMs()[0] = 12.3456789;
        one.heapBytes()[0] = 42.125 * 1024 * 1024;
        Map<String, Object> data = data(render(one, 7, List.of()));
        assertEquals(1, list(data, "t").size());
        assertEquals(12.3456789, number(((Map<?, ?>) list(series(data, "tickAvgMs"), "buckets").getFirst()).get("mean")), 1e-9);
        assertEquals(42.125, number(((Map<?, ?>) series(data, "heapMb").get("observed")).get("max")), 1e-9);

        String html = render(MetricsLog.Trend.EMPTY, 7, List.of());
        Map<String, Object> empty = data(html);
        assertNull(empty.get("start"));
        assertTrue(list(empty, "t").isEmpty());
        assertTrue(html.contains("No health metrics recorded."));
        assertTrue(html.contains("Metrics.Enabled</code> to <code>true"));
        assertTrue(html.contains("/hybox reload"));
    }

    @Test
    void incidentsCarryTimeSeverityHeadlinePlainTriggerAndBundleFile() throws Exception {
        MetricsLog.Trend trend = trend(10, NOW.minusSeconds(600).toEpochMilli(), 60_000L);
        Arrays.fill(trend.tps(), 30);
        long at = NOW.minusSeconds(300).toEpochMilli();
        String html = render(trend, 7, List.of(new TrendReport.Incident(at, "DEGRADED",
            "World default stopped responding", "HEARTBEAT_STALL", "incident-20260601-000400.000+0000-000001.zip")));
        Map<?, ?> incident = (Map<?, ?>) list(data(html), "incidents").getFirst();
        assertEquals(at, number(incident.get("t")), 0);
        assertEquals("DEGRADED", incident.get("severity"));
        assertEquals("World default stopped responding", incident.get("headline"));
        assertEquals("Freeze", incident.get("trigger"));
        assertEquals("incident-20260601-000400.000+0000-000001.zip", incident.get("file"));
    }

    private static MetricsLog.Trend trend(int count, long start, long step) {
        long[] times = new long[count];
        for (int i = 0; i < count; i++) {
            times[i] = start + i * step;
        }
        return trend(times);
    }

    private static MetricsLog.Trend trend(long[] times) {
        int n = times.length;
        return new MetricsLog.Trend(times, missing(n), missing(n), missing(n), missing(n), missing(n), missing(n),
            missing(n), missing(n), missing(n), missing(n), missing(n), missing(n), missing(n), missing(n));
    }

    private static double[] missing(int count) {
        double[] values = new double[count];
        Arrays.fill(values, Double.NaN);
        return values;
    }

    private static String render(MetricsLog.Trend trend, int days, List<TrendReport.Incident> incidents) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        TrendReport.write(trend, days, NOW, incidents, 30, out);
        return out.toString(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(String html) throws Exception {
        String prefix = "const D = ";
        int start = html.indexOf(prefix);
        assertTrue(start >= 0);
        return (Map<String, Object>) new JsonScanner(html, start + prefix.length()).value();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> series(Map<String, Object> data, String name) {
        return (Map<String, Object>) data.get(name);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Map<?, ?> data, String name) {
        return (List<Object>) data.get(name);
    }

    private static double number(Object value) {
        return ((Number) value).doubleValue();
    }
}
