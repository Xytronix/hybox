package io.github.xytronix.hybox.core.env;

import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class StructuredText {
    private static final Pattern HTML_MARKUP = Pattern.compile("<[^>]*>|&#?[A-Za-z0-9]+;");
    private static final Pattern ATTRIBUTE_VALUE = Pattern.compile("(?<=\\s)(title|alt|placeholder|aria-label"
        + "|data-tip)=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern RAW_ELEMENT = Pattern.compile("<(script|style)\\b[^>]*>.*?</\\1\\s*>",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private StructuredText() {
    }

    public static String json(String json, UnaryOperator<String> values) {
        if (json == null || json.isEmpty()) {
            return json;
        }
        StringBuilder out = null;
        int copied = 0;
        int i = 0;
        while (i < json.length()) {
            if (json.charAt(i) != '"') {
                i++;
                continue;
            }
            int end = stringEnd(json, i);
            if (end < 0) {
                break;
            }
            if (!isKey(json, end)) {
                String value = decode(json, i + 1, end - 1);
                String masked = values.apply(value);
                if (masked != null && !masked.equals(value)) {
                    if (out == null) {
                        out = new StringBuilder(json.length());
                    }
                    out.append(json, copied, i);
                    encode(masked, out);
                    copied = end;
                }
            }
            i = end;
        }
        if (out == null) {
            return json;
        }
        return out.append(json, copied, json.length()).toString();
    }

    public static void jsonValues(String json, Consumer<String> visitor) {
        if (json == null) {
            return;
        }
        int i = 0;
        while (i < json.length()) {
            if (json.charAt(i) != '"') {
                i++;
                continue;
            }
            int end = stringEnd(json, i);
            if (end < 0) {
                return;
            }
            if (!isKey(json, end)) {
                visitor.accept(decode(json, i + 1, end - 1));
            }
            i = end;
        }
    }

    public static int jsonEnd(String text, int start) {
        int depth = 0;
        int i = start;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '"') {
                i = stringEnd(text, i);
                if (i < 0) {
                    return -1;
                }
                continue;
            }
            i++;
            if (c == '{' || c == '[') {
                depth++;
            } else if ((c == '}' || c == ']') && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    public static String html(String html, UnaryOperator<String> text) {
        if (html == null || html.isEmpty()) {
            return html;
        }
        StringBuilder out = new StringBuilder(html.length());
        Matcher raw = RAW_ELEMENT.matcher(html);
        int at = 0;
        while (raw.find()) {
            markup(html.substring(at, raw.start()), text, out);
            out.append(raw.group());
            at = raw.end();
        }
        markup(html.substring(at), text, out);
        return out.toString();
    }

    public static boolean isJson(String part) {
        for (int i = 0; part != null && i < part.length(); i++) {
            char c = part.charAt(i);
            if (!Character.isWhitespace(c)) {
                return c == '{' || c == '[';
            }
        }
        return false;
    }

    private static void markup(String html, UnaryOperator<String> text, StringBuilder out) {
        Matcher markup = HTML_MARKUP.matcher(html);
        int at = 0;
        while (markup.find()) {
            out.append(text.apply(html.substring(at, markup.start())));
            String token = markup.group();
            out.append(token.startsWith("<") ? attributes(token, text) : token);
            at = markup.end();
        }
        out.append(text.apply(html.substring(at)));
    }

    private static String attributes(String tag, UnaryOperator<String> text) {
        Matcher value = ATTRIBUTE_VALUE.matcher(tag);
        if (!value.find()) {
            return tag;
        }
        StringBuilder out = new StringBuilder(tag.length());
        do {
            StringBuilder inner = new StringBuilder();
            markup(value.group(2), text, inner);
            value.appendReplacement(out, Matcher.quoteReplacement(value.group(1) + "=\"" + inner + "\""));
        } while (value.find());
        value.appendTail(out);
        return out.toString();
    }

    private static int stringEnd(String json, int open) {
        int i = open + 1;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '\\') {
                i += 2;
            } else if (c == '"') {
                return i + 1;
            } else {
                i++;
            }
        }
        return -1;
    }

    private static boolean isKey(String json, int after) {
        int i = after;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
            i++;
        }
        return i < json.length() && json.charAt(i) == ':';
    }

    private static String decode(String json, int from, int to) {
        if (json.indexOf('\\', from) < 0 || json.indexOf('\\', from) >= to) {
            return json.substring(from, to);
        }
        StringBuilder out = new StringBuilder(to - from);
        int i = from;
        while (i < to) {
            char c = json.charAt(i++);
            if (c != '\\' || i >= to) {
                out.append(c);
                continue;
            }
            char e = json.charAt(i++);
            switch (e) {
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    if (i + 4 <= to) {
                        try {
                            out.append((char) Integer.parseInt(json.substring(i, i + 4), 16));
                            i += 4;
                        } catch (NumberFormatException invalid) {
                            out.append("\\u");
                        }
                    } else {
                        out.append("\\u");
                    }
                }
                default -> out.append(e);
            }
        }
        return out.toString();
    }

    private static void encode(String value, StringBuilder out) {
        out.append('"');
        char previous = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '/' -> out.append(previous == '<' ? "\\/" : "/");
                default -> {
                    if (c <= 0x1F || c == 0x2028 || c == 0x2029) {
                        out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
            previous = c;
        }
        out.append('"');
    }
}
