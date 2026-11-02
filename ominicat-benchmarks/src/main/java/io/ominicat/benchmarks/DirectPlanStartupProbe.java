package io.ominicat.benchmarks;

import io.ominicat.core.Frame;
import io.ominicat.core.FrameSink;
import io.ominicat.core.Pipeline;
import io.ominicat.core.Processor;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/**
 * Paired build-cost diagnostic, separate from the steady-state direct probe.
 * Cached processor/frame/terminal construction is excluded. Every operation builds
 * a real pipeline; first-direct-4 also validates one synchronous call inside timing.
 * build-4 validates setup and the last built pipeline outside timing, never hiding
 * a process call in its measured construction-only boundary.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Threads(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class DirectPlanStartupProbe {
    @Param({"build-4", "first-direct-4"})
    public String scenario;
    private Harness harness;

    @Setup(Level.Trial) public void setup() throws Exception {
        verifyConfiguredOrigins();
        harness = new Harness(scenario, Fault.NONE);
        try {
            harness.invoke();
            harness.verifyLastBuild();
        } catch (Throwable failure) {
            harness.close();
            throw failure;
        }
    }

    /** Returning the new pipeline makes the whole built graph observable to JMH. */
    @Benchmark public Pipeline invoke() throws Exception { return harness.invoke(); }

    @TearDown(Level.Trial) public void teardown() throws Exception {
        if (harness != null) {
            try { harness.verifyLastBuild(); }
            finally { harness.close(); harness = null; }
        }
    }

    static void verifyConfiguredOrigins() throws Exception {
        // Validate required configuration before considering any class directory.
        DirectStateProbe.requireHash(System.getProperty(DirectStateProbe.CORE_PROPERTY));
        DirectStateProbe.requireHash(System.getProperty(DirectStateProbe.PROBE_PROPERTY));
        Path expected = DirectStateProbe.jarOrigin(DirectStateProbe.class);
        verifyProbeClassTree(DirectPlanStartupProbe.class, expected);
        // Checks runtime/probe bytes, every Pipeline nested class and the shared
        // probe, then prints each existing DIRECT_STATE_* marker exactly once.
        DirectStateProbe.verifyConfiguredOrigins();
    }

    private static void verifyProbeClassTree(Class<?> type, Path expected) throws Exception {
        if (!expected.equals(DirectStateProbe.jarOrigin(type))) {
            throw new IllegalStateException("partial startup probe classpath fallback");
        }
        for (Class<?> nested : type.getDeclaredClasses()) verifyProbeClassTree(nested, expected);
    }

    enum Fault { NONE, MISSING_STAGE, WRONG_ORDER, WRONG_FRAME, MISSING_OUTPUT, DUPLICATE_OUTPUT, POST_OUTPUT_THROW }

    static final class Harness implements AutoCloseable {
        private final Thread owner = Thread.currentThread();
        private final boolean firstDirect;
        private final Fault fault;
        private final Oracle oracle = new Oracle();
        private final Frame input = new Frame.Text("synthetic-startup-frame");
        private final Frame replacement = new Frame.Text("synthetic-wrong-frame");
        private final Processor[] stages = new Processor[4];
        private final FrameSink terminal = oracle;
        private Pipeline lastPipeline;
        private long built;
        private long successfulCalls;
        private long verifiedBuild;
        private Throwable failure;
        private boolean closed;

        Harness(String scenario, Fault fault) {
            firstDirect = switch (scenario) {
                case "build-4" -> false;
                case "first-direct-4" -> true;
                default -> throw new IllegalArgumentException("unsupported startup scenario");
            };
            this.fault = java.util.Objects.requireNonNull(fault, "fault");
            for (int index = 0; index < stages.length; index++) stages[index] = new Stage(this, index);
        }

        Pipeline invoke() throws Exception {
            requireReady();
            Pipeline.Builder builder = Pipeline.builder();
            for (int index = 0; index < stages.length; index++) {
                if (fault == Fault.MISSING_STAGE && index == 3) continue;
                int selected = fault == Fault.WRONG_ORDER && index < 2 ? 1 - index : index;
                builder.add(stages[selected]);
            }
            Pipeline pipeline = builder.build();
            lastPipeline = pipeline;
            built++;
            if (firstDirect) validate(pipeline);
            return pipeline;
        }

        void verifyLastBuild() throws Exception {
            requireReady();
            if (lastPipeline == null || built == 0) throw new IllegalStateException("no startup pipeline was built");
            if (verifiedBuild != built) validate(lastPipeline);
            if (oracle.active || oracle.entered != 4 || oracle.emitted != 1) {
                throw new IllegalStateException("startup validation did not settle");
            }
        }

        private void validate(Pipeline pipeline) throws Exception {
            oracle.begin();
            try {
                pipeline.process(input, terminal);
                oracle.finish();
                successfulCalls++;
                verifiedBuild = built;
            } catch (Exception | Error error) {
                failure = error;
                oracle.active = false;
                throw error;
            }
        }

        private void requireReady() {
            if (Thread.currentThread() != owner || closed || failure != null) {
                throw new IllegalStateException("startup fixture is unavailable on this thread");
            }
        }

        long built() { return built; }
        long successfulCalls() { return successfulCalls; }
        long witness() { return oracle.witness; }

        @Override public void close() {
            // Cached stubs own no worker, queue or resource and have a no-op close.
            // The caller owns them across all direct pipelines; no session is opened.
            if (closed) return;
            closed = true;
            lastPipeline = null;
        }

        private final class Oracle implements FrameSink {
            private boolean active;
            private int entered;
            private int emitted;
            private long sequence;
            private long witness;

            void begin() {
                requireReady();
                if (active) throw new IllegalStateException("overlapping startup invocation");
                active = true;
                entered = 0;
                emitted = 0;
                sequence++;
            }

            void enter(int index, Frame frame) {
                check(frame);
                if (entered != index) throw new IllegalStateException("startup stage order or count mismatch");
                entered++;
                witness = Long.rotateLeft(witness ^ (sequence + index + 1), 7);
            }

            private void check(Frame frame) {
                if (!active || Thread.currentThread() != owner || frame != input) {
                    throw new IllegalStateException("startup frame identity, thread or lifetime mismatch");
                }
            }

            @Override public void accept(Frame frame) {
                check(frame);
                if (entered != 4 || emitted++ != 0) throw new IllegalStateException("startup terminal count mismatch");
                witness ^= sequence;
            }

            void finish() {
                if (entered != 4 || emitted != 1) throw new IllegalStateException("startup output is missing");
                active = false;
            }
        }
    }

    /** Four distinct cached pass-through stubs; all mutable test state is in the oracle. */
    private static final class Stage implements Processor {
        private final Harness harness;
        private final int index;

        Stage(Harness harness, int index) { this.harness = harness; this.index = index; }

        @Override public void process(Frame frame, FrameSink output) throws Exception {
            harness.oracle.enter(index, frame);
            if (index == 3 && harness.fault == Fault.MISSING_OUTPUT) return;
            output.accept(index == 2 && harness.fault == Fault.WRONG_FRAME ? harness.replacement : frame);
            if (index == 3 && harness.fault == Fault.DUPLICATE_OUTPUT) output.accept(frame);
            if (index == 3 && harness.fault == Fault.POST_OUTPUT_THROW) {
                throw new IllegalStateException("synthetic post-output failure");
            }
        }

        @Override public void close() { }
    }
}
