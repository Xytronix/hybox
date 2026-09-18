package io.github.xytronix.hybox.core.bundle;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import io.github.xytronix.hybox.core.capture.BundleExtrasProvider;
import io.github.xytronix.hybox.core.incident.IncidentReport;
import io.github.xytronix.hybox.core.trigger.TriggerEvent;

class BundleExtrasRegistryTest {
    @Test
    void configurationExtrasAreForwardedForRecovery() {
        var registry = new BundleExtrasRegistry(System.getLogger("extras-test"));
        var attachment = new BundleAttachment("extras/config.txt", "configured".getBytes(StandardCharsets.UTF_8));
        registry.register(provider(List.of(attachment)));
        assertEquals(List.of(attachment), registry.configExtras());
    }

    @Test
    void failedAndNullConfigurationProvidersDoNotHideHealthyProviders() {
        var registry = new BundleExtrasRegistry(System.getLogger("extras-test"));
        registry.register(new BundleExtrasProvider() {
            public List<BundleAttachment> extras(IncidentReport report, TriggerEvent event) { return List.of(); }
            public List<BundleAttachment> configExtras() { throw new IllegalStateException("injected"); }
        });
        registry.register(provider(null));
        var attachment = new BundleAttachment("extras/config.txt", new byte[] {1});
        registry.register(provider(List.of(attachment)));
        assertEquals(List.of(attachment), registry.configExtras());
    }

    private static BundleExtrasProvider provider(List<BundleAttachment> attachments) {
        return new BundleExtrasProvider() {
            public List<BundleAttachment> extras(IncidentReport report, TriggerEvent event) { return List.of(); }
            public List<BundleAttachment> configExtras() { return attachments; }
        };
    }
}
