package io.github.xytronix.hybox.hytale;

import static org.junit.jupiter.api.Assertions.*;

import com.hypixel.hytale.common.plugin.PluginManifest;
import java.nio.charset.StandardCharsets;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;
import io.github.xytronix.hybox.core.metrics.HealthGauges;
import io.github.xytronix.hybox.core.metrics.PrometheusExporter;
import sun.misc.Unsafe;

class HyboxIdentityTest {
    @Test
    void manifestUsesHyboxEntryPoint() throws Exception {
        try (var input = getClass().getResourceAsStream("/manifest.json")) {
            assertNotNull(input);
            var manifest = PluginManifest.CODEC.decode(BsonDocument.parse(
                new String(input.readAllBytes(), StandardCharsets.UTF_8)));
            assertEquals("io.github.xytronix.hybox.hytale.HyboxPlugin", manifest.getMain());
        }
    }

    @Test
    void prometheusExportsOnlyHyboxMetricNames() {
        String rendered = PrometheusExporter.render(new HealthGauges());
        assertTrue(rendered.contains("hybox_up 1"));
        var samples = rendered.lines().filter(line -> !line.isBlank() && !line.startsWith("#")).toList();
        assertFalse(samples.isEmpty());
        assertTrue(samples.stream().allMatch(line -> line.startsWith("hybox_")), rendered);
    }

    @Test
    void embeddedResourcesUseHyboxNamespace() throws Exception {
        try (var input = getClass().getResourceAsStream("/io/github/xytronix/hybox/core/version.txt")) {
            assertNotNull(input);
            String version = new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
            assertTrue(version.matches("\\d+\\.\\d+\\.\\d+"), version);
        }
    }

    @Test
    void commandHasNoCompatibilityAliases() throws Exception {
        var field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var runtime = (HyboxRuntime) ((Unsafe) field.get(null)).allocateInstance(HyboxRuntime.class);
        var command = new HyboxCommand(runtime);
        assertTrue(command.getAliases().isEmpty(), command.getAliases().toString());
    }
}
