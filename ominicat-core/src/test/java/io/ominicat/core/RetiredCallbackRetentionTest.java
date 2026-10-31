package io.ominicat.core;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.lang.management.ManagementFactory;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
class RetiredCallbackRetentionTest {
    private record Retained(WeakReference<Object> outputState, Object callback) { }
    private record Active(WeakReference<PipelineSession> session, AsyncEmitter callback) { }

    @Test void activeCallbackKeepsItsSessionAliveUntilTheOperationTerminates() throws Exception {
        assumeFalse(ManagementFactory.getRuntimeMXBean().getInputArguments().contains("-XX:+DisableExplicitGC"));
        Active active = activeCallback();
        for (int attempt = 0; attempt < 10; attempt++) { System.gc(); Thread.sleep(15); }
        PipelineSession session = active.session().get();
        try {
            assertNotNull(session, "active callback lost its parked virtual-thread session before complete");
            assertTrue(active.callback().tryEmit(new Frame.Text("still live")));
            active.callback().complete(); session.finish(); session.completion().get(2, TimeUnit.SECONDS);
        } finally {
            active.callback().complete();
            if (session != null) session.close();
        }
        Reference.reachabilityFence(active.callback());
    }

    private static Active activeCallback() throws Exception {
        ArrayBlockingQueue<AsyncEmitter> started = new ArrayBlockingQueue<>(1);
        PipelineSession session = Pipeline.builder().add((AsyncProcessor) (frame, emitter) -> started.add(emitter))
                .build().openSession(new SessionOptions(2), frame -> { });
        assertTrue(session.trySubmit(new Frame.Text("request")));
        AsyncEmitter callback = started.poll(2, TimeUnit.SECONDS);
        assertNotNull(callback);
        return new Active(new WeakReference<>(session), callback);
    }

    @Test void retiredStreamingCallbackDoesNotRetainTerminatedSessionOutputGraph() throws Exception {
        assumeFalse(ManagementFactory.getRuntimeMXBean().getInputArguments().contains("-XX:+DisableExplicitGC"));
        for (int scenario = 0; scenario < 3; scenario++) {
            Retained retained = retiredAsyncCallback(scenario);
            assertEventuallyCollected(retained, "async terminal scenario=" + scenario);
        }
    }

    @Test void capturedInvalidSynchronousSinkDoesNotRetainItsDownstreamGraph() throws Exception {
        assumeFalse(ManagementFactory.getRuntimeMXBean().getInputArguments().contains("-XX:+DisableExplicitGC"));
        Retained retained = retiredSyncSink();
        assertEventuallyCollected(retained, "sync captured sink");
    }

    @Test void retiredEnvelopeCallbackReleasesInputPayloadDelegateAndIdSupplierGraphs() throws Exception {
        assumeFalse(ManagementFactory.getRuntimeMXBean().getInputArguments().contains("-XX:+DisableExplicitGC"));
        for (int scenario = 0; scenario < 3; scenario++) {
            for (Retained retained : retiredEnvelopeCallback(scenario)) {
                assertEventuallyCollected(retained, "envelope async scenario=" + scenario);
            }
        }
    }

    @Test void capturedEnvelopeSynchronousSinkReleasesInputPayloadAndDelegateGraphs() throws Exception {
        assumeFalse(ManagementFactory.getRuntimeMXBean().getInputArguments().contains("-XX:+DisableExplicitGC"));
        for (Retained retained : retiredEnvelopeSyncSink()) assertEventuallyCollected(retained, "envelope sync capture");
    }

    private static java.util.List<Retained> retiredEnvelopeCallback(int scenario) throws Exception {
        Object delegateState = new byte[1024 * 1024];
        Object idState = new byte[1024 * 1024];
        Frame.Audio input = new Frame.Audio(java.nio.ByteBuffer.allocate(1024 * 1024), 16000, 1);
        WeakReference<Object> delegateWeak = new WeakReference<>(delegateState);
        WeakReference<Object> idsWeak = new WeakReference<>(idState);
        WeakReference<Object> inputWeak = new WeakReference<>(input);
        ArrayBlockingQueue<AsyncEmitter> started = new ArrayBlockingQueue<>(1);
        Processor wrapped = EnvelopeProcessor.wrap((AsyncProcessor) (frame, emitter) -> {
            Reference.reachabilityFence(delegateState);
            emitter.onCancel(() -> Reference.reachabilityFence(delegateState));
            started.add(emitter);
        }, () -> { Reference.reachabilityFence(idState); return "derived"; }, () -> 10);
        PipelineSession session = Pipeline.builder().add(wrapped).build().openSession(new SessionOptions(2), frame -> { });
        session.trySubmit(new FrameEnvelope(input, "id", "s", "t", "c", "", 0, 1));
        AsyncEmitter retained = started.poll(2, TimeUnit.SECONDS);
        assertNotNull(retained);
        if (scenario == 0) { retained.complete(); session.finish(); }
        else if (scenario == 1) { session.interrupt(); session.finish(); }
        else session.close();
        try { session.completion().get(2, TimeUnit.SECONDS); }
        catch (ExecutionException failure) { assertEquals(2, scenario); }
        return java.util.List.of(new Retained(delegateWeak, retained), new Retained(idsWeak, retained), new Retained(inputWeak, retained));
    }

    private static java.util.List<Retained> retiredEnvelopeSyncSink() throws Exception {
        Object delegateState = new byte[1024 * 1024];
        Frame.Audio input = new Frame.Audio(java.nio.ByteBuffer.allocate(1024 * 1024), 16000, 1);
        WeakReference<Object> delegateWeak = new WeakReference<>(delegateState);
        WeakReference<Object> inputWeak = new WeakReference<>(input);
        java.util.concurrent.atomic.AtomicReference<FrameSink> retained = new java.util.concurrent.atomic.AtomicReference<>();
        Processor wrapped = EnvelopeProcessor.wrap((frame, next) -> {
            Reference.reachabilityFence(delegateState); retained.set(next);
        });
        wrapped.process(new FrameEnvelope(input, "id", "s", "t", "c", "", 0, 1), frame -> { });
        wrapped.close();
        return java.util.List.of(new Retained(delegateWeak, retained.get()), new Retained(inputWeak, retained.get()));
    }

    private static Retained retiredAsyncCallback(int scenario) throws Exception {
        Object outputState = new byte[1024 * 1024];
        WeakReference<Object> weak = new WeakReference<>(outputState);
        ArrayBlockingQueue<AsyncEmitter> started = new ArrayBlockingQueue<>(1);
        PipelineSession session = Pipeline.builder().add((AsyncProcessor) (frame, emitter) -> started.add(emitter))
                .build().openSession(new SessionOptions(2), frame -> Reference.reachabilityFence(outputState));
        session.trySubmit(new Frame.Text("request"));
        AsyncEmitter retained = started.poll(2, TimeUnit.SECONDS);
        assertNotNull(retained);
        if (scenario == 0) { retained.complete(); session.finish(); }
        else if (scenario == 1) { session.interrupt(); session.finish(); }
        else session.close();
        try { session.completion().get(2, TimeUnit.SECONDS); }
        catch (ExecutionException failure) { assertEquals(2, scenario); }
        return new Retained(weak, retained);
    }

    private static Retained retiredSyncSink() throws Exception {
        Object outputState = new byte[1024 * 1024];
        WeakReference<Object> weak = new WeakReference<>(outputState);
        java.util.concurrent.atomic.AtomicReference<FrameSink> captured = new java.util.concurrent.atomic.AtomicReference<>();
        Pipeline pipeline = Pipeline.builder().add((frame, next) -> captured.set(next)).build();
        pipeline.process(new Frame.Text("request"), frame -> Reference.reachabilityFence(outputState));
        return new Retained(weak, captured.get());
    }

    private static void assertEventuallyCollected(Retained retained, String trace) throws InterruptedException {
        for (int attempt = 0; attempt < 30 && retained.outputState().get() != null; attempt++) {
            System.gc(); Thread.sleep(15);
        }
        assertTrue(retained.outputState().get() == null, trace + ": retired callback retains terminated output state");
        Reference.reachabilityFence(retained.callback());
    }
}
