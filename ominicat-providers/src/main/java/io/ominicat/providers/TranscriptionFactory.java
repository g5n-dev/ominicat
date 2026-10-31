package io.ominicat.providers;

import io.ominicat.core.Frame;
import io.ominicat.core.FrameSink;
import io.ominicat.core.Processor;
import io.ominicat.core.spi.ProcessorFactory;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Bounded, track-isolated PCM utterances using the compatible multipart file API. */
public final class TranscriptionFactory implements ProcessorFactory {
    @Override public String type() { return "openai-transcribe"; }

    @Override public Processor create(Map<String, String> options) {
        Options.allowed(options, "baseUrl", "model", "apiKeyEnv", "timeoutSeconds", "maxResponseBytes",
                "maxAudioBytes", "maxStreams", "speaker", "speakers", "roles");
        CompatibleClient client = new CompatibleClient(options);
        int limit = Options.integer(options, "maxAudioBytes", 4 * 1024 * 1024, 2, 16 * 1024 * 1024);
        int maxStreams = Options.integer(options, "maxStreams", 8, 1, 32);
        String speaker = label(options.getOrDefault("speaker", "user"), "speaker");
        Map<String, String> speakers = mappings(options.getOrDefault("speakers", ""), "speakers");
        Map<String, String> roles = mappings(options.getOrDefault("roles", ""), "roles");
        return new Processor() {
            private final Map<String, Track> tracks = new LinkedHashMap<>();
            private int bufferedBytes;

            @Override public void process(Frame frame, FrameSink next) throws Exception {
                if (frame instanceof Frame.Audio pcm) {
                    append(pcm);
                    return;
                }
                if (frame instanceof Frame.Control control) {
                    if (control.signal() == Frame.Signal.INTERRUPT) onInterrupt();
                    if (control.signal() == Frame.Signal.TURN_END || control.signal() == Frame.Signal.SPEECH_STOPPED
                            || control.signal() == Frame.Signal.END) {
                        for (Map.Entry<String, Track> entry : tracks.entrySet()) {
                            flush(entry.getKey(), entry.getValue(), next);
                        }
                    }
                }
                next.accept(frame);
            }

            private void append(Frame.Audio pcm) {
                label(pcm.streamId(), "streamId");
                Track track = tracks.get(pcm.streamId());
                if (track == null && tracks.size() == maxStreams) {
                    throw new IllegalArgumentException("audio exceeds maxStreams=" + maxStreams);
                }
                if (track != null && (track.rate != pcm.sampleRate() || track.channels != pcm.channels())) {
                    throw new IllegalArgumentException("audio format cannot change within stream " + pcm.streamId());
                }
                int length = pcm.data().remaining();
                if ((long) bufferedBytes + length > limit) {
                    throw new IllegalArgumentException("buffered audio exceeds total maxAudioBytes");
                }
                long cursor = track == null ? 0 : track.nextSample;
                long start = pcm.startSample() < 0 ? cursor : pcm.startSample();
                if (track != null && pcm.startSample() >= 0
                        && (start < cursor || track.audio.size() > 0 && start != cursor)) {
                    throw new IllegalArgumentException("audio offsets must be ordered and contiguous within an utterance");
                }
                long samples = length / (pcm.channels() * 2L);
                long end;
                try {
                    end = Math.addExact(start, samples);
                    millis(start, pcm.sampleRate());
                    millis(end, pcm.sampleRate());
                } catch (ArithmeticException overflow) {
                    throw new IllegalArgumentException("audio sample timeline exceeds supported range", overflow);
                }
                if (track == null) {
                    track = new Track(pcm.sampleRate(), pcm.channels());
                    tracks.put(pcm.streamId(), track);
                }
                if (track.audio.size() == 0) track.utteranceStart = start;
                byte[] bytes = new byte[length];
                pcm.data().get(bytes);
                track.audio.writeBytes(bytes);
                track.nextSample = end;
                bufferedBytes += length;
            }

            private void flush(String streamId, Track track, FrameSink next) throws Exception {
                if (track.audio.size() == 0) return;
                byte[] pcm = track.audio.toByteArray();
                track.audio = new ByteArrayOutputStream();
                bufferedBytes -= pcm.length;
                long start = millis(track.utteranceStart, track.rate);
                long end = millis(track.nextSample, track.rate);
                String segmentId = streamId + ":" + ++track.sequence;
                String boundary = "ominicat-" + UUID.randomUUID();
                ByteArrayOutputStream multipart = new ByteArrayOutputStream();
                multipart.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n" + client.model + "\r\n"
                        + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"utterance.wav\"\r\nContent-Type: audio/wav\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                multipart.write(wav(pcm, track.rate, track.channels));
                multipart.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
                try (CompatibleClient.Response response = client.post("/audio/transcriptions", multipart.toByteArray(),
                        "multipart/form-data; boundary=" + boundary)) {
                    var json = CompatibleClient.JSON.readTree(response.bytes());
                    if (json == null || !json.path("text").isTextual()) {
                        throw new ProviderException("provider transcription omitted text", 502);
                    }
                    String attributedSpeaker = speakers.getOrDefault(streamId, speaker);
                    next.accept(new Frame.Transcript(json.path("text").asText(), attributedSpeaker, start, end, true,
                            -1, segmentId, 0, roles.getOrDefault(streamId, attributedSpeaker)));
                }
            }

            @Override public void onInterrupt() {
                tracks.values().forEach(track -> track.audio = new ByteArrayOutputStream());
                bufferedBytes = 0;
            }

            @Override public void close() {
                tracks.clear();
                bufferedBytes = 0;
            }
        };
    }

    private static final class Track {
        private final int rate;
        private final int channels;
        private ByteArrayOutputStream audio = new ByteArrayOutputStream();
        private long utteranceStart;
        private long nextSample;
        private long sequence;
        private Track(int rate, int channels) { this.rate = rate; this.channels = channels; }
    }

    private static long millis(long sample, int rate) {
        return Math.addExact(Math.multiplyExact(sample / rate, 1000), sample % rate * 1000 / rate);
    }

    private static String label(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException(name + " must contain 1..128 characters");
        }
        return value;
    }

    private static Map<String, String> mappings(String value, String name) {
        Map<String, String> mappings = new HashMap<>();
        if (value.isBlank()) return Map.of();
        for (String item : value.split(",", -1)) {
            int separator = item.indexOf('=');
            if (separator <= 0 || separator == item.length() - 1 || item.indexOf('=', separator + 1) >= 0) {
                throw new IllegalArgumentException(name + " must use comma-separated stream=value entries");
            }
            String stream = label(item.substring(0, separator).strip(), "streamId");
            String mapped = label(item.substring(separator + 1).strip(), name);
            if (mappings.putIfAbsent(stream, mapped) != null) {
                throw new IllegalArgumentException("duplicate stream mapping in " + name + ": " + stream);
            }
        }
        if (mappings.size() > 32) throw new IllegalArgumentException(name + " may contain at most 32 mappings");
        return Map.copyOf(mappings);
    }

    static byte[] wav(byte[] pcm, int rate, int channels) {
        ByteBuffer wav = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + pcm.length)
                .put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1)
                .putShort((short) channels).putInt(rate).putInt(rate * channels * 2)
                .putShort((short) (channels * 2)).putShort((short) 16)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcm.length).put(pcm);
        return wav.array();
    }
}
