package io.ominicat.providers;

import static org.junit.jupiter.api.Assertions.assertNull;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

abstract class ProviderHttpTestSupport {
    private HttpServer server;
    private ExecutorService executor;
    private final AtomicReference<Throwable> handlerFailure = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        executor.shutdownNow();
        assertNull(handlerFailure.get(), () -> "local provider assertion failed: " + handlerFailure.get());
    }

    void route(String path, HttpHandler handler) {
        server.createContext("/v1" + path, exchange -> {
            try {
                handler.handle(exchange);
            } catch (AssertionError | RuntimeException failure) {
                handlerFailure.compareAndSet(null, failure);
                exchange.close();
            }
        });
    }

    Map<String, String> options(String... additions) {
        Map<String, String> options = new HashMap<>();
        options.put("model", "test-model");
        options.put("apiKeyEnv", "");
        options.put("baseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        options.put("timeoutSeconds", "3");
        for (int i = 0; i < additions.length; i += 2) {
            options.put(additions[i], additions[i + 1]);
        }
        return options;
    }

    static void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        respond(exchange, status, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    static void respond(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    static String event(String text) throws IOException {
        return "data: " + CompatibleClient.JSON.writeValueAsString(Map.of("choices",
                java.util.List.of(Map.of("delta", Map.of("content", text))))) + "\n\n";
    }
}
