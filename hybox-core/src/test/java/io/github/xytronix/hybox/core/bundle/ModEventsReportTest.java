package io.github.xytronix.hybox.core.bundle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;
import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import jdk.jfr.StackTrace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.xytronix.hybox.core.env.TextRedactor;
import io.github.xytronix.hybox.core.incident.DiagnosticSection;
import io.github.xytronix.hybox.core.incident.IncidentId;
import io.github.xytronix.hybox.core.incident.IncidentMetadata;
import io.github.xytronix.hybox.core.incident.IncidentReport;
import io.github.xytronix.hybox.core.incident.Severity;

class ModEventsReportTest {

    @Name("reportmod.Metric")
    @Category({"Mods", "ReportMod"})
    @StackTrace(false)
    static final class Metric extends Event {
        String name;
        String kind;
        double value;
    }

    @Name("reportmod.Action")
    @Category({"Mods", "ReportMod"})
    @StackTrace(false)
    static final class Action extends Event {
        String category;
        String message;
    }

    @Name("reportmod.Setting")
    @Category({"Mods", "ReportMod"})
    @StackTrace(false)
    static final class Setting extends Event {
        String name;
        String value;
    }

    @Test
    void modEventsBecomeChartsMarkersAndASettingsCardWithMasks(@TempDir Path tempDir) throws Exception {
        Path recording = tempDir.resolve("recording.jfr");
        try (Recording jfr = new Recording()) {
            jfr.start();
            metric("removed", 3);
            Action action = new Action();
            action.category = "Watchdog";
            action.message = "Steve stalled world 'default'";
            action.commit();
            Setting setting = new Setting();
            setting.name = "Bind";
            setting.value = "203.0.113.7";
            setting.commit();
            Thread.sleep(120);
            metric("removed", 2);
            jfr.dump(recording);
        }

        Clock clock = Clock.fixed(Instant.now(), ZoneOffset.UTC);
        IncidentMetadata meta = new IncidentMetadata(new IncidentId("20260111-020000.000Z-abcdef"), clock.instant(),
            Severity.INFO, "Manual dump", "default", "Manual dump");
        IncidentReport report = new IncidentReport(meta, Map.of(), null, List.of(
            new DiagnosticSection("Plugins", Map.of("Vendor:ReportMod", "1.0.0"))));
        Path outputZip = tempDir.resolve("incident.zip");
        new BundleBuilder(clock, System.getLogger("bundle-test"), new TextRedactor(TextRedactor.DEFAULT_PATTERNS),
            s -> s.replace("Steve", "player-1")).build(report, recording, outputZip, List.of());

        try (ZipFile zip = new ZipFile(outputZip.toFile())) {
            String html = new String(zip.getInputStream(zip.getEntry("report.html")).readAllBytes(),
                StandardCharsets.UTF_8);
            assertTrue(html.contains("\"name\":\"Vendor:ReportMod/removed\""), html);
            assertTrue(html.contains("\"mod:Vendor:ReportMod\",\"[Watchdog] player-1 stalled world 'default'\""), html);
            assertFalse(html.contains("<b>Vendor:ReportMod settings</b>"), html);
            assertTrue(html.contains("{\"title\":\"Vendor:ReportMod settings\",\"entries\":["), html);
            assertTrue(html.contains(",\"settings\":true}"), html);
            assertTrue(html.contains("[REDACTED]"), html);
            assertFalse(html.contains("Steve"), html);
            assertFalse(html.contains("203.0.113.7"), html);
        }
    }

    private static void metric(String name, double value) {
        Metric metric = new Metric();
        metric.name = name;
        metric.kind = "counter";
        metric.value = value;
        metric.commit();
    }
}
