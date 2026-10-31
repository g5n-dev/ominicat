package io.ominicat.providers;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Shared connection pool, finite response budget and body deadline for compatible HTTP APIs. */
final class CompatibleClient {
    static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private static final java.util.concurrent.ScheduledExecutorService DEADLINES =
            Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("ominicat-http-deadlines").factory());
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
        ScheduledFuture<?> deadline = DEADLINES.schedule(() -> {
            try { stream.close(); } catch (IOException ignored) { /* best effort deadline */ }
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);
        return new Response(stream, deadline, maxBytes);
    }

    record Response(InputStream stream, ScheduledFuture<?> deadline, int maxBytes) implements AutoCloseable {
        byte[] bytes() throws IOException {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int length;
            while ((length = stream.read(chunk)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("provider response interrupted");
                if ((long) buffer.size() + length > maxBytes) throw new IOException("provider response exceeded byte budget");
                buffer.write(chunk, 0, length);
            }
            if (deadline.isDone()) throw new IOException("provider response deadline exceeded");
            return buffer.toByteArray();
        }
        @Override public void close() throws IOException { deadline.cancel(false); stream.close(); }
    }
}
