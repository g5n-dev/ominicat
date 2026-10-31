package io.ominicat.server;

import io.ominicat.core.spi.PluginRegistry;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/** Native HTTP/1.1 checks do not depend on a client's connection-reuse timing. */
@Timeout(20)
class HttpResponseBoundaryTest {
    @Test void successfulHttpRoutesAdvertiseTheirCloseBoundaryAndSendCompleteBodies() throws Exception {
        try (var server = server(null).start()) {
            for (String path : List.of("/health", "/metrics", "/api/runtime", "/api/activity", "/", "/console.css", "/console.js")) {
                exchange(server, "GET", path, "localhost", 200);
            }
        }
    }

    @Test void methodAndMissingRouteErrorsAdvertiseTheSameCloseBoundary() throws Exception {
        try (var server = server(null).start()) {
            var rejected = exchange(server, "POST", "/api/runtime", "localhost", 405);
            assertEquals("GET required", new String(rejected.body(), StandardCharsets.UTF_8));
            exchange(server, "GET", "/not-a-public-route", "localhost", 404);
        }
    }

    @Test void bearerChallengesAdvertiseCloseWithoutChangingAuthentication() throws Exception {
        try (var server = server("PATH").start()) {
            for (String path : List.of("/metrics", "/api/runtime", "/api/activity")) {
                var unauthorized = exchange(server, "GET", path, "localhost", 401);
                assertEquals("Bearer", unauthorized.headers().get("www-authenticate"));
                assertEquals("Bearer token required", new String(unauthorized.body(), StandardCharsets.UTF_8));
            }
            exchange(server, "GET", "/health", "localhost", 200);
        }
    }

    @Test void rejectedLoopbackAuthoritiesAdvertiseCloseBeforeEof() throws Exception {
        try (var server = server(null).start()) {
            exchange(server, "GET", "/health", "attacker.invalid", 403);
        }
    }

    @Test void unsupportedWebSocketVersionsAdvertiseCloseAndTheSupportedVersion() throws Exception {
        try (var server = server(null).start()) {
            var unsupported = exchange(server, "GET", "/ws", "localhost", 426,
                    "Upgrade: websocket\r\nSec-WebSocket-Version: 12\r\n"
                            + "Sec-WebSocket-Key: AAAAAAAAAAAAAAAAAAAAAA==\r\n");
            assertEquals("13", unsupported.headers().get("sec-websocket-version"));
            assertEquals(0, unsupported.body().length);
        }
    }

    private static OminiCatServer server(String tokenEnv) {
        return new OminiCatServer(new RuntimeConfig("127.0.0.1", 0, 8, 8, 4096, 60, tokenEnv,
                List.of(new RuntimeConfig.Stage("echo", Map.of()))), new PluginRegistry());
    }

    private static Response exchange(OminiCatServer server, String method, String path, String host,
                                     int expectedStatus) throws Exception {
        return exchange(server, method, path, host, expectedStatus, "");
    }

    private static Response exchange(OminiCatServer server, String method, String path, String host,
                                     int expectedStatus, String upgradeHeaders) throws Exception {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", server.port()), 2000);
            socket.setSoTimeout(2000);
            String body = method.equals("POST") ? "{}" : "";
            // Request persistence explicitly: the server must declare its own close-after-response policy.
            String request = method + " " + path + " HTTP/1.1\r\nHost: " + host + ":" + server.port()
                    + "\r\nConnection: keep-alive" + (upgradeHeaders.isEmpty() ? "" : ", Upgrade")
                    + "\r\n" + upgradeHeaders + "Content-Length: " + body.length() + "\r\n\r\n" + body;
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            var input = socket.getInputStream();
            var bytes = new ByteArrayOutputStream();
            int matched = 0;
            byte[] delimiter = {'\r', '\n', '\r', '\n'};
            while (matched < delimiter.length) {
                int value = input.read();
                assertTrue(value >= 0, "EOF before complete response headers: " + path);
                assertTrue(bytes.size() < 16_384, "response headers exceeded the fixture budget");
                bytes.write(value);
                matched = value == delimiter[matched] ? matched + 1 : value == '\r' ? 1 : 0;
            }
            String[] lines = bytes.toString(StandardCharsets.US_ASCII).split("\r\n");
            String[] statusLine = lines[0].split(" ", 3);
            assertEquals("HTTP/1.1", statusLine[0]);
            int status = Integer.parseInt(statusLine[1]);
            Map<String, String> headers = new LinkedHashMap<>();
            for (int index = 1; index < lines.length; index++) {
                int colon = lines[index].indexOf(':');
                assertTrue(colon > 0, "malformed response header");
                String name = lines[index].substring(0, colon).toLowerCase(Locale.ROOT);
                assertNull(headers.put(name, lines[index].substring(colon + 1).trim()), "duplicate response header");
            }
            assertNotNull(headers.get("content-length"), "response must have an explicit body length");
            int length = Integer.parseInt(headers.get("content-length"));
            assertTrue(length >= 0 && length <= 1_048_576, "body exceeded the fixture budget");
            byte[] payload = input.readNBytes(length);
            int trailing = input.read();
            assertAll(method + " " + path,
                    () -> assertEquals(expectedStatus, status),
                    () -> assertEquals("close", headers.get("connection"), "HTTP/1.1 response must advertise closure"),
                    () -> assertFalse(headers.containsKey("transfer-encoding")),
                    () -> assertEquals(length, payload.length, "Content-Length must match actual payload bytes"),
                    () -> assertEquals(-1, trailing, "connection must end immediately after the complete response body"));
            return new Response(Map.copyOf(headers), payload);
        }
    }

    private record Response(Map<String, String> headers, byte[] body) { }
}
