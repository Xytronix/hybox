package io.github.xytronix.hybox.hytale;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HyboxCommandUsageTest {
    @Test
    void usageListsProfileOptionsWithTheirDefaults() {
        assertEquals("Usage: /hybox ui|settings|incident [id]|status [--verbose]|list [--count=5] [--details]"
            + "|triggers [--details]|dump|histogram|profile [--minutes=5] [--keep-buffer=true] [stop|cancel]|trend|reload",
            HyboxCommand.usage());
    }
}
