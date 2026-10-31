package io.ominicat.server;

import io.ominicat.core.spi.PluginRegistry;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Timeout(15)
class LoopbackHostGuardTest {
    @Test void attackerHostAndMatchingOriginCannotReachHttpRoutesOrUpgradeOnUnauthenticatedLoopback() throws Exception {
        try (var server = server("127.0.0.1", null).start()) {
            String authority = "attacker.invalid:" + server.port();
            for (String path : List.of("/", "/health", "/metrics")) {
                assertEquals(403, exchange(server.port(), "127.0.0.1", authority, path, false, ""));
            }
            assertEquals(403, exchange(server.port(), "127.0.0.1", authority, "/ws", true, "Origin: http://" + authority + "\r\n"));
            assertEquals(0, server.resources().admittedSessions()); assertEquals(0, server.resources().pipelineWorkers());
        }
    }

    @Test void localhostAndCanonicalIpv4AndIpv6LoopbackAuthoritiesRemainUsable() throws Exception {
        try (var server = server("127.0.0.1", null).start()) {
            for (String host : List.of("localhost", "LOCALHOST", "127.0.0.1", "127.0.0.2", "[::1]")) {
                String authority = host + ":" + server.port();
                assertEquals(200, exchange(server.port(), "127.0.0.1", host, "/health", false, ""));
                assertEquals(200, exchange(server.port(), "127.0.0.1", authority, "/health", false, ""));
                assertEquals(101, exchange(server.port(), "127.0.0.1", authority, "/ws", true, "Origin: http://" + authority + "\r\n"));
            }
        }
    }

    @Test void ambiguousAuthoritiesDoNotBypassCanonicalHostGuard() throws Exception {
        try (var server = server("127.0.0.1", null).start()) {
            for (String authority : List.of("localhost.attacker.invalid", "127.0.0.1.attacker.invalid", "127.1", "2130706433",
                    "0x7f000001", "127.000.0.1", "127.0.0.256", "localhost:", "localhost:65536", "[::1]attacker.invalid", "user@localhost")) {
                assertEquals(403, exchange(server.port(), "127.0.0.1", authority, "/health", false, ""), authority);
            }
            assertEquals(403, exchange(server.port(), "127.0.0.1", "localhost", "/health", false, "Host: attacker.invalid\r\n"));
        }
    }

    @Test void authenticatedProxyAuthorityKeepsExistingHttpAndWebSocketBehavior() throws Exception {
        try (var server = server("127.0.0.1", "PATH").start()) {
            String authority = "app.example.invalid:" + server.port();
            String authorization = "Authorization: Bearer " + System.getenv("PATH") + "\r\n";
            assertEquals(200, exchange(server.port(), "127.0.0.1", authority, "/health", false, ""));
            assertEquals(401, exchange(server.port(), "127.0.0.1", authority, "/ws", true, "Origin: http://" + authority + "\r\n"));
            assertEquals(200, exchange(server.port(), "127.0.0.1", authority, "/metrics", false, authorization));
            assertEquals(101, exchange(server.port(), "127.0.0.1", authority, "/ws", true,
                    authorization + "Origin: http://" + authority + "\r\n"));
        }
    }

    @Test void wildcardListenerRetainsAuthorityBehaviorForReverseProxyDeployment() throws Exception {
        try (var server = server("0.0.0.0", null).start()) {
            String authority = "app.example.invalid:" + server.port();
            assertEquals(200, exchange(server.port(), "127.0.0.1", authority, "/health", false, ""));
            assertEquals(101, exchange(server.port(), "127.0.0.1", authority, "/ws", true, "Origin: http://" + authority + "\r\n"));
        }
    }

    @Test void actualIpv6LoopbackListenerAppliesTheSameHostGuardWhenAvailable() throws Exception {
        byte[] address = new byte[16]; address[15] = 1;
        assumeTrue(NetworkInterface.getByInetAddress(InetAddress.getByAddress(address)) != null, "IPv6 loopback is unavailable");
        try (var server = server("::1", null).start()) {
            String allowed = "[::1]:" + server.port(), denied = "attacker.invalid:" + server.port();
            assertEquals(200, exchange(server.port(), "::1", allowed, "/health", false, ""));
            assertEquals(101, exchange(server.port(), "::1", allowed, "/ws", true, "Origin: http://" + allowed + "\r\n"));
            assertEquals(403, exchange(server.port(), "::1", denied, "/ws", true, "Origin: http://" + denied + "\r\n"));
        }
    }

    private static OminiCatServer server(String host, String tokenEnv) {
        return new OminiCatServer(new RuntimeConfig(host, 0, 8, 8, 4096, 60, tokenEnv,
                List.of(new RuntimeConfig.Stage("echo", Map.of()))), new PluginRegistry());
    }

    private static int exchange(int port, String destination, String host, String path, boolean websocket, String extra) throws Exception {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(destination, port), 2000); socket.setSoTimeout(2000);
            String request = "GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\n" + extra;
            if (websocket) request += "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: AAAAAAAAAAAAAAAAAAAAAA==\r\n";
            socket.getOutputStream().write((request + "\r\n").getBytes(StandardCharsets.US_ASCII));
            StringBuilder response = new StringBuilder();
            while (!response.toString().endsWith("\r\n") && response.length() < 4096) {
                int value = socket.getInputStream().read(); if (value < 0) break; response.append((char) value);
            }
            assertTrue(response.toString().startsWith("HTTP/1.1 "), response.toString());
            return Integer.parseInt(response.toString().split(" ")[1]);
        }
    }
}
