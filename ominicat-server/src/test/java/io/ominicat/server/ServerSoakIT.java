package io.ominicat.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.PooledByteBufAllocator;
import io.ominicat.core.Frame;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in: -Dtest=ServerSoakIT -Dominicat.soak.seconds=180. Excluded by normal Surefire naming. */
class ServerSoakIT {
    @Test void sustainedNativeSocketChurnAndSlowReadersRemainBounded() throws Exception {
        int seconds = Integer.getInteger("ominicat.soak.seconds", 60);
        int clients = Integer.getInteger("ominicat.soak.clients", 16);
        int connectsPerSecond = Integer.getInteger("ominicat.soak.connectsPerSecond", 100);
        if (seconds < 10 || seconds > 7200 || clients < 1 || clients > 128) throw new IllegalArgumentException("soak seconds10..7200; clients1..128");
        if (connectsPerSecond < 1 || connectsPerSecond > 2000) throw new IllegalArgumentException("connectsPerSecond must be1..2000");
        long paceNanos = TimeUnit.SECONDS.toNanos(1) * clients / connectsPerSecond;
        int capacity = Math.max(32, clients * 4), frameBytes = 32768;
        var running = new AtomicBoolean(true); var operations = new AtomicLong(); var pings = new AtomicLong();
        var rejections = new AtomicLong(); var slowReaders = new AtomicLong(); var failureCount = new AtomicLong();
        var failures = new ConcurrentLinkedQueue<String>(); var samples = new ArrayList<Sample>();
        var config = new RuntimeConfig("127.0.0.1", 0, 8, capacity, frameBytes, 10, "PATH",
                List.of(new RuntimeConfig.Stage("adversarial-test", Map.of())), capacity * 2);
        String authorization = "Authorization: Bearer " + System.getenv("PATH") + "\r\n";
        long started = System.nanoTime(); long deadline = started + TimeUnit.SECONDS.toNanos(seconds);
        OminiCatServer.Resources drained;
        try (var server = ServerAdversarialTest.server(config, () -> (frame, next) -> {
            if (frame instanceof Frame.Text text && text.text().equals("burst")) {
                var chunk = new Frame.Text("x".repeat(16000));
                for (int index = 0; index < 20_000 && !Thread.currentThread().isInterrupted(); index++) next.accept(chunk);
            } else next.accept(frame);
        }).start(); var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int worker = 0; worker < clients; worker++) {
                int identity = worker;
                workers.submit(() -> {
                    long sequence = 0;
                    while (running.get() && System.nanoTime() < deadline) {
                        long iterationStarted = System.nanoTime();
                        try (var client = new RawWebSocketClient(server.port(), authorization)) {
                            if (client.status == 503) { rejections.incrementAndGet(); continue; }
                            if (client.status != 101) throw new IOException("Unexpected handshake status=" + client.status);
                            if (++sequence % 13 == 0) {
                                client.text("{\"type\":\"text\",\"text\":42}");
                                if (!client.read().text().contains("invalid_frame")) throw new IOException("Malformed frame did not return invalid_frame");
                            }
                            String token = "worker-" + identity + "/" + sequence;
                            client.text("{\"type\":\"text\",\"text\":\"" + token + "\"}");
                            if (!client.read().text().contains(token)) throw new IOException("Echo was corrupted");
                            if (sequence % 7 == 0) { client.frame(9, new byte[]{1, 2}); if (client.read().opcode() != 10) throw new IOException("Missing pong"); pings.incrementAndGet(); }
                            if (sequence % 2 == 0) { client.text("{\"type\":\"end\"}"); if (!client.read().text().contains("end")) throw new IOException("Missing end"); }
                            operations.incrementAndGet();
                        } catch (Exception failure) {
                            failureCount.incrementAndGet(); if (failures.size() < 20) failures.add(failure.getClass().getSimpleName() + ":" + failure.getMessage());
                            running.set(false);
                        } finally {
                            if (running.get()) java.util.concurrent.locks.LockSupport.parkNanos(Math.max(0, paceNanos - (System.nanoTime() - iterationStarted)));
                        }
                    }
                });
            }
            workers.submit(() -> {
                while (running.get() && System.nanoTime() < deadline) {
                    try (var slow = new RawWebSocketClient(server.port(), authorization)) {
                        if (slow.status != 101) { Thread.sleep(100); continue; }
                        slow.text("{\"type\":\"text\",\"text\":\"burst\"}");
                        slowReaders.incrementAndGet(); Thread.sleep(3000); // Zero reads after the 101 handshake.
                    } catch (Exception failure) {
                        failureCount.incrementAndGet(); if (failures.size() < 20) failures.add("slow:" + failure.getClass().getSimpleName()); running.set(false);
                    }
                }
            });
            while (running.get() && System.nanoTime() < deadline) {
                var sample = sample(server, started, operations.get()); samples.add(sample);
                if (sample.resources.admittedSessions() > capacity || sample.resources.pipelineWorkers() > capacity
                        || sample.resources.connections() > config.maxConnections()
                        || sample.resources.pendingOutputBytes() > 4L * frameBytes * capacity
                        || sample.resources.pendingOutputBytes() < 0
                        || sample.nettyPendingTasks > capacity * 16L
                        || sample.nettyScheduledTasks > config.maxConnections() + capacity + 16L) {
                    failures.add("Resource admission/budget invariant violated"); failureCount.incrementAndGet(); running.set(false);
                }
                Thread.sleep(1000);
            }
            running.set(false); workers.shutdown();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS), "Native clients did not finish");
            long drainDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!empty(server.resources()) && System.nanoTime() < drainDeadline) Thread.sleep(10);
            drained = server.resources(); samples.add(sample(server, started, operations.get()));
        }
        Path destination = Path.of(System.getProperty("ominicat.soak.output", "target/server-soak.json"));
        if (destination.toAbsolutePath().getParent() != null) Files.createDirectories(destination.toAbsolutePath().getParent());
        Map<String, Object> report = Map.ofEntries(Map.entry("secondsRequested", seconds), Map.entry("elapsedSeconds", (System.nanoTime() - started) / 1e9),
                Map.entry("concurrentClients", clients), Map.entry("sessionCapacity", capacity), Map.entry("maxFrameBytes", frameBytes),
                Map.entry("connectsPerSecondLimit", connectsPerSecond),
                Map.entry("operations", operations.get()), Map.entry("pings", pings.get()), Map.entry("slowReaderAttacks", slowReaders.get()),
                Map.entry("admissionRejections", rejections.get()), Map.entry("failureCount", failureCount.get()), Map.entry("failures", failures),
                Map.entry("drained", drained), Map.entry("samples", samples), Map.entry("java", System.getProperty("java.version")),
                Map.entry("os", System.getProperty("os.name")), Map.entry("availableProcessors", Runtime.getRuntime().availableProcessors()));
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(destination.toFile(), report);
        assertEquals(0, failureCount.get(), failures.toString()); assertTrue(operations.get() > clients * 10L);
        assertEquals(0, drained.connections()); assertEquals(0, drained.activeSessions()); assertEquals(0, drained.admittedSessions());
        assertEquals(0, drained.pipelineWorkers()); assertEquals(0, drained.pendingOutputBytes());
        System.out.println("Native server soak evidence: " + destination.toAbsolutePath() + "; operations=" + operations.get());
    }

    private static Sample sample(OminiCatServer server, long started, long operations) throws Exception {
        var memory = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        var queues = NettyQueueProbe.sample(server);
        return new Sample((System.nanoTime() - started) / 1e9, operations, server.resources(),
                ServerAdversarialTest.descriptors(), ManagementFactory.getThreadMXBean().getThreadCount(), memory.getUsed(),
                PooledByteBufAllocator.DEFAULT.metric().usedDirectMemory(), queues.pendingTasks(), queues.scheduledTasks());
    }
    private static boolean empty(OminiCatServer.Resources resources) {
        return resources.connections() == 0 && resources.activeSessions() == 0 && resources.admittedSessions() == 0
                && resources.pipelineWorkers() == 0 && resources.pendingOutputBytes() == 0;
    }
    record Sample(double seconds, long operations, OminiCatServer.Resources resources,
                  long openFileDescriptors, int platformThreads, long heapUsedBytes, long nettyDirectReservedBytes,
                  long nettyPendingTasks, long nettyScheduledTasks) { }
}
