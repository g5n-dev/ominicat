package io.ominicat.benchmarks;

import io.ominicat.core.Pipeline;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class DirectPlanStartupProbeTest {
    @TempDir Path temporary;

    @ParameterizedTest
    @ValueSource(strings = {"build-4", "first-direct-4"})
    void actualFourStageBuildAndFirstCallHaveDistinctTimingBoundaries(String scenario) throws Exception {
        var harness = new DirectPlanStartupProbe.Harness(scenario, DirectPlanStartupProbe.Fault.NONE);
        Pipeline previous = null;
        for (int index = 1; index <= 128; index++) {
            Pipeline next = harness.invoke();
            assertNotNull(next);
            if (previous != null) assertNotSame(previous, next, "each timed call builds a new actual pipeline");
            previous = next;
        }
        assertEquals(128, harness.built());
        assertEquals(scenario.equals("build-4") ? 0 : 128, harness.successfulCalls(),
                "construction-only must not smuggle processing into measured operations");
        harness.verifyLastBuild();
        assertEquals(scenario.equals("build-4") ? 1 : 128, harness.successfulCalls());
        long witness = harness.witness();
        harness.verifyLastBuild();
        assertEquals(witness, harness.witness(), "already verified last build is not replayed");
        harness.close();
        harness.close();
        assertThrows(IllegalStateException.class, harness::invoke);
        assertThrows(IllegalStateException.class, harness::verifyLastBuild);
    }

    @ParameterizedTest
    @ValueSource(strings = {"build-4", "first-direct-4"})
    void wrongStageOrderCountFrameOrOutputNeverPassesValidation(String scenario) throws Exception {
        for (var fault : new DirectPlanStartupProbe.Fault[]{DirectPlanStartupProbe.Fault.MISSING_STAGE,
                DirectPlanStartupProbe.Fault.WRONG_ORDER, DirectPlanStartupProbe.Fault.WRONG_FRAME,
                DirectPlanStartupProbe.Fault.MISSING_OUTPUT, DirectPlanStartupProbe.Fault.DUPLICATE_OUTPUT,
                DirectPlanStartupProbe.Fault.POST_OUTPUT_THROW}) {
            try (var harness = new DirectPlanStartupProbe.Harness(scenario, fault)) {
                if (scenario.equals("build-4")) {
                    assertNotNull(harness.invoke());
                    assertEquals(0, harness.successfulCalls());
                    assertThrows(Exception.class, harness::verifyLastBuild, fault.name());
                } else {
                    assertThrows(Exception.class, harness::invoke, fault.name());
                }
                assertEquals(0, harness.successfulCalls(), "a terminal frame before an exception is not success");
                assertThrows(IllegalStateException.class, harness::invoke, "a failed fixture cannot resume as green");
            }
        }
    }

    @Test void foreignCallCannotConsumeOrPoisonTheOwnersNextBuild() throws Exception {
        try (var harness = new DirectPlanStartupProbe.Harness("first-direct-4", DirectPlanStartupProbe.Fault.NONE)) {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread foreign = new Thread(() -> {
                try { harness.invoke(); }
                catch (Throwable error) { failure.set(error); }
            }, "startup-foreign-caller");
            foreign.start();
            foreign.join(3000);
            assertFalse(foreign.isAlive());
            assertInstanceOf(IllegalStateException.class, failure.get());
            assertEquals(0, harness.built());
            harness.invoke();
            harness.verifyLastBuild();
            assertEquals(1, harness.built());
            assertEquals(1, harness.successfulCalls());
        }
    }

    @Test void scenarioAndValidationAreExplicit() {
        for (String scenario : new String[]{"build-1", "first-direct-16", "raw-direct-4", "unknown"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new DirectPlanStartupProbe.Harness(scenario, DirectPlanStartupProbe.Fault.NONE));
        }
        try (var harness = new DirectPlanStartupProbe.Harness("build-4", DirectPlanStartupProbe.Fault.NONE)) {
            assertThrows(IllegalStateException.class, harness::verifyLastBuild);
        }
    }

    @Test void missingOrDirectoryOriginsFailWithoutPrintingSuccessMarkers() throws Exception {
        String previousCore = System.getProperty(DirectStateProbe.CORE_PROPERTY);
        String previousProbe = System.getProperty(DirectStateProbe.PROBE_PROPERTY);
        PrintStream previousOutput = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            System.clearProperty(DirectStateProbe.CORE_PROPERTY);
            System.clearProperty(DirectStateProbe.PROBE_PROPERTY);
            assertThrows(IllegalArgumentException.class, DirectPlanStartupProbe::verifyConfiguredOrigins);
            System.setProperty(DirectStateProbe.CORE_PROPERTY, "a".repeat(64));
            assertThrows(IllegalArgumentException.class, DirectPlanStartupProbe::verifyConfiguredOrigins);
            System.setProperty(DirectStateProbe.PROBE_PROPERTY, "invalid");
            assertThrows(IllegalArgumentException.class, DirectPlanStartupProbe::verifyConfiguredOrigins);
            System.setProperty(DirectStateProbe.PROBE_PROPERTY, "b".repeat(64));
            assertThrows(IllegalStateException.class, DirectPlanStartupProbe::verifyConfiguredOrigins,
                    "unit test classes are directories, not measured origin JARs");
            assertEquals("", output.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(previousOutput);
            restore(DirectStateProbe.CORE_PROPERTY, previousCore);
            restore(DirectStateProbe.PROBE_PROPERTY, previousProbe);
        }
    }

    @Test void theSharedArtifactVerifierRejectsWrongOrChangedProbeBytes() throws Exception {
        Path artifact = temporary.resolve("synthetic.jar");
        byte[] bytes = new byte[]{7, 8, 9};
        Files.write(artifact, bytes);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        assertEquals(hash, DirectStateProbe.verifyArtifact(artifact, hash));
        assertThrows(IllegalStateException.class, () -> DirectStateProbe.verifyArtifact(artifact, "0".repeat(64)));
        Files.write(artifact, new byte[]{3});
        assertThrows(IllegalStateException.class, () -> DirectStateProbe.verifyArtifact(artifact, hash));
    }

    private static void restore(String property, String value) {
        if (value == null) System.clearProperty(property); else System.setProperty(property, value);
    }
}
