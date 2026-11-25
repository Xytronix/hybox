package io.github.xytronix.hybox.core.health;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import io.github.xytronix.hybox.core.incident.DiagnosticSection;
import io.github.xytronix.hybox.core.trigger.TriggerEvent;
import io.github.xytronix.hybox.core.trigger.TriggerKind;

public final class StalledThread {
    private static final int MAX_FRAMES = 40;

    private StalledThread() {
    }

    public static final String FRAME_KEY = "Blocked in";

    public static String frameVerb(String state) {
        String key = state == null ? "" : state.trim().toUpperCase(Locale.ROOT);
        return switch (key) {
            case "BLOCKED" -> "Blocked in";
            case "WAITING", "TIMED_WAITING" -> "Waiting in";
            case "RUNNABLE" -> "Running in";
            default -> "In";
        };
    }

    public static String stateLabel(String state) {
        String key = state == null ? "" : state.trim().toUpperCase(Locale.ROOT);
        return switch (key) {
            case "RUNNABLE" -> "Running";
            case "BLOCKED" -> "Blocked";
            case "WAITING" -> "Waiting";
            case "TIMED_WAITING" -> "Timed waiting";
            case "NEW" -> "New";
            case "TERMINATED" -> "Terminated";
            default -> state;
        };
    }

    public static List<DiagnosticSection> prependTo(List<DiagnosticSection> base, TriggerEvent event,
                                                    Collection<String> frameworkPrefixes) {
        List<DiagnosticSection> sections = build(event, frameworkPrefixes);
        if (sections.isEmpty()) {
            return base;
        }
        List<DiagnosticSection> out = new ArrayList<>(sections.size() + base.size());
        out.addAll(sections);
        out.addAll(base);
        return out;
    }

    static List<DiagnosticSection> build(TriggerEvent event, Collection<String> frameworkPrefixes) {
        if (event == null) {
            return List.of();
        }
        long[] ids = threadIds(event);
        if (ids.length == 0) {
            return List.of();
        }
        String title = sectionTitle(event.kind());
        List<DiagnosticSection> sections = new ArrayList<>(ids.length);
        for (long id : ids) {
            DiagnosticSection section = section(title, id, event, frameworkPrefixes);
            if (section != null) {
                sections.add(section);
            }
        }
        return sections;
    }

    private static long[] threadIds(TriggerEvent event) {
        String raw = event.attrs().get("threadId");
        if (raw == null) {
            raw = event.attrs().get("threadIds");
        }
        if (raw == null || raw.isBlank()) {
            return new long[0];
        }
        String[] parts = raw.split(",");
        long[] ids = new long[parts.length];
        int count = 0;
        for (String part : parts) {
            try {
                ids[count] = Long.parseLong(part.trim());
                count++;
            } catch (NumberFormatException ignored) {
                continue;
            }
        }
        return java.util.Arrays.copyOf(ids, count);
    }

    private static String sectionTitle(TriggerKind kind) {
        return switch (kind) {
            case HEARTBEAT_STALL -> "Stalled thread";
            case DEADLOCK -> "Deadlocked thread";
            default -> "Thread";
        };
    }

    private static DiagnosticSection section(String title, long id, TriggerEvent event,
                                             Collection<String> frameworkPrefixes) {
        ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(id, MAX_FRAMES);
        if (info == null) {
            return null;
        }
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("Thread", info.getThreadName());
        entries.put("State", info.getThreadState().toString());
        if (info.getLockName() != null) {
            entries.put("Waiting on", info.getLockName());
        }
        if (info.getLockOwnerName() != null) {
            entries.put("Lock owner", info.getLockOwnerName() + " (#" + info.getLockOwnerId() + ")");
        }
        StackTraceElement suspect = topSuspectFrame(info.getStackTrace(), frameworkPrefixes);
        if (suspect != null) {
            entries.put(FRAME_KEY, FrameLabels.display(suspect.getClassName() + "." + suspect.getMethodName(),
                suspect.getLineNumber()));
        }
        String stallMs = event.attrs().get("stallMs");
        if (stallMs != null) {
            entries.put("Stalled for", stallMs + " ms");
        }
        return new DiagnosticSection(title, entries, renderStack(info.getStackTrace()));
    }

    static StackTraceElement topSuspectFrame(StackTraceElement[] stack, Collection<String> frameworkPrefixes) {
        StackTraceElement lockSite = null;
        for (StackTraceElement frame : stack) {
            String cls = frame.getClassName();
            if (cls.startsWith("java.") || cls.startsWith("jdk.") || cls.startsWith("sun.")
                || cls.startsWith("io.github.xytronix.hybox.")) {
                continue;
            }
            if (lockSite == null) {
                lockSite = frame;
            }
            if (!matchesPrefix(cls, frameworkPrefixes)) {
                return frame;
            }
        }
        return lockSite;
    }

    private static boolean matchesPrefix(String className, Collection<String> prefixes) {
        if (prefixes == null) {
            return false;
        }
        for (String prefix : prefixes) {
            if (prefix != null && !prefix.isEmpty() && className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String renderStack(StackTraceElement[] stack) {
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement frame : stack) {
            sb.append("at ").append(frame).append(FrameLabels.suffix(frame)).append('\n');
        }
        return sb.toString().stripTrailing();
    }
}
