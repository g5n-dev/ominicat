package io.ominicat.conversation;

import io.ominicat.core.Frame;
import io.ominicat.core.FrameSink;
import io.ominicat.core.Processor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Converts final input-speaker transcripts into bounded, configurable model prompts. */
public final class PromptTemplateProcessor implements Processor {
    public static final String DEFAULT_TEMPLATE = """
            Role: {{role}}
            Context: {{context}}
            Instruction: {{instruction}}
            Conversation transcript (speaker: text):
            {{history}}
            Continue the conversation after the latest {{speaker}} turn.
            """;

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Za-z][A-Za-z0-9_]*)}}");
    private static final Set<String> VARIABLES = Set.of("role", "context", "instruction", "transcript", "speaker", "history");
    private final ConversationWindow window;
    private final String inputSpeaker;
    private final String template;
    private final String role;
    private final String context;
    private final String instruction;
    private final int maxPromptChars;

    public PromptTemplateProcessor(String inputSpeaker, String template, String role, String context,
                                   String instruction, int maxTurns, int maxTextChars, int maxPromptChars) {
        if (inputSpeaker == null || inputSpeaker.isBlank()) {
            throw new IllegalArgumentException("inputSpeaker must not be blank");
        }
        if (template == null || template.isBlank()) {
            throw new IllegalArgumentException("template must not be blank");
        }
        if (maxPromptChars < 1 || maxPromptChars > 1_048_576) {
            throw new IllegalArgumentException("maxPromptChars must be between 1 and 1048576");
        }
        if (role == null || context == null || instruction == null) {
            throw new IllegalArgumentException("role, context and instruction must not be null");
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            if (!VARIABLES.contains(matcher.group(1))) {
                throw new IllegalArgumentException("unknown template variable: " + matcher.group(1));
            }
        }
        this.window = new ConversationWindow(maxTurns, maxTextChars);
        this.inputSpeaker = inputSpeaker;
        this.template = template;
        this.role = role;
        this.context = context;
        this.instruction = instruction;
        this.maxPromptChars = maxPromptChars;
    }

    @Override
    public void process(Frame frame, FrameSink next) throws Exception {
        if (frame instanceof Frame.Transcript transcript) {
            boolean added = window.add(transcript);
            if (transcript.finalResult() && transcript.speaker().equals(inputSpeaker)) {
                if (added) {
                    next.accept(new Frame.Text(render(transcript)));
                }
                return;
            }
        }
        next.accept(frame);
    }

    /** Called by the serialized session worker at an interruption boundary. */
    @Override
    public void onInterrupt() {
        window.reset();
    }

    private String render(Frame.Transcript transcript) {
        List<Turn> turns = window.snapshot().turns();
        StringBuilder history = new StringBuilder();
        List<Integer> lineStarts = new ArrayList<>(turns.size());
        for (Turn turn : turns) {
            if (!history.isEmpty()) {
                history.append('\n');
            }
            lineStarts.add(history.length());
            history.append(turn.speaker()).append(": ").append(turn.text());
        }
        Map<String, String> variables = Map.of("role", role, "context", context,
                "instruction", instruction, "transcript", transcript.text(), "speaker", transcript.speaker(),
                "history", "");
        String base = substitute(variables);
        int historyOccurrences = 0;
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            if (matcher.group(1).equals("history")) {
                historyOccurrences++;
            }
        }
        if (base.length() > maxPromptChars) {
            throw new IllegalArgumentException("prompt exceeds maxPromptChars even without history");
        }
        int start = 0;
        if (historyOccurrences > 0) {
            int historyBudget = (maxPromptChars - base.length()) / historyOccurrences;
            for (int lineStart : lineStarts) {
                start = lineStart;
                if (history.length() - start <= historyBudget) {
                    break;
                }
            }
            if (history.length() - start > historyBudget) {
                throw new IllegalArgumentException("latest turn exceeds maxPromptChars");
            }
        }
        Map<String, String> values = Map.of("role", role, "context", context,
                "instruction", instruction, "transcript", transcript.text(), "speaker", transcript.speaker(),
                "history", history.substring(start));
        return substitute(values);
    }

    /** Single-pass substitution never interprets placeholders supplied by transcript content. */
    private String substitute(Map<String, String> variables) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(variables.get(matcher.group(1))));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
