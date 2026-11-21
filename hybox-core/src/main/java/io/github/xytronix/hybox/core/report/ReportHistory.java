package io.github.xytronix.hybox.core.report;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import io.github.xytronix.hybox.core.health.JfrTimeline;
import io.github.xytronix.hybox.core.incident.IncidentMetadata;
import io.github.xytronix.hybox.core.incident.IncidentReport;
import io.github.xytronix.hybox.core.json.JsonScanner;
import io.github.xytronix.hybox.core.json.JsonWriter;
import io.github.xytronix.hybox.core.metrics.MetricsLog;
import io.github.xytronix.hybox.core.trigger.TriggerNames;

public final class ReportHistory {
    static final int DAYS = 7;
    static final int MONTH = 30;
    static final int MIN_DAYS = 3;
    static final double MARK = 1.25;
    static final double FAR = 2;
    private static final long HOUR_MS = 3_600_000L;
    private static final long DAY_MS = 24 * HOUR_MS;
    private static final long HALF_WINDOW_MS = 30 * 60_000L;
    private static final int MIN_DAY_ROWS = 10;
    private static final int MIN_VALUES = 30;
    private static final int MAX_BUNDLES = 200;
    private static final int MAX_REPORTS = 20;
    private static final int MAX_META_BYTES = 64 * 1024;
    private static final int MAX_REPORT_BYTES = 16 * 1024 * 1024;
    private static final int SHOWN = 5;
    private static final double GIB = 1024.0 * 1024 * 1024;
    private static final Pattern DATA = Pattern.compile("\\bconst\\s+D\\s*=\\s*");
    private static final Set<String> WORLD_TRIGGERS = Set.of("HEARTBEAT_STALL", "TICK_DEGRADED", "WORLD_FAILURE");
    private static final DateTimeFormatter WHEN =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter CLOCK =
        DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).withZone(ZoneOffset.UTC);

    enum Metric {
        MSPT("mspt", "Tick time", "ms", 1, 1, 1),
        TPS("tps", "TPS", "", 1, -1, 0),
        PLAYERS("players", "Players", "", 0, 0, 0),
        CPU("cpuJvm", "Server CPU", "%", 1, 1, 1),
        HEAP("heap", "Heap used", "GiB", 2, 1, 0.1),
        RSS("rss", "Server memory (RSS)", "GiB", 2, 1, 0.1),
        GC("gcTime", "GC time", "%", 1, 1, 0.5);

        final String key;
        final String label;
        final String unit;
        final int digits;
        final int direction;
        final double floor;

        Metric(String key, String label, String unit, int digits, int direction, double floor) {
            this.key = key;
            this.label = label;
            this.unit = unit;
            this.digits = digits;
            this.direction = direction;
            this.floor = floor;
        }
    }

    record Stat(Metric metric, double median, double low, double high, int samples) {}
    record Usual(boolean off, int days, int hours, List<Stat> stats) {}
    record Earlier(Instant at, String trigger, String world, String mod, String method, boolean read) {}
    record Row(Stat stat, Double incident, String flag, boolean far, double severity) {}

    private static final ReportHistory NONE = new ReportHistory(null, null, null, 0);

    private final Instant at;
    private final Usual usual;
    private final List<Earlier> similar;
    private final int checked;

    private ReportHistory(Instant at, Usual usual, List<Earlier> similar, int checked) {
        this.at = at;
        this.usual = usual;
        this.similar = similar;
        this.checked = checked;
    }

    public static ReportHistory none() {
        return NONE;
    }

    public static ReportHistory load(Path metricsDir, boolean metricsEnabled, Path incidentDir, String ownFile,
                                     IncidentMetadata meta) {
        Instant at = meta.createdAt();
        Usual usual = metricsDir == null ? null
            : usual(new MetricsLog(metricsDir).read(at, DAYS + 1), metricsEnabled, at);
        if (incidentDir == null || "MANUAL".equals(meta.trigger()) || !Files.isDirectory(incidentDir)) {
            return new ReportHistory(at, usual, null, 0);
        }
        List<Path> bundles;
        try (Stream<Path> files = Files.list(incidentDir)) {
            bundles = files
                .filter(p -> {
                    String name = p.getFileName().toString();
                    return name.endsWith(".zip") && !name.equals(ownFile);
                })
                .filter(Files::isRegularFile)
                .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                .limit(MAX_BUNDLES)
                .toList();
        } catch (IOException e) {
            bundles = List.of();
        }
        long from = at.toEpochMilli() - MONTH * DAY_MS;
        List<Earlier> similar = new ArrayList<>();
        int checked = 0;
        int reads = 0;
        for (Path bundle : bundles) {
            try (ZipFile zip = new ZipFile(bundle.toFile(), StandardCharsets.UTF_8)) {
                Map<String, Object> older = map(read(zip, "incident.json", MAX_META_BYTES, Set.of("meta"), 0).get("meta"));
                if (older.isEmpty()) {
                    continue;
                }
                checked++;
                Instant when = instant(older.get("createdAt"));
                String trigger = string(older.get("trigger"));
                String world = string(older.get("world"));
                if (when == null || trigger == null || "MANUAL".equals(trigger) || !when.isBefore(at)
                    || when.toEpochMilli() < from
                    || !(trigger.equals(meta.trigger()) || sameWorld(meta.trigger(), meta.world(), trigger, world))) {
                    continue;
                }
                String[] top = null;
                if (reads++ < MAX_REPORTS) {
                    try {
                        top = olderTop(zip);
                    } catch (IOException | RuntimeException e) {
                        top = null;
                    }
                }
                similar.add(new Earlier(when, trigger, world, top == null ? null : top[0], top == null ? null : top[1],
                    top != null));
            } catch (IOException | RuntimeException e) {
                continue;
            }
        }
        similar.sort(Comparator.comparing(Earlier::at).reversed());
        return new ReportHistory(at, usual, List.copyOf(similar), checked);
    }

    static Usual usual(MetricsLog.Trend trend, boolean enabled, Instant at) {
        long atMs = at.toEpochMilli();
        long from = atMs - DAYS * DAY_MS;
        long[] times = trend.timestamps();
        int[] dayOf = new int[times.length];
        int[] dayRows = new int[DAYS + 1];
        Set<Long> hours = new HashSet<>();
        for (int i = 0; i < times.length; i++) {
            long t = times[i];
            if (t < from || t >= atMs) {
                continue;
            }
            hours.add(t / HOUR_MS);
            long back = atMs - t;
            long day = Math.round(back / (double) DAY_MS);
            if (day >= 1 && day <= DAYS && Math.abs(back - day * DAY_MS) <= HALF_WINDOW_MS) {
                dayOf[i] = (int) day;
                dayRows[(int) day]++;
            }
        }
        int days = 0;
        for (int day = 1; day <= DAYS; day++) {
            if (dayRows[day] >= MIN_DAY_ROWS) {
                days++;
            }
        }
        List<Stat> stats = new ArrayList<>();
        if (days >= MIN_DAYS) {
            int[] rows = new int[times.length];
            int counted = 0;
            for (int i = 0; i < times.length; i++) {
                if (dayOf[i] > 0 && dayRows[dayOf[i]] >= MIN_DAY_ROWS) {
                    rows[counted++] = i;
                }
            }
            double[] values = new double[counted];
            for (Metric metric : Metric.values()) {
                int n = 0;
                for (int r = 0; r < counted; r++) {
                    double value = historyValue(metric, trend, rows[r]);
                    if (Double.isFinite(value)) {
                        values[n++] = value;
                    }
                }
                if (n >= MIN_VALUES) {
                    Arrays.sort(values, 0, n);
                    double median = n % 2 == 1 ? values[n / 2] : (values[n / 2 - 1] + values[n / 2]) / 2;
                    stats.add(new Stat(metric, median, rank(values, n, 0.05), rank(values, n, 0.95), n));
                }
            }
        }
        return new Usual(!enabled, days, hours.size(), List.copyOf(stats));
    }

    void write(JsonWriter json, IncidentReport report, JfrTimeline timeline,
               ReportActivityWriter.TickSelection tick, List<JfrTimeline.ModCpu> mods) throws IOException {
        if (usual == null && similar == null) {
            json.name("history").nullValue();
            return;
        }
        json.name("history").beginObject();
        json.name("usual");
        if (usual == null) {
            json.nullValue();
        } else {
            json.beginObject();
            json.name("off").value(usual.off());
            json.name("days").value((Number) usual.days());
            json.name("minDays").value((Number) MIN_DAYS);
            json.name("hours").value((Number) usual.hours());
            json.name("enough").value(usual.days() >= MIN_DAYS);
            json.name("clock").value(CLOCK.format(at));
            json.name("window").value(manual(report) ? "recording" : "incident");
            json.name("metrics").beginArray();
            for (Row row : rows(report, timeline, tick)) {
                Stat stat = row.stat();
                json.beginObject();
                json.name("key").value(stat.metric().key);
                json.name("label").value(stat.metric().label);
                json.name("unit").value(stat.metric().unit);
                ReportJson.writeNullableDouble(json, "incident", row.incident());
                json.name("median").value(ReportJson.round2(stat.median()));
                json.name("low").value(ReportJson.round2(stat.low()));
                json.name("high").value(ReportJson.round2(stat.high()));
                json.name("samples").value((Number) stat.samples());
                json.name("flag").value(row.flag());
                json.endObject();
            }
            json.endArray();
            json.endObject();
        }
        json.name("similar");
        if (similar == null) {
            json.nullValue();
        } else {
            writeSimilar(json, timeline, mods);
        }
        json.endObject();
    }

    ReportFindings.Entry evidence(IncidentReport report, JfrTimeline timeline, ReportActivityWriter.TickSelection tick) {
        if (usual == null) {
            return null;
        }
        Row worst = null;
        for (Row row : rows(report, timeline, tick)) {
            if (row.far() && (worst == null || row.severity() > worst.severity())) {
                worst = row;
            }
        }
        if (worst == null) {
            return null;
        }
        Metric metric = worst.stat().metric();
        String value = withUnit(worst.incident(), metric);
        String days = " over the last " + DAYS + " days";
        List<ReportFindings.Span> spans = new ArrayList<>();
        spans.add(text(metric.label + " averaged "));
        spans.add(bold(value));
        String hover;
        if (metric.direction < 0) {
            spans.add(text(", under half of its usual low for this time of day."));
            hover = "95% of samples at this time of day stayed above " + withUnit(worst.stat().low(), metric) + days
                + ". Shown when the value is under half of that.";
        } else if (worst.stat().high() > 0) {
            spans.add(text(", "));
            spans.add(bold(ReportFindings.fixed(worst.incident() / worst.stat().high(), 1) + " times"));
            spans.add(text(" its usual high for this time of day."));
            hover = "95% of samples at this time of day stayed under " + withUnit(worst.stat().high(), metric) + days
                + ". Shown from 2 times that.";
        } else {
            spans.add(text(", while it is usually " + withUnit(0, metric) + " at this time of day."));
            hover = "95% of samples at this time of day stayed at " + withUnit(0, metric) + days + ".";
        }
        return new ReportFindings.Entry(List.copyOf(spans), "metrics", "Key metrics", hover);
    }

    private List<Row> rows(IncidentReport report, JfrTimeline timeline, ReportActivityWriter.TickSelection tick) {
        double start = windowStart(report, timeline);
        List<Row> rows = new ArrayList<>(usual.stats().size());
        for (Stat stat : usual.stats()) {
            Metric metric = stat.metric();
            double[] series = timeline == null ? null : incidentSeries(metric, timeline, tick);
            if (series == null || series.length < 10) {
                rows.add(new Row(stat, null, null, false, 0));
                continue;
            }
            double value = ReportJson.round2(sliceAverage(series, start));
            double ref = Math.max(stat.high(), metric.floor);
            boolean mark = metric.direction > 0 ? value >= MARK * ref
                : metric.direction < 0 && value <= (2 - MARK) * stat.low();
            boolean far = metric.direction > 0 ? value >= FAR * ref
                : metric.direction < 0 && value <= stat.low() / FAR;
            double severity = metric.direction > 0 ? value / ref
                : metric.direction < 0 ? (value > 0 ? stat.low() / value : Double.MAX_VALUE) : 0;
            rows.add(new Row(stat, value, mark ? (metric.direction > 0 ? "high" : "low") : null, far, severity));
        }
        return rows;
    }

    private void writeSimilar(JsonWriter json, JfrTimeline timeline, List<JfrTimeline.ModCpu> mods) throws IOException {
        String mod = currentMod(timeline, mods);
        String method = timeline == null || timeline.totalSamples() <= 0 || timeline.hotMethods().isEmpty()
            ? null : timeline.hotMethods().getFirst().method();
        long weekFrom = at.toEpochMilli() - DAYS * DAY_MS;
        int week = 0;
        int compared = 0;
        int sameMod = 0;
        int sameMethod = 0;
        for (Earlier earlier : similar) {
            if (earlier.at().toEpochMilli() >= weekFrom) {
                week++;
            }
            if (earlier.read()) {
                compared++;
                if (mod != null && mod.equals(earlier.mod())) {
                    sameMod++;
                }
                if (method != null && method.equals(earlier.method())) {
                    sameMethod++;
                }
            }
        }
        int month = similar.size();
        json.beginObject();
        json.name("checked").value((Number) checked);
        json.name("week").value((Number) week);
        json.name("month").value((Number) month);
        json.name("newest").value(similar.isEmpty() ? null : WHEN.format(similar.getFirst().at()));
        json.name("compared").value((Number) compared);
        json.name("sameMod").value((Number) sameMod);
        json.name("sameMethod").value((Number) sameMethod);
        json.name("line").beginArray();
        for (ReportFindings.Span span : line(week, month, compared, mod, sameMod, method, sameMethod)) {
            json.beginArray().value(span.text()).value(span.style()).endArray();
        }
        json.endArray();
        json.name("rows").beginArray();
        for (Earlier earlier : similar.subList(0, Math.min(SHOWN, month))) {
            json.beginObject();
            json.name("created").value(WHEN.format(earlier.at()));
            json.name("trigger").value(earlier.trigger());
            json.name("triggerLabel").value(TriggerNames.label(earlier.trigger()));
            json.name("world").value(WORLD_TRIGGERS.contains(earlier.trigger()) ? earlier.world() : null);
            json.name("read").value(earlier.read());
            json.name("mod").value(earlier.mod() == null ? null : ReportFindings.modName(earlier.mod()));
            json.name("method").value(earlier.method());
            json.name("sameMod").value(mod != null && mod.equals(earlier.mod()));
            json.name("sameMethod").value(method != null && method.equals(earlier.method()));
            json.endObject();
        }
        json.endArray();
        json.endObject();
    }

    private List<ReportFindings.Span> line(int week, int month, int compared, String mod, int sameMod,
                                           String method, int sameMethod) {
        List<ReportFindings.Span> spans = new ArrayList<>();
        if (month == 0) {
            spans.add(text(checked == 0 ? "There are no older bundles on disk to compare with."
                : "No earlier incident had the same trigger or world in the last " + MONTH + " days."));
            return spans;
        }
        if (week == 0) {
            spans.add(text("No earlier incident had the same trigger or world in the last " + DAYS + " days, "));
        } else {
            spans.add(bold(Integer.toString(week)));
            spans.add(text((week == 1 ? " earlier incident" : " earlier incidents")
                + " had the same trigger or world in the last " + DAYS + " days, "));
        }
        spans.add(bold(Integer.toString(month)));
        spans.add(text(" in the last " + MONTH + " days. The newest was on "));
        spans.add(bold(WHEN.format(similar.getFirst().at())));
        spans.add(text("."));
        if (compared == 0) {
            return spans;
        }
        if (mod != null) {
            spans.add(text(" "));
            spans.add(bold(ReportFindings.modName(mod)));
            spans.add(text(sameAs("top mod", sameMod, compared, month)));
        }
        if (method != null) {
            spans.add(text(" "));
            spans.add(code(ReportFindings.leafName(method)));
            spans.add(text(sameAs("top method", sameMethod, compared, month)));
        }
        return spans;
    }

    private static String sameAs(String what, int same, int compared, int month) {
        if (compared == 1) {
            return same == 1 ? " was the " + what + " then too." : " was not the " + what + " then.";
        }
        String of = compared == month ? "them" : "the " + compared + " newest";
        return same == 0 ? " was the " + what + " in none of " + of + "."
            : " was also the " + what + " in " + same + " of " + of + ".";
    }

    private static String currentMod(JfrTimeline timeline, List<JfrTimeline.ModCpu> mods) {
        long total = timeline == null ? 0 : timeline.totalSamples();
        String best = null;
        long bestSelf = 0;
        if (total < ReportFindings.MIN_SAMPLES) {
            return null;
        }
        for (JfrTimeline.ModCpu mod : mods) {
            if (ReportFindings.nameable(mod.id()) && 100.0 * mod.self() / total >= ReportFindings.MOD_SHARE
                && mod.self() > bestSelf) {
                best = mod.id();
                bestSelf = mod.self();
            }
        }
        return best;
    }

    private static String[] olderTop(ZipFile zip) throws IOException {
        Map<String, Object> data = read(zip, "report.html", MAX_REPORT_BYTES, Set.of("incident", "hotMethods", "modCpu"), -1);
        if (data.isEmpty()) {
            return null;
        }
        double total = number(map(data.get("incident")).get("samples"));
        String method = null;
        List<Object> hot = list(data.get("hotMethods"));
        if (!hot.isEmpty() && list(hot.getFirst()).size() > 1) {
            method = string(list(hot.getFirst()).get(1));
        }
        String mod = null;
        double bestSelf = 0;
        if (total >= ReportFindings.MIN_SAMPLES) {
            for (Object item : list(data.get("modCpu"))) {
                List<Object> row = list(item);
                String id = row.isEmpty() ? null : string(row.getFirst());
                double self = number(row.size() > 4 ? row.get(4) : row.size() > 1 ? row.get(1) : null);
                if (id != null && ReportFindings.nameable(id) && 100 * self / total >= ReportFindings.MOD_SHARE
                    && self > bestSelf) {
                    mod = id;
                    bestSelf = self;
                }
            }
        }
        return new String[] {mod, method};
    }

    private static Map<String, Object> read(ZipFile zip, String name, int limit, Set<String> keys, int from)
        throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null || entry.getSize() > limit) {
            return Map.of();
        }
        byte[] bytes;
        try (InputStream in = zip.getInputStream(entry)) {
            bytes = in.readNBytes(limit + 1);
        }
        if (bytes.length > limit) {
            return Map.of();
        }
        String body = new String(bytes, StandardCharsets.UTF_8);
        int start = from;
        if (start < 0) {
            Matcher data = DATA.matcher(body);
            if (!data.find()) {
                return Map.of();
            }
            start = data.end();
        }
        return new JsonScanner(body, start).object(keys);
    }

    private static boolean manual(IncidentReport report) {
        return "MANUAL".equals(report.meta().trigger());
    }

    private static double windowStart(IncidentReport report, JfrTimeline timeline) {
        if (timeline == null || timeline.start() == null || timeline.end() == null || manual(report)) {
            return 0;
        }
        long start = timeline.start().toEpochMilli();
        long span = timeline.end().toEpochMilli() - start;
        if (span <= 0) {
            return 0;
        }
        IncidentMetadata meta = report.meta();
        long marker = "UNCLEAN_SHUTDOWN".equals(meta.trigger()) ? timeline.end().toEpochMilli()
            : ReportHtml.profileStartMs(report.context(), meta.createdAt().toEpochMilli());
        double frac = Math.round(Math.max(0, Math.min(1, (marker - start) / (double) span)) * 10000) / 10000.0;
        return Math.max(0, frac - 0.02);
    }

    private static double sliceAverage(double[] series, double from) {
        int last = series.length - 1;
        int first = Math.min(last, Math.max(0, (int) Math.floor(from * last)));
        double sum = 0;
        for (int i = first; i <= last; i++) {
            sum += series[i];
        }
        return sum / (last - first + 1);
    }

    private static double[] incidentSeries(Metric metric, JfrTimeline timeline, ReportActivityWriter.TickSelection tick) {
        return switch (metric) {
            case MSPT -> tick == null ? null : tick.mspt();
            case TPS -> tick == null ? null : tick.tps();
            case PLAYERS -> timeline.playersSeries();
            case CPU -> timeline.cpuJvmSeries();
            case HEAP -> gib(timeline.heapSeries());
            case RSS -> gib(timeline.rssSeries());
            case GC -> timeline.gcTimeSeries();
        };
    }

    private static double historyValue(Metric metric, MetricsLog.Trend trend, int i) {
        return switch (metric) {
            case MSPT -> trend.tickAvgMs()[i];
            case TPS -> trend.tps()[i];
            case PLAYERS -> trend.players()[i];
            case CPU -> trend.cpuPct()[i];
            case HEAP -> trend.heapBytes()[i] / GIB;
            case RSS -> trend.rssBytes()[i] / GIB;
            case GC -> trend.gcPct()[i];
        };
    }

    private static double rank(double[] sorted, int n, double quantile) {
        return sorted[Math.max(0, (int) Math.ceil(quantile * n) - 1)];
    }

    private static double[] gib(long[] bytes) {
        if (bytes == null) {
            return null;
        }
        double[] out = new double[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            out[i] = bytes[i] / GIB;
        }
        return out;
    }

    private static boolean sameWorld(String trigger, String world, String olderTrigger, String olderWorld) {
        return WORLD_TRIGGERS.contains(trigger) && WORLD_TRIGGERS.contains(olderTrigger)
            && world != null && !world.isBlank() && WorldNames.sameWorld(world, olderWorld);
    }

    private static String withUnit(double value, Metric metric) {
        String number = ReportFindings.fixed(value, metric.digits);
        return metric.unit.isEmpty() ? number : metric.unit.equals("%") ? number + "%" : number + " " + metric.unit;
    }

    private static Instant instant(Object value) {
        try {
            return value instanceof String s ? Instant.parse(s) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String string(Object value) {
        return value instanceof String s ? s : null;
    }

    private static double number(Object value) {
        return value instanceof Double d ? d : 0;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return value instanceof List<?> l ? (List<Object>) l : List.of();
    }

    private static ReportFindings.Span text(String value) {
        return new ReportFindings.Span(value, "");
    }

    private static ReportFindings.Span bold(String value) {
        return new ReportFindings.Span(value, "b");
    }

    private static ReportFindings.Span code(String value) {
        return new ReportFindings.Span(value, "code");
    }
}
