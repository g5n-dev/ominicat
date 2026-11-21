package io.ominicat.providers;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Budget accounting must bound raw bytes before UTF-8 decoding, on both read shapes. */
class ProviderResponseBudgetTest {
    private static final AtomicBoolean NEVER_EXPIRED = new AtomicBoolean();

    @Test void singleByteReadsRejectOnlyBytesBeyondTheBudget() throws Exception {
        try (var response = response(new ByteArrayInputStream(new byte[] {1, 2, 3, 4}), 3)) {
            assertEquals(1, response.stream().read());
            assertEquals(2, response.stream().read());
            assertEquals(3, response.stream().read());
            assertThrows(IOException.class, () -> response.stream().read());
        }
    }

    @Test void batchReadsDetectTheByteBeyondTheBudgetWithoutReturningIt() throws Exception {
        try (var response = response(new ByteArrayInputStream(new byte[] {1, 2, 3, 4, 5}), 4)) {
            byte[] chunk = new byte[8];
            assertEquals(3, response.stream().read(chunk, 0, 3));
            assertThrows(IOException.class, () -> response.stream().read(chunk, 0, chunk.length));
        }
        try (var exact = response(new ByteArrayInputStream(new byte[] {1, 2}), 2)) {
            assertEquals(2, exact.stream().read(new byte[4]));
            assertEquals(-1, exact.stream().read());
        }
    }

    @Test void zeroLengthBatchReadsStayWithinBudgetWithoutConsuming() throws Exception {
        try (var response = response(new ByteArrayInputStream(new byte[] {7}), 1)) {
            assertEquals(0, response.stream().read(new byte[0]));
            assertEquals(7, response.stream().read());
        }
    }

    @Test void bytesEnforcesTheSameBudgetAcrossChunkedReads() throws Exception {
        var exceeded = assertThrows(IOException.class, () -> {
            try (var response = response(new ByteArrayInputStream(new byte[8192 + 1]), 8192)) {
                response.bytes();
            }
        });
        assertEquals("provider response exceeded byte budget", exceeded.getMessage());
        try (var within = response(new ByteArrayInputStream(new byte[] {1, 2, 3}), 8192)) {
            assertArrayEquals(new byte[] {1, 2, 3}, within.bytes());
        }
    }

    private static CompatibleClient.Response response(java.io.InputStream source, int budget) {
        var cancelled = new AtomicBoolean();
        return new CompatibleClient.Response(source, cancellation(cancelled), budget, cancelled);
    }

    private static java.util.concurrent.ScheduledFuture<?> cancellation(AtomicBoolean expired) {
        return new java.util.concurrent.ScheduledFuture<Void>() {
            @Override public long getDelay(java.util.concurrent.TimeUnit unit) { return 0; }
            @Override public int compareTo(java.util.concurrent.Delayed other) { return 0; }
            @Override public boolean cancel(boolean mayInterruptIfRunning) { expired.set(true); return true; }
            @Override public boolean isCancelled() { return expired.get(); }
            @Override public boolean isDone() { return false; }
            @Override public Void get() { throw new IllegalStateException("deadline never runs in this fixture"); }
            @Override public Void get(long timeout, java.util.concurrent.TimeUnit unit) { throw new IllegalStateException("deadline never runs in this fixture"); }
        };
    }
}
