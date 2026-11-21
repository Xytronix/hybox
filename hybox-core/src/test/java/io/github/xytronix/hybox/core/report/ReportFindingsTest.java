package io.github.xytronix.hybox.core.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.xytronix.hybox.core.health.HealthSnapshot;
import io.github.xytronix.hybox.core.health.JfrSamples;
import io.github.xytronix.hybox.core.health.JfrTimeline;
import io.github.xytronix.hybox.core.incident.DiagnosticSection;
import io.github.xytronix.hybox.core.incident.IncidentId;
import io.github.xytronix.hybox.core.incident.IncidentMetadata;
import io.github.xytronix.hybox.core.incident.IncidentReport;
import io.github.xytronix.hybox.core.incident.Severity;

class ReportFindingsTest {
    private static final Instant CAPTURED = Instant.parse("2026-01-01T00:00:00Z");
    private static final long GIB = 1024L * 1024 * 1024;
    private static final long AT = CAPTURED.toEpochMilli();
    private static final JfrTimeline.LockWait WORLD_WAIT = new JfrTimeline.LockWait("WorldThread - default",
        "WorldThread - default", "java.util.concurrent.locks.ReentrantLock$NonfairSync", false, AT - 2_500, AT + 500,
        "com.example.probe.LockProbe.hold", "Example:LockProbe", null);

    @Test
    void freezeCausesFollowTheirOrder() throws Exception {
        JfrTimeline.GcPause pause = new JfrTimeline.GcPause(AT - 2_200, AT - 100, 2_100, "Allocation Failure");
        IncidentReport deadlocked = report(0, List.of("thread A", "thread B"), List.of());

        ReportFindings gc = findings(deadlocked, timeline(Map.of("gc", new JfrTimeline.GcStats(3, 2_100, 2_200),
            "gcPauses", List.of(pause), "lockWaits", List.of(WORLD_WAIT))));
        assertEquals(ReportFindings.CAUSE, gc.kind());
        assertEquals("A 2.1 s GC pause covers the whole 2.1 s freeze.", gc.cause().text());
        assertEquals(ReportFindings.CLEAR, gc.confidence());
        assertTrue(gc.cause().hover().contains("allocation failure"), gc.cause().hover());

        ReportFindings deadlock = findings(deadlocked, timeline(Map.of("lockWaits", List.of(WORLD_WAIT))));
        assertEquals("Threads thread A and thread B are deadlocked.", deadlock.cause().text());
        assertEquals(ReportFindings.CLEAR, deadlock.confidence());
        assertEquals("Restart the server. Deadlocked threads never recover.", deadlock.steps().getFirst().text());

        IncidentReport plain = report(0, List.of(), List.of());
        long[] times = new long[30];
        for (int i = 0; i < times.length; i++) {
            times[i] = AT - 2_000 + 50L * i;
        }
        ReportFindings busy = findings(plain, timeline(Map.of("lockWaits", List.of(WORLD_WAIT),
            "samples", samples(times, "WorldThread - default", "com.example.busy.Busy.spin", "Example:Busy"))));
        assertEquals("The world thread was busy in Busy.spin (Busy) for 2.1 s.", busy.cause().text());
        assertEquals(ReportFindings.CLEAR, busy.confidence());
        assertEquals("Report it to Busy with this bundle: it held the world thread for 2.1 s.",
            busy.steps().getFirst().text());

        ReportFindings lock = findings(plain, timeline(Map.of("lockWaits", List.of(WORLD_WAIT))));
        assertEquals("The world thread waited 3 s on ReentrantLock in LockProbe.hold.", lock.cause().text());
        assertEquals("Report it to LockProbe with this bundle: it held the world thread for 3 s.",
            lock.steps().getFirst().text());
        assertFalse(lock.other().stream().anyMatch(line -> "Lock waits".equals(line.label())));

        ReportFindings starved = findings(plain, timeline(Map.of("samples",
            samples(new long[] {AT - 10_000, AT + 2_000}, "Thread-3", "com.example.Work.run", null))));
        assertEquals("The whole process got no CPU during the 2.1 s freeze.", starved.cause().text());
        assertEquals(ReportFindings.LIKELY, starved.confidence());

        ReportFindings unclear = findings(plain, timeline(Map.of()));
        assertEquals("No clear cause, and nothing else crossed a threshold.", unclear.cause().text());
        assertEquals(ReportFindings.UNCLEAR, unclear.confidence());
        assertEquals(List.of("Capture during the next freeze with /hybox profile lag."),
            unclear.steps().stream().map(ReportFindings.Entry::text).toList());
    }

    @Test
    void unclearFreezeNamesItsStrongestSignals() throws Exception {
        JfrTimeline timeline = timeline(Map.of("totalSamples", 10_000L, "worldSamples", Map.of("default", 3_000L),
            "gc", new JfrTimeline.GcStats(5, 210, 900)));
        ReportFindings findings = findings(report(0, List.of(), List.of()), timeline);
        assertEquals("No clear cause. Strongest signals: stalled world at 30% CPU, GC pauses up to 210 ms.",
            findings.cause().text());
        assertEquals(List.of("The stalled world default used 30% of the CPU samples.", "GC paused the server for up to 210 ms."),
            findings.other().stream().map(ReportFindings.Entry::text).toList());
    }

    @Test
    void loadFreezeIsLikelyOnlyWhenCpuSamplesDropped() throws Exception {
        HealthSnapshot snapshot = new HealthSnapshot(30, -1, new HealthSnapshot.Cpu(8), null, List.of(),
            new HealthSnapshot.Sys("macOS", "OpenJDK 26", 16 * GIB, 1_000, 115), null,
            new HealthSnapshot.Threads(10, Map.of("BLOCKED", 0), List.of()), 1,
            List.of(new HealthSnapshot.World("default", 1, 10, 20, 30, 33)), List.of());
        IncidentReport report = new IncidentReport(new IncidentMetadata(new IncidentId("fixture"), CAPTURED,
            Severity.DEGRADED, "HEARTBEAT_STALL", "default", "Heartbeat stalled"), Map.of("stallMs", "2146"), snapshot, List.of());
        long[] before = new long[600];
        for (int i = 0; i < before.length; i++) {
            before[i] = AT - 2_146 - 30_000 + 50L * i;
        }
        long[] sparse = Arrays.copyOf(before, 602);
        sparse[600] = AT - 1_500;
        sparse[601] = AT - 500;
        ReportFindings dropped = findings(report, timeline(Map.of("samples", samples(sparse, "Thread-3", "com.example.Work.run", null))));
        assertEquals("The machine was overloaded during the 2.1 s freeze: load 115.0 on 8 cores.", dropped.cause().text());
        assertEquals(ReportFindings.LIKELY, dropped.confidence());
        assertTrue(dropped.cause().hover().startsWith("The load is the 1-minute average at capture"), dropped.cause().hover());

        long[] steady = Arrays.copyOf(before, 640);
        for (int i = 600; i < steady.length; i++) {
            steady[i] = AT - 2_100 + 50L * (i - 600);
        }
        ReportFindings kept = findings(report, timeline(Map.of("samples", samples(steady, "Thread-3", "com.example.Work.run", null))));
        assertEquals(ReportFindings.UNCLEAR, kept.confidence());
        assertTrue(kept.cause().hover().contains("no clear drop"), kept.cause().hover());
    }

    @Test
    void runLoopFramesAreNeverACauseOrANotableLine() throws Exception {
        String loop = "com.hypixel.hytale.server.core.util.thread.TickingThread.run$mixinextras$wrapped$39";
        long[] times = new long[40];
        for (int i = 0; i < times.length; i++) {
            times[i] = AT - 2_000 + 40L * i;
        }
        ReportFindings freeze = findings(report(0, List.of(), List.of()), timeline(Map.of("totalSamples", 1_000L,
            "hotMethods", List.of(new JfrTimeline.HotMethod(loop, 300L, List.of(), List.of())),
            "samples", samples(times, "WorldThread - default", loop, null))));
        assertEquals(ReportFindings.UNCLEAR, freeze.confidence());
        assertTrue(lines(freeze).stream().noneMatch(line -> line.text().contains("TickingThread")), lines(freeze).toString());
        assertTrue(JfrTimeline.isRunLoop("java.lang.Thread.run"));
        assertFalse(JfrTimeline.isRunLoop("com.example.busy.Busy.spin"));
    }

    @Test
    void slowTickCausesFollowTheirOrder() throws Exception {
        double[] tps = new double[13];
        double[] mspt = new double[13];
        Arrays.fill(tps, 30);
        Arrays.fill(mspt, 40);
        Map<String, Object> ticks = Map.of("ticks", Map.of("default", new JfrTimeline.WorldTicks(tps, mspt, null)));
        DiagnosticSection heavy = new DiagnosticSection("Tick systems",
            Map.of("EntityModel @ default", "31.7 60.0 31.0 60.0"), null);
        DiagnosticSection light = new DiagnosticSection("Tick systems",
            Map.of("EntityModel @ default", "5.0 20.0 4.02 20.0"), null);
        JfrTimeline.ModCpu busy = new JfrTimeline.ModCpu("Example:Busy", 500, "com.example.busy.Busy.spin", List.of(), 500, 500,
            "com.example.busy.Busy.spin", List.of(new JfrTimeline.ThreadShare("WorldThread - default", 500)), null);
        Map<String, Object> modded = new LinkedHashMap<>(ticks);
        modded.putAll(Map.of("totalSamples", 10_000L, "worldSamples", Map.of("default", 1_000L), "cpuByMod", List.of(busy)));

        ReportFindings system = findings(ticks("TICK_DEGRADED", List.of(heavy)), timeline(modded));
        assertEquals("Engine system EntityModel (entity model updates) took about 31.7 ms of each tick, 95% of the tick budget.",
            system.cause().text());
        assertEquals("Check how many entities are near players in default: this system sends their model changes to every "
            + "player who can see them.", system.steps().getFirst().text());

        DiagnosticSection save = new DiagnosticSection("Tick systems",
            Map.of("WorldConfigSaveSystem @ default", "73.72 3960.99 2.76 3960.99"), null);
        ReportFindings spike = findings(ticks("TICK_DEGRADED", List.of(save)), timeline(ticks));
        assertEquals("One WorldConfigSaveSystem tick took 4 s (world config save).", spike.cause().text());
        assertTrue(spike.cause().hover().contains("5-minute average 2.8 ms, slowest tick 3,961 ms."), spike.cause().hover());
        assertEquals("Check the disk speed: the world thread of default waits for each save of its config and resources "
            + "to finish.", spike.steps().getFirst().text());

        ReportFindings mod = findings(ticks("TICK_DEGRADED", List.of(light)), timeline(modded));
        assertEquals("Busy's own code took about 20.0 ms of each tick, 60% of the tick budget.", mod.cause().text());
        assertEquals("Report it to Busy with this bundle: its code took 20.0 ms of each tick.", mod.steps().getFirst().text());

        Map<String, Object> loaded = new LinkedHashMap<>(ticks);
        loaded.put("cpu", new JfrTimeline.CpuStats(40, 60, 95, 100));
        ReportFindings machine = findings(ticks("TICK_DEGRADED", List.of(light)), timeline(loaded));
        assertEquals("The machine was short of CPU: it averaged 95%.", machine.cause().text());
        assertEquals("Find what else ran on the host at that time and move it off, or give the server more cores or RAM.",
            machine.steps().getFirst().text());

        ReportFindings unclear = findings(ticks("TICK_DEGRADED", List.of(light)), timeline(ticks));
        assertEquals(ReportFindings.UNCLEAR, unclear.confidence());
        assertEquals("Capture during the next slowdown with /hybox profile lag.", unclear.steps().getFirst().text());
    }

    @Test
    void memoryCauseNamesTheTopAllocatorAndSizesXmxToFreeRam() throws Exception {
        long alloc = 10 * GIB;
        JfrTimeline.Alloc pipes = new JfrTimeline.Alloc(alloc, ordered("BSON serialization", alloc / 2, "Other", alloc / 2),
            Map.of(), Map.of(), Map.of("Example:Pipes", new JfrTimeline.ModShare(alloc * 3 / 10, alloc * 3 / 10, alloc * 4 / 10)),
            List.of(), 5_000L);
        long[] floor = Arrays.stream(new double[] {1, 1.1, 1.2, 1.5, 1.8, 2, 2.5, 2.6, 2.7})
            .mapToLong(value -> (long) (value * GIB)).toArray();
        Map<String, Object> values = new LinkedHashMap<>(Map.of("heapMax", 4 * GIB,
            "heapSeries", new long[] {2 * GIB, (long) (3.8 * GIB)}, "alloc", pipes, "postGcHeap", floor,
            "hostMemSeries", new long[] {8 * GIB}));
        ReportFindings findings = findings(memory(16 * GIB), timeline(values));

        assertEquals("Pipes' own code allocated 30% of sampled memory.", findings.cause().text());
        assertEquals(List.of("The post-GC floor rose from 1.0 GiB to 2.5 GiB, a possible leak.",
            "Heap peaked at 3.8 of 4.0 GiB (95%)."), findings.why().stream().map(ReportFindings.Entry::text).toList());
        assertEquals(List.of("Raise -Xmx from 4 GiB to 6 GiB, the machine has 8.0 GiB free.",
            "Check Pipes' settings, or report its memory use to its author with this bundle."),
            findings.steps().stream().map(ReportFindings.Entry::text).toList());

        values.put("hostMemSeries", new long[] {(long) (15.5 * GIB)});
        assertEquals("Free memory on the machine first: only 0.5 GiB is free, too little to raise -Xmx.",
            findings(memory(16 * GIB), timeline(values)).steps().getFirst().text());

        values.remove("alloc");
        values.put("alloc", new JfrTimeline.Alloc(alloc, ordered("BSON serialization", alloc / 2, "Other", alloc / 2),
            Map.of(), Map.of(), Map.of(), List.of(), 5_000L));
        assertEquals("BSON serialization allocated the most memory, 5.0 GiB of 10.0 GiB.",
            findings(memory(16 * GIB), timeline(values)).cause().text());
    }

    @Test
    void manualCaptureListsNotableSignalsWithoutACause() throws Exception {
        ReportFindings busy = findings(machine(115, 8, new HealthSnapshot.Disk(221 * GIB, 228 * GIB)), timeline(Map.of()));
        assertEquals(ReportFindings.NOTABLE, busy.kind());
        assertNull(busy.cause());
        assertNull(busy.confidence());
        assertEquals(List.of("The 1-minute load average was 115.0 on 8 cores.", "The server disk had 7.0 GiB free of 228.0 GiB (3%)."),
            busy.why().stream().map(ReportFindings.Entry::text).toList());
        assertTrue(busy.steps().isEmpty());
        assertEquals("The 1-minute average at capture. Shown from 2 per core (16). Programs waited for a free core.",
            busy.why().getFirst().hover());

        ReportFindings calm = findings(machine(12, 8, new HealthSnapshot.Disk(100 * GIB, 228 * GIB)), timeline(Map.of()));
        assertTrue(calm.why().isEmpty() && calm.other().isEmpty() && calm.terms().isEmpty());
    }

    @Test
    void stepsNeverRepeatTheCauseOrWhy() throws Exception {
        JfrTimeline.GcPause pause = new JfrTimeline.GcPause(AT - 2_200, AT - 100, 2_100, "G1 Evacuation Pause");
        List<ReportFindings> all = List.of(
            findings(report(3, List.of(), List.of()), timeline(Map.of("gc", new JfrTimeline.GcStats(3, 2_100, 2_200),
                "gcPauses", List.of(pause), "heapMax", 4 * GIB, "heapSeries", new long[] {(long) (3.8 * GIB)},
                "swap", new JfrTimeline.Swap(-1, -1, 3 * GIB)))),
            findings(report(3, List.of(), List.of()), timeline(Map.of("lockWaits", List.of(WORLD_WAIT),
                "swap", new JfrTimeline.Swap(-1, -1, 3 * GIB), "exceptionsSeries", spikeAt(9)))),
            findings(report(0, List.of("thread A", "thread B"), List.of()), timeline(Map.of())));
        for (ReportFindings findings : all) {
            List<String> said = new ArrayList<>();
            said.add(findings.cause().text());
            findings.why().forEach(line -> said.add(line.text()));
            for (ReportFindings.Entry step : findings.steps()) {
                for (String line : said) {
                    String shared = longestShared(step.text(), line);
                    assertTrue(shared.length() < 25, "step repeats \"" + shared + "\"");
                }
            }
        }
    }

    @Test
    void termsAreListedOnlyWhenUsedAndTaggedOnce() throws Exception {
        JfrTimeline.GcPause pause = new JfrTimeline.GcPause(AT - 2_200, AT - 100, 2_100, "G1 Evacuation Pause");
        ReportFindings findings = findings(report(0, List.of(), List.of()), timeline(Map.of(
            "gc", new JfrTimeline.GcStats(3, 2_100, 2_200), "gcPauses", List.of(pause),
            "heapMax", 4 * GIB, "heapSeries", new long[] {(long) (3.8 * GIB)})));
        List<String> tagged = lines(findings).stream().flatMap(line -> line.spans().stream())
            .map(ReportFindings.Span::term).filter(Objects::nonNull).toList();
        List<String> listed = findings.terms().stream().map(ReportTerms.Term::term).toList();
        assertEquals(listed.size(), tagged.size());
        assertEquals(List.copyOf(new java.util.TreeSet<>(listed)), List.copyOf(new java.util.TreeSet<>(tagged)));
        assertTrue(listed.containsAll(List.of("GC pause", "Freeze", "Heap", "Xmx")), listed.toString());
        assertFalse(listed.contains("Swap"));
        for (ReportTerms.Term term : findings.terms()) {
            assertTrue(term.doc().startsWith("reading-a-report#"), term.doc());
        }
    }

    @Test
    void everyTermLinksToAHeadingOfReadingAReport() throws Exception {
        // The wiki page slug is "reading-a-report". The wiki gives headings GitHub style ids: lower case,
        // punctuation dropped, spaces as hyphens.
        Set<String> ids = new LinkedHashSet<>();
        for (String line : Files.readAllLines(Path.of("..", "docs", "reading-a-report.md"), StandardCharsets.UTF_8)) {
            if (line.startsWith("#")) {
                String text = line.replaceFirst("^#+\\s+", "").replace("`", "").strip().toLowerCase(java.util.Locale.ROOT);
                ids.add(text.replaceAll("[^\\p{L}\\p{N}_ -]", "").replace(' ', '-'));
            }
        }
        assertTrue(ids.contains("terms"), ids.toString());
        for (ReportTerms.Term term : ReportTerms.all()) {
            assertTrue(term.doc().startsWith("reading-a-report#"), term.doc());
            String anchor = term.doc().substring("reading-a-report#".length());
            assertTrue(ids.contains(anchor), term.term() + " links to #" + anchor + ", not a heading of reading-a-report.md");
        }
    }

    @Test
    void visibleLinesStayShortAndKeepThresholdsInTheHover() throws Exception {
        long mib = 1024L * 1024;
        JfrTimeline timeline = timeline(Map.of(
            "gc", new JfrTimeline.GcStats(5, 210, 4_550),
            "cpu", new JfrTimeline.CpuStats(40, 60, 93, 100),
            "heapMax", 4 * GIB, "heapSeries", new long[] {GIB, (long) (3.8 * GIB)},
            "swap", new JfrTimeline.Swap(300 * mib, 100 * mib, 5 * GIB),
            "exceptionsSeries", spikeAt(9),
            "container", new JfrTimeline.Container("cgroupv2", 4, 4 * GIB),
            "rssSeries", new long[] {2_000_000_000L, 4_000_000_000L},
            "slowIo", List.of(new JfrTimeline.SlowIo("/srv/universe/worlds/default/chunks/0.0.region.bin", 2_071, 8, "read"),
                new JfrTimeline.SlowIo("/srv/config.json", 300, 10, "write"))));
        IncidentReport report = report(1, List.of(), List.of(
            new DiagnosticSection("Mixins", Map.of("Conflict: example.Target", "a; b")),
            new DiagnosticSection("Plugins", Map.of("vendor:mod", "1.0 @ <0.7 @ INCOMPATIBLE"))));
        ReportFindings findings = findings(report, timeline);
        List<ReportFindings.Entry> lines = lines(findings);
        assertTrue(lines.size() > 8, lines.toString());
        for (ReportFindings.Entry line : lines) {
            assertFalse(line.text().contains("threshold"), line.text());
            assertFalse(line.text().contains(";") || line.text().contains("\u2014"), line.text());
            assertTrue(line.text().length() <= 150, line.text());
        }
        ReportFindings.Entry gc = lines.stream().filter(line -> line.text().startsWith("GC paused")).findFirst().orElseThrow();
        assertEquals("GC paused the server for up to 210 ms. GC pauses took 7% of the time.", gc.text());
        assertEquals("Pauses shown from 50 ms, total GC time from 5%.", gc.hover());
        assertTrue(lines.stream().anyMatch(line -> line.text().equals("A file read took 2,071 ms (0.0.region.bin, a world "
            + "region file), 2 slow file operations in total.")));
    }

    @Test
    void worldCpuShareCountsEveryThreadOfTheWorldLikeTheCpuCard() throws Exception {
        JfrTimeline timeline = timeline(Map.of(
            "totalSamples", 10_000L,
            "worldSamples", ordered("default", 2_500L, "instance-other_world", 5_000L),
            "cpuByWorld", List.of(
                new JfrTimeline.WorldCpu("instance-other_world", 6_000L, Map.of(), Map.of()),
                new JfrTimeline.WorldCpu("default", 3_000L, Map.of(), Map.of()),
                new JfrTimeline.WorldCpu("Not tied to one world", 1_000L, Map.of(), Map.of()))));
        List<String> texts = lines(findings(report(0, List.of(), List.of()), timeline)).stream()
            .map(ReportFindings.Entry::text).toList();
        assertTrue(texts.contains("World other world used 60% of the CPU samples."), texts.toString());
        assertTrue(texts.contains("The stalled world default used 30% of the CPU samples, #2 of 2 worlds."), texts.toString());

        JfrTimeline alone = timeline(Map.of("totalSamples", 10_000L, "worldSamples", Map.of("default", 9_000L)));
        List<String> single = lines(findings(report(0, List.of(), List.of()), alone)).stream()
            .map(ReportFindings.Entry::text).toList();
        assertFalse(single.stream().anyMatch(text -> text.startsWith("World ")), single.toString());
        assertTrue(single.contains("The stalled world default used 90% of the CPU samples."), single.toString());
    }

    @Test
    void reportWritesTheDiagnosisContract() throws Exception {
        JfrTimeline.GcPause pause = new JfrTimeline.GcPause(AT - 2_200, AT - 100, 2_100, "G1 Evacuation Pause");
        String html = payload(report(0, List.of(), List.of()), timeline(Map.of(
            "gc", new JfrTimeline.GcStats(3, 2_100, 2_200), "gcPauses", List.of(pause))));
        String data = html.substring(html.indexOf("const D = "), html.indexOf(";\n", html.indexOf("const D = ")));
        assertTrue(data.contains("\"diagnosis\":{\"kind\":\"cause\",\"cause\":{\"text\":\"A 2.1 s GC pause covers the whole "
            + "2.1 s freeze.\",\"spans\":[[\"A \",\"\"],[\"2.1 s\",\"b\"],[\" \",\"\"],[\"GC pause\",\"\",\"GC pause\"]"), data);
        assertTrue(data.contains("\"section\":\"memory\",\"label\":\"Memory & GC\",\"hover\":\"GC pauses in the same interval"), data);
        assertTrue(data.contains("\"confidence\":\"clear\"},\"why\":[],\"steps\":["), data);
        assertTrue(data.contains("{\"term\":\"GC pause\",\"meaning\":\"A stop of every server thread while Java's garbage "
            + "collector frees memory.\",\"doc\":\"reading-a-report#gc-pause\"}"), data);
        assertFalse(data.contains("\"findings\""), data);
        assertFalse(data.contains("<b>"));
    }

    @Test
    void twoCopiesOfOneInstanceKeepTheirOwnCpuShare() throws Exception {
        String first = "instance-portals-0b7fd9ff-9045-524f-5ffa-3225b9f7c3c0", second = "instance-portals-1c8fe0aa-9045-524f-5ffa-3225b9f7c3c1";
        JfrTimeline timeline = timeline(Map.of(
            "totalSamples", 10_000L,
            "worldSamples", ordered(first, 1_000L, second, 3_000L),
            "cpuByWorld", List.of(
                new JfrTimeline.WorldCpu(second, 3_500L, Map.of(), Map.of()),
                new JfrTimeline.WorldCpu(first, 1_500L, Map.of(), Map.of()))));
        Map<String, Long> shares = ReportFindings.worldCpu(null, timeline);
        assertEquals(Long.valueOf(1_500L), shares.get(first));
        assertEquals(Long.valueOf(3_500L), shares.get(second));
        assertEquals(Long.valueOf(1_500L), ReportActivityWriter.samplesForWorld(shares, first.toUpperCase(java.util.Locale.ROOT)));
    }

    @Test
    void headlineFollowsTheSlowestWorldAndAnOlderStallShowsAsTheWorstTick() throws Exception {
        double[] fine = new double[13];
        double[] slow = new double[13];
        Arrays.fill(fine, 30);
        Arrays.fill(slow, 8);
        String slower = payload(report(0, List.of(), List.of()), timeline(Map.of("ticks", ordered2(
            "default", new JfrTimeline.WorldTicks(fine, new double[13], null),
            "instance-slow", new JfrTimeline.WorldTicks(slow, new double[13], null)))));
        assertTrue(slower.contains("\"tps\":{\"world\":\"instance-slow\",\"pick\":\"slowest\""), slower);
        assertTrue(slower.contains("\"vitals\":{\"world\":\"instance-slow\""), slower);
        assertTrue(slower.contains("\"msptIncident\":[" + String.join(",", java.util.Collections.nCopies(13, "0.0")) + "]"), slower);
        assertTrue(slower.contains("\"msptWorstIncident\":[null,null,null,null,null,null,null,null,null,null,null,2146.0,null]"), slower);
        assertTrue(slower.contains("\"msptWorst\":null"), slower);

        double[] dipped = fine.clone();
        dipped[5] = 13;
        String stays = payload(report(0, List.of(), List.of()), timeline(Map.of("ticks", ordered2(
            "default", new JfrTimeline.WorldTicks(dipped, new double[13], null),
            "instance-slow", new JfrTimeline.WorldTicks(slow, new double[13], null)))));
        assertTrue(stays.contains("\"tps\":{\"world\":\"default\",\"pick\":\"incident\""), stays);
        assertFalse(stays.contains("\"msptIncident\":"), stays);

        String stall = payload(report(0, List.of(), List.of()), timeline(Map.of("ticks", ordered2(
            "default", new JfrTimeline.WorldTicks(fine, new double[13], null),
            "instance-slow", new JfrTimeline.WorldTicks(fine, new double[13], null)))));
        assertTrue(stall.contains("\"tps\":{\"world\":\"default\",\"pick\":\"incident\""), stall);
        assertTrue(stall.contains("\"msptWorst\":[null,null,null,null,null,null,null,null,null,null,null,2146.0,null]"), stall);
        assertTrue(stall.contains("\"tickWorstMs\":2146.0"), stall);
    }

    @Test
    void tpsDipNamesHowLongItLasted() throws Exception {
        double[] tps = new double[13];
        Arrays.fill(tps, 30);
        tps[6] = 13.34;
        tps[7] = 24;
        ReportFindings findings = findings(report(0, List.of(), List.of()),
            timeline(Map.of("ticks", Map.of("default", new JfrTimeline.WorldTicks(tps, new double[13], null)))));
        ReportFindings.Entry dip = lines(findings).stream().filter(line -> line.text().startsWith("TPS")).findFirst().orElseThrow();
        assertEquals("TPS fell to 13.3 and stayed low for 10 s.", dip.text());
        assertEquals("Low means under 27 TPS, 90% of the 30 TPS target.", dip.hover());

        tps[6] = 27.5;
        tps[7] = 28;
        assertTrue(lines(findings(report(0, List.of(), List.of()),
            timeline(Map.of("ticks", Map.of("default", new JfrTimeline.WorldTicks(tps, new double[13], null))))))
            .stream().noneMatch(line -> line.text().startsWith("TPS")));
    }

    @Test
    void rebuiltReportTakesJavaFromTheRecordingAndLeavesOutRestartValues() throws Exception {
        IncidentReport recovered = new IncidentReport(new IncidentMetadata(new IncidentId("fixture"), CAPTURED,
            Severity.DEGRADED, "UNCLEAN_SHUTDOWN", null, "Recovered after an unclean shutdown"),
            Map.of("recovered", "true"));
        JfrTimeline timeline = timeline(Map.of(
            "sysProps", Map.of("java.vm.vendor", "Eclipse Adoptium"),
            "jvmInfo", new JfrTimeline.JvmInfo("OpenJDK 64-Bit Server VM", "26.0.2+10", "aarch64")));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ReportHtml.write(recovered, timeline, output);
        String html = output.toString(StandardCharsets.UTF_8);

        assertTrue(html.contains("\"arch\":\"aarch64\""));
        assertTrue(html.contains("\"jvm\":\"OpenJDK 64-Bit Server VM 26.0.2+10\",\"javaVendor\":\"Eclipse Adoptium\","
            + "\"javaVersion\":\"26.0.2+10\""));
        assertTrue(html.contains("\"loadAvg\":null"));
        assertTrue(html.contains("\"cpuset\":null"));
    }

    @Test
    void apiEventsAndJfrActionsOfOneModShareItsToggle() throws Exception {
        long at = CAPTURED.toEpochMilli() - 30_000;
        JfrTimeline timeline = timeline(Map.of(
            "pluginEvents", List.of(new JfrTimeline.PluginEvent(at, "refixes/PerPlayerHotRadius", "radius 8 to 2"),
                new JfrTimeline.PluginEvent(at + 20_000, "Server", "Hybox capture")),
            "modActions", List.of(new JfrTimeline.ModAction(at + 10_000, "Refixes", "IdleWorldPause", "paused world"))));
        StringBuilder out = new StringBuilder();
        io.github.xytronix.hybox.core.json.JsonWriter writer = new io.github.xytronix.hybox.core.json.JsonWriter(out);
        writer.beginObject();
        ReportEvents.write(writer, List.of(), timeline, new ModNames(Map.of("IroriPowered:Refixes", "1.0")), "own");
        writer.endObject();
        assertTrue(out.toString().contains("\"mod:IroriPowered:Refixes\",\"[PerPlayerHotRadius] radius 8 to 2\"]"), out.toString());
        assertTrue(out.toString().contains("\"mod:IroriPowered:Refixes\",\"[IdleWorldPause] paused world\"]"), out.toString());
        assertTrue(out.toString().contains("\"plugin\",\"[Server] Hybox capture\"]"), out.toString());
    }

    @Test
    void incidentMarksBecomeEventsExceptTheReportsOwn() throws Exception {
        long at = CAPTURED.minusSeconds(30).toEpochMilli();
        JfrTimeline timeline = timeline(Map.of("incidentMarkers", List.of(
            new JfrTimeline.IncidentMarker(at, "inc-1", "DEGRADED", "TPS dropped to 12"),
            new JfrTimeline.IncidentMarker(at + 10_000, "own", "CRITICAL", "World stopped"),
            new JfrTimeline.IncidentMarker(at + 20_000, "inc-2", "INFO", ""))));
        StringBuilder out = new StringBuilder();
        io.github.xytronix.hybox.core.json.JsonWriter writer = new io.github.xytronix.hybox.core.json.JsonWriter(out);
        writer.beginObject();
        ReportEvents.write(writer, List.of(), timeline, new ModNames(Map.of()), "own");
        writer.endObject();
        String json = out.toString();
        assertTrue(json.contains("\"incident\",\"inc-1: TPS dropped to 12\"]"), json);
        assertTrue(json.contains("\"incident\",\"inc-2\"]"), json);
        assertFalse(json.contains("World stopped"), json);
    }

    @Test
    void retainedRowsCarryTheirPathToAGcRoot() throws Exception {
        JfrTimeline.RetainedChain chain = new JfrTimeline.RetainedChain(List.of(
            new JfrTimeline.Holder("java.lang.Object[]", null, true, false, 0, null),
            new JfrTimeline.Holder("java.util.ArrayList", "elementData", false, false, 2, null),
            new JfrTimeline.Holder("com.acme.leaky.Cache", "ENTRIES", false, true, 0, "Acme:Leaky")),
            "Thread Name: main", "Threads", "Stack Variable", "Acme:Leaky", 4, 5);
        JfrTimeline timeline = timeline(Map.of("retained", List.of(
            new JfrTimeline.Retained("byte[]", 6, null, null, chain),
            new JfrTimeline.Retained("java.lang.String", 3, null, null, null))));
        StringBuilder out = new StringBuilder();
        io.github.xytronix.hybox.core.json.JsonWriter writer = new io.github.xytronix.hybox.core.json.JsonWriter(out);
        writer.beginObject();
        ReportSystemWriter.writeRetained(writer, timeline, new ReportAttribution(List.of(), Map.of()));
        writer.endObject();
        assertEquals("{\"retained\":[[\"byte[]\",6,null,null,{\"holders\":["
            + "{\"type\":\"java.lang.Object[]\",\"field\":null,\"ref\":\"array\",\"skip\":0,\"mod\":null},"
            + "{\"type\":\"java.util.ArrayList\",\"field\":\"elementData\",\"ref\":\"field\",\"skip\":2,\"mod\":null},"
            + "{\"type\":\"com.acme.leaky.Cache\",\"field\":\"ENTRIES\",\"ref\":\"static\",\"skip\":0,\"mod\":\"Acme:Leaky\"}],"
            + "\"root\":{\"description\":\"Thread Name: main\",\"system\":\"Threads\",\"type\":\"Stack Variable\"},"
            + "\"mod\":\"Acme:Leaky\",\"samples\":4,\"withPath\":5}],"
            + "[\"java.lang.String\",3,null,null,null]]}", out.toString());
    }

    @Test
    void manualDumpGetsACapturedMarker() throws Exception {
        IncidentReport dump = new IncidentReport(new IncidentMetadata(new IncidentId("fixture"), CAPTURED,
            Severity.INFO, "MANUAL", null, "Manual capture"));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ReportHtml.write(dump, timeline(Map.of()), output);
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("\"profileFrac\":null,\"captureFrac\":0.9231"));
    }

    @Test
    void decimalFormattingRoundsThePrintedValueHalfUpLikeTheTemplate() {
        assertEquals("2.6", ReportFindings.fixed(2.55, 1));
        assertEquals("2.4", ReportFindings.fixed(2.35, 1));
        assertEquals("1.01", ReportFindings.fixed(1.005, 2));
        assertEquals("1.13", ReportFindings.fixed(1.125, 2));
        assertEquals("-2.6", ReportFindings.fixed(-2.55, 1));
        assertEquals("0.2", ReportFindings.fixed(0.15, 1));
        assertEquals("0.0", ReportFindings.fixed(-0.01, 1));
        assertEquals("0.0", ReportFindings.fixed(-0.0, 1));
        assertEquals("0.0%", CompareMetrics.change(1446.73, 1446.70));
        assertEquals("0.0%", CompareMetrics.change(1446.70, 1446.73));
    }

    @Test
    void stalledWorldKeepsItsOriginalCasingInEveryView() throws Exception {
        String lower = "instance-shared-portals-portals_goblins";
        HealthSnapshot snapshot = new HealthSnapshot(30, -1, null, null, List.of(), null, null,
            new HealthSnapshot.Threads(10, Map.of("BLOCKED", 0), List.of()), 1,
            List.of(new HealthSnapshot.World(lower, 1, 10, 20, 30, 33)), List.of());
        IncidentReport report = new IncidentReport(new IncidentMetadata(new IncidentId("fixture"), CAPTURED,
            Severity.DEGRADED, "HEARTBEAT_STALL", lower, "Heartbeat stalled"), Map.of("stallMs", "3622"), snapshot, List.of());
        JfrTimeline timeline = timeline(Map.of("totalSamples", 10_000L,
            "worldSamples", Map.of("instance-shared-Portals-Portals_Goblins", 3_000L)));
        String html = payload(report, timeline);

        assertTrue(html.contains("<h1>World <i>shared-Portals-Portals Goblins</i> stopped responding"), html);
        assertTrue(html.contains("\"worldNames\":{\"" + lower + "\":\"shared-Portals-Portals Goblins\"}"), html);
        assertTrue(html.contains("\"worldShort\":\"shared-Portals-Portals Goblins\""), html);
        assertTrue(lines(findings(report, timeline)).stream().anyMatch(line ->
            line.text().equals("The stalled world shared-Portals-Portals Goblins used 30% of the CPU samples.")));
    }

    @Test
    void everyComparedMetricCarriesTheDirectionThatHurts() throws Exception {
        String html = payload(report(0, List.of(), List.of()), timeline(Map.of()));
        assertTrue(html.contains("{\"key\":\"tps\",\"label\":\"TPS\",\"unit\":\"\",\"dec\":1,\"perMin\":false,\"bad\":\"down\"}"), html);
        assertTrue(html.contains("{\"key\":\"chunks\",\"label\":\"Chunks in memory\",\"unit\":\"\",\"dec\":0,\"perMin\":false,\"bad\":\"up\"}"), html);
        assertTrue(html.contains("{\"key\":\"chunksLoaded\",\"label\":\"Chunks loaded\",\"unit\":\"/min\",\"dec\":0,\"perMin\":true,\"bad\":null}"), html);
        assertTrue(html.contains("{\"key\":\"heap\",\"label\":\"Heap used\",\"unit\":\"GiB\",\"dec\":1,\"perMin\":false,\"bad\":\"up\"}"), html);
        assertTrue(CompareMetrics.worse("down", 30, 20));
        assertFalse(CompareMetrics.worse(null, 100, 10));
        assertEquals("31x", CompareMetrics.change(1, 31));
        assertEquals("+42.0%", CompareMetrics.change(10, 14.2));
        assertFalse(CompareMetrics.informative("up", 0, 0.01, 1));
    }

    @Test
    void jvmWideTriggerNamesNoWorld() throws Exception {
        IncidentReport report = new IncidentReport(new IncidentMetadata(new IncidentId("fixture"), CAPTURED,
            Severity.DEGRADED, "GC_PRESSURE", "jvm", "GC pressure"));
        String html = payload(report, timeline(Map.of()));
        assertFalse(html.contains("World <code"), html);
        assertTrue(html.contains("\"world\":null,\"worldShort\":null"), html);
    }

    private static ReportFindings findings(IncidentReport report, JfrTimeline timeline) {
        return ReportFindings.build(report, timeline, ReportSections.route(report.diagnostics()), ReportHistory.none());
    }

    private static List<ReportFindings.Entry> lines(ReportFindings findings) {
        List<ReportFindings.Entry> lines = new ArrayList<>();
        if (findings.cause() != null) {
            lines.add(findings.cause());
        }
        lines.addAll(findings.why());
        lines.addAll(findings.steps());
        lines.addAll(findings.other());
        return lines;
    }

    private static String longestShared(String a, String b) {
        String best = "";
        for (int i = 0; i < a.length(); i++) {
            for (int j = i + best.length() + 1; j <= a.length() && b.contains(a.substring(i, j)); j++) {
                best = a.substring(i, j);
            }
        }
        return best;
    }

    private static IncidentReport report(int blocked, List<String> deadlocked, List<DiagnosticSection> diagnostics) {
        HealthSnapshot snapshot = new HealthSnapshot(30, -1, null, null, List.of(), null, null,
            new HealthSnapshot.Threads(10, Map.of("BLOCKED", blocked), deadlocked), 1,
            List.of(new HealthSnapshot.World("default", 1, 10, 20, 30, 33)), List.of());
        IncidentMetadata meta = new IncidentMetadata(new IncidentId("fixture"), CAPTURED, Severity.DEGRADED,
            "HEARTBEAT_STALL", "default", "Heartbeat stalled");
        return new IncidentReport(meta, Map.of("stallMs", "2146"), snapshot, diagnostics);
    }

    private static IncidentReport ticks(String trigger, List<DiagnosticSection> diagnostics) {
        HealthSnapshot snapshot = new HealthSnapshot(30, -1, null, null, List.of(), null, null,
            new HealthSnapshot.Threads(10, Map.of("BLOCKED", 0), List.of()), 1,
            List.of(new HealthSnapshot.World("default", 1, 10, 20, 30, 33)), List.of());
        IncidentMetadata meta = new IncidentMetadata(new IncidentId("fixture"), CAPTURED, Severity.DEGRADED,
            trigger, "default", "Slow ticks");
        return new IncidentReport(meta, Map.of(), snapshot, diagnostics);
    }

    private static IncidentReport memory(long ram) {
        HealthSnapshot snapshot = new HealthSnapshot(30, -1, new HealthSnapshot.Cpu(8), null, List.of(),
            new HealthSnapshot.Sys("Linux", "OpenJDK 25", ram, 1_000, 1), null,
            new HealthSnapshot.Threads(10, Map.of("BLOCKED", 0), List.of()), 1, List.of(), List.of());
        IncidentMetadata meta = new IncidentMetadata(new IncidentId("fixture"), CAPTURED, Severity.DEGRADED,
            "GC_PRESSURE", "jvm", "GC pressure");
        return new IncidentReport(meta, Map.of(), snapshot, List.of());
    }

    private static IncidentReport machine(double load, int cores, HealthSnapshot.Disk disk) {
        HealthSnapshot snapshot = new HealthSnapshot(30, -1, new HealthSnapshot.Cpu(cores), null, List.of(),
            new HealthSnapshot.Sys("macOS", "OpenJDK 26", 16 * GIB, 1_000, load), disk,
            new HealthSnapshot.Threads(10, Map.of("BLOCKED", 0), List.of()), 1, List.of(), List.of());
        IncidentMetadata meta = new IncidentMetadata(new IncidentId("fixture"), CAPTURED, Severity.INFO,
            "MANUAL", null, "Manual capture");
        return new IncidentReport(meta, Map.of(), snapshot, List.of());
    }

    private static String payload(IncidentReport report, JfrTimeline timeline) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ReportHtml.write(report, timeline, output);
        return output.toString(StandardCharsets.UTF_8);
    }

    private static Map<String, JfrTimeline.WorldTicks> ordered2(String first, JfrTimeline.WorldTicks a,
                                                              String second, JfrTimeline.WorldTicks b) {
        Map<String, JfrTimeline.WorldTicks> values = new LinkedHashMap<>();
        values.put(first, a);
        values.put(second, b);
        return values;
    }

    private static Map<String, Long> ordered(String first, long firstValue, String second, long secondValue) {
        Map<String, Long> values = new LinkedHashMap<>();
        values.put(first, firstValue);
        values.put(second, secondValue);
        return values;
    }

    private static double[] spikeAt(int index) {
        double[] values = new double[10];
        values[index] = 100;
        return values;
    }

    private static String text(List<ReportFindings.Span> spans) {
        return spans.stream().map(ReportFindings.Span::text).collect(Collectors.joining());
    }

    private static JfrSamples samples(long[] times, String thread, String method, String owner) throws Exception {
        Constructor<?> constructor = JfrSamples.class.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return (JfrSamples) constructor.newInstance(times, new int[times.length], new int[times.length], List.of(method),
            Arrays.asList(owner), List.of(thread));
    }

    // Keep synthetic report inputs deterministic without requiring live sampling or a large binary JFR fixture.
    private static JfrTimeline timeline(Map<String, Object> values) throws Exception {
        Constructor<?> constructor = JfrTimeline.class.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Object[] defaults = Arrays.stream(constructor.getParameterTypes()).map(type -> {
            if (type == long.class) return -1L;
            if (type == int.class) return 0;
            if (type == List.class) return List.of();
            if (type == Map.class) return Map.of();
            return null;
        }).toArray();
        JfrTimeline timeline = (JfrTimeline) constructor.newInstance(defaults);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("start", CAPTURED.minusSeconds(60));
        fields.put("end", CAPTURED.plusSeconds(5));
        fields.put("totalSamples", 0L);
        fields.put("explicitGcTimes", new long[0]);
        fields.putAll(values);
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            Field field = JfrTimeline.class.getDeclaredField(entry.getKey());
            field.setAccessible(true);
            field.set(timeline, entry.getValue());
        }
        return timeline;
    }
}
