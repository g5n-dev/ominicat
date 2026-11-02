package io.ominicat.benchmarks;

import io.ominicat.core.Frame;
import io.ominicat.core.FrameEnvelope;
import java.nio.ByteOrder;
import java.nio.ReadOnlyBufferException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class DirectStateProbeTest {
    @TempDir Path temporary;

    @ParameterizedTest
    @ValueSource(strings = {"raw-direct-1", "raw-direct-4", "raw-direct-16", "envelope-direct-4", "raw-session-4",
            "audio-direct-4", "image-direct-4"})
    void allScenariosValidateIndependentResultsAndReleaseOwnedWork(String name) throws Exception {
        var scenario = DirectStateProbe.Scenario.parse(name);
        var harness = harness(name, DirectStateProbe.Fault.NONE);
        Thread owner;
        try {
            Frame first = null;
            for (long sequence = 1; sequence <= 128; sequence++) {
                Frame result = harness.invoke();
                if (!scenario.media().isEmpty()) {
                    assertSame(harness.mediaAt((int) ((sequence - 1) & 63)), result);
                    if (sequence == 1) first = result;
                    if (sequence == 65) assertSame(first, result, "reuse the bounded prebuilt pool");
                } else {
                    long expected = sequence;
                    for (int stage = 0; stage < scenario.stages(); stage++) {
                        expected = Long.rotateLeft(expected ^ (0x9e3779b97f4a7c15L + stage), 7);
                    }
                    Frame payload = result;
                    if (scenario.envelope()) {
                        FrameEnvelope envelope = assertInstanceOf(FrameEnvelope.class, result);
                        assertEquals("stage-4-" + sequence, envelope.id());
                        assertEquals("stage-3-" + sequence, envelope.causationId());
                        assertEquals("synthetic-session", envelope.sessionId());
                        assertEquals("synthetic-turn", envelope.turnId());
                        assertEquals("synthetic-correlation", envelope.correlationId());
                        assertEquals(sequence * 32, envelope.originTimeNanos());
                        assertEquals(sequence * 32 + 1, envelope.ingestTimeNanos());
                        assertEquals(sequence * 32 + 6, envelope.emitTimeNanos());
                        payload = envelope.frame();
                    }
                    assertEquals(new DirectStateProbe.Value(sequence, expected), payload);
                }
            }
            assertEquals(128, harness.successful());
            harness.assertDrained();
            owner = harness.owner();
            if (scenario.session()) assertNotSame(Thread.currentThread(), owner);
            else assertSame(Thread.currentThread(), owner);
        } finally { harness.close(); }
        if (scenario.session()) assertFalse(owner.isAlive(), "join the actual session worker after cleanup");
        harness.close();
        assertThrows(IllegalStateException.class, harness::invoke);
    }

    @ParameterizedTest
    @ValueSource(strings = {"raw-direct-1", "raw-direct-4", "raw-direct-16", "envelope-direct-4", "raw-session-4",
            "audio-direct-4", "image-direct-4"})
    void numericEnvelopeAndMediaCorruptionCannotPassTheOracle(String scenario) throws Exception {
        try (var harness = harness(scenario, DirectStateProbe.Fault.CORRUPT_OUTPUT)) {
            assertThrows(Exception.class, harness::invoke);
            assertEquals(0, harness.successful());
            assertThrows(Exception.class, harness::assertDrained);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"raw-direct-4", "envelope-direct-4", "raw-session-4"})
    void duplicateAndPostOutputFailureNeverAcknowledgeSuccess(String scenario) throws Exception {
        for (var fault : new DirectStateProbe.Fault[]{DirectStateProbe.Fault.DUPLICATE_OUTPUT,
                DirectStateProbe.Fault.POST_OUTPUT_THROW}) {
            var harness = harness(scenario, fault);
            try {
                assertThrows(Exception.class, harness::invoke);
                assertEquals(0, harness.successful(), "an early correct frame is not a successful backend return");
            } finally { harness.close(); }
            if (scenario.equals("raw-session-4")) assertFalse(harness.owner().isAlive());
        }
    }

    @Test void missingOutputFailsAndTheSessionWatchdogStillReleasesItsWorker() throws Exception {
        for (String scenario : new String[]{"raw-direct-4", "raw-session-4"}) {
            var harness = new DirectStateProbe.Harness(scenario, DirectStateProbe.Fault.MISSING_OUTPUT,
                    TimeUnit.MILLISECONDS.toNanos(500));
            try {
                assertThrows(Exception.class, harness::invoke);
                assertEquals(0, harness.successful());
            } finally { harness.close(); }
            if (scenario.equals("raw-session-4")) assertFalse(harness.owner().isAlive());
        }
    }

    @Test void aForeignCallerIsRejectedWithoutConsumingTheNextSequence() throws Exception {
        try (var harness = harness("raw-direct-4", DirectStateProbe.Fault.NONE)) {
            var failure = new AtomicReference<Throwable>();
            Thread foreign = new Thread(() -> {
                try { harness.invoke(); }
                catch (Throwable error) { failure.set(error); }
            }, "direct-state-foreign-caller");
            foreign.start(); foreign.join(3000);
            assertFalse(foreign.isAlive());
            assertInstanceOf(IllegalStateException.class, failure.get());
            assertEquals(1, assertInstanceOf(DirectStateProbe.Value.class, harness.invoke()).sequence());
            harness.assertDrained();
        }
    }

    @Test void mediaPoolsHaveExactly64ImmutableIndependentFramesAndDeclaredMetadata() {
        for (String kind : new String[]{"audio", "image"}) {
            Frame[] frames = DirectStateProbe.mediaFrames(kind);
            assertEquals(64, frames.length);
            for (int i = 0; i < frames.length; i++) {
                var data = frames[i] instanceof Frame.Audio audio ? audio.data() : ((Frame.Image) frames[i]).data();
                assertTrue(data.isReadOnly());
                assertEquals(kind.equals("audio") ? 640 : 65_536, data.remaining());
                assertThrows(ReadOnlyBufferException.class, () -> data.put(0, (byte) 9));
                if (frames[i] instanceof Frame.Audio audio) {
                    assertEquals(16_000, audio.sampleRate()); assertEquals(1, audio.channels());
                    assertEquals(i * 320L, audio.startSample()); assertEquals(ByteOrder.LITTLE_ENDIAN, data.order());
                    data.get(); assertEquals(0, audio.data().position());
                } else assertEquals("image/png", ((Frame.Image) frames[i]).mimeType());
                if (i != 0) assertNotSame(frames[i - 1], frames[i]);
            }
        }
    }

    @Test void scenarioAndWatchdogAreExplicitAndBounded() {
        for (String name : new String[]{"raw-direct-2", "raw-session-16", "envelope-session-4", "unknown"}) {
            assertThrows(IllegalArgumentException.class, () -> harness(name, DirectStateProbe.Fault.NONE));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new DirectStateProbe.Harness("raw-direct-4", DirectStateProbe.Fault.NONE, 0));
    }

    @Test void bothOriginPropertiesAreRequiredAndNeverDefaultToUnchecked() throws Exception {
        String previousCore = System.getProperty(DirectStateProbe.CORE_PROPERTY);
        String previousProbe = System.getProperty(DirectStateProbe.PROBE_PROPERTY);
        try {
            System.clearProperty(DirectStateProbe.CORE_PROPERTY);
            System.clearProperty(DirectStateProbe.PROBE_PROPERTY);
            assertThrows(IllegalArgumentException.class, DirectStateProbe::verifyConfiguredOrigins);
            System.setProperty(DirectStateProbe.CORE_PROPERTY, "a".repeat(64));
            assertThrows(IllegalArgumentException.class, DirectStateProbe::verifyConfiguredOrigins);
            System.setProperty(DirectStateProbe.PROBE_PROPERTY, "invalid");
            assertThrows(IllegalArgumentException.class, DirectStateProbe::verifyConfiguredOrigins);
        } finally {
            restore(DirectStateProbe.CORE_PROPERTY, previousCore);
            restore(DirectStateProbe.PROBE_PROPERTY, previousProbe);
        }
    }

    @Test void hashesBindActualBytesAndClassDirectoriesAreRejected() throws Exception {
        Path artifact = temporary.resolve("fixture.jar");
        byte[] contents = new byte[]{1, 2, 3, 4};
        Files.write(artifact, contents);
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contents));
        assertEquals(expected, DirectStateProbe.verifyArtifact(artifact, expected));
        assertThrows(IllegalStateException.class, () -> DirectStateProbe.verifyArtifact(artifact, "0".repeat(64)));
        Files.write(artifact, new byte[]{8});
        assertThrows(IllegalStateException.class, () -> DirectStateProbe.verifyArtifact(artifact, expected));
        assertThrows(IllegalStateException.class, () -> DirectStateProbe.verifyArtifact(temporary, expected));
        assertThrows(IllegalStateException.class, () -> DirectStateProbe.jarOrigin(DirectStateProbe.class));
    }

    private static DirectStateProbe.Harness harness(String name, DirectStateProbe.Fault fault) {
        return new DirectStateProbe.Harness(name, fault, TimeUnit.SECONDS.toNanos(10));
    }

    private static void restore(String property, String value) {
        if (value == null) System.clearProperty(property); else System.setProperty(property, value);
    }
}
