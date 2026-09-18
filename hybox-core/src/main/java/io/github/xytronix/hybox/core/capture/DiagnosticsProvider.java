package io.github.xytronix.hybox.core.capture;

import java.util.List;

import io.github.xytronix.hybox.core.incident.DiagnosticSection;

@FunctionalInterface
public interface DiagnosticsProvider {
    List<DiagnosticSection> sections();

    static DiagnosticsProvider none() {
        return List::of;
    }
}
