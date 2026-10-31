package io.ominicat.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * A unit of multimodal data or a lifecycle signal. Applications may implement this
 * interface to add domain events without changing the kernel.
 */
public interface Frame {
    /** A text chunk. An empty chunk is valid, for example at a response boundary. */
    record Text(String text) implements Frame {
        public Text { Objects.requireNonNull(text, "text"); }
    }

    /**
     * Interleaved, signed 16-bit little-endian PCM. The remaining bytes are copied
     * on construction; accessors expose independent, read-only little-endian views.
     * streamId identifies a logical track; startSample is its per-channel sample
     * offset, or -1 when the adapter has no timeline information.
     */
    record Audio(ByteBuffer data, int sampleRate, int channels, String streamId, long startSample) implements Frame {
        /** PCM with unknown timeline position, retained for simple utterance adapters. */
        public Audio(ByteBuffer data, int sampleRate, int channels) {
            this(data, sampleRate, channels, "default", -1);
        }
        public Audio {
            Objects.requireNonNull(streamId, "streamId");
            if (streamId.isBlank()) throw new IllegalArgumentException("streamId must not be blank");
            if (startSample < -1) throw new IllegalArgumentException("startSample must be -1 (unknown) or nonnegative");
            if (sampleRate <= 0 || sampleRate > 384_000) {
                throw new IllegalArgumentException("sampleRate must be in 1..384000 Hz");
            }
            if (channels <= 0 || channels > 32) {
                throw new IllegalArgumentException("channels must be in 1..32");
            }
            Objects.requireNonNull(data, "data");
            if (!data.hasRemaining() || data.remaining() % (2 * channels) != 0) {
                throw new IllegalArgumentException("PCM payload must contain complete 16-bit sample frames");
            }
            data = snapshot(data);
        }
        @Override public ByteBuffer data() { return data.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN); }
    }

    /** An encoded image; the payload is defensively copied. */
    record Image(ByteBuffer data, String mimeType) implements Frame {
        public Image {
            Objects.requireNonNull(mimeType, "mimeType");
            if (!mimeType.matches("image/[A-Za-z0-9.+-]+")) {
                throw new IllegalArgumentException("mimeType must be an image media type");
            }
            Objects.requireNonNull(data, "data");
            if (!data.hasRemaining()) throw new IllegalArgumentException("image payload must not be empty");
            data = snapshot(data);
        }
        @Override public ByteBuffer data() { return data.asReadOnlyBuffer(); }
    }

    /**
     * A speaker-attributed transcript revision. confidence is 0..1, or -1 when
     * unavailable. segmentId remains stable across revisions; revision is monotonic
     * within a segment and role is the domain role, independent of speaker identity.
     */
    record Transcript(String text, String speaker, long startMillis, long endMillis,
                      boolean finalResult, double confidence, String segmentId,
                      long revision, String role) implements Frame {
        /** Stable ID derived from speaker and interval; use explicit IDs for revisable ASR segments. */
        public Transcript(String text, String speaker, long startMillis, long endMillis,
                          boolean finalResult, double confidence) {
            this(text, speaker, startMillis, endMillis, finalResult, confidence,
                    speaker + ":" + startMillis + "-" + endMillis, 0, speaker);
        }
        public Transcript {
            Objects.requireNonNull(segmentId, "segmentId");
            Objects.requireNonNull(role, "role");
            if (segmentId.isBlank()) throw new IllegalArgumentException("segmentId must not be blank");
            if (role.isBlank()) throw new IllegalArgumentException("role must not be blank");
            if (revision < 0) throw new IllegalArgumentException("revision must be nonnegative");
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(speaker, "speaker");
            if (speaker.isBlank()) throw new IllegalArgumentException("speaker must not be blank");
            if (startMillis < 0 || endMillis < startMillis) {
                throw new IllegalArgumentException("transcript timestamps must satisfy 0 <= startMillis <= endMillis");
            }
            if (!Double.isFinite(confidence) || (confidence != -1 && (confidence < 0 || confidence > 1))) {
                throw new IllegalArgumentException("confidence must be in 0..1, or -1 when unavailable");
            }
        }
    }

    /** An application-visible control event. Signals do not implicitly change session state. */
    record Control(Signal signal) implements Frame {
        public Control { Objects.requireNonNull(signal, "signal"); }
    }

    /** A classified error event. Messages must be sanitized by the emitting adapter. */
    record Failure(String code, boolean retryable, String scope, String nodeId, String message) implements Frame {
        public Failure {
            requireLabel(code, "code", 128);
            requireLabel(scope, "scope", 128);
            requireLabel(nodeId, "nodeId", 256);
            requireLabel(message, "message", 2048);
        }
    }

    /** An explicit cancellation request; drivers decide which owned work matches its scope. */
    record Cancel(String reason, CancelScope scope, String targetId) implements Frame {
        public Cancel {
            requireLabel(reason, "reason", 256);
            Objects.requireNonNull(scope, "scope");
            requireLabel(targetId, "targetId", 256);
        }
    }

    enum CancelScope { REQUEST, TURN, SESSION }

    /** Lifecycle, turn-boundary, and voice-activity events. */
    enum Signal { START, END, INTERRUPT, TURN_END, SPEECH_STARTED, SPEECH_STOPPED }

    private static ByteBuffer snapshot(ByteBuffer source) {
        ByteBuffer copy = ByteBuffer.allocate(source.remaining());
        copy.put(source.duplicate()).flip();
        return copy.asReadOnlyBuffer();
    }

    private static void requireLabel(String value, String name, int maxLength) {
        if (Objects.requireNonNull(value, name).isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(name + " must contain 1.." + maxLength + " characters");
        }
    }
}
