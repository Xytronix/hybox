package io.github.xytronix.hybox.core.report;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.xytronix.hybox.core.health.JfrTimeline;
import io.github.xytronix.hybox.core.health.StalledThread;
import io.github.xytronix.hybox.core.health.ThreadDumpProgress;
import io.github.xytronix.hybox.core.incident.DiagnosticSection;
import io.github.xytronix.hybox.core.json.JsonWriter;
import io.github.xytronix.hybox.core.text.Units;

final class ReportDiagnosticsWriter {
    private static final Pattern HEARTBEAT_AGE = Pattern.compile("\\((\\d+) ms ago\\)");
    private static final long ON_TIME_MS = 1000;
    private static final Pattern STALLED_MS = Pattern.compile("\\s*(\\d+) ms\\s*");

    private ReportDiagnosticsWriter() {
    }

    static void writePlugins(JsonWriter json, Map<String, String> plugins, Map<String, String> websites)
        throws IOException {
        writeModRows(json, "plugins", plugins, websites);
    }

    static void writeAssetPacks(JsonWriter json, Map<String, String> assetPacks) throws IOException {
        Map<String, String> others = assetPacks == null ? null : new LinkedHashMap<>(assetPacks);
        if (others != null) {
            others.keySet().removeIf(id -> id.replaceFirst(" #\\d+$", "").equals("Xytronix:Hybox"));
        }
        writeModRows(json, "assetPacks", others, null);
    }

    private static void writeModRows(JsonWriter json, String key, Map<String, String> entries,
                                     Map<String, String> websites) throws IOException {
        if (entries == null || entries.isEmpty()) {
            json.name(key).nullValue();
            return;
        }
        json.name(key).beginArray();
        for (Map.Entry<String, String> e : entries.entrySet()) {
            String id = e.getKey().replaceFirst(" #\\d+$", "");
            int colon = id.indexOf(':');
            String author = colon > 0 ? id.substring(0, colon) : "";
            String name = colon > 0 ? id.substring(colon + 1) : id;
            String[] parts = e.getValue().split(" @ ", 3);
            String website = websites == null ? null : webLink(websites.get(e.getKey()));
            json.beginArray();
            json.value(author);
            json.value(name);
            json.value(parts[0]);
            json.value(author.equalsIgnoreCase("hytale"));
            json.value(parts.length > 1 ? parts[1] : null);
            json.value(parts.length > 2 ? parts[2] : null);
            if (website != null) {
                json.value(website);
            }
            json.endArray();
        }
        json.endArray();
    }

    private static String webLink(String website) {
        if (website == null || website.isBlank()) {
            return null;
        }
        String trimmed = website.trim();
        try {
            URI uri = new URI(trimmed);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            return (scheme.equals("http") || scheme.equals("https")) && uri.getHost() != null ? trimmed : null;
        } catch (Exception e) {
            return null;
        }
    }

    static void writeLog(JsonWriter json, List<ReportHtml.LogLine> entries) throws IOException {
        if (entries.isEmpty()) {
            json.name("log").nullValue();
            return;
        }
        json.name("log").beginArray();
        for (ReportHtml.LogLine entry : entries) {
            json.beginArray();
            json.value(entry.level());
            json.value(entry.source());
            json.value(entry.message());
            json.endArray();
        }
        json.endArray();
    }

    static void writeConfigs(JsonWriter json, List<DiagnosticSection> configs) throws IOException {
        json.name("configs").beginArray();
        for (DiagnosticSection section : configs) {
            json.beginObject();
            json.name("title").value(section.title());
            json.name("content").value(section.preformatted());
            json.endObject();
        }
        json.endArray();
    }

    static List<DiagnosticSection> captured(List<DiagnosticSection> generic, boolean hasPlugins, WorldNames worldNames) {
        List<DiagnosticSection> shown = new ArrayList<>(generic.size());
        for (DiagnosticSection section : generic) {
            Map<String, String> entries = section.entries();
            if ("Environment".equals(section.title())) {
                Map<String, String> trimmed = new LinkedHashMap<>(entries);
                trimmed.remove("Server name");
                trimmed.remove("Hytale version");
                trimmed.remove("Container runtime");
                trimmed.values().removeIf(ReportSystemWriter::isLoaderEntry);
                if (hasPlugins) {
                    trimmed.remove("Mods");
                    trimmed.remove("Mods (count)");
                }
                entries = trimmed;
            }
            Map<String, String> beats = "Heartbeats".equals(section.title()) ? heartbeats(entries, worldNames) : null;
            if (beats != null) {
                entries = beats;
            } else if ("Thread progress".equals(section.title()) && nothingStuck(entries)) {
                Map<String, String> verdict = new LinkedHashMap<>();
                if (entries.containsKey("Dumps compared")) {
                    verdict.put("Dumps compared", entries.get("Dumps compared"));
                }
                verdict.put("Verdict", "no thread stuck. Threads that did not move wait by design.");
                entries = verdict;
            }
            String pre = section.preformatted();
            if (!entries.isEmpty() || pre != null && !pre.isBlank()) {
                shown.add(new DiagnosticSection(section.title(), entries, pre));
            }
        }
        return shown;
    }

    private static Map<String, String> heartbeats(Map<String, String> heartbeats, WorldNames worldNames) {
        long oldest = -1;
        List<Map.Entry<String, Long>> late = new ArrayList<>();
        for (Map.Entry<String, String> entry : heartbeats.entrySet()) {
            Matcher age = HEARTBEAT_AGE.matcher(entry.getValue());
            if (!age.find()) {
                return null;
            }
            long ms = Long.parseLong(age.group(1));
            if (ms >= ON_TIME_MS) {
                late.add(Map.entry(entry.getKey(), ms));
            } else {
                oldest = Math.max(oldest, ms);
            }
        }
        if (heartbeats.isEmpty()) {
            return null;
        }
        Map<String, String> out = new LinkedHashMap<>();
        late.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        for (Map.Entry<String, Long> entry : late) {
            out.put(worldNames.label(entry.getKey()), ReportFindings.fixed(entry.getValue() / 1000.0, 1) + " s late");
        }
        int onTime = heartbeats.size() - late.size();
        if (onTime > 0) {
            String worlds = late.isEmpty() ? (onTime == 1 ? "1 world" : "All " + onTime + " worlds")
                : onTime == 1 ? "1 other world" : "The other " + onTime + " worlds";
            out.put(worlds, "on time, the oldest heartbeat was " + oldest + " ms old");
        }
        return out;
    }

    private static boolean nothingStuck(Map<String, String> progress) {
        for (Map.Entry<String, String> entry : progress.entrySet()) {
            if (entry.getKey().equals("Dumps compared") || entry.getKey().equals("Verdict")) {
                continue;
            }
            int at = entry.getValue().indexOf(" @ ");
            if (at < 0 || !ThreadDumpProgress.isIdleFrame(entry.getValue().substring(at + 3))) {
                return false;
            }
        }
        return true;
    }

    static List<DiagnosticSection> modSettings(JfrTimeline timeline, ModNames modNames) {
        if (timeline == null) {
            return List.of();
        }
        List<DiagnosticSection> sections = new ArrayList<>();
        for (JfrTimeline.ModSettings settings : timeline.modSettings()) {
            if (!settings.values().isEmpty()) {
                sections.add(new DiagnosticSection(modNames.id(settings.mod()) + " settings", settings.values()));
            }
        }
        return sections;
    }

    private static String stalledFor(String value) {
        Matcher ms = STALLED_MS.matcher(value);
        return ms.matches() ? Units.elapsed(Long.parseLong(ms.group(1))) : value;
    }

    static String genericHtml(List<DiagnosticSection> captured) {
        StringBuilder html = new StringBuilder(2048);
        for (DiagnosticSection section : captured) {
            Map<String, String> entries = section.entries();
            String pre = section.preformatted();
            boolean hasPre = pre != null && !pre.isBlank();
            html.append("<div class=\"kvcard\" style=\"margin-bottom:16px\"><div class=\"kvh\"><b>")
                .append(ReportHtml.escapeHtml(section.title())).append("</b></div><div class=\"kv\">");
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                String key = entry.getKey().equals(StalledThread.FRAME_KEY)
                    ? StalledThread.frameVerb(entries.get("State")) : entry.getKey();
                String value = entry.getKey().equals("State") ? StalledThread.stateLabel(entry.getValue())
                    : entry.getKey().equals("Stalled for") ? stalledFor(entry.getValue()) : entry.getValue();
                html.append("<div class=\"row\"><span class=\"k\">")
                    .append(ReportHtml.escapeHtml(key)).append("</span><span class=\"v\">")
                    .append(ReportHtml.escapeHtml(value)).append("</span></div>");
            }
            html.append("</div>");
            if (hasPre) {
                html.append("<pre class=\"logpre\" style=\"margin:12px 0 0\" tabindex=\"0\" aria-label=\"")
                    .append(ReportHtml.escapeHtml(section.title())).append(" stack or capture text\">")
                    .append(ReportHtml.escapeHtml(pre)).append("</pre>");
            }
            html.append("</div>");
        }
        if (html.isEmpty()) {
            return "";
        }
        return "<section id=\"stalled\"><h2 class=\"section-heading\"><button type=\"button\" class=\"sh\" "
            + "data-collapse aria-expanded=\"true\" aria-controls=\"stalled-body\"><span class=\"num\">03</span>"
            + "<span>Captured diagnostics</span><span class=\"sub\"></span>"
            + "<span class=\"chev\" aria-hidden=\"true\">▾</span></button></h2>"
            + "<div class=\"sbody\" id=\"stalled-body\">" + html + "</div></section>";
    }
}
