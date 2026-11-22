package io.github.xytronix.hybox.core.jfr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import jdk.jfr.ValueDescriptor;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedObject;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

public final class RecordingScrubber {
    public static final Set<String> DROPPED_EVENTS = Set.of(
        "jdk.InitialEnvironmentVariable",
        "jdk.InitialSecurityProperty",
        "jdk.InitialSystemProperty",
        "jdk.JavaAgent",
        "jdk.NativeAgent",
        "jdk.ProcessStart",
        "jdk.SocketRead",
        "jdk.SocketWrite",
        "jdk.SystemProcess",
        "jdk.TLSHandshake");
    private static final Set<String> SKIPPED_TYPES = Set.of(
        "jdk.types.StackTrace", "java.lang.Class", "jdk.types.Method", "jdk.types.StackFrame");
    private static final int MAX_DEPTH = 4;
    private static final BitSet CLEAN = new BitSet();
    private static final List<String> JVM_VERSIONS = Stream.of("java.version", "java.runtime.version",
        "java.vm.version").map(System::getProperty).toList();

    public record Result(int maskedStrings, Map<String, Long> dropped, String fallback, Exception failure) {
    }

    interface Patcher {
        Set<String> patch(Map<String, BitSet> hidden, Path source, Path target) throws IOException;
    }

    private final Predicate<String> sensitive;
    private final PrivateText privateText;
    private final Patcher patcher;
    private final Map<String, BitSet> scanned = new HashMap<>();
    private final Map<String, Boolean> verdicts = new HashMap<>();

    public RecordingScrubber(Predicate<String> sensitive, List<UnaryOperator<String>> maskers) {
        this(sensitive, maskers, StringPatcher::patch);
    }

    RecordingScrubber(Predicate<String> sensitive, List<UnaryOperator<String>> maskers, Patcher patcher) {
        this.sensitive = Objects.requireNonNull(sensitive, "sensitive");
        this.privateText = new PrivateText(Objects.requireNonNull(maskers, "maskers"), JVM_VERSIONS);
        this.patcher = Objects.requireNonNull(patcher, "patcher");
    }

    public Result write(Path source, Path target) throws IOException {
        Map<String, Long> counts = scan(source);
        Map<String, BitSet> hidden = new HashMap<>();
        scanned.forEach((text, chars) -> {
            if (chars != CLEAN) {
                hidden.put(text, chars);
            }
        });
        Path directory = target.toAbsolutePath().getParent();
        Path masked = Files.createTempFile(directory, ".hybox-", ".masked.jfr.tmp");
        try {
            Set<String> found;
            Map<String, Long> dropped = new TreeMap<>();
            Map<String, Long> seen;
            try {
                found = patcher.patch(hidden, source, masked);
                seen = filter(masked, target, dropped);
            } catch (IOException | RuntimeException e) {
                return fallback(source, target, "the masked copy could not be read", e);
            }
            if (!seen.equals(counts)) {
                return fallback(source, target, "the masked copy has " + total(seen) + " events instead of "
                    + total(counts), null);
            }
            return new Result(found.size(), dropped, null, null);
        } finally {
            Files.deleteIfExists(masked);
        }
    }

    private Map<String, Long> scan(Path source) throws IOException {
        Map<String, Long> counts = new TreeMap<>();
        Map<RecordedThread, Boolean> threads = new IdentityHashMap<>();
        Predicate<String> collect = text -> {
            scanned.computeIfAbsent(text, this::hiddenChars);
            return true;
        };
        try (RecordingFile file = new RecordingFile(source)) {
            while (file.hasMoreEvents()) {
                RecordedEvent event = file.readEvent();
                String name = event.getEventType().getName();
                counts.merge(name, 1L, Long::sum);
                if (!DROPPED_EVENTS.contains(name)) {
                    visit(event, collect, threads, 0);
                }
            }
        }
        return counts;
    }

    private BitSet hiddenChars(String text) {
        if (text.isEmpty()) {
            return CLEAN;
        }
        BitSet chars = privateText.find(text);
        if (chars != null && !chars.isEmpty()) {
            return chars;
        }
        return sensitive.test(privateText.withoutPublicTexts(text)) ? new BitSet() : CLEAN;
    }

    private Result fallback(Path source, Path target, String reason, Exception failure) throws IOException {
        Map<String, Long> dropped = new TreeMap<>();
        filter(source, target, dropped);
        return new Result(0, dropped, reason, failure);
    }

    private Map<String, Long> filter(Path source, Path target, Map<String, Long> dropped) throws IOException {
        dropped.clear();
        Files.write(target, new byte[0]);
        Map<String, Long> seen = new TreeMap<>();
        Map<RecordedThread, Boolean> threads = new IdentityHashMap<>();
        Predicate<String> clean = text -> !flagged(text);
        try (RecordingFile file = new RecordingFile(source)) {
            file.write(target, event -> {
                String name = event.getEventType().getName();
                seen.merge(name, 1L, Long::sum);
                if (DROPPED_EVENTS.contains(name) || !visit(event, clean, threads, 0)) {
                    dropped.merge(name, 1L, Long::sum);
                    return false;
                }
                return true;
            });
        }
        return seen;
    }

    private boolean flagged(String text) {
        BitSet chars = scanned.get(text);
        if (chars != null) {
            return chars != CLEAN;
        }
        return verdicts.computeIfAbsent(text, value -> sensitive.test(privateText.withoutPublicTexts(value)));
    }

    private boolean visit(RecordedObject object, Predicate<String> clean, Map<RecordedThread, Boolean> threads,
                          int depth) {
        for (ValueDescriptor field : object.getFields()) {
            String type = field.getTypeName();
            if (SKIPPED_TYPES.contains(type) || (field.getFields().isEmpty() && !"java.lang.String".equals(type))) {
                continue;
            }
            if (!visitValue(object.getValue(field.getName()), clean, threads, depth)) {
                return false;
            }
        }
        return true;
    }

    private boolean visitValue(Object value, Predicate<String> clean, Map<RecordedThread, Boolean> threads,
                               int depth) {
        if (value instanceof String text) {
            return clean.test(text);
        }
        if (value instanceof RecordedThread thread) {
            Boolean known = threads.get(thread);
            if (known == null) {
                known = visit(thread, clean, threads, depth + 1);
                threads.put(thread, known);
            }
            return known;
        }
        if (value instanceof RecordedObject nested) {
            return depth >= MAX_DEPTH || visit(nested, clean, threads, depth + 1);
        }
        if (value instanceof Object[] array) {
            for (Object element : array) {
                if (!visitValue(element, clean, threads, depth)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static long total(Map<String, Long> counts) {
        return counts.values().stream().mapToLong(Long::longValue).sum();
    }
}
