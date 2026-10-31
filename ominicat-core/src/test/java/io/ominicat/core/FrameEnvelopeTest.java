package io.ominicat.core;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class FrameEnvelopeTest {
    @Test void derivedEventPreservesCorrelationAndAddsCausation() {
        FrameEnvelope source = envelope("root", "", 10, 20);
        Frame.Text replacement = new Frame.Text("response");
        FrameEnvelope derived = source.withFrame(replacement);
        assertSame(replacement, derived.frame());
        assertNotEquals(source.id(), derived.id());
        assertEquals(source.id(), derived.causationId());
        assertEquals(source.sessionId(), derived.sessionId());
        assertEquals(source.turnId(), derived.turnId());
        assertEquals(source.correlationId(), derived.correlationId());
        assertEquals(10, derived.originTimeNanos());
        assertEquals(20, derived.ingestTimeNanos());
    }

    @Test void validatesIdentifiersAndSameDomainTimestamps() {
        assertThrows(IllegalArgumentException.class, () -> envelope(" ", "", 10, 20));
        assertThrows(NullPointerException.class, () -> envelope("root", null, 10, 20));
        assertThrows(IllegalArgumentException.class, () -> envelope("root", "", 20, 10));
        assertDoesNotThrow(() -> envelope("root", "", -20, -10));
    }

    private static FrameEnvelope envelope(String id, String cause, long origin, long ingest) {
        return new FrameEnvelope(new Frame.Text("request"), id, "session", "turn", "correlation", cause, origin, ingest);
    }
}
