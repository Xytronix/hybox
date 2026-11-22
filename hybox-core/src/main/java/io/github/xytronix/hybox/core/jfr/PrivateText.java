package io.github.xytronix.hybox.core.jfr;

import java.util.BitSet;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PrivateText {
    private static final int ANCHOR = 8;
    private static final int MAX_TOKEN = 64;
    private static final int MAX_SPAN = 4096;

    private record Step(int text, int masked) {
        int cost() {
            return text + masked;
        }
    }

    private record PublicText(String literal, Pattern pattern) {
    }

    private final List<UnaryOperator<String>> maskers;
    private final List<PublicText> publicTexts;

    PrivateText(List<UnaryOperator<String>> maskers, Collection<String> publicTexts) {
        this.maskers = List.copyOf(maskers);
        this.publicTexts = publicTexts.stream()
            .filter(text -> text != null && !text.isBlank())
            .distinct()
            .sorted(Comparator.comparingInt(String::length).reversed())
            .map(text -> new PublicText(text,
                Pattern.compile("(?<![\\d.])" + Pattern.quote(text) + "(?!\\d|\\.\\d)")))
            .toList();
    }

    BitSet find(String text) {
        BitSet hidden = null;
        for (UnaryOperator<String> masker : maskers) {
            String masked = masker.apply(text);
            if (masked != null && !masked.equals(text)) {
                if (hidden == null) {
                    hidden = new BitSet(text.length());
                }
                mark(text, masked, hidden);
            }
        }
        if (hidden != null) {
            widenToWords(text, hidden);
            for (PublicText known : publicTexts) {
                if (text.contains(known.literal())) {
                    Matcher matcher = known.pattern().matcher(text);
                    while (matcher.find()) {
                        hidden.clear(matcher.start(), matcher.end());
                    }
                }
            }
        }
        return hidden;
    }

    String withoutPublicTexts(String text) {
        for (PublicText known : publicTexts) {
            if (text.contains(known.literal())) {
                text = known.pattern().matcher(text).replaceAll("v");
            }
        }
        return text;
    }

    private static void mark(String text, String masked, BitSet hidden) {
        int textEnd = text.length();
        int maskedEnd = masked.length();
        while (textEnd > 0 && maskedEnd > 0 && text.charAt(textEnd - 1) == masked.charAt(maskedEnd - 1)) {
            textEnd--;
            maskedEnd--;
        }
        int i = 0;
        int j = 0;
        while (i < textEnd) {
            if (j < maskedEnd && text.charAt(i) == masked.charAt(j)) {
                i++;
                j++;
                continue;
            }
            Step step = resync(text, i, textEnd, masked, j, maskedEnd);
            hidden.set(i, i + step.text());
            i += step.text();
            j += step.masked();
        }
    }

    private static Step resync(String text, int i, int textEnd, String masked, int j, int maskedEnd) {
        Step best = new Step(textEnd - i, maskedEnd - j);
        int lastToken = Math.min(MAX_TOKEN, maskedEnd - j - ANCHOR);
        int lastStart = Math.min(textEnd - ANCHOR, i + MAX_SPAN);
        for (int dj = 0; dj <= lastToken && dj < best.cost(); dj++) {
            for (int di = dj == 0 ? 1 : 0; i + di <= lastStart && di + dj < best.cost(); di++) {
                if (text.regionMatches(i + di, masked, j + dj, ANCHOR)) {
                    best = new Step(di, dj);
                    break;
                }
            }
        }
        return best;
    }

    private static void widenToWords(String text, BitSet hidden) {
        int start = 0;
        while (start < text.length()) {
            if (!Character.isLetterOrDigit(text.charAt(start))) {
                start++;
                continue;
            }
            int end = start + 1;
            while (end < text.length() && Character.isLetterOrDigit(text.charAt(end))) {
                end++;
            }
            int first = hidden.nextSetBit(start);
            if (first >= 0 && first < end) {
                hidden.set(start, end);
            }
            start = end;
        }
    }
}
