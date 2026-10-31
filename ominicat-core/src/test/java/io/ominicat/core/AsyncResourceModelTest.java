package io.ominicat.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.SplittableRandom;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Retained callbacks must neither retain a live cancellation resource nor release it twice. */
@Timeout(60)
class AsyncResourceModelTest {
    @Test void terminalOrderingMatchesIndependentExactlyOnceResourceOracle() throws Exception {
        int seeds = Integer.getInteger("ominicat.stress.seeds", 64);
        SplittableRandom random = new SplittableRandom(0xC105E_2026L);
        for (int run = 0; run < seeds * 2; run++) {
            int seed = run % seeds;
            boolean envelopes = run >= seeds;
            int scenario = random.nextInt(4);
            AtomicInteger releases = new AtomicInteger();
            AtomicInteger cancellationCalls = new AtomicInteger();
            AtomicInteger stageCloses = new AtomicInteger();
            AtomicReference<Thread> owner = new AtomicReference<>();
            CountDownLatch cancellationRan = new CountDownLatch(1);
            ArrayBlockingQueue<AsyncEmitter> started = new ArrayBlockingQueue<>(1);
            AsyncProcessor stage = new AsyncProcessor() {
                @Override public void processAsync(Frame frame, AsyncEmitter emitter) {
                    owner.set(Thread.currentThread());
                    emitter.onCancel(() -> {
                        assertSame(owner.get(), Thread.currentThread());
                        assertEquals(1, cancellationCalls.incrementAndGet());
                        assertEquals(1, releases.incrementAndGet(), "resource released twice");
                        cancellationRan.countDown();
                    });
                    started.add(emitter);
                }
                @Override public void close() {
                    assertEquals(1, releases.get(), "live per-operation resource survived stage cleanup");
                    assertEquals(1, stageCloses.incrementAndGet());
                }
            };
            Processor configured = envelopes ? EnvelopeProcessor.wrap(stage, () -> "derived", System::nanoTime) : stage;
            PipelineSession session = Pipeline.builder().add(configured).build().openSession(new SessionOptions(2), frame -> fail("no output expected"));
            Frame initial = new Frame.Text("start");
            session.trySubmit(envelopes ? new FrameEnvelope(initial, "id", "s", "t", "c", "", 0, 1) : initial);
            AsyncEmitter retained = started.poll(2, TimeUnit.SECONDS);
            assertNotNull(retained);
            if (scenario == 0) {
                assertEquals(1, releases.incrementAndGet()); // normal completion is provider-owned cleanup
                retained.complete();
                session.interrupt();
                session.finish();
            } else if (scenario == 1) {
                session.interrupt();
                assertTrue(cancellationRan.await(2, TimeUnit.SECONDS));
                session.finish();
            } else if (scenario == 2) session.close();
            else retained.fail(new IOException("expected resource-model failure"));
            if (scenario < 2) session.completion().get(2, TimeUnit.SECONDS);
            else assertThrows(ExecutionException.class, () -> session.completion().get(2, TimeUnit.SECONDS));
            assertEquals(scenario == 0 ? 0 : 1, cancellationCalls.get(), "seed=" + seed);
            assertEquals(1, releases.get());
            assertEquals(1, stageCloses.get());
            assertEquals(scenario != 0, retained.isCancelled(), "terminal result changed seed=" + seed + ", envelopes=" + envelopes);
            // Deliberately retain and abuse the callback after termination.
            retained.complete(); retained.fail(new IOException("stale"));
            assertFalse(retained.tryEmit(new Frame.Text("stale")));
            assertThrows(IllegalStateException.class, () -> retained.onCancel(() -> releases.incrementAndGet()));
            assertEquals(1, releases.get());
            SessionMetrics metrics = session.metrics();
            assertEquals(metrics.accepted(), metrics.processed() + metrics.failed() + metrics.cancelled());
        }
        System.out.printf("Retained async resource oracle: baseSeed=%d, seeds=%d, terminalScenarios=4, modes=raw+envelope%n", 0xC105E_2026L, seeds);
    }
}
