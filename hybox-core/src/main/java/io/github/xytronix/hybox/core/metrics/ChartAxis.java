package io.github.xytronix.hybox.core.metrics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class ChartAxis {
    public static final double REF_HEADROOM = 1.15;
    public static final double P98_HEADROOM = 1.5;
    public static final double CLIP_FACTOR = 2;
    public static final double LOG_SPAN = 100;
    public static final int LOG_DECADES = 6;
    private static final double[] NICE_STEPS = {1, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10};
    private static final double[] LOG_STEPS = {1, 2, 5, 10};

    public enum Family {
        TICK_TIME("tick", true),
        GC_PAUSE("gcPause", true),
        ALLOCATION("alloc", true),
        NETWORK("network", true),
        HEAP("heap", true),
        LOADED("loaded", true),
        PACKETS("packets", true),
        TPS("tps", false),
        CPU("cpu", false),
        SHARE("share", false),
        PLAYERS("players", false),
        ENTITIES("entities", false),
        OTHER("other", false);

        private final String key;
        private final boolean log;

        Family(String key, boolean log) {
            this.key = key;
            this.log = log;
        }

        public String key() {
            return key;
        }

        public boolean logAllowed() {
            return log;
        }
    }

    public record Stats(double p98, double max, double positiveMedian, double positiveMin) {
        public static Stats of(double[] values) {
            if (values == null) {
                return new Stats(Double.NaN, Double.NaN, Double.NaN, Double.NaN);
            }
            double[] finite = new double[values.length];
            int n = 0;
            for (double value : values) {
                if (Double.isFinite(value)) {
                    finite[n++] = value;
                }
            }
            if (n == 0) {
                return new Stats(Double.NaN, Double.NaN, Double.NaN, Double.NaN);
            }
            Arrays.sort(finite, 0, n);
            int first = 0;
            while (first < n && !(finite[first] > 0)) {
                first++;
            }
            int positives = n - first;
            return new Stats(finite[(int) Math.floor(0.98 * (n - 1))], finite[n - 1],
                positives == 0 ? Double.NaN : finite[first + (int) Math.floor(0.5 * (positives - 1))],
                positives == 0 ? Double.NaN : finite[first]);
        }

        public Stats scaled(double divisor) {
            return new Stats(p98 / divisor, max / divisor, positiveMedian / divisor, positiveMin / divisor);
        }
    }

    public record Axis(double top, double clippedMax, double bottom, boolean log) {
        public Axis(double top, double clippedMax) {
            this(top, clippedMax, 0, false);
        }

        public boolean clips() {
            return Double.isFinite(clippedMax);
        }

        public boolean clipped(double value) {
            return Double.isFinite(value) && value > top * (1 + 1e-9);
        }

        public double fraction(double value) {
            if (!Double.isFinite(value)) {
                return 0;
            }
            double fraction = log
                ? value > bottom ? Math.log10(value / bottom) / Math.log10(top / bottom) : 0
                : value / top;
            return Math.max(0, Math.min(1, fraction));
        }

        public Axis scaled(double factor) {
            return new Axis(top * factor, clippedMax * factor, bottom * factor, log);
        }
    }

    private ChartAxis() {
    }

    public static List<String> logFamilies() {
        List<String> keys = new ArrayList<>();
        for (Family family : Family.values()) {
            if (family.logAllowed()) {
                keys.add(family.key());
            }
        }
        return List.copyOf(keys);
    }

    public static boolean logScale(Family family, Stats stats) {
        return family.logAllowed() && stats.positiveMedian() > 0 && stats.max() >= LOG_SPAN * stats.positiveMedian();
    }

    public static Axis of(Family family, Stats stats, double ref, boolean whole, boolean ceiling) {
        return logScale(family, stats)
            ? log(stats.positiveMin(), stats.max(), ref)
            : of(stats.p98(), stats.max(), ref, whole, ceiling);
    }

    public static Axis of(double p98, double max, double ref, boolean whole, boolean ceiling) {
        boolean hasRef = ref > 0 && Double.isFinite(ref);
        double floor = hasRef ? ref * REF_HEADROOM : 0;
        double limit = Math.max(floor, Double.isFinite(p98) ? P98_HEADROOM * p98 : 0);
        double top;
        if (ceiling && hasRef) {
            top = floor;
        } else if (Double.isFinite(max) && max > 0 && limit > 0 && max <= CLIP_FACTOR * limit) {
            top = Math.max(max, floor);
            if (whole && top <= max) {
                top = max + 1;
            }
        } else {
            top = limit;
        }
        if (!(top > 0)) {
            top = Double.isFinite(max) && max > 0 ? Math.min(max, 1) : 1;
        }
        top = whole ? whole(top) : nice(top);
        return new Axis(top, Double.isFinite(max) && max > top * (1 + 1e-9) ? max : Double.NaN);
    }

    public static Axis log(double low, double high, double ref) {
        if (ref > 0 && Double.isFinite(ref)) {
            low = Double.isFinite(low) ? Math.min(low, ref) : ref;
            high = Double.isFinite(high) ? Math.max(high, ref) : ref;
        }
        if (!(high > 0)) {
            high = 1;
        }
        if (!(low > 0) || low > high) {
            low = high;
        }
        double top = logStep(high, true);
        double bottom = Math.max(logStep(low, false), logStep(top / Math.pow(10, LOG_DECADES), true));
        if (!(bottom < top * (1 - 1e-9))) {
            bottom = top / 10;
        }
        return new Axis(top, Double.NaN, bottom, true);
    }

    public static double[] logTicks(double bottom, double top) {
        List<Double> ticks = new ArrayList<>();
        int from = (int) Math.floor(Math.log10(bottom)) - 1;
        int to = (int) Math.ceil(Math.log10(top)) + 1;
        for (int decade = from; decade <= to; decade++) {
            for (int s = 0; s < LOG_STEPS.length - 1; s++) {
                double tick = power(LOG_STEPS[s], decade);
                if (tick >= bottom * (1 - 1e-9) && tick <= top * (1 + 1e-9)) {
                    ticks.add(tick);
                }
            }
        }
        return ticks.stream().mapToDouble(Double::doubleValue).toArray();
    }

    public static double[] logLabels(double bottom, double top, double pixels, double gap) {
        double[] all = logTicks(bottom, top);
        double perDecade = pixels / Math.log10(top / bottom);
        if (perDecade * Math.log10(2) >= gap) {
            return all;
        }
        int every = Math.max(1, (int) Math.ceil(gap / perDecade - 1e-9));
        List<Double> candidates = new ArrayList<>();
        for (double tick : all) {
            long decade = Math.round(Math.log10(tick));
            if (Math.abs(tick - power(1, (int) decade)) <= 1e-9 * tick && Math.floorMod(decade, every) == 0) {
                candidates.add(tick);
            }
        }
        candidates.add(top);
        candidates.add(bottom);
        List<Double> kept = new ArrayList<>();
        for (double candidate : candidates) {
            boolean free = true;
            for (double label : kept) {
                if (Math.abs(Math.log10(candidate / label)) * perDecade < gap) {
                    free = false;
                    break;
                }
            }
            if (free) {
                kept.add(candidate);
            }
        }
        return kept.stream().mapToDouble(Double::doubleValue).sorted().toArray();
    }

    public static double nice(double top) {
        if (!(top > 0) || !Double.isFinite(top)) {
            return 1;
        }
        double power = Math.pow(10, Math.floor(Math.log10(top)));
        for (double step : NICE_STEPS) {
            if (step * power >= top * (1 - 1e-9)) {
                return step * power;
            }
        }
        return 10 * power;
    }

    private static double logStep(double value, boolean up) {
        int decade = (int) Math.floor(Math.log10(value));
        double best = power(1, decade);
        for (double step : LOG_STEPS) {
            double candidate = power(step, decade);
            if (up && candidate >= value * (1 - 1e-9)) {
                return candidate;
            }
            if (!up && candidate <= value * (1 + 1e-9)) {
                best = candidate;
            }
        }
        return up ? power(10, decade) : best;
    }

    private static double power(double step, int decade) {
        return decade >= 0 ? step * Math.pow(10, decade) : step / Math.pow(10, -decade);
    }

    private static double whole(double top) {
        double whole = Math.max(2, Math.ceil(top - 1e-9));
        return whole % 2 == 0 ? whole : whole + 1;
    }
}
