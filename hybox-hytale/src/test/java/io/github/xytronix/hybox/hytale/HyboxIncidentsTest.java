package io.github.xytronix.hybox.hytale;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HyboxIncidentsTest {
    @TempDir Path directory;

    @Test
    void recentHistoryIsNewestFirstAndLimitedWithoutReadingOlderBundles() throws Exception {
        write("20261022-110000.000+0000-a", "old");
        write("20261022-120000.000+0000-c", "latest");
        write("20261022-113000.000+0000-b", "middle");
        Files.writeString(directory.resolve("not-an-incident.txt"), "ignored");

        var incidents = HyboxIncidents.recent(directory, 2);

        assertEquals(2, incidents.size());
        assertEquals("latest", incidents.get(0).headline());
        assertEquals("middle", incidents.get(1).headline());
        assertEquals(Instant.parse("2026-10-22T12:00:00Z"), incidents.get(0).createdAt());
        assertEquals("HEAP_PRESSURE", incidents.get(0).kind());
        assertEquals("DEGRADED", incidents.get(0).severity());
        assertEquals("default", incidents.get(0).world());
    }

    @Test
    void unreadableNewestBundleDoesNotHideUsableIncidentHistory() throws Exception {
        write("20261022-110000.000+0000-a", "usable");
        Files.writeString(directory.resolve("incident-20261022-120000.000+0000-b.zip"), "incomplete zip");

        var incidents = HyboxIncidents.recent(directory, 10);

        assertEquals(2, incidents.size());
        assertEquals("headline unavailable", incidents.get(0).headline());
        assertEquals("usable", incidents.get(1).headline());
    }

    @Test
    void absentHistoryIsEmpty() throws Exception {
        assertEquals(java.util.List.of(), HyboxIncidents.recent(directory.resolve("not-created"), 10));
    }

    private void write(String id, String headline) throws Exception {
        try (var zip = new ZipOutputStream(Files.newOutputStream(directory.resolve("incident-" + id + ".zip")))) {
            zip.putNextEntry(new ZipEntry("incident.json"));
            zip.write(("{\"meta\":{\"headline\":\"" + headline + "\",\"trigger\":\"HEAP_PRESSURE\","
                + "\"severity\":\"DEGRADED\",\"world\":\"default\"}}")
                .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }
}
