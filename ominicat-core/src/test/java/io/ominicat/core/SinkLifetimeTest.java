package io.ominicat.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class SinkLifetimeTest {
    @Test void retainedSinkCannotEnterClosedDownstreamAfterSuccessfulCompletion() throws Exception {
        AtomicReference<FrameSink> retained = new AtomicReference<>();
        AtomicInteger downstreamCalls = new AtomicInteger();
        AtomicInteger downstreamCloses = new AtomicInteger();
        Processor downstream = new Processor() {
            @Override public void process(Frame frame, FrameSink next) throws Exception {
                downstreamCalls.incrementAndGet(); next.accept(frame);
            }
            @Override public void close() { downstreamCloses.incrementAndGet(); }
        };
        PipelineSession session = Pipeline.builder().add((frame, next) -> retained.set(next)).add(downstream)
                .build().openSession(SessionOptions.defaults(), frame -> { });
        session.trySubmit(new Frame.Text("request"));
        session.finish();
        session.completion().get(2, TimeUnit.SECONDS);
        assertEquals(1, downstreamCloses.get());
        assertThrows(IllegalStateException.class, () -> retained.get().accept(new Frame.Text("late")));
        assertEquals(0, downstreamCalls.get());
    }

    @Test void wrongThreadCannotInvokeNextWhileSynchronousStageIsStillActive() throws Exception {
        AtomicReference<FrameSink> retained = new AtomicReference<>();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger downstreamCalls = new AtomicInteger();
        PipelineSession session = Pipeline.builder().add((frame, next) -> {
            retained.set(next); entered.countDown(); release.await();
        }).add((frame, next) -> { downstreamCalls.incrementAndGet(); next.accept(frame); }).build()
                .openSession(SessionOptions.defaults(), frame -> { });
        session.trySubmit(new Frame.Text("request"));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        try {
            assertThrows(IllegalStateException.class, () -> retained.get().accept(new Frame.Text("wrong-thread")));
            assertEquals(0, downstreamCalls.get());
        } finally {
            release.countDown(); session.finish(); session.completion().get(2, TimeUnit.SECONDS);
        }
    }

    @Test void retainedSinkCannotBeReusedFromLaterInvocationOnTheSameWorker() throws Exception {
        AtomicReference<FrameSink> retained = new AtomicReference<>();
        AtomicInteger downstreamCalls = new AtomicInteger();
        PipelineSession session = Pipeline.builder().add((frame, next) -> {
            if (frame.equals(new Frame.Text("first"))) retained.set(next);
            else assertThrows(IllegalStateException.class, () -> retained.get().accept(new Frame.Text("captured")));
        }).add((frame, next) -> { downstreamCalls.incrementAndGet(); next.accept(frame); }).build()
                .openSession(SessionOptions.defaults(), frame -> { });
        session.trySubmit(new Frame.Text("first"));
        session.trySubmit(new Frame.Text("second"));
        session.finish(); session.completion().get(2, TimeUnit.SECONDS);
        assertEquals(0, downstreamCalls.get());
    }

    @Test void directDispatchAlsoEnforcesSynchronousSinkLifetime() throws Exception {
        AtomicReference<FrameSink> retained = new AtomicReference<>();
        AtomicInteger delivered = new AtomicInteger();
        Pipeline pipeline = Pipeline.builder().add((frame, next) -> retained.set(next)).build();
        pipeline.process(new Frame.Text("input"), frame -> delivered.incrementAndGet());
        assertThrows(IllegalStateException.class, () -> retained.get().accept(new Frame.Text("late")));
        assertEquals(0, delivered.get());
    }
}
