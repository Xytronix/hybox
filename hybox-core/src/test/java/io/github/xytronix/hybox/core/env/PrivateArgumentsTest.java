package io.github.xytronix.hybox.core.env;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PrivateArgumentsTest {

    @Test
    void masksQuotedOwnerArgumentsAndKeepsTheQuotes() {
        assertEquals("\"--owner-name=[REDACTED]\" '--owner-uuid=[REDACTED]'",
            PrivateArguments.mask("\"--owner-name=Bob\" '--owner-uuid=7d15a022-4875-44f5-9f0b-73f1f4ecbdee'"));
        assertEquals("java -jar server.jar --owner-name [REDACTED] --bind [REDACTED] --world default",
            PrivateArguments.mask("java -jar server.jar --owner-name Bob --bind localhost:61857 --world default"));
    }

    @Test
    void maskedCommandStaysTheSame() {
        String masked = "\"--owner-name=[REDACTED]\" --bind [REDACTED]";

        assertEquals(masked, PrivateArguments.mask(masked));
    }
}
