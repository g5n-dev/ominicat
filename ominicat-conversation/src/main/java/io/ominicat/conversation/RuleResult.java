package io.ominicat.conversation;

import java.util.List;
import java.util.Objects;

/** A normalized policy result. Non-applicable rules have no numeric score. */
public record RuleResult(String ruleId, Status status, Double score, String explanation,
                         List<Evidence> evidence) {
    public enum Status { PASS, FAIL, NOT_APPLICABLE }

    public RuleResult {
        if (ruleId == null || ruleId.isBlank()) {
            throw new IllegalArgumentException("ruleId must not be blank");
        }
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(explanation, "explanation");
        evidence = List.copyOf(evidence);
        if (status == Status.NOT_APPLICABLE ? score != null
                : score == null || !Double.isFinite(score) || score < 0 || score > 1) {
            throw new IllegalArgumentException("score must be in [0,1], or null for NOT_APPLICABLE");
        }
    }
}
