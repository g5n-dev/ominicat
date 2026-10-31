package io.ominicat.providers;

import io.ominicat.core.Frame;
import io.ominicat.core.FrameSink;
import io.ominicat.core.Processor;
import io.ominicat.core.spi.ProcessorFactory;
import java.nio.ByteBuffer;
import java.util.Map;

/** Turn-buffered text to 24 kHz mono PCM; emits after TURN_END or END. */
public final class SpeechFactory implements ProcessorFactory {
    @Override public String type() { return "openai-speech"; }
    @Override public Processor create(Map<String, String> options) {
        Options.allowed(options, "baseUrl", "model", "apiKeyEnv", "timeoutSeconds", "maxResponseBytes", "voice", "maxTextChars");
        CompatibleClient client = new CompatibleClient(options);
        int maxChars = Options.integer(options, "maxTextChars", 4096, 1, 4096);
        String voice = options.getOrDefault("voice", "alloy");
        if (voice.isBlank()) throw new IllegalArgumentException("voice must not be blank");
        return new Processor() {
            private final StringBuilder text = new StringBuilder();
            @Override public void process(Frame frame, FrameSink next) throws Exception {
                if (frame instanceof Frame.Text chunk) {
                    if ((long) text.length() + chunk.text().length() > maxChars) throw new IllegalArgumentException("speech text exceeds maxTextChars");
                    text.append(chunk.text());
                } else if (frame instanceof Frame.Control control) {
                    if (control.signal() == Frame.Signal.INTERRUPT) onInterrupt();
                    if (control.signal() == Frame.Signal.TURN_END || control.signal() == Frame.Signal.END) {
                        String input = text.toString(); text.setLength(0);
                        if (!input.isBlank()) {
                            try (CompatibleClient.Response response = client.post("/audio/speech", Map.of("model", client.model, "input", input, "voice", voice, "response_format", "pcm"))) {
                                byte[] pcm = response.bytes();
                                if (pcm.length == 0 || pcm.length % 2 != 0) throw new ProviderException("provider returned invalid PCM", 502);
                                next.accept(new Frame.Audio(ByteBuffer.wrap(pcm), 24000, 1));
                            }
                        }
                    }
                }
                next.accept(frame);
            }
            @Override public void onInterrupt() { text.setLength(0); }
        };
    }
}
