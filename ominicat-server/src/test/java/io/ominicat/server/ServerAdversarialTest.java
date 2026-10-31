package io.ominicat.server;

import io.ominicat.core.Frame;
import io.ominicat.core.Processor;
import io.ominicat.core.spi.PluginRegistry;
import io.ominicat.core.spi.ProcessorFactory;
import java.io.IOException;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class ServerAdversarialTest {
    @Test void disconnectedNoncooperativeWorkerKeepsItsAdmissionSlotUntilCleanup() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var closes = new AtomicInteger();
        try (var server = server(1, 1024, 60, () -> new Processor() {
            @Override public void process(Frame frame, io.ominicat.core.FrameSink next) {
                entered.countDown();
                while (release.getCount() != 0) {
                    try { release.await(); } catch (InterruptedException ignored) { /* deliberately noncooperative fixture */ }
                }
            }
            @Override public void close() { closes.incrementAndGet(); }
        }).start()) {
            var first = new RawWebSocketClient(server.port()); assertEquals(101, first.status);
            first.text("{\"type\":\"text\",\"text\":\"stall\"}"); assertTrue(entered.await(3, TimeUnit.SECONDS));
            first.close(); await(() -> server.resources().activeSessions() == 0, Duration.ofSeconds(2));
            assertEquals(1, server.resources().pipelineWorkers()); assertEquals(1, server.resources().admittedSessions());
            try (var second = new RawWebSocketClient(server.port())) {
                assertEquals(503, second.status, "Socket churn must not create unbounded orphan pipeline workers");
            }
            release.countDown();
            await(() -> closes.get() == 2 && server.resources().admittedSessions() == 0, Duration.ofSeconds(3));
            try (var recovered = new RawWebSocketClient(server.port())) { assertEquals(101, recovered.status); }
        } finally { release.countDown(); }
    }

    @Test void outputOverloadAgainstNonreadingTcpPeerRecoversAdmissionWithinDeadline() throws Exception {
        try (var server = server(1, 32768, 60, () -> (frame, next) -> {
            for (int index = 0; index < 20_000 && !Thread.currentThread().isInterrupted(); index++) next.accept(new Frame.Text("x".repeat(16000)));
        }).start(); var slow = new RawWebSocketClient(server.port())) {
            assertEquals(101, slow.status); slow.text("{\"type\":\"text\",\"text\":\"burst\"}");
            Thread.sleep(3000); // Intentionally do not read server TCP output.
            try (var recovered = new RawWebSocketClient(server.port())) {
                assertEquals(101, recovered.status, "Output overload must not strand admission behind an unflushable close frame");
            }
        }
    }

    @Test void pingFloodAgainstNonreadingTcpPeerIsBoundedAndRecovers() throws Exception {
        try (var server = server(1, 1024, 60, () -> (frame, next) -> next.accept(frame)).start();
                var slow = new RawWebSocketClient(server.port())) {
            assertEquals(101, slow.status);
            Thread writer = Thread.ofVirtual().start(() -> {
                byte[] payload = new byte[125];
                try { for (int index = 0; index < 50_000; index++) slow.frame(9, payload); }
                catch (IOException expectedClosure) { /* expected when output protection closes the peer */ }
            });
            Thread.sleep(4000);
            try (var recovered = new RawWebSocketClient(server.port())) {
                assertEquals(101, recovered.status, "Pong writes must obey the same finite output budget as data frames");
            } finally { slow.close(); writer.join(3000); }
            assertFalse(writer.isAlive());
        }
    }

    @Test void idleNativeClientsExpireAndCapacityRecovers() throws Exception {
        try (var server = server(1, 1024, 1, () -> (frame, next) -> next.accept(frame)).start(); var idle = new RawWebSocketClient(server.port())) {
            assertEquals(101, idle.status); Thread.sleep(1600);
            try (var recovered = new RawWebSocketClient(server.port())) { assertEquals(101, recovered.status); }
        }
    }

    @Test void rawHalfHandshakesRespectConnectionCapAndRecoverAfterClose() throws Exception {
        var config = new RuntimeConfig("127.0.0.1", 0, 8, 1, 1024, 60, null,
                List.of(new RuntimeConfig.Stage("adversarial-test", Map.of())), 2);
        try (var server = server(config, () -> (frame, next) -> next.accept(frame)).start();
                var first = new Socket("127.0.0.1", server.port()); var second = new Socket("127.0.0.1", server.port())) {
            await(() -> server.resources().connections() == 2, Duration.ofSeconds(2));
            try (var rejected = new Socket("127.0.0.1", server.port())) {
                rejected.setSoTimeout(2000);
                assertEquals(-1, rejected.getInputStream().read(), "TCP admission must reject before allocating HTTP/session state");
            }
            assertEquals(2, server.resources().connections()); assertEquals(0, server.resources().pipelineWorkers());
            first.close(); await(() -> server.resources().connections() == 1, Duration.ofSeconds(2));
            try (var recovered = new RawWebSocketClient(server.port())) { assertEquals(101, recovered.status); }
        }
    }

    @Test void shutdownReturnsWithNoncooperativeWorkVisibleAndWithoutAcceptingMoreSockets() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var closes = new AtomicInteger();
        var server = server(1, 1024, 60, () -> new Processor() {
            @Override public void process(Frame frame, io.ominicat.core.FrameSink next) {
                entered.countDown();
                while (release.getCount() != 0) {
                    try { release.await(); } catch (InterruptedException ignored) { /* deliberately broken plugin */ }
                }
            }
            @Override public void close() { closes.incrementAndGet(); }
        }).start();
        int port = server.port();
        try (var client = new RawWebSocketClient(port)) {
            client.text("{\"type\":\"text\",\"text\":\"stall\"}"); assertTrue(entered.await(2, TimeUnit.SECONDS));
            long start = System.nanoTime(); server.close();
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(3), "Stalled plugin must not block transport shutdown");
            assertEquals(0, server.resources().connections()); assertEquals(0, server.resources().activeSessions());
            assertEquals(1, server.resources().pipelineWorkers()); assertEquals(1, server.resources().admittedSessions());
            assertEquals(1, closes.get(), "Cleanup cannot be claimed for a still-running noncooperative processor");
            assertThrows(IOException.class, () -> { try (var ignored = new Socket("127.0.0.1", port)) { } });
            release.countDown();
            await(() -> server.resources().pipelineWorkers() == 0 && server.resources().admittedSessions() == 0, Duration.ofSeconds(3));
            assertEquals(2, closes.get());
        } finally { release.countDown(); server.close(); }
    }

    @Test void nativeSocketChurnReleasesWorkersDescriptorsAndQueuedBytes() throws Exception {
        long beforeDescriptors = descriptors();
        try (var server = server(16, 4096, 10, () -> (frame, next) -> next.accept(frame)).start()) {
            for (int index = 0; index < 250; index++) {
                try (var client = new RawWebSocketClient(server.port())) {
                    assertEquals(101, client.status);
                    client.text("{\"type\":\"text\",\"text\":\"churn-" + index + "\"}");
                    assertTrue(client.read().text().contains("churn-" + index));
                    if (index % 2 == 0) {
                        client.frame(9, new byte[]{1, 2, 3}); assertEquals(10, client.read().opcode());
                        client.text("{\"type\":\"end\"}"); assertTrue(client.read().text().contains("end"));
                    }
                }
            }
            await(() -> server.resources().connections() == 0 && server.resources().pipelineWorkers() == 0
                    && server.resources().admittedSessions() == 0 && server.resources().pendingOutputBytes() == 0, Duration.ofSeconds(3));
            var queues = NettyQueueProbe.sample(server);
            assertTrue(queues.pendingTasks() <= 4, "Event-loop tasks did not drain after native socket churn");
            assertEquals(0, queues.scheduledTasks(), "Per-connection timeout/close timers leaked after socket churn");
        }
        if (beforeDescriptors >= 0) await(() -> descriptors() <= beforeDescriptors + 8, Duration.ofSeconds(3));
    }

    @Test void preflightClosesAllEarlierStagesWhenLaterCleanupThrowsError() throws Exception {
        var firstClosed = new AtomicInteger();
        var registry = new PluginRegistry();
        registry.register(factory("fatal-cleanup-first", () -> new Processor() {
            @Override public void process(Frame frame, io.ominicat.core.FrameSink next) { }
            @Override public void close() { firstClosed.incrementAndGet(); }
        }));
        registry.register(factory("fatal-cleanup-last", () -> new Processor() {
            @Override public void process(Frame frame, io.ominicat.core.FrameSink next) { }
            @Override public void close() { throw new AssertionError("cleanup fixture"); }
        }));
        var config = configuration(List.of("fatal-cleanup-first", "fatal-cleanup-last"));
        try (var server = new OminiCatServer(config, registry)) {
            assertThrows(AssertionError.class, server::start);
            assertEquals(1, firstClosed.get(), "Error from later cleanup must not skip an earlier owned resource");
            assertEquals(0, server.resources().connections());
        }
    }

    @Test void sessionFactoryAndCleanupErrorsReleaseAllEarlierResourcesAndAdmission() throws Exception {
        var firstCloses = new AtomicInteger(); var secondCloses = new AtomicInteger();
        var secondCreates = new AtomicInteger(); var lastCreates = new AtomicInteger();
        var registry = new PluginRegistry();
        registry.register(factory("session-first", () -> new Processor() {
            @Override public void process(Frame frame, io.ominicat.core.FrameSink next) { }
            @Override public void close() { firstCloses.incrementAndGet(); }
        }));
        registry.register(factory("session-second", () -> {
            int generation = secondCreates.incrementAndGet();
            return new Processor() {
                @Override public void process(Frame frame, io.ominicat.core.FrameSink next) { }
                @Override public void close() {
                    secondCloses.incrementAndGet();
                    if (generation > 1) throw new AssertionError("session cleanup fixture");
                }
            };
        }));
        registry.register(factory("session-last", () -> {
            if (lastCreates.incrementAndGet() > 1) throw new AssertionError("session factory fixture");
            return (frame, next) -> { };
        }));
        try (var server = new OminiCatServer(configuration(List.of("session-first", "session-second", "session-last")), registry).start();
                var client = new RawWebSocketClient(server.port())) {
            assertEquals(101, client.status);
            var close = client.read(); assertEquals(8, close.opcode()); assertEquals(1011, close.closeCode());
            await(() -> server.resources().admittedSessions() == 0, Duration.ofSeconds(2));
            assertEquals(2, firstCloses.get()); assertEquals(2, secondCloses.get());
            assertEquals(0, server.resources().pipelineWorkers());
        }
    }

    private static RuntimeConfig configuration(List<String> types) {
        return new RuntimeConfig("127.0.0.1", 0, 8, 1, 1024, 60, null,
                types.stream().map(type -> new RuntimeConfig.Stage(type, Map.of())).toList());
    }

    private static ProcessorFactory factory(String type, Supplier<Processor> supplier) {
        return new ProcessorFactory() {
            @Override public String type() { return type; }
            @Override public Processor create(Map<String, String> options) { return supplier.get(); }
        };
    }

    static OminiCatServer server(int maxSessions, int maxFrame, int idle, Supplier<Processor> factory) {
        return server(new RuntimeConfig("127.0.0.1", 0, 8, maxSessions, maxFrame, idle, null,
                List.of(new RuntimeConfig.Stage("adversarial-test", Map.of()))), factory);
    }

    static OminiCatServer server(RuntimeConfig config, Supplier<Processor> factory) {
        var registry = new PluginRegistry();
        registry.register(new ProcessorFactory() {
            @Override public String type() { return "adversarial-test"; }
            @Override public Processor create(Map<String, String> options) { return factory.get(); }
        });
        return new OminiCatServer(config, registry);
    }

    static long descriptors() {
        var bean = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        return bean instanceof com.sun.management.UnixOperatingSystemMXBean unix ? unix.getOpenFileDescriptorCount() : -1;
    }

    private static void await(java.util.function.BooleanSupplier done, Duration timeout) throws InterruptedException {
        long until = System.nanoTime() + timeout.toNanos();
        while (!done.getAsBoolean() && System.nanoTime() < until) Thread.sleep(10);
        assertTrue(done.getAsBoolean(), "Condition did not become true within " + timeout);
    }
}
