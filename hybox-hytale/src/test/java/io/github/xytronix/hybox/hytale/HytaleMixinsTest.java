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
import io.github.xytronix.hybox.core.health.JfrTimeline;
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
        assertFalse(reader.knowsOverloads());
        assertEquals(2, reader.mixinsFor(FakeHyinitDiagnostics.WORLD, "tick", "(I)V").size());
        HyinitReader overloads = HyinitReader.load(LOADER, FakeHyinitDiagnostics.Overloads.class.getName());
        assertTrue(overloads.knowsOverloads());
        assertEquals(2, overloads.mixinsFor(FakeHyinitDiagnostics.WORLD, "tick", null).size());
        assertEquals(List.of(), overloads.mixinsFor(FakeHyinitDiagnostics.WORLD, "tick", "(I)V"));

        HyinitReader missing = HyinitReader.load(LOADER, "cc.irori.hyinit.shared.Missing");
        assertFalse(missing.present());
        assertTrue(missing.classMissing());
        assertNull(missing.snapshot());

        HyinitReader failing = HyinitReader.load(LOADER, FakeHyinitDiagnostics.Throwing.class.getName());
        assertTrue(failing.present());
        assertNull(failing.snapshot());
        assertNull(failing.methodMap());
        assertEquals(List.of(), failing.mixinsFor("a.B", "c", null));
    }

    @Test
    void sectionNamesModsCountsMethodsAndListsMethodsSharedByTwoMods() {
        DiagnosticSection section = HytaleMixins.section(FakeHyinitDiagnostics.snapshot(), FakeHyinitDiagnostics.methodMap(), null);
        Map<String, String> entries = section.entries();

        assertEquals("Mixins", section.title());
        assertEquals("Hyinit 0.3.1", entries.get("Bootstrapper"));
        assertEquals(FakeHyinitDiagnostics.REFIXES_SHA, entries.get("Source 1 SHA-256"));
        assertEquals("5", entries.get("Source 1 methods"));
        assertEquals("2", entries.get("Source 2 methods"));
        assertEquals("error", entries.get("Problem 1 level"));
        assertNull(entries.get("Problem 1 stack"));
        assertTrue(entries.get("Problem 2 stack").contains("\tat com.example.region.RegionPlugin.check(RegionPlugin.java:12)\n"),
            entries.get("Problem 2 stack"));
        assertEquals("com.hypixel.hytale.server.core.universe.world.World.tick", entries.get("Overlap 1"));
        assertEquals("Refixes\tcc.irori.refixes.early.mixin.MixinWorld\nRegionManagerMixin\tcom.example.region.mixin.WorldMixin",
            entries.get("Overlap 1 mixins"));
        assertEquals(FakeHyinitDiagnostics.WORLD + ".unload", entries.get("Overlap 2"));
        assertFalse(entries.containsKey("Source 1 apply time (ns)") || entries.containsKey("Source 1 off by settings"));
        Map<String, String> current = HytaleMixins.section(FakeHyinitDiagnostics.Overloads.snapshot(),
            FakeHyinitDiagnostics.methodMap(true), null).entries();
        assertEquals("221289712", current.get("Source 1 apply time (ns)"));
        assertEquals("2", current.get("Source 1 off by settings"));
        assertFalse(current.containsKey("Source 2 apply time (ns)") || current.containsKey("Source 2 off by settings"));
        assertFalse(HytaleMixins.section(FakeHyinitDiagnostics.snapshot(), FakeHyinitDiagnostics.methodMap(true), null)
            .entries().containsKey("Overlap 2"));
    }

    @Test
    void labelsNameTheModAndMixinAndSayChangedForOriginalMethods() {
        HyinitReader reader = HyinitReader.load(LOADER, FakeHyinitDiagnostics.class.getName());
        FrameLabels.Source labels = HytaleMixins.labels(reader);

        FrameLabels.Label changed = labels.label("com.hypixel.hytale.server.core.universe.world.World", "tick", null, 0);
        assertEquals("changed by Mixin from Refixes (MixinWorld), RegionManagerMixin (WorldMixin)", changed.text());
        assertFalse(changed.merged());
        assertEquals(List.of(new FrameLabels.Origin("Refixes", "refixes-0.6.1.jar", List.of("MixinWorld")),
            new FrameLabels.Origin("RegionManagerMixin", "RegionManagerMixin-1.5.0.jar", List.of("WorldMixin"))),
            changed.origins());
        FrameLabels.Label merged = labels.label("com.hypixel.hytale.server.core.universe.world.World",
            "handler$zej000$refixesjar$refixes$tick", null, 0);
        assertEquals("Mixin code from Refixes (MixinWorld)", merged.text());
        assertTrue(merged.merged());
        assertEquals("refixes-0.6.1.jar", merged.origins().get(0).jar());
        assertEquals("changed by Mixin from Refixes (MixinChunk)",
            labels.label("com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk", "load$mixinextras$wrapped$3", null, 0)
                .text());
        assertNull(labels.label("com.hypixel.hytale.server.core.universe.world.World", "save", null, 0));
        assertNull(HytaleMixins.labels(HyinitReader.load(LOADER, "cc.irori.hyinit.shared.Missing")));
    }

    private static final String CHUNK = "com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk";
    private static final String BY_REFIXES = "changed by Mixin from Refixes (MixinWorld)";
    private static final String BY_REGION = "changed by Mixin from RegionManagerMixin (WorldMixin)";
    private static final String BY_BOTH = "changed by Mixin from Refixes (MixinWorld), RegionManagerMixin (WorldMixin)";

    private static String text(FrameLabels.Source labels, String method, String descriptor, int line) {
        FrameLabels.Label label = labels.label(FakeHyinitDiagnostics.WORLD, method, descriptor, line);
        return label == null ? null : label.text();
    }

    @Test
    void labelsFollowTheOverloadByDescriptorOrLineAndEveryOverloadWithoutThem() {
        FrameLabels.Source exact = HytaleMixins.labels(HyinitReader.load(LOADER, FakeHyinitDiagnostics.Overloads.class.getName()));

        assertEquals(BY_REFIXES, text(exact, "unload", "()V", 0));
        assertEquals(BY_REGION, text(exact, "unload", "(Z)V", 0));
        assertNull(text(exact, "unload", "(I)V", 0));
        assertNull(text(exact, "unload", "(I)V", 205));
        assertEquals(BY_REFIXES, text(exact, "unload", null, 200));
        assertEquals(BY_REGION, text(exact, "unload", null, 240));
        assertNull(text(exact, "unload", null, 215));
        assertEquals(BY_BOTH, text(exact, "unload", null, 0));
        assertEquals(BY_BOTH, text(exact, "unload", null, -1));
        assertEquals("Mixin code from Refixes (MixinWorld)", text(exact, "handler$zej000$refixesjar$refixes$tick", null, 999));
        assertEquals("changed by Mixin from Refixes (MixinChunk)", exact.label(CHUNK, "load$mixinextras$wrapped$3", "()V", 0).text());
        assertEquals("changed by Mixin from Refixes (MixinChunk)",
            exact.label(CHUNK, "mixinextras$bridge$load$mixinextras$wrapped$3$4", "([Ljava/lang/Object;)Ljava/lang/Void;", 0).text());

        FrameLabels.Source byName = HytaleMixins.labels(HyinitReader.load(LOADER, FakeHyinitDiagnostics.class.getName()));

        assertEquals(BY_BOTH, text(byName, "unload", "(I)V", 0));
        assertEquals(BY_BOTH, text(byName, "unload", null, 215));
    }

    private static Path record(Path file, Runnable work) throws Exception {
        try (jdk.jfr.Recording recording = new jdk.jfr.Recording()) {
            recording.enable("jdk.ExecutionSample").withPeriod(java.time.Duration.ofMillis(10));
            recording.start();
            Thread world = new Thread(work, "WorldThread - test");
            world.start();
            world.join();
            recording.dump(file);
        }
        return file;
    }

    private static long ownSamples(Path recording) throws Exception {
        return JfrTimeline.parse(recording).mixinCost().hits().stream()
            .filter(hit -> hit.kind().equals(JfrTimeline.MixinCost.OWN)).mapToLong(JfrTimeline.MixinHit::samples).sum();
    }

    @Test
    void samplesInAnOverloadNoMixinTouchedAreNotMixinCostUnlessHyinitOnlyGivesNames() throws Exception {
        Path untouched = record(directory.resolve("untouched.jfr"), () -> FakeHyinitDiagnostics.MixinOverloads.work(600, 1));
        Path added = record(directory.resolve("added.jfr"), () -> FakeHyinitDiagnostics.MixinOverloads.work(600));
        try {
            FrameLabels.install(HytaleMixins.labels(HyinitReader.load(LOADER, FakeHyinitDiagnostics.Overloads.class.getName())));
            assertNull(JfrTimeline.parse(untouched).mixinCost());
            assertTrue(ownSamples(added) > 0);

            FrameLabels.install(HytaleMixins.labels(HyinitReader.load(LOADER, FakeHyinitDiagnostics.class.getName())));
            assertTrue(ownSamples(untouched) > 0);
        } finally {
            FrameLabels.install(null);
        }
    }

    @Test
    void methodMapTextKeepsTheFullChecksumAndEveryChangedMethod() {
        String text = HytaleMixins.methodMapText(FakeHyinitDiagnostics.snapshot(), FakeHyinitDiagnostics.methodMap());

        assertTrue(text.contains("sha256 " + FakeHyinitDiagnostics.REFIXES_SHA));
        assertTrue(text.contains("Columns: method, kind, Mixin, config, JAR\n"), text);
        assertTrue(text.contains("  handler$zej000$refixesjar$refixes$tick  merged  cc.irori.refixes.early.mixin.MixinWorld"
            + "  refixes.mixins.json  refixes-0.6.1.jar\n"), text);
        assertFalse(text.contains("Mixin apply time") || text.contains("off by the mod's settings"), text);
        String overloads = HytaleMixins.methodMapText(FakeHyinitDiagnostics.Overloads.snapshot(),
            FakeHyinitDiagnostics.methodMap(true));
        assertTrue(overloads.contains("  methods 5  Mixin apply time 221 ms  off by the mod's settings 2  configs refixes.mixins.json\n"),
            overloads);
        assertTrue(overloads.contains("RegionManagerMixin-1.5.0.jar  plugin Example:RegionManagerMixin  version 1.5.0  sha256 ?"
            + "  methods 2  configs region_manager.mixins.json\n"), overloads);
        assertTrue(overloads.contains("Columns: method, kind, Mixin, config, JAR, descriptor, lines\n"), overloads);
        assertTrue(overloads.contains("  unload  changed  com.example.region.mixin.WorldMixin  region_manager.mixins.json"
            + "  RegionManagerMixin-1.5.0.jar  (Z)V  220-240\n"), overloads);
        assertTrue(overloads.contains("  refixes.mixins.json  refixes-0.6.1.jar  (F)V  -\n"), overloads);
        assertTrue(text.contains("com.hypixel.hytale.server.core.universe.world.World.tick: Refixes (cc.irori.refixes.early.mixin.MixinWorld)"));
        assertTrue(text.contains("ERROR  Mixin transformation of com.example.Broken failed"));
        assertTrue(text.contains("\n    InvalidInjectionException: no target\n"), text);
        assertTrue(text.contains("and was skipped  (RegionManagerMixin-1.5.0.jar)\n    java.lang.IllegalStateException: boom\n"
            + "    \tat com.example.region.RegionPlugin.check(RegionPlugin.java:12)\n"
            + "    \tat org.spongepowered.asm.mixin.transformer.MixinInfo.<init>(MixinInfo.java:868)\n"), text);
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
