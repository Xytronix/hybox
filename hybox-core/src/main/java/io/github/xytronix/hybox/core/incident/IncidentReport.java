package io.github.xytronix.hybox.core.incident;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.github.xytronix.hybox.core.health.HealthSnapshot;

/**
 * Full report data used to build an incident bundle.
 */
public record IncidentReport(
    IncidentMetadata meta,
    Map<String, String> context,
    HealthSnapshot snapshot,
    List<DiagnosticSection> diagnostics
) {
    public IncidentReport {
        Objects.requireNonNull(meta, "meta");
        Objects.requireNonNull(context, "context");
        context = Map.copyOf(context);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    public IncidentReport(IncidentMetadata meta, Map<String, String> context, HealthSnapshot snapshot) {
        this(meta, context, snapshot, List.of());
    }

    public IncidentReport(IncidentMetadata meta, Map<String, String> context) {
        this(meta, context, null, List.of());
    }

    public IncidentReport(IncidentMetadata meta) {
        this(meta, Map.of(), null, List.of());
    }
}
