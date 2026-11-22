package io.github.xytronix.hybox.hytale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HyboxCommandUsageTest {
    @Test
    void usageListsOneCommandPerLineWithProfileDefaults() {
        var usage = HyboxCommand.usage();
        assertEquals(12, usage.size());
        assertEquals("/hybox ui: open the dashboard", usage.getFirst());
        assertTrue(usage.contains("/hybox profile [--minutes=5] [--keep-buffer=true]: record a detailed profile, then save a bundle"), usage.toString());
        assertTrue(usage.stream().allMatch(line -> line.startsWith("/hybox ") && line.length() < 100), usage.toString());
    }
}
