package io.github.xytronix.hybox.core.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import io.github.xytronix.hybox.core.health.HealthSnapshot;
import io.github.xytronix.hybox.core.incident.DiagnosticSection;
import io.github.xytronix.hybox.core.incident.IncidentId;
import io.github.xytronix.hybox.core.incident.IncidentIds;
import io.github.xytronix.hybox.core.incident.IncidentMetadata;
import io.github.xytronix.hybox.core.incident.IncidentReport;
import io.github.xytronix.hybox.core.incident.Severity;

class ReportHtmlTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    private static IncidentReport report(HealthSnapshot snapshot) {
        return report(snapshot, List.of());
    }

    private static IncidentReport report(HealthSnapshot snapshot, List<DiagnosticSection> diagnostics) {
        IncidentId id = IncidentIds.next(CLOCK);
        IncidentMetadata meta = new IncidentMetadata(
            id, CLOCK.instant(), Severity.DEGRADED, "HEARTBEAT_STALL", "default", "Test headline");
        return new IncidentReport(meta, Map.of("stallMs", "2146"), snapshot, diagnostics);
    }

    private static String render(IncidentReport r) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ReportHtml.write(r, out);
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void rendersSnapshotDataAndHeader() throws Exception {
        HealthSnapshot snap = new HealthSnapshot(
            30,
            32,
            new HealthSnapshot.Cpu(8),
            new HealthSnapshot.Memory(30_000_000_000L, 40_000_000_000L, 42_000_000_000L,
                500_000_000L, 600_000_000L),
            List.of(new HealthSnapshot.Gc("Shenandoah Pauses", 362, 44)),
            new HealthSnapshot.Sys("Linux 6.8 (amd64)", "OpenJDK 25", 64_000_000_000L, 3_600_000L, 2.5),
            new HealthSnapshot.Disk(50_000_000_000L, 100_000_000_000L),
            new HealthSnapshot.Threads(238, Map.of("RUNNABLE", 44, "WAITING", 96), List.of()),
            10,
            List.of(new HealthSnapshot.World("default", 10, 250, 1200, 25.1, 35.0,
                List.of("Shebao", "PianoManu"), -1, 33.2, 48.9, 112.5, 5000, 8000, 30,
                HealthSnapshot.Radius.of(List.of(new HealthSnapshot.PlayerLoad("player-1", 12, 8, 9000),
                    new HealthSnapshot.PlayerLoad("player-2", 6, 4, 2000)), List.of(8, 4))),
                new HealthSnapshot.World("lobby", 0, 4, 10, 20, 2, List.of(), -1, 2, 3, 4, 0, 0, 20, null)),
            List.of(new HealthSnapshot.HotThread("WorldThread - default", 1234, "AStarBase.computePath")));

        String html = render(report(snap));

        assertTrue(html.contains("Hybox Incident Report"));
        assertTrue(html.contains("World <i>default</i> stopped responding for <span class=\"ms\">2.1 s</span>"));
        assertTrue(html.contains("\"vitals\":{\"world\":null,"));
        assertTrue(html.contains("DEGRADED"));
        assertTrue(html.contains("<span class=\"dot\"></span>Degraded</span><span class=\"pill\">Trigger <b>Freeze</b></span>"));
        assertFalse(html.contains("Trigger <b>HEARTBEAT_STALL</b>"));
        assertTrue(html.contains("\"triggerLabel\":\"Freeze\""));
        assertTrue(html.contains("<div class=\"hl-end\"><span class=\"incid\">Incident <b>"));
        assertTrue(html.contains("</b></span><span id=\"copySlot\"></span></div></div>"));
        assertTrue(html.contains("\"RUNNABLE\":44"));
        assertTrue(html.contains("WorldThread - default"));
        assertTrue(html.contains("AStarBase.computePath"));
        assertTrue(html.contains("Shebao"));
        assertTrue(html.contains("\"players\":10"));
        assertTrue(html.contains("\"entities\":250"));
        assertTrue(html.contains("\"chunks\":1200"));
        assertTrue(html.contains("\"msptP50\":33.2"));
        assertTrue(html.contains("\"msptP95\":48.9"));
        assertTrue(html.contains("\"msptMax\":112.5"));
        assertTrue(html.contains("\"committed\":40000000000"));
        assertTrue(html.contains("\"nonHeap\":{\"used\":500000000,\"committed\":600000000}"));
        assertTrue(html.contains("\"swap\":null"));
        assertTrue(html.contains("\"hostMemUsed\":null"));
        assertFalse(html.contains("\"arch\":null"));
        assertTrue(html.contains("\"loadAvg\""));
        assertTrue(html.contains("\"openFds\""));
        assertTrue(html.contains("\"cpuset\""));
        assertTrue(html.contains("\"gcByCollector\":[[\"Shenandoah Pauses\",362,44]]"));
        assertTrue(html.contains("\"maxViewRadius\":32"));
        assertTrue(html.contains("\"tpsMixed\":true"));
        assertTrue(html.contains("\"logScale\":{\"span\":"));
        assertTrue(html.contains("\"families\":[\"tick\",\"gcPause\",\"alloc\",\"network\",\"heap\",\"loaded\",\"packets\"]"));
        assertTrue(html.contains("\"targetTps\":30,\"radius\":{\"players\":2,\"view\":[6,6,12],\"hot\":[4,4,8],"
            + "\"sections\":11000,\"sectionsMax\":9000,\"hotLowered\":1,\"hotLoweredTo\":4,"
            + "\"top\":[[\"player-1\",12,8,9000],[\"player-2\",6,4,2000]]}"));
        assertTrue(html.contains("\"targetTps\":20,\"radius\":null"));
        assertFalse(html.contains("Shebao\",12"));
        assertFalse(html.contains("<script src="));
    }

    @Test
    void onlineTotalUsesTheUniverseCountWhilePlayersAreBetweenWorlds() throws Exception {
        String html = render(report(playersSnapshot(3, List.of(
            new HealthSnapshot.World("default", 1, 250, 1200, 30, 33),
            new HealthSnapshot.World("instance-forgotten-temple", 0, 40, 100, 30, 33)))));

        assertTrue(html.contains("<b>3</b> players online at capture"));
        assertTrue(html.contains("\"players\":3}"));
        assertTrue(html.contains("\"stalled\":true,\"players\":1,"));
        assertTrue(html.contains("\"stalled\":false,\"players\":0,"));
    }

    @Test
    void onlineTotalFallsBackToTheWorldSumWithoutAUniverseCount() throws Exception {
        String html = render(report(playersSnapshot(-1, List.of(
            new HealthSnapshot.World("default", 1, 250, 1200, 30, 33),
            new HealthSnapshot.World("lobby", 0, 40, 100, 30, 33)))));

        assertTrue(html.contains("<b>1</b> player online at capture"));
        assertTrue(html.contains("\"players\":1}"));
    }

    @Test
    void profileBundleShowsItsModeAndWritesItToTheData() throws Exception {
        IncidentMetadata meta = new IncidentMetadata(IncidentIds.next(CLOCK), CLOCK.instant(), Severity.INFO, "MANUAL",
            null, "Profile capture");
        Map<String, String> context = new LinkedHashMap<>();
        context.put("reason", "profile session");
        context.put("profileStartMs", "0");
        context.put("profileMode", "memory");

        String html = render(new IncidentReport(meta, context, null, List.of()));

        assertTrue(html.contains("<span class=\"pill\">Profile <b>memory</b></span>"));
        assertTrue(html.contains("\"profileMode\":\"memory\""));
        assertFalse(html.contains("profileStartMs <b>"));
    }

    @Test
    void incidentSavedDuringAProfileSaysSo() throws Exception {
        IncidentMetadata meta = new IncidentMetadata(IncidentIds.next(CLOCK), CLOCK.instant(), Severity.DEGRADED,
            "HEARTBEAT_STALL", "default", "Test headline");

        String html = render(new IncidentReport(meta, Map.of("stallMs", "2146", "duringProfile", "lag"), null,
            List.of()));

        assertTrue(html.contains("<span class=\"pill\">During profile (<b>lag</b>)</span>"));
        assertTrue(html.contains("\"profileMode\":null"));
    }

    @Test
    void legacyProfileBundleHasNoMode() throws Exception {
        IncidentMetadata meta = new IncidentMetadata(IncidentIds.next(CLOCK), CLOCK.instant(), Severity.INFO, "MANUAL",
            null, "Profile capture");

        String html = render(new IncidentReport(meta, Map.of("reason", "profile session", "profileStartMs", "0"), null,
            List.of()));

        assertTrue(html.contains("\"profileMode\":null"));
        assertFalse(html.contains("Profile <b>"));
        assertFalse(html.contains("During profile"));
    }

    private static HealthSnapshot playersSnapshot(int onlinePlayers, List<HealthSnapshot.World> worlds) {
        return new HealthSnapshot(30, -1, null, null, List.of(), null, null,
            new HealthSnapshot.Threads(10, Map.of(), List.of()), onlinePlayers, worlds, List.of());
    }

    @Test
    void rendersWithoutSnapshot() throws Exception {
        String html = render(report(null));

        assertTrue(html.contains("World <i>default</i> stopped responding for <span class=\"ms\">2.1 s</span>"));
        assertTrue(html.contains("\"sys\":null"));
        assertTrue(html.contains("\"worlds\":[]"));
        assertTrue(html.contains("\"gcCauses\":null"));
        assertTrue(html.contains("\"gcOverlap\":null"));
        assertTrue(html.contains("\"flame\":null"));
        assertTrue(html.contains("\"threadTimeline\":null"));
        assertTrue(html.contains("\"heapHistogram\":null"));
        assertTrue(html.contains("\"entities\":null"));
        assertTrue(html.contains("\"memPools\":null"));
        assertTrue(html.contains("\"worldTps\":null"));
        assertTrue(html.contains("\"slowIo\":null"));
    }

    @Test
    void missingRecordingPreservesWarningWithoutCaptureSummary() throws Exception {
        IncidentMetadata meta = report(null).meta();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ReportHtml.write(new IncidentReport(meta), null, "recording file is empty", out);
        String html = out.toString(StandardCharsets.UTF_8);
        String markup = html.substring(0, html.indexOf("<script>"));

        assertTrue(markup.contains("class=\"cwarn\" id=\"jfr-unavailable\""));
        assertTrue(markup.contains("This bundle has no usable recording: recording file is empty."));
        assertTrue(markup.contains("The server log from the capture time has the cause."));
        assertFalse(markup.contains("Capture summary"));
        assertFalse(html.contains("\"summary\":"));
        assertFalse(markup.contains("<!--__SUMMARY__-->"));
    }

    @Test
    void rendersGenericDiagnosticsRatherThanOnlyEmbeddingTheirData() throws Exception {
        String html = render(report(null, List.of(
            new DiagnosticSection("Heartbeats", Map.of("default", "2026-01-01T00:00:00Z (2409 ms ago)")),
            new DiagnosticSection("Thread progress", Map.of("Dumps compared", "3", "Verdict", "1 thread did not move",
                "worker-1", "RUNNABLE @ at example.Loop.spin(Loop.java:1)")),
            new DiagnosticSection("Capture failure", Map.of("Reason", "JFR <not available>"),
                "capture stack <diagnostic>"))));
        String markup = html.substring(0, html.indexOf("<script>"));

        assertTrue(markup.contains("<b>Heartbeats</b>"));
        assertTrue(markup.contains("<span class=\"k\">default</span><span class=\"v\">2.4 s late</span>"), markup);
        assertFalse(markup.contains("(2409 ms ago)"));
        assertTrue(markup.contains("<b>Thread progress</b>"));
        assertTrue(markup.contains("<span class=\"k\">Dumps compared</span><span class=\"v\">3</span>"));
        assertTrue(markup.contains("JFR &lt;not available&gt;"));
        assertTrue(markup.contains("capture stack &lt;diagnostic&gt;"));
        assertFalse(markup.contains("<!--__DIAGNOSTICS__-->"));

        String quiet = render(report(null, List.of(
            new DiagnosticSection("Heartbeats", Map.of("default", "2026-01-01T00:00:00Z (38 ms ago)",
                "instance-a", "2026-01-01T00:00:00Z (12 ms ago)", "instance-b_c", "2026-01-01T00:00:00Z (3100 ms ago)")),
            new DiagnosticSection("Thread progress", Map.of("Dumps compared", "3", "Verdict", "1 thread did not move",
                "JLine reader", "RUNNABLE @ at java.base/java.io.FileInputStream.read0(Native Method)")))));
        String quietMarkup = quiet.substring(0, quiet.indexOf("<script>"));
        assertTrue(quietMarkup.contains("<span class=\"k\">b c</span><span class=\"v\">3.1 s late</span></div><div class=\"row\">"
            + "<span class=\"k\">The other 2 worlds</span><span class=\"v\">on time, the oldest heartbeat was 38 ms old</span>"),
            quietMarkup);
        assertFalse(quietMarkup.contains("(38 ms ago)"));
        assertTrue(quietMarkup.contains("<span class=\"k\">Verdict</span><span class=\"v\">no thread stuck. Threads that did not move wait by design.</span>"), quietMarkup);
        assertFalse(quietMarkup.contains("JLine reader"));
    }

    @Test
    void emitsRecoveryNoteOnlyForUncleanShutdown() throws Exception {
        IncidentId id = IncidentIds.next(CLOCK);
        IncidentReport recovered = new IncidentReport(new IncidentMetadata(
            id, CLOCK.instant(), Severity.DEGRADED, "UNCLEAN_SHUTDOWN", null, "Recovered"));

        String html = render(recovered);

        assertTrue(html.contains(
            "\"note\":\"The server did not shut down cleanly. Hybox rebuilt this bundle at the next start. "
                + "Values are the last ones recorded before the stop.\""));
        assertTrue(render(report(null)).contains("\"note\":null"));
    }

    @Test
    void routesMemoryPools() throws Exception {
        LinkedHashMap<String, String> pools = new LinkedHashMap<>();
        pools.put("G1 Eden Space", "1024 2048 4096 512");
        pools.put("Metaspace", "300 400 -1 -1");
        pools.put("broken.Pool", "not numbers");

        String html = render(report(null, List.of(new DiagnosticSection("Memory pools", pools))));

        assertTrue(html.contains(
            "\"memPools\":[[\"G1 Eden Space\",1024,2048,4096,512],[\"Metaspace\",300,400,-1,-1]]"));
        assertFalse(html.contains("\"title\":\"Memory pools\""));
        assertFalse(html.substring(0, html.indexOf("<script>")).contains("<b>Memory pools</b>"));
    }

    @Test
    void stalledThreadSectionRendersStackAndHeaderHighlight() throws Exception {
        LinkedHashMap<String, String> entries = new LinkedHashMap<>();
        entries.put("Thread", "WorldThread - default");
        entries.put("State", "WAITING");
        entries.put("Blocked in", "com.example.Plugin.onEntityRemove");
        String stack = "at jdk.internal.misc.Unsafe.park(Native Method)\n"
            + "at com.example.Plugin.onEntityRemove(Plugin.java:1)";

        String html = render(report(null, List.of(new DiagnosticSection("Stalled thread", entries, stack))));

        assertTrue(html.contains("<b>Stalled thread</b>"));
        assertTrue(html.contains("aria-label=\"Stalled thread stack or capture text\""));
        assertTrue(html.contains(stack));
        assertTrue(html.contains("Waiting in <code>com.example.Plugin.onEntityRemove</code>"), html);
        assertTrue(html.contains("<span class=\"k\">State</span><span class=\"v\">Waiting</span>"), html);
        assertTrue(html.contains("<span class=\"k\">Waiting in</span>"), html);
    }

    @Test
    void blockedInShowsTheOriginalMethodAndItsMixinLabel() throws Exception {
        String labelled = render(report(null, List.of(new DiagnosticSection("Stalled thread", Map.of("State", "BLOCKED",
            "Blocked in", "com.x.TickingThread.run, changed by Mixin from Refixes (MixinTickSleep)"), "at x"))));
        assertTrue(labelled.contains(
            "Blocked in <code>com.x.TickingThread.run</code>, changed by Mixin from Refixes (MixinTickSleep)</div>"));

        String raw = render(report(null, List.of(new DiagnosticSection("Stalled thread", Map.of("State", "TIMED_WAITING",
            "Blocked in", "com.x.TickingThread.run$mixinextras$wrapped$39"), "at x"))));
        assertTrue(raw.contains("Waiting in <code>com.x.TickingThread.run</code></div>"));
    }

    @Test
    void routesSectionsByTitle() throws Exception {
        LinkedHashMap<String, String> mixins = new LinkedHashMap<>();
        mixins.put("Bootstrapper", "ExampleLoader 1.0.0");
        mixins.put("example.mixins.json", "MixinA, MixinB");
        mixins.put("Conflict: com.hypixel.hytale.X",
            "example.mixins.json → MixinX; other.mixins.json → MixinY; plain.mixins.json");
        LinkedHashMap<String, String> plugins = new LinkedHashMap<>();
        plugins.put("Harold:Hybox", "0.2.0 @ >=0.5.0 @ COMPATIBLE");
        plugins.put("ExampleVendor:ExamplePlugin", "0.4.4 @ 0.5.3 @ INCOMPATIBLE");
        plugins.put("Hytale:NPC", "1.0.0");
        LinkedHashMap<String, String> pluginConfig = new LinkedHashMap<>();
        pluginConfig.put("ExampleFeature.Enabled", "true");
        LinkedHashMap<String, String> tickSystems = new LinkedHashMap<>();
        tickSystems.put("EntityTickingSystem @ default", "5.10 12.40 4.80 30.00");
        LinkedHashMap<String, String> env = new LinkedHashMap<>();
        env.put("ExampleLoader", "installed (1.0.0)");
        env.put("Server name", "ExampleServer");
        env.put("Hytale version", "0.5.4");
        env.put("Container runtime", "Docker");
        String log = "[2026/06/06 15:41:09   WARN] [Spawning] Removing NPC\n"
            + "[2026/06/06 15:41:10 SEVERE] [ExamplePlugin] Uncaught exception\n"
            + "\tat com.example.plugin.ai.ExampleSystem.tick(ExampleSystem.java:142)\n"
            + "[2026/06/06 15:41:11   INFO] [Vote] Player ready";
        LinkedHashMap<String, String> heapHistogram = new LinkedHashMap<>();
        heapHistogram.put("[B", "1234567 987654321");
        heapHistogram.put("java.lang.String", "234 5678");
        heapHistogram.put("broken.Row", "not numbers");
        LinkedHashMap<String, String> entities = new LinkedHashMap<>();
        entities.put("default", "Zombie x12, Cow x5, malformed");

        IncidentReport r = report(null, List.of(
            new DiagnosticSection("Mixins", mixins),
            new DiagnosticSection("Plugins", plugins),
            new DiagnosticSection("Server log", Map.of(), log),
            new DiagnosticSection("ExamplePlugin", pluginConfig),
            new DiagnosticSection("Tick systems", tickSystems),
            new DiagnosticSection("Heap histogram", heapHistogram),
            new DiagnosticSection("Entities", entities),
            new DiagnosticSection("Environment", env),
            new DiagnosticSection("mods/ExampleVendor_ExamplePlugin/config.json", Map.of(), "{\"a\":1}")));

        String html = render(r);

        assertTrue(html.contains("\"bootstrapper\":\"ExampleLoader 1.0.0\""));
        assertTrue(html.contains("[\"example.mixins.json\",[\"MixinA\",\"MixinB\"]]"));
        assertTrue(html.contains(
            "\"conflicts\":[[\"com.hypixel.hytale.X\",[[\"example.mixins.json\",\"MixinX\"],"
            + "[\"other.mixins.json\",\"MixinY\"],[\"plain.mixins.json\",null]]]]"));
        assertTrue(html.contains("[\"Harold\",\"Hybox\",\"0.2.0\",false,\">=0.5.0\",\"COMPATIBLE\"]"));
        assertTrue(html.contains("[\"ExampleVendor\",\"ExamplePlugin\",\"0.4.4\",false,\"0.5.3\",\"INCOMPATIBLE\"]"));
        assertTrue(html.contains("[\"Hytale\",\"NPC\",\"1.0.0\",true,null,null]"));
        assertTrue(html.contains("[\"WARN\",\"Spawning\",\"Removing NPC\"]"));
        assertTrue(html.contains("\"ERROR\",\"ExamplePlugin\""));
        assertTrue(html.contains("ExampleSystem.tick"));
        assertTrue(html.contains("\"title\":\"mods/ExampleVendor_ExamplePlugin/config.json\""));
        assertTrue(html.contains("<b>ExamplePlugin</b>"));
        assertTrue(html.contains("ExampleFeature.Enabled"));
        assertTrue(html.contains("\"tickSystems\":[[\"EntityTickingSystem\",\"default\",5.1,12.4,4.8,30.0]]"));
        assertTrue(html.contains("\"tickSystemsNote\":null"));
        assertTrue(html.contains("\"modCpu\":null"));
        assertTrue(html.contains(
            "\"heapHistogram\":[[\"[B\",1234567,987654321],[\"java.lang.String\",234,5678]]"));
        assertFalse(html.contains("\"title\":\"Heap histogram\""));
        assertTrue(html.contains("\"entities\":[[\"default\",[[\"Zombie\",12],[\"Cow\",5]]]]"));
        assertFalse(html.contains("\"title\":\"Entities\""));
        assertTrue(html.contains("\"loaders\":[[\"ExampleLoader\",\"1.0.0\"]]"));
        assertTrue(html.contains("\"server\":\"ExampleServer\""));
        assertTrue(html.contains("\"hytale\":\"0.5.4\""));
        assertTrue(html.contains(
            "\"container\":{\"runtime\":\"Docker\",\"type\":null,\"cpuLimit\":null,\"memLimit\":null}"));
        assertFalse(html.contains("Container runtime"));
        assertFalse(html.contains("\"title\":\"Environment\""));
        assertFalse(html.substring(0, html.indexOf("<script>")).contains("<b>Environment</b>"));
    }

    @Test
    void pluginWebsitesLinkNamesOrFallBackToCurseForge() throws Exception {
        LinkedHashMap<String, String> plugins = new LinkedHashMap<>();
        plugins.put("IroriPowered:Refixes", "0.6.1 @ >=0.7.7 @ COMPATIBLE");
        plugins.put("Example:NoRange", "1.0.0");
        plugins.put("Example:NoSite", "2.0.0 @ * @ COMPATIBLE");
        plugins.put("Example:BadSite", "3.0.0 @ * @ COMPATIBLE");
        LinkedHashMap<String, String> websites = new LinkedHashMap<>();
        websites.put("IroriPowered:Refixes", "https://www.curseforge.com/hytale/mods/refixes");
        websites.put("Example:NoRange", "http://example.com/norange");
        websites.put("Example:BadSite", "javascript:alert(1)");

        String html = render(report(null, List.of(
            new DiagnosticSection("Plugins", plugins),
            new DiagnosticSection("Plugin websites", websites))));

        assertTrue(html.contains("[\"IroriPowered\",\"Refixes\",\"0.6.1\",false,\">=0.7.7\",\"COMPATIBLE\","
            + "\"https://www.curseforge.com/hytale/mods/refixes\"]"));
        assertTrue(html.contains("[\"Example\",\"NoRange\",\"1.0.0\",false,null,null,\"http://example.com/norange\"]"));
        assertTrue(html.contains("[\"Example\",\"NoSite\",\"2.0.0\",false,\"*\",\"COMPATIBLE\"]"));
        assertTrue(html.contains("[\"Example\",\"BadSite\",\"3.0.0\",false,\"*\",\"COMPATIBLE\"]"));
        assertFalse(html.contains("javascript:alert(1)"));
        assertFalse(html.contains("\"title\":\"Plugin websites\""));
        assertTrue(html.contains("https://www.curseforge.com/hytale/search?search=${encodeURIComponent(search||name)}"));
        assertTrue(html.contains("target=\"_blank\" rel=\"noopener noreferrer\" data-tip=\"${own?"));
        assertTrue(html.contains("modNameLink(name,p[6])"));
        assertTrue(html.contains("modNameLink(name,hp?hp[6]:null,"));
    }

    @Test
    void tickSystemsNoteRoutesWithoutRows() throws Exception {
        String html = render(report(null, List.of(new DiagnosticSection("Tick systems",
            Map.of("Note", "system metrics disabled")))));

        assertTrue(html.contains("\"tickSystems\":null"));
        assertTrue(html.contains("\"tickSystemsNote\":\"system metrics disabled\""));
    }

    @Test
    void mixinConflictsNoneRendersEmptyList() throws Exception {
        LinkedHashMap<String, String> mixins = new LinkedHashMap<>();
        mixins.put("Bootstrapper", "ExampleLoader");
        mixins.put("a.mixins.json", "MixinA");
        mixins.put("Conflicts", "none");

        String html = render(report(null, List.of(new DiagnosticSection("Mixins", mixins))));

        assertTrue(html.contains("\"conflicts\":[]"));
    }

    @Test
    void parsesHytaleLogFormat() {
        List<ReportHtml.LogLine> entries = ReportHtml.parseLog(
            "[2026/06/06 15:41:09   WARN] [Spawning] Removing NPC 'Boar'\n"
            + "[2026/06/06 15:41:10 SEVERE] [ExamplePlugin] boom\n"
            + "\tat a.b.C.d(C.java:1)\n"
            + "Caused by: java.lang.IllegalStateException\n"
            + "[2026/06/06 15:41:11   INFO] [Vote] ok");

        assertEquals(3, entries.size());
        assertEquals("WARN", entries.get(0).level());
        assertEquals("Spawning", entries.get(0).source());
        assertEquals("Removing NPC 'Boar'", entries.get(0).message());
        assertEquals(Instant.parse("2026-06-06T15:41:09Z").toEpochMilli(), entries.get(0).epochMs());
        assertEquals("ERROR", entries.get(1).level());
        assertTrue(entries.get(1).message().contains("Caused by"));
        assertEquals("INFO", entries.get(2).level());
    }

    @Test
    void parsesLogLinesWithRedactedTimestamp() {
        List<ReportHtml.LogLine> entries = ReportHtml.parseLog(
            "[2026/06/17 [REDACTED]   INFO] [HOSStatus|P] ONLINE\n"
            + "[2026/06/17 [REDACTED]   WARN] [Spawning] Removing NPC\n"
            + "[2026/06/17 [REDACTED] SEVERE] [ExamplePlugin] boom");

        assertEquals(3, entries.size());
        assertEquals("INFO", entries.get(0).level());
        assertEquals("HOSStatus|P", entries.get(0).source());
        assertEquals("ONLINE", entries.get(0).message());
        assertEquals(-1, entries.get(0).epochMs());
        assertEquals("WARN", entries.get(1).level());
        assertEquals("Removing NPC", entries.get(1).message());
        assertEquals("ERROR", entries.get(2).level());
    }

    @Test
    void classifiesConnectionLogLines() {
        List<ReportHtml.LogLine> entries = ReportHtml.parseLog(
            "[2026/06/11 17:02:08   INFO] [Hytale] Starting authenticated flow from Quic...\n"
            + "[2026/06/11 17:02:14   INFO] [Hytale] Disconnecting Wrex with the message: x\n"
            + "[2026/06/11 17:02:14   INFO] [Hytale] {Playing(null)} was closed.\n"
            + "[2026/06/11 17:02:14   INFO] [Vote] Disconnecting nothing");

        assertEquals("conn", ReportHtml.connectionEventType(entries.get(0)));
        assertEquals("conn", ReportHtml.connectionEventType(entries.get(1)));
        assertNull(ReportHtml.connectionEventType(entries.get(2)));
        assertNull(ReportHtml.connectionEventType(entries.get(3)));
    }

    @Test
    void escapesScriptCloseInsideData() throws Exception {
        IncidentReport r = report(null, List.of(
            new DiagnosticSection("Server log", Map.of(),
                "[2026/06/06 15:41:09   WARN] [X] payload </script> attack")));

        String html = render(r);

        int first = html.indexOf("</script>");
        int last = html.lastIndexOf("</script>");
        assertEquals(first, last);
        assertTrue(html.contains("<\\/script>"));
    }
}
