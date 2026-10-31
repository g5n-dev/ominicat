import com.fasterxml.jackson.databind.ObjectMapper;
import io.ominicat.core.*;
import io.ominicat.conversation.*;
import java.io.BufferedReader;
import java.security.DigestInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Deterministic transcript replay with an external, local checkpoint example. */
public class Replay {
    public record Checkpoint(String inputSha256, String policyVersion, long nextLine, ConversationSnapshot snapshot) { }
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private static final String POLICY = "example-v1";
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: Replay.java input.jsonl checkpoint.json");
        Path input = Path.of(args[0]), checkpoint = Path.of(args[1]);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var stream = new DigestInputStream(Files.newInputStream(input), digest)) { stream.transferTo(java.io.OutputStream.nullOutputStream()); }
        String fingerprint = HexFormat.of().formatHex(digest.digest());
        var evaluator = new ConversationEvaluatorProcessor("speaker-b", List.of(
                PolicyRules.requiredPhrase("greeting", "hello"), PolicyRules.requiredPhrase("closing", "thank you")), 256, 8192, false, POLICY);
        long nextLine = 0;
        if (Files.exists(checkpoint)) {
            Checkpoint saved = JSON.readValue(checkpoint.toFile(), Checkpoint.class);
            if (!fingerprint.equals(saved.inputSha256()) || !POLICY.equals(saved.policyVersion()) || saved.nextLine() < 0) throw new IllegalArgumentException("checkpoint input/policy mismatch");
            evaluator.restore(saved.snapshot()); nextLine = saved.nextLine();
        }
        long lineNumber = 0;
        var ids = new AtomicLong();
        Pipeline pipeline = Pipeline.builder().add(EnvelopeProcessor.wrap(evaluator, () -> "derived-" + ids.incrementAndGet(), () -> 0L)).build();
        FrameSink output = frame -> {
            if (frame instanceof FrameEnvelope envelope && envelope.frame() instanceof EvaluationFrame) {
                System.out.println(JSON.writeValueAsString(envelope));
                if (System.out.checkError()) throw new java.io.IOException("result output failed");
            }
        };
        try (BufferedReader lines = Files.newBufferedReader(input)) {
            String line;
            while ((line = lines.readLine()) != null) {
                long position = lineNumber++;
                if (position < nextLine) continue;
                if (line.length() > 1_048_576) throw new IllegalArgumentException("input line exceeds example budget");
                ids.set(position * 100);
                Frame.Transcript transcript = JSON.readValue(line, Frame.Transcript.class);
                pipeline.process(new FrameEnvelope(transcript, "input-" + position, "replay", "turn-" + position, "replay", "", 0, 0, 0), output);
                // Checkpoint only after successful processing; durable output/checkpoint atomicity is application-owned.
                save(checkpoint, new Checkpoint(fingerprint, POLICY, position + 1, evaluator.snapshot()));
            }
        }
        if (nextLine > lineNumber) throw new IllegalArgumentException("checkpoint is beyond input end");
        ids.set(lineNumber * 100);
        pipeline.process(new FrameEnvelope(new Frame.Control(Frame.Signal.END), "end", "replay", "end", "replay", "", 0, 0, 0), output);
        evaluator.close();
    }
    private static void save(Path path, Checkpoint value) throws Exception {
        Path absolute = path.toAbsolutePath(); Files.createDirectories(absolute.getParent());
        Path temporary = Files.createTempFile(absolute.getParent(), "checkpoint-", ".tmp");
        try {
            JSON.writeValue(temporary.toFile(), value);
            Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
}
