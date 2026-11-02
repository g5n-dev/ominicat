package io.ominicat.mechanisms;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.openjdk.jmh.results.IterationResult;
import org.openjdk.jmh.results.IterationResultMetaData;
import org.openjdk.jmh.results.ScalarResult;

class ProcessCpuProfilerTest {
    @Test void normalizesTheProcessCounterByAllOperationsRatherThanTimedOperations() {
        AtomicLong processCpu = new AtomicLong(1_000);
        ProcessCpuProfiler profiler = new ProcessCpuProfiler(processCpu::get);
        profiler.beforeIteration(null, null);
        processCpu.set(1_120);
        var results = profiler.afterIteration(null, null, iteration(12, 3));
        assertEquals(1, results.size());
        ScalarResult result = results.iterator().next();
        assertEquals("process.cpu.ns/op", result.getLabel());
        assertEquals("ns/op", result.getScoreUnit());
        assertEquals(10.0, result.getScore());
        assertTrue(result.getRole().isSecondary());
    }

    @Test void eachIterationGetsItsOwnCpuIntervalAndZeroCpuDeltaRemainsFinite() {
        AtomicLong processCpu = new AtomicLong(100);
        ProcessCpuProfiler profiler = new ProcessCpuProfiler(processCpu::get);
        profiler.beforeIteration(null, null);
        processCpu.set(180);
        assertEquals(20.0, profiler.afterIteration(null, null, iteration(4, 4)).iterator().next().getScore());
        processCpu.set(1_000);
        profiler.beforeIteration(null, null);
        assertEquals(0.0, profiler.afterIteration(null, null, iteration(5, 5)).iterator().next().getScore());
    }

    @Test void unsupportedClockCannotProduceAResultAtEitherBoundary() {
        AtomicLong processCpu = new AtomicLong(-1);
        ProcessCpuProfiler profiler = new ProcessCpuProfiler(processCpu::get);
        assertThrows(IllegalStateException.class, () -> profiler.beforeIteration(null, null));
        processCpu.set(100);
        profiler.beforeIteration(null, null);
        processCpu.set(-1);
        assertThrows(IllegalStateException.class,
                () -> profiler.afterIteration(null, null, iteration(10, 10)));
    }

    @Test void backwardsClockAndUnpairedIterationHooksFailInsteadOfPublishingMeasurements() {
        AtomicLong processCpu = new AtomicLong(100);
        ProcessCpuProfiler profiler = new ProcessCpuProfiler(processCpu::get);
        assertThrows(IllegalStateException.class,
                () -> profiler.afterIteration(null, null, iteration(10, 10)));
        profiler.beforeIteration(null, null);
        assertThrows(IllegalStateException.class, () -> profiler.beforeIteration(null, null));
        processCpu.set(99);
        assertThrows(IllegalStateException.class,
                () -> profiler.afterIteration(null, null, iteration(10, 10)));
    }

    @Test void absentMetadataAndNonpositiveAllOpsDoNotBecomeNanOrZeroResults() {
        for (IterationResult result : new IterationResult[]{null, new IterationResult(null, null, null),
                iteration(0, 100), iteration(-1, 100)}) {
            AtomicLong processCpu = new AtomicLong(100);
            ProcessCpuProfiler profiler = new ProcessCpuProfiler(processCpu::get);
            profiler.beforeIteration(null, null);
            processCpu.set(200);
            assertThrows(IllegalStateException.class, () -> profiler.afterIteration(null, null, result));
        }
    }

    @Test void largeValidCountersRemainFiniteAndNoJvmOptionsAreSilentlyAccepted() {
        AtomicLong processCpu = new AtomicLong(0);
        ProcessCpuProfiler profiler = new ProcessCpuProfiler(processCpu::get);
        profiler.beforeIteration(null, null);
        processCpu.set(Long.MAX_VALUE);
        double score = profiler.afterIteration(null, null, iteration(Long.MAX_VALUE, 1)).iterator().next().getScore();
        assertEquals(1.0, score);
        assertTrue(Double.isFinite(score));
        assertNotNull(new ProcessCpuProfiler());
        assertNotNull(new ProcessCpuProfiler(""));
        assertThrows(IllegalArgumentException.class, () -> new ProcessCpuProfiler("unsupported=true"));
        assertThrows(IllegalArgumentException.class, () -> new ProcessCpuProfiler((String) null));
    }

    private static IterationResult iteration(long allOps, long measuredOps) {
        return new IterationResult(null, null, new IterationResultMetaData(allOps, measuredOps));
    }
}
