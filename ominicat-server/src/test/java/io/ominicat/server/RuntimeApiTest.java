package io.ominicat.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ominicat.core.Processor;
import io.ominicat.core.spi.PluginRegistry;
import io.ominicat.core.spi.ProcessorFactory;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/** Real loopback HTTP/WebSocket checks for the console's safe, read-only runtime view. */
@Timeout(15)
class RuntimeApiTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void snapshotUsesAnExplicitAllowlistAndDoesNotCreateOrExposeConfiguredProcessors() throws Exception {
        var created = new AtomicInteger();
        try (var server = server(null, created).start(); var http = TestHttpClient.create()) {
            assertEquals(2, created.get()); // disposable preflight, one instance of each configured stage
            HttpResponse<String> response = get(http, server, "/api/runtime");
            assertEquals(200, response.statusCode());
            assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
            assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/json"));
            assertFalse(response.headers().firstValue("Access-Control-Allow-Origin").isPresent());
            JsonNode snapshot = JSON.readTree(response.body());
            assertEquals(1, snapshot.path("schemaVersion").asInt());
            assertEquals("running", snapshot.path("status").asText());
            assertFalse(snapshot.path("pipeline").path("editable").asBoolean());
            assertFalse(snapshot.path("pipeline").path("nodeTracing").asBoolean());
            assertEquals(JSON.readTree("[{\"id\":\"stage-0\",\"index\":0,\"type\":\"console-source\"},"
                    + "{\"id\":\"stage-1\",\"index\":1,\"type\":\"console-output\"}]"), snapshot.path("pipeline").path("stages"));
            assertEquals(7, snapshot.path("config").path("queueCapacity").asInt());
            assertEquals(4 * 4096, snapshot.path("config").path("maxPendingOutputBytes").asLong());
            assertFalse(snapshot.path("security").path("bearerRequired").asBoolean());
            for (String privateValue : List.of("options", "private-model-value", "private-prompt-value", "private-option-key",
                    "apiKeyEnv", "PRIVATE_CREDENTIAL_REFERENCE", "tokenEnv", "source-config.yaml")) {
                assertFalse(response.body().contains(privateValue), "Runtime response exposed a private configuration field");
            }
            assertEquals(System.getProperty("java.version"), snapshot.path("runtime").path("javaVersion").asText());
            assertTrue(snapshot.path("runtime").path("uptimeMillis").asLong() > 0);
            assertTrue(snapshot.path("runtime").path("availableProcessors").asInt() > 0);
            assertTrue(snapshot.path("memory").path("heapUsedBytes").asLong() > 0);
            assertTrue(snapshot.path("memory").path("heapCommittedBytes").asLong()
                    >= snapshot.path("memory").path("heapUsedBytes").asLong());
            assertEquals("platform", snapshot.path("threads").path("scope").asText());
            assertTrue(snapshot.path("threads").path("live").asInt() > 0);
            get(http, server, "/api/runtime");
            assertEquals(2, created.get(), "Polling must not create sessions or call plugin factories");
        }
    }

    @Test void realSocketTrafficUpdatesAggregateCountersAndResourcesDrainOnDisconnect() throws Exception {
        try (var server = server(null, new AtomicInteger()).start(); var http = TestHttpClient.create()) {
            var before = JSON.readTree(get(http, server, "/api/runtime").body()).path("metrics");
            assertEquals(0, before.path("framesInTotal").asLong());
            try (var socket = new RawWebSocketClient(server.port())) {
                assertEquals(101, socket.status);
                socket.text("{\"type\":\"text\",\"text\":\"private-frame-content\"}");
                assertTrue(socket.read().text().contains("private-frame-content"));
                var response = get(http, server, "/api/runtime");
                var metrics = JSON.readTree(response.body()).path("metrics");
                assertEquals(1, metrics.path("activeSessions").asInt());
                assertEquals(1, metrics.path("admittedSessions").asInt());
                assertEquals(1, metrics.path("pipelineWorkers").asInt());
                assertEquals(1, metrics.path("sessionsTotal").asLong());
                assertEquals(1, metrics.path("framesInTotal").asLong());
                assertEquals(1, metrics.path("framesOutTotal").asLong());
                assertTrue(metrics.path("bytesOutTotal").asLong() > 0);
                assertFalse(response.body().contains("private-frame-content"));
                assertFalse(response.body().contains("sessionId"));
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (server.resources().admittedSessions() != 0 && System.nanoTime() < deadline) Thread.sleep(5);
            var after = JSON.readTree(get(http, server, "/api/runtime").body()).path("metrics");
            assertEquals(0, after.path("activeSessions").asInt());
            assertEquals(0, after.path("admittedSessions").asInt());
            assertEquals(0, after.path("pipelineWorkers").asInt());
            assertEquals(0, after.path("pendingOutputBytes").asLong());
            assertEquals(1, after.path("sessionsTotal").asLong());
        }
    }

    @Test void configuredBearerProtectsRuntimeWhileHealthStaysAnonymousAndOriginsAreChecked() throws Exception {
        try (var server = server("PATH", new AtomicInteger()).start(); var http = TestHttpClient.create()) {
            assertEquals(200, get(http, server, "/health").statusCode());
            var denied = get(http, server, "/api/runtime");
            assertEquals(401, denied.statusCode());
            assertEquals("Bearer", denied.headers().firstValue("WWW-Authenticate").orElseThrow());
            assertFalse(denied.body().contains("console-source"));
            var invalid = http.send(request(server, "/api/runtime").header("Authorization", "Bearer wrong-token").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(401, invalid.statusCode());
            String token = System.getenv("PATH");
            var allowed = http.send(request(server, "/api/runtime").header("Authorization", "Bearer " + token)
                    .header("Origin", "http://127.0.0.1:" + server.port()).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, allowed.statusCode());
            assertTrue(JSON.readTree(allowed.body()).path("security").path("bearerRequired").asBoolean());
            assertFalse(allowed.body().contains(token));
            var foreign = http.send(request(server, "/api/runtime").header("Authorization", "Bearer " + token)
                    .header("Origin", "https://unrelated.invalid").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, foreign.statusCode());
            assertFalse(foreign.headers().firstValue("Access-Control-Allow-Origin").isPresent());
        }
    }

    @Test void nativeHostGuardProtectsRuntimeAndOriginChecksDoNotRequireBrowserCors() throws Exception {
        try (var server = server(null, new AtomicInteger()).start()) {
            assertEquals(403, nativeStatus(server.port(), "attacker.invalid", "http://attacker.invalid"));
            assertEquals(403, nativeStatus(server.port(), "localhost", "https://unrelated.invalid"));
            assertEquals(200, nativeStatus(server.port(), "localhost", "http://localhost"));
        }
    }

    @Test void metadataCannotMutateConfigurationAndAssetsUseOnlyFixedRoutes() throws Exception {
        try (var server = server(null, new AtomicInteger()).start(); var http = TestHttpClient.create()) {
            var before = JSON.readTree(get(http, server, "/api/runtime").body()).path("pipeline");
            var mutation = http.send(request(server, "/api/runtime").POST(HttpRequest.BodyPublishers.ofString(
                    "{\"stages\":[{\"type\":\"other\"}]}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(405, mutation.statusCode());
            assertEquals(before, JSON.readTree(get(http, server, "/api/runtime").body()).path("pipeline"));
            for (var asset : Map.of("/console.css", "text/css", "/console.js", "application/javascript").entrySet()) {
                var response = get(http, server, asset.getKey());
                assertEquals(200, response.statusCode());
                assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith(asset.getValue()));
                assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElseThrow());
            }
            for (String path : List.of("/web/index.html", "/console.js/extra", "/RuntimeConfig.java")) {
                assertEquals(404, get(http, server, path).statusCode());
            }
        }
    }

    private static OminiCatServer server(String tokenEnv, AtomicInteger created) {
        var stages = List.of(new RuntimeConfig.Stage("console-source", Map.of("model", "private-model-value",
                "systemPrompt", "private-prompt-value", "apiKeyEnv", "PRIVATE_CREDENTIAL_REFERENCE")),
                new RuntimeConfig.Stage("console-output", Map.of("private-option-key", "source-config.yaml")));
        var plugins = new PluginRegistry();
        for (var stage : stages) {
            plugins.register(new ProcessorFactory() {
                @Override public String type() { return stage.type(); }
                @Override public Processor create(Map<String, String> options) {
                    created.incrementAndGet();
                    return (frame, next) -> next.accept(frame);
                }
            });
        }
        return new OminiCatServer(new RuntimeConfig("127.0.0.1", 0, 7, 8, 4096, 60, tokenEnv, stages), plugins);
    }

    private static HttpRequest.Builder request(OminiCatServer server, String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path)).timeout(Duration.ofSeconds(3));
    }

    private static HttpResponse<String> get(TestHttpClient client, OminiCatServer server, String path) throws Exception {
        return client.send(request(server, path).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static int nativeStatus(int port, String host, String origin) throws Exception {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 2000);
            socket.setSoTimeout(2000);
            String request = "GET /api/runtime HTTP/1.1\r\nHost: " + host + "\r\nOrigin: " + origin + "\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            StringBuilder line = new StringBuilder();
            while (!line.toString().endsWith("\r\n") && line.length() < 4096) {
                int value = socket.getInputStream().read();
                if (value < 0) break;
                line.append((char) value);
            }
            assertTrue(line.toString().startsWith("HTTP/1.1 "));
            return Integer.parseInt(line.toString().split(" ")[1]);
        }
    }
}
