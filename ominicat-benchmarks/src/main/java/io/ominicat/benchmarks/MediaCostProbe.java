package io.ominicat.benchmarks;

import io.ominicat.core.Frame;
import io.ominicat.core.FrameSink;
import io.ominicat.core.Pipeline;
import io.ominicat.core.Processor;
import java.io.InputStream;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ReadOnlyBufferException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
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
 * Media construction (defensive snapshot) measured separately from dispatch of a
 * prebuilt immutable frame, per the iteration-plan media-cost increment. Numeric
 * kernel results exclude media copying; this probe isolates that copy and the
 * pass-through dispatch over an already-constructed frame.
 *
 * <p>Coverage: 640 and 3,840 byte PCM16 blocks (20 ms at 16 kHz mono and stereo)
 * and 64 KiB / 1 MiB synthetic image payloads. Reusable source buffers are
 * prepared outside timing and mutated between operations so the copy cannot be
 * constant-folded or elided; every constructed frame's snapshot semantics are
 * re-verified by an exact-oracle accessor check inside the timed method.
 *
 * <p>Scope: local frame construction and in-memory dispatch only. No codec,
 * network, model or real-time media capacity is measured or claimed. Use JMH
 * {@code -jvmArgsAppend} with {@code -Dominicat.probe.harnessSha256} and
 * {@code -Dominicat.probe.coreSha256} to bind paired revisions; printed hashes
 * exclude paths and a supplied mismatch fails setup.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class MediaCostProbe {
    private static final long SALT = 0x9e3779b97f4a7c15L;
    private static final int POOL = 64;

    /** pcm-640, pcm-3840, image-64k, image-1m select payload; plus mode via benchmark method names. */
    @Param({"pcm-640", "pcm-3840", "image-64k", "image-1m"}) public String payload;
    byte[][] sources;
    int size;
    Frame[] prebuilt;
    Pipeline passThrough;
    long sequence;
    long witnessed;

    @Setup(Level.Trial) public void setup() throws Exception {
        verifyOrigin(MediaCostProbe.class, "ominicat.probe.harnessSha256", "PROBE_HARNESS_SHA256=");
        verifyOrigin(Frame.class, "ominicat.probe.coreSha256", "PROBE_CORE_SHA256=");
        prepareFixtures();
    }

    /** Fixture preparation separated from JAR-origin verification so unit tests can exercise the oracle. */
    void prepareFixtures() {
        size = switch (payload) {
            case "pcm-640" -> 640;
            case "pcm-3840" -> 3_840;
            case "image-64k" -> 65_536;
            case "image-1m" -> 1_048_576;
            default -> throw new IllegalArgumentException("unsupported payload: " + payload);
        };
        sources = new byte[POOL][size];
        for (int i = 0; i < POOL; i++) {
            for (int j = 0; j < size; j++) sources[i][j] = pattern(i, j);
        }
        prebuilt = new Frame[POOL];
        for (int i = 0; i < POOL; i++) prebuilt[i] = construct(i);
        // The construction oracle must accept every prebuilt frame before timing starts.
        for (int i = 0; i < POOL; i++) verifyFrame(prebuilt[i], i);
        Pipeline.Builder builder = Pipeline.builder();
        for (int index = 0; index < 4; index++) {
            int position = index;
            builder.add((Frame frame, FrameSink next) -> {
                if (frame != prebuilt[(int) (sequence - 1 & (POOL - 1))]) {
                    throw new IllegalStateException("pass-through stage changed frame identity");
                }
                witnessed = Long.rotateLeft(witnessed ^ (SALT + position), 7);
                next.accept(frame);
            });
        }
        passThrough = builder.build();
    }

    /** Construction only: fresh defensive copy plus accessor validation; input mutation defeats constant folding. */
    @Benchmark public Frame construct(Blackhole blackhole) throws Exception {
        int index = (int) (sequence++ & (POOL - 1));
        byte[] source = sources[index];
        source[size - 1] ^= 1; // force a genuinely different snapshot each operation
        Frame frame = construct(index);
        verifyFrame(frame, index);
        blackhole.consume(frame);
        return frame;
    }

    /** Dispatch only: four real pass-through pipeline stages over one prebuilt immutable frame. */
    @Benchmark public Frame dispatch(Blackhole blackhole) throws Exception {
        int index = (int) (sequence++ & (POOL - 1));
        witnessed = sequence;
        Frame input = prebuilt[index];
        Captured captured = new Captured();
        passThrough.process(input, captured);
        if (captured.output != input) throw new IllegalStateException("terminal identity changed");
        if (witnessed != expectedWitness(sequence)) throw new IllegalStateException("stage order witness mismatch");
        blackhole.consume(captured.output);
        return captured.output;
    }

    private static final class Captured implements FrameSink {
        private Frame output;
        @Override public void accept(Frame frame) {
            if (output != null) throw new IllegalStateException("duplicate terminal output");
            output = frame;
        }
    }

    static long expectedWitness(long sequence) {
        long witness = sequence;
        for (int index = 0; index < 4; index++) witness = Long.rotateLeft(witness ^ (SALT + index), 7);
        return witness;
    }

    Frame construct(int index) {
        return payload.startsWith("pcm")
                ? new Frame.Audio(ByteBuffer.wrap(sources[index]), 16_000,
                        payload.equals("pcm-3840") ? 2 : 1, "synthetic-track", index * 320L)
                : new Frame.Image(ByteBuffer.wrap(sources[index]), "image/png");
    }

    void verifyFrame(Frame frame, int index) {
        if (payload.startsWith("pcm")) {
            Frame.Audio audio = (Frame.Audio) frame;
            if (audio.sampleRate() != 16_000 || audio.channels() != (payload.equals("pcm-3840") ? 2 : 1)
                    || audio.startSample() != index * 320L || !"synthetic-track".equals(audio.streamId())) {
                throw new IllegalStateException("PCM fixture metadata changed");
            }
        } else if (!"image/png".equals(((Frame.Image) frame).mimeType())) {
            throw new IllegalStateException("image fixture metadata changed");
        }
        ByteBuffer first = data(frame), second = data(frame);
        if (!first.isReadOnly() || !second.isReadOnly() || second.position() != 0
                || second.remaining() != size || first.order() != ByteOrder.LITTLE_ENDIAN && payload.startsWith("pcm")) {
            throw new IllegalStateException("media accessor is not an independent read-only view");
        }
        int step = Math.max(1, size / 16);
        for (int j = 0; j < size; j += step) {
            if (first.get() != sources[index][j]) throw new IllegalStateException("media bytes were not snapshotted");
            first.position(Math.min(size, j + step));
        }
        if (second.position() != 0) throw new IllegalStateException("media accessor positions are shared");
        try { second.put(0, (byte) 0); throw new IllegalStateException("media view must be read-only"); }
        catch (ReadOnlyBufferException expected) { }
    }

    private static ByteBuffer data(Frame frame) {
        return frame instanceof Frame.Audio audio ? audio.data() : ((Frame.Image) frame).data();
    }

    private static byte pattern(int i, int j) {
        return (byte) (i * 31 + j * 17 + 7);
    }

    @TearDown(Level.Trial) public void teardown() {
        if (sequence == 0) throw new IllegalStateException("media probe executed no operations");
        for (int i = 0; i < POOL; i++) {
            ByteBuffer view = data(prebuilt[i]);
            if (!view.isReadOnly() || view.remaining() != size) {
                throw new IllegalStateException("prebuilt media mutated during the trial");
            }
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
}
