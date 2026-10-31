package io.ominicat.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(90)
class AsyncRaceStressTest {
    private static final long BASE_SEED = 0xA51C_2026L;
    private static final int SEEDS = Integer.getInteger("ominicat.stress.seeds", 64);
    private record Result(long id) implements Frame { }
    private static final class ResourceProbe {
        final AtomicInteger cancelCalls = new AtomicInteger();
        final AtomicInteger releases = new AtomicInteger();
        final AtomicBoolean released = new AtomicBoolean();
        void release() { if (released.compareAndSet(false, true)) releases.incrementAndGet(); }
    }

    @Test void callbackEmissionCompleteFailAndLifecycleRacePreserveBoundsAndResourceLifetime() throws Exception {
        for (int run = 0; run < SEEDS * 2; run++) {
            int seed = run % SEEDS;
            boolean envelopes = run >= SEEDS;
            int currentSeed = seed;
            int capacity = 1 + seed % 8;
            ResourceProbe resource = new ResourceProbe();
            ArrayBlockingQueue<AsyncEmitter> started = new ArrayBlockingQueue<>(1);
            AtomicReference<Thread> owner = new AtomicReference<>();
            AtomicInteger stageCloses = new AtomicInteger();
            AtomicLong accepted = new AtomicLong(1);
            AtomicLong rejected = new AtomicLong();
            Set<Long> acceptedIds = ConcurrentHashMap.newKeySet();
            Set<Long> deliveredIds = ConcurrentHashMap.newKeySet();
            AsyncProcessor provider = new AsyncProcessor() {
                @Override public void processAsync(Frame frame, AsyncEmitter emitter) {
                    owner.set(Thread.currentThread());
                    emitter.onCancel(() -> {
                        assertSame(owner.get(), Thread.currentThread());
                        assertEquals(1, resource.cancelCalls.incrementAndGet());
                        resource.release();
                    });
                    started.add(emitter);
                }
                @Override public void onInterrupt() { assertSame(owner.get(), Thread.currentThread()); }
                @Override public void close() { assertSame(owner.get(), Thread.currentThread()); stageCloses.incrementAndGet(); }
            };
            AtomicLong ids = new AtomicLong();
            Processor configured = envelopes ? EnvelopeProcessor.wrap(provider, () -> "derived-" + ids.incrementAndGet(), System::nanoTime) : provider;
            PipelineSession session = Pipeline.builder().add(configured).add((frame, next) -> {
                assertSame(owner.get(), Thread.currentThread()); next.accept(frame);
            }).build().openSession(new SessionOptions(capacity), frame -> {
                Frame payload = frame instanceof FrameEnvelope envelope ? envelope.frame() : frame;
                assertTrue(deliveredIds.add(((Result) payload).id()), "duplicate callback result seed=" + currentSeed);
            });
            Frame initial = new Frame.Text("start");
            assertTrue(session.trySubmit(envelopes ? new FrameEnvelope(initial, "id", "s", "t", "c", "", 0, 1) : initial));
            AsyncEmitter emitter = started.poll(2, TimeUnit.SECONDS);
            assertNotNull(emitter);
            CountDownLatch firstEmissions = new CountDownLatch(1);
            try (var executor = WorkerThreads.executor("test-task-", 4, 1024)) {
                List<java.util.concurrent.Future<?>> actors = new ArrayList<>();
                actors.add(executor.submit(() -> {
                    for (int operation = 0; operation < 256; operation++) {
                        boolean result = emitter.tryEmit(new Result(operation));
                        if (result) { accepted.incrementAndGet(); acceptedIds.add((long) operation); } else rejected.incrementAndGet();
                        if (operation == 15) firstEmissions.countDown();
                        if ((operation & 3) == 0) Thread.yield();
                    }
                    firstEmissions.countDown();
                    return null;
                }));
                actors.add(executor.submit(() -> {
                    firstEmissions.await();
                    SplittableRandom random = new SplittableRandom(BASE_SEED + currentSeed);
                    assertThrows(IllegalStateException.class, () -> emitter.onCancel(() -> fail("late registration")));
                    for (int operation = 0; operation < 128; operation++) {
                        if (operation < 96 && random.nextBoolean()) {
                            session.interrupt();
                            assertFalse(emitter.tryEmit(new Result(1000 + operation)));
                            rejected.incrementAndGet();
                        } else if (operation > 119) {
                            if ((currentSeed & 1) == 0) session.close(); else session.finish();
                        } else {
                            SessionMetrics metrics = session.metrics();
                            assertTrue(metrics.queueDepth() >= 0 && metrics.queueDepth() <= capacity);
                            long unaccounted = metrics.accepted() - metrics.processed() - metrics.failed() - metrics.cancelled() - metrics.queueDepth();
                            assertTrue(unaccounted >= 0 && unaccounted <= 1, "seed=" + currentSeed + ", " + metrics);
                            Thread.yield();
                        }
                    }
                    return null;
                }));
                actors.add(executor.submit(() -> {
                    firstEmissions.await();
                    SplittableRandom random = new SplittableRandom(BASE_SEED ^ currentSeed);
                    for (int operation = 0; operation < 128; operation++) {
                        if (operation == 31) {
                            resource.release();
                            if ((currentSeed & 7) == 0) emitter.fail(new IOException("expected synthetic provider failure"));
                            else emitter.complete();
                        } else if (random.nextBoolean()) Thread.yield();
                        else emitter.isCancelled();
                    }
                    resource.release(); emitter.complete();
                    return null;
                }));
                for (var actor : actors) actor.get(5, TimeUnit.SECONDS);
            } finally { resource.release(); emitter.complete(); session.finish(); }
            try { session.completion().get(3, TimeUnit.SECONDS); }
            catch (ExecutionException failure) {
                assertTrue(failure.getCause() instanceof IOException || failure.getCause() instanceof java.util.concurrent.CancellationException,
                        "unexpected seed=" + seed + ": " + failure.getCause());
            }
            SessionMetrics metrics = session.metrics();
            assertEquals(accepted.get(), metrics.accepted(), "seed=" + seed);
            if (!envelopes) assertEquals(rejected.get(), metrics.rejected(), "seed=" + seed);
            else assertTrue(metrics.rejected() <= rejected.get(), "kernel rejection count exceeds callback attempts seed=" + seed);
            assertEquals(metrics.accepted(), metrics.processed() + metrics.failed() + metrics.cancelled(), "seed=" + seed);
            assertEquals(0, metrics.queueDepth());
            assertTrue(acceptedIds.containsAll(deliveredIds));
            assertEquals(1, resource.releases.get(), "retained resource seed=" + seed);
            assertTrue(resource.cancelCalls.get() <= 1);
            assertEquals(1, stageCloses.get());
            assertFalse(emitter.tryEmit(new Result(9999)));
        }
        System.out.printf("Async callback/lifecycle stress: baseSeed=%d, seeds=%d, actors=3, modes=raw+envelope, operations=%d%n", BASE_SEED, SEEDS, SEEDS * 1024L);
    }
}
