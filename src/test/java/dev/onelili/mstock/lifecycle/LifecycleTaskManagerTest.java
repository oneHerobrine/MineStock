package dev.onelili.mstock.lifecycle;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LifecycleTaskManagerTest {

    @Test
    void cancelsRegularWorkAndRejectsNewWorkAfterShutdown() throws Exception {
        LifecycleTaskManager manager = new LifecycleTaskManager("test", 1, 1);
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<Void> task = manager.runAsync(() -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(started.await(1, TimeUnit.SECONDS));

        assertTrue(manager.shutdown(Duration.ofSeconds(1)));
        assertTrue(task.isCancelled());
        CompletionException rejected = assertThrows(
                CompletionException.class, () -> manager.runAsync(() -> {}).join());
        assertTrue(rejected.getCause() instanceof IllegalStateException);
        assertTrue(manager.shutdown(Duration.ofSeconds(1)));
    }

    @Test
    void waitsForAcceptedCriticalWork() throws Exception {
        LifecycleTaskManager manager = new LifecycleTaskManager("test", 1, 1);
        LifecycleTaskManager.CriticalOperation operation = manager.beginCritical();
        assertNotNull(operation);

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        operation.runAsync(() -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(started.await(1, TimeUnit.SECONDS));

        CompletableFuture<Boolean> shutdown = CompletableFuture.supplyAsync(
                () -> manager.shutdown(Duration.ofSeconds(1)));
        assertFalse(shutdown.isDone());
        release.countDown();

        assertTrue(shutdown.get(1, TimeUnit.SECONDS));
        assertNull(manager.beginCritical());
    }

    @Test
    void timesOutAnUnfinishedCriticalReservation() {
        LifecycleTaskManager manager = new LifecycleTaskManager("test", 1, 1);
        LifecycleTaskManager.CriticalOperation operation = manager.beginCritical();
        assertNotNull(operation);

        assertFalse(manager.shutdown(Duration.ofMillis(20)));
        operation.close();
        assertTrue(manager.shutdown(Duration.ofMillis(20)));
    }

    @Test
    void runsDeferredCompensationOnlyOnce() {
        LifecycleTaskManager manager = new LifecycleTaskManager("test", 1, 1);
        AtomicInteger calls = new AtomicInteger();
        Runnable action = calls::incrementAndGet;
        manager.deferUntilShutdown(action);

        manager.shutdown(Duration.ofSeconds(1));
        manager.runDeferredShutdownActions(error -> {
            throw new AssertionError(error);
        });
        manager.runDeferredShutdownActions(error -> {
            throw new AssertionError(error);
        });

        assertTrue(calls.get() == 1);
    }
}
