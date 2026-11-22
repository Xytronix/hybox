package io.github.xytronix.hybox.core.env;

import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PathMasker implements UnaryOperator<String> {
    private static final String PATH_START = "(?:(?<![^\\s\"'=(:,\\[>|;])|(?<=file://))";
    private static final String SEGMENT_END = "(?=[/\\\\\\s\"'<>|,;:)\\]]|$)";
    private static final String SEPARATOR = "(?:/|\\\\{1,2})";
    private static final Pattern ANY_HOME = Pattern.compile(PATH_START
        + "(?:/(?:Users|home|var/home|export/home)/[^/\\\\\\s\"'<>|:]+|/root"
        + "|[A-Za-z]:" + SEPARATOR + "(?:Users|Documents and Settings)" + SEPARATOR + "[^/\\\\\\s\"'<>|:]+)"
        + SEGMENT_END);
    private static final Pattern ABSOLUTE = Pattern.compile(PATH_START
        + "(?:/|[A-Za-z]:" + SEPARATOR + ")(?:[\\w.@+-]+" + SEPARATOR + ")+([\\w.@+-]*)");

    private final Pattern serverDir;
    private final Pattern home;

    public PathMasker(String home, String serverDir) {
        this.home = exact(home);
        this.serverDir = exact(serverDir);
    }

    public static PathMasker local() {
        return new PathMasker(System.getProperty("user.home"), System.getProperty("user.dir"));
    }

    @Override
    public String apply(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        if (serverDir != null) {
            text = serverDir.matcher(text).replaceAll(".");
        }
        if (home != null) {
            text = home.matcher(text).replaceAll("~");
        }
        text = ANY_HOME.matcher(text).replaceAll("~");
        Matcher absolute = ABSOLUTE.matcher(text);
        if (!absolute.find()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        do {
            String name = absolute.group(1);
            absolute.appendReplacement(out, Matcher.quoteReplacement(name.isEmpty() ? "..." : ".../" + name));
        } while (absolute.find());
        absolute.appendTail(out);
        return out.toString();
    }

    private static Pattern exact(String dir) {
        if (dir == null) {
            return null;
        }
        String trimmed = dir.strip();
        while (trimmed.length() > 1 && (trimmed.endsWith("/") || trimmed.endsWith("\\"))) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.length() < 3 || trimmed.matches("[A-Za-z]:")) {
            return null;
        }
        String alternatives = Pattern.quote(trimmed);
        if (trimmed.indexOf('\\') >= 0) {
            alternatives = Pattern.quote(trimmed.replace("\\", "\\\\")) + "|" + alternatives;
        }
        return Pattern.compile(PATH_START + "(?:" + alternatives + ")" + SEGMENT_END);
    }
}
