package io.github.xytronix.hybox.hytale;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import io.github.xytronix.hybox.core.health.FrameLabels;
import io.github.xytronix.hybox.core.json.IncidentJson;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

final class IncidentModel {
    private static final Pattern DATA = Pattern.compile("\\bconst\\s+D\\s*=\\s*");
    private static final Pattern CARD = Pattern.compile("<div class=\"kvcard\"[^>]*><div class=\"kvh\"><b>(.*?)</b></div><div class=\"kv\">(.*?)(?=<div class=\"kvcard\"|$)", Pattern.DOTALL);
    private static final Pattern ENTRY = Pattern.compile("<div class=\"row\"><span class=\"k\">(.*?)</span><span class=\"v\">(.*?)</span></div>", Pattern.DOTALL);
    private static final Pattern PRE = Pattern.compile("<pre\\b[^>]*>(.*?)</pre>", Pattern.DOTALL);
    private static final Pattern BLOCKED = Pattern.compile("Blocked in <code>(.*?)</code>(, [^<]*)?", Pattern.DOTALL);
    private static final String WRAPPED = "$mixinextras$wrapped$";
    private final Path bundle;
    private final JsonObject data;
    private final String headline;
    private final boolean cleaned;
    private final boolean frozen;

    IncidentModel(Path bundle, JsonObject metadata, JsonObject data) {
        this.bundle = bundle;
        this.data = data;
        JsonElement meta = metadata.get("meta");
        JsonElement stored = object(meta).get("headline");
        this.headline = stored != null && stored.isJsonPrimitive() ? stored.getAsString().strip() : "";
        this.cleaned = number(object(meta).get(IncidentJson.CLEANED), 0) >= IncidentJson.CLEANED_SCHEMA;
        this.frozen = text(object(metadata.get("context")), "frozen").equals("true");
        if (!has("incident") && metadata.has("meta")) data.add("incident", meta.deepCopy());
        for (JsonElement item : array("diag")) {
            for (JsonElement row : array(object(item).get("entries"))) {
                JsonArray pair = array(row);
                if (pair.size() > 1 && text(pair.get(0)).equals("Blocked in") && pair.get(1).isJsonPrimitive()) {
                    pair.set(1, new JsonPrimitive(frame(pair.get(1).getAsString())));
                }
            }
        }
    }

    static IncidentModel load(Path bundleZip) throws IOException {
        try (ZipFile zip = new ZipFile(bundleZip.toFile(), StandardCharsets.UTF_8)) {
            var metaEntry = zip.getEntry("incident.json");
            var htmlEntry = zip.getEntry("report.html");
            if (metaEntry == null || htmlEntry == null) throw new IOException("the bundle is missing its incident data.");
            JsonObject metadata;
            try (var reader = new InputStreamReader(zip.getInputStream(metaEntry), StandardCharsets.UTF_8)) {
                metadata = JsonParser.parseReader(reader).getAsJsonObject();
            }
            String html;
            try (var input = zip.getInputStream(htmlEntry)) {
                html = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            var marker = DATA.matcher(html);
            if (!marker.find()) throw new IOException("the bundle has no stored incident data.");
            JsonObject data;
            try (JsonReader reader = new JsonReader(new StringReader(html.substring(marker.end())))) {
                data = JsonParser.parseReader(reader).getAsJsonObject();
            }
            if (!data.has("diag")) {
                JsonArray diagnostics = legacyDiagnostics(html);
                boolean hasCapturedDiagnostics = !diagnostics.isEmpty();
                var blocked = BLOCKED.matcher(html);
                if (diagnostics.isEmpty() && blocked.find()) {
                    JsonObject stalled = new JsonObject(); stalled.addProperty("title", "Stalled thread");
                    JsonArray entries = new JsonArray(); JsonArray frame = new JsonArray();
                    String label = blocked.group(2) == null ? "" : plain(blocked.group(2)).stripTrailing();
                    frame.add("Blocked in"); frame.add(plain(blocked.group(1)) + label); entries.add(frame);
                    stalled.add("entries", entries); diagnostics.add(stalled);
                }
                var heartbeats = zip.getEntry("extras/heartbeats.txt");
                if (!hasCapturedDiagnostics && heartbeats != null) {
                    JsonObject snapshot = new JsonObject();
                    snapshot.addProperty("title", "Heartbeats");
                    snapshot.add("entries", new JsonArray());
                    try (var input = zip.getInputStream(heartbeats)) { snapshot.addProperty("pre", new String(input.readAllBytes(), StandardCharsets.UTF_8)); }
                    diagnostics.add(snapshot);
                }
                data.add("diag", diagnostics);
            }
            IncidentPrivacy.mask(metadata, data);
            return new IncidentModel(bundleZip, metadata, data);
        } catch (IllegalStateException | com.google.gson.JsonParseException failure) {
            throw new IOException("the incident data in the bundle is damaged.", failure);
        }
    }

    private static JsonArray legacyDiagnostics(String html) {
        JsonArray diagnostics = new JsonArray();
        int start = html.indexOf("<section id=\"stalled\"");
        int end = start < 0 ? -1 : html.indexOf("</section>", start);
        if (end < 0) return diagnostics;
        var cards = CARD.matcher(html.substring(start, end));
        while (cards.find()) {
            JsonObject section = new JsonObject();
            section.addProperty("title", plain(cards.group(1)));
            JsonArray entries = new JsonArray();
            var rows = ENTRY.matcher(cards.group(2));
            while (rows.find()) {
                JsonArray pair = new JsonArray();
                pair.add(plain(rows.group(1)));
                pair.add(plain(rows.group(2)));
                entries.add(pair);
            }
            section.add("entries", entries);
            var pre = PRE.matcher(cards.group(2));
            if (pre.find()) section.addProperty("pre", plain(pre.group(1)));
            else section.add("pre", JsonNull.INSTANCE);
            diagnostics.add(section);
        }
        return diagnostics;
    }

    private static String plain(String html) {
        String text = html.replaceAll("<[^>]+>", "").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&nbsp;", " ").replace("&amp;", "&");
        var numeric = Pattern.compile("&#(x[0-9a-fA-F]+|[0-9]+);").matcher(text);
        StringBuilder decoded = new StringBuilder();
        while (numeric.find()) {
            String number = numeric.group(1);
            int cp = Integer.parseInt(number.startsWith("x") ? number.substring(1) : number, number.startsWith("x") ? 16 : 10);
            numeric.appendReplacement(decoded, java.util.regex.Matcher.quoteReplacement(Character.isValidCodePoint(cp) ? new String(Character.toChars(cp)) : "�"));
        }
        return numeric.appendTail(decoded).toString();
    }

    static String frame(String shown) {
        if (shown == null || !shown.contains(WRAPPED)) return shown;
        int note = shown.indexOf(", ");
        if (note < 0) return FrameLabels.display(shown);
        String method = shown.substring(0, note);
        int dot = method.lastIndexOf('.');
        String stripped = dot <= 0 ? method : method.substring(0, dot + 1) + FrameLabels.originalMethod(method.substring(dot + 1));
        return stripped + shown.substring(note);
    }

    Path bundle() { return bundle; }
    JsonElement field(String key) { return data.has(key) ? data.get(key) : JsonNull.INSTANCE; }
    boolean has(String key) { return !field(key).isJsonNull(); }
    JsonObject object(String key) { return object(field(key)); }
    JsonArray array(String key) { return array(field(key)); }
    String text(String key) { return text(field(key)); }
    String id() { return text(object("incident"), "id"); }
    String headline() { return headline; }
    boolean cleaned() { return cleaned; }
    boolean frozen() { return frozen; }
    String blockedIn() {
        for (JsonElement item : array("diag")) {
            JsonObject diagnostic = object(item);
            String title = text(diagnostic, "title");
            if (!title.equals("Stalled thread") && !title.equals("Deadlocked thread")) continue;
            for (JsonElement row : array(diagnostic.get("entries"))) {
                JsonArray pair = array(row);
                if (text(at(pair, 0)).equals("Blocked in")) return text(at(pair, 1));
            }
        }
        return "Not captured";
    }
    static JsonObject object(JsonElement value) { return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject(); }
    static JsonArray array(JsonElement value) { return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray(); }
    static JsonElement at(JsonArray values, int index) { return index >= 0 && index < values.size() ? values.get(index) : JsonNull.INSTANCE; }
    static String text(JsonObject object, String key) { return text(object.get(key)); }
    static String text(JsonElement value) { return value == null || value.isJsonNull() ? "Not captured" : value.isJsonPrimitive() ? value.getAsString() : value.toString(); }
    static double number(JsonElement value, double fallback) {
        try { return value == null || value.isJsonNull() ? fallback : value.getAsDouble(); }
        catch (NumberFormatException | UnsupportedOperationException | IllegalStateException ignored) { return fallback; }
    }
}
