package io.ominicat.conversation;

import static org.junit.jupiter.api.Assertions.*;

import io.ominicat.core.Frame;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConversationWindowTest {
    @Test
    void excludesPartialsAndDeduplicatesFinalsEvenWhenConfidenceChanges() {
        ConversationWindow window = new ConversationWindow(4);
        assertFalse(window.add(new Frame.Transcript("hel", "user", 0, 10, false, .5)));
        assertTrue(window.add(finalTurn("hello", "user", 0, 10)));
        assertFalse(window.add(new Frame.Transcript("hello", "user", 0, 10, true, .99)));
        assertTrue(window.add(finalTurn("hello", "agent", 0, 10)));
        assertEquals(2, window.snapshot().turns().size());
        assertEquals(1, window.snapshot().turnsBySpeaker("user").size());
    }

    @Test
    void retentionIsBoundedAndDedupKeysAreEvictedWithTurns() {
        ConversationWindow window = new ConversationWindow(2);
        Frame.Transcript first = finalTurn("one", "user", 0, 10);
        window.add(first);
        window.add(finalTurn("two", "user", 10, 20));
        ConversationSnapshot previous = window.snapshot();
        window.add(finalTurn("three", "user", 20, 30));
        assertEquals(List.of("two", "three"), window.snapshot().turns().stream().map(Turn::text).toList());
        assertEquals(1, window.snapshot().evictedTurns());
        assertTrue(window.add(first));
        assertEquals(2, window.snapshot().evictedTurns());
        assertEquals(List.of("one", "two"), previous.turns().stream().map(Turn::text).toList());
        assertThrows(UnsupportedOperationException.class, () -> previous.turns().clear());
    }

    @Test
    void intervalMetricsMergeOverlappingTurnsWithoutDoubleCounting() {
        ConversationWindow window = new ConversationWindow(8);
        window.add(finalTurn("a", "user", 100, 200));
        window.add(finalTurn("b", "user", 150, 250));
        window.add(finalTurn("c", "agent", 230, 300));
        window.add(finalTurn("d", "agent", 400, 500));
        ConversationMetrics metrics = window.snapshot().metrics();
        assertEquals(400, metrics.durationMillis());
        assertEquals(300, metrics.speechMillis());
        assertEquals(100, metrics.silenceMillis());
        assertEquals(150L, metrics.speakerSpeechMillis().get("user"));
        assertEquals(170L, metrics.speakerSpeechMillis().get("agent"));
        assertThrows(UnsupportedOperationException.class, () -> metrics.speakerSpeechMillis().clear());
    }

    @Test
    void metricsHandleEmptyAndZeroLengthIntervals() {
        ConversationWindow window = new ConversationWindow(4);
        assertEquals(new ConversationMetrics(0, 0, 0, java.util.Map.of()), window.snapshot().metrics());
        window.add(finalTurn("a", "user", 10, 10));
        window.add(finalTurn("b", "user", 20, 20));
        assertEquals(10, window.snapshot().metrics().silenceMillis());
        assertEquals(0, window.snapshot().metrics().speechMillis());
    }

    @Test
    void rejectsInvalidBoundsAndOversizedFinalTranscripts() {
        assertThrows(IllegalArgumentException.class, () -> new ConversationWindow(0));
        assertThrows(IllegalArgumentException.class, () -> new ConversationWindow(10_001));
        assertThrows(IllegalArgumentException.class, () -> new ConversationWindow(1, 0));
        assertThrows(IllegalArgumentException.class, () -> new ConversationWindow(1, 1_048_577));
        ConversationWindow window = new ConversationWindow(1, 3);
        assertThrows(IllegalArgumentException.class, () -> window.add(finalTurn("long", "user", 0, 1)));
        assertThrows(IllegalArgumentException.class, () -> window.add(finalTurn("a", "s".repeat(129), 0, 1)));
        assertTrue(window.snapshot().turns().isEmpty());
    }

    @Test
    void turnAndSnapshotValidateInputs() {
        assertThrows(IllegalArgumentException.class,
                () -> Turn.from(new Frame.Transcript("x", "user", 0, 1, false, 1)));
        assertThrows(IllegalArgumentException.class, () -> new Turn("x", " ", 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Turn("x", "user", -1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Turn("x", "user", 2, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Turn("x", "user", 0, 1, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new Turn("x", "user", 0, 1, 2));
        assertThrows(IllegalArgumentException.class,
                () -> new ConversationSnapshot(List.of(), -1, new ConversationMetrics(0, 0, 0, java.util.Map.of())));
    }

    @Test
    void resetDropsRetentionAndDeduplicationState() {
        ConversationWindow window = new ConversationWindow(1);
        Frame.Transcript first = finalTurn("one", "user", 0, 10);
        window.add(first);
        window.add(finalTurn("two", "user", 10, 20));
        window.reset();
        assertEquals(0, window.snapshot().evictedTurns());
        assertTrue(window.snapshot().turns().isEmpty());
        assertTrue(window.add(first));
    }

    @Test
    void stableIdsCommitFinalFactsAndPreserveRoleIndependentlyOfSpeaker() {
        ConversationWindow window = new ConversationWindow(4);
        Frame.Transcript first = new Frame.Transcript("ready", "track-3", 0, 10, true, -1,
                "segment-1", 2, "participant");
        assertTrue(window.add(first));
        assertFalse(window.add(new Frame.Transcript("ready", "track-3", 0, 10, true, .9,
                "segment-1", 3, "participant")));
        assertEquals("participant", window.snapshot().turns().getFirst().role());
        assertEquals("track-3", window.snapshot().turns().getFirst().speaker());
        assertEquals(2, window.snapshot().turns().getFirst().revision());
        assertThrows(IllegalArgumentException.class, () -> window.add(new Frame.Transcript(
                "changed", "track-3", 0, 10, true, .9, "segment-1", 4, "participant")));
        assertThrows(IllegalArgumentException.class, () -> window.add(new Frame.Transcript(
                "ready", "track-3", 0, 10, true, .9, "segment-1", 4, "assistant")));
        assertEquals(1, window.snapshot().turns().size());
        assertTrue(window.add(new Frame.Transcript("ready", "track-3", 0, 10, true, .9,
                "segment-2", 0, "participant")));
    }

    @Test
    void restoresDeterministicSnapshotWithDeduplicationAndEvictionCounts() {
        ConversationWindow original = new ConversationWindow(2);
        original.add(finalTurn("one", "user", 0, 10));
        original.add(finalTurn("two", "agent", 10, 20));
        original.add(finalTurn("three", "user", 20, 30));
        ConversationSnapshot snapshot = original.snapshot();
        ConversationWindow restored = new ConversationWindow(2, 8192, snapshot);
        assertEquals(snapshot, restored.snapshot());
        assertFalse(restored.add(finalTurn("three", "user", 20, 30)));
        assertTrue(restored.add(finalTurn("four", "agent", 30, 40)));
        assertEquals(2, restored.snapshot().evictedTurns());
    }

    @Test
    void invalidRestoreIsAtomicAndRejectsMismatchedMetricsAndDuplicateIds() {
        ConversationWindow window = new ConversationWindow(2);
        window.add(finalTurn("one", "user", 0, 10));
        ConversationSnapshot before = window.snapshot();
        assertThrows(IllegalArgumentException.class, () -> window.restore(new ConversationSnapshot(before.turns(),
                0, new ConversationMetrics(0, 0, 0, java.util.Map.of()))));
        assertEquals(before, window.snapshot());
        assertThrows(IllegalArgumentException.class, () -> window.restore(new ConversationSnapshot(
                List.of(before.turns().getFirst(), before.turns().getFirst()), 0, before.metrics())));
        assertEquals(before, window.snapshot());
        assertThrows(IllegalArgumentException.class, () -> new ConversationWindow(1, 2, before));
    }

    static Frame.Transcript finalTurn(String text, String speaker, long start, long end) {
        return new Frame.Transcript(text, speaker, start, end, true, 1);
    }
}
