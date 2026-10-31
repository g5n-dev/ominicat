package io.ominicat.server;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** One bounded shared JDK client; each test closes only its owned WebSockets on Java 17+. */
final class TestHttpClient implements AutoCloseable {
    private static final HttpClient SHARED = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final Set<WebSocket> sockets = ConcurrentHashMap.newKeySet();
    private final Set<CompletableFuture<WebSocket>> pending = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    static TestHttpClient create() { return new TestHttpClient(); }

    <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException, InterruptedException {
        ensureOpen();
        return SHARED.send(request, handler);
    }

    WebSocket.Builder newWebSocketBuilder() {
        ensureOpen();
        WebSocket.Builder delegate = SHARED.newWebSocketBuilder();
        return new WebSocket.Builder() {
            @Override public WebSocket.Builder header(String name, String value) { delegate.header(name, value); return this; }
            @Override public WebSocket.Builder connectTimeout(Duration timeout) { delegate.connectTimeout(timeout); return this; }
            @Override public WebSocket.Builder subprotocols(String first, String... rest) { delegate.subprotocols(first, rest); return this; }
            @Override public CompletableFuture<WebSocket> buildAsync(URI uri, WebSocket.Listener listener) {
                ensureOpen();
                CompletableFuture<WebSocket> future = delegate.buildAsync(uri, listener);
                pending.add(future);
                future.whenComplete((socket, failure) -> {
                    pending.remove(future);
                    if (socket != null) {
                        sockets.add(socket);
                        if (closed.get() && sockets.remove(socket)) socket.abort();
                    }
                });
                if (closed.get()) future.cancel(true);
                return future;
            }
        };
    }

    private void ensureOpen() {
        if (closed.get()) throw new IllegalStateException("Test client is closed");
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        pending.forEach(future -> future.cancel(true));
        sockets.forEach(WebSocket::abort);
        sockets.clear();
    }
}
