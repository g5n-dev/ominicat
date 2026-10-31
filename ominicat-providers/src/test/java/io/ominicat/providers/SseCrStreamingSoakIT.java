package io.ominicat.providers;

import static org.junit.jupiter.api.Assertions.*;

import io.ominicat.core.Frame;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Explicit opt-in streaming liveness probe; excluded from Surefire's ordinary test naming. */
@Timeout(120)
class SseCrStreamingSoakIT extends ProviderHttpTestSupport {
    @Test void sixThousandCrEventsAreConsumedIncrementallyAndDoneDoesNotNeedEof() throws Exception {
        final int count = 6000;
        final long interval = TimeUnit.MILLISECONDS.toNanos(10);
        CountDownLatch[] observed = new CountDownLatch[count];
        for (int i = 0; i < count; i++) observed[i] = new CountDownLatch(1);
        CountDownLatch clientCompleted = new CountDownLatch(1), producerCompleted = new CountDownLatch(1);
        AtomicInteger delivered = new AtomicInteger(), observedBeforeNextWrite = new AtomicInteger(), turnEnds = new AtomicInteger();
        AtomicBoolean bodyClosed = new AtomicBoolean();
        AtomicLong producerStart = new AtomicLong(), firstOutput = new AtomicLong(), lastOutput = new AtomicLong(), maximumGap = new AtomicLong();
        route("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                long start = System.nanoTime(); producerStart.set(start);
                for (int i = 0; i < count; i++) {
                    pace(start + i * interval);
                    output.write(event(chunk(i)).replace("\n", "\r").getBytes(StandardCharsets.UTF_8));
                    output.flush();
                    try {
                        assertTrue(observed[i].await(2, TimeUnit.SECONDS), "event " + i + " was not consumed before the next write");
                    } catch (InterruptedException error) { throw new java.io.IOException(error); }
                    observedBeforeNextWrite.incrementAndGet();
                }
                pace(start + count * interval);
                output.write("data: [DONE]\r\r".getBytes(StandardCharsets.UTF_8));
                output.flush();
                try { assertTrue(clientCompleted.await(5, TimeUnit.SECONDS), "DONE required producer EOF"); }
                catch (InterruptedException error) { throw new java.io.IOException(error); }
            } finally { bodyClosed.set(true); producerCompleted.countDown(); }
        });
        var chat = new ChatFactory().create(options("timeoutSeconds", "120", "maxResponseBytes", "4194304"));
        long began = System.nanoTime();
        try {
            chat.process(new Frame.Text("synthetic streaming probe"), frame -> {
                if (frame instanceof Frame.Text text) {
                    int index = delivered.getAndIncrement();
                    assertTrue(index < count, "extra text output");
                    assertEquals(chunk(index), text.text(), "Unicode content/order at event " + index);
                    long now = System.nanoTime();
                    firstOutput.compareAndSet(0, now);
                    long previous = lastOutput.getAndSet(now);
                    if (previous != 0) maximumGap.accumulateAndGet(now - previous, Math::max);
                    observed[index].countDown();
                } else {
                    assertEquals(new Frame.Control(Frame.Signal.TURN_END), frame);
                    turnEnds.incrementAndGet();
                }
            });
            assertEquals(count, delivered.get());
            assertEquals(count, observedBeforeNextWrite.get());
            assertEquals(1, turnEnds.get());
            assertFalse(bodyClosed.get(), "producer must keep the response body open until client completion");
        } finally {
            clientCompleted.countDown();
            chat.close();
            assertTrue(producerCompleted.await(5, TimeUnit.SECONDS));
        }
        long elapsed = System.nanoTime() - began;
        assertTrue(elapsed >= TimeUnit.SECONDS.toNanos(59));
        String report = System.getProperty("ominicat.sse.soak.report");
        if (report != null) {
            var result = new LinkedHashMap<String, Object>();
            result.put("schema", "ominicat-sse-cr-streaming-v1");
            result.put("requestedEvents", count); result.put("deliveredEvents", delivered.get());
            result.put("producerEventsPerSecond", 100);
            result.put("elapsedSeconds", elapsed / 1e9);
            result.put("firstTextAfterProducerStartMillis", (firstOutput.get() - producerStart.get()) / 1e6);
            result.put("maximumInterTextGapMillis", maximumGap.get() / 1e6);
            result.put("eventsObservedBeforeFollowingWrite", observedBeforeNextWrite.get());
            result.put("exactUnicodeAndOrder", true); result.put("doneCompletedWithoutProducerEof", true);
            result.put("providerTimeoutSeconds", 120); result.put("rawResponseBudgetBytes", 4194304);
            Files.writeString(Path.of(report), CompatibleClient.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result) + "\n");
        }
    }

    private static String chunk(int index) { return "chunk-" + index + "-你好🎵"; }

    private static void pace(long deadline) throws java.io.IOException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) return;
        try { TimeUnit.NANOSECONDS.sleep(remaining); }
        catch (InterruptedException error) { throw new java.io.IOException(error); }
    }
}
