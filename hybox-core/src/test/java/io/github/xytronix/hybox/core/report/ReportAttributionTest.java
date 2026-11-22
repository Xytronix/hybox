package io.github.xytronix.hybox.core.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.xytronix.hybox.core.health.JfrTimeline;
import io.github.xytronix.hybox.core.json.JsonWriter;

class ReportAttributionTest {
    @Test
    void mergesPackageFallbackIntoRecordedManifestIdentityWithCombinedThreadShares() throws Exception {
        var mods = List.of(
            new JfrTimeline.ModCpu("cc.irori.refixes", 585,
                "cc.irori.refixes.early.util.ParallelSpatialCollector.collectSequential",
                List.of(new JfrTimeline.ThreadShare("WorldThread - default", 545))),
            new JfrTimeline.ModCpu("IroriPowered:Refixes", 42,
                "cc.irori.refixes.service.ChunkLoaderService.retainSections",
                List.of(new JfrTimeline.ThreadShare("WorldThread - default", 19),
                    new JfrTimeline.ThreadShare("Refixes-Watchdog", 5))),
            new JfrTimeline.ModCpu("com.sun.management", 11, "com.sun.management.GcInfo.get",
                List.of(new JfrTimeline.ThreadShare("Notification Thread", 11))));
        ReportAttribution attribution = new ReportAttribution(mods);
        List<JfrTimeline.ModCpu> canonical = attribution.cpu(mods);

        assertEquals(1, canonical.size());
        assertEquals("IroriPowered:Refixes", canonical.getFirst().id());
        assertEquals(627, canonical.getFirst().samples());
        assertEquals(mods.getFirst().method(), canonical.getFirst().method());
        assertEquals(List.of(new JfrTimeline.ThreadShare("WorldThread - default", 564),
            new JfrTimeline.ThreadShare("Refixes-Watchdog", 5)), canonical.getFirst().threads());
        assertEquals(Map.of("IroriPowered:Refixes", 30L), attribution.counts(
            Map.of("cc.irori.refixes", 10L, "IroriPowered:Refixes", 20L, "com.sun.management", 99L)));

        StringBuilder output = new StringBuilder();
        JsonWriter json = new JsonWriter(output);
        json.beginObject();
        ReportActivityWriter.writeModCpu(json, mods, attribution);
        json.endObject();
        assertTrue(output.toString().contains("\"modCpu\":[[\"IroriPowered:Refixes\",627,"));
        assertFalse(output.toString().contains("[\"cc.irori.refixes\","));
        assertFalse(output.toString().contains("com.sun.management"));
    }

    @Test
    void leavesSharedPackagesUnattributedInsteadOfGuessingAnOwner() {
        ReportAttribution attribution = new ReportAttribution(List.of(
            new JfrTimeline.ModCpu("Vendor:First", 20, "com.shared.library.First.run", List.of()),
            new JfrTimeline.ModCpu("Vendor:Second", 10, "com.shared.library.Second.run", List.of())));

        assertEquals("com.shared.library", attribution.canonical("com.shared.library"));
        assertEquals("Vendor:First", attribution.canonical("Vendor:First"));
        assertNull(attribution.canonical("com.sun.management"));
        assertNull(attribution.canonical("java.lang"));
        assertNull(attribution.canonical("javax.crypto"));
        assertNull(attribution.canonical("jdk.internal"));
        assertNull(attribution.canonical("sun.nio.ch"));
    }
}
