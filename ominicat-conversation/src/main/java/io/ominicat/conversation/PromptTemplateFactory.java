package io.ominicat.conversation;

import io.ominicat.core.Processor;
import io.ominicat.core.spi.ProcessorFactory;
import java.util.Map;
import java.util.Set;

/** Configures role-aware prompt composition without coupling the runtime to a model service. */
public final class PromptTemplateFactory implements ProcessorFactory {
    private static final Set<String> OPTIONS = Set.of("inputSpeaker", "template", "role", "context",
            "instruction", "maxTurns", "maxTextChars", "maxPromptChars");

    @Override
    public String type() {
        return "prompt-template";
    }

    @Override
    public Processor create(Map<String, String> options) {
        PluginOptions.checkKeys(options, OPTIONS);
        return new PromptTemplateProcessor(options.getOrDefault("inputSpeaker", "user"),
                options.getOrDefault("template", PromptTemplateProcessor.DEFAULT_TEMPLATE),
                options.getOrDefault("role", "assistant"), options.getOrDefault("context", ""),
                options.getOrDefault("instruction", "Respond to the latest turn."),
                PluginOptions.integer(options, "maxTurns", 32),
                PluginOptions.integer(options, "maxTextChars", ConversationWindow.DEFAULT_MAX_TEXT_CHARS),
                PluginOptions.integer(options, "maxPromptChars", 65_536));
    }
}
