package io.ominicat.providers;

import io.ominicat.core.Frame;
import io.ominicat.core.FrameSink;
import io.ominicat.core.Processor;
import io.ominicat.core.spi.ProcessorFactory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;

/** Deterministic energy detector; intentionally not a neural voice activity detector. */
public final class RmsVadFactory implements ProcessorFactory {
    @Override public String type() { return "rms-vad"; }
    @Override public Processor create(Map<String, String> options) {
        Options.allowed(options, "threshold", "silenceMillis");
        double threshold = Double.parseDouble(options.getOrDefault("threshold", "0.02"));
        if (!Double.isFinite(threshold) || threshold <= 0 || threshold > 1) {
            throw new IllegalArgumentException("threshold must be in (0,1]");
        }
        int silenceMillis = Options.integer(options, "silenceMillis", 300, 1, 60_000);
        return new Processor() {
            private boolean speaking;
            private double silence;
            @Override public void process(Frame frame, FrameSink next) throws Exception {
                if (frame instanceof Frame.Audio audio) {
                    ByteBuffer pcm = audio.data().order(ByteOrder.LITTLE_ENDIAN);
                    int samples = pcm.remaining() / 2;
                    double energy = 0;
                    while (pcm.hasRemaining()) {
                        double value = pcm.getShort() / 32768.0;
                        energy += value * value;
                    }
                    boolean active = Math.sqrt(energy / samples) >= threshold;
                    if (active) {
                        silence = 0;
                        if (!speaking) { speaking = true; next.accept(new Frame.Control(Frame.Signal.SPEECH_STARTED)); }
                    } else if (speaking) {
                        silence += samples * 1000.0 / audio.channels() / audio.sampleRate();
                        if (silence >= silenceMillis) {
                            speaking = false;
                            silence = 0;
                            next.accept(new Frame.Control(Frame.Signal.SPEECH_STOPPED));
                        }
                    }
                } else if (frame instanceof Frame.Control control && control.signal() == Frame.Signal.END) {
                    if (speaking) next.accept(new Frame.Control(Frame.Signal.SPEECH_STOPPED));
                    onInterrupt();
                }
                next.accept(frame);
            }
            @Override public void onInterrupt() { speaking = false; silence = 0; }
        };
    }
}
