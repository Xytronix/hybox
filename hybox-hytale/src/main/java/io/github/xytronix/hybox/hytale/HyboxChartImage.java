package io.github.xytronix.hybox.hytale;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

final class HyboxChartImage {
    static final int WIDTH = 880, HEIGHT = 96, TOP = 6, BASELINE = 95;
    static final int LINE = 0xffd21f, INCIDENT = 0xf0626b;
    static final int[] STATES = {-1, 0x4cc38a, 0xa29d93, 0x6b665e, 0xf0626b};
    private static final int[] FLAME = {0xbdb8ad, 0xd9c48c, 0xffd21f, 0x8f8a81};

    record FlameRect(int left, int top, int width, int height, int kind) {}

    static String hex(int rgb) {
        return String.format(java.util.Locale.ROOT, "#%06x", rgb);
    }

    private final int width, height;
    private final float[] r, g, b, a;

    private HyboxChartImage(int width, int height) {
        this.width = width;
        this.height = height;
        r = new float[width * height]; g = new float[width * height]; b = new float[width * height]; a = new float[width * height];
    }

    static byte[] line(double[] values, double scale, double marker) {
        HyboxChartImage image = new HyboxChartImage(WIDTH, HEIGHT);
        image.drawLine(values, scale > 0 ? scale : 1, marker);
        return image.encode();
    }

    static byte[] line(double[] values, double scale, double[] markers) {
        HyboxChartImage image = new HyboxChartImage(WIDTH, HEIGHT);
        image.drawLine(values, scale > 0 ? scale : 1, -1);
        for (double marker : markers) image.marker(marker, values.length);
        return image.encode();
    }

    static int lineRow(double value, double scale) {
        double base = BASELINE + 0.5;
        return (int) Math.floor(base - (base - TOP) * Math.max(0, Math.min(1, value / (scale > 0 ? scale : 1))));
    }

    static byte[] states(int[] states, int width, int height) {
        HyboxChartImage image = new HyboxChartImage(width, height);
        int n = states.length;
        int[] counts = new int[STATES.length];
        for (int x = 0; x < width && n > 0; x++) {
            int from = (int) ((long) x * n / width), to = Math.max(from + 1, (int) ((long) (x + 1) * n / width));
            java.util.Arrays.fill(counts, 0);
            for (int i = from; i < Math.min(n, to); i++) counts[Math.max(0, Math.min(STATES.length - 1, states[i]))]++;
            int state = 0;
            for (int candidate = 1; candidate < counts.length; candidate++) if (counts[candidate] > counts[state]) state = candidate;
            if (STATES[state] >= 0) image.span(x, 0, height, STATES[state], 1f);
        }
        return image.encode();
    }

    static byte[] flame(List<FlameRect> frames, int width, int height) {
        HyboxChartImage image = new HyboxChartImage(width, height);
        for (FlameRect frame : frames) {
            int kind = Math.max(0, Math.min(FLAME.length - 1, frame.kind()));
            for (int x = Math.max(0, frame.left()); x < Math.min(width, frame.left() + frame.width()); x++) {
                image.span(x, frame.top(), frame.top() + frame.height(), FLAME[kind], 1f);
            }
        }
        return image.encode();
    }

    private void drawLine(double[] values, double scale, double marker) {
        int n = values.length;
        double top = TOP, base = BASELINE + 0.5;
        double markerX = marker >= 0 && n > 1 ? marker * (width - 1) / (n - 1) : -1;
        if (markerX >= 0) for (int x = (int) Math.ceil(markerX); x < width; x++) span(x, top, base, INCIDENT, 0.07f);
        double previous = Double.NaN;
        for (int x = 0; x < width && n > 0; x++) {
            double start = n > 1 ? (x - 0.5) * (n - 1) / (width - 1) : 0, end = n > 1 ? (x + 0.5) * (n - 1) / (width - 1) : 0;
            int first = Math.max(0, (int) Math.ceil(start)), last = Math.min(n - 1, (int) Math.floor(end));
            double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY, centre = Double.NaN;
            for (int i = first; i <= last; i++) {
                if (Double.isNaN(values[i])) continue;
                low = Math.min(low, values[i]); high = Math.max(high, values[i]);
            }
            double position = n > 1 ? (double) x * (n - 1) / (width - 1) : 0;
            int i = Math.min(n - 1, (int) position), j = Math.min(n - 1, i + 1);
            if (!Double.isNaN(values[i]) && !Double.isNaN(values[j])) centre = values[i] + (values[j] - values[i]) * (position - i);
            if (Double.isNaN(centre) && low > high) { previous = Double.NaN; continue; }
            if (!Double.isNaN(centre)) { low = Math.min(low, centre); high = Math.max(high, centre); }
            double yHigh = base - (base - top) * Math.max(0, Math.min(1, high / scale));
            double yLow = base - (base - top) * Math.max(0, Math.min(1, low / scale));
            double yMid = Double.isNaN(centre) ? (yHigh + yLow) / 2 : base - (base - top) * Math.max(0, Math.min(1, centre / scale));
            span(x, yMid, base, LINE, 0.1f);
            double from = Math.min(yHigh, Double.isNaN(previous) ? yHigh : previous), to = Math.max(yLow, Double.isNaN(previous) ? yLow : previous);
            span(x, from - 0.75, to + 0.75, LINE, 1f);
            previous = yMid;
        }
        marker(marker, n);
    }

    private void marker(double marker, int samples) {
        if (!Double.isFinite(marker) || marker < 0 || marker > samples - 1 || samples < 2) return;
        int x = (int) Math.round(marker * (width - 1) / (samples - 1));
        for (int y = TOP - 2; y <= BASELINE; y++) if ((y / 3) % 2 == 0) blend(x, y, INCIDENT, 0.95f);
    }

    private void span(int x, double from, double to, int rgb, float alpha) {
        int start = Math.max(0, (int) Math.floor(from)), end = Math.min(height - 1, (int) Math.ceil(to));
        for (int y = start; y <= end; y++) {
            double coverage = Math.min(to, y + 1) - Math.max(from, y);
            if (coverage > 0) blend(x, y, rgb, (float) (alpha * Math.min(1, coverage)));
        }
    }

    private void blend(int x, int y, int rgb, float alpha) {
        if (x < 0 || y < 0 || x >= width || y >= height || alpha <= 0) return;
        int p = y * width + x;
        float out = alpha + a[p] * (1 - alpha);
        if (out <= 0) return;
        float keep = a[p] * (1 - alpha);
        r[p] = (((rgb >> 16) & 0xff) * alpha + r[p] * keep) / out;
        g[p] = (((rgb >> 8) & 0xff) * alpha + g[p] * keep) / out;
        b[p] = ((rgb & 0xff) * alpha + b[p] * keep) / out;
        a[p] = out;
    }

    private byte[] encode() {
        byte[] raw = new byte[height * (width * 4 + 1)];
        int o = 0;
        for (int y = 0; y < height; y++) {
            raw[o++] = 0;
            for (int x = 0; x < width; x++) {
                int p = y * width + x;
                raw[o++] = (byte) Math.round(r[p]); raw[o++] = (byte) Math.round(g[p]);
                raw[o++] = (byte) Math.round(b[p]); raw[o++] = (byte) Math.round(a[p] * 255);
            }
        }
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
        deflater.setInput(raw); deflater.finish();
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        while (!deflater.finished()) compressed.write(buffer, 0, deflater.deflate(buffer));
        deflater.end();
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        png.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
        chunk(png, "IHDR", ByteBuffer.allocate(13).putInt(width).putInt(height).put((byte) 8).put((byte) 6).put((byte) 0).put((byte) 0).put((byte) 0).array());
        chunk(png, "IDAT", compressed.toByteArray());
        chunk(png, "IEND", new byte[0]);
        return png.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32(); crc.update(name); crc.update(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
        out.writeBytes(name); out.writeBytes(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }
}
