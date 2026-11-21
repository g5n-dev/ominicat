package io.ominicat.benchmarks;

import io.ominicat.core.Frame;
import io.ominicat.core.FrameEnvelope;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class AsyncContinuationProbeTest {

    @Test
    void bothScenariosValidateIndependentOraclesAndDrainEveryInvocation() throws Exception {
        for (boolean envelope : new boolean[]{false, true}) {
            var harness = new AsyncContinuationProbe.Harness(envelope, TimeUnit.SECONDS.toNanos(10));
            Thread worker;
            try {
                for (long sequence = 1; sequence <= 128; sequence++) {
                    Frame result = harness.invoke();
                    if (envelope) {
                        FrameEnvelope source = assertInstanceOf(FrameEnvelope.class, result);
                        assertEquals("stage-4-" + sequence, source.id());
                        assertEquals("synthetic-session", source.sessionId());
                        assertEquals("synthetic-correlation", source.correlationId());
                        result = source.frame();
                    }
                    AsyncContinuationProbe.Value value = assertInstanceOf(AsyncContinuationProbe.Value.class, result);
                    assertEquals(sequence, value.sequence());
                }
                assertEquals(128, harness.successful());
                harness.assertDrained();
                worker = harness.worker();
                assertNotSame(Thread.currentThread(), worker);
            } finally { harness.close(); }
            assertFalse(worker.isAlive(), "join the actual session worker after cleanup");
            harness.close();
            assertThrows(IllegalStateException.class, harness::invoke);
        }
    }

    @Test
    void watchdogAndLifetimeAreExplicitAndBounded() {
        assertThrows(IllegalArgumentException.class,
                () -> new AsyncContinuationProbe.Harness(true, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new AsyncContinuationProbe.Harness(false, TimeUnit.SECONDS.toNanos(11)));
    }

    @Test
    void aForeignCallerIsRejectedWithoutConsumingTheNextSequence() throws Exception {
        try (var harness = new AsyncContinuationProbe.Harness(false, TimeUnit.SECONDS.toNanos(10))) {
            var failure = new AtomicReference<Throwable>();
            Thread foreign = new Thread(() -> {
                try { harness.invoke(); }
                catch (Throwable error) { failure.set(error); }
            }, "async-probe-foreign-caller");
            foreign.start();
            foreign.join(3000);
            assertFalse(foreign.isAlive());
            assertInstanceOf(IllegalStateException.class, failure.get());
            AsyncContinuationProbe.Value first = assertInstanceOf(AsyncContinuationProbe.Value.class, harness.invoke());
            assertEquals(1, first.sequence());
            harness.assertDrained();
        }
    }
}
