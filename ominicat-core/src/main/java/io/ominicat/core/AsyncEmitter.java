package io.ominicat.core;

/**
 * A generation-scoped streaming callback handle. Callbacks may run on arbitrary
 * provider threads; accepted emissions reenter the bounded session queue at the
 * next stage and never execute downstream processors on a provider thread.
 */
public interface AsyncEmitter {
    /**
     * Enqueue a continuation without blocking. False explicitly means saturation,
     * completion, or invalidation; the provider must choose its overflow policy.
     */
    boolean tryEmit(Frame frame);

    /** Mark this stream complete. No further emissions are accepted; idempotent. */
    void complete();

    /** Fail the session, unless this stream already completed or was invalidated. */
    void fail(Throwable failure);

    /**
     * Register one cancellation action during processAsync, on the session worker.
     * The worker invokes it on interruption, failure, or session close, before stage
     * reset/cleanup. Normal completion does not invoke it. Late or duplicate
     * registration is a programming error.
     */
    void onCancel(AutoCloseable cancellation);

    /** True when this operation was invalidated or cancelled; an earlier normal completion stays complete. */
    boolean isCancelled();
}
