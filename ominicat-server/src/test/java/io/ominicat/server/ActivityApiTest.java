package io.ominicat.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ominicat.core.Frame;
import io.ominicat.core.FrameEnvelope;
import io.ominicat.core.Processor;
import io.ominicat.core.spi.PluginRegistry;
import io.ominicat.core.spi.ProcessorFactory;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class ActivityApiTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void ordinaryAndExplicitlyDisabledConnectionsKeepTheirWireShapeAndGenerateNoActivity() throws Exception {
        try (var server = server(null).start(); var client = TestHttpClient.create()) {
            for (String query : List.of("", "?observe=0")) {
                var inbox = new Inbox(); var socket = connect(client, server, query, inbox);
                socket.sendText("{\"type\":\"text\",\"text\":\"normal\"}", true).get(3, TimeUnit.SECONDS);
                assertEquals("{\"type\":\"text\",\"text\":\"normal\"}", inbox.next());
                socket.abort();
            }
            var snapshot = JSON.readTree(get(client, server, "/api/activity?after=0").body());
            assertEquals(0, snapshot.path("latestSeq").asLong());
            assertEquals(0, snapshot.path("events").size());
            assertFalse(snapshot.path("gap").asBoolean());
        }
    }

    @Test void optedInConnectionExposesActualStageEventsWithoutChangingEnvelopeOutput() throws Exception {
        try (var server = server(null).start(); var client = TestHttpClient.create()) {
            var inbox = new Inbox(); var socket = connect(client, server, "?observe=1", inbox);
            var codec = new FrameCodec();
            var frame = new FrameEnvelope(new Frame.Text("synthetic activity"), "event", "caller-session", "turn", "correlation", "", 0, 0);
            socket.sendText(codec.encode(frame), true).get(3, TimeUnit.SECONDS);
            var output = assertInstanceOf(FrameEnvelope.class, codec.decode(inbox.next()));
            assertEquals(frame.frame(), output.frame());
            assertEquals(frame.correlationId(), output.correlationId());
            JsonNode snapshot = awaitPhase(client, server, "transport_output_written");
            var events = snapshot.path("events");
            assertTrue(has(events, "ingress_accepted"));
            assertEquals(2, StreamSupport.stream(events.spliterator(), false)
                    .filter(event -> event.path("phase").asText().equals("enter")).count());
            assertTrue(has(events, "sync_return"));
            for (JsonNode event : events) {
                assertEquals("caller-session", event.path("clientSessionId").asText());
                assertNotEquals("caller-session", event.path("sessionId").asText());
                assertEquals("correlation", event.path("correlationId").asText());
                assertTrue(event.path("runId").asText().startsWith(event.path("sessionId").asText() + ":correlation:"));
                if (event.path("phase").asText().equals("output")) assertEquals("offered", event.path("outcome").asText());
                if (event.path("phase").asText().equals("transport_output_written")) assertEquals("netty_write", event.path("outcome").asText());
            }
            var response = get(client, server, "/api/activity?after=0");
            assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
            assertFalse(response.headers().firstValue("Access-Control-Allow-Origin").isPresent());
            assertFalse(response.body().contains("private-provider-configuration"));
            socket.sendText("{\"type\":\"end\"}", true).get(3, TimeUnit.SECONDS);
            inbox.closed.get(3, TimeUnit.SECONDS);
            var settled = awaitPhase(client, server, "session_settled");
            assertTrue(StreamSupport.stream(settled.path("events").spliterator(), false)
                    .anyMatch(event -> event.path("phase").asText().equals("session_settled") && event.path("outcome").asText().equals("drained")));
        }
    }

    @Test void clientDisconnectIsASettledConnectionRatherThanASuccessfulTaskCommit() throws Exception {
        try (var server = server(null).start(); var client = TestHttpClient.create()) {
            var inbox = new Inbox(); var socket = connect(client, server, "?observe=1", inbox);
            socket.sendText("{\"type\":\"text\",\"text\":\"raw\"}", true).get(3, TimeUnit.SECONDS);
            inbox.next(); socket.abort();
            var snapshot = awaitPhase(client, server, "session_settled");
            assertTrue(StreamSupport.stream(snapshot.path("events").spliterator(), false)
                    .anyMatch(event -> event.path("phase").asText().equals("session_settled")
                            && event.path("outcome").asText().equals("closed_or_cancelled")));
            assertFalse(snapshot.toString().contains("task_complete"));
        }
    }

    @Test void disconnectWithARealCleanupFailureRemainsFailedRatherThanAPureCancellation() throws Exception {
        try (var server = server(null, true).start(); var client = TestHttpClient.create()) {
            var inbox = new Inbox(); var socket = connect(client, server, "?observe=1", inbox);
            socket.sendText("{\"type\":\"text\",\"text\":\"raw\"}", true).get(3, TimeUnit.SECONDS);
            inbox.next(); socket.abort();
            var snapshot = awaitPhase(client, server, "session_settled");
            assertTrue(StreamSupport.stream(snapshot.path("events").spliterator(), false)
                    .anyMatch(event -> event.path("phase").asText().equals("session_settled")
                            && event.path("outcome").asText().equals("failed")));
            assertFalse(snapshot.toString().contains("private-cleanup-failure"));
        }
    }

    @Test void activityEndpointUsesExistingBearerOriginAndReadOnlyRequestBoundaries() throws Exception {
        try (var server = server("PATH").start(); var client = TestHttpClient.create()) {
            assertEquals(200, get(client, server, "/health").statusCode());
            assertEquals(401, get(client, server, "/api/activity").statusCode());
            String token = System.getenv("PATH");
            var foreign = client.send(request(server, "/api/activity").header("Authorization", "Bearer " + token)
                    .header("Origin", "https://unrelated.invalid").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, foreign.statusCode());
            var allowed = client.send(request(server, "/api/activity?after=0").header("Authorization", "Bearer " + token)
                    .header("Origin", "http://127.0.0.1:" + server.port()).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, allowed.statusCode());
            assertFalse(allowed.body().contains(token));
            assertEquals(405, client.send(request(server, "/api/activity").POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }

    @Test void malformedCursorAndAmbiguousOptInFailExplicitly() throws Exception {
        try (var server = server(null).start(); var client = TestHttpClient.create()) {
            for (String query : List.of("after=-1", "after=private-invalid-cursor", "after=9223372036854775808", "after=0&after=1")) {
                var response = get(client, server, "/api/activity?" + query);
                assertEquals(400, response.statusCode());
                assertEquals("Invalid activity cursor", response.body());
            }
            for (String query : List.of("?observe=yes", "?observe=1&observe=0")) {
                var failure = assertThrows(CompletionException.class, () -> connect(client, server, query, new Inbox()));
                assertEquals(400, assertInstanceOf(WebSocketHandshakeException.class, failure.getCause()).getResponse().statusCode());
            }
        }
    }

    private static OminiCatServer server(String tokenEnv) {
        return server(tokenEnv, false);
    }
    private static OminiCatServer server(String tokenEnv, boolean cleanupFails) {
        var plugins = new PluginRegistry();
        var stages = List.of(new RuntimeConfig.Stage("activity-first", Map.of("secret", "private-provider-configuration")),
                new RuntimeConfig.Stage("activity-last", Map.of()));
        for (var stage : stages) plugins.register(new ProcessorFactory() {
            @Override public String type() { return stage.type(); }
            @Override public Processor create(Map<String, String> options) {
                return new Processor() {
                    private boolean processed;
                    @Override public void process(Frame frame, io.ominicat.core.FrameSink next) throws Exception { processed = true; next.accept(frame); }
                    @Override public void close() {
                        if (processed && cleanupFails) throw new IllegalStateException("private-cleanup-failure");
                    }
                };
            }
        });
        return new OminiCatServer(new RuntimeConfig("127.0.0.1", 0, 8, 8, 4096, 60, tokenEnv, stages), plugins);
    }
    private static HttpRequest.Builder request(OminiCatServer server, String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path)).timeout(Duration.ofSeconds(3));
    }
    private static HttpResponse<String> get(TestHttpClient client, OminiCatServer server, String path) throws Exception {
        return client.send(request(server, path).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private static WebSocket connect(TestHttpClient client, OminiCatServer server, String query, Inbox inbox) {
        return client.newWebSocketBuilder().buildAsync(URI.create("ws://127.0.0.1:" + server.port() + "/ws" + query), inbox).join();
    }
    private static JsonNode awaitPhase(TestHttpClient client, OminiCatServer server, String phase) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        JsonNode response;
        do {
            response = JSON.readTree(get(client, server, "/api/activity?after=0").body());
            if (has(response.path("events"), phase)) return response;
            Thread.sleep(5);
        } while (System.nanoTime() < deadline);
        fail("Expected activity phase was not observed: " + phase);
        return response;
    }
    private static boolean has(JsonNode events, String phase) {
        return StreamSupport.stream(events.spliterator(), false).anyMatch(event -> event.path("phase").asText().equals(phase));
    }
    private static final class Inbox implements WebSocket.Listener {
        final ArrayBlockingQueue<String> messages = new ArrayBlockingQueue<>(16);
        final CompletableFuture<Integer> closed = new CompletableFuture<>();
        private final StringBuilder pending = new StringBuilder();
        @Override public void onOpen(WebSocket socket) { socket.request(1); }
        @Override public java.util.concurrent.CompletionStage<?> onText(WebSocket socket, CharSequence text, boolean last) {
            pending.append(text);
            if (last) { messages.add(pending.toString()); pending.setLength(0); }
            socket.request(1); return null;
        }
        @Override public java.util.concurrent.CompletionStage<?> onClose(WebSocket socket, int status, String reason) { closed.complete(status); return null; }
        @Override public void onError(WebSocket socket, Throwable failure) { closed.completeExceptionally(failure); }
        String next() throws InterruptedException { String value = messages.poll(3, TimeUnit.SECONDS); assertNotNull(value); return value; }
    }
}
