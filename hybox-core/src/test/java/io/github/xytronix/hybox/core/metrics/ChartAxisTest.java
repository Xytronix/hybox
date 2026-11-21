package io.github.xytronix.hybox.core.metrics;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ChartAxisTest {
    @Test
    void oneSpikeIsClippedAndNamedWhileTheBudgetStaysVisible() {
        double[] ticks = new double[200];
        Arrays.fill(ticks, 3);
        ticks[100] = 117.2;
        ChartAxis.Axis axis = axis(ticks, 1000 / 30.0, false, false);
        assertEquals(40, axis.top(), 1e-9);
        assertTrue(axis.clips());
        assertEquals(117.2, axis.clippedMax(), 1e-9);
        assertTrue(axis.clipped(117.2));
        assertFalse(axis.clipped(39.9));
    }

    @Test
    void topFollowsTheReachedMaximumWhileItStaysWithinTwiceTheLimit() {
        double[] values = new double[100];
        for (int i = 0; i < values.length; i++) values[i] = i + 1;
        assertEquals(98, ChartAxis.Stats.of(values).p98(), 1e-9);
        ChartAxis.Axis axis = axis(values, 0, false, false);
        assertEquals(100, axis.top(), 1e-9);
        assertFalse(axis.clips());
        double[] tail = new double[100];
        Arrays.fill(tail, 10);
        tail[99] = 29;
        ChartAxis.Axis wide = axis(tail, 0, false, false);
        assertEquals(30, wide.top(), 1e-9, "29 is below twice the limit of 15, so it is drawn to scale");
        assertFalse(wide.clips());
        tail[99] = 31;
        ChartAxis.Axis cut = axis(tail, 0, false, false);
        assertEquals(15, cut.top(), 1e-9, "31 is above twice the limit, so the top is the limit and 31 is clipped");
        assertEquals(31, cut.clippedMax(), 1e-9);
    }

    @Test
    void referenceHeadroomWinsWhenDataIsLow() {
        ChartAxis.Axis ticks = axis(new double[] {2.7, 3.1, 2.9, 4.0}, 1000 / 30.0, false, false);
        assertEquals(40, ticks.top(), 1e-9);
        assertFalse(ticks.clips());
        ChartAxis.Axis tps = axis(new double[] {29, 30, 30, 8.5}, 30, false, false);
        assertEquals(40, tps.top(), 1e-9);
        ChartAxis.Axis ceiling = axis(new double[] {29, 30, 30, 8.5}, 30, false, true);
        assertEquals(40, ceiling.top(), 1e-9);
        assertFalse(ceiling.clips());
        assertEquals(25, axis(new double[] {20, 19.5, 20}, 20, false, true).top(), 1e-9);
        assertEquals(1, axis(new double[] {0.5, 0.6, 1.0}, 0, false, false).top(), 1e-9);
    }

    @Test
    void wholeNumberChartsUseEvenIntegersAndEmptyDataStillHasAnAxis() {
        assertEquals(4, axis(new double[] {3, 3, 3}, 0, true, false).top(), 1e-9);
        assertEquals(2, axis(new double[] {0, 0, 0}, 0, true, false).top(), 1e-9);
        assertEquals(1, axis(new double[0], 0, false, false).top(), 1e-9);
        double[] packets = new double[51];
        packets[50] = 25;
        ChartAxis.Axis axis = axis(packets, 0, true, false);
        assertEquals(2, axis.top(), 1e-9);
        assertEquals(25, axis.clippedMax(), 1e-9);
    }

    @Test
    void niceStepsCoverEveryDecade() {
        assertEquals(1.5, ChartAxis.nice(1.2), 1e-9);
        assertEquals(2.5, ChartAxis.nice(2.1), 1e-9);
        assertEquals(6, ChartAxis.nice(5.5), 1e-9);
        assertEquals(0.08, ChartAxis.nice(0.07), 1e-12);
        assertEquals(1000, ChartAxis.nice(801), 1e-9);
    }

    @Test
    void logScaleIsOnlyAllowedForPositiveMagnitudesWhereRatiosMatter() {
        Set<ChartAxis.Family> log = EnumSet.of(ChartAxis.Family.TICK_TIME, ChartAxis.Family.GC_PAUSE,
            ChartAxis.Family.ALLOCATION, ChartAxis.Family.NETWORK, ChartAxis.Family.HEAP, ChartAxis.Family.LOADED,
            ChartAxis.Family.PACKETS);
        for (ChartAxis.Family family : ChartAxis.Family.values()) {
            assertEquals(log.contains(family), family.logAllowed(), family.name());
        }
        assertEquals(List.of("tick", "gcPause", "alloc", "network", "heap", "loaded", "packets"), ChartAxis.logFamilies());
        double[] freeze = new double[100];
        Arrays.fill(freeze, 5);
        freeze[60] = 2000;
        ChartAxis.Stats stats = ChartAxis.Stats.of(freeze);
        assertTrue(ChartAxis.logScale(ChartAxis.Family.TICK_TIME, stats));
        for (ChartAxis.Family never : List.of(ChartAxis.Family.TPS, ChartAxis.Family.CPU, ChartAxis.Family.SHARE,
            ChartAxis.Family.PLAYERS, ChartAxis.Family.ENTITIES, ChartAxis.Family.OTHER)) {
            assertFalse(ChartAxis.logScale(never, stats), never.name());
            assertFalse(ChartAxis.of(never, stats, 0, false, false).log(), never.name());
        }
        freeze[60] = 499;
        assertFalse(ChartAxis.logScale(ChartAxis.Family.TICK_TIME, ChartAxis.Stats.of(freeze)), "less than two decades stays linear");
        freeze[60] = 500;
        assertTrue(ChartAxis.logScale(ChartAxis.Family.TICK_TIME, ChartAxis.Stats.of(freeze)), "exactly two decades switches to log");
    }

    @Test
    void zerosDoNotCountTowardsTheMedianAndAreDrawnOnTheFloor() {
        double[] pauses = new double[300];
        for (int i = 0; i < 100; i++) pauses[3 * i] = 2;
        pauses[150] = 400;
        ChartAxis.Stats stats = ChartAxis.Stats.of(pauses);
        assertEquals(2, stats.positiveMedian(), 1e-9);
        assertEquals(2, stats.positiveMin(), 1e-9);
        assertEquals(2, stats.p98(), 1e-9);
        ChartAxis.Axis axis = ChartAxis.of(ChartAxis.Family.GC_PAUSE, stats, 0, false, false);
        assertTrue(axis.log());
        assertEquals(2, axis.bottom(), 1e-9);
        assertEquals(500, axis.top(), 1e-9);
        assertEquals(0, axis.fraction(0), 1e-12);
        assertEquals(0, axis.fraction(2), 1e-12);
        assertEquals(1, axis.fraction(500), 1e-12);
        assertEquals(Math.log10(20) / Math.log10(250), axis.fraction(40), 1e-12);
        assertFalse(ChartAxis.logScale(ChartAxis.Family.GC_PAUSE, ChartAxis.Stats.of(new double[] {0, 0, 0})), "all zero stays linear");
        assertFalse(ChartAxis.logScale(ChartAxis.Family.GC_PAUSE, ChartAxis.Stats.of(new double[0])));
    }

    @Test
    void logTicksAreOneTwoFivePerDecadeWithPlainValues() {
        assertArrayEquals(new double[] {5, 10, 20, 50, 100, 200, 500, 1000, 2000}, ChartAxis.logTicks(5, 2000), 1e-12);
        assertArrayEquals(new double[] {0.01, 0.02, 0.05, 0.1, 0.2, 0.5, 1}, ChartAxis.logTicks(0.01, 1), 0);
        assertArrayEquals(new double[] {0.002, 0.005, 0.01}, ChartAxis.logTicks(0.002, 0.01), 0);
        assertArrayEquals(new double[] {10, 100, 1000}, ChartAxis.logLabels(5, 2000, 89.5, 12), 1e-12);
        assertArrayEquals(ChartAxis.logTicks(5, 2000), ChartAxis.logLabels(5, 2000, 400, 14), 1e-12);
        assertArrayEquals(new double[] {0.01, 1, 100}, ChartAxis.logLabels(0.001, 1000, 60, 12), 1e-12);
        ChartAxis.Axis wide = ChartAxis.log(1e-6, 2000, 0);
        assertEquals(2000, wide.top(), 1e-9);
        assertEquals(0.002, wide.bottom(), 1e-12, "the floor stays within six decades of the top");
    }

    @Test
    void aFreezeNextToFastTicksFitsTheLogAxisWithTheBudgetLine() {
        double[] ticks = new double[600];
        Arrays.fill(ticks, 5);
        ticks[400] = 2000;
        double budget = 1000 / 30.0;
        ChartAxis.Axis axis = ChartAxis.of(ChartAxis.Family.TICK_TIME, ChartAxis.Stats.of(ticks), budget, false, false);
        assertTrue(axis.log());
        assertFalse(axis.clips());
        assertFalse(axis.clipped(2000));
        assertEquals(2000, axis.top(), 1e-9);
        assertEquals(5, axis.bottom(), 1e-9);
        double line = axis.fraction(budget);
        assertTrue(line > axis.fraction(10) && line < axis.fraction(100), "budget line sits between 10 and 100 ms");
        ChartAxis.Axis quiet = ChartAxis.of(ChartAxis.Family.TICK_TIME, ChartAxis.Stats.of(new double[] {0.2, 0.3, 50}), budget, false, false);
        assertTrue(quiet.log());
        assertEquals(0.2, quiet.bottom(), 1e-12);
        assertEquals(50, quiet.top(), 1e-9);
        assertTrue(quiet.fraction(budget) < 1, "the budget stays inside the scale");
        ChartAxis.Axis linear = axis(ticks, budget, false, false);
        assertTrue(linear.clips(), "the same data on a linear axis clips the freeze");
    }

    private static ChartAxis.Axis axis(double[] values, double ref, boolean whole, boolean ceiling) {
        return ChartAxis.of(ChartAxis.Family.OTHER, ChartAxis.Stats.of(values), ref, whole, ceiling);
    }
}
