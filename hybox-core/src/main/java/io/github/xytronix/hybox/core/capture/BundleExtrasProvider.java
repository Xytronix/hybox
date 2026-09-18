package io.github.xytronix.hybox.core.capture;

import java.util.List;
import io.github.xytronix.hybox.core.bundle.BundleAttachment;
import io.github.xytronix.hybox.core.incident.IncidentReport;
import io.github.xytronix.hybox.core.trigger.TriggerEvent;

/**
 * Supplies platform-specific attachments to include in incident bundles.
 */
@FunctionalInterface
public interface BundleExtrasProvider {
    List<BundleAttachment> extras(IncidentReport report, TriggerEvent triggerEvent) throws Exception;

    default List<BundleAttachment> historicalExtras() {
        return List.of();
    }

    default List<BundleAttachment> configExtras() {
        return List.of();
    }

    static BundleExtrasProvider none() {
        return (report, triggerEvent) -> List.of();
    }
}

