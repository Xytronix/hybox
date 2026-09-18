package io.github.xytronix.hybox.core.incident;

/**
 * Stable incident identifier wrapper.
 */
public record IncidentId(String value) {
    public IncidentId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("IncidentId value must be non-blank.");
        }
    }
}
