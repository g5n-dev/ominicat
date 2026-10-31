package io.ominicat.server;

import io.ominicat.core.WorkerThreads;
import io.ominicat.core.spi.PluginRegistry;
import io.ominicat.core.spi.ProcessorFactory;
import io.ominicat.core.Processor;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class WorkerRuntimeCompatibilityTest {
    @Test void actualWebSocketProcessorUsesSupportedWorkerAndCleansUpOnEverySupportedJdk() throws Exception {
        var executedOn = new AtomicReference<Thread>();
        var registry = new PluginRegistry();
        registry.register(new ProcessorFactory() {
            @Override public String type() { return "worker-runtime-test"; }
            @Override public Processor create(Map<String, String> options) {
                return (frame, next) -> { executedOn.set(Thread.currentThread()); next.accept(frame); };
            }
        });
        var config = new RuntimeConfig("127.0.0.1", 0, 4, 2, 1024, 10, null,
                List.of(new RuntimeConfig.Stage("worker-runtime-test", Map.of())));
        try (var server = new OminiCatServer(config, registry).start()) {
            try (var client = new RawWebSocketClient(server.port())) {
                assertEquals(101, client.status);
                client.text("{\"type\":\"text\",\"text\":\"runtime-compatible\"}");
                assertTrue(client.read().text().contains("runtime-compatible"));
                Thread worker = executedOn.get();
                assertNotNull(worker); assertTrue(worker.isDaemon());
                assertEquals(WorkerThreads.virtualThreadsAvailable(), WorkerThreads.isVirtual(worker));
                assertFalse(worker.getName().startsWith("nioEventLoopGroup"), "Processor must not block transport event loops");
            }
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            while (server.resources().admittedSessions() != 0 && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(0, server.resources().pipelineWorkers()); assertEquals(0, server.resources().admittedSessions());
        }
    }
}
