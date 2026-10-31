package io.ominicat.core;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;

/**
 * An independently delivered, bounded output branch. A slow external destination
 * cannot block the caller: saturation is explicit rejection. No retries or delivery
 * guarantees beyond successful invocation of the supplied sink are implied. A sink
 * that performs blocking I/O should honor interruption for cooperative cancellation.
 */
public final class AsyncOutlet implements FrameSink, AutoCloseable {
    private final PipelineSession delivery;

    /** Start one delivery worker for this runtime with at most {@code capacity} pending frames. */
    public AsyncOutlet(int capacity, FrameSink delegate) {
        Objects.requireNonNull(delegate, "delegate");
        delivery = Pipeline.builder().build().openSession(new SessionOptions(capacity), delegate);
    }

    /** Enqueue without blocking; reject explicitly when full, failed, draining, or closed. */
    @Override public void accept(Frame frame) {
        if (!tryAccept(frame)) throw new RejectedExecutionException("async outlet is full or no longer accepting frames");
    }

    /** Nonthrowing saturation check. A false return means the frame was never accepted. */
    public boolean tryAccept(Frame frame) { return delivery.trySubmit(frame); }

    /** Drain all accepted output before completing and closing intake. */
    public void finish() { delivery.finish(); }

    /** Invalidate pending and in-flight output for an interrupted turn; keep intake open. */
    public void interrupt() { delivery.interrupt(); }

    /** Completes after delivery termination; sink failures and cancellation remain observable. */
    public CompletableFuture<Void> completion() { return delivery.completion(); }

    /** processed counts successful sink calls; accepted, rejected, and cancelled retain session semantics. */
    public SessionMetrics metrics() { return delivery.metrics(); }

    /** Cancel delivery immediately, without waiting for a blocking sink to stop. */
    @Override public void close() { delivery.close(); }
}
