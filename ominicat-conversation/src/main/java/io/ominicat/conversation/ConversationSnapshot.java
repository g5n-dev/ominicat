package io.ominicat.conversation;

import java.util.List;
import java.util.Objects;

/** Immutable evaluation input. Eviction is explicit so windowed results are identifiable. */
public record ConversationSnapshot(List<Turn> turns, long evictedTurns, ConversationMetrics metrics) {
    public ConversationSnapshot {
        turns = List.copyOf(turns);
        if (evictedTurns < 0) {
            throw new IllegalArgumentException("evictedTurns must not be negative");
        }
        Objects.requireNonNull(metrics, "metrics");
    }

    public List<Turn> turnsBySpeaker(String speaker) {
        return turns.stream().filter(turn -> turn.speaker().equals(speaker)).toList();
    }
}
