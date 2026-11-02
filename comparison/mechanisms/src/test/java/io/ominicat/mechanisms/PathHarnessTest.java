package io.ominicat.mechanisms;

import java.util.stream.Stream;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class PathHarnessTest {
    static Stream<Arguments> paths() {
        return Stream.of("ominicat", "camel").flatMap(framework ->
                Stream.of("caller", "handoff").map(execution -> Arguments.of(framework, execution)));
    }

    @ParameterizedTest @MethodSource("paths")
    void realPathsUseTheDeclaredThreadValidateEveryOutputAndReleaseWorker(String framework, String execution) throws Exception {
        Thread worker;
        var path = new PathHarness(framework, execution, PathHarness.Fault.NONE);
        try {
            worker = path.worker();
            if (execution.equals("caller")) {
                assertNull(worker);
                assertSame(Thread.currentThread(), path.stageOwner());
            } else {
                assertNotNull(worker);
                assertNotSame(Thread.currentThread(), worker);
                assertSame(worker, path.stageOwner());
                assertTrue(worker.isDaemon());
            }
            for (long sequence = 1; sequence <= 128; sequence++) {
                PathHarness.Value output = path.invoke(new PathHarness.Value(sequence, sequence));
                long expected = sequence;
                for (int stage = 0; stage < 4; stage++) expected = Long.rotateLeft(expected ^ (0x9e3779b97f4a7c15L + stage), 7);
                assertEquals(new PathHarness.Value(sequence, expected), output);
            }
            assertEquals(128, path.completed());
            path.assertDrained();
        } finally { path.close(); }
        if (worker != null) assertFalse(worker.isAlive(), "the actual handoff thread must terminate");
        path.close();
        assertThrows(IllegalStateException.class, () -> path.invoke(new PathHarness.Value(129, 129)));
    }

    static Stream<Arguments> faults() {
        return paths().flatMap(path -> Stream.of(PathHarness.Fault.WRONG_VALUE, PathHarness.Fault.MISSING_OUTPUT,
                PathHarness.Fault.DUPLICATE_OUTPUT, PathHarness.Fault.POST_OUTPUT_THROW)
                .map(fault -> Arguments.of(path.get()[0], path.get()[1], fault)));
    }

    @ParameterizedTest @MethodSource("faults")
    void invalidOrPostOutputFailureNeverBecomesASuccessfulAcknowledgment(String framework, String execution,
                                                                       PathHarness.Fault fault) throws Exception {
        var path = new PathHarness(framework, execution, fault);
        Thread worker = path.worker();
        try {
            assertThrows(Exception.class, () -> path.invoke(new PathHarness.Value(1, 1)));
            assertEquals(0, path.completed(), "a valid early output cannot hide later backend failure");
            assertThrows(Exception.class, path::assertDrained);
        } finally { path.close(); }
        if (worker != null) assertFalse(worker.isAlive());
    }

    @Test void rejectsMalformedAndOutOfOrderInputsAndInvalidModes() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new PathHarness("unknown", "caller", PathHarness.Fault.NONE));
        assertThrows(IllegalArgumentException.class, () -> new PathHarness("ominicat", "unknown", PathHarness.Fault.NONE));
        for (PathHarness.Value input : new PathHarness.Value[]{new PathHarness.Value(2, 2), new PathHarness.Value(1, 9)}) {
            try (var path = new PathHarness("ominicat", "caller", PathHarness.Fault.NONE)) {
                assertThrows(IllegalStateException.class, () -> path.invoke(input));
                assertEquals(0, path.completed());
            }
        }
    }

    @Test void oracleRejectsForeignStageAndTerminalThreadsAndWrongStageOrder() throws Exception {
        var oracle = new PathHarness.Oracle(Thread.currentThread());
        var input = new PathHarness.Value(1, 1);
        oracle.begin(input);
        assertRejectedOnForeignThread(() -> oracle.stage(0, input));
        assertThrows(IllegalStateException.class, () -> oracle.stage(1, input));
        var value = input;
        for (int stage = 0; stage < 4; stage++) {
            oracle.stage(stage, value);
            value = value.transform(stage);
        }
        var output = value;
        assertRejectedOnForeignThread(() -> oracle.emit(output));
        oracle.emit(output);
        oracle.returned(output);
        oracle.end();
    }

    private static void assertRejectedOnForeignThread(Runnable action) throws InterruptedException {
        var failure = new AtomicReference<Throwable>();
        Thread foreign = new Thread(() -> {
            try { action.run(); }
            catch (Throwable error) { failure.set(error); }
        }, "mechanism-wrong-thread-fixture");
        foreign.start(); foreign.join(3000);
        assertFalse(foreign.isAlive());
        assertInstanceOf(IllegalStateException.class, failure.get());
    }
}
