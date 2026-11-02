package io.ominicat.core;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Behavioral and reachability oracles shared by the old and combined direct invocation representations. */
@Timeout(15)
class DirectInvocationLifecycleTest {
    private static final String RETIRED = "synchronous sink is no longer valid after process returns";
    private static final String WRONG_THREAD =
            "synchronous sink may only emit on its invocation thread; use AsyncProcessor for callbacks";

    @Test void filteringAndFanoutKeepTheirSuccessorAndDepthFirstOrder() throws Exception {
        List<String> calls = new ArrayList<>();
        List<Frame> delivered = new ArrayList<>();
        Frame first = new Frame.Text("first");
        Frame second = new Frame.Text("second");
        Pipeline pipeline = Pipeline.builder().add((frame, next) -> {
            if (frame instanceof Frame.Control) return;
            calls.add("before");
            next.accept(first);
            calls.add("between");
            next.accept(second);
            calls.add("after");
        }).add((frame, next) -> {
            calls.add(((Frame.Text) frame).text());
            next.accept(frame);
            next.accept(frame);
        }).build();

        pipeline.process(new Frame.Control(Frame.Signal.START), delivered::add);
        assertTrue(calls.isEmpty());
        assertTrue(delivered.isEmpty());
        pipeline.process(new Frame.Text("input"), delivered::add);
        assertEquals(List.of("before", "first", "between", "second", "after"), calls);
        assertEquals(4, delivered.size());
        assertSame(first, delivered.get(0));
        assertSame(first, delivered.get(1));
        assertSame(second, delivered.get(2));
        assertSame(second, delivered.get(3));
    }

    @Test void activeDirectSinkRejectsWrongThreadBeforeInspectingEvenANullFrame() throws Exception {
        AtomicReference<FrameSink> captured = new AtomicReference<>();
        AtomicInteger delivered = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Pipeline pipeline = Pipeline.builder().add((frame, next) -> {
            captured.set(next);
            entered.countDown();
            release.await();
            next.accept(frame);
        }).build();
        CompletableFuture<Void> completed = new CompletableFuture<>();
        Thread owner = WorkerThreads.start("direct-invocation-owner", () -> {
            try {
                pipeline.process(new Frame.Text("input"), frame -> delivered.incrementAndGet());
                completed.complete(null);
            } catch (Throwable failure) { completed.completeExceptionally(failure); }
        });
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            for (Frame frame : new Frame[] {new Frame.Text("wrong thread"), null}) {
                IllegalStateException error = assertThrows(IllegalStateException.class, () -> captured.get().accept(frame));
                assertEquals(WRONG_THREAD, error.getMessage());
            }
            assertEquals(0, delivered.get());
        } finally {
            release.countDown();
            owner.join(2_000);
        }
        assertFalse(owner.isAlive());
        completed.get(2, TimeUnit.SECONDS);
        assertEquals(1, delivered.get());
        assertRetired(captured.get());
    }

    @Test void aRetiredHandleNeverRevivesDuringTheNextDirectInvocationOnTheSameThread() throws Exception {
        AtomicReference<FrameSink> previous = new AtomicReference<>();
        AtomicInteger invocations = new AtomicInteger();
        List<Frame> delivered = new ArrayList<>();
        Pipeline pipeline = Pipeline.builder().add((frame, next) -> {
            if (invocations.getAndIncrement() == 0) previous.set(next);
            else {
                assertNotSame(previous.get(), next);
                assertRetired(previous.get());
            }
            next.accept(frame);
        }).build();
        Frame first = new Frame.Text("first");
        Frame second = new Frame.Text("second");
        pipeline.process(first, delivered::add);
        assertRetired(previous.get());
        pipeline.process(second, delivered::add);
        assertEquals(List.of(first, second), delivered);
        assertRetired(previous.get());
    }

    @Test void nullEmissionFailsAtTheBoundaryButTheActiveInvocationCanRecoverAndFanOut() throws Exception {
        List<Frame> delivered = new ArrayList<>();
        AtomicInteger downstream = new AtomicInteger();
        Pipeline pipeline = Pipeline.builder().add((frame, next) -> {
            NullPointerException error = assertThrows(NullPointerException.class, () -> next.accept(null));
            assertEquals("emitted frame", error.getMessage());
            next.accept(frame);
            next.accept(frame);
        }).add((frame, next) -> {
            downstream.incrementAndGet();
            next.accept(frame);
        }).build();
        Frame input = new Frame.Text("valid after rejected null");
        pipeline.process(input, delivered::add);
        assertEquals(2, downstream.get());
        assertEquals(List.of(input, input), delivered);
    }

    @Test void processorExceptionsAndErrorsRetireTheHandleBeforeAndAfterOutput() throws Exception {
        for (boolean emitFirst : new boolean[] {false, true}) {
            for (Throwable failure : List.of(new IOException("processor failure"), new AssertionError("processor error"))) {
                AtomicReference<FrameSink> captured = new AtomicReference<>();
                List<Frame> delivered = new ArrayList<>();
                Pipeline pipeline = Pipeline.builder().add((frame, next) -> {
                    captured.set(next);
                    if (emitFirst) next.accept(frame);
                    throwFailure(failure);
                }).build();
                Frame input = new Frame.Text("input");
                assertSame(failure, assertThrows(Throwable.class, () -> pipeline.process(input, delivered::add)));
                assertEquals(emitFirst ? List.of(input) : List.of(), delivered);
                assertRetired(captured.get());
            }
        }
    }

    @Test void outputExceptionsAndErrorsUnwindEveryScopeWithoutClosingCallerOwnedProcessors() throws Exception {
        for (Throwable failure : List.of(new IOException("output failure"), new AssertionError("output error"))) {
            List<FrameSink> captured = new ArrayList<>();
            List<Frame> delivered = new ArrayList<>();
            AtomicInteger closes = new AtomicInteger();
            Processor processor = new Processor() {
                @Override public void process(Frame frame, FrameSink next) throws Exception {
                    captured.add(next);
                    next.accept(frame);
                }
                @Override public void close() { closes.incrementAndGet(); }
            };
            Pipeline pipeline = Pipeline.builder().add(processor).add((frame, next) -> {
                captured.add(next);
                next.accept(frame);
            }).build();
            Frame input = new Frame.Text("output may already have an external effect");
            assertSame(failure, assertThrows(Throwable.class, () -> pipeline.process(input, frame -> {
                delivered.add(frame);
                throwFailure(failure);
            })));
            assertEquals(List.of(input), delivered);
            assertEquals(2, captured.size());
            for (FrameSink sink : captured) assertRetired(sink);
            assertEquals(0, closes.get());
            processor.close();
            assertEquals(1, closes.get());
        }
    }

    @Test void aNestedDirectCallRetiresOnlyItsOwnInvocation() throws Exception {
        AtomicReference<Pipeline> pipeline = new AtomicReference<>();
        AtomicReference<FrameSink> inner = new AtomicReference<>();
        AtomicReference<FrameSink> outer = new AtomicReference<>();
        List<Frame> delivered = new ArrayList<>();
        Frame inside = new Frame.Text("inside");
        Frame outside = new Frame.Text("outside");
        pipeline.set(Pipeline.builder().add((frame, next) -> {
            if (frame == outside) {
                outer.set(next);
                pipeline.get().process(inside, delivered::add);
                assertNotSame(inner.get(), next);
                assertRetired(inner.get());
            } else inner.set(next);
            next.accept(frame);
        }).build());
        pipeline.get().process(outside, delivered::add);
        assertEquals(List.of(inside, outside), delivered);
        assertRetired(inner.get());
        assertRetired(outer.get());
    }

    @Test void concurrentStatelessDirectCallsKeepTheirOwnerOutputAndFanoutIndependent() throws Exception {
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        Pipeline pipeline = Pipeline.builder().add((frame, next) -> {
            entered.countDown();
            release.await();
            String text = ((Frame.Text) frame).text();
            next.accept(new Frame.Text(text + "-first"));
            next.accept(new Frame.Text(text + "-second"));
        }).add((frame, next) -> next.accept(frame)).build();
        CompletableFuture<List<Frame>> alpha = new CompletableFuture<>();
        CompletableFuture<List<Frame>> beta = new CompletableFuture<>();
        Thread first = startDirect(pipeline, "alpha", alpha);
        Thread second = startDirect(pipeline, "beta", beta);
        try { assertTrue(entered.await(2, TimeUnit.SECONDS), "direct calls did not overlap"); }
        finally {
            release.countDown();
            first.join(2_000);
            second.join(2_000);
        }
        assertFalse(first.isAlive());
        assertFalse(second.isAlive());
        assertEquals(List.of(new Frame.Text("alpha-first"), new Frame.Text("alpha-second")), alpha.get(2, TimeUnit.SECONDS));
        assertEquals(List.of(new Frame.Text("beta-first"), new Frame.Text("beta-second")), beta.get(2, TimeUnit.SECONDS));
    }

    @Test void retiredDirectHandlesDoNotPinPipelineProcessorOutputInputOrTheirOwnerThread() throws Exception {
        assumeFalse(ManagementFactory.getRuntimeMXBean().getInputArguments().contains("-XX:+DisableExplicitGC"));
        for (int outcome = 0; outcome < 3; outcome++) {
            Retired retained = retireGraphOnOwnedThread(outcome);
            for (int attempt = 0; attempt < 40 && hasLiveReferent(retained); attempt++) {
                System.gc();
                Thread.sleep(15);
            }
            for (Referent referent : retained.references()) {
                assertNull(referent.reference().get(), referent.label() + " retained after outcome " + outcome);
            }
            assertRetired(retained.handle());
            Reference.reachabilityFence(retained.handle());
        }
    }

    private record Referent(String label, WeakReference<?> reference) { }
    private record Retired(FrameSink handle, List<Referent> references) { }

    private static Thread startDirect(Pipeline pipeline, String label, CompletableFuture<List<Frame>> completed) {
        return WorkerThreads.start("direct-concurrent-" + label, () -> {
            Thread owner = Thread.currentThread();
            List<Frame> delivered = new ArrayList<>();
            try {
                pipeline.process(new Frame.Text(label), frame -> {
                    assertSame(owner, Thread.currentThread(), "direct output changed its invocation thread");
                    delivered.add(frame);
                });
                completed.complete(List.copyOf(delivered));
            } catch (Throwable failure) { completed.completeExceptionally(failure); }
        });
    }

    private static Retired retireGraphOnOwnedThread(int outcome) throws Exception {
        CompletableFuture<Retired> completed = new CompletableFuture<>();
        Thread owner = new Thread(() -> {
            try { completed.complete(retireGraph(outcome)); }
            catch (Throwable failure) { completed.completeExceptionally(failure); }
        }, "direct-retired-graph");
        owner.setDaemon(true);
        owner.start();
        try { return completed.get(2, TimeUnit.SECONDS); }
        finally {
            owner.join(2_000);
            assertFalse(owner.isAlive(), "graph-probe thread did not terminate");
        }
    }

    private static Retired retireGraph(int outcome) throws Exception {
        Object processorState = new byte[1024 * 1024];
        Object outputState = new byte[1024 * 1024];
        AtomicReference<FrameSink> captured = new AtomicReference<>();
        Throwable failure = outcome == 1 ? new IOException("synthetic processor failure")
                : outcome == 2 ? new AssertionError("synthetic processor error") : null;
        Processor first = (frame, next) -> {
            captured.set(next);
            next.accept(frame);
            if (failure != null) throwFailure(failure);
        };
        // The retained first-stage handle has a nonterminal successor. A missed
        // nextStage detach must retain this downstream processor and its payload.
        Processor processor = (frame, next) -> {
            Reference.reachabilityFence(processorState);
            next.accept(frame);
        };
        Pipeline pipeline = Pipeline.builder().add(first).add(processor).build();
        FrameSink output = frame -> Reference.reachabilityFence(outputState);
        Frame input = new Frame.Text("synthetic input");
        if (failure == null) pipeline.process(input, output);
        else assertSame(failure, assertThrows(Throwable.class, () -> pipeline.process(input, output)));
        return new Retired(captured.get(), List.of(
                new Referent("pipeline", new WeakReference<>(pipeline)),
                new Referent("first processor", new WeakReference<>(first)),
                new Referent("downstream processor", new WeakReference<>(processor)),
                new Referent("downstream processor state", new WeakReference<>(processorState)),
                new Referent("output sink", new WeakReference<>(output)),
                new Referent("output state", new WeakReference<>(outputState)),
                new Referent("input", new WeakReference<>(input)),
                new Referent("owner thread", new WeakReference<>(Thread.currentThread()))));
    }

    private static boolean hasLiveReferent(Retired retained) {
        return retained.references().stream().anyMatch(referent -> referent.reference().get() != null);
    }

    private static void assertRetired(FrameSink sink) {
        for (Frame frame : new Frame[] {new Frame.Text("late"), null}) {
            IllegalStateException error = assertThrows(IllegalStateException.class, () -> sink.accept(frame));
            assertEquals(RETIRED, error.getMessage());
        }
    }

    private static void throwFailure(Throwable failure) throws Exception {
        if (failure instanceof Exception exception) throw exception;
        if (failure instanceof Error error) throw error;
        throw new AssertionError("unexpected fixture failure type");
    }
}
