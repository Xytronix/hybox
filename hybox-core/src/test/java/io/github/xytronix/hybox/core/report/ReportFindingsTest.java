package io.github.xytronix.hybox.core.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    private static final String CREATED = "2026-01-01 00:00:00 UTC";
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

        assertEquals("HEARTBEAT_STALL fired after default missed its heartbeat by 2,146 ms.", text(findings.intro()));
        assertEquals(List.of("cpu", "methods", "worlds", "memory", "states", "memory"),
            findings.evidence().stream().map(ReportFindings.Entry::section).toList());
        assertEquals("WorldThread - instance-other_world produced 60.0% of 10,000 CPU samples, "
            + "the largest world-thread share.", text(findings.evidence().get(0).spans()));
        assertEquals("Top frame BsonWriter.write holds 12.34% of samples (1,234 hits).",
            text(findings.evidence().get(1).spans()));
        assertEquals("The stalled world default drew 25.0% of samples, #2 of 2 by CPU.",
            text(findings.evidence().get(2).spans()));
        assertEquals("Shenandoah ran 23 collections, longest pause 12.5 ms (47.25 ms total).",
            text(findings.evidence().get(3).spans()));
        assertEquals("1 thread was waiting for a lock at capture.", text(findings.evidence().get(4).spans()));
        assertEquals("BSON serialization leads allocation pressure at 3.0 GiB sampled.",
            text(findings.evidence().get(5).spans()));
        assertEquals("allocation", findings.evidence().get(5).label());
        assertEquals(3, findings.steps().size());
        assertEquals("Investigate lock contention in the 1 blocked thread(s) (thread states)",
            text(findings.steps().get(0).spans()));
        assertEquals("states", findings.steps().get(0).section());
        assertEquals("thread states", findings.steps().get(0).label());
        assertEquals("Profile WorldThread - instance-other_world in JDK Mission Control "
            + "(recording.jfr)", text(findings.steps().get(1).spans()));
        assertNull(findings.steps().get(1).section());
        assertNull(findings.steps().get(1).label());
        assertEquals("Review the BSON serialization hot path: 1,234 samples, 12.3% of samples (hot methods)",
            text(findings.steps().get(2).spans()));
        assertEquals("methods", findings.steps().get(2).section());
        assertFalse(findings.evidence().stream().anyMatch(entry -> text(entry.spans()).contains("GC pause:")));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ReportHtml.write(report, timeline, output);
        String html = output.toString(StandardCharsets.UTF_8);
        String payload = html.substring(html.indexOf("const D = "), html.indexOf(";\n", html.indexOf("const D = ")));
        assertTrue(payload.contains("\"findings\":{\"intro\":[[\"HEARTBEAT_STALL\",\"code\"]"));
        assertTrue(payload.contains("[\"BSON serialization\",\"b\"],[\" leads allocation pressure at \",\"\"]"));
        assertTrue(payload.contains("\"section\":\"memory\",\"label\":\"allocation\""));
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

        assertEquals("Inspect the 2 detected deadlocked threads and their lock owners (thread states)",
            text(findings.steps().get(0).spans()));
        assertEquals(3, findings.steps().size());
        assertEquals("methods", findings.steps().get(2).section());
        assertTrue(findings.evidence().stream().anyMatch(entry -> "deadlocked threads".equals(entry.label())));
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

        assertEquals(List.of("cpu", "memory", "metrics", "system", "plugins"),
            findings.evidence().stream().map(ReportFindings.Entry::section).toList());
        assertEquals("Machine CPU peaked at 100.0% (average 33.3%).", text(findings.evidence().get(0).spans()));
        assertEquals("Process memory (RSS) peaked at 2.8 GiB of 4.0 GiB container limit (70%). Java heap peaked at "
                + "3.0 of 4.0 GiB. RSS can be lower than the heap when the system compresses or swaps memory.",
            text(findings.evidence().get(1).spans()));
        assertEquals("100 exceptions/interval right before the incident, 10× the window average of 10.0.",
            text(findings.evidence().get(2).spans()));
        assertEquals("Heap -Xmx 4.0 GiB is at or above the container memory limit of 4.0 GiB; "
            + "the JVM can be OOM-killed before GC reacts.", text(findings.evidence().get(3).spans()));
        assertEquals("1 installed mod declares a server version range that excludes the running server.",
            text(findings.evidence().get(4).spans()));
        assertEquals(List.of("mixins", "log"), findings.steps().stream().map(ReportFindings.Entry::section).toList());
        assertEquals("Inspect the 1 class patched by multiple mixin configs (mixins)", text(findings.steps().get(0).spans()));
        assertEquals("Trace the exception burst in the server log tail", text(findings.steps().get(1).spans()));
        assertEquals(List.of(new ReportFindings.Span("Trace the exception burst in the ", ""),
            new ReportFindings.Span("server log", ""), new ReportFindings.Span(" tail", "")), findings.steps().get(1).spans());
    }

    @Test
    void absentDataAndEarlyExceptionPeaksDoNotCreateEvidence() throws Exception {
        IncidentReport report = new IncidentReport(new IncidentMetadata(new IncidentId("fixture"), CAPTURED,
            Severity.INFO, "MANUAL", null, "Profile"));
        ReportFindings findings = findings(report, timeline(Map.of("exceptionsSeries", spikeAt(8))));
        assertTrue(findings.evidence().isEmpty());
        assertTrue(findings.steps().isEmpty());
        assertEquals("MANUAL fired at 2026-01-01 00:00:00 UTC.",
            text(findings.intro()));
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
        return ReportFindings.build(report, timeline, ReportSections.route(report.diagnostics()),
            report.context().containsKey("stallMs") ? Long.parseLong(report.context().get("stallMs")) : null, CREATED);
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
