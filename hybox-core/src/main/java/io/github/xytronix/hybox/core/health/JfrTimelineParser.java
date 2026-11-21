package io.github.xytronix.hybox.core.health;

import static io.github.xytronix.hybox.core.health.JfrTimeline.BUCKETS;

import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jdk.jfr.EventType;
import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedClassLoader;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedObject;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

import io.github.xytronix.hybox.core.health.JfrTimeline.Alloc;
import io.github.xytronix.hybox.core.health.JfrTimeline.ConnEvent;
import io.github.xytronix.hybox.core.health.JfrTimeline.Container;
import io.github.xytronix.hybox.core.health.JfrTimeline.CpuInfo;
import io.github.xytronix.hybox.core.health.JfrTimeline.CpuStats;
import io.github.xytronix.hybox.core.health.JfrTimeline.ExplicitGc;
import io.github.xytronix.hybox.core.health.JfrTimeline.Flame;
import io.github.xytronix.hybox.core.health.JfrTimeline.GcCause;
import io.github.xytronix.hybox.core.health.JfrTimeline.GcPause;
import io.github.xytronix.hybox.core.health.JfrTimeline.GcConfig;
import io.github.xytronix.hybox.core.health.JfrTimeline.GcStats;
import io.github.xytronix.hybox.core.health.JfrTimeline.Holder;
import io.github.xytronix.hybox.core.health.JfrTimeline.HostCpu;
import io.github.xytronix.hybox.core.health.JfrTimeline.HotMethod;
import io.github.xytronix.hybox.core.health.JfrTimeline.IncidentMarker;
import io.github.xytronix.hybox.core.health.JfrTimeline.JvmInfo;
import io.github.xytronix.hybox.core.health.JfrTimeline.LockWait;
import io.github.xytronix.hybox.core.health.JfrTimeline.ModCpu;
import io.github.xytronix.hybox.core.health.JfrTimeline.ModShare;
import io.github.xytronix.hybox.core.health.JfrTimeline.NetIface;
import io.github.xytronix.hybox.core.health.JfrTimeline.PluginEvent;
import io.github.xytronix.hybox.core.health.JfrTimeline.PluginMetric;
import io.github.xytronix.hybox.core.health.JfrTimeline.Retained;
import io.github.xytronix.hybox.core.health.JfrTimeline.RetainedChain;
import io.github.xytronix.hybox.core.health.JfrTimeline.Safepoint;
import io.github.xytronix.hybox.core.health.JfrTimeline.SlowIo;
import io.github.xytronix.hybox.core.health.JfrTimeline.Swap;
import io.github.xytronix.hybox.core.health.JfrTimeline.SystemTick;
import io.github.xytronix.hybox.core.health.JfrTimeline.ThreadLane;
import io.github.xytronix.hybox.core.health.JfrTimeline.WorldAlloc;
import io.github.xytronix.hybox.core.health.JfrTimeline.WorldCpu;
import io.github.xytronix.hybox.core.health.JfrTimeline.WorldTicks;
import io.github.xytronix.hybox.core.jfr.IncidentMark;

final class JfrTimelineParser {
    private static final int MAX_BUCKETS = 960;
    private static final int HOT_METHOD_LIMIT = 50;
    private static final int CALLER_CHAIN_FRAMES = 5;
    private static final int CHAINS_PER_METHOD_CAP = 64;
    private static final int ALLOC_THREAD_LIMIT = 15;
    private static final int ALLOC_CLASS_LIMIT = 15;
    private static final int ALLOC_MOD_LIMIT = 10;
    private static final int PLUGIN_EVENT_CAP = 2000;
    private static final int LOCK_WAIT_CAP = 2000;
    private static final double GC_PAUSE_LISTED_MS = 50;
    private static final int GC_PAUSE_LIMIT = 1000;
    private static final int PLUGIN_METRIC_LIMIT = 64;
    private static final int RETAINED_LIMIT = 20;
    private static final int OLD_OBJECT_PATH_LIMIT = 256;
    private static final String CLASS_NAME_PREFIX = "Class Name: ";
    private static final Set<String> PRIMITIVES = Set.of("boolean", "byte", "char", "double", "float", "int", "long",
        "short");
    private static final int SLOW_IO_LIMIT = 15;
    private static final int SYSTEM_TICK_LIMIT = 24;
    private static final int SYSTEM_TICK_PER_WORLD = 4;
    private static final int SYSTEM_TICK_MIN_EVENTS = 2;
    private static final int SUBSYSTEM_LIMIT = 14;
    private static final int FLAME_DEPTH_LIMIT = 64;
    private static final int FLAME_NODE_CAP = 6000;
    private static final int FLAME_MIN_DIVISOR = 2000;
    private static final int THREAD_LANE_LIMIT = 25;
    private static final long SAMPLE_PASS_GAP_MS = 250;
    private static final int MOD_CPU_LIMIT = 20;
    private static final int NET_OTHERS_LIMIT = 5;
    private static final int THREAD_ID_CHARS = 12;
    private static final String WORLD_THREAD_PREFIX = "WorldThread - ";
    private static final List<String> WORLD_THREAD_PREFIXES = List.of(WORLD_THREAD_PREFIX, "ChunkLighting - ", "WorldMap - ");
    static final String NO_WORLD = "Not tied to one world";
    private static final String HYTALE_PACKAGE = "com.hypixel.hytale.";

    static final class FlameNode {
        final String name;
        long samples;
        int owner;
        String label;
        final Map<String, FlameNode> children = new LinkedHashMap<>();

        FlameNode(String name) {
            this.name = name;
        }
    }

    record Pt(long t, double v) {}

    private record MetricInput(String name, String mod, boolean counter, List<Pt> pts, Pt running) {}

    private record OldObjectPath(String key, List<Holder> holders, String rootDescription, String rootSystem,
                                 String rootType, String mod) {}

    private static final class PathTally {
        final OldObjectPath path;
        long count;

        PathTally(OldObjectPath path) {
            this.path = path;
        }
    }

    private record StackInfo(String leaf, String self, String[] flameFrames, int[] flameOwners, String[] flameLabels,
                             String chain, String[] chainLabels, String subsystem, JfrModAttribution.Owners owners) {}

    private static final String[][] SUBSYSTEM_PATTERNS = {
        {"Collision", ".modules.collision."},
        {"Entity ticking", "EntityTickingSystem", ".component.system.tick.", "ArchetypeTickingSystem"},
        {"World generation", ".hytalegenerator.", ".worldgen."},
        {"Chunk / palette", "SectionPalette", ".world.chunk.", "ChunkStore", "BlockSection"},
        {"NPC pathfinding", ".pathfinding.", "AStar", "MotionController", "Steering"},
        {"NPC / AI", ".builtin.npc", "Behavior", "Behaviour", "Sensor"},
        {"Spawning", ".spawning.", "SpawnMarker", "Spawner"},
        {"Entity stats", ".entitystats.", "EntityStatMap"},
        {"Fluid simulation", ".builtin.fluid.", "FluidSystems", "FluidSection"},
        {"Lighting", ".lighting.", "FloodLight"},
        {"Block ticking", ".builtin.blocktick.", "RandomTickSystem"},
        {"Physics", ".modules.physics.", ".blockphysics.", "Repulsion"},
        {"Projectiles", ".modules.projectile."},
        {"Items / inventory", ".modules.item.", "Inventory"},
        {"Interaction", ".modules.interaction.", "InteractionChain", "InteractionManager"},
        {"Crafting", ".crafting."},
        {"World map", "worldmap", "WorldMap"},
        {"Saving / storage", "ChunkSaving", "SavingSystem"},
        {"BSON serialization", "org.bson", "com.mongodb"},
        {"Networking", "io.netty.", ".protocol.", "PacketHandler"},
    };
    private static final String SUBSYSTEM_OTHER = "Other";

    private static final Set<String> WANTED_PROPS = Set.of(
        "java.vendor", "java.vm.vendor", "java.home", "java.runtime.version", "os.arch", "os.name", "os.version");
    private static final Pattern JVM_VERSION = Pattern.compile(
        "\\(([^)]*)\\) for [A-Za-z]+-([A-Za-z0-9_]+)(?: JRE \\(([^)]*)\\))?");

    private JfrTimelineParser() {
    }

    static JfrTimeline parse(Path recording) {
        List<Pt> cpuJvm = new ArrayList<>();
        List<Pt> cpuMach = new ArrayList<>();
        List<Pt> heap = new ArrayList<>();
        List<Pt> postGcHeap = new ArrayList<>();
        List<Pt> rss = new ArrayList<>();
        Map<String, List<Pt>> netIn = new HashMap<>();
        Map<String, List<Pt>> netOut = new HashMap<>();
        List<Pt> threads = new ArrayList<>();
        List<Pt> gcPause = new ArrayList<>();
        List<Pt> gcTime = new ArrayList<>();
        long safepointCount = 0;
        double safepointTotalMs = 0;
        long gcCount = 0;
        double gcMaxPause = 0;
        double gcTotalPause = 0;
        GcConfig gcConfig = null;
        Map<String, long[]> explicitGc = new LinkedHashMap<>();
        List<Long> explicitGcTimes = new ArrayList<>();
        String jvmArgs = null;
        String javaArgs = null;
        JvmInfo jvmInfo = null;
        long nmtCommitted = -1;
        CpuInfo cpuInfo = null;
        Map<String, String> sysProps = new HashMap<>();
        long heapMax = -1;

        long totalSamples = 0;
        Map<String, long[]> samplesByMethod = new HashMap<>();
        Map<String, Map<String, long[]>> chainsByMethod = new HashMap<>();
        Map<String, String[]> chainLabels = new HashMap<>();
        Map<String, long[]> worldSamples = new HashMap<>();
        Map<String, long[]> subsystems = new HashMap<>();
        Map<String, Map<String, long[]>> subsystemsByWorld = new HashMap<>();
        Map<String, String> threadStates = new HashMap<>();
        Map<String, List<Pt>> tpsByWorld = new HashMap<>();
        Map<String, List<Pt>> msptByWorld = new HashMap<>();
        Map<String, List<Pt>> worstByWorld = new HashMap<>();
        Map<String, List<Pt>> playersByWorld = new HashMap<>();
        List<Pt> onlinePlayers = new ArrayList<>();
        Map<String, List<Pt>> entitiesByWorld = new HashMap<>();
        Map<String, List<Pt>> chunksByWorld = new HashMap<>();
        Map<String, List<Pt>> chunksGenByWorld = new HashMap<>();
        Map<String, List<Pt>> chunksLoadByWorld = new HashMap<>();
        List<Pt> heapCommitted = new ArrayList<>();
        List<Pt> hostMem = new ArrayList<>();
        long swapFree = -1;
        long swapTotal = -1;
        List<long[]> swapUsed = new ArrayList<>();
        List<long[]> swapMoved = new ArrayList<>();
        List<Pt> diskFree = new ArrayList<>();
        long diskTotal = -1;
        List<Pt> diskRead = new ArrayList<>();
        List<Pt> diskWrite = new ArrayList<>();
        List<SlowIo> slowIo = new ArrayList<>();
        List<long[]> hostCpuSamples = new ArrayList<>();
        String osName = null;
        List<Pt> exceptionsCum = new ArrayList<>();
        long allocTotal = 0;
        Set<Long> allocSampled = new HashSet<>();
        long allocSamples = 0;
        Map<String, long[]> allocSubsystem = new HashMap<>();
        Map<String, long[]> allocThread = new HashMap<>();
        Map<String, long[]> allocClass = new HashMap<>();
        Map<String, Map<String, long[]>> allocSubByWorld = new HashMap<>();
        Container container = null;
        Map<String, List<Pt>> pingWeightedByWorld = new HashMap<>();
        Map<String, List<Pt>> pingWeightsByWorld = new HashMap<>();
        ArrayDeque<PluginEvent> pluginEvents = new ArrayDeque<>();
        ArrayDeque<ConnEvent> connEvents = new ArrayDeque<>();
        ArrayDeque<LockWait> lockWaits = new ArrayDeque<>();
        List<IncidentMarker> incidentMarkers = new ArrayList<>();
        Map<String, List<Pt>> metricPts = new LinkedHashMap<>();
        Map<String, Boolean> metricIsCounter = new HashMap<>();
        Map<String, Pt> metricRunningTotal = new HashMap<>();
        Map<String, List<Pt>> systemTickPts = new LinkedHashMap<>();
        Map<String, long[]> oldObjectCounts = new HashMap<>();
        Map<String, Map<String, long[]>> oldObjectSites = new HashMap<>();
        Map<String, String> oldObjectOwners = new HashMap<>();
        Map<String, Map<String, PathTally>> oldObjectPaths = new HashMap<>();
        Map<String, long[]> oldObjectWithPath = new HashMap<>();
        Map<String, double[]> gcCauseAgg = new LinkedHashMap<>();
        List<long[]> gcWindows = new ArrayList<>();
        List<GcPause> gcPauses = new ArrayList<>();
        FlameNode flameRoot = new FlameNode("all");
        Map<String, List<Pt>> threadStatePts = new HashMap<>();
        JfrModAttribution mods = new JfrModAttribution();
        JfrStackCache<RecordedStackTrace, StackInfo> stacks = new JfrStackCache<>(stack -> stackInfo(stack, mods));
        JfrModEvents modEvents = new JfrModEvents(PLUGIN_EVENT_CAP);
        Map<String, Set<String>> idThreads = new HashMap<>();
        JfrSlices slices = new JfrSlices();

        long minT = Long.MAX_VALUE;
        long maxT = Long.MIN_VALUE;
        boolean any = false;

        long slowIoThreshold = Long.MAX_VALUE;
        try (RecordingFile file = new RecordingFile(recording)) {
            Map<Long, String> typeNames = new HashMap<>();
            for (EventType type : file.readEventTypes()) {
                typeNames.put(type.getId(), type.getName());
            }
            while (file.hasMoreEvents()) {
                RecordedEvent e = file.readEvent();
                long t = e.getEndTime() != null ? e.getEndTime().toEpochMilli() : -1;
                if (t > 0) {
                    minT = Math.min(minT, t);
                    maxT = Math.max(maxT, t);
                    any = true;
                }
                switch (e.getEventType().getName()) {
                    case "jdk.ExecutionSample" -> {
                        totalSamples++;
                        RecordedThread thread = e.getThread("sampledThread");
                        String threadName = thread == null ? null : thread.getJavaName();
                        noteIdThread(idThreads, threadName);
                        if (threadName != null && threadName.startsWith(WORLD_THREAD_PREFIX)) {
                            worldSamples.computeIfAbsent(
                                threadName.substring(WORLD_THREAD_PREFIX.length()), k -> new long[1])[0]++;
                        }
                        if (threadName != null) {
                            String state = mapState(getS(e, "state"));
                            if (state != null) {
                                threadStates.put(threadName, state);
                                if (t > 0) {
                                    threadStatePts.computeIfAbsent(threadName, k -> new ArrayList<>())
                                        .add(new Pt(t, stateCode(state)));
                                }
                            }
                        }
                        recordStack(e.getStackTrace(), stacks, samplesByMethod, chainsByMethod, chainLabels, subsystems,
                            subsystemsByWorld, flameRoot, mods, threadName,
                            JfrModAttribution.captureThread(threadName), slices, t);
                    }
                    case "jdk.CPULoad" -> {
                        cpuJvm.add(new Pt(t, 100.0 * (getD(e, "jvmUser") + getD(e, "jvmSystem"))));
                        cpuMach.add(new Pt(t, 100.0 * getD(e, "machineTotal")));
                    }
                    case "jdk.GCHeapSummary" -> {
                        heap.add(new Pt(t, getL(e, "heapUsed")));
                        if ("After GC".equals(getS(e, "when"))) {
                            postGcHeap.add(new Pt(t, getL(e, "heapUsed")));
                        }
                        long committed = heapSpaceCommitted(e);
                        if (committed > 0) {
                            heapCommitted.add(new Pt(t, committed));
                        }
                    }
                    case "jdk.PhysicalMemory" -> {
                        long used = getL(e, "usedSize");
                        if (used > 0) {
                            hostMem.add(new Pt(t, used));
                        }
                    }
                    case "jdk.SwapSpace" -> {
                        swapTotal = getL(e, "totalSize");
                        swapFree = getL(e, "freeSize");
                        if (t > 0 && swapTotal >= 0 && swapFree >= 0) {
                            swapUsed.add(new long[] {t, swapTotal - swapFree});
                        }
                    }
                    case "jdk.GCHeapConfiguration" -> {
                        long mx = getL(e, "maxSize");
                        if (mx > 0) {
                            heapMax = mx;
                        }
                    }
                    case "jdk.ResidentSetSize" -> rss.add(new Pt(t, getL(e, "size")));
                    case "jdk.NetworkUtilization" -> {
                        String iface = getS(e, "networkInterface");
                        if (iface != null) {
                            netIn.computeIfAbsent(iface, k -> new ArrayList<>())
                                .add(new Pt(t, getL(e, "readRate") / 1e6));
                            netOut.computeIfAbsent(iface, k -> new ArrayList<>())
                                .add(new Pt(t, getL(e, "writeRate") / 1e6));
                        }
                    }
                    case "jdk.JavaThreadStatistics" -> threads.add(new Pt(t, getL(e, "activeCount")));
                    case "jdk.GarbageCollection" -> {
                        gcCount++;
                        double pauseMs = durationMs(e, "longestPause");
                        double sumMs = durationMs(e, "sumOfPauses");
                        if (sumMs <= 0) {
                            sumMs = pauseMs;
                        }
                        gcMaxPause = Math.max(gcMaxPause, pauseMs);
                        gcTotalPause += sumMs;
                        gcPause.add(new Pt(t, pauseMs));
                        gcTime.add(new Pt(t, sumMs));
                        String cause = getS(e, "cause");
                        if (cause != null && !cause.isBlank()) {
                            double[] agg = gcCauseAgg.computeIfAbsent(cause, k -> new double[2]);
                            agg[0]++;
                            agg[1] += sumMs;
                        }
                        if (t > 0 && pauseMs > 0) {
                            gcWindows.add(new long[] {t - (long) Math.ceil(pauseMs), t});
                        }
                        if (t > 0 && pauseMs >= GC_PAUSE_LISTED_MS && gcPauses.size() < GC_PAUSE_LIMIT) {
                            long startMs = e.getStartTime() == null ? t - (long) Math.ceil(pauseMs)
                                : Math.min(e.getStartTime().toEpochMilli(), t - (long) Math.ceil(pauseMs));
                            gcPauses.add(new GcPause(startMs, t, round2(pauseMs), cause));
                        }
                    }
                    case "jdk.SystemGC" -> {
                        String caller = systemGcCaller(e.getStackTrace());
                        explicitGc.computeIfAbsent(caller, k -> new long[1])[0]++;
                        if (t > 0) {
                            explicitGcTimes.add(t);
                        }
                    }
                    case "jdk.JVMInformation" -> {
                        String jvm = getS(e, "jvmArguments");
                        String java = getS(e, "javaArguments");
                        if (jvm != null && !jvm.isBlank()) {
                            jvmArgs = jvm.trim();
                        }
                        if (java != null && !java.isBlank()) {
                            javaArgs = java.trim();
                        }
                        String name = getS(e, "jvmName");
                        if (name != null && !name.isBlank()) {
                            jvmInfo = jvmInfo(name.trim(), getS(e, "jvmVersion"));
                        }
                    }
                    case "jdk.GCConfiguration" -> gcConfig = new GcConfig(
                        getS(e, "youngCollector"), getS(e, "oldCollector"),
                        getL(e, "parallelGCThreads"), getL(e, "concurrentGCThreads"),
                        getL(e, "gcTimeRatio"),
                        getB(e, "isExplicitGCConcurrent"), getB(e, "isExplicitGCDisabled"));
                    case "jdk.NativeMemoryUsageTotal" -> {
                        long committed = getL(e, "committed");
                        if (committed > 0) {
                            nmtCommitted = committed;
                        }
                    }
                    case "jdk.ExceptionStatistics" -> exceptionsCum.add(new Pt(t, getL(e, "throwables")));
                    case "jdk.ObjectAllocationSample", "jdk.ObjectAllocationInNewTLAB", "jdk.ObjectAllocationOutsideTLAB" -> {
                        String type = e.getEventType().getName();
                        RecordedThread sampled = e.getThread();
                        boolean firstSample = type.equals("jdk.ObjectAllocationSample") && sampled != null
                            && allocSampled.add(sampled.getJavaThreadId());
                        if (!firstSample) {
                            String weightField = switch (type) {
                                case "jdk.ObjectAllocationSample" -> "weight";
                                case "jdk.ObjectAllocationInNewTLAB" -> "tlabSize";
                                default -> "allocationSize";
                            };
                            long weight = recordAlloc(e, getL(e, weightField), stacks, allocSubsystem, allocThread,
                                allocClass, mods, allocSubByWorld, idThreads);
                            allocTotal += weight;
                            if (weight > 0) {
                                allocSamples++;
                            }
                        }
                    }
                    case "jdk.ContainerConfiguration" -> {
                        long quota = getL(e, "cpuQuota");
                        long period = getL(e, "cpuSlicePeriod");
                        long effective = getL(e, "effectiveCpuCount");
                        double cpuLimit = quota > 0 && period > 0 ? round2((double) quota / period)
                            : (effective > 0 ? effective : -1);
                        long memLimit = getL(e, "memoryLimit");
                        container = new Container(getS(e, "containerType"), cpuLimit,
                            memLimit > 0 ? memLimit : -1);
                    }
                    case "io.github.xytronix.hybox.SystemTick" -> {
                        String world = getS(e, "world");
                        String system = getS(e, "system");
                        if (t > 0 && world != null && !world.isBlank()
                            && system != null && !system.isBlank()) {
                            systemTickPts.computeIfAbsent(system + " @ " + world, k -> new ArrayList<>())
                                .add(new Pt(t, getD(e, "avgMs")));
                        }
                    }
                    case "io.github.xytronix.hybox.HostCpu" -> {
                        if (t > 0) {
                            hostCpuSamples.add(new long[] {
                                t,
                                getL(e, "throttledPeriods"),
                                getL(e, "throttledMicros"),
                                getL(e, "cpuTotalJiffies"),
                                getL(e, "cpuStealJiffies"),
                                getL(e, "cpuIowaitJiffies"),
                                getL(e, "cpuUsageUsec")
                            });
                            long swapIn = e.hasField("swapInBytes") ? getL(e, "swapInBytes") : -1;
                            long swapOut = e.hasField("swapOutBytes") ? getL(e, "swapOutBytes") : -1;
                            if (swapIn >= 0 && swapOut >= 0) {
                                swapMoved.add(new long[] {t, swapIn, swapOut});
                            }
                        }
                    }
                    case "jdk.SafepointBegin" -> {
                        safepointCount++;
                        safepointTotalMs += durationMs(e, "duration");
                    }
                    case "io.github.xytronix.hybox.PluginMetric" -> {
                        String name = getS(e, "name");
                        if (t > 0 && name != null && !name.isBlank()) {
                            boolean counter = Boolean.TRUE.equals(getB(e, "counter"));
                            metricIsCounter.putIfAbsent(name, counter);
                            metricPts.computeIfAbsent(name, k -> new ArrayList<>())
                                .add(new Pt(t, getD(e, "value")));
                            if (counter && e.hasField("total")) {
                                Pt running = new Pt(t, getD(e, "total"));
                                metricRunningTotal.merge(name, running, (a, b) -> b.t() >= a.t() ? b : a);
                            }
                        }
                    }
                    case "jdk.OldObjectSample" -> {
                        String className = oldObjectClass(e);
                        if (className != null && !className.isBlank()) {
                            oldObjectCounts.computeIfAbsent(className, k -> new long[1])[0]++;
                            RecordedStackTrace stack = e.getStackTrace();
                            StackInfo info = stack == null ? null : stacks.get(stack);
                            String site = info == null ? null : info.self();
                            if (site != null) {
                                oldObjectSites.computeIfAbsent(className, k -> new HashMap<>())
                                    .computeIfAbsent(site, k -> new long[1])[0]++;
                                oldObjectOwners.putIfAbsent(site, info.owners().self());
                            }
                            OldObjectPath path = oldObjectPath(e, mods);
                            if (path != null) {
                                oldObjectPaths.computeIfAbsent(className, k -> new HashMap<>())
                                    .computeIfAbsent(path.key(), k -> new PathTally(path)).count++;
                                oldObjectWithPath.computeIfAbsent(className, k -> new long[1])[0]++;
                            }
                        }
                    }
                    case "io.github.xytronix.hybox.marker" -> {
                        IncidentMark mark = IncidentMark.parse(getS(e, "message"));
                        if (mark != null && t > 0) {
                            incidentMarkers.add(new IncidentMarker(t, mark.id(), mark.severity(), mark.headline()));
                        }
                    }
                    case "io.github.xytronix.hybox.PluginEvent" -> {
                        if (t > 0) {
                            String category = getS(e, "category");
                            String message = getS(e, "message");
                            if ((category != null && !category.isBlank())
                                || (message != null && !message.isBlank())) {
                                pluginEvents.addLast(new PluginEvent(t,
                                    category == null ? "" : category, message == null ? "" : message));
                                if (pluginEvents.size() > PLUGIN_EVENT_CAP) {
                                    pluginEvents.removeFirst();
                                }
                            }
                        }
                    }
                    case "io.github.xytronix.hybox.Connection" -> {
                        String phase = getS(e, "phase");
                        if (t > 0 && phase != null && !phase.isBlank()) {
                            String player = getS(e, "player");
                            connEvents.addLast(new ConnEvent(t,
                                player == null ? "" : player, phase, getS(e, "detail")));
                            if (connEvents.size() > PLUGIN_EVENT_CAP) {
                                connEvents.removeFirst();
                            }
                        }
                    }
                    case "jdk.ThreadPark", "jdk.JavaMonitorEnter" -> {
                        LockWait wait = lockWait(e, t, stacks);
                        if (wait != null) {
                            lockWaits.addLast(wait);
                            if (lockWaits.size() > LOCK_WAIT_CAP) {
                                lockWaits.removeFirst();
                            }
                        }
                    }
                    case "jdk.OSInformation" -> {
                        String parsed = parseOsName(getS(e, "osVersion"));
                        if (parsed != null) {
                            osName = parsed;
                        }
                    }
                    case "jdk.CPUInformation" -> cpuInfo = new CpuInfo(
                        cpuBrand(getS(e, "cpu"), getS(e, "description")),
                        getL(e, "sockets"), getL(e, "cores"), getL(e, "hwThreads"));
                    case "jdk.InitialSystemProperty" -> {
                        String key = getS(e, "key");
                        if (key != null && WANTED_PROPS.contains(key)) {
                            sysProps.putIfAbsent(key, getS(e, "value"));
                        }
                    }
                    case "io.github.xytronix.hybox.Disk" -> {
                        diskFree.add(new Pt(t, getL(e, "freeBytes")));
                        long total = getL(e, "totalBytes");
                        if (total > 0) {
                            diskTotal = total;
                        }
                        long read = getL(e, "readBytes");
                        if (read >= 0 && e.hasField("readBytes")) {
                            diskRead.add(new Pt(t, read));
                        }
                        long write = getL(e, "writeBytes");
                        if (write >= 0 && e.hasField("writeBytes")) {
                            diskWrite.add(new Pt(t, write));
                        }
                    }
                    case "jdk.FileRead" -> {
                        String path = getS(e, "path");
                        if (diskPath(path)) {
                            slowIo.add(new SlowIo(path, durationMs(e, "duration"), getL(e, "bytesRead"), "read"));
                        }
                    }
                    case "jdk.ActiveSetting" -> {
                        String type = typeNames.get(getL(e, "id"));
                        if ("threshold".equals(getS(e, "name"))
                            && ("jdk.FileRead".equals(type) || "jdk.FileWrite".equals(type))) {
                            long thresholdMs = settingMillis(getS(e, "value"));
                            if (thresholdMs >= 0) {
                                slowIoThreshold = Math.min(slowIoThreshold, thresholdMs);
                            }
                        }
                    }
                    case "jdk.FileWrite" -> {
                        String path = getS(e, "path");
                        if (diskPath(path)) {
                            slowIo.add(new SlowIo(path, durationMs(e, "duration"), getL(e, "bytesWritten"), "write"));
                        }
                    }
                    case "io.github.xytronix.hybox.WorldTick" -> {
                        String world = getS(e, "world");
                        if (world != null) {
                            tpsByWorld.computeIfAbsent(world, k -> new ArrayList<>()).add(new Pt(t, getD(e, "tps")));
                            msptByWorld.computeIfAbsent(world, k -> new ArrayList<>()).add(new Pt(t, getD(e, "mspt")));
                            double worst = e.hasField("msptMax") ? getD(e, "msptMax") : -1;
                            if (worst >= 0) {
                                worstByWorld.computeIfAbsent(world, k -> new ArrayList<>()).add(new Pt(t, worst));
                            }
                            playersByWorld.computeIfAbsent(world, k -> new ArrayList<>())
                                .add(new Pt(t, getL(e, "players")));
                            long online = getL(e, "onlinePlayers");
                            if (online >= 0 && e.hasField("onlinePlayers")) {
                                onlinePlayers.add(new Pt(t, online));
                            }
                            long entityCount = getL(e, "entities");
                            if (entityCount >= 0 && e.hasField("entities")) {
                                entitiesByWorld.computeIfAbsent(world, k -> new ArrayList<>())
                                    .add(new Pt(t, entityCount));
                            }
                            long chunkCount = getL(e, "chunks");
                            if (chunkCount >= 0 && e.hasField("chunks")) {
                                chunksByWorld.computeIfAbsent(world, k -> new ArrayList<>())
                                    .add(new Pt(t, chunkCount));
                            }
                            long chunksGen = getL(e, "chunksGeneratedTotal");
                            if (chunksGen >= 0 && e.hasField("chunksGeneratedTotal")) {
                                chunksGenByWorld.computeIfAbsent(world, k -> new ArrayList<>())
                                    .add(new Pt(t, chunksGen));
                            }
                            long chunksLoad = getL(e, "chunksLoadedTotal");
                            if (chunksLoad >= 0 && e.hasField("chunksLoadedTotal")) {
                                chunksLoadByWorld.computeIfAbsent(world, k -> new ArrayList<>())
                                    .add(new Pt(t, chunksLoad));
                            }
                            double ping = getD(e, "avgPingMs");
                            long pingPlayers = getL(e, "players");
                            if (ping > 0 && pingPlayers > 0) {
                                pingWeightedByWorld.computeIfAbsent(world, k -> new ArrayList<>())
                                    .add(new Pt(t, ping * pingPlayers));
                                pingWeightsByWorld.computeIfAbsent(world, k -> new ArrayList<>())
                                    .add(new Pt(t, pingPlayers));
                            }
                        }
                    }
                    default -> modEvents.record(e, t);
                }
            }
        } catch (Exception ex) {
            if (!any) {
                return null;
            }
        }
        if (!any) {
            return null;
        }

        final long lo = minT;
        final long hi = maxT;
        final int buckets = bucketCount(lo, hi);
        Instant start = Instant.ofEpochMilli(lo);
        Instant end = Instant.ofEpochMilli(hi);

        double[] cpuJvmSeries = bucketLast(cpuJvm, minT, maxT, buckets);
        double[] cpuMachSeries = bucketLast(cpuMach, minT, maxT, buckets);
        CpuStats cpuStats = (cpuJvmSeries != null && cpuMachSeries != null)
            ? new CpuStats(avg(cpuJvmSeries), max(cpuJvmSeries), avg(cpuMachSeries), max(cpuMachSeries))
            : null;

        String iface = null;
        double best = -1;
        for (Map.Entry<String, List<Pt>> en : netOut.entrySet()) {
            double sum = en.getValue().stream().mapToDouble(Pt::v).sum();
            if (sum > best) {
                best = sum;
                iface = en.getKey();
            }
        }

        GcStats gcStats = gcCount > 0
            ? new GcStats(gcCount, round2(gcMaxPause), round2(gcTotalPause))
            : null;

        List<ExplicitGc> explicit = new ArrayList<>();
        explicitGc.forEach((caller, count) -> explicit.add(new ExplicitGc(caller, count[0])));

        List<HotMethod> hot = topMethods(samplesByMethod, chainsByMethod, chainLabels);

        Map<String, Long> worlds = new LinkedHashMap<>();
        worldSamples.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
            .forEach(en -> worlds.put(en.getKey(), en.getValue()[0]));

        Map<String, Long> subs = orderSubsystems(subsystems);
        List<WorldCpu> cpuByWorld = buildCpuByWorld(subsystemsByWorld,
            mods.cpuBy(JfrTimelineParser::worldOf, MOD_CPU_LIMIT));

        Map<String, WorldTicks> ticks = new LinkedHashMap<>();
        for (Map.Entry<String, List<Pt>> en : tpsByWorld.entrySet()) {
            double[] tps = bucketLast(en.getValue(), minT, maxT, buckets);
            double[] mspt = bucketLast(msptByWorld.getOrDefault(en.getKey(), List.of()), minT, maxT, buckets);
            double[] worst = bucketMax(worstByWorld.getOrDefault(en.getKey(), List.of()), minT, maxT, buckets);
            if (tps != null) {
                ticks.put(en.getKey(), new WorldTicks(tps, mspt, worst));
            }
        }
        onlinePlayers.sort(java.util.Comparator.comparingLong(Pt::t));
        double[] players = bucketLast(onlinePlayers.isEmpty() ? totalsPerPass(playersByWorld) : onlinePlayers,
            lo, hi, buckets);
        double[] entities = bucketLast(totalsPerPass(entitiesByWorld), lo, hi, buckets);
        double[] chunks = bucketLast(totalsPerPass(chunksByWorld), lo, hi, buckets);
        Map<String, long[]> chunkChurnByWorld = chunkChurnDeltas(chunksGenByWorld, chunksLoadByWorld);
        double[] chunksGenerated = toDeltas(sumSeries(chunksGenByWorld.values().stream()
            .map(pts -> bucketLast(pts, lo, hi, buckets)).toList()));
        double[] chunksLoaded = toDeltas(sumSeries(chunksLoadByWorld.values().stream()
            .map(pts -> bucketLast(pts, lo, hi, buckets)).toList()));

        List<NetIface> netOthers = new ArrayList<>();
        if (iface != null) {
            final String primary = iface;
            Set<String> allIfaces = new java.util.LinkedHashSet<>(netIn.keySet());
            allIfaces.addAll(netOut.keySet());
            allIfaces.stream()
                .filter(name -> !name.equals(primary) && !name.equals("lo") && !name.equals("lo0"))
                .sorted((a, b) -> Double.compare(totalTraffic(netIn, netOut, b), totalTraffic(netIn, netOut, a)))
                .limit(NET_OTHERS_LIMIT)
                .forEach(name -> {
                    double[] in = bucketLast(netIn.getOrDefault(name, List.of()), lo, hi, buckets);
                    double[] outRate = bucketLast(netOut.getOrDefault(name, List.of()), lo, hi, buckets);
                    if (in != null && outRate != null) {
                        netOthers.add(new NetIface(name, avg(in), max(in), avg(outRate), max(outRate)));
                    }
                });
        }

        Alloc alloc = null;
        if (allocTotal > 0) {
            Map<String, Long> bySubsystem = orderSubsystems(allocSubsystem);
            Map<String, Long> byThread = new LinkedHashMap<>();
            allocThread.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                .limit(ALLOC_THREAD_LIMIT)
                .forEach(en -> byThread.put(en.getKey(), en.getValue()[0]));
            Map<String, Long> byClass = new LinkedHashMap<>();
            allocClass.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                .limit(ALLOC_CLASS_LIMIT)
                .forEach(en -> byClass.put(en.getKey(), en.getValue()[0]));
            Map<String, ModShare> byMod = mods.alloc(ALLOC_MOD_LIMIT);
            Map<String, Map<String, ModShare>> modsByWorld = mods.allocByWorld(ALLOC_MOD_LIMIT);
            List<WorldAlloc> byWorld = new ArrayList<>();
            Set<String> aworlds = new java.util.LinkedHashSet<>(allocSubByWorld.keySet());
            aworlds.addAll(modsByWorld.keySet());
            for (String world : aworlds) {
                Map<String, Long> ws = orderSubsystems(allocSubByWorld.getOrDefault(world, Map.of()));
                long wb = ws.values().stream().mapToLong(Long::longValue).sum();
                byWorld.add(new WorldAlloc(world, wb, ws, modsByWorld.getOrDefault(world, Map.of())));
            }
            byWorld.sort((a, b) -> Long.compare(b.bytes(), a.bytes()));
            alloc = new Alloc(allocTotal, bySubsystem, byThread, byClass, byMod, byWorld, allocSamples);
        }

        List<MetricInput> metricInputs = new ArrayList<>();
        metricPts.forEach((name, pts) -> metricInputs.add(new MetricInput(name, null,
            Boolean.TRUE.equals(metricIsCounter.get(name)), pts, metricRunningTotal.get(name))));
        for (JfrModEvents.Metric metric : modEvents.metrics()) {
            metricInputs.add(new MetricInput(metric.mod() + "/" + metric.name(), metric.mod(), metric.counter(),
                metric.points(), null));
        }
        List<PluginMetric> pluginMetrics = pluginMetrics(metricInputs, lo, hi, buckets);

        List<Retained> retained = new ArrayList<>();
        oldObjectCounts.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
            .limit(RETAINED_LIMIT)
            .forEach(en -> {
                Map<String, long[]> sites = oldObjectSites.get(en.getKey());
                String site = sites == null || sites.isEmpty() ? null : sites.entrySet().stream()
                    .max((a, b) -> Long.compare(a.getValue()[0], b.getValue()[0]))
                    .map(Map.Entry::getKey).orElse(null);
                retained.add(new Retained(en.getKey(), en.getValue()[0], site,
                    site == null ? null : mods.resolve(oldObjectOwners.get(site)),
                    retainedChain(oldObjectPaths.get(en.getKey()), oldObjectWithPath.get(en.getKey()), mods)));
            });

        List<SystemTick> tickSeries = new ArrayList<>();
        Map<String, List<Map.Entry<String, List<Pt>>>> tickByWorld = new LinkedHashMap<>();
        systemTickPts.entrySet().stream()
            .filter(en -> en.getValue().size() >= SYSTEM_TICK_MIN_EVENTS)
            .sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
            .forEach(en -> {
                int at = en.getKey().lastIndexOf(" @ ");
                String world = at >= 0 ? en.getKey().substring(at + 3) : "";
                tickByWorld.computeIfAbsent(world, k -> new ArrayList<>()).add(en);
            });
        List<Map.Entry<String, List<Pt>>> tickPicked = new ArrayList<>();
        tickByWorld.values().forEach(list ->
            tickPicked.addAll(list.subList(0, Math.min(SYSTEM_TICK_PER_WORLD, list.size()))));
        tickPicked.sort((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()));
        tickPicked.subList(0, Math.min(SYSTEM_TICK_LIMIT, tickPicked.size()))
            .forEach(en -> {
                double[] series = bucketLast(en.getValue(), lo, hi, buckets);
                if (series != null) {
                    tickSeries.add(new SystemTick(en.getKey(), series));
                }
            });

        List<GcCause> gcCauses = new ArrayList<>();
        gcCauseAgg.entrySet().stream()
            .sorted((a, b) -> Double.compare(b.getValue()[0], a.getValue()[0]))
            .forEach(en -> gcCauses.add(
                new GcCause(en.getKey(), (long) en.getValue()[0], round2(en.getValue()[1]))));

        int[] gcOverlap = overlapBuckets(gcWindows, lo, hi, buckets);

        Flame flame = flamePrune(flameRoot, totalSamples);

        Map<String, List<Pt>> threadLaneGroups = new LinkedHashMap<>();
        for (Map.Entry<String, List<Pt>> en : threadStatePts.entrySet()) {
            threadLaneGroups.computeIfAbsent(normalizeThreadName(en.getKey()), k -> new ArrayList<>())
                .addAll(en.getValue());
        }
        List<ThreadLane> threadTimeline = new ArrayList<>();
        threadLaneGroups.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
            .limit(THREAD_LANE_LIMIT)
            .forEach(en -> threadTimeline.add(
                new ThreadLane(en.getKey(), modalStates(en.getValue(), lo, hi, buckets))));

        List<ModCpu> cpuByMod = mods.cpu(MOD_CPU_LIMIT);

        double[] ping = null;
        double[] pingWeighted = sumSeries(pingWeightedByWorld.values().stream()
            .map(pts -> bucketLast(pts, lo, hi, buckets)).toList());
        double[] pingWeights = sumSeries(pingWeightsByWorld.values().stream()
            .map(pts -> bucketLast(pts, lo, hi, buckets)).toList());
        if (pingWeighted != null && pingWeights != null) {
            ping = new double[pingWeighted.length];
            for (int i = 0; i < ping.length; i++) {
                ping[i] = pingWeights[i] > 0 ? pingWeighted[i] / pingWeights[i] : 0;
            }
        }

        return new JfrTimeline(
            start, end, totalSamples, cpuStats,
            cpuMachSeries, cpuJvmSeries,
            toLongs(bucketLast(heap, minT, maxT, buckets)),
            toLongs(bucketLast(rss, minT, maxT, buckets)),
            iface,
            iface == null ? null : bucketLast(netIn.getOrDefault(iface, List.of()), minT, maxT, buckets),
            iface == null ? null : bucketLast(netOut.getOrDefault(iface, List.of()), minT, maxT, buckets),
            toLongs(bucketLast(threads, minT, maxT, buckets)),
            bucketMaxZeroFilled(gcPause, minT, maxT, buckets),
            gcTime.isEmpty() ? null : bucketTimeShare(gcTime, minT, maxT, buckets),
            gcStats, gcConfig, explicit, nmtCommitted, cpuInfo,
            Map.copyOf(sysProps), heapMax, hot, worlds, subs, cpuByWorld,
            Map.copyOf(threadStates), ticks, players, osName, alloc,
            toDeltas(bucketLast(exceptionsCum, lo, hi, buckets)),
            explicitGcTimes.stream().mapToLong(Long::longValue).toArray(), jvmArgs, javaArgs, jvmInfo,
            container, ping, List.copyOf(pluginEvents), List.copyOf(connEvents),
            countPerBucket(connEvents, "connect", lo, hi, buckets),
            countPerBucket(connEvents, "join", lo, hi, buckets),
            countPerBucket(connEvents, "leave", lo, hi, buckets),
            List.copyOf(pluginMetrics), List.copyOf(retained),
            List.copyOf(tickSeries), List.copyOf(gcCauses), gcOverlap, flame,
            List.copyOf(threadTimeline), List.copyOf(cpuByMod),
            toLongs(bucketLast(diskFree, lo, hi, buckets)), diskTotal,
            toRateMBps(bucketLast(diskRead, lo, hi, buckets), lo, hi, buckets),
            toRateMBps(bucketLast(diskWrite, lo, hi, buckets), lo, hi, buckets),
            topSlowIo(slowIo),
            slowIoThreshold == Long.MAX_VALUE ? -1 : slowIoThreshold,
            entities,
            chunks,
            chunkChurnByWorld,
            chunksGenerated,
            chunksLoaded,
            toLongs(bucketLast(heapCommitted, lo, hi, buckets)),
            toLongs(bucketLast(hostMem, lo, hi, buckets)),
            swapFree, swapTotal, List.copyOf(netOthers),
            computeHostCpu(hostCpuSamples),
            new Safepoint(safepointCount, round2(safepointTotalMs)),
            modEvents.actions(), modEvents.settings(), groupSizes(idThreads),
            slices.build(lo, hi, buckets, mods::resolve), List.copyOf(gcPauses), swap(swapUsed, swapMoved),
            lockWaits.stream().map(w -> w.mod() == null ? w : new LockWait(w.thread(), w.group(), w.lock(),
                w.monitor(), w.startMs(), w.endMs(), w.frame(), mods.resolve(w.mod()), w.owner())).toList(),
            List.copyOf(incidentMarkers), slices.samples(mods::resolve),
            postGcHeap.stream().sorted(Comparator.comparingLong(Pt::t)).mapToLong(p -> (long) p.v()).toArray());
    }

    private static RetainedChain retainedChain(Map<String, PathTally> paths, long[] withPath, JfrModAttribution mods) {
        if (paths == null || paths.isEmpty() || withPath == null) {
            return null;
        }
        PathTally best = null;
        for (PathTally tally : paths.values()) {
            if (best == null || tally.count > best.count) {
                best = tally;
            }
        }
        OldObjectPath path = best.path;
        List<Holder> holders = path.holders().stream().map(h -> new Holder(h.type(), h.field(), h.array(),
            h.isStatic(), h.skip(), mods.resolve(h.mod()))).toList();
        return new RetainedChain(holders, path.rootDescription(), path.rootSystem(), path.rootType(),
            mods.resolve(path.mod()), best.count, withPath[0]);
    }

    private static OldObjectPath oldObjectPath(RecordedEvent e, JfrModAttribution mods) {
        RecordedObject object = getO(e, "object");
        if (object == null) {
            return null;
        }
        RecordedObject root = getO(e, "root");
        List<Holder> holders = new ArrayList<>();
        StringBuilder key = new StringBuilder();
        String chainMod = null;
        boolean decided = false;
        RecordedClass sampled = getO(object, "type") instanceof RecordedClass rc ? rc : null;
        String sampledType = sampled == null ? null : attributionType(decodeTypeName(sampled.getName()));
        if (sampledType != null && !JfrModAttribution.isLibrary(sampledType)) {
            decided = true;
            chainMod = mods.classOwner(sampledType, sampled.getClassLoader());
        }
        RecordedObject current = object;
        for (int hop = 0; hop < OLD_OBJECT_PATH_LIMIT; hop++) {
            RecordedObject ref = getO(current, "referrer");
            RecordedObject holder = ref == null ? null : getO(ref, "object");
            if (holder == null) {
                break;
            }
            RecordedObject field = getO(ref, "field");
            boolean array = getO(ref, "array") != null;
            String name = field == null ? null : getS(field, "name");
            RecordedClass holderClass = getO(holder, "type") instanceof RecordedClass rc ? rc : null;
            String type = holderClass == null ? "" : cleanType(decodeTypeName(holderClass.getName()));
            RecordedClassLoader loader = holderClass == null ? null : holderClass.getClassLoader();
            String description = getS(holder, "description");
            boolean mirror = "java.lang.Class".equals(type) && description != null
                && description.startsWith(CLASS_NAME_PREFIX);
            boolean isStatic = field != null && (field.hasField("modifiers")
                ? Modifier.isStatic((int) getL(field, "modifiers")) : mirror);
            if (isStatic && mirror) {
                type = description.substring(CLASS_NAME_PREFIX.length()).strip();
                loader = null;
            }
            String attributed = attributionType(type);
            String mod = attributed == null ? null : mods.classOwner(attributed, loader);
            if (!decided && attributed != null && !JfrModAttribution.isLibrary(attributed)) {
                decided = true;
                chainMod = mod;
            }
            holders.add(new Holder(type, name, array, isStatic, (int) getL(ref, "skip"), mod));
            key.append(type).append(isStatic ? "#" : ".").append(array ? "[]" : name).append('\n');
            current = holder;
        }
        if (holders.isEmpty() && root == null) {
            return null;
        }
        String rootDescription = root == null ? null : getS(root, "description");
        String rootSystem = root == null ? null : getS(root, "system");
        String rootType = root == null ? null : getS(root, "type");
        key.append(rootSystem).append('/').append(rootType);
        return new OldObjectPath(key.toString(), List.copyOf(holders), rootDescription, rootSystem, rootType, chainMod);
    }

    private static String attributionType(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        String base = type;
        while (base.endsWith("[]")) {
            base = base.substring(0, base.length() - 2);
        }
        return PRIMITIVES.contains(base) ? null : base;
    }

    private static RecordedObject getO(RecordedObject o, String f) {
        try {
            return o.hasField(f) && o.getValue(f) instanceof RecordedObject ro ? ro : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, long[]> chunkChurnDeltas(Map<String, List<Pt>> gen,
                                                        Map<String, List<Pt>> load) {
        Map<String, long[]> out = new LinkedHashMap<>();
        Set<String> worlds = new java.util.LinkedHashSet<>(gen.keySet());
        worlds.addAll(load.keySet());
        for (String world : worlds) {
            long g = windowDelta(gen.get(world));
            long l = windowDelta(load.get(world));
            if (g >= 0 || l >= 0) {
                out.put(world, new long[] {g, l});
            }
        }
        return out;
    }

    private static long windowDelta(List<Pt> pts) {
        if (pts == null || pts.isEmpty()) {
            return -1;
        }
        double min = Double.MAX_VALUE;
        double max = -1;
        for (Pt p : pts) {
            min = Math.min(min, p.v());
            max = Math.max(max, p.v());
        }
        long d = (long) (max - min);
        return d < 0 ? -1 : d;
    }

    private static double[] countPerBucket(Collection<ConnEvent> events, String phase,
                                           long lo, long hi, int buckets) {
        if (events.isEmpty() || hi <= lo) {
            return null;
        }
        double[] out = null;
        for (ConnEvent e : events) {
            if (!phase.equals(e.phase()) || e.timeMs() < lo || e.timeMs() > hi) {
                continue;
            }
            if (out == null) {
                out = new double[buckets];
            }
            int b = (int) ((e.timeMs() - lo) * buckets / (hi - lo + 1));
            out[Math.min(buckets - 1, Math.max(0, b))]++;
        }
        return out;
    }

    private static Swap swap(List<long[]> used, List<long[]> moved) {
        long inBytes = -1;
        long outBytes = -1;
        if (moved.size() >= 2) {
            moved.sort((a, b) -> Long.compare(a[0], b[0]));
            long[] first = moved.getFirst();
            long[] last = moved.getLast();
            if (last[1] >= first[1] && last[2] >= first[2]) {
                inBytes = last[1] - first[1];
                outBytes = last[2] - first[2];
            }
        }
        long growth = -1;
        if (used.size() >= 2) {
            used.sort((a, b) -> Long.compare(a[0], b[0]));
            growth = used.getLast()[1] - used.getFirst()[1];
        }
        return new Swap(inBytes, outBytes, growth);
    }

    private static HostCpu computeHostCpu(List<long[]> samples) {
        if (samples.size() < 2) {
            return new HostCpu(-1, -1, -1, -1, -1);
        }
        long[] first = samples.get(0);
        long[] last = samples.get(0);
        for (long[] s : samples) {
            if (s[0] < first[0]) {
                first = s;
            }
            if (s[0] > last[0]) {
                last = s;
            }
        }
        long periods = first[1] >= 0 && last[1] >= first[1] ? last[1] - first[1] : -1;
        long throttledMs = first[2] >= 0 && last[2] >= first[2] ? (last[2] - first[2]) / 1000L : -1;
        double stealPct = -1;
        double iowaitPct = -1;
        if (first[3] > 0 && last[3] > first[3]) {
            long totalDelta = last[3] - first[3];
            if (totalDelta > 0) {
                if (first[4] >= 0 && last[4] >= first[4]) {
                    stealPct = round2(100.0 * (last[4] - first[4]) / totalDelta);
                }
                if (first[5] >= 0 && last[5] >= first[5]) {
                    iowaitPct = round2(100.0 * (last[5] - first[5]) / totalDelta);
                }
            }
        }
        double cpuUsageCores = -1;
        if (first[6] >= 0 && last[6] >= first[6]) {
            long windowUs = (last[0] - first[0]) * 1000L;
            if (windowUs > 0) {
                cpuUsageCores = round2((double) (last[6] - first[6]) / windowUs);
            }
        }
        return new HostCpu(periods, throttledMs, stealPct, iowaitPct, cpuUsageCores);
    }

    private static double totalTraffic(Map<String, List<Pt>> in, Map<String, List<Pt>> out, String iface) {
        double sum = 0;
        for (Pt p : in.getOrDefault(iface, List.of())) {
            sum += p.v();
        }
        for (Pt p : out.getOrDefault(iface, List.of())) {
            sum += p.v();
        }
        return sum;
    }

    private static long heapSpaceCommitted(RecordedEvent e) {
        try {
            Object space = e.hasField("heapSpace") ? e.getValue("heapSpace") : null;
            return space instanceof RecordedObject ro ? getL(ro, "committedSize") : -1;
        } catch (Exception ex) {
            return -1;
        }
    }

    private static double[] toRateMBps(double[] cumulative, long lo, long hi, int buckets) {
        double[] deltas = toDeltas(cumulative);
        if (deltas == null) {
            return null;
        }
        double bucketSeconds = (hi - lo) / 1000.0 / buckets;
        if (bucketSeconds <= 0) {
            return null;
        }
        for (int i = 0; i < deltas.length; i++) {
            deltas[i] = Math.max(0, deltas[i]) / bucketSeconds / 1e6;
        }
        return deltas;
    }

    static boolean diskPath(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String p = path.replace('\\', '/');
        return !p.startsWith("/dev/") && !p.startsWith("/proc/") && !p.startsWith("//./") && !p.startsWith("//?/pipe/");
    }

    private static List<SlowIo> topSlowIo(List<SlowIo> all) {
        all.sort((a, b) -> Double.compare(b.durationMs(), a.durationMs()));
        return List.copyOf(all.size() > SLOW_IO_LIMIT ? all.subList(0, SLOW_IO_LIMIT) : all);
    }

    private static Map<String, Long> orderSubsystems(Map<String, long[]> raw) {
        List<Map.Entry<String, Long>> named = new ArrayList<>();
        long otherTotal = 0;
        for (Map.Entry<String, long[]> en : raw.entrySet()) {
            if (SUBSYSTEM_OTHER.equals(en.getKey())) {
                otherTotal += en.getValue()[0];
            } else {
                named.add(Map.entry(en.getKey(), en.getValue()[0]));
            }
        }
        named.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        while (named.size() + (otherTotal > 0 ? 1 : 0) > SUBSYSTEM_LIMIT) {
            int smallestEngine = -1;
            for (int i = named.size() - 1; i >= 0; i--) {
                if (named.get(i).getKey().startsWith("Engine: ")) {
                    smallestEngine = i;
                    break;
                }
            }
            if (smallestEngine < 0) {
                break;
            }
            otherTotal += named.remove(smallestEngine).getValue();
        }
        Map<String, Long> out = new LinkedHashMap<>();
        for (Map.Entry<String, Long> en : named) {
            out.put(en.getKey(), en.getValue());
        }
        if (otherTotal > 0) {
            out.put(SUBSYSTEM_OTHER, otherTotal);
        }
        return out;
    }

    private static double[] toDeltas(double[] cumulative) {
        if (cumulative == null) {
            return null;
        }
        double[] out = new double[cumulative.length];
        for (int i = 1; i < cumulative.length; i++) {
            out[i] = Math.max(0, cumulative[i] - cumulative[i - 1]);
        }
        return out;
    }

    private static long recordAlloc(RecordedEvent e, long weight, JfrStackCache<RecordedStackTrace, StackInfo> stacks,
                                    Map<String, long[]> bySubsystem,
                                    Map<String, long[]> byThread, Map<String, long[]> byClass,
                                    JfrModAttribution mods,
                                    Map<String, Map<String, long[]>> allocSubByWorld,
                                    Map<String, Set<String>> idThreads) {
        if (weight <= 0) {
            return 0;
        }
        RecordedThread thread = e.getThread();
        String threadName = thread == null ? null : thread.getJavaName();
        String world = worldOf(threadName);
        RecordedStackTrace stack = e.getStackTrace();
        if (stack != null) {
            StackInfo info = stacks.get(stack);
            String sub = info.subsystem();
            bySubsystem.computeIfAbsent(sub, k -> new long[1])[0] += weight;
            allocSubByWorld.computeIfAbsent(world, k -> new HashMap<>())
                .computeIfAbsent(sub, k -> new long[1])[0] += weight;
            boolean capture = JfrModAttribution.captureThread(threadName);
            mods.alloc(capture ? mods.capture(info.owners()) : info.owners(), world, weight);
        }
        if (threadName != null && !threadName.isBlank()) {
            String group = noteIdThread(idThreads, threadName);
            byThread.computeIfAbsent(group == null ? threadName : group, k -> new long[1])[0] += weight;
        }
        String className = getClassName(e, "objectClass");
        if (className != null && !className.isBlank()) {
            byClass.computeIfAbsent(className, k -> new long[1])[0] += weight;
        }
        return weight;
    }

    private static String parseOsName(String osVersion) {
        if (osVersion == null || osVersion.isBlank()) {
            return null;
        }
        for (String line : osVersion.split("\n")) {
            String trimmed = line.trim();
            int eq = trimmed.indexOf('=');
            String key = eq < 0 ? null : trimmed.substring(0, eq).trim();
            if ("PRETTY_NAME".equals(key) || "DISTRIB_DESCRIPTION".equals(key)) {
                String value = trimmed.substring(eq + 1).trim();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        String first = osVersion.split("\n", 2)[0].trim();
        return first.isBlank() ? null : first;
    }

    static String normalizeThreadName(String name) {
        if (name == null) {
            return null;
        }
        for (String prefix : WORLD_THREAD_PREFIXES) {
            if (name.startsWith(prefix)) {
                return name;
            }
        }
        String group = idGroup(name);
        if (group != null) {
            return group;
        }
        int i = name.length() - 1;
        while (i >= 0 && Character.isDigit(name.charAt(i))) {
            i--;
        }
        if (i < name.length() - 1 && i >= 0 && (name.charAt(i) == '-' || name.charAt(i) == '#')) {
            return name.substring(0, i);
        }
        return name;
    }

    static String idGroup(String name) {
        int dash = name.lastIndexOf('-');
        if (dash < 0 || name.length() - dash - 1 < THREAD_ID_CHARS) {
            return null;
        }
        boolean digit = false;
        for (int i = dash + 1; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c >= '0' && c <= '9') {
                digit = true;
            } else if ((c < 'a' || c > 'f') && (c < 'A' || c > 'F')) {
                return null;
            }
        }
        return digit ? name.substring(0, dash + 1) + "*" : null;
    }

    private static String noteIdThread(Map<String, Set<String>> idThreads, String name) {
        String group = name == null ? null : idGroup(name);
        if (group != null) {
            idThreads.computeIfAbsent(group, k -> new HashSet<>()).add(name);
        }
        return group;
    }

    private static Map<String, Integer> groupSizes(Map<String, Set<String>> idThreads) {
        Map<String, Integer> sizes = new HashMap<>();
        idThreads.forEach((group, names) -> sizes.put(group, names.size()));
        return Map.copyOf(sizes);
    }

    private static void recordStack(RecordedStackTrace stack, JfrStackCache<RecordedStackTrace, StackInfo> stacks,
                                    Map<String, long[]> samplesByMethod,
                                    Map<String, Map<String, long[]>> chainsByMethod,
                                    Map<String, String[]> chainLabels,
                                    Map<String, long[]> subsystems,
                                    Map<String, Map<String, long[]>> subsystemsByWorld, FlameNode flameRoot,
                                    JfrModAttribution mods, String threadName, boolean capture,
                                    JfrSlices slices, long t) {
        if (stack == null) {
            return;
        }
        StackInfo info = stacks.get(stack);
        String leaf = info.leaf();
        if (leaf == null) {
            return;
        }
        samplesByMethod.computeIfAbsent(leaf, k -> new long[1])[0]++;

        String thread = threadName == null ? null : normalizeThreadName(threadName);
        flameInsert(flameRoot, thread == null || thread.isBlank() ? "(unknown thread)" : thread,
            info.flameFrames(), info.flameOwners(), info.flameLabels());

        JfrModAttribution.Owners owners = capture ? mods.capture(info.owners()) : info.owners();
        mods.cpu(owners, thread);
        slices.add(t, info.self(), owners.self(), thread);

        String chain = info.chain();
        if (chain != null) {
            Map<String, long[]> chains = chainsByMethod.computeIfAbsent(leaf, k -> new HashMap<>());
            long[] counter = chains.get(chain);
            if (counter != null) {
                counter[0]++;
            } else if (chains.size() < CHAINS_PER_METHOD_CAP) {
                chains.put(chain, new long[] {1});
                if (info.chainLabels() != null) {
                    chainLabels.putIfAbsent(chain, info.chainLabels());
                }
            }
        }

        String sub = info.subsystem();
        subsystems.computeIfAbsent(sub, k -> new long[1])[0]++;
        subsystemsByWorld.computeIfAbsent(worldOf(threadName), k -> new HashMap<>())
            .computeIfAbsent(sub, k -> new long[1])[0]++;
    }

    private static StackInfo stackInfo(RecordedStackTrace stack, JfrModAttribution mods) {
        List<RecordedFrame> frames = stack.getFrames();
        int size = frames.size();
        String[] types = new String[size];
        String[] names = new String[size];
        for (int i = 0; i < size; i++) {
            RecordedFrame frame = frames.get(i);
            RecordedMethod method = frame.isJavaFrame() ? frame.getMethod() : null;
            if (method != null) {
                types[i] = method.getType().getName();
                names[i] = method.getName();
            }
        }
        String subsystem = classify(types);
        int leafIndex = -1;
        for (int i = 0; i < size; i++) {
            if (types[i] != null) {
                leafIndex = i;
                break;
            }
        }
        if (leafIndex < 0) {
            return new StackInfo(null, null, null, null, null, null, null, subsystem, JfrModAttribution.Owners.NONE);
        }
        String leaf = cleanType(types[leafIndex]) + "." + names[leafIndex];
        String[] frameLabels = new String[size];
        boolean labelled = false;
        for (int i = 0; i < size; i++) {
            if (types[i] != null) {
                frameLabels[i] = FrameLabels.label(cleanType(types[i]), names[i]);
                labelled |= frameLabels[i] != null;
            }
        }

        List<String> path = new ArrayList<>(Math.min(size, FLAME_DEPTH_LIMIT - 1));
        List<Integer> owners = new ArrayList<>(path.size());
        List<String> pathLabels = labelled ? new ArrayList<>(path.size()) : null;
        for (int i = size - 1; i >= 0 && path.size() < FLAME_DEPTH_LIMIT - 1; i--) {
            if (types[i] != null) {
                String type = cleanType(types[i]);
                path.add(shortName(type) + "." + names[i]);
                owners.add(JfrModAttribution.mixinCode(names[i], frameLabels[i]) ? FLAME_OWNER_MOD : frameOwner(type));
                if (pathLabels != null) {
                    pathLabels.add(frameLabels[i]);
                }
            }
        }
        int[] ownerArr = new int[owners.size()];
        for (int i = 0; i < ownerArr.length; i++) {
            ownerArr[i] = owners.get(i);
        }

        JfrModAttribution.Owners modOwners = mods.owners(types, names, i -> frameLabels[i],
            i -> JfrModAttribution.loaderId(frames.get(i)));
        int selfAt = JfrModAttribution.selfFrame(types, names, i -> frameLabels[i]);
        String self = selfAt < 0 ? leaf : cleanType(types[selfAt]) + "." + names[selfAt];

        StringBuilder chain = new StringBuilder(96);
        String[] chainLabels = new String[CALLER_CHAIN_FRAMES];
        boolean chainLabelled = false;
        int added = 0;
        for (int i = leafIndex + 1; i < size && added < CALLER_CHAIN_FRAMES; i++) {
            if (types[i] == null) {
                continue;
            }
            if (added > 0) {
                chain.append(' ');
            }
            chain.append(shortName(cleanType(types[i]))).append('.').append(names[i]);
            chainLabels[added] = frameLabels[i];
            chainLabelled |= frameLabels[i] != null;
            added++;
        }
        return new StackInfo(leaf, self, path.toArray(new String[0]), ownerArr,
            pathLabels == null ? null : pathLabels.toArray(new String[0]),
            added > 0 ? chain.toString() : null, chainLabelled ? java.util.Arrays.copyOf(chainLabels, added) : null,
            subsystem, modOwners);
    }

    private static LockWait lockWait(RecordedEvent e, long end, JfrStackCache<RecordedStackTrace, StackInfo> stacks) {
        long ms = e.getDuration().toMillis();
        boolean monitor = "jdk.JavaMonitorEnter".equals(e.getEventType().getName());
        RecordedThread thread = e.getThread();
        String name = thread == null ? null : thread.getJavaName();
        String lock = getClassName(e, monitor ? "monitorClass" : "parkedClass");
        if (ms < JfrTimeline.LOCK_WAIT_MS || end <= 0 || name == null || lock == null
            || JfrModAttribution.captureThread(name)
            || !monitor && !name.startsWith(WORLD_THREAD_PREFIX) && !lockClass(lock)) {
            return null;
        }
        RecordedStackTrace stack = e.getStackTrace();
        StackInfo info = stack == null ? null : stacks.get(stack);
        RecordedThread previous = monitor && e.hasField("previousOwner") ? e.getThread("previousOwner") : null;
        return new LockWait(name, normalizeThreadName(name), lock, monitor, end - ms, end,
            info == null ? null : info.self(), info == null ? null : info.owners().self(),
            previous == null ? null : previous.getJavaName());
    }

    private static boolean lockClass(String type) {
        return type.equals("java.util.concurrent.locks.StampedLock")
            || type.startsWith("java.util.concurrent.locks.ReentrantLock$")
            || type.startsWith("java.util.concurrent.locks.ReentrantReadWriteLock$");
    }

    private static String classify(String[] types) {
        String deepestEngine = null;
        for (String type : types) {
            if (type == null) {
                continue;
            }
            for (String[] pattern : SUBSYSTEM_PATTERNS) {
                for (int i = 1; i < pattern.length; i++) {
                    if (type.contains(pattern[i])) {
                        return pattern[0];
                    }
                }
            }
            if (deepestEngine == null && type.startsWith(HYTALE_PACKAGE)) {
                deepestEngine = type;
            }
        }
        return deepestEngine == null ? SUBSYSTEM_OTHER : engineArea(deepestEngine);
    }

    private static String engineArea(String type) {
        String rest = type.substring(HYTALE_PACKAGE.length());
        if (rest.startsWith("component.")) {
            return "Engine: ECS core";
        }
        if (rest.startsWith("protocol")) {
            return "Engine: protocol";
        }
        if (rest.startsWith("builtin.")) {
            return engineAreaSegment(rest.substring("builtin.".length()));
        }
        if (rest.startsWith("server.core.modules.")) {
            return engineAreaSegment(rest.substring("server.core.modules.".length()));
        }
        if (rest.startsWith("server.core.universe.")) {
            return "Engine: universe";
        }
        if (rest.startsWith("server.core.")) {
            return engineAreaSegment(rest.substring("server.core.".length()));
        }
        return engineAreaSegment(rest);
    }

    private static String engineAreaSegment(String rest) {
        int dot = rest.indexOf('.');
        String segment = dot < 0 ? "" : rest.substring(0, dot);
        return segment.isBlank() ? SUBSYSTEM_OTHER : "Engine: " + segment;
    }

    private static List<HotMethod> topMethods(Map<String, long[]> samplesByMethod,
                                              Map<String, Map<String, long[]>> chainsByMethod,
                                              Map<String, String[]> chainLabels) {
        List<Map.Entry<String, long[]>> ordered = new ArrayList<>(samplesByMethod.entrySet());
        ordered.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
        List<HotMethod> out = new ArrayList<>(Math.min(HOT_METHOD_LIMIT, ordered.size()));
        for (Map.Entry<String, long[]> en : ordered) {
            if (out.size() >= HOT_METHOD_LIMIT) {
                break;
            }
            List<String> callers = List.of();
            List<String> callerLabels = List.of();
            Map<String, long[]> chains = chainsByMethod.get(en.getKey());
            if (chains != null && !chains.isEmpty()) {
                String dominant = chains.entrySet().stream()
                    .max((a, b) -> Long.compare(a.getValue()[0], b.getValue()[0]))
                    .map(Map.Entry::getKey).orElse(null);
                if (dominant != null) {
                    callers = List.of(dominant.split(" "));
                    String[] labels = chainLabels.get(dominant);
                    if (labels != null) {
                        callerLabels = java.util.Arrays.asList(labels);
                    }
                }
            }
            out.add(new HotMethod(en.getKey(), en.getValue()[0], callers, callerLabels));
        }
        return out;
    }

    static String cleanType(String type) {
        if (type == null) {
            return "";
        }
        int lam = type.indexOf("$$Lambda");
        if (lam > 0) {
            return type.substring(0, lam);
        }
        int slash = type.indexOf("/0x");
        if (slash > 0) {
            return type.substring(0, slash);
        }
        if (type.startsWith("0x")) {
            return "lambda@" + type.substring(Math.max(2, type.length() - 6));
        }
        return type;
    }

    private static String shortName(String type) {
        int i = type.lastIndexOf('.');
        return i < 0 ? type : type.substring(i + 1);
    }

    static String worldOf(String threadName) {
        if (threadName != null) {
            for (String prefix : WORLD_THREAD_PREFIXES) {
                if (threadName.startsWith(prefix) && threadName.length() > prefix.length()) {
                    return threadName.substring(prefix.length());
                }
            }
        }
        return NO_WORLD;
    }

    private static List<WorldCpu> buildCpuByWorld(Map<String, Map<String, long[]>> subsystemsByWorld,
                                                  Map<String, Map<String, ModShare>> modsByWorld) {
        Set<String> worlds = new java.util.LinkedHashSet<>(subsystemsByWorld.keySet());
        worlds.addAll(modsByWorld.keySet());
        List<WorldCpu> out = new ArrayList<>();
        for (String world : worlds) {
            Map<String, Long> subs = orderSubsystems(subsystemsByWorld.getOrDefault(world, Map.of()));
            long total = subs.values().stream().mapToLong(Long::longValue).sum();
            if (total > 0) {
                out.add(new WorldCpu(world, total, subs, modsByWorld.getOrDefault(world, Map.of())));
            }
        }
        out.sort((a, b) -> Long.compare(b.samples(), a.samples()));
        return out;
    }

    private static final int FLAME_OWNER_THREAD = 0;
    private static final int FLAME_OWNER_ENGINE = 1;
    private static final int FLAME_OWNER_MOD = 2;
    private static final int FLAME_OWNER_JDK = 3;

    private static int frameOwner(String type) {
        if (type.startsWith(HYTALE_PACKAGE)) {
            return FLAME_OWNER_ENGINE;
        }
        if (type.startsWith("java.") || type.startsWith("jdk.") || type.startsWith("sun.")
            || type.startsWith("javax.") || type.startsWith("com.sun.")) {
            return FLAME_OWNER_JDK;
        }
        return FLAME_OWNER_MOD;
    }

    static void flameInsert(FlameNode root, List<String> path) {
        flameInsert(root, path, null);
    }

    static void flameInsert(FlameNode root, List<String> path, int[] owners) {
        root.samples++;
        FlameNode node = root;
        for (int i = 0; i < path.size() && i < FLAME_DEPTH_LIMIT; i++) {
            node = node.children.computeIfAbsent(path.get(i), FlameNode::new);
            node.samples++;
            if (owners != null && i < owners.length && node.owner == 0) {
                node.owner = owners[i];
            }
        }
    }

    private static void flameInsert(FlameNode root, String thread, String[] frames, int[] owners, String[] labels) {
        root.samples++;
        FlameNode node = root.children.computeIfAbsent(thread, FlameNode::new);
        node.samples++;
        if (node.owner == 0) {
            node.owner = FLAME_OWNER_THREAD;
        }
        for (int i = 0; i < frames.length; i++) {
            node = node.children.computeIfAbsent(frames[i], FlameNode::new);
            node.samples++;
            if (node.owner == 0) {
                node.owner = owners[i];
            }
            if (labels != null && node.label == null) {
                node.label = labels[i];
            }
        }
    }

    static Flame flamePrune(FlameNode root, long totalSamples) {
        if (root == null || root.samples <= 0) {
            return null;
        }
        long threshold = Math.max(3, totalSamples / FLAME_MIN_DIVISOR);
        while (countAbove(root, threshold) > FLAME_NODE_CAP) {
            threshold *= 2;
        }
        return toFlame(root, threshold);
    }

    private static int countAbove(FlameNode node, long threshold) {
        int n = 1;
        for (FlameNode child : node.children.values()) {
            if (child.samples >= threshold) {
                n += countAbove(child, threshold);
            }
        }
        return n;
    }

    private static Flame toFlame(FlameNode node, long threshold) {
        List<Flame> children = new ArrayList<>();
        for (FlameNode child : node.children.values()) {
            if (child.samples >= threshold) {
                children.add(toFlame(child, threshold));
            }
        }
        return new Flame(node.name, node.samples, children, node.owner, node.label);
    }

    private static int stateCode(String mapped) {
        return switch (mapped) {
            case "RUNNABLE" -> 1;
            case "WAITING" -> 2;
            case "TIMED_WAITING" -> 3;
            case "BLOCKED" -> 4;
            default -> 0;
        };
    }

    private static int[] modalStates(List<Pt> pts, long lo, long hi, int buckets) {
        int[][] counts = new int[buckets][5];
        for (Pt p : pts) {
            int code = (int) p.v();
            if (code >= 1 && code <= 4) {
                counts[bucketOf(p.t(), lo, hi, buckets)][code]++;
            }
        }
        int[] preference = {1, 4, 2, 3};
        int[] out = new int[buckets];
        for (int b = 0; b < buckets; b++) {
            int bestCode = 0;
            int bestCount = 0;
            for (int code : preference) {
                if (counts[b][code] > bestCount) {
                    bestCount = counts[b][code];
                    bestCode = code;
                }
            }
            out[b] = bestCode;
        }
        return out;
    }

    private static int[] overlapBuckets(List<long[]> windows, long lo, long hi, int buckets) {
        java.util.TreeSet<Integer> touched = new java.util.TreeSet<>();
        for (long[] window : windows) {
            if (window[1] < lo || window[0] > hi) {
                continue;
            }
            int from = bucketOf(Math.max(window[0], lo), lo, hi, buckets);
            int to = bucketOf(Math.min(window[1], hi), lo, hi, buckets);
            for (int b = from; b <= to; b++) {
                touched.add(b);
            }
        }
        int[] out = new int[touched.size()];
        int i = 0;
        for (int b : touched) {
            out[i++] = b;
        }
        return out;
    }

    private static String mapState(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.startsWith("STATE_") ? raw.substring("STATE_".length()) : raw;
        return switch (s) {
            case "RUNNABLE" -> "RUNNABLE";
            case "SLEEPING", "PARKED_TIMED", "TIMED_WAITING", "IN_OBJECT_WAIT_TIMED" -> "TIMED_WAITING";
            case "PARKED", "WAITING", "IN_OBJECT_WAIT" -> "WAITING";
            case "BLOCKED", "BLOCKED_ON_MONITOR_ENTER" -> "BLOCKED";
            default -> null;
        };
    }

    private static String systemGcCaller(RecordedStackTrace stack) {
        if (stack == null) {
            return "<unknown>";
        }
        for (RecordedFrame frame : stack.getFrames()) {
            if (!frame.isJavaFrame() || frame.getMethod() == null) {
                continue;
            }
            String type = frame.getMethod().getType().getName();
            if (type.equals("java.lang.System") || type.equals("java.lang.Runtime")) {
                continue;
            }
            return type + "." + frame.getMethod().getName();
        }
        return "<unknown>";
    }

    static JvmInfo jvmInfo(String name, String version) {
        Matcher m = version == null ? null : JVM_VERSION.matcher(version);
        if (m == null || !m.find()) {
            return new JvmInfo(name, null, null);
        }
        return new JvmInfo(name, m.group(3) != null ? m.group(3) : m.group(1), m.group(2));
    }

    private static String cpuBrand(String cpu, String description) {
        if (description != null) {
            int i = description.indexOf("Brand: ");
            if (i >= 0) {
                int from = i + "Brand: ".length();
                int to = description.indexOf(',', from);
                int nl = description.indexOf('\n', from);
                if (nl >= 0 && (to < 0 || nl < to)) {
                    to = nl;
                }
                String brand = (to < 0 ? description.substring(from) : description.substring(from, to)).trim();
                if (!brand.isEmpty()) {
                    return brand;
                }
            }
        }
        return cpu;
    }

    private static double[] bucketMax(List<Pt> pts, long minT, long maxT, int buckets) {
        if (pts == null || pts.size() < 2 || maxT <= minT) {
            return null;
        }
        double[] out = new double[buckets];
        boolean[] seen = new boolean[buckets];
        for (Pt p : pts) {
            int b = bucketOf(p.t(), minT, maxT, buckets);
            out[b] = seen[b] ? Math.max(out[b], p.v()) : p.v();
            seen[b] = true;
        }
        return fillGaps(out, seen);
    }

    private static double[] bucketLast(List<Pt> pts, long minT, long maxT, int buckets) {
        if (pts == null || pts.size() < 2 || maxT <= minT) {
            return null;
        }
        double[] out = new double[buckets];
        boolean[] seen = new boolean[buckets];
        for (Pt p : pts) {
            int b = bucketOf(p.t(), minT, maxT, buckets);
            out[b] = p.v();
            seen[b] = true;
        }
        return fillGaps(out, seen);
    }

    private static List<PluginMetric> pluginMetrics(List<MetricInput> inputs, long lo, long hi, int buckets) {
        String[] uncharted = new String[inputs.size()];
        List<Integer> chartable = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            MetricInput input = inputs.get(i);
            if (allZero(input.pts())) {
                uncharted[i] = "zero";
            } else if (input.counter() && input.pts().stream().mapToLong(Pt::t).distinct().count() < 2) {
                uncharted[i] = "single";
            } else {
                chartable.add(i);
            }
        }
        chartable.sort((a, b) -> Integer.compare(inputs.get(b).pts().size(), inputs.get(a).pts().size()));
        for (int i = PLUGIN_METRIC_LIMIT; i < chartable.size(); i++) {
            uncharted[chartable.get(i)] = "limit";
        }
        double bucketSeconds = (hi - lo + 1) / 1000.0 / buckets;
        List<PluginMetric> out = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            MetricInput input = inputs.get(i);
            List<Pt> values = input.pts();
            boolean counter = input.counter();
            String reason = uncharted[i];
            double[] series = reason != null ? null
                : counter ? counterRate(values, lo, hi, buckets) : gaugeSeries(values, lo, hi, buckets);
            double total;
            double cumulative = Double.NaN;
            long first = values.stream().mapToLong(Pt::t).min().orElse(0);
            boolean rated = counter && values.stream().mapToLong(Pt::t).anyMatch(t -> t != first);
            if (counter) {
                double all = values.stream().mapToDouble(Pt::v).sum();
                double before = rated ? values.stream().filter(p -> p.t() == first).mapToDouble(Pt::v).sum() : 0;
                total = all - before;
                Pt running = input.running();
                if (running != null && running.v() >= all - 1e-9 * Math.abs(all)) {
                    cumulative = running.v();
                } else if (before != 0) {
                    cumulative = all;
                }
            } else {
                total = values.stream().max(Comparator.comparingLong(Pt::t)).map(Pt::v).orElse(0.0);
            }
            double[] amounts = null;
            if (counter && series != null) {
                amounts = new double[buckets];
                for (Pt p : values) {
                    if (!(rated && p.t() == first)) {
                        amounts[bucketOf(p.t(), lo, hi, buckets)] += p.v();
                    }
                }
            }
            out.add(new PluginMetric(input.name(), counter ? "count" : "gauge", series, amounts, bucketSeconds,
                values.size(), total, cumulative, reason, input.mod()));
        }
        out.sort(Comparator.comparing(PluginMetric::name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    private static boolean allZero(List<Pt> pts) {
        return pts.stream().allMatch(p -> p.v() == 0);
    }

    private static double[] gaugeSeries(List<Pt> pts, long lo, long hi, int buckets) {
        double[] out = new double[buckets];
        boolean[] seen = new boolean[buckets];
        for (Pt p : pts) {
            int b = bucketOf(p.t(), lo, hi, buckets);
            out[b] = p.v();
            seen[b] = true;
        }
        return fillGaps(out, seen);
    }

    static double[] counterRate(List<Pt> pts, long lo, long hi, int buckets) {
        TreeMap<Long, Double> byTime = new TreeMap<>();
        for (Pt p : pts) {
            byTime.merge(p.t(), p.v(), Double::sum);
        }
        if (byTime.size() < 2) {
            return null;
        }
        long[] times = byTime.keySet().stream().mapToLong(Long::longValue).toArray();
        double[] deltas = byTime.values().stream().mapToDouble(Double::doubleValue).toArray();
        double span = hi - lo + 1;
        double bucketMs = span / buckets;
        double[] amount = new double[buckets];
        double[] covered = new double[buckets];
        for (int i = 1; i < times.length; i++) {
            double perMs = deltas[i] / (times[i] - times[i - 1]);
            double a = Math.max(times[i - 1], lo);
            double b = Math.min(times[i], lo + span);
            for (int k = Math.max(0, (int) ((a - lo) / bucketMs)); k < buckets && lo + k * bucketMs < b; k++) {
                double overlap = Math.min(b, lo + (k + 1) * bucketMs) - Math.max(a, lo + k * bucketMs);
                if (overlap > 0) {
                    amount[k] += perMs * overlap;
                    covered[k] += overlap;
                }
            }
        }
        double[] out = new double[buckets];
        for (int k = 0; k < buckets; k++) {
            out[k] = covered[k] > 0 ? amount[k] / covered[k] * 1000.0 : Double.NaN;
        }
        return out;
    }

    private static double[] bucketMaxZeroFilled(List<Pt> pts, long minT, long maxT, int buckets) {
        if (pts == null || pts.size() < 2 || maxT <= minT) {
            return null;
        }
        double[] out = new double[buckets];
        for (Pt p : pts) {
            int b = bucketOf(p.t(), minT, maxT, buckets);
            out[b] = Math.max(out[b], p.v());
        }
        return out;
    }

    private static double[] bucketTimeShare(List<Pt> pts, long minT, long maxT, int buckets) {
        if (maxT <= minT) {
            return null;
        }
        double[] out = new double[buckets];
        for (Pt p : pts) {
            out[bucketOf(p.t(), minT, maxT, buckets)] += p.v();
        }
        double bucketMs = (maxT - minT + 1) / (double) buckets;
        for (int i = 0; i < buckets; i++) {
            out[i] = Math.min(100, 100 * out[i] / bucketMs);
        }
        return out;
    }

    private static int bucketOf(long t, long minT, long maxT, int buckets) {
        int b = (int) ((t - minT) * buckets / (maxT - minT + 1));
        return Math.max(0, Math.min(buckets - 1, b));
    }

    static int bucketCount(long lo, long hi) {
        if (hi <= lo) {
            return BUCKETS;
        }
        long target = Math.round((hi - lo) / 1000.0 / 10.0);
        return (int) Math.max(BUCKETS, Math.min(MAX_BUCKETS, target));
    }

    private static double[] fillGaps(double[] values, boolean[] seen) {
        double first = Double.NaN;
        for (int i = 0; i < values.length; i++) {
            if (seen[i]) {
                first = values[i];
                break;
            }
        }
        if (Double.isNaN(first)) {
            return null;
        }
        double last = first;
        for (int i = 0; i < values.length; i++) {
            if (seen[i]) {
                last = values[i];
            } else {
                values[i] = last;
            }
        }
        return values;
    }

    private static List<Pt> totalsPerPass(Map<String, List<Pt>> byWorld) {
        record WorldPt(String world, Pt pt) {
        }
        List<WorldPt> all = new ArrayList<>();
        byWorld.forEach((world, pts) -> pts.forEach(pt -> all.add(new WorldPt(world, pt))));
        all.sort(java.util.Comparator.comparingLong(w -> w.pt().t()));
        List<Pt> out = new ArrayList<>();
        Set<String> inPass = new HashSet<>();
        long passStart = 0;
        double sum = -1;
        for (WorldPt sample : all) {
            if (inPass.isEmpty() || sample.pt().t() - passStart > SAMPLE_PASS_GAP_MS || !inPass.add(sample.world())) {
                if (sum >= 0) {
                    out.add(new Pt(passStart, sum));
                }
                inPass.clear();
                inPass.add(sample.world());
                passStart = sample.pt().t();
                sum = -1;
            }
            if (sample.pt().v() >= 0) {
                sum = Math.max(sum, 0) + sample.pt().v();
            }
        }
        if (sum >= 0) {
            out.add(new Pt(passStart, sum));
        }
        return out;
    }

    private static double[] sumSeries(List<double[]> series) {
        double[] out = null;
        for (double[] s : series) {
            if (s == null) {
                continue;
            }
            if (out == null) {
                out = s.clone();
            } else {
                for (int i = 0; i < out.length; i++) {
                    out[i] += s[i];
                }
            }
        }
        return out;
    }

    private static long[] toLongs(double[] values) {
        if (values == null) {
            return null;
        }
        long[] out = new long[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = Math.round(values[i]);
        }
        return out;
    }

    private static double avg(double[] values) {
        double sum = 0;
        for (double v : values) {
            sum += v;
        }
        return round2(sum / values.length);
    }

    private static double max(double[] values) {
        double m = values[0];
        for (double v : values) {
            m = Math.max(m, v);
        }
        return round2(m);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double durationMs(RecordedEvent e, String field) {
        try {
            if (e.hasField(field)) {
                java.time.Duration d = e.getDuration(field);
                return d == null ? 0 : d.toNanos() / 1_000_000.0;
            }
        } catch (Exception ignored) {
        }
        try {
            return e.getDuration() == null ? 0 : e.getDuration().toNanos() / 1_000_000.0;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static double getD(RecordedObject o, String f) {
        try {
            return o.hasField(f) ? o.getDouble(f) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static long getL(RecordedObject o, String f) {
        try {
            return o.hasField(f) ? o.getLong(f) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static long settingMillis(String value) {
        if (value == null) {
            return -1;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\s*(ns|us|ms|s|m|h|d)").matcher(value.trim());
        if (!m.matches()) {
            return -1;
        }
        long amount = Long.parseLong(m.group(1));
        return switch (m.group(2)) {
            case "ns" -> amount / 1_000_000;
            case "us" -> amount / 1_000;
            case "ms" -> amount;
            case "s" -> amount * 1_000;
            case "m" -> amount * 60_000;
            case "h" -> amount * 3_600_000;
            default -> amount * 86_400_000;
        };
    }

    private static String getS(RecordedObject o, String f) {
        try {
            return o.hasField(f) ? o.getString(f) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Boolean getB(RecordedObject o, String f) {
        try {
            return o.hasField(f) ? o.getBoolean(f) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String getClassName(RecordedObject o, String f) {
        try {
            Object value = o.hasField(f) ? o.getValue(f) : null;
            return value instanceof RecordedClass rc ? decodeTypeName(rc.getName()) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Turns JVM array descriptors ("[J", "[Ljava.lang.Object;") into source form ("long[]", "java.lang.Object[]"). */
    static String decodeTypeName(String name) {
        if (name == null || !name.startsWith("[")) {
            return name;
        }
        int dims = 0;
        while (dims < name.length() && name.charAt(dims) == '[') {
            dims++;
        }
        String rest = name.substring(dims);
        if (rest.isEmpty()) {
            return name;
        }
        String base = switch (rest.charAt(0)) {
            case 'Z' -> "boolean";
            case 'B' -> "byte";
            case 'C' -> "char";
            case 'D' -> "double";
            case 'F' -> "float";
            case 'I' -> "int";
            case 'J' -> "long";
            case 'S' -> "short";
            case 'L' -> rest.endsWith(";") ? rest.substring(1, rest.length() - 1) : rest;
            default -> rest;
        };
        return base + "[]".repeat(dims);
    }

    private static String oldObjectClass(RecordedEvent e) {
        try {
            Object object = e.hasField("object") ? e.getValue("object") : null;
            if (object instanceof RecordedObject ro) {
                Object type = ro.hasField("type") ? ro.getValue("type") : null;
                if (type instanceof RecordedClass rc) {
                    return decodeTypeName(rc.getName());
                }
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }
}
