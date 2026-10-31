package io.ominicat.conversation;

import static io.ominicat.conversation.ConversationWindowTest.finalTurn;
import static org.junit.jupiter.api.Assertions.*;

import io.ominicat.core.Frame;
import io.ominicat.core.Processor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PromptTemplateTest {
    @Test
    void composesRoleAwarePromptAndConsumesFinalInputTranscript() throws Exception {
        Processor processor = new PromptTemplateFactory().create(Map.of("role", "assistant",
                "context", "Review a shared document", "instruction", "Ask one clear question"));
        List<Frame> output = new ArrayList<>();
        processor.process(finalTurn("Please continue.", "agent", 0, 10), output::add);
        processor.process(finalTurn("What changed?", "user", 10, 20), output::add);
        assertInstanceOf(Frame.Transcript.class, output.getFirst());
        String prompt = assertInstanceOf(Frame.Text.class, output.get(1)).text();
        assertTrue(prompt.contains("Role: assistant"));
        assertTrue(prompt.contains("Context: Review a shared document"));
        assertTrue(prompt.contains("Instruction: Ask one clear question"));
        assertTrue(prompt.contains("agent: Please continue.\nuser: What changed?"));
        assertEquals(2, output.size());
    }

    @Test
    void substitutesOnceAndPreservesDollarBackslashAndUserPlaceholders() throws Exception {
        Processor processor = new PromptTemplateFactory().create(Map.of("template",
                "{{role}}|{{context}}|{{instruction}}|{{speaker}}|{{transcript}}", "role", "system"));
        List<Frame> output = new ArrayList<>();
        processor.process(finalTurn("$1\\{{role}}", "user", 0, 1), output::add);
        assertEquals("system||Respond to the latest turn.|user|$1\\{{role}}",
                assertInstanceOf(Frame.Text.class, output.getFirst()).text());
    }

    @Test
    void excludesPartialsFromHistoryAndSuppressesDuplicatePrompts() throws Exception {
        Processor processor = new PromptTemplateFactory().create(Map.of("template", "{{history}}"));
        List<Frame> output = new ArrayList<>();
        processor.process(new Frame.Transcript("par", "user", 0, 1, false, .5), output::add);
        Frame.Transcript complete = finalTurn("complete", "user", 0, 10);
        processor.process(complete, output::add);
        processor.process(complete, output::add);
        assertEquals(2, output.size());
        assertInstanceOf(Frame.Transcript.class, output.getFirst());
        assertEquals("user: complete", assertInstanceOf(Frame.Text.class, output.get(1)).text());
    }

    @Test
    void retainsConfiguredWindowAndTrimsOldestHistoryToPromptLimit() throws Exception {
        Processor processor = new PromptTemplateFactory().create(Map.of("template", "{{history}}",
                "maxTurns", "2", "maxPromptChars", "12"));
        List<Frame> output = new ArrayList<>();
        processor.process(finalTurn("old", "agent", 0, 1), output::add);
        processor.process(finalTurn("middle", "agent", 1, 2), output::add);
        processor.process(finalTurn("new", "user", 2, 3), output::add);
        assertEquals("user: new", assertInstanceOf(Frame.Text.class, output.get(2)).text());
    }

    @Test
    void duplicatedHistoryPlaceholderStillHonorsPromptLimit() throws Exception {
        Processor processor = new PromptTemplateFactory().create(Map.of("template", "{{history}}|{{history}}",
                "maxPromptChars", "23"));
        List<Frame> output = new ArrayList<>();
        processor.process(finalTurn("old", "agent", 0, 1), output::add);
        processor.process(finalTurn("new", "user", 1, 2), output::add);
        assertEquals("user: new|user: new", assertInstanceOf(Frame.Text.class, output.get(1)).text());
    }

    @Test
    void configurableInputSpeakerAndOtherFramesPassThrough() throws Exception {
        Processor processor = new PromptTemplateFactory().create(Map.of("inputSpeaker", "participant",
                "template", "{{speaker}}={{transcript}}"));
        List<Frame> output = new ArrayList<>();
        processor.process(new Frame.Text("direct"), output::add);
        processor.process(new Frame.Control(Frame.Signal.TURN_END), output::add);
        processor.process(finalTurn("hi", "user", 0, 1), output::add);
        processor.process(finalTurn("hello", "participant", 1, 2), output::add);
        assertEquals(4, output.size());
        assertEquals("participant=hello", assertInstanceOf(Frame.Text.class, output.get(3)).text());
    }

    @Test
    void rejectsOversizedLatestTurnAndOversizedNonHistoryContent() {
        Processor history = new PromptTemplateFactory().create(Map.of("template", "{{history}}", "maxPromptChars", "3"));
        assertThrows(IllegalArgumentException.class,
                () -> history.process(finalTurn("x", "user", 0, 1), ignored -> {}));
        Processor direct = new PromptTemplateFactory().create(Map.of("template", "{{transcript}}", "maxPromptChars", "3"));
        assertThrows(IllegalArgumentException.class,
                () -> direct.process(finalTurn("long", "user", 0, 1), ignored -> {}));
    }

    @Test
    void invalidConfigurationFailsBeforeSessionStarts() {
        PromptTemplateFactory factory = new PromptTemplateFactory();
        assertThrows(IllegalArgumentException.class, () -> factory.create(Map.of("unknown", "x")));
        assertThrows(IllegalArgumentException.class, () -> factory.create(Map.of("template", "{{typo}}")));
        assertThrows(IllegalArgumentException.class, () -> factory.create(Map.of("template", " ")));
        assertThrows(IllegalArgumentException.class, () -> factory.create(Map.of("inputSpeaker", " ")));
        assertThrows(IllegalArgumentException.class, () -> factory.create(Map.of("maxPromptChars", "0")));
        assertThrows(IllegalArgumentException.class, () -> factory.create(Map.of("maxPromptChars", "1048577")));
        assertThrows(IllegalArgumentException.class, () -> factory.create(Map.of("maxTextChars", "0")));
    }

    @Test
    void interruptionClearsPromptHistoryAndAllowsSameTranscriptInNewSegment() throws Exception {
        Processor processor = new PromptTemplateFactory().create(Map.of("template", "{{history}}"));
        List<Frame> output = new ArrayList<>();
        Frame.Transcript first = finalTurn("hello", "user", 0, 1);
        processor.process(first, output::add);
        processor.onInterrupt();
        processor.process(first, output::add);
        processor.process(finalTurn("next", "user", 1, 2), output::add);
        assertEquals(3, output.size());
        assertEquals("user: hello", assertInstanceOf(Frame.Text.class, output.get(1)).text());
        assertEquals("user: hello\nuser: next", assertInstanceOf(Frame.Text.class, output.get(2)).text());
    }
}
