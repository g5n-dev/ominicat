package io.ominicat.core;

import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Preserves an input envelope through a legacy processor. Each emitted payload gets
 * a new event ID and a direct causation link to the stage input. Raw frames remain raw.
 * Supply an ID sequence and deterministic clock for reproducible offline replay.
 */
public final class EnvelopeProcessor implements Processor {
    private final Processor delegate;
    private final Supplier<String> ids;
    private final LongSupplier clock;

    public EnvelopeProcessor(Processor delegate) {
        this(delegate, () -> UUID.randomUUID().toString(), System::nanoTime);
    }
    public EnvelopeProcessor(Processor delegate, Supplier<String> ids, LongSupplier clock) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        if (delegate instanceof AsyncProcessor) throw new IllegalArgumentException("use EnvelopeProcessor.wrap for an AsyncProcessor");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
    }
    /** Selects a decorator that preserves the optional asynchronous processor capability. */
    public static Processor wrap(Processor processor) {
        return wrap(processor, () -> UUID.randomUUID().toString(), System::nanoTime);
    }
    /** Replay-oriented decorator factory. Suppliers used by async callbacks must be thread-safe. */
    public static Processor wrap(Processor processor, Supplier<String> ids, LongSupplier clock) {
        if (processor instanceof AsyncProcessor asynchronous) return new AsyncEnvelopeAdapter(asynchronous, ids, clock);
        return new EnvelopeProcessor(processor, ids, clock);
    }
    @Override public void process(Frame frame, FrameSink next) throws Exception {
        if (frame instanceof FrameEnvelope envelope) {
            delegate.process(envelope.frame(), output -> next.accept(envelope.derive(output, ids.get(),
                    Math.max(envelope.emitTimeNanos(), clock.getAsLong()))));
        } else delegate.process(frame, next);
    }
    @Override public void onInterrupt() throws Exception { delegate.onInterrupt(); }
    @Override public void close() throws Exception { delegate.close(); }

    private static final class AsyncEnvelopeAdapter implements AsyncProcessor {
        private final AsyncProcessor delegate;
        private final Supplier<String> ids;
        private final LongSupplier clock;
        private AsyncEnvelopeAdapter(AsyncProcessor delegate, Supplier<String> ids, LongSupplier clock) {
            this.delegate = Objects.requireNonNull(delegate);
            this.ids = Objects.requireNonNull(ids);
            this.clock = Objects.requireNonNull(clock);
        }
        @Override public void processAsync(Frame frame, AsyncEmitter emitter) throws Exception {
            if (!(frame instanceof FrameEnvelope envelope)) { delegate.processAsync(frame, emitter); return; }
            delegate.processAsync(envelope.frame(), new AsyncEmitter() {
                @Override public boolean tryEmit(Frame emitted) {
                    return emitter.tryEmit(envelope.derive(emitted, ids.get(), Math.max(envelope.emitTimeNanos(), clock.getAsLong())));
                }
                @Override public void complete() { emitter.complete(); }
                @Override public void fail(Throwable cause) { emitter.fail(cause); }
                @Override public void onCancel(AutoCloseable handle) { emitter.onCancel(handle); }
                @Override public boolean isCancelled() { return emitter.isCancelled(); }
            });
        }
        @Override public void onInterrupt() throws Exception { delegate.onInterrupt(); }
        @Override public void close() throws Exception { delegate.close(); }
    }
}
