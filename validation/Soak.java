import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import io.ominicat.core.WorkerThreads;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/** Mixed-frame, sustained socket/churn correctness probe with bounded latency histograms. */
public class Soak {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final AtomicBoolean RUNNING = new AtomicBoolean(true);
    private static final AtomicBoolean MEASURING = new AtomicBoolean();
    private static final AtomicLong FRAMES = new AtomicLong(), CONNECTIONS = new AtomicLong(), FAILURES = new AtomicLong();
    private static AtomicLongArray CLIENT_FRAMES;
    private static final Map<String, LongAdder> KINDS = Map.of("text", new LongAdder(), "audio", new LongAdder(),
            "transcript", new LongAdder(), "image", new LongAdder(), "envelope", new LongAdder());
    private static final Histogram LATENCY = new Histogram();
    private static final Histogram CHURN_LATENCY = new Histogram();
    private static final ArrayBlockingQueue<String> ERRORS = new ArrayBlockingQueue<>(32);
    private static final String PCM = Base64.getEncoder().encodeToString(new byte[640]);
    private static final String IMAGE = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aI6kAAAAASUVORK5CYII=";

    private static final class Histogram {
        private final long[] buckets = new long[110001];
        private long count, maximum;
        synchronized void add(long nanos) {
            long micros = (nanos + 999) / 1000;
            int index = micros <= 100000 ? (int) micros : (int) Math.min(110000, 100000 + (micros - 100000 + 999) / 1000);
            buckets[index]++; count++; maximum = Math.max(maximum, nanos);
        }
        synchronized LinkedHashMap<String, Object> snapshot() {
            var result = new LinkedHashMap<String, Object>();
            result.put("count", count); result.put("p50MicrosUpperBound", percentile(.5));
            result.put("p95MicrosUpperBound", percentile(.95)); result.put("p99MicrosUpperBound", percentile(.99));
            result.put("maximumMicros", maximum / 1000.0); return result;
        }
        private long percentile(double p) {
            long target = (long) Math.ceil(count * p), seen = 0;
            if (count == 0) return 0;
            for (int index = 0; index < buckets.length; index++) {
                seen += buckets[index];
                if (seen >= target) return index <= 100000 ? index : 100000L + (index - 100000L) * 1000;
            }
            return Long.MAX_VALUE;
        }
    }
    private static final class Listener implements WebSocket.Listener {
        final ArrayBlockingQueue<String> replies = new ArrayBlockingQueue<>(4);
        final CompletableFuture<Integer> closed = new CompletableFuture<>();
        private final StringBuilder pending = new StringBuilder();
        @Override public void onOpen(WebSocket socket) { socket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence text, boolean last) {
            if (pending.length() + text.length() > 300000) { socket.abort(); replies.offer("INVALID"); return null; }
            pending.append(text);
            if (last) { if (!replies.offer(pending.toString())) socket.abort(); pending.setLength(0); }
            socket.request(1); return null;
        }
        @Override public void onError(WebSocket socket, Throwable failure) { replies.offer("ERROR"); }
        @Override public CompletionStage<?> onClose(WebSocket socket, int code, String reason) { closed.complete(code); replies.offer("CLOSED:" + code); return null; }
        void exchange(WebSocket socket, ObjectNode input, boolean churn) throws Exception {
            String encoded = input.toString();
            JsonNode expected = JSON.readTree(encoded);
            long before = System.nanoTime();
            socket.sendText(encoded, true).get(5, TimeUnit.SECONDS);
            String reply = replies.poll(5, TimeUnit.SECONDS);
            if (reply == null) throw new IllegalStateException("echo deadline");
            JsonNode actual = JSON.readTree(reply);
            if (input.path("type").asText().equals("envelope")) {
                if (!actual.path("frame").equals(expected.path("frame"))
                        || !actual.path("correlationId").equals(expected.path("correlationId"))
                        || !actual.path("sessionId").equals(expected.path("sessionId"))
                        || !actual.path("turnId").equals(expected.path("turnId"))
                        || actual.path("id").asText().isBlank() || actual.path("id").equals(expected.path("id"))
                        || actual.path("originTimeNanos").asLong() != expected.path("originTimeNanos").asLong()
                        || actual.path("ingestTimeNanos").asLong() != expected.path("ingestTimeNanos").asLong()
                        || actual.path("emitTimeNanos").asLong() < expected.path("emitTimeNanos").asLong()
                        || !actual.path("causationId").asText().equals(input.path("id").asText())) throw new IllegalStateException("envelope corruption");
            } else if (!actual.equals(expected)) throw new IllegalStateException("payload corruption: " + input.path("type").asText());
            if (MEASURING.get()) {
                FRAMES.incrementAndGet(); KINDS.get(input.path("type").asText()).increment();
                (churn ? CHURN_LATENCY : LATENCY).add(System.nanoTime() - before);
            }
        }
    }
    private static ObjectNode payload(int client, long sequence) {
        String id = client + ":" + sequence;
        int type = (int) (sequence % 100);
        var node = JSON.createObjectNode();
        if (type < 72) return node.put("type", "text").put("text", id + "-" + "x".repeat(sequence % 500 == 0 ? 32768 : 512));
        if (type < 87) return node.put("type", "audio").put("data", PCM).put("sampleRate", 16000).put("channels", 1).put("streamId", "source").put("startSample", sequence * 320);
        if (type < 97) return node.put("type", "transcript").put("text", "Sample " + id).put("speaker", "person").put("role", "user")
                .put("startMillis", sequence * 20).put("endMillis", sequence * 20 + 20).put("segmentId", id).put("revision", 1).put("finalResult", true).put("confidence", -1.0);
        if (type < 99) return node.put("type", "image").put("data", IMAGE).put("mimeType", "image/png");
        node.put("type", "envelope").put("id", id).put("sessionId", "client-" + client).put("turnId", "turn-" + sequence)
                .put("correlationId", id).put("causationId", "").put("originTimeNanos", 0).put("ingestTimeNanos", 0).put("emitTimeNanos", 0);
        node.set("frame", JSON.createObjectNode().put("type", "text").put("text", id)); return node;
    }
    private static void failure(Throwable error) {
        FAILURES.incrementAndGet(); ERRORS.offer(error.getClass().getSimpleName() + ":" + String.valueOf(error.getMessage()).substring(0, Math.min(150, String.valueOf(error.getMessage()).length())));
    }
    private static JsonNode get(String address) throws Exception {
        return JSON.readTree(HTTP.send(HttpRequest.newBuilder(URI.create(address)).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString()).body());
    }
    private static String metrics(String base) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(base + "/metrics")).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
    }
    private static JsonNode gc(String base) throws Exception {
        return JSON.readTree(HTTP.send(HttpRequest.newBuilder(URI.create(base + "/gc")).timeout(Duration.ofSeconds(10)).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString()).body());
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 6) throw new IllegalArgumentException("Usage: Soak.java httpBase observerBase measuredSeconds warmupSeconds clients outputDir");
        String base = args[0], observer = args[1];
        int seconds = Integer.parseInt(args[2]), warmup = Integer.parseInt(args[3]), clients = Integer.parseInt(args[4]);
        if (seconds < 1 || seconds > 86400 || warmup < 0 || clients < 1 || clients > 512) throw new IllegalArgumentException("invalid workload bounds");
        Path output = Path.of(args[5]); Files.createDirectories(output);
        CLIENT_FRAMES = new AtomicLongArray(clients + 4);
        URI ws = URI.create(base.replaceFirst("^http", "ws") + "/ws");
        List<CompletableFuture<Void>> tasks = new ArrayList<>();
        long begun = System.nanoTime();
        var workers = WorkerThreads.executor("ominicat-soak-", clients + 4);
        try (var samples = Files.newBufferedWriter(output.resolve("samples.jsonl"))) {
            for (int client = 0; client < clients + 4; client++) {
                final int id = client; final boolean churn = client >= clients;
                var done = new CompletableFuture<Void>(); tasks.add(done);
                workers.submit(() -> {
                    long sequence = 0;
                    try {
                        while (RUNNING.get()) {
                            WebSocket socket = null;
                            try {
                                var listener = new Listener();
                                socket = HTTP.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5)).buildAsync(ws, listener).get(7, TimeUnit.SECONDS);
                                if (MEASURING.get()) CONNECTIONS.incrementAndGet();
                                do {
                                    long start = System.nanoTime(); listener.exchange(socket, payload(id, sequence++), churn);
                                    if (MEASURING.get()) CLIENT_FRAMES.incrementAndGet(id);
                                    if (churn) break;
                                    long rest = 50_000_000 - (System.nanoTime() - start);
                                    if (rest > 0) TimeUnit.NANOSECONDS.sleep(rest);
                                } while (RUNNING.get());
                                if (sequence % 2 == 0) {
                                    socket.sendClose(1000, "validation").get(2, TimeUnit.SECONDS);
                                    listener.closed.get(2, TimeUnit.SECONDS);
                                }
                            } catch (Throwable error) { if (RUNNING.get()) failure(error); }
                            finally { if (socket != null) socket.abort(); }
                            if (!churn && RUNNING.get()) break; // Unexpected persistent disconnect is not silently healed.
                            if (churn) Thread.sleep(100);
                        }
                        done.complete(null);
                    } catch (Throwable error) { failure(error); done.completeExceptionally(error); }
                });
            }
            long warmupEnd = System.nanoTime() + TimeUnit.SECONDS.toNanos(warmup);
            while (System.nanoTime() < warmupEnd) Thread.sleep(1000);
            JsonNode baseline = gc(observer); MEASURING.set(true);
            long measuredStart = System.nanoTime(), deadline = measuredStart + TimeUnit.SECONDS.toNanos(seconds);
            long nextSample = measuredStart;
            while (System.nanoTime() < deadline) {
                if (System.nanoTime() >= nextSample) {
                    var sample = new LinkedHashMap<String, Object>();
                    sample.put("elapsedSeconds", (System.nanoTime() - measuredStart) / 1e9);
                    sample.put("frames", FRAMES.get()); sample.put("connections", CONNECTIONS.get()); sample.put("failures", FAILURES.get());
                    sample.put("runtime", get(observer + "/stats")); sample.put("metrics", metrics(base)); sample.put("latency", LATENCY.snapshot());
                    samples.write(JSON.writeValueAsString(sample)); samples.newLine(); samples.flush();
                    System.out.printf(java.util.Locale.ROOT, "SOAK %.0fs frames=%d connections=%d failures=%d heap=%s fds=%s%n", sample.get("elapsedSeconds"), FRAMES.get(), CONNECTIONS.get(), FAILURES.get(), ((JsonNode) sample.get("runtime")).path("heapUsedBytes"), ((JsonNode) sample.get("runtime")).path("openFileDescriptors"));
                    nextSample += TimeUnit.SECONDS.toNanos(30);
                }
                Thread.sleep(200);
            }
            double measuredElapsed = (System.nanoTime() - measuredStart) / 1e9;
            MEASURING.set(false); RUNNING.set(false);
            CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).get(15, TimeUnit.SECONDS);
            String drained = metrics(base);
            long drainDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!drained(drained) && System.nanoTime() < drainDeadline) { Thread.sleep(100); drained = metrics(base); }
            Thread.sleep(2500); // Also allow the2s forced-close task references to retire.
            JsonNode after = gc(observer); drained = metrics(base);
            var result = new LinkedHashMap<String, Object>();
            result.put("measuredSeconds", measuredElapsed);
            result.put("requestedMeasuredSeconds", seconds); result.put("warmupSeconds", warmup); result.put("persistentClients", clients); result.put("churnClients", 4);
            result.put("measuredFrames", FRAMES.get()); result.put("newMeasuredConnections", CONNECTIONS.get()); result.put("failures", FAILURES.get());
            result.put("steadyLatency", LATENCY.snapshot()); result.put("churnLatency", CHURN_LATENCY.snapshot());
            result.put("afterWarmupFullGc", baseline); result.put("afterDrainFullGc", after); result.put("drainedMetrics", drained);
            result.put("boundedErrorExamples", new ArrayList<>(ERRORS)); result.put("totalSeconds", (System.nanoTime() - begun) / 1e9);
            var progress = new ArrayList<Long>(); boolean progressed = true;
            for (int client = 0; client < clients + 4; client++) {
                long count = CLIENT_FRAMES.get(client); progress.add(count);
                if (count < (client < clients ? Math.max(1, seconds * 10L) : 1)) progressed = false;
            }
            result.put("measuredFramesPerClient", progress); result.put("allClientsProgressed", progressed);
            var kinds = new LinkedHashMap<String, Long>();
            for (String name : List.of("text", "audio", "transcript", "image", "envelope")) kinds.put(name, KINDS.get(name).sum());
            result.put("validatedFramesByType", kinds);
            Files.writeString(output.resolve("result.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result) + "\n");
            if (FAILURES.get() != 0 || !progressed || !drained(drained)) throw new IllegalStateException("soak correctness, progress or drain failure");
        } finally {
            RUNNING.set(false);
            workers.shutdownNow();
            if (!workers.awaitTermination(15, TimeUnit.SECONDS)) throw new IllegalStateException("probe workers did not stop");
            if ((Object) HTTP instanceof AutoCloseable closeable) closeable.close();
        }
    }
    private static boolean drained(String metrics) {
        for (String name : List.of("ominicat_active_sessions", "ominicat_admitted_sessions", "ominicat_live_pipeline_workers", "ominicat_pending_output_bytes")) {
            if (!java.util.regex.Pattern.compile("(?m)^" + name + " 0$").matcher(metrics).find()) return false;
        }
        return true;
    }
}
