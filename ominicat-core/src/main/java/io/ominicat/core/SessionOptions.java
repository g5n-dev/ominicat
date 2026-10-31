package io.ominicat.core;

/** Resource limits for a session. Capacity bounds pending work items and, separately, live async operations. */
public record SessionOptions(int queueCapacity) {
    public SessionOptions {
        if (queueCapacity < 1 || queueCapacity > 1_000_000) {
            throw new IllegalArgumentException("queueCapacity must be in 1..1000000");
        }
    }
    /** Conservative default: at most 256 frames waiting behind one active frame. */
    public static SessionOptions defaults() { return new SessionOptions(256); }
}
