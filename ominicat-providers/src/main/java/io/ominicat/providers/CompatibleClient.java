package io.ominicat.providers;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.ominicat.core.WorkerThreads;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.FilterInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Shared connection pool, finite response budget and body deadline for compatible HTTP APIs. */
final class CompatibleClient {
    static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private static final ScheduledThreadPoolExecutor DEADLINES = new ScheduledThreadPoolExecutor(1,
            WorkerThreads.platformFactory("ominicat-http-deadlines"));
    static { DEADLINES.setRemoveOnCancelPolicy(true); }
    final String model;
    final int maxBytes;
    private final URI base;
    private final String key;
    private final Duration timeout;

    CompatibleClient(Map<String, String> options) {
        String address = options.getOrDefault("baseUrl", "https://api.openai.com/v1").replaceAll("/+$", "");
        base = URI.create(address);
        if (base.getHost() == null || base.getRawUserInfo() != null || base.getRawQuery() != null || base.getRawFragment() != null) {
            throw new IllegalArgumentException("baseUrl must be an HTTP(S) URL without userinfo, query or fragment");
        }
        boolean local = Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(base.getHost());
        if (!"https".equals(base.getScheme()) && !(local && "http".equals(base.getScheme()))) {
            throw new IllegalArgumentException("baseUrl requires HTTPS (HTTP is allowed only on loopback)");
        }
        model = options.get("model");
        if (model == null || model.isBlank()) throw new IllegalArgumentException("model is required");
        String env = options.getOrDefault("apiKeyEnv", "OPENAI_API_KEY");
        key = env.isBlank() ? "" : System.getenv(env);
        if ((key == null || key.isBlank()) && !(local && env.isBlank())) {
            throw new IllegalArgumentException("missing provider credential environment variable: " + env);
        }
        timeout = Duration.ofSeconds(Options.integer(options, "timeoutSeconds", 30, 1, 300));
        maxBytes = Options.integer(options, "maxResponseBytes", 8 * 1024 * 1024, 1024, 64 * 1024 * 1024);
    }

    Response post(String path, Object json) throws IOException, InterruptedException {
        return post(path, JSON.writeValueAsBytes(json), "application/json");
    }
    Response post(String path, byte[] body, String contentType) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path)).timeout(timeout)
                .header("Content-Type", contentType).POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (key != null && !key.isBlank()) request.header("Authorization", "Bearer " + key);
        HttpResponse<InputStream> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            response.body().close();
            throw new ProviderException("provider returned HTTP " + response.statusCode(), response.statusCode());
        }
        InputStream stream = response.body();
        AtomicBoolean expired = new AtomicBoolean();
        ScheduledFuture<?> deadline = DEADLINES.schedule(() -> {
            expired.set(true);
            try { stream.close(); } catch (IOException ignored) { /* best effort deadline */ }
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);
        return new Response(stream, deadline, maxBytes, expired);
    }

    record Response(InputStream stream, ScheduledFuture<?> deadline, int maxBytes, AtomicBoolean expired) implements AutoCloseable {
        Response { stream = new BudgetedInputStream(stream, maxBytes); }
        Response(InputStream stream, ScheduledFuture<?> deadline, int maxBytes) {
            this(stream, deadline, maxBytes, new AtomicBoolean());
        }
        boolean timedOut() { return expired.get(); }
        byte[] bytes() throws IOException {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int length;
            while ((length = stream.read(chunk)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("provider response interrupted");
                if ((long) buffer.size() + length > maxBytes) throw new IOException("provider response exceeded byte budget");
                buffer.write(chunk, 0, length);
            }
            if (timedOut()) throw new IOException("provider response deadline exceeded");
            return buffer.toByteArray();
        }
        @Override public void close() throws IOException { deadline.cancel(false); stream.close(); }
    }

    /** Counts raw bytes before UTF-8 decoding, including ignored SSE fields and carriage returns. */
    private static final class BudgetedInputStream extends FilterInputStream {
        private final int limit;
        private long consumed;
        private BudgetedInputStream(InputStream source, int limit) { super(source); this.limit = limit; }
        @Override public int read() throws IOException {
            int value = in.read();
            if (value != -1 && ++consumed > limit) throw new IOException("provider response exceeded byte budget");
            return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) return 0;
            int permitted = (int) Math.min(length, Math.max(1, limit - consumed + 1));
            int count = in.read(bytes, offset, permitted);
            if (count > 0 && (consumed += count) > limit) throw new IOException("provider response exceeded byte budget");
            return count;
        }
    }
}
