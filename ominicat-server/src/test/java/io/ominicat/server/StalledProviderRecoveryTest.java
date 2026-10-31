package io.ominicat.server;

import io.ominicat.core.WorkerThreads;
import com.sun.net.httpserver.HttpServer;
import io.ominicat.core.spi.PluginRegistry;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class StalledProviderRecoveryTest {
    @Test void deadlineClosesStalledSseBodyAndNewSessionRecoversWithoutLeakingAdmission() throws Exception {
        var fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var release = new CountDownLatch(1); var requests = new AtomicInteger();
        try (var fixtureWorkers = WorkerThreads.executor("stalled-http-fixture-", 2)) {
            fixture.setExecutor(fixtureWorkers);
            fixture.createContext("/v1/chat/completions", exchange -> {
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                try (var output = exchange.getResponseBody()) {
                    if (requests.incrementAndGet() == 1) {
                        output.write(": response body will stall\n\n".getBytes(StandardCharsets.UTF_8)); output.flush();
                        try { release.await(8, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    } else {
                        output.write("data: {\"choices\":[{\"delta\":{\"content\":\"recovered\"}}]}\n\ndata: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                    }
                }
            });
            fixture.start();
            var config = new RuntimeConfig("127.0.0.1", 0, 4, 1, 4096, 60, null, List.of(new RuntimeConfig.Stage("openai-chat",
                    Map.of("baseUrl", "http://127.0.0.1:" + fixture.getAddress().getPort() + "/v1", "model", "fixture", "apiKeyEnv", "", "timeoutSeconds", "1"))));
            try (var server = new OminiCatServer(config, new PluginRegistry()).start()) {
                long begin = System.nanoTime();
                try (var stalled = new RawWebSocketClient(server.port())) {
                    stalled.text("{\"type\":\"text\",\"text\":\"stall\"}");
                    var close = stalled.read(); assertEquals(8, close.opcode()); assertEquals(1011, close.closeCode());
                }
                assertTrue(System.nanoTime() - begin < TimeUnit.SECONDS.toNanos(4));
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (server.resources().admittedSessions() != 0 && System.nanoTime() < until) Thread.sleep(10);
                assertEquals(0, server.resources().pipelineWorkers()); assertEquals(0, server.resources().admittedSessions());
                try (var recovered = new RawWebSocketClient(server.port())) {
                    assertEquals(101, recovered.status);
                    recovered.text("{\"type\":\"text\",\"text\":\"retry\"}");
                    assertTrue(recovered.read().text().contains("recovered"));
                }
                assertEquals(2, requests.get());
            } finally { release.countDown(); }
        } finally { release.countDown(); fixture.stop(0); }
    }
}
