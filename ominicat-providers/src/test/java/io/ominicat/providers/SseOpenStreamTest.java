package io.ominicat.providers;

import static org.junit.jupiter.api.Assertions.*;

import io.ominicat.core.Frame;
import io.ominicat.core.WorkerThreads;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class SseOpenStreamTest extends ProviderHttpTestSupport {
    private record Fixture(String ending, String bom, CountDownLatch flushed, CountDownLatch release) {}

    @Test void completeTextEventIsDeliveredBeforeFollowingEventOrEof() throws Exception {
        var active = new AtomicReference<Fixture>();
        route("/chat/completions", exchange -> {
            Fixture fixture = active.get();
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                output.write((fixture.bom() + event("你好🎵").replace("\n", fixture.ending())).getBytes(StandardCharsets.UTF_8));
                output.flush();
                fixture.flushed().countDown();
                try { fixture.release().await(5, TimeUnit.SECONDS); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                output.write(("data: [DONE]" + fixture.ending().repeat(2)).getBytes(StandardCharsets.UTF_8));
            }
        });
        for (String ending : List.of("\n", "\r\n", "\r")) {
            for (String bom : List.of("", "\ufeff")) {
                Fixture fixture = new Fixture(ending, bom, new CountDownLatch(1), new CountDownLatch(1));
                active.set(fixture);
                CountDownLatch delivered = new CountDownLatch(1);
                var output = new java.util.concurrent.CopyOnWriteArrayList<Frame>();
                var chat = new ChatFactory().create(options("timeoutSeconds", "5"));
                var completed = new CompletableFuture<Void>();
                WorkerThreads.start("ominicat-provider-test-", () -> {
                    try {
                        chat.process(new Frame.Text("input"), frame -> {
                            output.add(frame);
                            if (frame instanceof Frame.Text) delivered.countDown();
                        });
                        completed.complete(null);
                    } catch (Throwable error) { completed.completeExceptionally(error); }
                });
                try {
                    assertTrue(fixture.flushed().await(2, TimeUnit.SECONDS));
                    assertTrue(delivered.await(1, TimeUnit.SECONDS), "complete event blocked before release; ending=" + label(ending) + ", BOM=" + !bom.isEmpty());
                    assertFalse(completed.isDone(), "DONE has not been sent yet");
                } finally {
                    fixture.release().countDown();
                    completed.get(3, TimeUnit.SECONDS);
                    chat.close();
                }
                assertEquals(List.of(new Frame.Text("你好🎵"), new Frame.Control(Frame.Signal.TURN_END)), output);
            }
        }
    }

    @Test void completeDoneEventTerminatesWhileResponseBodyRemainsOpen() throws Exception {
        var active = new AtomicReference<Fixture>();
        route("/chat/completions", exchange -> {
            Fixture fixture = active.get();
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                output.write((fixture.bom() + "data: [DONE]" + fixture.ending().repeat(2)).getBytes(StandardCharsets.UTF_8));
                output.flush();
                fixture.flushed().countDown();
                try { fixture.release().await(5, TimeUnit.SECONDS); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            }
        });
        for (String ending : List.of("\n", "\r\n", "\r")) {
            for (String bom : List.of("", "\ufeff")) {
                Fixture fixture = new Fixture(ending, bom, new CountDownLatch(1), new CountDownLatch(1));
                active.set(fixture);
                var output = new java.util.concurrent.CopyOnWriteArrayList<Frame>();
                var chat = new ChatFactory().create(options("timeoutSeconds", "5"));
                var completed = new CompletableFuture<Void>();
                WorkerThreads.start("ominicat-provider-test-", () -> {
                    try { chat.process(new Frame.Text("input"), output::add); completed.complete(null); }
                    catch (Throwable error) { completed.completeExceptionally(error); }
                });
                try {
                    assertTrue(fixture.flushed().await(2, TimeUnit.SECONDS));
                    assertDoesNotThrow(() -> completed.get(1, TimeUnit.SECONDS), "DONE blocked before EOF; ending=" + label(ending) + ", BOM=" + !bom.isEmpty());
                    assertEquals(1, fixture.release().getCount(), "server body must still be held open");
                } finally {
                    fixture.release().countDown();
                    completed.get(3, TimeUnit.SECONDS);
                    chat.close();
                }
                assertEquals(List.of(new Frame.Control(Frame.Signal.TURN_END)), output);
            }
        }
    }

    private static String label(String ending) { return ending.replace("\r", "CR").replace("\n", "LF"); }
}
