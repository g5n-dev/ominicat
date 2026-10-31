package io.ominicat.providers;

import static org.junit.jupiter.api.Assertions.*;

import io.ominicat.core.Frame;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class TranscriptGateOracleTest {
    @Test void shuffledPartialRevisionsNeverProduceDuplicateCommittedFinals() throws Exception {
        for (long seed = 0x6a7e; seed < 0x6a7e + 32; seed++) {
            Random random = new Random(seed);
            var gate = new TranscriptGateFactory().create(Map.of("maxSegments", "2048"));
            List<Frame> output = new ArrayList<>();
            for (int id = 0; id < 1000; id++) {
                List<Integer> revisions = new ArrayList<>(List.of(0, 1, 2, 3, 4, 5));
                Collections.shuffle(revisions, random);
                for (int revision : revisions) gate.process(turn(id, revision, "partial-" + revision, false), output::add);
                Frame.Transcript committed = turn(id, 6, "final-" + id, true);
                gate.process(committed, output::add);
                gate.process(committed, output::add);
                gate.process(turn(id, 2, "partial-2", false), output::add);
                assertThrows(IllegalArgumentException.class, () -> gate.process(new Frame.Transcript("conflict", committed.speaker(),
                        0, 1, true, -1, committed.segmentId(), 7, "user"), output::add));
                gate.process(committed, output::add);
            }
            assertEquals(1000, output.size(), "seed=" + seed);
            for (int i = 0; i < 1000; i++) assertEquals("final-" + i, ((Frame.Transcript) output.get(i)).text());
        }
    }

    @Test void retainedIdBoundHasObservableFifoEviction() throws Exception {
        var gate = new TranscriptGateFactory().create(Map.of("maxSegments", "7"));
        List<Frame> output = new ArrayList<>();
        for (int id = 0; id < 10000; id++) gate.process(turn(id, 0, "final", true), output::add);
        for (int id = 9993; id < 10000; id++) gate.process(turn(id, 0, "final", true), output::add);
        assertEquals(10000, output.size());
        for (int id = 0; id < 10; id++) gate.process(turn(id, 0, "final", true), output::add);
        assertEquals(10010, output.size());
    }

    private static Frame.Transcript turn(int id, long revision, String text, boolean finalResult) {
        return new Frame.Transcript(text, "person", 0, 1, finalResult, -1, "segment-" + id, revision, "user");
    }
}
