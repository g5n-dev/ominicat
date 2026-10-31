package io.ominicat.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.ominicat.core.Frame;
import io.ominicat.core.Processor;
import io.ominicat.core.WorkerThreads;
import io.ominicat.core.spi.PluginRegistry;
import io.ominicat.core.spi.ProcessorFactory;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/** Cancels a genuinely blocked provider-body read, without waiting for EOF or its 30-second deadline. */
@Timeout(15)
class HttpBodyCancellationTest {
    private static final Duration WATCHDOG = Duration.ofSeconds(3);

    @Test void disconnectReleasesBlockedBodyWorkerAndAdmissionWhilePeerBodyRemainsHeldOpen() throws Exception {
        try (var peer = new HeldBodyPeer(); var server = peer.runtime().start()) {
            Thread blockedWorker;
            long cancelledAt;
            try (var client = new RawWebSocketClient(server.port())) {
                assertEquals(101, client.status);
                client.text("{\"type\":\"text\",\"text\":\"first request\"}");
                assertEquals("{\"type\":\"text\",\"text\":\"first chunk\"}", client.read().text());
                blockedWorker = peer.awaitActualBodyTake();
                peer.assertFirstBodyStillHeldOpen();
                cancelledAt = System.nanoTime();
            }
            await(() -> drained(server.resources()), WATCHDOG,
                    "Disconnect did not release blocked provider work: " + server.resources());
            await(() -> !blockedWorker.isAlive(), WATCHDOG, "Disconnected processor worker remained alive");
            assertTrue(System.nanoTime() - cancelledAt < TimeUnit.SECONDS.toNanos(6), "Cancellation waited for the 30-second provider deadline");
            peer.assertFirstBodyStillHeldOpen();
            try (var recovered = new RawWebSocketClient(server.port())) {
                assertEquals(101, recovered.status, "The sole admission slot was not reusable after disconnect");
                recovered.text("{\"type\":\"text\",\"text\":\"second request\"}");
                assertEquals("{\"type\":\"text\",\"text\":\"recovered\"}", recovered.read().text());
            }
            await(() -> drained(server.resources()), WATCHDOG, "Reconnected session did not release resources");
            peer.assertFirstBodyStillHeldOpen();
            assertEquals(2, peer.requests.get());
        }
    }

    @Test void interruptCancelsBlockedBodyAndProcessesNextGenerationOnTheSameSocketWithoutPeerEof() throws Exception {
        try (var peer = new HeldBodyPeer(); var server = peer.runtime().start(); var client = new RawWebSocketClient(server.port())) {
            assertEquals(101, client.status);
            client.text("{\"type\":\"text\",\"text\":\"old generation\"}");
            assertEquals("{\"type\":\"text\",\"text\":\"first chunk\"}", client.read().text());
            Thread blockedWorker = peer.awaitActualBodyTake();
            peer.assertFirstBodyStillHeldOpen();
            long interruptedAt = System.nanoTime();
            client.text("{\"type\":\"interrupt\"}");
            assertEquals("{\"type\":\"interrupt\"}", client.read().text());
            client.text("{\"type\":\"text\",\"text\":\"new generation\"}");
            assertEquals("{\"type\":\"text\",\"text\":\"recovered\"}", client.read().text());
            assertTrue(System.nanoTime() - interruptedAt < WATCHDOG.toNanos(), "Next generation depended on the old provider deadline");
            assertSame(blockedWorker, peer.recoveredWorker.get(), "A new worker hid the old blocked invocation");
            peer.assertFirstBodyStillHeldOpen();
            client.text("{\"type\":\"end\"}");
            boolean closedNormally = false;
            for (int index = 0; index < 4 && !closedNormally; index++) {
                var response = client.read();
                if (response.opcode() == 8) { assertEquals(1000, response.closeCode()); closedNormally = true; }
                else assertTrue(response.text().equals("{\"type\":\"turn_end\"}") || response.text().equals("{\"type\":\"end\"}"));
            }
            assertTrue(closedNormally);
            await(() -> drained(server.resources()), WATCHDOG, "Interrupted session retained admission, worker or output bytes");
            peer.assertFirstBodyStillHeldOpen();
            assertEquals(2, peer.requests.get());
        }
    }

    private static boolean drained(OminiCatServer.Resources resources) {
        return resources.connections() == 0 && resources.activeSessions() == 0 && resources.admittedSessions() == 0
                && resources.pipelineWorkers() == 0 && resources.pendingOutputBytes() == 0;
    }

    private static void await(BooleanSupplier condition, Duration timeout, String failure) throws InterruptedException {
        long until = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(5);
        assertTrue(condition.getAsBoolean(), failure);
    }

    private static final class HeldBodyPeer implements AutoCloseable {
        private final HttpServer http;
        private final WorkerThreads.TaskExecutor handlers = WorkerThreads.executor("held-provider-body-", 2);
        private final CountDownLatch releaseFirstBody = new CountDownLatch(1);
        private final CountDownLatch firstHandlerFinished = new CountDownLatch(1);
        private final AtomicInteger requests = new AtomicInteger();
        private final AtomicReference<Thread> firstWorker = new AtomicReference<>();
        private final AtomicReference<Thread> recoveredWorker = new AtomicReference<>();

        private HeldBodyPeer() throws IOException {
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            http.setExecutor(handlers);
            http.createContext("/v1/chat/completions", this::respond);
            http.start();
        }

        private void respond(HttpExchange exchange) throws IOException {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            boolean first = requests.incrementAndGet() == 1;
            try (var output = exchange.getResponseBody()) {
                output.write(event(first ? "first chunk" : "recovered"));
                output.flush();
                if (first) {
                    // No DONE, EOF, or application release until every cancellation assertion has completed.
                    try { releaseFirstBody.await(); }
                    catch (InterruptedException interruption) { Thread.currentThread().interrupt(); }
                } else output.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            } finally { if (first) firstHandlerFinished.countDown(); }
        }

        private OminiCatServer runtime() {
            var registry = new PluginRegistry();
            registry.register(new ProcessorFactory() {
                @Override public String type() { return "capture-body-reader-worker"; }
                @Override public Processor create(Map<String, String> options) {
                    return (frame, next) -> {
                        if (frame instanceof Frame.Text text) {
                            if (text.text().equals("first chunk")) firstWorker.set(Thread.currentThread());
                            if (text.text().equals("recovered")) recoveredWorker.set(Thread.currentThread());
                        }
                        next.accept(frame);
                    };
                }
            });
            var stages = List.of(new RuntimeConfig.Stage("openai-chat", Map.of("baseUrl",
                    "http://127.0.0.1:" + http.getAddress().getPort() + "/v1", "model", "local-fixture",
                    "apiKeyEnv", "", "timeoutSeconds", "30")), new RuntimeConfig.Stage("capture-body-reader-worker", Map.of()));
            return new OminiCatServer(new RuntimeConfig("127.0.0.1", 0, 4, 1, 4096, 60, null, stages), registry);
        }

        private Thread awaitActualBodyTake() throws InterruptedException {
            await(() -> bodyTake(firstWorker.get()), WATCHDOG,
                    "Processor did not reach the actual HTTP body queue.take; stack=" + stack(firstWorker.get()));
            return firstWorker.get();
        }

        private void assertFirstBodyStillHeldOpen() {
            assertEquals(1, releaseFirstBody.getCount(), "Fixture released the blocked provider body");
            assertEquals(1, firstHandlerFinished.getCount(), "Provider fixture sent EOF before cancellation completed");
        }

        private static boolean bodyTake(Thread worker) {
            if (worker == null) return false;
            StackTraceElement[] trace = worker.getStackTrace();
            boolean take = Arrays.stream(trace).anyMatch(frame -> frame.getClassName().equals("java.util.concurrent.ArrayBlockingQueue")
                    && frame.getMethodName().equals("take"));
            boolean body = Arrays.stream(trace).anyMatch(frame -> frame.getClassName().contains("HttpResponseInputStream")
                    || frame.getClassName().equals("io.ominicat.providers.CompatibleClient$PublisherInputStream"));
            return take && body;
        }

        private static String stack(Thread worker) { return worker == null ? "unobserved" : Arrays.toString(worker.getStackTrace()); }
        private static byte[] event(String text) {
            return ("data: {\"choices\":[{\"delta\":{\"content\":\"" + text + "\"}}]}\n\n").getBytes(StandardCharsets.UTF_8);
        }

        @Override public void close() {
            releaseFirstBody.countDown();
            http.stop(0);
            handlers.close();
        }
    }
}
