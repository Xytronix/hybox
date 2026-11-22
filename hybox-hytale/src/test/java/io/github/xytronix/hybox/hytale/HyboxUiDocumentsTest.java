package io.github.xytronix.hybox.hytale;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class HyboxUiDocumentsTest {
    private static final Set<String> LABEL_ALIGNMENT = Set.of("Start", "Center", "End");
    private static final Pattern ALIGNMENT = Pattern.compile("\\b(?:Horizontal|Vertical)?Alignment\\s*:\\s*(\\w+)");

    @Test
    void alignmentsUseValuesTheClientParses() throws Exception {
        Path custom = Path.of(getClass().getResource("/Common/UI/Custom").toURI());
        List<String> invalid = new ArrayList<>();
        int documents = 0;
        try (var files = Files.walk(custom)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".ui")).sorted().toList()) {
                documents++;
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    var matcher = ALIGNMENT.matcher(lines.get(i));
                    while (matcher.find()) {
                        if (!LABEL_ALIGNMENT.contains(matcher.group(1))) {
                            invalid.add(file.getFileName() + ":" + (i + 1) + " " + matcher.group());
                        }
                    }
                }
            }
        }
        assertTrue(documents > 0, "no UI documents packaged");
        assertEquals(List.of(), invalid);
    }

    @Test
    void dashboardCaptureHintDoesNotRepeatAsATooltip() throws Exception {
        String dashboard = Files.readString(Path.of(getClass().getResource("/Common/UI/Custom/Pages/HyboxDashboard.ui").toURI()));
        var hint = Pattern.compile("Label #CaptureHint \\{([^}]*)}").matcher(dashboard);
        assertTrue(hint.find());
        assertTrue(hint.group(1).contains("Text: \"Saves the last 15 minutes of recording and thread dumps as an incident bundle.\";"));
        assertFalse(hint.group(1).contains("TooltipText:"));
        assertFalse(hint.group(1).contains("TextTooltipStyle:"));
    }

    @Test
    void settingsContainsEveryConfiguredField() throws Exception {
        String settings = Files.readString(Path.of(getClass().getResource("/Common/UI/Custom/Pages/HyboxSettings.ui").toURI()));
        for (var field : HytaleHyboxConfig.SETTINGS_FIELDS) {
            assertTrue(Pattern.compile("#" + Pattern.quote(field.id()) + "\\b").matcher(settings).find(),
                    "missing settings field: " + field.id());
        }
    }

    @Test
    void settingsOmitsTheRedundantSubtitleAndKeepsTheCooldownRange() throws Exception {
        String settings = Files.readString(Path.of(getClass().getResource("/Common/UI/Custom/Pages/HyboxSettings.ui").toURI()));
        assertFalse(settings.contains("Configure incident triggers and bundle retention"));
        assertTrue(settings.contains("Text: \"0–3600 seconds.\";"));
        assertFalse(settings.contains("Existing cooldown exemptions"));
    }
}
