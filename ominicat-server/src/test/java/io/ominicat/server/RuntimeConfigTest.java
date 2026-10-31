package io.ominicat.server;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeConfigTest {
    @TempDir Path temporary;

    @Test void omittedSettingsUseDefaults() throws Exception {
        var config = load("port: 0\n");
        assertEquals(0, config.port());
        assertEquals("127.0.0.1", config.host());
        assertEquals(256, config.queueCapacity());
        assertEquals("echo", config.stages().getFirst().type());
    }

    @Test void yamlAssemblesOrderedPluginStagesAndOptions() throws Exception {
        var config = load("""
                host: 127.0.0.1
                port: 9090
                queueCapacity: 32
                maxSessions: 10
                maxFrameBytes: 2048
                idleTimeoutSeconds: 120
                tokenEnv: OMINICAT_TOKEN
                stages:
                  - type: echo
                    options: {prefix: 'hello '}
                  - type: custom
                """);
        assertEquals(9090, config.port());
        assertEquals(32, config.queueCapacity());
        assertEquals("OMINICAT_TOKEN", config.tokenEnv());
        assertEquals("hello ", config.stages().getFirst().options().get("prefix"));
        assertEquals(Map.of(), config.stages().get(1).options());
    }

    @Test void unknownPropertiesFailEarly() {
        assertThrows(Exception.class, () -> load("queueCapcity: 10\n"));
        assertThrows(Exception.class, () -> load("stages:\n  - type: echo\n    typo: true\n"));
        assertThrows(Exception.class, () -> load("port: 0\n---\nport: 8080\n"));
    }

    @Test void explicitInvalidZeroIsNotReplacedByDefault() {
        assertThrows(IllegalArgumentException.class, () -> load("queueCapacity: 0\n"));
        assertThrows(IllegalArgumentException.class, () -> load("maxSessions: 0\n"));
        assertThrows(IllegalArgumentException.class, () -> load("maxFrameBytes: 0\n"));
        assertThrows(IllegalArgumentException.class, () -> load("idleTimeoutSeconds: 0\n"));
    }

    @Test void rejectsInvalidBoundsAndEmptyPipelines() {
        assertThrows(IllegalArgumentException.class, () -> load("port: 65536\n"));
        assertThrows(IllegalArgumentException.class, () -> load("maxFrameBytes: 99999999\n"));
        assertThrows(IllegalArgumentException.class, () -> load("stages: []\n"));
        assertThrows(IllegalArgumentException.class, () -> new RuntimeConfig.Stage(" ", Map.of()));
    }

    @Test void secretsAreEnvironmentReferences() {
        assertThrows(IllegalArgumentException.class, () -> load("tokenEnv: 'literal secret'\n"));
    }

    @Test void collectionsAreImmutable() {
        var options = new java.util.HashMap<>(Map.of("a", "b"));
        var stages = new java.util.ArrayList<>(List.of(new RuntimeConfig.Stage("echo", options)));
        var config = new RuntimeConfig("127.0.0.1", 0, 1, 1, 1024, 1, null, stages);
        options.put("a", "changed"); stages.clear();
        assertEquals("b", config.stages().getFirst().options().get("a"));
        assertThrows(UnsupportedOperationException.class, () -> config.stages().clear());
    }

    private RuntimeConfig load(String yaml) throws Exception {
        Path path = temporary.resolve("runtime.yaml");
        Files.writeString(path, yaml);
        return RuntimeConfig.load(path);
    }
}
