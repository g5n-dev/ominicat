package io.ominicat.conversation.reference;

import java.util.Objects;

/**
 * A driver-supplied exact map from frame-boundary indices to media milliseconds.
 * Each adjacent boundary pair describes one frame, including the final end boundary.
 * This profile maps one asset/version/track only; it does not infer cross-source or
 * clock alignment. An average frame rate is deliberately not an accepted mapping.
 */
public final class FrameBoundaryMap {
    /** At most 65,536 frame intervals in one immutable mapping. */
    public static final int MAX_BOUNDARIES = 65_537;
    private final MediaReference.Source frameSource;
    private final MediaReference.Source millisSource;
    private final long firstFrame;
    private final long lastBoundary;
    private final long[] boundaries;

    public FrameBoundaryMap(MediaReference.Source frameSource, MediaReference.Source millisSource,
                            long firstFrame, long[] boundaries) {
        this.frameSource = Objects.requireNonNull(frameSource, "frameSource");
        this.millisSource = Objects.requireNonNull(millisSource, "millisSource");
        if (frameSource.unit() != MediaReference.Unit.FRAME_INDEX
                || millisSource.unit() != MediaReference.Unit.MEDIA_MILLISECONDS) {
            throw new IllegalArgumentException("mapping requires frame-index and media-millisecond sources");
        }
        if (!frameSource.namespace().equals(millisSource.namespace())
                || !frameSource.id().equals(millisSource.id())
                || !frameSource.version().equals(millisSource.version())
                || !frameSource.track().equals(millisSource.track())
                || !frameSource.sha256().equals(millisSource.sha256())) {
            throw new IllegalArgumentException("mapping must preserve asset/version/track and fingerprint claim");
        }
        Objects.requireNonNull(boundaries, "boundaries");
        if (firstFrame < 0 || boundaries.length < 2 || boundaries.length > MAX_BOUNDARIES) {
            throw new IllegalArgumentException("mapping requires nonnegative firstFrame and 2..65537 boundaries");
        }
        this.firstFrame = firstFrame;
        this.lastBoundary = Math.addExact(firstFrame, boundaries.length - 1L);
        this.boundaries = boundaries.clone();
        for (int index = 0; index < this.boundaries.length; index++) {
            if (this.boundaries[index] < 0 || index > 0 && this.boundaries[index] <= this.boundaries[index - 1]) {
                throw new IllegalArgumentException("millisecond boundaries must be nonnegative and strictly increasing");
            }
        }
    }

    /** Map an exact range/boundary point, or preserve UNKNOWN without inventing coordinates. */
    public MediaReference map(MediaReference reference) {
        Objects.requireNonNull(reference, "reference");
        reference.source().requireMatching(frameSource);
        MediaReference.Position input = reference.position();
        MediaReference.Position output = switch (input.kind()) {
            case UNKNOWN -> MediaReference.Position.unknown();
            case POINT -> MediaReference.Position.point(boundaries[index(input.start())]);
            case RANGE -> MediaReference.Position.range(boundaries[index(input.start())], boundaries[index(input.end())]);
        };
        return new MediaReference(millisSource, reference.producerScope(), reference.segmentId(), reference.revision(), output);
    }

    /**
     * Import inclusive frame indices by mapping the boundary after the last frame.
     * A missing final boundary fails; no extra millisecond or guessed duration is added.
     * Opaque scope/segment IDs follow {@link MediaReference}'s 256-character limits.
     */
    public MediaReference mapInclusiveRange(String producerScope, String segmentId, long revision,
                                             long startFrame, long lastFrameInclusive) {
        if (startFrame < 0 || lastFrameInclusive < startFrame) {
            throw new IllegalArgumentException("invalid inclusive frame interval");
        }
        return map(new MediaReference(frameSource, producerScope, segmentId, revision,
                MediaReference.Position.range(startFrame, Math.addExact(lastFrameInclusive, 1))));
    }

    private int index(long frameBoundary) {
        if (frameBoundary < firstFrame || frameBoundary > lastBoundary) {
            throw new IllegalArgumentException("frame boundary is outside the explicit mapping");
        }
        return (int) (frameBoundary - firstFrame);
    }
}
