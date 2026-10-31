package io.ominicat.core;

/**
 * A per-pipeline processing stage. Emit zero, one, or many frames to {@code next}.
 * Emission is synchronous and must finish before {@code process} returns. Blocking
 * I/O is supported on session virtual threads and should respond to interruption.
 * The runtime rejects captured sinks after the invocation returns and any sink
 * emission from another thread; use AsyncProcessor for provider callbacks.
 */
@FunctionalInterface
public interface Processor extends AutoCloseable {
    /** Process one frame, forwarding any resulting frames synchronously. */
    void process(Frame frame, FrameSink next) throws Exception;

    /**
     * Clear state belonging to an interrupted turn. The session worker invokes this
     * serially, in pipeline order, before processing frames from the new generation.
     * Repeated interrupts may coalesce into one callback.
     */
    default void onInterrupt() throws Exception { }

    /** Release stage resources. Sessions call this once, in reverse stage order. */
    @Override default void close() throws Exception { }
}
