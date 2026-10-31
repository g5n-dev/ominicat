package io.ominicat.core;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FallbackProcessorTest {
    @Test void asyncChildrenFailAtCompositionTime() {
        AsyncProcessor async = (f, emitter) -> emitter.complete();
        Processor sync = (f, next) -> next.accept(f);
        assertThrows(IllegalArgumentException.class, () -> new FallbackProcessor(async, sync));
        assertThrows(IllegalArgumentException.class, () -> new FallbackProcessor(sync, async));
        assertThrows(IllegalArgumentException.class, () -> new EnvelopeProcessor(async));
        assertInstanceOf(AsyncProcessor.class, EnvelopeProcessor.wrap(async));
    }
    @Test void fallbackBeforeOutputButNeverAfterOutput() throws Exception {
        var outputs = new ArrayList<Frame>();
        var fallback = new FallbackProcessor((f, n) -> { throw new IllegalStateException("offline"); },
                (f, n) -> n.accept(new Frame.Text("secondary")));
        fallback.process(new Frame.Text("input"), outputs::add);
        assertEquals(new Frame.Text("secondary"), outputs.get(0));
        var secondaryCalled = new AtomicBoolean();
        var partial = new FallbackProcessor((f, n) -> { n.accept(f); throw new IllegalStateException("midstream"); },
                (f, n) -> secondaryCalled.set(true));
        assertThrows(IllegalStateException.class, () -> partial.process(new Frame.Text("x"), outputs::add));
        assertFalse(secondaryCalled.get());
    }
    @Test void interruptionAndSinkFailuresDoNotFallback() {
        var called = new AtomicBoolean();
        var interrupted = new FallbackProcessor((f, n) -> { throw new InterruptedException(); }, (f, n) -> called.set(true));
        assertThrows(InterruptedException.class, () -> interrupted.process(new Frame.Text("x"), f -> { }));
        var sinkFailure = new FallbackProcessor((f, n) -> n.accept(f), (f, n) -> called.set(true));
        assertThrows(IllegalArgumentException.class, () -> sinkFailure.process(new Frame.Text("x"), f -> { throw new IllegalArgumentException(); }));
        assertFalse(called.get());
    }
}
