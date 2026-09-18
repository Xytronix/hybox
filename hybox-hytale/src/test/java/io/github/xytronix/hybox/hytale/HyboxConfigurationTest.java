package io.github.xytronix.hybox.hytale;

import static org.junit.jupiter.api.Assertions.*;

import com.hypixel.hytale.common.plugin.PluginManifest;
import com.hypixel.hytale.common.plugin.PluginManifest.ServerVersionCheck;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.DoubleSupplier;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HyboxConfigurationTest {
    @Test
    void packagedManifestTargetsHytale067() throws Exception {
        try (var input = getClass().getResourceAsStream("/manifest.json")) {
            assertNotNull(input);
            var manifest = PluginManifest.CODEC.decode(BsonDocument.parse(
                new String(input.readAllBytes(), StandardCharsets.UTF_8)));
            assertEquals("Hybox", manifest.getName());
            assertEquals("Xytronix", manifest.getGroup());
            assertEquals(ServerVersionCheck.COMPATIBLE,
                PluginManifest.checkServerVersionCompatibility(manifest.getServerVersion(), "0.6.7"));
            assertEquals(ServerVersionCheck.INCOMPATIBLE,
                PluginManifest.checkServerVersionCompatibility(manifest.getServerVersion(), "0.5.6"));
            assertEquals(HyboxPlugin.class.getName(), manifest.getMain());
        }
    }

    @Test
    void nativeApiExposesInstrumentation() throws Exception {
        var api = HyboxApi.class;
        assertNotNull(api.getMethod("recordEvent", String.class, String.class, String.class));
        assertNotNull(api.getMethod("recordGauge", String.class, String.class, double.class));
        assertNotNull(api.getMethod("recordCount", String.class, String.class, long.class));
        assertTrue(AutoCloseable.class.isAssignableFrom(
            api.getMethod("registerGauge", String.class, String.class, DoubleSupplier.class).getReturnType()));
    }

    @Test
    void newInstallCreatesOnlyHyboxConfiguration(@TempDir Path dir) throws Exception {
        assertEquals(dir.resolve("hybox.json"), HytaleHyboxConfig.path(dir));
        assertTrue(HytaleHyboxConfig.loadOrCreate(dir, System.getLogger("config-test")).capturePolicy().enabled());
        try (var files = Files.list(dir)) {
            assertEquals(java.util.List.of("hybox.json"), files.map(path -> path.getFileName().toString()).toList());
        }
    }

    @Test
    void unrelatedConfigurationIsNotAdoptedOrModified(@TempDir Path dir) throws Exception {
        var unrelated = dir.resolve("previous.json");
        var contents = "{\"Version\":1,\"Capture\":{\"Enabled\":false}}";
        Files.writeString(unrelated, contents);
        assertEquals(dir.resolve("hybox.json"), HytaleHyboxConfig.path(dir));
        assertTrue(HytaleHyboxConfig.loadOrCreate(dir, System.getLogger("config-test")).capturePolicy().enabled());
        assertEquals(contents, Files.readString(unrelated));
        assertTrue(Files.exists(dir.resolve("hybox.json")));
    }

    @Test
    void existingHyboxConfigurationIsPreserved(@TempDir Path dir) throws Exception {
        var path = dir.resolve("hybox.json");
        var contents = "{\"Version\":1,\"Capture\":{\"Enabled\":false}}";
        Files.writeString(path, contents);
        assertFalse(HytaleHyboxConfig.loadOrCreate(dir, System.getLogger("config-test")).capturePolicy().enabled());
        assertEquals(contents, Files.readString(path));
    }

    @Test
    void malformedConfigurationDoesNotSilentlyEnableDefaultCapture(@TempDir Path dir) throws Exception {
        var config = HytaleHyboxConfig.path(dir);
        Files.writeString(config, "{broken");
        assertThrows(IllegalStateException.class,
            () -> HytaleHyboxConfig.loadOrCreate(dir, System.getLogger("config-test")));
        assertEquals("{broken", Files.readString(config));
    }
}
