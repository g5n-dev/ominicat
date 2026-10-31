import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Standalone JDK 17+ closed-loop transport probe; run against config/demo.yaml. */
public class WebSocketLoad {
    private static final class Listener implements WebSocket.Listener {
        private final ArrayBlockingQueue<String> replies = new ArrayBlockingQueue<>(4);
        private final StringBuilder pending = new StringBuilder();
        @Override public void onOpen(WebSocket socket) { socket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence text, boolean last) {
            if (pending.length() + text.length() > 4096) { socket.abort(); replies.offer("ERROR oversized reply"); return null; }
            pending.append(text);
            if (last) { if (!replies.offer(pending.toString())) socket.abort(); pending.setLength(0); }
            socket.request(1); return null;
        }
        @Override public void onError(WebSocket socket, Throwable error) { replies.offer("ERROR connection failed"); }
        @Override public CompletionStage<?> onClose(WebSocket socket, int status, String reason) { replies.offer("CLOSED " + status); return null; }
        void exchange(WebSocket socket, String value) throws Exception {
            socket.sendText("{\"type\":\"text\",\"text\":\"" + value + "\"}", true).get(10, TimeUnit.SECONDS);
            String reply = replies.poll(10, TimeUnit.SECONDS);
            if (reply == null || !reply.contains("\"text\":\"" + value + "\"")) throw new IllegalStateException("missing, failed or mismatched echo");
        }
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Usage: java scripts/WebSocketLoad.java ws://127.0.0.1:8080/ws connections framesPerConnection");
        URI address = URI.create(args[0]);
        int connections = Integer.parseInt(args[1]); int frames = Integer.parseInt(args[2]);
        long total = (long) connections * frames;
        if (connections < 1 || connections > 512 || frames < 1 || total > 10_000_000) throw new IllegalArgumentException("invalid bounded workload");
        long[] latencies = new long[(int) total];
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        CountDownLatch ready = new CountDownLatch(connections), start = new CountDownLatch(1);
        List<CompletableFuture<Void>> results = new ArrayList<>();
        var workers = Executors.newFixedThreadPool(connections, task -> {
            Thread thread = new Thread(task, "websocket-load"); thread.setDaemon(true); return thread;
        });
        try {
            for (int connection = 0; connection < connections; connection++) {
                final int id = connection;
                var result = new CompletableFuture<Void>(); results.add(result);
                workers.submit(() -> {
                    WebSocket socket = null;
                    try {
                        var listener = new Listener();
                        socket = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10)).buildAsync(address, listener).get(15, TimeUnit.SECONDS);
                        for (int warmup = 0; warmup < 100; warmup++) listener.exchange(socket, "warmup-" + id + "-" + warmup);
                        ready.countDown(); start.await();
                        for (int frame = 0; frame < frames; frame++) {
                            long before = System.nanoTime();
                            listener.exchange(socket, id + ":" + frame);
                            latencies[id * frames + frame] = System.nanoTime() - before;
                        }
                        result.complete(null);
                    } catch (Throwable error) { ready.countDown(); result.completeExceptionally(error); }
                    finally { if (socket != null) socket.abort(); }
                });
            }
            if (!ready.await(30, TimeUnit.SECONDS)) { start.countDown(); throw new IllegalStateException("client warmup timeout"); }
            for (var result : results) if (result.isCompletedExceptionally()) result.join();
            long before = System.nanoTime(); start.countDown();
            CompletableFuture.allOf(results.toArray(CompletableFuture[]::new)).get(120, TimeUnit.SECONDS);
            double seconds = (System.nanoTime() - before) / 1e9;
            Arrays.sort(latencies);
            System.out.printf(java.util.Locale.ROOT,
                "{\n  \"workload\": \"WebSocket JSON text echo; closed-loop, one in-flight frame per connection\",\n  \"connections\": %d,\n  \"measuredFrames\": %d,\n  \"warmupFramesPerConnection\": 100,\n  \"seconds\": %.6f,\n  \"framesPerSecond\": %.3f,\n  \"p50Micros\": %.3f,\n  \"p95Micros\": %.3f,\n  \"p99Micros\": %.3f,\n  \"maxMicros\": %.3f,\n  \"failedFrames\": 0,\n  \"javaVersion\": \"%s\",\n  \"architecture\": \"%s\"\n}\n",
                connections, total, seconds, total / seconds, percentile(latencies, .50), percentile(latencies, .95), percentile(latencies, .99),
                latencies[latencies.length - 1] / 1000.0, System.getProperty("java.version"), System.getProperty("os.arch"));
        } finally {
            start.countDown();
            workers.shutdownNow();
            if (!workers.awaitTermination(15, TimeUnit.SECONDS)) throw new IllegalStateException("probe workers did not stop");
            if ((Object) http instanceof AutoCloseable closeable) closeable.close();
        }
    }
    private static double percentile(long[] values, double percentile) { return values[(int) Math.ceil(values.length * percentile) - 1] / 1000.0; }
}
