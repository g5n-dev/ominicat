package io.ominicat.comparison;

import static org.junit.jupiter.api.Assertions.*;

import io.ominicat.comparison.NumericWorkload.Phase;
import io.ominicat.comparison.NumericWorkload.Terminal;
import io.ominicat.comparison.NumericWorkload.Value;
import io.ominicat.core.Frame;
import io.ominicat.core.WorkerThreads;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class AdapterTest {
    static Stream<Arguments> engines() {
        return Stream.of("ominicat", "camel").flatMap(engine -> Stream.of(1, 4, 16).map(stages -> Arguments.of(engine, stages)));
    }

    @ParameterizedTest @MethodSource("engines")
    void nativeQueueUsesAnotherWorkerValidatesEveryOutputAndCloses(String engine, int stages) throws Exception {
        Terminal terminal = new Terminal(stages, 256);
        Thread caller = Thread.currentThread();
        long sequence = 0;
        try (QueuedAdapter adapter = QueuedAdapter.create(engine, stages, terminal)) {
            adapter.invoke(input(++sequence, 0, Phase.SETUP));
            adapter.assertDrained();
            terminal.assertWorker(caller);
            if (Runtime.version().feature() == 17) assertFalse(WorkerThreads.isVirtual(terminal.worker()));
            terminal.begin(Phase.WARMUP);
            for (int index = 0; index < 32; index++) adapter.invoke(input(++sequence, index, Phase.WARMUP));
            adapter.assertDrained();
            assertEquals(32, terminal.phaseCount());
            terminal.begin(Phase.MEASURE);
            for (int index = 0; index < 128; index++) adapter.invoke(input(++sequence, index, Phase.MEASURE));
            adapter.assertDrained();
            assertEquals(161, terminal.count());
            assertEquals(128, terminal.phaseCount());
            double[] percentiles = terminal.percentiles(128);
            assertTrue(percentiles[0] >= 0 && percentiles[0] <= percentiles[1]);
            assertTrue(percentiles[1] <= percentiles[2] && percentiles[2] <= percentiles[3]);
            assertEquals(engine.equals("camel") ? "4.22.2" : "0.1.0-SNAPSHOT", adapter.version());
        }
        assertNotNull(terminal.worker());
        assertFalse(terminal.worker().isAlive(), "adapter must release its actual worker");
    }

    @Test void combinedOracleMatchesStageArithmeticIncludingSignedLongWraparound() {
        for (int stages : new int[] {1, 4, 16}) {
            for (long sequence : new long[] {0, 1, -1, Long.MIN_VALUE, Long.MAX_VALUE, 0x12345678abcdef01L}) {
                Value value = new Value(sequence, sequence, 0, 0, Phase.SETUP);
                for (int stage = 0; stage < stages; stage++) value = value.transform(stage);
                assertEquals(value.value(), Long.rotateLeft(sequence, (stages * 7) & 63) ^ NumericWorkload.combinedMask(stages));
                assertEquals(sequence, value.sequence());
            }
        }
    }

    @Test void oracleRejectsWrongValueOrderPhaseUnknownDataAndDuplicateOutput() {
        Terminal terminal = new Terminal(4, 4);
        Value correct = transformed(input(1, 0, Phase.SETUP), 4);
        terminal.expect(correct);
        assertThrows(IllegalStateException.class, () -> terminal.accept(new Value(1, correct.value() ^ 1, correct.startedNanos(), 0, Phase.SETUP)));
        assertThrows(IllegalStateException.class, () -> terminal.accept(transformed(input(2, 0, Phase.SETUP), 4)));
        assertThrows(IllegalStateException.class, () -> terminal.accept(transformed(input(1, 0, Phase.WARMUP), 4)));
        assertThrows(IllegalStateException.class, () -> terminal.accept(new Frame.Text("unexpected")));
        assertThrows(IllegalStateException.class, () -> terminal.accept(new Value(1, correct.value(), correct.startedNanos() + 1, 0, Phase.SETUP)));
        terminal.accept(correct);
        assertThrows(IllegalStateException.class, () -> terminal.accept(correct));
        assertEquals(1, terminal.count());
    }

    @Test void sampleOverflowAndWrongAcknowledgmentAreFailures() {
        Terminal terminal = new Terminal(1, 1);
        Value setup = transformed(input(1, 0, Phase.SETUP), 1);
        terminal.expect(setup);
        terminal.accept(setup);
        assertThrows(IllegalStateException.class, () -> terminal.assertAcknowledged(setup, setup, 1));
        terminal.begin(Phase.MEASURE);
        Value first = transformed(input(2, 0, Phase.MEASURE), 1);
        terminal.expect(first);
        terminal.accept(first);
        Value second = transformed(input(3, 1, Phase.MEASURE), 1);
        terminal.expect(second);
        assertThrows(IllegalStateException.class, () -> terminal.accept(second));
        assertThrows(IllegalStateException.class, () -> terminal.percentiles(2));
        assertEquals(2, terminal.count());
    }

    @Test void optionsAreBoundedAndRejectNonfiniteDurationsOrUnknownArguments() {
        BenchmarkMain.Options options = BenchmarkMain.Options.parse(new String[] {"--engine", "camel", "--output", "result.json"});
        assertEquals(new BenchmarkMain.Options("camel", 4, 3, 5, 2_000_000, Path.of("result.json")), options);
        for (String value : new String[] {"NaN", "Infinity", "0", "-1", "3601"}) {
            assertThrows(IllegalArgumentException.class, () -> BenchmarkMain.Options.parse(new String[] {
                    "--engine", "ominicat", "--output", "result.json", "--measure-seconds", value}));
        }
        for (String[] arguments : new String[][] {
                {"--engine", "other", "--output", "result.json"},
                {"--engine", "camel", "--output", "result.json", "--stages", "2"},
                {"--engine", "camel", "--output", "result.json", "--max-samples", "2000001"},
                {"--engine", "camel", "--output", "result.json", "--unknown", "1"},
                {"--engine", "camel", "--engine", "camel", "--output", "result.json"},
                {"--engine", "camel"}, {"--engine"}}) {
            assertThrows(IllegalArgumentException.class, () -> BenchmarkMain.Options.parse(arguments));
        }
    }

    @Test void resultEncoderEscapesMetadataAndRejectsNonfiniteValues() {
        assertEquals("{\"text\":\"a\\\"b\\\\c\\n\\t\\u0001\"}", BenchmarkMain.json(Map.of("text", "a\"b\\c\n\t\u0001")));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkMain.json(Map.of("bad", Double.NaN)));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkMain.json(Map.of("bad", Double.POSITIVE_INFINITY)));
    }

    private static Value input(long sequence, int index, Phase phase) {
        long started = System.nanoTime();
        return new Value(sequence, sequence, started, index, phase);
    }

    private static Value transformed(Value input, int stages) {
        Value output = input;
        for (int stage = 0; stage < stages; stage++) output = output.transform(stage);
        return output;
    }
}
