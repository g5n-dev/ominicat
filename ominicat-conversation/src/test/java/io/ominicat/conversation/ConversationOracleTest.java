package io.ominicat.conversation;

import static org.junit.jupiter.api.Assertions.*;

import io.ominicat.core.Frame;
import io.ominicat.conversation.flow.StepFlowFactory;
import io.ominicat.conversation.flow.StepFlowProcessor;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Discrete-time occupancy is an independent oracle for interval merging. */
class ConversationOracleTest {
    @Test void seededWindowsAgreeWithOccupancyOracleAndSnapshotReplay() {
        for (long seed = 0x51a7; seed < 0x51a7 + 64; seed++) {
            Random random = new Random(seed);
            int capacity = 1 + random.nextInt(32);
            ConversationWindow window = new ConversationWindow(capacity);
            List<Frame.Transcript> retained = new ArrayList<>();
            for (int i = 0; i < 500; i++) {
                int start = random.nextInt(1000), end = start + random.nextInt(101);
                Frame.Transcript turn = new Frame.Transcript("turn-" + i, "s" + random.nextInt(3), start, end,
                        true, -1, "segment-" + i, 0, "participant");
                window.add(turn);
                retained.add(turn);
                if (retained.size() > capacity) retained.remove(0);
                if (i % 10 != 0) continue;
                var snapshot = window.snapshot();
                assertEquals(Math.max(0, i + 1 - capacity), snapshot.evictedTurns(), "seed=" + seed);
                BitSet all = new BitSet();
                Map<String, BitSet> speakers = new LinkedHashMap<>();
                long first = Long.MAX_VALUE, last = 0;
                for (Frame.Transcript input : retained) {
                    all.set((int) input.startMillis(), (int) input.endMillis());
                    speakers.computeIfAbsent(input.speaker(), ignored -> new BitSet())
                            .set((int) input.startMillis(), (int) input.endMillis());
                    first = Math.min(first, input.startMillis()); last = Math.max(last, input.endMillis());
                }
                assertEquals(all.cardinality(), snapshot.metrics().speechMillis(), "seed=" + seed + ", event=" + i);
                assertEquals(last - first - all.cardinality(), snapshot.metrics().silenceMillis());
                speakers.forEach((speaker, bits) -> assertEquals((long) bits.cardinality(), snapshot.metrics().speakerSpeechMillis().get(speaker)));
                assertEquals(snapshot, new ConversationWindow(capacity, 8192, snapshot).snapshot());
                assertFalse(window.add(turn));
            }
        }
    }

    @Test void corruptSnapshotsAndThrowingPredicatesLeaveFlowUntouched() throws Exception {
        var flow = (StepFlowProcessor) new StepFlowFactory().create(Map.of("steps", "a,b"));
        var before = flow.snapshot();
        List<StepFlowProcessor.Snapshot> corrupt = List.of(
                new StepFlowProcessor.Snapshot("1", "b", 1, false, List.of("id")),
                new StepFlowProcessor.Snapshot("1", "b", 0, true, List.of()),
                new StepFlowProcessor.Snapshot("1", "a", 0, false, List.of("id")),
                new StepFlowProcessor.Snapshot("1", "a", 1, true, List.of("same", "same")));
        for (var snapshot : corrupt) {
            assertThrows(IllegalArgumentException.class, () -> flow.restore(snapshot));
            assertEquals(before, flow.snapshot());
        }
        var throwing = new StepFlowProcessor("v1", "a", "user", List.of(
                new StepFlowProcessor.Step("a", "", List.of(new StepFlowProcessor.Transition("a", t -> { throw new IllegalStateException("policy unavailable"); })))), 2);
        throwing.process(new Frame.Control(Frame.Signal.START), ignored -> {});
        var initial = throwing.snapshot();
        assertThrows(IllegalStateException.class, () -> throwing.process(new Frame.Transcript("x", "user", 0, 1, true, -1), ignored -> {}));
        assertEquals(initial, throwing.snapshot());
    }

    @Test void policyEvidenceAlwaysIdentifiesOriginalCommittedSegment() throws Exception {
        Random random = new Random(0xeff1de);
        for (int i = 0; i < 1000; i++) {
            String prefix = "x".repeat(random.nextInt(1000));
            var window = new ConversationWindow(2);
            window.add(new Frame.Transcript(prefix + "needle" + "y".repeat(random.nextInt(1000)), "track", i, i + 1,
                    true, -1, "segment-" + i, 3, "participant"));
            var result = PolicyRules.requiredPhrase("needle", "needle").evaluate(window.snapshot(), "track");
            assertEquals(1.0, result.score());
            Evidence evidence = result.evidence().get(0);
            assertEquals("segment-" + i, evidence.segmentId());
            assertEquals("participant", evidence.role());
            assertEquals(i, evidence.startMillis());
            assertTrue(evidence.excerpt().contains("needle"));
            assertTrue(evidence.excerpt().length() <= 182);
        }
    }
}
