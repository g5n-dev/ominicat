package io.ominicat.providers;

import static org.junit.jupiter.api.Assertions.*;

import io.ominicat.core.Frame;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class TranscriptionTimelineOracleTest extends ProviderHttpTestSupport {
    private record Expected(byte[] pcm, int rate, int channels) {}
    private static final class Track {
        final int rate, channels;
        long cursor, start, sequence;
        ByteArrayOutputStream pending = new ByteArrayOutputStream();
        Track(int rate, int channels) { this.rate = rate; this.channels = channels; }
    }

    @Test void seededTrackSchedulesMatchIndependentPcmAndSampleTimelineOracle() throws Exception {
        var requests = new ConcurrentLinkedQueue<Expected>();
        var requestCount = new java.util.concurrent.atomic.AtomicInteger();
        route("/audio/transcriptions", exchange -> {
            requestCount.incrementAndGet();
            byte[] body = exchange.getRequestBody().readAllBytes();
            int offset = new String(body, StandardCharsets.ISO_8859_1).indexOf("RIFF");
            assertTrue(offset >= 0);
            ByteBuffer wav = ByteBuffer.wrap(body, offset, body.length - offset).slice().order(ByteOrder.LITTLE_ENDIAN);
            Expected expected = requests.poll();
            assertNotNull(expected);
            assertEquals(expected.rate(), wav.getInt(24));
            assertEquals(expected.channels(), wav.getShort(22));
            assertArrayEquals(expected.pcm(), Arrays.copyOfRange(body, offset + 44, offset + 44 + wav.getInt(40)));
            respond(exchange, 200, "application/json", "{\"text\":\"recognized\"}");
        });
        for (long seed = 0x5a91e; seed < 0x5a91e + 4; seed++) {
            Random random = new Random(seed);
            var processor = new TranscriptionFactory().create(options("maxStreams", "3", "maxAudioBytes", "32768",
                    "speakers", "s0=p0,s1=p1,s2=p2", "roles", "s0=user,s1=assistant,s2=user"));
            Map<String, Track> tracks = new LinkedHashMap<>();
            List<Frame> output = new ArrayList<>();
            for (int round = 0; round < 12; round++) {
                for (int chunk = 0; chunk < 30; chunk++) {
                    int index = random.nextInt(3);
                    String stream = "s" + index;
                    Track track = tracks.computeIfAbsent(stream, ignored -> new Track(new int[]{8000,16000,48000}[index], index == 1 ? 2 : 1));
                    boolean known = random.nextBoolean();
                    long start = track.cursor;
                    if (track.pending.size() == 0 && known) start += random.nextInt(101);
                    int samples = 1 + random.nextInt(30);
                    byte[] pcm = new byte[samples * track.channels * 2];
                    random.nextBytes(pcm);
                    if (track.pending.size() == 0) track.start = start;
                    track.pending.writeBytes(pcm);
                    track.cursor = start + samples;
                    processor.process(new Frame.Audio(ByteBuffer.wrap(pcm), track.rate, track.channels, stream,
                            known ? start : -1), output::add);
                }
                if (round % 4 == 1) {
                    processor.onInterrupt();
                    tracks.values().forEach(track -> track.pending.reset());
                    continue;
                }
                List<String> flushed = new ArrayList<>();
                tracks.forEach((stream, track) -> {
                    if (track.pending.size() > 0) {
                        requests.add(new Expected(track.pending.toByteArray(), track.rate, track.channels));
                        flushed.add(stream);
                    }
                });
                int before = output.size();
                processor.process(new Frame.Control(Frame.Signal.TURN_END), output::add);
                List<Frame.Transcript> transcripts = output.subList(before, output.size()).stream()
                        .filter(Frame.Transcript.class::isInstance).map(Frame.Transcript.class::cast).toList();
                assertEquals(flushed.size(), transcripts.size(), "seed=" + seed + ", round=" + round);
                for (int i = 0; i < flushed.size(); i++) {
                    String stream = flushed.get(i);
                    Track track = tracks.get(stream);
                    Frame.Transcript transcript = transcripts.get(i);
                    assertEquals(stream + ":" + ++track.sequence, transcript.segmentId());
                    assertEquals(millis(track.start, track.rate), transcript.startMillis());
                    assertEquals(millis(track.cursor, track.rate), transcript.endMillis());
                    assertEquals("p" + stream.substring(1), transcript.speaker());
                    assertEquals(stream.equals("s1") ? "assistant" : "user", transcript.role());
                    track.pending.reset();
                }
                assertTrue(requests.isEmpty());
            }
            processor.close();
        }
        assertEquals(108, requestCount.get());
    }

    private static long millis(long sample, int rate) {
        return BigInteger.valueOf(sample).multiply(BigInteger.valueOf(1000)).divide(BigInteger.valueOf(rate)).longValueExact();
    }
}
