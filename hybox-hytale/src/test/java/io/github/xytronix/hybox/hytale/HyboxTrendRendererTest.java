package io.github.xytronix.hybox.hytale;

import static org.junit.jupiter.api.Assertions.*;

import com.hypixel.hytale.protocol.packets.interface_.CustomUICommand;
import com.hypixel.hytale.protocol.packets.interface_.CustomUICommandType;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import io.github.xytronix.hybox.core.metrics.ChartAxis;
import io.github.xytronix.hybox.core.metrics.MetricsLog;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.imageio.ImageIO;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

class HyboxTrendRendererTest {
    private static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");
    private static final HyboxTrendRenderer.ViewState READY = new HyboxTrendRenderer.ViewState("", false, false, true, true, true);

    @Test
    void rendersAllPresentMetricsWithStatisticsBothAxesAndAllIncidentMarkers() {
        var model = model(true);
        var commands = render(model, READY);
        assertEquals(9, Arrays.stream(commands.getCommands()).filter(c -> c.selector != null && c.selector.endsWith("#ChartTitle.Text")).count());
        assertEquals(50, model.metrics().get(0).ref().value(), 1e-9, "Tick budget follows the world's 20 TPS target");
        assertEquals(20, model.metrics().get(1).ref().value(), 1e-9);
        var one = trend(new long[] {NOW.toEpochMilli()});
        one.tickAvgMs()[0] = 1;
        one.tps()[0] = 30;
        assertEquals(30, HyboxTrendModel.from(true, 7, NOW, one, new long[0], 0)
            .metrics().get(1).ref().value(), 1e-9, "No running world falls back to 30 TPS");
        one.targetTps()[0] = 60;
        assertEquals(60, HyboxTrendModel.from(true, 7, NOW, one, new long[0], 20)
            .metrics().get(1).ref().value(), 1e-9, "The recorded target of the sample wins");
        assertTrue(text(commands, "#Summary.Text").matches("3 samples from \\w+ \\d+ \\d\\d:\\d\\d to .* UTC, 2 incidents"), text(commands, "#Summary.Text"));
        for (int i = 0; i < 9; i++) {
            String chart = "#Charts[" + i + "]";
            String statistics = text(commands, chart + " #ChartStats.Text");
            for (String label : List.of("min ", "avg ", "max ", "last ")) assertTrue(statistics.contains(label), statistics);
            for (String axis : List.of("#YMax", "#YMid", "#YMin", "#X0", "#X1", "#X2", "#X3", "#X4")) {
                assertFalse(text(commands, chart + " " + axis + ".Text").isBlank(), axis);
            }
            assertEquals("Oct 18 12:00", text(commands, chart + " #X0.Text"));
            assertEquals("Oct 25 12:00", text(commands, chart + " #X4.Text"));
            assertEquals(2, Arrays.stream(commands.getCommands()).filter(c -> (chart + " #Bars").equals(c.selector)
                && c.text != null && c.text.contains(HyboxChartImage.hex(HyboxChartImage.INCIDENT))).count());
        }
        assertFalse(Arrays.stream(commands.getCommands()).anyMatch(c -> c.text != null && c.text.contains("#ff8a3d")));
        assertEquals("min 10.0 · avg 20.0 · max 30.0 · last 30.0", text(commands, "#Charts[0] #ChartStats.Text"));
        assertEquals("Tick time (ms)", text(commands, "#Charts[0] #ChartTitle.Text"));
        assertFalse(bool(commands, "#EmptyState.Visible"));
    }

    @Test
    void integerChartsUseWholeDistinctAxisLabelsAndClipAboveTheTop() {
        for (double[] values : List.of(new double[] {1, 1, 1}, new double[] {0, 0, 0}, new double[] {4, 4}, new double[] {0, 1, 0, 1},
            new double[] {2, 5, 3}, new double[] {0, 3, 1})) {
            var commands = new UICommandBuilder();
            double max = Arrays.stream(values).max().orElse(0);
            HyboxChartRenderer.plot("#C", values, new double[0], 100, "count", 0, ChartAxis.Family.PACKETS, null, null, null, commands);
            long top = Long.parseLong(text(commands, "#C #YMax.Text"));
            long mid = Long.parseLong(text(commands, "#C #YMid.Text"));
            long low = Long.parseLong(text(commands, "#C #YMin.Text"));
            String shown = Arrays.toString(values);
            assertEquals(0, low, shown);
            assertEquals(top, 2 * mid, shown);
            assertTrue(mid > low && mid < top, shown);
            assertEquals(top < max, bool(commands, "#C #ClipLabel.Visible"), shown);
            if (Arrays.stream(values).allMatch(value -> value == values[0])) assertTrue(top >= values[0] + 1, shown);
        }
        var players = new UICommandBuilder();
        HyboxChartRenderer.plot("#P", new double[] {1, 1, 1}, new double[0], 100, "players", 0, ChartAxis.Family.PLAYERS, null, null, null, players);
        assertEquals("2 players", text(players, "#P #YMax.Text"));
        assertEquals("1 player", text(players, "#P #YMid.Text"));
        assertEquals("0 players", text(players, "#P #YMin.Text"));
        assertEquals("1 chunk", HyboxChartRenderer.value(1, "chunks", 0));
        assertEquals("1.5 chunks", HyboxChartRenderer.value(1.5, "chunks", 1));
        assertEquals("1 entity", HyboxChartRenderer.value(1, "entities", 0));
        var tps = new UICommandBuilder();
        HyboxChartRenderer.plot("#T", new double[] {30, 30, 29.6, 8.5, 30}, new double[0], 100, "TPS", 1, ChartAxis.Family.TPS,
            new HyboxChartRenderer.Ref(30, "30 TPS target", true), null, null, tps);
        assertEquals("40 TPS", text(tps, "#T #YMax.Text"), "TPS never passes its target, so the top is 15% above it");
        assertFalse(bool(tps, "#T #ClipLabel.Visible"));
        var budget = new UICommandBuilder();
        HyboxChartRenderer.plot("#B", new double[] {5, 6}, new double[0], 100, "ms", 1, ChartAxis.Family.TICK_TIME, new HyboxChartRenderer.Ref(1000 / 30.0, "33.3 ms budget"), null, null, budget);
        assertEquals("40 ms", text(budget, "#B #YMax.Text"));
        assertEquals("20 ms", text(budget, "#B #YMid.Text"));
        assertFalse(bool(budget, "#B #ClipLabel.Visible"));
    }

    @Test
    void oneSpikeIsClippedAndNamedWhileTheBudgetLineStaysVisible() throws Exception {
        double[] values = new double[120];
        Arrays.fill(values, 3);
        values[90] = 117.2;
        var commands = new UICommandBuilder();
        var requests = new ArrayList<HyboxIncidentRenderer.ChartRequest>();
        var axis = HyboxChartRenderer.plot("#C", values, new double[0], 100, "ms", 1, ChartAxis.Family.TICK_TIME,
            new HyboxChartRenderer.Ref(1000 / 30.0, "33.3 ms budget"), null, requests::add, commands);
        assertFalse(axis.log(), "117.2 next to 3 is less than two decades, so the axis stays linear");
        assertEquals("40 ms", text(commands, "#C #YMax.Text"));
        assertTrue(bool(commands, "#C #ClipLabel.Visible"));
        assertEquals("117.2 ms off scale", text(commands, "#C #ClipLabel.Text"));
        assertTrue(bool(commands, "#C #RefLine.Visible"));
        var image = ImageIO.read(new ByteArrayInputStream(requests.getFirst().draw().get()));
        int x = (int) Math.round(90.0 / 119 * (HyboxChartImage.WIDTH - 1));
        assertTrue((image.getRGB(x, HyboxChartImage.TOP - 3) >>> 24) > 200, "triangle marks the clipped tick");
        int[] tops = HyboxChartRenderer.lineTops(values, axis);
        var spot = HyboxChartRenderer.placement(tops, "117.2 ms off scale".length() * 7 + 4, x, 636, HyboxChartImage.WIDTH);
        assertNotNull(spot);
        assertTrue(spot.left() + spot.width() <= x - 10, "label sits left of the triangle with a gap");
        var expected = new UICommandBuilder();
        expected.setObject("#C #ClipLabel.Anchor", HyboxIncidentRenderer.anchor(spot.left(), 0, spot.width(), HyboxChartRenderer.CLIP_LABEL_HEIGHT));
        assertEquals(expected.getCommands()[0].data, command(commands, "#C #ClipLabel.Anchor").data);
        for (int px = spot.left(); px < spot.left() + spot.width(); px++) {
            for (int py = 0; py < HyboxChartRenderer.CLIP_LABEL_HEIGHT; py++) {
                assertEquals(0, image.getRGB(px, py) >>> 24, "nothing is drawn under the label at " + px + "," + py);
            }
        }
    }

    @Test
    void aFreezeNextToFastTicksIsDrawnToScaleOnALogAxisWithTheBudgetLine() throws Exception {
        double[] values = new double[200];
        Arrays.fill(values, 5);
        values[150] = 2000;
        values[20] = 0;
        var commands = new UICommandBuilder();
        var requests = new ArrayList<HyboxIncidentRenderer.ChartRequest>();
        double budget = 1000 / 30.0;
        var axis = HyboxChartRenderer.plot("#L", values, new double[0], 100, "ms", 1, ChartAxis.Family.TICK_TIME,
            new HyboxChartRenderer.Ref(budget, "33.3 ms budget"), null, requests::add, commands);
        assertTrue(axis.log());
        assertEquals(2000, axis.top(), 1e-9);
        assertEquals(5, axis.bottom(), 1e-9);
        assertFalse(axis.clips());
        assertFalse(bool(commands, "#L #ClipLabel.Visible"));
        assertFalse(bool(commands, "#L #YMax.Visible"));
        assertFalse(bool(commands, "#L #GridMid.Visible"));
        assertEquals(List.of("10 ms", "100 ms", "1000 ms"), List.of(text(commands, "#L #YTicks[0].Text"),
            text(commands, "#L #YTicks[1].Text"), text(commands, "#L #YTicks[2].Text")));
        assertEquals(3, Arrays.stream(commands.getCommands()).filter(c -> "#L #YTicks".equals(c.selector)).count());
        assertTrue(bool(commands, "#L #RefLine.Visible"));
        int refRow = HyboxChartImage.lineRow(budget, axis);
        assertTrue(refRow > HyboxChartImage.lineRow(100, axis) && refRow < HyboxChartImage.lineRow(10, axis));
        assertEquals(anchor("#L #RefLine.Anchor", refRow), command(commands, "#L #RefLine.Anchor").data);
        var image = ImageIO.read(new ByteArrayInputStream(requests.getFirst().draw().get()));
        int x = (int) Math.round(150.0 / 199 * (HyboxChartImage.WIDTH - 1));
        assertTrue((image.getRGB(x, HyboxChartImage.TOP) >>> 24) > 200, "the freeze reaches the top of the scale");
        assertEquals(0, image.getRGB(x, HyboxChartImage.TOP - 3) >>> 24, "no off scale triangle on a log axis");
        int zero = (int) Math.round(20.0 / 199 * (HyboxChartImage.WIDTH - 1));
        assertTrue((image.getRGB(zero, HyboxChartImage.BASELINE) >>> 24) > 200, "a zero sample sits on the axis floor");
        int fast = (int) Math.round(100.0 / 199 * (HyboxChartImage.WIDTH - 1));
        assertTrue((image.getRGB(fast, HyboxChartImage.BASELINE) >>> 24) > 200, "5 ms ticks sit on the floor of a 5 to 2000 ms scale");
        assertTrue((image.getRGB(fast, refRow) >>> 24) < 50, "fast ticks stay well below the budget line");
        var trend = trend(times(240));
        Arrays.fill(trend.tickAvgMs(), 5);
        trend.tickAvgMs()[200] = 2000;
        var trendCommands = render(HyboxTrendModel.from(true, 7, NOW, trend, new long[0], 30), READY);
        assertEquals("Tick time (ms, log scale)", text(trendCommands, "#Charts[0] #ChartTitle.Text"));
        assertEquals("TPS", text(render(model(true), READY), "#Charts[1] #ChartTitle.Text"));
    }

    private static long[] times(int count) {
        long[] times = new long[count];
        for (int i = 0; i < count; i++) times[i] = NOW.minus(Duration.ofHours(4)).plusSeconds(i * 60L).toEpochMilli();
        return times;
    }

    @Test
    void offScaleLabelFallsBackToTheLegendWhenTheTopIsFull() {
        double[] values = new double[1000];
        for (int i = 0; i < values.length; i++) values[i] = i % 53 == 0 ? 1000 : 1;
        var commands = new UICommandBuilder();
        HyboxChartRenderer.plot("#F", values, new double[0], 100, "ms", 1, ChartAxis.Family.OTHER, null, null, null, commands);
        assertFalse(bool(commands, "#F #ClipLabel.Visible"));
        assertTrue(text(commands, "#F #ChartLegend.Text").endsWith(" 1000.0 ms off scale."), text(commands, "#F #ChartLegend.Text"));
    }

    @Test
    void referenceLineSitsAtTheBudgetOnTheImage() throws Exception {
        double budget = 1000 / 30.0;
        double[] values = new double[40];
        Arrays.fill(values, budget);
        var commands = new UICommandBuilder();
        var requests = new ArrayList<HyboxIncidentRenderer.ChartRequest>();
        HyboxChartRenderer.plot("#C", values, new double[0], 100, "ms", 1, ChartAxis.Family.TICK_TIME, new HyboxChartRenderer.Ref(budget, "33.3 ms budget"), null, requests::add, commands);
        assertTrue(bool(commands, "#C #RefLine.Visible"));
        assertEquals("33.3 ms budget", text(commands, "#C #RefLabel.Text"));
        var image = ImageIO.read(new ByteArrayInputStream(requests.getFirst().draw().get()));
        int row = 0;
        for (int y = 1; y < image.getHeight(); y++) if ((image.getRGB(image.getWidth() / 2, y) >>> 24) > (image.getRGB(image.getWidth() / 2, row) >>> 24)) row = y;
        assertEquals(anchor("#C #RefLine.Anchor", row), command(commands, "#C #RefLine.Anchor").data);
        var swap = new UICommandBuilder();
        requests.getFirst().apply().accept("UI/Custom/Hybox/Charts/test.png", swap);
        assertTrue(bool(swap, "#C #PlotImage.Visible"));
    }

    private static String anchor(String selector, int top) {
        var expected = new UICommandBuilder();
        expected.setObject(selector, HyboxIncidentRenderer.anchor(0, top, HyboxChartImage.WIDTH, 1));
        return expected.getCommands()[0].data;
    }

    @Test
    void skipsAbsentSeriesAndExplainsMetricsThatAreOff() {
        var model = model(false);
        var commands = render(model, READY);
        assertEquals(7, model.metrics().size());
        assertEquals(7, Arrays.stream(commands.getCommands()).filter(c -> c.selector != null && c.selector.endsWith("#ChartTitle.Text")).count());
        assertTrue(bool(commands, "#Unavailable.Visible"));
        assertEquals("No data: CPU use, Allocation rate. Turn on CPU use and Allocation rate in Settings to record them.",
            text(commands, "#Unavailable.Text"));
        assertFalse(Arrays.stream(commands.getCommands()).anyMatch(c -> c.selector != null && c.selector.endsWith("#ChartTitle.Text")
            && c.data != null && (c.data.contains("CPU") || c.data.contains("Allocation"))));
    }

    @Test
    void missingRssSaysLinuxOnlyInsteadOfNoData() {
        assertEquals("Process RSS: Linux only.", HyboxTrendRenderer.unavailable(List.of("Process RSS")));
        assertEquals("No data: Allocation rate. Process RSS: Linux only. Turn on Allocation rate in Settings to record it.",
            HyboxTrendRenderer.unavailable(List.of("Process RSS", "Allocation rate")));
    }

    @Test
    void disabledMetricsAndAnEmptyRangeHaveDistinctStates() {
        var disabled = render(HyboxTrendModel.from(false, 7, NOW, MetricsLog.Trend.EMPTY, new long[0], 0), READY);
        assertEquals("Trends are off. Turn on Metrics history in /hybox settings.", text(disabled, "#EmptyState.Text"));
        assertTrue(bool(disabled, "#EmptyState.Visible"));
        assertTrue(bool(disabled, "#SettingsButton.Visible"));
        assertTrue(bool(disabled, "#SaveButton.Disabled"));
        var denied = render(HyboxTrendModel.from(false, 7, NOW, MetricsLog.Trend.EMPTY, new long[0], 0),
            new HyboxTrendRenderer.ViewState("", false, false, false, false, false));
        assertFalse(bool(denied, "#SettingsButton.Visible"));
        assertTrue(bool(denied, "#BackButton.Disabled"));
        var empty = render(HyboxTrendModel.from(true, 30, NOW, MetricsLog.Trend.EMPTY, new long[0], 0), READY);
        assertEquals("No health metrics recorded in the last 30 days.", text(empty, "#EmptyState.Text"));
        assertFalse(bool(empty, "#SettingsButton.Visible"));
        assertFalse(Arrays.stream(empty.getCommands()).anyMatch(c -> "#Charts".equals(c.selector) && c.type == CustomUICommandType.Append));
    }

    @Test
    void rangeSelectionLoadingAndSavingKeepActionsHonest() {
        assertEquals(1, HyboxTrendModel.days(-5));
        assertEquals(30, HyboxTrendModel.days(90));
        assertEquals(5, HyboxTrendModel.days(5));
        var commands = render(model(true), new HyboxTrendRenderer.ViewState("Loading health metrics…", true, false, true, true, true));
        assertTrue(text(commands, "#EmptyState.Text").contains("Loading"));
        assertTrue(bool(commands, "#SaveButton.Disabled"));
        assertFalse(Arrays.stream(commands.getCommands()).anyMatch(c -> c.selector != null && c.selector.endsWith("#ChartTitle.Text")));
        var denied = render(model(true), new HyboxTrendRenderer.ViewState("denied", false, false, false, false, false));
        for (int days : new int[] {1, 7, 30}) assertTrue(bool(denied, "#Range" + days + ".Disabled"));
        var ready = render(model(true), READY);
        for (int days : new int[] {1, 7, 30}) {
            assertEquals(days == 7, bool(ready, "#Range" + days + "Marker.Visible"));
            assertEquals(days == 7, command(ready, "#Range" + days + ".Style").data.contains("SelectedButtonStyle"));
        }
        assertTrue(bool(denied, "#SaveButton.Disabled"));
        assertEquals("denied", text(denied, "#Feedback.Text"));
        var events = new UIEventBuilder();
        HyboxTrendRenderer.bind(events);
        assertEquals(7, events.getEvents().length);
        assertTrue(Arrays.stream(events.getEvents()).anyMatch(e -> "#SaveButton".equals(e.selector) && e.data.contains("save")));
    }

    @Test
    void bucketCountComesFromTheChartWidthAndKeepsRawSampleStatistics() {
        int size = 1200;
        long[] times = new long[size];
        double[] values = new double[size];
        for (int i = 0; i < size; i++) {
            times[i] = NOW.minus(Duration.ofDays(7)).plusSeconds(i * 400L).toEpochMilli();
            values[i] = i;
        }
        var trend = trend(times);
        System.arraycopy(values, 0, trend.tickAvgMs(), 0, size);
        var model = HyboxTrendModel.from(true, 7, NOW, trend, new long[] {times[600], NOW.plusSeconds(1).toEpochMilli()}, 0);
        var flat = trend(times);
        java.util.Arrays.fill(flat.tickAvgMs(), 0);
        java.util.Arrays.fill(flat.players(), 1);
        var flatCommands = render(HyboxTrendModel.from(true, 7, NOW, flat, new long[0], 0), READY);
        assertEquals("Tick time: 0.0 ms the whole time. Players online: 1 the whole time.", text(flatCommands, "#Charts[0] #Paragraph.Text"));
        assertFalse(Arrays.stream(flatCommands.getCommands()).anyMatch(c -> c.selector != null && c.selector.endsWith("#ChartTitle.Text")));
        assertEquals(1200, model.samples());
        assertEquals(HyboxTrendModel.BUCKETS, model.metrics().getFirst().series().mean().length);
        assertEquals(HyboxChartImage.WIDTH / 2, HyboxTrendModel.BUCKETS);
        assertEquals(0, model.metrics().getFirst().min());
        assertEquals(1199, model.metrics().getFirst().max());
        assertEquals(599.5, model.metrics().getFirst().average());
        assertEquals(1199, model.metrics().getFirst().now());
        assertEquals(1, model.markers().length);
        assertFalse(model.timeline().collapsed());
        int bucket = model.timeline().bucket(times[600]);
        assertTrue(model.metrics().getFirst().series().max()[bucket] >= 600);
        assertTrue(model.metrics().getFirst().series().min()[bucket] <= 600);
    }

    @Test
    void sparseSessionsCollapseAndHoverShowsMinAverageMaxOrServerOff() {
        long first = NOW.minus(Duration.ofDays(3)).toEpochMilli();
        long second = NOW.minus(Duration.ofHours(5)).toEpochMilli();
        long[] times = new long[240];
        for (int i = 0; i < 120; i++) {
            times[i] = first + i * 10_000L;
            times[120 + i] = second + i * 10_000L;
        }
        var trend = trend(times);
        Arrays.fill(trend.tickAvgMs(), 3);
        trend.tickAvgMs()[150] = 117.2;
        var model = HyboxTrendModel.from(true, 7, NOW, trend, new long[0], 30);
        assertTrue(model.timeline().collapsed());
        assertEquals(1, model.timeline().gaps().size());
        var commands = render(model, READY);
        assertTrue(text(commands, "#Charts[0] #X0.Text").startsWith("Oct 22"), text(commands, "#Charts[0] #X0.Text"));
        assertTrue(text(commands, "#Charts[0] #X4.Text").startsWith("Oct 25"), text(commands, "#Charts[0] #X4.Text"));
        assertTrue(bool(commands, "#Charts[0] #ClipLabel.Visible"));
        assertEquals("117.2 ms off scale", text(commands, "#Charts[0] #ClipLabel.Text"));
        assertEquals("40 ms", text(commands, "#Charts[0] #YMax.Text"));
        assertTrue(text(commands, "#Charts[0] #ChartLegend.Text").contains("Server off time is cut out"));
        List<String> tips = HyboxTrendRenderer.tips(model, model.metrics().getFirst());
        assertEquals(HyboxChartRenderer.HOVER_CELLS, tips.size());
        assertTrue(tips.stream().anyMatch(tip -> tip.contains("min 3.0 · avg") && tip.endsWith("max 117.2 ms")), tips.toString());
        assertTrue(tips.stream().noneMatch(tip -> tip.contains("no data")), tips.toString());
    }

    @Test
    void sharedAssetPipelineAddsEveryRedMarkerWithoutAnIncidentOverlay() throws Exception {
        var model = model(true);
        var commands = new UICommandBuilder();
        var requests = new ArrayList<HyboxIncidentRenderer.ChartRequest>();
        HyboxTrendRenderer.render(model, READY, commands, requests::add);
        assertEquals(9, requests.size());
        byte[] png = requests.getFirst().draw().get();
        var image = ImageIO.read(new ByteArrayInputStream(png));
        assertEquals(HyboxChartImage.WIDTH, image.getWidth());
        assertEquals(HyboxChartImage.HEIGHT, image.getHeight());
        int markerColumns = 0, seriesPixels = 0;
        for (int x = 0; x < image.getWidth(); x++) {
            int pixel = image.getRGB(x, HyboxChartImage.TOP);
            int red = pixel >> 16 & 255, green = pixel >> 8 & 255, alpha = pixel >>> 24;
            if (alpha > 200 && red > 200 && green < 150) markerColumns++;
            for (int y = 0; y < image.getHeight(); y++) {
                int p = image.getRGB(x, y);
                if (p >>> 24 == 255 && (p >> 16 & 255) == 0xff && (p >> 8 & 255) == 0xd2 && (p & 255) == 0x1f) seriesPixels++;
            }
        }
        assertEquals(2, markerColumns);
        assertTrue(seriesPixels > 0);
        var swap = new UICommandBuilder();
        requests.getFirst().apply().accept("UI/Custom/Hybox/Charts/test.png", swap);
        assertTrue(bool(swap, "#Charts[0] #PlotImage.Visible"));
        assertEquals("UI/Custom/Hybox/Charts/test.png", text(swap, "#Charts[0] #PlotImage.AssetPath"));
        for (var c : commands.getCommands()) {
            if (c.type == CustomUICommandType.Set && c.data != null) assertFalse(c.data.matches(".*#[0-9a-fA-F]{6}\\(.*"));
        }
    }

    private static HyboxTrendModel model(boolean optional) {
        long[] times = {NOW.minus(Duration.ofDays(7)).toEpochMilli(), NOW.minus(Duration.ofDays(3)).toEpochMilli(), NOW.toEpochMilli()};
        double[] v = {10, 20, 30};
        double[] missing = {Double.NaN, Double.NaN, Double.NaN};
        var trend = new MetricsLog.Trend(times, v, new double[] {20, 19, 20}, new double[] {2, 5, 3},
            new double[] {1e9, 2e9, 1.5e9}, new double[] {3e9, 4e9, 3.5e9}, new double[] {0.1, 1.8, 0.4},
            optional ? v : missing, optional ? v : missing, new double[] {0, 25, 5}, missing, missing, missing, missing, missing);
        return HyboxTrendModel.from(true, 7, NOW, trend, new long[] {times[0] - 1, times[1], times[2]}, 20);
    }

    private static MetricsLog.Trend trend(long[] times) {
        int n = times.length;
        double[][] columns = new double[14][n];
        for (double[] column : columns) Arrays.fill(column, Double.NaN);
        return new MetricsLog.Trend(times, columns[0], columns[1], columns[2], columns[3], columns[4], columns[5], columns[6],
            columns[7], columns[8], columns[9], columns[10], columns[11], columns[12], columns[13]);
    }

    private static UICommandBuilder render(HyboxTrendModel model, HyboxTrendRenderer.ViewState state) {
        var commands = new UICommandBuilder();
        HyboxTrendRenderer.render(model, state, commands, null);
        return commands;
    }

    private static CustomUICommand command(UICommandBuilder commands, String selector) {
        return Arrays.stream(commands.getCommands()).filter(c -> selector.equals(c.selector) && c.type == CustomUICommandType.Set)
            .findFirst().orElseThrow(() -> new AssertionError(selector));
    }

    private static String text(UICommandBuilder commands, String selector) {
        return BsonDocument.parse(command(commands, selector).data).getString("0").getValue();
    }

    private static boolean bool(UICommandBuilder commands, String selector) {
        return BsonDocument.parse(command(commands, selector).data).getBoolean("0").getValue();
    }
}
