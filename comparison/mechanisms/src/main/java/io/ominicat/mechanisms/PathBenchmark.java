package io.ominicat.mechanisms;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** Public synchronous APIs, with an identical optional external queue/worker/ack wrapper. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Threads(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-Xms256m", "-Xmx256m"})
public class PathBenchmark {
    @Param({"ominicat", "camel"}) public String framework;
    @Param({"caller", "handoff"}) public String execution;
    private PathHarness path;
    private long sequence;

    @Setup(Level.Trial) public void setup() throws Exception {
        BenchmarkOrigin.verify(PathBenchmark.class);
        path = new PathHarness(framework, execution, PathHarness.Fault.NONE);
        try {
            path.invoke(new PathHarness.Value(++sequence, sequence));
            path.assertDrained();
        } catch (Throwable failure) {
            try { path.close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    /** Includes input construction, stage allocations, exact output checks, return and optional ack. */
    @Benchmark public Object roundTrip() throws Exception {
        long next = ++sequence;
        return path.invoke(new PathHarness.Value(next, next));
    }

    @TearDown(Level.Trial) public void close() throws Exception {
        if (path != null) {
            try { path.assertDrained(); }
            finally { path.close(); }
        }
    }
}
