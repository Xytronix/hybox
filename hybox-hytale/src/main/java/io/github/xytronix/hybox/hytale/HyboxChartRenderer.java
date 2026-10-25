package io.github.xytronix.hybox.hytale;

import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import java.util.Locale;

final class HyboxChartRenderer {
    private HyboxChartRenderer() {}

    static void plot(String selector, double[] values, double[] markers, int columnCap, String unit,
                     int precision, boolean shadeIncident, HyboxIncidentRenderer.ChartImages images, UICommandBuilder commands) {
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
            bars[c] = count == 0 ? shadeIncident ? 0 : Double.NaN : Math.max(0, sum / count);
            if (Double.isFinite(bars[c])) tallest = Math.max(tallest, bars[c]);
        }
        double scale = tallest > 0 ? tallest : 1;
        labels(selector, scale, unit, precision, commands);
        commands.set(selector + " #ChartLegend.Text", "red = incident · bars = average per column");
        for (int c = 0; c < columns; c++) {
            if (!Double.isFinite(bars[c])) continue;
            int height = Math.max(1, (int) Math.round(90 * bars[c] / scale));
            int left = c * HyboxChartImage.WIDTH / columns;
            int width = Math.max(1, (c + 1) * HyboxChartImage.WIDTH / columns - left - 1);
            boolean incident = false;
            if (shadeIncident) for (double marker : markers) {
                if (marker >= c * values.length / columns && marker < (c + 1) * values.length / columns) incident = true;
            }
            String color = incident ? "#ef8181" : "#7a9cc6";
            commands.appendInline(selector + " #Bars", "Group { Anchor: (Left: " + left + ", Bottom: 0, Width: " + width + ", Height: " + height + "); Background: " + color + "(0.8); }");
        }
        if (!shadeIncident) for (double marker : markers) {
            if (!Double.isFinite(marker) || marker < 0 || marker > values.length - 1) continue;
            int left = (int) Math.round(marker / Math.max(1, values.length - 1) * (HyboxChartImage.WIDTH - 1));
            commands.appendInline(selector + " #Bars", "Group { Anchor: (Left: " + left + ", Bottom: 0, Width: 1, Height: 96); Background: #ef8181(0.8); }");
        }
        if (images == null || values.length < 2) return;
        double lineScale = peak > 0 ? peak : 1;
        images.request(new HyboxIncidentRenderer.ChartRequest(
            () -> shadeIncident && markers.length == 1
                ? HyboxChartImage.line(values, lineScale, markers[0]) : HyboxChartImage.line(values, lineScale, markers),
            (path, swap) -> {
                swap.clear(selector + " #Bars");
                labels(selector, lineScale, unit, precision, swap);
                swap.set(selector + " #PlotImage.AssetPath", path);
                swap.set(selector + " #PlotImage.Visible", true);
                swap.set(selector + " #ChartLegend.Text", shadeIncident ? "every sample · red line = incident" : "sample averages · red lines = incidents");
            }));
    }

    static String value(double number, String unit, int precision) {
        if (unit.equals("bytes")) {
            if (number < 0) return "n/a";
            if (number < 1024) return String.format(Locale.ROOT, "%,.0f B", number);
            String[] units = {"KiB", "MiB", "GiB", "TiB"};
            int i = -1;
            do { number /= 1024; i++; } while (number >= 1024 && i < 3);
            return String.format(Locale.ROOT, "%.1f ", number) + units[i];
        }
        if (unit.equals("GiB")) number /= 1073741824.0;
        if (unit.equals("GB")) number /= 1e9;
        return String.format(Locale.ROOT, "%." + precision + "f", number) + (unit.isBlank() ? "" : " " + unit);
    }

    private static void labels(String selector, double scale, String unit, int precision, UICommandBuilder commands) {
        commands.set(selector + " #YMax.Text", value(scale, unit, precision));
        commands.set(selector + " #YMid.Text", value(scale / 2, unit, precision));
        commands.set(selector + " #YMin.Text", value(0, unit, 0));
    }
}
