package io.ominicat.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class WorkerThreadsTest {
    @Test void preferredFactorySelectsSupportedNonPreviewRuntimeBackend() throws Exception {
        assertEquals(Runtime.version().feature() >= 21, WorkerThreads.virtualThreadsAvailable());
        AtomicBoolean ran = new AtomicBoolean();
        Thread worker = WorkerThreads.unstarted("preferred-worker-", () -> ran.set(true));
        assertEquals(Thread.State.NEW, worker.getState());
        assertTrue(worker.getName().startsWith("preferred-worker-"));
        assertTrue(worker.isDaemon());
        assertEquals(WorkerThreads.virtualThreadsAvailable(), WorkerThreads.isVirtual(worker));
        assertFalse(ran.get());
        worker.start(); worker.join(2000);
        assertFalse(worker.isAlive()); assertTrue(ran.get());
    }

    @Test void platformFactoryAlwaysCreatesDaemonWorkersAndDistinctNames() throws Exception {
        var factory = WorkerThreads.platformFactory("platform-worker-");
        var names = new HashSet<String>();
        for (int index = 0; index < 32; index++) {
            Thread worker = factory.newThread(() -> { });
            assertEquals(Thread.State.NEW, worker.getState());
            assertFalse(WorkerThreads.isVirtual(worker));
            assertTrue(worker.isDaemon());
            assertTrue(names.add(worker.getName()));
        }
        CountDownLatch ran = new CountDownLatch(1);
        Thread worker = factory.newThread(ran::countDown);
        worker.start(); assertTrue(ran.await(2, TimeUnit.SECONDS)); worker.join(2000);
    }

    @Test void startRunsTaskImmediatelyUsingThePreferredBackend() throws Exception {
        CountDownLatch ran = new CountDownLatch(1);
        Thread worker = WorkerThreads.start("started-worker-", ran::countDown);
        assertTrue(ran.await(2, TimeUnit.SECONDS)); worker.join(2000);
        assertTrue(worker.isDaemon());
        assertEquals(WorkerThreads.virtualThreadsAvailable(), WorkerThreads.isVirtual(worker));
    }

    @Test void executorBoundsRunningAndQueuedTasksOnBothBackends() throws Exception {
        CountDownLatch entered = new CountDownLatch(3);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();
        WorkerThreads.TaskExecutor executor = WorkerThreads.executor("bounded-task-", 3, 3);
        try {
            for (int index = 0; index < 6; index++) executor.submit(() -> {
                int running = active.incrementAndGet(); maximum.accumulateAndGet(running, Math::max);
                assertTrue(Thread.currentThread().isDaemon());
                assertTrue(Thread.currentThread().getName().startsWith("bounded-task-"));
                assertEquals(WorkerThreads.virtualThreadsAvailable(), WorkerThreads.isVirtual(Thread.currentThread()));
                entered.countDown();
                try { release.await(); }
                catch (InterruptedException interruption) { Thread.currentThread().interrupt(); }
                finally { active.decrementAndGet(); completed.incrementAndGet(); }
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertEquals(3, active.get());
            assertThrows(RejectedExecutionException.class, () -> executor.submit(() -> fail("saturated task ran")));
        } finally { release.countDown(); executor.close(); }
        assertEquals(3, maximum.get());
        assertEquals(0, active.get()); assertEquals(6, completed.get());
        assertTrue(executor.isShutdown()); assertTrue(executor.isTerminated());
        assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> { }));
    }

    @Test void closeDrainsAcceptedTasksBeforeReturning() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch closeStarted = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        AtomicInteger completed = new AtomicInteger();
        WorkerThreads.TaskExecutor executor = WorkerThreads.executor("drain-task-", 1, 2);
        executor.submit(() -> { entered.countDown(); release.await(); completed.incrementAndGet(); return null; });
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        executor.submit(completed::incrementAndGet);
        Thread closer = WorkerThreads.platformFactory("executor-closer-").newThread(() -> {
            closeStarted.countDown(); executor.close(); closed.countDown();
        });
        closer.start(); assertTrue(closeStarted.await(2, TimeUnit.SECONDS));
        try { assertFalse(closed.await(50, TimeUnit.MILLISECONDS), "close returned before active task drained"); }
        finally { release.countDown(); }
        assertTrue(closed.await(2, TimeUnit.SECONDS)); closer.join(2000);
        assertEquals(2, completed.get()); assertTrue(executor.isTerminated());
        executor.close();
    }

    @Test void shutdownNowInterruptsActiveWorkAndCancelsDiscardedSubmittedFutures() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        WorkerThreads.TaskExecutor executor = WorkerThreads.executor("cancel-task-", 1, 1);
        executor.submit(() -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException expected) { interrupted.countDown(); }
        });
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        var queued = executor.submit(() -> fail("discarded task executed"));
        assertEquals(1, executor.shutdownNow().size());
        assertTrue(queued.isCancelled());
        assertTrue(interrupted.await(2, TimeUnit.SECONDS));
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS)); executor.close();
    }

    @Test void interruptedCloseCancelsTasksRestoresInterruptAndTerminates() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicBoolean restored = new AtomicBoolean();
        WorkerThreads.TaskExecutor executor = WorkerThreads.executor("interrupted-close-task-", 1, 1);
        executor.submit(() -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException expected) { interrupted.countDown(); }
        });
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        var queued = executor.submit(() -> fail("cancelled queued task ran"));
        Thread closer = WorkerThreads.platformFactory("interrupted-closer-").newThread(() -> {
            Thread.currentThread().interrupt();
            executor.close();
            restored.set(Thread.currentThread().isInterrupted());
        });
        closer.start(); closer.join(2000);
        assertFalse(closer.isAlive()); assertTrue(restored.get());
        assertTrue(interrupted.await(2, TimeUnit.SECONDS));
        assertTrue(queued.isCancelled()); assertTrue(executor.isTerminated());
    }

    @Test void shutdownNowDoesNotPublishAnUnexecutedCompletionServiceFutureAsCompleted() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        WorkerThreads.TaskExecutor executor = WorkerThreads.executor("completion-queue-", 1, 1);
        executor.execute(() -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException expected) { Thread.currentThread().interrupt(); }
        });
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        var completions = new java.util.concurrent.ExecutorCompletionService<Integer>(executor);
        var queued = completions.submit(() -> 42);
        try {
            assertEquals(1, executor.shutdownNow().size());
            assertNull(completions.poll(50, TimeUnit.MILLISECONDS), "an unexecuted inner Future was reported completed");
        } finally { queued.cancel(false); executor.close(); }
    }

    @Test void invalidResourceLimitsAndMissingTasksFailBeforeStartingWork() {
        assertThrows(IllegalArgumentException.class, () -> WorkerThreads.executor("x-", 0));
        assertThrows(IllegalArgumentException.class, () -> WorkerThreads.executor("x-", 1, 0));
        assertThrows(IllegalArgumentException.class, () -> WorkerThreads.factory(" "));
        assertThrows(IllegalArgumentException.class, () -> WorkerThreads.platformFactory("x".repeat(129)));
        assertThrows(NullPointerException.class, () -> WorkerThreads.unstarted("x-", null));
        assertThrows(NullPointerException.class, () -> WorkerThreads.isVirtual(null));
    }
}
