package io.github.xytronix.hybox.core.bundle;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.xytronix.hybox.core.env.TextRedactor;
import io.github.xytronix.hybox.core.incident.*;
import io.github.xytronix.hybox.core.json.JsonWriter;

class BundleSafetyTest {
    private final Clock clock = Clock.systemUTC();

    private IncidentReport report() {
        return new IncidentReport(new IncidentMetadata(IncidentIds.next(clock), clock.instant(),
            Severity.INFO, "MANUAL", null, "Safety regression"),
            new IncidentSummary("Unknown", List.of("Manual capture"), List.of("Inspect recording")));
    }

    @Test
    void failedCaptureDoesNotPublishPartialBundle(@TempDir Path dir) throws Exception {
        Path output = dir.resolve("incident.zip");
        assertThrows(IOException.class, () -> new BundleBuilder(clock).build(report(),
            dir.resolve("missing.jfr"), output, List.of(), Set.of("jfr")));
        assertFalse(Files.exists(output));
        try (var entries = Files.list(dir)) {
            assertEquals(0, entries.count());
        }
    }

    @Test
    void failedReplacementPreservesExistingBundle(@TempDir Path dir) throws Exception {
        Path output = dir.resolve("incident.zip");
        byte[] original = {7, 8, 9};
        Files.write(output, original);
        assertThrows(IOException.class, () -> new BundleBuilder(clock).build(report(),
            dir.resolve("missing.jfr"), output, List.of(), Set.of("jfr")));
        assertArrayEquals(original, Files.readAllBytes(output));
        try (var entries = Files.list(dir)) {
            assertEquals(1, entries.count());
        }
    }

    @Test
    void binaryAttachmentsAreNotDecodedAndMaskedAsText(@TempDir Path dir) throws Exception {
        Path recording = Files.write(dir.resolve("source.jfr"), new byte[]{1});
        Path output = dir.resolve("incident.zip");
        byte[] binary = {(byte)0xff, 'A', 'l', 'i', 'c', 'e', (byte)0x80};
        var builder = new BundleBuilder(clock, System.getLogger("bundle-safety"), null,
            text -> text.replace("Alice", "Masked"));
        builder.build(report(), recording, output,
            List.of(new BundleAttachment("extras/plugin.bin", binary)), Set.of("jfr"));
        try (var zip = new ZipFile(output.toFile())) {
            assertArrayEquals(binary, zip.getInputStream(zip.getEntry("extras/plugin.bin")).readAllBytes());
        }
    }

    @Test
    void configuredRedactionCoversEnvironmentFiles(@TempDir Path dir) throws Exception {
        Path recording = Files.write(dir.resolve("source.jfr"), new byte[]{1});
        Path output = dir.resolve("incident.zip");
        var builder = new BundleBuilder(clock, System.getLogger("bundle-safety"),
            new TextRedactor(List.of("java\\.home=[^\\r\\n]+")));
        builder.build(report(), recording, output, List.of(), Set.of("env"));
        try (var zip = new ZipFile(output.toFile())) {
            String data = new String(zip.getInputStream(zip.getEntry("env/jvm.txt")).readAllBytes(),
                StandardCharsets.UTF_8);
            assertFalse(data.contains("java.home="));
            assertTrue(data.contains("[REDACTED]"));
        }
    }

    @Test
    void nonFiniteNumbersProduceValidJsonNulls() throws Exception {
        StringBuilder result = new StringBuilder();
        var json = new JsonWriter(result);
        json.beginArray().value(Double.NaN).value(Double.POSITIVE_INFINITY)
            .value(Double.NEGATIVE_INFINITY).value(Float.NaN).value(42).endArray();
        assertEquals("[null,null,null,null,42]", result.toString());
    }
}
