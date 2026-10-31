package io.ominicat.core;

import java.lang.ref.WeakReference;
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
        if (delegate instanceof AsyncProcessor) throw new IllegalArgumentException("use EnvelopeProcessor.wrap for AsyncProcessor");
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
        SynchronousSink invocation = new SynchronousSink(frame instanceof FrameEnvelope envelope
                ? output -> next.accept(envelope.derive(output, ids.get(), Math.max(envelope.emitTimeNanos(), clock.getAsLong())))
                : next);
        try { delegate.process(frame instanceof FrameEnvelope envelope ? envelope.frame() : frame, invocation); }
        finally { invocation.invalidate(); }
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
            EnvelopeStream stream = new EnvelopeStream(envelope, emitter, ids, clock);
            try { delegate.processAsync(envelope.frame(), stream); }
            finally { stream.endRegistration(); }
        }
        @Override public void onInterrupt() throws Exception { delegate.onInterrupt(); }
        @Override public void close() throws Exception { delegate.close(); }
    }

    /** Holds metadata only, never the input payload or an enclosing adapter instance. */
    private record Metadata(String id, String session, String turn, String correlation,
                            long origin, long ingest, long emitted) {
        Metadata(FrameEnvelope source) {
            this(source.id(), source.sessionId(), source.turnId(), source.correlationId(),
                    source.originTimeNanos(), source.ingestTimeNanos(), source.emitTimeNanos());
        }
        FrameEnvelope derive(Frame frame, String nextId, long now) {
            return new FrameEnvelope(frame, nextId, session, turn, correlation, id, origin, ingest, Math.max(emitted, now));
        }
    }

    private static final class EnvelopeStream implements AsyncEmitter {
        private AsyncEmitter emitter;
        private Metadata metadata;
        private Supplier<String> ids;
        private LongSupplier clock;
        private final WeakReference<Thread> registrationThread = new WeakReference<>(Thread.currentThread());
        private boolean registrationOpen = true;
        private boolean cancellationRegistered;
        private boolean finished;
        private boolean cancelled;
        private boolean cancellationHandled;
        private AutoCloseable providerCancellation;

        EnvelopeStream(FrameEnvelope source, AsyncEmitter emitter, Supplier<String> ids, LongSupplier clock) {
            this.metadata = new Metadata(source);
            this.emitter = emitter;
            this.ids = ids;
            this.clock = clock;
            emitter.onCancel(this::cancelOnWorker);
        }
        @Override public synchronized boolean tryEmit(Frame frame) {
            Objects.requireNonNull(frame, "frame");
            if (finished) return false;
            return emitter.tryEmit(metadata.derive(frame, ids.get(), clock.getAsLong()));
        }
        @Override public synchronized void complete() {
            if (finished) return;
            emitter.complete();
            cancelled = emitter.isCancelled();
            finished = true;
            if (!cancelled) providerCancellation = null;
            detach();
        }
        @Override public synchronized void fail(Throwable failure) {
            Objects.requireNonNull(failure, "failure");
            if (finished) return;
            emitter.fail(failure);
            cancelled = emitter.isCancelled();
            finished = true;
            if (!cancelled) providerCancellation = null;
            detach();
        }
        @Override public synchronized void onCancel(AutoCloseable action) {
            Objects.requireNonNull(action, "cancellation");
            if (!registrationOpen || Thread.currentThread() != registrationThread.get()) {
                throw new IllegalStateException("register cancellation during processAsync on the session worker");
            }
            if (cancellationRegistered) throw new IllegalStateException("cancellation already registered");
            cancellationRegistered = true;
            if (!finished || (cancelled && !cancellationHandled)) providerCancellation = action;
        }
        @Override public synchronized boolean isCancelled() {
            return cancelled || (!finished && emitter.isCancelled());
        }
        synchronized void endRegistration() { registrationOpen = false; registrationThread.clear(); }
        private void cancelOnWorker() throws Exception {
            AutoCloseable action;
            synchronized (this) {
                if (cancellationHandled) return;
                cancellationHandled = true;
                cancelled = true;
                finished = true;
                action = providerCancellation;
                providerCancellation = null;
                detach();
            }
            // Provider close may wait for its callback thread; never hold this monitor while closing.
            if (action != null) action.close();
        }
        private void detach() {
            emitter = null;
            metadata = null;
            ids = null;
            clock = null;
        }
    }
}
