package io.ominicat.server;

import io.ominicat.core.WorkerThreads;
import io.ominicat.core.Frame;
import io.ominicat.core.FrameEnvelope;
import io.ominicat.core.Processor;
import io.ominicat.core.AsyncProcessor;
import io.ominicat.core.AsyncEmitter;
import io.ominicat.core.spi.PluginRegistry;
import io.ominicat.core.spi.ProcessorFactory;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class OminiCatServerTest {
    private static final HttpResponse.BodyHandler<String> STRING = HttpResponse.BodyHandlers.ofString();

    @Test void servesHealthConsoleAndPrometheusMetrics() throws Exception {
        try (var server = server(config(), () -> (frame, next) -> next.accept(frame)).start(); var http = TestHttpClient.create()) {
            var health = http.send(HttpRequest.newBuilder(http(server, "/health")).GET().build(), STRING);
            assertEquals(200, health.statusCode());
            assertEquals("{\"status\":\"up\"}", health.body());
            var demo = http.send(HttpRequest.newBuilder(http(server, "/")).GET().build(), STRING);
            assertEquals(200, demo.statusCode());
            assertTrue(demo.body().contains("/console.js"));
            assertTrue(demo.body().contains("/console.css"));
            var metrics = http.send(HttpRequest.newBuilder(http(server, "/metrics")).GET().build(), STRING);
            assertEquals(200, metrics.statusCode());
            assertTrue(metrics.body().contains("ominicat_active_sessions 0"));
            assertFalse(metrics.body().contains("session_id"));
            assertEquals(404, http.send(HttpRequest.newBuilder(http(server, "/missing")).GET().build(), STRING).statusCode());
            assertEquals(405, http.send(HttpRequest.newBuilder(http(server, "/health")).POST(HttpRequest.BodyPublishers.noBody()).build(), STRING).statusCode());
        }
    }

    @Test void realWebSocketRoundTripsTextAndFragmentedMessages() throws Exception {
        try (var server = server(config(), () -> (frame, next) -> next.accept(frame)).start(); var http = TestHttpClient.create()) {
            var listener = new Inbox();
            var socket = connect(http, server, listener);
            socket.sendText("{\"type\":\"text\",\"text\":\"hello 世界\"}", true).join();
            assertEquals("{\"type\":\"text\",\"text\":\"hello 世界\"}", listener.next());
            socket.sendText("{\"type\":\"text\",", false).join();
            socket.sendText("\"text\":\"fragmented\"}", true).join();
            assertEquals("{\"type\":\"text\",\"text\":\"fragmented\"}", listener.next());
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
            assertEquals(1000, listener.closed.get(5, TimeUnit.SECONDS));
        }
    }

    @Test void separateConnectionsOwnSeparateProcessorInstances() throws Exception {
        try (var server = server(config(), () -> {
            var count = new AtomicInteger();
            return (frame, next) -> next.accept(new Frame.Text(Integer.toString(count.incrementAndGet())));
        }).start(); var http = TestHttpClient.create()) {
            var first = new Inbox(); var second = new Inbox();
            var a = connect(http, server, first); var b = connect(http, server, second);
            a.sendText("{\"type\":\"text\",\"text\":\"a\"}", true).join();
            a.sendText("{\"type\":\"text\",\"text\":\"a\"}", true).join();
            b.sendText("{\"type\":\"text\",\"text\":\"b\"}", true).join();
            assertTrue(first.next().contains("\"1\"")); assertTrue(first.next().contains("\"2\""));
            assertTrue(second.next().contains("\"1\""));
            a.abort(); b.abort();
        }
    }

    @Test void envelopesPreserveMetadataAcrossConfiguredLegacyStages() throws Exception {
        try (var server = server(config(), () -> (frame, next) -> next.accept(frame)).start(); var http = TestHttpClient.create()) {
            var inbox = new Inbox(); var socket = connect(http, server, inbox);
            var codec = new FrameCodec();
            var input = new FrameEnvelope(new Frame.Text("hello"), "event-1", "session-1", "turn-1", "trace-1", "", 10, 20, 30);
            socket.sendText(codec.encode(input), true).join();
            var output = (FrameEnvelope) codec.decode(inbox.next());
            assertEquals(input.frame(), output.frame());
            assertEquals("session-1", output.sessionId()); assertEquals("turn-1", output.turnId());
            assertEquals("trace-1", output.correlationId()); assertEquals("event-1", output.causationId());
            assertNotEquals(input.id(), output.id()); assertTrue(output.emitTimeNanos() >= input.emitTimeNanos());
            socket.abort();
        }
    }

    @Test void boundedIngressRejectsOverflowWithoutDroppingAcceptedFrames() throws Exception {
        var entered = new CountDownLatch(1); var release = new Semaphore(0);
        var config = new RuntimeConfig("127.0.0.1", 0, 1, 4, 4096, 60, null, stages());
        try (var server = server(config, () -> (frame, next) -> {
            if (frame instanceof Frame.Text text && text.text().equals("slow")) { entered.countDown(); release.acquire(); }
            next.accept(frame);
        }).start(); var http = TestHttpClient.create()) {
            var inbox = new Inbox(); var socket = connect(http, server, inbox);
            socket.sendText("{\"type\":\"text\",\"text\":\"slow\"}", true).join();
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            socket.sendText("{\"type\":\"text\",\"text\":\"queued\"}", true).join();
            socket.sendText("{\"type\":\"text\",\"text\":\"overflow\"}", true).join();
            assertTrue(inbox.next().contains("overloaded"));
            socket.sendText("{\"type\":\"end\"}", true).join();
            assertTrue(inbox.next().contains("retry end"));
            release.release();
            assertTrue(inbox.next().contains("slow")); assertTrue(inbox.next().contains("queued"));
            socket.sendText("{\"type\":\"end\"}", true).join();
            assertEquals("{\"type\":\"end\"}", inbox.next());
            assertEquals(1000, inbox.closed.get(5, TimeUnit.SECONDS));
        } finally { release.release(); }
    }

    @Test void gracefulEndWaitsForDelayedAsyncOutputAndPreservesItsEnvelope() throws Exception {
        var started = new CountDownLatch(1); var release = new CountDownLatch(1); var closes = new AtomicInteger();
        try (var server = server(config(), () -> new AsyncProcessor() {
            @Override public void processAsync(Frame frame, AsyncEmitter emitter) {
                if (frame instanceof Frame.Text) {
                    Thread callback = WorkerThreads.unstarted("server-callback-fixture-", () -> {
                        try {
                            release.await();
                            if (emitter.tryEmit(new Frame.Text("delayed result"))) emitter.complete();
                            else emitter.fail(new IllegalStateException("unexpected callback rejection"));
                        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    });
                    emitter.onCancel(callback::interrupt); callback.start(); started.countDown();
                } else {
                    assertTrue(emitter.tryEmit(frame)); emitter.complete();
                }
            }
            @Override public void close() { closes.incrementAndGet(); }
        }).start(); var http = TestHttpClient.create()) {
            var inbox = new Inbox(); var socket = connect(http, server, inbox); var codec = new FrameCodec();
            var input = new FrameEnvelope(new Frame.Text("request"), "event-1", "s", "t", "trace", "", 1, 2, 3);
            socket.sendText(codec.encode(input), true).join(); assertTrue(started.await(3, TimeUnit.SECONDS));
            socket.sendText("{\"type\":\"end\"}", true).join();
            assertEquals("{\"type\":\"end\"}", inbox.next());
            assertFalse(inbox.closed.isDone(), "END is not a barrier that discards outstanding asynchronous results");
            release.countDown();
            var result = (FrameEnvelope) codec.decode(inbox.next());
            assertEquals(new Frame.Text("delayed result"), result.frame()); assertEquals("trace", result.correlationId());
            assertEquals("event-1", result.causationId());
            assertEquals(1000, inbox.closed.get(5, TimeUnit.SECONDS)); assertEquals(2, closes.get());
        } finally { release.countDown(); }
    }

    @Test void interruptFencesStaleOutputAndAllowsNextTurn() throws Exception {
        var entered = new CountDownLatch(1);
        try (var server = server(config(), () -> (frame, next) -> {
            if (((Frame.Text) frame).text().equals("slow")) {
                entered.countDown();
                try { new Semaphore(0).acquire(); } catch (InterruptedException ignored) { /* exercise emission fence */ }
            }
            next.accept(frame);
        }).start(); var http = TestHttpClient.create()) {
            var inbox = new Inbox(); var socket = connect(http, server, inbox);
            socket.sendText("{\"type\":\"text\",\"text\":\"slow\"}", true).join();
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            socket.sendText("{\"type\":\"interrupt\"}", true).join();
            assertEquals("{\"type\":\"interrupt\"}", inbox.next());
            socket.sendText("{\"type\":\"text\",\"text\":\"new turn\"}", true).join();
            assertTrue(inbox.next().contains("new turn")); socket.abort();
        }
    }

    @Test void preflightRejectsInvalidPluginOptionsWithoutRevealingValues() throws Exception {
        var config = new RuntimeConfig("127.0.0.1", 0, 8, 8, 4096, 60, null,
                List.of(new RuntimeConfig.Stage("test-echo", Map.of("apiKeyEnv", "secret-value-not-to-print"))));
        try (var server = server(config, () -> { throw new IllegalArgumentException("secret-value-not-to-print"); })) {
            var failure = assertThrows(IllegalArgumentException.class, server::start);
            assertTrue(failure.getMessage().contains("test-echo")); assertTrue(failure.getMessage().contains("apiKeyEnv"));
            assertFalse(failure.getMessage().contains("secret-value-not-to-print")); assertNull(failure.getCause());
            assertThrows(IllegalStateException.class, server::port);
        }
    }

    @Test void failedBindReleasesItsResourcesAndServerIsSingleUse() throws Exception {
        try (var first = server(config(), () -> (frame, next) -> next.accept(frame)).start()) {
            var occupied = new RuntimeConfig("127.0.0.1", first.port(), 8, 8, 4096, 60, null, stages());
            try (var second = server(occupied, () -> (frame, next) -> next.accept(frame))) {
                assertThrows(IllegalStateException.class, second::start);
                assertThrows(IllegalStateException.class, second::start);
            }
        }
    }

    @Test void invalidFrameDoesNotKillHealthySession() throws Exception {
        try (var server = server(config(), () -> (frame, next) -> next.accept(frame)).start(); var http = TestHttpClient.create()) {
            var inbox = new Inbox(); var socket = connect(http, server, inbox);
            socket.sendText("{\"type\":\"image\",\"data\":\"!\",\"mimeType\":\"image/png\"}", true).join();
            assertTrue(inbox.next().contains("invalid_frame"));
            socket.sendText("{\"type\":\"text\",\"text\":\"still here\"}", true).join();
            assertTrue(inbox.next().contains("still here")); socket.abort();
        }
    }

    @Test void gracefulEndDrainsAcceptedInputAndClosesProcessorsOnce() throws Exception {
        var closes = new AtomicInteger();
        try (var server = server(config(), () -> new Processor() {
            @Override public void process(Frame frame, io.ominicat.core.FrameSink next) throws Exception { next.accept(frame); }
            @Override public void close() { closes.incrementAndGet(); }
        }).start(); var http = TestHttpClient.create()) {
            var inbox = new Inbox(); var socket = connect(http, server, inbox);
            socket.sendText("{\"type\":\"text\",\"text\":\"before end\"}", true).join();
            socket.sendText("{\"type\":\"end\"}", true).join();
            assertTrue(inbox.next().contains("before end"));
            assertEquals("{\"type\":\"end\"}", inbox.next());
            assertEquals(1000, inbox.closed.get(5, TimeUnit.SECONDS));
            assertEquals(2, closes.get()); // one disposable startup preflight, one session instance
        }
    }

    @Test void processorFailureClosesSessionWithoutExposingExceptionText() throws Exception {
        try (var server = server(config(), () -> (frame, next) -> { throw new IllegalStateException("private-provider-detail"); }).start(); var http = TestHttpClient.create()) {
            var inbox = new Inbox(); var socket = connect(http, server, inbox);
            socket.sendText("{\"type\":\"text\",\"text\":\"hello\"}", true).join();
            assertEquals(1011, inbox.closed.get(5, TimeUnit.SECONDS));
            assertEquals("Processor failed", inbox.reason);
            var metrics = http.send(HttpRequest.newBuilder(http(server, "/metrics")).GET().build(), STRING);
            assertTrue(metrics.body().contains("ominicat_session_failures_total 1"));
        }
    }

    @Test void capacityRejectsSecondConnectionAndIsReleasedOnClose() throws Exception {
        var config = new RuntimeConfig("127.0.0.1", 0, 8, 1, 4096, 60, null, stages());
        try (var server = server(config, () -> (frame, next) -> next.accept(frame)).start(); var http = TestHttpClient.create()) {
            var first = new Inbox(); var a = connect(http, server, first);
            CompletionException failure = assertThrows(CompletionException.class, () -> connect(http, server, new Inbox()));
            assertInstanceOf(WebSocketHandshakeException.class, failure.getCause());
            assertEquals(503, ((WebSocketHandshakeException) failure.getCause()).getResponse().statusCode());
            a.sendClose(1000, "done").join(); first.closed.get(5, TimeUnit.SECONDS);
            var next = new Inbox(); var b = connect(http, server, next); b.abort();
        }
    }

    @Test void oversizedOutputIsClosedBeforeQueueingIt() throws Exception {
        var config = new RuntimeConfig("127.0.0.1", 0, 8, 4, 1024, 60, null, stages());
        try (var server = server(config, () -> (frame, next) -> next.accept(new Frame.Text("x".repeat(2000)))).start(); var http = TestHttpClient.create()) {
            var inbox = new Inbox(); var socket = connect(http, server, inbox);
            socket.sendText("{\"type\":\"text\",\"text\":\"hello\"}", true).join();
            assertEquals(1013, inbox.closed.get(5, TimeUnit.SECONDS));
            assertTrue(inbox.messages.isEmpty());
        }
    }

    @Test void oversizedInputClosesWebSocket() throws Exception {
        var config = new RuntimeConfig("127.0.0.1", 0, 8, 4, 1024, 60, null, stages());
        try (var server = server(config, () -> (frame, next) -> next.accept(frame)).start(); var http = TestHttpClient.create()) {
            var inbox = new Inbox(); var socket = connect(http, server, inbox);
            socket.sendText("{\"type\":\"text\",\"text\":\"" + "x".repeat(2000) + "\"}", true).join();
            assertEquals(1009, inbox.closed.get(5, TimeUnit.SECONDS));
        }
    }

    @Test void bearerHeaderAndBrowserSubprotocolAuthenticateWithoutUrlSecrets() throws Exception {
        var config = new RuntimeConfig("127.0.0.1", 0, 8, 4, 4096, 60, "PATH", stages());
        try (var server = server(config, () -> (frame, next) -> next.accept(frame)).start(); var http = TestHttpClient.create()) {
            assertEquals(401, http.send(HttpRequest.newBuilder(http(server, "/metrics")).GET().build(), STRING).statusCode());
            assertEquals(200, http.send(HttpRequest.newBuilder(http(server, "/health")).GET().build(), STRING).statusCode());
            String token = System.getenv("PATH");
            assertEquals(200, http.send(HttpRequest.newBuilder(http(server, "/metrics")).header("Authorization", "Bearer " + token).GET().build(), STRING).statusCode());
            assertThrows(CompletionException.class, () -> connect(http, server, new Inbox()));
            var header = http.newWebSocketBuilder().header("Authorization", "Bearer " + token).buildAsync(ws(server), new Inbox()).join();
            header.abort();
            String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var browser = http.newWebSocketBuilder().subprotocols("ominicat", "bearer." + encoded).buildAsync(ws(server), new Inbox()).join();
            assertEquals("ominicat", browser.getSubprotocol()); browser.abort();
        }
    }

    @Test void refusesCrossOriginBrowsersButAcceptsSameOrigin() throws Exception {
        try (var server = server(config(), () -> (frame, next) -> next.accept(frame)).start(); var http = TestHttpClient.create()) {
            var failure = assertThrows(CompletionException.class, () -> http.newWebSocketBuilder().header("Origin", "https://unrelated.invalid").buildAsync(ws(server), new Inbox()).join());
            assertEquals(403, ((WebSocketHandshakeException) failure.getCause()).getResponse().statusCode());
            var socket = http.newWebSocketBuilder().header("Origin", "http://127.0.0.1:" + server.port()).buildAsync(ws(server), new Inbox()).join();
            socket.abort();
        }
    }

    @Test void unknownPluginAndMissingRequiredTokenFailBeforeBinding() {
        var unknown = new RuntimeConfig("127.0.0.1", 0, 1, 1, 1024, 60, null, List.of(new RuntimeConfig.Stage("absent-type", Map.of())));
        assertThrows(IllegalArgumentException.class, () -> new OminiCatServer(unknown, new PluginRegistry()));
        var missing = new RuntimeConfig("127.0.0.1", 0, 1, 1, 1024, 60, "OMINICAT_TEST_MISSING_SECRET_92900", stages());
        assertThrows(IllegalArgumentException.class, () -> server(missing, () -> (frame, next) -> next.accept(frame)));
    }

    private static RuntimeConfig config() { return new RuntimeConfig("127.0.0.1", 0, 8, 8, 4096, 60, null, stages()); }
    private static List<RuntimeConfig.Stage> stages() { return List.of(new RuntimeConfig.Stage("test-echo", Map.of())); }
    private static OminiCatServer server(RuntimeConfig config, Supplier<Processor> factory) {
        var plugins = new PluginRegistry();
        plugins.register(new ProcessorFactory() {
            @Override public String type() { return "test-echo"; }
            @Override public Processor create(Map<String, String> options) { return factory.get(); }
        });
        return new OminiCatServer(config, plugins);
    }
    private static URI http(OminiCatServer server, String path) { return URI.create("http://127.0.0.1:" + server.port() + path); }
    private static URI ws(OminiCatServer server) { return URI.create("ws://127.0.0.1:" + server.port() + "/ws"); }
    private static WebSocket connect(TestHttpClient http, OminiCatServer server, Inbox inbox) { return http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(3)).buildAsync(ws(server), inbox).join(); }

    private static final class Inbox implements WebSocket.Listener {
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        final CompletableFuture<Integer> closed = new CompletableFuture<>();
        final StringBuilder fragments = new StringBuilder();
        volatile String reason;
        @Override public void onOpen(WebSocket socket) { socket.request(1); }
        @Override public java.util.concurrent.CompletionStage<?> onText(WebSocket socket, CharSequence text, boolean last) {
            fragments.append(text);
            if (last) { messages.add(fragments.toString()); fragments.setLength(0); }
            socket.request(1); return null;
        }
        @Override public java.util.concurrent.CompletionStage<?> onClose(WebSocket socket, int status, String message) { reason = message; closed.complete(status); return null; }
        @Override public void onError(WebSocket socket, Throwable error) { closed.completeExceptionally(error); }
        String next() throws InterruptedException { String value = messages.poll(5, TimeUnit.SECONDS); assertNotNull(value, "No server frame received within 5 seconds"); return value; }
    }
}
