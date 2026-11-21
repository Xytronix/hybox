package io.github.xytronix.hybox.core.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class TrendTimelineTest {
    private static final long HOUR = 3_600_000L;
    private static final long DAY = 24 * HOUR;

    @Test
    void bucketCountIsFixedAndBucketsFollowTimeNotSampleCount() {
        long[] few = {DAY / 4, DAY / 2, 3 * DAY / 4};
        TrendTimeline sparse = TrendTimeline.of(times(0, 2000, 30_000L), 0, DAY, 400);
        TrendTimeline dense = TrendTimeline.of(times(0, 8640, 10_000L), 0, DAY, 400);
        assertEquals(400, sparse.buckets());
        assertEquals(400, dense.buckets());
        assertEquals(400, TrendTimeline.of(few, 0, DAY, 400).buckets());
        assertFalse(dense.collapsed());
        assertEquals(0, dense.bucket(0));
        assertEquals(200, dense.bucket(DAY / 2));
        assertEquals(399, dense.bucket(DAY - 1));
        assertEquals(10_000, dense.intervalMs());
    }

    @Test
    void seriesKeepsMeanMinAndMaxPerBucket() {
        long[] times = times(0, 8640, 10_000L);
        double[] values = new double[times.length];
        Arrays.fill(values, 5);
        values[4320] = 120;
        values[4321] = 1;
        TrendTimeline timeline = TrendTimeline.of(times, 0, DAY, 400);
        TrendTimeline.Series series = timeline.series(values);
        int bucket = timeline.bucket(times[4320]);
        assertEquals(120, series.max()[bucket]);
        assertEquals(1, series.min()[bucket]);
        assertTrue(series.mean()[bucket] > 5 && series.mean()[bucket] < 120);
        assertEquals(120, series.observedMax());
        assertEquals(5, series.stats().p98());
        assertEquals(8640, Arrays.stream(series.count()).sum());
    }

    @Test
    void gapsLongerThanThreeIntervalsBreakTheLineAndShortOnesDoNot() {
        long[] times = new long[300];
        for (int i = 0; i < times.length; i++) {
            times[i] = i * 10_000L + (i >= 100 ? 15_000L : 0) + (i >= 200 ? 600_000L : 0);
        }
        double[] values = new double[times.length];
        Arrays.fill(values, 3);
        TrendTimeline timeline = TrendTimeline.of(times, 0, times[299] + 10_000L, 300);
        assertEquals(1, timeline.gaps().size());
        assertEquals(times[199], timeline.gaps().getFirst().startMs());
        assertEquals(times[200], timeline.gaps().getFirst().endMs());
        TrendTimeline.Series series = timeline.series(values);
        int breaks = 0;
        for (boolean b : series.breakBefore()) if (b) breaks++;
        assertEquals(1, breaks);
        assertTrue(series.breakBefore()[timeline.bucket(times[200])]);
        assertFalse(series.breakBefore()[timeline.bucket(times[100])]);
    }

    @Test
    void sparseDataCollapsesServerOffTimeIntoDividers() {
        long[] first = times(2 * DAY, 120, 10_000L);
        long[] second = times(5 * DAY, 120, 10_000L);
        long[] third = times(6 * DAY + 12 * HOUR, 60, 10_000L);
        long[] all = new long[300];
        System.arraycopy(first, 0, all, 0, 120);
        System.arraycopy(second, 0, all, 120, 120);
        System.arraycopy(third, 0, all, 240, 60);
        TrendTimeline timeline = TrendTimeline.of(all, 0, 7 * DAY, 440);
        assertTrue(timeline.collapsed());
        assertTrue(timeline.coverage() < TrendTimeline.SPARSE_COVERAGE);
        assertEquals(2 * DAY, timeline.from());
        assertEquals(3, timeline.sessionStarts().length);
        assertEquals(2, timeline.gaps().size());
        for (TrendTimeline.Gap gap : timeline.gaps()) {
            assertTrue(gap.toX() - gap.fromX() < 0.05, "divider width " + (gap.toX() - gap.fromX()));
        }
        assertTrue(timeline.x(second[0]) > 0.3 && timeline.x(second[0]) < 0.5);
        assertEquals(0, timeline.x(all[0]));
        assertTrue(timeline.inGap((timeline.gaps().getFirst().fromX() + timeline.gaps().getFirst().toX()) / 2));
        assertEquals(second[0], timeline.timeAt(timeline.x(second[0])));
        TrendTimeline.Series series = timeline.series(constant(all.length, 7));
        int breaks = 0;
        for (boolean b : series.breakBefore()) if (b) breaks++;
        assertEquals(2, breaks);
    }

    @Test
    void denseEnoughDataKeepsTheFixedWindow() {
        long[] times = times(DAY, 5 * 8640, 10_000L);
        TrendTimeline timeline = TrendTimeline.of(times, 0, 7 * DAY, 440);
        assertFalse(timeline.collapsed());
        assertEquals(DAY, timeline.from(), "the axis starts at the first sample, not at the window start");
        assertEquals(times[times.length - 1], timeline.to());
        assertEquals(0, timeline.gaps().size());
    }

    @Test
    void missingValuesInsideASessionBreakOnlyThatSeries() {
        long[] times = times(0, 8640, 10_000L);
        double[] values = constant(times.length, 2);
        for (int i = 3000; i < 4000; i++) values[i] = Double.NaN;
        TrendTimeline timeline = TrendTimeline.of(times, 0, DAY, 400);
        TrendTimeline.Series series = timeline.series(values);
        assertTrue(series.breakBefore()[timeline.bucket(times[4000])]);
        TrendTimeline.Series full = timeline.series(constant(times.length, 2));
        for (boolean b : full.breakBefore()) assertFalse(b);
    }

    private static long[] times(long start, int count, long step) {
        long[] out = new long[count];
        for (int i = 0; i < count; i++) out[i] = start + i * step;
        return out;
    }

    private static double[] constant(int count, double value) {
        double[] out = new double[count];
        Arrays.fill(out, value);
        return out;
    }
}
