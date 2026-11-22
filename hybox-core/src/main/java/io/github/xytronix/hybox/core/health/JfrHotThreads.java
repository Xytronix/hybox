package io.github.xytronix.hybox.core.health;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

public final class JfrHotThreads {
    private static final String EXECUTION_SAMPLE = "jdk.ExecutionSample";
    private static final Set<String> ALLOCATION_EVENTS = Set.of(
        "jdk.ObjectAllocationSample", "jdk.ObjectAllocationInNewTLAB", "jdk.ObjectAllocationOutsideTLAB");
    private static final int HOT_THREAD_METHOD_LIMIT = 6;
    private static final int HOT_THREAD_MOD_LIMIT = 3;

    private record Top(String method, JfrModAttribution.Owners owners) {}

    private JfrHotThreads() {
    }

    public static List<HealthSnapshot.HotThread> parse(Path recording, int limit) {
        Map<String, long[]> samplesByThread = new HashMap<>();
        Map<String, Map<String, Long>> topMethodByThread = new HashMap<>();
        JfrModAttribution mods = new JfrModAttribution();
        JfrStackCache<RecordedStackTrace, Top> tops = new JfrStackCache<>(stack -> top(stack, mods));
        try (RecordingFile file = new RecordingFile(recording)) {
            while (file.hasMoreEvents()) {
                RecordedEvent event = file.readEvent();
                String type = event.getEventType().getName();
                if (!EXECUTION_SAMPLE.equals(type)) {
                    if (ALLOCATION_EVENTS.contains(type) && event.getStackTrace() != null) {
                        tops.get(event.getStackTrace());
                    }
                    continue;
                }
                RecordedThread thread = event.getThread("sampledThread");
                if (thread == null) {
                    thread = event.getThread();
                }
                String threadName = thread == null ? null : thread.getJavaName();
                if (threadName == null || threadName.isBlank()) {
                    threadName = thread == null ? null : thread.getOSName();
                }
                if (threadName == null || threadName.isBlank()) {
                    threadName = "<unknown>";
                }
                samplesByThread.computeIfAbsent(threadName, k -> new long[1])[0]++;

                RecordedStackTrace stack = event.getStackTrace();
                Top top = stack == null ? null : tops.get(stack);
                if (top != null) {
                    mods.cpu(top.owners(), threadName);
                    if (top.method() != null) {
                        topMethodByThread
                            .computeIfAbsent(threadName, k -> new HashMap<>())
                            .merge(top.method(), 1L, Long::sum);
                    }
                }
            }
        } catch (Exception e) {
            return List.of();
        }

        Map<String, Map<String, JfrTimeline.ModShare>> modsByThread = mods.cpuBy(UnaryOperator.identity(),
            HOT_THREAD_MOD_LIMIT);
        List<HealthSnapshot.HotThread> out = new ArrayList<>(samplesByThread.size());
        for (Map.Entry<String, long[]> entry : samplesByThread.entrySet()) {
            String name = entry.getKey();
            long samples = entry.getValue()[0];
            Map<String, Long> methods = topMethodByThread.get(name);
            out.add(new HealthSnapshot.HotThread(name, samples, dominantMethod(methods),
                topMethods(methods, HOT_THREAD_METHOD_LIMIT), modsByThread.get(name)));
        }
        out.sort((a, b) -> Long.compare(b.samples(), a.samples()));
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    private static List<HealthSnapshot.MethodSample> topMethods(Map<String, Long> methods, int limit) {
        if (methods == null || methods.isEmpty()) {
            return List.of();
        }
        return methods.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
            .limit(limit)
            .map(e -> new HealthSnapshot.MethodSample(e.getKey(), e.getValue()))
            .toList();
    }

    private static Top top(RecordedStackTrace stack, JfrModAttribution mods) {
        String method = null;
        for (RecordedFrame frame : stack.getFrames()) {
            if (frame.isJavaFrame() && frame.getMethod() != null) {
                method = JfrTimelineParser.cleanType(frame.getMethod().getType().getName())
                    + "." + frame.getMethod().getName();
                break;
            }
        }
        return new Top(method, mods.owners(stack));
    }

    private static String dominantMethod(Map<String, Long> methods) {
        if (methods == null || methods.isEmpty()) {
            return "<unknown>";
        }
        return methods.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse("<unknown>");
    }
}
