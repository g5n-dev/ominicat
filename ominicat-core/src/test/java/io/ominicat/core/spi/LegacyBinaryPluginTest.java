package io.ominicat.core.spi;

import static org.junit.jupiter.api.Assertions.*;

import io.ominicat.core.Frame;
import io.ominicat.core.FrameSink;
import io.ominicat.core.Pipeline;
import io.ominicat.core.Processor;
import io.ominicat.core.SessionOptions;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarInputStream;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(15)
class LegacyBinaryPluginTest {
    @TempDir Path temporary;

    @Test void frozenJava17JarContainsOnlyOldPluginClassesAndMatchesItsSourceBinding() throws Exception {
        byte[] jar = resource("legacy-plugin.jar");
        String metadata = new String(resource("metadata.json"), StandardCharsets.UTF_8);
        assertEquals("d6698fa275006e40d7d69b2b1b1debc8deae1174", field(metadata, "baselineRevision"));
        assertTrue(field(metadata, "compiler").startsWith("javac 17."));
        assertEquals(field(metadata, "jarSha256"), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(jar)));
        List<String> entries = new ArrayList<>();
        try (var input = new JarInputStream(new java.io.ByteArrayInputStream(jar))) {
            java.util.jar.JarEntry entry;
            while ((entry = input.getNextJarEntry()) != null) {
                entries.add(entry.getName());
                assertFalse(entry.getName().startsWith("io/ominicat/"), "legacy JAR must not shadow current core");
                if (entry.getName().endsWith(".class")) {
                    byte[] bytes = input.readAllBytes();
                    assertEquals(0xcafebabe, ByteBuffer.wrap(bytes).getInt());
                    assertEquals(61, Short.toUnsignedInt(ByteBuffer.wrap(bytes).getShort(6)));
                    String constants = new String(bytes, StandardCharsets.ISO_8859_1);
                    assertFalse(constants.contains("DescribedProcessorFactory"));
                    assertFalse(constants.contains("ProcessorDescription"));
                }
            }
        }
        assertEquals(Set.of("legacy/fixture/LegacyFactory.class", "legacy/fixture/LegacyFactory$1.class",
                "META-INF/services/io.ominicat.core.spi.ProcessorFactory"), Set.copyOf(entries));
    }

    @Test void oldBinaryStaysUnknownWhileItsIndependentSessionsExecuteAndClose() throws Exception {
        Path jar = temporary.resolve("legacy-plugin.jar");
        Files.write(jar, resource("legacy-plugin.jar"));
        try (var loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, getClass().getClassLoader())) {
            PluginRegistry registry = new PluginRegistry(loader);
            assertTrue(registry.types().contains("legacy-fixture"));
            assertTrue(registry.describe("legacy-fixture", Map.of()).isEmpty());
            registry.requireDeclaredCapabilities("legacy-fixture", Map.of(), Set.of());
            IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class, () ->
                    registry.requireDeclaredCapabilities("legacy-fixture", Map.of(), Set.of("fixture.partial.v1")));
            assertTrue(unknown.getMessage().contains("UNKNOWN"));
            Processor first = registry.create("legacy-fixture", Map.of("prefix", "first"));
            Processor second = registry.create("legacy-fixture", Map.of("prefix", "second"));
            assertNotSame(first, second);
            assertSame(loader, first.getClass().getClassLoader());
            var firstOutput = new ArrayBlockingQueue<Frame>(2);
            var secondOutput = new ArrayBlockingQueue<Frame>(2);
            try (var a = Pipeline.builder().add(first).build().openSession(new SessionOptions(2), firstOutput::put);
                 var b = Pipeline.builder().add(second).build().openSession(new SessionOptions(2), secondOutput::put)) {
                assertTrue(a.trySubmit(new Frame.Text("hello")));
                assertTrue(a.trySubmit(new Frame.Text("again")));
                assertTrue(b.trySubmit(new Frame.Text("hello")));
                a.finish(); b.finish();
                a.completion().get(5, TimeUnit.SECONDS);
                b.completion().get(5, TimeUnit.SECONDS);
                assertEquals(List.of(new Frame.Text("first:1:hello"), new Frame.Text("first:2:again")), List.copyOf(firstOutput));
                assertEquals(List.of(new Frame.Text("second:1:hello")), List.copyOf(secondOutput));
            }
            FrameSink ignored = frame -> {};
            assertThrows(IllegalStateException.class, () -> first.process(new Frame.Text("closed"), ignored));
            assertThrows(IllegalStateException.class, () -> second.process(new Frame.Text("closed"), ignored));
        }
    }

    private static byte[] resource(String name) throws Exception {
        try (var input = LegacyBinaryPluginTest.class.getResourceAsStream("/legacy-plugin/" + name)) {
            assertNotNull(input, name);
            return input.readAllBytes();
        }
    }

    private static String field(String json, String name) {
        var matcher = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        assertTrue(matcher.find(), name);
        return matcher.group(1);
    }
}
