package io.ominicat.benchmarks;

import io.ominicat.core.Frame;
import io.ominicat.core.Pipeline;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/** In-memory dispatch through real transforming stages; no network or model execution. */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class PipelineBenchmark {
    @Param({"1", "4", "16"}) public int stages;
    private Pipeline pipeline;
    private long sequence;

    /** A nonconstant input prevents benchmarking a constant-folded pass-through. */
    public record Value(long value) implements Frame { }

    @Setup(Level.Trial) public void setup() {
        Pipeline.Builder builder = Pipeline.builder();
        for (int index = 0; index < stages; index++) {
            long salt = 0x9e3779b97f4a7c15L + index;
            builder.add((frame, next) -> {
                Value value = (Value) frame;
                next.accept(new Value(Long.rotateLeft(value.value() ^ salt, 7)));
            });
        }
        pipeline = builder.build();
    }

    /** Includes input and stage output allocation; consumes actual transformed output. */
    @Benchmark public void dispatch(Blackhole blackhole) throws Exception {
        pipeline.process(new Value(++sequence), blackhole::consume);
    }
}
