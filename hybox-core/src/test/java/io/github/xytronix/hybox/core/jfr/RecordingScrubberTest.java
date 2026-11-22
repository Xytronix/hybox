package io.github.xytronix.hybox.core.jfr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import io.github.xytronix.hybox.core.env.PathMasker;
import io.github.xytronix.hybox.core.env.TextRedactor;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordingScrubberTest {
    private static final String LISTENER = "quiche-listener-203.0.113.9/203.0.113.9:47613";
    private static final String MASKED_LISTENER = "quiche-listener-" + "*".repeat(29);
    private static final List<String> SECRETS = List.of("203.0.113.9", "47613", "fakeuser", "FakePlayer");
    private static final List<UnaryOperator<String>> MASKERS = List.of(
        new PathMasker("/home/fakeuser", "/srv/fake"),
        new TextRedactor(TextRedactor.DEFAULT_PATTERNS)::redact,
        text -> text.replace("FakePlayer", "player-1"));
    private static final Predicate<String> SENSITIVE =
        text -> MASKERS.stream().anyMatch(masker -> !masker.apply(text).equals(text));

    @Test
    void maskedRecordingKeepsThreadDumpAndSamplesOfAddressThread(@TempDir Path tempDir) throws Exception {
        Path source = tempDir.resolve("source.jfr");
        CountDownLatch stop = new CountDownLatch(1);
        Thread listener = new Thread(() -> {
            plugin("FakePlayer joined");
            while (stop.getCount() > 0) {
                Thread.onSpinWait();
            }
        }, LISTENER);
        try (Recording jfr = new Recording()) {
            jfr.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10));
            jfr.enable("jdk.ThreadDump").with("period", "endChunk");
            jfr.enable("jdk.InitialSystemProperty");
            jfr.enable(HyboxPluginEvent.class).withoutStackTrace();
            jfr.start();
            listener.start();
            try {
                plugin("/home/fakeuser/server/universe/worlds/default");
                Thread.sleep(800);
                jfr.stop();
            } finally {
                stop.countDown();
                listener.join();
            }
            jfr.dump(source);
        }
        String raw = Files.readString(source, StandardCharsets.ISO_8859_1);
        assertTrue(raw.contains("203.0.113.9") && raw.contains("FakePlayer") && raw.contains("fakeuser"));
        long sourceSamples = listenerSamples(source);
        assertTrue(sourceSamples > 0, "no samples of the listener thread were recorded");

        Path target = tempDir.resolve("shared.jfr");
        RecordingScrubber.Result result = new RecordingScrubber(SENSITIVE, MASKERS).write(source, target);

        assertNull(result.fallback(), result.fallback());
        assertTrue(result.maskedStrings() > 0);
        assertTrue(result.dropped().containsKey("jdk.InitialSystemProperty"), result.dropped().toString());
        assertNoSecrets(target);
        assertEquals(sourceSamples, listenerSamples(target));
        List<String> dumps = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        for (RecordedEvent event : RecordingFile.readAllEvents(target)) {
            if (event.getEventType().getName().equals("jdk.ThreadDump")) {
                dumps.add(event.getString("result"));
            } else if (event.hasField("message")) {
                messages.add(event.getString("message"));
            }
        }
        assertFalse(dumps.isEmpty(), "thread dump was left out");
        assertTrue(dumps.stream().anyMatch(dump -> dump.contains("\"" + MASKED_LISTENER + "\"")), dumps.get(0));
        assertEquals(List.of("********** joined", "**************/server/universe/worlds/default"),
            messages.stream().sorted().toList());
    }

    @Test
    void unreadableMaskedCopyFallsBackToLeavingOutPrivateEvents(@TempDir Path tempDir) throws Exception {
        Path source = tempDir.resolve("source.jfr");
        try (Recording jfr = new Recording()) {
            jfr.enable("jdk.InitialSystemProperty");
            jfr.enable(HyboxPluginEvent.class).withoutStackTrace();
            jfr.start();
            plugin("/home/fakeuser/server/universe/worlds/default");
            plugin("FakePlayer joined");
            plugin("kept event");
            Thread listener = new Thread(() -> plugin("listener event"), LISTENER);
            listener.start();
            listener.join();
            jfr.dump(source);
        }
        Path target = tempDir.resolve("shared.jfr");
        RecordingScrubber scrubber = new RecordingScrubber(SENSITIVE, MASKERS, (hidden, from, to) -> {
            byte[] bytes = Files.readAllBytes(from);
            Files.write(to, Arrays.copyOf(bytes, bytes.length / 2));
            return hidden.keySet();
        });

        RecordingScrubber.Result result = scrubber.write(source, target);

        assertNotNull(result.fallback());
        assertNotNull(result.failure());
        assertEquals(0, result.maskedStrings());
        assertEquals(3L, result.dropped().get(HyboxPluginEvent.NAME),
            result.dropped().toString());
        assertNoSecrets(target);
        List<String> messages = new ArrayList<>();
        for (RecordedEvent event : RecordingFile.readAllEvents(target)) {
            assertFalse(event.getEventType().getName().startsWith("jdk.Initial"), event.getEventType().getName());
            if (event.hasField("message")) {
                messages.add(event.getString("message"));
            }
        }
        assertEquals(List.of("kept event"), messages);
    }

    @Test
    void masksNonAsciiNamesInEveryStringEncoding(@TempDir Path tempDir) throws Exception {
        List<String> names = List.of("Jörg", "Łukasz");
        List<UnaryOperator<String>> maskers = List.of(
            text -> text.replace("Jörg", "player-1").replace("Łukasz", "player-2"));
        Predicate<String> sensitive = text -> !maskers.get(0).apply(text).equals(text);
        Path source = tempDir.resolve("source.jfr");
        try (Recording jfr = new Recording()) {
            jfr.enable(HyboxPluginEvent.class).withoutStackTrace();
            jfr.start();
            for (String name : names) {
                plugin(name + " joined");
                plugin(name + " joined the server at the spawn point");
            }
            jfr.dump(source);
        }
        Path target = tempDir.resolve("shared.jfr");

        RecordingScrubber.Result result = new RecordingScrubber(sensitive, maskers).write(source, target);

        assertNull(result.fallback(), result.fallback());
        assertEquals(4, result.maskedStrings());
        byte[] raw = Files.readAllBytes(target);
        for (String name : names) {
            for (byte[] form : List.of(name.getBytes(StandardCharsets.UTF_8), name.getBytes(StandardCharsets.UTF_16BE),
                name.substring(1).getBytes(StandardCharsets.UTF_8))) {
                assertEquals(-1, indexOf(raw, form), name);
            }
        }
        List<String> messages = new ArrayList<>();
        for (RecordedEvent event : RecordingFile.readAllEvents(target)) {
            messages.add(event.getString("message"));
        }
        assertEquals(4, messages.size(), messages.toString());
        for (String message : messages) {
            assertTrue(message.matches("[*\u2217\uFF0A]+ joined.*"), message);
        }
    }

    private static int indexOf(byte[] data, byte[] part) {
        for (int i = 0; i + part.length <= data.length; i++) {
            if (Arrays.equals(data, i, i + part.length, part, 0, part.length)) {
                return i;
            }
        }
        return -1;
    }

    private static long listenerSamples(Path recording) throws Exception {
        long samples = 0;
        for (RecordedEvent event : RecordingFile.readAllEvents(recording)) {
            if (event.getEventType().getName().equals("jdk.ExecutionSample")
                && event.getThread("sampledThread").getJavaName().startsWith("quiche-listener-")) {
                samples++;
            }
        }
        return samples;
    }

    private static void assertNoSecrets(Path recording) throws Exception {
        String raw = Files.readString(recording, StandardCharsets.ISO_8859_1);
        for (String secret : SECRETS) {
            assertFalse(raw.contains(secret), secret);
        }
    }

    private static void plugin(String message) {
        HyboxPluginEvent event = new HyboxPluginEvent();
        event.category = "test";
        event.message = message;
        event.commit();
    }
}
