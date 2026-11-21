package io.github.xytronix.hybox.core.report;

import java.io.IOException;
import java.util.List;

import io.github.xytronix.hybox.core.json.JsonWriter;

public final class CompareMetrics {
    public static final String UP = "up";
    public static final String DOWN = "down";
    public static final String LOG_ERRORS = "logErrors";
    public static final String ENGINE_SYSTEM = "engineSystem";
    public static final double WORSE_PCT = 5;

    public record Metric(String key, String label, String unit, int decimals, boolean perMinute, String bad) {}

    public static final List<Metric> METRICS = List.of(
        new Metric("tps", "TPS", "", 1, false, DOWN),
        new Metric("mspt", "Tick time", "ms", 1, false, UP),
        new Metric("players", "Players", "", 0, false, null),
        new Metric("entities", "Entities", "", 0, false, UP),
        new Metric("chunks", "Chunks in memory", "", 0, false, UP),
        new Metric("chunksGenerated", "Chunks generated", "/min", 0, true, null),
        new Metric("chunksLoaded", "Chunks loaded", "/min", 0, true, null),
        new Metric("cpu", "Machine CPU", "%", 1, false, UP),
        new Metric("cpuJvm", "Server CPU", "%", 1, false, UP),
        new Metric("heap", "Heap used", "GiB", 1, false, UP),
        new Metric("rss", "Process RSS", "GiB", 1, false, UP),
        new Metric("hostMem", "Machine memory", "GiB", 1, false, UP),
        new Metric("disk", "Disk free", "GiB", 1, false, DOWN),
        new Metric("diskWrite", "Disk write", "MiB/s", 1, false, UP),
        new Metric("diskRead", "Disk read", "MiB/s", 1, false, UP),
        new Metric("netOut", "Network outbound", "Mbit/s", 1, false, null),
        new Metric("netIn", "Network inbound", "Mbit/s", 1, false, null),
        new Metric("threads", "Threads", "", 0, false, UP),
        new Metric("gcPause", "GC pause", "ms", 2, false, UP),
        new Metric("gcTime", "GC time", "%", 2, false, UP),
        new Metric("exceptions", "Exceptions", "/min", 1, true, UP),
        new Metric("ping", "Ping", "ms", 0, false, UP),
        new Metric(LOG_ERRORS, "Log errors", "/min", 1, false, UP),
        new Metric(ENGINE_SYSTEM, "Engine system", "ms", 1, false, UP));

    private CompareMetrics() {
    }

    public static Metric metric(String key) {
        for (Metric metric : METRICS) {
            if (metric.key().equals(key)) {
                return metric;
            }
        }
        return null;
    }

    public static boolean worse(String bad, double baseline, double incident) {
        if (bad == null || Math.abs(baseline) < 1e-9) {
            return false;
        }
        double delta = 100 * (incident - baseline) / Math.abs(baseline);
        return Math.abs(delta) >= WORSE_PCT && (UP.equals(bad) ? delta > 0 : DOWN.equals(bad) && delta < 0);
    }

    public static String change(double baseline, double incident) {
        if (Math.abs(baseline) < 1e-9) {
            return null;
        }
        double delta = 100 * (incident - baseline) / Math.abs(baseline);
        if (delta > 999) {
            return ReportFindings.fixed(incident / baseline, 0) + "x";
        }
        String shown = ReportFindings.fixed(delta, Math.abs(delta) >= 100 ? 0 : 1);
        return (delta > 0 && Double.parseDouble(shown) != 0 ? "+" : "") + shown + "%";
    }

    public static boolean informative(String bad, double baseline, double incident, int decimals) {
        double half = 0.5 * Math.pow(10, -decimals);
        if (Math.abs(baseline) < half && Math.abs(incident) < half) {
            return false;
        }
        return bad != null || Math.abs(incident - baseline) >= half;
    }

    static void write(JsonWriter json) throws IOException {
        json.name("compare").beginArray();
        for (Metric metric : METRICS) {
            json.beginObject();
            json.name("key").value(metric.key());
            json.name("label").value(metric.label());
            json.name("unit").value(metric.unit());
            json.name("dec").value((Number) metric.decimals());
            json.name("perMin").value(metric.perMinute());
            json.name("bad").value(metric.bad());
            json.endObject();
        }
        json.endArray();
    }
}
