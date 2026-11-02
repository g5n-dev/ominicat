package io.ominicat.mechanisms;

import java.util.concurrent.TimeUnit;
import org.apache.camel.Exchange;
import org.openjdk.jmh.annotations.*;

/** Prebuilt immutable-body exchange; source construction and ID initialization are outside timing. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Threads(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-Xms256m", "-Xmx256m"})
public class ExchangeCopyBenchmark {
    @Param({"identity", "correlated-copy"}) public String operation;
    private ExchangeCopyFixture fixture;
    private boolean copy;

    @Setup(Level.Trial) public void setup() throws Exception {
        BenchmarkOrigin.verify(ExchangeCopyBenchmark.class);
        copy = switch (operation) {
            case "identity" -> false;
            case "correlated-copy" -> true;
            default -> throw new IllegalArgumentException("unknown exchange operation");
        };
        fixture = new ExchangeCopyFixture();
        try { fixture.invoke(copy); }
        catch (Throwable error) {
            try { fixture.close(); } catch (Throwable cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }

    /** Both cells perform the same body/metadata oracle; neither includes source construction. */
    @Benchmark public Exchange copy() { return fixture.invoke(copy); }

    @TearDown(Level.Trial) public void close() throws Exception {
        if (fixture != null) fixture.close();
    }
}
