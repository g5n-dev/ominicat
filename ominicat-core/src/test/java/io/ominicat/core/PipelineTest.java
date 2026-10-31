package io.ominicat.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class PipelineTest {
    @Test void composesFilteringTransformationAndFanoutInOrder() throws Exception {
        Pipeline pipeline = Pipeline.builder()
                .add((frame, next) -> { if (frame instanceof Frame.Text t) next.accept(new Frame.Text(t.text().toUpperCase())); })
                .add((frame, next) -> { next.accept(frame); next.accept(new Frame.Text("tail")); })
                .build();
        List<Frame> output = new ArrayList<>();
        pipeline.process(new Frame.Text("hello"), output::add);
        pipeline.process(new Frame.Control(Frame.Signal.START), output::add);
        assertEquals(List.of(new Frame.Text("HELLO"), new Frame.Text("tail")), output);
    }

    @Test void emptyPipelinePreservesIdentityAndPropagatesSinkFailure() throws Exception {
        Frame text = new Frame.Text("hello");
        Pipeline pipeline = Pipeline.builder().build();
        List<Frame> output = new ArrayList<>();
        pipeline.process(text, output::add);
        assertSame(text, output.getFirst());
        Exception failure = new Exception("sink offline");
        assertSame(failure, assertThrows(Exception.class, () -> pipeline.process(text, frame -> { throw failure; })));
    }

    @Test void builderTakesSnapshotAndRejectsNullStagesAndEmissions() throws Exception {
        Pipeline.Builder builder = Pipeline.builder();
        Pipeline empty = builder.build();
        builder.add((frame, next) -> next.accept(new Frame.Text("changed")));
        List<Frame> output = new ArrayList<>();
        empty.process(new Frame.Text("original"), output::add);
        assertEquals(new Frame.Text("original"), output.getFirst());
        assertThrows(NullPointerException.class, () -> builder.add(null));
        Pipeline broken = Pipeline.builder().add((frame, next) -> next.accept(null)).build();
        assertThrows(NullPointerException.class, () -> broken.process(new Frame.Text("x"), output::add));
    }

    @Test void sessionExclusivelyClaimsProcessorLifetime() throws Exception {
        Pipeline pipeline = Pipeline.builder().build();
        PipelineSession session = pipeline.openSession(SessionOptions.defaults(), frame -> { });
        assertThrows(IllegalStateException.class, () -> pipeline.openSession(SessionOptions.defaults(), frame -> { }));
        assertThrows(IllegalStateException.class, () -> pipeline.process(new Frame.Text("x"), frame -> { }));
        session.finish();
        session.completion().get(2, TimeUnit.SECONDS);
    }

    @Test void optionsValidateResourceBounds() {
        assertEquals(256, SessionOptions.defaults().queueCapacity());
        assertThrows(IllegalArgumentException.class, () -> new SessionOptions(0));
        assertThrows(IllegalArgumentException.class, () -> new SessionOptions(1000001));
    }
}
