package io.ominicat.core;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Try a secondary processor only when the primary fails before emitting anything.
 * Once output starts, failure is propagated to avoid replaying a partially delivered
 * response. Interruptions never trigger fallback. Deadlines belong to each adapter.
 */
public final class FallbackProcessor implements Processor {
    private final Processor primary;
    private final Processor secondary;
    private final AtomicBoolean closed = new AtomicBoolean();
    public FallbackProcessor(Processor primary, Processor secondary) {
        this.primary = Objects.requireNonNull(primary, "primary");
        this.secondary = Objects.requireNonNull(secondary, "secondary");
        if (primary == secondary) throw new IllegalArgumentException("processors must be distinct owned instances");
        if (primary instanceof AsyncProcessor || secondary instanceof AsyncProcessor) {
            throw new IllegalArgumentException("FallbackProcessor owns synchronous children; use an async-aware provider for streaming failover");
        }
    }
    @Override public void process(Frame frame, FrameSink next) throws Exception {
        if (closed.get()) throw new IllegalStateException("fallback processor is closed");
        boolean[] emitted = {false};
        try {
            invoke(primary, frame, output -> { emitted[0] = true; next.accept(output); });
        } catch (Exception first) {
            if (emitted[0] || first instanceof InterruptedException || Thread.currentThread().isInterrupted()) throw first;
            try { invoke(secondary, frame, next); }
            catch (Exception last) { if (last != first) last.addSuppressed(first); throw last; }
        }
    }
    private static void invoke(Processor processor, Frame frame, FrameSink next) throws Exception {
        SynchronousSink invocation = new SynchronousSink(next);
        try { processor.process(frame, invocation); }
        finally { invocation.invalidate(); }
    }
    @Override public void onInterrupt() throws Exception {
        if (closed.get()) return;
        Throwable failure = null;
        try { primary.onInterrupt(); } catch (Throwable error) { failure = error; }
        try { secondary.onInterrupt(); } catch (Throwable error) {
            if (failure == null) failure = error; else if (failure != error) failure.addSuppressed(error);
        }
        rethrow(failure);
    }
    @Override public void close() throws Exception {
        if (!closed.compareAndSet(false, true)) return;
        Throwable failure = null;
        try { secondary.close(); } catch (Throwable error) { failure = error; }
        try { primary.close(); } catch (Throwable error) {
            if (failure == null) failure = error; else if (failure != error) failure.addSuppressed(error);
        }
        rethrow(failure);
    }
    private static void rethrow(Throwable failure) throws Exception {
        if (failure instanceof Exception exception) throw exception;
        if (failure instanceof Error error) throw error;
        if (failure != null) throw new Exception("processor lifecycle failed", failure);
    }
}
