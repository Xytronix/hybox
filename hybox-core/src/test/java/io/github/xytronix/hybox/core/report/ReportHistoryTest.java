package io.github.xytronix.hybox.core.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.xytronix.hybox.core.bundle.BundleBuilder;
import io.github.xytronix.hybox.core.health.JfrTimeline;
import io.github.xytronix.hybox.core.incident.IncidentId;
import io.github.xytronix.hybox.core.incident.IncidentMetadata;
import io.github.xytronix.hybox.core.incident.IncidentReport;
import io.github.xytronix.hybox.core.incident.Severity;
import io.github.xytronix.hybox.core.metrics.MetricsLog;

class ReportHistoryTest {
    private static final Instant AT = Instant.parse("2026-03-10T14:00:00Z");
    private static final long GIB = 1024L * 1024 * 1024;

    @Test
    void usualValuesComeFromTheSameTimeOfDayAndDiagnosisNamesAFarDeviation(@TempDir Path dir) throws Exception {
        Path metrics = dir.resolve("metrics");
        writeHistory(metrics, 6);
        IncidentReport report = stall("default");
        ReportHistory history = ReportHistory.load(metrics, true, null, null, report.meta());
        double[] mspt = new double[20];
        double[] tps = new double[20];
        Arrays.fill(mspt, 40);
        Arrays.fill(tps, 30);
        JfrTimeline timeline = timeline(Map.of("ticks", Map.of("default", new JfrTimeline.WorldTicks(tps, mspt, null))));

        String data = data(report, timeline, history);
        assertTrue(data.contains("\"usual\":{\"off\":false,\"days\":6,\"minDays\":3,\"hours\":18,\"enough\":true,"
            + "\"clock\":\"14:00\",\"window\":\"incident\""), data);
        assertTrue(data.contains("{\"key\":\"mspt\",\"label\":\"Tick time\",\"unit\":\"ms\",\"incident\":40.0,"
            + "\"median\":10.0,\"low\":10.0,\"high\":12.0,\"samples\":306,\"flag\":\"high\"}"), data);
        assertTrue(data.contains("{\"key\":\"tps\",\"label\":\"TPS\",\"unit\":\"\",\"incident\":30.0,"
            + "\"median\":30.0,\"low\":30.0,\"high\":30.0,\"samples\":306,\"flag\":null}"), data);
        assertTrue(data.contains("\"similar\":null"), data);

        ReportFindings findings = ReportFindings.build(report, timeline, ReportSections.route(report.diagnostics()), history);
        List<ReportFindings.Entry> lines = new java.util.ArrayList<>(findings.why());
        lines.addAll(findings.other());
        assertEquals(List.of("Tick time averaged 40.0 ms, 3.3 times its usual high for this time of day."),
            lines.stream().map(entry -> text(entry.spans())).toList());
        assertEquals("95% of samples at this time of day stayed under 12.0 ms over the last 7 days. Shown from 2 times that.",
            lines.getFirst().hover());

        Arrays.fill(mspt, 20);
        ReportFindings calm = ReportFindings.build(report, timeline, ReportSections.route(report.diagnostics()), history);
        assertTrue(calm.why().isEmpty() && calm.other().isEmpty(), "Unusual but under twice the usual high stays out of Diagnosis");
    }

    @Test
    void tooLittleHistorySaysHowManyHoursItHas(@TempDir Path dir) throws Exception {
        Path metrics = dir.resolve("metrics");
        writeHistory(metrics, 2);
        IncidentReport report = stall("default");
        String data = data(report, timeline(Map.of()), ReportHistory.load(metrics, false, null, null, report.meta()));
        assertTrue(data.contains("\"usual\":{\"off\":true,\"days\":2,\"minDays\":3,\"hours\":6,\"enough\":false,"), data);
        assertTrue(data.contains("\"metrics\":[]"), data);
    }

    @Test
    void similarIncidentsCountEarlierBundlesAndCompareTheirTopModAndMethod(@TempDir Path dir) throws Exception {
        Path incidents = dir.resolve("incidents");
        Files.createDirectories(incidents);
        String lagger = "[[\"Acme:Lagger\",300,\"acme.Lag.run\",[],250,300,\"acme.Lag.run\",[],null,\"acme.Lag.run\",null]]";
        older(incidents, "a", AT.minus(Duration.ofDays(2)), "HEARTBEAT_STALL", "default", "engine.World.tick", lagger);
        older(incidents, "b", AT.minus(Duration.ofDays(10)), "TICK_DEGRADED", "default", "engine.Chunk.save", "null");
        older(incidents, "c", AT.minus(Duration.ofDays(1)), "MANUAL", "default", "engine.World.tick", lagger);
        older(incidents, "d", AT.minus(Duration.ofDays(40)), "HEARTBEAT_STALL", "default", "engine.World.tick", lagger);
        older(incidents, "e", AT.minus(Duration.ofDays(1)), "GC_PRESSURE", "server", "engine.World.tick", lagger);

        IncidentReport report = stall("default");
        Path recording = dir.resolve("recording.jfr");
        Files.write(recording, new byte[] {1, 2, 3});
        Clock clock = Clock.fixed(AT, ZoneOffset.UTC);
        Path bundle = incidents.resolve("incident-current.zip");
        new BundleBuilder(clock).withHistory(dir.resolve("metrics"), true).build(report, recording, bundle, List.of());
        String html;
        try (ZipFile zip = new ZipFile(bundle.toFile())) {
            html = new String(zip.getInputStream(zip.getEntry("report.html")).readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(html.contains("\"similar\":{\"checked\":5,\"week\":1,\"month\":2,\"newest\":\"2026-03-08 14:00 UTC\""), html);
        assertTrue(html.contains("[\"1\",\"b\"],[\" earlier incident had the same trigger or world in the last 7 days, \",\"\"],"
            + "[\"2\",\"b\"],[\" in the last 30 days. The newest was on \",\"\"],[\"2026-03-08 14:00 UTC\",\"b\"]"), html);
        assertFalse(html.contains("SecretPlayer"), "Player names from older bundles never reach the new report");
        assertTrue(html.contains("\"usual\":{\"off\":false,\"days\":0,"), html);

        ReportHistory history = ReportHistory.load(null, false, incidents, "incident-current.zip", report.meta());
        JfrTimeline timeline = timeline(Map.of("totalSamples", 1000L,
            "hotMethods", List.of(new JfrTimeline.HotMethod("engine.World.tick", 500, List.of(), List.of())),
            "cpuByMod", List.of(new JfrTimeline.ModCpu("Acme:Lagger", 300, "acme.Lag.run", List.of(), 250, 300,
                "acme.Lag.run", List.of(), "acme.Lag.run"))));
        String data = data(report, timeline, history);
        assertTrue(data.contains("\"compared\":2,\"sameMod\":1,\"sameMethod\":1"), data);
        assertTrue(data.contains("[\"Lagger\",\"b\"],[\" was also the top mod in 1 of them.\",\"\"],[\" \",\"\"],"
            + "[\"World.tick\",\"code\"],[\" was also the top method in 1 of them.\",\"\"]"), data);
        assertTrue(data.contains("\"usual\":null"), data);

        IncidentReport manual = new IncidentReport(new IncidentMetadata(new IncidentId("dump"), AT, Severity.INFO,
            "MANUAL", "default", "Manual capture"));
        assertTrue(data(manual, timeline, ReportHistory.load(null, false, incidents, "x.zip", manual.meta()))
            .contains("\"history\":null"));
    }

    private static void writeHistory(Path dir, int days) throws Exception {
        MetricsLog log = new MetricsLog(dir);
        for (int day = 1; day <= days; day++) {
            Instant noon = AT.minus(Duration.ofDays(day));
            for (int minute = -25; minute <= 25; minute++) {
                Instant t = noon.plus(Duration.ofMinutes(minute));
                log.append(t, MetricsLog.formatRow(t, minute >= 20 ? 12 : 10, 30, 2, GIB, 2 * GIB, 0.2, 5, -1, 0, MetricsLog.Load.NONE), 30);
            }
            for (int minute = 0; minute < 30; minute++) {
                Instant t = noon.minus(Duration.ofHours(11)).plus(Duration.ofMinutes(minute));
                log.append(t, MetricsLog.formatRow(t, 90, 10, 9, GIB, 2 * GIB, 3, 50, -1, 0, MetricsLog.Load.NONE), 30);
            }
        }
    }

    private static void older(Path dir, String id, Instant at, String trigger, String world, String method, String mods)
        throws Exception {
        String meta = "{\"meta\":{\"id\":\"" + id + "\",\"createdAt\":\"" + at + "\",\"severity\":\"DEGRADED\","
            + "\"trigger\":\"" + trigger + "\",\"world\":\"" + world + "\",\"headline\":\"SecretPlayer lagged\"}}";
        String data = "{\"incident\":{\"id\":\"" + id + "\",\"samples\":1000},"
            + "\"worlds\":[{\"name\":\"" + world + "\",\"playerNames\":[\"SecretPlayer\"]}],"
            + "\"hotMethods\":[[500,\"" + method + "\",[\"caller.<\\/a>\"]]],\"modCpu\":" + mods + "}";
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(dir.resolve("incident-" + id + ".zip")))) {
            zip.putNextEntry(new ZipEntry("incident.json"));
            zip.write(meta.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("report.html"));
            zip.write(("<html><script>const DV = 1;\nconst D = " + data + ";\nrender();</script>")
                .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    private static IncidentReport stall(String world) {
        return new IncidentReport(new IncidentMetadata(new IncidentId("current"), AT, Severity.DEGRADED,
            "HEARTBEAT_STALL", world, "World stopped responding"));
    }

    private static String data(IncidentReport report, JfrTimeline timeline, ReportHistory history) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ReportHtml.write(report, timeline, null, UnaryOperator.identity(), -1, history, out);
        String html = out.toString(StandardCharsets.UTF_8);
        return html.substring(html.indexOf("const D = "));
    }

    private static String text(List<ReportFindings.Span> spans) {
        return spans.stream().map(ReportFindings.Span::text).collect(Collectors.joining());
    }

    private static JfrTimeline timeline(Map<String, Object> values) throws Exception {
        Constructor<?> constructor = JfrTimeline.class.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Object[] defaults = Arrays.stream(constructor.getParameterTypes()).map(type -> {
            if (type == long.class) return -1L;
            if (type == int.class) return 0;
            if (type == List.class) return List.of();
            if (type == Map.class) return Map.of();
            return null;
        }).toArray();
        JfrTimeline timeline = (JfrTimeline) constructor.newInstance(defaults);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("start", AT.minusSeconds(60));
        fields.put("end", AT.plusSeconds(5));
        fields.put("totalSamples", 0L);
        fields.put("explicitGcTimes", new long[0]);
        fields.putAll(values);
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            Field field = JfrTimeline.class.getDeclaredField(entry.getKey());
            field.setAccessible(true);
            field.set(timeline, entry.getValue());
        }
        return timeline;
    }
}
