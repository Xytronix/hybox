package io.github.xytronix.hybox.core.report;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.xytronix.hybox.core.incident.DiagnosticSection;
import io.github.xytronix.hybox.core.json.JsonWriter;

final class ReportDiagnosticsWriter {

    private ReportDiagnosticsWriter() {
    }

    static void writePlugins(JsonWriter json, Map<String, String> plugins) throws IOException {
        writeModRows(json, "plugins", plugins);
    }

    static void writeAssetPacks(JsonWriter json, Map<String, String> assetPacks) throws IOException {
        Map<String, String> others = assetPacks == null ? null : new LinkedHashMap<>(assetPacks);
        if (others != null) {
            others.keySet().removeIf(id -> id.replaceFirst(" #\\d+$", "").equals("Xytronix:Hybox"));
        }
        writeModRows(json, "assetPacks", others);
    }

    private static void writeModRows(JsonWriter json, String key, Map<String, String> entries) throws IOException {
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
            json.beginArray();
            json.value(author);
            json.value(name);
            json.value(parts[0]);
            json.value(author.equalsIgnoreCase("hytale"));
            json.value(parts.length > 1 ? parts[1] : null);
            json.value(parts.length > 2 ? parts[2] : null);
            json.endArray();
        }
        json.endArray();
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

    static List<DiagnosticSection> captured(List<DiagnosticSection> generic, boolean hasPlugins) {
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
            String pre = section.preformatted();
            if (!entries.isEmpty() || pre != null && !pre.isBlank()) {
                shown.add(new DiagnosticSection(section.title(), entries, pre));
            }
        }
        return shown;
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
                html.append("<div class=\"row\"><span class=\"k\">")
                    .append(ReportHtml.escapeHtml(entry.getKey())).append("</span><span class=\"v\">")
                    .append(ReportHtml.escapeHtml(entry.getValue())).append("</span></div>");
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
            + "data-collapse aria-expanded=\"true\" aria-controls=\"stalled-body\"><span class=\"num\">02</span>"
            + "<span>Captured diagnostics</span><span class=\"sub\">snapshots at capture</span>"
            + "<span class=\"chev\" aria-hidden=\"true\">▾</span></button></h2>"
            + "<div class=\"sbody\" id=\"stalled-body\">" + html + "</div></section>";
    }
}
