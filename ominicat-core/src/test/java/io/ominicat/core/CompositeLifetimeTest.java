package io.ominicat.core;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class CompositeLifetimeTest {
    @Test void parallelCloseAttemptsAllOwnedChildrenEvenWhenOneThrowsError() {
        List<String> closes = new ArrayList<>();
        Error error = new AssertionError("right close failed");
        Processor left = owned("left", closes, null);
        Processor right = owned("right", closes, error);
        ParallelProcessor parallel = parallel(left, right);
        assertSame(error, assertThrows(Error.class, parallel::close));
        assertEquals(List.of("right", "left"), closes);
        assertDoesNotThrow(parallel::close);
    }

    @Test void fallbackCloseAttemptsAllOwnedChildrenEvenWhenOneThrowsError() {
        List<String> closes = new ArrayList<>();
        Error error = new AssertionError("secondary close failed");
        FallbackProcessor fallback = new FallbackProcessor(owned("primary", closes, null), owned("secondary", closes, error));
        assertSame(error, assertThrows(Error.class, fallback::close));
        assertEquals(List.of("secondary", "primary"), closes);
        assertDoesNotThrow(fallback::close);
    }

    @Test void duplicateStageOwnershipIsRejectedInsteadOfDoubleClosingAResource() {
        Processor owned = (frame, next) -> next.accept(frame);
        Pipeline.Builder builder = Pipeline.builder().add(owned);
        assertThrows(IllegalArgumentException.class, () -> builder.add(owned));
    }

    @Test void failedPrimaryCannotEmitFromCapturedSinkWhileSecondaryIsActive() throws Exception {
        AtomicReference<FrameSink> primarySink = new AtomicReference<>();
        AtomicInteger delivered = new AtomicInteger();
        Processor primary = (frame, next) -> { primarySink.set(next); throw new java.io.IOException("primary failed"); };
        Processor secondary = (frame, next) -> {
            assertThrows(IllegalStateException.class, () -> primarySink.get().accept(new Frame.Text("stale primary")));
            next.accept(new Frame.Text("secondary"));
        };
        FallbackProcessor fallback = new FallbackProcessor(primary, secondary);
        fallback.process(new Frame.Text("request"), frame -> delivered.incrementAndGet());
        assertEquals(1, delivered.get());
        fallback.close();
    }

    @Test void capturedParallelBranchSinkCannotEscapeItsInvocation() throws Exception {
        AtomicReference<FrameSink> retained = new AtomicReference<>();
        try (ParallelProcessor parallel = parallel((frame, next) -> retained.set(next), (frame, next) -> { })) {
            parallel.process(new Frame.Text("request"), frame -> fail("branches emit nothing"));
            assertThrows(IllegalStateException.class, () -> retained.get().accept(new Frame.Text("late")));
        }
    }

    @Test void closingParallelJoinPreventsFurtherEmissionsAfterAlreadyEnteredSinkReturns() throws Exception {
        CountDownLatch deliveredFirst = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger delivered = new AtomicInteger();
        ParallelProcessor parallel = parallel((frame, next) -> { next.accept(frame); next.accept(frame); }, (frame, next) -> { });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var running = executor.submit(() -> {
                parallel.process(new Frame.Text("result"), frame -> {
                    delivered.incrementAndGet(); deliveredFirst.countDown(); releaseFirst.await();
                });
                return null;
            });
            assertTrue(deliveredFirst.await(2, TimeUnit.SECONDS));
            parallel.close();
            releaseFirst.countDown();
            ExecutionException failure = assertThrows(ExecutionException.class, () -> running.get(2, TimeUnit.SECONDS));
            assertInstanceOf(CancellationException.class, failure.getCause());
            assertEquals(1, delivered.get());
        } finally { releaseFirst.countDown(); parallel.close(); }
    }

    @Test void timeoutCannotAllowCleanupToOverlapAnUncooperativeBranch() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch closeAttempted = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        Processor slow = new Processor() {
            @Override public void process(Frame frame, FrameSink next) {
                assertEquals(1, active.incrementAndGet()); entered.countDown();
                boolean released = false;
                while (!released) {
                    try { release.await(); released = true; }
                    catch (InterruptedException ignored) { }
                }
                active.decrementAndGet();
            }
            @Override public void close() { assertEquals(0, active.get()); closed.incrementAndGet(); }
        };
        ParallelProcessor parallel = new ParallelProcessor(List.of(new ParallelProcessor.Branch("slow", slow)), Duration.ofMillis(25), 1);
        assertThrows(TimeoutException.class, () -> parallel.process(new Frame.Text("request"), frame -> { }));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var closing = executor.submit(() -> { closeAttempted.countDown(); parallel.close(); return null; });
            assertTrue(closeAttempted.await(2, TimeUnit.SECONDS));
            assertEquals(0, closed.get());
            release.countDown();
            closing.get(2, TimeUnit.SECONDS);
        } finally { release.countDown(); parallel.close(); }
        assertEquals(1, closed.get());
    }

    private static Processor owned(String name, List<String> closes, Error failure) {
        return new Processor() {
            @Override public void process(Frame frame, FrameSink next) throws Exception { next.accept(frame); }
            @Override public void close() {
                closes.add(name);
                if (failure != null) throw failure;
            }
        };
    }

    private static ParallelProcessor parallel(Processor left, Processor right) {
        return new ParallelProcessor(List.of(new ParallelProcessor.Branch("left", left), new ParallelProcessor.Branch("right", right)), Duration.ofSeconds(2), 4);
    }
}
