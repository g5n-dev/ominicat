package io.ominicat.core;

/** Internal invocation boundary; asynchronous extensions must use AsyncEmitter. */
final class SynchronousSink implements FrameSink {
    private FrameSink delegate;
    private Thread owner = Thread.currentThread();

    SynchronousSink(FrameSink delegate) { this.delegate = delegate; }

    @Override public void accept(Frame frame) throws Exception {
        Thread invocationOwner = owner;
        if (invocationOwner == null) {
            throw new IllegalStateException("synchronous sink is no longer valid after process returns");
        }
        if (Thread.currentThread() != invocationOwner) {
            throw new IllegalStateException("synchronous sink may only emit on its invocation thread; use AsyncProcessor for callbacks");
        }
        // Only the invocation thread reaches this point, so it also serializes invalidation.
        delegate.accept(frame);
    }

    void invalidate() {
        // Detach the downstream graph and worker even if a plugin retains this invalid handle.
        owner = null;
        delegate = null;
    }
}
