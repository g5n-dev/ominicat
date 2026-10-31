package io.ominicat.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.ominicat.core.Frame;
import io.ominicat.core.FrameEnvelope;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ActivityJournalTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void ringReportsLossAndPagesWithoutPretendingTheTraceIsComplete() throws Exception {
        var journal = new ActivityJournal();
        assertEquals(0, JSON.readTree(journal.json(0)).path("events").size());
        var activity = journal.openSession();
        for (int index = 0; index < 1500; index++) activity.record("ingress_accepted", new Frame.Text("frame-" + index), "accepted");
        var first = JSON.readTree(journal.json(0));
        assertEquals(1024, first.path("capacity").asInt());
        assertEquals(477, first.path("oldestSeq").asLong());
        assertEquals(1500, first.path("latestSeq").asLong());
        assertEquals(476, first.path("lost").asLong());
        assertTrue(first.path("gap").asBoolean());
        assertTrue(first.path("incomplete").asBoolean());
        assertEquals(128, first.path("events").size());
        assertEquals(604, first.path("nextCursor").asLong());
        assertTrue(first.path("events").get(0).path("timeNanos").isTextual());
        assertDoesNotThrow(() -> Long.parseLong(first.path("events").get(0).path("timeNanos").asText()));
        var second = JSON.readTree(journal.json(604));
        assertFalse(second.path("gap").asBoolean());
        assertEquals(605, second.path("events").get(0).path("seq").asLong());
        assertTrue(JSON.readTree(journal.json(2000)).path("reset").asBoolean());
        assertEquals(0, JSON.readTree(journal.json(1500)).path("events").size());
    }

    @Test void correlationIsQualifiedByServerSessionAndRawFramesRemainUnattributed() throws Exception {
        var journal = new ActivityJournal();
        var first = journal.openSession(); var second = journal.openSession();
        var envelope = new FrameEnvelope(new Frame.Text("content"), "event", "caller-session", "turn", "same-correlation", "", 0, 0);
        first.record("ingress_accepted", envelope, "accepted");
        second.record("ingress_accepted", envelope, "accepted");
        first.record("ingress_accepted", new Frame.Text("raw"), "accepted");
        var events = JSON.readTree(journal.json(0)).path("events");
        assertNotEquals(events.get(0).path("sessionId"), events.get(1).path("sessionId"));
        assertNotEquals(events.get(0).path("runId"), events.get(1).path("runId"));
        assertEquals("caller-session", events.get(0).path("clientSessionId").asText());
        assertNotEquals("caller-session", events.get(0).path("sessionId").asText());
        assertEquals("", events.get(2).path("runId").asText());
        assertEquals("", events.get(2).path("correlationId").asText());
    }

    @Test void previewsAreBoundedAndNeverReflectOrStringifyUnknownFramesOrMedia() throws Exception {
        record UnknownFrame(String secret) implements Frame {
            @Override public String toString() { throw new AssertionError("must not stringify custom frames"); }
        }
        var journal = new ActivityJournal(); var activity = journal.openSession();
        activity.record("output", new UnknownFrame("private-custom-data"), "offered");
        activity.record("output", new Frame.Text("a".repeat(239) + "😀" + "private-tail"), "offered");
        activity.record("output", new Frame.Audio(ByteBuffer.wrap(new byte[]{1, 2, 3, 4}), 16000, 1), "offered");
        activity.record("output", new Frame.Image(ByteBuffer.wrap(new byte[]{1, 2, 3, 4}), "image/png"), "offered");
        var events = JSON.readTree(journal.json(0)).path("events");
        assertEquals("", events.get(0).path("preview").asText());
        assertFalse(journal.json(0).contains("private-custom-data"));
        assertFalse(journal.json(0).contains("private-tail"));
        assertEquals(239, events.get(1).path("preview").asText().length());
        assertTrue(events.get(2).path("preview").asText().contains("16000 Hz"));
        assertTrue(events.get(3).path("preview").asText().contains("4 bytes"));
        assertFalse(journal.json(0).contains("AQIDBA"));
    }
}
