package io.github.xytronix.hybox.hytale;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.protocol.packets.interface_.CustomUICommand;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HyboxIncidentViewerTest {
    private static final Path REAL_BUNDLES = Path.of(System.getProperty("hybox.test.incidents",
        "/home/coolify/src/opus-all-incidents-20261019/incidents"));
    @TempDir Path directory;

    @Test
    void loadsEveryRealBundleWhenAvailable() throws Exception {
        assumeTrue(Files.isDirectory(REAL_BUNDLES), "Optional real-bundle corpus is not installed");
        List<Path> paths;
        try (var files = Files.list(REAL_BUNDLES)) { paths = files.filter(p -> p.toString().endsWith(".zip")).sorted().toList(); }
        assertEquals(9, paths.size());
        for (Path path : paths) {
            IncidentModel model = assertDoesNotThrow(() -> IncidentModel.load(path), path.toString());
            assertNotEquals("Not captured", model.id());
            assertTrue(model.has("incident"));
            assertFalse(model.array("diag").isEmpty(), "Early bundles retain their capture extras");
            if ("HEARTBEAT_STALL".equals(IncidentModel.text(model.object("incident"), "trigger"))) assertNotEquals("Not captured", model.blockedIn());
        }
    }

    @Test
    void parsesOneJsonValueWithoutBeingConfusedByJavascriptOrBracesInStrings() throws Exception {
        JsonObject data = fixture();
        data.getAsJsonObject("incident").addProperty("note", "a }; and const D = { inside a string \" });");
        IncidentModel model = IncidentModel.load(bundle(data, "\nwindow.after = {other: 1};\n"));
        assertEquals(data.getAsJsonObject("incident").get("note"), model.object("incident").get("note"));
        assertEquals("synthetic-000005", model.id());
    }

    @Test
    void missingJfrAndOlderNullSectionsHaveExplicitStates() throws Exception {
        JsonObject data = new JsonObject();
        data.add("incident", JsonParser.parseString("{\"id\":\"old\",\"trigger\":\"MANUAL\",\"severity\":\"INFO\",\"note\":\"Capture requested by operator\"}"));
        data.addProperty("jfrUnavailableReason", "JFR was disabled");
        data.addProperty("hyboxVersion", "1.0.0");
        data.add("series", JsonNull.INSTANCE);
        IncidentModel model = IncidentModel.load(bundle(data, ""));
        assertEquals("JFR was disabled", model.text("jfrUnavailableReason"));
        assertFalse(model.has("findings"));
        String overview = renderText(model, "top");
        for (String fact : List.of("Capture note", "Capture requested by operator", "Recording unavailable", "JFR was disabled", "Bundle", "old · Hybox 1.0.0")) assertTrue(overview.contains(fact), fact);
        assertFalse(overview.contains("Incident details"));
        assertFalse(overview.contains("Capture summary & next steps"));
        for (String section : HyboxIncidentRenderer.SECTIONS.keySet()) {
            UICommandBuilder cmd = new UICommandBuilder();
            HyboxIncidentRenderer.render(model, state(section, 0, "all"), cmd, new UIEventBuilder(), null);
            assertTrue(cmd.getCommands().length > 0, section);
        }
        assertTrue(renderText(model, "diagnosis").contains("Diagnosis findings are not stored in this older bundle."));
        assertTrue(renderText(model, "pluginmetrics").contains("Not captured."));
        assertTrue(renderText(model, "threads").contains("Not captured."));
    }

    @Test
    void recordingBadgeKeepsScopeSeparateFromRecordingTileFacts() throws Exception {
        IncidentModel model = IncidentModel.load(bundle(fixture(), ""));
        UICommandBuilder overview = new UICommandBuilder();
        HyboxIncidentRenderer.render(model, state("top", 0, "all"), overview, new UIEventBuilder(), null);
        var commands = Arrays.asList(overview.getCommands());
        assertTrue(commands.stream().anyMatch(c -> "#RecordingBadge.Text".equals(c.selector)
            && JsonParser.parseString(c.data).getAsJsonObject().get("0").getAsString().isEmpty()));
        assertTrue(commands.stream().anyMatch(c -> "#Tiles[0] #Sub.Text".equals(c.selector)
            && "Freeze".equals(JsonParser.parseString(c.data).getAsJsonObject().get("0").getAsString())));
        assertTrue(commands.stream().anyMatch(c -> c.selector != null && c.selector.endsWith("#Value.Text")
            && "17 min 49 s from 06:47:44 UTC".equals(JsonParser.parseString(c.data).getAsJsonObject().get("0").getAsString())));
        assertEquals("Last 1 min", badge(model, "metrics", "1m"));
        assertEquals("Whole recording", badge(model, "metrics", "all"));
        assertEquals("Whole recording", badge(model, "top", "1m"));
    }

    private static String badge(IncidentModel model, String section, String range) {
        UICommandBuilder commands = new UICommandBuilder();
        HyboxIncidentRenderer.render(model, state(section, 0, range), commands, new UIEventBuilder(), null);
        return Arrays.stream(commands.getCommands()).filter(c -> "#RecordingBadge.Text".equals(c.selector))
            .map(c -> JsonParser.parseString(c.data).getAsJsonObject().get("0").getAsString()).findFirst().orElseThrow();
    }

    @Test
    void mixinProblemsPointToTheBundleForStackTracesWithoutShowingFrames() throws Exception {
        JsonObject data = fixture();
        data.add("mixins", JsonParser.parseString("""
            {"bootstrapper":"Hyinit 0.3.1","configs":[],"conflicts":null,"hyinit":{"version":"0.3.1"},"sources":[],"overlaps":[],"note":null,
             "problems":[["warn","Mixin a.B was skipped","a.jar","java.lang.IllegalStateException: boom",
                          [["java.lang.IllegalStateException: boom","h"],["\\tat com.a.Plugin.run(Plugin.java:3)","m","A"]]],
                         ["error","Mixin transformation of c.D failed","c.jar","java.lang.RuntimeException: x"]]}
            """));
        String traced = renderText(IncidentModel.load(bundle(data, "")), "mixins");
        assertTrue(traced.contains("The bundle has the stack traces. "
            + "The server log has the stack trace of the other problem."), traced);
        assertFalse(traced.contains("com.a.Plugin.run"), traced);

        data.getAsJsonObject("mixins").getAsJsonArray("problems").get(0).getAsJsonArray().remove(4);
        String old = renderText(IncidentModel.load(bundle(data, "")), "mixins");
        assertTrue(old.contains("The server log has the full stack traces."), old);
        assertFalse(old.contains("The bundle has the stack traces"), old);
    }

    @Test
    void mixinApplyTimeAndMixinsOffBySettingsShowPerModOnlyWhenHyinitGivesThem() throws Exception {
        JsonObject data = fixture();
        String head = "{\"bootstrapper\":\"Hyinit 0.5.0\",\"configs\":[],\"conflicts\":null,\"hyinit\":{\"version\":\"0.5.0\"},"
            + "\"overlaps\":[],\"note\":null,\"problems\":[],\"sources\":[";
        data.add("mixins", JsonParser.parseString(head
            + "[\"refixes-0.6.1.jar\",\"IroriPowered:Refixes\",\"0.6.1\",null,[],true,false,null,742,221.3,67],"
            + "[\"late.jar\",null,null,null,[],true,false,null,0,null,1],"
            + "[\"plain.jar\",null,null,null,[],true,false,null,3,null,null]]}"));
        String shown = renderText(IncidentModel.load(bundle(data, "")), "mixins");

        assertTrue(shown.contains("Mixin apply time is spent once when a class loads, mostly at startup."
            + " It is not CPU while the server runs."), shown);
        assertTrue(shown.contains("\"Refixes\"") && shown.contains("Mixin apply time 221.3 ms. 67 Mixins off by the mod's settings."), shown);
        assertTrue(shown.contains("\"late.jar\"") && shown.contains("\"1 Mixin off by the mod's settings.\""), shown);
        assertFalse(shown.contains("plain.jar"), shown);

        data.add("mixins", JsonParser.parseString(head + "[\"refixes-0.6.1.jar\",\"IroriPowered:Refixes\",\"0.6.1\",null,[],true,"
            + "false,null,742]]}"));
        String old = renderText(IncidentModel.load(bundle(data, "")), "mixins");
        assertFalse(old.contains("Early plugin JARs") || old.contains("Mixin apply time") || old.contains("settings"), old);
    }

    @Test
    void cpuMemoryAndEngineFitOneScreen() throws Exception {
        JsonObject data = fixture();
        IncidentModel model = IncidentModel.load(bundle(data, ""));
        String cpu = renderText(model, "cpu");
        assertTrue(cpu.contains("\"Plugin\"") && cpu.contains("3.2%"), cpu);
        assertFalse(cpu.contains("Limited CPU sample count"));
        String memory = renderText(model, "memory");
        assertTrue(memory.contains("Allocation rate") && memory.contains("freed by GC, not memory in use"), memory);
        assertFalse(memory.contains("RetainedClass") || memory.contains("LiveClass") || memory.contains("memPool"), memory);
        String engine = renderText(model, "engine");
        assertTrue(engine.contains("EngineSystem in default") && engine.contains("% of the 33.3 ms tick"), engine);
        data.getAsJsonObject("incident").addProperty("samples", 42);
        String lowSamples = renderText(IncidentModel.load(bundle(data, "")), "cpu");
        assertTrue(lowSamples.contains("Only 42 CPU samples. Too few to rank threads."), lowSamples);
    }

    @Test
    void threadStatesLeadWithLockAndDeadlockFactsAndKeepInternalsLast() throws Exception {
        JsonObject data = fixture();
        data.remove("deadlocked");
        for (int blocked : new int[] {0, 1, 2}) {
            data.getAsJsonObject("threadStates").addProperty("BLOCKED", blocked);
            String rendered = renderText(IncidentModel.load(bundle(data, "")), "states");
            assertTrue(rendered.contains("Threads waiting for a lock"), rendered);
            assertTrue(rendered.contains(blocked == 0 ? "None at capture."
                : blocked == 1 ? "1 thread was waiting for a lock at capture." : "2 threads were waiting for a lock at capture."), rendered);
            assertTrue(rendered.contains("No deadlock found."), rendered);
            assertFalse(rendered.contains("thread(s)"));
            int lead = rendered.indexOf("Threads waiting for a lock");
            assertTrue(lead < rendered.indexOf("Live threads"), rendered);
            assertTrue(lead < rendered.indexOf("Running"), rendered);
        }
        data.remove("threadStates");
        assertTrue(renderText(IncidentModel.load(bundle(data, "")), "states").contains("Deadlock check did not run."));
        String captured = renderText(IncidentModel.load(bundle(fixture(), "")), "states");
        assertTrue(captured.contains("Deadlocked threads"), captured);
        assertTrue(captured.contains("thread-a awaits thread-b"), captured);
        assertFalse(captured.contains("No deadlock found."));
        UICommandBuilder cmd = new UICommandBuilder();
        HyboxIncidentRenderer.render(IncidentModel.load(bundle(fixture(), "")), state("states", 0, "all"), cmd, new UIEventBuilder(), null);
        assertFalse(Arrays.stream(cmd.getCommands()).anyMatch(c -> "Pages/HyboxIncidentChart.ui".equals(c.text)), "Thread states fit one screen without charts");
    }

    @Test
    void overviewKeepsEachFactOnceAndEndsWithTheBundleRow() throws Exception {
        JsonObject data = fixture();
        IncidentModel model = IncidentModel.load(bundle(data, ""));
        String overview = renderText(model, "top");
        assertFalse(overview.contains("Incident details"), overview);
        assertFalse(overview.contains("CPU samples"), overview);
        assertTrue(overview.contains("synthetic-000005 · Hybox 1.0.0"), overview);
        UICommandBuilder cmd = new UICommandBuilder();
        HyboxIncidentRenderer.render(model, state("top", 0, "all"), cmd, new UIEventBuilder(), null);
        assertEquals("Freeze, world default, captured 2026-10-19 07:05:31 UTC", value(cmd, "#IncidentMeta.Text"));
        data.remove("hyboxVersion");
        String unversioned = renderText(IncidentModel.load(bundle(data, "")), "top");
        assertTrue(unversioned.contains("\"synthetic-000005\""), unversioned);
        assertFalse(unversioned.contains("Hybox "), unversioned);
    }

    @Test
    void overviewShowsTheFirstFindingsWithLinksToTheirSections() throws Exception {
        JsonObject data = fixture();
        JsonArray evidence = new JsonArray(), steps = new JsonArray();
        List<String> sections = List.of("cpu", "memory", "threads", "network", "worlds");
        for (int i = 0; i < 5; i++) evidence.add(JsonParser.parseString("{\"spans\":[[\"Evidence " + i + "\",\"\"]],\"section\":\"" + sections.get(i) + "\",\"label\":\"" + sections.get(i) + "\"}"));
        for (int i = 0; i < 4; i++) steps.add(JsonParser.parseString("{\"spans\":[[\"Step " + i + "\",\"\"]],\"section\":\"plugins\",\"label\":\"plugins\"}"));
        data.getAsJsonObject("findings").add("evidence", evidence);
        data.getAsJsonObject("findings").add("steps", steps);
        IncidentModel model = IncidentModel.load(bundle(data, ""));
        UICommandBuilder cmd = new UICommandBuilder(); UIEventBuilder ev = new UIEventBuilder();
        HyboxIncidentRenderer.render(model, state("top", 0, "all"), cmd, ev, null);
        assertEquals(6, Arrays.stream(cmd.getCommands()).filter(c -> "Pages/HyboxIncidentEvidence.ui".equals(c.text)).count());
        String overview = renderText(model, "top");
        for (String line : List.of("Evidence 0", "Evidence 2", "Step 0", "Step 2", "synthetic-000005 · Hybox 1.0.0")) assertTrue(overview.contains(line), line);
        for (String line : List.of("Evidence 3", "Step 3")) assertFalse(overview.contains(line), line);
        for (String section : List.of("cpu", "memory", "threads", "plugins")) {
            assertTrue(Arrays.stream(ev.getEvents()).anyMatch(e -> e.selector.endsWith("#EvidenceLink") && e.data.contains("\"" + section + "\"")), section);
        }
        assertTrue(Arrays.stream(ev.getEvents()).anyMatch(e -> !e.selector.startsWith("#SectionNav") && e.data.contains("\"diagnosis\"")));
        assertEquals(1, Arrays.stream(ev.getEvents()).filter(e -> e.selector.endsWith("#EvidenceLink") && e.data.contains("\"plugins\"")).count(), "A card shows a section button once");
        assertTrue(overview.contains("3 more findings in Diagnosis"), overview);
        String diagnosis = renderText(model, "diagnosis");
        for (String line : List.of("Other findings", "Evidence 3", "Evidence 4", "Step 3")) assertTrue(diagnosis.contains(line), line);
        for (String line : List.of("Evidence 0", "Evidence 2", "Step 0", "Step 2", "What the recording shows", "Next steps")) assertFalse(diagnosis.contains(line), line);
        assertFalse(renderText(IncidentModel.load(bundle(fixture(), "")), "top").contains("more finding"), "No Diagnosis link when Overview shows every finding");
        JsonObject older = fixture();
        older.remove("findings");
        UICommandBuilder old = new UICommandBuilder();
        HyboxIncidentRenderer.render(IncidentModel.load(bundle(older, "")), state("top", 0, "all"), old, new UIEventBuilder(), null);
        assertFalse(Arrays.stream(old.getCommands()).anyMatch(c -> "Pages/HyboxIncidentEvidence.ui".equals(c.text)));
    }

    @Test
    void overviewLeadsWithTheStallAndTheFourNumbers() throws Exception {
        JsonObject data = fixture();
        UICommandBuilder stalled = new UICommandBuilder();
        HyboxIncidentRenderer.render(IncidentModel.load(bundle(data, "")), state("top", 0, "all"), stalled, new UIEventBuilder(), null);
        assertEquals("Stall", value(stalled, "#Tiles[0] #Key.Text"));
        assertEquals("2.9 s", value(stalled, "#Tiles[0] #Value.Text"));
        assertEquals(List.of("Tick time", "TPS", "CPU", "Heap"), List.of(value(stalled, "#Tiles[1] #Key.Text"), value(stalled, "#Tiles[2] #Key.Text"),
            value(stalled, "#Tiles[3] #Key.Text"), value(stalled, "#Tiles[4] #Key.Text")));
        assertEquals(DashboardModel.Tone.WARN.color, value(stalled, "#Tiles[4] #Value.Style.TextColor"), "Heap at its maximum is flagged");
        assertFalse(Arrays.stream(stalled.getCommands()).anyMatch(c -> "#Tiles[2] #Value.Style.TextColor".equals(c.selector)), "TPS above 90% of the target is not flagged");
        String overview = renderText(IncidentModel.load(bundle(data, "")), "top");
        assertTrue(overview.indexOf("\"Stalled thread\"") < overview.indexOf("What the recording shows"), overview);
        data.getAsJsonObject("incident").add("stallMs", JsonNull.INSTANCE);
        data.getAsJsonObject("incident").addProperty("trigger", "MANUAL");
        UICommandBuilder manual = new UICommandBuilder();
        HyboxIncidentRenderer.render(IncidentModel.load(bundle(data, "")), state("top", 0, "all"), manual, new UIEventBuilder(), null);
        assertEquals("Tick time", value(manual, "#Tiles[0] #Key.Text"));
        assertFalse(Arrays.stream(manual.getCommands()).anyMatch(c -> c.selector != null && c.selector.startsWith("#Tiles[4]")));
        JsonObject slowest = fixture();
        slowest.getAsJsonArray("worlds").add(JsonParser.parseString("{\"name\":\"instance-Arena_One\",\"samples\":10,\"stalled\":false,\"tps\":12.0,\"mspt\":83.29,\"msptMax\":865.7}"));
        slowest.getAsJsonObject("tps").addProperty("world", "instance-arena_one");
        slowest.getAsJsonObject("tps").addProperty("pick", "slowest");
        slowest.add("vitals", JsonParser.parseString("{\"world\":\"instance-arena_one\",\"tpsTarget\":30,\"tickBudgetMs\":33.33,\"tickAvgMs\":27.9,\"tickWorstMs\":865.7,\"tpsAvg\":27.07,\"tpsMin\":7.77}"));
        UICommandBuilder shared = new UICommandBuilder();
        HyboxIncidentRenderer.render(IncidentModel.load(bundle(slowest, "")), state("top", 0, "all"), shared, new UIEventBuilder(), null);
        assertEquals(List.of("Tick time in Arena One", "27.9 ms avg", "TPS in Arena One", "27.1 avg"), List.of(value(shared, "#Tiles[1] #Key.Text"),
            value(shared, "#Tiles[1] #Value.Text"), value(shared, "#Tiles[2] #Key.Text"), value(shared, "#Tiles[2] #Value.Text")), "The tiles show the report's vitals, not the at-capture values");
    }

    @Test
    void hyboxThreadsAndPoolsHaveNoSeparateHyboxLabel() throws Exception {
        JsonObject data = fixture();
        JsonArray hot = new JsonArray();
        hot.add(JsonParser.parseString("[9000,\"RUNNABLE\",\"hybox-worker-1\",\"io.github.xytronix.hybox.Work.run\",[]]"));
        hot.add(JsonParser.parseString("[8000,\"RUNNABLE\",\"hybox-worker-2\",\"io.github.xytronix.hybox.Work.run\",[]]"));
        hot.addAll(data.getAsJsonArray("hotThreads"));
        data.add("hotThreads", hot);
        IncidentModel model = IncidentModel.load(bundle(data, ""));
        UICommandBuilder cmd = new UICommandBuilder();
        HyboxIncidentRenderer.render(model, state("threads", 0, "all"), cmd, new UIEventBuilder(), null);
        var commands = Arrays.asList(cmd.getCommands());
        assertTrue(commands.stream().anyMatch(c -> c.selector != null && c.selector.endsWith(" #Name.Text") && c.data.contains("hybox-worker-1")));
        assertFalse(commands.stream().anyMatch(c -> c.selector != null && c.selector.contains("#State")));
        String rendered = renderText(model, "threads");
        assertTrue(rendered.contains("hybox-worker 2 threads"), rendered);
        assertTrue(rendered.contains("Worker 50 threads"), rendered);
        assertFalse(rendered.contains("Hybox itself"), rendered);
        assertEquals(6, commands.stream().filter(c -> "Pages/HyboxIncidentThread.ui".equals(c.text)).count());
    }

    private static final String HYBRID_WAIT =
        "com.hypixel.hytale.server.core.util.thread.TickingThread.redirect$zfj000$refixesjar$refixes$hybridWait";

    private static String mixinCostRow(String file, String mod, long own, long started, long reach) {
        String methods = "[[\"" + HYBRID_WAIT + "\"," + Math.max(1, own) + "]]";
        return "{\"file\":\"" + file + "\",\"mod\":" + (mod == null ? "null" : "\"" + mod + "\"") + ",\"own\":" + own
            + ",\"started\":" + started + ",\"reach\":" + reach + ",\"ownMethods\":" + (own > 0 ? methods : "[]")
            + ",\"startedMethods\":" + methods + ",\"changedMethods\":[]}";
    }

    private String mixinCostPage(long worldSamples, String... rows) throws Exception {
        JsonObject data = fixture();
        data.add("mixinCost", JsonParser.parseString("{\"worldSamples\":" + worldSamples + ",\"rows\":["
            + String.join(",", rows) + "]}"));
        return renderText(IncidentModel.load(bundle(data, "")), "cpu");
    }

    @Test
    void mixinCodeByModShowsTheOwnShareAndSaysAtMostForTheCodeItStartedAlsoWithoutOwnSamples() throws Exception {
        String cpu = mixinCostPage(6477, mixinCostRow("refixes-0.6.1.jar", "Refixes", 90, 636, 6471),
            mixinCostRow("late.jar", "Late", 0, 363, 6477), mixinCostRow("idle.jar", "Idle", 0, 0, 6477));

        assertTrue(cpu.contains("\"Mixin code by mod\""), cpu);
        assertTrue(cpu.contains("\"1.4%\""), cpu);
        assertTrue(cpu.contains("1.4% of world thread samples ran in its own Mixin code, mostly TickingThread.redirect...hybridWait."
            + " At most 9.8% with the Hytale code that Mixin code called."
            + " 99.9% had a method it changed on the stack, which is not its cost."), cpu);
        assertTrue(cpu.contains("No world thread sample ran in its own Mixin code."
            + " At most 5.6% with the Hytale code that Mixin code called, mostly TickingThread.redirect...hybridWait."
            + " 100.0% had a method it changed on the stack, which is not its cost."), cpu);
        assertFalse(cpu.contains("refixesjar") || cpu.contains("\"Idle\"") || cpu.contains("Too few for shares"), cpu);
    }

    @Test
    void mixinCodeOnTheCommonPoolIsALineOfItsOwnThatSaysItIsNotWorldThreadTime() throws Exception {
        JsonObject data = fixture();
        String row = mixinCostRow("refixes-0.6.1.jar", "Refixes", 28, 2171, 2227);
        data.add("mixinCost", JsonParser.parseString("{\"worldSamples\":6477,\"rows\":[" + row + "],\"poolSamples\":3529,"
            + "\"poolRows\":[" + row + "," + mixinCostRow("idle.jar", "Idle", 0, 0, 40) + "]}"));
        String cpu = renderText(IncidentModel.load(bundle(data, "")), "cpu");

        assertTrue(cpu.contains("On common pool threads, which is not world thread time, 0.8% of their samples ran in the own"
            + " Mixin code of Refixes and at most 61.5% with the Hytale code that Mixin code called.\""), cpu);
        assertTrue(cpu.contains("0.4% of world thread samples ran in its own Mixin code"), cpu);
        assertFalse(cpu.contains("Mixin code of Idle"), cpu);

        data.add("mixinCost", JsonParser.parseString("{\"worldSamples\":6477,\"rows\":[" + row + "],\"poolSamples\":120,"
            + "\"poolRows\":[" + mixinCostRow("refixes-0.6.1.jar", "Refixes", 2, 78, 98) + "]}"));
        String low = renderText(IncidentModel.load(bundle(data, "")), "cpu");
        assertTrue(low.contains("which is not world thread time, 2 samples ran in the own Mixin code of Refixes and at most"
            + " 78 samples with the Hytale code that Mixin code called. Only 120 common pool samples, too few for shares."), low);
    }

    @Test
    void mixinCodeOnTheWorkPoolIsALineOfItsOwnApartFromTheCommonPool() throws Exception {
        JsonObject data = fixture();
        String row = mixinCostRow("refixes-0.6.1.jar", "Refixes", 28, 2171, 2227);
        data.add("mixinCost", JsonParser.parseString("{\"worldSamples\":6477,\"rows\":[" + row + "],\"workPoolSamples\":2000,"
            + "\"workPoolRows\":[" + mixinCostRow("refixes-0.6.1.jar", "Refixes", 50, 400, 600) + "]}"));
        String cpu = renderText(IncidentModel.load(bundle(data, "")), "cpu");

        assertTrue(cpu.contains("On work pool threads, which is not world thread time, 2.5% of their samples ran in the own"
            + " Mixin code of Refixes and at most 20.0% with the Hytale code that Mixin code called.\""), cpu);
        assertFalse(cpu.contains("On common pool threads"), cpu);
    }

    @Test
    void mixinCodeByModShowsCountsBelowThreeHundredWorldSamplesAndTheJarForEqualNames() throws Exception {
        String cpu = mixinCostPage(150, mixinCostRow("twin-a.jar", "Twin", 2, 3, 148), mixinCostRow("twin-b.jar", "Twin", 1, 1, 9));

        assertTrue(cpu.contains("Only 150 world thread samples. Too few for shares, so these are sample counts."), cpu);
        assertTrue(cpu.contains("2 samples ran in its own Mixin code, mostly TickingThread.redirect...hybridWait."
            + " At most 3 samples with the Hytale code that Mixin code called."
            + " 148 samples had a method it changed on the stack, which is not its cost."), cpu);
        assertTrue(cpu.contains("\"twin-a.jar\"") && cpu.contains("\"twin-b.jar\""), cpu);
        assertFalse(cpu.contains("1.3%") || cpu.contains("\"Twin\""), cpu);
    }

    @Test
    void cpuByModIsOneListWithoutAHyboxLabel() throws Exception {
        JsonObject data = fixture();
        data.getAsJsonArray("modCpu").add(JsonParser.parseString("[\"Xytronix:Hybox\",89,\"io.github.xytronix.hybox.Pump.tick\",[[\"hybox-scheduler\",34],[\"hybox-worker\",55]]]"));
        data.getAsJsonArray("cpuByWorld").get(0).getAsJsonObject().getAsJsonArray("mods").add(JsonParser.parseString("[\"io.github.xytronix.hybox.hytale\",20]"));
        String cpu = renderText(IncidentModel.load(bundle(data, "")), "cpu");
        assertTrue(cpu.contains("CPU by mod"), cpu);
        assertTrue(cpu.contains("\"Plugin\"") && cpu.contains("\"Hybox\""), cpu);
        assertTrue(cpu.indexOf("\"Plugin\"") < cpu.indexOf("\"Hybox\""), "Mods are sorted by samples");
        assertFalse(cpu.contains("Hybox itself"), cpu);
    }

    @Test
    void modSettingsAndModEventsStayInTheReport() throws Exception {
        JsonObject data = fixture();
        data.getAsJsonArray("events").add(JsonParser.parseString("[0.5,\"mod:IroriPowered:Refixes\",\"[IdleWorldPause] paused world 'default'\"]"));
        data.getAsJsonArray("diag").add(JsonParser.parseString("{\"title\":\"IroriPowered:Refixes settings\",\"entries\":[[\"Refixes version\",\"0.6.1\"]],\"pre\":null}"));
        IncidentModel model = IncidentModel.load(bundle(data, ""));
        assertFalse(renderText(model, "metrics").contains("[IdleWorldPause]"));
        assertFalse(renderText(model, "top").contains("setting"));
        assertTrue(renderText(model, "plugins").contains("\"1 setting from Refixes.\""));
        assertFalse(renderText(model, "top").contains("Refixes version") || renderText(model, "stalled").contains("Refixes version"));
    }

    @Test
    void modRowsShowTheirOwnCostAndExplainTheCallChainOnHover() throws Exception {
        JsonObject data = fixture();
        data.add("modCpu", JsonParser.parseString("""
            [["IroriPowered:Refixes",3002,"cc.irori.refixes.Lifetime.child",[["ForkJoinPool.commonPool-worker",2019]],323,9817,
              "com.hypixel.hytale.lib.quiche.QuicheChannel.wrapOperation$zdb000$refixesjar$refixes$accountPoll",[["WorldThread - default",131]],null,
              "com.hypixel.hytale.server.core.util.thread.TickingThread.wrapMethod$zfi000$refixesjar$refixes$settleFailedStartup","Mixin code from Refixes"],
             ["Xytronix:Hybox",89,"io.github.xytronix.hybox.Pump.tick",[],2000,2100,"io.github.xytronix.hybox.Pump.tick",[],null,"io.github.xytronix.hybox.Pump.tick",null,"Core sentence for Hybox."],
             ["Ghost:Mod",0,null,[],0,0,null,[],null,null,null]]"""));
        data.getAsJsonArray("cpuByWorld").get(0).getAsJsonObject().add("mods", JsonParser.parseString("[[\"IroriPowered:Refixes\",844,137,3100],[\"Xytronix:Hybox\",25,200,210]]"));
        data.getAsJsonObject("alloc").add("byMod", JsonParser.parseString("[[\"IroriPowered:Refixes\",1617884936,6663499053,56000000000]]"));
        data.getAsJsonArray("hotThreads").set(0, JsonParser.parseString("[4305,\"RUNNABLE\",\"Worker-0\",\"engine.Tick.run\",[[\"engine.Tick.run\",384]],null,[[\"IroriPowered:Refixes\",90,4300],[\"Xytronix:Hybox\",2,3]]]"));
        IncidentModel model = IncidentModel.load(bundle(data, ""));
        String cpu = renderText(model, "cpu");
        assertTrue(cpu.indexOf("\"Hybox\"") < cpu.indexOf("\"Refixes\""), cpu);
        assertTrue(cpu.contains("\"1.6%\"") && cpu.contains("\"10.1%\""), "A mod row shows only its own cost: " + cpu);
        assertTrue(cpu.contains("\"2,000 samples in its own code. Core sentence for Hybox.\""), cpu);
        assertTrue(cpu.contains("\"323 samples in its own code. Refixes code was in the call chain of 49.5% of samples, mostly Hytale") && cpu.contains("ticking thread that runs through its code. This is not its cost.\""), cpu);
        assertFalse(cpu.contains("Ghost"), cpu);
        String memory = renderText(model, "memory");
        assertTrue(memory.contains("\"Refixes, 10.0%\"") && memory.contains("in its own code. Refixes code was in the call chain of 84.0% of sampled memory. This is not its cost."), memory);
        String threads = renderText(model, "threads");
        assertTrue(threads.contains("Mods on this thread: Refixes 2.1%. Refixes code was in the call chain of 99.9% of this thread"), threads);
        for (String rendered : List.of(cpu, memory, threads)) assertFalse(rendered.contains("on the stack"), rendered);
    }

    @Test
    void emptySectionsAreHiddenInTheNavigationButStillRenderWhenSelected() throws Exception {
        List<String> nav = HyboxIncidentRenderer.NAV;
        UICommandBuilder full = new UICommandBuilder();
        HyboxIncidentRenderer.render(IncidentModel.load(bundle(fixture(), "")), state("top", 0, "all"), full, new UIEventBuilder(), null);
        for (int i = 0; i < nav.size(); i++) assertEquals("true", value(full, "#SectionNav[" + i + "].Visible"), nav.get(i));
        assertEquals(List.of("#Incident", "top", "diagnosis", "stalled", "#Evidence"), nav.subList(0, 5));
        JsonObject sparse = new JsonObject();
        sparse.add("incident", JsonParser.parseString("{\"id\":\"m\",\"trigger\":\"MANUAL\",\"severity\":\"INFO\"}"));
        sparse.add("hotMethods", new JsonArray());
        IncidentModel model = IncidentModel.load(bundle(sparse, ""));
        UICommandBuilder cmd = new UICommandBuilder();
        HyboxIncidentRenderer.render(model, state("top", 0, "all"), cmd, new UIEventBuilder(), null);
        for (int i = 0; i < nav.size(); i++) {
            boolean shown = nav.get(i).equals("top") || nav.get(i).equals("#Incident");
            assertEquals(Boolean.toString(shown), value(cmd, "#SectionNav[" + i + "].Visible"), nav.get(i));
        }
        UICommandBuilder selected = new UICommandBuilder();
        HyboxIncidentRenderer.render(model, state("methods", 0, "all"), selected, new UIEventBuilder(), null);
        assertEquals("true", value(selected, "#SectionNav[" + nav.indexOf("methods") + "].Visible"));
        assertEquals("true", value(selected, "#SectionNav[" + nav.indexOf("#Evidence") + "].Visible"));
        assertEquals("Hot methods", value(selected, "#SectionTitle.Text"));
        assertTrue(renderText(model, "methods").contains("Not captured."));
    }

    @Test
    void stalledThreadShowsOnlyTheThreadCardsAndDiagnosisDropsRepeatedFacts() throws Exception {
        JsonObject data = fixture();
        data.getAsJsonArray("diag").add(JsonParser.parseString("{\"title\":\"Environment\",\"entries\":[[\"Hyinit\",\"installed (0.3.1)\"],[\"Java\",\"25\"]],\"pre\":null}"));
        data.getAsJsonArray("diag").add(JsonParser.parseString("{\"title\":\"server.properties\",\"entries\":[[\"a\",\"b\"]],\"pre\":null}"));
        IncidentModel model = IncidentModel.load(bundle(data, ""));
        String stalled = renderText(model, "stalled");
        assertTrue(stalled.contains("WorldThread - default") && stalled.contains("at first"), stalled);
        for (String gone : List.of("Environment", "installed (0.3.1)", "server.properties", "Heartbeats", "Thread progress")) assertFalse(stalled.contains(gone), gone);
        String diagnosis = renderText(model, "diagnosis");
        assertFalse(diagnosis.contains("What the recording shows") || diagnosis.contains("Next steps"), "Diagnosis does not repeat the Overview findings");
        for (String gone : List.of("Assessment", "Inspect stacks", "Timing is not causation", "GC pause 2884.44 ms", "Tags", "Caveat")) assertFalse(diagnosis.contains(gone), gone);
    }

    private static String value(UICommandBuilder cmd, String selector) {
        return Arrays.stream(cmd.getCommands()).filter(c -> selector.equals(c.selector))
            .map(c -> JsonParser.parseString(c.data).getAsJsonObject().get("0").getAsString()).reduce((a, b) -> b).orElseThrow(() -> new AssertionError(selector));
    }

    @Test
    void ignoresObsoleteCaptureGuidanceInLegacyBundles() throws Exception {
        JsonObject data = new JsonObject();
        data.add("incident", JsonParser.parseString("{\"id\":\"legacy\",\"severity\":\"INFO\",\"trigger\":\"MANUAL\"}"));
        Path path = directory.resolve("legacy-metadata.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("incident.json"));
            zip.write("{\"meta\":{\"id\":\"legacy\"},\"summary\":\"obsolete capture guidance\"}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry(); zip.putNextEntry(new ZipEntry("report.html"));
            zip.write(("<script>const D = " + data + ";</script>").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        IncidentModel metadataOnly = IncidentModel.load(path);
        assertEquals("legacy", metadataOnly.id());
        assertFalse(metadataOnly.has("summary"));

        data.addProperty("summary", "obsolete capture guidance");
        IncidentModel stored = IncidentModel.load(bundle(data, ""));
        assertEquals("legacy", stored.id());
        assertFalse(renderText(stored, "top").contains("obsolete capture guidance"));
        assertFalse(String.join("\n", HyboxIncidentRenderer.consoleSummary(stored)).contains("obsolete capture guidance"));
    }

    @Test
    void consoleSummaryOmitsAbsentFactsAndFormatsPresentOnes() throws Exception {
        JsonObject sparse = new JsonObject();
        sparse.add("incident", JsonParser.parseString("{\"id\":\"m\",\"trigger\":\"MANUAL\",\"severity\":\"INFO\",\"samples\":22}"));
        var manual = HyboxIncidentRenderer.consoleSummary(IncidentModel.load(bundle(sparse, "")));
        assertTrue(manual.stream().noneMatch(line -> line.contains("Not captured") || line.contains("stall") || line.contains("Unknown")), manual::toString);

        JsonObject stall = new JsonObject();
        stall.add("incident", JsonParser.parseString("{\"id\":\"s\",\"trigger\":\"HEARTBEAT_STALL\",\"severity\":\"DEGRADED\",\"world\":\"default\","
            + "\"stallMs\":2944,\"players\":1,\"samples\":19816}"));
        String all = String.join("\n", HyboxIncidentRenderer.consoleSummary(IncidentModel.load(bundle(stall, ""))));
        assertTrue(all.contains("stall 2,944 ms") && all.contains("1 player ·") && all.contains("19,816 samples"), all);
        assertTrue(all.startsWith("s · Degraded · Freeze · default"), all);
        assertFalse(all.contains("HEARTBEAT") || all.contains("DEGRADED"), all);
    }

    @Test
    void headerShowsTriggerAndSeverityInPlainWords() throws Exception {
        UICommandBuilder cmd = new UICommandBuilder();
        HyboxIncidentRenderer.render(IncidentModel.load(bundle(fixture(), "")), state("top", 0, "all"), cmd, new UIEventBuilder(), null);
        var commands = Arrays.asList(cmd.getCommands());
        assertTrue(commands.stream().anyMatch(c -> "#Headline.Text".equals(c.selector)
            && "World default stopped responding for 2.9 s".equals(JsonParser.parseString(c.data).getAsJsonObject().get("0").getAsString())));
        assertTrue(commands.stream().anyMatch(c -> "#Severity.Text".equals(c.selector)
            && "Degraded".equals(JsonParser.parseString(c.data).getAsJsonObject().get("0").getAsString())));
    }

    @Test
    void blockedInKeepsTheMixinLabelAndStripsGeneratedNames() throws Exception {
        Path legacy = directory.resolve("legacy-blocked.zip");
        writeZip(legacy, "<div class=\"subhead\">Captured <span class=\"mono\">x</span> · Blocked in <code>com.x.TickingThread.run</code>, changed by Mixin from Refixes (MixinTickSleep, A &amp; B)</div>"
            + "<script>const D = {\"incident\":{\"id\":\"legacy\"}};\n</script>");
        assertEquals("com.x.TickingThread.run, changed by Mixin from Refixes (MixinTickSleep, A & B)", IncidentModel.load(legacy).blockedIn());

        Path wrapped = directory.resolve("legacy-wrapped.zip");
        writeZip(wrapped, "<div class=\"subhead\">Blocked in <code>com.x.TickingThread.run$mixinextras$wrapped$39</code></div>"
            + "<script>const D = {\"incident\":{\"id\":\"legacy\"}};\n</script>");
        String stripped = IncidentModel.load(wrapped).blockedIn();
        assertTrue(stripped.startsWith("com.x.TickingThread.run"), stripped);
        assertFalse(stripped.contains("mixinextras"), stripped);

        JsonObject data = fixture();
        data.add("diag", JsonParser.parseString("[{\"title\":\"Stalled thread\",\"entries\":[[\"Blocked in\","
            + "\"com.x.TickingThread.mixinextras$bridge$run$mixinextras$wrapped$39$40, changed by Mixin from Refixes (MixinTickSleep)\"]],\"pre\":null}]"));
        IncidentModel model = IncidentModel.load(bundle(data, ""));
        assertEquals("com.x.TickingThread.run, changed by Mixin from Refixes (MixinTickSleep)", model.blockedIn());
        assertFalse(renderText(model, "stalled").contains("mixinextras"));
        assertTrue(String.join("\n", HyboxIncidentRenderer.consoleSummary(model)).contains("In com.x.TickingThread.run, changed by Mixin from Refixes (MixinTickSleep)"));
    }

    @Test
    void threadStateImageLeavesNoSampleColumnsEmpty() throws Exception {
        byte[] png = HyboxChartImage.states(new int[] {0, 0, 1, 2, 3, 4}, 6, 2);
        var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
        assertEquals(0, image.getRGB(0, 0) >>> 24, "No samples stays transparent");
        assertEquals(0, image.getRGB(1, 1) >>> 24, "No samples stays transparent");
        assertEquals(0xffa29d93, image.getRGB(3, 0), "WAITING is warm grey");
        assertEquals(0xff6b665e, image.getRGB(4, 0), "TIMED_WAITING is dark grey");
        assertEquals(0xff4cc38a, image.getRGB(2, 0));
        assertEquals(0xfff0626b, image.getRGB(5, 0));
    }

    @Test
    void legacyDiagnosticsPreserveEveryEntryAndStackWithoutDecodingMarkupIntoStructure() throws Exception {
        Path path = directory.resolve("legacy.zip");
        String html = "<section id=\"stalled\"><div class=\"kvcard\" style=\"margin-bottom:16px\"><div class=\"kvh\"><b>Stalled thread</b></div><div class=\"kv\">"
            + "<div class=\"row\"><span class=\"k\">Blocked in</span><span class=\"v\">engine.Tick.run</span></div>"
            + "<div class=\"row\"><span class=\"k\">Owner</span><span class=\"v\">&lt;plugin&gt; &amp; x</span></div></div>"
            + "<pre class=\"logpre\">at one\nat two</pre></div><div class=\"kvcard\"><div class=\"kvh\"><b>Thread progress</b></div><div class=\"kv\"><div class=\"row\"><span class=\"k\">advanced</span><span class=\"v\">2</span></div></div></div></section>"
            + "<script>const D = {\"incident\":{\"id\":\"legacy\"}};\nmore();</script>";
        writeZip(path, html);
        IncidentModel model = IncidentModel.load(path);
        assertEquals(2, model.array("diag").size());
        assertEquals("engine.Tick.run", model.blockedIn());
        String rendered = renderText(model, "stalled");
        assertTrue(rendered.contains("engine.Tick.run")); assertTrue(rendered.contains("at one"));
        assertTrue(rendered.contains("at two")); assertFalse(rendered.contains("Thread progress"));
    }

    @Test
    void allSectionsRenderWithinThePacketBudget() throws Exception {
        IncidentModel model = IncidentModel.load(bundle(fixture(), ""));
        for (String section : HyboxIncidentRenderer.SECTIONS.keySet()) {
            int pages = HyboxIncidentRenderer.pageCount(model, state(section, 0, "all"));
            for (int page = 0; page < pages; page++) {
                UICommandBuilder cmd = new UICommandBuilder(); UIEventBuilder ev = new UIEventBuilder();
                HyboxIncidentRenderer.render(model, state(section, page, "all"), cmd, ev, null);
                assertTrue(Arrays.stream(cmd.getCommands()).anyMatch(c -> "#SectionTitle.Text".equals(c.selector)), section);
                assertTrue(Arrays.stream(cmd.getCommands()).anyMatch(c -> "#SectionBody".equals(c.selector) && c.data != null || c.selector != null && c.selector.startsWith("#SectionBody[")), section);
                long bytes = Arrays.stream(cmd.getCommands()).mapToLong(CustomUICommand::computeSize).sum()
                    + Arrays.stream(ev.getEvents()).mapToLong(e -> e.computeSize()).sum();
                assertTrue(bytes < 300_000, section + " packet bytes " + bytes);
                for (var event : ev.getEvents()) {
                    assertTrue(event.data.contains("Action"), event.selector);
                    assertTrue(event.data.contains("Arg"), event.selector);
                }
            }
        }
        assertEquals(1, HyboxIncidentRenderer.pageCount(model, state("plugins", 0, "all")));
        assertTrue(HyboxIncidentRenderer.pageCount(model, new HyboxIncidentRenderer.ViewState("plugins", 0, "", "all", true)) >= 6);
        assertEquals(1, HyboxIncidentRenderer.pageCount(model, state("methods", 0, "all")));
        String all = String.join("\n", HyboxIncidentRenderer.SECTIONS.keySet().stream().map(s -> renderText(model, s)).toList());
        for (String fact : List.of("2.9 s", "19,816", "106 Hytale core", "WorldThread", "1 asset pack and 1 config file", "1 warning in the server log", "Top caller")) assertTrue(all.contains(fact), fact);
        assertTrue(renderText(model, "top").contains("allocation"), "Findings use link labels, not section ids");
        String methods = renderText(model, "methods");
        assertTrue(methods.indexOf("engine.Method5") < methods.indexOf("And 44 more methods."), "The count of hidden methods comes after the list");
        UICommandBuilder zoomed = new UICommandBuilder();
        HyboxIncidentRenderer.render(model, new HyboxIncidentRenderer.ViewState("flame", 0, "0/0", "all", false), zoomed, new UIEventBuilder(), null);
        List<String> appended = Arrays.stream(zoomed.getCommands()).filter(c -> c.text != null && c.text.startsWith("Pages/HyboxIncident")).map(c -> c.text).toList();
        assertTrue(appended.indexOf("Pages/HyboxIncidentButton.ui") < appended.indexOf("Pages/HyboxIncidentFlame.ui"), "Zoom buttons stay above the graph");
    }

    @Test
    void humanCardsRangesAndScrollReplaceDebugDump() throws Exception {
        IncidentModel model = IncidentModel.load(bundle(fixture(), ""));
        for (String section : List.of("top", "system", "cpu", "metrics", "engine", "memory", "pluginmetrics", "network")) {
            assertEquals(1, HyboxIncidentRenderer.pageCount(model, state(section, 0, "all")), section + " scrolls as one section");
        }
        String overview = renderText(model, "top");
        assertTrue(overview.contains("17 min 49 s")); assertTrue(overview.contains("online at capture"));
        assertFalse(overview.contains("markerFrac")); assertFalse(overview.contains("worldShort")); assertFalse(overview.contains("profileFrac"));
        String system = renderText(model, "system");
        for (String title : List.of("Processor and memory", "Software", "\"Cores\"")) assertTrue(system.contains(title), title);
        assertFalse(system.contains("javaHome-value") || system.contains("startCommand-value"), "Paths and the start command are not shown in game");
        assertFalse(system.contains("Max heap"));
        UICommandBuilder cmd = new UICommandBuilder();
        HyboxIncidentRenderer.render(model, state("top", 0, "all"), cmd, new UIEventBuilder(), null);
        assertTrue(Arrays.stream(cmd.getCommands()).anyMatch(c -> "#RangeControls.Visible".equals(c.selector) && c.data.contains("false")));
        assertFalse(Arrays.stream(cmd.getCommands()).anyMatch(c -> c.text != null && c.text.contains("RangeSelected.ui")));
        cmd = new UICommandBuilder();
        HyboxIncidentRenderer.render(model, state("metrics", 0, "all"), cmd, new UIEventBuilder(), null);
        assertTrue(Arrays.stream(cmd.getCommands()).anyMatch(c -> c.text != null && c.text.contains("RangeSelected.ui")));
        assertFalse(Arrays.stream(cmd.getCommands()).anyMatch(c -> c.selector != null && c.selector.startsWith("#RangeControls[") && c.selector.endsWith(".Disabled")));
        cmd = new UICommandBuilder(); UIEventBuilder ev = new UIEventBuilder();
        HyboxIncidentRenderer.render(model, state("top", 0, "all"), cmd, ev, null);
        assertTrue(Arrays.stream(cmd.getCommands()).anyMatch(c -> c.text != null && c.text.contains("HyboxIncidentEvidence.ui")));
        assertTrue(Arrays.stream(ev.getEvents()).anyMatch(e -> e.selector.contains("#EvidenceLink")));
    }

    private static final class RecordingDelivery implements HyboxChartAssets.Delivery {
        final List<Runnable> work = new ArrayList<>(), world = new ArrayList<>();
        final List<com.hypixel.hytale.protocol.ToClientPacket[]> sent = new ArrayList<>();
        final List<UICommandBuilder> updates = new ArrayList<>();
        boolean failSend;
        @Override public void work(Runnable task) { work.add(task); }
        @Override public void send(com.hypixel.hytale.protocol.ToClientPacket[] packets) { if (failSend) throw new IllegalStateException("closed"); sent.add(packets); }
        @Override public void update(Runnable onWorldThread) { world.add(onWorldThread); }
        @Override public void update(UICommandBuilder commands) { updates.add(commands); }
        void runWorker() { var tasks = List.copyOf(work); work.clear(); tasks.forEach(Runnable::run); }
        void runWorld() { var tasks = List.copyOf(world); world.clear(); tasks.forEach(Runnable::run); }
    }

    private static void renderWith(HyboxChartAssets assets, IncidentModel model, String section, String range, UICommandBuilder cmd) {
        assets.begin();
        HyboxIncidentRenderer.render(model, state(section, 0, range), cmd, new UIEventBuilder(), assets);
        assets.flush();
    }

    private static List<String> assetNames(com.hypixel.hytale.protocol.ToClientPacket[] packets) {
        return Arrays.stream(packets).filter(p -> p instanceof com.hypixel.hytale.protocol.packets.setup.AssetInitialize)
            .map(p -> ((com.hypixel.hytale.protocol.packets.setup.AssetInitialize) p).asset.name).toList();
    }

    @Test
    void everyChartIsDrawnOffThreadAndSwappedInAfterTheNativeBars() throws Exception {
        IncidentModel model = IncidentModel.load(bundle(fixture(), ""));
        RecordingDelivery delivery = new RecordingDelivery();
        HyboxChartAssets assets = new HyboxChartAssets(delivery, failure -> fail(failure));
        UICommandBuilder cmd = new UICommandBuilder();
        renderWith(assets, model, "metrics", "all", cmd);
        var initial = Arrays.asList(cmd.getCommands());
        assertFalse(initial.stream().anyMatch(c -> c.selector != null && c.selector.endsWith("Image.AssetPath")));
        List<String> charts = initial.stream().filter(c -> c.selector != null && c.selector.endsWith("#ChartTitle.Text")).map(c -> c.selector.replace(" #ChartTitle.Text", "")).toList();
        assertTrue(charts.size() >= 2, "Tick time and TPS charts");
        for (String chart : charts) assertTrue(initial.stream().anyMatch(c -> (chart + " #Bars").equals(c.selector) && c.text != null), "Native bars show until the image lands: " + chart);
        assertTrue(delivery.sent.isEmpty());
        assertEquals(1, delivery.work.size(), "One worker task for the whole page");

        delivery.runWorker();
        assertEquals(1, delivery.sent.size(), "All new images go out in one batch");
        var packets = delivery.sent.get(0);
        assertFalse(assetNames(packets).isEmpty());
        assertEquals(assetNames(packets).size(), Set.copyOf(assetNames(packets)).size(), "No asset is sent twice in a batch");
        assertEquals(1, Arrays.stream(packets).filter(p -> p instanceof com.hypixel.hytale.protocol.packets.setup.RequestCommonAssetsRebuild).count(), "One cache rebuild per page");
        assertInstanceOf(com.hypixel.hytale.protocol.packets.setup.RequestCommonAssetsRebuild.class, packets[packets.length - 1]);
        assertTrue(delivery.updates.isEmpty(), "The page update waits for the world thread");
        delivery.runWorld();
        assertEquals(1, delivery.updates.size());
        var swap = Arrays.asList(delivery.updates.get(0).getCommands());
        for (String chart : charts) {
            String encoded = swap.stream().filter(c -> (chart + " #PlotImage.AssetPath").equals(c.selector)).map(c -> c.data).findFirst().orElseThrow();
            String path = JsonParser.parseString(encoded).getAsJsonObject().get("0").getAsString();
            assertTrue(path.matches("UI/Custom/Hybox/Charts/[0-9a-f]{64}\\.png"), path);
            assertTrue(assetNames(packets).contains(path));
            assertTrue(swap.stream().anyMatch(c -> (chart + " #Bars").equals(c.selector) && c.type.name().equals("Clear")), "Bars are cleared when the image arrives");
            assertTrue(initial.stream().anyMatch(c -> (chart + " #YMax.Text").equals(c.selector)), "Bars and image share one axis");
            assertFalse(swap.stream().anyMatch(c -> (chart + " #YMax.Text").equals(c.selector)), "The image keeps the axis of the bars");
        }
        var init = (com.hypixel.hytale.protocol.packets.setup.AssetInitialize) packets[0];
        byte[] png = ((com.hypixel.hytale.protocol.packets.setup.AssetPart) packets[1]).part;
        assertInstanceOf(com.hypixel.hytale.protocol.packets.setup.AssetFinalize.class, packets[2]);
        assertEquals(init.size, png.length);
        assertEquals(HyboxChartAssets.sha256(png), init.asset.hash);
        var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
        assertEquals(HyboxChartImage.WIDTH, image.getWidth(), "Drawn at displayed size");
        assertEquals(HyboxChartImage.HEIGHT, image.getHeight());

        renderWith(assets, model, "metrics", "all", new UICommandBuilder());
        delivery.runWorker(); delivery.runWorld();
        assertEquals(1, delivery.sent.size());
        assertEquals(2, delivery.updates.size());
        renderWith(assets, model, "system", "all", new UICommandBuilder());
        assertTrue(delivery.work.isEmpty());
    }

    @Test
    void threadStripsAndOtherThreadTimelinesBecomeImages() throws Exception {
        IncidentModel model = IncidentModel.load(bundle(fixture(), ""));
        RecordingDelivery delivery = new RecordingDelivery();
        HyboxChartAssets assets = new HyboxChartAssets(delivery, failure -> fail(failure));
        UICommandBuilder cmd = new UICommandBuilder();
        renderWith(assets, model, "threads", "all", cmd);
        var initial = Arrays.asList(cmd.getCommands());
        long strips = initial.stream().filter(c -> c.selector != null && c.selector.endsWith("#StripBars") && c.text != null).map(c -> c.selector).distinct().count();
        assertTrue(strips > 0, "Native strip runs render first");
        delivery.runWorker(); delivery.runWorld();
        var swap = Arrays.asList(delivery.updates.get(0).getCommands());
        assertEquals(strips, swap.stream().filter(c -> c.selector != null && c.selector.endsWith("#StripImage.AssetPath")).count(), "Every visible strip gets an image");
        assertTrue(swap.stream().anyMatch(c -> c.selector != null && c.selector.endsWith("#StripBars") && c.type.name().equals("Clear")));
        byte[] png = ((com.hypixel.hytale.protocol.packets.setup.AssetPart) delivery.sent.get(0)[1]).part;
        var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
        assertEquals(12, image.getHeight());
    }

    @Test
    void flameKeepsWideFramesInteractiveAndDrawsNarrowOnesAsOneImage() throws Exception {
        JsonObject data = fixture();
        JsonArray children = new JsonArray();
        children.add(JsonParser.parseString("[\"wide.Frame\",10000,[],1]"));
        for (int i = 0; i < 40; i++) children.add(JsonParser.parseString("[\"sliver" + i + "\",40,[],2]"));
        JsonArray root = new JsonArray(); root.add("all"); root.add(11600); root.add(children); root.add(0);
        data.add("flame", root);
        IncidentModel model = IncidentModel.load(bundle(data, ""));
        RecordingDelivery delivery = new RecordingDelivery();
        HyboxChartAssets assets = new HyboxChartAssets(delivery, failure -> fail(failure));
        UICommandBuilder cmd = new UICommandBuilder(); UIEventBuilder events = new UIEventBuilder();
        assets.begin();
        HyboxIncidentRenderer.render(model, state("flame", 0, "all"), cmd, events, assets);
        assets.flush();
        var commands = Arrays.asList(cmd.getCommands());
        long buttons = commands.stream().filter(c -> "Pages/HyboxIncidentFlame.ui".equals(c.text)).count();
        assertEquals(2, buttons, "Root and the wide frame stay clickable buttons");
        assertTrue(Arrays.stream(events.getEvents()).anyMatch(e -> e.selector.startsWith("#FlameFrames[") && e.data.contains("zoom")));
        delivery.runWorker(); delivery.runWorld();
        assertEquals(1, assetNames(delivery.sent.get(0)).size(), "All narrow frames share one image");
        var swap = Arrays.asList(delivery.updates.get(0).getCommands());
        assertTrue(swap.stream().anyMatch(c -> "#FlameImage.AssetPath".equals(c.selector)));
        byte[] png = ((com.hypixel.hytale.protocol.packets.setup.AssetPart) delivery.sent.get(0)[1]).part;
        var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
        int sliverX = (int) Math.round(10000.0 / 11600 * 940) + 5;
        assertTrue((image.getRGB(sliverX, 25 + 10) >>> 24) > 100, "Narrow frames are drawn");
        assertEquals(0, image.getRGB(100, 25 + 10) >>> 24, "Wide frames are left to their native buttons");
    }

    @Test
    void switchingSectionBeforeImagesLandDropsTheStaleSwap() throws Exception {
        IncidentModel model = IncidentModel.load(bundle(fixture(), ""));
        RecordingDelivery delivery = new RecordingDelivery();
        HyboxChartAssets assets = new HyboxChartAssets(delivery, failure -> fail(failure));
        renderWith(assets, model, "metrics", "all", new UICommandBuilder());
        delivery.runWorker();
        renderWith(assets, model, "system", "all", new UICommandBuilder());
        delivery.runWorld();
        assertTrue(delivery.updates.isEmpty(), "Images for Key metrics must not be applied to another section");
        renderWith(assets, model, "metrics", "1m", new UICommandBuilder());
        renderWith(assets, model, "system", "all", new UICommandBuilder());
        delivery.runWorker();
        assertEquals(1, delivery.sent.size());
        renderWith(assets, model, "metrics", "1m", new UICommandBuilder());
        delivery.runWorker();
        assertEquals(2, delivery.sent.size());
    }

    @Test
    void chartImageFailureKeepsNativeBarsAndStopsTrying() throws Exception {
        IncidentModel model = IncidentModel.load(bundle(fixture(), ""));
        RecordingDelivery delivery = new RecordingDelivery();
        delivery.failSend = true;
        List<Throwable> failures = new ArrayList<>();
        HyboxChartAssets assets = new HyboxChartAssets(delivery, failures::add);
        renderWith(assets, model, "metrics", "all", new UICommandBuilder());
        delivery.runWorker(); delivery.runWorld();
        assertEquals(1, failures.size());
        assertTrue(delivery.updates.isEmpty(), "Native bars stay when delivery fails");
        renderWith(assets, model, "metrics", "1m", new UICommandBuilder());
        assertTrue(delivery.work.isEmpty(), "No further image work after the first failure");
    }

    @Test
    void chartImageKeepsEverySpikeWhenSamplesOutnumberPixels() throws Exception {
        double[] values = new double[HyboxChartImage.WIDTH * 4];
        values[1234] = 100; values[1235] = 0;
        var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(HyboxChartImage.line(values, new io.github.xytronix.hybox.core.metrics.ChartAxis.Axis(100, Double.NaN), new double[0])));
        int x = (int) Math.round(1234.0 * (HyboxChartImage.WIDTH - 1) / (values.length - 1));
        int topAlpha = image.getRGB(x, HyboxChartImage.TOP + 1) >>> 24;
        assertTrue(topAlpha > 200, "Single-sample spike reaches the top gridline, alpha " + topAlpha);
    }

    @Test
    void noColourWithAlphaIsSentFromJavaInAnySection() throws Exception {
        IncidentModel model = IncidentModel.load(bundle(fixture(), ""));
        var alpha = java.util.regex.Pattern.compile("#[0-9a-fA-F]{6}\\(");
        for (String section : HyboxIncidentRenderer.SECTIONS.keySet()) {
            UICommandBuilder cmd = new UICommandBuilder();
            List<HyboxIncidentRenderer.ChartRequest> requests = new ArrayList<>();
            HyboxIncidentRenderer.render(model, state(section, 0, "all"), cmd, new UIEventBuilder(), requests::add);
            for (var request : requests) request.apply().accept("UI/Custom/Hybox/Charts/test.png", cmd);
            for (CustomUICommand c : cmd.getCommands()) {
                assertFalse(c.data != null && alpha.matcher(c.data).find(), section + " " + c.selector);
                assertFalse(c.text != null && alpha.matcher(c.text).find(), section + " " + c.selector);
            }
        }
    }

    @Test
    void chartsUseSeriesBarsWithoutGcOverlapAndTimeOnlyCaption() throws Exception {
        IncidentModel model = IncidentModel.load(bundle(fixture(), ""));
        UICommandBuilder cmd = new UICommandBuilder();
        HyboxIncidentRenderer.render(model, state("metrics", 0, "all"), cmd, new UIEventBuilder(), null);
        assertFalse(Arrays.stream(cmd.getCommands()).anyMatch(c -> c.text != null && c.text.contains(HyboxChartImage.hex(HyboxChartImage.STATES[3]))), "GC overlap is not drawn on charts");
        assertFalse(Arrays.stream(cmd.getCommands()).anyMatch(c -> c.data != null && c.data.contains("GC overlap")));
        assertTrue(Arrays.stream(cmd.getCommands()).anyMatch(c -> c.selector != null && c.selector.endsWith("#Bars") && c.text != null && c.text.contains(HyboxChartImage.hex(HyboxChartImage.LINE))));
        var commands = Arrays.asList(cmd.getCommands());
        assertTrue(commands.stream().anyMatch(c -> c.selector != null && c.selector.endsWith("#YMax.Text") && c.data.contains("ms")));
        assertTrue(commands.stream().anyMatch(c -> c.selector != null && c.selector.endsWith("#YMin.Text") && c.data.contains("0")));
        for (int i = 0; i < 5; i++) {
            String axis = "#X" + i + ".Text";
            assertTrue(commands.stream().anyMatch(c -> c.selector != null && c.selector.endsWith(axis) && c.data.matches(".*\\d\\d:\\d\\d:\\d\\d.*")), axis);
        }
        String first = commands.stream().filter(c -> c.selector != null && c.selector.endsWith("#X0.Text")).map(c -> c.data).findFirst().orElseThrow();
        String last = commands.stream().filter(c -> c.selector != null && c.selector.endsWith("#X4.Text")).map(c -> c.data).findFirst().orElseThrow();
        assertNotEquals(first, last, "X axis spans the recording");
        cmd = new UICommandBuilder(); UIEventBuilder events = new UIEventBuilder();
        HyboxIncidentRenderer.render(model, state("plugins", 0, "all"), cmd, events, null);
        assertTrue(Arrays.stream(events.getEvents()).anyMatch(e -> e.data.contains("toggle") && e.data.contains("core")));
        assertFalse(Arrays.stream(cmd.getCommands()).anyMatch(c -> c.selector != null && c.selector.endsWith("#Name.Text") && c.data.contains("Plugin0")));
        UICommandBuilder allThreads = new UICommandBuilder(), minuteThreads = new UICommandBuilder();
        HyboxIncidentRenderer.render(model, state("threads", 0, "all"), allThreads, new UIEventBuilder(), null);
        HyboxIncidentRenderer.render(model, state("threads", 0, "1m"), minuteThreads, new UIEventBuilder(), null);
        String allIntervals = Arrays.stream(allThreads.getCommands()).filter(c -> c.selector != null && c.selector.endsWith("#StripHover[1].TooltipText")).map(c -> c.data).findFirst().orElseThrow();
        String minuteIntervals = Arrays.stream(minuteThreads.getCommands()).filter(c -> c.selector != null && c.selector.endsWith("#StripHover[1].TooltipText")).map(c -> c.data).findFirst().orElseThrow();
        assertTrue(allIntervals.matches(".*\\d\\d:\\d\\d:\\d\\d to \\d\\d:\\d\\d:\\d\\d · .*"), allIntervals);
        assertNotEquals(allIntervals, minuteIntervals, "Thread state strips honor the selected time range");
    }

    @Test
    void pluginMetricsShowTwoChartsAndEightRowsOfRealModValuesOnly() throws Exception {
        JsonObject data = fixture();
        JsonArray metrics = new JsonArray();
        for (int i = 0; i < 64; i++) {
            JsonObject o = new JsonObject();
            o.addProperty("name", "plugin/rate-" + i); o.addProperty("kind", "count"); o.addProperty("bucketSec", 3.75); o.addProperty("events", 100 - i);
            o.addProperty("total", 100); o.add("amounts", series(240)); o.add("series", series(240));
            metrics.add(o);
        }
        for (int i = 0; i < 130; i++) {
            JsonObject o = new JsonObject();
            o.addProperty("name", "plugin/idle-" + i); o.addProperty("kind", "count"); o.addProperty("total", 0);
            o.addProperty("uncharted", i < 120 ? "zero" : "single"); o.add("series", JsonNull.INSTANCE);
            metrics.add(o);
        }
        JsonObject own = new JsonObject();
        own.addProperty("name", "Hybox/capture"); own.addProperty("kind", "gauge"); own.addProperty("events", 1000); own.add("series", series(240));
        metrics.add(own);
        data.add("pluginMetrics", metrics);
        IncidentModel model = IncidentModel.load(bundle(data, ""));
        assertEquals(1, HyboxIncidentRenderer.pageCount(model, state("pluginmetrics", 0, "all")));
        UICommandBuilder cmd = new UICommandBuilder();
        HyboxIncidentRenderer.render(model, state("pluginmetrics", 0, "all"), cmd, new UIEventBuilder(), null);
        assertEquals(2, Arrays.stream(cmd.getCommands()).filter(c -> "Pages/HyboxIncidentChart.ui".equals(c.text)).count());
        String all = renderText(model, "pluginmetrics");
        for (String name : List.of("Plugin · Rate-0 · ", "Plugin · Rate-1 · ", "\"Plugin · Rate-9\"", "\"plugin/rate-0\"", "\"plugin/rate-9\"", "And 54 more metrics.")) assertTrue(all.contains(name), name);
        for (String name : List.of("Rate-10", "plugin/rate-10", "plugin/idle-", "Hybox/capture")) assertFalse(all.contains(name), name);
        assertTrue(all.contains("Plugin · Idle-0, ") && all.contains("Plugin · Idle-7 and 112 more"), "Metrics that stay at zero are named, not dropped: " + all);
        assertTrue(all.contains("Plugin · Idle-120 (total 0), ") && all.contains(" and 2 more"), "Metrics reported once are named with their totals: " + all);
        data.add("pluginMetrics", JsonParser.parseString("[" + own + "]"));
        assertFalse(HyboxIncidentRenderer.hasContent(IncidentModel.load(bundle(data, "")), "pluginmetrics"), "Hybox's own metrics alone hide the section");
    }

    @Test
    void flatSeriesBecomeOneLineAndAFreezeUsesALogAxis() throws Exception {
        JsonObject data = fixture();
        JsonObject series = data.getAsJsonObject("series");
        JsonArray heap = new JsonArray(), net = new JsonArray(), mspt = new JsonArray();
        for (int i = 0; i < 240; i++) { heap.add(2147483648L); net.add(0); mspt.add(i == 200 ? 2000 : 5); }
        series.add("heap", heap); series.add("netOut", net); series.add("mspt", mspt);
        IncidentModel model = IncidentModel.load(bundle(data, ""));
        String memory = renderText(model, "memory");
        assertTrue(memory.contains("Heap used: 2.0 GiB the whole time."), memory);
        assertTrue(renderText(model, "network").contains("Network outbound · lo0: 0.0 Mbit/s the whole time."));
        UICommandBuilder cmd = new UICommandBuilder();
        HyboxIncidentRenderer.render(model, state("memory", 0, "all"), cmd, new UIEventBuilder(), null);
        assertFalse(Arrays.stream(cmd.getCommands()).anyMatch(c -> "Pages/HyboxIncidentChart.ui".equals(c.text)), "A flat heap is not charted");
        String metrics = renderText(model, "metrics");
        assertTrue(metrics.contains(" ms, log scale"), metrics);
        assertTrue(metrics.contains("1000 ms"), "log tick labels reach 1000 ms: " + metrics);
    }

    @Test
    void arbitraryBundleTextNeverEntersInlineMarkup() throws Exception {
        String attack = "\"; } Group { Label { Text: \"x";
        JsonObject data = fixture();
        data.getAsJsonArray("plugins").get(107).getAsJsonArray().set(1, new com.google.gson.JsonPrimitive(attack));
        data.getAsJsonArray("flame").set(0, new com.google.gson.JsonPrimitive(attack));
        data.getAsJsonArray("hotMethods").get(0).getAsJsonArray().set(1, new com.google.gson.JsonPrimitive(attack));
        data.getAsJsonArray("pluginMetrics").get(0).getAsJsonObject().addProperty("name", attack);
        data.add("log", JsonParser.parseString("[[\"WARN\",\"source\",\"safe\"]]"));
        data.getAsJsonArray("log").get(0).getAsJsonArray().set(2, new com.google.gson.JsonPrimitive(attack));
        IncidentModel model = IncidentModel.load(bundle(data, ""));
        boolean sentAsValue = false;
        for (String section : HyboxIncidentRenderer.SECTIONS.keySet()) {
            int count = HyboxIncidentRenderer.pageCount(model, state(section, 0, "all"));
            for (int page = 0; page < count; page++) {
                UICommandBuilder cmd = new UICommandBuilder();
                HyboxIncidentRenderer.render(model, state(section, page, "all"), cmd, new UIEventBuilder(), null);
                for (CustomUICommand c : cmd.getCommands()) {
                    if (c.text != null) {
                        assertFalse(c.text.contains(attack), section + " inline markup contains bundle text");
                        assertFalse(c.text.contains("Text:"), "Inline markup is geometry only");
                    }
                    if (c.data != null && c.data.contains("Group { Label")) sentAsValue = true;
                }
            }
        }
        assertTrue(sentAsValue, "Malicious name is still rendered, safely through set");
    }

    @Test
    void flameZoomRangeAndTimelineProduceGeometryAndActions() throws Exception {
        IncidentModel model = IncidentModel.load(bundle(fixture(), ""));
        UICommandBuilder cmd = new UICommandBuilder(); UIEventBuilder events = new UIEventBuilder();
        var zoom = new HyboxIncidentRenderer.ViewState("flame", 0, "0", "all", false);
        HyboxIncidentRenderer.render(model, zoom, cmd, events, null);
        assertTrue(Arrays.stream(events.getEvents()).anyMatch(e -> e.data != null && e.data.contains("zoom")));
        assertTrue(Arrays.stream(cmd.getCommands()).anyMatch(c -> c.text != null && c.text.contains("HyboxIncidentFlame.ui")), "First flame page includes the icicle, not just controls");
        assertTrue(renderText(model, "threads").contains("Hot threads · ranked by CPU samples"));
        cmd = new UICommandBuilder();
        HyboxIncidentRenderer.render(model, state("metrics", 0, "1m"), cmd, new UIEventBuilder(), null);
        assertTrue(Arrays.stream(cmd.getCommands()).anyMatch(c -> c.data != null && c.data.contains("1m")));
        assertTrue(Arrays.stream(cmd.getCommands()).filter(c -> c.text != null).count() < 1500);
    }

    @Test
    void hotThreadsShowTheTopSixWithTheirOwnTimeline() throws Exception {
        JsonObject data = fixture();
        data.getAsJsonArray("threadTimeline").get(1).getAsJsonObject().addProperty("name", "Unranked-worker");
        IncidentModel model = IncidentModel.load(bundle(data, ""));
        assertEquals(1, HyboxIncidentRenderer.pageCount(model, state("threads", 0, "all")));
        UICommandBuilder firstPage = new UICommandBuilder(); UIEventBuilder events = new UIEventBuilder();
        HyboxIncidentRenderer.render(model, state("threads", 0, "all"), firstPage, events, null);
        var commands = Arrays.asList(firstPage.getCommands());
        assertEquals(6, commands.stream().filter(c -> "Pages/HyboxIncidentThread.ui".equals(c.text)).count());
        assertEquals(1, commands.stream().filter(c -> "Pages/HyboxIncidentThreadLegend.ui".equals(c.text)).count());
        assertEquals(1, commands.stream().filter(c -> "Pages/HyboxIncidentThreadAxis.ui".equals(c.text)).count());
        assertEquals(0, commands.stream().filter(c -> "Pages/HyboxIncidentChart.ui".equals(c.text)).count(), "Other sampled threads are not charted");
        String topRow = commands.stream().filter(c -> c.selector != null && c.selector.endsWith(" #Name.Text") && c.data.contains("Worker-0"))
            .map(c -> c.selector.substring(0, c.selector.length() - " #Name.Text".length())).findFirst().orElseThrow();
        assertTrue(commands.stream().anyMatch(c -> (topRow + " #Rank.Text").equals(c.selector) && c.data.contains("#1")));
        assertTrue(commands.stream().anyMatch(c -> (topRow + " #Name.TooltipText").equals(c.selector) && c.data.contains("Worker-0")));
        assertTrue(commands.stream().anyMatch(c -> (topRow + " #Strip.Visible").equals(c.selector) && c.data.contains("true")));
        assertTrue(commands.stream().anyMatch(c -> (topRow + " #StripBars").equals(c.selector) && c.text != null && c.text.contains("Height: 12")));
        assertTrue(commands.stream().anyMatch(c -> (topRow + " #Top.TooltipText").equals(c.selector) && c.data.contains("engine.Tick.run") && c.data.contains("384 samples")));
        String withoutTimeline = commands.stream().filter(c -> c.selector != null && c.selector.endsWith(" #Name.Text") && c.data != null
                && "Worker-1".equals(JsonParser.parseString(c.data).getAsJsonObject().get("0").getAsString()))
            .map(c -> c.selector.substring(0, c.selector.length() - " #Name.Text".length())).findFirst().orElseThrow();
        assertTrue(commands.stream().anyMatch(c -> (withoutTimeline + " #Strip.Visible").equals(c.selector) && c.data.contains("true")), "Rows line up with an empty strip");
        assertFalse(commands.stream().anyMatch(c -> (withoutTimeline + " #StripBars").equals(c.selector)));
        assertTrue(commands.stream().anyMatch(c -> (withoutTimeline + " #StripHover[0].TooltipText").equals(c.selector) && c.data.contains("No timeline saved for this thread")));
        long bytes = commands.stream().mapToLong(CustomUICommand::computeSize).sum() + Arrays.stream(events.getEvents()).mapToLong(e -> e.computeSize()).sum();
        assertTrue(bytes < 300_000, "Thread page packet bytes " + bytes);
        for (int i = 0; i < 5; i++) {
            String axis = "#X" + i + ".Text";
            assertTrue(commands.stream().anyMatch(c -> c.selector != null && c.selector.endsWith(axis) && c.data.matches(".*\\d\\d:\\d\\d:\\d\\d.*")), axis);
        }
        assertTrue(renderText(model, "threads").contains("And 44 more threads."));
    }

    @Test
    void hotThreadsUseTheTimelineOfTheirPool() throws Exception {
        JsonObject data = fixture();
        JsonObject pool = new JsonObject(); pool.addProperty("name", "Worker"); pool.add("states", data.getAsJsonArray("threadTimeline").get(0).getAsJsonObject().get("states"));
        data.add("threadTimeline", JsonParser.parseString("[" + pool + "]"));
        UICommandBuilder cmd = new UICommandBuilder();
        HyboxIncidentRenderer.render(IncidentModel.load(bundle(data, "")), state("threads", 0, "all"), cmd, new UIEventBuilder(), null);
        var commands = Arrays.asList(cmd.getCommands());
        assertEquals(6, commands.stream().filter(c -> c.selector != null && c.selector.endsWith(" #StripBars") && c.text != null).map(c -> c.selector).distinct().count(), "Every hot thread of the pool shows the pool timeline");
        assertTrue(commands.stream().anyMatch(c -> c.selector != null && c.selector.contains("#StripHover[") && c.data != null && c.data.contains("All Worker threads · ")));
        assertFalse(commands.stream().anyMatch(c -> c.data != null && c.data.contains("No timeline saved for this thread")));
    }

    @Test
    void real000005HotThreadsStayWithinElementAndPacketBoundsWhenAvailable() throws Exception {
        Path path = Path.of(System.getProperty("hybox.test.currentIncident", "/tmp/hybox-ui-preview/incident-000005-current.zip"));
        assumeTrue(Files.isRegularFile(path), "Optional current incident 000005 fixture is not installed");
        IncidentModel model = IncidentModel.load(path);
        int pages = HyboxIncidentRenderer.pageCount(model, state("threads", 0, "all"));
        assertEquals(1, pages);
        for (String range : List.of("all", "incident", "1m", "5m")) {
            for (int page = 0; page < pages; page++) {
                UICommandBuilder cmd = new UICommandBuilder(); UIEventBuilder events = new UIEventBuilder();
                HyboxIncidentRenderer.render(model, state("threads", page, range), cmd, events, null);
                long bytes = Arrays.stream(cmd.getCommands()).mapToLong(CustomUICommand::computeSize).sum() + Arrays.stream(events.getEvents()).mapToLong(e -> e.computeSize()).sum();
                assertTrue(bytes < 300_000, "Thread page " + page + " range " + range + " packet bytes " + bytes);
                assertEquals(1, Arrays.stream(cmd.getCommands()).filter(c -> "Pages/HyboxIncidentThreadLegend.ui".equals(c.text)).count());
                System.out.println("CURRENT_000005 threads page=" + page + " range=" + range + " packetBytes=" + bytes);
            }
        }
    }

    @Test
    void twoCopiesOfOneInstanceKeepTheirOwnCpuShare() throws Exception {
        JsonObject data = fixture();
        String first = "instance-portals-0b7fd9ff-9045-524f-5ffa-3225b9f7c3c0", second = "instance-portals-1c8fe0aa-9045-524f-5ffa-3225b9f7c3c1", arena = "instance-arena-2d9f11bb-9045-524f-5ffa-3225b9f7c3c2";
        data.add("worlds", JsonParser.parseString("[{\"name\":\"" + first + "\",\"samples\":100},{\"name\":\"" + second + "\",\"samples\":300},{\"name\":\"" + arena + "\",\"samples\":50}]"));
        data.add("cpuByWorld", JsonParser.parseString("[{\"world\":\"" + first + "\",\"samples\":1981},{\"world\":\"" + second + "\",\"samples\":3963},{\"world\":\"" + arena + "\",\"samples\":991}]"));
        IncidentModel model = IncidentModel.load(bundle(data, ""));
        String worlds = renderText(model, "worlds");
        assertTrue(worlds.contains("CPU share 10.0%, #2 of 3") && worlds.contains("CPU share 20.0%, #1 of 3"), worlds);
        String cpu = renderText(model, "cpu");
        for (String rendered : List.of(worlds, cpu)) {
            assertTrue(rendered.contains("\"portals-0b7fd9ff-9045-524f-5ffa-3225b9f7c3c0\"") && rendered.contains("\"portals-1c8fe0aa-9045-524f-5ffa-3225b9f7c3c1\""), "Two copies with the same short name keep their ids: " + rendered);
            assertTrue(rendered.contains("\"arena\""), "A world with a unique short name keeps it: " + rendered);
            assertFalse(rendered.contains(first) || rendered.contains(arena), rendered);
        }
        assertFalse(cpu.contains("30.0%"), cpu);
    }

    @Test
    void cpuShareKeepsTheIncidentWorldWhenItIsNotInTheTopThree() throws Exception {
        JsonObject data = fixture();
        data.add("cpuByWorld", JsonParser.parseString("[{\"world\":\"(shared)\",\"samples\":9000},{\"world\":\"instance-a\",\"samples\":4000},"
            + "{\"world\":\"instance-b\",\"samples\":3000},{\"world\":\"instance-c\",\"samples\":2000},{\"world\":\"default\",\"samples\":300}]"));
        String five = renderText(IncidentModel.load(bundle(data, "")), "cpu");
        assertTrue(five.contains("2,000 samples") && five.contains("1.5%, 300 samples"), "Five rows or fewer are all shown: " + five);
        data.getAsJsonArray("cpuByWorld").add(JsonParser.parseString("{\"world\":\"instance-d\",\"samples\":1000}"));
        String cpu = renderText(IncidentModel.load(bundle(data, "")), "cpu");
        assertTrue(cpu.contains("\"default\"") && cpu.contains("1.5%, 300 samples"), cpu);
        assertFalse(cpu.contains("2,000 samples") || cpu.contains("1,000 samples"), "Worlds past the top three are counted, not listed");
        assertTrue(cpu.contains("And 2 more worlds."), cpu);
    }

    @Test
    void tickChartNamesTheStallWhenTheMarkerIsAtTheEnd() throws Exception {
        JsonObject data = fixture();
        data.getAsJsonObject("incident").addProperty("markerFrac", 1.0);
        UICommandBuilder cmd = new UICommandBuilder();
        HyboxIncidentRenderer.render(IncidentModel.load(bundle(data, "")), state("metrics", 0, "all"), cmd, new UIEventBuilder(), null);
        String stats = Arrays.stream(cmd.getCommands()).filter(c -> c.selector != null && c.selector.endsWith("#ChartStats.Text") && c.data.contains(" ms"))
            .map(c -> c.data).findFirst().orElseThrow();
        assertTrue(stats.contains("stall 2.9 s"), stats);
    }

    @Test
    void worldsWithTheSameCpuKeepTheOrderOfTheBundle() throws Exception {
        JsonObject data = fixture();
        data.add("worlds", JsonParser.parseString("[{\"name\":\"instance-Frontier\",\"samples\":103,\"stalled\":false},{\"name\":\"default\",\"samples\":103,\"stalled\":true}]"));
        data.add("cpuByWorld", JsonParser.parseString("[{\"world\":\"instance-Frontier\",\"samples\":103},{\"world\":\"default\",\"samples\":103}]"));
        String worlds = renderText(IncidentModel.load(bundle(data, "")), "worlds");
        assertTrue(worlds.contains("#2 of 2 by CPU, stalled world") && worlds.contains("#1 of 2 by CPU\""), worlds);
    }

    @Test
    void overviewLeadsWithTheCauseAndStepsAndDiagnosisKeepsTheRest() throws Exception {
        JsonObject data = fixture();
        data.remove("findings");
        data.add("diagnosis", JsonParser.parseString("""
            {"kind":"cause",
             "cause":{"text":"A 2.9 s GC pause covers the whole freeze.","spans":[["A ",""],["2.9 s","b"],[" GC pause","","GC pause"],[" covers the whole freeze.",""]],
               "section":"memory","label":"Memory & GC","hover":"Threshold 50 ms.","confidence":"clear"},
             "why":[{"text":"Heap 4.0 of 4.0 GiB.","spans":[["Heap 4.0 of 4.0 GiB.",""]],"section":"memory","label":"Memory & GC","hover":null}],
             "steps":[{"text":"Raise -Xmx.","spans":[["Raise ",""],["-Xmx","code"],[" to 6 GiB.",""]],"section":"system","label":"System & hardware","hover":null}],
             "other":[{"text":"Swap grew.","spans":[["Swap grew.",""]],"section":null,"label":null,"hover":null}],
             "terms":[{"term":"GC pause","meaning":"A stop of every server thread while the garbage collector frees memory.","doc":"reading-a-report#gc-pause"}]}
            """));
        IncidentModel model = IncidentModel.load(bundle(data, ""));
        String overview = renderText(model, "top");
        assertTrue(overview.contains("\"Cause\"") && overview.contains(" covers the whole freeze.") && overview.contains("\"Next steps\"") && overview.contains(" to 6 GiB."), overview);
        assertTrue(overview.contains("GC pause: A stop of every server thread") && overview.contains("Threshold 50 ms."), "The term meaning and the threshold are on the line tooltip: " + overview);
        assertFalse(overview.contains("Heap 4.0 of 4.0 GiB") || overview.contains("Swap grew"), overview);
        assertTrue(overview.contains("2 more findings in Diagnosis"), overview);
        String diagnosis = renderText(model, "diagnosis");
        assertTrue(diagnosis.contains("\"Why\"") && diagnosis.contains("Heap 4.0 of 4.0 GiB") && diagnosis.contains("\"Other findings\"") && diagnosis.contains("Swap grew"), diagnosis);
        assertFalse(diagnosis.contains("Older bundle") || diagnosis.contains(" covers the whole freeze.") || diagnosis.contains(" to 6 GiB."), diagnosis);
        JsonObject notable = data.getAsJsonObject("diagnosis");
        notable.addProperty("kind", "notable");
        notable.add("cause", JsonNull.INSTANCE);
        notable.add("steps", new JsonArray());
        IncidentModel quiet = IncidentModel.load(bundle(data, ""));
        String notableOverview = renderText(quiet, "top");
        assertTrue(notableOverview.contains("\"Notable\"") && notableOverview.contains("Heap 4.0 of 4.0 GiB"), notableOverview);
        assertFalse(notableOverview.contains("\"Next steps\"") || notableOverview.contains("cause\""), notableOverview);
        assertFalse(renderText(quiet, "diagnosis").contains("Heap 4.0 of 4.0 GiB"), "Notable lines stay on Overview only");
        String older = renderText(IncidentModel.load(bundle(fixture(), "")), "top");
        assertTrue(older.contains("What the recording shows"), "Bundles with the older findings keep their layout");
    }

    @Test
    void noSectionPointsToTheReportFile() throws Exception {
        JsonObject data = fixture();
        data.add("locks", JsonParser.parseString("{\"rows\":[[\"WorldThread - default\",\"lock\",10,1,\"frame\",null,null]]}"));
        data.getAsJsonArray("diag").add(JsonParser.parseString("{\"title\":\"IroriPowered:Refixes settings\",\"entries\":[[\"Refixes version\",\"0.6.1\"]],\"pre\":null}"));
        IncidentModel model = IncidentModel.load(bundle(data, ""));
        for (String section : HyboxIncidentRenderer.SECTIONS.keySet()) {
            int pages = HyboxIncidentRenderer.pageCount(model, state(section, 0, "all"));
            for (int page = 0; page < pages; page++) {
                UICommandBuilder cmd = new UICommandBuilder();
                HyboxIncidentRenderer.render(model, state(section, page, "all"), cmd, new UIEventBuilder(), null);
                String shown = String.join("\n", Arrays.stream(cmd.getCommands()).filter(c -> c.data != null).map(c -> c.data).toList()).toLowerCase(Locale.ROOT);
                assertFalse(shown.contains("report.html") || shown.contains("in report") || shown.contains("see report"), section + ": " + shown);
            }
        }
    }

    @Test
    void baselineComparisonIsATableWithReadableNames() throws Exception {
        JsonObject data = fixture();
        JsonArray heapSeries = new JsonArray();
        for (int i = 0; i < 240; i++) heapSeries.add((10 + i % 20) * 107_374_182L);
        data.getAsJsonObject("series").add("heap", heapSeries);
        IncidentModel model = IncidentModel.load(bundle(data, ""));
        UICommandBuilder cmd = new UICommandBuilder();
        HyboxIncidentRenderer.render(model, state("diagnosis", 0, "all"), cmd, new UIEventBuilder(), null);
        var commands = Arrays.asList(cmd.getCommands());
        assertEquals(1, commands.stream().filter(c -> "Pages/HyboxIncidentCompareHead.ui".equals(c.text)).count());
        List<String> metrics = commands.stream().filter(c -> c.selector != null && c.selector.endsWith(" #Metric.Text"))
            .map(c -> JsonParser.parseString(c.data).getAsJsonObject().get("0").getAsString()).toList();
        assertTrue(metrics.containsAll(List.of("TPS", "Tick time", "Players", "Entities", "Chunks in memory", "Chunks generated", "Chunks loaded", "Machine CPU", "Server CPU")), metrics.toString());
        String diagnosis = renderText(model, "diagnosis");
        assertFalse(diagnosis.contains("baseline / incident / delta") || diagnosis.contains("chunks Generated") || diagnosis.contains("cpu Jvm"), diagnosis);
        String heap = compareRow(commands, "Heap used"), players = compareRow(commands, "Players");
        assertTrue(value(cmd, heap + " #Change.Text").endsWith("% worse"), "A heap that grows is marked like any other metric that hurts");
        assertEquals(DashboardModel.Tone.WARN.color, value(cmd, heap + " #Change.Style.TextColor"));
        assertFalse(value(cmd, players + " #Change.Text").contains("worse"), "Players are never marked");
    }

    private static String compareRow(List<CustomUICommand> commands, String metric) {
        return commands.stream().filter(c -> c.selector != null && c.selector.endsWith(" #Metric.Text") && c.data.contains("\"" + metric + "\""))
            .map(c -> c.selector.replace(" #Metric.Text", "")).findFirst().orElseThrow(() -> new AssertionError(metric));
    }

    @Test
    void keyMetricsFollowTheVitalsWorldThenTheStalledWorld() throws Exception {
        JsonObject data = fixture();
        data.getAsJsonArray("worlds").add(JsonParser.parseString("{\"name\":\"instance-Arena_One\",\"samples\":10,\"stalled\":false,\"tps\":12.0,\"mspt\":83.29,\"msptMax\":865.7}"));
        data.getAsJsonObject("tps").addProperty("world", "instance-arena_one");
        data.getAsJsonObject("tps").addProperty("pick", "slowest");
        data.getAsJsonObject("series").add("msptIncident", series(240));
        UICommandBuilder cmd = new UICommandBuilder();
        HyboxIncidentRenderer.render(IncidentModel.load(bundle(data, "")), state("metrics", 0, "all"), cmd, new UIEventBuilder(), null);
        List<String> titles = Arrays.stream(cmd.getCommands()).filter(c -> c.selector != null && c.selector.endsWith("#ChartTitle.Text"))
            .map(c -> JsonParser.parseString(c.data).getAsJsonObject().get("0").getAsString()).toList();
        assertEquals(List.of("Tick time · Arena One", "TPS · Arena One", "Tick time · default", "TPS · default"), titles);
        data.getAsJsonObject("series").remove("msptIncident");
        String older = renderText(IncidentModel.load(bundle(data, "")), "metrics");
        assertTrue(older.contains("2.9 s worst.") && older.indexOf("TPS · Arena One") < older.indexOf("Tick time · default"), older);
    }

    @Test
    void cpuByModShowsOnlyModsWithAVisibleShare() throws Exception {
        JsonObject data = fixture();
        data.getAsJsonArray("plugins").add(JsonParser.parseString("[\"IroriPowered\",\"Refixes\",\"1.0.0\",false,\"*\",\"COMPATIBLE\"]"));
        data.add("modCpu", JsonParser.parseString("[[\"Xytronix:Hybox\",159,null,[]],[\"com.github.luben\",63,null,[]],[\"cc.irori.refixes\",12,null,[]],"
            + "[\"IroriPowered:Refixes\",5,null,[]],[\"org.jline.utils\",1,null,[]],[\"Quiet:Mod\",1,null,[]]]"));
        String cpu = renderText(IncidentModel.load(bundle(data, "")), "cpu");
        assertTrue(cpu.contains("\"Hybox\"") && cpu.contains("\"Refixes\""), cpu);
        assertEquals(cpu.indexOf("\"Refixes\""), cpu.lastIndexOf("\"Refixes\""), "The package and the plugin id of one mod share a row");
        for (String gone : List.of("com.github.luben", "org.jline.utils", "Quiet")) assertFalse(cpu.contains(gone), gone);
    }

    @Test
    void findingsAboutDetailsTheGameLacksLinkToTheClosestSection() throws Exception {
        JsonObject data = fixture();
        data.getAsJsonObject("findings").add("steps", JsonParser.parseString("[{\"spans\":[[\"A world thread waited on a lock. \",\"\"],[\"Lock waits\",\"\"],"
            + "[\" shows the code that waited and the lock.\",\"\"]],\"section\":\"cpu\",\"label\":\"Lock waits\"},"
            + "{\"spans\":[[\"Exceptions spiked before the incident. \",\"\"],[\"Server log\",\"\"],[\" shows the errors from that time.\",\"\"]],\"section\":\"log\",\"label\":\"Server log\"}]"));
        IncidentModel model = IncidentModel.load(bundle(data, ""));
        UICommandBuilder cmd = new UICommandBuilder(); UIEventBuilder ev = new UIEventBuilder();
        HyboxIncidentRenderer.render(model, state("top", 0, "all"), cmd, ev, null);
        String all = String.join("\n", Arrays.stream(cmd.getCommands()).filter(c -> c.data != null).map(c -> c.data).toList());
        assertFalse(all.contains("report.html"), all);
        assertTrue(all.contains("\"CPU & load\"") && all.contains("\"Go to CPU & load\""), "The button names the game section, not the missing detail: " + all);
        assertFalse(Arrays.stream(ev.getEvents()).anyMatch(e -> e.data.contains("\"log\"")), "No button for a section the game does not have");
    }

    @Test
    void incidentSelectionAcceptsFullIdsAndSuffixesWithoutPaths() throws Exception {
        var incidents = List.of(new HyboxIncidents.Incident("20261019-070531-000005", directory.resolve("one.zip"), null, null, "a", "INFO", "default", null, false, true),
            new HyboxIncidents.Incident("20261019-070532-000006", directory.resolve("two.zip"), null, null, "b", "INFO", "default", null, false, true));
        assertEquals(0, HyboxIncidentPage.select(incidents, null));
        assertEquals(1, HyboxIncidentPage.select(incidents, "000006"));
        assertEquals(0, HyboxIncidentPage.select(incidents, "incident-20261019-070531-000005.zip"));
        assertThrows(java.io.IOException.class, () -> HyboxIncidentPage.select(incidents, "../../one"));
    }

    @Test
    void chartsMarkEachBundleKindWithTheWordTheReportUses() throws Exception {
        String[][] kinds = {{"HEARTBEAT_STALL", "markerFrac", "incident", "incident"}, {"UNCLEAN_SHUTDOWN", "markerFrac", "server stopped", ""},
            {"MANUAL", "profileFrac", "profile start", "profile"}, {"MANUAL", "captureFrac", "captured", ""}};
        for (String[] kind : kinds) {
            JsonObject data = fixture();
            JsonObject incident = data.getAsJsonObject("incident");
            incident.addProperty("trigger", kind[0]);
            incident.add("markerFrac", JsonNull.INSTANCE);
            if (!kind[0].equals("HEARTBEAT_STALL")) incident.add("stallMs", JsonNull.INSTANCE);
            incident.addProperty(kind[1], 0.5);
            IncidentModel model = IncidentModel.load(bundle(data, ""));
            UICommandBuilder cmd = new UICommandBuilder(), swap = new UICommandBuilder();
            UIEventBuilder events = new UIEventBuilder();
            List<HyboxIncidentRenderer.ChartRequest> requests = new ArrayList<>();
            HyboxIncidentRenderer.render(model, state("metrics", 0, "all"), cmd, events, requests::add);
            requests.forEach(request -> request.apply().accept("chart.png", swap));
            int rgb = kind[2].equals("incident") || kind[2].equals("server stopped") ? HyboxChartImage.INCIDENT : HyboxChartImage.PROFILE;
            assertTrue(Arrays.stream(cmd.getCommands()).anyMatch(c -> c.selector != null && c.selector.endsWith("#ChartLegend.Text")
                && c.data.contains("Highlighted bar: " + kind[2] + ".")), kind[2]);
            assertTrue(Arrays.stream(cmd.getCommands()).anyMatch(c -> c.selector != null && c.selector.endsWith("#Bars")
                && c.text != null && c.text.contains(HyboxChartImage.hex(rgb))), kind[2]);
            assertTrue(Arrays.stream(cmd.getCommands()).anyMatch(c -> c.selector != null && c.selector.endsWith(".TooltipText")
                && c.data.contains(" · " + kind[2])), kind[2]);
            assertTrue(Arrays.stream(swap.getCommands()).anyMatch(c -> c.selector != null && c.selector.endsWith("#ChartLegend.Text")
                && c.data.contains("Dashed line: " + kind[2] + ".")), kind[2]);
            for (String preset : List.of("incident", "profile")) {
                assertEquals(preset.equals(kind[3]), Arrays.stream(events.getEvents()).anyMatch(e -> e.data.contains("range") && e.data.contains("\"" + preset + "\"")),
                    kind[2] + " preset " + preset);
            }
        }
    }

    @Test
    void overviewCountsSimilarIncidentsAndDiagnosisComparesWithTheUsualValues() throws Exception {
        IncidentModel model = IncidentModel.load(bundle(fixture(), ""));
        String overview = renderText(model, "top");
        assertTrue(overview.contains("Similar incidents"), overview);
        assertTrue(overview.contains("1 in the last 7 days, 2 in the last 30 days"), overview);
        String diagnosis = renderText(model, "diagnosis");
        assertTrue(diagnosis.contains("Incident 40.0 ms, usual 10.0 ms, 95% under 12.0 ms. Unusual for this server."), diagnosis);
        assertTrue(diagnosis.contains("Freeze in world default. Top mod Lagger, same as now."), diagnosis);
        JsonObject thin = fixture();
        thin.add("history", JsonParser.parseString("{\"usual\":{\"off\":false,\"days\":1,\"minDays\":3,\"hours\":5,\"enough\":false,\"metrics\":[]},\"similar\":null}"));
        String early = renderText(IncidentModel.load(bundle(thin, "")), "diagnosis");
        assertTrue(early.contains("Not enough history yet (5 hours). Usual values need this time of day on 3 of the last 7 days, and the history has it on 1."), early);
        assertFalse(renderText(IncidentModel.load(bundle(thin, "")), "top").contains("Similar incidents"));
    }

    @Test
    void longCapturedStacksSplitOnlyBetweenFrames() throws Exception {
        JsonObject data = fixture();
        StringBuilder stack = new StringBuilder();
        for (int i = 0; i < 120; i++) stack.append(i == 0 ? "" : "\n").append("at com.example.deep.module.LongClassName").append(i).append(".runTheMethodWithALongName(LongClassName").append(i).append(".java:").append(i).append(')');
        data.getAsJsonArray("diag").get(0).getAsJsonObject().addProperty("pre", stack.toString());
        String rendered = renderText(IncidentModel.load(bundle(data, "")), "stalled");
        assertTrue(rendered.contains("Stack · continued"), rendered);
        assertTrue(rendered.contains("and 95 more frames"), rendered);
        for (int i = 0; i < 25; i++) {
            String frame = "at com.example.deep.module.LongClassName" + i + ".runTheMethodWithALongName(LongClassName" + i + ".java:" + i + ")";
            assertTrue(rendered.contains(frame), frame);
        }
    }

    @Test
    void worldCpuShareIsOneNumberInWorldsAndCpu() throws Exception {
        JsonObject data = fixture();
        data.getAsJsonArray("cpuByWorld").get(0).getAsJsonObject().addProperty("samples", 4000);
        IncidentModel model = IncidentModel.load(bundle(data, ""));
        String worlds = renderText(model, "worlds");
        assertTrue(worlds.contains("CPU share 20.2%"), worlds);
        String cpu = renderText(model, "cpu");
        assertTrue(cpu.contains("20.2%, 4,000 samples"), cpu);
        assertFalse(worlds.contains("15.8%") || cpu.contains("15.8%"), "The world thread alone is not shown as a second share");
    }

    private static HyboxIncidentRenderer.ViewState state(String section, int page, String range) {
        return new HyboxIncidentRenderer.ViewState(section, page, "", range, false);
    }

    private static String renderText(IncidentModel model, String section) {
        List<String> values = new ArrayList<>();
        int pages = HyboxIncidentRenderer.pageCount(model, state(section, 0, "all"));
        for (int page = 0; page < pages; page++) {
            UICommandBuilder cmd = new UICommandBuilder();
            HyboxIncidentRenderer.render(model, state(section, page, "all"), cmd, new UIEventBuilder(), null);
            for (CustomUICommand c : cmd.getCommands()) if (c.data != null) values.add(c.data);
        }
        return String.join("\n", values);
    }

    private Path bundle(JsonObject data, String trailingJs) throws Exception {
        Path path = directory.resolve("synthetic-" + java.util.UUID.randomUUID() + ".zip");
        writeZip(path, "<script>const D = " + data + ";\n" + trailingJs + "</script>");
        return path;
    }

    private void writeZip(Path path, String html) throws Exception {
        try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("incident.json"));
            zip.write("{\"meta\":{\"id\":\"old\"}}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry(); zip.putNextEntry(new ZipEntry("report.html")); zip.write(html.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
        }
    }

    private static JsonObject fixture() {
        JsonObject d = JsonParser.parseString("""
            {"hyboxVersion":"1.0.0","jfrUnavailableReason":null,
             "incident":{"id":"synthetic-000005","severity":"DEGRADED","trigger":"HEARTBEAT_STALL","world":"default","worldShort":"default","created":"2026-10-19 07:05:31 UTC","note":null,"stallMs":2944,"players":1,"recStart":"06:47:44","windowSec":1069,"markerFrac":0.9982,"recoverySec":null,"profileFrac":null,"samples":19816},
             "diag":[{"title":"Stalled thread","entries":[["Thread","WorldThread - default"],["Blocked in","engine.Tick.run"]],"pre":"at first\\nat second"},{"title":"Heartbeats","entries":[["default","55 ms ago"]],"pre":null},{"title":"Thread progress","entries":[["advanced","2"]],"pre":null}],
             "findings":{"evidence":[{"spans":[["Evidence ",""],["19,816 samples","b"]],"section":"cpu","label":"CPU & load"}],"severity":"DEGRADED","direction":"Inspect stacks","steps":[{"spans":[["Review ",""],["allocation","code"]],"section":"memory","label":"allocation"}],"tags":["GC pause 2884.44 ms"],"caveat":"Timing is not causation"},
             "cpu":{"jvmAvg":8.08,"jvmMax":70.51,"machAvg":33.25,"machMax":100},"heap":{"peak":4294715904,"floor":518874080,"max":4294967296,"committed":4294967296},"ram":{"rssLast":3642015744,"rssPeak":4035395584,"nmtOn":false,"nativeCommitted":null},
             "gc":{"collections":239,"maxPauseMs":2884.44,"totalPauseMs":6013.51,"explicitDisabled":false,"explicitConcurrent":true,"explicitCalls":[["caller",1]]},"gcByCollector":[["collector",239,6013]],"gcCauses":[["gcCause",2,120]],
             "net":{"iface":"lo0","inAvg":0.81,"inPeak":15.94,"outAvg":0.81,"outPeak":15.94,"others":[["en0",0.86,12.1,0.03,0.33]]},"tps":{"world":"default","target":30,"avg":29.19,"min":8.96},
             "gcOverlap":[1,2,6],"retained":[["RetainedClass",2,"allocationSite"]],"slowIo":[["world/chunk.bin",274.15,512,"write"]],"heapHistogram":[["LiveClass",4,8192]],"memPools":[["memPool",1024,2048,4096,512]],"entities":[["default",[["Player",1],["NPC",39]]]],
             "tickSystems":[["EngineSystem","default",1,2,3,4]],"tickSystemsNote":"Profiling enabled", "modCpu":[["Plugin",627,"plugin.Method",[["WorldThread",557]]]],
             "flame":["all",19816,[["WorldThread",10000,[["engine.Tick.run",10000,[],1]],1],["worker",9816,[],2]],0],
             "events":[[0.2,"error","exception ×2"],[0.99,"plugin","radius changed"]],"eventsHidden":2,"threadTotal":68,"threadStates":{"BLOCKED":1,"RUNNABLE":20,"TIMED_WAITING":14,"WAITING":33},"deadlocked":["thread-a awaits thread-b"],
             "worlds":[{"name":"default","samples":3135,"stalled":true,"players":1,"playerNames":["player-1"],"entities":40,"chunks":581,"chunksGenerated":1143,"chunksLoaded":7019,"tps":21.26,"mspt":47.05,"msptP50":0.08,"msptP95":65.98,"msptMax":2932.07}],
             "subsystems":[["World generation",5979],["Other",3518]],"cpuByWorld":[{"world":"default","samples":3135,"subsystems":[["Entity ticking",500]],"mods":[["Plugin",300]]}],
             "alloc":{"total":66634990536,"bySubsystem":[["BSON",26281373864]],"byThread":[["WorldThread",1870149224]],"byClass":[["byte[]",35350852584]],"byMod":[["Plugin",1617884936]],"byWorld":[{"world":"default","bytes":2192150928,"bySubsystem":[["BSON",50000]],"byMod":[["Plugin",5000]]}]},
             "assetPacks":[["author","asset-pack","1.0",false,"*","COMPATIBLE"]],"log":[["WARN","source","server log line"]],"configs":[{"title":"Config","content":"plugin-setting=true"}],"mixins":{"bootstrapper":"loader","configs":[["mixin.config",["MixinClass"]]],"conflicts":[["overlap",[["PluginA","ClassA"],["PluginB","ClassB"]]]]}}
            """).getAsJsonObject();
        JsonObject sys = new JsonObject();
        for (String key : List.of("os","arch","loadAvg","openFds","maxFds","containerMemUsed","oomKills","psiCpu","psiIo","psiMem","containerSwapUsed","containerSwapMax","ioReadLimit","ioWriteLimit","cpuset","throttledPeriods","throttledMs","cpuStealPct","cpuIowaitPct","cpuUsageCores","safepointCount","safepointMs","server","hytale","jvm","javaVendor","javaVersion","javaHome","cpuBrand","cpuCores","hwThreads","sockets","ramTotal","uptimeMs","hostMemUsed","gcYoung","gcOld","gcParallel","gcConc","gcTimeRatio","xmxBytes","startCommand")) sys.addProperty(key, key + "-value");
        sys.add("nonHeap", JsonParser.parseString("{\"used\":250268744,\"committed\":294191104}"));
        sys.add("swap", JsonParser.parseString("{\"free\":1716977664,\"total\":5368709120}"));
        sys.add("disk", JsonParser.parseString("{\"free\":76649816064,\"total\":245107195904}"));
        sys.add("loaders", JsonParser.parseString("[[\"Hyinit\",\"version\"]]"));
        sys.add("container", JsonParser.parseString("{\"runtime\":\"Docker\",\"type\":\"container\",\"cpuLimit\":4,\"memLimit\":8589934592}"));
        d.add("sys", sys);
        JsonObject series = new JsonObject();
        for (String key : List.of("cpu","cpuJvm","heap","heapCommitted","rss","hostMem","entities","chunks","chunksGenerated","chunksLoaded","netIn","netOut","threads","gcPause","players","exceptions","connects","joins","leaves","ping","disk","diskRead","diskWrite","tps","mspt")) series.add(key, series(240));
        d.add("series", series);
        JsonArray metrics = new JsonArray(), ticks = new JsonArray(), worldTps = new JsonArray(), timeline = new JsonArray(), threads = new JsonArray(), methods = new JsonArray(), plugins = new JsonArray();
        for (int i = 0; i < 8; i++) { JsonObject o = new JsonObject(); o.addProperty("name", "EngineSystem" + i + " @ default"); o.add("series", series(240)); ticks.add(o); }
        for (int i = 0; i < 5; i++) { JsonObject o = new JsonObject(); o.addProperty("name", "plugin:metric-" + i); o.addProperty("kind", i == 0 ? "count" : "gauge"); o.add("series", series(240)); metrics.add(o); }
        JsonObject w = new JsonObject(); w.addProperty("name", "default"); w.add("series", series(240)); worldTps.add(w);
        for (int i = 0; i < 25; i++) { JsonObject o = new JsonObject(); o.addProperty("name", "Worker-" + i); JsonArray states = new JsonArray(); for (int k = 0; k < 240; k++) states.add(k % 5); o.add("states", states); timeline.add(o); }
        for (int i = 0; i < 50; i++) {
            threads.add(JsonParser.parseString("[" + (4305 - i) + ",\"RUNNABLE\",\"Worker-" + i + "\",\"engine.Tick.run\",[[\"engine.Tick.run\",384]] ]"));
            methods.add(JsonParser.parseString("[" + (760 - i) + ",\"engine.Method" + i + "\",[\"Top caller\",\"Next caller\"]]"));
        }
        for (int i = 0; i < 108; i++) plugins.add(JsonParser.parseString("[\"" + (i < 106 ? "Hytale" : "Community") + "\",\"Plugin" + i + "\",\"1.0.0\"," + (i < 106) + ",\"*\",\"COMPATIBLE\"]"));
        d.add("pluginMetrics", metrics); d.add("tickSeries", ticks); d.add("worldTps", worldTps); d.add("threadTimeline", timeline); d.add("hotThreads", threads); d.add("hotMethods", methods); d.add("plugins", plugins);
        d.add("history", JsonParser.parseString("""
            {"usual":{"off":false,"days":6,"minDays":3,"hours":150,"enough":true,"clock":"14:00","window":"incident","metrics":[{"key":"mspt","label":"Tick time","unit":"ms","incident":40.0,"median":10.0,"low":10.0,"high":12.0,"samples":306,"flag":"high"}]},
             "similar":{"checked":5,"week":1,"month":2,"newest":"2026-03-08 14:00 UTC","compared":2,"sameMod":1,"sameMethod":1,"line":[["1","b"],[" earlier incident had the same trigger or world in the last 7 days, ",""],["2","b"],[" in the last 30 days.",""]],
              "rows":[{"created":"2026-03-08 14:00 UTC","trigger":"HEARTBEAT_STALL","triggerLabel":"Freeze","world":"default","read":true,"mod":"Lagger","method":"engine.World.tick","sameMod":true,"sameMethod":true}]}}
            """));
        return d;
    }

    private static JsonArray series(int length) { JsonArray a = new JsonArray(); for (int i = 0; i < length; i++) a.add(10 + (i % 20)); return a; }
}
