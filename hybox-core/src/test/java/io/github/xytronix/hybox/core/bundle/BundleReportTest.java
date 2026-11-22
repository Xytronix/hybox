package io.github.xytronix.hybox.core.bundle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.xytronix.hybox.core.env.TextRedactor;
import io.github.xytronix.hybox.core.incident.IncidentId;
import io.github.xytronix.hybox.core.incident.IncidentMetadata;
import io.github.xytronix.hybox.core.incident.IncidentReport;
import io.github.xytronix.hybox.core.incident.Severity;

class BundleReportTest {

    @Test
    void bundleContainsReportHtml(@TempDir Path tempDir) throws Exception {
        Path recording = tempDir.resolve("recording.jfr");
        Files.write(recording, new byte[] {1, 2, 3});

        Clock clock = Clock.fixed(Instant.parse("2026-01-11T02:00:00Z"), ZoneOffset.UTC);
        IncidentReport report = reportWithEscapes(clock);

        Path outputZip = tempDir.resolve("incident.zip");
        new BundleBuilder(clock).build(report, recording, outputZip, List.of());

        try (ZipFile zip = new ZipFile(outputZip.toFile())) {
            ZipEntry reportEntry = zip.getEntry("report.html");
            assertNotNull(reportEntry);

            String html = new String(zip.getInputStream(reportEntry).readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(html.contains("Hybox Incident Report"));
            assertTrue(html.contains("20260111-020000.000Z-abcdef"));
            assertTrue(html.contains("DEGRADED"));
            assertTrue(html.contains("world-one"));
            assertTrue(html.contains("2026-01-11T02:00:00Z"));

            assertTrue(html.contains("Quote &quot;here&quot;"));
            assertTrue(html.contains("temp\\file<br>Line two"));

            assertFalse(html.contains("<script src="));
            assertFalse(html.contains("<link"));
            assertFalse(html.contains("src=\"http"));
        }
    }

    @Test
    void unusableRecordingRetainsMetadataAndExplainsMissingTimeline(@TempDir Path tempDir) throws Exception {
        Path recording = tempDir.resolve("recording.jfr");
        Files.write(recording, new byte[] {1, 2, 3});
        Clock clock = Clock.fixed(Instant.parse("2026-01-11T02:00:00Z"), ZoneOffset.UTC);
        IncidentReport report = reportWithEscapes(clock);
        Path outputZip = tempDir.resolve("incident.zip");
        new BundleBuilder(clock).build(report, recording, outputZip, List.of());

        try (ZipFile zip = new ZipFile(outputZip.toFile())) {
            String html = new String(zip.getInputStream(zip.getEntry("report.html")).readAllBytes(),
                StandardCharsets.UTF_8);
            String markup = html.substring(0, html.indexOf("<script>"));
            assertTrue(markup.contains("JFR recording unavailable: "));
            assertTrue(markup.contains("empty, unreadable, or contains no usable events"));
            assertTrue(markup.contains("Quote &quot;here&quot;"));
            assertTrue(markup.contains("temp\\file<br>Line two"));
            assertTrue(markup.contains("world-one"));
            assertTrue(markup.contains("20260111-020000.000Z-abcdef"));
        }
    }

    @Test
    void redactsReportHtmlAndIncidentJson(@TempDir Path tempDir) throws Exception {
        Path recording = tempDir.resolve("recording.jfr");
        Files.write(recording, new byte[] {1, 2, 3});

        Clock clock = Clock.fixed(Instant.parse("2026-01-11T02:00:00Z"), ZoneOffset.UTC);
        IncidentMetadata meta = new IncidentMetadata(
            new IncidentId("20260111-020000.000Z-abcdef"),
            clock.instant(),
            Severity.DEGRADED,
            "Player SECRET_NAME triggered a stall",
            "world-one",
            "Stall while SECRET_NAME was online"
        );
        IncidentReport report = new IncidentReport(meta, Map.of("Player", "SECRET_CONTEXT"));

        Path outputZip = tempDir.resolve("incident.zip");
        TextRedactor redactor = new TextRedactor(List.of("SECRET_[A-Z]+"));
        new BundleBuilder(clock, System.getLogger("bundle-test"), redactor)
            .build(report, recording, outputZip, List.of());

        try (ZipFile zip = new ZipFile(outputZip.toFile())) {
            for (String name : List.of("report.html", "incident.json")) {
                ZipEntry entry = zip.getEntry(name);
                assertNotNull(entry);
                String text = new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
                assertFalse(text.contains("SECRET_NAME"), name + " should not contain the secret");
                assertFalse(text.contains("SECRET_CONTEXT"), name + " should not contain the context secret");
                assertTrue(text.contains("[REDACTED]"), name + " should contain the redaction marker");
            }
        }
    }

    @Test
    void masksTextArtifactsIncludingExtras(@TempDir Path tempDir) throws Exception {
        Path recording = tempDir.resolve("recording.jfr");
        Files.write(recording, new byte[] {1, 2, 3});

        Clock clock = Clock.fixed(Instant.parse("2026-01-11T02:00:00Z"), ZoneOffset.UTC);
        IncidentMetadata meta = new IncidentMetadata(
            new IncidentId("20260111-020000.000Z-abcdef"),
            clock.instant(),
            Severity.DEGRADED,
            "Stall caused by Steve",
            "world-one",
            "Steve was online"
        );
        IncidentReport report = new IncidentReport(meta);

        Path outputZip = tempDir.resolve("incident.zip");
        new BundleBuilder(clock, System.getLogger("bundle-test"), null, s -> s.replace("Steve", "player-1"))
            .build(report, recording, outputZip, List.of(
                new BundleAttachment("extras/server-log.txt", "Steve joined".getBytes(StandardCharsets.UTF_8))));

        try (ZipFile zip = new ZipFile(outputZip.toFile())) {
            for (String name : List.of("report.html", "incident.json", "extras/server-log.txt")) {
                String text = new String(zip.getInputStream(zip.getEntry(name)).readAllBytes(),
                    StandardCharsets.UTF_8);
                assertFalse(text.contains("Steve"), name + " should not contain the player name");
                assertTrue(text.contains("player-1"), name + " should contain the pseudonym");
            }
        }
    }

    private static IncidentReport reportWithEscapes(Clock clock) {
        IncidentMetadata meta = new IncidentMetadata(
            new IncidentId("20260111-020000.000Z-abcdef"),
            clock.instant(),
            Severity.DEGRADED,
            "Trigger: \"quoted\" and path temp\\file",
            "world-one",
            "Quote \"here\" and backslash temp\\file\nLine two"
        );
        return new IncidentReport(meta);
    }
}
