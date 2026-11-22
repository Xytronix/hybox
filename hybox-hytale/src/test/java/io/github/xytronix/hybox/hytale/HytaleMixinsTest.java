package io.github.xytronix.hybox.hytale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.xytronix.hybox.core.health.FrameLabels;
import io.github.xytronix.hybox.core.incident.DiagnosticSection;

class HytaleMixinsTest {
    private static final ClassLoader LOADER = HytaleMixinsTest.class.getClassLoader();

    @TempDir
    Path directory;

    @Test
    void readerFindsTheApiAndTreatsMissingOrFailingApisAsAbsent() {
        HyinitReader reader = HyinitReader.load(LOADER, FakeHyinitDiagnostics.class.getName());
        assertTrue(reader.present());
        assertEquals("0.3.1", reader.snapshot().get("hyinitVersion"));
        assertEquals(2, reader.mixinsFor("com.hypixel.hytale.server.core.universe.world.World", "tick").size());

        HyinitReader missing = HyinitReader.load(LOADER, "cc.irori.hyinit.shared.Missing");
        assertFalse(missing.present());
        assertTrue(missing.classMissing());
        assertNull(missing.snapshot());

        HyinitReader failing = HyinitReader.load(LOADER, FakeHyinitDiagnostics.Throwing.class.getName());
        assertTrue(failing.present());
        assertNull(failing.snapshot());
        assertNull(failing.methodMap());
        assertEquals(List.of(), failing.mixinsFor("a.B", "c"));
    }

    @Test
    void sectionNamesModsCountsMethodsAndListsMethodsSharedByTwoMods() {
        DiagnosticSection section = HytaleMixins.section(FakeHyinitDiagnostics.snapshot(), FakeHyinitDiagnostics.methodMap(), null);
        Map<String, String> entries = section.entries();

        assertEquals("Mixins", section.title());
        assertEquals("Hyinit 0.3.1", entries.get("Bootstrapper"));
        assertEquals(FakeHyinitDiagnostics.REFIXES_SHA, entries.get("Source 1 SHA-256"));
        assertEquals("3", entries.get("Source 1 methods"));
        assertEquals("1", entries.get("Source 2 methods"));
        assertEquals("error", entries.get("Problem 1 level"));
        assertEquals("com.hypixel.hytale.server.core.universe.world.World.tick", entries.get("Overlap 1"));
        assertEquals("Refixes\tcc.irori.refixes.early.mixin.MixinWorld\nRegionManagerMixin\tcom.example.region.mixin.WorldMixin",
            entries.get("Overlap 1 mixins"));
        assertFalse(entries.containsKey("Overlap 2"));
    }

    @Test
    void labelsNameTheModAndMixinAndSayChangedForOriginalMethods() {
        HyinitReader reader = HyinitReader.load(LOADER, FakeHyinitDiagnostics.class.getName());
        FrameLabels.Source labels = HytaleMixins.labels(reader);

        assertEquals("changed by Mixin from Refixes (MixinWorld), RegionManagerMixin (WorldMixin)",
            labels.label("com.hypixel.hytale.server.core.universe.world.World", "tick"));
        assertEquals("Mixin code from Refixes (MixinWorld)",
            labels.label("com.hypixel.hytale.server.core.universe.world.World", "handler$zej000$refixesjar$refixes$tick"));
        assertEquals("changed by Mixin from Refixes (MixinChunk)",
            labels.label("com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk", "load$mixinextras$wrapped$3"));
        assertNull(labels.label("com.hypixel.hytale.server.core.universe.world.World", "save"));
        assertNull(HytaleMixins.labels(HyinitReader.load(LOADER, "cc.irori.hyinit.shared.Missing")));
    }

    @Test
    void methodMapTextKeepsTheFullChecksumAndEveryChangedMethod() {
        String text = HytaleMixins.methodMapText(FakeHyinitDiagnostics.snapshot(), FakeHyinitDiagnostics.methodMap());

        assertTrue(text.contains("sha256 " + FakeHyinitDiagnostics.REFIXES_SHA));
        assertTrue(text.contains("  handler$zej000$refixesjar$refixes$tick  merged  cc.irori.refixes.early.mixin.MixinWorld"));
        assertTrue(text.contains("com.hypixel.hytale.server.core.universe.world.World.tick: Refixes (cc.irori.refixes.early.mixin.MixinWorld)"));
        assertTrue(text.contains("ERROR  Mixin transformation of com.example.Broken failed"));
    }

    @Test
    void savedSnapshotFromThePreviousRunStillBuildsTheSection() throws Exception {
        Path file = directory.resolve("live").resolve("hyinit.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, new com.google.gson.Gson().toJson(FakeHyinitDiagnostics.snapshot()));

        Map<String, Object> saved = HytaleMixins.readSaved(file);
        DiagnosticSection section = HytaleMixins.section(saved, null, "saved");

        assertEquals("812", section.entries().get("Classes transformed"));
        assertEquals("1000", section.entries().get("Config 1 priority"));
        assertEquals("saved", section.entries().get("Note"));
        assertFalse(section.entries().containsKey("Source 1 methods"));
    }

    @Test
    void dashboardLineCountsErrorsAndWarnings() {
        assertNull(DashboardModel.mixinAttention(new HytaleMixins.ProblemCounts(0, 0)));
        assertEquals("Mixins: 1 error and 2 warnings since startup. Capture an incident to see them.",
            DashboardModel.mixinAttention(new HytaleMixins.ProblemCounts(1, 2)));
    }
}
