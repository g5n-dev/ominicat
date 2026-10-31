package io.ominicat.core;

import java.util.Objects;

/**
 * Try a secondary processor only when the primary fails before emitting anything.
 * Once output starts, failure is propagated to avoid replaying a partially delivered
 * response. Interruptions never trigger fallback. Deadlines belong to each adapter.
 */
public final class FallbackProcessor implements Processor {
    private final Processor primary;
    private final Processor secondary;
    public FallbackProcessor(Processor primary, Processor secondary) {
        this.primary = Objects.requireNonNull(primary, "primary");
        this.secondary = Objects.requireNonNull(secondary, "secondary");
        if (primary == secondary) throw new IllegalArgumentException("processors must be distinct owned instances");
        if (primary instanceof AsyncProcessor || secondary instanceof AsyncProcessor) {
            throw new IllegalArgumentException("FallbackProcessor owns synchronous children; use an async-aware provider for streaming failover");
        }
    }
    @Override public void process(Frame frame, FrameSink next) throws Exception {
        boolean[] emitted = {false};
        try {
            primary.process(frame, output -> { emitted[0] = true; next.accept(output); });
        } catch (Exception first) {
            if (emitted[0] || first instanceof InterruptedException || Thread.currentThread().isInterrupted()) throw first;
            try { secondary.process(frame, next); }
            catch (Exception last) { if (last != first) last.addSuppressed(first); throw last; }
        }
    }
    @Override public void onInterrupt() throws Exception {
        try { primary.onInterrupt(); }
        finally { secondary.onInterrupt(); }
    }
    @Override public void close() throws Exception {
        Exception failure = null;
        try { secondary.close(); } catch (Exception error) { failure = error; }
        try { primary.close(); } catch (Exception error) {
            if (failure == null) failure = error; else if (failure != error) failure.addSuppressed(error);
        }
        if (failure != null) throw failure;
    }
}
