package io.github.xytronix.hybox.hytale;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CallbackExecutorTest {
    @Test
    void timeoutQuarantinesRegistrationWhileHealthyCallbacksContinue() throws Exception {
        var release = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        try (var callbacks = new CallbackExecutor()) {
            var stuck = new CallbackExecutor.Token("plugin\n/diagnostics/status");
            assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertNull(callbacks.invoke(stuck, () -> block(release, exited), callbacks.deadline())));
            assertEquals(1, callbacks.health().timeouts());
            assertEquals(1, callbacks.health().quarantined());
            assertTrue(callbacks.health().lastFailure().startsWith("plugin /diagnostics/status:"));
            assertFalse(callbacks.health().lastFailure().contains("\n"));
            assertNull(callbacks.invoke(stuck, () -> "late", callbacks.deadline()));
            assertEquals("healthy", callbacks.invoke(new CallbackExecutor.Token("test/callback"), () -> "healthy", callbacks.deadline()));
            assertNull(callbacks.invoke(new CallbackExecutor.Token("test/callback"), () -> {
                throw new IllegalStateException("broken");
            }, callbacks.deadline()));
            assertEquals(1, callbacks.health().failures());
            assertTrue(callbacks.health().lastFailure().contains("test/callback"));
            stuck.close();
            assertEquals(0, callbacks.health().quarantined());
        } finally {
            release.countDown();
            assertTrue(exited.await(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void exhaustedWorkersRejectWithoutQueueingOrSpawningReplacements() throws Exception {
        var release = new CountDownLatch(1);
        var exited = new CountDownLatch(2);
        var unexpected = new AtomicInteger();
        try (var callbacks = new CallbackExecutor()) {
            for (int i = 0; i < 2; i++) {
                assertNull(callbacks.invoke(new CallbackExecutor.Token("test/callback"), () -> block(release, exited), callbacks.deadline()));
            }
            String failure = callbacks.health().lastFailure();
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
                for (int i = 0; i < 100; i++) {
                    var token = new CallbackExecutor.Token("test/callback");
                    assertNull(callbacks.invoke(token, unexpected::incrementAndGet, callbacks.deadline()));
                    token.close();
                }
                assertEquals(2, callbacks.health().active());
            });
            assertEquals(100, callbacks.health().rejected());
            assertEquals(2, callbacks.health().calls());
            assertEquals(failure, callbacks.health().lastFailure());
            release.countDown();
            assertTrue(exited.await(2, TimeUnit.SECONDS));
            assertEquals(0, unexpected.get());
        } finally {
            release.countDown();
        }
    }

    @Test
    void collectionDeadlinePreventsLaterCallbacksFromStarting() {
        var calls = new AtomicInteger();
        try (var callbacks = new CallbackExecutor()) {
            assertNull(callbacks.invoke(new CallbackExecutor.Token("test/callback"), calls::incrementAndGet, System.nanoTime() - 1));
            assertEquals(0, calls.get());
        }
    }

    @Test
    void closingExecutorCancelsWaitersAndDiscardsLateResults() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        var callbacks = new CallbackExecutor();
        try (var callers = Executors.newSingleThreadExecutor()) {
            var result = callers.submit(() -> callbacks.invoke(new CallbackExecutor.Token("test/callback"), () -> {
                entered.countDown();
                return block(release, exited);
            }, callbacks.deadline()));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            callbacks.close();
            assertNull(result.get(1, TimeUnit.SECONDS));
            assertNull(callbacks.invoke(new CallbackExecutor.Token("test/callback"), () -> "after close", callbacks.deadline()));
            assertEquals(0, callbacks.health().quarantined());
        } finally {
            callbacks.close();
            release.countDown();
            assertTrue(exited.await(2, TimeUnit.SECONDS));
        }
    }

    private static String block(CountDownLatch release, CountDownLatch exited) {
        try {
            while (release.getCount() != 0) {
                try {
                    release.await();
                } catch (InterruptedException ignored) {
                }
            }
            return "late";
        } finally {
            exited.countDown();
        }
    }
}
