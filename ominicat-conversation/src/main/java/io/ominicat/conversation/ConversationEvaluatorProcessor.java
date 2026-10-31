package io.ominicat.conversation;

import io.ominicat.core.Frame;
import io.ominicat.core.FrameSink;
import io.ominicat.core.Processor;
import java.util.ArrayList;
import java.util.List;

/** Collects final transcripts and emits a policy evaluation before an END boundary. */
public final class ConversationEvaluatorProcessor implements Processor {
    private final ConversationWindow window;
    private final String targetSpeaker;
    private final List<PolicyRule> rules;
    private final boolean evaluateOnTurnEnd;
    private final String policyVersion;

    public ConversationEvaluatorProcessor(String targetSpeaker, List<PolicyRule> rules, int maxTurns,
                                           int maxTextChars, boolean evaluateOnTurnEnd, String policyVersion) {
        if (targetSpeaker == null || targetSpeaker.isBlank()) {
            throw new IllegalArgumentException("targetSpeaker must not be blank");
        }
        if (policyVersion == null || policyVersion.isBlank() || policyVersion.length() > 128) {
            throw new IllegalArgumentException("policyVersion must contain 1..128 characters");
        }
        this.targetSpeaker = targetSpeaker;
        this.rules = List.copyOf(rules);
        this.window = new ConversationWindow(maxTurns, maxTextChars);
        this.evaluateOnTurnEnd = evaluateOnTurnEnd;
        this.policyVersion = policyVersion;
    }

    public ConversationEvaluatorProcessor(String targetSpeaker, List<PolicyRule> rules, int maxTurns,
                                           int maxTextChars, boolean evaluateOnTurnEnd) {
        this(targetSpeaker, rules, maxTurns, maxTextChars, evaluateOnTurnEnd, "1");
    }

    public ConversationEvaluatorProcessor(String targetSpeaker, List<PolicyRule> rules, int maxTurns) {
        this(targetSpeaker, rules, maxTurns, ConversationWindow.DEFAULT_MAX_TEXT_CHARS, false);
    }

    @Override
    public void process(Frame frame, FrameSink next) throws Exception {
        if (frame instanceof Frame.Transcript transcript) {
            window.add(transcript);
        } else if (frame instanceof Frame.Control control
                && (control.signal() == Frame.Signal.END
                || evaluateOnTurnEnd && control.signal() == Frame.Signal.TURN_END)) {
            next.accept(evaluate());
        }
        next.accept(frame);
    }

    public EvaluationFrame evaluate() throws Exception {
        ConversationSnapshot snapshot = window.snapshot();
        List<RuleResult> results = new ArrayList<>(rules.size());
        double scoreSum = 0;
        int applicable = 0;
        for (PolicyRule rule : rules) {
            RuleResult result = rule.evaluate(snapshot, targetSpeaker);
            results.add(result);
            if (result.score() != null) {
                scoreSum += result.score();
                applicable++;
            }
        }
        return new EvaluationFrame(policyVersion, targetSpeaker, applicable == 0 ? null : scoreSum / applicable,
                results, snapshot.metrics(), snapshot.turns().size(), snapshot.evictedTurns());
    }

    public ConversationSnapshot snapshot() {
        return window.snapshot();
    }

    /** Restore a snapshot before processing resumes; callers must serialize access. */
    public void restore(ConversationSnapshot snapshot) {
        window.restore(snapshot);
    }
}
