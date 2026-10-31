package io.ominicat.core;

import java.util.Objects;
import java.util.UUID;

/**
 * A first-class frame carrying correlation and media timing metadata.
 * Wrap stages with {@link EnvelopeProcessor} to preserve metadata across ordinary processors.
 * Nanosecond times belong to the same monotonic clock domain, not wall-clock time.
 * {@link #withFrame(Frame)} derives a new event ID and causation link while preserving
 * session, turn, correlation, and original timing metadata.
 */
public record FrameEnvelope(Frame frame, String id, String sessionId, String turnId,
                            String correlationId, String causationId,
                            long originTimeNanos, long ingestTimeNanos, long emitTimeNanos) implements Frame {
    public FrameEnvelope {
        Objects.requireNonNull(frame, "frame");
        requireId(id, "id");
        requireId(sessionId, "sessionId");
        requireId(turnId, "turnId");
        requireId(correlationId, "correlationId");
        Objects.requireNonNull(causationId, "causationId");
        if (!causationId.isEmpty()) requireId(causationId, "causationId");
        if (originTimeNanos > ingestTimeNanos) {
            throw new IllegalArgumentException("originTimeNanos must not exceed ingestTimeNanos in the same clock domain");
        }
        if (ingestTimeNanos > emitTimeNanos) throw new IllegalArgumentException("emit time must not precede ingest time");
        if (frame instanceof FrameEnvelope) throw new IllegalArgumentException("nested envelopes are not supported");
    }

    /** Source compatibility constructor for an event not yet emitted downstream. */
    public FrameEnvelope(Frame frame, String id, String sessionId, String turnId,
                         String correlationId, String causationId, long originTimeNanos, long ingestTimeNanos) {
        this(frame, id, sessionId, turnId, correlationId, causationId, originTimeNanos, ingestTimeNanos, ingestTimeNanos);
    }

    /** Derive a downstream event whose direct cause is this envelope's ID. */
    public FrameEnvelope withFrame(Frame replacement) {
        return new FrameEnvelope(replacement, UUID.randomUUID().toString(), sessionId,
                turnId, correlationId, id, originTimeNanos, ingestTimeNanos, Math.max(emitTimeNanos, System.nanoTime()));
    }

    /** Deterministic derivation when IDs and clock readings are supplied by a replay driver. */
    public FrameEnvelope derive(Frame replacement, String nextId, long emittedAtNanos) {
        return new FrameEnvelope(replacement, nextId, sessionId, turnId, correlationId, id,
                originTimeNanos, ingestTimeNanos, emittedAtNanos);
    }

    private static void requireId(String value, String name) {
        if (Objects.requireNonNull(value, name).isBlank() || value.length() > 256) throw new IllegalArgumentException(name + " must contain 1..256 characters");
    }
}
