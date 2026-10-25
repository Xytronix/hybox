package io.github.xytronix.hybox.hytale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.xytronix.hybox.core.bundle.BundleAttachment;
import io.github.xytronix.hybox.core.incident.DiagnosticSection;
import io.github.xytronix.hybox.core.incident.IncidentId;
import io.github.xytronix.hybox.core.incident.IncidentMetadata;
import io.github.xytronix.hybox.core.incident.IncidentReport;
import io.github.xytronix.hybox.core.incident.Severity;
import io.github.xytronix.hybox.core.trigger.heartbeat.HeartbeatRegistry;

class HytaleBundleExtrasProviderTest {

    @Test
    void classPresenceCheckDoesNotInitializeClass() {
        InitMarker.INITIALIZED.set(false);
        String className = InitProbe.class.getName();

        boolean present = HytaleBundleExtrasProvider.isClassPresent(
            className,
            HytaleBundleExtrasProviderTest.class.getClassLoader()
        );

        assertTrue(present);
        assertFalse(InitMarker.INITIALIZED.get(), "Class initialization should not run.");
    }

    @Test
    void classPresenceCheckReturnsFalseForMissingClass() {
        boolean present = HytaleBundleExtrasProvider.isClassPresent(
            "not.present.ClassName",
            HytaleBundleExtrasProviderTest.class.getClassLoader()
        );
        assertFalse(present);
    }

    @Test
    void classPresenceCheckGuardsThrowableFromLoader() {
        ClassLoader throwingLoader = new ThrowingLoader(HytaleBundleExtrasProviderTest.class.getClassLoader());
        boolean present = HytaleBundleExtrasProvider.isClassPresent("boom.Broken", throwingLoader);
        assertFalse(present);
    }

    @Test
    void readTailDropsOversizedPartialLine(@TempDir Path tempDir) throws Exception {
        Path log = tempDir.resolve("latest.log");
        String prefix = "x".repeat(20_000);
        String suffix = "::THE_END::";
        Files.writeString(log, prefix + suffix, StandardCharsets.UTF_8);

        String tail = HytaleBundleExtrasProvider.readTail(log, 1, 64);

        assertEquals("", tail);
    }

    @Test
    void readTailKeepsLineBehaviorWhenWithinByteBound(@TempDir Path tempDir) throws Exception {
        Path log = tempDir.resolve("lines.log");
        Files.writeString(log, "a\nb\nc\n", StandardCharsets.UTF_8);

        String tail = HytaleBundleExtrasProvider.readTail(log, 2, 1024);

        assertEquals("b\nc\n", tail);
    }

    @Test
    void readTailAlignsUtf8ByteBoundToNextCompleteLine(@TempDir Path tempDir) throws Exception {
        Path log = tempDir.resolve("utf8.log");
        Files.writeString(log, "discardé\nkept 世界\nlast", StandardCharsets.UTF_8);
        int maxBytes = "é\nkept 世界\nlast".getBytes(StandardCharsets.UTF_8).length - 1;

        String tail = HytaleBundleExtrasProvider.readTail(log, 10, maxBytes);

        assertEquals("kept 世界\nlast", tail);
        assertTrue(tail.getBytes(StandardCharsets.UTF_8).length <= maxBytes);
    }

    @Test
    void readTailKeepsLineStartingExactlyAtByteBound(@TempDir Path tempDir) throws Exception {
        Path log = tempDir.resolve("boundary.log");
        Files.writeString(log, "discard\n世界\nlast", StandardCharsets.UTF_8);
        int maxBytes = "世界\nlast".getBytes(StandardCharsets.UTF_8).length;

        assertEquals("世界\nlast", HytaleBundleExtrasProvider.readTail(log, 2, maxBytes));
        assertEquals("last", HytaleBundleExtrasProvider.readTail(log, 1, maxBytes));
    }

    @Test
    void historicalMetricsKeepOnlyBoundedCompleteRowsFromLatestTwoFiles(@TempDir Path metricsDir)
        throws Exception {
        String row = "2026-10-22T12:00:00Z,世界,42\n";
        String content = "timestamp,world,tickMillis\n" + row.repeat(40_000);
        Files.writeString(metricsDir.resolve("health-2026-10-20.csv"), "old\n", StandardCharsets.UTF_8);
        Files.writeString(metricsDir.resolve("health-2026-10-21.csv"), "previous\n", StandardCharsets.UTF_8);
        Files.writeString(metricsDir.resolve("health-2026-10-22.csv"), content, StandardCharsets.UTF_8);
        Files.writeString(metricsDir.resolve("not-health.csv"), "unrelated\n", StandardCharsets.UTF_8);
        HytaleBundleExtrasProvider provider = new HytaleBundleExtrasProvider(
            new HeartbeatRegistry(Clock.systemUTC()), () -> 0, () -> true, metricsDir,
            new PlayerNameMasker(), () -> List.of(), () -> true);

        List<BundleAttachment> extras = provider.historicalExtras();

        assertEquals(2, extras.size());
        assertEquals("previous\n", contentOf(extras, "extras/metrics/health-2026-10-21.csv"));
        String tail = contentOf(extras, "extras/metrics/health-2026-10-22.csv");
        assertTrue(tail.getBytes(StandardCharsets.UTF_8).length <= 1024 * 1024);
        assertFalse(tail.isEmpty());
        assertEquals(row.repeat(tail.length() / row.length()), tail);
    }

    @Test
    void serverLogTailReusesCapturedDiagnosticsWithoutReadingTheCurrentLog() throws Exception {
        String capturedTail = "2026-10-22T12:00:00Z captured at incident time\n";
        IncidentReport report = new IncidentReport(
            new IncidentMetadata(new IncidentId("capture"), Instant.now(), Severity.DEGRADED,
                "MANUAL", null, "Manual capture"),
            Map.of(), null,
            List.of(new DiagnosticSection("Server log", Map.of(), capturedTail)));
        HytaleBundleExtrasProvider provider = new HytaleBundleExtrasProvider(
            new HeartbeatRegistry(Clock.systemUTC()), () -> 500, () -> true, null,
            new PlayerNameMasker(), () -> List.of(), () -> false);

        var readTail = HytaleBundleExtrasProvider.class.getDeclaredMethod("buildServerLogTail", IncidentReport.class);
        readTail.setAccessible(true);

        assertEquals(capturedTail, readTail.invoke(provider, report));
    }

    @Test
    void emitsNumberedThreadDumpsWhenMultipleCaptured() {
        List<BundleAttachment> extras =
            HytaleBundleExtrasProvider.threadDumpAttachments(List.of("dump-A", "dump-B", "dump-C"), true);

        assertEquals("dump-A", contentOf(extras, "extras/threads-1.txt"));
        assertEquals("dump-B", contentOf(extras, "extras/threads-2.txt"));
        assertEquals("dump-C", contentOf(extras, "extras/threads-3.txt"));
        assertFalse(hasPath(extras, "extras/threads.txt"));
    }

    @Test
    void usesSingleCapturedDumpAsThreadsTxt() {
        List<BundleAttachment> extras =
            HytaleBundleExtrasProvider.threadDumpAttachments(List.of("only-dump"), false);

        assertEquals("only-dump", contentOf(extras, "extras/threads.txt"));
    }

    @Test
    void capturesLiveThreadDumpWhenNoneProvided() {
        List<BundleAttachment> extras =
            HytaleBundleExtrasProvider.threadDumpAttachments(List.of(), true);

        assertTrue(contentOf(extras, "extras/threads.txt").startsWith("threads="));
    }

    @Test
    void takesNoLiveThreadDumpWhenDumpsAreDisabled() {
        assertTrue(HytaleBundleExtrasProvider.threadDumpAttachments(List.of(), false).isEmpty());
    }

    private static String contentOf(List<BundleAttachment> extras, String path) {
        return extras.stream()
            .filter(a -> a.pathInZip().equals(path))
            .map(a -> new String(a.data(), StandardCharsets.UTF_8))
            .findFirst()
            .orElseThrow();
    }

    private static boolean hasPath(List<BundleAttachment> extras, String path) {
        return extras.stream().anyMatch(a -> a.pathInZip().equals(path));
    }

    private static final class InitMarker {
        private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);
    }

    private static final class InitProbe {
        static {
            InitMarker.INITIALIZED.set(true);
        }
    }

    private static final class ThrowingLoader extends ClassLoader {
        private ThrowingLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if ("boom.Broken".equals(name)) {
                throw new LinkageError("simulated linkage failure");
            }
            return super.loadClass(name, resolve);
        }
    }
}
