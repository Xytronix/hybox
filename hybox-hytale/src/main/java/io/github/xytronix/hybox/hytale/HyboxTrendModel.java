package io.github.xytronix.hybox.hytale;

import io.github.xytronix.hybox.core.metrics.MetricsLog;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

record HyboxTrendModel(boolean metricsEnabled, int days, Instant generatedAt, int samples,
                       long firstSample, long lastSample, List<Metric> metrics, List<String> unavailable,
                       double[] markers) {
    private static final int MAX_POINTS = 600;

    record Metric(String title, String unit, int precision, double min, double average,
                  double max, double now, double[] values) {}

    static int days(int requested) {
        return Math.max(1, Math.min(30, requested));
    }

    static HyboxTrendModel from(boolean enabled, int requestedDays, Instant now, MetricsLog.Trend trend, long[] incidents) {
        int days = days(requestedDays);
        long from = now.minus(Duration.ofDays(days)).toEpochMilli(), to = now.toEpochMilli();
        int samples = 0;
        long first = 0, last = 0;
        for (long time : trend.timestamps()) {
            if (time < from || time > to) continue;
            if (samples++ == 0) first = time;
            last = time;
        }
        int points = Math.min(MAX_POINTS, Math.max(2, samples));
        List<Metric> metrics = new ArrayList<>(9);
        List<String> unavailable = new ArrayList<>();
        if (enabled && samples > 0) {
            add(metrics, unavailable, "Tick time (ms)", "ms", 1, trend.tickAvgMs(), trend.timestamps(), from, to, points);
            add(metrics, unavailable, "Ticks per second", "TPS", 1, trend.tps(), trend.timestamps(), from, to, points);
            add(metrics, unavailable, "Players online", "players", 0, trend.players(), trend.timestamps(), from, to, points);
            add(metrics, unavailable, "Heap memory", "GiB", 2, trend.heapBytes(), trend.timestamps(), from, to, points);
            add(metrics, unavailable, "Process RSS", "GiB", 2, trend.rssBytes(), trend.timestamps(), from, to, points);
            add(metrics, unavailable, "CPU (Metrics.Cpu)", "%", 1, trend.cpuPct(), trend.timestamps(), from, to, points);
            add(metrics, unavailable, "GC time", "%", 1, trend.gcPct(), trend.timestamps(), from, to, points);
            add(metrics, unavailable, "Allocation (Metrics.Allocation)", "MB/s", 1, trend.allocMbPerSec(), trend.timestamps(), from, to, points);
            add(metrics, unavailable, "Queued packets", "packets", 0, trend.queuedPackets(), trend.timestamps(), from, to, points);
        }
        double[] markers = Arrays.stream(incidents).filter(time -> time >= from && time <= to)
            .mapToDouble(time -> (double) (time - from) / (to - from) * (points - 1)).toArray();
        return new HyboxTrendModel(enabled, days, now, samples, first, last, List.copyOf(metrics), List.copyOf(unavailable), markers);
    }

    long rangeStart() {
        return generatedAt.minus(Duration.ofDays(days)).toEpochMilli();
    }

    private static void add(List<Metric> metrics, List<String> unavailable, String title, String unit, int precision,
                            double[] series, long[] times, long from, long to, int points) {
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY, sum = 0, now = Double.NaN;
        int count = 0;
        double[] values = new double[points];
        int[] counts = new int[points];
        for (int i = 0; i < Math.min(series.length, times.length); i++) {
            double value = series[i];
            if (times[i] < from || times[i] > to || !Double.isFinite(value) || value < 0) continue;
            min = Math.min(min, value);
            max = Math.max(max, value);
            sum += value;
            now = value;
            count++;
            int bucket = (int) Math.round((double) (times[i] - from) / (to - from) * (points - 1));
            values[bucket] += value;
            counts[bucket]++;
        }
        if (count == 0) {
            unavailable.add(title);
            return;
        }
        for (int i = 0; i < points; i++) values[i] = counts[i] == 0 ? Double.NaN : values[i] / counts[i];
        String chartTitle = title.startsWith("CPU") ? "CPU (%)" : title.startsWith("Allocation") ? "Allocation (MB/s)" : title;
        metrics.add(new Metric(chartTitle, unit, precision, min, sum / count, max, now, values));
    }
}
