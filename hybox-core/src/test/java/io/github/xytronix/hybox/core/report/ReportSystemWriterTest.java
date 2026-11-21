package io.github.xytronix.hybox.core.report;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.xytronix.hybox.core.env.PrivateArguments;
import org.junit.jupiter.api.Test;

class ReportSystemWriterTest {

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
}
