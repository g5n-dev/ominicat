package io.ominicat.server;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Validated, declarative server and per-connection pipeline configuration. */
public record RuntimeConfig(
        String host, int port, int queueCapacity, int maxSessions, int maxFrameBytes,
        int idleTimeoutSeconds, String tokenEnv, List<Stage> stages) {
    public RuntimeConfig {
        Objects.requireNonNull(host, "host");
        if (host.isBlank()) throw new IllegalArgumentException("host must not be blank");
        if (port < 0 || port > 65535) throw new IllegalArgumentException("port must be 0..65535");
        if (queueCapacity < 1 || queueCapacity > 65_536) throw new IllegalArgumentException("queueCapacity must be 1..65536");
        if (maxSessions < 1 || maxSessions > 100_000) throw new IllegalArgumentException("maxSessions must be 1..100000");
        if (maxFrameBytes < 1024 || maxFrameBytes > 16 * 1024 * 1024) throw new IllegalArgumentException("maxFrameBytes must be 1024..16777216");
        if (idleTimeoutSeconds < 1 || idleTimeoutSeconds > 86400) throw new IllegalArgumentException("idleTimeoutSeconds must be 1..86400");
        if (tokenEnv != null && !tokenEnv.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("tokenEnv must name an environment variable");
        stages = List.copyOf(Objects.requireNonNull(stages, "stages"));
        if (stages.isEmpty() || stages.size() > 64) throw new IllegalArgumentException("stages must contain 1..64 processors");
    }

    /** Port zero asks the operating system to select an available port. */
    public static RuntimeConfig defaults() {
        return new RuntimeConfig("127.0.0.1", 8080, 256, 256, 1_048_576, 60, null,
                List.of(new Stage("echo", Map.of())));
    }

    /** Loads YAML, rejects unknown properties, and applies defaults for omitted settings. */
    public static RuntimeConfig load(Path path) throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        Document document = mapper.readValue(path.toFile(), Document.class);
        if (document == null) throw new IllegalArgumentException("Configuration must be a YAML object");
        RuntimeConfig defaults = defaults();
        return new RuntimeConfig(
                document.host == null ? defaults.host : document.host,
                document.port == null ? defaults.port : document.port,
                document.queueCapacity == null ? defaults.queueCapacity : document.queueCapacity,
                document.maxSessions == null ? defaults.maxSessions : document.maxSessions,
                document.maxFrameBytes == null ? defaults.maxFrameBytes : document.maxFrameBytes,
                document.idleTimeoutSeconds == null ? defaults.idleTimeoutSeconds : document.idleTimeoutSeconds,
                document.tokenEnv,
                document.stages == null ? defaults.stages : document.stages);
    }

    /** A plugin type and its own validated, string-valued options. */
    public record Stage(String type, Map<String, String> options) {
        public Stage {
            if (type == null || !type.matches("[a-z][a-z0-9._-]{0,63}")) throw new IllegalArgumentException("stage type must be a valid plugin identifier");
            options = options == null ? Map.of() : Map.copyOf(options);
        }
    }

    /** Jackson input DTO keeps missing values distinct from invalid explicit zero values. */
    public static final class Document {
        public String host;
        public Integer port;
        public Integer queueCapacity;
        public Integer maxSessions;
        public Integer maxFrameBytes;
        public Integer idleTimeoutSeconds;
        public String tokenEnv;
        public List<Stage> stages;
    }
}
