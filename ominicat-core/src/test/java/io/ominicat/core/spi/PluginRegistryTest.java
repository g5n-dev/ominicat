package io.ominicat.core.spi;

import static org.junit.jupiter.api.Assertions.*;

import io.ominicat.core.Frame;
import io.ominicat.core.Processor;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginRegistryTest {
    @TempDir Path temporary;

    @Test void discoversFactoryFromServiceDescriptor() throws Exception {
        Path descriptor = temporary.resolve("META-INF/services/" + ProcessorFactory.class.getName());
        Files.createDirectories(descriptor.getParent());
        Files.writeString(descriptor, DiscoveredFactory.class.getName() + "\n");
        try (var loader = new URLClassLoader(new URL[]{temporary.toUri().toURL()}, getClass().getClassLoader())) {
            PluginRegistry registry = new PluginRegistry(loader);
            assertTrue(registry.types().contains("discovered"));
            List<Frame> output = new ArrayList<>();
            registry.create("discovered", Map.of("prefix", "echo: ")).process(new Frame.Text("hello"), output::add);
            assertEquals(List.of(new Frame.Text("echo: hello")), output);
        }
    }

    @Test void createsFreshInstancesAndProtectsConfigurationSnapshot() {
        PluginRegistry registry = new PluginRegistry();
        registry.register(new ProcessorFactory() {
            @Override public String type() { return "fresh"; }
            @Override public Processor create(Map<String, String> options) {
                assertThrows(UnsupportedOperationException.class, () -> options.put("x", "bad"));
                return new Processor() {
                    @Override public void process(Frame frame, io.ominicat.core.FrameSink next) throws Exception { next.accept(frame); }
                };
            }
        });
        assertNotSame(registry.create("fresh", Map.of()), registry.create("fresh", Map.of()));
        assertThrows(UnsupportedOperationException.class, () -> registry.types().add("injected"));
    }

    @Test void rejectsDuplicateInvalidAndUnknownPluginTypes() {
        PluginRegistry registry = new PluginRegistry();
        registry.register(new DiscoveredFactory());
        assertThrows(IllegalArgumentException.class, () -> registry.register(new DiscoveredFactory()));
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> registry.create("missing", Map.of()));
        assertTrue(missing.getMessage().contains("discovered"));
        assertThrows(IllegalArgumentException.class, () -> registry.register(factory("Bad Type")));
        assertThrows(IllegalArgumentException.class, () -> registry.register(factory("")));
        assertThrows(NullPointerException.class, () -> registry.register(factory(null)));
        registry.register(factory("null-processor"));
        assertThrows(NullPointerException.class, () -> registry.create("null-processor", Map.of()));
    }

    private static ProcessorFactory factory(String type) {
        return new ProcessorFactory() {
            @Override public String type() { return type; }
            @Override public Processor create(Map<String, String> options) { return null; }
        };
    }

    public static final class DiscoveredFactory implements ProcessorFactory {
        @Override public String type() { return "discovered"; }
        @Override public Processor create(Map<String, String> options) {
            String prefix = options.getOrDefault("prefix", "");
            return (frame, next) -> {
                if (frame instanceof Frame.Text text) next.accept(new Frame.Text(prefix + text.text()));
                else next.accept(frame);
            };
        }
    }
}
