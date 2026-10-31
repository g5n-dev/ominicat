package io.ominicat.conversation;

import static org.junit.jupiter.api.Assertions.*;

import io.ominicat.core.Frame;
import io.ominicat.conversation.flow.StepFlowProcessor;
import java.util.List;
import org.junit.jupiter.api.Test;

class StateAtomicityTest {
    @Test void revisionOverflowLeavesStepAndDedupStateUntouched() throws Exception {
        var flow = new StepFlowProcessor("v1", "a", "user", List.of(
                new StepFlowProcessor.Step("a", "", List.of(new StepFlowProcessor.Transition("b", t -> true))),
                new StepFlowProcessor.Step("b", "", List.of())), 4);
        flow.restore(new StepFlowProcessor.Snapshot("v1", "a", Long.MAX_VALUE, true, List.of("prior")));
        var before = flow.snapshot();
        assertThrows(ArithmeticException.class, () -> flow.process(new Frame.Transcript("x", "user", 0, 1,
                true, -1, "next", 0, "user"), ignored -> {}));
        assertEquals(before, flow.snapshot());
    }

    @Test void evictionOverflowLeavesCommittedWindowRestorable() {
        var window = new ConversationWindow(1);
        window.add(new Frame.Transcript("old", "user", 0, 1, true, -1));
        var initial = window.snapshot();
        window.restore(new ConversationSnapshot(initial.turns(), Long.MAX_VALUE, initial.metrics()));
        var before = window.snapshot();
        assertThrows(ArithmeticException.class,
                () -> window.add(new Frame.Transcript("next", "user", 1, 2, true, -1)));
        assertEquals(before, window.snapshot());
    }
}
