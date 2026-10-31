package io.ominicat.providers;

import static org.junit.jupiter.api.Assertions.*;

import io.ominicat.core.Frame;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.http.HttpTimeoutException;
import java.net.ServerSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
class ProviderFaultMatrixTest extends ProviderHttpTestSupport {
    @Test void statusMatrixNeverFollowsRedirectsOrDisclosesResponseBodies() {
        List<Integer> statuses = List.of(301, 302, 400, 401, 408, 413, 429, 500, 502, 503);
        AtomicInteger requests = new AtomicInteger(), redirected = new AtomicInteger();
        route("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Location", "/v1/redirect-target");
            respond(exchange, statuses.get(requests.getAndIncrement()), "text/plain", "private provider body");
        });
        route("/redirect-target", exchange -> { redirected.incrementAndGet(); respond(exchange, 200, "text/plain", "unexpected"); });
        var chat = new ChatFactory().create(options());
        for (int status : statuses) {
            ProviderException failure = assertThrows(ProviderException.class,
                    () -> chat.process(new Frame.Text("x"), ignored -> {}), "status=" + status);
            assertEquals(status, failure.status());
            assertEquals(status == 429 || status >= 500, failure.retryable());
            assertFalse(failure.getMessage().contains("private"));
        }
        assertEquals(0, redirected.get());
    }

    @Test void slowHeadersTimeoutAndFollowingRequestStillSucceeds() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        route("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (requests.getAndIncrement() == 0) {
                try { release.await(4, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            respond(exchange, 200, "text/event-stream", "data: [DONE]\n\n");
        });
        var chat = new ChatFactory().create(options("timeoutSeconds", "1"));
        try {
            assertThrows(HttpTimeoutException.class, () -> chat.process(new Frame.Text("x"), ignored -> {}));
        } finally { release.countDown(); }
        chat.process(new Frame.Text("healthy request"), ignored -> {});
    }

    @Test void truncatedFixedLengthBodyFailsAndFollowingRequestStillSucceeds() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        route("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (requests.getAndIncrement() == 0) {
                exchange.getResponseHeaders().set("Connection", "close");
                exchange.sendResponseHeaders(200, 1000);
                exchange.getResponseBody().write("data: {\"choices\":[".getBytes(StandardCharsets.UTF_8));
                try { exchange.getResponseBody().close(); } catch (IOException expected) { exchange.close(); }
            } else respond(exchange, 200, "text/event-stream", "data: [DONE]\n\n");
        });
        var chat = new ChatFactory().create(options());
        assertThrows(IOException.class, () -> chat.process(new Frame.Text("x"), ignored -> {}));
        chat.process(new Frame.Text("healthy request"), ignored -> {});
    }

    @Test void binaryBodyDeadlineDoesNotReturnPartialSuccessfulSpeech() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        route("/audio/speech", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(new byte[]{1, 0});
            exchange.getResponseBody().flush();
            try { release.await(4, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        var speech = new SpeechFactory().create(options("timeoutSeconds", "1"));
        List<Frame> output = new java.util.ArrayList<>();
        speech.process(new Frame.Text("hello"), output::add);
        try {
            assertThrows(IOException.class, () -> speech.process(new Frame.Control(Frame.Signal.END), output::add));
        } finally { release.countDown(); }
        assertEquals(List.of(new Frame.Text("hello")), output);
    }

    @Test void completedResponsesReleaseDeadlineTasksAndDoNotAccumulateDescriptors() throws Exception {
        route("/ok", exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 200, "application/json", "{}");
        });
        var client = new CompatibleClient(options("timeoutSeconds", "300"));
        for (int i = 0; i < 10; i++) try (var response = client.post("/ok", Map.of())) { response.bytes(); }
        long before = descriptorCount();
        for (int i = 0; i < 250; i++) try (var response = client.post("/ok", Map.of())) { response.bytes(); }
        var field = CompatibleClient.class.getDeclaredField("DEADLINES");
        field.setAccessible(true);
        var scheduler = (ScheduledThreadPoolExecutor) field.get(null);
        assertEquals(0, scheduler.getQueue().size(), "completed request deadlines must not remain queued for 300 seconds");
        long after = descriptorCount();
        if (before >= 0 && after >= 0) assertTrue(after - before <= 12, "descriptor growth=" + (after - before));
    }

    private static long descriptorCount() {
        var bean = ManagementFactory.getOperatingSystemMXBean();
        return bean instanceof com.sun.management.UnixOperatingSystemMXBean unix ? unix.getOpenFileDescriptorCount() : -1;
    }

    @Test void tcpResetAfterPartialOutputNeverEmitsSuccessfulTurnBoundary() throws Exception {
        try (var socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            CountDownLatch partialDelivered = new CountDownLatch(1);
            CompletableFuture<Void> peer = new CompletableFuture<>();
            Thread.ofVirtual().start(() -> {
                try (var connection = socket.accept()) {
                    connection.setSoTimeout(3000);
                    int matched = 0, character;
                    while (matched < 4 && (character = connection.getInputStream().read()) != -1) {
                        byte[] delimiter = {'\r','\n','\r','\n'};
                        matched = character == delimiter[matched] ? matched + 1 : character == '\r' ? 1 : 0;
                    }
                    connection.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: 10000\r\n\r\n"
                            + event("partial")).getBytes(StandardCharsets.UTF_8));
                    connection.getOutputStream().flush();
                    if (!partialDelivered.await(3, TimeUnit.SECONDS)) throw new IOException("partial output was not delivered");
                    connection.setSoLinger(true, 0);
                    peer.complete(null);
                } catch (Throwable error) { peer.completeExceptionally(error); }
            });
            var chat = new ChatFactory().create(options("baseUrl", "http://127.0.0.1:" + socket.getLocalPort() + "/v1"));
            List<Frame> output = new java.util.ArrayList<>();
            assertThrows(IOException.class, () -> chat.process(new Frame.Text("x"), frame -> {
                output.add(frame);
                if (frame instanceof Frame.Text) partialDelivered.countDown();
            }));
            peer.get(4, TimeUnit.SECONDS);
            assertEquals(List.of(new Frame.Text("partial")), output);
        }
    }

    @Test void timeoutFlagRejectsEofWhileDeadlineCloseIsStillInProgress() throws Exception {
        CountDownLatch closeStarted = new CountDownLatch(1), finishClose = new CountDownLatch(1);
        var source = new java.io.InputStream() {
            @Override public int read() throws IOException {
                try { closeStarted.await(); return -1; }
                catch (InterruptedException error) { throw new IOException(error); }
            }
            @Override public void close() {
                closeStarted.countDown();
                try { finishClose.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            }
        };
        AtomicBoolean expired = new AtomicBoolean();
        var scheduler = new ScheduledThreadPoolExecutor(1);
        var deadline = scheduler.schedule(() -> { expired.set(true); source.close(); }, 10, TimeUnit.MILLISECONDS);
        var response = new CompatibleClient.Response(source, deadline, 1024, expired);
        try {
            assertThrows(IOException.class, response::bytes);
            assertFalse(deadline.isDone(), "deadline close is deliberately still blocked");
        } finally {
            finishClose.countDown();
            response.close();
            scheduler.shutdownNow();
        }
    }
}
