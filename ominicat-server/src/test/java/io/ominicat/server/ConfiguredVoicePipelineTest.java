package io.ominicat.server;

import io.ominicat.core.WorkerThreads;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.ominicat.core.Frame;
import io.ominicat.core.FrameSink;
import io.ominicat.core.Processor;
import io.ominicat.core.spi.PluginRegistry;
import io.ominicat.core.spi.ProcessorFactory;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class ConfiguredVoicePipelineTest {
    @Test void configuredVoicePathFlushesAudioGatesDuplicateFinalsAndProducesTextAndPcm() throws Exception {
        ObjectMapper json = new ObjectMapper();
        var requests = new LinkedBlockingQueue<Request>();
        var capturedTranscript = new AtomicReference<Frame.Transcript>();
        var tapCloses = new AtomicInteger();
        byte[] synthesized = new byte[]{0, 1, 2, 3};
        var fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var fixtureWorkers = WorkerThreads.executor("voice-http-fixture-", 3); var http = TestHttpClient.create()) {
            fixture.setExecutor(fixtureWorkers);
            fixture.createContext("/v1/", exchange -> {
                byte[] input = exchange.getRequestBody().readAllBytes();
                requests.add(new Request(exchange.getRequestURI().getPath(), input,
                        exchange.getRequestHeaders().getFirst("Content-Type"), exchange.getRequestHeaders().getFirst("Authorization")));
                switch (exchange.getRequestURI().getPath()) {
                    case "/v1/audio/transcriptions" -> respond(exchange, "application/json", "{\"text\":\"hello\"}".getBytes(StandardCharsets.UTF_8));
                    case "/v1/chat/completions" -> respond(exchange, "text/event-stream", ("data: {\"choices\":[{\"delta\":{\"content\":\"Hello \"}}]}\n\n"
                            + "data: {\"choices\":[{\"delta\":{\"content\":\"there\"}}]}\n\n"
                            + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
                    case "/v1/audio/speech" -> respond(exchange, "application/octet-stream", synthesized);
                    default -> { exchange.sendResponseHeaders(404, -1); exchange.close(); }
                }
            });
            fixture.start();
            String base = "http://127.0.0.1:" + fixture.getAddress().getPort() + "/v1";
            var stages = List.of(
                    new RuntimeConfig.Stage("openai-transcribe", provider(base, "stt-test")),
                    new RuntimeConfig.Stage("test-transcript-tap", Map.of()),
                    new RuntimeConfig.Stage("transcript-gate", Map.of()),
                    new RuntimeConfig.Stage("prompt-template", Map.of("inputSpeaker", "user", "role", "assistant", "template", "{{role}}|{{speaker}}|{{transcript}}")),
                    new RuntimeConfig.Stage("openai-chat", provider(base, "chat-test")),
                    new RuntimeConfig.Stage("openai-speech", provider(base, "speech-test")));
            var config = new RuntimeConfig("127.0.0.1", 0, 16, 4, 4096, 60, null, stages);
            var registry = new PluginRegistry();
            registry.register(new ProcessorFactory() {
                @Override public String type() { return "test-transcript-tap"; }
                @Override public Processor create(Map<String, String> options) {
                    return new Processor() {
                        @Override public void process(Frame frame, FrameSink next) throws Exception {
                            if (frame instanceof Frame.Transcript transcript) capturedTranscript.set(transcript);
                            next.accept(frame);
                        }
                        @Override public void close() { tapCloses.incrementAndGet(); }
                    };
                }
            });
            try (var runtime = new OminiCatServer(config, registry).start()) {
                var inbox = new Inbox();
                var socket = http.newWebSocketBuilder().buildAsync(URI.create("ws://127.0.0.1:" + runtime.port() + "/ws"), inbox).join();
                var codec = new FrameCodec();
                socket.sendText(codec.encode(new Frame.Audio(ByteBuffer.wrap(new byte[]{4, 5, 6, 7}), 16000, 1, "microphone", 0)), true).join();
                socket.sendText("{\"type\":\"turn_end\"}", true).join();
                var text = new StringBuilder();
                Frame.Audio audio = null;
                for (int index = 0; index < 8 && audio == null; index++) {
                    String wire = inbox.messages.poll(5, TimeUnit.SECONDS);
                    assertNotNull(wire, "Configured voice pipeline produced no output");
                    Frame output = codec.decode(wire);
                    if (output instanceof Frame.Text chunk) text.append(chunk.text());
                    if (output instanceof Frame.Audio pcm) audio = pcm;
                }
                assertEquals("Hello there", text.toString());
                assertNotNull(audio); assertEquals(24000, audio.sampleRate()); assertEquals(1, audio.channels());
                assertEquals(ByteBuffer.wrap(synthesized), audio.data());
                Frame.Transcript transcript = capturedTranscript.get();
                assertNotNull(transcript); assertTrue(transcript.finalResult()); assertEquals("user", transcript.speaker());
                // Redeliver the same final through the actual socket: the gate must prevent another model/TTS call.
                socket.sendText(codec.encode(transcript), true).join();
                socket.sendText("{\"type\":\"end\"}", true).join();
                assertEquals(1000, inbox.closed.get(5, TimeUnit.SECONDS));
                assertEquals(2, tapCloses.get()); // disposable startup preflight + actual session
                assertEquals(3, requests.size());
                var all = new ArrayList<Request>(); requests.drainTo(all);
                assertEquals(List.of("/v1/audio/transcriptions", "/v1/chat/completions", "/v1/audio/speech"), all.stream().map(Request::path).toList());
                assertTrue(all.get(0).contentType().startsWith("multipart/form-data; boundary="));
                String multipart = new String(all.get(0).body(), StandardCharsets.ISO_8859_1);
                assertTrue(multipart.contains("stt-test")); assertTrue(multipart.contains("RIFF")); assertTrue(multipart.contains("WAVEfmt "));
                JsonNode chat = json.readTree(all.get(1).body());
                assertEquals("chat-test", chat.path("model").asText()); assertTrue(chat.path("stream").asBoolean());
                assertEquals("assistant|user|hello", chat.path("messages").get(1).path("content").asText());
                JsonNode speech = json.readTree(all.get(2).body());
                assertEquals("speech-test", speech.path("model").asText());
                assertEquals("Hello there", speech.path("input").asText()); assertEquals("pcm", speech.path("response_format").asText());
                assertTrue(all.stream().allMatch(request -> request.authorization() == null));
            }
        } finally { fixture.stop(0); }
    }

    private static Map<String, String> provider(String base, String model) { return Map.of("baseUrl", base, "model", model, "apiKeyEnv", "", "timeoutSeconds", "5"); }
    private static void respond(HttpExchange exchange, String type, byte[] bytes) throws java.io.IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(200, bytes.length);
        try (var body = exchange.getResponseBody()) { body.write(bytes); }
    }
    private record Request(String path, byte[] body, String contentType, String authorization) { }
    private static final class Inbox implements WebSocket.Listener {
        final LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
        final CompletableFuture<Integer> closed = new CompletableFuture<>();
        final StringBuilder fragments = new StringBuilder();
        @Override public void onOpen(WebSocket socket) { socket.request(1); }
        @Override public java.util.concurrent.CompletionStage<?> onText(WebSocket socket, CharSequence text, boolean last) {
            fragments.append(text);
            if (last) { messages.add(fragments.toString()); fragments.setLength(0); }
            socket.request(1); return null;
        }
        @Override public java.util.concurrent.CompletionStage<?> onClose(WebSocket socket, int code, String reason) { closed.complete(code); return null; }
        @Override public void onError(WebSocket socket, Throwable error) { closed.completeExceptionally(error); }
    }
}
