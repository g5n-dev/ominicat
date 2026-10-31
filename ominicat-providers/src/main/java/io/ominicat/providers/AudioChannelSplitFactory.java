package io.ominicat.providers;

import io.ominicat.core.Frame;
import io.ominicat.core.spi.ProcessorFactory;
import io.ominicat.core.Processor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Map;

/** Deinterleaves PCM channels into independently identified mono streams. */
public final class AudioChannelSplitFactory implements ProcessorFactory {
    @Override public String type() { return "audio-channel-split"; }
    @Override public Processor create(Map<String, String> options) {
        Options.allowed(options, "streamIds");
        String[] names = options.getOrDefault("streamIds", "").isBlank() ? new String[0]
                : options.get("streamIds").split(",", -1);
        if (Arrays.stream(names).anyMatch(String::isBlank) || Arrays.stream(names).distinct().count() != names.length) {
            throw new IllegalArgumentException("streamIds must be nonblank and unique");
        }
        return (frame, next) -> {
            if (!(frame instanceof Frame.Audio audio)) { next.accept(frame); return; }
            if (names.length != 0 && names.length != audio.channels()) throw new IllegalArgumentException("streamIds count must match channel count");
            int samples = audio.data().remaining() / 2 / audio.channels();
            ByteBuffer source = audio.data().order(ByteOrder.LITTLE_ENDIAN);
            for (int channel = 0; channel < audio.channels(); channel++) {
                ByteBuffer mono = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN);
                for (int sample = 0; sample < samples; sample++) mono.putShort(source.getShort((sample * audio.channels() + channel) * 2));
                mono.flip();
                String streamId = names.length == 0 ? audio.streamId() + "/ch-" + channel : names[channel];
                next.accept(new Frame.Audio(mono, audio.sampleRate(), 1, streamId, audio.startSample()));
            }
        };
    }
}
