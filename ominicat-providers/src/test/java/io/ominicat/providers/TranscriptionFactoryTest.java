package io.ominicat.providers;

import static org.junit.jupiter.api.Assertions.*;

import io.ominicat.core.Frame;
import io.ominicat.core.Processor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class TranscriptionFactoryTest extends ProviderHttpTestSupport {
    @Test
    void buffersPcmAndPostsMultipartWavAtBoundaryWithMonotonicAudioTimeline() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        route("/audio/transcriptions", exchange -> {
            requests.incrementAndGet();
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            assertTrue(contentType.startsWith("multipart/form-data; boundary=ominicat-"));
            byte[] body = exchange.getRequestBody().readAllBytes();
            String multipart = new String(body, StandardCharsets.ISO_8859_1);
            assertTrue(multipart.contains("name=\"model\"\r\n\r\ntest-model"));
            assertTrue(multipart.contains("filename=\"utterance.wav\""));
            int wavOffset = multipart.indexOf("RIFF");
            assertTrue(wavOffset > 0);
            ByteBuffer wav = ByteBuffer.wrap(body, wavOffset, 44).slice().order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(3236, wav.getInt(4));
            assertEquals(1, wav.getShort(22));
            assertEquals(16000, wav.getInt(24));
            assertEquals(32000, wav.getInt(28));
            assertEquals(16, wav.getShort(34));
            assertEquals(3200, wav.getInt(40));
            assertArrayEquals(new byte[3200], Arrays.copyOfRange(body, wavOffset + 44, wavOffset + 44 + 3200));
            respond(exchange, 200, "application/json", "{\"text\":\"recognized\"}");
        });
        Processor processor = new TranscriptionFactory().create(options("speaker", "track-1"));
        List<Frame> output = new ArrayList<>();
        for (int utterance = 0; utterance < 2; utterance++) {
            processor.process(new Frame.Audio(ByteBuffer.wrap(new byte[1600]), 16000, 1), output::add);
            processor.process(new Frame.Audio(ByteBuffer.wrap(new byte[1600]), 16000, 1), output::add);
            assertEquals(utterance, requests.get());
            processor.process(new Frame.Control(Frame.Signal.TURN_END), output::add);
        }
        assertEquals(2, requests.get());
        Frame.Transcript first = assertInstanceOf(Frame.Transcript.class, output.getFirst());
        assertEquals("recognized", first.text());
        assertEquals("track-1", first.speaker());
        assertEquals(0, first.startMillis());
        assertEquals(100, first.endMillis());
        assertTrue(first.finalResult());
        assertEquals(-1, first.confidence());
        Frame.Transcript second = assertInstanceOf(Frame.Transcript.class, output.get(2));
        assertEquals(100, second.startMillis());
        assertEquals(200, second.endMillis());
    }

    @Test
    void speechStoppedFlushesAndInterruptDropsUncommittedPcm() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        route("/audio/transcriptions", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 200, "application/json", "{\"text\":\"new\"}");
        });
        Processor processor = new TranscriptionFactory().create(options());
        List<Frame> output = new ArrayList<>();
        processor.process(new Frame.Audio(ByteBuffer.wrap(new byte[100]), 16000, 1), output::add);
        processor.onInterrupt();
        processor.process(new Frame.Control(Frame.Signal.TURN_END), output::add);
        assertEquals(0, requests.get());
        processor.process(new Frame.Audio(ByteBuffer.wrap(new byte[100]), 16000, 1), output::add);
        processor.process(new Frame.Control(Frame.Signal.INTERRUPT), output::add);
        processor.process(new Frame.Control(Frame.Signal.END), output::add);
        assertEquals(0, requests.get());
        processor.process(new Frame.Audio(ByteBuffer.wrap(new byte[3200]), 16000, 1), output::add);
        processor.process(new Frame.Control(Frame.Signal.SPEECH_STOPPED), output::add);
        assertEquals(1, requests.get());
        Frame.Transcript transcript = assertInstanceOf(Frame.Transcript.class, output.get(3));
        assertEquals(6, transcript.startMillis());
        assertEquals(106, transcript.endMillis());
    }

    @Test
    void enforcesUtteranceLimitAndStableAudioFormat() throws Exception {
        Processor limited = new TranscriptionFactory().create(options("maxAudioBytes", "2"));
        assertThrows(IllegalArgumentException.class, () -> limited.process(
                new Frame.Audio(ByteBuffer.wrap(new byte[4]), 16000, 1), ignored -> {}));
        Processor processor = new TranscriptionFactory().create(options());
        processor.process(new Frame.Audio(ByteBuffer.wrap(new byte[4]), 16000, 1), ignored -> {});
        assertThrows(IllegalArgumentException.class, () -> processor.process(
                new Frame.Audio(ByteBuffer.wrap(new byte[4]), 8000, 1), ignored -> {}));
        assertThrows(IllegalArgumentException.class, () -> processor.process(
                new Frame.Audio(ByteBuffer.wrap(new byte[4]), 16000, 2), ignored -> {}));
    }

    @Test
    void missingTextAndOversizedResponsesFailExplicitly() throws Exception {
        AtomicInteger request = new AtomicInteger();
        route("/audio/transcriptions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 200, "application/json", request.getAndIncrement() == 0 ? "{}"
                    : "{\"text\":\"" + "x".repeat(2048) + "\"}");
        });
        Processor processor = new TranscriptionFactory().create(options("maxResponseBytes", "1024"));
        processor.process(new Frame.Audio(ByteBuffer.wrap(new byte[2]), 16000, 1), ignored -> {});
        assertThrows(ProviderException.class, () -> processor.process(new Frame.Control(Frame.Signal.END), ignored -> {}));
        processor.process(new Frame.Audio(ByteBuffer.wrap(new byte[2]), 16000, 1), ignored -> {});
        assertThrows(IOException.class, () -> processor.process(new Frame.Control(Frame.Signal.END), ignored -> {}));
    }

    @Test
    void wavHeaderPreservesStereoFormat() {
        byte[] pcm = {1, 2, 3, 4, 5, 6, 7, 8};
        byte[] encoded = TranscriptionFactory.wav(pcm, 48000, 2);
        ByteBuffer wav = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(2, wav.getShort(22));
        assertEquals(48000, wav.getInt(24));
        assertEquals(192000, wav.getInt(28));
        assertEquals(4, wav.getShort(32));
        assertEquals(8, wav.getInt(40));
        assertArrayEquals(pcm, Arrays.copyOfRange(encoded, 44, encoded.length));
    }

    @Test
    void distinctShortUtterancesHaveDistinctStableSegmentIds() throws Exception {
        AtomicInteger utterance = new AtomicInteger();
        route("/audio/transcriptions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 200, "application/json", "{\"text\":\"utterance-" + utterance.incrementAndGet() + "\"}");
        });
        Processor processor = new TranscriptionFactory().create(options());
        List<Frame> output = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            processor.process(new Frame.Audio(ByteBuffer.wrap(new byte[2]), 16000, 1), output::add);
            processor.process(new Frame.Control(Frame.Signal.TURN_END), output::add);
        }
        Frame.Transcript first = assertInstanceOf(Frame.Transcript.class, output.get(0));
        Frame.Transcript second = assertInstanceOf(Frame.Transcript.class, output.get(2));
        assertNotEquals(first.segmentId(), second.segmentId());
    }

    @Test
    void isolatesStreamsAndPreservesSpeakerRoleAndKnownOffsets() throws Exception {
        List<byte[]> utterances = new java.util.concurrent.CopyOnWriteArrayList<>();
        route("/audio/transcriptions", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            int offset = new String(body, StandardCharsets.ISO_8859_1).indexOf("RIFF");
            ByteBuffer wav = ByteBuffer.wrap(body, offset, body.length - offset).slice().order(ByteOrder.LITTLE_ENDIAN);
            int length = wav.getInt(40);
            assertEquals(1, wav.getShort(22));
            utterances.add(Arrays.copyOfRange(body, offset + 44, offset + 44 + length));
            respond(exchange, 200, "application/json", "{\"text\":\"track text\"}");
        });
        Processor processor = new TranscriptionFactory().create(options("speakers", "left=person-a,right=person-b",
                "roles", "left=participant,right=assistant"));
        List<Frame> output = new ArrayList<>();
        processor.process(new Frame.Audio(ByteBuffer.wrap(new byte[]{1, 0, 2, 0}), 16000, 1, "left", 1600), output::add);
        processor.process(new Frame.Audio(ByteBuffer.wrap(new byte[]{9, 0, 10, 0}), 16000, 1, "right", 3200), output::add);
        processor.process(new Frame.Control(Frame.Signal.END), output::add);
        assertArrayEquals(new byte[]{1, 0, 2, 0}, utterances.get(0));
        assertArrayEquals(new byte[]{9, 0, 10, 0}, utterances.get(1));
        Frame.Transcript left = assertInstanceOf(Frame.Transcript.class, output.get(0));
        Frame.Transcript right = assertInstanceOf(Frame.Transcript.class, output.get(1));
        assertEquals("left:1", left.segmentId());
        assertEquals("right:1", right.segmentId());
        assertEquals("person-a", left.speaker());
        assertEquals("person-b", right.speaker());
        assertEquals("participant", left.role());
        assertEquals("assistant", right.role());
        assertEquals(100, left.startMillis());
        assertEquals(200, right.startMillis());
    }

    @Test
    void totalByteLimitAndStreamIdentityLimitApplyAcrossTracks() throws Exception {
        Processor processor = new TranscriptionFactory().create(options("maxAudioBytes", "4", "maxStreams", "2"));
        processor.process(new Frame.Audio(ByteBuffer.wrap(new byte[2]), 16000, 1, "a", -1), ignored -> {});
        processor.process(new Frame.Audio(ByteBuffer.wrap(new byte[2]), 16000, 1, "b", -1), ignored -> {});
        assertThrows(IllegalArgumentException.class, () -> processor.process(
                new Frame.Audio(ByteBuffer.wrap(new byte[2]), 16000, 1, "a", -1), ignored -> {}));
        processor.onInterrupt();
        assertThrows(IllegalArgumentException.class, () -> processor.process(
                new Frame.Audio(ByteBuffer.wrap(new byte[2]), 16000, 1, "c", -1), ignored -> {}));
        processor.process(new Frame.Audio(ByteBuffer.wrap(new byte[2]), 16000, 1, "a", -1), ignored -> {});
    }

    @Test
    void rejectsDiscontinuousBufferedAudioAndBackwardOffsetsButAllowsForwardGapAtBoundary() throws Exception {
        route("/audio/transcriptions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 200, "application/json", "{\"text\":\"text\"}");
        });
        Processor processor = new TranscriptionFactory().create(options());
        List<Frame> output = new ArrayList<>();
        processor.process(new Frame.Audio(ByteBuffer.wrap(new byte[2]), 16000, 1, "a", 1600), output::add);
        assertThrows(IllegalArgumentException.class, () -> processor.process(
                new Frame.Audio(ByteBuffer.wrap(new byte[2]), 16000, 1, "a", 1602), output::add));
        assertThrows(IllegalArgumentException.class, () -> processor.process(
                new Frame.Audio(ByteBuffer.wrap(new byte[2]), 16000, 1, "a", 1599), output::add));
        processor.process(new Frame.Audio(ByteBuffer.wrap(new byte[2]), 16000, 1, "a", 1601), output::add);
        processor.process(new Frame.Control(Frame.Signal.TURN_END), output::add);
        processor.process(new Frame.Audio(ByteBuffer.wrap(new byte[2]), 16000, 1, "a", 3200), output::add);
        processor.process(new Frame.Control(Frame.Signal.TURN_END), output::add);
        assertEquals(200, assertInstanceOf(Frame.Transcript.class, output.get(2)).startMillis());
        assertThrows(IllegalArgumentException.class, () -> processor.process(
                new Frame.Audio(ByteBuffer.wrap(new byte[2]), 16000, 1, "a", 1), output::add));
        processor.onInterrupt();
        assertThrows(IllegalArgumentException.class, () -> processor.process(
                new Frame.Audio(ByteBuffer.wrap(new byte[2]), 8000, 1, "a", -1), output::add));
    }

    @Test
    void sampleCursorAvoidsPerUtteranceRoundingDrift() throws Exception {
        route("/audio/transcriptions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 200, "application/json", "{\"text\":\"text\"}");
        });
        Processor processor = new TranscriptionFactory().create(options());
        List<Frame> output = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            processor.process(new Frame.Audio(ByteBuffer.wrap(new byte[1000]), 16000, 1), output::add);
            processor.process(new Frame.Control(Frame.Signal.TURN_END), output::add);
        }
        assertEquals(125, assertInstanceOf(Frame.Transcript.class, output.get(6)).endMillis());
    }

    @Test
    void streamConfigurationAndTimelineOverflowFailBeforeNetworkCalls() {
        for (String mapping : List.of("a", "a=", "=b", "a=b,a=c", "a=b=c", "a=b,")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new TranscriptionFactory().create(options("speakers", mapping)));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new TranscriptionFactory().create(options("roles", "a=")));
        assertThrows(IllegalArgumentException.class,
                () -> new TranscriptionFactory().create(options("maxStreams", "33")));
        Processor processor = new TranscriptionFactory().create(options());
        assertThrows(IllegalArgumentException.class, () -> processor.process(
                new Frame.Audio(ByteBuffer.wrap(new byte[2]), 16000, 1, "a", Long.MAX_VALUE), ignored -> {}));
        assertThrows(IllegalArgumentException.class, () -> processor.process(
                new Frame.Audio(ByteBuffer.wrap(new byte[2]), 16000, 1, "a".repeat(129), -1), ignored -> {}));
    }
}
