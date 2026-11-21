package io.github.xytronix.hybox.core.bundle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.xytronix.hybox.core.env.PathMasker;
import io.github.xytronix.hybox.core.env.TextRedactor;
import io.github.xytronix.hybox.core.incident.IncidentId;
import io.github.xytronix.hybox.core.incident.IncidentMetadata;
import io.github.xytronix.hybox.core.incident.IncidentReport;
import io.github.xytronix.hybox.core.incident.Severity;
import io.github.xytronix.hybox.core.json.IncidentJson;
import io.github.xytronix.hybox.core.json.JsonScanner;

class BundleMaskerTest {
    private static final UnaryOperator<String> PLAYERS_NAMED_LIKE_KEYS = text -> text.replaceAll(
        "(?<![A-Za-z])(?i:name|world|headline|true|null|meta)(?![A-Za-z])", "player-1");

    @Test
    void playersNamedLikeJsonKeysOrLiteralsCannotBreakTheBundle(@TempDir Path dir) throws Exception {
        Path recording = Files.write(dir.resolve("recording.jfr"), new byte[] {1, 2, 3});
        Clock clock = Clock.fixed(Instant.parse("2026-01-11T02:00:00Z"), ZoneOffset.UTC);
        IncidentReport report = new IncidentReport(new IncidentMetadata(new IncidentId("20260111-020000.000Z-abcdef"),
            clock.instant(), Severity.DEGRADED, "LOG_ERROR", "default", "Server error: name was null in world"),
            Map.of("error", "true"));
        Path zip = dir.resolve("incident.zip");

        new BundleBuilder(clock, System.getLogger("bundle-test"), null, PLAYERS_NAMED_LIKE_KEYS,
            new PathMasker("/home/hytale", "/srv/hytale")).build(report, recording, zip, List.of());

        try (ZipFile file = new ZipFile(zip.toFile())) {
            String json = new String(file.getInputStream(file.getEntry("incident.json")).readAllBytes(),
                StandardCharsets.UTF_8);
            Map<String, Object> parsed = new JsonScanner(json, 0).object(null);
            Map<?, ?> meta = (Map<?, ?>) parsed.get("meta");
            assertEquals("Server error: player-1 was player-1 in player-1", meta.get("headline"));
            assertEquals(2.0, meta.get(IncidentJson.CLEANED));
            assertEquals("player-1", ((Map<?, ?>) parsed.get("context")).get("error"));

            String html = new String(file.getInputStream(file.getEntry("report.html")).readAllBytes(),
                StandardCharsets.UTF_8);
            Matcher data = Pattern.compile("\\bconst\\s+D\\s*=\\s*").matcher(html);
            assertTrue(data.find());
            Map<String, Object> payload = new JsonScanner(html, data.end()).object(null);
            assertTrue(payload.containsKey("incident"), payload.keySet().toString());
            assertFalse(html.contains("<player-1"), "markup stays intact");
        }
    }

    @Test
    void valuesShownOrSentAlwaysLoseAddressesPathsAndPrivateArguments() {
        BundleMasker masker = new BundleMasker(null, null, new PathMasker("/home/hytale", "/srv/hytale"));

        assertEquals("World default failed: ~/x.bin from [REDACTED] with --owner-name [REDACTED]",
            masker.value("World default failed: /home/bob/x.bin from 2001:db8::1 with --owner-name Bob"));
        String masked = "World default failed: ~/x.bin from [REDACTED] player-2";
        assertEquals(masked, masker.value(masked));
    }

    @Test
    void htmlKeepsTagsAndEntitiesWhileMaskingText() {
        BundleMasker masker = new BundleMasker(new TextRedactor(TextRedactor.DEFAULT_PATTERNS), PLAYERS_NAMED_LIKE_KEYS,
            new PathMasker("/home/hytale", "/srv/hytale"));

        assertEquals("<div class=\"name\" title=\"player-1\">player-1 &amp; ~/x on [REDACTED]</div>",
            masker.html("<div class=\"name\" title=\"name\">Name &amp; /home/bob/x on 203.0.113.7</div>"));
    }
}
