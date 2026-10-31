package io.ominicat.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class ParallelProcessorTest {
    @Test void joinsInDeclarationOrderRegardlessOfCompletionOrder() throws Exception {
        CountDownLatch rightFinished = new CountDownLatch(1);
        Processor left = (frame, next) -> {
            assertTrue(Thread.currentThread().isVirtual());
            rightFinished.await();
            next.accept(new Frame.Text("left-1")); next.accept(new Frame.Text("left-2"));
        };
        Processor right = (frame, next) -> {
            next.accept(new Frame.Text("right")); rightFinished.countDown();
        };
        try (ParallelProcessor parallel = parallel(left, right, Duration.ofSeconds(2), 2)) {
            List<Frame> output = new ArrayList<>();
            parallel.process(new Frame.Text("input"), output::add);
            assertEquals(List.of(new Frame.Text("left-1"), new Frame.Text("left-2"), new Frame.Text("right")), output);
        }
    }

    @Test void failsFastOnLaterBranchAndCancelsSlowSiblingWithoutPartialOutput() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        Processor slow = (frame, next) -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException failure) { cancelled.countDown(); throw failure; }
        };
        Processor failing = (frame, next) -> { entered.await(); throw new IOException("boom"); };
        try (ParallelProcessor parallel = parallel(slow, failing, Duration.ofSeconds(30), 2)) {
            List<Frame> output = new ArrayList<>();
            Exception failure = assertThrows(Exception.class, () -> parallel.process(new Frame.Text("input"), output::add));
            assertTrue(failure.getMessage().contains("right"));
            assertEquals("boom", failure.getCause().getMessage());
            await(cancelled);
            assertTrue(output.isEmpty());
        }
    }

    @Test void deadlineCancelsSlowBranch() throws Exception {
        CountDownLatch cancelled = new CountDownLatch(1);
        Processor slow = (frame, next) -> {
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException failure) { cancelled.countDown(); throw failure; }
        };
        try (ParallelProcessor parallel = parallel(slow, (frame, next) -> next.accept(frame), Duration.ofMillis(50), 2)) {
            assertThrows(TimeoutException.class, () -> parallel.process(new Frame.Text("input"), frame -> fail("partial join")));
            await(cancelled);
        }
    }

    @Test void boundsEachBranchBufferAndEmitsNothingOnOverflow() throws Exception {
        Processor overflowing = (frame, next) -> { next.accept(frame); next.accept(frame); next.accept(frame); };
        try (ParallelProcessor parallel = parallel(overflowing, (frame, next) -> next.accept(frame), Duration.ofSeconds(2), 2)) {
            List<Frame> output = new ArrayList<>();
            Exception failure = assertThrows(Exception.class, () -> parallel.process(new Frame.Text("input"), output::add));
            assertTrue(failure.getCause().getMessage().contains("max buffered frames 2"));
            assertTrue(output.isEmpty());
        }
    }

    @Test void sessionInterruptionSerializesBranchResetAndAllowsNextTurn() throws Exception {
        CountDownLatch active = new CountDownLatch(2);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger resets = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        List<Frame> output = new CopyOnWriteArrayList<>();
        Processor left = resettingProcessor(active, running, resets, closes);
        Processor right = resettingProcessor(active, running, resets, closes);
        ParallelProcessor parallel = parallel(left, right, Duration.ofSeconds(5), 2);
        PipelineSession session = Pipeline.builder().add(parallel).build().openSession(SessionOptions.defaults(), output::add);
        session.trySubmit(new Frame.Text("old"));
        await(active);
        session.interrupt();
        session.trySubmit(new Frame.Text("new"));
        session.finish();
        session.completion().get(3, TimeUnit.SECONDS);
        assertEquals(List.of(new Frame.Text("new"), new Frame.Text("new")), output);
        assertEquals(2, resets.get());
        assertEquals(2, closes.get());
        assertEquals(0, running.get());
    }

    @Test void closesAllBranchesInReverseOrderAndRetainsFailures() {
        List<String> closed = new ArrayList<>();
        Processor left = closingProcessor("left", closed);
        Processor right = closingProcessor("right", closed);
        ParallelProcessor parallel = parallel(left, right, Duration.ofSeconds(1), 1);
        IOException failure = assertThrows(IOException.class, parallel::close);
        assertEquals("right", failure.getMessage());
        assertEquals("left", failure.getSuppressed()[0].getMessage());
        assertEquals(List.of("right", "left"), closed);
        assertDoesNotThrow(parallel::close);
        assertThrows(IllegalStateException.class, () -> parallel.process(new Frame.Text("late"), frame -> { }));
    }

    @Test void validatesNamesOwnershipAndBounds() {
        Processor stage = (frame, next) -> next.accept(frame);
        assertThrows(IllegalArgumentException.class, () -> new ParallelProcessor.Branch(" ", stage));
        assertThrows(IllegalArgumentException.class, () -> new ParallelProcessor(List.of(), Duration.ofSeconds(1), 1));
        assertThrows(IllegalArgumentException.class, () -> parallel(stage, stage, Duration.ofSeconds(1), 1));
        assertThrows(IllegalArgumentException.class, () -> parallel(
                (AsyncProcessor) (frame, emitter) -> emitter.complete(), stage, Duration.ofSeconds(1), 1));
        assertThrows(IllegalArgumentException.class, () -> new ParallelProcessor(
                List.of(new ParallelProcessor.Branch("same", stage), new ParallelProcessor.Branch("same", (frame, next) -> { })), Duration.ofSeconds(1), 1));
        assertThrows(IllegalArgumentException.class, () -> parallel(stage, (frame, next) -> { }, Duration.ZERO, 1));
        assertThrows(IllegalArgumentException.class, () -> parallel(stage, (frame, next) -> { }, Duration.ofSeconds(1), 0));
    }

    private static ParallelProcessor parallel(Processor left, Processor right, Duration timeout, int max) {
        return new ParallelProcessor(List.of(new ParallelProcessor.Branch("left", left), new ParallelProcessor.Branch("right", right)), timeout, max);
    }

    private static Processor resettingProcessor(CountDownLatch active, AtomicInteger running, AtomicInteger resets, AtomicInteger closes) {
        return new Processor() {
            @Override public void process(Frame frame, FrameSink next) throws Exception {
                running.incrementAndGet();
                try {
                    if (frame.equals(new Frame.Text("old"))) { active.countDown(); new CountDownLatch(1).await(); }
                    next.accept(frame);
                } finally { running.decrementAndGet(); }
            }
            @Override public void onInterrupt() {
                // Branch-local ownership is also enforced by the combinator's lock.
                resets.incrementAndGet();
            }
            @Override public void close() { closes.incrementAndGet(); }
        };
    }

    private static Processor closingProcessor(String name, List<String> closed) {
        return new Processor() {
            @Override public void process(Frame frame, FrameSink next) throws Exception { next.accept(frame); }
            @Override public void close() throws IOException { closed.add(name); throw new IOException(name); }
        };
    }

    private static void await(CountDownLatch latch) throws InterruptedException { assertTrue(latch.await(3, TimeUnit.SECONDS)); }
}
