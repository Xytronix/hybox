package io.github.xytronix.hybox.core.bundle;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.xytronix.hybox.core.incident.IncidentId;
import io.github.xytronix.hybox.core.incident.IncidentIds;
import io.github.xytronix.hybox.core.incident.IncidentMetadata;
import io.github.xytronix.hybox.core.incident.IncidentReport;
import io.github.xytronix.hybox.core.incident.Severity;

class BundleBuilderTest {

    @Test
    void build_createsExpectedEntries(@TempDir Path tempDir) throws Exception {
        Path recording = tempDir.resolve("recording.jfr");
        byte[] recordingBytes = new byte[] {1, 2, 3, 4, 5, 6};
        Files.write(recording, recordingBytes);

        Clock clock = Clock.fixed(Instant.parse("2024-02-03T04:05:06.007Z"), ZoneOffset.UTC);
        IncidentReport report = reportWithEscapes(clock);

        Path outputZip = tempDir.resolve("incident.zip");
        BundleBuilder builder = new BundleBuilder(clock);
        builder.build(report, recording, outputZip, List.of());

        try (ZipFile zip = new ZipFile(outputZip.toFile())) {
            ZipEntry incidentEntry = zip.getEntry("incident.json");
            ZipEntry recordingEntry = zip.getEntry("recording.jfr");
            ZipEntry jvmEntry = zip.getEntry("env/jvm.txt");
            ZipEntry osEntry = zip.getEntry("env/os.txt");

            assertNotNull(incidentEntry);
            assertNotNull(recordingEntry);
            assertNotNull(jvmEntry);
            assertNotNull(osEntry);

            String json = new String(zip.getInputStream(incidentEntry).readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(json.contains("\"meta\""));
            assertTrue(json.contains("Headline with newline\\nand tab\\tplus carriage\\rreturn"));
            assertFalse(json.contains("\"summary\""));
            assertTrue(json.contains("\\\""));
            assertTrue(json.contains("\\\\"));
            assertTrue(json.contains("\\n"));
            assertTrue(json.contains("\\t"));
            assertTrue(json.contains("\\r"));

            byte[] zippedRecording = zip.getInputStream(recordingEntry).readAllBytes();
            assertArrayEquals(recordingBytes, zippedRecording);

            String jvmInfo = new String(zip.getInputStream(jvmEntry).readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(jvmInfo.contains("java.runtime.version="));
            assertTrue(jvmInfo.contains("java.vm.name="));

            String osInfo = new String(zip.getInputStream(osEntry).readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(osInfo.contains("os.name="));
            assertTrue(osInfo.contains("os.arch="));
        }
    }

    @Test
    void extras_areIncludedAndSorted(@TempDir Path tempDir) throws Exception {
        Path recording = tempDir.resolve("recording.jfr");
        Files.write(recording, new byte[] {9, 8, 7});

        Clock clock = Clock.fixed(Instant.parse("2024-05-06T07:08:09.010Z"), ZoneOffset.UTC);
        IncidentReport report = simpleReport(clock);

        List<BundleAttachment> extras = List.of(
            new BundleAttachment("extras/zeta.txt", "z".getBytes(StandardCharsets.UTF_8)),
            new BundleAttachment("extras/alpha.txt", "a".getBytes(StandardCharsets.UTF_8))
        );

        Path outputZip = tempDir.resolve("extras.zip");
        new BundleBuilder(clock).build(report, recording, outputZip, extras);

        try (ZipFile zip = new ZipFile(outputZip.toFile())) {
            assertNotNull(zip.getEntry("extras/alpha.txt"));
            assertNotNull(zip.getEntry("extras/zeta.txt"));
        }
    }

    @Test
    void artifacts_jfrOnlyExcludesOthers(@TempDir Path tempDir) throws Exception {
        Path recording = tempDir.resolve("recording.jfr");
        Files.write(recording, new byte[] {1, 2, 3});

        Clock clock = Clock.fixed(Instant.parse("2024-05-06T07:08:09.010Z"), ZoneOffset.UTC);
        IncidentReport report = simpleReport(clock);

        List<BundleAttachment> extras = List.of(
            new BundleAttachment("extras/threads.txt", "t".getBytes(StandardCharsets.UTF_8)));

        Path outputZip = tempDir.resolve("jfronly.zip");
        new BundleBuilder(clock).build(report, recording, outputZip, extras, java.util.Set.of("jfr"));

        try (ZipFile zip = new ZipFile(outputZip.toFile())) {
            assertNotNull(zip.getEntry("incident.json"));
            assertNotNull(zip.getEntry("recording.jfr"));
            assertNull(zip.getEntry("report.html"));
            assertNull(zip.getEntry("env/jvm.txt"));
            assertNull(zip.getEntry("extras/threads.txt"));
        }
    }

    @Test
    void artifacts_excludeNumberedThreadDumpsAndMetrics(@TempDir Path tempDir) throws Exception {
        Path recording = tempDir.resolve("recording.jfr");
        Files.write(recording, new byte[] {1, 2, 3});

        Clock clock = Clock.fixed(Instant.parse("2024-05-06T07:08:09.010Z"), ZoneOffset.UTC);
        IncidentReport report = simpleReport(clock);

        List<BundleAttachment> extras = List.of(
            new BundleAttachment("extras/threads-1.txt", "a".getBytes(StandardCharsets.UTF_8)),
            new BundleAttachment("extras/threads-2.txt", "b".getBytes(StandardCharsets.UTF_8)),
            new BundleAttachment("extras/metrics/health-2024-05-06.csv", "c".getBytes(StandardCharsets.UTF_8)),
            new BundleAttachment("extras/plugins.txt", "d".getBytes(StandardCharsets.UTF_8)));

        java.util.Set<String> enabled = new java.util.HashSet<>(BundleArtifacts.ALL);
        enabled.remove("threads");
        enabled.remove("metrics");
        Path outputZip = tempDir.resolve("filtered.zip");
        new BundleBuilder(clock).build(report, recording, outputZip, extras, enabled);

        try (ZipFile zip = new ZipFile(outputZip.toFile())) {
            assertNull(zip.getEntry("extras/threads-1.txt"));
            assertNull(zip.getEntry("extras/threads-2.txt"));
            assertNull(zip.getEntry("extras/metrics/health-2024-05-06.csv"));
            assertNotNull(zip.getEntry("extras/plugins.txt"));
        }
    }

    @Test
    void duplicateExtraPathsKeepFirstEntry(@TempDir Path tempDir) throws Exception {
        Path recording = tempDir.resolve("recording.jfr");
        Files.write(recording, new byte[] {1, 2, 3});

        Clock clock = Clock.fixed(Instant.parse("2024-05-06T07:08:09.010Z"), ZoneOffset.UTC);
        IncidentReport report = simpleReport(clock);

        List<BundleAttachment> extras = List.of(
            new BundleAttachment("extras/shared.txt", "first".getBytes(StandardCharsets.UTF_8)),
            new BundleAttachment("extras/shared.txt", "second".getBytes(StandardCharsets.UTF_8)),
            new BundleAttachment("report.html", "plugin".getBytes(StandardCharsets.UTF_8)));

        Path outputZip = tempDir.resolve("duplicates.zip");
        new BundleBuilder(clock).build(report, recording, outputZip, extras);

        try (ZipFile zip = new ZipFile(outputZip.toFile())) {
            assertArrayEquals("first".getBytes(StandardCharsets.UTF_8),
                zip.getInputStream(zip.getEntry("extras/shared.txt")).readAllBytes());
            assertFalse(new String(zip.getInputStream(zip.getEntry("report.html")).readAllBytes(),
                StandardCharsets.UTF_8).equals("plugin"));
        }
    }

    private static IncidentReport reportWithEscapes(Clock clock) {
        IncidentId id = IncidentIds.next(clock);
        Instant createdAt = clock.instant();
        IncidentMetadata meta = new IncidentMetadata(
            id,
            createdAt,
            Severity.CRITICAL,
            "trigger \"quote\" and backslash \\",
            "world\\one",
            "Headline with newline\nand tab\tplus carriage\rreturn"
        );
        return new IncidentReport(meta);
    }

    private static IncidentReport simpleReport(Clock clock) {
        IncidentId id = IncidentIds.next(clock);
        Instant createdAt = clock.instant();
        IncidentMetadata meta = new IncidentMetadata(
            id,
            createdAt,
            Severity.INFO,
            "manual",
            null,
            "Simple incident"
        );
        return new IncidentReport(meta);
    }
}
