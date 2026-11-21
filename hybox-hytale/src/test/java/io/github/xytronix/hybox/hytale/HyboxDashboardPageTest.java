package io.github.xytronix.hybox.hytale;

import static org.junit.jupiter.api.Assertions.*;

import com.hypixel.hytale.protocol.packets.interface_.CustomUICommand;
import com.hypixel.hytale.protocol.packets.interface_.CustomUICommandType;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import io.github.xytronix.hybox.core.metrics.ChartAxis;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class HyboxDashboardPageTest {
    private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");

    @Test
    void sendingOnlyChangedValuesLeavesTheClientShowingTheFullRefresh() {
        List<DashboardModel> models = List.of(busy("Running", 19.5), quiet("Running", 30.0), quiet("Stopped", 30.0),
            busy("Running", 29.9), busy("Running", 29.9));
        for (String tab : List.of("overview", "incidents", "triggers")) {
            Map<String, String> client = new HashMap<>();
            UICommandBuilder first = new UICommandBuilder();
            HyboxDashboardRenderer.render(models.getFirst(), tab, first, new UIEventBuilder());
            apply(client, first.getCommands());
            Map<String, String> shown = new HashMap<>();
            HyboxDashboardPage.remember(shown, values(models.getFirst(), tab));
            for (int i = 1; i < models.size(); i++) {
                DashboardModel model = models.get(i);
                assertEquals(HyboxDashboardRenderer.shape(models.getFirst(), tab), HyboxDashboardRenderer.shape(model, tab));
                CustomUICommand[] full = values(model, tab);
                CustomUICommand[] sent = HyboxDashboardPage.changed(shown, full);
                assertTrue(sent.length <= full.length);
                apply(client, sent);
                boolean changed = HyboxDashboardPage.remember(shown, full);
                assertEquals(changed, sent.length > 0, tab + " step " + i);
                for (CustomUICommand command : full) assertEquals(command.data, client.get(command.selector), tab + " " + command.selector);
            }
            assertEquals(0, HyboxDashboardPage.changed(shown, values(models.getLast(), tab)).length, "Nothing is resent when nothing changed");
        }
        Map<String, String> shown = new HashMap<>();
        HyboxDashboardPage.remember(shown, values(busy("Running", 19.5), "overview"));
        CustomUICommand[] tick = HyboxDashboardPage.changed(shown, values(busy("Running", 20.5), "overview"));
        assertTrue(tick.length > 0 && tick.length < 5, Arrays.toString(Arrays.stream(tick).map(c -> c.selector).toArray()));
    }

    @Test
    void timerAndRepeatedSelectorsAreNeverFilteredOut() {
        UICommandBuilder commands = new UICommandBuilder();
        commands.set("#A.Text", "one");
        commands.clear("#Host");
        commands.appendInline("#Host", "TimerLabel { Seconds: 5; }");
        Map<String, String> shown = new HashMap<>();
        HyboxDashboardPage.remember(shown, commands.getCommands());
        CustomUICommand[] sent = HyboxDashboardPage.changed(shown, commands.getCommands());
        assertEquals(List.of(CustomUICommandType.Clear, CustomUICommandType.AppendInline), Arrays.stream(sent).map(c -> c.type).toList());

        UICommandBuilder twice = new UICommandBuilder();
        twice.set("#A.Text", "two");
        twice.set("#A.Text", "one");
        assertEquals(2, HyboxDashboardPage.changed(shown, twice.getCommands()).length);
    }

    @Test
    void chartsReuseBuffersAndSkipUnchangedRenders() throws Exception {
        List<HyboxChartImage.FlameRect> frames = new ArrayList<>();
        for (int i = 0; i < 40; i++) frames.add(new HyboxChartImage.FlameRect(i * 20, i * 5, 300, 5, i % 4));
        byte[] flame = HyboxChartImage.flame(frames, 940, 300);
        assertSame(flame, HyboxChartImage.flame(List.copyOf(frames), 940, 300), "Same data and size reuses the render");
        assertNotSame(flame, HyboxChartImage.flame(frames, 940, 299));

        var empty = ImageIO.read(new ByteArrayInputStream(HyboxChartImage.states(new int[] {0, 0, 0}, 4, 2)));
        assertEquals(4, empty.getWidth());
        assertEquals(2, empty.getHeight());
        for (int x = 0; x < 4; x++) for (int y = 0; y < 2; y++) assertEquals(0, empty.getRGB(x, y) >>> 24, "Reused canvas starts clear");

        var green = ImageIO.read(new ByteArrayInputStream(HyboxChartImage.states(new int[] {1, 1}, 2, 1)));
        assertEquals(0xff000000 | HyboxChartImage.STATES[1], green.getRGB(0, 0));
        assertEquals(0xff000000 | HyboxChartImage.STATES[1], green.getRGB(1, 0));

        double[] values = new double[200];
        for (int i = 0; i < values.length; i++) values[i] = i % 50;
        ChartAxis.Axis axis = new ChartAxis.Axis(100, Double.NaN);
        byte[] line = HyboxChartImage.line(values, axis, new double[] {20});
        assertSame(line, HyboxChartImage.line(values.clone(), axis, new double[] {20}));
        values[3] = 7;
        assertNotSame(line, HyboxChartImage.line(values, axis, new double[] {20}));
        assertFalse(Arrays.equals(line, HyboxChartImage.line(values, axis, new double[] {20})));
        byte[][] other = new byte[1][];
        Thread thread = new Thread(() -> other[0] = HyboxChartImage.line(new double[] {1, 2, 3}, new ChartAxis.Axis(4, Double.NaN), new double[0]));
        thread.start();
        thread.join();
        var image = ImageIO.read(new ByteArrayInputStream(other[0]));
        assertEquals(HyboxChartImage.WIDTH, image.getWidth());
        assertEquals(HyboxChartImage.HEIGHT, image.getHeight());
    }

    private static CustomUICommand[] values(DashboardModel model, String tab) {
        UICommandBuilder commands = new UICommandBuilder();
        HyboxDashboardRenderer.refresh(model, tab, commands, false);
        return commands.getCommands();
    }

    private static void apply(Map<String, String> client, CustomUICommand[] commands) {
        for (CustomUICommand command : commands) if (command.type == CustomUICommandType.Set) client.put(command.selector, command.data);
    }

    private static DashboardModel busy(String recorder, double tps) {
        return new DashboardModel(List.of(new DashboardModel.Tile("Recorder", recorder, "Crash backup saved 4s ago", DashboardModel.Tone.OK, "WARN")),
            List.of(new DashboardModel.Warning("Storage: low", DashboardModel.Tone.WARN)),
            new DashboardModel.Profile(NOW.minusSeconds(150), Duration.ofMinutes(5), NOW, "Owner", io.github.xytronix.hybox.core.jfr.ProfileMode.LAG), "saving the recording",
            List.of(new HyboxIncidents.Incident("a", Path.of("a.zip"), NOW, "TPS_DROP", "TPS dropped", "WARN", "default", null, false, true)),
            "", List.of(new HyboxChatFormatter.Trigger("TPS_DROP", "TPS drop", true, "below 15")), "Checked every second", "", true, "", true,
            true, true, true, false, true, DashboardModel.ProfileForm.DEFAULT, DashboardModel.Heap.EMPTY,
            new DashboardModel.Live(List.of(new DashboardModel.Stat("TPS", Double.toString(tps), "target 30", DashboardModel.Tone.WARN)), 1, NOW),
            false, DashboardModel.ProfileQueue.NONE, false);
    }

    private static DashboardModel quiet(String recorder, double tps) {
        return new DashboardModel(List.of(new DashboardModel.Tile("Recorder", recorder, "No crash backup yet", DashboardModel.Tone.ERROR, null)),
            List.of(), null, null,
            List.of(new HyboxIncidents.Incident("a", Path.of("a.zip"), NOW.minusSeconds(60), "DEADLOCK", "Deadlock", "CRITICAL", null, null, false, true)),
            "", List.of(new HyboxChatFormatter.Trigger("TPS_DROP", "TPS drop", false, "below 10")), "Checked every 5 s", "Saved", false, "", false,
            false, false, false, false, false, new DashboardModel.ProfileForm(true, 15, false, false), DashboardModel.Heap.EMPTY,
            new DashboardModel.Live(List.of(new DashboardModel.Stat("TPS", Double.toString(tps), "target 30", DashboardModel.Tone.NEUTRAL)), 0, NOW),
            false, DashboardModel.ProfileQueue.NONE, false);
    }
}
