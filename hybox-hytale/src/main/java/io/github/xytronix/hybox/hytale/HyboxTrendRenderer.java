package io.github.xytronix.hybox.hytale;

import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import io.github.xytronix.hybox.core.metrics.TrendTimeline;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import io.github.xytronix.hybox.core.text.Units;

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
        commands.set("#RangeLabel.Text", "Health metrics, last " + Units.count(model.days(), "day"));
        for (int days : new int[] {1, 7, 30}) {
            boolean chosen = days == model.days();
            HyboxDashboardRenderer.choice(commands, "#Range" + days, "#Range" + days + "Marker", chosen, "SecondaryButtonStyle");
            commands.set("#Range" + days + ".Disabled", !state.trendAllowed() || chosen);
        }
        boolean sameDay = model.firstSample() / 86_400_000L == model.lastSample() / 86_400_000L;
        String span = model.samples() == 0 ? "no recorded time span" : "from " + date(model.firstSample()) + " to "
            + (sameDay ? TIME.format(Instant.ofEpochMilli(model.lastSample())) : date(model.lastSample())) + " UTC";
        commands.set("#Summary.Text", Units.count(model.samples(), "sample") + " " + span + ", " + Units.count(model.markers().length, "incident"));
        commands.set("#Unavailable.Visible", !model.unavailable().isEmpty() && !state.loading());
        commands.set("#Unavailable.Text", unavailable(model.unavailable()));
        String empty = state.loading() ? "Loading health metrics..." : !model.metricsEnabled()
            ? "Trends are off. Turn on Metrics history in /hybox settings."
            : model.samples() == 0 ? "No health metrics recorded in the last " + Units.count(model.days(), "day") + "."
            : model.metrics().isEmpty() ? "No metric values are available in the recorded samples." : "";
        commands.set("#EmptyState.Text", empty);
        commands.set("#EmptyState.Visible", !empty.isEmpty());
        commands.set("#SaveButton.Disabled", state.saving() || state.loading() || !state.trendAllowed() || model.samples() == 0);
        commands.set("#SettingsButton.Visible", !model.metricsEnabled() && state.settingsAllowed() && !state.loading());
        commands.set("#BackButton.Disabled", !state.dashboardAllowed());
        feedback(state, commands);
        if (state.loading()) return;
        TrendTimeline timeline = model.timeline();
        List<String> flat = new ArrayList<>();
        int shown = 0;
        for (HyboxTrendModel.Metric metric : model.metrics()) {
            if (metric.min() == metric.max()) {
                flat.add(flatLine(metric));
                continue;
            }
            String selector = "#Charts[" + shown++ + "]";
            commands.append("#Charts", "Pages/HyboxIncidentChart.ui");
            commands.set(selector + " #ChartTitle.Text", HyboxChartRenderer.logTitle(metric.title(),
                HyboxChartRenderer.trend(selector, model, metric, images, commands).log()));
            String stats = "min " + stat(metric.min(), metric) + " · avg " + stat(metric.average(), metric)
                + " · max " + stat(metric.max(), metric) + " · last " + stat(metric.now(), metric)
                + (metric.note() == null ? "" : " · " + metric.note());
            commands.set(selector + " #ChartStats.Text", stats);
            for (int x = 0; x < 5; x++) {
                long time = timeline.timeAt(x / 4.0);
                commands.set(selector + " #X" + x + ".Text", (model.days() == 1 && !timeline.collapsed() ? TIME : DATE).format(Instant.ofEpochMilli(time)));
            }
            HyboxChartRenderer.hover(commands, selector + " #Hover", HyboxChartImage.WIDTH, HyboxChartImage.HEIGHT, tips(model, metric));
        }
        if (!flat.isEmpty()) {
            commands.append("#Charts", "Pages/HyboxIncidentParagraph.ui");
            commands.set("#Charts[" + shown + "] #Paragraph.Text", String.join(" ", flat));
        }
    }

    static String flatLine(HyboxTrendModel.Metric metric) {
        int paren = metric.title().indexOf(" (");
        String name = paren < 0 ? metric.title() : metric.title().substring(0, paren);
        boolean bare = java.util.Set.of("TPS", "players", "packets", "sections").contains(metric.unit());
        String amount = bare ? stat(metric.min(), metric) : value(metric.min(), metric);
        return name + ": " + amount + " the whole time.";
    }

    static String unavailable(List<String> names) {
        List<String> missing = names.stream().filter(name -> !name.equals(HyboxTrendModel.RSS)).toList();
        List<String> settings = missing.stream().filter(name -> name.equals("CPU use") || name.equals("Allocation rate")).toList();
        String text = missing.isEmpty() ? "" : "No data: " + String.join(", ", missing) + ".";
        if (names.contains(HyboxTrendModel.RSS)) text += (text.isEmpty() ? "" : " ") + HyboxTrendModel.RSS + ": Linux only.";
        if (!settings.isEmpty()) {
            text += " Turn on " + String.join(" and ", settings) + " in Settings to record " + (settings.size() == 1 ? "it." : "them.");
        }
        return text;
    }

    static List<String> tips(HyboxTrendModel model, HyboxTrendModel.Metric metric) {
        TrendTimeline timeline = model.timeline();
        TrendTimeline.Series series = metric.series();
        long[][] ranges = timeline.bucketRanges();
        int buckets = series.mean().length, cells = Math.min(HyboxChartRenderer.HOVER_CELLS, buckets);
        List<String> tips = new ArrayList<>(cells);
        for (int c = 0; c < cells; c++) {
            int from = c * buckets / cells, to = (c + 1) * buckets / cells;
            double sum = 0, low = Double.NaN, high = Double.NaN;
            int count = 0;
            long first = Long.MAX_VALUE, last = Long.MIN_VALUE;
            for (int b = from; b < to; b++) {
                if (ranges[b] != null) {
                    first = Math.min(first, ranges[b][0]);
                    last = Math.max(last, ranges[b][1]);
                }
                if (series.count()[b] == 0) continue;
                sum += series.mean()[b] * series.count()[b];
                count += series.count()[b];
                low = Double.isNaN(low) ? series.min()[b] : Math.min(low, series.min()[b]);
                high = Double.isNaN(high) ? series.max()[b] : Math.max(high, series.max()[b]);
            }
            double middle = (from + to) / 2.0 / buckets;
            String when = first <= last ? range(first, last) : range(timeline.timeAt((double) from / buckets), timeline.timeAt((double) to / buckets));
            if (count > 0) {
                tips.add(when + " · min " + stat(low, metric) + " · avg " + stat(sum / count, metric) + " · max " + value(high, metric));
            } else {
                tips.add(when + " · " + (timeline.inGap(middle) ? "server off" : "no data"));
            }
        }
        return tips;
    }

    static void feedback(ViewState state, UICommandBuilder commands) {
        commands.set("#Feedback.Text", state.feedback());
        commands.set("#Feedback.TooltipText", state.feedback());
    }

    private static String range(long first, long last) {
        if (last - first < 60_000) return date(first);
        boolean sameDay = first / 86_400_000L == last / 86_400_000L;
        return date(first) + " to " + (sameDay ? TIME : DATE).format(Instant.ofEpochMilli(last));
    }

    private static String value(double value, HyboxTrendModel.Metric metric) {
        return HyboxChartRenderer.value(value, metric.unit(), metric.precision());
    }

    private static String stat(double value, HyboxTrendModel.Metric metric) {
        return HyboxChartRenderer.number(value, metric.unit(), metric.precision());
    }

    private static String date(long time) {
        return DATE.format(Instant.ofEpochMilli(time));
    }

    private static void bind(UIEventBuilder events, String selector, String action, String arg) {
        events.addEventBinding(CustomUIEventBindingType.Activating, selector, EventData.of("Action", action).append("Arg", arg));
    }
}
