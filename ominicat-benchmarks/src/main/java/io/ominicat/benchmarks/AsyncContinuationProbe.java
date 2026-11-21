package io.ominicat.benchmarks;

import io.ominicat.core.AsyncEmitter;
import io.ominicat.core.AsyncProcessor;
import io.ominicat.core.EnvelopeProcessor;
import io.ominicat.core.Frame;
import io.ominicat.core.FrameEnvelope;
import io.ominicat.core.Pipeline;
import io.ominicat.core.PipelineSession;
import io.ominicat.core.Processor;
import io.ominicat.core.SessionMetrics;
import io.ominicat.core.SessionOptions;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
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

/**
 * Bounded external-callback continuation probe over real async sessions: a provider
 * thread completes each AsyncProcessor after emitting, measuring scheduler reentry,
 * generation fences, handle registration and (in the envelope scenario) real adapter
 * identity/causation/timestamp derivation. Executor, identifier and clock supplier
 * costs are inside the measured scope, matching the iteration-plan acceptance.
 *
 * <p>Timed scope of one operation: session submission, worker wakeup, one async
 * stage dispatch, handle creation, a bounded provider-thread completion with an
 * exact-oracle emission, reentry at the remaining synchronous stage, terminal
 * acknowledgment and processed-count settle. Frame construction is inside timing;
 * worker creation, media pools and teardown are outside. One request is outstanding
 * at any moment. No network, model, codec or production capacity is exercised.
 *
 * <p>Use JMH {@code -jvmArgsAppend} to pass
 * {@code -Dominicat.probe.harnessSha256=<expected JAR SHA-256>} and
 * {@code -Dominicat.probe.coreSha256=<expected JAR SHA-256>} when binding paired
 * revisions through classpath precedence; actual loaded hashes are printed without
 * paths and a supplied mismatch fails setup.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class AsyncContinuationProbe {
    static final String SESSION = "synthetic-session";
    static final String TURN = "synthetic-turn";
    static final String CORRELATION = "synthetic-correlation";
    private static final long SALT = 0x9e3779b97f4a7c15L;
    private static final long WATCHDOG_NANOS = TimeUnit.SECONDS.toNanos(10);

    /** raw emits bare Value frames; envelope wraps input and re-derives output metadata. */
    @Param({"raw-async-4", "envelope-async-4"}) public String scenario;
    private boolean envelope;
    private Harness harness;

    @Setup(Level.Trial) public void setup() throws Exception {
        verifyOrigin(AsyncContinuationProbe.class, "ominicat.probe.harnessSha256", "PROBE_HARNESS_SHA256=");
        verifyOrigin(Frame.class, "ominicat.probe.coreSha256", "PROBE_CORE_SHA256=");
        envelope = scenario.equals("envelope-async-4");
        harness = new Harness(envelope, WATCHDOG_NANOS);
        try {
            harness.invoke();
            harness.assertDrained();
        } catch (Throwable failure) {
            try { harness.close(); } catch (Throwable cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            rethrow(failure);
        }
    }

    @Benchmark public Frame invoke() throws Exception { return harness.invoke(); }

    @TearDown(Level.Trial) public void teardown() throws Exception {
        if (harness != null) {
            try { harness.assertDrained(); }
            finally { harness.close(); }
        }
    }

    record Value(long sequence, long value) implements Frame { }
    private record Reply(Frame frame, Throwable failure) { }

    /**
     * One pipeline: async provider stage (wrapped in EnvelopeProcessor for the
     * envelope scenario) plus three synchronous transform stages, over a capacity-one
     * session with a single-outstanding-request closed loop.
     */
    static final class Harness implements AutoCloseable {
        private final boolean envelope;
        private final Thread caller = Thread.currentThread();
        private final long watchdogNanos;
        private final Pipeline pipeline;
        private final PipelineSession session;
        private final CompletableFuture<Void> completion;
        private final ArrayBlockingQueue<Reply> replies = new ArrayBlockingQueue<>(1);
        private final AtomicInteger cancellations = new AtomicInteger();
        private final long[] masks = new long[5];
        private Thread worker;
        private long sequence;
        private long successful;
        private int emitted;
        private Frame input;
        private boolean active;
        private boolean closed;
        private Throwable operationFailure;

        Harness(boolean envelope, long watchdogNanos) {
            this.envelope = envelope;
            if (watchdogNanos < 1 || watchdogNanos > TimeUnit.SECONDS.toNanos(10)) {
                throw new IllegalArgumentException("invalid watchdog");
            }
            this.watchdogNanos = watchdogNanos;
            for (int i = 1; i < 5; i++) masks[i] = Long.rotateLeft(masks[i - 1] ^ (SALT + i - 1), 7);

            AsyncProcessor provider = new AsyncProcessor() {
                @Override public void processAsync(Frame frame, AsyncEmitter emitter) {
                    Frame payload = frame instanceof FrameEnvelope source ? source.frame() : frame;
                    Value input = (Value) payload;
                    if (input.sequence() != sequence || input.value() != sequence) {
                        throw new IllegalStateException("async provider received an unverified input");
                    }
                    long emitted = Long.rotateLeft(input.value() ^ SALT, 7);
                    if (!emitter.tryEmit(new Value(input.sequence(), emitted))) {
                        throw new IllegalStateException("provider emission was rejected");
                    }
                    emitter.complete();
                }
                @Override public void onInterrupt() { cancellations.incrementAndGet(); }
            };
            Processor stage = EnvelopeProcessor.wrap(provider,
                    () -> "stage-4-" + sequence, () -> TimeUnit.MILLISECONDS.toNanos(sequence) + 4);
            if (!envelope) stage = provider;

            Pipeline.Builder builder = Pipeline.builder();
            builder.add(stage);
            for (int index = 1; index <= 3; index++) {
                int position = index;
                Processor synchronous = (frame, next) -> {
                    checkWorker();
                    if (!active) throw new IllegalStateException("stage ran outside an active invocation");
                    Frame payload = frame instanceof FrameEnvelope source ? source.frame() : frame;
                    Value value = (Value) payload;
                    long shifted = Long.rotateLeft(value.value() ^ (SALT + position), 7);
                    next.accept(envelope
                            ? ((FrameEnvelope) frame).derive(new Value(value.sequence(), shifted),
                                    "stage-" + (position + 1) + "-" + sequence,
                                    TimeUnit.MILLISECONDS.toNanos(sequence) + position + 4)
                            : new Value(value.sequence(), shifted));
                };
                builder.add(synchronous);
            }
            pipeline = builder.build();
            session = pipeline.openSession(new SessionOptions(1), this::acceptOutput);
            completion = session.completion();
            completion.whenComplete((ignored, failure) -> {
                if (failure != null) replies.offer(new Reply(null, failure));
            });
        }

        Frame invoke() throws Exception {
            if (Thread.currentThread() != caller || closed || active) {
                throw new IllegalStateException("invalid probe caller or lifetime");
            }
            if (operationFailure != null) rethrow(operationFailure);
            sequence = Math.incrementExact(sequence);
            active = true;
            emitted = 0;
            input = envelope
                    ? new FrameEnvelope(new Value(sequence, sequence), "input-" + sequence, SESSION, TURN,
                            CORRELATION, "", TimeUnit.MILLISECONDS.toNanos(sequence),
                            TimeUnit.MILLISECONDS.toNanos(sequence) + 1, TimeUnit.MILLISECONDS.toNanos(sequence) + 2)
                    : new Value(sequence, sequence);
            try {
                if (!session.trySubmit(input)) throw new IllegalStateException("probe submission was rejected");
                Reply reply = replies.poll(watchdogNanos, TimeUnit.NANOSECONDS);
                if (reply == null) throw new IllegalStateException("probe produced no acknowledgment");
                if (reply.failure() != null) rethrow(reply.failure());
                awaitProcessed(sequence);
                successful++;
                return reply.frame();
            } catch (Throwable failure) {
                operationFailure = failure;
                rethrow(failure);
                throw new AssertionError("unreachable");
            } finally { active = false; input = null; }
        }

        private void acceptOutput(Frame output) {
            checkWorker();
            if (!active || emitted++ != 0) {
                throw new IllegalStateException("missing, duplicate or unordered terminal output");
            }
            long base = TimeUnit.MILLISECONDS.toNanos(sequence);
            Frame frame = output;
            if (envelope) {
                FrameEnvelope source = (FrameEnvelope) frame;
                if (!("stage-4-" + sequence).equals(source.id())
                        || !SESSION.equals(source.sessionId()) || !TURN.equals(source.turnId())
                        || !CORRELATION.equals(source.correlationId())
                        || source.originTimeNanos() != base || source.ingestTimeNanos() != base + 1
                        || source.emitTimeNanos() < base + 4) {
                    throw new IllegalStateException("envelope output identity or timing mismatch");
                }
                frame = source.frame();
            }
            Value value = (Value) frame;
            if (value.sequence() != sequence || value.value() != expected(4)) {
                throw new IllegalStateException("async output failed the independent oracle");
            }
            if (!replies.offer(new Reply(output, null))) {
                throw new IllegalStateException("duplicate terminal acknowledgment");
            }
        }

        private long expected(int index) { return Long.rotateLeft(sequence, index * 7) ^ masks[index]; }

        private void checkWorker() {
            Thread current = Thread.currentThread();
            if (worker == null) {
                if (current == caller) throw new IllegalStateException("stage ran on the probe caller");
                worker = current;
            } else if (current != worker && current != caller) {
                throw new IllegalStateException("stage or terminal ran on the wrong thread");
            }
        }

        private void awaitProcessed(long expected) throws Exception {
            // An async provider re-emission re-enters the queue: one invocation settles two accepted/processed items.
            long target = 2 * expected;
            SessionMetrics metrics = session.metrics();
            long started = System.nanoTime();
            while (metrics.processed() != target || metrics.accepted() != target
                    || metrics.queueDepth() != 0 || metrics.failed() != 0 || metrics.rejected() != 0) {
                if (completion.isDone()) completion.get(watchdogNanos, TimeUnit.NANOSECONDS);
                if (System.nanoTime() - started >= watchdogNanos) {
                    throw new IllegalStateException("probe processing did not settle");
                }
                java.util.concurrent.locks.LockSupport.parkNanos(1_000);
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("probe caller interrupted");
                metrics = session.metrics();
            }
        }

        void assertDrained() throws Exception {
            if (operationFailure != null) rethrow(operationFailure);
            if (active || successful == 0 || successful != sequence) {
                throw new IllegalStateException("probe did not settle every invocation");
            }
            SessionMetrics metrics = session.metrics();
            if (metrics.processed() != 2 * successful || metrics.accepted() != 2 * successful
                    || metrics.queueDepth() != 0 || metrics.failed() != 0 || metrics.rejected() != 0
                    || metrics.cancelled() != 0 || !replies.isEmpty() || completion.isDone()) {
                throw new IllegalStateException("probe retained or failed work");
            }
        }

        long successful() { return successful; }
        Thread worker() { return worker; }

        @Override public void close() throws Exception {
            if (closed) return;
            closed = true;
            Throwable cleanupFailure = null;
            if (operationFailure == null) session.finish(); else session.close();
            try {
                completion.get(watchdogNanos, TimeUnit.NANOSECONDS);
            } catch (ExecutionException | java.util.concurrent.CancellationException error) {
                if (operationFailure == null) cleanupFailure = error;
            } finally {
                session.close();
                Thread owner = worker;
                if (owner != null) {
                    owner.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(watchdogNanos)));
                    if (owner.isAlive()) throw new IllegalStateException("probe worker did not terminate");
                }
                replies.clear();
            }
            if (cleanupFailure != null) rethrow(cleanupFailure);
        }
    }

    static void verifyOrigin(Class<?> type, String property, String logPrefix) throws Exception {
        String expectedHash = System.getProperty(property);
        if (expectedHash != null && !expectedHash.isBlank() && !expectedHash.matches("[a-fA-F0-9]{64}")) {
            throw new IllegalArgumentException("expected probe origin SHA-256 must have 64 hexadecimal characters");
        }
        if (type.getProtectionDomain().getCodeSource() == null) {
            throw new IllegalStateException("probe class origin is unavailable");
        }
        URL location = type.getProtectionDomain().getCodeSource().getLocation();
        if (!"file".equals(location.getProtocol())) throw new IllegalStateException("probe class origin must be a JAR file");
        Path artifact = Path.of(location.toURI());
        if (!Files.isRegularFile(artifact)) throw new IllegalStateException("probe class origin must be a JAR file");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(artifact)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        String actualHash = HexFormat.of().formatHex(digest.digest());
        System.out.println(logPrefix + actualHash);
        if (expectedHash != null && !expectedHash.isBlank() && !actualHash.equalsIgnoreCase(expectedHash)) {
            throw new IllegalStateException("loaded probe dependency does not match the requested JAR SHA-256");
        }
    }

    static void rethrow(Throwable failure) throws Exception {
        if (failure instanceof Exception exception) throw exception;
        if (failure instanceof Error error) throw error;
        throw new IllegalStateException("probe operation failed", failure);
    }
}
