package io.github.xytronix.hybox.core.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jdk.jfr.Recording;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.xytronix.hybox.core.health.JfrTimeline;
import io.github.xytronix.hybox.core.health.JfrTimeline.MixinCost;
import io.github.xytronix.hybox.core.health.JfrTimeline.MixinHit;
import io.github.xytronix.hybox.core.incident.DiagnosticSection;
import io.github.xytronix.hybox.core.incident.IncidentId;
import io.github.xytronix.hybox.core.incident.IncidentMetadata;
import io.github.xytronix.hybox.core.incident.IncidentReport;
import io.github.xytronix.hybox.core.incident.Severity;
import io.github.xytronix.hybox.core.json.JsonWriter;

class ReportMixinCostTest {
    private static final String REFIXES = "refixes-0.6.1.jar";
    private static final String OTHER = "other_mod-2.0.jar";
    private static final MixinSection.Source TWIN = source("BetterPerformance-2.0.jar", "Example:Twin (Fast)");
    private static final MixinSection.Source TWIN_ADDON = source("BetterPerformance-Addon.jar", "Example:Twin (Fast)");
    private static final List<MixinSection.Source> SOURCES = List.of(source(REFIXES, "IroriPowered:Refixes"),
        source(OTHER, null));

    private static final MixinPackages PACKAGES = new MixinPackages(List.of(
        new MixinSection.Config("refixes.mixins.json", REFIXES, "cc.irori.refixes.early.mixin", List.of(), 1, null, 1000,
            true),
        new MixinSection.Config("other.mixins.json", OTHER, "com.example.other.mixin", List.of(), 1, null, 1000, true)));

    private static MixinHit hit(String kind, String key, String method, long samples) {
        return new MixinHit(kind, key, method, List.of(), samples);
    }

    private static MixinSection.Source source(String file, String pluginId) {
        return new MixinSection.Source(file, pluginId, null, null, List.of(), pluginId != null, false, null, 1L, null, null);
    }

    @Test
    void keysResolveToTheEarlyPluginJar() {
        assertEquals(new ReportMixinCost.Jar(REFIXES, "Refixes"), ReportMixinCost.jar("#refixesjar", SOURCES));
        assertEquals(new ReportMixinCost.Jar(REFIXES, "Refixes"), ReportMixinCost.jar("@" + REFIXES, SOURCES));
        assertEquals(new ReportMixinCost.Jar(OTHER, null), ReportMixinCost.jar("#othermodjar", SOURCES));
        assertEquals(new ReportMixinCost.Jar(null, "unknownjar"), ReportMixinCost.jar("#unknownjar", SOURCES));
        assertEquals(new ReportMixinCost.Jar("gone.jar", null), ReportMixinCost.jar("@gone.jar", SOURCES));
    }

    @Test
    void sourceIdKeepsTwelveLettersAndTwoJarsWithOneIdStayApart() {
        assertEquals(new ReportMixinCost.Jar(TWIN.file(), "Twin (Fast)"), ReportMixinCost.jar("#BetterPerfor", List.of(TWIN)));

        List<MixinSection.Source> twins = List.of(TWIN, TWIN_ADDON);
        assertEquals(new ReportMixinCost.Jar(null, "BetterPerfor"), ReportMixinCost.jar("#BetterPerfor", twins));
        MixinCost cost = new MixinCost(100, List.of(
            hit(MixinCost.OWN, "@" + TWIN.file(), "a.B.x", 3),
            hit(MixinCost.OWN, "@" + TWIN_ADDON.file(), "a.B.y", 2)));

        List<ReportMixinCost.Row> rows = ReportMixinCost.rows(cost, twins, PACKAGES);

        assertEquals(List.of(new ReportMixinCost.Jar(TWIN.file(), "Twin (Fast)"),
            new ReportMixinCost.Jar(TWIN_ADDON.file(), "Twin (Fast)")), rows.stream().map(ReportMixinCost.Row::jar).toList());
        assertEquals(List.of(3L, 2L), rows.stream().map(ReportMixinCost.Row::own).toList());
    }

    @Test
    void rowsAddFileAndIdKeysOfOneJarAndRankByOwnCost() {
        MixinCost cost = new MixinCost(200, List.of(
            hit(MixinCost.REACH, "#refixesjar", null, 150),
            hit(MixinCost.REACH, "#refixesjar\n#othermodjar", null, 40),
            hit(MixinCost.REACH, "#refixesjar\n@" + REFIXES, null, 10),
            hit(MixinCost.OWN, "#othermodjar", "a.B.handler$x$othermodjar$tick", 20),
            hit(MixinCost.STARTED, "#othermodjar", "a.B.handler$x$othermodjar$tick", 30),
            hit(MixinCost.OWN, "#refixesjar", "a.C.md1$refixesjar$lambda$x$0", 6),
            hit(MixinCost.OWN, "@" + REFIXES, "a.C.refixes$helper", 2),
            hit(MixinCost.STARTED, "#refixesjar", "a.C.md1$refixesjar$lambda$x$0", 8),
            hit(MixinCost.CHANGED, "#refixesjar", "a.T.run", 200)));

        List<ReportMixinCost.Row> rows = ReportMixinCost.rows(cost, SOURCES, PACKAGES);

        assertEquals(2, rows.size());
        ReportMixinCost.Row other = rows.get(0);
        assertEquals(OTHER, other.jar().file());
        assertEquals(20, other.own());
        assertEquals(30, other.started());
        assertEquals(40, other.reach());
        ReportMixinCost.Row refixes = rows.get(1);
        assertEquals("Refixes", refixes.jar().mod());
        assertEquals(8, refixes.own());
        assertEquals(8, refixes.started());
        assertEquals(200, refixes.reach());
        assertEquals(List.of(Map.entry("a.C.md1$refixesjar$lambda$x$0", 6L), Map.entry("a.C.refixes$helper", 2L)),
            refixes.ownMethods());
        assertEquals(List.of(Map.entry("a.T.run", 200L)), refixes.changedMethods());
    }

    @Test
    void ownCountsClassesOfTheSameJarAndDropsSamplesInClassesOfAnotherJarOrOfNone() {
        String wait = "a.T.redirect$zfj000$refixesjar$refixes$hybridWait";
        MixinCost cost = new MixinCost(1000, List.of(
            hit(MixinCost.OWN, "#refixesjar", wait, 26),
            new MixinHit(MixinCost.OWN, "#refixesjar", wait, List.of("cc.irori.refixes.early.util.PatchCounters$Counter"), 37),
            new MixinHit(MixinCost.OWN, "@" + REFIXES, "a.S.refixes$save",
                List.of("cc.irori.refixes.early.util.CubicTransfer", "cc.irori.refixes.early.SaveCompletions"), 14),
            new MixinHit(MixinCost.OWN, "#refixesjar", wait, List.of("com.example.other.util.Helper"), 5),
            new MixinHit(MixinCost.OWN, "#refixesjar", wait, List.of("org.unknown.Library"), 3),
            new MixinHit(MixinCost.OWN, "#unknownjar", "a.T.handler$x$unknownjar$y", List.of("cc.irori.refixes.early.X"), 2),
            hit(MixinCost.STARTED, "#refixesjar", wait, 85)));

        List<ReportMixinCost.Row> rows = ReportMixinCost.rows(cost, SOURCES, PACKAGES);

        ReportMixinCost.Row refixes = rows.get(0);
        assertEquals(REFIXES, refixes.jar().file());
        assertEquals(77, refixes.own());
        assertEquals(85, refixes.started());
        assertEquals(List.of(Map.entry(wait, 63L), Map.entry("a.S.refixes$save", 14L)), refixes.ownMethods());
        assertEquals(0, rows.get(1).own());
    }

    private static String written(MixinCost cost, Map<String, String> mixins) throws Exception {
        return written(cost, null, mixins);
    }

    private static String written(MixinCost cost, MixinCost pool, Map<String, String> mixins) throws Exception {
        StringBuilder out = new StringBuilder();
        JsonWriter json = new JsonWriter(out);
        json.beginObject();
        ReportMixinCost.write(json, cost, pool, mixins);
        json.endObject();
        return out.toString();
    }

    private static String html(JfrTimeline timeline, List<DiagnosticSection> diagnostics) throws Exception {
        IncidentMetadata meta = new IncidentMetadata(new IncidentId("fixture"), Instant.parse("2026-01-01T00:00:00Z"),
            Severity.INFO, "MANUAL", null, "Manual capture");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ReportHtml.write(new IncidentReport(meta, Map.of(), null, diagnostics), timeline, out);
        return out.toString(StandardCharsets.UTF_8);
    }

    private static long handler$zza000$testjar$spin(long millis) {
        long end = System.nanoTime() + millis * 1_000_000;
        long sum = 0;
        while (System.nanoTime() < end) {
            sum += Long.numberOfTrailingZeros(sum + end);
        }
        return sum;
    }

    @Test
    void reportOfARecordingNamesTheJarOfMixinCodeOnWorldThreadsOnly(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("recording.jfr");
        try (Recording recording = new Recording()) {
            recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10));
            recording.start();
            List<Thread> threads = List.of(
                new Thread(() -> handler$zza000$testjar$spin(600), "WorldThread - test"),
                new Thread(() -> handler$zza000$testjar$spin(600), "ForkJoinPool.commonPool-worker-1"),
                new Thread(() -> handler$zza000$testjar$spin(600), "pool-worker-1"));
            threads.forEach(Thread::start);
            for (Thread thread : threads) {
                thread.join();
            }
            recording.dump(target);
        }
        JfrTimeline timeline = JfrTimeline.parse(target);
        Map<String, String> hyinit = MixinSection.entries("Hyinit 0.5.0",
            new MixinSection.Versions("0.5.0", "earlyPlugin", "0.8.7", "0.17.5", "0.5.5", 1L, 1L, 1L, 1L, 0L),
            List.of(source("test.jar", "Example:Test")), List.of(), List.of(), List.of(), null);

        String html = html(timeline, List.of(new DiagnosticSection(MixinSection.TITLE, hyinit)));

        Matcher row = Pattern.compile("\"mixinCost\":\\{\"worldSamples\":(\\d+),\"rows\":\\[\\{\"file\":\"test.jar\","
            + "\"mod\":\"Test\",\"own\":(\\d+),\"started\":(\\d+),\"reach\":(\\d+),\"ownMethods\":\\[\\[\""
            + Pattern.quote(ReportMixinCostTest.class.getName() + ".handler$zza000$testjar$spin") + "\",(\\d+)]]").matcher(html);
        assertTrue(row.find(), html.substring(Math.max(0, html.indexOf("\"mixinCost\"")), html.indexOf("\"mixinCost\"") + 400));
        long world = Long.parseLong(row.group(1));
        long own = Long.parseLong(row.group(2));
        assertEquals(timeline.worldSamples().get("test"), world);
        Matcher pool = Pattern.compile("\"poolSamples\":(\\d+),\"poolRows\":\\[\\{\"file\":\"test.jar\",\"mod\":\"Test\","
            + "\"own\":(\\d+),").matcher(html);
        assertTrue(pool.find(), html.substring(html.indexOf("\"mixinCost\""), html.indexOf("\"mixinCost\"") + 900));
        long pooled = Long.parseLong(pool.group(1));
        assertTrue(pooled > 0 && Long.parseLong(pool.group(2)) > 0 && Long.parseLong(pool.group(2)) <= pooled, pool.group());
        assertTrue(timeline.totalSamples() > world + pooled, world + " and " + pooled + " of " + timeline.totalSamples());
        assertTrue(own > 0 && own <= world, own + " of " + world);
        assertEquals(own, Long.parseLong(row.group(3)));
        assertEquals(own, Long.parseLong(row.group(4)));
        assertEquals(own, Long.parseLong(row.group(5)));
        assertTrue(html(timeline, List.of()).contains("\"mixinCost\":null"));
    }

    @Test
    void reportDataHasTheRowsTheTemplateAndTheInGamePageReadAndNeedsHyinitAndARecording() throws Exception {
        String wait = "a.T.redirect$zfj000$refixesjar$refixes$hybridWait";
        MixinCost cost = new MixinCost(1000, List.of(
            hit(MixinCost.REACH, "#refixesjar", null, 990),
            new MixinHit(MixinCost.OWN, "#refixesjar", wait, List.of("cc.irori.refixes.early.util.PatchCounters"), 9),
            hit(MixinCost.OWN, "#refixesjar", wait, 5),
            hit(MixinCost.STARTED, "#refixesjar", wait, 98),
            hit(MixinCost.CHANGED, "#refixesjar", "a.T.run", 990)));
        Map<String, String> hyinit = MixinSection.entries("Hyinit 0.5.0",
            new MixinSection.Versions("0.5.0", "earlyPlugin", "0.8.7", "0.17.5", "0.5.5", 1L, 1L, 1L, 1L, 0L),
            SOURCES, List.of(new MixinSection.Config("refixes.mixins.json", REFIXES, "cc.irori.refixes.early.mixin",
                List.of("cc.irori.refixes.early.mixin.MixinTickingThread"), 1, null, 1000, true)),
            List.of(), List.of(), null);

        assertEquals("{\"mixinCost\":{\"worldSamples\":1000,\"rows\":[{\"file\":\"refixes-0.6.1.jar\",\"mod\":\"Refixes\","
            + "\"own\":14,\"started\":98,\"reach\":990,\"ownMethods\":[[\"" + wait + "\",14]],"
            + "\"startedMethods\":[[\"" + wait + "\",98]],\"changedMethods\":[[\"a.T.run\",990]]}]}}",
            written(cost, hyinit));
        assertEquals("{\"mixinCost\":null}", written(cost, Map.of("Bootstrapper", "Hyxin")));
        assertEquals("{\"mixinCost\":null}", written(null, cost, hyinit));
        String pooled = written(cost, new MixinCost(400, List.of(hit(MixinCost.STARTED, "#refixesjar", wait, 250))), hyinit);
        assertTrue(pooled.endsWith(",\"poolSamples\":400,\"poolRows\":[{\"file\":\"refixes-0.6.1.jar\",\"mod\":\"Refixes\","
            + "\"own\":0,\"started\":250,\"reach\":0,\"ownMethods\":[],\"startedMethods\":[[\"" + wait + "\",250]],"
            + "\"changedMethods\":[]}]}}"), pooled);
    }
}
