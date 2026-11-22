package io.github.xytronix.hybox.core.env;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;

class ThreadDumperTest {

    @Test
    void dumpListsThreadsWithHeaderAndStackFrames() {
        String dump = ThreadDumper.dump();
        assertTrue(dump.startsWith("threads="), dump);
        assertTrue(dump.contains(Thread.currentThread().getName()), dump);
        assertTrue(dump.contains("state="), dump);
        assertTrue(dump.contains("    at "), dump);
    }

    @Test
    void dumpKeepsMonitorOwnershipAndLockWaitersWithoutSynchronizerScan() throws Exception {
        Object monitor = new Object();
        ReentrantLock lock = new ReentrantLock();
        lock.lock();
        CountDownLatch waiting = new CountDownLatch(1);
        Thread waiter = new Thread(() -> {
            waiting.countDown();
            lock.lock();
            lock.unlock();
        }, "hybox-dump-waiter");
        waiter.setDaemon(true);
        try {
            waiter.start();
            assertTrue(waiting.await(5, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (waiter.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            String dump;
            synchronized (monitor) {
                dump = ThreadDumper.dump();
            }
            assertTrue(dump.contains("- locked java.lang.Object@"), dump);
            assertTrue(dump.contains("\"hybox-dump-waiter\""), dump);
            assertTrue(dump.contains("waiting on java.util.concurrent.locks.ReentrantLock"), dump);
            assertTrue(dump.contains("owned by \"" + Thread.currentThread().getName() + "\""), dump);
            assertFalse(dump.contains("Locked ownable synchronizers"), dump);
        } finally {
            lock.unlock();
            waiter.join(5_000);
        }
    }
}
