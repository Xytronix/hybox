package io.github.xytronix.hybox.core.metrics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class TrendTimeline {
    public static final double SPARSE_COVERAGE = 0.2;
    public static final int GAP_FACTOR = 3;
    private static final long DEFAULT_INTERVAL_MS = 10_000;
    private static final double DIVIDER_SHARE = 0.03;
    private static final double DIVIDERS_MAX_SHARE = 0.3;

    public record Gap(long startMs, long endMs, double fromX, double toX) {}

    public record Series(double[] mean, double[] min, double[] max, double[] lastIn, int[] count, boolean[] breakBefore,
                         double observedMin, double observedMean, double observedMax, double last,
                         int observedCount, ChartAxis.Stats stats) {
        public boolean isEmpty() {
            return observedCount == 0;
        }
    }

    private final long from;
    private final long to;
    private final boolean collapsed;
    private final long intervalMs;
    private final int buckets;
    private final long[] times;
    private final int[] source;
    private final int[] bucketOf;
    private final boolean[] gapBefore;
    private final long[] segmentStart;
    private final long[] segmentEnd;
    private final double[] segmentX;
    private final double span;
    private final List<Gap> gaps;
    private final double coverage;

    private TrendTimeline(long from, long to, boolean collapsed, long intervalMs, int buckets, long[] times, int[] source,
                          boolean[] gapBefore, long[] segmentStart, long[] segmentEnd, double[] segmentX, double span,
                          double coverage) {
        this.from = from;
        this.to = to;
        this.collapsed = collapsed;
        this.intervalMs = intervalMs;
        this.buckets = buckets;
        this.times = times;
        this.source = source;
        this.gapBefore = gapBefore;
        this.segmentStart = segmentStart;
        this.segmentEnd = segmentEnd;
        this.segmentX = segmentX;
        this.span = span;
        this.coverage = coverage;
        this.bucketOf = new int[times.length];
        for (int i = 0; i < times.length; i++) {
            bucketOf[i] = bucket(times[i]);
        }
        List<Gap> found = new ArrayList<>();
        for (int i = 1; i < times.length; i++) {
            if (gapBefore[i]) {
                found.add(new Gap(times[i - 1], times[i], x(times[i - 1]), x(times[i])));
            }
        }
        this.gaps = List.copyOf(found);
    }

    public static TrendTimeline of(long[] sortedTimes, long windowFrom, long windowTo, int buckets) {
        int count = 0;
        for (long time : sortedTimes) {
            if (time >= windowFrom && time <= windowTo) {
                count++;
            }
        }
        long[] times = new long[count];
        int[] source = new int[count];
        for (int i = 0, n = 0; i < sortedTimes.length; i++) {
            if (sortedTimes[i] >= windowFrom && sortedTimes[i] <= windowTo) {
                times[n] = sortedTimes[i];
                source[n++] = i;
            }
        }
        long interval = interval(times);
        long threshold = GAP_FACTOR * interval;
        boolean[] gapBefore = new boolean[count];
        List<long[]> segments = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (i > 0 && times[i] - times[i - 1] > threshold) {
                gapBefore[i] = true;
            }
            if (i == 0 || gapBefore[i]) {
                segments.add(new long[] {times[i], times[i]});
            } else {
                segments.getLast()[1] = times[i];
            }
        }
        double covered = 0;
        for (long[] segment : segments) {
            covered += segment[1] - segment[0] + interval;
        }
        long window = Math.max(1, windowTo - windowFrom);
        double coverage = Math.min(1, covered / window);
        int k = Math.max(1, buckets);
        if (count == 0 || coverage >= SPARSE_COVERAGE) {
            long first = count == 0 ? windowFrom : times[0];
            long last = count == 0 ? windowTo : Math.max(times[count - 1], first + interval);
            return new TrendTimeline(first, last, false, interval, k, times, source, gapBefore,
                new long[] {first}, new long[] {last}, new double[] {0}, Math.max(1, last - first), coverage);
        }
        int dividers = segments.size() - 1;
        double share = dividers == 0 ? 0 : Math.min(DIVIDER_SHARE, DIVIDERS_MAX_SHARE / dividers);
        double divider = dividers == 0 ? 0 : Math.max(interval, share * covered / (1 - share * dividers));
        long[] starts = new long[segments.size()];
        long[] ends = new long[segments.size()];
        double[] xs = new double[segments.size()];
        double x = 0;
        for (int s = 0; s < segments.size(); s++) {
            if (s > 0) {
                x += Math.min(segments.get(s)[0] - ends[s - 1], divider);
            }
            starts[s] = segments.get(s)[0];
            ends[s] = segments.get(s)[1] + interval;
            xs[s] = x;
            x += ends[s] - starts[s];
        }
        return new TrendTimeline(starts[0], ends[ends.length - 1], true, interval, k, times, source, gapBefore,
            starts, ends, xs, Math.max(1, x), coverage);
    }

    static long interval(long[] times) {
        if (times.length < 2) {
            return DEFAULT_INTERVAL_MS;
        }
        long[] deltas = new long[times.length - 1];
        int n = 0;
        for (int i = 1; i < times.length; i++) {
            long delta = times[i] - times[i - 1];
            if (delta > 0) {
                deltas[n++] = delta;
            }
        }
        if (n == 0) {
            return DEFAULT_INTERVAL_MS;
        }
        Arrays.sort(deltas, 0, n);
        return Math.max(1, deltas[(n - 1) / 2]);
    }

    public long from() {
        return from;
    }

    public long to() {
        return to;
    }

    public boolean collapsed() {
        return collapsed;
    }

    public long intervalMs() {
        return intervalMs;
    }

    public int buckets() {
        return buckets;
    }

    public int samples() {
        return times.length;
    }

    public double coverage() {
        return coverage;
    }

    public List<Gap> gaps() {
        return gaps;
    }

    public long[] sessionStarts() {
        return collapsed ? segmentStart.clone() : new long[0];
    }

    public double sessionX(int index) {
        return segmentX[index] / span;
    }

    public double x(long time) {
        if (!collapsed) {
            return clamp01((double) (time - from) / span);
        }
        int s = segment(time);
        if (time <= segmentStart[s]) {
            return clamp01(segmentX[s] / span);
        }
        double inSegment = segmentEnd[s] - segmentStart[s];
        if (time <= segmentEnd[s] || s + 1 == segmentX.length) {
            return clamp01((segmentX[s] + Math.min(inSegment, time - segmentStart[s])) / span);
        }
        double gapReal = segmentStart[s + 1] - segmentEnd[s];
        double gapVirtual = segmentX[s + 1] - segmentX[s] - inSegment;
        return clamp01((segmentX[s] + inSegment + (time - segmentEnd[s]) / gapReal * gapVirtual) / span);
    }

    public long timeAt(double fraction) {
        double v = clamp01(fraction) * span;
        if (!collapsed) {
            return from + Math.round(v);
        }
        for (int s = segmentX.length - 1; s >= 0; s--) {
            if (v >= segmentX[s] - 1e-6) {
                return Math.min(segmentEnd[s], segmentStart[s] + Math.max(0, Math.round(v - segmentX[s])));
            }
        }
        return segmentStart[0];
    }

    public boolean inGap(double fraction) {
        for (Gap gap : gaps) {
            if (fraction > gap.fromX() && fraction < gap.toX()) {
                return true;
            }
        }
        return false;
    }

    public int bucket(long time) {
        return Math.max(0, Math.min(buckets - 1, (int) Math.floor(x(time) * buckets)));
    }

    public double bucketX(int bucket) {
        return (bucket + 0.5) / buckets;
    }

    public long[] bucketTimes() {
        long[] sum = new long[buckets];
        int[] count = new int[buckets];
        for (int i = 0; i < times.length; i++) {
            sum[bucketOf[i]] += times[i] - from;
            count[bucketOf[i]]++;
        }
        long[] out = new long[buckets];
        for (int b = 0; b < buckets; b++) {
            out[b] = count[b] == 0 ? timeAt(bucketX(b)) : from + sum[b] / count[b];
        }
        return out;
    }

    public long[][] bucketRanges() {
        long[][] out = new long[buckets][];
        for (int i = 0; i < times.length; i++) {
            long[] range = out[bucketOf[i]];
            if (range == null) {
                out[bucketOf[i]] = new long[] {times[i], times[i]};
            } else {
                range[1] = times[i];
            }
        }
        return out;
    }

    public int[] sampleCounts() {
        int[] out = new int[buckets];
        for (int b : bucketOf) {
            out[b]++;
        }
        return out;
    }

    public Series series(double[] values) {
        return series(values, 1);
    }

    public Series series(double[] values, double divisor) {
        double[] sum = new double[buckets];
        double[] min = new double[buckets];
        double[] max = new double[buckets];
        int[] count = new int[buckets];
        boolean[] breakBefore = new boolean[buckets];
        double[] lastIn = new double[buckets];
        Arrays.fill(min, Double.NaN);
        Arrays.fill(max, Double.NaN);
        Arrays.fill(lastIn, Double.NaN);
        double[] raw = new double[times.length];
        int rawCount = 0;
        double observedSum = 0;
        double observedMin = Double.NaN;
        double observedMax = Double.NaN;
        double last = Double.NaN;
        int previousBucket = -1;
        boolean pendingBreak = false;
        for (int i = 0; i < times.length; i++) {
            if (gapBefore[i]) {
                pendingBreak = true;
            }
            int index = source[i];
            double value = index < values.length ? values[index] : Double.NaN;
            if (!Double.isFinite(value) || value < 0) {
                if (previousBucket >= 0) {
                    pendingBreak = true;
                }
                continue;
            }
            value /= divisor;
            int b = bucketOf[i];
            if (pendingBreak && previousBucket >= 0 && b != previousBucket) {
                breakBefore[b] = true;
            }
            pendingBreak = false;
            previousBucket = b;
            sum[b] += value;
            min[b] = count[b] == 0 ? value : Math.min(min[b], value);
            max[b] = count[b] == 0 ? value : Math.max(max[b], value);
            count[b]++;
            lastIn[b] = value;
            raw[rawCount++] = value;
            observedSum += value;
            observedMin = rawCount == 1 ? value : Math.min(observedMin, value);
            observedMax = rawCount == 1 ? value : Math.max(observedMax, value);
            last = value;
        }
        double[] mean = new double[buckets];
        for (int b = 0; b < buckets; b++) {
            mean[b] = count[b] == 0 ? Double.NaN : sum[b] / count[b];
        }
        return new Series(mean, min, max, lastIn, count, breakBefore, observedMin,
            rawCount == 0 ? Double.NaN : observedSum / rawCount, observedMax, last, rawCount,
            ChartAxis.Stats.of(Arrays.copyOf(raw, rawCount)));
    }

    private int segment(long time) {
        int found = Arrays.binarySearch(segmentStart, time);
        return Math.max(0, found >= 0 ? found : -found - 2);
    }

    private static double clamp01(double value) {
        return Math.max(0, Math.min(1, value));
    }
}
