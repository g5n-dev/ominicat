package io.ominicat.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class PipelineSessionTest {
    @Test void drainsFifoOnOneCompatibleWorkerWithoutSyntheticControlFrames() throws Exception {
        List<Frame> output = new CopyOnWriteArrayList<>();
        AtomicBoolean virtual = new AtomicBoolean();
        PipelineSession session = Pipeline.builder().add((frame, next) -> {
            virtual.set(WorkerThreads.isVirtual(Thread.currentThread()));
            next.accept(frame);
        }).build().openSession(new SessionOptions(128), output::add);
        for (int i = 0; i < 100; i++) assertTrue(session.submit(new Frame.Text("" + i), Duration.ofSeconds(1)));
        session.finish();
        session.finish();
        assertFalse(session.trySubmit(new Frame.Text("late")));
        session.completion().get(3, TimeUnit.SECONDS);
        assertEquals(WorkerThreads.virtualThreadsAvailable(), virtual.get());
        assertEquals(100, output.size());
        for (int i = 0; i < 100; i++) assertEquals(new Frame.Text("" + i), output.get(i));
        SessionMetrics metrics = session.metrics();
        assertEquals(100, metrics.accepted());
        assertEquals(100, metrics.processed());
        assertEquals(1, metrics.rejected());
        assertEquals(0, metrics.queueDepth());
        assertTrue(metrics.totalProcessingNanos() > 0);
    }

    @Test void boundsPendingWorkAndTimesOutWithoutDroppingAcceptedFrames() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<Frame> output = new CopyOnWriteArrayList<>();
        PipelineSession session = blockingPipeline(entered, release).openSession(new SessionOptions(1), output::add);
        assertTrue(session.trySubmit(new Frame.Text("active")));
        await(entered);
        assertTrue(session.trySubmit(new Frame.Text("queued")));
        assertFalse(session.trySubmit(new Frame.Text("overflow")));
        assertFalse(session.submit(new Frame.Text("timed-out"), Duration.ZERO));
        assertEquals(1, session.metrics().queueDepth());
        session.finish();
        release.countDown();
        session.completion().get(3, TimeUnit.SECONDS);
        assertEquals(List.of(new Frame.Text("active"), new Frame.Text("queued")), output);
        assertEquals(2, session.metrics().rejected());
    }

    @Test void waitingProducerWakesWhenFinishClosesIntake() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        PipelineSession session = blockingPipeline(entered, release).openSession(new SessionOptions(1), frame -> { });
        session.trySubmit(new Frame.Text("active"));
        await(entered);
        session.trySubmit(new Frame.Text("queued"));
        try (var executor = WorkerThreads.executor("test-task-", 4, 1024)) {
            var waiting = executor.submit(() -> session.submit(new Frame.Text("late"), Duration.ofHours(1)));
            session.finish();
            assertFalse(waiting.get(2, TimeUnit.SECONDS));
        } finally { release.countDown(); }
        session.completion().get(2, TimeUnit.SECONDS);
    }

    @Test void waitingProducerEnqueuesOnceCapacityReturns() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        PipelineSession session = blockingPipeline(entered, release).openSession(new SessionOptions(1), frame -> { });
        session.trySubmit(new Frame.Text("active"));
        await(entered);
        session.trySubmit(new Frame.Text("queued"));
        try (var executor = WorkerThreads.executor("test-task-", 4, 1024)) {
            var waiting = executor.submit(() -> session.submit(new Frame.Text("next"), Duration.ofSeconds(2)));
            release.countDown();
            assertTrue(waiting.get(3, TimeUnit.SECONDS));
        }
        session.finish();
        session.completion().get(2, TimeUnit.SECONDS);
        assertEquals(3, session.metrics().processed());
    }

    @Test void interruptsFenceStaleEmissionsAndDropPendingOldTurn() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<Frame> output = new CopyOnWriteArrayList<>();
        PipelineSession session = Pipeline.builder().add((frame, next) -> {
            if (frame.equals(new Frame.Text("old"))) {
                entered.countDown();
                boolean released = false;
                while (!released) {
                    try { release.await(); released = true; }
                    catch (InterruptedException ignore) { interrupted.countDown(); }
                }
            }
            next.accept(frame);
        }).build().openSession(new SessionOptions(2), output::add);
        session.trySubmit(new Frame.Text("old"));
        await(entered);
        session.trySubmit(new Frame.Text("old-pending"));
        session.interrupt();
        await(interrupted);
        assertTrue(session.trySubmit(new Frame.Text("new")));
        release.countDown();
        session.finish();
        session.completion().get(3, TimeUnit.SECONDS);
        assertEquals(List.of(new Frame.Text("new")), output);
        assertEquals(2, session.metrics().cancelled());
        assertEquals(1, session.metrics().processed());
        assertEquals(0, session.metrics().failed());
    }

    @Test void processorFailureCancelsQueuedWorkAndClosesStagesInReverseOrder() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<Integer> closed = new CopyOnWriteArrayList<>();
        IOException failure = new IOException("provider failed");
        Processor first = new Processor() {
            @Override public void process(Frame frame, FrameSink next) throws Exception {
                entered.countDown(); release.await(); throw failure;
            }
            @Override public void close() { closed.add(1); }
        };
        Processor second = new Processor() {
            @Override public void process(Frame frame, FrameSink next) throws Exception { next.accept(frame); }
            @Override public void close() { closed.add(2); }
        };
        PipelineSession session = Pipeline.builder().add(first).add(second).build()
                .openSession(new SessionOptions(2), frame -> fail("must not emit"));
        session.trySubmit(new Frame.Text("broken"));
        await(entered);
        session.trySubmit(new Frame.Text("pending"));
        release.countDown();
        ExecutionException exception = assertThrows(ExecutionException.class,
                () -> session.completion().get(3, TimeUnit.SECONDS));
        assertSame(failure, exception.getCause());
        assertEquals(List.of(2, 1), closed);
        assertEquals(1, session.metrics().failed());
        assertEquals(1, session.metrics().cancelled());
        assertFalse(session.trySubmit(new Frame.Text("after-failure")));
        session.close();
        assertEquals(List.of(2, 1), closed);
    }

    @Test void outputFailurePropagatesAndCleanupFailureIsSuppressed() throws Exception {
        IOException outputFailure = new IOException("socket closed");
        IOException cleanupFailure = new IOException("close failed");
        Processor stage = new Processor() {
            @Override public void process(Frame frame, FrameSink next) throws Exception { next.accept(frame); }
            @Override public void close() throws Exception { throw cleanupFailure; }
        };
        PipelineSession session = Pipeline.builder().add(stage).build().openSession(SessionOptions.defaults(),
                frame -> { throw outputFailure; });
        session.trySubmit(new Frame.Text("x"));
        ExecutionException exception = assertThrows(ExecutionException.class,
                () -> session.completion().get(2, TimeUnit.SECONDS));
        assertSame(outputFailure, exception.getCause());
        assertArrayEquals(new Throwable[]{cleanupFailure}, outputFailure.getSuppressed());
    }

    @Test void cleanupFailureFailsOtherwiseSuccessfulCompletionAndContinuesCleanup() throws Exception {
        List<Integer> closed = new CopyOnWriteArrayList<>();
        Processor first = closingProcessor(1, closed, false);
        Processor second = closingProcessor(2, closed, true);
        PipelineSession session = Pipeline.builder().add(first).add(second).build()
                .openSession(SessionOptions.defaults(), frame -> { });
        session.finish();
        assertThrows(ExecutionException.class, () -> session.completion().get(2, TimeUnit.SECONDS));
        assertEquals(List.of(2, 1), closed);
    }

    @Test void closeCancelsBlockingWorkAndClosesExactlyOnce() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        AtomicInteger closed = new AtomicInteger();
        Processor stage = new Processor() {
            @Override public void process(Frame frame, FrameSink next) throws Exception {
                entered.countDown(); new CountDownLatch(1).await(); next.accept(frame);
            }
            @Override public void close() { closed.incrementAndGet(); }
        };
        PipelineSession session = Pipeline.builder().add(stage).build()
                .openSession(SessionOptions.defaults(), frame -> fail("cancelled frame emitted"));
        session.trySubmit(new Frame.Text("x"));
        await(entered);
        session.close(); session.close(); session.finish(); session.interrupt();
        assertThrows(ExecutionException.class, () -> session.completion().get(2, TimeUnit.SECONDS));
        assertEquals(1, closed.get());
        assertEquals(1, session.metrics().cancelled());
        assertFalse(session.trySubmit(new Frame.Text("late")));
    }

    @Test void idleInterruptsAndCloseCompleteWithoutHanging() throws Exception {
        PipelineSession session = Pipeline.builder().build().openSession(SessionOptions.defaults(), frame -> { });
        for (int i = 0; i < 100; i++) session.interrupt();
        assertTrue(session.trySubmit(new Frame.Text("new-turn")));
        session.finish();
        session.completion().get(2, TimeUnit.SECONDS);
        assertEquals(1, session.metrics().processed());
        assertThrows(IllegalArgumentException.class,
                () -> session.submit(new Frame.Text("x"), Duration.ofNanos(-1)));
    }

    @Test void callerCannotCompleteTheWorkerFuture() throws Exception {
        PipelineSession session = Pipeline.builder().build().openSession(SessionOptions.defaults(), frame -> { });
        var view = session.completion();
        assertTrue(view.complete(null));
        assertFalse(session.completion().isDone());
        session.finish();
        session.completion().get(2, TimeUnit.SECONDS);
    }

    @Test void interruptHookClearsStateOnWorkerBeforeNewGeneration() throws Exception {
        CountDownLatch active = new CountDownLatch(1);
        CountDownLatch hookEntered = new CountDownLatch(1);
        List<String> observed = new CopyOnWriteArrayList<>();
        Processor stateful = new Processor() {
            private String buffer = "";
            @Override public void process(Frame frame, FrameSink next) throws Exception {
                Frame.Text text = (Frame.Text) frame;
                buffer += text.text();
                if (text.text().equals("old")) {
                    active.countDown(); new CountDownLatch(1).await();
                }
                next.accept(new Frame.Text(buffer));
            }
            @Override public void onInterrupt() {
                assertEquals(WorkerThreads.virtualThreadsAvailable(), WorkerThreads.isVirtual(Thread.currentThread()));
                buffer = "";
                hookEntered.countDown();
            }
        };
        PipelineSession session = Pipeline.builder().add(stateful).build()
                .openSession(SessionOptions.defaults(), frame -> observed.add(((Frame.Text) frame).text()));
        session.trySubmit(new Frame.Text("old"));
        await(active);
        session.interrupt();
        await(hookEntered);
        session.trySubmit(new Frame.Text("new"));
        session.finish();
        session.completion().get(2, TimeUnit.SECONDS);
        assertEquals(List.of("new"), observed);
        assertEquals(1, session.metrics().cancelled());
        assertEquals(1, session.metrics().processed());
    }

    @Test void interruptHookFailureFailsSessionAndStillCleansUp() throws Exception {
        CountDownLatch hook = new CountDownLatch(1);
        AtomicInteger closed = new AtomicInteger();
        Processor stage = new Processor() {
            @Override public void process(Frame frame, FrameSink next) throws Exception { next.accept(frame); }
            @Override public void onInterrupt() throws Exception { hook.countDown(); throw new IOException("reset failed"); }
            @Override public void close() { closed.incrementAndGet(); }
        };
        PipelineSession session = Pipeline.builder().add(stage).build().openSession(SessionOptions.defaults(), frame -> { });
        session.interrupt();
        await(hook);
        ExecutionException failure = assertThrows(ExecutionException.class, () -> session.completion().get(2, TimeUnit.SECONDS));
        assertEquals("reset failed", failure.getCause().getMessage());
        assertEquals(1, closed.get());
        assertFalse(session.trySubmit(new Frame.Text("late")));
    }

    @Test void concurrentProducersDoNotLoseAcceptedFrames() throws Exception {
        AtomicInteger output = new AtomicInteger();
        PipelineSession session = Pipeline.builder().build().openSession(new SessionOptions(16), frame -> output.incrementAndGet());
        List<java.util.concurrent.Future<?>> jobs = new ArrayList<>();
        try (var executor = WorkerThreads.executor("test-task-", 4, 1024)) {
            for (int producer = 0; producer < 8; producer++) jobs.add(executor.submit(() -> {
                for (int i = 0; i < 100; i++) {
                    try { assertTrue(session.submit(new Frame.Text("x"), Duration.ofSeconds(3))); }
                    catch (InterruptedException error) { throw new RuntimeException(error); }
                }
            }));
            for (var job : jobs) job.get(5, TimeUnit.SECONDS);
        }
        session.finish();
        session.completion().get(2, TimeUnit.SECONDS);
        assertEquals(800, output.get());
        assertEquals(800, session.metrics().accepted());
        assertEquals(800, session.metrics().processed());
    }

    private static Pipeline blockingPipeline(CountDownLatch entered, CountDownLatch release) {
        return Pipeline.builder().add((frame, next) -> {
            entered.countDown(); release.await(); next.accept(frame);
        }).build();
    }

    private static Processor closingProcessor(int id, List<Integer> closed, boolean fail) {
        return new Processor() {
            @Override public void process(Frame frame, FrameSink next) throws Exception { next.accept(frame); }
            @Override public void close() throws Exception {
                closed.add(id);
                if (fail) throw new IOException("cleanup " + id);
            }
        };
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(3, TimeUnit.SECONDS), "latch did not complete");
    }
}
