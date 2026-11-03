package io.ominicat.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.ominicat.core.Frame;
import io.ominicat.core.WorkerThreads;
import io.ominicat.core.spi.PluginRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/** Executes the published service wiring against three separate, local protocol fixtures. */
@Timeout(20)
class VoiceServiceDeploymentTest {
    @Test void publishedYamlRoutesOneVoiceTurnThroughDistinctServicesAndDrains() throws Exception {
        RuntimeConfig published = RuntimeConfig.load(Path.of("..", "deploy", "voice-assistant", "runtime.yaml"));
        assertEquals(List.of("openai-transcribe", "transcript-gate", "prompt-template", "openai-chat", "openai-speech"),
                published.stages().stream().map(RuntimeConfig.Stage::type).toList());
        byte[] synthesized = {0, 1, 2, 3, 4, 5};
        byte[] sse = ("data: {\"choices\":[{\"delta\":{\"content\":\"Please contact \"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"customer support.\"}}]}\n\n"
                + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);

        try (var workers = WorkerThreads.executor("deployment-fixture-", 3);
             var asr = new ServiceFixture(workers, "/v1/audio/transcriptions", "application/json",
                     "{\"text\":\"Where is my order?\"}".getBytes(StandardCharsets.UTF_8));
             var llm = new ServiceFixture(workers, "/v1/chat/completions", "text/event-stream", sse);
             var tts = new ServiceFixture(workers, "/v1/audio/speech", "application/octet-stream", synthesized);
             var http = TestHttpClient.create()) {
            // Rebind by the declared service address, not by stage type: a swapped endpoint must fail.
            Map<String, String> endpoints = Map.of(
                    "https://asr.example.invalid/v1", asr.baseUrl(),
                    "https://llm.example.invalid/v1", llm.baseUrl(),
                    "https://tts.example.invalid/v1", tts.baseUrl());
            List<RuntimeConfig.Stage> stages = new ArrayList<>();
            for (RuntimeConfig.Stage stage : published.stages()) {
                Map<String, String> options = new HashMap<>(stage.options());
                if (options.containsKey("baseUrl")) {
                    String endpoint = endpoints.get(options.get("baseUrl"));
                    assertNotNull(endpoint, "Every configured provider must use a declared local fixture");
                    options.put("baseUrl", endpoint);
                    options.put("apiKeyEnv", "");
                }
                stages.add(new RuntimeConfig.Stage(stage.type(), options));
            }
            var config = new RuntimeConfig("127.0.0.1", 0, published.queueCapacity(), published.maxSessions(),
                    published.maxFrameBytes(), published.idleTimeoutSeconds(), null, stages, published.maxConnections());
            try (var runtime = new OminiCatServer(config, new PluginRegistry()).start()) {
                // Preflight validates configuration without sending model requests.
                assertEquals(0, asr.calls.get() + llm.calls.get() + tts.calls.get());
                var inbox = new Inbox();
                WebSocket socket = http.newWebSocketBuilder()
                        .buildAsync(URI.create("ws://127.0.0.1:" + runtime.port() + "/ws"), inbox)
                        .get(5, TimeUnit.SECONDS);
                var codec = new FrameCodec();
                socket.sendText(codec.encode(new Frame.Audio(ByteBuffer.wrap(new byte[]{6, 7, 8, 9}),
                        16000, 1, "microphone", 0)), true).get(5, TimeUnit.SECONDS);
                socket.sendText("{\"type\":\"turn_end\"}", true).get(5, TimeUnit.SECONDS);

                StringBuilder text = new StringBuilder();
                Frame.Audio audio = null;
                for (int index = 0; index < 8 && audio == null; index++) {
                    String wire = inbox.messages.poll(5, TimeUnit.SECONDS);
                    assertNotNull(wire, "Published voice service YAML produced no output");
                    Frame output = codec.decode(wire);
                    if (output instanceof Frame.Text chunk) text.append(chunk.text());
                    else if (output instanceof Frame.Audio pcm) audio = pcm;
                    else assertInstanceOf(Frame.Control.class, output);
                }
                assertEquals("Please contact customer support.", text.toString());
                assertNotNull(audio);
                assertEquals(24000, audio.sampleRate());
                assertEquals(1, audio.channels());
                assertEquals(ByteBuffer.wrap(synthesized), audio.data());

                socket.sendText("{\"type\":\"end\"}", true).get(5, TimeUnit.SECONDS);
                assertEquals(1000, inbox.closed.get(5, TimeUnit.SECONDS));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (runtime.resources().admittedSessions() != 0 && System.nanoTime() < deadline) Thread.sleep(10);
                assertEquals(0, runtime.resources().admittedSessions());
                assertEquals(0, runtime.resources().pipelineWorkers());
                assertEquals(0, runtime.resources().activeSessions());
                boolean ended = false;
                for (String wire : inbox.messages) {
                    Frame.Control control = assertInstanceOf(Frame.Control.class, codec.decode(wire),
                            "No extra text or audio may appear after the single synthesized response");
                    if (control.signal() == Frame.Signal.END) ended = true;
                }
                assertTrue(ended, "END reaches the client before normal session closure");

                Request recognition = asr.onlyRequest();
                assertEquals("/v1/audio/transcriptions", recognition.path());
                assertTrue(recognition.contentType().startsWith("multipart/form-data; boundary="));
                String multipart = new String(recognition.body(), StandardCharsets.ISO_8859_1);
                assertTrue(multipart.contains("\r\n\r\nreplace-with-asr-model\r\n"));
                assertTrue(multipart.contains("filename=\"utterance.wav\""));
                assertTrue(multipart.contains("RIFF"));
                assertTrue(multipart.contains("WAVEfmt "));

                var json = new ObjectMapper();
                Request completion = llm.onlyRequest();
                assertEquals("/v1/chat/completions", completion.path());
                assertEquals("application/json", completion.contentType());
                var chat = json.readTree(completion.body());
                assertEquals("replace-with-chat-model", chat.path("model").asText());
                assertTrue(chat.path("stream").asBoolean());
                assertEquals(256, chat.path("max_completion_tokens").asInt());
                assertEquals("user", chat.path("messages").get(1).path("role").asText());
                String prompt = chat.path("messages").get(1).path("content").asText();
                assertTrue(prompt.contains("user: Where is my order?"));
                assertTrue(prompt.contains("Role: assistant"));
                assertTrue(prompt.contains("Do not invent account or order information."));

                Request synthesis = tts.onlyRequest();
                assertEquals("/v1/audio/speech", synthesis.path());
                assertEquals("application/json", synthesis.contentType());
                var speech = json.readTree(synthesis.body());
                assertEquals("replace-with-speech-model", speech.path("model").asText());
                assertEquals("Please contact customer support.", speech.path("input").asText());
                assertEquals("pcm", speech.path("response_format").asText());
                assertEquals("alloy", speech.path("voice").asText());
            }
        }
    }

    private record Request(String path, byte[] body, String contentType, String authorization, String method) { }

    private static final class ServiceFixture implements AutoCloseable {
        private static final int MAX_REQUEST_BYTES = 8192;
        final HttpServer server;
        final AtomicInteger calls = new AtomicInteger();
        final ArrayBlockingQueue<Request> requests = new ArrayBlockingQueue<>(4);

        ServiceFixture(Executor executor, String path, String contentType, byte[] response) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                try (exchange) {
                    calls.incrementAndGet();
                    byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
                    if (body.length > MAX_REQUEST_BYTES) { exchange.sendResponseHeaders(413, -1); return; }
                    Request request = new Request(exchange.getRequestURI().getPath(), body,
                            exchange.getRequestHeaders().getFirst("Content-Type"),
                            exchange.getRequestHeaders().getFirst("Authorization"), exchange.getRequestMethod());
                    if (!requests.offer(request)) { exchange.sendResponseHeaders(429, -1); return; }
                    if (!request.path().equals(path)) { exchange.sendResponseHeaders(404, -1); return; }
                    respond(exchange, contentType, response);
                }
            });
            server.start();
        }

        String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"; }

        Request onlyRequest() {
            assertEquals(1, calls.get(), "Each independent service receives exactly one request");
            assertEquals(1, requests.size());
            Request request = requests.remove();
            assertEquals("POST", request.method());
            assertNull(request.authorization(), "Local fixtures must not resolve or transmit provider credentials");
            return request;
        }

        @Override public void close() { server.stop(0); }
    }

    private static void respond(HttpExchange exchange, String type, byte[] response) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(200, response.length);
        try (var output = exchange.getResponseBody()) { output.write(response); }
    }

    private static final class Inbox implements WebSocket.Listener {
        final ArrayBlockingQueue<String> messages = new ArrayBlockingQueue<>(16);
        final CompletableFuture<Integer> closed = new CompletableFuture<>();
        final StringBuilder fragments = new StringBuilder();

        @Override public void onOpen(WebSocket socket) { socket.request(1); }
        @Override public java.util.concurrent.CompletionStage<?> onText(WebSocket socket, CharSequence text, boolean last) {
            if ((long) fragments.length() + text.length() > 8192) {
                closed.completeExceptionally(new IllegalStateException("Fixture output exceeded its character budget"));
                socket.abort();
                return null;
            }
            fragments.append(text);
            if (last) {
                if (!messages.offer(fragments.toString())) {
                    closed.completeExceptionally(new IllegalStateException("Fixture output queue overflow"));
                    socket.abort();
                }
                fragments.setLength(0);
            }
            socket.request(1);
            return null;
        }
        @Override public java.util.concurrent.CompletionStage<?> onClose(WebSocket socket, int code, String reason) {
            closed.complete(code);
            return null;
        }
        @Override public void onError(WebSocket socket, Throwable error) { closed.completeExceptionally(error); }
    }
}
