import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.netty.buffer.PooledByteBufAllocator;
import io.ominicat.core.spi.PluginRegistry;
import io.ominicat.server.OminiCatServer;
import io.ominicat.server.RuntimeConfig;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.concurrent.Executors;

/** Loopback-only validation observer; this is not part of the production distribution. */
public class ValidationServer {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: ValidationServer.java config.yaml observerPort");
        var json = new ObjectMapper();
        var config = RuntimeConfig.load(Path.of(args[0]));
        var observer = HttpServer.create(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[1])), 16);
        var observers = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("validation-observer").factory());
        observer.setExecutor(observers);
        observer.createContext("/stats", exchange -> {
            byte[] body = json.writeValueAsBytes(stats());
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        observer.createContext("/gc", exchange -> {
            if (!exchange.getRequestMethod().equals("POST")) { exchange.sendResponseHeaders(405, -1); exchange.close(); return; }
            System.gc();
            byte[] body = json.writeValueAsBytes(stats());
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        try (var server = new OminiCatServer(config, new PluginRegistry())) {
            Runtime.getRuntime().addShutdownHook(new Thread(server::close, "validation-shutdown"));
            server.start(); observer.start();
            System.out.println("VALIDATION_READY port=" + server.port() + " pid=" + ProcessHandle.current().pid());
            server.awaitTermination();
        } finally { observer.stop(0); observers.shutdownNow(); }
    }
    private static LinkedHashMap<String, Object> stats() {
        var result = new LinkedHashMap<String, Object>();
        var memory = ManagementFactory.getMemoryMXBean();
        result.put("uptimeMillis", ManagementFactory.getRuntimeMXBean().getUptime());
        result.put("heapUsedBytes", memory.getHeapMemoryUsage().getUsed());
        result.put("heapCommittedBytes", memory.getHeapMemoryUsage().getCommitted());
        result.put("heapMaxBytes", memory.getHeapMemoryUsage().getMax());
        result.put("nonHeapUsedBytes", memory.getNonHeapMemoryUsage().getUsed());
        result.put("platformThreads", ManagementFactory.getThreadMXBean().getThreadCount());
        result.put("loadedClasses", ManagementFactory.getClassLoadingMXBean().getLoadedClassCount());
        result.put("gcCount", ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(gc -> Math.max(0, gc.getCollectionCount())).sum());
        result.put("gcMillis", ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(gc -> Math.max(0, gc.getCollectionTime())).sum());
        result.put("nettyDirectBytes", PooledByteBufAllocator.DEFAULT.metric().usedDirectMemory());
        result.put("nettyHeapBytes", PooledByteBufAllocator.DEFAULT.metric().usedHeapMemory());
        for (var pool : ManagementFactory.getPlatformMXBeans(java.lang.management.BufferPoolMXBean.class)) {
            if (pool.getName().equals("direct")) {
                result.put("directBufferBytes", pool.getMemoryUsed()); result.put("directBufferCount", pool.getCount());
            }
        }
        var os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.UnixOperatingSystemMXBean unix) result.put("openFileDescriptors", unix.getOpenFileDescriptorCount());
        if (os instanceof com.sun.management.OperatingSystemMXBean process) result.put("processCpuNanos", process.getProcessCpuTime());
        return result;
    }
}
