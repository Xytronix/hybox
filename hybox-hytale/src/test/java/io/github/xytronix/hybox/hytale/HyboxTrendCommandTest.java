package io.github.xytronix.hybox.hytale;

import static org.junit.jupiter.api.Assertions.*;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import io.github.xytronix.hybox.core.metrics.MetricsLog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sun.misc.Unsafe;

class HyboxTrendCommandTest {
    @TempDir Path directory;

    @Test
    void consoleStillWritesTheHtmlReportOnTheWorkerWithTheOriginalChatLine() throws Exception {
        Unsafe unsafe = unsafe();
        var runtime = (HyboxRuntime) unsafe.allocateInstance(HyboxRuntime.class);
        Instant now = Instant.parse("2026-10-25T12:00:00Z");
        var worker = Executors.newSingleThreadExecutor();
        try {
            set(runtime, "worker", worker);
            set(runtime, "clock", Clock.fixed(now, ZoneOffset.UTC));
            set(runtime, "dataDir", directory);
            set(runtime, "incidentDir", directory.resolve("incidents"));
            set(runtime, "worldStats", new HytaleWorldStatsProvider(new PlayerNameMasker()));
            var log = new MetricsLog(directory.resolve("metrics"));
            log.append(now, MetricsLog.formatRow(now, 12, 20, 4, 1000000, 2000000, 0.4, 25, -1, 2, MetricsLog.Load.NONE), 30);
            var command = new HyboxCommand(runtime).getSubCommand("trend");
            List<Message> messages = new ArrayList<>();
            var sender = new Sender(messages);
            var context = new CommandContext(command, sender, "hybox trend");
            assertFalse(context.isPlayer());
            execute(command, context).get(10, TimeUnit.SECONDS);
            Path html = directory.resolve("trend-report.html");
            assertTrue(Files.readString(html).contains("Hybox Health Trends · 7d"));
            assertEquals(1, messages.size());
            assertTrue(plain(messages.getFirst()).contains("Trend for 7 days saved: " + html));
            assertFalse(plain(messages.getFirst()).replace(html.toString(), "").toLowerCase(java.util.Locale.ROOT).contains("report"));
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    void aPlayerWithNoLiveEntityDoesNotFallThroughToHtmlGeneration() throws Exception {
        Unsafe unsafe = unsafe();
        var runtime = (HyboxRuntime) unsafe.allocateInstance(HyboxRuntime.class);
        AtomicInteger submitted = new AtomicInteger();
        var worker = Executors.newSingleThreadExecutor(task -> {
            submitted.incrementAndGet();
            return new Thread(task);
        });
        try {
            set(runtime, "worker", worker);
            var player = (PlayerRef) unsafe.allocateInstance(PlayerRef.class);
            var command = new HyboxCommand(runtime).getSubCommand("trend");
            var context = new CommandContext(command, player, "hybox trend");
            assertTrue(context.isPlayer());
            execute(command, context).get(10, TimeUnit.SECONDS);
            assertEquals(0, submitted.get());
            assertFalse(Files.exists(directory.resolve("trend-report.html")));
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    void playerListTriggersAndHistogramOpenTheDashboardInsteadOfWalkingTheHeapOrPrintingChat() throws Exception {
        Unsafe unsafe = unsafe();
        var runtime = (HyboxRuntime) unsafe.allocateInstance(HyboxRuntime.class);
        AtomicInteger submitted = new AtomicInteger();
        var worker = Executors.newSingleThreadExecutor(task -> {
            submitted.incrementAndGet();
            return new Thread(task);
        });
        try {
            set(runtime, "worker", worker);
            var root = new HyboxCommand(runtime);
            var player = (PlayerRef) unsafe.allocateInstance(PlayerRef.class);
            for (String name : List.of("list", "triggers", "histogram")) {
                var command = root.getSubCommand(name);
                var context = new CommandContext(command, player, "hybox " + name);
                assertTrue(context.isPlayer());
                try {
                    execute(command, context).get(10, TimeUnit.SECONDS);
                    fail(name + " should reach the dashboard, whose fallback cannot message a disconnected player");
                } catch (java.lang.reflect.InvocationTargetException reached) {
                    assertTrue(java.util.Arrays.stream(reached.getCause().getStackTrace()).anyMatch(frame ->
                        frame.getClassName().equals(HyboxDashboardPage.class.getName()) && frame.getMethodName().equals("open")), name);
                }
            }
            assertEquals(0, submitted.get());
            assertNull(runtime.lastHeap());
        } finally {
            worker.shutdownNow();
        }
    }

    @SuppressWarnings("unchecked")
    private static CompletableFuture<Void> execute(Object command, CommandContext context) throws Exception {
        var method = command.getClass().getDeclaredMethod("executeAsync", CommandContext.class);
        method.setAccessible(true);
        return (CompletableFuture<Void>) method.invoke(command, context);
    }

    private static void set(HyboxRuntime runtime, String name, Object value) throws Exception {
        var field = HyboxRuntime.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(runtime, value);
    }

    private static Unsafe unsafe() throws Exception {
        var field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }

    private static String plain(Message message) {
        StringBuilder text = new StringBuilder(message.getRawText() == null ? "" : message.getRawText());
        for (Message child : message.getChildren()) text.append(plain(child));
        return text.toString();
    }

    private record Sender(List<Message> messages) implements CommandSender {
        @Override public String getUsername() { return "console-trend-test"; }
        @Override public UUID getUuid() { return new UUID(0, 4); }
        @Override public boolean hasPermission(String permission) { return true; }
        @Override public boolean hasPermission(String permission, boolean defaultValue) { return true; }
        @Override public void sendMessage(Message message) { messages.add(message); }
    }
}
