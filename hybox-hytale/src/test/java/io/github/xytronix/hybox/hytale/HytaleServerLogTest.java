package io.github.xytronix.hybox.hytale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HytaleServerLogTest {
    @Test
    void serverLogSectionIsMaskedEvenWithLegacySanitizeLogFalse(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("hybox.json"),
            "{\"Version\":1,\"Capture\":{\"IncludeServerLog\":true,\"SanitizeLog\":false}}");
        var capture = HytaleHyboxConfig.loadOrCreate(dir, System.getLogger("server-log-test")).capturePolicy();
        Path log = dir.resolve("server.log");
        Files.writeString(log, "[INFO] Player 'Steve' (7d15a022-4875-44f5-9f0b-73f1f4ecbdee) joined\n");

        var sections = HytaleServerLog.appendTo(List.of(), log, capture.logTailLines(), new PlayerNameMasker());

        assertTrue(capture.includeServerLog());
        assertEquals(1, sections.size());
        String text = sections.get(0).preformatted();
        assertFalse(text.contains("Steve"));
        assertFalse(text.contains("7d15a022-4875-44f5-9f0b-73f1f4ecbdee"));
        assertTrue(text.contains("player-1"));
    }
}
