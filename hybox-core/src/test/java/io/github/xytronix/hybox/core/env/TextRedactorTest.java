package io.github.xytronix.hybox.core.env;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class TextRedactorTest {

    @Test
    void defaultPatternsRedactAddressesAndWebhooks() {
        TextRedactor redactor = new TextRedactor(TextRedactor.DEFAULT_PATTERNS);
        String input = "password=hunter2 "
            + "ip=203.0.113.7 "
            + "world=7d15a022-4875-44f5-9f0b-73f1f4ecbdee "
            + "hook=https://discord.com/api/webhooks/12345678901234567/abcDEF_ghi";

        String redacted = redactor.redact(input);

        assertTrue(redacted.contains("[REDACTED]"));
        assertFalse(redacted.contains("hunter2"));
        assertTrue(redacted.contains("7d15a022-4875-44f5-9f0b-73f1f4ecbdee"));
        assertFalse(redacted.contains("203.0.113.7"));
        assertFalse(redacted.contains("12345678901234567"));
    }

    @Test
    void defaultPatternsRedactJsonPasswordValueButNotEmpty() {
        TextRedactor redactor = new TextRedactor(TextRedactor.DEFAULT_PATTERNS);

        assertFalse(redactor.redact("{\"Password\": \"hunter2\"}").contains("hunter2"));
        assertTrue(redactor.redact("{\"Password\": \"hunter2\"}").contains("\"Password\": \"[REDACTED]\""));
        assertEquals("{\"Password\": \"\"}", redactor.redact("{\"Password\": \"\"}"));
    }

    @Test
    void usesProvidedPatternsLiterallyWithoutMergingDefaults() {
        TextRedactor redactor = new TextRedactor(List.of("CUSTOM_SECRET_[A-Z0-9]+"));
        String input = "ip=192.168.10.20 custom=CUSTOM_SECRET_ABC123";

        String redacted = redactor.redact(input);

        assertTrue(redacted.contains("192.168.10.20"));
        assertFalse(redacted.contains("CUSTOM_SECRET_ABC123"));
    }

    @Test
    void invalidPatternIsSkippedWithoutBreakingOthers() {
        TextRedactor redactor = new TextRedactor(List.of("[", "password=\\S+"));
        String redacted = redactor.redact("password=swordfish");

        assertFalse(redacted.contains("swordfish"));
    }

    @Test
    void keepsJvmHytaleAndPluginVersionsThatLookLikeAddresses() {
        TextRedactor redactor = new TextRedactor(TextRedactor.DEFAULT_PATTERNS,
            () -> List.of("25.0.4.1+1-LTS", "0.7.7", "1.2.3.4"));

        assertEquals("Java runtime Temurin 25.0.4.1+1-LTS, Refixes 1.2.3.4, bind [REDACTED]",
            redactor.redact("Java runtime Temurin 25.0.4.1+1-LTS, Refixes 1.2.3.4, bind 10.0.4.1:5520"));
        String running = "Temurin " + System.getProperty("java.runtime.version");
        assertEquals(running, new TextRedactor(TextRedactor.DEFAULT_PATTERNS).redact(running));
        assertEquals("25.9.9.9+1-LTS and v 1.2.3.4 build and 1.2.3.4.5",
            new TextRedactor(TextRedactor.DEFAULT_PATTERNS).redact("25.9.9.9+1-LTS and v 1.2.3.4 build and 1.2.3.4.5"));
    }

    @Test
    void defaultPatternsRedactBareIpv6ButNotClockTimes() {
        TextRedactor redactor = new TextRedactor(TextRedactor.DEFAULT_PATTERNS);

        assertEquals("/[REDACTED]", redactor.redact("/2001:db8:0:0:0:0:0:1"));
        assertEquals("addr [REDACTED] port", redactor.redact("addr 2001:db8::1 port"));
        assertEquals("[REDACTED]", redactor.redact("fe80::1c2b:3aff:fe4d:5e6f%en0"));
        assertEquals("[REDACTED]", redactor.redact("::1"));
        assertEquals("[REDACTED]", redactor.redact("[::1]:5520"));
        String log = "[12:34:56] [Server thread/INFO]: hi at 2026-11-21T12:34:56.789Z, std::vector Foo::bar";
        assertEquals(log, redactor.redact(log));
    }

    @Test
    void defaultPatternsRedactHostnamesWithPortsAndLocalNames() {
        TextRedactor redactor = new TextRedactor(TextRedactor.DEFAULT_PATTERNS);

        assertEquals("host [REDACTED]", redactor.redact("host play.example.net:5520"));
        assertEquals("[REDACTED]", redactor.redact("Bobs-MacBook-Pro.local"));
        assertEquals("[REDACTED]", redactor.redact("localhost:61857"));
        assertEquals("localhost/[REDACTED]", redactor.redact("localhost/127.0.0.1:5520"));
        assertEquals("[REDACTED] [REDACTED] [REDACTED] [REDACTED]",
            redactor.redact("mc.serwer.pl:5520 node1.example.rs:5520 Play.Example.NET:5520 play.example.ovh:5520"));
        assertEquals("https://[REDACTED]/status jdbc:mysql://[REDACTED]/db",
            redactor.redact("https://Play.Example.NET:5520/status jdbc:mysql://db1:3306/db"));
        String frames = "at com.example.Foo.bar(Foo.java:123) window.localStorage x.local.Foo Thread.run:1583 "
            + "world.default:20 ChunkStore.tick:120 ms script.pl:12 timeout.ms:5000 kafka.timeout.ms:5000 Main.kt:12";
        assertEquals(frames, redactor.redact(frames));
    }

    @Test
    void defaultPatternsRedactSecretKeysAndVersionedWebhooks() {
        TextRedactor redactor = new TextRedactor(TextRedactor.DEFAULT_PATTERNS);

        assertEquals("{\"rconPassword\":\"[REDACTED]\",\"token\":\"[REDACTED]\"}",
            redactor.redact("{\"rconPassword\":\"x\",\"token\":\"abc\"}"));
        assertEquals("password=[REDACTED]", redactor.redact("password=hunter2"));
        assertEquals("password=[REDACTED]", redactor.redact("password=[REDACTED]"));
        assertEquals("[REDACTED]", redactor.redact("https://discord.com/api/v10/webhooks/123456789012345678/abc_DEF"));
        String json = "{\"note\":\"token:\",\"x\":\"y\",\"a\":\"password=\\\"x\\\"\"}";
        assertEquals(json, redactor.redact(json));
        String settings = "at TokenStore.load(TokenStore.java:55) token: 5 password_min_length=8";
        assertEquals(settings, redactor.redact(settings));
        assertEquals("token: [REDACTED])", redactor.redact("token: abc)"));
        assertEquals("password=[REDACTED] rcon.password=[REDACTED] api_key=[REDACTED] password=[REDACTED]",
            redactor.redact("password=123456 rcon.password=12345678 api_key=1234567890 password=ab)cd"));
    }

    @Test
    void theDefaultListOfEarlierVersionsGetsTheCurrentRules() {
        TextRedactor redactor = new TextRedactor(TextRedactor.PREVIOUS_DEFAULT_PATTERNS);

        assertEquals("addr [REDACTED] port", redactor.redact("addr 2001:db8::1 port"));
    }
}
