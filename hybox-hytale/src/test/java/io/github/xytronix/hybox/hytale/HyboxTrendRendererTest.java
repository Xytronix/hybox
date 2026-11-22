package io.github.xytronix.hybox.hytale;

import static org.junit.jupiter.api.Assertions.*;

import com.hypixel.hytale.protocol.packets.interface_.CustomUICommand;
import com.hypixel.hytale.protocol.packets.interface_.CustomUICommandType;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
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
        assertEquals(30, HyboxTrendModel.from(true, 7, NOW, new MetricsLog.Trend(new long[] {NOW.toEpochMilli()}, new double[] {1},
            new double[] {30}, new double[] {1}, new double[] {1}, new double[] {1}, new double[] {1}, new double[] {1}, new double[] {1},
            new double[] {1}), new long[0], 0).metrics().get(1).ref().value(), 1e-9, "No running world falls back to 30 TPS");
        assertTrue(text(commands, "#Summary.Text").contains("3 samples"));
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
    void integerChartsUseWholeDistinctAxisLabels() {
        for (double[] values : List.of(new double[] {1, 1, 1}, new double[] {0, 0, 0}, new double[] {4, 4}, new double[] {0, 1, 0, 1},
            new double[] {2, 5, 3}, new double[] {0, 3, 1})) {
            var commands = new UICommandBuilder();
            double max = Arrays.stream(values).max().orElse(0);
            HyboxChartRenderer.plot("#C", values, new double[0], 100, "count", 0, max, null, false, null, commands);
            long top = Long.parseLong(text(commands, "#C #YMax.Text"));
            long mid = Long.parseLong(text(commands, "#C #YMid.Text"));
            long low = Long.parseLong(text(commands, "#C #YMin.Text"));
            String shown = Arrays.toString(values);
            assertEquals(0, low, shown);
            assertEquals(top, 2 * mid, shown);
            assertTrue(mid > low && mid < top, shown);
            assertTrue(top >= max, shown);
            if (Arrays.stream(values).allMatch(value -> value == values[0])) assertTrue(top >= values[0] + 1, shown);
        }
        var players = new UICommandBuilder();
        HyboxChartRenderer.plot("#P", new double[] {1, 1, 1}, new double[0], 100, "players", 0, 1, null, false, null, players);
        assertEquals("2 players", text(players, "#P #YMax.Text"));
        assertEquals(2.0, HyboxChartRenderer.axisTop(new double[] {1, 1}, 1, 0));
        assertEquals(6.0, HyboxChartRenderer.axisTop(new double[] {2, 5}, 5, 0));
        assertEquals(2.5, HyboxChartRenderer.axisTop(new double[] {1, 2.5}, 2.5, 1));
    }

    @Test
    void referenceLineSitsAtTheBudgetOnBarsAndOnTheImage() throws Exception {
        double budget = 1000 / 30.0;
        double[] values = new double[40];
        Arrays.fill(values, budget);
        var commands = new UICommandBuilder();
        var requests = new ArrayList<HyboxIncidentRenderer.ChartRequest>();
        HyboxChartRenderer.plot("#C", values, new double[0], 100, "ms", 1, budget, new HyboxChartRenderer.Ref(budget, "33.3 ms budget"), false, requests::add, commands);
        assertTrue(bool(commands, "#C #RefLine.Visible"));
        assertEquals("33.3 ms budget", text(commands, "#C #RefLabel.Text"));
        String bar = Arrays.stream(commands.getCommands()).filter(c -> "#C #Bars".equals(c.selector) && c.text != null).findFirst().orElseThrow().text;
        int height = Integer.parseInt(bar.replaceAll(".*Height: (\\d+).*", "$1"));
        assertEquals(anchor(HyboxChartImage.HEIGHT - height), command(commands, "#C #RefLine.Anchor").data);
        var image = ImageIO.read(new ByteArrayInputStream(requests.getFirst().draw().get()));
        int row = 0;
        while (row < image.getHeight() && image.getRGB(image.getWidth() / 2, row) >>> 24 != 255) row++;
        var swap = new UICommandBuilder();
        requests.getFirst().apply().accept("UI/Custom/Hybox/Charts/test.png", swap);
        assertEquals(anchor(row), command(swap, "#C #RefLine.Anchor").data);
    }

    private static String anchor(int top) {
        var expected = new UICommandBuilder();
        expected.setObject("#C #RefLine.Anchor", HyboxIncidentRenderer.anchor(0, top, HyboxChartImage.WIDTH, 1));
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
    void bucketsUseRealTimeAndKeepRawSampleStatistics() {
        int size = 1200;
        long[] times = new long[size];
        double[] values = new double[size];
        for (int i = 0; i < size; i++) {
            times[i] = NOW.minus(Duration.ofDays(7)).plusSeconds(i * 400L).toEpochMilli();
            values[i] = i;
        }
        var trend = new MetricsLog.Trend(times, values, values, values, values, values, values, values, values, values);
        var model = HyboxTrendModel.from(true, 7, NOW, trend, new long[] {NOW.minusSeconds(3600).toEpochMilli(), NOW.plusSeconds(1).toEpochMilli()}, 0);
        assertEquals(1200, model.samples());
        assertEquals(600, model.metrics().getFirst().values().length);
        assertEquals(0, model.metrics().getFirst().min());
        assertEquals(1199, model.metrics().getFirst().max());
        assertEquals(599.5, model.metrics().getFirst().average());
        assertEquals(1199, model.metrics().getFirst().now());
        assertEquals(1, model.markers().length);
        assertTrue(Arrays.stream(model.metrics().getFirst().values()).anyMatch(Double::isNaN));
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
        assertNotNull(command(swap, "#Charts[0] #YMax.Text"));
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
            optional ? v : missing, optional ? v : missing, new double[] {0, 25, 5});
        return HyboxTrendModel.from(true, 7, NOW, trend, new long[] {times[0] - 1, times[1], times[2]}, 20);
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
