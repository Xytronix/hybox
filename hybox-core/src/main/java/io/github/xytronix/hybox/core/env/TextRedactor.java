package io.github.xytronix.hybox.core.env;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class TextRedactor {
    private static final System.Logger LOGGER = System.getLogger(TextRedactor.class.getName());

    public static final String IPV4 = "(?<!\\d)(?:\\d{1,3}\\.){3}\\d{1,3}(?!\\d)";
    public static final String IPV6 =
        "\\[(?:[0-9A-Fa-f]{0,4}:){2,7}[0-9A-Fa-f]{0,4}(?:%[0-9A-Za-z_.\\-]+)?\\](?::\\d{1,5})?";
    public static final List<String> DEFAULT_PATTERNS = List.of(
        IPV4,
        IPV6,
        "https://(?:ptb\\.|canary\\.)?discord(?:app)?\\.com/api/webhooks/[0-9]{17,20}/[A-Za-z0-9._\\-]+",
        "(?i)(?<=\"password\"\\s{0,4}:\\s{0,4}\")(?:[^\"\\\\]|\\\\.)+"
    );
    private static final String REPLACEMENT = "[REDACTED]";
    private static final Pattern REDACTED_PORT = Pattern.compile(Pattern.quote(REPLACEMENT) + ":\\d{1,5}(?!\\d)");
    private static final Pattern VERSION_PART = Pattern.compile(IPV4);
    private static final Set<String> JVM_VERSIONS = versionParts(List.of("java.version", "java.runtime.version",
        "java.vm.version", "java.vendor.version").stream().map(System::getProperty).toList());

    private final List<Pattern> patterns;
    private final Supplier<? extends Collection<String>> knownVersions;

    public TextRedactor(List<String> regexes) {
        this(regexes, List::of);
    }

    public TextRedactor(List<String> regexes, Supplier<? extends Collection<String>> knownVersions) {
        Objects.requireNonNull(regexes, "regexes");
        this.knownVersions = Objects.requireNonNull(knownVersions, "knownVersions");
        List<Pattern> compiled = new ArrayList<>(regexes.size());
        for (String regex : regexes) {
            if (regex == null || regex.isBlank()) {
                continue;
            }
            try {
                compiled.add(Pattern.compile(regex));
            } catch (PatternSyntaxException e) {
                LOGGER.log(System.Logger.Level.WARNING,
                    "Skipping invalid redact pattern: " + regex, e);
            }
        }
        this.patterns = List.copyOf(compiled);
    }

    public String redact(String text) {
        if (text == null || text.isEmpty() || patterns.isEmpty()) {
            return text;
        }
        String original = text;
        Set<String> keep = null;
        for (Pattern p : patterns) {
            Matcher matcher = p.matcher(text);
            if (!matcher.find()) {
                continue;
            }
            if (keep == null) {
                keep = new HashSet<>(JVM_VERSIONS);
                keep.addAll(versionParts(knownVersions.get()));
            }
            Set<String> kept = keep;
            text = matcher.replaceAll(match -> kept.contains(match.group())
                ? Matcher.quoteReplacement(match.group()) : REPLACEMENT);
        }
        return text.equals(original) ? text : REDACTED_PORT.matcher(text).replaceAll(REPLACEMENT);
    }

    private static Set<String> versionParts(Collection<String> versions) {
        Set<String> parts = new HashSet<>();
        for (String version : versions == null ? List.<String>of() : versions) {
            if (version != null) {
                VERSION_PART.matcher(version).results().forEach(part -> parts.add(part.group()));
            }
        }
        return parts;
    }

    public byte[] redact(byte[] data) {
        if (data == null || data.length == 0 || patterns.isEmpty()) {
            return data;
        }
        String text = new String(data, StandardCharsets.UTF_8);
        String redacted = redact(text);
        if (text.equals(redacted)) {
            return data;
        }
        return redacted.getBytes(StandardCharsets.UTF_8);
    }

    public boolean hasPatterns() {
        return !patterns.isEmpty();
    }
}
