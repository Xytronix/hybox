package io.github.xytronix.hybox.core.jfr;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class StringPatcher {
    private static final int UTF8 = 3;
    private static final int CHAR_ARRAY = 4;
    private static final int LATIN1 = 5;
    private static final byte MASK = '*';
    private static final int MAX_HEADER = 6;
    private static final int BUFFER = 1 << 20;

    private record Target(String text, byte[] original, byte[] masked) {
    }

    private final Map<Long, List<Target>> targets = new HashMap<>();
    private final BitSet sizes = new BitSet();
    private final Set<String> found = new HashSet<>();
    private int longest;

    private StringPatcher(Map<String, BitSet> hidden) {
        hidden.forEach((text, chars) -> {
            if (!chars.isEmpty()) {
                addUtf8(text, chars);
                addLatin1(text, chars);
                addCharArray(text, chars);
            }
        });
    }

    static Set<String> patch(Map<String, BitSet> hidden, Path source, Path target) throws IOException {
        StringPatcher patcher = new StringPatcher(hidden);
        patcher.copy(source, target);
        return patcher.found;
    }

    private void copy(Path source, Path target) throws IOException {
        int keep = longest + MAX_HEADER;
        byte[] buffer = new byte[Math.max(BUFFER, 2 * keep)];
        try (InputStream in = Files.newInputStream(source); OutputStream out = Files.newOutputStream(target)) {
            int length = 0;
            int position = 0;
            while (true) {
                length += in.readNBytes(buffer, length, buffer.length - length);
                boolean end = length < buffer.length;
                int limit = end ? length : length - keep;
                while (position < limit) {
                    position = match(buffer, position, length);
                }
                if (end) {
                    out.write(buffer, 0, length);
                    return;
                }
                out.write(buffer, 0, limit);
                System.arraycopy(buffer, limit, buffer, 0, length - limit);
                length -= limit;
                position -= limit;
            }
        }
    }

    private int match(byte[] buffer, int position, int length) {
        int encoding = buffer[position];
        if (encoding < UTF8 || encoding > LATIN1) {
            return position + 1;
        }
        int at = position + 1;
        long size = 0;
        for (int shift = 0; ; shift += 7) {
            if (at >= length || shift > 28) {
                return position + 1;
            }
            byte b = buffer[at++];
            size |= (long) (b & 0x7F) << shift;
            if (b >= 0) {
                break;
            }
        }
        if (size > longest || !sizes.get((int) size)) {
            return position + 1;
        }
        List<Target> candidates = targets.get(key(encoding, (int) size));
        if (candidates == null) {
            return position + 1;
        }
        for (Target target : candidates) {
            int n = target.original().length;
            if (at + n <= length && Arrays.equals(buffer, at, at + n, target.original(), 0, n)) {
                System.arraycopy(target.masked(), 0, buffer, at, n);
                found.add(target.text());
                return at + n;
            }
        }
        return position + 1;
    }

    private void addUtf8(String text, BitSet chars) {
        byte[] original = text.getBytes(StandardCharsets.UTF_8);
        byte[] masked = original.clone();
        int at = 0;
        for (int i = 0; i < text.length(); ) {
            int count = Character.charCount(text.codePointAt(i));
            int bytes = text.charAt(i) < 0x80 ? 1
                : text.substring(i, i + count).getBytes(StandardCharsets.UTF_8).length;
            int next = chars.nextSetBit(i);
            if (next >= 0 && next < i + count) {
                Arrays.fill(masked, at, at + bytes, MASK);
            }
            at += bytes;
            i += count;
        }
        if (at == original.length) {
            add(UTF8, original.length, text, original, masked);
        }
    }

    private void addLatin1(String text, BitSet chars) {
        if (!StandardCharsets.ISO_8859_1.newEncoder().canEncode(text)) {
            return;
        }
        byte[] original = text.getBytes(StandardCharsets.ISO_8859_1);
        byte[] masked = original.clone();
        for (int i = chars.nextSetBit(0); i >= 0 && i < masked.length; i = chars.nextSetBit(i + 1)) {
            masked[i] = MASK;
        }
        add(LATIN1, original.length, text, original, masked);
    }

    private void addCharArray(String text, BitSet chars) {
        ByteArrayOutputStream original = new ByteArrayOutputStream(text.length());
        ByteArrayOutputStream masked = new ByteArrayOutputStream(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            writeChar(original, c);
            writeChar(masked, chars.get(i) ? maskOfSameWidth(c) : c);
        }
        add(CHAR_ARRAY, text.length(), text, original.toByteArray(), masked.toByteArray());
    }

    private void add(int encoding, int size, String text, byte[] original, byte[] masked) {
        targets.computeIfAbsent(key(encoding, size), k -> new ArrayList<>(1)).add(new Target(text, original, masked));
        sizes.set(size);
        longest = Math.max(longest, Math.max(size, original.length));
    }

    private static char maskOfSameWidth(char c) {
        if (c < 0x80) {
            return '*';
        }
        return c < 0x4000 ? '\u2217' : '\uFF0A';
    }

    private static void writeChar(ByteArrayOutputStream out, char c) {
        if (c < 0x80) {
            out.write(c);
        } else if (c < 0x4000) {
            out.write(c & 0x7F | 0x80);
            out.write(c >>> 7);
        } else {
            out.write(c & 0x7F | 0x80);
            out.write(c >>> 7 & 0x7F | 0x80);
            out.write(c >>> 14);
        }
    }

    private static long key(int encoding, int size) {
        return (long) encoding << 32 | size;
    }
}
