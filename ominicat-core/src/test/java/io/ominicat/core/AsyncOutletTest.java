package io.ominicat.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class AsyncOutletTest {
    @Test void isolatesSlowDeliveryAndRejectsSaturationExplicitly() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<Frame> delivered = new CopyOnWriteArrayList<>();
        AsyncOutlet outlet = new AsyncOutlet(1, frame -> {
            assertTrue(Thread.currentThread().isVirtual());
            entered.countDown(); release.await(); delivered.add(frame);
        });
        outlet.accept(new Frame.Text("active"));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        outlet.accept(new Frame.Text("pending"));
        assertFalse(outlet.tryAccept(new Frame.Text("rejected")));
        assertThrows(RejectedExecutionException.class, () -> outlet.accept(new Frame.Text("also-rejected")));
        assertEquals(1, outlet.metrics().queueDepth());
        outlet.finish();
        release.countDown();
        outlet.completion().get(2, TimeUnit.SECONDS);
        assertEquals(List.of(new Frame.Text("active"), new Frame.Text("pending")), delivered);
        assertEquals(2, outlet.metrics().processed());
        assertEquals(2, outlet.metrics().rejected());
    }

    @Test void sinkFailureIsObservableAndDoesNotRetry() throws Exception {
        IOException cause = new IOException("destination failed");
        AsyncOutlet outlet = new AsyncOutlet(4, frame -> { throw cause; });
        outlet.accept(new Frame.Text("event"));
        ExecutionException failure = assertThrows(ExecutionException.class, () -> outlet.completion().get(2, TimeUnit.SECONDS));
        assertSame(cause, failure.getCause());
        assertEquals(1, outlet.metrics().failed());
        assertFalse(outlet.tryAccept(new Frame.Text("late")));
        outlet.close();
    }

    @Test void closeCancelsBlockingDeliveryAndRejectsNewFrames() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        AsyncOutlet outlet = new AsyncOutlet(1, frame -> { entered.countDown(); new CountDownLatch(1).await(); });
        outlet.accept(new Frame.Text("event"));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        outlet.close();
        assertThrows(ExecutionException.class, () -> outlet.completion().get(2, TimeUnit.SECONDS));
        assertFalse(outlet.tryAccept(new Frame.Text("late")));
        assertEquals(1, outlet.metrics().cancelled());
    }
}
