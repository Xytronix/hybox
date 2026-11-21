package io.github.xytronix.hybox.core.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Predicate;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import jdk.jfr.StackTrace;
import org.junit.jupiter.api.Test;

import io.github.xytronix.hybox.core.metrics.ModMetrics;
import io.github.xytronix.hybox.core.metrics.ModMetrics.Series;

class ModEventReaderTest {
    private static final System.Logger LOGGER = System.getLogger(ModEventReaderTest.class.getName());

    @Name("livetest.Metric")
    @Category({"Mods", "LiveMod"})
    @StackTrace(false)
    static final class LiveMetric extends Event {
        String name;
        String kind;
        double value;
    }

    @Name("livetest.Metric")
    @Category({"Mods", "LiveMod"})
    @StackTrace(false)
    static final class LiveMetricOtherLoader extends Event {
        String name;
        String kind;
        double value;
    }

    @Name("livetest.Late")
    @Category({"Mods", "LateMod"})
    @StackTrace(false)
    static final class LateMetric extends Event {
        String name;
        String kind;
        double value;
    }

    @Name("livetest.Tool")
    @Category({"Tools", "LiveMod"})
    @StackTrace(false)
    static final class ToolMetric extends Event {
        String name;
        String kind;
        double value;
    }

    @Name("io.github.xytronix.hybox.LiveTestMetric")
    @Category({"Mods", "LiveMod"})
    @StackTrace(false)
    static final class HyboxMetric extends Event {
        String name;
        String kind;
        double value;
    }

    @Test
    void exportsCountersAsSumsAndGaugesAsLastValueUnderThePluginId() throws Exception {
        FlightRecorder.register(LiveMetric.class);
        FlightRecorder.register(LiveMetricOtherLoader.class);
        FlightRecorder.register(ToolMetric.class);
        FlightRecorder.register(HyboxMetric.class);
        ModMetrics metrics = new ModMetrics();
        try (Recording recording = new Recording()) {
            recording.setToDisk(true);
            recording.start();
            try (ModEventReader reader = ModEventReader.start(metrics,
                mod -> mod.equals("LiveMod") ? "Tester:LiveMod" : mod, LOGGER)) {
                Thread.sleep(1500);
                live("froze", "counter", 3);
                live("froze", "counter", 4);
                other("froze", "counter", 5);
                live("radius", "gauge", 5);
                live("radius", "gauge", 9);
                ToolMetric tool = new ToolMetric();
                tool.name = "tool";
                tool.kind = "gauge";
                tool.value = 1;
                tool.commit();
                HyboxMetric hybox = new HyboxMetric();
                hybox.name = "hybox";
                hybox.kind = "gauge";
                hybox.value = 1;
                hybox.commit();

                await(metrics, s -> s.stream().anyMatch(m -> m.name().equals("froze") && m.value() == 12)
                    && s.stream().anyMatch(m -> m.name().equals("radius") && m.value() == 9));
                Thread.sleep(1500);

                assertEquals(List.of(new Series("Tester:LiveMod", "froze", true, 12),
                    new Series("Tester:LiveMod", "radius", false, 9)), metrics.snapshot());
            }
            assertTrue(metrics.snapshot().isEmpty());
        }
    }

    @Test
    void subscribesToModEventTypesRegisteredAfterTheStart() throws Exception {
        ModMetrics metrics = new ModMetrics();
        try (Recording recording = new Recording()) {
            recording.setToDisk(true);
            recording.start();
            try (ModEventReader reader = ModEventReader.start(metrics, mod -> mod, LOGGER)) {
                List<Series> series = List.of();
                for (int i = 0; i < 60 && series.isEmpty(); i++) {
                    LateMetric late = new LateMetric();
                    late.name = "late";
                    late.kind = "gauge";
                    late.value = 7;
                    late.commit();
                    Thread.sleep(250);
                    series = metrics.snapshot();
                }
                assertEquals(List.of(new Series("LateMod", "late", false, 7)), series);
            }
        }
    }

    private static void live(String name, String kind, double value) {
        LiveMetric event = new LiveMetric();
        event.name = name;
        event.kind = kind;
        event.value = value;
        event.commit();
    }

    private static void other(String name, String kind, double value) {
        LiveMetricOtherLoader event = new LiveMetricOtherLoader();
        event.name = name;
        event.kind = kind;
        event.value = value;
        event.commit();
    }

    private static List<Series> await(ModMetrics metrics, Predicate<List<Series>> done) throws InterruptedException {
        long deadline = System.nanoTime() + 15_000_000_000L;
        List<Series> series = metrics.snapshot();
        while (!done.test(series) && System.nanoTime() < deadline) {
            Thread.sleep(100);
            series = metrics.snapshot();
        }
        return series;
    }
}
