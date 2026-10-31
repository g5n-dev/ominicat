package io.ominicat.core;

import static org.junit.jupiter.api.Assertions.*;

import io.ominicat.core.spi.PluginRegistry;
import io.ominicat.core.spi.ProcessorFactory;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class AsyncSessionTest {
    @Test void streamingCallbacksResumeAtNextStageOnSessionWorkerAndDrainWaitsForComplete() throws Exception {
        var callbacks = new ArrayBlockingQueue<AsyncEmitter>(1);
        var worker = new AtomicReference<Thread>();
        var before = new AtomicInteger();
        var output = new CopyOnWriteArrayList<Frame>();
        PipelineSession session = Pipeline.builder()
                .add((frame, next) -> { before.incrementAndGet(); next.accept(frame); })
                .add((AsyncProcessor) (frame, emitter) -> { worker.set(Thread.currentThread()); callbacks.add(emitter); })
                .add((frame, next) -> { assertSame(worker.get(), Thread.currentThread()); next.accept(frame); })
                .build().openSession(new SessionOptions(4), output::add);
        session.trySubmit(new Frame.Text("request"));
        AsyncEmitter emitter = take(callbacks);
        session.finish();
        assertFalse(session.completion().isDone());
        assertFalse(session.trySubmit(new Frame.Text("late-input")));
        try (var provider = WorkerThreads.executor("test-provider-", 1)) {
            provider.submit(() -> {
                assertNotSame(worker.get(), Thread.currentThread());
                assertTrue(emitter.tryEmit(new Frame.Text("first")));
                assertTrue(emitter.tryEmit(new Frame.Text("second")));
                emitter.complete();
            }).get(2, TimeUnit.SECONDS);
        }
        session.completion().get(2, TimeUnit.SECONDS);
        assertEquals(List.of(new Frame.Text("first"), new Frame.Text("second")), output);
        assertEquals(1, before.get());
        assertEquals(3, session.metrics().accepted());
        assertEquals(3, session.metrics().processed());
        assertFalse(emitter.tryEmit(new Frame.Text("after-complete")));
    }

    @Test void streamingContinuationsShareBoundedIngressWithoutBlockingCallbackThread() throws Exception {
        var callbacks = new ArrayBlockingQueue<AsyncEmitter>(1);
        CountDownLatch release = new CountDownLatch(1);
        List<Frame> output = new CopyOnWriteArrayList<>();
        PipelineSession session = Pipeline.builder().add((AsyncProcessor) (frame, emitter) -> {
            callbacks.add(emitter); release.await();
        }).build().openSession(new SessionOptions(1), output::add);
        session.trySubmit(new Frame.Text("request"));
        AsyncEmitter emitter = take(callbacks);
        assertTrue(emitter.tryEmit(new Frame.Text("accepted")));
        assertFalse(emitter.tryEmit(new Frame.Text("overflow")));
        assertFalse(session.trySubmit(new Frame.Text("external-overflow")));
        assertEquals(1, session.metrics().queueDepth());
        emitter.complete();
        session.finish();
        release.countDown();
        session.completion().get(2, TimeUnit.SECONDS);
        assertEquals(List.of(new Frame.Text("accepted")), output);
        assertEquals(2, session.metrics().rejected());
    }

    @Test void interruptInvalidatesCallbacksCancelsOnWorkerAndResetsBeforeNextTurn() throws Exception {
        var callbacks = new ArrayBlockingQueue<AsyncEmitter>(2);
        CountDownLatch cancellation = new CountDownLatch(1);
        AtomicInteger resets = new AtomicInteger();
        List<Frame> output = new CopyOnWriteArrayList<>();
        AsyncProcessor stage = new AsyncProcessor() {
            @Override public void processAsync(Frame frame, AsyncEmitter emitter) {
                if (frame.equals(new Frame.Text("new"))) assertEquals(1, resets.get());
                Thread worker = Thread.currentThread();
                emitter.onCancel(() -> { assertSame(worker, Thread.currentThread()); cancellation.countDown(); });
                callbacks.add(emitter);
            }
            @Override public void onInterrupt() { assertEquals(0, cancellation.getCount()); resets.incrementAndGet(); }
        };
        PipelineSession session = Pipeline.builder().add(stage).build().openSession(new SessionOptions(4), output::add);
        session.trySubmit(new Frame.Text("old"));
        AsyncEmitter old = take(callbacks);
        session.interrupt();
        session.trySubmit(new Frame.Text("new"));
        AsyncEmitter current = take(callbacks);
        assertTrue(old.isCancelled());
        assertFalse(old.tryEmit(new Frame.Text("stale")));
        old.fail(new IOException("stale failure must be ignored"));
        old.complete();
        assertTrue(current.tryEmit(new Frame.Text("fresh")));
        current.complete();
        session.finish();
        session.completion().get(2, TimeUnit.SECONDS);
        assertEquals(List.of(new Frame.Text("fresh")), output);
    }

    @Test void asyncFailureFailsFutureCancelsPeersAndClosesStageExactlyOnce() throws Exception {
        var callbacks = new ArrayBlockingQueue<AsyncEmitter>(2);
        AtomicInteger cancellations = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        AsyncProcessor stage = new AsyncProcessor() {
            @Override public void processAsync(Frame frame, AsyncEmitter emitter) {
                emitter.onCancel(cancellations::incrementAndGet); callbacks.add(emitter);
            }
            @Override public void close() { closes.incrementAndGet(); }
        };
        PipelineSession session = Pipeline.builder().add(stage).build().openSession(new SessionOptions(4), frame -> { });
        session.trySubmit(new Frame.Text("one"));
        session.trySubmit(new Frame.Text("two"));
        AsyncEmitter one = take(callbacks);
        AsyncEmitter two = take(callbacks);
        IOException failure = new IOException("provider disconnected");
        one.fail(failure);
        ExecutionException result = assertThrows(ExecutionException.class, () -> session.completion().get(2, TimeUnit.SECONDS));
        assertSame(failure, result.getCause());
        assertTrue(two.isCancelled());
        assertEquals(2, cancellations.get());
        assertEquals(1, closes.get());
        session.close();
        assertEquals(1, closes.get());
    }

    @Test void liveAsyncOperationsHaveAnExplicitBound() throws Exception {
        var callbacks = new ArrayBlockingQueue<AsyncEmitter>(1);
        PipelineSession session = Pipeline.builder().add((AsyncProcessor) (frame, emitter) -> callbacks.add(emitter))
                .build().openSession(new SessionOptions(1), frame -> { });
        assertTrue(session.trySubmit(new Frame.Text("one")));
        AsyncEmitter first = take(callbacks);
        assertTrue(session.trySubmit(new Frame.Text("two")));
        ExecutionException result = assertThrows(ExecutionException.class, () -> session.completion().get(2, TimeUnit.SECONDS));
        assertInstanceOf(RejectedExecutionException.class, result.getCause());
        assertTrue(first.isCancelled());
    }

    @Test void closeCancelsPendingAsyncWorkWithoutWaitingForProviderCallback() throws Exception {
        var callbacks = new ArrayBlockingQueue<AsyncEmitter>(1);
        AtomicInteger cancels = new AtomicInteger();
        PipelineSession session = Pipeline.builder().add((AsyncProcessor) (frame, emitter) -> {
            emitter.onCancel(cancels::incrementAndGet); callbacks.add(emitter);
        }).build().openSession(new SessionOptions(4), frame -> fail("no output expected"));
        session.trySubmit(new Frame.Text("request"));
        AsyncEmitter emitter = take(callbacks);
        session.close();
        assertThrows(ExecutionException.class, () -> session.completion().get(2, TimeUnit.SECONDS));
        assertTrue(emitter.isCancelled());
        assertEquals(1, cancels.get());
        assertFalse(emitter.tryEmit(new Frame.Text("late")));
    }

    @Test void cancellationRegistrationIsLimitedToTheWorkerInvocation() throws Exception {
        var callbacks = new ArrayBlockingQueue<AsyncEmitter>(1);
        PipelineSession session = Pipeline.builder().add((AsyncProcessor) (frame, emitter) -> callbacks.add(emitter))
                .build().openSession(new SessionOptions(4), frame -> { });
        session.trySubmit(new Frame.Text("request"));
        AsyncEmitter emitter = take(callbacks);
        assertThrows(IllegalStateException.class, () -> emitter.onCancel(() -> { }));
        emitter.complete();
        session.finish();
        session.completion().get(2, TimeUnit.SECONDS);
    }

    @Test void asyncFailureRetainsCancellationAndStageCleanupErrors() throws Exception {
        var callbacks = new ArrayBlockingQueue<AsyncEmitter>(1);
        IOException providerFailure = new IOException("provider failed");
        IOException cancelFailure = new IOException("cancel failed");
        IOException closeFailure = new IOException("close failed");
        AsyncProcessor stage = new AsyncProcessor() {
            @Override public void processAsync(Frame frame, AsyncEmitter emitter) {
                emitter.onCancel(() -> { throw cancelFailure; });
                callbacks.add(emitter);
            }
            @Override public void close() throws Exception { throw closeFailure; }
        };
        PipelineSession session = Pipeline.builder().add(stage).build().openSession(new SessionOptions(4), frame -> { });
        session.trySubmit(new Frame.Text("request"));
        take(callbacks).fail(providerFailure);
        ExecutionException failure = assertThrows(ExecutionException.class, () -> session.completion().get(2, TimeUnit.SECONDS));
        assertSame(providerFailure, failure.getCause());
        assertArrayEquals(new Throwable[]{cancelFailure, closeFailure}, providerFailure.getSuppressed());
    }

    @Test void duplicateCancellationRegistrationFailsAndReleasesTheFirstHandle() throws Exception {
        AtomicInteger cancelled = new AtomicInteger();
        AsyncProcessor stage = (frame, emitter) -> {
            emitter.onCancel(cancelled::incrementAndGet);
            emitter.onCancel(() -> fail("duplicate handle must never be registered"));
        };
        PipelineSession session = Pipeline.builder().add(stage).build().openSession(new SessionOptions(4), frame -> { });
        session.trySubmit(new Frame.Text("request"));
        ExecutionException failure = assertThrows(ExecutionException.class, () -> session.completion().get(2, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertEquals(1, cancelled.get());
    }

    @Test void directPipelineRejectsAsyncStagesBeforeStartingAnyWork() {
        AtomicInteger called = new AtomicInteger();
        Pipeline pipeline = Pipeline.builder().add((frame, next) -> { called.incrementAndGet(); next.accept(frame); })
                .add((AsyncProcessor) (frame, emitter) -> emitter.complete()).build();
        assertThrows(IllegalStateException.class, () -> pipeline.process(new Frame.Text("x"), frame -> { }));
        assertEquals(0, called.get());
    }

    @Test void ordinaryEndMarkerIsNotABarrierForEarlierStreamingCallbacks() throws Exception {
        var callbacks = new ArrayBlockingQueue<AsyncEmitter>(1);
        CountDownLatch endDelivered = new CountDownLatch(1);
        List<Frame> output = new CopyOnWriteArrayList<>();
        AsyncProcessor stage = (frame, emitter) -> {
            if (frame instanceof Frame.Control) { assertTrue(emitter.tryEmit(frame)); emitter.complete(); }
            else callbacks.add(emitter);
        };
        PipelineSession session = Pipeline.builder().add(stage).build().openSession(new SessionOptions(4), frame -> {
            output.add(frame);
            if (frame.equals(new Frame.Control(Frame.Signal.END))) endDelivered.countDown();
        });
        session.trySubmit(new Frame.Text("request"));
        AsyncEmitter emitter = take(callbacks);
        session.trySubmit(new Frame.Control(Frame.Signal.END));
        session.finish();
        assertTrue(endDelivered.await(2, TimeUnit.SECONDS));
        assertFalse(session.completion().isDone());
        assertTrue(emitter.tryEmit(new Frame.Text("late-stream-result")));
        emitter.complete();
        session.completion().get(2, TimeUnit.SECONDS);
        assertEquals(List.of(new Frame.Control(Frame.Signal.END), new Frame.Text("late-stream-result")), output);
    }

    @Test void envelopeFactoryPreservesAsyncCapabilityAndCausalMetadata() throws Exception {
        var callbacks = new ArrayBlockingQueue<AsyncEmitter>(1);
        AtomicInteger ids = new AtomicInteger();
        Processor decorated = EnvelopeProcessor.wrap((AsyncProcessor) (frame, emitter) -> {
            assertEquals(new Frame.Text("request"), frame); callbacks.add(emitter);
        }, () -> "derived-" + ids.incrementAndGet(), () -> 40);
        assertInstanceOf(AsyncProcessor.class, decorated);
        List<Frame> output = new CopyOnWriteArrayList<>();
        PipelineSession session = Pipeline.builder().add(decorated).build().openSession(new SessionOptions(4), output::add);
        session.trySubmit(new FrameEnvelope(new Frame.Text("request"), "root", "s", "t", "c", "", 10, 20, 30));
        AsyncEmitter emitter = take(callbacks);
        assertTrue(emitter.tryEmit(new Frame.Text("response")));
        emitter.complete();
        session.finish();
        session.completion().get(2, TimeUnit.SECONDS);
        FrameEnvelope result = assertInstanceOf(FrameEnvelope.class, output.get(0));
        assertEquals("derived-1", result.id());
        assertEquals("root", result.causationId());
        assertEquals("s", result.sessionId());
        assertEquals("t", result.turnId());
        assertEquals("c", result.correlationId());
        assertEquals(40, result.emitTimeNanos());
        assertEquals(new Frame.Text("response"), result.frame());
    }

    @Test void cancellationRegisteredAfterConcurrentInterruptStillRunsBeforeNextTurn() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger cancels = new AtomicInteger();
        List<Frame> output = new CopyOnWriteArrayList<>();
        AsyncProcessor stage = (frame, emitter) -> {
            if (frame.equals(new Frame.Text("old"))) {
                entered.countDown();
                boolean resumed = false;
                while (!resumed) {
                    try { release.await(); resumed = true; }
                    catch (InterruptedException ignored) { }
                }
                emitter.onCancel(cancels::incrementAndGet);
                assertFalse(emitter.tryEmit(new Frame.Text("stale")));
            } else {
                assertEquals(1, cancels.get());
                assertTrue(emitter.tryEmit(frame));
                emitter.complete();
            }
        };
        PipelineSession session = Pipeline.builder().add(stage).build().openSession(new SessionOptions(4), output::add);
        session.trySubmit(new Frame.Text("old"));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        session.interrupt();
        session.trySubmit(new Frame.Text("new"));
        release.countDown();
        session.finish();
        session.completion().get(2, TimeUnit.SECONDS);
        assertEquals(1, cancels.get());
        assertEquals(List.of(new Frame.Text("new")), output);
    }

    @Test void multipleAsyncStagesResumeAtTheirOwnContinuationOffsets() throws Exception {
        var callbacks = new ArrayBlockingQueue<AsyncEmitter>(1);
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        List<Frame> output = new CopyOnWriteArrayList<>();
        PipelineSession session = Pipeline.builder()
                .add((AsyncProcessor) (frame, emitter) -> {
                    first.incrementAndGet();
                    assertTrue(emitter.tryEmit(new Frame.Text("intermediate")));
                    emitter.complete();
                })
                .add((AsyncProcessor) (frame, emitter) -> {
                    assertEquals(new Frame.Text("intermediate"), frame);
                    second.incrementAndGet(); callbacks.add(emitter);
                }).build().openSession(new SessionOptions(4), output::add);
        session.trySubmit(new Frame.Text("request"));
        AsyncEmitter result = take(callbacks);
        assertTrue(result.tryEmit(new Frame.Text("result")));
        result.complete();
        session.finish();
        session.completion().get(2, TimeUnit.SECONDS);
        assertEquals(1, first.get());
        assertEquals(1, second.get());
        assertEquals(List.of(new Frame.Text("result")), output);
    }

    @Test void customFactoryStreamsFromExternalExecutorWithoutCloudDependencies() throws Exception {
        AtomicReference<Thread> providerThread = new AtomicReference<>();
        PluginRegistry registry = new PluginRegistry().register(new ProcessorFactory() {
            @Override public String type() { return "callback-fixture"; }
            @Override public Processor create(Map<String, String> options) {
                return new AsyncProcessor() {
                    private final java.util.concurrent.ExecutorService provider = Executors.newSingleThreadExecutor();
                    @Override public void processAsync(Frame frame, AsyncEmitter emitter) {
                        var job = provider.submit(() -> {
                            providerThread.set(Thread.currentThread());
                            if (!emitter.tryEmit(new Frame.Text(options.get("reply")))) {
                                emitter.fail(new RejectedExecutionException("callback continuation rejected")); return;
                            }
                            emitter.complete();
                        });
                        emitter.onCancel(() -> job.cancel(true));
                    }
                    @Override public void close() { provider.shutdownNow(); }
                };
            }
        });
        List<Frame> output = new CopyOnWriteArrayList<>();
        PipelineSession session = Pipeline.builder().add(registry.create("callback-fixture", Map.of("reply", "streamed")))
                .add((frame, next) -> { assertNotSame(providerThread.get(), Thread.currentThread()); next.accept(frame); })
                .build().openSession(new SessionOptions(4), output::add);
        session.trySubmit(new Frame.Text("request"));
        session.finish();
        session.completion().get(2, TimeUnit.SECONDS);
        assertEquals(List.of(new Frame.Text("streamed")), output);
    }

    private static AsyncEmitter take(ArrayBlockingQueue<AsyncEmitter> queue) throws InterruptedException {
        AsyncEmitter emitter = queue.poll(3, TimeUnit.SECONDS);
        assertNotNull(emitter, "async operation never started");
        return emitter;
    }
}
