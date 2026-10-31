package io.ominicat.conversation;

import io.ominicat.core.Processor;
import io.ominicat.core.spi.ProcessorFactory;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Configurable factory for literal policies over speaker-attributed transcripts. */
public final class ConversationEvaluatorFactory implements ProcessorFactory {
    private static final Set<String> OPTIONS = Set.of("speaker", "requiredPhrases", "prohibitedPhrases",
            "minimumQuestionRatio", "maxTurns", "maxTextChars", "evaluateOnTurnEnd", "policyVersion");

    @Override
    public String type() {
        return "conversation-evaluator";
    }

    @Override
    public Processor create(Map<String, String> options) {
        PluginOptions.checkKeys(options, OPTIONS);
        List<PolicyRule> rules = new ArrayList<>();
        int index = 1;
        for (String phrase : phrases(options.getOrDefault("requiredPhrases", ""))) {
            rules.add(PolicyRules.requiredPhrase("required-phrase-" + index++, phrase));
        }
        index = 1;
        for (String phrase : phrases(options.getOrDefault("prohibitedPhrases", ""))) {
            rules.add(PolicyRules.prohibitedPhrase("prohibited-phrase-" + index++, phrase));
        }
        if (options.containsKey("minimumQuestionRatio")) {
            try {
                rules.add(PolicyRules.minimumQuestionRatio("minimum-question-ratio",
                        Double.parseDouble(options.get("minimumQuestionRatio"))));
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("minimumQuestionRatio must be a number in [0,1]", error);
            }
        }
        return new ConversationEvaluatorProcessor(options.getOrDefault("speaker", "agent"), rules,
                PluginOptions.integer(options, "maxTurns", ConversationWindow.DEFAULT_MAX_TURNS),
                PluginOptions.integer(options, "maxTextChars", ConversationWindow.DEFAULT_MAX_TEXT_CHARS),
                PluginOptions.bool(options, "evaluateOnTurnEnd", false),
                options.getOrDefault("policyVersion", "1"));
    }

    private static List<String> phrases(String value) {
        Set<String> phrases = new LinkedHashSet<>();
        for (String part : value.split(",")) {
            if (!part.isBlank()) {
                phrases.add(part.strip());
            }
        }
        if (phrases.size() > 64) {
            throw new IllegalArgumentException("at most 64 phrases may be configured per option");
        }
        return List.copyOf(phrases);
    }
}
