package io.github.xytronix.hybox.core.bundle;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.xytronix.hybox.core.env.PathMasker;
import io.github.xytronix.hybox.core.env.TextRedactor;

class BundleCleanerTest {
    private static final String SCRIPT = "function show(d){return d.max:100}";

    @Test
    void anOlderBundleIsRewrittenMaskedAndMarked(@TempDir Path dir) throws Exception {
        Path bundle = dir.resolve("incident-old.zip");
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("incident.json", "{\"meta\":{\"id\":\"old\",\"headline\":\"World Bob_home failed: /Users/bob/x.bin\","
            + "\"world\":\"Bob_home\"},\"context\":{\"remote\":\"203.0.113.7:5520\"}}");
        entries.put("report.html", "<html><head><style>a::before{content:'1.2.3.4'}</style></head><body>"
            + "<div class=\"kv\">Start command /Users/bob/server --bind localhost:61857 --session-token abc</div>"
            + "<script>const D = {\"sys\":{\"startCommand\":\"java -jar /Users/bob/s.jar --owner-name=Bob "
            + "--session-token abc --identity-token def --boot-command op\"},"
            + "\"worlds\":[{\"playerNames\":[\"Bob\"]}]};\n" + SCRIPT + "</script></body></html>");
        entries.put("env/jvm.txt", "java.home=/Users/bob/Library/Java\n");
        entries.put("extras/capture.bin", "Bob /Users/bob");
        write(bundle, entries);
        BundleMasker masker = new BundleMasker(new TextRedactor(TextRedactor.DEFAULT_PATTERNS),
            text -> text.replaceAll("(?<![A-Za-z])Bob(?![A-Za-z])", "player-1"), new PathMasker("/home/hytale", "/srv"));

        assertFalse(BundleCleaner.cleaned(bundle));
        new BundleCleaner(masker, System.getLogger("cleaner-test")).clean(bundle);

        assertTrue(BundleCleaner.cleaned(bundle));
        Map<String, String> cleaned = read(bundle);
        String json = cleaned.get("incident.json");
        assertTrue(json.contains("\"headline\":\"World player-1_home failed: ~/x.bin\""), json);
        assertTrue(json.contains("\"cleaned\":2"), json);
        assertTrue(json.contains("\"remote\":\"[REDACTED]\""), json);
        String html = cleaned.get("report.html");
        assertTrue(html.contains("Start command ~/server --bind [REDACTED] --session-token [REDACTED]"), html);
        assertTrue(html.contains("java -jar ~/s.jar --owner-name=[REDACTED] --session-token [REDACTED] "
            + "--identity-token [REDACTED] --boot-command [REDACTED]"), html);
        assertFalse(html.contains("abc") || html.contains("def"), html);
        assertTrue(html.contains("\"playerNames\":[\"player-1\"]"), html);
        assertTrue(html.contains(SCRIPT), "code outside the report data is left alone");
        assertTrue(html.contains("a::before{content:'1.2.3.4'}"), "styles are left alone");
        assertTrue(cleaned.get("env/jvm.txt").contains("java.home=~/Library/Java"));
        assertTrue(cleaned.get("extras/capture.bin").contains("Bob /Users/bob"), "binary entries are copied as they are");
        assertNoTempFiles(dir);
    }

    @Test
    void aBundleThatCannotBeCleanedStaysAsItWas(@TempDir Path dir) throws Exception {
        Path bundle = dir.resolve("incident-broken.zip");
        write(bundle, Map.of("incident.json", "{\"meta\":{\"headline\":\"x\"}", "report.html", "<html></html>"));
        byte[] before = Files.readAllBytes(bundle);
        BundleMasker masker = new BundleMasker(null, null, new PathMasker("/home/hytale", "/srv"));

        assertThrows(IOException.class, () -> new BundleCleaner(masker, System.getLogger("cleaner-test")).clean(bundle));

        assertArrayEquals(before, Files.readAllBytes(bundle));
        assertFalse(BundleCleaner.cleaned(bundle));
        assertNoTempFiles(dir);
    }

    private static void write(Path zip, Map<String, String> entries) throws IOException {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
    }

    private static Map<String, String> read(Path zip) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipFile file = new ZipFile(zip.toFile())) {
            for (ZipEntry entry : java.util.Collections.list(file.entries())) {
                entries.put(entry.getName(), new String(file.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

    private static void assertNoTempFiles(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            List<String> names = files.map(path -> path.getFileName().toString()).toList();
            assertFalse(names.stream().anyMatch(name -> name.startsWith(".hybox-")), names.toString());
        }
    }
}
