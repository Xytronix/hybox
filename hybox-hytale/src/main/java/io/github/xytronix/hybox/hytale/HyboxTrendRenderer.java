package io.github.xytronix.hybox.hytale;

import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import io.github.xytronix.hybox.core.text.Units;

final class HyboxTrendRenderer {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMM d HH:mm", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter SECONDS = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT).withZone(ZoneOffset.UTC);

    record ViewState(String feedback, boolean loading, boolean saving, boolean trendAllowed,
                     boolean settingsAllowed, boolean dashboardAllowed) {}

    private HyboxTrendRenderer() {}

    static void bind(UIEventBuilder events) {
        for (int days : new int[] {1, 7, 30}) bind(events, "#Range" + days, "range", Integer.toString(days));
        bind(events, "#SaveButton", "save", "");
        bind(events, "#SettingsButton", "settings", "");
        bind(events, "#BackButton", "back", "");
        bind(events, "#DismissButton", "close", "");
    }

    static void render(HyboxTrendModel model, ViewState state, UICommandBuilder commands,
                       HyboxIncidentRenderer.ChartImages images) {
        commands.clear("#Charts");
        commands.set("#RangeLabel.Text", "Health metrics · last " + Units.count(model.days(), "day"));
        for (int days : new int[] {1, 7, 30}) commands.set("#Range" + days + ".Disabled", !state.trendAllowed() || days == model.days());
        String span = model.samples() == 0 ? "no recorded time span" : date(model.firstSample()) + " – " + date(model.lastSample()) + " UTC";
        commands.set("#Summary.Text", Units.count(model.samples(), "sample") + " · " + span + " · " + Units.count(model.markers().length, "incident") + " in range");
        commands.set("#Unavailable.Visible", !model.unavailable().isEmpty() && !state.loading());
        commands.set("#Unavailable.Text", "Off / unavailable: " + String.join(", ", model.unavailable()));
        String empty = state.loading() ? "Loading health metrics…" : !model.metricsEnabled()
            ? "Metrics.Enabled is off. Enable it to record health metric history."
            : model.samples() == 0 ? "No health metrics recorded in the last " + Units.count(model.days(), "day") + "."
            : model.metrics().isEmpty() ? "No metric values are available in the recorded samples." : "";
        commands.set("#EmptyState.Text", empty);
        commands.set("#EmptyState.Visible", !empty.isEmpty());
        commands.set("#SaveButton.Disabled", state.saving() || state.loading() || !state.trendAllowed() || model.samples() == 0);
        commands.set("#SettingsButton.Visible", !model.metricsEnabled() && state.settingsAllowed() && !state.loading());
        commands.set("#BackButton.Disabled", !state.dashboardAllowed());
        feedback(state, commands);
        if (state.loading()) return;
        for (int i = 0; i < model.metrics().size(); i++) {
            HyboxTrendModel.Metric metric = model.metrics().get(i);
            String selector = "#Charts[" + i + "]";
            commands.append("#Charts", "Pages/HyboxIncidentChart.ui");
            commands.set(selector + " #ChartTitle.Text", metric.title());
            String stats = "min " + value(metric.min(), metric) + " · avg " + value(metric.average(), metric)
                + " · max " + value(metric.max(), metric) + " · now " + value(metric.now(), metric);
            commands.set(selector + " #ChartStats.Text", stats);
            for (int x = 0; x < 5; x++) {
                long time = model.rangeStart() + (model.generatedAt().toEpochMilli() - model.rangeStart()) * x / 4;
                commands.set(selector + " #X" + x + ".Text", (model.days() == 1 ? TIME : DATE).format(Instant.ofEpochMilli(time)));
            }
            HyboxChartRenderer.plot(selector, metric.values(), model.markers(), 120 - HyboxChartRenderer.HOVER_CELLS, metric.unit(), metric.precision(),
                metric.max(), false, images, commands);
            HyboxChartRenderer.hover(commands, selector + " #Hover", HyboxChartImage.WIDTH, HyboxChartImage.HEIGHT, tips(model, metric));
        }
    }

    private static java.util.List<String> tips(HyboxTrendModel model, HyboxTrendModel.Metric metric) {
        double[] values = metric.values();
        int n = values.length, cells = Math.min(HyboxChartRenderer.HOVER_CELLS, n);
        long start = model.rangeStart(), span = model.generatedAt().toEpochMilli() - start;
        java.util.List<String> tips = new java.util.ArrayList<>();
        for (int c = 0; c < cells; c++) {
            int from = c * n / cells, to = (c + 1) * n / cells;
            double sum = 0;
            int count = 0;
            for (int i = from; i < to; i++) if (Double.isFinite(values[i])) { sum += values[i]; count++; }
            Instant time = Instant.ofEpochMilli(start + span * from / Math.max(1, n - 1));
            tips.add((model.days() == 1 ? SECONDS : DATE).format(time) + " · " + (count == 0 ? "no data" : value(sum / count, metric)));
        }
        return tips;
    }

    static void feedback(ViewState state, UICommandBuilder commands) {
        commands.set("#Feedback.Text", state.feedback());
        commands.set("#Feedback.TooltipText", state.feedback());
    }

    private static String value(double value, HyboxTrendModel.Metric metric) {
        return HyboxChartRenderer.value(value, metric.unit(), metric.precision());
    }

    private static String date(long time) {
        return DATE.format(Instant.ofEpochMilli(time));
    }

    private static void bind(UIEventBuilder events, String selector, String action, String arg) {
        events.addEventBinding(CustomUIEventBindingType.Activating, selector, EventData.of("Action", action).append("Arg", arg));
    }
}
