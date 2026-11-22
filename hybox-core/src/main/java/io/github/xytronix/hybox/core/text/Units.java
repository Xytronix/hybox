package io.github.xytronix.hybox.core.text;

import java.time.Duration;
import java.util.Locale;

public final class Units {
    private static final String[] BYTE_UNITS = {"KiB", "MiB", "GiB", "TiB"};

    private Units() {
    }

    public static String bytes(long bytes) {
        if (bytes < 0) {
            return "n/a";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        int unit = -1;
        do {
            value /= 1024.0;
            unit++;
        } while (value >= 1024 && unit < BYTE_UNITS.length - 1);
        String number = String.format(Locale.ROOT, value >= 100 ? "%.0f" : "%.1f", value);
        if (number.endsWith(".0")) {
            number = number.substring(0, number.length() - 2);
        }
        return number + " " + BYTE_UNITS[unit];
    }

    public static String number(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    public static String count(long count, String unit) {
        return number(count) + " " + unit + (count == 1 ? "" : "s");
    }

    public static String duration(Duration duration) {
        long seconds = duration.toSeconds();
        if (seconds != 0 && seconds % 86_400 == 0) {
            return count(seconds / 86_400, "day");
        }
        if (seconds != 0 && seconds % 3_600 == 0) {
            return count(seconds / 3_600, "hour");
        }
        if (seconds != 0 && seconds % 60 == 0) {
            return count(seconds / 60, "minute");
        }
        return count(seconds, "second");
    }

    public static String elapsed(long millis) {
        if (millis < 1000) {
            return Math.max(0, millis) + " ms";
        }
        if (millis < 60_000) {
            String seconds = String.format(Locale.ROOT, "%.1f", millis / 1000.0);
            return (seconds.endsWith(".0") ? seconds.substring(0, seconds.length() - 2) : seconds) + " s";
        }
        long seconds = Math.round(millis / 1000.0);
        if (seconds < 3_600) {
            return seconds / 60 + " min" + (seconds % 60 == 0 ? "" : " " + seconds % 60 + " s");
        }
        long minutes = Math.round(seconds / 60.0);
        return minutes / 60 + " h" + (minutes % 60 == 0 ? "" : " " + minutes % 60 + " min");
    }

    public static String percent(double part, double total) {
        if (!(total > 0)) {
            return "n/a";
        }
        return String.format(Locale.ROOT, "%.1f%%", 100.0 * part / total);
    }
}
