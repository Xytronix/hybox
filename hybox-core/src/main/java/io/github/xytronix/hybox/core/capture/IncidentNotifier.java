package io.github.xytronix.hybox.core.capture;

import java.nio.file.Path;
import io.github.xytronix.hybox.core.incident.IncidentReport;

/**
 * Hook for notifications after an incident bundle is written.
 */
@FunctionalInterface
public interface IncidentNotifier {
    void onIncident(IncidentReport report, Path zip);

    static IncidentNotifier noop() {
        return (report, zip) -> {
        };
    }
}
