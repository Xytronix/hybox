package io.github.xytronix.hybox.core.env;

import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PathMasker implements UnaryOperator<String> {
    private static final String PATH_START = "(?:(?<![^\\s\"'=(:,\\[>|;{`@])|(?<=file://)|(?<=-[IL]))";
    private static final String SEGMENT_END = "(?=[/\\\\\\s\"'<>|,;:)\\]}`]|$)";
    private static final String SEPARATOR = "(?:/|\\\\{1,2})";
    private static final String HOME_WORD = "[^/\\\\\\s\"'<>|:,;)\\]}`]+";
    private static final String HOME_NAME = "(?:" + HOME_WORD + "(?: " + HOME_WORD + ")+(?=" + SEPARATOR + ")|"
        + HOME_WORD + ")";
    private static final Pattern ANY_HOME = Pattern.compile(PATH_START
        + "(?:(?:/mnt/[a-z])?/(?:Users|home|var/home|export/home)/" + HOME_NAME + "|/root"
        + "|[a-z]:" + SEPARATOR + "(?:Users|Documents and Settings)" + SEPARATOR + HOME_NAME + ")"
        + SEGMENT_END, Pattern.CASE_INSENSITIVE);
    private static final String SEGMENT = "[\\w.@+-]+";
    private static final String SPACED_SEGMENT = SEGMENT + "(?: " + SEGMENT + ")*";
    private static final Pattern ABSOLUTE = Pattern.compile(PATH_START
        + "(?:(?:[A-Za-z]:" + SEPARATOR + "|\\\\{2,4}(?=\\w)|/(?=(?i:Volumes|media|mnt|run/media)/))(?:"
        + SPACED_SEGMENT + SEPARATOR + ")+"
        + "|/(?:" + SEGMENT + SEPARATOR + ")+)([\\w.@+-]*)");

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
            alternatives = Pattern.quote(trimmed.replace("\\", "\\\\")) + "|" + Pattern.quote(trimmed.replace('\\', '/'))
                + "|" + alternatives;
        }
        return Pattern.compile(PATH_START + "(?:" + alternatives + ")" + SEGMENT_END,
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }
}
