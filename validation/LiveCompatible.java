import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ominicat.core.Frame;
import io.ominicat.core.Processor;
import io.ominicat.providers.ChatFactory;
import io.ominicat.providers.SpeechFactory;
import io.ominicat.providers.TranscriptionFactory;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import javax.imageio.ImageIO;

/** Explicit opt-in live checks: one request per configured capability, synthetic inputs only. */
public class LiveCompatible {
    private static final ObjectMapper JSON = new ObjectMapper();
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: LiveCompatible.java private-config.json output.json");
        JsonNode config = JSON.readTree(Files.readString(Path.of(args[0])));
        String base = required(config, "baseUrl"), credential = required(config, "apiKeyEnv");
        if (System.getenv(credential) == null || System.getenv(credential).isBlank()) throw new IllegalArgumentException("Test credential environment variable is unavailable");
        var results = new ArrayList<Map<String, Object>>();
        if (config.hasNonNull("chatModel")) {
            var options = options(base, credential, config.path("chatModel").asText());
            options.put("maxOutputTokens", "32"); options.put("systemPrompt", "Follow the user's output format exactly.");
            try (Processor processor = new ChatFactory().create(options)) {
                results.add(run("streamed-text", processor, new Frame.Text("Return exactly OMINICAT_OK and nothing else."), false));
            }
            if (config.path("vision").asBoolean(false)) {
                options.put("imagePrompt", "What is the dominant color? Reply with exactly BLUE.");
                var image = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
                var graphics = image.createGraphics(); graphics.setColor(Color.BLUE); graphics.fillRect(0, 0, 256, 256); graphics.dispose();
                var bytes = new ByteArrayOutputStream(); ImageIO.write(image, "png", bytes);
                try (Processor processor = new ChatFactory().create(options)) {
                    results.add(run("vision", processor, new Frame.Image(ByteBuffer.wrap(bytes.toByteArray()), "image/png"), false));
                }
            }
        }
        if (config.hasNonNull("transcriptionModel")) {
            Path fixture = Path.of(required(config, "pcmFixture"));
            if (Files.size(fixture) > 320000) throw new IllegalArgumentException("Speech fixture exceeds10s budget");
            byte[] pcm = Files.readAllBytes(fixture);
            if (pcm.length == 0 || pcm.length > 320000 || pcm.length % 2 != 0) throw new IllegalArgumentException("Speech fixture must be at most10s PCM16LE/16kHz/mono");
            var options = options(base, credential, config.path("transcriptionModel").asText());
            try (Processor processor = new TranscriptionFactory().create(options)) {
                processor.process(new Frame.Audio(ByteBuffer.wrap(pcm), 16000, 1, "fixture", 0), ignored -> { });
                results.add(run("transcription", processor, new Frame.Control(Frame.Signal.TURN_END), false));
            }
        }
        if (config.hasNonNull("speechModel")) {
            var options = options(base, credential, config.path("speechModel").asText());
            options.put("voice", config.path("voice").asText("alloy"));
            try (Processor processor = new SpeechFactory().create(options)) {
                processor.process(new Frame.Text("The blue cat has seven stars."), ignored -> { });
                results.add(run("speech", processor, new Frame.Control(Frame.Signal.TURN_END), true));
            }
        }
        if (results.isEmpty()) throw new IllegalArgumentException("Specify at least one test model");
        var result = new LinkedHashMap<String, Object>();
        result.put("schema", "ominicat-live-validation-v1"); result.put("input", "synthetic marker / solid blue image / locally generated speech");
        result.put("requests", results); result.put("allConfiguredCapabilitiesPassed", results.stream().allMatch(r -> Boolean.TRUE.equals(r.get("passed"))));
        Files.writeString(Path.of(args[1]), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result) + "\n");
        System.out.println("Live checks completed; credentials and endpoint are omitted from the report.");
        if (Boolean.FALSE.equals(result.get("allConfiguredCapabilitiesPassed"))) throw new IllegalStateException("Live capability checks failed; inspect sanitized results");
    }
    private static Map<String, Object> run(String capability, Processor processor, Frame input, boolean audioExpected) {
        long start = System.nanoTime(); var first = new AtomicLong(); var frames = new ArrayList<Frame>();
        var result = new LinkedHashMap<String, Object>(); result.put("capability", capability);
        try {
            processor.process(input, frame -> { first.compareAndSet(0, System.nanoTime()); frames.add(frame); });
            String text = frames.stream().map(frame -> frame instanceof Frame.Text value ? value.text() : frame instanceof Frame.Transcript value ? value.text() : "").reduce("", String::concat);
            boolean passed;
            if (audioExpected) {
                var audio = frames.stream().filter(Frame.Audio.class::isInstance).map(Frame.Audio.class::cast).toList();
                long bytes = 0; double energy = 0; long samples = 0;
                for (Frame.Audio frame : audio) {
                    var pcm = frame.data().order(ByteOrder.LITTLE_ENDIAN); bytes += pcm.remaining();
                    while (pcm.hasRemaining()) { double value = pcm.getShort() / 32768.0; energy += value * value; samples++; }
                }
                double rms = samples == 0 ? 0 : Math.sqrt(energy / samples);
                result.put("pcmBytes", bytes); result.put("rms", rms);
                passed = bytes > 9600 && rms > .0005;
            } else if (capability.equals("transcription")) {
                String normalized = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", "").replace("seven", "7").replaceAll(" +", " ").strip();
                passed = normalized.contains("blue cat") && normalized.contains("7 stars");
            } else {
                passed = capability.equals("vision") ? text.strip().equalsIgnoreCase("BLUE") : text.strip().equals("OMINICAT_OK");
            }
            result.put("textCharacters", text.length());
            result.put("textSha256", java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
            result.put("passed", passed); result.put("outputFrames", frames.size());
        } catch (Exception error) {
            result.put("passed", false); result.put("errorClass", error.getClass().getName());
            if (error instanceof io.ominicat.providers.ProviderException provider) result.put("httpStatus", provider.status());
        }
        result.put("firstOutputMillis", first.get() == 0 ? null : (first.get() - start) / 1e6);
        result.put("totalMillis", (System.nanoTime() - start) / 1e6); return result;
    }
    private static LinkedHashMap<String, String> options(String base, String env, String model) {
        var options = new LinkedHashMap<String, String>(); options.put("baseUrl", base); options.put("apiKeyEnv", env);
        options.put("model", model); options.put("timeoutSeconds", "30"); options.put("maxResponseBytes", "1048576"); return options;
    }
    private static String required(JsonNode node, String name) {
        String value = node.path(name).asText(); if (value.isBlank()) throw new IllegalArgumentException("Required test config: " + name); return value;
    }
}
