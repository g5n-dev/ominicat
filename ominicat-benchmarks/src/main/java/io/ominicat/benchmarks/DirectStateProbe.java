package io.ominicat.benchmarks;

import io.ominicat.core.EnvelopeProcessor;
import io.ominicat.core.Frame;
import io.ominicat.core.FrameEnvelope;
import io.ominicat.core.FrameSink;
import io.ominicat.core.Pipeline;
import io.ominicat.core.PipelineSession;
import io.ominicat.core.Processor;
import io.ominicat.core.SessionMetrics;
import io.ominicat.core.SessionOptions;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ReadOnlyBufferException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.openjdk.jmh.annotations.*;

/**
 * Same probe JAR, paired runtime JARs. Includes exact oracles in every operation.
 * Media construction is excluded; deterministic envelope ID preparation and supplier calls are included.
 * The session control includes its queue/reply and processed-count acknowledgment, unlike direct dispatch.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Threads(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class DirectStateProbe {
    static final String CORE_PROPERTY = "ominicat.directState.coreSha256";
    static final String PROBE_PROPERTY = "ominicat.directState.probeSha256";

    @Param({"raw-direct-1", "raw-direct-4", "raw-direct-16", "envelope-direct-4", "raw-session-4",
            "audio-direct-4", "image-direct-4"})
    public String scenario;
    private Harness harness;

    @Setup(Level.Trial) public void setup() throws Exception {
        verifyConfiguredOrigins();
        harness = new Harness(scenario, Fault.NONE, TimeUnit.SECONDS.toNanos(10));
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

    static void verifyConfiguredOrigins() throws Exception {
        String coreHash = requireHash(System.getProperty(CORE_PROPERTY));
        String probeHash = requireHash(System.getProperty(PROBE_PROPERTY));
        if (Runtime.version().feature() != 17) throw new IllegalStateException("direct-state measurements require Java 17");
        Path core = jarOrigin(Pipeline.class);
        Path probe = jarOrigin(DirectStateProbe.class);
        if (core.equals(probe)) throw new IllegalStateException("runtime core must precede the separate shared probe JAR");
        for (Class<?> type : new Class<?>[]{Frame.class, FrameEnvelope.class, PipelineSession.class, EnvelopeProcessor.class,
                Class.forName("io.ominicat.core.SynchronousSink", false, Pipeline.class.getClassLoader())}) {
            requireOrigin(type, core);
        }
        for (Class<?> nested : Pipeline.class.getDeclaredClasses()) requireOrigin(nested, core);
        for (Class<?> nested : DirectStateProbe.class.getDeclaredClasses()) requireOrigin(nested, probe);
        String actualCore = verifyArtifact(core, coreHash);
        String actualProbe = verifyArtifact(probe, probeHash);
        System.out.println("DIRECT_STATE_CORE_SHA256=" + actualCore);
        System.out.println("DIRECT_STATE_PROBE_SHA256=" + actualProbe);
    }

    static String requireHash(String expected) {
        if (expected == null || !expected.matches("[a-fA-F0-9]{64}")) {
            throw new IllegalArgumentException("explicit core and probe SHA-256 properties are required");
        }
        return expected.toLowerCase(java.util.Locale.ROOT);
    }

    static String verifyArtifact(Path artifact, String expected) throws Exception {
        expected = requireHash(expected);
        if (!Files.isRegularFile(artifact)) throw new IllegalStateException("origin must be a regular JAR file");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(artifact)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        String actual = HexFormat.of().formatHex(digest.digest());
        if (!actual.equals(expected)) throw new IllegalStateException("loaded artifact SHA-256 mismatch");
        return actual;
    }

    static Path jarOrigin(Class<?> type) throws Exception {
        if (type.getProtectionDomain().getCodeSource() == null) throw new IllegalStateException("class origin unavailable");
        var location = type.getProtectionDomain().getCodeSource().getLocation();
        if (!"file".equals(location.getProtocol())) throw new IllegalStateException("class origin must be a local JAR");
        Path origin = Path.of(location.toURI()).toRealPath();
        if (!Files.isRegularFile(origin)) throw new IllegalStateException("class directories are not measured artifacts");
        return origin;
    }

    private static void requireOrigin(Class<?> type, Path expected) throws Exception {
        if (!expected.equals(jarOrigin(type))) throw new IllegalStateException("partial runtime or probe classpath fallback");
    }

    public record Value(long sequence, long value) implements Frame { }
    enum Fault { NONE, CORRUPT_OUTPUT, MISSING_OUTPUT, DUPLICATE_OUTPUT, POST_OUTPUT_THROW }
    private record Reply(Frame frame, Throwable failure) { }

    record Scenario(int stages, boolean envelope, boolean session, String media) {
        static Scenario parse(String name) {
            return switch (name) {
                case "raw-direct-1" -> new Scenario(1, false, false, "");
                case "raw-direct-4" -> new Scenario(4, false, false, "");
                case "raw-direct-16" -> new Scenario(16, false, false, "");
                case "envelope-direct-4" -> new Scenario(4, true, false, "");
                case "raw-session-4" -> new Scenario(4, false, true, "");
                case "audio-direct-4" -> new Scenario(4, false, false, "audio");
                case "image-direct-4" -> new Scenario(4, false, false, "image");
                default -> throw new IllegalArgumentException("unsupported direct-state scenario");
            };
        }
    }

    static final class Harness implements AutoCloseable {
        private static final long SALT = 0x9e3779b97f4a7c15L;
        private static final String SESSION = "synthetic-session";
        private static final String TURN = "synthetic-turn";
        private static final String CORRELATION = "synthetic-correlation";
        private final Scenario scenario;
        private final Thread caller = Thread.currentThread();
        private final long watchdogNanos;
        private final List<Processor> stages = new ArrayList<>();
        private final Pipeline pipeline;
        private final PipelineSession session;
        private final CompletableFuture<Void> completion;
        private final ArrayBlockingQueue<Reply> replies;
        private final FrameSink terminal;
        private final Frame[] media;
        private final long[] masks;
        private final String[] ids;
        private Thread owner;
        private long sequence;
        private long successful;
        private long witness;
        private long baseTime;
        private Frame input;
        private Frame output;
        private int entered;
        private int metadataEntered;
        private int emitted;
        private int idCalls;
        private int clockCalls;
        private boolean active;
        private boolean closed;
        private Throwable operationFailure;

        Harness(String name, Fault fault, long watchdogNanos) {
            scenario = Scenario.parse(name);
            if (watchdogNanos < 1 || watchdogNanos > TimeUnit.SECONDS.toNanos(10)) throw new IllegalArgumentException("invalid watchdog");
            this.watchdogNanos = watchdogNanos;
            media = scenario.media().isEmpty() ? null : mediaFrames(scenario.media());
            masks = new long[scenario.stages() + 1];
            ids = new String[scenario.stages() + 1];
            for (int i = 0; i < scenario.stages(); i++) masks[i + 1] = Long.rotateLeft(masks[i] ^ (SALT + i), 7);
            replies = scenario.session() ? new ArrayBlockingQueue<>(1) : null;
            terminal = switch (fault) {
                case NONE -> this::acceptOutput;
                case CORRUPT_OUTPUT -> frame -> acceptOutput(corrupt(frame));
                case MISSING_OUTPUT -> frame -> { };
                case DUPLICATE_OUTPUT -> frame -> { acceptOutput(frame); acceptOutput(frame); };
                case POST_OUTPUT_THROW -> frame -> { acceptOutput(frame); throw new IllegalStateException("synthetic post-output failure"); };
            };
            var builder = Pipeline.builder();
            for (int i = 0; i < scenario.stages(); i++) {
                int index = i;
                Processor stage = (frame, next) -> {
                    enterStage(index, frame);
                    if (media != null) next.accept(frame);
                    else {
                        Value value = (Value) frame;
                        next.accept(new Value(value.sequence(), Long.rotateLeft(value.value() ^ (SALT + index), 7)));
                    }
                };
                if (scenario.envelope()) {
                    Processor wrapped = EnvelopeProcessor.wrap(stage, () -> nextId(index), () -> nextClock(index));
                    stage = new Processor() {
                        @Override public void process(Frame frame, FrameSink next) throws Exception {
                            checkThread();
                            if (metadataEntered++ != index) throw new IllegalStateException("envelope stage order mismatch");
                            checkEnvelope(frame, index);
                            wrapped.process(frame, next);
                        }
                        @Override public void onInterrupt() throws Exception { wrapped.onInterrupt(); }
                        @Override public void close() throws Exception { wrapped.close(); }
                    };
                }
                stages.add(stage); builder.add(stage);
            }
            pipeline = builder.build();
            if (scenario.session()) {
                session = pipeline.openSession(new SessionOptions(1), terminal);
                completion = session.completion();
                completion.whenComplete((ignored, failure) -> {
                    if (failure != null) replies.offer(new Reply(null, failure));
                });
            } else { session = null; completion = null; owner = caller; }
        }

        Frame invoke() throws Exception {
            if (Thread.currentThread() != caller || closed || active) throw new IllegalStateException("invalid probe caller or lifetime");
            if (operationFailure != null) rethrow(operationFailure);
            sequence = Math.incrementExact(sequence);
            entered = 0; metadataEntered = 0; emitted = 0; idCalls = 0; clockCalls = 0;
            output = null; witness = sequence;
            if (media != null) input = media[(int) ((sequence - 1) & 63)];
            else {
                Frame numeric = new Value(sequence, sequence);
                if (scenario.envelope()) {
                    baseTime = Math.multiplyExact(sequence, 32);
                    ids[0] = "input-" + sequence;
                    for (int i = 1; i <= scenario.stages(); i++) ids[i] = "stage-" + i + "-" + sequence;
                    input = new FrameEnvelope(numeric, ids[0], SESSION, TURN, CORRELATION, "", baseTime, baseTime + 1, baseTime + 2);
                } else input = numeric;
            }
            active = true;
            try {
                Frame result;
                if (session == null) {
                    pipeline.process(input, terminal);
                    result = output;
                } else {
                    if (!session.trySubmit(input)) throw new IllegalStateException("negative control rejected input");
                    Reply reply = replies.poll(watchdogNanos, TimeUnit.NANOSECONDS);
                    if (reply == null) throw new IllegalStateException("negative control produced no acknowledgment");
                    if (reply.failure() != null) rethrow(reply.failure());
                    result = reply.frame();
                    awaitProcessed(sequence);
                }
                if (entered != scenario.stages() || emitted != 1 || result == null || result != output
                        || successful + 1 != sequence) throw new IllegalStateException("incomplete invocation or terminal count mismatch");
                if (scenario.envelope() && (metadataEntered != scenario.stages() || idCalls != scenario.stages()
                        || clockCalls != scenario.stages())) throw new IllegalStateException("envelope supplier count mismatch");
                successful++;
                return result;
            } catch (Throwable failure) {
                operationFailure = failure;
                rethrow(failure);
                throw new AssertionError("unreachable");
            } finally { active = false; input = null; output = null; }
        }

        private void enterStage(int index, Frame frame) {
            checkThread();
            if (!active || entered++ != index) throw new IllegalStateException("stage count or order mismatch");
            if (media == null) checkNumeric(frame, index);
            else {
                if (frame != input) throw new IllegalStateException("media frame identity changed");
                witness = Long.rotateLeft(witness ^ (SALT + index), 7);
                if (witness != expected(index + 1)) throw new IllegalStateException("media sequence witness mismatch");
            }
        }

        private void acceptOutput(Frame frame) {
            checkThread();
            if (!active || entered != scenario.stages() || emitted++ != 0) throw new IllegalStateException("missing, duplicate or unordered terminal output");
            if (scenario.envelope()) checkEnvelope(frame, scenario.stages());
            else if (media == null) checkNumeric(frame, scenario.stages());
            else if (frame != input || witness != expected(scenario.stages())) throw new IllegalStateException("terminal media identity or sequence changed");
            output = frame;
            if (replies != null && !replies.offer(new Reply(frame, null))) throw new IllegalStateException("duplicate session acknowledgment");
        }

        private void checkNumeric(Frame frame, int index) {
            if (!(frame instanceof Value value) || value.sequence() != sequence || value.value() != expected(index)) {
                throw new IllegalStateException("numeric output failed independent oracle");
            }
        }

        private long expected(int index) { return Long.rotateLeft(sequence, index * 7) ^ masks[index]; }

        private void checkEnvelope(Frame frame, int index) {
            if (!(frame instanceof FrameEnvelope envelope) || !ids[index].equals(envelope.id())
                    || !(index == 0 ? "" : ids[index - 1]).equals(envelope.causationId())
                    || !SESSION.equals(envelope.sessionId()) || !TURN.equals(envelope.turnId())
                    || !CORRELATION.equals(envelope.correlationId()) || envelope.originTimeNanos() != baseTime
                    || envelope.ingestTimeNanos() != baseTime + 1 || envelope.emitTimeNanos() != baseTime + index + 2) {
                throw new IllegalStateException("envelope identity, causation or time mismatch");
            }
            checkNumeric(envelope.frame(), index);
        }

        private String nextId(int index) {
            if (idCalls++ != index) throw new IllegalStateException("ID supplier order mismatch");
            return ids[index + 1];
        }

        private long nextClock(int index) {
            if (clockCalls++ != index || idCalls != index + 1) throw new IllegalStateException("clock supplier order mismatch");
            return baseTime + index + 3;
        }

        private void checkThread() {
            Thread current = Thread.currentThread();
            if (owner == null && scenario.session() && current != caller) owner = current;
            if (current != owner) throw new IllegalStateException("stage or terminal ran on the wrong thread");
        }

        private Frame corrupt(Frame frame) {
            if (frame instanceof FrameEnvelope value) return new FrameEnvelope(value.frame(), value.id(), value.sessionId(),
                    value.turnId(), "wrong-correlation", value.causationId(), value.originTimeNanos(), value.ingestTimeNanos(), value.emitTimeNanos());
            if (frame instanceof Value value) return new Value(value.sequence(), value.value() ^ 1);
            return media[(int) (sequence & 63)];
        }

        private void awaitProcessed(long expected) throws Exception {
            SessionMetrics metrics = session.metrics();
            if (processed(metrics, expected)) return;
            long started = System.nanoTime();
            do {
                if (metrics.failed() != 0 || metrics.rejected() != 0 || metrics.cancelled() != 0 || completion.isCompletedExceptionally()) {
                    if (completion.isDone()) completion.get(watchdogNanos, TimeUnit.NANOSECONDS);
                    throw new IllegalStateException("negative control failed after output");
                }
                if (metrics.processed() > expected || metrics.accepted() != expected) throw new IllegalStateException("negative control count mismatch");
                if (System.nanoTime() - started >= watchdogNanos) throw new IllegalStateException("negative control processing did not settle");
                LockSupport.parkNanos(1_000);
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("probe caller interrupted");
                metrics = session.metrics();
            } while (!processed(metrics, expected));
        }

        private static boolean processed(SessionMetrics metrics, long expected) {
            return metrics.processed() == expected && metrics.accepted() == expected && metrics.queueDepth() == 0
                    && metrics.failed() == 0 && metrics.rejected() == 0 && metrics.cancelled() == 0;
        }

        void assertDrained() throws Exception {
            if (operationFailure != null) rethrow(operationFailure);
            if (active || successful == 0 || successful != sequence) throw new IllegalStateException("probe did not settle every invocation");
            if (session != null) {
                if (!processed(session.metrics(), successful) || !replies.isEmpty() || completion.isDone()) {
                    throw new IllegalStateException("negative control retained or failed work");
                }
            }
        }

        long successful() { return successful; }
        Thread owner() { return owner; }
        Frame mediaAt(int index) { return media[index]; }

        @Override public void close() throws Exception {
            if (closed) return;
            closed = true;
            if (session != null) {
                Throwable cleanupFailure = null;
                if (operationFailure == null) session.finish(); else session.close();
                try { completion.get(watchdogNanos, TimeUnit.NANOSECONDS); }
                catch (ExecutionException | java.util.concurrent.CancellationException error) {
                    if (operationFailure == null) cleanupFailure = error;
                } finally {
                    session.close();
                    if (owner != null) {
                        owner.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(watchdogNanos)));
                        if (owner.isAlive()) throw new IllegalStateException("negative control worker did not terminate");
                    }
                    replies.clear();
                }
                if (cleanupFailure != null) rethrow(cleanupFailure);
            } else {
                Throwable failure = null;
                for (int i = stages.size() - 1; i >= 0; i--) {
                    try { stages.get(i).close(); }
                    catch (Throwable error) { if (failure == null) failure = error; else if (failure != error) failure.addSuppressed(error); }
                }
                if (failure != null) rethrow(failure);
            }
        }
    }

    static Frame[] mediaFrames(String kind) {
        Frame[] frames = new Frame[64];
        for (int i = 0; i < frames.length; i++) {
            int size = kind.equals("audio") ? 640 : 65_536;
            byte[] bytes = new byte[size];
            for (int j = 0; j < size; j++) bytes[j] = pattern(i, j);
            Frame frame = switch (kind) {
                case "audio" -> new Frame.Audio(ByteBuffer.wrap(bytes), 16_000, 1, "synthetic-track", i * 320L);
                case "image" -> new Frame.Image(ByteBuffer.wrap(bytes), "image/png");
                default -> throw new IllegalArgumentException("unknown media fixture");
            };
            java.util.Arrays.fill(bytes, (byte) 0);
            ByteBuffer first = data(frame), second = data(frame);
            if (!first.isReadOnly() || !second.isReadOnly() || second.position() != 0 || second.remaining() != size) {
                throw new IllegalStateException("media accessor is not an independent read-only view");
            }
            if (frame instanceof Frame.Audio audio && (audio.sampleRate() != 16_000 || audio.channels() != 1
                    || audio.startSample() != i * 320L || first.order() != ByteOrder.LITTLE_ENDIAN)) {
                throw new IllegalStateException("PCM fixture metadata changed");
            }
            for (int j = 0; j < size; j++) if (first.get() != pattern(i, j)) throw new IllegalStateException("media bytes were not snapshotted");
            if (second.position() != 0) throw new IllegalStateException("media accessor positions are shared");
            try { second.put(0, (byte) 1); throw new IllegalStateException("media buffer is mutable"); }
            catch (ReadOnlyBufferException expected) { /* Setup validates the immutable-byte contract. */ }
            frames[i] = frame;
        }
        return frames;
    }

    private static byte pattern(int frame, int offset) { return (byte) (frame * 13 + offset * 31 + 1); }
    private static ByteBuffer data(Frame frame) {
        return frame instanceof Frame.Audio audio ? audio.data() : ((Frame.Image) frame).data();
    }

    private static void rethrow(Throwable failure) throws Exception {
        if (failure instanceof Error error) throw error;
        if (failure instanceof Exception exception) throw exception;
        throw new IllegalStateException("direct-state probe failed", failure);
    }
}
