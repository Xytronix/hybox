package io.github.xytronix.hybox.hytale;

import io.github.xytronix.hybox.core.json.IncidentJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipFile;
import org.bson.BsonDocument;

final class HyboxIncidents {
    private static final DateTimeFormatter INCIDENT_TIMESTAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss.SSSZ").withLocale(Locale.ROOT);
    private static final Comparator<Incident> NEWEST_FIRST = Comparator
        .comparing((Incident incident) -> incident.createdAt() == null ? Instant.EPOCH : incident.createdAt())
        .thenComparing(Incident::id).reversed();

    record Incident(String id, Path path, Instant createdAt, String kind, String headline,
                    String severity, String world, Long stallMs, boolean frozen, boolean cleaned) {}

    private HyboxIncidents() {
    }

    static List<Incident> recent(Path incidentDir, int limit) throws IOException {
        if (limit <= 0) return List.of();
        List<Incident> incidents = list(incidentDir);
        incidents.sort(NEWEST_FIRST);
        List<Incident> recent = new ArrayList<>(Math.min(limit, incidents.size()));
        for (int i = 0; i < Math.min(limit, incidents.size()); i++) recent.add(details(incidents.get(i)));
        return List.copyOf(recent);
    }

    static List<Incident> within(Path incidentDir, Instant from, Instant to) throws IOException {
        List<Incident> incidents = new ArrayList<>();
        for (Incident incident : list(incidentDir)) {
            Instant at = incident.createdAt();
            if (at != null && !at.isBefore(from) && !at.isAfter(to)) incidents.add(incident);
        }
        incidents.sort(NEWEST_FIRST.reversed());
        return List.copyOf(incidents);
    }

    private static List<Incident> list(Path incidentDir) throws IOException {
        List<Incident> incidents = new ArrayList<>();
        if (!Files.isDirectory(incidentDir)) return incidents;
        try (var entries = Files.list(incidentDir)) {
            entries.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".zip")).forEach(path -> {
                String id = id(path);
                incidents.add(new Incident(id, path, createdAt(id), null, "headline unavailable", null, null, null,
                    false, false));
            });
        }
        return incidents;
    }

    static String id(Path path) {
        String filename = path.getFileName().toString();
        String id = filename.endsWith(".zip") ? filename.substring(0, filename.length() - 4) : filename;
        return id.startsWith("incident-") ? id.substring("incident-".length()) : id;
    }

    static Instant createdAt(String id) {
        int suffix = id.lastIndexOf('-');
        if (suffix <= 0) return null;
        try {
            return OffsetDateTime.parse(id.substring(0, suffix), INCIDENT_TIMESTAMP).toInstant();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    static Incident details(Incident incident) {
        try (ZipFile zip = new ZipFile(incident.path().toFile())) {
            var entry = zip.getEntry("incident.json");
            if (entry == null) return incident;
            BsonDocument document;
            try (var input = zip.getInputStream(entry)) {
                document = BsonDocument.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
            BsonDocument meta = document.getDocument("meta");
            String headline = IncidentPrivacy.text(string(meta, "headline", incident.headline()));
            String kind = string(meta, "trigger", null);
            String severity = string(meta, "severity", null);
            String world = IncidentPrivacy.text(string(meta, "world", null));
            Instant at = incident.createdAt();
            String created = string(meta, "createdAt", null);
            if (at == null && created != null) at = Instant.parse(created);
            var cleaned = meta.get(IncidentJson.CLEANED);
            BsonDocument context = document.containsKey("context") && document.get("context").isDocument()
                ? document.getDocument("context") : new BsonDocument();
            return new Incident(incident.id(), incident.path(), at, kind, headline, severity, world, stallMs(context),
                "true".equals(string(context, "frozen", null)),
                cleaned != null && cleaned.isNumber() && cleaned.asNumber().intValue() >= IncidentJson.CLEANED_SCHEMA);
        } catch (IOException | RuntimeException ignored) {
            return incident;
        }
    }

    private static Long stallMs(BsonDocument context) {
        var value = context.get("stallMs");
        if (value == null) return null;
        if (value.isNumber()) return value.asNumber().longValue();
        if (!value.isString()) return null;
        try {
            return Math.round(Double.parseDouble(value.asString().getValue().strip()));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String string(BsonDocument document, String key, String fallback) {
        var value = document.get(key);
        return value != null && value.isString() ? value.asString().getValue() : fallback;
    }
}
