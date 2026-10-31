package io.ominicat.comparison;

import io.ominicat.core.Frame;
import java.util.Arrays;

/** Identical immutable data, transformation, and terminal oracle for both Java adapters. */
final class NumericWorkload {
    static final int MAX_SAMPLES = 2_000_000;
    static final int BUFFER_LONGS = 2_097_152;
    static final long SALT = 0x9e3779b97f4a7c15L;
    enum Phase { SETUP, WARMUP, MEASURE }

    record Value(long sequence, long value, long startedNanos, int index, Phase phase) implements Frame {
        Value transform(int stage) {
            return new Value(sequence, Long.rotateLeft(value ^ (SALT + stage), 7), startedNanos, index, phase);
        }
    }

    static long combinedMask(int stages) {
        long mask = 0;
        for (int index = 0; index < stages; index++) mask = Long.rotateLeft(mask ^ (SALT + index), 7);
        return mask;
    }

    static final class Terminal {
        private final long[] samples = new long[BUFFER_LONGS];
        private final int maxSamples;
        private final long mask;
        private final int rotation;
        private long nextSequence = 1;
        private int nextIndex;
        private long count;
        private Phase phase = Phase.SETUP;
        private Thread stageThread;
        private Thread terminalThread;
        private long expectedStartedNanos;
        private boolean awaitingInput;

        Terminal(int stages, int maxSamples) {
            if (stages < 1 || stages > 16) throw new IllegalArgumentException("invalid stages");
            if (maxSamples < 1 || maxSamples > MAX_SAMPLES) throw new IllegalArgumentException("invalid sample capacity");
            this.maxSamples = maxSamples;
            mask = combinedMask(stages);
            rotation = (stages * 7) & 63;
            // Touch every byte of the fixed 16 MiB buffer before any warmup or measurement.
            Arrays.fill(samples, -1L);
        }

        void begin(Phase next) {
            if (awaitingInput) throw new IllegalStateException("previous input is still outstanding");
            phase = next;
            nextIndex = 0;
        }

        void expect(Value input) {
            if (awaitingInput) throw new IllegalStateException("more than one outstanding input");
            expectedStartedNanos = input.startedNanos();
            awaitingInput = true;
        }

        void observeFirstStage(Value value) {
            if (value.phase() == Phase.SETUP) {
                Thread current = Thread.currentThread();
                if (stageThread != null && stageThread != current) throw new IllegalStateException("multiple stage workers");
                stageThread = current;
            }
        }

        void accept(Object frame) {
            if (!(frame instanceof Value value)) throw new IllegalStateException("unexpected terminal data type");
            Thread current = Thread.currentThread();
            if (terminalThread == null) terminalThread = current;
            if (terminalThread != current) throw new IllegalStateException("multiple terminal workers");
            if (!awaitingInput || value.sequence() != nextSequence || value.phase() != phase || value.index() != nextIndex) {
                throw new IllegalStateException("missing, duplicate, or out-of-order output");
            }
            if (value.startedNanos() != expectedStartedNanos) throw new IllegalStateException("input timestamp was not preserved");
            long expected = Long.rotateLeft(value.sequence(), rotation) ^ mask;
            if (value.value() != expected) throw new IllegalStateException("incorrect transformed output");
            if (phase == Phase.MEASURE && nextIndex >= maxSamples) throw new IllegalStateException("sample capacity exceeded");
            // Stop after all output validation and before publishing the caller acknowledgment.
            long latency = System.nanoTime() - value.startedNanos();
            if (latency < 0) throw new IllegalStateException("invalid monotonic latency");
            if (phase == Phase.MEASURE) samples[nextIndex] = latency;
            awaitingInput = false;
            nextSequence++;
            nextIndex++;
            count++;
        }

        void assertAcknowledged(Value input, Value output, long before) {
            if (output == null || output.sequence() != input.sequence() || output.phase() != input.phase()
                    || output.index() != input.index() || output.startedNanos() != input.startedNanos()
                    || output.value() != (Long.rotateLeft(input.sequence(), rotation) ^ mask) || count != before + 1) {
                throw new IllegalStateException("terminal acknowledgment mismatch");
            }
        }

        void assertWorker(Thread caller) {
            if (stageThread == null || stageThread == caller || terminalThread != stageThread) {
                throw new IllegalStateException("queued path did not use one separate stage/terminal worker");
            }
        }

        long count() { return count; }
        int phaseCount() { return nextIndex; }
        Thread worker() { return stageThread; }
        int maxSamples() { return maxSamples; }

        double[] percentiles(int expected) {
            if (expected < 1 || expected > maxSamples || phaseCount() != expected) {
                throw new IllegalStateException("invalid final sample count");
            }
            Arrays.sort(samples, 0, expected);
            return new double[] {percentile(expected, .50), percentile(expected, .95), percentile(expected, .99), samples[expected - 1] / 1_000d};
        }

        private double percentile(int count, double fraction) {
            return samples[Math.max(0, (int) Math.ceil(count * fraction) - 1)] / 1_000d;
        }
    }

    private NumericWorkload() { }
}
