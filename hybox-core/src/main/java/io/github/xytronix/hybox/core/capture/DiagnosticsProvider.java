package io.github.xytronix.hybox.core.capture;

import java.util.Collection;
import java.util.List;

import io.github.xytronix.hybox.core.incident.DiagnosticSection;

@FunctionalInterface
public interface DiagnosticsProvider {
    List<DiagnosticSection> sections();

    default List<DiagnosticSection> recoveredSections() {
        return sections();
    }

    default Collection<String> knownVersions() {
        return List.of();
    }

    static DiagnosticsProvider none() {
        return List::of;
    }
}
