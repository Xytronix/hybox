package io.github.xytronix.hybox.core.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.xytronix.hybox.core.health.HealthSnapshot;
import io.github.xytronix.hybox.core.health.JfrTimeline;
import io.github.xytronix.hybox.core.incident.DiagnosticSection;
import io.github.xytronix.hybox.core.incident.IncidentId;
import io.github.xytronix.hybox.core.incident.IncidentMetadata;
import io.github.xytronix.hybox.core.incident.IncidentReport;
import io.github.xytronix.hybox.core.incident.Severity;

class ReportFindingsTest {
    private static final Instant CAPTURED = Instant.parse("2026-01-01T00:00:00Z");
    private static final long GIB = 1024L * 1024 * 1024;

    @Test
    void reportStoresStallGcBlockedAndBsonFindingsWithSectionLinks() throws Exception {
        JfrTimeline timeline = timeline(Map.of(
            "totalSamples", 10_000L,
            "worldSamples", ordered("default", 2_500L, "instance-other_world", 6_000L),
            "hotMethods", List.of(new JfrTimeline.HotMethod("org.bson.BsonWriter.write", 1_234L, List.of(), List.of())),
            "gc", new JfrTimeline.GcStats(23, 12.5, 47.25),
            "gcConfig", new JfrTimeline.GcConfig("Shenandoah Young", "Shenandoah Old", 2, 1, 99, true, false),
            "alloc", new JfrTimeline.Alloc(4 * GIB, ordered("BSON serialization", 3 * GIB, "Other", GIB),
                Map.of(), Map.of(), Map.of(), List.of()),
            "subsystems", ordered("Other", 9_000L, "BSON serialization", 1_234L)));
        IncidentReport report = report(1, List.of(), List.of(new DiagnosticSection("Stalled thread",
            Map.of("Blocked in", "org.bson.BsonWriter.write"), "at org.bson.BsonWriter.write(BsonWriter.java:1)")));
        ReportFindings findings = findings(report, timeline);

        assertEquals(List.of("cpu", "methods", "worlds", "states", "memory"),
            findings.evidence().stream().map(ReportFindings.Entry::section).toList());
        assertEquals("World other world used 60% of the CPU samples, more than any other world and above the 50% "
            + "threshold.", text(findings.evidence().get(0).spans()));
        assertEquals("The most sampled method was BsonWriter.write, in 12.3% of CPU samples, above the 10% threshold.",
            text(findings.evidence().get(1).spans()));
        assertEquals("The stalled world default used 25% of the CPU samples, #2 of 2 worlds by CPU.",
            text(findings.evidence().get(2).spans()));
        assertEquals("1 thread was waiting for a lock at capture.", text(findings.evidence().get(3).spans()));
        assertEquals("BSON serialization allocated the most memory: 3.0 GiB of 4.0 GiB.",
            text(findings.evidence().get(4).spans()));
        assertEquals("Memory & GC", findings.evidence().get(4).label());
        assertEquals(3, findings.steps().size());
        assertEquals("1 thread was waiting for a lock. Thread states shows what it waited for.",
            text(findings.steps().get(0).spans()));
        assertEquals("states", findings.steps().get(0).section());
        assertEquals("Thread states", findings.steps().get(0).label());
        assertEquals("See what WorldThread - instance-other_world ran: open it in the Flame graph.",
            text(findings.steps().get(1).spans()));
        assertEquals("flame", findings.steps().get(1).section());
        assertEquals("Flame graph", findings.steps().get(1).label());
        assertEquals("Most CPU went to BSON serialization (12.3%). Hot methods lists the methods.",
            text(findings.steps().get(2).spans()));
        assertEquals("methods", findings.steps().get(2).section());

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ReportHtml.write(report, timeline, output);
        String html = output.toString(StandardCharsets.UTF_8);
        String payload = html.substring(html.indexOf("const D = "), html.indexOf(";\n", html.indexOf("const D = ")));
        assertFalse(payload.contains("\"intro\""));
        assertTrue(payload.contains("[\"BSON serialization\",\"b\"],[\" allocated the most memory: \",\"\"]"));
        assertTrue(payload.contains("\"section\":\"states\",\"label\":\"Thread states\""));
        assertTrue(payload.contains("\"diag\":[{\"title\":\"Stalled thread\",\"entries\":[[\"Blocked in\","));
        assertTrue(payload.contains("\"pre\":\"at org.bson.BsonWriter.write(BsonWriter.java:1)\""));
        assertFalse(payload.contains("<b>"));
        assertFalse(payload.contains("\"direction\":") || payload.contains("\"caveat\":") || payload.contains("\"tags\":"));
        assertFalse(html.contains("legacyDiagnosisHtml"));
        assertFalse(html.contains("Investigation direction"));
        assertFalse(html.contains("Every line below is computed from this bundle's recording."));
        assertFalse(html.contains("(recording.jfr in this bundle)"));
    }

    @Test
    void deadlockOutranksBlockingAndKeepsOnlyFirstThreeSteps() throws Exception {
        JfrTimeline timeline = timeline(Map.of(
            "totalSamples", 100L, "worldSamples", Map.of("default", 100L),
            "subsystems", Map.of("BSON serialization", 50L), "exceptionsSeries", spikeAt(9)));
        IncidentReport report = report(4, List.of("thread A", "thread B"),
            List.of(new DiagnosticSection("Mixins", Map.of("Conflict: example.Target", "a; b"))));
        ReportFindings findings = findings(report, timeline);

        assertEquals("2 threads are deadlocked and will not recover. Restart the server. Thread states names both "
            + "threads and what each one holds.", text(findings.steps().get(0).spans()));
        assertEquals(3, findings.steps().size());
        assertEquals("methods", findings.steps().get(2).section());
        assertTrue(findings.evidence().stream().anyMatch(entry ->
            text(entry.spans()).equals("Java detected a deadlock between 2 threads.")));
        assertFalse(findings.evidence().stream().anyMatch(entry -> "worlds".equals(entry.section())));
    }

    @Test
    void spikeMixinsMemoryAndPluginCompatibilityMatchRecordedValues() throws Exception {
        JfrTimeline timeline = timeline(Map.of(
            "container", new JfrTimeline.Container("cgroupv2", 4, 4 * GIB),
            "heapMax", 4 * GIB, "heapSeries", new long[] {2 * GIB, 3 * GIB},
            "rssSeries", new long[] {2_000_000_000L, 3_000_000_000L},
            "exceptionsSeries", spikeAt(9), "cpu", new JfrTimeline.CpuStats(5, 9, 33.25, 100)));
        IncidentReport report = report(0, List.of(), List.of(
            new DiagnosticSection("Mixins", Map.of("Conflict: example.Target", "a; b")),
            new DiagnosticSection("Plugins", Map.of("vendor:mod", "1.0 @ <0.7 @ INCOMPATIBLE"))));
        ReportFindings findings = findings(report, timeline);

        assertEquals(List.of("metrics", "system", "plugins"),
            findings.evidence().stream().map(ReportFindings.Entry::section).toList());
        assertEquals("100 exceptions in the 6.5 s before the incident, 10 times the average, above the 5 times "
            + "threshold.", text(findings.evidence().get(0).spans()));
        assertEquals("The heap limit (-Xmx 4.0 GiB) is 100% of the container's 4.0 GiB, above the 90% threshold. "
            + "The container can kill the server. Set -Xmx to about 75% of the container limit.",
            text(findings.evidence().get(1).spans()));
        assertEquals("1 mod was built for another Hytale version.", text(findings.evidence().get(2).spans()));
        assertEquals(List.of("mixins", "log"), findings.steps().stream().map(ReportFindings.Entry::section).toList());
        assertEquals("1 class is changed by more than one mod. If the problem started after you added a mod, test "
            + "without it. Mixins lists it.", text(findings.steps().get(0).spans()));
        assertEquals(List.of(new ReportFindings.Span("Exceptions spiked before the incident. ", ""),
            new ReportFindings.Span("Server log", ""), new ReportFindings.Span(" shows the errors from that time.", "")),
            findings.steps().get(1).spans());
    }

    @Test
    void resultsShowOnlyWhenTheyCrossTheirThreshold() throws Exception {
        JfrTimeline timeline = timeline(Map.of(
            "gc", new JfrTimeline.GcStats(5, 210, 4_550),
            "cpu", new JfrTimeline.CpuStats(40, 60, 93, 100),
            "heapMax", 4 * GIB, "heapSeries", new long[] {GIB, (long) (3.8 * GIB)}));
        ReportFindings findings = findings(report(0, List.of(), List.of()), timeline);

        assertEquals(List.of(
            "GC paused the server for up to 210 ms, above the 50 ms threshold. 7% of the time went to GC pauses, "
                + "above the 5% threshold.",
            "The machine's CPU averaged 93%, above the 90% threshold. The server itself used 40%.",
            "Heap peaked at 3.8 of 4.0 GiB (95%), above the 90% threshold."),
            findings.evidence().stream().map(entry -> text(entry.spans())).toList());

        JfrTimeline calm = timeline(Map.of(
            "gc", new JfrTimeline.GcStats(110, 15.75, 79.89),
            "cpu", new JfrTimeline.CpuStats(9.8, 54.5, 45, 98.1),
            "heapMax", 4 * GIB, "heapSeries", new long[] {GIB, 3 * GIB},
            "totalSamples", 12_018L,
            "hotMethods", List.of(new JfrTimeline.HotMethod("a.TickingThread.run$mixinextras$wrapped$39", 498L,
                List.of(), List.of()))));
        assertTrue(findings(report(0, List.of(), List.of()), calm).evidence().isEmpty());
    }

    @Test
    void absentDataAndEarlyExceptionPeaksDoNotCreateEvidence() throws Exception {
        IncidentReport report = new IncidentReport(new IncidentMetadata(new IncidentId("fixture"), CAPTURED,
            Severity.INFO, "MANUAL", null, "Profile"));
        ReportFindings findings = findings(report, timeline(Map.of("exceptionsSeries", spikeAt(8))));
        assertTrue(findings.evidence().isEmpty());
        assertTrue(findings.steps().isEmpty());
    }

    @Test
    void decimalFormattingPreservesJavascriptFixedRounding() {
        assertEquals("2.5", ReportFindings.fixed(2.55, 1));
        assertEquals("2.4", ReportFindings.fixed(2.35, 1));
        assertEquals("1.00", ReportFindings.fixed(1.005, 2));
        assertEquals("1.13", ReportFindings.fixed(1.125, 2));
        assertEquals("-0.0", ReportFindings.fixed(-0.01, 1));
        assertEquals("0.0", ReportFindings.fixed(-0.0, 1));
    }

    private static ReportFindings findings(IncidentReport report, JfrTimeline timeline) {
        return ReportFindings.build(report, timeline, ReportSections.route(report.diagnostics()));
    }

    private static IncidentReport report(int blocked, List<String> deadlocked, List<DiagnosticSection> diagnostics) {
        HealthSnapshot snapshot = new HealthSnapshot(30, null, null, List.of(), null, null,
            new HealthSnapshot.Threads(10, Map.of("BLOCKED", blocked), deadlocked),
            List.of(new HealthSnapshot.World("default", 1, 10, 20, 30, 33)), List.of());
        IncidentMetadata meta = new IncidentMetadata(new IncidentId("fixture"), CAPTURED, Severity.DEGRADED,
            "HEARTBEAT_STALL", "default", "Heartbeat stalled");
        return new IncidentReport(meta, Map.of("stallMs", "2146"), snapshot, diagnostics);
    }

    private static Map<String, Long> ordered(String first, long firstValue, String second, long secondValue) {
        Map<String, Long> values = new LinkedHashMap<>();
        values.put(first, firstValue);
        values.put(second, secondValue);
        return values;
    }

    private static double[] spikeAt(int index) {
        double[] values = new double[10];
        values[index] = 100;
        return values;
    }

    private static String text(List<ReportFindings.Span> spans) {
        return spans.stream().map(ReportFindings.Span::text).collect(Collectors.joining());
    }

    // Keep synthetic report inputs deterministic without requiring live sampling or a large binary JFR fixture.
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
        fields.put("start", CAPTURED.minusSeconds(60));
        fields.put("end", CAPTURED.plusSeconds(5));
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
