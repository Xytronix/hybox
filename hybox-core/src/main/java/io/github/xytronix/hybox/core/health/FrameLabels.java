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
    private static final String WRAPPED = "$mixinextras$wrapped$";
    private static final String BRIDGE = "mixinextras$bridge$";
    private static final String MIXIN_CODE = "Mixin code from ";
    private static final Pattern HANDLER = Pattern.compile("^(?:(?:handler|redirect|modify|args|constant|localvar"
        + "|wrapOperation|wrapMethod|modifyExpressionValue|modifyReceiver|modifyReturnValue|wrapWithCondition)"
        + "\\$[^$]+|md[0-9a-f]+)\\$([^$]+)\\$.+");

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

    public static String originalMethod(String methodName) {
        int wrapped = methodName == null ? -1 : methodName.indexOf(WRAPPED);
        if (wrapped <= 0) {
            return methodName;
        }
        String name = methodName.substring(0, wrapped);
        return name.startsWith(BRIDGE) && name.length() > BRIDGE.length() ? name.substring(BRIDGE.length()) : name;
    }

    public static String mixinCodeMod(String label) {
        if (label == null || !label.startsWith(MIXIN_CODE)) {
            return null;
        }
        String mods = label.substring(MIXIN_CODE.length());
        int open = mods.indexOf(" (");
        String name = (open < 0 ? mods : mods.substring(0, open)).trim();
        return name.isEmpty() ? null : name;
    }

    public static String mixinJar(String methodName) {
        if (methodName == null || methodName.indexOf('$') < 0) {
            return null;
        }
        Matcher handler = HANDLER.matcher(methodName);
        return handler.matches() ? handler.group(1) : null;
    }

    public static String display(String classMethod) {
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
        String label = labelOrOriginal(className, method, original);
        String shown = className + "." + original;
        return label == null ? shown : shown + ", " + label;
    }

    public static String suffix(StackTraceElement frame) {
        String note = note(frame.getClassName(), frame.getMethodName());
        return note == null ? "" : "  [" + note + "]";
    }

    private static String labelOrOriginal(String className, String method, String original) {
        String label = label(className, method);
        return label == null && !original.equals(method) ? label(className, original) : label;
    }

    private static String note(String className, String methodName) {
        String original = originalMethod(methodName);
        String label = labelOrOriginal(className, methodName, original);
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
                String note = note(frame.group(1), frame.group(2));
                if (note != null) {
                    out.append("  [").append(note).append(']');
                }
            }
        }
        return out.toString();
    }
}
