package io.github.xytronix.hybox.hytale;

import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import io.github.xytronix.hybox.core.metrics.ChartAxis;
import io.github.xytronix.hybox.core.metrics.TrendTimeline;
import io.github.xytronix.hybox.core.text.Units;
import java.util.Arrays;
import java.util.Map;

final class HyboxChartRenderer {
    static final int HOVER_CELLS = 20;
    static final int CLIP_LABEL_HEIGHT = 12;
    static final int TICK_GAP = 12;
    private static final int REF_LABEL_WIDTH = 240;
    private static final int PLOT_HEIGHT = 90;
    private static final int CLIP_CHAR_WIDTH = 7;
    private static final int CLIP_GAP = 10;
    private static final int TRIANGLE_HALF = 5;
    private static final Map<String, String> SINGULAR = Map.of("players", "player", "packets", "packet",
        "sections", "section", "chunks", "chunk", "entities", "entity", "worlds", "world", "incidents", "incident",
        "samples", "sample");

    record Ref(double value, String label, boolean ceiling) {
        Ref(double value, String label) {
            this(value, label, false);
        }
    }

    record Mark(String kind, double frac, String word, int rgb) {
        boolean tint() { return kind.equals("incident"); }
    }

    record Placement(int left, int width) {}

    private HyboxChartRenderer() {}

    static void hover(UICommandBuilder commands, String container, int width, int height, java.util.List<String> tips) {
        for (int c = 0; c < tips.size(); c++) {
            int left = c * width / tips.size(), right = (c + 1) * width / tips.size();
            commands.append(container, "Pages/HyboxChartHover.ui");
            String cell = container + "[" + c + "]";
            commands.setObject(cell + ".Anchor", HyboxIncidentRenderer.anchor(left, 0, right - left, height));
            commands.set(cell + ".TooltipText", tips.get(c));
        }
    }

    static ChartAxis.Axis axis(ChartAxis.Stats stats, ChartAxis.Family family, String unit, int precision, Ref ref) {
        double factor = unitFactor(unit);
        boolean hasRef = hasRef(ref);
        return ChartAxis.of(family, stats.scaled(factor), hasRef ? ref.value() / factor : 0, precision == 0,
            hasRef && ref.ceiling()).scaled(factor);
    }

    static String logTitle(String title, boolean log) {
        if (!log) return title;
        return title.endsWith(")") ? title.substring(0, title.length() - 1) + ", log scale)" : title + " (log scale)";
    }

    static ChartAxis.Axis plot(String selector, double[] values, double[] markers, int columnCap, String unit, int precision,
                               ChartAxis.Family family, Ref ref, Mark mark, HyboxIncidentRenderer.ChartImages images,
                               UICommandBuilder commands) {
        boolean shadeIncident = mark != null;
        int columns = Math.min(columnCap, values.length);
        double[] bars = new double[columns];
        for (int c = 0; c < columns; c++) {
            int from = c * values.length / columns, to = (c + 1) * values.length / columns;
            double sum = 0;
            int count = 0;
            for (int i = from; i < to; i++) {
                if (!Double.isFinite(values[i])) continue;
                sum += values[i];
                count++;
            }
            bars[c] = count == 0 ? Double.NaN : Math.max(0, sum / count);
        }
        ChartAxis.Axis axis = axis(ChartAxis.Stats.of(values), family, unit, precision, ref);
        labels(selector, axis, unit, precision, commands);
        reference(selector, ref, axis, commands);
        int peak = -1;
        for (int i = 0; i < values.length; i++) {
            if (Double.isFinite(values[i]) && (peak < 0 || values[i] > values[peak])) peak = i;
        }
        String note = clip(selector, axis, lineTops(values, axis), peak < 0 ? 0 : (double) peak / Math.max(1, values.length - 1),
            unit, precision, ref, commands);
        commands.set(selector + " #ChartLegend.Text", (shadeIncident
            ? "Bars show the average per column." + (markers.length > 0 ? " Highlighted bar: " + mark.word() + "." : "")
            : "Bars show the average per column. Thin lines mark incidents.") + note);
        for (int c = 0; c < columns; c++) {
            if (!Double.isFinite(bars[c])) continue;
            boolean incident = false;
            if (shadeIncident) for (double marker : markers) {
                if (marker >= c * values.length / columns && marker < (c + 1) * values.length / columns) incident = true;
            }
            bar(selector, c, columns, bars[c], axis, incident ? mark.rgb() : HyboxChartImage.LINE, commands);
        }
        if (!shadeIncident) for (double marker : markers) {
            if (!Double.isFinite(marker) || marker < 0 || marker > values.length - 1) continue;
            markerLine(selector, marker / Math.max(1, values.length - 1), commands);
        }
        if (images == null || values.length < 2) return axis;
        images.request(new HyboxIncidentRenderer.ChartRequest(
            () -> shadeIncident && markers.length == 1
                ? HyboxChartImage.line(values, axis, markers[0], mark.rgb(), mark.tint())
                : HyboxChartImage.line(values, axis, markers),
            (path, swap) -> {
                swap.clear(selector + " #Bars");
                swap.set(selector + " #PlotImage.AssetPath", path);
                swap.set(selector + " #PlotImage.Visible", true);
                swap.set(selector + " #ChartLegend.Text", (shadeIncident
                    ? "Every sample." + (markers.length > 0 ? " Dashed line: " + mark.word() + "." : "")
                    : "Sample averages. Dashed lines mark incidents.") + note);
            }));
        return axis;
    }

    static ChartAxis.Axis trend(String selector, HyboxTrendModel model, HyboxTrendModel.Metric metric,
                                HyboxIncidentRenderer.ChartImages images, UICommandBuilder commands) {
        TrendTimeline.Series series = metric.series();
        Ref ref = metric.ref();
        ChartAxis.Axis axis = axis(series.stats(), metric.family(), metric.unit(), metric.precision(), ref);
        labels(selector, axis, metric.unit(), metric.precision(), commands);
        reference(selector, ref, axis, commands);
        int peak = -1;
        for (int b = 0; b < series.max().length; b++) {
            if (series.count()[b] > 0 && (peak < 0 || series.max()[b] > series.max()[peak])) peak = b;
        }
        String note = clip(selector, axis, trendTops(series, axis), peak < 0 ? 0 : model.timeline().bucketX(peak),
            metric.unit(), metric.precision(), ref, commands);
        commands.set(selector + " #ChartLegend.Text", legend(model, axis) + note);
        int buckets = series.mean().length;
        int cells = Math.min(HOVER_CELLS, buckets);
        for (int c = 0; c < cells; c++) {
            int from = c * buckets / cells, to = (c + 1) * buckets / cells;
            double sum = 0;
            int count = 0;
            for (int b = from; b < to; b++) {
                if (series.count()[b] == 0) continue;
                sum += series.mean()[b] * series.count()[b];
                count += series.count()[b];
            }
            if (count > 0) bar(selector, c, cells, sum / count, axis, HyboxChartImage.LINE, commands);
        }
        for (double marker : model.markers()) markerLine(selector, marker, commands);
        if (images == null) return axis;
        double[] gaps = gapFractions(model.timeline());
        boolean collapsed = model.timeline().collapsed();
        double[] markers = model.markers();
        images.request(new HyboxIncidentRenderer.ChartRequest(
            () -> HyboxChartImage.trend(series.mean(), series.min(), series.max(), series.breakBefore(), axis,
                gaps, collapsed, markers),
            (path, swap) -> {
                swap.clear(selector + " #Bars");
                swap.set(selector + " #PlotImage.AssetPath", path);
                swap.set(selector + " #PlotImage.Visible", true);
            }));
        return axis;
    }

    private static String legend(HyboxTrendModel model, ChartAxis.Axis axis) {
        TrendTimeline timeline = model.timeline();
        StringBuilder text = new StringBuilder("Line: average. Band: lowest to highest.");
        if (!timeline.gaps().isEmpty()) text.append(timeline.collapsed() ? " Server off time is cut out at the dividers." : " Shaded: server off.");
        if (model.markers().length > 0) text.append(" Dashed lines mark incidents.");
        if (axis.clips()) text.append(" Triangles mark values above the scale.");
        return text.toString();
    }

    static double[] gapFractions(TrendTimeline timeline) {
        double[] out = new double[timeline.gaps().size() * 2];
        for (int i = 0; i < timeline.gaps().size(); i++) {
            out[2 * i] = timeline.gaps().get(i).fromX();
            out[2 * i + 1] = timeline.gaps().get(i).toX();
        }
        return out;
    }

    static int[] lineTops(double[] values, ChartAxis.Axis axis) {
        int width = HyboxChartImage.WIDTH, n = values.length;
        int[] tops = new int[width];
        Arrays.fill(tops, HyboxChartImage.HEIGHT);
        double previous = Double.NaN;
        for (int x = 0; x < width && n > 0; x++) {
            double start = n > 1 ? (x - 0.5) * (n - 1) / (width - 1) : 0, end = n > 1 ? (x + 0.5) * (n - 1) / (width - 1) : 0;
            double high = Double.NaN;
            for (int i = Math.max(0, (int) Math.ceil(start)); i <= Math.min(n - 1, (int) Math.floor(end)); i++) {
                if (Double.isFinite(values[i]) && !(values[i] <= high)) high = values[i];
            }
            double centre = centre(values, n > 1 ? (double) x * (n - 1) / (width - 1) : 0);
            if (Double.isFinite(centre) && !(centre <= high)) high = centre;
            double drawn = Double.isFinite(high) && Double.isFinite(previous) ? Math.max(high, previous) : high;
            if (Double.isFinite(drawn)) tops[x] = HyboxChartImage.lineRow(drawn, axis) - 1;
            previous = Double.isFinite(centre) ? centre : high;
        }
        return tops;
    }

    private static double centre(double[] values, double position) {
        int i = Math.min(values.length - 1, (int) position), j = Math.min(values.length - 1, i + 1);
        return Double.isFinite(values[i]) && Double.isFinite(values[j]) ? values[i] + (values[j] - values[i]) * (position - i) : Double.NaN;
    }

    static int[] trendTops(TrendTimeline.Series series, ChartAxis.Axis axis) {
        int width = HyboxChartImage.WIDTH, n = series.mean().length;
        double[] high = new double[width];
        Arrays.fill(high, Double.NaN);
        int previous = -1;
        for (int b = 0; b < n; b++) {
            if (series.count()[b] == 0) continue;
            int left = b * width / n, right = Math.max(left + 1, (b + 1) * width / n);
            for (int x = left - 2; x <= right + 1; x++) raise(high, x, series.max()[b]);
            if (previous >= 0 && !series.breakBefore()[b]) {
                double x0 = (previous + 0.5) * width / n, x1 = (b + 0.5) * width / n;
                for (int x = (int) Math.floor(x0) - 1; x <= (int) Math.ceil(x1) + 1; x++) {
                    double t = Math.max(0, Math.min(1, (x - x0) / Math.max(1e-9, x1 - x0)));
                    raise(high, x, series.mean()[previous] + (series.mean()[b] - series.mean()[previous]) * t);
                }
            }
            previous = b;
        }
        int[] tops = new int[width];
        for (int x = 0; x < width; x++) tops[x] = Double.isNaN(high[x]) ? HyboxChartImage.HEIGHT : HyboxChartImage.lineRow(high[x], axis) - 2;
        return tops;
    }

    private static void raise(double[] high, int x, double value) {
        if (x >= 0 && x < high.length && (Double.isNaN(high[x]) || value > high[x])) high[x] = value;
    }

    static Placement placement(int[] tops, int width, int peak, int blockedFrom, int blockedTo) {
        boolean[] busy = new boolean[tops.length];
        for (int x = 0; x < tops.length; x++) {
            if (tops[x] <= CLIP_LABEL_HEIGHT + 1) {
                for (int dx = -TRIANGLE_HALF; dx <= TRIANGLE_HALF; dx++) {
                    if (x + dx >= 0 && x + dx < busy.length) busy[x + dx] = true;
                }
            }
        }
        for (int x = Math.max(0, blockedFrom); x < Math.min(busy.length, blockedTo); x++) busy[x] = true;
        int[] free = new int[busy.length + 1];
        for (int x = 0; x < busy.length; x++) free[x + 1] = free[x] + (busy[x] ? 0 : 1);
        int best = -1, bestDistance = Integer.MAX_VALUE;
        for (int left = 0; left + width <= busy.length; left++) {
            if (free[left + width] - free[left] != width) continue;
            int distance = left + width <= peak ? peak - (left + width) : left >= peak ? left - peak : Integer.MAX_VALUE;
            if (distance < CLIP_GAP || distance >= bestDistance) continue;
            best = left;
            bestDistance = distance;
        }
        return best < 0 ? null : new Placement(best, width);
    }

    private static void bar(String selector, int column, int columns, double value, ChartAxis.Axis axis, int rgb, UICommandBuilder commands) {
        int height = Math.max(1, Math.min(PLOT_HEIGHT, (int) Math.round(PLOT_HEIGHT * axis.fraction(value))));
        int left = column * HyboxChartImage.WIDTH / columns;
        int width = Math.max(1, (column + 1) * HyboxChartImage.WIDTH / columns - left - 1);
        commands.appendInline(selector + " #Bars", "Group { Anchor: (Left: " + left + ", Bottom: 0, Width: " + width + ", Height: " + height + "); Background: " + HyboxChartImage.hex(rgb) + "; }");
    }

    private static void markerLine(String selector, double fraction, UICommandBuilder commands) {
        if (!Double.isFinite(fraction) || fraction < 0 || fraction > 1) return;
        int left = (int) Math.round(fraction * (HyboxChartImage.WIDTH - 1));
        commands.appendInline(selector + " #Bars", "Group { Anchor: (Left: " + left + ", Bottom: 0, Width: 1, Height: 96); Background: " + HyboxChartImage.hex(HyboxChartImage.INCIDENT) + "; }");
    }

    private static String clip(String selector, ChartAxis.Axis axis, int[] tops, double fraction, String unit, int precision,
                               Ref ref, UICommandBuilder commands) {
        boolean placed = false;
        String text = axis.clips() ? value(axis.clippedMax(), unit, precision) + " off scale" : "";
        if (axis.clips()) {
            int peak = (int) Math.round(Math.max(0, Math.min(1, fraction)) * (HyboxChartImage.WIDTH - 1));
            int refTop = hasRef(ref) ? Math.max(0, HyboxChartImage.lineRow(ref.value(), axis) - 13) : Integer.MAX_VALUE;
            int refLeft = HyboxChartImage.WIDTH - REF_LABEL_WIDTH - 4;
            boolean refInTheWay = refTop <= CLIP_LABEL_HEIGHT + 1;
            Placement spot = placement(tops, text.length() * CLIP_CHAR_WIDTH + 4, peak,
                refInTheWay ? refLeft : Integer.MAX_VALUE, refInTheWay ? HyboxChartImage.WIDTH : Integer.MAX_VALUE);
            if (spot != null) {
                commands.set(selector + " #ClipLabel.Text", text);
                commands.setObject(selector + " #ClipLabel.Anchor", HyboxIncidentRenderer.anchor(spot.left(), 0, spot.width(), CLIP_LABEL_HEIGHT));
                placed = true;
            }
        }
        commands.set(selector + " #ClipLabel.Visible", placed);
        return axis.clips() && !placed ? " " + text + "." : "";
    }

    private static boolean hasRef(Ref ref) {
        return ref != null && Double.isFinite(ref.value()) && ref.value() > 0;
    }

    private static void reference(String selector, Ref ref, ChartAxis.Axis axis, UICommandBuilder commands) {
        if (!hasRef(ref)) return;
        commands.set(selector + " #RefLabel.Text", ref.label());
        commands.set(selector + " #RefLine.Visible", true);
        commands.set(selector + " #RefLabel.Visible", true);
        int y = Math.max(0, Math.min(HyboxChartImage.HEIGHT - 1, HyboxChartImage.lineRow(ref.value(), axis)));
        commands.setObject(selector + " #RefLine.Anchor", HyboxIncidentRenderer.anchor(0, y, HyboxChartImage.WIDTH, 1));
        commands.setObject(selector + " #RefLabel.Anchor", HyboxIncidentRenderer.anchor(HyboxChartImage.WIDTH - REF_LABEL_WIDTH - 4, Math.max(0, y - 13), REF_LABEL_WIDTH, 12));
    }

    private static double unitFactor(String unit) {
        return unit.equals("GiB") ? 1073741824.0 : unit.equals("GB") ? 1e9 : 1;
    }

    private static int axisDigits(double top) {
        double mid = top / 2;
        for (int digits = 0; digits < 3; digits++) {
            double scaled = mid * Math.pow(10, digits);
            if (Math.abs(scaled - Math.rint(scaled)) < 1e-6 * Math.max(1, Math.abs(scaled))) return digits;
        }
        return 3;
    }

    static String number(double number, String unit, int precision) {
        if (unit.equals("bytes")) return Units.bytes(Math.round(number));
        return Units.decimal(number / unitFactor(unit), precision);
    }

    static String value(double number, String unit, int precision) {
        String text = number(number, unit, precision);
        return unit.equals("bytes") ? text : text + suffix(unit, text);
    }

    static String suffix(String unit) {
        return unit.isBlank() || unit.equals("count") ? "" : unit.startsWith("/") ? unit : " " + unit;
    }

    static String suffix(String unit, String number) {
        String singular = SINGULAR.get(unit);
        return singular != null && number.equals("1") ? " " + singular : suffix(unit);
    }

    private static void labels(String selector, ChartAxis.Axis axis, String unit, int precision, UICommandBuilder commands) {
        if (axis.log()) {
            logLabels(selector, axis, unit, commands);
            return;
        }
        double scale = axis.top();
        int digits = precision == 0 ? 0 : axisDigits(scale / unitFactor(unit));
        commands.set(selector + " #YMax.Text", value(scale, unit, digits));
        commands.set(selector + " #YMid.Text", value(scale / 2, unit, digits));
        commands.set(selector + " #YMin.Text", value(0, unit, 0));
    }

    private static void logLabels(String selector, ChartAxis.Axis axis, String unit, UICommandBuilder commands) {
        for (String fixed : new String[] {"#YMax", "#YMid", "#YMin", "#GridMid"}) commands.set(selector + " " + fixed + ".Visible", false);
        double factor = unitFactor(unit);
        double[] ticks = ChartAxis.logLabels(axis.bottom() / factor, axis.top() / factor,
            HyboxChartImage.BASELINE + 0.5 - HyboxChartImage.TOP, TICK_GAP);
        for (int i = 0; i < ticks.length; i++) {
            int row = HyboxChartImage.lineRow(ticks[i] * factor, axis);
            String tick = selector + " #YTicks[" + i + "]";
            commands.append(selector + " #YTicks", "Pages/HyboxChartTick.ui");
            commands.set(tick + ".Text", value(ticks[i] * factor, unit, ticks[i] >= 1 ? 0 : (int) Math.ceil(-Math.log10(ticks[i]) - 1e-9)));
            commands.setObject(tick + ".Anchor", HyboxIncidentRenderer.anchor(0, Math.max(0, Math.min(90, row - 6)), 56, 12));
            commands.append(selector + " #LogGrid", "Pages/HyboxChartGrid.ui");
            commands.setObject(selector + " #LogGrid[" + i + "].Anchor", HyboxIncidentRenderer.anchor(0, Math.min(HyboxChartImage.BASELINE, row), HyboxChartImage.WIDTH, 1));
        }
    }
}
