package io.github.xytronix.hybox.hytale;

import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

final class HyboxTrendRenderer {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMM d HH:mm", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).withZone(ZoneOffset.UTC);

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
        commands.set("#RangeLabel.Text", "Health metrics · last " + model.days() + (model.days() == 1 ? " day" : " days"));
        for (int days : new int[] {1, 7, 30}) commands.set("#Range" + days + ".Disabled", !state.trendAllowed() || days == model.days());
        String span = model.samples() == 0 ? "no recorded time span" : date(model.firstSample()) + " – " + date(model.lastSample()) + " UTC";
        commands.set("#Summary.Text", count(model.samples()) + " samples · " + span + " · " + count(model.markers().length) + " incidents in range");
        commands.set("#Unavailable.Visible", !model.unavailable().isEmpty() && !state.loading());
        commands.set("#Unavailable.Text", "Off / unavailable: " + String.join(", ", model.unavailable()));
        String empty = state.loading() ? "Loading health metrics…" : !model.metricsEnabled()
            ? "Metrics.Enabled is off. Enable it to record health metric history."
            : model.samples() == 0 ? "No health metrics recorded in the last " + model.days() + " day(s)."
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
            commands.set(selector + " #ChartStats.TooltipText", stats);
            for (int x = 0; x < 5; x++) {
                long time = model.rangeStart() + (model.generatedAt().toEpochMilli() - model.rangeStart()) * x / 4;
                commands.set(selector + " #X" + x + ".Text", (model.days() == 1 ? TIME : DATE).format(Instant.ofEpochMilli(time)));
            }
            commands.set(selector + " #Plot.TooltipText", "UTC · red markers: " + model.markers().length + " incidents in range");
            HyboxChartRenderer.plot(selector, metric.values(), model.markers(), 120, metric.unit(), metric.precision(), false, images, commands);
        }
    }

    static void feedback(ViewState state, UICommandBuilder commands) {
        commands.set("#Feedback.Text", state.feedback());
        commands.set("#Feedback.TooltipText", state.feedback());
    }

    private static String value(double value, HyboxTrendModel.Metric metric) {
        return HyboxChartRenderer.value(value, metric.unit(), metric.precision());
    }

    private static String count(int count) {
        return String.format(Locale.ROOT, "%,d", count);
    }

    private static String date(long time) {
        return DATE.format(Instant.ofEpochMilli(time));
    }

    private static void bind(UIEventBuilder events, String selector, String action, String arg) {
        events.addEventBinding(CustomUIEventBindingType.Activating, selector, EventData.of("Action", action).append("Arg", arg));
    }
}
