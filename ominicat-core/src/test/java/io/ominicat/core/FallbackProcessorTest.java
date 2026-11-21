package io.ominicat.core;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FallbackProcessorTest {
    @Test void asyncChildrenFailAtCompositionTime() {
        AsyncProcessor async = (f, emitter) -> emitter.complete();
        Processor sync = (f, next) -> next.accept(f);
        assertThrows(IllegalArgumentException.class, () -> new FallbackProcessor(async, sync));
        assertThrows(IllegalArgumentException.class, () -> new FallbackProcessor(sync, async));
        assertThrows(IllegalArgumentException.class, () -> new EnvelopeProcessor(async));
        assertInstanceOf(AsyncProcessor.class, EnvelopeProcessor.wrap(async));
    }
    @Test void fallbackBeforeOutputButNeverAfterOutput() throws Exception {
        var outputs = new ArrayList<Frame>();
        var fallback = new FallbackProcessor((f, n) -> { throw new IllegalStateException("offline"); },
                (f, n) -> n.accept(new Frame.Text("secondary")));
        fallback.process(new Frame.Text("input"), outputs::add);
        assertEquals(new Frame.Text("secondary"), outputs.get(0));
        var secondaryCalled = new AtomicBoolean();
        var partial = new FallbackProcessor((f, n) -> { n.accept(f); throw new IllegalStateException("midstream"); },
                (f, n) -> secondaryCalled.set(true));
        assertThrows(IllegalStateException.class, () -> partial.process(new Frame.Text("x"), outputs::add));
        assertFalse(secondaryCalled.get());
    }
    @Test void interruptionAndSinkFailuresDoNotFallback() {
        var called = new AtomicBoolean();
        var interrupted = new FallbackProcessor((f, n) -> { throw new InterruptedException(); }, (f, n) -> called.set(true));
        assertThrows(InterruptedException.class, () -> interrupted.process(new Frame.Text("x"), f -> { }));
        var sinkFailure = new FallbackProcessor((f, n) -> n.accept(f), (f, n) -> called.set(true));
        assertThrows(IllegalArgumentException.class, () -> sinkFailure.process(new Frame.Text("x"), f -> { throw new IllegalArgumentException(); }));
        assertFalse(called.get());
    }

    @Test void constructorOwnsDistinctSyncInstances() {
        assertThrows(NullPointerException.class, () -> new FallbackProcessor(null, (f, n) -> { }));
        assertThrows(NullPointerException.class, () -> new FallbackProcessor((f, n) -> { }, null));
        Processor identity = (f, n) -> n.accept(f);
        assertThrows(IllegalArgumentException.class, () -> new FallbackProcessor(identity, identity));
    }

    @Test void closedProcessorRefusesFurtherWork() throws Exception {
        var fallback = new FallbackProcessor((f, n) -> n.accept(f), (f, n) -> n.accept(f));
        fallback.close();
        assertThrows(IllegalStateException.class, () -> fallback.process(new Frame.Text("x"), f -> { }));
        fallback.close();
        fallback.onInterrupt();
    }

    @Test void interruptHooksRunBothChildrenAndAggregateFailures() throws Exception {
        var reset = new ArrayList<String>();
        var both = new FallbackProcessor(recording("primary", reset), recording("secondary", reset));
        both.onInterrupt();
        assertEquals(List.of("primary", "secondary"), reset);
        var primaryFailure = new java.io.IOException("primary reset failed");
        var secondaryFailure = new java.io.IOException("secondary reset failed");
        var failing = new FallbackProcessor(hookThrowing(primaryFailure), hookThrowing(secondaryFailure));
        var failure = assertThrows(java.io.IOException.class, failing::onInterrupt);
        assertEquals(1, failure.getSuppressed().length);
        assertEquals(secondaryFailure, failure.getSuppressed()[0]);
    }

    @Test void closeClosesSecondaryFirstAndAggregatesSuppressedFailures() throws Exception {
        var closed = new ArrayList<String>();
        var fallback = new FallbackProcessor(closing("primary", closed), closing("secondary", closed));
        fallback.close();
        assertEquals(List.of("secondary", "primary"), closed);
        var secondaryFailure = new java.io.IOException("secondary close failed");
        var primaryFailure = new java.io.IOException("primary close failed");
        var failing = new FallbackProcessor(closingThrowing(primaryFailure), closingThrowing(secondaryFailure));
        var failure = assertThrows(java.io.IOException.class, failing::close);
        assertEquals(secondaryFailure, failure);
        assertEquals(1, failure.getSuppressed().length);
        assertEquals(primaryFailure, failure.getSuppressed()[0]);
    }

    @Test void secondaryFailureSuppressesTheOriginalPrimaryFailure() {
        var fallback = new FallbackProcessor((f, n) -> { throw new IllegalStateException("primary"); },
                (f, n) -> { throw new java.io.IOException("secondary"); });
        var failure = assertThrows(java.io.IOException.class, () -> fallback.process(new Frame.Text("x"), f -> { }));
        assertEquals(1, failure.getSuppressed().length);
        assertEquals("primary", failure.getSuppressed()[0].getMessage());
    }

    private static Processor recording(String name, List<String> calls) {
        return new Processor() {
            @Override public void process(Frame frame, FrameSink next) throws Exception { next.accept(frame); }
            @Override public void onInterrupt() { calls.add(name); }
        };
    }

    private static Processor hookThrowing(Exception failure) {
        return new Processor() {
            @Override public void process(Frame frame, FrameSink next) throws Exception { next.accept(frame); }
            @Override public void onInterrupt() throws Exception { throw failure; }
        };
    }

    private static Processor closing(String name, List<String> calls) {
        return new Processor() {
            @Override public void process(Frame frame, FrameSink next) throws Exception { next.accept(frame); }
            @Override public void close() { calls.add(name); }
        };
    }

    private static Processor closingThrowing(Exception failure) {
        return new Processor() {
            @Override public void process(Frame frame, FrameSink next) throws Exception { next.accept(frame); }
            @Override public void close() throws Exception { throw failure; }
        };
    }
}
