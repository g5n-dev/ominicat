package io.ominicat.providers;

import static org.junit.jupiter.api.Assertions.*;

import io.ominicat.core.Frame;
import io.ominicat.core.Processor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TranscriptGateTest {
    @Test
    void suppressesPartialsAndCommitsOnlyOneFinalPerRetainedSegment() throws Exception {
        Processor gate = new TranscriptGateFactory().create(Map.of());
        List<Frame> output = new ArrayList<>();
        gate.process(transcript("s1", 0, "h", false), output::add);
        gate.process(transcript("s1", 1, "hello", false), output::add);
        gate.process(transcript("s1", 2, "hello!", true), output::add);
        gate.process(transcript("s1", 2, "hello!", true), output::add);
        gate.process(transcript("s1", 3, "hello!", true), output::add);
        assertEquals(List.of(transcript("s1", 2, "hello!", true)), output);
    }

    @Test
    void optionalPartialsRemainOrderedAndStaleRevisionsAreIgnored() throws Exception {
        Processor gate = new TranscriptGateFactory().create(Map.of("passPartials", "true"));
        List<Frame> output = new ArrayList<>();
        gate.process(transcript("s1", 2, "latest", false), output::add);
        gate.process(transcript("s1", 1, "old", false), output::add);
        gate.process(transcript("s1", 2, "latest", false), output::add);
        gate.process(transcript("s1", 2, "latest", true), output::add);
        gate.process(transcript("s1", 1, "old", false), output::add);
        assertEquals(List.of(transcript("s1", 2, "latest", false), transcript("s1", 2, "latest", true)), output);
    }

    @Test
    void rejectsConflictingFinalsAndRevisionReuse() throws Exception {
        Processor gate = new TranscriptGateFactory().create(Map.of());
        gate.process(transcript("s1", 0, "partial", false), ignored -> {});
        assertThrows(IllegalArgumentException.class,
                () -> gate.process(transcript("s1", 0, "changed", false), ignored -> {}));
        gate.process(transcript("s1", 1, "final", true), ignored -> {});
        assertThrows(IllegalArgumentException.class,
                () -> gate.process(transcript("s1", 2, "changed", true), ignored -> {}));
        assertThrows(IllegalArgumentException.class,
                () -> gate.process(transcript("s1", 2, "partial again", false), ignored -> {}));
    }

    @Test
    void preservesSpeakerAndRoleIdentityAcrossRevisions() throws Exception {
        Processor gate = new TranscriptGateFactory().create(Map.of());
        gate.process(transcript("s1", 0, "partial", false), ignored -> {});
        assertThrows(IllegalArgumentException.class, () -> gate.process(new Frame.Transcript("partial", "other",
                0, 10, false, -1, "s1", 1, "participant"), ignored -> {}));
        assertThrows(IllegalArgumentException.class, () -> gate.process(new Frame.Transcript("partial", "person-a",
                0, 10, false, -1, "s1", 1, "assistant"), ignored -> {}));
    }

    @Test
    void boundedRetentionAllowsReplayAfterEviction() throws Exception {
        Processor gate = new TranscriptGateFactory().create(Map.of("maxSegments", "1"));
        List<Frame> output = new ArrayList<>();
        gate.process(transcript("s1", 0, "one", true), output::add);
        gate.process(transcript("s2", 0, "two", true), output::add);
        gate.process(transcript("s1", 0, "one", true), output::add);
        assertEquals(3, output.size());
    }

    @Test
    void interruptionDropsUncommittedRevisionsAndRetainsFinalDeduplication() throws Exception {
        Processor gate = new TranscriptGateFactory().create(Map.of());
        List<Frame> output = new ArrayList<>();
        gate.process(transcript("s1", 3, "partial", false), output::add);
        gate.process(transcript("s2", 1, "committed", true), output::add);
        gate.onInterrupt();
        gate.process(transcript("s1", 0, "restart", true), output::add);
        gate.process(transcript("s2", 1, "committed", true), output::add);
        assertEquals(2, output.size());
    }

    @Test
    void controlFramesPassThroughAndDirectInterruptClearsPartials() throws Exception {
        Processor gate = new TranscriptGateFactory().create(Map.of());
        List<Frame> output = new ArrayList<>();
        gate.process(transcript("s1", 3, "partial", false), output::add);
        gate.process(new Frame.Control(Frame.Signal.INTERRUPT), output::add);
        gate.process(transcript("s1", 0, "fresh", true), output::add);
        gate.process(new Frame.Text("other"), output::add);
        assertEquals(new Frame.Control(Frame.Signal.INTERRUPT), output.get(0));
        assertEquals(new Frame.Text("other"), output.get(output.size() - 1));
        assertEquals(3, output.size());
    }

    @Test
    void limitsAndConfigurationAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> new TranscriptGateFactory().create(Map.of("maxSegments", "0")));
        assertThrows(IllegalArgumentException.class, () -> new TranscriptGateFactory().create(Map.of("passPartials", "yes")));
        assertThrows(IllegalArgumentException.class, () -> new TranscriptGateFactory().create(Map.of("unknown", "x")));
        Processor gate = new TranscriptGateFactory().create(Map.of("maxTextChars", "2"));
        assertThrows(IllegalArgumentException.class,
                () -> gate.process(transcript("s1", 0, "long", true), ignored -> {}));
        assertThrows(IllegalArgumentException.class,
                () -> gate.process(transcript("s".repeat(257), 0, "x", true), ignored -> {}));
    }

    private static Frame.Transcript transcript(String segment, long revision, String text, boolean finalResult) {
        return new Frame.Transcript(text, "person-a", 0, 10, finalResult, -1, segment, revision, "participant");
    }
}
