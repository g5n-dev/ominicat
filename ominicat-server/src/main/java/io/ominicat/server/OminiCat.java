package io.ominicat.server;

import io.ominicat.core.spi.PluginRegistry;
import java.nio.file.Path;

/** Standalone launcher; validates configuration and installed plugin types before binding. */
public final class OminiCat {
    private OminiCat() { }

    public static void main(String[] args) throws Exception {
        RuntimeConfig config;
        if (args.length == 0) config = RuntimeConfig.defaults();
        else if (args.length == 2 && args[0].equals("--config")) config = RuntimeConfig.load(Path.of(args[1]));
        else {
            System.err.println("Usage: java -jar ominicat-server.jar [--config path/to/runtime.yaml]");
            System.exit(2);
            return;
        }
        PluginRegistry plugins = new PluginRegistry();
        try (OminiCatServer server = new OminiCatServer(config, plugins)) {
            Runtime.getRuntime().addShutdownHook(new Thread(server::close, "ominicat-shutdown"));
            server.start();
            System.out.println("OminiCat listening on http://" + config.host() + ":" + server.port());
            System.out.println("Installed processors: " + plugins.types());
            server.awaitTermination();
        }
    }
}
