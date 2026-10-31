package io.ominicat.core;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Independent queue model plus schedule-adversarial conservation checks; seeds select operations, not JVM schedules. */
@Timeout(90)
class KernelModelStressTest {
    private static final long BASE_SEED = 0x5EED_0C47_2026L;
    private static final int SEEDS = Integer.getInteger("ominicat.stress.seeds", 64);
    private record Token(long id) implements Frame { }
    private enum Intake { OPEN, DRAINING, CLOSED }

    @Test void blockedWorkerMatchesIndependentQueueAndLifecycleOracleAcross65536Operations() throws Exception {
        for (int seed = 0; seed < SEEDS; seed++) {
            SplittableRandom random = new SplittableRandom(BASE_SEED + seed);
            int capacity = random.nextInt(1, 9);
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            List<Frame> delivered = new ArrayList<>();
            AtomicInteger closes = new AtomicInteger();
            Processor blocked = new Processor() {
                @Override public void process(Frame frame, FrameSink next) throws Exception {
                    if (frame.equals(new Token(-1))) {
                        entered.countDown();
                        boolean resumed = false;
                        while (!resumed) {
                            try { release.await(); resumed = true; }
                            catch (InterruptedException ignored) { }
                        }
                    }
                    next.accept(frame);
                }
                @Override public void close() { closes.incrementAndGet(); }
            };
            PipelineSession session = Pipeline.builder().add(blocked).build().openSession(new SessionOptions(capacity), delivered::add);
            assertTrue(session.trySubmit(new Token(-1)));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            ArrayList<Frame> expectedQueue = new ArrayList<>();
            Intake intake = Intake.OPEN;
            long accepted = 1, rejected = 0, cleared = 0;
            boolean invalidated = false;
            try {
                for (int operation = 0; operation < 1024; operation++) {
                    String trace = "seed=" + seed + ", operation=" + operation;
                    int choice = random.nextInt(100);
                    if (choice < 75) {
                        Frame frame = new Token(operation);
                        boolean expected = intake == Intake.OPEN && expectedQueue.size() < capacity;
                        boolean actual = choice < 60 ? session.trySubmit(frame) : session.submit(frame, Duration.ZERO);
                        assertEquals(expected, actual, trace);
                        if (expected) { expectedQueue.add(frame); accepted++; } else rejected++;
                    } else if (choice < 92) {
                        session.interrupt();
                        if (intake == Intake.OPEN) {
                            cleared += expectedQueue.size(); expectedQueue.clear(); invalidated = true;
                        }
                    } else if (choice == 97 && operation > 768) {
                        session.finish();
                        if (intake == Intake.OPEN) intake = Intake.DRAINING;
                    } else if (choice == 98 && operation > 960) {
                        session.close();
                        if (intake != Intake.CLOSED) {
                            cleared += expectedQueue.size(); expectedQueue.clear(); intake = Intake.CLOSED;
                        }
                    }
                    SessionMetrics metrics = session.metrics();
                    assertEquals(expectedQueue.size(), metrics.queueDepth(), trace);
                    assertEquals(accepted, metrics.accepted(), trace);
                    assertEquals(rejected, metrics.rejected(), trace);
                    assertEquals(cleared, metrics.cancelled(), trace);
                    assertEquals(0, metrics.processed(), trace);
                    assertEquals(0, metrics.failed(), trace);
                }
                if (intake == Intake.OPEN) { session.finish(); intake = Intake.DRAINING; }
                release.countDown();
                if (intake == Intake.CLOSED) assertThrows(ExecutionException.class, () -> session.completion().get(3, TimeUnit.SECONDS));
                else session.completion().get(3, TimeUnit.SECONDS);
                ArrayList<Frame> expectedOutput = new ArrayList<>();
                if (!invalidated && intake != Intake.CLOSED) expectedOutput.add(new Token(-1));
                if (intake != Intake.CLOSED) expectedOutput.addAll(expectedQueue);
                assertEquals(expectedOutput, delivered, "seed=" + seed);
                SessionMetrics terminal = session.metrics();
                long expectedCancelled = cleared + ((invalidated || intake == Intake.CLOSED) ? 1 : 0);
                assertEquals(expectedCancelled, terminal.cancelled(), "seed=" + seed);
                assertEquals(expectedOutput.size(), terminal.processed(), "seed=" + seed);
                assertConservation(terminal, true, "seed=" + seed);
                assertEquals(1, closes.get(), "seed=" + seed);
            } finally { release.countDown(); session.close(); }
        }
        System.out.printf("Independent queue oracle: baseSeed=%d, seeds=%d, operations=%d%n", BASE_SEED, SEEDS, SEEDS * 1024L);
    }

    @Test void concurrentSubmitInterruptFinishCloseConserveAcceptedWorkAcross65536Operations() throws Exception {
        for (int seed = 0; seed < SEEDS; seed++) {
            int currentSeed = seed;
            int capacity = 1 + seed % 8;
            AtomicLong identities = new AtomicLong();
            AtomicLong accepted = new AtomicLong();
            AtomicLong rejected = new AtomicLong();
            Set<Long> acceptedIds = ConcurrentHashMap.newKeySet();
            Set<Long> deliveredIds = ConcurrentHashMap.newKeySet();
            AtomicReference<Thread> owner = new AtomicReference<>();
            AtomicInteger closes = new AtomicInteger();
            CountDownLatch start = new CountDownLatch(1);
            Processor stage = new Processor() {
                private void checkOwner() {
                    owner.compareAndSet(null, Thread.currentThread()); assertSame(owner.get(), Thread.currentThread());
                }
                @Override public void process(Frame frame, FrameSink next) throws Exception {
                    checkOwner(); if ((((Token) frame).id() & 3) == 0) Thread.yield(); next.accept(frame);
                }
                @Override public void onInterrupt() { checkOwner(); }
                @Override public void close() { checkOwner(); closes.incrementAndGet(); }
            };
            PipelineSession session = Pipeline.builder().add(stage).build().openSession(new SessionOptions(capacity), frame -> {
                assertSame(owner.get(), Thread.currentThread());
                assertTrue(deliveredIds.add(((Token) frame).id()), "duplicate delivery seed=" + currentSeed);
            });
            try (var executor = WorkerThreads.executor("test-task-", 4, 1024)) {
                List<java.util.concurrent.Future<?>> actors = new ArrayList<>();
                for (int actor = 0; actor < 4; actor++) {
                    int actorId = actor;
                    actors.add(executor.submit(() -> {
                        SplittableRandom random = new SplittableRandom(BASE_SEED ^ ((long) currentSeed << 16) ^ actorId);
                        start.await();
                        for (int operation = 0; operation < 256; operation++) {
                            int choice = random.nextInt(100);
                            if (choice < 70) {
                                long id = identities.incrementAndGet();
                                boolean result = choice < 55 ? session.trySubmit(new Token(id)) : session.submit(new Token(id), Duration.ZERO);
                                if (result) { accepted.incrementAndGet(); acceptedIds.add(id); } else rejected.incrementAndGet();
                            } else if (choice < 85) session.interrupt();
                            else if (actorId == 3 && operation >= 224 && choice >= 97) {
                                if ((currentSeed & 1) == 0) session.close(); else session.finish();
                            } else {
                                SessionMetrics snapshot = session.metrics();
                                assertTrue(snapshot.queueDepth() >= 0 && snapshot.queueDepth() <= capacity);
                                assertConservation(snapshot, false, "seed=" + currentSeed);
                                Thread.yield();
                            }
                        }
                        return null;
                    }));
                }
                start.countDown();
                for (var actor : actors) actor.get(5, TimeUnit.SECONDS);
            } finally {
                session.finish();
            }
            try { session.completion().get(3, TimeUnit.SECONDS); }
            catch (ExecutionException failure) { assertInstanceOf(java.util.concurrent.CancellationException.class, failure.getCause()); }
            SessionMetrics terminal = session.metrics();
            assertEquals(accepted.get(), terminal.accepted(), "seed=" + seed);
            assertEquals(rejected.get(), terminal.rejected(), "seed=" + seed);
            assertEquals(0, terminal.failed(), "seed=" + seed);
            assertConservation(terminal, true, "seed=" + seed);
            assertTrue(acceptedIds.containsAll(deliveredIds), "unaccepted output seed=" + seed);
            assertTrue(deliveredIds.size() >= terminal.processed());
            assertTrue(deliveredIds.size() <= terminal.processed() + terminal.cancelled());
            assertEquals(1, closes.get(), "seed=" + seed);
        }
        System.out.printf("Concurrent lifecycle stress: baseSeed=%d, seeds=%d, actors=4, operations=%d%n", BASE_SEED, SEEDS, SEEDS * 1024L);
    }

    private static void assertConservation(SessionMetrics metrics, boolean terminal, String trace) {
        long outstanding = metrics.accepted() - metrics.processed() - metrics.failed() - metrics.cancelled() - metrics.queueDepth();
        assertTrue(outstanding >= 0 && outstanding <= (terminal ? 0 : 1), trace + ", metrics=" + metrics);
    }
}
