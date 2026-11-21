package io.github.xytronix.hybox.core.capture;

import java.nio.file.Path;
import io.github.xytronix.hybox.core.incident.IncidentMetadata;
import io.github.xytronix.hybox.core.incident.IncidentReport;

@FunctionalInterface
public interface IncidentNotifier {
    void onIncident(IncidentReport report, Path zip);

    default void onAccepted(IncidentMetadata meta) {
    }

    default void onFailed(IncidentMetadata meta) {
    }

    static IncidentNotifier noop() {
        return (report, zip) -> {
        };
    }
}
