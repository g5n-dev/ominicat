import com.sun.net.httpserver.HttpServer;
import io.ominicat.core.Processor;
import io.ominicat.core.spi.PluginRegistry;
import io.ominicat.core.spi.ProcessorFactory;
import io.ominicat.server.OminiCatServer;
import io.ominicat.server.RuntimeConfig;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/** Deliberately wrong loopback fixtures for checking that the sustained driver rejects bad results. */
public class NegativeControl {
    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !List.of("corrupt", "drop").contains(args[0])) throw new IllegalArgumentException("Usage: NegativeControl.java corrupt|drop serverPort observerPort");
        String mode = args[0];
        var registry = new PluginRegistry().register(new ProcessorFactory() {
            @Override public String type() { return "negative-control"; }
            @Override public Processor create(Map<String, String> options) {
                return (frame, next) -> {
                    if (mode.equals("corrupt")) next.accept(new io.ominicat.core.Frame.Text("INTENTIONALLY_WRONG"));
                };
            }
        });
        var config = new RuntimeConfig("127.0.0.1", Integer.parseInt(args[1]), 8, 16, 262144, 15, null,
                List.of(new RuntimeConfig.Stage("negative-control", Map.of())));
        var observer = HttpServer.create(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[2])), 8);
        var executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());
        observer.setExecutor(executor);
        observer.createContext("/", exchange -> {
            // Explicit dummy observer counters: these fixtures validate response/progress checks, not memory.
            byte[] body = "{\"heapUsedBytes\":0,\"openFileDescriptors\":0}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        try (var server = new OminiCatServer(config, registry)) {
            Runtime.getRuntime().addShutdownHook(new Thread(server::close));
            server.start(); observer.start(); System.out.println("NEGATIVE_CONTROL_READY mode=" + mode + " pid=" + ProcessHandle.current().pid());
            server.awaitTermination();
        } finally { observer.stop(0); executor.shutdownNow(); }
    }
}
