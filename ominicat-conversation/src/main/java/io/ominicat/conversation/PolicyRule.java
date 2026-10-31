package io.ominicat.conversation;

/** Extension point for deterministic or model-backed policies over immutable conversation data. */
@FunctionalInterface
public interface PolicyRule {
    RuleResult evaluate(ConversationSnapshot conversation, String targetSpeaker) throws Exception;
}
