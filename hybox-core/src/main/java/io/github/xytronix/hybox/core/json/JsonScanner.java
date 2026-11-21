package io.github.xytronix.hybox.core.json;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class JsonScanner {
    private final CharSequence text;
    private int pos;

    public JsonScanner(CharSequence text, int start) {
        this.text = text;
        this.pos = start;
    }

    public Map<String, Object> object(Set<String> keep) throws IOException {
        expect('{');
        Map<String, Object> out = new LinkedHashMap<>();
        if (peek() == '}') {
            pos++;
            return out;
        }
        while (true) {
            space();
            String key = string();
            expect(':');
            if (keep == null || keep.contains(key)) {
                out.put(key, value());
            } else {
                skip();
            }
            char next = next();
            if (next == '}') {
                return out;
            }
            if (next != ',') {
                throw error();
            }
        }
    }

    public Object value() throws IOException {
        char c = peek();
        return switch (c) {
            case '{' -> object(null);
            case '[' -> array();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private List<Object> array() throws IOException {
        expect('[');
        List<Object> out = new ArrayList<>();
        if (peek() == ']') {
            pos++;
            return out;
        }
        while (true) {
            out.add(value());
            char next = next();
            if (next == ']') {
                return out;
            }
            if (next != ',') {
                throw error();
            }
        }
    }

    private void skip() throws IOException {
        char c = peek();
        if (c == '"') {
            skipString();
            return;
        }
        if (c != '{' && c != '[') {
            while (pos < text.length() && ",}] \t\r\n".indexOf(text.charAt(pos)) < 0) {
                pos++;
            }
            return;
        }
        int depth = 0;
        while (pos < text.length()) {
            char d = text.charAt(pos);
            if (d == '"') {
                skipString();
                continue;
            }
            pos++;
            if (d == '{' || d == '[') {
                depth++;
            } else if ((d == '}' || d == ']') && --depth == 0) {
                return;
            }
        }
        throw error();
    }

    private void skipString() throws IOException {
        pos++;
        while (pos < text.length()) {
            char c = text.charAt(pos++);
            if (c == '\\') {
                pos++;
            } else if (c == '"') {
                return;
            }
        }
        throw error();
    }

    private String string() throws IOException {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (pos < text.length()) {
            char c = text.charAt(pos++);
            if (c == '"') {
                return out.toString();
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (pos >= text.length()) {
                break;
            }
            char e = text.charAt(pos++);
            switch (e) {
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    if (pos + 4 > text.length()) {
                        throw error();
                    }
                    try {
                        out.append((char) Integer.parseInt(text.subSequence(pos, pos + 4).toString(), 16));
                    } catch (NumberFormatException failure) {
                        throw error();
                    }
                    pos += 4;
                }
                default -> out.append(e);
            }
        }
        throw error();
    }

    private Object literal(String word, Object value) throws IOException {
        if (pos + word.length() > text.length() || !word.contentEquals(text.subSequence(pos, pos + word.length()))) {
            throw error();
        }
        pos += word.length();
        return value;
    }

    private Double number() throws IOException {
        int start = pos;
        while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) {
            pos++;
        }
        try {
            return Double.parseDouble(text.subSequence(start, pos).toString());
        } catch (NumberFormatException failure) {
            throw error();
        }
    }

    private void expect(char c) throws IOException {
        if (next() != c) {
            throw error();
        }
    }

    private char next() throws IOException {
        char c = peek();
        pos++;
        return c;
    }

    private char peek() throws IOException {
        space();
        if (pos >= text.length()) {
            throw error();
        }
        return text.charAt(pos);
    }

    private void space() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }
    }

    private IOException error() {
        return new IOException("Invalid JSON at offset " + pos);
    }
}
