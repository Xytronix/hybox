package io.github.xytronix.hybox.hytale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IncidentPrivacyTest {

    @Test
    void anOlderBundleIsShownWithoutPathsAddressesOrOwnerArguments(@TempDir Path dir) throws Exception {
        Path bundle = bundle(dir.resolve("incident-20261025-131537.000+0000-000001.zip"),
            "{\"meta\":{\"id\":\"old\",\"trigger\":\"WORLD_FAILURE\",\"world\":\"default\","
                + "\"headline\":\"World default failed: /Users/bob/hytale/chunk.bin from 203.0.113.7\"},"
                + "\"context\":{\"stallMs\":\"3622\"}}",
            "{\"sys\":{\"javaHome\":\"/Users/bob/Library/Java\",\"startCommand\":\"java -jar /Users/bob/s.jar "
                + "--bind localhost:61857 --owner-name=Bob\"},\"diag\":[]}");

        IncidentModel model = IncidentModel.load(bundle);

        assertEquals("World default failed: ~/hytale/chunk.bin from [REDACTED]", model.headline());
        String command = IncidentModel.text(model.object("sys"), "startCommand");
        assertEquals("java -jar ~/s.jar --bind [REDACTED] --owner-name=[REDACTED]", command);
        assertEquals("~/Library/Java", IncidentModel.text(model.object("sys"), "javaHome"));
        assertFalse(model.cleaned());

        HyboxIncidents.Incident incident = HyboxIncidents.details(new HyboxIncidents.Incident("old", bundle, null, null,
            "headline unavailable", null, null, null, false, true));
        assertEquals("World default failed: ~/hytale/chunk.bin from [REDACTED]", incident.headline());
        assertEquals(3622L, incident.stallMs());
        assertFalse(incident.cleaned());
    }

    @Test
    void aCleanedBundleIsShownAsItWasSaved(@TempDir Path dir) throws Exception {
        Path bundle = bundle(dir.resolve("incident-new.zip"),
            "{\"meta\":{\"id\":\"new\",\"trigger\":\"HEARTBEAT_STALL\",\"world\":\"default\",\"headline\":"
                + "\"World default stopped responding for 2 min and was still frozen when the bundle was saved\","
                + "\"cleaned\":2},\"context\":{\"stallMs\":\"120000\",\"frozen\":\"true\"}}",
            "{\"sys\":{\"javaHome\":\"~/Library/Java\",\"startCommand\":\"java -jar .../s.jar --bind [REDACTED] "
                + "player-3\"},\"diag\":[]}");

        IncidentModel model = IncidentModel.load(bundle);

        assertTrue(model.cleaned());
        assertTrue(model.frozen());
        assertEquals("java -jar .../s.jar --bind [REDACTED] player-3", IncidentModel.text(model.object("sys"), "startCommand"));
        HyboxIncidents.Incident incident = HyboxIncidents.details(new HyboxIncidents.Incident("new", bundle, null, null,
            "headline unavailable", null, null, null, false, true));
        assertTrue(incident.cleaned());
        assertTrue(incident.frozen());
        assertEquals(120000L, incident.stallMs());
    }

    @Test
    void errorsShownToPlayersCarryNoAbsolutePath() {
        assertEquals("the bundle file is missing.",
            IncidentPrivacy.failure(new NoSuchFileException("/Users/bob/server/mods/Hybox/incidents/x.zip")));
        assertEquals("zip at ~/server/x.zip is damaged",
            IncidentPrivacy.failure(new IOException("zip at /Users/bob/server/x.zip is damaged")));
    }

    private static Path bundle(Path zip, String incidentJson, String payload) throws IOException {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("incident.json"));
            out.write(incidentJson.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new ZipEntry("report.html"));
            out.write(("<html><script>const D = " + payload + ";</script></html>").getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return zip;
    }
}
