package io.github.xytronix.hybox.hytale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.xytronix.hybox.core.incident.DiagnosticSection;

class HytaleModConfigsTest {

    @Test
    void capturesNothingWhenScrapeDisabledAndNoOptIn() {
        List<DiagnosticSection> out = HytaleModConfigs.appendTo(List.of(), false, List.of());

        assertTrue(out.isEmpty());
    }

    @Test
    void includesRegisteredConfigEvenWhenScrapeDisabled(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("my-mod.json");
        Files.writeString(cfg, "{\"secret\":\"abc\"}", StandardCharsets.UTF_8);

        List<DiagnosticSection> out = HytaleModConfigs.appendTo(List.of(), false, List.of(cfg));

        assertEquals(1, out.size());
        assertEquals("{\"secret\":\"abc\"}", out.get(0).preformatted());
        assertTrue(out.get(0).title().endsWith("my-mod.json"));
    }

    @Test
    void skipsMissingRegisteredFile(@TempDir Path dir) {
        Path missing = dir.resolve("nope.json");

        List<DiagnosticSection> out = HytaleModConfigs.appendTo(List.of(), false, List.of(missing));

        assertTrue(out.isEmpty());
    }
    @Test
    void readsOnlyABoundedPrefixOfOversizedConfiguration(@TempDir Path dir) throws Exception {
        Path config = dir.resolve("large.json");
        long length = (long) Integer.MAX_VALUE + 4096L;
        try (var file = new java.io.RandomAccessFile(config.toFile(), "rw")) {
            file.write("bounded-prefix".getBytes(StandardCharsets.UTF_8));
            file.setLength(length);
        }
        List<DiagnosticSection> sections;
        try {
            sections = HytaleModConfigs.appendTo(List.of(), false, List.of(config));
        } catch (OutOfMemoryError oversizedRead) {
            throw new AssertionError("Configuration reader attempted an unbounded array allocation", oversizedRead);
        }
        assertEquals(1, sections.size());
        String text = sections.get(0).preformatted();
        assertTrue(text.startsWith("bounded-prefix"));
        assertTrue(text.length() <= 96 * 1024 + 100);
        assertTrue(text.contains("truncated " + (length - 96 * 1024) + " more bytes"));
    }
}
