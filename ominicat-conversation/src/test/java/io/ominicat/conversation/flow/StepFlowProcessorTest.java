package io.ominicat.conversation.flow;

import io.ominicat.core.Frame;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StepFlowProcessorTest {
    @Test void configuredProgressionDedupAndSnapshotContinuation() throws Exception {
        var factory = new StepFlowFactory();
        var options = Map.of("steps", "a,b,c", "step.a.prompt", "First", "step.b.prompt", "Second");
        var first = (StepFlowProcessor) factory.create(options);
        var output = new ArrayList<Frame>();
        first.process(new Frame.Control(Frame.Signal.START), output::add);
        first.process(new Frame.Control(Frame.Signal.START), output::add);
        assertEquals(2, output.size());
        var input = new Frame.Transcript("answer", "person", 0, 1, true, -1, "seg1", 0, "user");
        first.process(input, output::add);
        assertEquals("b", first.snapshot().currentStep());
        var restored = (StepFlowProcessor) factory.create(options);
        restored.restore(first.snapshot());
        restored.process(input, output::add);
        assertEquals("b", restored.snapshot().currentStep());
        restored.process(new Frame.Transcript("next", "person", 1, 2, true, -1, "seg2", 0, "user"), output::add);
        assertEquals("c", restored.snapshot().currentStep());
        assertTrue(((StepFlowProcessor.StepEvent) output.getLast()).terminal());
    }
    @Test void conditionalRoutesAndPartialRoleBoundaries() throws Exception {
        var flow = new StepFlowProcessor("v1", "a", "user", List.of(
                new StepFlowProcessor.Step("a", "", List.of(new StepFlowProcessor.Transition("b", t -> t.text().equals("yes")))),
                new StepFlowProcessor.Step("b", "", List.of())), 2);
        var out = new ArrayList<Frame>();
        flow.process(new Frame.Control(Frame.Signal.START), out::add);
        flow.process(new Frame.Transcript("yes", "user", 0, 1, false, -1), out::add);
        assertEquals("a", flow.snapshot().currentStep());
        flow.process(new Frame.Transcript("yes", "user", 0, 1, true, -1), out::add);
        assertEquals("b", flow.snapshot().currentStep());
    }
    @Test void invalidDefinitionAndRestoreAreAtomic() {
        assertThrows(IllegalArgumentException.class, () -> new StepFlowFactory().create(Map.of("steps", "a,a")));
        assertThrows(IllegalArgumentException.class, () -> new StepFlowFactory().create(Map.of("typo", "x")));
        var flow = (StepFlowProcessor) new StepFlowFactory().create(Map.of());
        var before = flow.snapshot();
        assertThrows(IllegalArgumentException.class, () -> flow.restore(new StepFlowProcessor.Snapshot("wrong", "end", 1, true, List.of())));
        assertEquals(before, flow.snapshot());
    }
    @Test void acceptedSnapshotsRemainRestorableAndIdsAreBounded() throws Exception {
        var flow = (StepFlowProcessor) new StepFlowFactory().create(Map.of());
        flow.process(new Frame.Control(Frame.Signal.START), f -> { });
        var before = flow.snapshot();
        var invalid = new Frame.Transcript("x", "user", 0, 1, true, -1, "x".repeat(257), 0, "user");
        assertThrows(IllegalArgumentException.class, () -> flow.process(invalid, f -> { }));
        assertEquals(before, flow.snapshot());
        flow.restore(flow.snapshot());
        assertEquals(before, flow.snapshot());
    }
}
