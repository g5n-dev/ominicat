package io.ominicat.providers;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.ominicat.core.WorkerThreads;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.FilterInputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Flow;
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
        HttpResponse<Flow.Publisher<List<ByteBuffer>>> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofPublisher());
        PublisherInputStream stream = new PublisherInputStream();
        try { response.body().subscribe(stream); }
        catch (RuntimeException | Error failure) {
            try { stream.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            stream.close();
            throw new ProviderException("provider returned HTTP " + response.statusCode(), response.statusCode());
        }
        AtomicBoolean expired = new AtomicBoolean();
        ScheduledFuture<?> deadline = DEADLINES.schedule(() -> {
            expired.set(true);
            try { stream.close(); } catch (IOException ignored) { /* best effort deadline */ }
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);
        return new Response(stream, deadline, maxBytes, expired);
    }

    /**
     * One demanded data batch plus a terminal slot. Unlike the JDK 17 input-stream
     * subscriber, a blocked read preserves and propagates thread interruption.
     * Request/cancel run outside stateLock; callbacks never wait for the reader.
     */
    static final class PublisherInputStream extends InputStream implements Flow.Subscriber<List<ByteBuffer>> {
        private record Item(List<ByteBuffer> buffers, IOException failure) { }
        private final Object stateLock = new Object();
        private final ArrayBlockingQueue<Item> items = new ArrayBlockingQueue<>(2);
        private Flow.Subscription subscription;
        private boolean subscribed, outstanding, terminal, closed, eof, nextBatchNeeded;
        private Iterator<ByteBuffer> currentBatch;
        private ByteBuffer currentBuffer;
        private IOException readFailure;

        @Override public void onSubscribe(Flow.Subscription incoming) {
            Objects.requireNonNull(incoming, "subscription");
            boolean accepted;
            synchronized (stateLock) {
                accepted = !subscribed && !closed && !terminal;
                subscribed = true;
                if (accepted) { subscription = incoming; outstanding = true; }
            }
            if (accepted) request(incoming);
            else incoming.cancel();
        }

        @Override public void onNext(List<ByteBuffer> buffers) {
            Flow.Subscription cancel = null;
            synchronized (stateLock) {
                if (closed || terminal) return;
                if (!outstanding || buffers == null) {
                    cancel = subscription;
                    finishLocked(new IOException("provider published a body batch without demand"));
                } else {
                    outstanding = false;
                    if (!items.offer(new Item(buffers, null))) {
                        throw new IllegalStateException("provider body queue invariant violated");
                    }
                }
            }
            if (cancel != null) cancel.cancel();
        }

        @Override public void onError(Throwable failure) {
            Objects.requireNonNull(failure, "failure");
            synchronized (stateLock) {
                if (!closed && !terminal) finishLocked(failure instanceof IOException io ? io
                        : new IOException("provider response failed", failure));
            }
        }

        @Override public void onComplete() {
            synchronized (stateLock) { if (!closed && !terminal) finishLocked(null); }
        }

        private void finishLocked(IOException failure) {
            terminal = true; outstanding = false; subscription = null;
            if (!items.offer(new Item(null, failure))) {
                throw new IllegalStateException("provider body terminal slot invariant violated");
            }
        }

        private void request(Flow.Subscription active) {
            try { active.request(1); }
            catch (RuntimeException failure) {
                synchronized (stateLock) {
                    if (!closed && !terminal) finishLocked(new IOException("provider body demand failed", failure));
                }
                active.cancel();
            }
        }

        @Override public int read() throws IOException {
            byte[] single = new byte[1];
            return read(single, 0, 1) == -1 ? -1 : single[0] & 0xff;
        }

        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) return 0;
            while (true) {
                if (Thread.currentThread().isInterrupted()) throw interrupted(null);
                Flow.Subscription request = null;
                synchronized (stateLock) {
                    checkOpen();
                    if (readFailure != null) throw readFailure;
                    if (eof) return -1;
                    while (currentBatch != null) {
                        if (currentBuffer == null || !currentBuffer.hasRemaining()) {
                            if (!currentBatch.hasNext()) { currentBuffer = null; currentBatch = null; break; }
                            currentBuffer = Objects.requireNonNull(currentBatch.next(), "provider body buffer");
                            if (!currentBuffer.hasRemaining()) continue;
                        }
                        int count = Math.min(length, currentBuffer.remaining());
                        currentBuffer.get(bytes, offset, count);
                        if (!currentBuffer.hasRemaining() && !currentBatch.hasNext()) {
                            currentBuffer = null; currentBatch = null;
                        }
                        return count;
                    }
                    if (nextBatchNeeded) {
                        nextBatchNeeded = false;
                        if (!terminal && !outstanding && subscription != null) {
                            outstanding = true; request = subscription;
                        }
                    }
                }
                if (request != null) request(request);
                Item item;
                try { item = items.take(); }
                catch (InterruptedException interruption) { throw interrupted(interruption); }
                synchronized (stateLock) {
                    checkOpen();
                    if (item.buffers() == null) {
                        if (item.failure() != null) { readFailure = item.failure(); throw readFailure; }
                        eof = true;
                        return -1;
                    }
                    currentBatch = item.buffers().iterator();
                    nextBatchNeeded = true;
                }
            }
        }

        private InterruptedIOException interrupted(InterruptedException cause) {
            InterruptedIOException failure = new InterruptedIOException("provider response interrupted");
            if (cause != null) failure.initCause(cause);
            try { close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            finally { Thread.currentThread().interrupt(); }
            return failure;
        }

        private void checkOpen() throws IOException {
            if (closed) throw new IOException("provider response closed");
        }

        @Override public void close() throws IOException {
            Flow.Subscription cancel;
            synchronized (stateLock) {
                if (closed) return;
                closed = true; outstanding = false; nextBatchNeeded = false;
                cancel = subscription; subscription = null;
                currentBatch = null; currentBuffer = null; readFailure = null;
                items.clear();
                items.offer(new Item(null, null));
            }
            if (cancel != null) {
                try { cancel.cancel(); }
                catch (RuntimeException failure) { throw new IOException("provider body cancellation failed", failure); }
            }
        }
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
