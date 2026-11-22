package io.github.xytronix.hybox.hytale;

import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import java.util.Locale;
import io.github.xytronix.hybox.core.text.Units;

final class HyboxChartRenderer {
    static final int HOVER_CELLS = 20;
    private static final int REF_LABEL_WIDTH = 240;

    record Ref(double value, String label) {}

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

    static void plot(String selector, double[] values, double[] markers, int columnCap, String unit, int precision,
                     double statedMax, Ref ref, boolean shadeIncident, HyboxIncidentRenderer.ChartImages images, UICommandBuilder commands) {
        int columns = Math.min(columnCap, values.length);
        double[] bars = new double[columns];
        double tallest = 0, peak = 0;
        for (int c = 0; c < columns; c++) {
            int from = c * values.length / columns, to = (c + 1) * values.length / columns;
            double sum = 0;
            int count = 0;
            for (int i = from; i < to; i++) {
                if (!Double.isFinite(values[i])) continue;
                sum += values[i];
                peak = Math.max(peak, values[i]);
                count++;
            }
            bars[c] = count == 0 ? Double.NaN : Math.max(0, sum / count);
            if (Double.isFinite(bars[c])) tallest = Math.max(tallest, bars[c]);
        }
        double top = Double.isFinite(statedMax) ? statedMax : 0;
        boolean hasRef = ref != null && Double.isFinite(ref.value()) && ref.value() > 0;
        if (hasRef) top = Math.max(top, ref.value() * 1.15);
        double scale = axisTop(values, Math.max(top, tallest), precision);
        labels(selector, scale, unit, precision, commands);
        if (hasRef) {
            commands.set(selector + " #RefLabel.Text", ref.label());
            commands.set(selector + " #RefLine.Visible", true);
            commands.set(selector + " #RefLabel.Visible", true);
            reference(selector, HyboxChartImage.HEIGHT - (int) Math.round(90 * ref.value() / scale), commands);
        }
        commands.set(selector + " #ChartLegend.Text", shadeIncident
            ? "Bars show the average per column. The highlighted bar holds the incident."
            : "Bars show the average per column. Thin lines mark incidents.");
        for (int c = 0; c < columns; c++) {
            if (!Double.isFinite(bars[c])) continue;
            int height = Math.max(1, (int) Math.round(90 * bars[c] / scale));
            int left = c * HyboxChartImage.WIDTH / columns;
            int width = Math.max(1, (c + 1) * HyboxChartImage.WIDTH / columns - left - 1);
            boolean incident = false;
            if (shadeIncident) for (double marker : markers) {
                if (marker >= c * values.length / columns && marker < (c + 1) * values.length / columns) incident = true;
            }
            String color = HyboxChartImage.hex(incident ? HyboxChartImage.INCIDENT : HyboxChartImage.LINE);
            commands.appendInline(selector + " #Bars", "Group { Anchor: (Left: " + left + ", Bottom: 0, Width: " + width + ", Height: " + height + "); Background: " + color + "; }");
        }
        if (!shadeIncident) for (double marker : markers) {
            if (!Double.isFinite(marker) || marker < 0 || marker > values.length - 1) continue;
            int left = (int) Math.round(marker / Math.max(1, values.length - 1) * (HyboxChartImage.WIDTH - 1));
            commands.appendInline(selector + " #Bars", "Group { Anchor: (Left: " + left + ", Bottom: 0, Width: 1, Height: 96); Background: " + HyboxChartImage.hex(HyboxChartImage.INCIDENT) + "; }");
        }
        if (images == null || values.length < 2) return;
        double lineScale = axisTop(values, Math.max(top, peak), precision);
        images.request(new HyboxIncidentRenderer.ChartRequest(
            () -> shadeIncident && markers.length == 1
                ? HyboxChartImage.line(values, lineScale, markers[0]) : HyboxChartImage.line(values, lineScale, markers),
            (path, swap) -> {
                swap.clear(selector + " #Bars");
                labels(selector, lineScale, unit, precision, swap);
                if (hasRef) reference(selector, HyboxChartImage.lineRow(ref.value(), lineScale), swap);
                swap.set(selector + " #PlotImage.AssetPath", path);
                swap.set(selector + " #PlotImage.Visible", true);
                swap.set(selector + " #ChartLegend.Text", shadeIncident ? "Every sample. The dashed line marks the incident." : "Sample averages. Dashed lines mark incidents.");
            }));
    }

    private static void reference(String selector, int row, UICommandBuilder commands) {
        int y = Math.max(0, Math.min(HyboxChartImage.HEIGHT - 1, row));
        commands.setObject(selector + " #RefLine.Anchor", HyboxIncidentRenderer.anchor(0, y, HyboxChartImage.WIDTH, 1));
        commands.setObject(selector + " #RefLabel.Anchor", HyboxIncidentRenderer.anchor(HyboxChartImage.WIDTH - REF_LABEL_WIDTH - 4, Math.max(0, y - 13), REF_LABEL_WIDTH, 12));
    }

    static double axisTop(double[] values, double top, int precision) {
        if (precision != 0) return top > 0 ? top : 1;
        double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
        for (double value : values) {
            if (!Double.isFinite(value)) continue;
            low = Math.min(low, value);
            high = Math.max(high, value);
        }
        double whole = Math.ceil(Math.max(0, top));
        if (low == high) whole = Math.max(whole, Math.ceil(high) + 1);
        whole = Math.max(2, whole);
        return whole % 2 == 0 ? whole : whole + 1;
    }

    static String value(double number, String unit, int precision) {
        if (unit.equals("bytes")) return Units.bytes(Math.round(number));
        if (unit.equals("GiB")) number /= 1073741824.0;
        if (unit.equals("GB")) number /= 1e9;
        return String.format(Locale.ROOT, "%." + precision + "f", number) + suffix(unit);
    }

    static String suffix(String unit) {
        return unit.isBlank() || unit.equals("count") ? "" : unit.startsWith("/") ? unit : " " + unit;
    }

    private static void labels(String selector, double scale, String unit, int precision, UICommandBuilder commands) {
        commands.set(selector + " #YMax.Text", value(scale, unit, precision));
        commands.set(selector + " #YMid.Text", value(scale / 2, unit, precision));
        commands.set(selector + " #YMin.Text", value(0, unit, 0));
    }
}
