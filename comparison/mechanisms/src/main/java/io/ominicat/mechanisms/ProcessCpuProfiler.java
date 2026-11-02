package io.ominicat.mechanisms;

import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.profile.InternalProfiler;
import org.openjdk.jmh.results.AggregationPolicy;
import org.openjdk.jmh.results.IterationResult;
import org.openjdk.jmh.results.ScalarResult;

/**
 * Whole-process CPU nanoseconds per JMH operation, sampled around each iteration.
 * The public JDK process clock includes all JVM threads: caller, handoff worker,
 * Camel/background work, GC, JIT and JMH. This is neither terminal wall latency nor
 * framework-exclusive CPU attribution. The sampling interval also includes work
 * between JMH's iteration hooks and its benchmark loop.
 *
 * <p>The denominator is JMH's all-operation count rather than only its
 * measured-operation count.
 * Unsupported clocks and incomplete/invalid observations fail the run; no NaN,
 * zero-operation fallback or wall-clock substitution is published.
 */
public final class ProcessCpuProfiler implements InternalProfiler {
    private final LongSupplier processCpuTime;
    private long beforeCpuNanos;
    private boolean iterationActive;

    /** Construct through JMH's no-argument profiler entry point. */
    public ProcessCpuProfiler() {
        this(systemProcessClock());
    }

    /** Construct through JMH's string-option entry point; this profiler has no options. */
    public ProcessCpuProfiler(String options) {
        this(checkedSystemProcessClock(options));
    }

    // An injected clock makes unsupported counters and invalid iteration metadata testable.
    ProcessCpuProfiler(LongSupplier processCpuTime) {
        this.processCpuTime = Objects.requireNonNull(processCpuTime, "processCpuTime");
    }

    @Override
    public String getDescription() {
        return "Whole-process CPU ns/op across all JVM threads, including workers, GC, JIT and JMH; not framework-exclusive CPU";
    }

    @Override
    public void beforeIteration(BenchmarkParams benchmarkParams, IterationParams iterationParams) {
        if (iterationActive) throw new IllegalStateException("process CPU iteration is already active");
        long sampled = processCpuTime.getAsLong();
        if (sampled < 0) throw new IllegalStateException("process CPU time is unsupported or negative");
        beforeCpuNanos = sampled;
        iterationActive = true;
    }

    @Override
    public Collection<ScalarResult> afterIteration(BenchmarkParams benchmarkParams, IterationParams iterationParams,
                                                   IterationResult result) {
        if (!iterationActive) throw new IllegalStateException("process CPU iteration has no starting observation");
        long afterCpuNanos = processCpuTime.getAsLong();
        iterationActive = false;
        if (afterCpuNanos < 0 || afterCpuNanos < beforeCpuNanos) {
            throw new IllegalStateException("process CPU time is unsupported, negative or decreasing");
        }
        if (result == null || result.getMetadata() == null) {
            throw new IllegalStateException("process CPU iteration metadata is missing");
        }
        long operations = result.getMetadata().getAllOps();
        if (operations <= 0) throw new IllegalStateException("process CPU iteration must contain positive operation count");
        double nanosPerOperation = (double) (afterCpuNanos - beforeCpuNanos) / operations;
        if (!Double.isFinite(nanosPerOperation) || nanosPerOperation < 0) {
            throw new IllegalStateException("process CPU observation is not finite and nonnegative");
        }
        return List.of(new ScalarResult("process.cpu.ns/op", nanosPerOperation, "ns/op", AggregationPolicy.AVG));
    }

    private static LongSupplier checkedSystemProcessClock(String options) {
        if (options == null || !options.isBlank()) {
            throw new IllegalArgumentException("process CPU profiler does not accept options");
        }
        return systemProcessClock();
    }

    private static LongSupplier systemProcessClock() {
        if (!(ManagementFactory.getOperatingSystemMXBean() instanceof OperatingSystemMXBean operatingSystem)) {
            throw new IllegalStateException("public JDK process CPU clock is unavailable");
        }
        return operatingSystem::getProcessCpuTime;
    }
}
