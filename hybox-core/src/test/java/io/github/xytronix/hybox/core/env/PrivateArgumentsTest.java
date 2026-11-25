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
    void startCommandHidesOwnerTokensAndBindAddress() {
        String command = "java -Xms768M -Dhytale.api-key=abc HytaleServer.jar --client-pid 37667 --bind localhost:61244 "
            + "--singleplayer --owner-uuid=bd97e661-1c0e-40c8-8863-b37f97b66447 --owner-name=Wrex "
            + "--session-token \"a b\" --identity-token eyJ.x.y -b 0.0.0.0:5520 --auth-mode=authenticated";

        assertEquals("java -Xms768M -Dhytale.api-key=[REDACTED] HytaleServer.jar --client-pid 37667 --bind [REDACTED] "
                + "--singleplayer --owner-uuid=[REDACTED] --owner-name=[REDACTED] "
                + "--session-token [REDACTED] --identity-token [REDACTED] -b [REDACTED] --auth-mode=authenticated",
            PrivateArguments.mask(command));
    }

    @Test
    void maskedCommandStaysTheSame() {
        String masked = "\"--owner-name=[REDACTED]\" --bind [REDACTED]";

        assertEquals(masked, PrivateArguments.mask(masked));
    }
}
