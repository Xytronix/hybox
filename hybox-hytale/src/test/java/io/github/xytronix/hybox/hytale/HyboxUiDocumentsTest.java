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
        assertTrue(settings.contains("Text: \"0 to 3600 seconds.\";"));
        assertFalse(settings.contains("Existing cooldown exemptions"));
    }

    @Test
    void toneReferencesResolveToDefinitionsInTheTonesDocument() throws Exception {
        Path custom = Path.of(getClass().getResource("/Common/UI/Custom").toURI());
        String tones = Files.readString(custom.resolve("Pages/HyboxTones.ui"));
        var defined = Pattern.compile("(?m)^@(\\w+)\\s*=").matcher(tones).results().map(m -> m.group(1)).collect(java.util.stream.Collectors.toSet());
        List<String> missing = new ArrayList<>();
        try (var files = Files.walk(custom)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".ui")).sorted().toList()) {
                String text = Files.readString(file);
                var alias = Pattern.compile("\\$(\\w+) = \"(?:\\.\\./Pages/)?HyboxTones\\.ui\";").matcher(text);
                if (!alias.find()) {
                    if (!file.endsWith("HyboxTones.ui")) assertFalse(text.contains("$H."), file.getFileName() + " uses $H without importing the tones");
                    continue;
                }
                var uses = Pattern.compile("\\$" + alias.group(1) + "\\.@(\\w+)").matcher(text);
                while (uses.find()) if (!defined.contains(uses.group(1))) missing.add(file.getFileName() + " " + uses.group());
            }
        }
        assertEquals(List.of(), missing);
        for (var tone : DashboardModel.Tone.values()) {
            String name = tone.name().charAt(0) + tone.name().substring(1).toLowerCase(java.util.Locale.ROOT);
            assertTrue(defined.contains(name + "Pill"), name + "Pill");
        }
        for (String name : List.of("WarnBanner", "ErrorBanner", "SelectedButtonStyle", "SecondaryButtonStyle", "TabButtonStyle")) {
            assertTrue(defined.contains(name), name);
        }
    }

    @Test
    void everyTexturePathResolvesToAFileInTheAssetPackOrTheNativeCommonFolder() throws Exception {
        Path custom = Path.of(getClass().getResource("/Common/UI/Custom").toURI());
        var path = Pattern.compile("\"([^\"]+\\.png)\"");
        List<String> missing = new ArrayList<>();
        int own = 0;
        try (var files = Files.walk(custom)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".ui")).sorted().toList()) {
                var matcher = path.matcher(Files.readString(file));
                while (matcher.find()) {
                    Path target = file.getParent().resolve(matcher.group(1)).normalize();
                    if (custom.relativize(target).startsWith("Common")) continue;
                    Path doubled = target.resolveSibling(target.getFileName().toString().replace(".png", "@2x.png"));
                    if (!Files.isRegularFile(target) && !Files.isRegularFile(doubled)) missing.add(file.getFileName() + " " + matcher.group(1));
                    else own++;
                }
            }
        }
        assertEquals(List.of(), missing);
        assertTrue(own > 0);
        for (String name : List.of("HyboxLogo", "BarFill", "Frame")) {
            assertTrue(Files.isRegularFile(custom.resolve("Hybox/" + name + ".png")), name);
            assertTrue(Files.isRegularFile(custom.resolve("Hybox/" + name + "@2x.png")), name);
        }
    }

    @Test
    void noUiTextMentionsAReport() throws Exception {
        Path custom = Path.of(getClass().getResource("/Common/UI/Custom").toURI());
        Pattern text = Pattern.compile("(?:\\bText\\s*:|@Text\\s*=)\\s*\"([^\"]*)\"");
        List<String> found = new ArrayList<>();
        try (var files = Files.walk(custom)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".ui")).sorted().toList()) {
                var matcher = text.matcher(Files.readString(file));
                while (matcher.find()) if (matcher.group(1).toLowerCase(java.util.Locale.ROOT).contains("report")) found.add(file.getFileName() + ": " + matcher.group(1));
            }
        }
        assertEquals(List.of(), found);
    }

    @Test
    void dashboardAndIncidentHeadersShowTheLogo() throws Exception {
        for (String page : List.of("HyboxDashboard.ui", "HyboxIncident.ui")) {
            String text = Files.readString(Path.of(getClass().getResource("/Common/UI/Custom/Pages/" + page).toURI()));
            var title = Pattern.compile("#Title \\{.*?\\$H\\.@Logo #HeaderLogo", Pattern.DOTALL).matcher(text);
            assertTrue(title.find(), page);
        }
    }
}
