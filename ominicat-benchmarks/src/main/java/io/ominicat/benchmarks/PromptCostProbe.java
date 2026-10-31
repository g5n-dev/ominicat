package io.ominicat.benchmarks;

import io.ominicat.conversation.ConversationSnapshot;
import io.ominicat.conversation.ConversationWindow;
import io.ominicat.conversation.PromptTemplateProcessor;
import io.ominicat.core.Frame;
import io.ominicat.core.FrameSink;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URL;
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
 * Synthetic prompt-rendering probe, deliberately outside the existing *Benchmark*
 * profile selection. Time includes incoming transcript/segment-ID construction,
 * real window insertion/eviction, rendering, exact output validation and consumption.
 * It excludes pipeline scheduling, codecs, providers and network/model execution.
 *
 * <p>Trial setup uses reflection only to seed the existing private window without
 * invoking rendering once per seed; that would make baseline setup quadratic.
 * All retained turns have identical text, so a full sliding window has a constant,
 * independently assembled expected output despite unique incoming segment IDs.
 * Setup, origin hashing and teardown are outside the measured method.
 *
 * <p>Use JMH {@code -jvmArgsAppend} to pass
 * {@code -Dominicat.probe.conversationSha256=<expected JAR SHA-256>} and
 * {@code -Dominicat.probe.coreSha256=<expected JAR SHA-256>} to each fork when
 * selecting baseline/head dependencies through classpath precedence. Actual loaded
 * JAR hashes are printed without paths; a supplied mismatch fails setup.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class PromptCostProbe {
    private static final String SPEAKER = "user";
    private static final String TEXT = "Summarize this short input note.";
    private static final String ROLE = "synthetic assistant";
    private static final String CONTEXT = "local prompt cost probe";
    private static final String INSTRUCTION = "Summarize the latest input.";
    private static final String TEMPLATE = "Role: {{role}}\nContext: {{context}}\n"
            + "Instruction: {{instruction}}\nLatest {{speaker}}: {{transcript}}";
    private static final String HISTORY_LINE = SPEAKER + ": " + TEXT;
    private static final int FULL_PROMPT_CHARS = 1_048_576;
    private static final int TRIMMED_PROMPT_CHARS = 256;

    @Param({"32", "256", "10000"}) public int maxTurns;
    @Param({"none", "full", "trimmed"}) public String history;

    private PromptTemplateProcessor processor;
    private ConversationWindow window;
    private FrameSink checkingSink;
    private String expected;
    private Frame.Text lastOutput;
    private long sequence;
    private long invocations;
    private long outputs;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        verifyOrigin(PromptCostProbe.class, "ominicat.probe.harnessSha256", "PROBE_HARNESS_SHA256=");
        verifyOrigin(PromptTemplateProcessor.class, "ominicat.probe.conversationSha256",
                "PROBE_CONVERSATION_SHA256=");
        verifyOrigin(Frame.class, "ominicat.probe.coreSha256", "PROBE_CORE_SHA256=");
        if (TEXT.length() != 32) throw new IllegalStateException("synthetic input must have 32 characters");
        boolean includeHistory;
        int promptLimit;
        switch (history) {
            case "none" -> { includeHistory = false; promptLimit = FULL_PROMPT_CHARS; }
            case "full" -> { includeHistory = true; promptLimit = FULL_PROMPT_CHARS; }
            case "trimmed" -> { includeHistory = true; promptLimit = TRIMMED_PROMPT_CHARS; }
            default -> throw new IllegalArgumentException("unsupported prompt history parameter");
        }
        String template = TEMPLATE + (includeHistory ? "\nHistory:\n{{history}}" : "");
        processor = new PromptTemplateProcessor(SPEAKER, template, ROLE, CONTEXT,
                INSTRUCTION, maxTurns, 1024, promptLimit);
        Field field = PromptTemplateProcessor.class.getDeclaredField("window");
        field.setAccessible(true);
        window = (ConversationWindow) field.get(processor);
        sequence = 0;
        invocations = 0;
        outputs = 0;
        for (int index = 0; index < maxTurns; index++) {
            if (!window.add(nextTranscript())) throw new IllegalStateException("seed turn was not retained");
        }

        // An independent literal oracle: do not call production render, substitute or snapshot.
        String prefix = "Role: " + ROLE + "\nContext: " + CONTEXT
                + "\nInstruction: " + INSTRUCTION + "\nLatest user: " + TEXT;
        if (!includeHistory) expected = prefix;
        else {
            prefix += "\nHistory:\n";
            int historyBudget = promptLimit - prefix.length();
            int retainedLines = Math.min(maxTurns, (historyBudget + 1) / (HISTORY_LINE.length() + 1));
            if (retainedLines < 1) throw new IllegalStateException("fixture does not fit the latest complete turn");
            if (history.equals("full") && retainedLines != maxTurns) {
                throw new IllegalStateException("full history fixture was unexpectedly truncated");
            }
            if (history.equals("trimmed") && retainedLines >= maxTurns) {
                throw new IllegalStateException("trimmed fixture must evict prompt history lines");
            }
            expected = prefix + HISTORY_LINE + ("\n" + HISTORY_LINE).repeat(retainedLines - 1);
        }
        checkingSink = frame -> {
            if (!(frame instanceof Frame.Text text) || !expected.equals(text.text())) {
                throw new IllegalStateException("prompt output does not match the independent literal oracle");
            }
            outputs = Math.incrementExact(outputs);
            lastOutput = text;
        };
    }

    /** Includes exact String.equals validation; it is the same oracle for both revisions. */
    @Benchmark
    public void render(Blackhole blackhole) throws Exception {
        Frame.Transcript transcript = nextTranscript();
        invocations = Math.incrementExact(invocations);
        processor.process(transcript, checkingSink);
        if (outputs != invocations) throw new IllegalStateException("expected exactly one prompt per new final turn");
        blackhole.consume(lastOutput);
    }

    private Frame.Transcript nextTranscript() {
        long next = Math.incrementExact(sequence);
        sequence = next;
        long start = Math.multiplyExact(next, 1000L);
        return new Frame.Transcript(TEXT, SPEAKER, start, Math.addExact(start, 100L),
                true, -1, "prompt-segment-" + next, 0, SPEAKER);
    }

    @TearDown(Level.Trial)
    public void teardown() {
        try {
            if (invocations == 0 || outputs != invocations) {
                throw new IllegalStateException("prompt probe did not validate every invocation");
            }
            ConversationSnapshot snapshot = window.snapshot();
            if (snapshot.turns().size() != maxTurns || snapshot.evictedTurns() != invocations) {
                throw new IllegalStateException("real window insertion/eviction did not match invocation count");
            }
            String latestId = snapshot.turns().get(snapshot.turns().size() - 1).segmentId();
            if (!latestId.equals("prompt-segment-" + sequence)) {
                throw new IllegalStateException("latest final turn was not retained");
            }
        } finally {
            processor.onInterrupt();
            checkingSink = null;
            lastOutput = null;
        }
        if (!window.snapshot().turns().isEmpty()) throw new IllegalStateException("interrupt did not reset prompt state");
    }

    private static void verifyOrigin(Class<?> type, String property, String logPrefix) throws Exception {
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
