package io.ominicat.core;

/**
 * Optional streaming stage SPI. Start asynchronous provider work and return promptly;
 * emit zero or more frames through the callback handle, then call complete or fail.
 * A session caps live handles at its queue capacity. Graceful finish waits for every
 * handle, so providers must always signal a terminal outcome. Use cancellation for
 * provider timeouts that cannot finish normally.
 */
@FunctionalInterface
public interface AsyncProcessor extends Processor {
    /** Start one streaming operation. Register its cancellation action before returning. */
    void processAsync(Frame frame, AsyncEmitter emitter) throws Exception;

    /** Streaming stages require a session scheduler and cannot run in direct dispatch. */
    @Override default void process(Frame frame, FrameSink next) {
        throw new IllegalStateException("AsyncProcessor requires Pipeline.openSession");
    }
}
