package io.github.xytronix.hybox.hytale;

import io.github.xytronix.hybox.core.metrics.ChartAxis;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

final class HyboxChartImage {
    static final int WIDTH = 880, HEIGHT = 96, TOP = 6, BASELINE = 95;
    static final int LINE = 0xffd21f, INCIDENT = 0xf0626b, PROFILE = 0xece9e2, OFF = 0xa29d93;
    static final int[] STATES = {-1, 0x4cc38a, 0xa29d93, 0x6b665e, 0xf0626b};
    private static final int[] FLAME = {0xbdb8ad, 0xd9c48c, 0xffd21f, 0x8f8a81};
    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
    private static final int IHDR = 0x49484452, IDAT = 0x49444154, IEND = 0x49454e44;
    private static final int CACHE_BYTES = 1 << 20, CACHE_ENTRIES = 128;
    private static final ThreadLocal<HyboxChartImage> CANVAS = ThreadLocal.withInitial(HyboxChartImage::new);
    private static final Map<Key, byte[]> RENDERED = new LinkedHashMap<>(16, 0.75f, true);
    private static long renderedBytes;

    record FlameRect(int left, int top, int width, int height, int kind) {}

    private record Key(long a, long b, long c, long d) {}

    static String hex(int rgb) {
        return String.format(java.util.Locale.ROOT, "#%06x", rgb);
    }

    private int width, height;
    private float[] r = new float[0], g = r, b = r, a = r;
    private byte[] row = new byte[0];
    private byte[] packed = new byte[16384];
    private int packedLength;
    private final Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
    private final CRC32 crc = new CRC32();
    private final MessageDigest sha = sha256();
    private final ByteBuffer scratch = ByteBuffer.allocate(4096);

    private HyboxChartImage() {
    }

    static byte[] line(double[] values, ChartAxis.Axis axis, double marker, int markerRgb, boolean tint) {
        HyboxChartImage image = CANVAS.get();
        Key key = image.begin(1).mix(values).mix(axis).mix(marker).mix(markerRgb).mix(tint ? 1 : 0).key();
        byte[] cached = cached(key);
        if (cached != null) return cached;
        image.reset(WIDTH, HEIGHT);
        image.drawLine(values, axis, tint ? marker : -1);
        image.marker(marker, values.length, markerRgb);
        return remember(key, image.encode());
    }

    static byte[] line(double[] values, ChartAxis.Axis axis, double[] markers) {
        HyboxChartImage image = CANVAS.get();
        Key key = image.begin(2).mix(values).mix(axis).mix(markers).key();
        byte[] cached = cached(key);
        if (cached != null) return cached;
        image.reset(WIDTH, HEIGHT);
        image.drawLine(values, axis, -1);
        for (double marker : markers) image.marker(marker, values.length, INCIDENT);
        return remember(key, image.encode());
    }

    static byte[] trend(double[] mean, double[] min, double[] max, boolean[] breakBefore, ChartAxis.Axis axis,
                        double[] gaps, boolean collapsed, double[] markers) {
        HyboxChartImage image = CANVAS.get();
        Key key = image.begin(5).mix(mean).mix(min).mix(max).mix(breakBefore).mix(axis).mix(gaps)
            .mix(collapsed ? 1 : 0).mix(markers).key();
        byte[] cached = cached(key);
        if (cached != null) return cached;
        image.reset(WIDTH, HEIGHT);
        image.drawTrend(mean, min, max, breakBefore, axis, gaps, collapsed);
        for (double marker : markers) image.fractionMarker(marker, INCIDENT);
        return remember(key, image.encode());
    }

    static int lineRow(double value, ChartAxis.Axis axis) {
        double base = BASELINE + 0.5;
        return (int) Math.floor(base - (base - TOP) * axis.fraction(value));
    }

    static byte[] states(int[] states, int width, int height) {
        HyboxChartImage image = CANVAS.get();
        Key key = image.begin(3).mix(width).mix(height).mix(states).key();
        byte[] cached = cached(key);
        if (cached != null) return cached;
        image.reset(width, height);
        int n = states.length;
        int[] counts = new int[STATES.length];
        for (int x = 0; x < width && n > 0; x++) {
            int from = (int) ((long) x * n / width), to = Math.max(from + 1, (int) ((long) (x + 1) * n / width));
            Arrays.fill(counts, 0);
            for (int i = from; i < Math.min(n, to); i++) counts[Math.max(0, Math.min(STATES.length - 1, states[i]))]++;
            int state = 0;
            for (int candidate = 1; candidate < counts.length; candidate++) if (counts[candidate] > counts[state]) state = candidate;
            if (STATES[state] >= 0) image.span(x, 0, height, STATES[state], 1f);
        }
        return remember(key, image.encode());
    }

    static byte[] flame(List<FlameRect> frames, int width, int height) {
        HyboxChartImage image = CANVAS.get();
        image.begin(4).mix(width).mix(height).mix(frames.size());
        for (FlameRect frame : frames) image.mix(frame.left()).mix(frame.top()).mix(frame.width()).mix(frame.height()).mix(frame.kind());
        Key key = image.key();
        byte[] cached = cached(key);
        if (cached != null) return cached;
        image.reset(width, height);
        for (FlameRect frame : frames) {
            int kind = Math.max(0, Math.min(FLAME.length - 1, frame.kind()));
            for (int x = Math.max(0, frame.left()); x < Math.min(width, frame.left() + frame.width()); x++) {
                image.span(x, frame.top(), frame.top() + frame.height(), FLAME[kind], 1f);
            }
        }
        return remember(key, image.encode());
    }

    private static byte[] cached(Key key) {
        synchronized (RENDERED) {
            return RENDERED.get(key);
        }
    }

    private static byte[] remember(Key key, byte[] png) {
        if (png.length > CACHE_BYTES) return png;
        synchronized (RENDERED) {
            byte[] previous = RENDERED.put(key, png);
            renderedBytes += png.length - (previous == null ? 0 : previous.length);
            Iterator<byte[]> eldest = RENDERED.values().iterator();
            while ((renderedBytes > CACHE_BYTES || RENDERED.size() > CACHE_ENTRIES) && eldest.hasNext()) {
                renderedBytes -= eldest.next().length;
                eldest.remove();
            }
        }
        return png;
    }

    private HyboxChartImage begin(int kind) {
        sha.reset();
        scratch.clear();
        return mix(kind);
    }

    private HyboxChartImage mix(int value) {
        if (scratch.remaining() < Integer.BYTES) flush();
        scratch.putInt(value);
        return this;
    }

    private HyboxChartImage mix(double value) {
        if (scratch.remaining() < Double.BYTES) flush();
        scratch.putDouble(value);
        return this;
    }

    private HyboxChartImage mix(ChartAxis.Axis axis) {
        return mix(axis.top()).mix(axis.bottom()).mix(axis.log() ? 1 : 0);
    }

    private HyboxChartImage mix(double[] values) {
        mix(values.length);
        for (double value : values) mix(value);
        return this;
    }

    private HyboxChartImage mix(int[] values) {
        mix(values.length);
        for (int value : values) mix(value);
        return this;
    }

    private HyboxChartImage mix(boolean[] values) {
        mix(values.length);
        for (boolean value : values) mix(value ? 1 : 0);
        return this;
    }

    private void flush() {
        sha.update(scratch.array(), 0, scratch.position());
        scratch.clear();
    }

    private Key key() {
        flush();
        ByteBuffer digest = ByteBuffer.wrap(sha.digest());
        return new Key(digest.getLong(), digest.getLong(), digest.getLong(), digest.getLong());
    }

    private void reset(int width, int height) {
        int size = width * height;
        if (r.length < size) {
            r = new float[size]; g = new float[size]; b = new float[size]; a = new float[size];
        } else {
            Arrays.fill(r, 0, size, 0f); Arrays.fill(g, 0, size, 0f); Arrays.fill(b, 0, size, 0f); Arrays.fill(a, 0, size, 0f);
        }
        this.width = width;
        this.height = height;
    }

    private void drawLine(double[] values, ChartAxis.Axis axis, double tintFrom) {
        int n = values.length;
        double top = TOP, base = BASELINE + 0.5;
        double markerX = tintFrom >= 0 && n > 1 ? tintFrom * (width - 1) / (n - 1) : -1;
        if (markerX >= 0) for (int x = (int) Math.ceil(markerX); x < width; x++) span(x, top, base, INCIDENT, 0.07f);
        double previous = Double.NaN;
        int clipStart = -1;
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
            boolean clipped = axis.clipped(high);
            if (clipped && clipStart < 0) clipStart = x;
            if (!clipped && clipStart >= 0) { triangle((clipStart + x - 1) / 2.0, LINE); clipStart = -1; }
            if (Double.isNaN(centre) && low > high) { previous = Double.NaN; continue; }
            if (!Double.isNaN(centre)) { low = Math.min(low, centre); high = Math.max(high, centre); }
            double yHigh = base - (base - top) * axis.fraction(high);
            double yLow = base - (base - top) * axis.fraction(low);
            double yMid = Double.isNaN(centre) ? (yHigh + yLow) / 2 : base - (base - top) * axis.fraction(centre);
            span(x, yMid, base, LINE, 0.1f);
            double from = Math.min(yHigh, Double.isNaN(previous) ? yHigh : previous), to = Math.max(yLow, Double.isNaN(previous) ? yLow : previous);
            span(x, from - 0.75, to + 0.75, LINE, 1f);
            previous = yMid;
        }
        if (clipStart >= 0) triangle((clipStart + width - 1) / 2.0, LINE);
    }

    private void drawTrend(double[] mean, double[] min, double[] max, boolean[] breakBefore, ChartAxis.Axis axis,
                           double[] gaps, boolean collapsed) {
        int n = mean.length;
        double top = TOP, base = BASELINE + 0.5;
        for (int g = 0; g + 1 < gaps.length; g += 2) {
            double from = gaps[g] * (width - 1), to = gaps[g + 1] * (width - 1);
            for (int x = (int) Math.ceil(from); x <= (int) Math.floor(to); x++) span(x, top, base, OFF, 0.1f);
            if (collapsed) {
                int middle = (int) Math.round((from + to) / 2);
                for (int y = TOP; y <= BASELINE; y++) if ((y / 3) % 2 == 0) blend(middle, y, OFF, 0.6f);
            }
        }
        for (int b = 0; b < n; b++) {
            if (Double.isNaN(mean[b])) continue;
            int left = b * width / n, right = Math.max(left + 1, (b + 1) * width / n);
            double yMax = base - (base - top) * axis.fraction(max[b]);
            double yMin = base - (base - top) * axis.fraction(min[b]);
            for (int x = left; x < right; x++) span(x, yMax, yMin, LINE, 0.22f);
        }
        boolean[] linked = new boolean[n];
        int previous = -1;
        double previousX = 0, previousY = 0;
        for (int b = 0; b < n; b++) {
            if (Double.isNaN(mean[b])) continue;
            double x = (b + 0.5) * width / n;
            double y = base - (base - top) * axis.fraction(mean[b]);
            if (previous >= 0 && !breakBefore[b]) {
                segment(previousX, previousY, x, y);
                linked[previous] = true;
                linked[b] = true;
            }
            previous = b;
            previousX = x;
            previousY = y;
        }
        int runStart = -1, runEnd = -1;
        for (int b = 0; b < n; b++) {
            if (Double.isNaN(mean[b])) continue;
            double x = (b + 0.5) * width / n;
            if (!linked[b]) {
                double y = base - (base - top) * axis.fraction(mean[b]);
                for (int dx = -1; dx <= 1; dx++) span((int) Math.round(x) + dx, y - 1.5, y + 1.5, LINE, 1f);
            }
            boolean clipped = axis.clipped(max[b]);
            boolean joins = runStart >= 0 && !breakBefore[b];
            if (clipped && joins) {
                runEnd = b;
            } else {
                if (runStart >= 0) triangle(((runStart + runEnd) / 2.0 + 0.5) * width / n, LINE);
                runStart = clipped ? b : -1;
                runEnd = b;
            }
        }
        if (runStart >= 0) triangle(((runStart + runEnd) / 2.0 + 0.5) * width / n, LINE);
    }

    private void segment(double x0, double y0, double x1, double y1) {
        int from = (int) Math.floor(x0), to = (int) Math.ceil(x1);
        double previous = y0;
        for (int x = from; x <= to; x++) {
            double t = x1 > x0 ? Math.max(0, Math.min(1, (x - x0) / (x1 - x0))) : 1;
            double y = y0 + (y1 - y0) * t;
            span(x, Math.min(previous, y) - 0.75, Math.max(previous, y) + 0.75, LINE, 1f);
            previous = y;
        }
    }

    private void triangle(double centre, int rgb) {
        for (int row = 0; row <= 6; row++) {
            double half = row * 4.0 / 6;
            for (int x = (int) Math.round(centre - half); x <= (int) Math.round(centre + half); x++) blend(x, TOP - 4 + row, rgb, 1f);
        }
    }

    private void fractionMarker(double fraction, int rgb) {
        if (!Double.isFinite(fraction) || fraction < 0 || fraction > 1) return;
        int x = (int) Math.round(fraction * (width - 1));
        for (int y = TOP - 2; y <= BASELINE; y++) if ((y / 3) % 2 == 0) blend(x, y, rgb, 0.95f);
    }

    private void marker(double marker, int samples, int rgb) {
        if (!Double.isFinite(marker) || marker < 0 || marker > samples - 1 || samples < 2) return;
        int x = (int) Math.round(marker * (width - 1) / (samples - 1));
        for (int y = TOP - 2; y <= BASELINE; y++) if ((y / 3) % 2 == 0) blend(x, y, rgb, 0.95f);
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
        int rowBytes = width * 4 + 1;
        if (row.length < rowBytes) row = new byte[rowBytes];
        deflater.reset();
        packedLength = 0;
        for (int y = 0; y < height; y++) {
            int o = 0;
            row[o++] = 0;
            for (int x = 0; x < width; x++) {
                int p = y * width + x;
                row[o++] = (byte) Math.round(r[p]); row[o++] = (byte) Math.round(g[p]);
                row[o++] = (byte) Math.round(b[p]); row[o++] = (byte) Math.round(a[p] * 255);
            }
            deflater.setInput(row, 0, rowBytes);
            while (!deflater.needsInput()) deflate();
        }
        deflater.finish();
        while (!deflater.finished()) deflate();
        byte[] png = new byte[SIGNATURE.length + 12 + 13 + 12 + packedLength + 12];
        ByteBuffer out = ByteBuffer.wrap(png);
        out.put(SIGNATURE);
        int start = chunk(out, IHDR, 13);
        out.putInt(width).putInt(height).put((byte) 8).put((byte) 6).put((byte) 0).put((byte) 0).put((byte) 0);
        seal(out, start);
        start = chunk(out, IDAT, packedLength);
        out.put(packed, 0, packedLength);
        seal(out, start);
        seal(out, chunk(out, IEND, 0));
        return png;
    }

    private void deflate() {
        if (packed.length - packedLength < 1024) packed = Arrays.copyOf(packed, packed.length * 2);
        packedLength += deflater.deflate(packed, packedLength, packed.length - packedLength);
    }

    private static int chunk(ByteBuffer out, int type, int length) {
        out.putInt(length);
        int start = out.position();
        out.putInt(type);
        return start;
    }

    private void seal(ByteBuffer out, int start) {
        crc.reset();
        crc.update(out.array(), start, out.position() - start);
        out.putInt((int) crc.getValue());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
