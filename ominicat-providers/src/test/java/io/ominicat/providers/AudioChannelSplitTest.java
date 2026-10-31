package io.ominicat.providers;

import static org.junit.jupiter.api.Assertions.*;

import io.ominicat.core.Frame;
import io.ominicat.core.Processor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AudioChannelSplitTest {
    @Test
    void deinterleavesStereoWithoutCrossContaminationAndPreservesSampleOffset() throws Exception {
        ByteBuffer stereo = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        stereo.putShort((short) 1).putShort((short) 101).putShort((short) 2).putShort((short) 102)
                .putShort((short) -3).putShort((short) -103).flip();
        Processor split = new AudioChannelSplitFactory().create(Map.of("streamIds", "left,right"));
        List<Frame> output = new ArrayList<>();
        Frame.Audio input = new Frame.Audio(stereo, 48000, 2, "stereo", 900);
        split.process(input, output::add);
        Frame.Audio left = assertInstanceOf(Frame.Audio.class, output.get(0));
        Frame.Audio right = assertInstanceOf(Frame.Audio.class, output.get(1));
        assertEquals("left", left.streamId());
        assertEquals("right", right.streamId());
        assertEquals(900, left.startSample());
        assertEquals(900, right.startSample());
        assertEquals(48000, left.sampleRate());
        assertEquals(1, left.channels());
        assertArrayEquals(new short[]{1, 2, -3}, samples(left));
        assertArrayEquals(new short[]{101, 102, -103}, samples(right));
        assertEquals(12, input.data().remaining());
    }

    @Test
    void defaultNamesAreStableAndNonAudioFramesPassThrough() throws Exception {
        Processor split = new AudioChannelSplitFactory().create(Map.of());
        List<Frame> output = new ArrayList<>();
        split.process(new Frame.Audio(ByteBuffer.wrap(new byte[4]), 16000, 2, "track", -1), output::add);
        split.process(new Frame.Text("text"), output::add);
        assertEquals("track/ch-0", assertInstanceOf(Frame.Audio.class, output.get(0)).streamId());
        assertEquals("track/ch-1", assertInstanceOf(Frame.Audio.class, output.get(1)).streamId());
        assertEquals(new Frame.Text("text"), output.get(2));
    }

    @Test
    void invalidNamesAndChannelCountFailExplicitly() {
        AudioChannelSplitFactory factory = new AudioChannelSplitFactory();
        assertThrows(IllegalArgumentException.class, () -> factory.create(Map.of("streamIds", "same,same")));
        assertThrows(IllegalArgumentException.class, () -> factory.create(Map.of("streamIds", "left,")));
        Processor split = factory.create(Map.of("streamIds", "only-one"));
        assertThrows(IllegalArgumentException.class, () -> split.process(
                new Frame.Audio(ByteBuffer.wrap(new byte[4]), 16000, 2), ignored -> {}));
    }

    private static short[] samples(Frame.Audio audio) {
        ByteBuffer bytes = audio.data().order(ByteOrder.LITTLE_ENDIAN);
        short[] samples = new short[bytes.remaining() / 2];
        for (int i = 0; i < samples.length; i++) samples[i] = bytes.getShort();
        return samples;
    }
}
