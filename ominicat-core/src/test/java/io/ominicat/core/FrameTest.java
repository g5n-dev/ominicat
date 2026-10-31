package io.ominicat.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import org.junit.jupiter.api.Test;

class FrameTest {
    @Test void audioSnapshotsRemainingBytesAndReturnsIndependentReadOnlyViews() {
        byte[] bytes = {99, 1, 2, 3, 4, 99};
        ByteBuffer source = ByteBuffer.wrap(bytes);
        source.position(1).limit(5);
        Frame.Audio frame = new Frame.Audio(source, 16000, 1);
        bytes[1] = 88;
        assertEquals(1, source.position());
        assertEquals(4, frame.data().remaining());
        assertEquals(1, frame.data().get());
        assertEquals(0, frame.data().position());
        assertThrows(ReadOnlyBufferException.class, () -> frame.data().put((byte) 0));
    }

    @Test void imageSnapshotsPayloadAndDoesNotShareCursor() {
        ByteBuffer source = ByteBuffer.wrap(new byte[]{1, 2, 3});
        Frame.Image image = new Frame.Image(source, "image/jpeg");
        source.put(0, (byte) 9);
        assertEquals(1, image.data().get());
        assertEquals(3, image.data().remaining());
        assertThrows(ReadOnlyBufferException.class, () -> image.data().put((byte) 5));
    }

    @Test void rejectsMalformedMediaAndTranscript() {
        assertThrows(IllegalArgumentException.class, () -> new Frame.Audio(ByteBuffer.wrap(new byte[3]), 16000, 1));
        assertThrows(IllegalArgumentException.class, () -> new Frame.Audio(ByteBuffer.wrap(new byte[2]), 16000, 2));
        assertThrows(IllegalArgumentException.class, () -> new Frame.Audio(ByteBuffer.allocate(0), 16000, 1));
        assertThrows(IllegalArgumentException.class, () -> new Frame.Audio(ByteBuffer.allocate(2), 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new Frame.Audio(ByteBuffer.allocate(2), 384001, 1));
        assertThrows(IllegalArgumentException.class, () -> new Frame.Audio(ByteBuffer.allocate(2), 16000, 0));
        assertThrows(IllegalArgumentException.class, () -> new Frame.Audio(ByteBuffer.allocate(2), 16000, 33));
        assertThrows(IllegalArgumentException.class, () -> new Frame.Image(ByteBuffer.allocate(1), "text/plain"));
        assertThrows(IllegalArgumentException.class, () -> new Frame.Image(ByteBuffer.allocate(0), "image/png"));
        assertThrows(IllegalArgumentException.class, () -> transcript(-1, 2, .5));
        assertThrows(IllegalArgumentException.class, () -> transcript(2, 1, .5));
        assertThrows(IllegalArgumentException.class, () -> transcript(0, 1, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> transcript(0, 1, 1.1));
        assertThrows(IllegalArgumentException.class, () -> transcript(0, 1, -.1));
        assertThrows(IllegalArgumentException.class, () -> new Frame.Transcript("ok", " ", 0, 1, true, 1));
        assertThrows(NullPointerException.class, () -> new Frame.Text(null));
        assertThrows(NullPointerException.class, () -> new Frame.Control(null));
    }

    @Test void acceptsBoundaryTranscriptAndCustomFrames() {
        assertEquals(1, transcript(0, 0, 1).confidence());
        assertEquals(-1, transcript(0, 0, -1).confidence());
        assertEquals(0, transcript(0, 1, 0).startMillis());
        record DomainEvent(String id) implements Frame { }
        Frame custom = new DomainEvent("case-123");
        assertInstanceOf(DomainEvent.class, custom);
    }

    @Test void retainsStreamCoordinatesAndExplicitTranscriptRevisionMetadata() {
        Frame.Audio positioned = new Frame.Audio(ByteBuffer.allocate(4), 48000, 2, "channel-a", 4096);
        assertEquals("channel-a", positioned.streamId());
        assertEquals(4096, positioned.startSample());
        assertEquals(java.nio.ByteOrder.LITTLE_ENDIAN, positioned.data().order());
        Frame.Audio legacy = new Frame.Audio(ByteBuffer.allocate(2), 16000, 1);
        assertEquals("default", legacy.streamId());
        assertEquals(-1, legacy.startSample());
        Frame.Transcript revised = new Frame.Transcript("revised", "track-0", 100, 250, false, -1,
                "segment-1", 2, "customer");
        assertEquals("segment-1", revised.segmentId());
        assertEquals(2, revised.revision());
        assertEquals("customer", revised.role());
        assertEquals("track-0", revised.speaker());
        assertThrows(IllegalArgumentException.class, () -> new Frame.Audio(ByteBuffer.allocate(2), 16000, 1, " ", 0));
        assertThrows(IllegalArgumentException.class, () -> new Frame.Audio(ByteBuffer.allocate(2), 16000, 1, "track", -2));
        assertThrows(IllegalArgumentException.class, () -> new Frame.Transcript("x", "s", 0, 1, false, 1, "seg", -1, "role"));
        assertThrows(IllegalArgumentException.class, () -> new Frame.Transcript("x", "s", 0, 1, false, 1, " ", 0, "role"));
        assertThrows(IllegalArgumentException.class, () -> new Frame.Transcript("x", "s", 0, 1, false, 1, "seg", 0, " "));
    }

    private static Frame.Transcript transcript(long start, long end, double confidence) {
        return new Frame.Transcript("hello", "customer", start, end, true, confidence);
    }
}
