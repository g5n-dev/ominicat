package io.ominicat.conversation;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small, deterministic policies. Literal matching is case-insensitive and speaker-specific. */
public final class PolicyRules {
    private static final int MAX_EVIDENCE = 8;

    private PolicyRules() {}

    public static PolicyRule requiredPhrase(String ruleId, String phrase) {
        return phraseRule(ruleId, phrase, true);
    }

    public static PolicyRule prohibitedPhrase(String ruleId, String phrase) {
        return phraseRule(ruleId, phrase, false);
    }

    private static PolicyRule phraseRule(String ruleId, String phrase, boolean required) {
        validateId(ruleId);
        if (phrase == null || phrase.isBlank()) {
            throw new IllegalArgumentException("phrase must not be blank");
        }
        Pattern pattern = Pattern.compile(Pattern.quote(phrase), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        return (snapshot, speaker) -> {
            List<Turn> turns = snapshot.turnsBySpeaker(speaker);
            if (turns.isEmpty()) {
                return notApplicable(ruleId);
            }
            List<Evidence> matches = new ArrayList<>();
            for (Turn turn : turns) {
                Matcher matcher = pattern.matcher(turn.text());
                if (matcher.find() && matches.size() < MAX_EVIDENCE) {
                    matches.add(Evidence.around(turn, matcher.start()));
                }
            }
            boolean found = !matches.isEmpty();
            boolean passed = required == found;
            String explanation = (required ? "Required" : "Prohibited") + " phrase "
                    + (found ? "was found" : "was not found") + " in retained turns: " + phrase;
            return new RuleResult(ruleId, passed ? RuleResult.Status.PASS : RuleResult.Status.FAIL,
                    passed ? 1.0 : 0.0, explanation, matches);
        };
    }

    /** Counts turns containing '?' or '？'; this is a textual heuristic, not semantic intent detection. */
    public static PolicyRule minimumQuestionRatio(String ruleId, double minimumRatio) {
        validateId(ruleId);
        if (!Double.isFinite(minimumRatio) || minimumRatio < 0 || minimumRatio > 1) {
            throw new IllegalArgumentException("minimumRatio must be between 0 and 1");
        }
        return (snapshot, speaker) -> {
            List<Turn> turns = snapshot.turnsBySpeaker(speaker);
            if (turns.isEmpty()) {
                return notApplicable(ruleId);
            }
            List<Turn> questions = turns.stream()
                    .filter(turn -> turn.text().contains("?") || turn.text().contains("？")).toList();
            double ratio = (double) questions.size() / turns.size();
            boolean passed = ratio >= minimumRatio;
            List<Evidence> evidence = questions.stream().limit(MAX_EVIDENCE)
                    .map(turn -> Evidence.around(turn, Math.max(turn.text().indexOf('?'), turn.text().indexOf('？'))))
                    .toList();
            return new RuleResult(ruleId, passed ? RuleResult.Status.PASS : RuleResult.Status.FAIL,
                    minimumRatio == 0 ? 1.0 : Math.min(1.0, ratio / minimumRatio),
                    "Question-mark turn ratio " + ratio + "; minimum " + minimumRatio, evidence);
        };
    }

    private static RuleResult notApplicable(String ruleId) {
        return new RuleResult(ruleId, RuleResult.Status.NOT_APPLICABLE, null,
                "No retained turns for the target speaker", List.of());
    }

    private static void validateId(String ruleId) {
        if (ruleId == null || ruleId.isBlank()) {
            throw new IllegalArgumentException("ruleId must not be blank");
        }
    }
}
