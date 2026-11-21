package io.github.xytronix.hybox.core.json;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import io.github.xytronix.hybox.core.incident.IncidentMetadata;
import io.github.xytronix.hybox.core.incident.IncidentReport;

/**
 * Deterministic JSON writer for incident reports.
 *
 * Field order:
 * - meta: id, createdAt, severity, trigger, world, headline, cleaned
 */
public final class IncidentJson {
    public static final String CLEANED = "cleaned";
    public static final int CLEANED_SCHEMA = 2;

    private IncidentJson() {
    }

    public static void write(IncidentReport report, OutputStream out) throws IOException {
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(out, "out");

        OutputStreamWriter writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        JsonWriter json = new JsonWriter(writer);
        json.beginObject();
        writeMeta(json, report.meta());
        writeContext(json, report.context());
        json.endObject();
        writer.flush();
    }

    private static void writeMeta(JsonWriter json, IncidentMetadata meta) throws IOException {
        json.name("meta").beginObject();
        json.name("id").value(meta.id().value());
        json.name("createdAt").value(meta.createdAt().toString());
        json.name("severity").value(meta.severity().name());
        json.name("trigger").value(meta.trigger());
        json.name("world");
        if (meta.world() == null) {
            json.nullValue();
        } else {
            json.value(meta.world());
        }
        json.name("headline").value(meta.headline());
        json.name(CLEANED).value(CLEANED_SCHEMA);
        json.endObject();
    }


    private static void writeContext(JsonWriter json, Map<String, String> context) throws IOException {
        if (context == null || context.isEmpty()) {
            return;
        }
        json.name("context").beginObject();
        for (Map.Entry<String, String> entry : new TreeMap<>(context).entrySet()) {
            json.name(entry.getKey()).value(entry.getValue());
        }
        json.endObject();
    }
}
