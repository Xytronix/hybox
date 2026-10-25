package io.github.xytronix.hybox.hytale;

import static org.junit.jupiter.api.Assertions.*;

import com.hypixel.hytale.protocol.packets.interface_.CustomUICommand;
import com.hypixel.hytale.protocol.packets.interface_.CustomUICommandType;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class HyboxProgressHudTest {
    private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");
    private static final String HOSTILE = "dump\"; } Group #Injected { Background: #ff0000; } //";

    @Test
    void profileShowsTheRingAndANativeCountdown() {
        var commands = new UICommandBuilder();
        HyboxProgressHud.render(commands, new HyboxProgressHud.State(true, NOW, 123, 20, ""), null);
        assertTrue(bool(commands, "#Profile.Visible"));
        assertFalse(bool(commands, "#Capture.Visible"));
        assertEquals(0.5, org.bson.BsonDocument.parse(set(commands, "#Ring.Value").data).getNumber("0").doubleValue(), 1e-6);
        var timer = Arrays.stream(commands.getCommands()).filter(c -> c.type == CustomUICommandType.AppendInline).findFirst().orElseThrow();
        assertEquals("#TimerHost", timer.selector);
        assertTrue(Pattern.compile("TimerLabel \\{ Seconds: 123;").matcher(timer.text).find());
    }

    @Test
    void refreshSendsOnlyWhatChangedAndNeverRestartsARunningCountdown() {
        var first = new HyboxProgressHud.State(true, NOW, 300, 0, "");
        var sameStep = new HyboxProgressHud.State(true, NOW, 299, 0, "");
        assertEquals(first, sameStep, "A second passing alone is not a change; the client timer counts down itself");
        var commands = new UICommandBuilder();
        HyboxProgressHud.render(commands, new HyboxProgressHud.State(true, NOW, 290, 1, ""), first);
        assertEquals(1, commands.getCommands().length);
        assertEquals("#Ring.Value", commands.getCommands()[0].selector);
    }

    @Test
    void captureStepIsOnlySetAsText() {
        var commands = new UICommandBuilder();
        HyboxProgressHud.render(commands, new HyboxProgressHud.State(false, null, 0, 0, HOSTILE), null);
        assertFalse(bool(commands, "#Profile.Visible"));
        assertTrue(bool(commands, "#Capture.Visible"));
        CustomUICommand step = set(commands, "#Step.Text");
        assertEquals(HOSTILE, org.bson.BsonDocument.parse(step.data).getString("0").getValue());
        assertTrue(Arrays.stream(commands.getCommands()).noneMatch(c -> c.text != null && c.text.contains("Injected")));
        assertTrue(Arrays.stream(commands.getCommands()).noneMatch(c -> c.data != null && Pattern.compile("#[0-9a-fA-F]{6}\\(").matcher(c.data).find()));
    }

    @Test
    void ringMovesInVisibleStepsAcrossAProfile() {
        int changes = 0, previous = -1;
        for (int second = 0; second <= 300; second++) {
            int ring = HyboxProgressHud.step(HyboxProgressHud.profileFraction(NOW, Duration.ofMinutes(5), NOW.plusSeconds(second)));
            if (ring != previous) changes++;
            previous = ring;
        }
        assertEquals(40, previous);
        assertTrue(changes <= 41, "Five-minute profile changes the ring " + changes + " times");
        assertEquals(0, HyboxProgressHud.step(-1));
        assertEquals(40, HyboxProgressHud.step(2));
    }

    @Test
    void profileFractionUsesElapsedTimeAndClampsBeforeStartAndAfterCompletion() {
        assertEquals(0.5, HyboxProgressHud.profileFraction(NOW.minusSeconds(150), Duration.ofMinutes(5), NOW));
        assertEquals(0, HyboxProgressHud.profileFraction(NOW.plusSeconds(1), Duration.ofMinutes(5), NOW));
        assertEquals(1, HyboxProgressHud.profileFraction(NOW.minusSeconds(301), Duration.ofMinutes(5), NOW));
        assertEquals(0, HyboxProgressHud.profileFraction(null, Duration.ZERO, NOW));
        assertEquals(1, HyboxProgressHud.profileFraction(NOW, Duration.ZERO, NOW));
    }

    private static CustomUICommand set(UICommandBuilder commands, String selector) {
        return Arrays.stream(commands.getCommands()).filter(c -> selector.equals(c.selector)).findFirst().orElseThrow();
    }

    private static boolean bool(UICommandBuilder commands, String selector) {
        return org.bson.BsonDocument.parse(set(commands, selector).data).getBoolean("0").getValue();
    }
}
