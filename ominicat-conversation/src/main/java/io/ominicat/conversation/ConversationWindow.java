package io.ominicat.conversation;

import io.ominicat.core.Frame;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Session-confined, bounded final-transcript state. Deduplication covers the retained window. */
public final class ConversationWindow {
    public static final int DEFAULT_MAX_TURNS = 256;
    public static final int DEFAULT_MAX_TEXT_CHARS = 8192;

    private final int maxTurns;
    private final int maxTextChars;
    private final ArrayDeque<Turn> turns = new ArrayDeque<>();
    private final Map<String, Turn> segments = new HashMap<>();
    private long evictedTurns;

    public ConversationWindow(int maxTurns, int maxTextChars) {
        if (maxTurns < 1 || maxTurns > 10_000) {
            throw new IllegalArgumentException("maxTurns must be between 1 and 10000");
        }
        if (maxTextChars < 1 || maxTextChars > 1_048_576) {
            throw new IllegalArgumentException("maxTextChars must be between 1 and 1048576");
        }
        this.maxTurns = maxTurns;
        this.maxTextChars = maxTextChars;
    }

    public ConversationWindow(int maxTurns) {
        this(maxTurns, DEFAULT_MAX_TEXT_CHARS);
    }

    /** Restore externally retained state; no storage or checkpoint I/O is performed. */
    public ConversationWindow(int maxTurns, int maxTextChars, ConversationSnapshot snapshot) {
        this(maxTurns, maxTextChars);
        restore(snapshot);
    }

    /** Returns true only when a new final turn was retained. */
    public boolean add(Frame.Transcript transcript) {
        if (!transcript.finalResult()) {
            return false;
        }
        if (transcript.text().length() > maxTextChars) {
            throw new IllegalArgumentException("transcript exceeds maxTextChars=" + maxTextChars);
        }
        if (transcript.speaker().length() > 128) {
            throw new IllegalArgumentException("speaker must not exceed 128 characters");
        }
        Turn turn = Turn.from(transcript);
        Turn committed = segments.get(turn.segmentId());
        if (committed != null) {
            if (!sameCommittedFacts(committed, turn)) {
                throw new IllegalArgumentException("conflicting final transcript for segmentId=" + turn.segmentId());
            }
            return false;
        }
        if (turns.size() == maxTurns) {
            segments.remove(turns.removeFirst().segmentId());
            evictedTurns++;
        }
        turns.addLast(turn);
        segments.put(turn.segmentId(), turn);
        return true;
    }

    public ConversationSnapshot snapshot() {
        List<Turn> retained = List.copyOf(turns);
        return new ConversationSnapshot(retained, evictedTurns, ConversationMetrics.from(retained));
    }

    /** Drops retained state and resets the eviction count for a new conversation segment. */
    public void reset() {
        turns.clear();
        segments.clear();
        evictedTurns = 0;
    }

    /** Replace state atomically after fully validating a snapshot against this window's limits. */
    public void restore(ConversationSnapshot snapshot) {
        if (snapshot.turns().size() > maxTurns) {
            throw new IllegalArgumentException("snapshot exceeds maxTurns=" + maxTurns);
        }
        ConversationWindow restored = new ConversationWindow(maxTurns, maxTextChars);
        for (Turn turn : snapshot.turns()) {
            boolean added = restored.add(new Frame.Transcript(turn.text(), turn.speaker(), turn.startMillis(),
                    turn.endMillis(), true, turn.confidence(), turn.segmentId(), turn.revision(), turn.role()));
            if (!added) {
                throw new IllegalArgumentException("snapshot contains duplicate segmentId=" + turn.segmentId());
            }
        }
        if (!restored.snapshot().metrics().equals(snapshot.metrics())) {
            throw new IllegalArgumentException("snapshot metrics do not match retained turns");
        }
        turns.clear();
        turns.addAll(restored.turns);
        segments.clear();
        segments.putAll(restored.segments);
        evictedTurns = snapshot.evictedTurns();
    }

    private static boolean sameCommittedFacts(Turn first, Turn second) {
        return first.speaker().equals(second.speaker()) && first.text().equals(second.text())
                && first.startMillis() == second.startMillis() && first.endMillis() == second.endMillis()
                && first.role().equals(second.role());
    }
}
