package io.github.xytronix.hybox.core.health;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FrameLabels {
    public record Origin(String mod, String jar, List<String> mixins) {}

    public record Label(boolean merged, List<Origin> origins, String text) {
        public Label(boolean merged, List<Origin> origins) {
            this(merged, List.copyOf(origins), text(merged, origins));
        }

        private static String text(boolean merged, List<Origin> origins) {
            List<String> parts = new ArrayList<>(origins.size());
            for (Origin origin : origins) {
                parts.add(origin.mod() + " (" + String.join(", ", origin.mixins()) + ")");
            }
            return (merged ? MIXIN_CODE : CHANGED_BY) + String.join(", ", parts);
        }
    }

    @FunctionalInterface
    public interface Source {
        Label label(String className, String methodName, String descriptor, int line);
    }

    private static final Source NONE = (className, methodName, descriptor, line) -> null;
    private static final Pattern DUMP_FRAME =
        Pattern.compile("^\\s*at (?:[^\\s(]*/)?([\\w.$]+)\\.([^.\\s(]+)\\(.*?(?::(\\d+))?\\)$");
    private static final String WRAPPED = "$mixinextras$wrapped$";
    private static final String BRIDGE = "mixinextras$bridge$";
    private static final String MIXIN_CODE = "Mixin code from ";
    private static final String CHANGED_BY = "changed by Mixin from ";
    private static final int SOURCE_ID_LETTERS = 12;
    private static final Pattern HANDLER = Pattern.compile("^(?:(?:handler|redirect|modify|args|constant|localvar"
        + "|wrapOperation|wrapMethod|modifyExpressionValue|modifyReceiver|modifyReturnValue|wrapWithCondition)"
        + "\\$[^$]+|md[0-9a-f]+)\\$([^$]+)\\$.+");

    private static volatile Source source = NONE;

    private FrameLabels() {
    }

    public static void install(Source labels) {
        source = labels == null ? NONE : labels;
    }

    public static Label find(String className, String methodName, String descriptor, int line) {
        if (className == null || methodName == null || className.isEmpty() || methodName.isEmpty()) {
            return null;
        }
        Label label = source.label(className, methodName, descriptor, line);
        return label == null || label.origins().isEmpty() ? null : label;
    }

    private static String label(String className, String methodName, int line) {
        Label label = find(className, methodName, null, line);
        return label == null ? null : label.text();
    }

    public static String labelOf(String method) {
        int dot = method == null ? -1 : method.lastIndexOf('.');
        return dot <= 0 ? null : label(method.substring(0, dot), method.substring(dot + 1), 0);
    }

    public static String originalMethod(String methodName) {
        int wrapped = methodName == null ? -1 : methodName.indexOf(WRAPPED);
        if (wrapped <= 0) {
            return methodName;
        }
        String name = methodName.substring(0, wrapped);
        return name.startsWith(BRIDGE) && name.length() > BRIDGE.length() ? name.substring(BRIDGE.length()) : name;
    }

    public static String mixinJar(String methodName) {
        if (methodName == null || methodName.indexOf('$') < 0) {
            return null;
        }
        Matcher handler = HANDLER.matcher(methodName);
        return handler.matches() ? handler.group(1) : null;
    }

    public static boolean mixinCode(Label label, String methodName) {
        return label != null && label.merged() || mixinJar(methodName) != null;
    }

    public static Origin mergedBy(Label label, String methodName) {
        if (label == null || !label.merged()) {
            return null;
        }
        List<Origin> origins = label.origins();
        if (origins.size() == 1) {
            return origins.get(0);
        }
        String id = mixinJar(methodName);
        Origin found = null;
        for (Origin origin : origins) {
            if (id != null && id.equals(sourceId(origin.jar()))) {
                if (found != null) {
                    return null;
                }
                found = origin;
            }
        }
        return found;
    }

    public static String sourceId(String jar) {
        if (jar == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(SOURCE_ID_LETTERS);
        for (int i = 0; i < jar.length() && out.length() < SOURCE_ID_LETTERS; i++) {
            char c = jar.charAt(i);
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z') {
                out.append(c);
            }
        }
        return out.toString();
    }

    public static boolean resumedOriginal(String methodName) {
        return methodName != null && (methodName.contains(WRAPPED) || methodName.startsWith(BRIDGE));
    }

    public static String display(String classMethod) {
        return display(classMethod, 0);
    }

    public static String display(String classMethod, int line) {
        if (classMethod == null) {
            return null;
        }
        String plain = classMethod.substring(classMethod.lastIndexOf('/') + 1);
        int dot = plain.lastIndexOf('.');
        if (dot <= 0) {
            return plain;
        }
        String className = plain.substring(0, dot);
        String method = plain.substring(dot + 1);
        String original = originalMethod(method);
        String label = labelOrOriginal(className, method, original, line);
        String shown = className + "." + original;
        return label == null ? shown : shown + ", " + label;
    }

    public static String suffix(StackTraceElement frame) {
        String note = note(frame.getClassName(), frame.getMethodName(), frame.getLineNumber());
        return note == null ? "" : "  [" + note + "]";
    }

    private static String labelOrOriginal(String className, String method, String original, int line) {
        String label = label(className, method, line);
        return label == null && !original.equals(method) ? label(className, original, 0) : label;
    }

    private static String note(String className, String methodName, int line) {
        String original = originalMethod(methodName);
        String label = labelOrOriginal(className, methodName, original, line);
        if (original == null || original.equals(methodName)) {
            return label;
        }
        String shown = className.substring(className.lastIndexOf('.') + 1) + "." + original;
        return label == null ? shown : shown + ", " + label;
    }

    public static String labelDump(String dump) {
        if (dump == null || (source == NONE && !dump.contains(WRAPPED))) {
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
                String note = note(frame.group(1), frame.group(2),
                    frame.group(3) == null ? 0 : Integer.parseInt(frame.group(3)));
                if (note != null) {
                    out.append("  [").append(note).append(']');
                }
            }
        }
        return out.toString();
    }
}
