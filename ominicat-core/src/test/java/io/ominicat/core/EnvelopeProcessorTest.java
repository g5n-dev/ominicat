package io.ominicat.core;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EnvelopeProcessorTest {
    @Test void deterministicMultiEmissionPreservesCausalChain() throws Exception {
        var ids = new AtomicInteger();
        Processor duplicate = (frame, next) -> { next.accept(frame); next.accept(new Frame.Text("derived")); };
        Pipeline pipeline = Pipeline.builder()
                .add(new EnvelopeProcessor(duplicate, () -> "event-" + ids.incrementAndGet(), () -> 30))
                .add(new EnvelopeProcessor((frame, next) -> next.accept(frame), () -> "event-" + ids.incrementAndGet(), () -> 40))
                .build();
        var outputs = new ArrayList<Frame>();
        pipeline.process(new FrameEnvelope(new Frame.Text("source"), "source", "session", "turn", "correlation", "", 10, 20), outputs::add);
        assertEquals(2, outputs.size());
        FrameEnvelope first = (FrameEnvelope) outputs.getFirst();
        assertEquals("event-1", first.causationId());
        assertEquals("correlation", first.correlationId());
        assertEquals(40, first.emitTimeNanos());
        assertEquals(new Frame.Text("source"), first.frame());
    }
    @Test void rawFramesStayRawAndMetadataValidationRejectsNesting() throws Exception {
        var outputs = new ArrayList<Frame>();
        new EnvelopeProcessor((frame, next) -> next.accept(frame)).process(new Frame.Text("raw"), outputs::add);
        assertEquals(new Frame.Text("raw"), outputs.getFirst());
        var envelope = new FrameEnvelope(new Frame.Text("x"), "id", "s", "t", "c", "", 1, 2);
        assertThrows(IllegalArgumentException.class, () -> envelope.derive(envelope, "next", 3));
        assertThrows(IllegalArgumentException.class, () -> envelope.derive(new Frame.Text("x"), "next", 1));
    }
}
