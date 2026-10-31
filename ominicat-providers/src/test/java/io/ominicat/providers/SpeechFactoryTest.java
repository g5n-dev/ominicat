package io.ominicat.providers;

import static org.junit.jupiter.api.Assertions.*;

import io.ominicat.core.Frame;
import io.ominicat.core.Processor;
import io.ominicat.core.WorkerThreads;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class SpeechFactoryTest extends ProviderHttpTestSupport {
    @Test
    void synthesizesBufferedTurnOnceAndPreservesTextFrames() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        byte[] pcm = {1, 0, -1, 127};
        route("/audio/speech", exchange -> {
            requests.incrementAndGet();
            var request = CompatibleClient.JSON.readTree(exchange.getRequestBody());
            assertEquals("hello world", request.path("input").asText());
            assertEquals("custom", request.path("voice").asText());
            assertEquals("pcm", request.path("response_format").asText());
            assertEquals("test-model", request.path("model").asText());
            respond(exchange, 200, "application/octet-stream", pcm);
        });
        Processor processor = new SpeechFactory().create(options("voice", "custom"));
        List<Frame> output = new ArrayList<>();
        processor.process(new Frame.Text("hello "), output::add);
        processor.process(new Frame.Text("world"), output::add);
        assertEquals(0, requests.get());
        processor.process(new Frame.Control(Frame.Signal.TURN_END), output::add);
        processor.process(new Frame.Control(Frame.Signal.END), output::add);
        assertEquals(1, requests.get());
        assertEquals(List.of(new Frame.Text("hello "), new Frame.Text("world")), output.subList(0, 2));
        Frame.Audio audio = assertInstanceOf(Frame.Audio.class, output.get(2));
        assertEquals(24000, audio.sampleRate());
        assertEquals(1, audio.channels());
        byte[] actual = new byte[audio.data().remaining()];
        audio.data().get(actual);
        assertArrayEquals(pcm, actual);
        assertEquals(new Frame.Control(Frame.Signal.TURN_END), output.get(3));
    }

    @Test
    void interruptionClearsTextAndBlankTurnsDoNotCallProvider() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        route("/audio/speech", exchange -> {
            requests.incrementAndGet();
            var request = CompatibleClient.JSON.readTree(exchange.getRequestBody());
            assertEquals("new", request.path("input").asText());
            respond(exchange, 200, "application/octet-stream", new byte[2]);
        });
        Processor processor = new SpeechFactory().create(options());
        processor.process(new Frame.Text("discard"), ignored -> {});
        processor.onInterrupt();
        processor.process(new Frame.Text("also discard"), ignored -> {});
        processor.process(new Frame.Control(Frame.Signal.INTERRUPT), ignored -> {});
        processor.process(new Frame.Text("  "), ignored -> {});
        processor.process(new Frame.Control(Frame.Signal.TURN_END), ignored -> {});
        assertEquals(0, requests.get());
        processor.process(new Frame.Text("new"), ignored -> {});
        processor.process(new Frame.Control(Frame.Signal.END), ignored -> {});
        assertEquals(1, requests.get());
    }

    @Test
    void rejectsTextBudgetAndInvalidPcmPayloads() throws Exception {
        AtomicInteger request = new AtomicInteger();
        route("/audio/speech", exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 200, "application/octet-stream", request.getAndIncrement() == 0 ? new byte[1] : new byte[0]);
        });
        Processor limited = new SpeechFactory().create(options("maxTextChars", "3"));
        limited.process(new Frame.Text("abc"), ignored -> {});
        assertThrows(IllegalArgumentException.class, () -> limited.process(new Frame.Text("d"), ignored -> {}));
        assertThrows(ProviderException.class, () -> limited.process(new Frame.Control(Frame.Signal.END), ignored -> {}));
        limited.process(new Frame.Text("a"), ignored -> {});
        assertThrows(ProviderException.class, () -> limited.process(new Frame.Control(Frame.Signal.END), ignored -> {}));
    }

    @Test
    void interruptionCancelsBlockedBinaryBodyWithoutEmittingPartialAudio() throws Exception {
        var headers = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        route("/audio/speech", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(new byte[]{1, 0});
            exchange.getResponseBody().flush();
            headers.countDown();
            try { release.await(30, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        Processor speech = new SpeechFactory().create(options("timeoutSeconds", "30"));
        speech.process(new Frame.Text("synthetic"), ignored -> {});
        var output = new java.util.concurrent.CopyOnWriteArrayList<Frame>();
        var result = new java.util.concurrent.CompletableFuture<Throwable>();
        var interruptedAtFailure = new java.util.concurrent.atomic.AtomicBoolean();
        Thread worker = WorkerThreads.start("ominicat-speech-cancel-", () -> {
            try { speech.process(new Frame.Control(Frame.Signal.TURN_END), output::add); result.complete(null); }
            catch (Throwable error) { interruptedAtFailure.set(Thread.currentThread().isInterrupted()); result.complete(error); }
        });
        try {
            assertTrue(headers.await(2, java.util.concurrent.TimeUnit.SECONDS));
            awaitResponseRead(worker);
            worker.interrupt();
            assertInstanceOf(java.io.InterruptedIOException.class, result.get(2, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(interruptedAtFailure.get());
            assertTrue(output.isEmpty(), "partial PCM must never become a successful audio frame");
        } finally { release.countDown(); worker.interrupt(); worker.join(3000); speech.close(); }
    }
}
