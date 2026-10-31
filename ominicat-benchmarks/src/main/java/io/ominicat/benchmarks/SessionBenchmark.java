package io.ominicat.benchmarks;

import io.ominicat.core.Frame;
import io.ominicat.core.Pipeline;
import io.ominicat.core.PipelineSession;
import io.ominicat.core.SessionOptions;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/** One outstanding frame, measuring submission, worker wakeup, dispatch, and output handoff. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class SessionBenchmark {
    @Param({"1", "4", "16"}) public int stages;
    private PipelineSession session;
    private ArrayBlockingQueue<Frame> output;
    private long sequence;

    @Setup(Level.Trial) public void setup() {
        output = new ArrayBlockingQueue<>(1);
        Pipeline.Builder builder = Pipeline.builder();
        for (int i = 0; i < stages; i++) {
            long salt = 0x9e3779b97f4a7c15L + i;
            builder.add((frame, next) -> next.accept(new PipelineBenchmark.Value(
                    Long.rotateLeft(((PipelineBenchmark.Value) frame).value() ^ salt, 7))));
        }
        session = builder.build().openSession(new SessionOptions(1), output::put);
    }

    @Benchmark public void roundTrip(Blackhole blackhole) throws Exception {
        if (!session.submit(new PipelineBenchmark.Value(++sequence), Duration.ofSeconds(10))) {
            throw new IllegalStateException("session stopped accepting frames");
        }
        Frame result = output.poll(10, TimeUnit.SECONDS);
        if (result == null) throw new IllegalStateException("session produced no result");
        blackhole.consume(result);
    }

    @TearDown(Level.Trial) public void close() throws Exception {
        session.finish();
        session.completion().get(10, TimeUnit.SECONDS);
    }
}
