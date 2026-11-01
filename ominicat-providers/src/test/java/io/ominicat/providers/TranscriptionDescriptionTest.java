package io.ominicat.providers;

import static org.junit.jupiter.api.Assertions.*;

import io.ominicat.core.Frame;
import io.ominicat.core.Processor;
import io.ominicat.core.spi.DescribedProcessorFactory;
import io.ominicat.core.spi.PluginRegistry;
import io.ominicat.core.spi.ProcessorDescription;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Local adapter contracts and scripted counterexamples, not remote ASR validation. */
@Timeout(10)
class TranscriptionDescriptionTest extends ProviderHttpTestSupport {
    private static final String UTTERANCE = TranscriptionFactory.UTTERANCE_FINALS;
    private static final String PARTIALS = TranscriptionFactory.PRE_BOUNDARY_PARTIALS;

    @Test
    void describesWithoutResolvingMissingModelCredentialsOrMalformedValues() {
        TranscriptionFactory factory = new TranscriptionFactory();
        ProcessorDescription empty = factory.describe(Map.of());
        ProcessorDescription malformed = factory.describe(unusableOptions());

        assertSame(empty, malformed);
        assertEquals(factory.type(), empty.type());
        assertEquals(Optional.empty(), empty.implementationVersion());
        assertEquals(Map.of(UTTERANCE, ProcessorDescription.Support.SUPPORTED,
                PARTIALS, ProcessorDescription.Support.UNSUPPORTED), empty.capabilities());
        assertThrows(UnsupportedOperationException.class,
                () -> empty.capabilities().put("fixture.extra.v1", ProcessorDescription.Support.SUPPORTED));
    }

    @Test
    void registryDiscoveryAndQueriesDoNotCreateProcessorsOrContactProvider() {
        AtomicInteger requests = new AtomicInteger();
        route("/audio/transcriptions", exchange -> {
            requests.incrementAndGet();
            respond(exchange, 500, "application/json", "{}");
        });
        CountingFactory counting = new CountingFactory();
        PluginRegistry registry = registry(counting);

        assertEquals(counting.describe(Map.of()), registry.describe(counting.type(), options()).orElseThrow());
        assertEquals(counting.describe(Map.of()), registry.describe(counting.type(), unusableOptions()).orElseThrow());
        assertEquals(ProcessorDescription.Support.SUPPORTED,
                new PluginRegistry().describe(counting.type(), Map.of()).orElseThrow().support(UTTERANCE));
        assertEquals(0, counting.creates.get());
        assertEquals(0, requests.get());
    }

    @Test
    void explicitPartialRequirementRejectsUtteranceAdapterBeforeCreation() {
        CountingFactory counting = new CountingFactory();
        PluginRegistry registry = registry(counting);
        registry.requireDeclaredCapabilities(counting.type(), unusableOptions(), Set.of(UTTERANCE));

        IllegalArgumentException mismatch = assertThrows(IllegalArgumentException.class,
                () -> registry.requireDeclaredCapabilities(counting.type(), unusableOptions(), Set.of(PARTIALS)));
        assertTrue(mismatch.getMessage().contains(counting.type()));
        assertTrue(mismatch.getMessage().contains(PARTIALS));
        assertTrue(mismatch.getMessage().contains("UNSUPPORTED"));
        assertFalse(mismatch.getMessage().contains("synthetic-missing-credential"));
        assertEquals(0, counting.creates.get());
    }

    @Test
    void describeAndCreateShareAllowedKeysWithoutEchoingOptionValues() {
        TranscriptionFactory factory = new TranscriptionFactory();
        Map<String, String> unknown = Map.of("notAnOption", "synthetic-private-value");
        IllegalArgumentException metadata = assertThrows(IllegalArgumentException.class,
                () -> factory.describe(unknown));
        IllegalArgumentException construction = assertThrows(IllegalArgumentException.class,
                () -> factory.create(unknown));

        assertEquals("unknown option: notAnOption", metadata.getMessage());
        assertEquals(metadata.getMessage(), construction.getMessage());
        assertFalse(metadata.getMessage().contains(unknown.get("notAnOption")));
    }

    @Test
    void declaredUtteranceContractMatchesLocalMultipartBoundaryBehavior() throws Exception {
        byte[] first = chunkBytes(1);
        byte[] second = chunkBytes(37);
        byte[] expected = new byte[first.length + second.length];
        System.arraycopy(first, 0, expected, 0, first.length);
        System.arraycopy(second, 0, expected, first.length, second.length);
        AtomicInteger requests = new AtomicInteger();
        route("/audio/transcriptions", exchange -> {
            requests.incrementAndGet();
            assertEquals("POST", exchange.getRequestMethod());
            assertTrue(exchange.getRequestHeaders().getFirst("Content-Type").startsWith("multipart/form-data; boundary="));
            byte[] body = exchange.getRequestBody().readAllBytes();
            String multipart = new String(body, StandardCharsets.ISO_8859_1);
            assertTrue(multipart.contains("name=\"model\"\r\n\r\ntest-model\r\n"));
            int offset = multipart.indexOf("RIFF");
            assertTrue(offset > 0);
            ByteBuffer wav = ByteBuffer.wrap(body, offset, 44).slice().order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(36 + expected.length, wav.getInt(4));
            assertEquals(1, wav.getShort(22));
            assertEquals(16000, wav.getInt(24));
            assertEquals(16, wav.getShort(34));
            assertEquals(expected.length, wav.getInt(40));
            assertArrayEquals(expected, Arrays.copyOfRange(body, offset + 44, offset + 44 + expected.length));
            respond(exchange, 200, "application/json", "{\"text\":\"scripted response\"}");
        });
        PluginRegistry registry = new PluginRegistry();
        Map<String, String> config = options("speaker", "fixture-speaker", "roles", "fixture=user");
        registry.requireDeclaredCapabilities("openai-transcribe", config, Set.of(UTTERANCE));
        List<Frame> output = new ArrayList<>();
        try (Processor processor = registry.create("openai-transcribe", config)) {
            processor.process(audio(first, 0), output::add);
            processor.process(audio(second, 160), output::add);
            assertEquals(0, requests.get(), "PCM before an explicit boundary must remain buffered");
            assertTrue(output.isEmpty());

            Frame.Control boundary = new Frame.Control(Frame.Signal.TURN_END);
            processor.process(boundary, output::add);
            assertEquals(1, requests.get());
            assertEquals(2, output.size());
            Frame.Transcript transcript = assertInstanceOf(Frame.Transcript.class, output.get(0));
            assertEquals("scripted response", transcript.text());
            assertEquals("fixture-speaker", transcript.speaker());
            assertEquals("user", transcript.role());
            assertEquals("fixture:1", transcript.segmentId());
            assertEquals(0, transcript.revision());
            assertEquals(0, transcript.startMillis());
            assertEquals(20, transcript.endMillis());
            assertTrue(transcript.finalResult());
            assertEquals(-1, transcript.confidence());
            assertSame(boundary, output.get(1));
        }
    }

    @Test
    void scriptedIncrementalFixtureMeetsPreBoundaryPartialBehavior() throws Exception {
        ScriptedFactory factory = new ScriptedFactory(true);
        PluginRegistry registry = registry(factory);
        registry.requireDeclaredCapabilities(factory.type(), Map.of(), Set.of(PARTIALS));
        List<Frame> output = new ArrayList<>();
        try (Processor processor = registry.create(factory.type(), Map.of())) {
            assertPreBoundaryPartials(processor, output);
            assertEquals(2, output.size());
            assertEquals(List.of(1L, 2L), output.stream().map(frame -> ((Frame.Transcript) frame).revision()).toList());
            processor.process(new Frame.Control(Frame.Signal.TURN_END), output::add);
            assertTrue(assertInstanceOf(Frame.Transcript.class, output.get(2)).finalResult());
        }
    }

    @Test
    void declarationAloneCannotMakeBoundaryOnlyFixturePassPartialProbe() throws Exception {
        ScriptedFactory factory = new ScriptedFactory(false);
        PluginRegistry registry = registry(factory);
        // Deliberately dishonest metadata is accepted as a declaration, not a behavior certificate.
        registry.requireDeclaredCapabilities(factory.type(), Map.of(), Set.of(PARTIALS));
        List<Frame> output = new ArrayList<>();
        try (Processor processor = registry.create(factory.type(), Map.of())) {
            AssertionError mismatch = assertThrows(AssertionError.class,
                    () -> assertPreBoundaryPartials(processor, output));
            assertTrue(mismatch.getMessage().contains("before boundary"));
            assertTrue(output.isEmpty());
            processor.process(audio(chunkBytes(37), 160), output::add);
            processor.process(new Frame.Control(Frame.Signal.TURN_END), output::add);
            assertEquals(2, output.size());
            Frame.Transcript finalOnly = assertInstanceOf(Frame.Transcript.class, output.get(0));
            assertTrue(finalOnly.finalResult());
            assertEquals("scripted chunks: 2", finalOnly.text());
        }
    }

    /** Observable behavior: each chunk yields a partial before sending any explicit boundary. */
    private static void assertPreBoundaryPartials(Processor processor, List<Frame> output) throws Exception {
        for (int chunk = 0; chunk < 2; chunk++) {
            int before = output.size();
            processor.process(audio(chunkBytes(chunk + 1), chunk * 160L), output::add);
            assertTrue(output.subList(before, output.size()).stream()
                    .anyMatch(frame -> frame instanceof Frame.Transcript transcript && !transcript.finalResult()),
                    "chunk " + (chunk + 1) + " must yield a nonfinal transcript before boundary");
        }
    }

    private static Frame.Audio audio(byte[] bytes, long startSample) {
        return new Frame.Audio(ByteBuffer.wrap(bytes), 16000, 1, "fixture", startSample);
    }

    private static byte[] chunkBytes(int seed) {
        byte[] bytes = new byte[320];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (seed + i * 3);
        return bytes;
    }

    private static Map<String, String> unusableOptions() {
        return Map.of("baseUrl", "not a URI [synthetic]", "model", " ",
                "apiKeyEnv", "synthetic-missing-credential", "maxStreams", "not-an-integer",
                "maxAudioBytes", "-1");
    }

    private static PluginRegistry registry(DescribedProcessorFactory factory) {
        return new PluginRegistry(ClassLoader.getPlatformClassLoader()).register(factory);
    }

    private static final class CountingFactory implements DescribedProcessorFactory {
        private final TranscriptionFactory delegate = new TranscriptionFactory();
        private final AtomicInteger creates = new AtomicInteger();

        @Override public String type() { return delegate.type(); }
        @Override public ProcessorDescription describe(Map<String, String> options) { return delegate.describe(options); }
        @Override public Processor create(Map<String, String> options) {
            creates.incrementAndGet();
            throw new AssertionError("metadata lookup must not construct a processor");
        }
    }

    /** Synthetic protocol behavior only: emits scripted text without recognizing audio. */
    private record ScriptedFactory(boolean incremental) implements DescribedProcessorFactory {
        @Override public String type() { return incremental ? "fixture-incremental" : "fixture-boundary-only"; }
        @Override public ProcessorDescription describe(Map<String, String> options) {
            return new ProcessorDescription(type(), Optional.empty(), Map.of(PARTIALS, ProcessorDescription.Support.SUPPORTED));
        }
        @Override public Processor create(Map<String, String> options) {
            return new Processor() {
                private int chunks;
                @Override public void process(Frame frame, io.ominicat.core.FrameSink next) throws Exception {
                    if (frame instanceof Frame.Audio) {
                        chunks++;
                        if (incremental) next.accept(transcript(false));
                    } else if (frame instanceof Frame.Control control && control.signal() == Frame.Signal.TURN_END) {
                        next.accept(transcript(true));
                        next.accept(frame);
                    } else {
                        next.accept(frame);
                    }
                }
                private Frame.Transcript transcript(boolean finalResult) {
                    return new Frame.Transcript("scripted chunks: " + chunks, "fixture-speaker", 0, chunks * 10L,
                            finalResult, -1, "fixture-segment", chunks, "user");
                }
            };
        }
    }
}
