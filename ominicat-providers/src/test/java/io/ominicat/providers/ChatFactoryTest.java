package io.ominicat.providers;

import static org.junit.jupiter.api.Assertions.*;

import io.ominicat.core.Frame;
import io.ominicat.core.WorkerThreads;
import io.ominicat.core.Processor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class ChatFactoryTest extends ProviderHttpTestSupport {
    @Test
    void handlesChunkedUtf8AndMultilineSseWhileConstructingStatelessRequest() throws Exception {
        route("/chat/completions", exchange -> {
            var request = CompatibleClient.JSON.readTree(exchange.getRequestBody());
            assertEquals("test-model", request.path("model").asText());
            assertTrue(request.path("stream").asBoolean());
            assertEquals(77, request.path("max_completion_tokens").asInt());
            assertEquals("system rule", request.path("messages").path(0).path("content").asText());
            assertEquals("hello", request.path("messages").path(1).path("content").asText());
            assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
            String stream = ": comment\r\n\r\ndata: {\"choices\":[\r\ndata: {\"delta\":{\"content\":\"你好\"}}]}\r\n\r\n"
                    + event(" world") + "data: [DONE]\n\n";
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            byte[] encoded = stream.getBytes(StandardCharsets.UTF_8);
            try (var output = exchange.getResponseBody()) {
                for (int i = 0; i < encoded.length; i += 7) {
                    output.write(encoded, i, Math.min(7, encoded.length - i));
                    output.flush();
                }
            }
        });
        Processor processor = new ChatFactory().create(options("systemPrompt", "system rule", "maxOutputTokens", "77"));
        List<Frame> output = new ArrayList<>();
        processor.process(new Frame.Text("hello"), output::add);
        assertEquals(List.of(new Frame.Text("你好"), new Frame.Text(" world"),
                new Frame.Control(Frame.Signal.TURN_END)), output);
    }

    @Test
    void imageRequestCarriesDataUrlAndConfiguredPrompt() throws Exception {
        byte[] encoded = {1, 2, 3, 4};
        route("/chat/completions", exchange -> {
            var content = CompatibleClient.JSON.readTree(exchange.getRequestBody()).path("messages").path(1).path("content");
            assertEquals("inspect", content.path(0).path("text").asText());
            assertEquals("data:image/png;base64," + Base64.getEncoder().encodeToString(encoded),
                    content.path(1).path("image_url").path("url").asText());
            respond(exchange, 200, "text/event-stream", "data: [DONE]\n\n");
        });
        List<Frame> output = new ArrayList<>();
        new ChatFactory().create(options("imagePrompt", "inspect"))
                .process(new Frame.Image(ByteBuffer.wrap(encoded), "image/png"), output::add);
        assertEquals(List.of(new Frame.Control(Frame.Signal.TURN_END)), output);
    }

    @Test
    void partialTranscriptsAndOtherFrameTypesPassThroughWithoutRequests() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        route("/chat/completions", exchange -> {
            requests.incrementAndGet();
            var request = CompatibleClient.JSON.readTree(exchange.getRequestBody());
            assertEquals("final", request.path("messages").path(1).path("content").asText());
            respond(exchange, 200, "text/event-stream", "data: [DONE]\n\n");
        });
        Processor processor = new ChatFactory().create(options());
        List<Frame> output = new ArrayList<>();
        Frame partial = new Frame.Transcript("part", "user", 0, 1, false, .5);
        Frame audio = new Frame.Audio(ByteBuffer.wrap(new byte[2]), 16000, 1);
        processor.process(partial, output::add);
        processor.process(audio, output::add);
        assertEquals(List.of(partial, audio), output);
        assertEquals(0, requests.get());
        processor.process(new Frame.Transcript("final", "user", 0, 1, true, 1), output::add);
        assertEquals(1, requests.get());
    }

    @Test
    void remoteStatusIsSanitizedAndRetryabilityIsExplicit() {
        route("/chat/completions", exchange -> respond(exchange, 429, "text/plain", "DO_NOT_EXPOSE"));
        ProviderException error = assertThrows(ProviderException.class, () -> new ChatFactory().create(options())
                .process(new Frame.Text("hello"), ignored -> {}));
        assertEquals(429, error.status());
        assertTrue(error.retryable());
        assertFalse(error.getMessage().contains("DO_NOT_EXPOSE"));
        assertFalse(new ProviderException("denied", 401).retryable());
        assertTrue(new ProviderException("unavailable", 503).retryable());
    }

    @Test
    void providerStreamErrorsDoNotProduceSuccessfulTurnBoundary() {
        route("/chat/completions", exchange -> respond(exchange, 200, "text/event-stream",
                "data: {\"error\":{\"message\":\"DO_NOT_EXPOSE\"}}\n\n"));
        List<Frame> output = new ArrayList<>();
        ProviderException error = assertThrows(ProviderException.class, () -> new ChatFactory().create(options())
                .process(new Frame.Text("hello"), output::add));
        assertEquals(502, error.status());
        assertFalse(error.getMessage().contains("DO_NOT_EXPOSE"));
        assertTrue(output.isEmpty());
    }

    @Test
    void prematureEofFailsInsteadOfAcceptingTruncatedResponse() {
        route("/chat/completions", exchange -> respond(exchange, 200, "text/event-stream", event("partial")));
        List<Frame> output = new ArrayList<>();
        IOException error = assertThrows(IOException.class, () -> new ChatFactory().create(options())
                .process(new Frame.Text("hello"), output::add));
        assertTrue(error.getMessage().contains("DONE"));
        assertEquals(List.of(new Frame.Text("partial")), output);
    }

    @Test
    void invalidJsonAndOversizedLinesFailWithoutCompletingTurn() throws Exception {
        AtomicInteger request = new AtomicInteger();
        route("/chat/completions", exchange -> respond(exchange, 200, "text/event-stream",
                request.getAndIncrement() == 0 ? "data: {broken}\n\n" : "data: " + "x".repeat(65_537) + "\n\n"));
        Processor processor = new ChatFactory().create(options());
        assertThrows(IOException.class, () -> processor.process(new Frame.Text("hello"), ignored -> {}));
        IOException error = assertThrows(IOException.class, () -> processor.process(new Frame.Text("hello"), ignored -> {}));
        assertTrue(error.getMessage().contains("line too large"));
    }

    @Test
    void responseAndInputBudgetsRejectExcessData() {
        route("/chat/completions", exchange -> respond(exchange, 200, "text/event-stream", ":" + "x".repeat(300) + "\n\n"));
        Processor processor = new ChatFactory().create(options("maxResponseBytes", "1024", "maxInputChars", "3"));
        assertThrows(IllegalArgumentException.class, () -> processor.process(new Frame.Text("long"), ignored -> {}));
        assertThrows(IllegalArgumentException.class, () -> processor.process(
                new Frame.Transcript("long", "user", 0, 1, true, 1), ignored -> {}));
        assertThrows(IllegalArgumentException.class, () -> processor.process(
                new Frame.Image(ByteBuffer.wrap(new byte[1025]), "image/png"), ignored -> {}));
        assertThrows(IOException.class, () -> processor.process(new Frame.Text("fit"), ignored -> {}));
    }

    @Test
    void stalledBodyIsClosedByDeadline() throws Exception {
        CountDownLatch headers = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        route("/chat/completions", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(": pending\n".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            headers.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        Processor processor = new ChatFactory().create(options("timeoutSeconds", "1"));
        CompletableFuture<Throwable> result = processOnWorkerThread(processor);
        try {
            assertTrue(headers.await(2, TimeUnit.SECONDS));
            assertInstanceOf(IOException.class, result.get(3, TimeUnit.SECONDS));
        } finally { release.countDown(); }
    }

    @Test
    void interruptUnblocksStreamingRead() throws Exception {
        CountDownLatch headers = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        route("/chat/completions", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(": pending\n".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            headers.countDown();
            try { release.await(30, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        Processor processor = new ChatFactory().create(options("timeoutSeconds", "30"));
        CompletableFuture<Throwable> result = new CompletableFuture<>();
        var interruptedAtFailure = new java.util.concurrent.atomic.AtomicBoolean();
        Thread worker = WorkerThreads.start("ominicat-provider-test-", () -> {
            try { processor.process(new Frame.Text("hello"), ignored -> {}); result.complete(null); }
            catch (Throwable error) { interruptedAtFailure.set(Thread.currentThread().isInterrupted()); result.complete(error); }
        });
        try {
            assertTrue(headers.await(2, TimeUnit.SECONDS));
            awaitResponseRead(worker);
            worker.interrupt();
            assertInstanceOf(java.io.InterruptedIOException.class, result.get(2, TimeUnit.SECONDS));
            assertTrue(interruptedAtFailure.get(), "the provider must preserve cancellation's interrupt status");
        } finally { release.countDown(); worker.interrupt(); worker.join(3000); }
    }

    private static CompletableFuture<Throwable> processOnWorkerThread(Processor processor) {
        CompletableFuture<Throwable> result = new CompletableFuture<>();
        WorkerThreads.start("ominicat-provider-test-", () -> {
            try { processor.process(new Frame.Text("hello"), ignored -> {}); result.complete(null); }
            catch (Throwable error) { result.complete(error); }
        });
        return result;
    }
}
