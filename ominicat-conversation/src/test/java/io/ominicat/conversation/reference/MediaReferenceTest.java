package io.ominicat.conversation.reference;

import static org.junit.jupiter.api.Assertions.*;

import io.ominicat.core.Frame;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

class MediaReferenceTest {
    private static final String SHA = "a".repeat(64);

    static MediaReference.Source source(MediaReference.Unit unit, int rate, String digest) {
        return new MediaReference.Source("Example/Namespace", "Asset:α/音声", "Version-A", "left",
                unit == MediaReference.Unit.FRAME_INDEX ? "frame-boundaries" : "asset-origin", unit, rate, digest);
    }

    private static MediaReference reference(MediaReference.Source source, MediaReference.Position position) {
        return new MediaReference(source, "producer/run-A", "segment-1", 0, position);
    }

    @Test void opaqueBindingsRetainCaseSeparatorsAndDoNotRequireFilesOrDigests() {
        var source = source(MediaReference.Unit.MEDIA_MILLISECONDS, 0, "");
        assertEquals("Example/Namespace", source.namespace());
        assertEquals("Asset:α/音声", source.id());
        assertEquals("Version-A", source.version());
        assertEquals("", source.sha256());
        assertEquals(SHA, source(MediaReference.Unit.MEDIA_MILLISECONDS, 0, SHA.toUpperCase()).sha256());
        var ref = new MediaReference(source, " Run/A ", "segment:1/一", 42, MediaReference.Position.point(7));
        assertEquals(" Run/A ", ref.producerScope());
    }

    @Test void everyIdentifierBoundaryAndMalformedUnicodeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new MediaReference.Source("n".repeat(129), "id", "v", "t", "b",
                MediaReference.Unit.MEDIA_MILLISECONDS, 0, ""));
        for (int field = 0; field < 4; field++) {
            String[] values = {"id", "v", "t", "b"};
            values[field] = "x".repeat(257);
            assertThrows(IllegalArgumentException.class, () -> new MediaReference.Source("n", values[0], values[1],
                    values[2], values[3], MediaReference.Unit.MEDIA_MILLISECONDS, 0, ""));
        }
        var source = source(MediaReference.Unit.MEDIA_MILLISECONDS, 0, "");
        assertThrows(IllegalArgumentException.class, () -> new MediaReference(source, "x".repeat(257), "s", 0,
                MediaReference.Position.point(0)));
        assertThrows(IllegalArgumentException.class, () -> new MediaReference(source, "p", "x".repeat(257), 0,
                MediaReference.Position.point(0)));
        for (String invalid : new String[]{"", "  ", "bad\ud800", "bad\udc00", "a\n", "a\u0000"}) {
            assertThrows(IllegalArgumentException.class, () -> new MediaReference(source, invalid, "s", 0,
                    MediaReference.Position.point(0)));
        }
        assertThrows(NullPointerException.class, () -> reference(source, null));
        assertThrows(IllegalArgumentException.class, () -> new MediaReference(source, "p", "s", -1,
                MediaReference.Position.point(0)));
    }

    @Test void unitRateAndFingerprintStatesAreExplicit() {
        assertThrows(IllegalArgumentException.class, () -> source(MediaReference.Unit.PCM_SAMPLES, 0, ""));
        assertThrows(IllegalArgumentException.class, () -> source(MediaReference.Unit.PCM_SAMPLES, 384_001, ""));
        assertThrows(IllegalArgumentException.class, () -> source(MediaReference.Unit.MEDIA_MILLISECONDS, 16_000, ""));
        assertThrows(IllegalArgumentException.class, () -> source(MediaReference.Unit.FRAME_INDEX, 20, ""));
        assertThrows(IllegalArgumentException.class, () -> source(MediaReference.Unit.MEDIA_MILLISECONDS, 0, "v1"));
    }

    @Test void rangePointAndUnknownHaveDifferentUnambiguousStates() {
        assertEquals(new MediaReference.Position(MediaReference.Kind.RANGE, 0, 1), MediaReference.Position.range(0, 1));
        assertThrows(IllegalArgumentException.class, () -> MediaReference.Position.range(10, 10));
        assertThrows(IllegalArgumentException.class, () -> MediaReference.Position.range(10, 9));
        assertThrows(IllegalArgumentException.class, () -> MediaReference.Position.point(-1));
        assertThrows(IllegalArgumentException.class, () -> new MediaReference.Position(MediaReference.Kind.UNKNOWN, 0, 0));
        assertEquals(MediaReference.Kind.POINT, MediaReference.Position.point(0).kind());
        assertEquals(-1, MediaReference.Position.unknown().start());
    }

    @Test void producersSourcesAndRevisionsDoNotCollideOnLocalSegmentKeys() {
        var source = source(MediaReference.Unit.MEDIA_MILLISECONDS, 0, "");
        var first = reference(source, MediaReference.Position.range(0, 10));
        var otherProducer = new MediaReference(source, "producer/run-B", "segment-1", 0, first.position());
        var otherAsset = new MediaReference.Source(source.namespace(), "Other-Asset", source.version(), source.track(),
                source.timeBasis(), source.unit(), 0, "");
        assertNotEquals(first, otherProducer);
        assertNotEquals(first, reference(otherAsset, first.position()));
        assertNotEquals(first, new MediaReference(source, first.producerScope(), first.segmentId(), 1, first.position()));
        assertEquals(first.position(), otherProducer.position());
    }

    @Test void stereoPcmCountsSamplesPerChannelAndPreservesUnknownLegacyPosition() {
        var source = source(MediaReference.Unit.PCM_SAMPLES, 16_000, "");
        var audio = new Frame.Audio(ByteBuffer.allocate(640), 16_000, 2, "left", 800);
        var reference = MediaReference.fromPcm16(source, "run", "segment", 0, audio);
        assertEquals(MediaReference.Position.range(800, 960), reference.position());
        assertEquals(160, reference.position().end() - reference.position().start());
        assertEquals(10, reference.durationMillisExact());
        assertEquals(800, reference.requireSeekStart(source));
        assertEquals(640, audio.data().remaining());

        var legacy = new Frame.Audio(ByteBuffer.allocate(640), 16_000, 2);
        var legacySource = new MediaReference.Source("n", "asset", "v", "default", "samples",
                MediaReference.Unit.PCM_SAMPLES, 16_000, "");
        var unknown = MediaReference.fromPcm16(legacySource, "run", "segment", 0, legacy);
        assertEquals(-1, legacy.startSample());
        assertEquals(MediaReference.Position.unknown(), unknown.position());
        assertThrows(IllegalStateException.class, () -> unknown.requireSeekStart(legacySource));
        assertThrows(IllegalStateException.class, unknown::durationMillisExact);
    }

    @Test void pcmAdapterRejectsMismatchedBindingAndOverflowWithoutGuessing() {
        var audio = new Frame.Audio(ByteBuffer.allocate(640), 16_000, 2, "left", 0);
        assertThrows(IllegalArgumentException.class, () -> MediaReference.fromPcm16(
                source(MediaReference.Unit.MEDIA_MILLISECONDS, 0, ""), "p", "s", 0, audio));
        assertThrows(IllegalArgumentException.class, () -> MediaReference.fromPcm16(
                source(MediaReference.Unit.PCM_SAMPLES, 8_000, ""), "p", "s", 0, audio));
        var source = source(MediaReference.Unit.PCM_SAMPLES, 16_000, "");
        var overflow = new Frame.Audio(ByteBuffer.allocate(640), 16_000, 2, "left", Long.MAX_VALUE - 159);
        assertThrows(ArithmeticException.class, () -> MediaReference.fromPcm16(source, "p", "s", 0, overflow));
        var exactEnd = new Frame.Audio(ByteBuffer.allocate(640), 16_000, 2, "left", Long.MAX_VALUE - 160);
        assertEquals(Long.MAX_VALUE, MediaReference.fromPcm16(source, "p", "s", 0, exactEnd).position().end());
    }

    @Test void requiredDigestMustMatchAndSourceVersionIsNotAContentDigest() {
        var expected = source(MediaReference.Unit.MEDIA_MILLISECONDS, 0, SHA);
        var ref = reference(expected, MediaReference.Position.range(10, 20));
        assertEquals(10, ref.requireSeekStart(expected));
        assertThrows(IllegalArgumentException.class, () -> ref.requireSeekStart(source(MediaReference.Unit.MEDIA_MILLISECONDS, 0, "")));
        assertThrows(IllegalArgumentException.class, () -> ref.requireSeekStart(source(MediaReference.Unit.MEDIA_MILLISECONDS, 0, "b".repeat(64))));
        var otherVersion = new MediaReference.Source(expected.namespace(), expected.id(), "Version-B", expected.track(),
                expected.timeBasis(), expected.unit(), 0, SHA);
        assertThrows(IllegalArgumentException.class, () -> ref.requireSeekStart(otherVersion));
        assertEquals(10, reference(source(MediaReference.Unit.MEDIA_MILLISECONDS, 0, ""), ref.position()).requireSeekStart(expected));
    }

    @Test void exactDurationsRejectRoundingOverflowAndFrameRateInference() {
        var samples = source(MediaReference.Unit.PCM_SAMPLES, 16_000, "");
        assertThrows(ArithmeticException.class, () -> reference(samples, MediaReference.Position.range(0, 1)).durationMillisExact());
        assertThrows(ArithmeticException.class, () -> reference(source(MediaReference.Unit.PCM_SAMPLES, 1, ""),
                MediaReference.Position.range(0, Long.MAX_VALUE)).durationMillisExact());
        var frames = reference(source(MediaReference.Unit.FRAME_INDEX, 0, ""), MediaReference.Position.range(0, 2));
        assertThrows(IllegalStateException.class, frames::durationMillisExact);
        assertThrows(IllegalStateException.class, () -> frames.requireSeekStart(frames.source()));
        assertThrows(IllegalStateException.class, () -> reference(samples, MediaReference.Position.point(0)).durationMillisExact());
    }

    @Test void explicitNonuniformMappingUsesRealBoundariesIncludingTheTerminalEnd() {
        var frames = source(MediaReference.Unit.FRAME_INDEX, 0, SHA);
        var millis = source(MediaReference.Unit.MEDIA_MILLISECONDS, 0, SHA);
        var mapping = new FrameBoundaryMap(frames, millis, 0, new long[]{0, 40, 100, 180});
        var second = mapping.mapInclusiveRange("run", "second", 7, 1, 1);
        assertEquals(MediaReference.Position.range(40, 100), second.position());
        assertEquals(40, second.requireSeekStart(millis));
        assertNotEquals(1000 / 20, second.requireSeekStart(millis)); // Average 20 fps predicts 50 ms.
        assertEquals(MediaReference.Position.range(100, 180), mapping.mapInclusiveRange("run", "last", 0, 2, 2).position());
        assertEquals(MediaReference.Position.point(100), mapping.map(reference(frames, MediaReference.Position.point(2))).position());
        assertEquals(MediaReference.Position.unknown(), mapping.map(reference(frames, MediaReference.Position.unknown())).position());

        var noLastEnd = new FrameBoundaryMap(frames, millis, 0, new long[]{0, 40, 100});
        assertThrows(IllegalArgumentException.class, () -> noLastEnd.mapInclusiveRange("run", "last", 0, 2, 2));
        assertThrows(ArithmeticException.class, () -> mapping.mapInclusiveRange("run", "overflow", 0, 0, Long.MAX_VALUE));
    }

    @Test void mappingOwnsABoundedSnapshotAndCannotSilentlySwitchSource() {
        var frames = source(MediaReference.Unit.FRAME_INDEX, 0, "");
        var millis = source(MediaReference.Unit.MEDIA_MILLISECONDS, 0, "");
        long[] boundaries = {0, 40, 100};
        var mapping = new FrameBoundaryMap(frames, millis, 5, boundaries);
        boundaries[1] = 50;
        assertEquals(MediaReference.Position.range(0, 40), mapping.mapInclusiveRange("p", "s", 0, 5, 5).position());
        assertThrows(IllegalArgumentException.class, () -> mapping.mapInclusiveRange("p", "s", 0, 4, 4));
        var wrongAsset = new MediaReference.Source(frames.namespace(), "another", frames.version(), frames.track(),
                frames.timeBasis(), frames.unit(), 0, "");
        assertThrows(IllegalArgumentException.class, () -> mapping.map(reference(wrongAsset, MediaReference.Position.point(5))));
        assertThrows(IllegalArgumentException.class, () -> new FrameBoundaryMap(frames, millis, 0, new long[]{0}));
        assertThrows(IllegalArgumentException.class, () -> new FrameBoundaryMap(frames, millis, 0, new long[FrameBoundaryMap.MAX_BOUNDARIES + 1]));
        assertThrows(IllegalArgumentException.class, () -> new FrameBoundaryMap(frames, millis, 0, new long[]{0, 0}));
        assertThrows(IllegalArgumentException.class, () -> new FrameBoundaryMap(frames, millis, 0, new long[]{0, -1}));
        assertThrows(ArithmeticException.class, () -> new FrameBoundaryMap(frames, millis, Long.MAX_VALUE, new long[]{0, 1}));
    }
}
