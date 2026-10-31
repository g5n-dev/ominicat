package io.ominicat.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ominicat.core.AsyncEmitter;
import io.ominicat.core.AsyncProcessor;
import io.ominicat.core.EnvelopeProcessor;
import io.ominicat.core.Frame;
import io.ominicat.core.FrameEnvelope;
import io.ominicat.core.Pipeline;
import io.ominicat.core.Processor;
import io.ominicat.core.SessionOptions;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class ActivitySessionTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void recursiveDownstreamFailureIsNotAttributedToAnEarlierProcessor() throws Exception {
        var journal = new ActivityJournal(); var activity = journal.openSession();
        Processor first = (frame, next) -> next.accept(frame);
        Processor second = (frame, next) -> { throw new IllegalStateException("private-provider-message"); };
        var pipeline = Pipeline.builder().add(activity.wrap(EnvelopeProcessor.wrap(first), 0, "first"))
                .add(activity.wrap(EnvelopeProcessor.wrap(second), 1, "second")).build();
        try (var session = pipeline.openSession(SessionOptions.defaults(), frame -> { })) {
            assertTrue(session.trySubmit(input())); session.finish();
            assertThrows(ExecutionException.class, () -> session.completion().get(3, TimeUnit.SECONDS));
        }
        var events = events(journal);
        assertTrue(has(events, 0, "downstream_error"));
        assertFalse(has(events, 0, "invocation_error"));
        assertTrue(has(events, 1, "invocation_error"));
        assertFalse(journal.json(0).contains("private-provider-message"));
    }

    @Test void asynchronousEmitterPreservesRegistrationAndRetiresAfterNormalCompletion() throws Exception {
        var journal = new ActivityJournal(); var activity = journal.openSession();
        var emitter = new AtomicReference<AsyncEmitter>(); var started = new CountDownLatch(1); var cancellations = new AtomicInteger();
        AsyncProcessor provider = (frame, output) -> {
            output.onCancel(cancellations::incrementAndGet);
            assertThrows(IllegalStateException.class, () -> output.onCancel(() -> { }));
            emitter.set(output); started.countDown();
        };
        Processor observed = activity.wrap(EnvelopeProcessor.wrap(provider), 0, "stream");
        assertInstanceOf(AsyncProcessor.class, observed);
        var received = new ArrayBlockingQueue<Frame>(2);
        try (var session = Pipeline.builder().add(observed).build().openSession(new SessionOptions(2), received::add)) {
            assertTrue(session.trySubmit(input())); assertTrue(started.await(3, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, () -> emitter.get().onCancel(() -> { }));
            assertTrue(emitter.get().tryEmit(new Frame.Text("streamed")));
            var output = assertInstanceOf(FrameEnvelope.class, received.poll(3, TimeUnit.SECONDS));
            assertEquals("correlation", output.correlationId());
            assertEquals(new Frame.Text("streamed"), output.frame());
            emitter.get().complete(); session.finish(); session.completion().get(3, TimeUnit.SECONDS);
            assertFalse(emitter.get().isCancelled());
            long count = JSON.readTree(journal.json(0)).path("latestSeq").asLong();
            assertFalse(emitter.get().tryEmit(new Frame.Text("late")));
            emitter.get().complete(); emitter.get().fail(new IllegalStateException("late-private-error"));
            assertEquals(count, JSON.readTree(journal.json(0)).path("latestSeq").asLong());
        }
        assertEquals(0, cancellations.get());
        assertTrue(has(events(journal), 0, "async_return"));
        assertTrue(has(events(journal), 0, "async_complete"));
        assertTrue(StreamSupport.stream(events(journal).spliterator(), false)
                .anyMatch(event -> event.path("phase").asText().equals("output") && event.path("outcome").asText().equals("accepted")));
    }

    @Test void realCancellationDoesNotTurnALateCompleteIntoSuccess() throws Exception {
        var journal = new ActivityJournal(); var activity = journal.openSession();
        var captured = new AtomicReference<AsyncEmitter>(); var started = new CountDownLatch(1); var reset = new CountDownLatch(1);
        var cancellations = new AtomicInteger();
        AsyncProcessor provider = new AsyncProcessor() {
            @Override public void processAsync(Frame frame, AsyncEmitter emitter) {
                emitter.onCancel(cancellations::incrementAndGet); captured.set(emitter); started.countDown();
            }
            @Override public void onInterrupt() { reset.countDown(); }
        };
        try (var session = Pipeline.builder().add(activity.wrap(EnvelopeProcessor.wrap(provider), 0, "stream")).build()
                .openSession(new SessionOptions(2), frame -> fail("cancelled operation emitted output"))) {
            assertTrue(session.trySubmit(input())); assertTrue(started.await(3, TimeUnit.SECONDS));
            session.interrupt(); assertTrue(reset.await(3, TimeUnit.SECONDS));
            captured.get().complete();
            assertTrue(captured.get().isCancelled());
            assertFalse(captured.get().tryEmit(new Frame.Text("late")));
            session.finish(); session.completion().get(3, TimeUnit.SECONDS);
        }
        assertEquals(1, cancellations.get());
        assertTrue(has(events(journal), 0, "cancel"));
        assertFalse(has(events(journal), 0, "async_complete"));
    }

    @Test void zeroOutputCompletionIsNotAnOutputOrTaskCommit() throws Exception {
        var journal = new ActivityJournal(); var activity = journal.openSession();
        AsyncProcessor provider = (frame, output) -> { output.complete(); output.onCancel(() -> fail("normal completion cancelled")); };
        try (var session = Pipeline.builder().add(activity.wrap(provider, 0, "empty-stream")).build()
                .openSession(new SessionOptions(2), frame -> fail("must not manufacture output"))) {
            assertTrue(session.trySubmit(input())); session.finish(); session.completion().get(3, TimeUnit.SECONDS);
        }
        assertTrue(has(events(journal), 0, "async_complete"));
        assertFalse(has(events(journal), 0, "output"));
        assertFalse(journal.json(0).contains("task_complete"));
    }

    @Test void saturatedAsyncContinuationIsRejectedRatherThanReportedAsAcceptedDelivery() throws Exception {
        var journal = new ActivityJournal(); var activity = journal.openSession();
        var captured = new AtomicReference<AsyncEmitter>(); var started = new CountDownLatch(1);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        AsyncProcessor provider = (frame, output) -> { output.onCancel(() -> { }); captured.set(output); started.countDown(); };
        Processor blocked = (frame, next) -> { entered.countDown(); release.await(); next.accept(frame); };
        try (var session = Pipeline.builder().add(activity.wrap(provider, 0, "stream")).add(blocked).build()
                .openSession(new SessionOptions(1), frame -> { })) {
            assertTrue(session.trySubmit(input())); assertTrue(started.await(3, TimeUnit.SECONDS));
            assertTrue(captured.get().tryEmit(new Frame.Text("active")));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertTrue(captured.get().tryEmit(new Frame.Text("queued")));
            assertFalse(captured.get().tryEmit(new Frame.Text("rejected")));
            assertTrue(StreamSupport.stream(events(journal).spliterator(), false).anyMatch(event ->
                    event.path("phase").asText().equals("output_rejected") && event.path("outcome").asText().equals("rejected")));
            session.interrupt(); session.finish(); session.completion().get(3, TimeUnit.SECONDS);
        } finally { release.countDown(); }
    }

    @Test void asynchronousFailureIsAReportedOperationErrorWithoutExceptionContent() throws Exception {
        var journal = new ActivityJournal(); var activity = journal.openSession();
        var captured = new AtomicReference<AsyncEmitter>(); var started = new CountDownLatch(1); var closed = new AtomicInteger();
        AsyncProcessor provider = new AsyncProcessor() {
            @Override public void processAsync(Frame frame, AsyncEmitter output) {
                output.onCancel(() -> { }); captured.set(output); started.countDown();
            }
            @Override public void close() { closed.incrementAndGet(); }
        };
        try (var session = Pipeline.builder().add(activity.wrap(EnvelopeProcessor.wrap(provider), 0, "stream")).build()
                .openSession(new SessionOptions(2), frame -> { })) {
            assertTrue(session.trySubmit(input())); assertTrue(started.await(3, TimeUnit.SECONDS));
            captured.get().fail(new IllegalStateException("private-asynchronous-error"));
            assertThrows(ExecutionException.class, () -> session.completion().get(3, TimeUnit.SECONDS));
            assertFalse(captured.get().tryEmit(new Frame.Text("late")));
        }
        assertEquals(1, closed.get());
        assertTrue(has(events(journal), 0, "async_fail"));
        assertFalse(has(events(journal), 0, "async_complete"));
        assertFalse(journal.json(0).contains("private-asynchronous-error"));
    }

    @Test void normalEmitterCompletionKeepsItsTerminalStateWhenTheProviderSubsequentlyThrows() throws Exception {
        var journal = new ActivityJournal(); var activity = journal.openSession();
        var captured = new AtomicReference<AsyncEmitter>();
        AsyncProcessor provider = (frame, output) -> {
            captured.set(output); output.complete();
            throw new IllegalStateException("private-error-after-completion");
        };
        try (var session = Pipeline.builder().add(activity.wrap(provider, 0, "stream")).build()
                .openSession(new SessionOptions(2), frame -> { })) {
            assertTrue(session.trySubmit(new Frame.Text("input"))); session.finish();
            assertThrows(ExecutionException.class, () -> session.completion().get(3, TimeUnit.SECONDS));
            assertFalse(captured.get().isCancelled(), "Normal handle completion wins permanently, independently of session failure");
            assertFalse(captured.get().tryEmit(new Frame.Text("late")));
        }
        assertTrue(has(events(journal), 0, "async_complete"));
        assertTrue(has(events(journal), 0, "invocation_error"));
        assertFalse(journal.json(0).contains("private-error-after-completion"));
    }

    private static Frame input() { return new FrameEnvelope(new Frame.Text("input"), "event", "caller", "turn", "correlation", "", 0, 0); }
    private static JsonNode events(ActivityJournal journal) throws Exception { return JSON.readTree(journal.json(0)).path("events"); }
    private static boolean has(JsonNode events, int stage, String phase) {
        return StreamSupport.stream(events.spliterator(), false).anyMatch(event -> event.path("stageIndex").asInt() == stage
                && event.path("phase").asText().equals(phase));
    }
}
