package io.ominicat.conversation;

import io.ominicat.core.Frame;
import java.util.List;
import java.util.Objects;

/** Structured policy output; score is the mean of applicable rule scores, or null. */
public record EvaluationFrame(String policyVersion, String targetSpeaker, Double score, List<RuleResult> results,
                              ConversationMetrics metrics, int retainedTurns, long evictedTurns)
        implements Frame {
    public EvaluationFrame {
        if (policyVersion == null || policyVersion.isBlank() || policyVersion.length() > 128) {
            throw new IllegalArgumentException("policyVersion must contain 1..128 characters");
        }
        if (targetSpeaker == null || targetSpeaker.isBlank()) {
            throw new IllegalArgumentException("targetSpeaker must not be blank");
        }
        if (score != null && (!Double.isFinite(score) || score < 0 || score > 1)) {
            throw new IllegalArgumentException("score must be between 0 and 1");
        }
        results = List.copyOf(results);
        Objects.requireNonNull(metrics, "metrics");
        if (retainedTurns < 0 || evictedTurns < 0) {
            throw new IllegalArgumentException("turn counts must not be negative");
        }
    }

    public EvaluationFrame(String targetSpeaker, Double score, List<RuleResult> results,
                           ConversationMetrics metrics, int retainedTurns, long evictedTurns) {
        this("1", targetSpeaker, score, results, metrics, retainedTurns, evictedTurns);
    }
}
