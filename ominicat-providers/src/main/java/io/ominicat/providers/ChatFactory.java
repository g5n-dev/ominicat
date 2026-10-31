package io.ominicat.providers;

import com.fasterxml.jackson.databind.JsonNode;
import io.ominicat.core.Frame;
import io.ominicat.core.FrameSink;
import io.ominicat.core.Processor;
import io.ominicat.core.spi.ProcessorFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/** Streaming compatible Chat Completions adapter. Each input is a stateless request. */
public final class ChatFactory implements ProcessorFactory {
    @Override public String type() { return "openai-chat"; }
    @Override public Processor create(Map<String, String> options) {
        Options.allowed(options, "baseUrl", "model", "apiKeyEnv", "timeoutSeconds", "maxResponseBytes", "systemPrompt", "imagePrompt", "maxOutputTokens", "maxInputChars");
        CompatibleClient client = new CompatibleClient(options);
        int maxTokens = Options.integer(options, "maxOutputTokens", 1024, 1, 16384);
        int maxInputChars = Options.integer(options, "maxInputChars", 65536, 1, 1_000_000);
        return (frame, next) -> {
            Object content;
            if (frame instanceof Frame.Text text) {
                if (text.text().length() > maxInputChars) throw new IllegalArgumentException("text exceeds maxInputChars");
                content = text.text();
            } else if (frame instanceof Frame.Transcript transcript) {
                if (!transcript.finalResult()) { next.accept(frame); return; }
                if (transcript.text().length() > maxInputChars) throw new IllegalArgumentException("transcript exceeds maxInputChars");
                content = transcript.text();
            } else if (frame instanceof Frame.Image image) {
                if (image.data().remaining() > client.maxBytes) throw new IllegalArgumentException("image exceeds byte budget");
                byte[] bytes = new byte[image.data().remaining()]; image.data().get(bytes);
                content = List.of(Map.of("type", "text", "text", options.getOrDefault("imagePrompt", "Describe this image.")),
                        Map.of("type", "image_url", "image_url", Map.of("url", "data:" + image.mimeType() + ";base64," + Base64.getEncoder().encodeToString(bytes))));
            } else { next.accept(frame); return; }
            Map<String, Object> request = Map.of("model", client.model, "stream", true, "max_completion_tokens", maxTokens,
                    "messages", List.of(Map.of("role", "system", "content", options.getOrDefault("systemPrompt", "Be concise and helpful.")),
                            Map.of("role", "user", "content", content)));
            try (CompatibleClient.Response response = client.post("/chat/completions", request)) {
                readStream(response, next);
            }
            next.accept(new Frame.Control(Frame.Signal.TURN_END));
        };
    }

    static void readStream(CompatibleClient.Response response, FrameSink next) throws Exception {
        PushbackReader reader = new PushbackReader(new BufferedReader(new InputStreamReader(response.stream(),
                StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT))), 1);
        int first = reader.read();
        if (first != -1 && first != 0xfeff) reader.unread(first);
        StringBuilder event = new StringBuilder();
        int consumed = 0;
        while (true) {
            String line = boundedLine(reader);
            if (line == null) throw new IOException("provider stream ended before DONE");
            consumed += line.length() + 1;
            if (consumed > response.maxBytes() / 4) throw new IOException("provider stream exceeded character budget");
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("provider stream interrupted");
            if (response.timedOut()) throw new IOException("provider response deadline exceeded");
            if (line.isEmpty()) {
                if (event.isEmpty()) continue;
                String payload = event.toString(); event.setLength(0);
                if (payload.equals("[DONE]")) return;
                JsonNode json = CompatibleClient.JSON.readTree(payload);
                if (json.has("error")) throw new ProviderException("provider reported a stream error", 502);
                JsonNode content = json.path("choices").path(0).path("delta").path("content");
                if (content.isTextual() && !content.asText().isEmpty()) next.accept(new Frame.Text(content.asText()));
            } else if (line.startsWith("data:")) {
                if (!event.isEmpty()) event.append('\n');
                event.append(line.substring(5).stripLeading());
                if (event.length() > 65536) throw new IOException("provider SSE event too large");
            }
        }
    }
    private static String boundedLine(PushbackReader reader) throws IOException {
        StringBuilder line = new StringBuilder();
        int character;
        while ((character = reader.read()) != -1) {
            if (character == '\n') return line.toString();
            if (character == '\r') {
                int following = reader.read();
                if (following != -1 && following != '\n') reader.unread(following);
                return line.toString();
            }
            line.append((char) character);
            if (line.length() > 65536) throw new IOException("provider SSE line too large");
        }
        return line.isEmpty() ? null : line.toString();
    }
}
