package io.github.xytronix.hybox.core.jfr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.BitSet;
import java.util.List;

import io.github.xytronix.hybox.core.env.PathMasker;
import io.github.xytronix.hybox.core.env.PrivateArguments;
import io.github.xytronix.hybox.core.env.TextRedactor;
import org.junit.jupiter.api.Test;

class PrivateTextTest {
    private final PrivateText text = new PrivateText(List.of(
        new PathMasker("/Users/michael", "/srv/hytale"),
        new TextRedactor(TextRedactor.DEFAULT_PATTERNS)::redact,
        PrivateArguments::mask,
        value -> value.replace("player42", "player-1")), List.of("25.0.4.1", "25.0.4.1+1-LTS"));

    @Test
    void hidesOnlyThePrivatePartWithTheSameLength() {
        assertEquals("quiche-listener-*************************",
            masked("quiche-listener-127.0.0.1/127.0.0.1:61244"));
        assertEquals("quiche-listener-::1/[**********************",
            masked("quiche-listener-::1/[0:0:0:0:0:0:0:1]:61244"));
        assertEquals("\"quiche-listener-*************************\" #52 daemon prio=5\n\tat Foo.run()",
            masked("\"quiche-listener-127.0.0.1/127.0.0.1:61244\" #52 daemon prio=5\n\tat Foo.run()"));
        assertEquals("**************/Library/Application Support/Hytale/Server/HytaleServer.jar",
            masked("/Users/michael/Library/Application Support/Hytale/Server/HytaleServer.jar"));
        assertEquals("*******************/libjava.so", masked("/opt/jdk-25.0.4/lib/libjava.so"));
        assertEquals("*".repeat(56) + "/CoreText",
            masked("/System/Library/Frameworks/CoreText.framework/Versions/A/CoreText"));
        assertEquals("x.jar --owner-name=**** --bind *************** --auth-mode=offline",
            masked("x.jar --owner-name=Wrex --bind localhost:61244 --auth-mode=offline"));
    }

    @Test
    void hidesWholeWordsWhenTheTokenSharesLetters() {
        assertEquals("******** joined", masked("player42 joined"));
    }

    @Test
    void leavesPublicTextAlone() {
        assertNull(text.find("Server Thread"));
    }

    @Test
    void keepsTheRunningJvmVersion() {
        assertEquals("OpenJDK 64-Bit Server VM (25.0.4.1+1-LTS) at *************",
            masked("OpenJDK 64-Bit Server VM (25.0.4.1+1-LTS) at 10.0.0.1:5520"));
        assertEquals("peer **********", masked("peer 125.0.4.10"));
        assertEquals("Temurin-v", text.withoutPublicTexts("Temurin-25.0.4.1+1-LTS"));
    }

    private String masked(String value) {
        BitSet hidden = text.find(value);
        char[] chars = value.toCharArray();
        for (int i = hidden.nextSetBit(0); i >= 0; i = hidden.nextSetBit(i + 1)) {
            chars[i] = '*';
        }
        return new String(chars);
    }
}
