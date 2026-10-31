package io.ominicat.core;

/** A synchronous receiver. Exceptions fail the current frame and its session. */
@FunctionalInterface
public interface FrameSink {
    /** Accept a frame; a slow receiver naturally applies backpressure to the worker. */
    void accept(Frame frame) throws Exception;
}
