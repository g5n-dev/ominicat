package io.ominicat.conversation.reference;

import io.ominicat.core.Frame;
import java.util.Locale;
import java.util.Objects;

/**
 * An immutable, producer-qualified segment version and its source coordinates.
 * This optional value contract does not edit transcripts, allocate identities,
 * resolve media, authorize access, or establish a shared clock between sources.
 * Opaque identifiers retain their original case and separators. Identifier limits
 * are UTF-16 code units; ISO control characters and malformed surrogate pairs are
 * rejected. No trimming or URI/path normalization occurs.
 */
public record MediaReference(Source source, String producerScope, String segmentId,
                             long revision, Position position) {
    public static final int MAX_ID_CHARS = 256;

    public MediaReference {
        Objects.requireNonNull(source, "source");
        producerScope = label(producerScope, "producerScope", MAX_ID_CHARS);
        segmentId = label(segmentId, "segmentId", MAX_ID_CHARS);
        if (revision < 0) throw new IllegalArgumentException("revision must be nonnegative");
        Objects.requireNonNull(position, "position");
    }

    /** Supported coordinate profiles, not an automatic conversion registry. */
    public enum Unit { PCM_SAMPLES, MEDIA_MILLISECONDS, FRAME_INDEX }

    /** RANGE is nonempty and half-open; POINT is explicitly zero-width; UNKNOWN has no coordinate. */
    public enum Kind { RANGE, POINT, UNKNOWN }

    /**
     * A source binding and optional required content fingerprint. Namespace is at
     * most 128 characters; id, version, track and timeBasis are each 1..256 characters.
     * The version is an opaque application label, independent of the SHA-256 claim.
     * sha256 is either empty (unavailable) or 64 hexadecimal characters. No hashing
     * or file/URI lookup occurs here. timeBasis identifies the caller's coordinate
     * origin; equal units alone do not establish equal timelines.
     */
    public record Source(String namespace, String id, String version, String track,
                         String timeBasis, Unit unit, int sampleRate, String sha256) {
        public Source {
            namespace = label(namespace, "namespace", 128);
            id = label(id, "id", MAX_ID_CHARS);
            version = label(version, "version", MAX_ID_CHARS);
            track = label(track, "track", MAX_ID_CHARS);
            timeBasis = label(timeBasis, "timeBasis", MAX_ID_CHARS);
            Objects.requireNonNull(unit, "unit");
            if (unit == Unit.PCM_SAMPLES ? sampleRate < 1 || sampleRate > 384_000 : sampleRate != 0) {
                throw new IllegalArgumentException("PCM sample rate must be 1..384000; other units require zero");
            }
            Objects.requireNonNull(sha256, "sha256");
            if (!sha256.isEmpty() && (sha256.length() != 64 || !sha256.matches("[0-9a-fA-F]{64}"))) {
                throw new IllegalArgumentException("sha256 must be empty or 64 hexadecimal characters");
            }
            sha256 = sha256.toLowerCase(Locale.ROOT);
        }

        /**
         * Check a caller-resolved binding, including a required fingerprint when
         * this reference supplies one. A missing resolved digest cannot match a
         * required digest. If this digest is unknown, no content proof is claimed.
         * The caller owns obtaining and verifying the resolved metadata.
         */
        public void requireMatching(Source resolved) {
            Objects.requireNonNull(resolved, "resolved");
            if (!namespace.equals(resolved.namespace) || !id.equals(resolved.id)
                    || !version.equals(resolved.version) || !track.equals(resolved.track)
                    || !timeBasis.equals(resolved.timeBasis) || unit != resolved.unit
                    || sampleRate != resolved.sampleRate) {
                throw new IllegalArgumentException("resolved source binding does not match");
            }
            if (!sha256.isEmpty() && !sha256.equals(resolved.sha256)) {
                throw new IllegalArgumentException("resolved source fingerprint does not match required sha256");
            }
        }
    }

    /** Numeric bounds in the enclosing source's unit; unknown is never inferred to be zero. */
    public record Position(Kind kind, long start, long end) {
        public Position {
            Objects.requireNonNull(kind, "kind");
            boolean valid = switch (kind) {
                case RANGE -> start >= 0 && end > start;
                case POINT -> start >= 0 && start == end;
                case UNKNOWN -> start == -1 && end == -1;
            };
            if (!valid) throw new IllegalArgumentException("invalid bounds for position kind");
        }
        public static Position range(long startInclusive, long endExclusive) {
            return new Position(Kind.RANGE, startInclusive, endExclusive);
        }
        public static Position point(long at) { return new Position(Kind.POINT, at, at); }
        public static Position unknown() { return new Position(Kind.UNKNOWN, -1, -1); }
    }

    /**
     * Bind one PCM16 frame without copying its payload or changing legacy semantics.
     * The supplied source must name its stream and sample rate. Unknown startSample
     * remains UNKNOWN; the adapter does not invent a cumulative cursor.
     */
    public static MediaReference fromPcm16(Source source, String producerScope, String segmentId,
                                         long revision, Frame.Audio audio) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(audio, "audio");
        if (source.unit != Unit.PCM_SAMPLES || source.sampleRate != audio.sampleRate()
                || !source.track.equals(audio.streamId())) {
            throw new IllegalArgumentException("PCM source unit, rate and track must match the audio frame");
        }
        long samplesPerChannel = audio.data().remaining() / (2L * audio.channels());
        Position position = audio.startSample() == -1 ? Position.unknown()
                : Position.range(audio.startSample(), Math.addExact(audio.startSample(), samplesPerChannel));
        return new MediaReference(source, producerScope, segmentId, revision, position);
    }

    /**
     * Return a known start in samples or milliseconds after checking a resolved
     * source. This seek profile does not convert frame indices using an average
     * frame rate; use an explicit {@link FrameBoundaryMap} first. No media is opened.
     */
    public long requireSeekStart(Source resolved) {
        source.requireMatching(resolved);
        if (position.kind == Kind.UNKNOWN) throw new IllegalStateException("unknown position cannot be sought");
        if (source.unit == Unit.FRAME_INDEX) {
            throw new IllegalStateException("frame-index seek requires an explicit boundary mapping");
        }
        return position.start;
    }

    /**
     * Exact duration of a RANGE in integral milliseconds. Rejects unknown/point
     * positions, frame indices, overflow and sub-millisecond rounding. This is a
     * duration calculation, not a mapping between source clock origins.
     */
    public long durationMillisExact() {
        if (position.kind != Kind.RANGE) throw new IllegalStateException("duration requires a nonempty range");
        long length = position.end - position.start;
        if (source.unit == Unit.MEDIA_MILLISECONDS) return length;
        if (source.unit != Unit.PCM_SAMPLES) {
            throw new IllegalStateException("frame duration requires an explicit boundary mapping");
        }
        long remainderMillis = (length % source.sampleRate) * 1000;
        if (remainderMillis % source.sampleRate != 0) {
            throw new ArithmeticException("duration cannot be represented as integral milliseconds");
        }
        return Math.addExact(Math.multiplyExact(length / source.sampleRate, 1000),
                remainderMillis / source.sampleRate);
    }

    private static String label(String value, String name, int maxChars) {
        Objects.requireNonNull(value, name);
        if (value.length() > maxChars || value.isBlank()) {
            throw new IllegalArgumentException(name + " must contain 1.." + maxChars + " characters");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isISOControl(character)) {
                throw new IllegalArgumentException(name + " contains a control character");
            }
            if (Character.isHighSurrogate(character)) {
                if (++index == value.length() || !Character.isLowSurrogate(value.charAt(index))) {
                    throw new IllegalArgumentException(name + " contains an unpaired surrogate");
                }
            } else if (Character.isLowSurrogate(character)) {
                throw new IllegalArgumentException(name + " contains an unpaired surrogate");
            }
        }
        return value;
    }
}
