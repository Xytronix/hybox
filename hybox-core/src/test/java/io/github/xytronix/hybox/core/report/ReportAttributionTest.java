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
                List.of(new JfrTimeline.ThreadShare("WorldThread - default", 545)), 500, 900,
                "cc.irori.refixes.early.util.ParallelSpatialCollector.collectSequential",
                List.of(new JfrTimeline.ThreadShare("WorldThread - default", 480)),
                "cc.irori.refixes.early.util.IndexedStorageLifetime.child"),
            new JfrTimeline.ModCpu("IroriPowered:Refixes", 42,
                "cc.irori.refixes.service.ChunkLoaderService.retainSections",
                List.of(new JfrTimeline.ThreadShare("WorldThread - default", 19),
                    new JfrTimeline.ThreadShare("Refixes-Watchdog", 5)), 30, 60,
                "cc.irori.refixes.service.ChunkLoaderService.retainSections",
                List.of(new JfrTimeline.ThreadShare("Refixes-Watchdog", 30)),
                "cc.irori.refixes.service.ChunkLoaderService.retainSections"),
            new JfrTimeline.ModCpu("com.sun.management", 11, "com.sun.management.GcInfo.get",
                List.of(new JfrTimeline.ThreadShare("Notification Thread", 11)), 11, 11,
                "com.sun.management.GcInfo.get", List.of(), "com.sun.management.GcInfo.get"));
        ReportAttribution attribution = new ReportAttribution(mods, Map.of());
        List<JfrTimeline.ModCpu> canonical = attribution.cpu(mods);

        assertEquals(1, canonical.size());
        assertEquals("IroriPowered:Refixes", canonical.getFirst().id());
        assertEquals(627, canonical.getFirst().samples());
        assertEquals(530, canonical.getFirst().self());
        assertEquals(960, canonical.getFirst().total());
        assertEquals(mods.getFirst().method(), canonical.getFirst().method());
        assertEquals(mods.getFirst().selfMethod(), canonical.getFirst().selfMethod());
        assertEquals("cc.irori.refixes.early.util.IndexedStorageLifetime.child", canonical.getFirst().totalMethod());
        assertEquals(List.of(new JfrTimeline.ThreadShare("WorldThread - default", 564),
            new JfrTimeline.ThreadShare("Refixes-Watchdog", 5)), canonical.getFirst().threads());
        assertEquals(List.of(new JfrTimeline.ThreadShare("WorldThread - default", 480),
            new JfrTimeline.ThreadShare("Refixes-Watchdog", 30)), canonical.getFirst().selfThreads());
        assertEquals(Map.of("IroriPowered:Refixes", new JfrTimeline.ModShare(30, 3, 70)), attribution.shares(Map.of(
            "cc.irori.refixes", new JfrTimeline.ModShare(10, 1, 40),
            "IroriPowered:Refixes", new JfrTimeline.ModShare(20, 2, 30),
            "com.sun.management", new JfrTimeline.ModShare(99, 99, 99))));

        StringBuilder output = new StringBuilder();
        JsonWriter json = new JsonWriter(output);
        json.beginObject();
        ReportActivityWriter.writeModCpu(json, mods, attribution, Map.of());
        json.endObject();
        assertTrue(output.toString().contains("\"modCpu\":[[\"IroriPowered:Refixes\",627,"));
        assertTrue(output.toString().contains("[\"Refixes-Watchdog\",5]],530,960,"
            + "\"cc.irori.refixes.early.util.ParallelSpatialCollector.collectSequential\","
            + "[[\"WorldThread - default\",480],[\"Refixes-Watchdog\",30]],null,"
            + "\"cc.irori.refixes.early.util.IndexedStorageLifetime.child\",null]"));
        assertFalse(output.toString().contains("[\"cc.irori.refixes\","));
        assertFalse(output.toString().contains("com.sun.management"));
    }

    @Test
    void leavesSharedPackagesUnattributedInsteadOfGuessingAnOwner() {
        ReportAttribution attribution = new ReportAttribution(List.of(
            new JfrTimeline.ModCpu("Vendor:First", 20, "com.shared.library.First.run", List.of(), 20, 20,
                "com.shared.library.First.run", List.of(), "com.shared.library.First.run"),
            new JfrTimeline.ModCpu("Vendor:Second", 10, "com.shared.library.Second.run", List.of(), 10, 10,
                "com.shared.library.Second.run", List.of(), "com.shared.library.Second.run")), Map.of());

        assertEquals("com.shared.library", attribution.canonical("com.shared.library"));
        assertEquals("Vendor:First", attribution.canonical("Vendor:First"));
        assertNull(attribution.canonical("com.sun.management"));
        assertNull(attribution.canonical("java.lang"));
        assertNull(attribution.canonical("javax.crypto"));
        assertNull(attribution.canonical("jdk.internal"));
        assertNull(attribution.canonical("sun.nio.ch"));
    }

    @Test
    void namesHyinitAndPackagesThatHoldAPluginMainClass() throws Exception {
        ReportAttribution attribution = new ReportAttribution(List.of(), Map.of(
            "IroriPowered:Refixes", "cc.irori.refixes.Refixes",
            "Vendor:First", "com.shared.library.First",
            "Vendor:First #2", "com.shared.library.Again",
            "Vendor:Second", "com.shared.other.Second",
            "Vendor:Third", "com.shared.other.Third",
            "Hytale:Builtin", "com.hypixel.hytale.builtin.Builtin"));

        assertEquals("Hyinit", attribution.canonical("cc.irori.hyinit"));
        assertEquals("IroriPowered:Refixes", attribution.canonical("cc.irori.refixes"));
        assertEquals("Vendor:First", attribution.canonical("com.shared.library"));
        assertEquals("com.shared.other", attribution.canonical("com.shared.other"));
        assertEquals("com.example.mod", attribution.canonical("com.example.mod"));

        var mods = List.of(new JfrTimeline.ModCpu("cc.irori.refixes", 30, "cc.irori.refixes.early.Net.poll",
            List.of(new JfrTimeline.ThreadShare("quiche-conn-*", 30)), 30, 30, "cc.irori.refixes.early.Net.poll",
            List.of(new JfrTimeline.ThreadShare("quiche-conn-*", 20), new JfrTimeline.ThreadShare("Thread", 10)),
            "cc.irori.refixes.early.Net.poll"));
        StringBuilder output = new StringBuilder();
        JsonWriter json = new JsonWriter(output);
        json.beginObject();
        ReportActivityWriter.writeModCpu(json, mods, attribution, Map.of("quiche-conn-*", 3));
        json.endObject();
        assertTrue(output.toString().contains("[\"IroriPowered:Refixes\",30,"), output.toString());
        assertTrue(output.toString().contains("[[\"quiche-conn-*\",20,3],[\"Thread\",10]]"), output.toString());
    }
}
