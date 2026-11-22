package io.github.xytronix.hybox.core.report;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.xytronix.hybox.core.health.HealthSnapshot;
import io.github.xytronix.hybox.core.health.JfrTimeline;
import io.github.xytronix.hybox.core.incident.DiagnosticSection;
import io.github.xytronix.hybox.core.incident.IncidentMetadata;
import io.github.xytronix.hybox.core.incident.IncidentReport;
import io.github.xytronix.hybox.core.json.JsonWriter;
import io.github.xytronix.hybox.core.text.Units;

public final class ReportHtml {
    private static final String TEMPLATE_RESOURCE = "report-template.html";
    private static final String TITLE_TOKEN = "__TITLE__";
    private static final String HEADER_TOKEN = "<!--__HEADER__-->";
    private static final String SUMMARY_TOKEN = "<!--__SUMMARY__-->";
    private static final String DIAGNOSTICS_TOKEN = "<!--__DIAGNOSTICS__-->";
    private static final String DATA_TOKEN = "/*__DATA__*/null";

    private static final DateTimeFormatter CREATED_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter CLOCK_FORMAT =
        DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT).withZone(ZoneOffset.UTC);

    private static final Pattern LOG_LINE = Pattern.compile(
        "^\\[(\\d{4})/(\\d{2})/(\\d{2}) (?:(\\d{2}):(\\d{2}):(\\d{2})|\\[REDACTED\\])\\s+([A-Z]+)\\]\\s+\\[([^\\]]*)\\]\\s?(.*)$");
    private static final Pattern WORLD_UUID_SUFFIX = Pattern.compile(
        "-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private static final int MAX_LOG_MESSAGE_CHARS = 8000;

    private ReportHtml() {
    }

    public static void write(IncidentReport report, OutputStream out) throws IOException {
        write(report, null, out);
    }

    public static void write(IncidentReport report, JfrTimeline timeline, OutputStream out) throws IOException {
        write(report, timeline, null, out);
    }

    public static void write(IncidentReport report, JfrTimeline timeline, String recordingUnavailableReason,
                             OutputStream out) throws IOException {
        write(report, timeline, recordingUnavailableReason, UnaryOperator.identity(), out);
    }

    public static void write(IncidentReport report, JfrTimeline timeline, String recordingUnavailableReason,
                             UnaryOperator<String> dataMask, OutputStream out) throws IOException {
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(dataMask, "dataMask");
        Objects.requireNonNull(out, "out");

        OutputStreamWriter writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        writer.write(render(report, timeline, recordingUnavailableReason, dataMask));
        writer.flush();
    }

    private static String render(IncidentReport report, JfrTimeline timeline, String recordingUnavailableReason,
                                 UnaryOperator<String> dataMask) throws IOException {
        String template = loadTemplate();
        ReportSections sections = ReportSections.route(report.diagnostics());
        Long stallMs = parseStallMs(report.context());
        Integer players = totalPlayers(report.snapshot());
        String unavailableReason = recordingUnavailableReason(timeline, recordingUnavailableReason);
        List<DiagnosticSection> captured = ReportDiagnosticsWriter.captured(sections.generic(), sections.plugins() != null);

        String html = replaceOnce(template, TITLE_TOKEN,
            "Hybox Incident Report · " + escapeHtml(report.meta().id().value()));
        html = replaceOnce(html, HEADER_TOKEN, dataMask.apply(
            buildHeader(report.meta(), stallMs, players, report.context(), blockedInFrame(report),
                mixinChip(sections.mixins()))));
        html = replaceOnce(html, SUMMARY_TOKEN, dataMask.apply(buildSummary(unavailableReason)));
        html = replaceOnce(html, DIAGNOSTICS_TOKEN, dataMask.apply(ReportDiagnosticsWriter.genericHtml(captured)));
        String json = dataMask.apply(buildData(report, timeline, sections, captured, stallMs, players, unavailableReason))
            .replace("</", "<\\/");
        return replaceOnce(html, DATA_TOKEN, json);
    }

    private static String loadTemplate() throws IOException {
        try (InputStream in = ReportHtml.class.getResourceAsStream(TEMPLATE_RESOURCE)) {
            if (in == null) {
                throw new IOException("Missing resource " + TEMPLATE_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String replaceOnce(String haystack, String token, String replacement) throws IOException {
        int i = haystack.indexOf(token);
        if (i < 0) {
            throw new IOException("Report template is missing token " + token);
        }
        return haystack.substring(0, i) + replacement + haystack.substring(i + token.length());
    }


    private static String blockedInFrame(IncidentReport report) {
        if (report.diagnostics() == null) {
            return null;
        }
        for (DiagnosticSection section : report.diagnostics()) {
            if ("Stalled thread".equals(section.title()) || "Deadlocked thread".equals(section.title())) {
                String frame = section.entries().get("Blocked in");
                if (frame != null && !frame.isBlank()) {
                    return frame;
                }
            }
        }
        return null;
    }

    static String mixinChip(Map<String, String> mixins) {
        MixinSection.Parsed parsed = MixinSection.parse(mixins);
        if (parsed == null || parsed.problems().isEmpty()) {
            return "";
        }
        long errors = parsed.errors();
        long warnings = parsed.warnings();
        List<String> parts = new ArrayList<>(2);
        if (errors > 0) {
            parts.add(Units.count(errors, "error"));
        }
        if (warnings > 0) {
            parts.add(Units.count(warnings, "warning"));
        }
        return "<a class=\"pill " + (errors > 0 ? "bad" : "warn") + "\" href=\"#mixins\">Mixin problems <b>"
            + String.join(" · ", parts) + "</b></a>";
    }

    private static String buildHeader(IncidentMetadata meta, Long stallMs, Integer players,
                                      Map<String, String> context, String blockedIn, String mixinChip) {
        String sevClass = switch (meta.severity()) {
            case CRITICAL -> "crit";
            case DEGRADED -> "warn";
            default -> "info";
        };
        StringBuilder h = new StringBuilder(1024);
        h.append("<div class=\"hl-top\">")
            .append("<span class=\"badge ").append(sevClass).append("\"><span class=\"dot\"></span>")
            .append(escapeHtml(meta.severity().name())).append("</span>")
            .append("<span class=\"pill\">Trigger <b>").append(escapeHtml(meta.trigger())).append("</b></span>")
            .append(mixinChip);
        if (players != null) {
            h.append("<span class=\"pill\"><b>").append(players).append("</b> online</span>");
        }
        if (context != null) {
            for (Map.Entry<String, String> entry : context.entrySet()) {
                String pill = contextPill(entry.getKey(), entry.getValue());
                if (pill != null) {
                    h.append("<span class=\"pill\">").append(pill).append("</span>");
                }
            }
        }
        h.append("<span class=\"incid\">Incident <b>").append(escapeHtml(meta.id().value())).append("</b></span>")
            .append("<span id=\"copySlot\" style=\"margin-left:14px\"></span>")
            .append("</div>");

        String worldShort = worldShort(meta.world());
        if ("HEARTBEAT_STALL".equals(meta.trigger()) && stallMs != null && worldShort != null) {
            h.append("<h1>World <i>").append(escapeHtml(worldShort)).append("</i> stopped responding for ")
                .append("<span class=\"ms\">").append(Units.elapsed(stallMs)).append("</span></h1>");
        } else {
            h.append("<h1>").append(escapeHtmlWithBreaks(meta.headline())).append("</h1>");
        }

        h.append("<div class=\"subhead\">");
        if (meta.world() != null && !"server".equals(meta.world())) {
            h.append("World <code>").append(escapeHtml(meta.world())).append("</code> · ");
        }
        h.append("Captured <span class=\"mono\">").append(CREATED_FORMAT.format(meta.createdAt()))
            .append("</span>");
        if (blockedIn != null) {
            h.append(" · Blocked in <code>").append(escapeHtml(blockedIn)).append("</code>");
        }
        h.append("</div>");
        return h.toString();
    }

    private static String contextPill(String key, String value) {
        String label;
        String shown = value;
        switch (key) {
            case "stallMs", "profileStartMs", "recovered" -> {
                return null;
            }
            case "tickAvgMs" -> { label = "Tick time"; shown = value + " ms"; }
            case "threads" -> label = "Deadlocked threads";
            case "threadIds" -> label = "Thread IDs";
            case "threadId" -> label = "Thread ID";
            case "gcPct" -> { label = "GC time"; shown = value + "%"; }
            case "cpuPct" -> { label = "Server CPU"; shown = value + "%"; }
            case "usedPct" -> { label = "Heap after GC"; shown = value + "%"; }
            case "sustainedSec" -> { label = "For"; shown = value + " s"; }
            case "direction" -> {
                label = "Direction";
                shown = "in".equals(value) ? "inbound" : "out".equals(value) ? "outbound" : value;
            }
            case "mbps" -> { label = "Traffic"; shown = value + " Mbit/s"; }
            case "before" -> label = "Players before";
            case "after" -> label = "Players after";
            case "error" -> label = "Error";
            case "errorStack" -> label = "Stack";
            case "reason" -> label = "Reason";
            default -> label = key;
        }
        return escapeHtml(label) + " <b>" + escapeHtml(shown) + "</b>";
    }

    private static String recordingUnavailableReason(JfrTimeline timeline, String suppliedReason) {
        if (timeline != null && timeline.start() != null && timeline.end() != null) {
            return null;
        }
        if (suppliedReason != null && !suppliedReason.isBlank()) {
            return suppliedReason;
        }
        return timeline == null ? "no readable recording timeline was supplied"
            : "the recording contains no usable event timestamps";
    }

    private static String buildSummary(String unavailableReason) {
        if (unavailableReason == null) {
            return "";
        }
        return "<div class=\"cwarn\" id=\"jfr-unavailable\"><span><b>This bundle has no usable recording: "
            + escapeHtml(unavailableReason) + (unavailableReason.endsWith(".") ? "" : ".") + "</b><br>"
            + "Charts and CPU sections are empty. The server log from the capture time has the cause."
            + "</span></div>";
    }

    private static void writeVitals(JsonWriter json, ReportActivityWriter.TickSelection tick,
                                    HealthSnapshot snapshot, JfrTimeline timeline) throws IOException {
        double target = snapshot != null && snapshot.targetTps() > 0 ? snapshot.targetTps() : 30;
        double[] mspt = tick == null ? null : tick.mspt();
        double[] tps = tick == null ? null : tick.tps();
        double worst = mspt == null || mspt.length == 0 ? Double.NaN : ReportJson.max(mspt);
        if (tick != null && snapshot != null) {
            for (HealthSnapshot.World world : snapshot.worlds()) {
                if (world.name().equals(tick.world()) && world.msptMax() >= 0 && !(world.msptMax() <= worst)) {
                    worst = world.msptMax();
                }
            }
        }
        JfrTimeline.CpuStats cpu = timeline == null ? null : timeline.cpu();
        json.name("vitals").beginObject();
        json.name("world").value(tick == null ? null : tick.world());
        json.name("tpsTarget").value(target);
        json.name("tickBudgetMs").value(ReportJson.round2(1000.0 / target));
        ReportJson.writeNullableDouble(json, "tickAvgMs",
            mspt == null || mspt.length == 0 ? null : ReportJson.round2(ReportJson.avg(mspt)));
        ReportJson.writeNullableDouble(json, "tickWorstMs", Double.isFinite(worst) ? ReportJson.round2(worst) : null);
        ReportJson.writeNullableDouble(json, "tpsAvg",
            tps == null || tps.length == 0 ? null : ReportJson.round2(ReportJson.avg(tps)));
        ReportJson.writeNullableDouble(json, "tpsMin",
            tps == null || tps.length == 0 ? null : ReportJson.round2(ReportJson.min(tps)));
        ReportJson.writeNullableDouble(json, "cpuServerAvg",
            cpu != null && cpu.jvmAvg() >= 0 ? ReportJson.round2(cpu.jvmAvg()) : null);
        ReportJson.writeNullableDouble(json, "cpuMachineAvg",
            cpu != null && cpu.machAvg() >= 0 ? ReportJson.round2(cpu.machAvg()) : null);
        ReportJson.writeNullable(json, "heapPeak", ReportFindings.heapPeak(snapshot, timeline));
        ReportJson.writeNullable(json, "heapMax", ReportSystemWriter.heapMax(snapshot, timeline));
        json.endObject();
    }


    static String worldShort(String world) {
        if (world == null) {
            return null;
        }
        String s = world.startsWith("instance-") ? world.substring("instance-".length()) : world;
        s = WORLD_UUID_SUFFIX.matcher(s).replaceFirst("");
        return s.replace('_', ' ');
    }

    private static String hyboxVersion() {
        try (InputStream in = ReportHtml.class.getResourceAsStream("/io/github/xytronix/hybox/core/version.txt")) {
            if (in != null) {
                String version = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
                if (!version.isEmpty() && !version.contains("${")) {
                    return version;
                }
            }
        } catch (IOException ignored) {
        }
        return ReportHtml.class.getPackage() == null ? null : ReportHtml.class.getPackage().getImplementationVersion();
    }

    private static Long parseStallMs(Map<String, String> context) {
        try {
            String raw = context == null ? null : context.get("stallMs");
            return raw == null ? null : Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long profileStartMs(Map<String, String> context, long fallback) {
        try {
            String raw = context == null ? null : context.get("profileStartMs");
            return raw == null ? fallback : Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Integer totalPlayers(HealthSnapshot snapshot) {
        if (snapshot == null || snapshot.worlds().isEmpty()) {
            return null;
        }
        int sum = 0;
        boolean anyKnown = false;
        for (HealthSnapshot.World w : snapshot.worlds()) {
            if (w.players() >= 0) {
                sum += w.players();
                anyKnown = true;
            }
        }
        return anyKnown ? sum : null;
    }


    private static String buildData(IncidentReport report, JfrTimeline timeline, ReportSections sections,
                                    List<DiagnosticSection> captured, Long stallMs, Integer players,
                                    String unavailableReason) throws IOException {
        IncidentMetadata meta = report.meta();
        HealthSnapshot snapshot = report.snapshot();
        Map<String, String> env = sections.environment();
        String hytaleVersion = env == null ? null : env.get("Hytale version");
        String serverName = env == null ? null : env.get("Server name");
        String containerRuntime = env == null ? null : env.get("Container runtime");
        List<String[]> loaders = ReportSystemWriter.loaders(env);
        ReportAttribution attribution = new ReportAttribution(timeline == null ? List.of() : timeline.cpuByMod());

        StringBuilder out = new StringBuilder(16384);
        JsonWriter json = new JsonWriter(out);
        json.beginObject();

        json.name("hyboxVersion").value(hyboxVersion());
        json.name("jfrUnavailableReason").value(unavailableReason);

        json.name("diag").beginArray();
        for (DiagnosticSection section : captured) {
            json.beginObject();
            json.name("title").value(section.title());
            json.name("entries").beginArray();
            for (Map.Entry<String, String> entry : section.entries().entrySet()) {
                json.beginArray();
                json.value(entry.getKey());
                json.value(entry.getValue());
                json.endArray();
            }
            json.endArray();
            json.name("pre").value(section.preformatted());
            json.endObject();
        }
        json.endArray();

        json.name("incident").beginObject();
        json.name("id").value(meta.id().value());
        json.name("severity").value(meta.severity().name());
        json.name("trigger").value(meta.trigger());
        json.name("world").value(meta.world());
        json.name("worldShort").value(worldShort(meta.world()));
        json.name("created").value(CREATED_FORMAT.format(meta.createdAt()));
        json.name("note");
        if ("UNCLEAN_SHUTDOWN".equals(meta.trigger())) {
            json.value("The server did not shut down cleanly. Hybox rebuilt this bundle at the next start. "
                + "Values are the last ones recorded before the stop.");
        } else {
            json.nullValue();
        }
        ReportJson.writeNullable(json, "stallMs", stallMs);
        long markerMs = profileStartMs(report.context(), meta.createdAt().toEpochMilli());
        if (timeline != null && timeline.start() != null && timeline.end() != null) {
            json.name("recStart").value(CLOCK_FORMAT.format(timeline.start()));
            long spanMs = timeline.end().toEpochMilli() - timeline.start().toEpochMilli();
            json.name("windowSec").value(Math.max(0, spanMs / 1000));
            boolean manual = "MANUAL".equals(meta.trigger());
            if (spanMs > 0 && !manual) {
                long incidentMs = "UNCLEAN_SHUTDOWN".equals(meta.trigger())
                    ? timeline.end().toEpochMilli() : markerMs;
                double frac = (incidentMs - timeline.start().toEpochMilli()) / (double) spanMs;
                json.name("markerFrac").value(Math.round(Math.max(0, Math.min(1, frac)) * 10000) / 10000.0);
                double recoverySec = (timeline.end().toEpochMilli() - incidentMs) / 1000.0;
                ReportJson.writeNullableDouble(json, "recoverySec", recoverySec >= 2 ? recoverySec : null);
            } else {
                json.name("markerFrac").nullValue();
                json.name("recoverySec").nullValue();
            }
            String profileStart = report.context() == null ? null : report.context().get("profileStartMs");
            if (manual && profileStart != null && spanMs > 0) {
                double frac = (markerMs - timeline.start().toEpochMilli()) / (double) spanMs;
                json.name("profileFrac").value(Math.round(Math.max(0, Math.min(1, frac)) * 10000) / 10000.0);
            } else {
                json.name("profileFrac").nullValue();
            }
        } else {
            json.name("recStart").nullValue();
            json.name("windowSec").nullValue();
            json.name("markerFrac").nullValue();
            json.name("recoverySec").nullValue();
            json.name("profileFrac").nullValue();
        }
        ReportJson.writeNullable(json, "samples", timeline != null && timeline.totalSamples() > 0
            ? timeline.totalSamples() : null);
        ReportJson.writeNullable(json, "players", players == null ? null : players.longValue());
        json.endObject();

        ReportSystemWriter.writeSys(json, snapshot, timeline, hytaleVersion, serverName, containerRuntime, loaders);
        ReportSystemWriter.writeCpu(json, timeline);
        ReportSystemWriter.writeHeap(json, snapshot, timeline);
        ReportSystemWriter.writeRam(json, timeline);
        ReportSystemWriter.writeAlloc(json, timeline, attribution);
        ReportSystemWriter.writeGc(json, snapshot, timeline);
        ReportSystemWriter.writeGcCauses(json, timeline);
        ReportSystemWriter.writeNet(json, timeline);
        ReportActivityWriter.TickSelection tick = ReportActivityWriter.selectTicks(meta, snapshot, timeline);
        ReportActivityWriter.writeTps(json, tick, snapshot);
        writeVitals(json, tick, snapshot, timeline);
        ReportActivityWriter.writeSeries(json, timeline, tick);
        ReportSystemWriter.writeGcOverlap(json, timeline);
        ReportActivityWriter.writePluginMetrics(json, timeline);
        ReportSystemWriter.writeRetained(json, timeline);
        ReportSystemWriter.writeSlowIo(json, timeline);
        ReportSystemWriter.writeHeapHistogram(json, sections.heapHistogram());
        ReportSystemWriter.writeMemPools(json, sections.memPools());
        ReportActivityWriter.writeEntities(json, sections.entities());
        ReportActivityWriter.writeTickSystems(json, sections.tickSystems());
        ReportActivityWriter.writeTickSeries(json, timeline);
        ReportActivityWriter.writeModCpu(json, timeline == null ? List.of() : timeline.cpuByMod(), attribution);
        ReportActivityWriter.writeFlame(json, timeline);
        ReportActivityWriter.writeThreadTimeline(json, timeline);
        List<LogLine> logLines = parseLog(sections.serverLog());
        ReportEvents.write(json, logLines, timeline);
        ReportActivityWriter.writeThreads(json, snapshot, timeline);
        ReportActivityWriter.writeHotMethods(json, timeline);
        ReportActivityWriter.writeWorlds(json, meta, snapshot, timeline);
        ReportActivityWriter.writeWorldTps(json, timeline, tick);
        ReportActivityWriter.writeSubsystems(json, timeline);
        ReportActivityWriter.writeCpuByWorld(json, timeline, attribution);
        ReportDiagnosticsWriter.writePlugins(json, sections.plugins());
        ReportDiagnosticsWriter.writeAssetPacks(json, sections.assetPacks());
        MixinSection.write(json, sections.mixins());
        ReportDiagnosticsWriter.writeLog(json, logLines);
        ReportDiagnosticsWriter.writeConfigs(json, sections.configs());
        ReportFindings.build(report, timeline, sections).write(json);

        json.endObject();
        return out.toString();
    }

    /** Connection lifecycle lines from the Hytale log become "conn" timeline events. */
    static String connectionEventType(LogLine line) {
        if (!"Hytale".equals(line.source())) {
            return null;
        }
        String msg = line.message();
        return msg.startsWith("Starting authenticated flow") || msg.startsWith("Disconnecting ")
            ? "conn" : null;
    }

    record LogLine(String level, String source, String message, long epochMs) {
        LogLine appendMessage(String line) {
            return new LogLine(level, source, message + "\n" + line, epochMs);
        }
    }

    static List<LogLine> parseLog(String serverLog) {
        if (serverLog == null || serverLog.isBlank()) {
            return List.of();
        }
        List<LogLine> out = new ArrayList<>();
        for (String line : serverLog.split("\n", -1)) {
            if (line.isEmpty()) {
                continue;
            }
            Matcher m = LOG_LINE.matcher(line);
            if (m.matches()) {
                String level = switch (m.group(7)) {
                    case "WARNING" -> "WARN";
                    case "SEVERE" -> "ERROR";
                    default -> m.group(7);
                };
                long epochMs = -1;
                try {
                    if (m.group(4) != null) {
                        epochMs = java.time.LocalDateTime.of(
                            Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                            Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)),
                            Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6)))
                            .toInstant(ZoneOffset.UTC).toEpochMilli();
                    }
                } catch (Exception ignored) {
                }
                out.add(new LogLine(level, m.group(8), m.group(9), epochMs));
            } else if (!out.isEmpty()) {
                LogLine prev = out.get(out.size() - 1);
                if (prev.message().length() < MAX_LOG_MESSAGE_CHARS) {
                    out.set(out.size() - 1, prev.appendMessage(line));
                }
            } else {
                out.add(new LogLine("", "", line, -1));
            }
        }
        return out;
    }


    static String escapeHtml(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&#39;");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }

    private static String escapeHtmlWithBreaks(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\r') {
                if (i + 1 < value.length() && value.charAt(i + 1) == '\n') {
                    i++;
                }
                escaped.append("<br>");
                continue;
            }
            if (c == '\n') {
                escaped.append("<br>");
                continue;
            }
            if (c == '\t') {
                escaped.append("&#9;");
                continue;
            }
            switch (c) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&#39;");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }
}
