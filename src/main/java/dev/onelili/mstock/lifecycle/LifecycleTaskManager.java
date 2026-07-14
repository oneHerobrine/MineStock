package dev.onelili.mstock.lifecycle;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Owns asynchronous work that must not outlive a plugin enable cycle.
 * Regular work is cancelled on shutdown, while critical work is given a
 * bounded window to finish.
 */
public final class LifecycleTaskManager implements AutoCloseable {

    private static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);

    private final Object monitor = new Object();
    private final ExecutorService regularExecutor;
    private final ExecutorService criticalExecutor;
    private final Set<TrackedTask<?>> regularTasks = new HashSet<>();
    private final ConcurrentLinkedQueue<Runnable> deferredShutdownActions =
            new ConcurrentLinkedQueue<>();

    private boolean accepting = true;
    private boolean closed;
    private int criticalOperations;

    public LifecycleTaskManager(String threadNamePrefix) {
        this(threadNamePrefix, 2, 2);
    }

    LifecycleTaskManager(String threadNamePrefix, int regularThreads, int criticalThreads) {
        if (regularThreads < 1 || criticalThreads < 1) {
            throw new IllegalArgumentException("Thread counts must be positive");
        }
        regularExecutor = Executors.newFixedThreadPool(
                regularThreads, threadFactory(threadNamePrefix + "-async-"));
        criticalExecutor = Executors.newFixedThreadPool(
                criticalThreads, threadFactory(threadNamePrefix + "-settlement-"));
    }

    public boolean isAccepting() {
        synchronized (monitor) {
            return accepting;
        }
    }

    public CompletableFuture<Void> runAsync(Runnable task) {
        return supplyAsync(() -> {
            task.run();
            return null;
        });
    }

    public <T> CompletableFuture<T> supplyAsync(Callable<T> task) {
        TrackedTask<T> tracked = new TrackedTask<>();
        synchronized (monitor) {
            if (!accepting) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("MineStock is shutting down"));
            }
            regularTasks.add(tracked);
            try {
                tracked.worker = regularExecutor.submit(() -> executeRegular(tracked, task));
            } catch (RejectedExecutionException e) {
                regularTasks.remove(tracked);
                tracked.result.completeExceptionally(e);
            }
        }
        return tracked.result;
    }

    /**
     * Reserves a settlement slot before changing a player's economy balance.
     * The returned operation remains submit-capable while shutdown is waiting.
     */
    public CriticalOperation beginCritical() {
        synchronized (monitor) {
            if (!accepting) return null;
            criticalOperations++;
            return new CriticalOperation();
        }
    }

    public void deferUntilShutdown(Runnable action) {
        deferredShutdownActions.add(action);
    }

    public void cancelDeferredAction(Runnable action) {
        deferredShutdownActions.remove(action);
    }

    public void runDeferredShutdownActions(Consumer<Throwable> errorHandler) {
        Runnable action;
        while ((action = deferredShutdownActions.poll()) != null) {
            try {
                action.run();
            } catch (Throwable e) {
                errorHandler.accept(e);
            }
        }
    }

    public boolean shutdown(Duration timeout) {
        if (timeout.isNegative()) throw new IllegalArgumentException("timeout must not be negative");

        Set<TrackedTask<?>> tasksToCancel;
        synchronized (monitor) {
            if (closed) return criticalOperations == 0;
            accepting = false;
            tasksToCancel = new HashSet<>(regularTasks);
        }

        for (TrackedTask<?> tracked : tasksToCancel) tracked.cancel();
        regularExecutor.shutdownNow();

        long deadline = System.nanoTime() + timeout.toNanos();
        boolean criticalDrained;
        synchronized (monitor) {
            while (criticalOperations > 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                try {
                    TimeUnit.NANOSECONDS.timedWait(monitor, remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            criticalDrained = criticalOperations == 0;
            closed = true;
        }

        if (criticalDrained) criticalExecutor.shutdown();
        else criticalExecutor.shutdownNow();

        awaitExecutor(regularExecutor, deadline);
        awaitExecutor(criticalExecutor, deadline);
        return criticalDrained;
    }

    @Override
    public void close() {
        shutdown(DEFAULT_SHUTDOWN_TIMEOUT);
    }

    private <T> void executeRegular(TrackedTask<T> tracked, Callable<T> task) {
        try {
            if (!tracked.result.isCancelled()) tracked.result.complete(task.call());
        } catch (CancellationException e) {
            tracked.result.cancel(false);
        } catch (Throwable e) {
            tracked.result.completeExceptionally(e);
        } finally {
            synchronized (monitor) {
                regularTasks.remove(tracked);
            }
        }
    }

    private void finishCritical() {
        synchronized (monitor) {
            criticalOperations--;
            monitor.notifyAll();
        }
    }

    private static void awaitExecutor(ExecutorService executor, long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) return;
        try {
            executor.awaitTermination(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ThreadFactory threadFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            thread.setContextClassLoader(ClassLoader.getPlatformClassLoader());
            return thread;
        };
    }

    private static final class TrackedTask<T> {
        private final CompletableFuture<T> result = new CompletableFuture<>();
        private volatile Future<?> worker;

        private void cancel() {
            result.cancel(false);
            Future<?> currentWorker = worker;
            if (currentWorker != null) currentWorker.cancel(true);
        }
    }

    public final class CriticalOperation implements AutoCloseable {
        private final AtomicBoolean completed = new AtomicBoolean();

        private CriticalOperation() {
        }

        public CompletableFuture<Void> runAsync(Runnable task) {
            if (!completed.compareAndSet(false, true)) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Critical operation was already completed"));
            }

            CompletableFuture<Void> result = new CompletableFuture<>();
            try {
                criticalExecutor.execute(() -> {
                    try {
                        task.run();
                        result.complete(null);
                    } catch (Throwable e) {
                        result.completeExceptionally(e);
                    } finally {
                        finishCritical();
                    }
                });
            } catch (RejectedExecutionException e) {
                finishCritical();
                result.completeExceptionally(e);
            }
            return result;
        }

        @Override
        public void close() {
            if (completed.compareAndSet(false, true)) finishCritical();
        }
    }
}
