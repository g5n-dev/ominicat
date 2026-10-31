package io.ominicat.conversation;

import io.ominicat.core.Frame;
import java.util.Objects;

/** One final, speaker-attributed transcript interval, in milliseconds. */
public record Turn(String text, String speaker, long startMillis, long endMillis, double confidence,
                   String segmentId, long revision, String role) {
    public Turn {
        Objects.requireNonNull(text, "text");
        if (speaker == null || speaker.isBlank()) {
            throw new IllegalArgumentException("speaker must not be blank");
        }
        if (startMillis < 0 || endMillis < startMillis) {
            throw new IllegalArgumentException("invalid turn interval");
        }
        if (!Double.isFinite(confidence) || confidence != -1 && (confidence < 0 || confidence > 1)) {
            throw new IllegalArgumentException("confidence must be -1 (unknown) or between 0 and 1");
        }
        if (segmentId == null || segmentId.isBlank() || segmentId.length() > 256) {
            throw new IllegalArgumentException("segmentId must contain 1..256 characters");
        }
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        if (role == null || role.isBlank() || role.length() > 128) {
            throw new IllegalArgumentException("role must contain 1..128 characters");
        }
    }

    public Turn(String text, String speaker, long startMillis, long endMillis, double confidence) {
        this(text, speaker, startMillis, endMillis, confidence,
                speaker + ":" + startMillis + "-" + endMillis, 0, speaker);
    }

    public static Turn from(Frame.Transcript transcript) {
        if (!transcript.finalResult()) {
            throw new IllegalArgumentException("a turn requires a final transcript");
        }
        return new Turn(transcript.text(), transcript.speaker(), transcript.startMillis(),
                transcript.endMillis(), transcript.confidence(), transcript.segmentId(),
                transcript.revision(), transcript.role());
    }
}
