package io.ominicat.providers;

import static org.junit.jupiter.api.Assertions.*;

import io.ominicat.core.Frame;
import io.ominicat.core.Processor;
import io.ominicat.core.spi.PluginRegistry;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OfflineProvidersTest {
    @Test
    void echoPreservesAnyFrameIncludingApplicationExtensions() throws Exception {
        record Extension(int value) implements Frame {}
        Frame input = new Extension(42);
        List<Frame> output = new ArrayList<>();
        new EchoFactory().create(Map.of()).process(input, output::add);
        assertSame(input, output.getFirst());
        assertThrows(IllegalArgumentException.class, () -> new EchoFactory().create(Map.of("typo", "x")));
    }

    @Test
    void vadUsesPerChannelSampleDurationAndDoesNotRepeatTransitions() throws Exception {
        Processor vad = new RmsVadFactory().create(Map.of("threshold", "0.5", "silenceMillis", "40"));
        List<Frame> output = new ArrayList<>();
        Frame.Audio active = pcm(640, (short) 16384, 2);
        Frame.Audio silence = pcm(640, (short) 0, 2);
        vad.process(active, output::add);
        vad.process(active, output::add);
        vad.process(silence, output::add);
        assertEquals(1, output.stream().filter(frame -> frame instanceof Frame.Control).count());
        vad.process(silence, output::add);
        assertEquals(List.of(new Frame.Control(Frame.Signal.SPEECH_STARTED), active, active, silence,
                new Frame.Control(Frame.Signal.SPEECH_STOPPED), silence), output);
    }

    @Test
    void vadInterruptAndEndResetState() throws Exception {
        Processor vad = new RmsVadFactory().create(Map.of());
        List<Frame> output = new ArrayList<>();
        Frame.Audio active = pcm(320, Short.MAX_VALUE, 1);
        vad.process(active, output::add);
        vad.onInterrupt();
        vad.process(active, output::add);
        vad.process(new Frame.Control(Frame.Signal.END), output::add);
        assertEquals(2, output.stream().filter(frame -> frame.equals(new Frame.Control(Frame.Signal.SPEECH_STARTED))).count());
        assertEquals(1, output.stream().filter(frame -> frame.equals(new Frame.Control(Frame.Signal.SPEECH_STOPPED))).count());
        assertEquals(new Frame.Control(Frame.Signal.END), output.getLast());
    }

    @Test
    void factoriesAreDiscoverableAndOptionsFailEarly() {
        assertTrue(new PluginRegistry().types().containsAll(List.of("echo", "rms-vad", "openai-chat",
                "openai-transcribe", "openai-speech", "audio-channel-split", "transcript-gate")));
        for (String threshold : List.of("0", "NaN", "1.1")) {
            assertThrows(IllegalArgumentException.class, () -> new RmsVadFactory().create(Map.of("threshold", threshold)));
        }
        assertThrows(IllegalArgumentException.class, () -> new RmsVadFactory().create(Map.of("silenceMillis", "0")));
        assertThrows(IllegalArgumentException.class, () -> new RmsVadFactory().create(Map.of("unknown", "0")));
        assertThrows(IllegalArgumentException.class, () -> new ChatFactory().create(Map.of("unknown", "x")));
    }

    private static Frame.Audio pcm(int samples, short amplitude, int channels) {
        ByteBuffer bytes = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < samples; i++) bytes.putShort(amplitude);
        bytes.flip();
        return new Frame.Audio(bytes, 16000, channels);
    }
}
