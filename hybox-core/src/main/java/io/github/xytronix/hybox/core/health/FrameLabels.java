package io.github.xytronix.hybox.core.health;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FrameLabels {
    @FunctionalInterface
    public interface Source {
        String label(String className, String methodName);
    }

    private static final Source NONE = (className, methodName) -> null;
    private static final Pattern DUMP_FRAME = Pattern.compile("^\\s*at (?:[^\\s(]*/)?([\\w.$]+)\\.([^.\\s(]+)\\(.*\\)$");

    private static volatile Source source = NONE;

    private FrameLabels() {
    }

    public static void install(Source labels) {
        source = labels == null ? NONE : labels;
    }

    public static String label(String className, String methodName) {
        if (className == null || methodName == null || className.isEmpty() || methodName.isEmpty()) {
            return null;
        }
        String label = source.label(className, methodName);
        return label == null || label.isBlank() ? null : label;
    }

    public static String labelOf(String method) {
        int dot = method == null ? -1 : method.lastIndexOf('.');
        return dot <= 0 ? null : label(method.substring(0, dot), method.substring(dot + 1));
    }

    public static String suffix(StackTraceElement frame) {
        String label = label(frame.getClassName(), frame.getMethodName());
        return label == null ? "" : "  [" + label + "]";
    }

    public static String labelDump(String dump) {
        if (dump == null || source == NONE) {
            return dump;
        }
        String[] lines = dump.split("\n", -1);
        StringBuilder out = new StringBuilder(dump.length() + 256);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            String line = lines[i];
            out.append(line);
            Matcher frame = DUMP_FRAME.matcher(line);
            if (frame.matches()) {
                String label = label(frame.group(1), frame.group(2));
                if (label != null) {
                    out.append("  [").append(label).append(']');
                }
            }
        }
        return out.toString();
    }
}
