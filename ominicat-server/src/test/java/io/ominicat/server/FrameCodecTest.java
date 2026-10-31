package io.ominicat.server;

import io.ominicat.core.Frame;
import io.ominicat.core.FrameEnvelope;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FrameCodecTest {
    private final FrameCodec codec = new FrameCodec();

    @Test void textRoundTripsUnicodeAndEscapes() throws Exception {
        var frame = new Frame.Text("Hello 世界\n\"<script>\"");
        assertEquals(frame, codec.decode(codec.encode(frame)));
    }

    @Test void audioRoundTripsFormatAndBytes() throws Exception {
        var frame = new Frame.Audio(ByteBuffer.wrap(new byte[]{0, 1, 2, 3}), 16000, 1);
        var decoded = (Frame.Audio) codec.decode(codec.encode(frame));
        assertEquals(frame.data(), decoded.data());
        assertEquals(16000, decoded.sampleRate());
        assertEquals(1, decoded.channels());
    }

    @Test void imageRoundTripsWithoutConsumingSourcePosition() throws Exception {
        var frame = new Frame.Image(ByteBuffer.wrap(new byte[]{1, 2, 3}), "image/png");
        var decoded = (Frame.Image) codec.decode(codec.encode(frame));
        assertEquals(frame.data(), decoded.data());
        assertEquals("image/png", decoded.mimeType());
        assertEquals(0, frame.data().position());
    }

    @Test void transcriptRoundTripsAttributionAndTiming() throws Exception {
        var frame = new Frame.Transcript("hello", "speaker-2", 123, 456, true, .97);
        assertEquals(frame, codec.decode(codec.encode(frame)));
    }

    @Test void richMediaMetadataAndUnknownConfidenceRoundTrip() throws Exception {
        var audio = new Frame.Audio(ByteBuffer.wrap(new byte[]{0, 1}), 16000, 1, "microphone-1", 320);
        assertEquals(audio, codec.decode(codec.encode(audio)));
        var transcript = new Frame.Transcript("revised", "speaker-1", 100, 200, false, -1, "segment-1", 4, "user");
        assertEquals(transcript, codec.decode(codec.encode(transcript)));
    }

    @Test void firstClassEnvelopeRoundTripsAndPreservesCorrelation() throws Exception {
        var envelope = new FrameEnvelope(new Frame.Text("hello"), "event-1", "session-1", "turn-1", "trace-1", "", 10, 20, 30);
        assertEquals(envelope, codec.decode(codec.encode(envelope)));
    }

    @Test void nestedEnvelopesAndDuplicateFieldsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> codec.decode("{\"type\":\"envelope\",\"id\":\"a\",\"sessionId\":\"s\",\"turnId\":\"t\",\"correlationId\":\"c\",\"originTimeNanos\":0,\"ingestTimeNanos\":0,\"frame\":{\"type\":\"envelope\"}}"));
        assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class, () -> codec.decode("{\"type\":\"text\",\"text\":\"a\",\"text\":\"b\"}"));
        assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class, () -> codec.decode("[".repeat(20) + "0" + "]".repeat(20)));
    }

    @Test void trailingJsonRootsAreRejectedInsteadOfSilentlyIgnoringContent() {
        assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,
                () -> codec.decode("{\"type\":\"text\",\"text\":\"first\"}{\"unparsed\":true}"));
        assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,
                () -> codec.decode("{\"type\":\"text\",\"text\":\"first\"} true"));
    }

    @Test void classifiedFailuresAndExplicitCancellationRoundTrip() throws Exception {
        var failure = new Frame.Failure("provider_timeout", true, "request", "speech-1", "Provider timed out");
        var cancellation = new Frame.Cancel("user_requested", Frame.CancelScope.TURN, "turn-1");
        assertEquals(failure, codec.decode(codec.encode(failure)));
        assertEquals(cancellation, codec.decode(codec.encode(cancellation)));
    }

    @Test void controlMessagesAreExplicit() throws Exception {
        assertEquals(new Frame.Control(Frame.Signal.INTERRUPT), codec.decode("{\"type\":\"interrupt\"}"));
        assertEquals(new Frame.Control(Frame.Signal.END), codec.decode("{\"type\":\"end\"}"));
        assertEquals(new Frame.Control(Frame.Signal.TURN_END), codec.decode("{\"type\":\"turn_end\"}"));
        assertEquals("{\"type\":\"speech_started\"}", codec.encode(new Frame.Control(Frame.Signal.SPEECH_STARTED)));
    }

    @Test void rejectsUnknownAndPolymorphicTypes() {
        assertThrows(IllegalArgumentException.class, () -> codec.decode("{\"type\":\"java.lang.Runtime\"}"));
        assertThrows(IllegalArgumentException.class, () -> codec.decode("[]"));
        assertThrows(IllegalArgumentException.class, () -> codec.decode("null"));
    }

    @Test void rejectsMissingOrWrongTypes() {
        assertThrows(IllegalArgumentException.class, () -> codec.decode("{\"type\":\"text\"}"));
        assertThrows(IllegalArgumentException.class, () -> codec.decode("{\"type\":\"text\",\"text\":4}"));
        assertThrows(IllegalArgumentException.class, () -> codec.decode("{\"type\":\"audio\",\"data\":\"AAE=\",\"sampleRate\":1.2,\"channels\":1}"));
        assertThrows(IllegalArgumentException.class, () -> codec.decode("{\"type\":\"audio\",\"data\":\"!\",\"sampleRate\":16000,\"channels\":1}"));
    }

    @Test void rejectsInvalidModalityMetadata() {
        assertThrows(IllegalArgumentException.class, () -> codec.decode("{\"type\":\"image\",\"data\":\"AAE=\",\"mimeType\":\"text/html\"}"));
        assertThrows(IllegalArgumentException.class, () -> codec.decode("{\"type\":\"audio\",\"data\":\"AA==\",\"sampleRate\":16000,\"channels\":1}"));
        assertThrows(IllegalArgumentException.class, () -> codec.decode("{\"type\":\"audio\",\"data\":\"AAE=\",\"sampleRate\":2147483648,\"channels\":1}"));
    }

    record CustomFrame(String outcome, double score) implements Frame { }

    @Test void customPluginFramesHaveExplicitOutputEnvelope() throws Exception {
        assertEquals("{\"type\":\"CustomFrame\",\"data\":{\"outcome\":\"pass\",\"score\":0.8}}", codec.encode(new CustomFrame("pass", .8)));
    }

    @Test void errorsAreValidJsonAndEscapeContent() {
        assertEquals("{\"type\":\"error\",\"code\":\"invalid\",\"message\":\"\\\"bad\\\"\"}", codec.error("invalid", "\"bad\""));
    }
}
