package io.github.xytronix.hybox.hytale;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import com.hypixel.hytale.server.core.universe.world.World;

final class HytaleWorldThreads {
    private HytaleWorldThreads() {
    }

    static <T> CompletableFuture<T> submit(World world, Supplier<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        if (world.isInThread()) {
            run(future, task);
            return future;
        }
        try {
            world.execute(() -> {
                if (!future.isDone()) {
                    run(future, task);
                }
            });
        } catch (RuntimeException e) {
            future.cancel(false);
        }
        return future;
    }

    static <T> T await(CompletableFuture<T> future, long deadlineNanos) throws ExecutionException {
        try {
            return future.get(Math.max(0L, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException | CancellationException e) {
            future.cancel(false);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(false);
            return null;
        }
    }

    private static <T> void run(CompletableFuture<T> future, Supplier<T> task) {
        try {
            future.complete(task.get());
        } catch (Throwable t) {
            future.completeExceptionally(t);
        }
    }
}
