package io.github.xytronix.hybox.hytale;

import io.github.xytronix.hybox.core.metrics.ChartAxis;
import io.github.xytronix.hybox.core.metrics.MetricsLog;
import io.github.xytronix.hybox.core.metrics.TrendTimeline;
import io.github.xytronix.hybox.core.text.Units;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

record HyboxTrendModel(boolean metricsEnabled, int days, Instant generatedAt, int samples,
                       long firstSample, long lastSample, List<Metric> metrics, List<String> unavailable,
                       double[] markers, TrendTimeline timeline) {
    static final int BUCKETS = HyboxChartImage.WIDTH / 2;
    static final String RSS = "Process RSS";
    private static final int DEFAULT_TARGET_TPS = 30;
    private static final Set<String> QUIET = Set.of("View radius", "Hot radius", "Loaded sections");

    record Metric(String title, String unit, int precision, ChartAxis.Family family, TrendTimeline.Series series,
                  HyboxChartRenderer.Ref ref, String note) {
        double min() { return series.observedMin(); }
        double average() { return series.observedMean(); }
        double max() { return series.observedMax(); }
        double now() { return series.last(); }
    }

    static int days(int requested) {
        return Math.max(1, Math.min(30, requested));
    }

    static HyboxTrendModel from(boolean enabled, int requestedDays, Instant now, MetricsLog.Trend trend, long[] incidents,
                                int targetTps) {
        int days = days(requestedDays);
        long from = now.minus(Duration.ofDays(days)).toEpochMilli(), to = now.toEpochMilli();
        TrendTimeline timeline = TrendTimeline.of(trend.timestamps(), from, to, BUCKETS);
        int samples = timeline.samples();
        long first = 0, last = 0;
        for (long time : trend.timestamps()) {
            if (time < from || time > to) continue;
            if (first == 0) first = time;
            last = time;
        }
        TrendTimeline.Series targets = timeline.series(trend.targetTps());
        double target = targets.observedCount() > 0 && targets.last() > 0 ? Math.round(targets.last())
            : targetTps > 0 ? targetTps : DEFAULT_TARGET_TPS;
        List<Metric> metrics = new ArrayList<>(12);
        List<String> unavailable = new ArrayList<>();
        if (enabled && samples > 0) {
            add(metrics, unavailable, timeline, "Tick time", "ms", 1, ChartAxis.Family.TICK_TIME, trend.tickAvgMs(),
                new HyboxChartRenderer.Ref(1000 / target, Units.decimal(1000 / target, 1) + " ms budget"), null);
            add(metrics, unavailable, timeline, "TPS", "TPS", 1, ChartAxis.Family.TPS, trend.tps(),
                new HyboxChartRenderer.Ref(target, Units.decimal(target, 0) + " TPS target", true), null);
            add(metrics, unavailable, timeline, "Players online", "players", 0, ChartAxis.Family.PLAYERS, trend.players(), null, null);
            add(metrics, unavailable, timeline, "Heap", "GiB", 2, ChartAxis.Family.HEAP, trend.heapBytes(), null, null);
            add(metrics, unavailable, timeline, RSS, "GiB", 2, ChartAxis.Family.HEAP, trend.rssBytes(), null, null);
            add(metrics, unavailable, timeline, "CPU use", "%", 1, ChartAxis.Family.CPU, trend.cpuPct(), null, null);
            add(metrics, unavailable, timeline, "GC time", "%", 1, ChartAxis.Family.SHARE, trend.gcPct(), null, null);
            add(metrics, unavailable, timeline, "Allocation rate", "MiB/s", 1, ChartAxis.Family.ALLOCATION, trend.allocMbPerSec(), null, null);
            add(metrics, unavailable, timeline, "Queued packets", "packets", 0, ChartAxis.Family.PACKETS, trend.queuedPackets(), null, null);
            TrendTimeline.Series maxView = timeline.series(trend.maxViewRadius());
            add(metrics, unavailable, timeline, "View radius", "chunks", 0, ChartAxis.Family.OTHER, trend.viewRadiusMedian(), null,
                maxView.observedCount() > 0 ? "server max " + Units.decimal(maxView.last(), 0) : null);
            add(metrics, unavailable, timeline, "Hot radius", "chunks", 0, ChartAxis.Family.OTHER, trend.hotRadiusMedian(), null, null);
            add(metrics, unavailable, timeline, "Loaded sections", "sections", 0, ChartAxis.Family.LOADED, trend.loadedSections(), null, null);
        }
        double[] markers = Arrays.stream(incidents).filter(time -> samples > 0 && time >= timeline.from() && time <= timeline.to())
            .mapToDouble(timeline::x).toArray();
        return new HyboxTrendModel(enabled, days, now, samples, first, last, List.copyOf(metrics), List.copyOf(unavailable),
            markers, timeline);
    }

    private static void add(List<Metric> metrics, List<String> unavailable, TrendTimeline timeline, String title, String unit,
                            int precision, ChartAxis.Family family, double[] values, HyboxChartRenderer.Ref ref, String note) {
        TrendTimeline.Series series = timeline.series(values);
        if (series.isEmpty()) {
            if (!QUIET.contains(title)) unavailable.add(title);
            return;
        }
        String chartTitle = Set.of("TPS", "players", "packets", "sections").contains(unit) ? title : title + " (" + unit + ")";
        metrics.add(new Metric(chartTitle, unit, precision, family, series, ref, note));
    }
}
