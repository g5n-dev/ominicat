package io.ominicat.providers;

import io.ominicat.core.WorkerThreads;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PublisherInputStreamTest {
    @Test void requestsExactlyOneWholeBatchAndDoesNotPrefetch() throws Exception {
        try (var stream = new CompatibleClient.PublisherInputStream()) {
            var subscription = new Subscription();
            stream.onSubscribe(subscription);
            assertEquals(1, subscription.requests.get());
            stream.onNext(List.of(ByteBuffer.allocate(0), ByteBuffer.wrap(new byte[] {1, 2}),
                    ByteBuffer.wrap(new byte[] {3, 4})));
            assertEquals(1, stream.read());
            assertEquals(2, stream.read());
            assertEquals(1, subscription.requests.get());
            var bytes = new byte[8];
            assertEquals(2, stream.read(bytes));
            assertArrayEquals(new byte[] {3, 4}, java.util.Arrays.copyOf(bytes, 2));
            assertEquals(1, subscription.requests.get(), "drained batch must not trigger eager prefetch");
            subscription.onRequest = () -> { stream.onNext(List.of(ByteBuffer.wrap(new byte[] {5}))); stream.onComplete(); };
            assertEquals(5, stream.read());
            assertEquals(2, subscription.requests.get());
            assertEquals(-1, stream.read());
            assertEquals(-1, stream.read());
            assertEquals(2, subscription.requests.get());
            assertFalse(subscription.badDemand.get());
        }
    }

    @Test void completionWithoutFurtherDemandPreservesQueuedDataAndStickyEof() throws Exception {
        try (var stream = new CompatibleClient.PublisherInputStream()) {
            var subscription = new Subscription();
            stream.onSubscribe(subscription);
            stream.onNext(List.of(ByteBuffer.wrap(new byte[] {11, 12})));
            stream.onComplete();
            assertArrayEquals(new byte[] {11, 12}, stream.readAllBytes());
            assertEquals(-1, stream.read());
            assertEquals(-1, stream.read(new byte[4]));
            assertEquals(1, subscription.requests.get());
        }
    }

    @Test void upstreamErrorFollowsAllPreviouslyDeliveredBytesAndRemainsSticky() throws Exception {
        try (var stream = new CompatibleClient.PublisherInputStream()) {
            stream.onSubscribe(new Subscription());
            stream.onNext(List.of(ByteBuffer.wrap(new byte[] {21}), ByteBuffer.wrap(new byte[] {22, 23})));
            assertEquals(21, stream.read());
            var failure = new IOException("synthetic upstream failure");
            stream.onError(failure);
            assertEquals(22, stream.read());
            assertEquals(23, stream.read());
            assertSame(failure, assertThrows(IOException.class, stream::read));
            assertSame(failure, assertThrows(IOException.class, stream::read));
        }
    }

    @Test void closeWakesBlockedReaderAndCancelsOnlyOnce() throws Exception {
        var stream = new CompatibleClient.PublisherInputStream();
        var subscription = new Subscription();
        stream.onSubscribe(subscription);
        var result = new CompletableFuture<Throwable>();
        Thread worker = WorkerThreads.start("body-close-test-", () -> {
            try { stream.read(); result.complete(new AssertionError("read unexpectedly succeeded")); }
            catch (Throwable failure) { result.complete(failure); }
        });
        try {
            ProviderHttpTestSupport.awaitResponseRead(worker);
            stream.close();
            assertInstanceOf(IOException.class, result.get(2, TimeUnit.SECONDS));
            assertEquals(1, subscription.cancels.get());
            stream.close();
            assertEquals(1, subscription.cancels.get());
            stream.onNext(List.of(ByteBuffer.allocate(32_768)));
            stream.onComplete();
            assertThrows(IOException.class, stream::read);
        } finally { stream.close(); worker.interrupt(); worker.join(3000); }
        assertFalse(worker.isAlive());
    }

    @Test void interruptedBlockedReaderCancelsAndRestoresInterruptFlag() throws Exception {
        var stream = new CompatibleClient.PublisherInputStream();
        var subscription = new Subscription();
        stream.onSubscribe(subscription);
        var result = new CompletableFuture<Throwable>();
        var interruptFlag = new AtomicBoolean();
        Thread worker = WorkerThreads.start("body-interrupt-test-", () -> {
            try { stream.read(); result.complete(new AssertionError("read unexpectedly succeeded")); }
            catch (Throwable failure) {
                interruptFlag.set(Thread.currentThread().isInterrupted());
                result.complete(failure);
            }
        });
        try {
            ProviderHttpTestSupport.awaitResponseRead(worker);
            worker.interrupt();
            Throwable failure = result.get(2, TimeUnit.SECONDS);
            assertInstanceOf(InterruptedIOException.class, failure);
            assertInstanceOf(InterruptedException.class, failure.getCause());
            assertTrue(interruptFlag.get());
            assertEquals(1, subscription.cancels.get());
            stream.onNext(List.of(ByteBuffer.allocate(32_768)));
            stream.onError(new IOException("late error"));
            assertThrows(IOException.class, stream::read);
        } finally { stream.close(); worker.interrupt(); worker.join(3000); }
        assertFalse(worker.isAlive());
    }

    @Test void closeBeforeSubscriptionAndDuplicateSubscriptionCancelIncomingOnly() throws Exception {
        var closed = new CompatibleClient.PublisherInputStream();
        closed.close();
        var late = new Subscription();
        closed.onSubscribe(late);
        assertEquals(0, late.requests.get());
        assertEquals(1, late.cancels.get());
        try (var stream = new CompatibleClient.PublisherInputStream()) {
            var first = new Subscription();
            var duplicate = new Subscription();
            stream.onSubscribe(first);
            stream.onSubscribe(duplicate);
            assertEquals(1, first.requests.get());
            assertEquals(0, first.cancels.get());
            assertEquals(0, duplicate.requests.get());
            assertEquals(1, duplicate.cancels.get());
            stream.close();
            assertEquals(1, first.cancels.get());
        }
    }

    @Test void failedDemandCancelsAndSurfacesAsIOException() throws Exception {
        try (var stream = new CompatibleClient.PublisherInputStream()) {
            var subscription = new Subscription();
            subscription.onRequest = () -> { throw new IllegalStateException("synthetic demand failure"); };
            stream.onSubscribe(subscription);
            IOException failure = assertThrows(IOException.class, stream::read);
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertEquals(1, subscription.cancels.get());
        }
    }

    @Test void overproductionCancelsAndPreservesTheDemandedBatchBeforeError() throws Exception {
        try (var stream = new CompatibleClient.PublisherInputStream()) {
            var subscription = new Subscription();
            stream.onSubscribe(subscription);
            stream.onNext(List.of(ByteBuffer.wrap(new byte[] {31, 32})));
            stream.onNext(List.of(ByteBuffer.wrap(new byte[] {99})));
            stream.onComplete();
            assertEquals(1, subscription.cancels.get());
            assertEquals(31, stream.read());
            assertEquals(32, stream.read());
            assertThrows(IOException.class, stream::read);
        }
    }

    @Test void subscriptionCallsPermitCallbacksFromAnotherThreadBeforeTheyReturn() throws Exception {
        try (var stream = new CompatibleClient.PublisherInputStream()) {
            var subscription = new Subscription();
            subscription.onRequest = () -> callbackBeforeReturning(() ->
                    stream.onNext(List.of(ByteBuffer.wrap(new byte[] {41}))));
            subscription.onCancel = () -> callbackBeforeReturning(() -> {
                stream.onNext(List.of(ByteBuffer.allocate(32_768)));
                stream.onComplete();
            });
            stream.onSubscribe(subscription);
            assertEquals(41, stream.read());
            stream.close();
            assertEquals(1, subscription.cancels.get());
        }
    }

    private static void callbackBeforeReturning(Runnable callback) {
        var completed = new CompletableFuture<Void>();
        Thread worker = WorkerThreads.start("body-callback-test-", () -> {
            try { callback.run(); completed.complete(null); }
            catch (Throwable failure) { completed.completeExceptionally(failure); }
        });
        try { completed.get(2, TimeUnit.SECONDS); }
        catch (Exception failure) { throw new AssertionError("subscription callback was blocked by the reader state lock", failure); }
        finally {
            worker.interrupt();
            try { worker.join(3000); }
            catch (InterruptedException interruption) { Thread.currentThread().interrupt(); throw new AssertionError(interruption); }
        }
        assertFalse(worker.isAlive());
    }

    private static final class Subscription implements Flow.Subscription {
        final AtomicInteger requests = new AtomicInteger();
        final AtomicInteger cancels = new AtomicInteger();
        final AtomicBoolean badDemand = new AtomicBoolean();
        Runnable onRequest = () -> { };
        Runnable onCancel = () -> { };
        @Override public void request(long count) {
            if (count != 1) badDemand.set(true);
            requests.incrementAndGet();
            onRequest.run();
        }
        @Override public void cancel() { cancels.incrementAndGet(); onCancel.run(); }
    }
}
