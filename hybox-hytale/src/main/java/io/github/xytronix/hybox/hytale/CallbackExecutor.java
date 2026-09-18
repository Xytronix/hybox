package io.github.xytronix.hybox.hytale;

import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

final class CallbackExecutor implements AutoCloseable {
    private static final long CALL_NANOS = TimeUnit.MILLISECONDS.toNanos(100);
    private static final long COLLECTION_NANOS = TimeUnit.MILLISECONDS.toNanos(500);
    private final Set<Token> tokens = ConcurrentHashMap.newKeySet();
    private final Object lifecycle = new Object();
    private final AtomicLong calls = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong timeouts = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicInteger quarantined = new AtomicInteger();
    private final Semaphore slots = new Semaphore(2);
    private final ThreadPoolExecutor workers;
    private volatile boolean closed;
    private volatile String lastFailure;

    CallbackExecutor() {
        var sequence = new AtomicInteger();
        workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(2), task -> {
            Thread thread = new Thread(task, "hybox-callback-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    long deadline() {
        return System.nanoTime() + COLLECTION_NANOS;
    }

    <T> T invoke(Token token, Callable<T> callback, long collectionDeadline) {
        long remaining = Math.min(CALL_NANOS, collectionDeadline - System.nanoTime());
        if (closed || remaining <= 0 || Thread.currentThread().isInterrupted()) {
            return null;
        }
        long callDeadline = System.nanoTime() + remaining;
        FutureTask<T> task;
        synchronized (token) {
            if (closed || !token.active || token.quarantined) {
                return null;
            }
            if (token.inFlight != null) {
                rejected.incrementAndGet();
                return null;
            }
            if (!slots.tryAcquire()) {
                rejected.incrementAndGet();
                if (lastFailure == null) {
                    lastFailure = "Callback capacity exhausted";
                }
                return null;
            }
            token.executor = this;
            tokens.add(token);
            task = task(() -> closed || !token.active() || System.nanoTime() - callDeadline >= 0
                ? null : callback.call());
            token.inFlight = task;
            try {
                synchronized (lifecycle) {
                    if (closed) {
                        token.close();
                        return null;
                    }
                    workers.execute(task);
                    calls.incrementAndGet();
                }
            } catch (RejectedExecutionException e) {
                task.cancel(false);
                token.inFlight = null;
                rejected.incrementAndGet();
                if (lastFailure == null) {
                    lastFailure = "Callback capacity exhausted";
                }
                return null;
            }
        }
        try {
            T result = task.get(Math.max(0, callDeadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            synchronized (token) {
                if (closed || !token.active || token.quarantined) {
                    return null;
                }
                if (System.nanoTime() - callDeadline >= 0) {
                    quarantine(token, task);
                    return null;
                }
                return result;
            }
        } catch (TimeoutException e) {
            synchronized (token) {
                if (!closed && token.active) {
                    quarantine(token, task);
                }
            }
            return null;
        } catch (InterruptedException e) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            return null;
        } catch (CancellationException e) {
            return null;
        } catch (ExecutionException e) {
            failures.incrementAndGet();
            lastFailure = token.label + ": " + e.getCause().getClass().getSimpleName();
            return null;
        } finally {
            synchronized (token) {
                if (token.inFlight == task) {
                    token.inFlight = null;
                }
            }
        }
    }

    private <T> FutureTask<T> task(Callable<T> callback) {
        var released = new AtomicBoolean();
        Runnable release = () -> {
            if (released.compareAndSet(false, true)) {
                slots.release();
            }
        };
        return new FutureTask<>(() -> {
            try {
                return callback.call();
            } finally {
                release.run();
            }
        }) {
            private boolean started;

            @Override
            public void run() {
                synchronized (this) {
                    started = true;
                }
                try {
                    super.run();
                } finally {
                    release.run();
                }
            }

            @Override
            protected synchronized void done() {
                if (!started) {
                    release.run();
                }
            }
        };
    }

    private void quarantine(Token token, FutureTask<?> task) {
        if (!token.quarantined) {
            token.quarantined = true;
            quarantined.incrementAndGet();
            timeouts.incrementAndGet();
            lastFailure = token.label + ": callback deadline exceeded";
        }
        task.cancel(true);
    }

    Health health() {
        return new Health(calls.get(), failures.get(), timeouts.get(), rejected.get(), quarantined.get(),
            workers.getActiveCount(), lastFailure);
    }

    @Override
    public void close() {
        synchronized (lifecycle) {
            closed = true;
            workers.shutdownNow();
        }
        for (Token token : tokens) {
            token.close();
        }
    }

    record Health(long calls, long failures, long timeouts, long rejected, int quarantined, int active,
                  String lastFailure) {
    }

    static final class Token implements AutoCloseable {
        private final String label;

        Token(String label) {
            StringBuilder sanitized = new StringBuilder(Math.min(label.length(), 120));
            for (int i = 0; i < label.length() && sanitized.length() < 120; i++) {
                char value = label.charAt(i);
                sanitized.append(Character.isISOControl(value) ? ' ' : value);
            }
            this.label = sanitized.toString();
        }

        private volatile boolean active = true;
        private boolean quarantined;
        private CallbackExecutor executor;
        private FutureTask<?> inFlight;

        boolean active() {
            return active;
        }

        @Override
        public synchronized void close() {
            active = false;
            if (inFlight != null) {
                inFlight.cancel(true);
            }
            if (executor != null) {
                executor.tokens.remove(this);
                if (quarantined) {
                    quarantined = false;
                    executor.quarantined.decrementAndGet();
                }
            }
        }
    }
}
