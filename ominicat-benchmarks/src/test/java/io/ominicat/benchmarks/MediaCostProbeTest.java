package io.ominicat.benchmarks;

import io.ominicat.core.Frame;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class MediaCostProbeTest {

    @ParameterizedTest
    @ValueSource(strings = {"pcm-640", "pcm-3840", "image-64k", "image-1m"})
    void constructionProducesVerifiedIndependentSnapshots(String payload) throws Exception {
        var probe = probe(payload);
        assertEquals(payloadSize(payload), probe.size);
        for (int index = 0; index < 64; index++) {
            Frame frame = probe.construct(index);
            probe.verifyFrame(frame, index);
        }
        // Mutating the source after construction must not change an already-held frame.
        Frame held = probe.construct(0);
        ByteBuffer heldView = held instanceof io.ominicat.core.Frame.Audio audio ? audio.data()
                : ((io.ominicat.core.Frame.Image) held).data();
        byte heldByte = heldView.get(0);
        probe.sources[0][0] ^= (byte) 0xFF;
        assertEquals(heldByte, heldView.get(0), "post-construction source mutation leaked into the snapshot");
        probe.sources[0][0] ^= (byte) 0xFF;
    }

    @ParameterizedTest
    @ValueSource(strings = {"pcm-640", "image-64k"})
    void dispatchPreservesIdentityThroughFourRealStages(String payload) throws Exception {
        var probe = probe(payload);
        var sink = new MediaCostProbeTestSink();
        for (int index = 0; index < 64; index++) {
            Frame input = probe.prebuilt[index];
            probe.witnessed = index + 1;
            probe.sequence = index + 1;
            probe.passThrough.process(input, sink);
            assertSame(input, sink.output, "pass-through must preserve frame identity");
            assertEquals(probe.expectedWitness(index + 1), probe.witnessed);
            sink.output = null;
        }
    }

    @Test void rejectsUnknownPayloadsAndRequiresOperations() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> probe("pcm-128"));
    }

    private static int payloadSize(String payload) {
        return switch (payload) {
            case "pcm-640" -> 640;
            case "pcm-3840" -> 3_840;
            case "image-64k" -> 65_536;
            case "image-1m" -> 1_048_576;
            default -> throw new IllegalArgumentException(payload);
        };
    }

    private static MediaCostProbe probe(String payload) {
        MediaCostProbe probe = new MediaCostProbe();
        probe.payload = payload;
        probe.prepareFixtures();
        return probe;
    }

    private static final class MediaCostProbeTestSink implements io.ominicat.core.FrameSink {
        Frame output;
        @Override public void accept(Frame frame) {
            if (output != null) throw new IllegalStateException("duplicate terminal output");
            output = frame;
        }
    }
}
