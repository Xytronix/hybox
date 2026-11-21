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

    private static final String ADDRESS_SHAPE = "(?<!\\d)(?:\\d{1,3}\\.){3}\\d{1,3}(?!\\d)";
    public static final String IPV4 = "(?<![\\d.])(?<!(?i:\\bv|version|build|release)[ =:]?)"
        + "(?:\\d{1,3}\\.){3}\\d{1,3}(?!\\.?\\d)(?![+_]|-[A-Za-z])";
    private static final String HEX = "[0-9A-Fa-f]{1,4}";
    private static final String IPV6_ADDRESS = "(?:(?:" + HEX + ":){7}" + HEX
        + "|(?:" + HEX + ":){1,6}:" + HEX
        + "|(?:" + HEX + ":){1,5}(?::" + HEX + "){1,2}"
        + "|(?:" + HEX + ":){1,4}(?::" + HEX + "){1,3}"
        + "|(?:" + HEX + ":){1,3}(?::" + HEX + "){1,4}"
        + "|(?:" + HEX + ":){1,2}(?::" + HEX + "){1,5}"
        + "|" + HEX + ":(?::" + HEX + "){1,6}"
        + "|(?:" + HEX + ":){1,7}:"
        + "|:(?::" + HEX + "){1,7})(?:%[0-9A-Za-z_.\\-]+)?";
    public static final String IPV6 = "\\[" + IPV6_ADDRESS + "\\](?::\\d{1,5})?"
        + "|(?<![\\w:.%])" + IPV6_ADDRESS + "(?![\\w:])";
    private static final String LABEL = "[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?";
    private static final String TLD = "(?i:[a-z]{2}|com|net|org|info|biz|io|gg|xyz|app|dev|cloud|host|online|site"
        + "|club|games|network|tech|pro|ovh|fun|top|live|world|zone|link|store|one|svc|lan|internal|localdomain|home"
        + "|arpa)";
    private static final String SOURCE_FILE = "(?i:js|ts|kt|md|py|rs|go|sh|cs|rb|pl|ms|ns|gz|db|so|cc|el|ml|fs|ps)";
    public static final String HOST_PORT = "(?<=://)[A-Za-z0-9.-]+:\\d{1,5}(?!\\d)"
        + "|(?<![\\w.@-])(?:(?i:localhost)"
        + "|(?:" + LABEL + "\\.){2,}(?!(?i:ms|ns):)" + TLD
        + "|(?![A-Z])(?!" + LABEL + "\\." + SOURCE_FILE + ":)" + LABEL + "\\." + TLD + ")"
        + ":\\d{2,5}(?!\\d)";
    public static final String LOCAL_DOMAIN =
        "(?<![\\w.-])[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9-]+)*\\.local(?![\\w.-])";
    public static final List<String> NETWORK_PATTERNS = List.of(IPV4, IPV6, HOST_PORT, LOCAL_DOMAIN);
    private static final String SECRET_KEY = "(?:password|passwd|secret|token|api[-_]?key|credential)";
    private static final String SECRET_END = "(?=[)\\]}]*(?:[\\s\"',;&<>\\[\\\\]|$))";
    public static final List<String> DEFAULT_PATTERNS = List.of(
        IPV4,
        IPV6,
        HOST_PORT,
        LOCAL_DOMAIN,
        "https://(?:ptb\\.|canary\\.)?discord(?:app)?\\.com/api/(?:v\\d+/)?webhooks/[0-9]{17,20}/[A-Za-z0-9._\\-]+",
        "(?i)(?<=\"[\\w.-]{0,40}" + SECRET_KEY + "[\\w.-]{0,20}\"\\s{0,4}:\\s{0,4}\")(?:[^\"\\\\]|\\\\.)+",
        "(?i)(?<=" + SECRET_KEY + "[\\w.-]{0,20}\\s{0,2}[=:]\\s{0,2})(?!\\[REDACTED\\])(?!\\d{1,3}" + SECRET_END + ")"
            + "[^\\s\"',;&<>\\[\\\\]+?" + SECRET_END
    );
    static final List<String> PREVIOUS_DEFAULT_PATTERNS = List.of(
        "(?<!\\d)(?:\\d{1,3}\\.){3}\\d{1,3}(?!\\d)",
        "\\[(?:[0-9A-Fa-f]{0,4}:){2,7}[0-9A-Fa-f]{0,4}(?:%[0-9A-Za-z_.\\-]+)?\\](?::\\d{1,5})?",
        "https://(?:ptb\\.|canary\\.)?discord(?:app)?\\.com/api/webhooks/[0-9]{17,20}/[A-Za-z0-9._\\-]+",
        "(?i)(?<=\"password\"\\s{0,4}:\\s{0,4}\")(?:[^\"\\\\]|\\\\.)+"
    );
    private static final String REPLACEMENT = "[REDACTED]";
    private static final Pattern REDACTED_PORT = Pattern.compile(Pattern.quote(REPLACEMENT) + ":\\d{1,5}(?!\\d)");
    private static final Pattern VERSION_PART = Pattern.compile(ADDRESS_SHAPE);
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
        List<String> effective = regexes.equals(PREVIOUS_DEFAULT_PATTERNS) ? DEFAULT_PATTERNS : regexes;
        List<Pattern> compiled = new ArrayList<>(effective.size());
        for (String regex : effective) {
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
